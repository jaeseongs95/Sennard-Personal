// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The key files of the attachments: one file per key in {@code sennard.attachments.key-directory}, named by its key id, holding the 256 random bits of an AES-256 key and nothing else
 * (the design's 4.3). New uploads are written with the active key ({@link AttachmentProperties#activeKey()}); a file is read with the key its row names, so a key is replaced by
 * making a new key file and naming it active, and an old key stays while any current row points at it. A key is never written to a log, a row or a message.
 */
@Component
public class AttachmentKeys {
    /** A key id is a file name: letters, digits, underscore and hyphen, so it cannot name a path. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int KEY_BYTES = 32;

    private final AttachmentProperties properties;
    private final StorageFolders folders;
    private final PrivateStorage storage;
    private final SecureRandom random;

    public AttachmentKeys(AttachmentProperties properties, StorageFolders folders, PrivateStorage storage, SecureRandom random) {
        this.properties = properties;
        this.folders = folders;
        this.storage = storage;
        this.random = random;
    }

    /** Whether the text can be the id of a key. */
    public static boolean validId(String keyId) {
        return keyId != null && KEY_ID.matcher(keyId).matches();
    }

    /** The key new uploads are written with. */
    public String activeKeyId() {
        return properties.activeKey();
    }

    private Path folder() {
        Path folder = properties.keyDirectory();
        if (folder == null) {
            throw ArtifactErrors.storageUnsafe("key-directory-not-set");
        }
        return folder;
    }

    private Path fileOf(String keyId) {
        if (!validId(keyId)) {
            throw ArtifactErrors.keyMissing("invalid");
        }
        return folder().resolve(keyId);
    }

    /** The key of the id; the caller wipes it after use when it can. ARTIFACT_KEY_MISSING when there is no such file. */
    public byte[] key(String keyId) {
        Path file = fileOf(keyId);
        // The read of a secret file is the port's (AGS3-D-0268). Only the kind of the path is judged, never who owns it or who may read it: a path that is not a regular file is
        // ARTIFACT_STORAGE_UNSAFE (the port's PRIVATE_STORAGE_NOT_PLAIN); a key file that is empty or not 256 bits is no usable key (design edition 11): ARTIFACT_KEY_MISSING.
        try {
            SecretValue secret = read(keyId, file);
            try {
                if (secret.length() != KEY_BYTES) {
                    throw ArtifactErrors.keyUnusable(keyId, "wrong-length");
                }
                return secret.revealBytes();
            } finally {
                secret.destroy(); // the value read from the file is an intermediate; the caller wipes the copy it is given
            }
        } catch (NoSuchFileException missing) {
            throw ArtifactErrors.keyMissing(keyId);
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /**
     * The port's read of the key file, its refusals in the attachments' codes: a path that is not a regular file is ARTIFACT_STORAGE_UNSAFE (key-not-a-file), and so is a file system that cannot
     * make an owner-only path; an empty file, which the port refuses with PRIVATE_STORAGE_EMPTY, is ARTIFACT_KEY_MISSING (reason empty). The decision is the port's code and nothing is
     * looked at again here, so an access refusal is never taken for a missing key. A file that is not there ({@link NoSuchFileException}) is ARTIFACT_KEY_MISSING at the caller and any
     * other {@link IOException} is ARTIFACT_STORAGE_IO_ERROR there.
     */
    private SecretValue read(String keyId, Path file) throws IOException {
        try {
            return storage.readSecret(file);
        } catch (UnavailableException refusal) {
            if (refusal.code() == UnavailableCode.PRIVATE_STORAGE_EMPTY) {
                throw ArtifactErrors.keyUnusable(keyId, "empty");
            }
            throw ArtifactErrors.privateStorage(refusal, "key-not-a-file");
        }
    }

    /**
     * The start check of one key (the design's 4.3, edition 11): the key file is really read and judged the way a request judges it ({@link #key}), so a start that would only fail at the
     * first request fails here. A path that is not a regular file (a folder in the key's place) is ARTIFACT_STORAGE_UNSAFE; no file, an empty file and a file that is not 256 bits are
     * ARTIFACT_KEY_MISSING. The key that was read is wiped at once and is not kept.
     */
    public void requireUsable(String keyId) {
        byte[] key = key(keyId);
        Arrays.fill(key, (byte) 0);
    }

    /** The ids of the key files that are there, in name order. */
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (validId(name) && Files.isRegularFile(file)) {
                    ids.add(name);
                }
            }
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    /**
     * The first start: the folder is made (or checked) and, when it holds no key at all, the first key is made. The active key must be usable afterwards ({@link #requireUsable}: read
     * and judged like a request does), so a setting that names a key nobody made, or whose file is a folder, empty or not 256 bits, stops the start instead of the first upload.
     */
    public void provision() {
        folders.ensureOwnerOnlyFolder(folder());
        // A path in the first key's place that is no regular file (a folder) is not "no key at all": it is judged below, as ARTIFACT_STORAGE_UNSAFE, and nothing is made over it.
        if (ids().isEmpty() && !Files.exists(fileOf(AttachmentProperties.FIRST_KEY_ID))) {
            create(AttachmentProperties.FIRST_KEY_ID);
        }
        requireUsable(activeKeyId());
    }

    /**
     * Makes the key file of this id through the port, 256 random bits, private to its owner from its first instant. An id that is no file name, and a key that exists (a key is never
     * replaced), are misuses of the command {@code keygen}: an input error ({@link ArtifactErrors#invalidArgument}), not ARTIFACT_STORAGE_UNSAFE, which only says that a storage path
     * cannot be used.
     */
    public void create(String keyId) {
        if (!validId(keyId)) {
            throw ArtifactErrors.invalidArgument("--id", "a key id is letters, digits, underscore and hyphen, 1 to 64 characters");
        }
        Path file = fileOf(keyId);
        byte[] fresh = new byte[KEY_BYTES];
        random.nextBytes(fresh);
        try {
            folders.createOwnerOnlyFile(file);
            Files.write(file, fresh, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException present) {
            throw ArtifactErrors.invalidArgument("--id", "the key folder already has a path of this id; a key is never replaced");
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(fresh, (byte) 0);
        }
    }
}
