// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.ProcessUser;

/**
 * The personal edition's storage of secrets (D-0268). The edition trusts the PC: what it creates is private to its owner from its first instant (checked here by the mode or the access
 * list), and what exists is read as it is, whatever its access or owner and through a link; only a folder where a file is needed, a file where a folder is needed and an empty secret
 * file are refused. The cases that need permission bits run on Linux and macOS, the ones that need an access control list on Windows.
 */
class LocalPrivateStorageTest {
    private static final byte[] SECRET = "s3cret-value-0268".getBytes(StandardCharsets.UTF_8);

    /** The storage as the product builds it: where an access list is made it names the account of the SID in the process's token. */
    private final LocalPrivateStorage storage = new LocalPrivateStorage();

    @TempDir
    Path folder;

    private Path fileWithSecret(String name) throws IOException {
        Path file = folder.resolve(name);
        storage.createFile(file);
        Files.write(file, SECRET);
        return file;
    }

    // ---- creating ----

    @Test
    void aFileIsCreatedEmptyAndNeverReplaced() throws Exception {
        Path file = fileWithSecret("key");
        assertArrayEquals(SECRET, Files.readAllBytes(file));
        assertThrows(FileAlreadyExistsException.class, () -> storage.createFile(file));
        assertArrayEquals(SECRET, Files.readAllBytes(file), "an existing file is not replaced");
    }

    @Test
    void aFolderIsCreatedWithItsParentsAndAFolderThatExistsIsLeftAsItIs() throws Exception {
        Path deep = folder.resolve("state").resolve("service");
        storage.createFolder(deep);
        assertTrue(Files.isDirectory(deep));
        Path inside = deep.resolve("kept");
        Files.writeString(inside, "kept");
        storage.createFolder(deep);
        assertTrue(Files.exists(inside));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aNewFileAndAFolderHaveTheModesOnlyTheOwnerUses() throws Exception {
        Path file = fileWithSecret("key");
        Path deep = folder.resolve("state").resolve("service");
        storage.createFolder(deep);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(deep)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(deep.getParent())), "a parent that is made is private too");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aNewFileGrantsOnlyItsOwnerAndANewFolderTheOwnerAndTheSystemWithNothingInherited() throws Exception {
        Path file = fileWithSecret("key");
        Path directory = folder.resolve("a-folder");
        storage.createFolder(directory);
        // The access list names the account of the SID in the process's token (not the owner of the file, which a token can set to a group, and not a name setting).
        UserPrincipal owner = ProcessUser.current().principal(folder, FileSystems.getDefault().getUserPrincipalLookupService());
        List<AclEntry> fileEntries = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
        assertEquals(1, fileEntries.size(), "the owner and nothing inherited: " + fileEntries);
        assertEquals(owner, fileEntries.get(0).principal());
        assertEquals(AclEntryType.ALLOW, fileEntries.get(0).type());
        List<AclEntry> folderEntries = Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl();
        assertEquals(2, folderEntries.size(), "the owner and the system, nothing inherited: " + folderEntries);
        for (AclEntry entry : folderEntries) {
            assertEquals(AclEntryType.ALLOW, entry.type());
        }
    }

