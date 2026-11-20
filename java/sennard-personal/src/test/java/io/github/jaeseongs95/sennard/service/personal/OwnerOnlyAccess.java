// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import io.github.jaeseongs95.sennard.service.secret.ProcessUser;

/**
 * What a folder that {@code LocalPrivateStorage} made looks like to the file system, for the tests that make a state folder by any route (the installer, the start lock, the host
 * identity, the server's own start): where the file store has permission bits the folder is {@code rwx------}; where it has an access list the list names the user of the process (the
 * SID of its token, {@link ProcessUser}) and the system account and nobody else.
 */
public final class OwnerOnlyAccess {
    private OwnerOnlyAccess() {
    }

    /** Asserts that the folder is private to its owner by the means its file store has. */
    public static void assertOwnerOnlyFolder(Path folder) throws IOException {
        assertTrue(Files.isDirectory(folder), folder + " is a folder");
        PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals("rwx------", PosixFilePermissions.toString(posix.readAttributes().permissions()), folder.toString());
            return;
        }
        UserPrincipal user = ProcessUser.current().principal(folder.getParent(), FileSystems.getDefault().getUserPrincipalLookupService());
        List<AclEntry> entries = Files.getFileAttributeView(folder, AclFileAttributeView.class).getAcl();
        assertFalse(entries.isEmpty(), "an access list that names the user: " + entries);
        assertTrue(entries.size() <= 2, "the user and the system account, nothing inherited: " + entries);
        for (AclEntry entry : entries) {
            assertEquals(AclEntryType.ALLOW, entry.type(), entries.toString());
            boolean system = entry.principal().getName().toUpperCase(java.util.Locale.ROOT).endsWith("SYSTEM");
            assertTrue(entry.principal().equals(user) || system, "only the user of the process and the system account have access: " + entries);
        }
        assertTrue(entries.stream().anyMatch(entry -> entry.principal().equals(user)), "the user of the process has access: " + entries);
    }
}
