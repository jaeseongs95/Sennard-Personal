// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session pepper of this PC: {@code <state>/service/session-pepper.json}, a list of
 * {@code {version, sealed}}, owner-only from its creation, written to a temporary file and renamed, the values sealed with the local data key. The first
 * start makes it. A file that exists and cannot be read is a failure, never a new pepper: a new one would quietly orphan every
 * session row made under the old one. Only the service holding the owner lock calls this.
 */
public final class SessionPepper {
    private static final SecretName NAME = new SecretName("session-pepper");
    private static final int KEY_BYTES = 32;

    /** One pepper of the file: its version and the key sealed with the local data key. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(int version, String sealed) {
    }

    private final JsonMapper json;
    private final SecureRandom random;
    private final PrivateStorage storages;

    /** @param storages creates the pepper file owner-only and reads it: the port of the storage of secrets */
    public SessionPepper(JsonMapper json, SecureRandom random, PrivateStorage storages) {
        this.json = json;
        this.random = random;
        this.storages = storages;
    }

    /**
     * Every valid pepper of the file, creating version 1 when there is no file; SESSION_PEPPER_UNAVAILABLE when it cannot be used. The file is read through the port of the storage of
     * secrets, which refuses what is not a regular file (an {@code UnavailableException}).
     */
    public List<SessionKeys.Pepper> loadOrCreate(Path file, SecretSealer sealer) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return List.of(create(file, sealer));
            }
            SecretValue read = storages.readSecret(file);
            byte[] stored;
            try {
                stored = read.revealBytes();
            } finally {
                read.destroy(); // the value read from the file is an intermediate
            }
            Entry[] entries;
            try {
                entries = json.readValue(stored, Entry[].class);
            } finally {
                SecretValue.clear(stored);
            }
            if (entries == null || entries.length == 0) {
                throw new IllegalArgumentException("The pepper file holds no list.");
            }
            List<SessionKeys.Pepper> peppers = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry == null || entry.sealed() == null) {
                    throw new IllegalArgumentException("A pepper of the file has no sealed value.");
                }
                byte[] opened = sealer.open(new SealedValue(entry.sealed()), NAME, aad(entry.version()));
                try {
                    peppers.add(new SessionKeys.Pepper(entry.version(), SecretValue.ofBytes(opened)));
                } finally {
                    SecretValue.clear(opened);
                }
            }
            return List.copyOf(peppers);
        } catch (IOException | JacksonException | IllegalArgumentException | SecretSealException cause) {
            throw new StoreException(StoreCode.SESSION_PEPPER_UNAVAILABLE, "The session pepper cannot be read or created.", cause);
        } catch (UnavailableException refusal) {
            if (refusal.code() != UnavailableCode.PRIVATE_STORAGE_EMPTY) {
                throw refusal; // a path that is not a regular file is the port's refusal, named by its own code
            }
            throw new StoreException(StoreCode.SESSION_PEPPER_UNAVAILABLE, "The session pepper file is empty.", refusal);
        }
    }

    private SessionKeys.Pepper create(Path file, SecretSealer sealer) throws IOException {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        String list;
        SecretValue pepper;
        try {
            list = json.writeValueAsString(List.of(new Entry(1, sealer.seal(NAME, key, aad(1)).text())));
            pepper = SecretValue.ofBytes(key);
        } finally {
            SecretValue.clear(key);
        }
        Path folder = file.toAbsolutePath().getParent();
        storages.createFolder(folder);
        storages.requireSafeFolder(folder);
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            storages.createFile(temporary);
            Files.writeString(temporary, list, StandardOpenOption.WRITE);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new SessionKeys.Pepper(1, pepper);
    }

    private static Map<String, String> aad(int version) {
        return Map.of("scope", "service", "version", Integer.toString(version));
    }
}
