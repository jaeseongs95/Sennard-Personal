// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/** The password file of the business database, read through the port of the storage of secrets. */
class CredentialsTest {
    @TempDir
    Path folder;

    @Test
    void theFirstLineTrimmedIsThePassword() throws Exception {
        Path file = Files.writeString(folder.resolve("password"), "  secret-value \nsecond line\n", StandardCharsets.UTF_8);
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), Credentials.read(file, new TestPrivateStorage()).revealBytes());
    }

    /** The value read from the file is an intermediate and is overwritten once the characters are taken from it; the password the caller gets is a separate value. */
    @Test
    void theValueReadFromThePasswordFileIsDestroyed() throws Exception {
        Path file = Files.writeString(folder.resolve("password"), "secret-value\n", StandardCharsets.UTF_8);
        List<SecretValue> read = new ArrayList<>();
        TestPrivateStorage inner = new TestPrivateStorage();
        PrivateStorage recording = new PrivateStorage() {
            @Override
            public void createFolder(Path path) throws IOException {
                inner.createFolder(path);
            }

            @Override
            public void createFile(Path path) throws IOException {
                inner.createFile(path);
            }

            @Override
            public void requireSafeFolder(Path path) throws IOException {
                inner.requireSafeFolder(path);
            }

            @Override
            public void requireTrustedFolder(Path path) throws IOException {
                inner.requireTrustedFolder(path);
            }

            @Override
            public SecretValue readSecret(Path path) throws IOException {
                SecretValue value = inner.readSecret(path);
                read.add(value);
                return value;
            }
        };

        SecretValue password = Credentials.read(file, recording);

        assertEquals(1, read.size());
        assertTrue(read.get(0).isDestroyed(), "the value read from the file is destroyed");
        assertFalse(password.isDestroyed());
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), password.revealBytes());
    }

    @Test
    void anEmptyPasswordFileAndABlankFirstLineAreAnUnreadableCredential() throws Exception {
        Path empty = Files.createFile(folder.resolve("empty"));
        StoreException emptyFailure = assertThrows(StoreException.class, () -> Credentials.read(empty, new TestPrivateStorage()));
        assertEquals(StoreCode.DB_CREDENTIAL_UNREADABLE, emptyFailure.code(), "the port's PRIVATE_STORAGE_EMPTY is the credential that cannot be read");
        assertEquals(UnavailableCode.PRIVATE_STORAGE_EMPTY, ((UnavailableException) emptyFailure.getCause()).code());

        Path blank = Files.writeString(folder.resolve("blank"), "   \nnext\n", StandardCharsets.UTF_8);
        assertEquals(StoreCode.DB_CREDENTIAL_UNREADABLE, assertThrows(StoreException.class, () -> Credentials.read(blank, new TestPrivateStorage())).code());
    }

    @Test
    void aPathThatIsNotAFileIsTheRefusalOfThePortAndAMissingFileIsUnreadable() {
        UnavailableException notPlain = assertThrows(UnavailableException.class, () -> Credentials.read(folder, new TestPrivateStorage()));
        assertEquals(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, notPlain.code());
        assertEquals(StoreCode.DB_CREDENTIAL_UNREADABLE, assertThrows(StoreException.class, () -> Credentials.read(folder.resolve("missing"), new TestPrivateStorage())).code());
    }
}