    @Test
    void anAccountTheFileSystemDoesNotKnowCannotOwnAnAccessListOnWindowsAndNeedsNoneOnPosix() throws Exception {
        LocalPrivateStorage stranger = new LocalPrivateStorage(() -> new ProcessUser("S-1-5-21-0-0-0-0", "NO-SUCH-DOMAIN-0268", "sennard-no-such-account-0268"));
        Path file = folder.resolve("other");
        if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
            stranger.createFile(file);
            assertTrue(Files.exists(file));
        } else {
            assertEquals(UnavailableCode.PRIVATE_STORAGE_USER_UNKNOWN, assertThrows(UnavailableException.class, () -> stranger.createFile(file)).code());
            assertFalse(Files.exists(file), "nothing is created for an account that is not known");
        }
    }

    /** A token that cannot be read gives no account, and nothing is created for a guessed one; permission bits name no account, so a system that has them never asks. */
    @Test
    void anAccountThatCannotBeReadIsUnknownWhereAnAccessListIsMadeAndIsNeverAskedWherePermissionBitsAre() throws Exception {
        LocalPrivateStorage unreadable = new LocalPrivateStorage(() -> {
            throw new IllegalStateException("no token");
        });
        Path file = folder.resolve("unreadable");
        if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
            unreadable.createFile(file);
            assertTrue(Files.exists(file), "a file store with permission bits needs no account");
        } else {
            assertEquals(UnavailableCode.PRIVATE_STORAGE_USER_UNKNOWN, assertThrows(UnavailableException.class, () -> unreadable.createFile(file)).code());
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void theAccountSourceIsRequired() {
        assertThrows(NullPointerException.class, () -> new LocalPrivateStorage(null));
    }

    // ---- reading and checking what exists: the PC is trusted ----

    @Test
    void aSecretFileItCreatedIsRead() throws Exception {
        Path file = fileWithSecret("key");
        SecretValue read = storage.readSecret(file);
        assertArrayEquals(SECRET, read.revealBytes());
        assertFalse(read.toString().contains("s3cret"));
    }

    @Test
    void aFolderWhereAFileIsNeededAndAFileWhereAFolderIsNeededAreRefused() throws Exception {
        Path directory = folder.resolve("a-folder");
        storage.createFolder(directory);
        Path file = fileWithSecret("a-file");
        UnavailableException notAFile = assertThrows(UnavailableException.class, () -> storage.readSecret(directory));
        assertEquals(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, notAFile.code());
        assertEquals(directory.toString(), notAFile.arguments().get("path"));
        assertFalse(String.valueOf(notAFile.getMessage()).contains("s3cret"));
        assertEquals(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, assertThrows(UnavailableException.class, () -> storage.requireSafeFolder(file)).code());
        assertEquals(UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN, assertThrows(UnavailableException.class, () -> storage.requireTrustedFolder(file)).code());
        storage.requireSafeFolder(directory);
        storage.requireTrustedFolder(directory);
    }

    @Test
    void anEmptySecretFileAndAMissingOneAreAnErrorAndNeverAnEmptySecret() throws Exception {
        Path file = folder.resolve("empty");
        storage.createFile(file);
        UnavailableException empty = assertThrows(UnavailableException.class, () -> storage.readSecret(file));
        assertEquals(UnavailableCode.PRIVATE_STORAGE_EMPTY, empty.code(), "an empty secret file is refused with the contract's code, not an IOException of no shape");
        assertEquals(file.toString(), empty.arguments().get("path"), "the refusal names the path and never the content");
        assertThrows(IOException.class, () -> storage.readSecret(folder.resolve("missing")));
        assertThrows(IOException.class, () -> storage.requireSafeFolder(folder.resolve("missing")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aFileThatTheGroupAndOthersMayReadIsReadAndAFolderOthersMayUseIsAcceptedAndNothingIsRepaired() throws Exception {
        Path file = fileWithSecret("key");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertArrayEquals(SECRET, storage.readSecret(file).revealBytes());
        Path state = folder.resolve("state");
        storage.createFolder(state);
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxrwxrwx"));
        storage.requireSafeFolder(state);
        storage.requireTrustedFolder(state);
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwxrwxrwx", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aLinkToAFileIsReadAndALinkToAFolderIsAccepted() throws Exception {
        Path target = fileWithSecret("target");
        Path link = folder.resolve("link");
        Files.createSymbolicLink(link, target);
        assertArrayEquals(SECRET, storage.readSecret(link).revealBytes());
        Path targetFolder = folder.resolve("target-folder");
        storage.createFolder(targetFolder);
        Path folderLink = folder.resolve("folder-link");
        Files.createSymbolicLink(folderLink, targetFolder);
        storage.requireSafeFolder(folderLink);
        storage.requireTrustedFolder(folderLink);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileAnotherAccountMayReadIsReadAndAFolderAnotherAccountMayWriteIsAccepted() throws Exception {
        Path file = fileWithSecret("key");
        UserPrincipal users = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("BUILTIN\\Users");
        AclFileAttributeView fileAcl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        List<AclEntry> fileEntries = new ArrayList<>(fileAcl.getAcl());
        fileEntries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users).setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA)).build());
        fileAcl.setAcl(fileEntries);
        assertArrayEquals(SECRET, storage.readSecret(file).revealBytes());
        Path directory = folder.resolve("a-folder");
        storage.createFolder(directory);
        AclFileAttributeView folderAcl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        List<AclEntry> folderEntries = new ArrayList<>(folderAcl.getAcl());
        folderEntries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users).setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.ADD_FILE)).build());
        folderAcl.setAcl(folderEntries);
        storage.requireSafeFolder(directory);
        storage.requireTrustedFolder(directory);
        assertEquals(fileEntries.size(), fileAcl.getAcl().size(), "nothing is repaired");
    }
}
