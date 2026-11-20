// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;

/**
 * The personal edition's sealer: AES-256-GCM with a random 96-bit IV per value, the additional data binding a
 * value to its name and to the caller's data (scope, scope id, fingerprint), the 256-bit data key a file beside the shared state.
 * The same meaning as today's JEV key vault. It keeps a database dump or a copied state file on its own from revealing a value;
 * a program running as the same OS user can read the key file too and is not stopped by this.
 *
 * <p>Stored form: {@code v1.<iv>.<ciphertext and tag>}, base64url. */
public final class LocalFileSealer implements SecretSealer {
    /** The stored forms of a sealed value: the first part of its text says which one it is. */
    private enum Format {
        V1("v1");

        private final String tag;

        Format(String tag) {
            this.tag = tag;
        }

        /** Whether the text is this form's tag. */
        boolean isTaggedAs(String text) {
            return tag.equals(text);
        }
    }

    /** The form written now. */
    private static final Format FORMAT = Format.V1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSealer.class);
    private static final Pattern PARTS = Pattern.compile("\\.");

    /** The two codes a failure of this sealer carries; {@code SecretSealException} is the sealer API's type (in sennard-api), which takes its code as text. */
    private enum Refusal {
        SECRET_KEY_UNAVAILABLE, SECRET_UNREADABLE;

        SecretSealException failure() {
            return new SecretSealException(name());
        }

        SecretSealException failure(Throwable cause) {
            return new SecretSealException(name(), cause);
        }
    }

    private final Path keyFile;
    private final SecureRandom random;
    private final PrivateStorage storages;

    /**
     * @param random the process's {@link SecureRandom} (the bean), which makes every IV and the key
     * @param storages creates the key file owner-only and reads it: the port of the storage of secrets, which refuses a key file that is not a regular file or is empty
     */
    public LocalFileSealer(Path keyFile, SecureRandom random, PrivateStorage storages) {
        this.keyFile = keyFile;
        this.random = random;
        this.storages = storages;
    }

    @Override
    public SealedValue seal(SecretName name, byte[] plaintext, Map<String, String> aad) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] key = key(true);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(additionalData(name, aad));
            byte[] sealed = cipher.doFinal(plaintext);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new SealedValue(FORMAT.tag + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(sealed));
        } catch (GeneralSecurityException cause) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure(cause);
        } finally {
            SecretValue.clear(key);
        }
    }

    @Override
    public byte[] open(SealedValue sealed, SecretName name, Map<String, String> aad) {
        String[] parts = PARTS.split(sealed.text(), -1);
        if (parts.length != 3 || !FORMAT.isTaggedAs(parts[0])) {
            throw Refusal.SECRET_UNREADABLE.failure();
        }
        byte[] key = key(false);
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(additionalData(name, aad));
            return cipher.doFinal(decoder.decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException cause) {
            throw Refusal.SECRET_UNREADABLE.failure(cause);
        } finally {
            SecretValue.clear(key);
        }
    }

    /** name, then every entry of the data in key order, each separated by a NUL so one field cannot run into the next. */
    private static byte[] additionalData(SecretName name, Map<String, String> aad) {
        StringBuilder text = new StringBuilder("sennard.seal\u0000").append(FORMAT.tag).append('\u0000').append(name.value());
        for (Map.Entry<String, String> entry : new TreeMap<>(aad).entrySet()) {
            text.append('\u0000').append(entry.getKey()).append('\u0000').append(entry.getValue());
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The data key. Created on first seal: a temporary file private from its creation, then the bytes, then a hard link into place,
     * so a reader never sees a partial file and, when two processes race, exactly one key wins (the loser reads the winner's).
     */
    private byte[] key(boolean create) {
        byte[] existing = readKey();
        if (existing != null) {
            return existing;
        }
        if (!create) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure();
        }
        Path temporary = keyFile.resolveSibling(keyFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Path folder = keyFile.toAbsolutePath().getParent();
            storages.createFolder(folder);
            storages.requireSafeFolder(folder);
            byte[] fresh = new byte[KEY_BYTES];
            random.nextBytes(fresh);
            storages.createFile(temporary);
            try {
                Files.write(temporary, fresh, java.nio.file.StandardOpenOption.WRITE);
            } finally {
                SecretValue.clear(fresh);
            }
            try {
                Files.createLink(keyFile, temporary);
            } catch (FileAlreadyExistsException lost) {
                // Another process created it between our read and now; the link is the one atomic test of that, and the key below is read from the winner's file.
                LOG.debug("{} was created by another process first; its key is used.", keyFile, lost);
            }
        } catch (IOException cause) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure(cause);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException notRemoved) {
                // A leftover temporary file holds a key nobody uses; the next call does not read it.
                LOG.warn("The temporary key file {} could not be removed.", temporary, notRemoved);
            }
        }
        byte[] created = readKey();
        if (created == null) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure();
        }
        return created;
    }

    private byte[] readKey() {
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            // The key file is read only through the port of the storage of secrets (a file that is not a regular file is an UnavailableException, not caught here).
            SecretValue key = storages.readSecret(keyFile);
            try {
                if (key.length() != KEY_BYTES) {
                    throw Refusal.SECRET_KEY_UNAVAILABLE.failure();
                }
                return key.revealBytes();
            } finally {
                key.destroy(); // the value read from the file is an intermediate; the caller clears the copy it is given
            }
        } catch (IOException cause) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure(cause);
        } catch (UnavailableException refusal) {
            if (refusal.code() != UnavailableCode.PRIVATE_STORAGE_EMPTY) {
                throw refusal; // a path that is not a regular file is the port's refusal, named by its own code
            }
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure(refusal);
        }
    }
}
