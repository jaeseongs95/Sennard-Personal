// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * The folders and files the attachments live in (design 4.2, edition 10), on the real file system of the test run. The personal edition trusts the whole computer: a folder or a file that
 * is there is used as it is, whoever owns it and whatever its access; the class only makes new folders and files private to their owner from their first instant, and refuses a path that
 * is not what it must be (ARTIFACT_STORAGE_UNSAFE).
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 14  f1  made when missing    a folder that is not there is made, parents included; a second call changes nothing
 * 6   f2  subfolders, files    a subfolder of the attachment folder is made; a file is made from its first instant and a second creation fails (FileAlreadyExistsException)
 * 14  f3  a folder that is there is used as it is   a folder made with the default access (not private to its owner) is accepted by ensureOwnerOnlyFolder and ensureSubfolder, with nothing changed
 * 14  f4  not a folder         a file where a folder belongs is ARTIFACT_STORAGE_UNSAFE (reason not-a-folder), for the folder and for a subfolder
 * 14  f5  made by the port      a folder and a file are made by the PrivateStorage port (AGS3-D-0268), which gives them their access from the first instant; a second call on a folder that is there only checks it
 * 14  f6  refusals of the port  a refusal that is not about the kind of the path (PRIVATE_STORAGE_USER_UNKNOWN, PRIVATE_STORAGE_UNSUPPORTED) is ARTIFACT_STORAGE_UNSAFE (edition 11), reason account-unknown or filesystem-unsupported; the owner fixes the storage location
 * </pre>
 *
 * What f5 used to assert here, that what is made is readable by its owner alone (POSIX mode rwx------ and rw-------, or an access control list of the owner), is the port's and is
 * asserted where the port is implemented: sennard-personal's {@code LocalPrivateStorageTest} (the POSIX modes of a folder and a file, and the entries of the access lists, one test for each file
 * system family). The class makes no access of its own, so the test here uses {@link TestPrivateStorage}, which has none.
 */
class StorageFoldersTest {
    @TempDir
    Path scratch;

    private final TestPrivateStorage storage = new TestPrivateStorage();
    private final StorageFolders folders = new StorageFolders(storage);

    @Test
    void aFolderThatIsNotThereIsMadeWithItsParentsAndASecondCallChangesNothing() {
        Path folder = scratch.resolve("a").resolve("b").resolve("secrets");

        folders.ensureOwnerOnlyFolder(folder);
        assertTrue(Files.isDirectory(folder), "the folder and its parents are made");
        folders.ensureOwnerOnlyFolder(folder);
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void aSubfolderIsMadeAndAFileIsNeverMadeTwice() throws IOException {
        Path root = scratch.resolve("attachments");
        folders.ensureOwnerOnlyFolder(root);
        Path sub = root.resolve("ab");

        folders.ensureSubfolder(sub);
        assertTrue(Files.isDirectory(sub));
        folders.ensureSubfolder(sub);
        Path file = sub.resolve("0123456789abcdef0123456789abcdef");
        folders.createOwnerOnlyFile(file);
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, Files.size(file));
        assertThrows(FileAlreadyExistsException.class, () -> folders.createOwnerOnlyFile(file), "a file that exists is never taken over");
    }

    @Test
    void aFolderThatIsThereIsUsedAsItIsWhateverItsAccess() throws IOException {
        Path folder = Files.createDirectory(scratch.resolve("made-by-someone-else")); // the default access of the system, not private to the owner
        Path inside = Files.createDirectory(folder.resolve("cd"));
        assertTrue(Files.isDirectory(folder));

        assertDoesNotThrow(() -> folders.ensureOwnerOnlyFolder(folder));
        assertDoesNotThrow(() -> folders.ensureSubfolder(inside));
        assertTrue(Files.isDirectory(folder));
        assertTrue(Files.isDirectory(inside));
    }

    @Test
    void aFileWhereAFolderBelongsIsRefusedAsNotAFolder() throws IOException {
        Path root = scratch.resolve("attachments");
        folders.ensureOwnerOnlyFolder(root);
        Path notAFolder = root.resolve("cd");
        folders.createOwnerOnlyFile(notAFolder);

        UnavailableException subfolder = assertThrows(UnavailableException.class, () -> folders.ensureSubfolder(notAFolder));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, subfolder.code());
        assertEquals("not-a-folder", subfolder.details().get("reason"));
        assertEquals(Boolean.FALSE, subfolder.details().get("retryable"), "the owner has to fix the folder");
        UnavailableException folder = assertThrows(UnavailableException.class, () -> folders.ensureOwnerOnlyFolder(notAFolder));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, folder.code());
        assertEquals("not-a-folder", folder.details().get("reason"));
        assertFalse(Files.isDirectory(notAFolder), "the file was not replaced");
    }

    @Test
    void aFolderAndAFileAreMadeByThePortAndNotByTheClass() throws IOException {
        Path folder = scratch.resolve("private");
        Path sub = folder.resolve("ab");
        Path file = sub.resolve("0123456789abcdef0123456789abcdef");

        folders.ensureOwnerOnlyFolder(folder);
        folders.ensureSubfolder(sub);
        folders.createOwnerOnlyFile(file);

        assertEquals(List.of("createFolder " + folder, "requireSafeFolder " + folder, "createFolder " + sub, "requireSafeFolder " + sub, "createFile " + file), storage.calls(),
                "every folder and file is made by the port, which gives it its access; the access itself is asserted by LocalPrivateStorageTest (rwx------ and rw------- on a file system with permission bits, an access list of the owner on NTFS)");
    }

    @Test
    void aFolderThatIsThereIsNotMadeAgain() {
        Path folder = scratch.resolve("there");
        folders.ensureOwnerOnlyFolder(folder);

        folders.ensureOwnerOnlyFolder(folder);

        assertEquals(List.of("createFolder " + folder, "requireSafeFolder " + folder, "requireSafeFolder " + folder), storage.calls(), "the second call only checks the kind of the path");
    }

    @Test
    void aPortThatCannotMakeAnOwnerOnlyPathIsAnUnsafeStoragePath() {
        PrivateStorage unknownAccount = new PrivateStorage() {
            @Override
            public void createFolder(Path folder) {
                throw new UnavailableException(UnavailableCode.PRIVATE_STORAGE_USER_UNKNOWN, SennardException.args("path", folder.toString()));
            }

            @Override
            public void createFile(Path file) {
                throw new UnavailableException(UnavailableCode.PRIVATE_STORAGE_UNSUPPORTED, SennardException.args("path", file.toString()));
            }

            @Override
            public void requireSafeFolder(Path folder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void requireTrustedFolder(Path folder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SecretValue readSecret(Path file) {
                throw new UnsupportedOperationException();
            }
        };
        StorageFolders refusing = new StorageFolders(unknownAccount);

        UnavailableException folder = assertThrows(UnavailableException.class, () -> refusing.ensureOwnerOnlyFolder(scratch.resolve("x")));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, folder.code(), "an account the system does not know: the owner fixes the storage location (edition 11)");
        assertEquals("account-unknown", folder.details().get("reason"));
        UnavailableException file = assertThrows(UnavailableException.class, () -> refusing.createOwnerOnlyFile(scratch.resolve("y")));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, file.code(), "a file system that cannot make an owner-only file");
        assertEquals("filesystem-unsupported", file.details().get("reason"));
    }
}
