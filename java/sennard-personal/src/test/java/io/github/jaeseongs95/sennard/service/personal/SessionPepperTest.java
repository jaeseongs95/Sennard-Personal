// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.protocol.Json;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.personal.local.SessionPepper;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/** The session pepper this PC keeps in its state folder: made once, sealed, owner-only, and the digests it gives. */
class SessionPepperTest {
    @TempDir
    Path temporary;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** The storage as the product builds it: the user of the process is read from its token where an access list is made. */
    private static final PrivateStorage STORAGES = new LocalPrivateStorage();

    private static LocalFileSealer sealer(Path directory) {
        return new LocalFileSealer(directory.resolve("session-pepper.key"), RANDOM, STORAGES);
    }

    /** The pepper store as the product builds it: the JSON mapper and the random source it is given. */
    private static SessionPepper pepper() {
        return new SessionPepper(Json.MAPPER, RANDOM, STORAGES);
    }

    /** A file that holds {@code content} and is private to its owner, made the way the product makes one (through the port, so it has the access the product gives from its first instant; a file of another access is read as it is). */
    private static void writePrivately(Path file, byte[] content) throws Exception {
        STORAGES.createFile(file);
        Files.write(file, content);
    }

    @Test
    void thePepperIsMadeOnceSealedAndPrivate() throws Exception {
        Path file = temporary.resolve("session-pepper.json");
        List<SessionKeys.Pepper> made = pepper().loadOrCreate(file, sealer(temporary));
        assertEquals(1, made.size());
        assertEquals(1, made.get(0).version());
        assertEquals(32, made.get(0).key().length());
        List<SessionKeys.Pepper> read = pepper().loadOrCreate(file, sealer(temporary));
        assertArrayEquals(made.get(0).key().revealBytes(), read.get(0).key().revealBytes());
        String text = Files.readString(file);
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(made.get(0).key().revealBytes())), "the value is sealed");
        assertFalse(made.get(0).toString().contains(java.util.Base64.getEncoder().encodeToString(made.get(0).key().revealBytes())), "the text of the pepper has no key");
        assertEquals("Pepper[version=1, key=SecretValue[redacted]]", made.get(0).toString());
        assertEquals(1, Json.MAPPER.readTree(text).get(0).get("version").intValue());
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    /**
     * The pepper file must be owner-only when its bytes arrive, on every platform. The fixture records the real file at the
     * moment of the write, so a temporary file created with default access and narrowed afterwards (or never, on Windows) fails here.
     */
    @Test
    void thePepperFileIsOwnerOnlyBeforeAnyOfItsBytesAreWritten() throws Exception {
        WriteObservingFileSystem observing = new WriteObservingFileSystem(java.nio.file.FileSystems.getDefault());
        Path file = observing.wrap(temporary).resolve("service").resolve("session-pepper.json");
        pepper().loadOrCreate(file, sealer(temporary));

        List<WriteObservingFileSystem.Observation> writes = observing.observations();
        assertEquals(1, writes.size(), "one write of the pepper file: " + writes);
        WriteObservingFileSystem.Observation write = writes.get(0);
        assertEquals(0, write.sizeBefore(), "nothing was in the file before the owner-only state: " + write);
        assertTrue(write.ownerOnly(), "the pepper file allows others when its bytes are written: " + write.access());
        assertTrue(WriteObservingFileSystem.ownerOnly(temporary.resolve("service").resolve("session-pepper.json")),
                WriteObservingFileSystem.describeAccess(temporary.resolve("service").resolve("session-pepper.json")));
    }

    @Test
    void aPepperFileThatCannotBeReadIsAFailureAndIsNotReplaced() throws Exception {
        Path file = temporary.resolve("session-pepper.json");
        writePrivately(file, "[{\"version\":1,\"sealed\":\"v1.broken.value\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String before = Files.readString(file);
        assertEquals(StoreCode.SESSION_PEPPER_UNAVAILABLE, assertThrows(StoreException.class, () -> pepper().loadOrCreate(file, sealer(temporary))).code());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void anEmptyPepperFileIsAFailureAndIsNotReplaced() throws Exception {
        Path file = temporary.resolve("session-pepper.json");
        STORAGES.createFile(file);
        StoreException failure = assertThrows(StoreException.class, () -> pepper().loadOrCreate(file, sealer(temporary)));
        assertEquals(StoreCode.SESSION_PEPPER_UNAVAILABLE, failure.code());
        assertEquals(0, Files.size(file), "an empty pepper file is not overwritten with a new pepper");
    }

    @Test
    void anotherDataKeyCannotOpenThePepper() throws Exception {
        Path file = temporary.resolve("session-pepper.json");
        pepper().loadOrCreate(file, sealer(temporary));
        Path other = temporary.resolve("other");
        STORAGES.createFolder(other);
        writePrivately(other.resolve("session-pepper.key"), new byte[32]);
        assertEquals(StoreCode.SESSION_PEPPER_UNAVAILABLE, assertThrows(StoreException.class, () -> pepper().loadOrCreate(file, sealer(other))).code());
    }

    /** The personal edition trusts the PC: a pepper file or key file that others may read, or that is a link, is read as it is. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aPepperFileOrKeyFileThatOthersMayReadOrThatIsALinkIsStillRead() throws Exception {
        Path file = temporary.resolve("session-pepper.json");
        List<SessionKeys.Pepper> made = pepper().loadOrCreate(file, sealer(temporary));
        Files.setPosixFilePermissions(temporary.resolve("session-pepper.key"), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
        assertEquals(made.get(0).key(), pepper().loadOrCreate(file, sealer(temporary)).get(0).key());
        Path link = temporary.resolve("link.json");
        Files.createSymbolicLink(link, file);
        assertEquals(made.get(0).key(), pepper().loadOrCreate(link, sealer(temporary)).get(0).key());
    }

    @Test
    void differentPeppersGiveDifferentDigestsAndAnOlderOneStillFindsItsRow() {
        SessionKeys old = new SessionKeys(List.of(new SessionKeys.Pepper(1, SecretValue.ofBytes(new byte[] {1}))), RANDOM);
        SessionKeys rotated = new SessionKeys(List.of(new SessionKeys.Pepper(1, SecretValue.ofBytes(new byte[] {1})), new SessionKeys.Pepper(2, SecretValue.ofBytes(new byte[] {2}))), RANDOM);
        assertEquals(2, rotated.currentDigest("h", "s").version());
        assertNotEquals(rotated.currentDigest("h", "s").value(), old.currentDigest("h", "s").value());
        assertEquals(old.currentDigest("h", "s").value(), rotated.digests("h", "s").get(1).value());
    }
}
