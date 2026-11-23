// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

class VersionRetirementTest {
    @TempDir
    Path versions;

    private InstalledVersion runtime(String name, boolean unexposed) throws IOException {
        Path folder = versions.resolve(name);
        InstallStandIns.installedRuntime(folder, name);
        if (unexposed) {
            Files.createFile(folder.resolve(VersionRetirement.UNEXPOSED));
        }
        return new InstalledVersion(folder);
    }

    @Test
    void unknownActiveCopyOrUnreloadedHostPreventsCollection() throws IOException {
        InstalledVersion legacy = runtime("1.0.0", false);
        InstalledVersion exposed = runtime("1.1.0", true);
        VersionRetirement.protect(exposed);
        InstalledVersion neverExposed = runtime("1.2.0", true);

        new VersionRetirement().collect(versions, List.of(legacy, exposed, neverExposed));

        for (InstalledVersion pinned : List.of(legacy, exposed)) {
            assertTrue(Files.isRegularFile(pinned.appJar()));
            assertTrue(Files.isRegularFile(pinned.hookClientJar()));
        }
        assertFalse(Files.exists(neverExposed.folder()));
    }

    @Test
    void failedAtomicRetirementDoesNotDeleteSource() throws IOException {
        InstalledVersion old = runtime("1.0.0", true);
        Path hook = old.hookClientJar();
        VersionRetirement unavailable = new VersionRetirement(
                (source, target) -> {
                    assertEquals(versions, source.getParent());
                    assertEquals(versions, target.getParent().getParent());
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture");
                },
                path -> { throw new AssertionError("no deletion after a move failure: " + path); });

        UnavailableException failed = assertThrows(UnavailableException.class, () -> unavailable.collect(versions, List.of(old)));

        assertEquals(UnavailableCode.DELETE_FAILED, failed.code());
        assertTrue(Files.isRegularFile(old.appJar()));
        assertTrue(Files.isRegularFile(hook));
        assertEquals(old, InstalledVersion.at(old.folder()).orElseThrow());

        new VersionRetirement().collect(versions, List.of(old));
        assertFalse(Files.exists(old.folder()));
    }

    @Test
    void aRetiringPrefixAloneDoesNotAuthorizeDeletion() throws IOException {
        Path unrelated = Files.createDirectory(versions.resolve(".retiring-1.0.0-7b6650bb-cafa-45a8-acb1-98c00cdf71ea"));
        Path content = Files.writeString(Files.createDirectory(unrelated.resolve("version")).resolve("notes.txt"), "keep this");
        Path copied = Files.createDirectory(versions.resolve(".retiring-copied"));
        Files.writeString(copied.resolve(".retirement"), unrelated.getFileName().toString());
        Path copiedContent = Files.writeString(Files.createDirectory(copied.resolve("version")).resolve("notes.txt"), "keep this too");

        new VersionRetirement().collect(versions, List.of());

        assertEquals("keep this", Files.readString(content));
        assertEquals("keep this too", Files.readString(copiedContent));
    }

    @Test
    void interruptedRetirementIsExcludedAndRetried() throws IOException {
        InstalledVersion old = runtime("1.0.0", true);
        Path hook = old.hookClientJar();
        VersionRetirement interrupted = new VersionRetirement(
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
                folder -> {
                    Files.delete(folder.resolve(VersionRetirement.UNEXPOSED));
                    Files.delete(folder.resolve("contracts/workflow-plan.v1.schema.json"));
                    throw new IOException("interrupted after removing one file");
                });

        UnavailableException failed = assertThrows(UnavailableException.class, () -> interrupted.collect(versions, List.of(old)));

        assertEquals(UnavailableCode.DELETE_FAILED, failed.code());
        assertFalse(Files.exists(old.folder()));
        Path tombstone;
        try (var children = Files.list(versions)) {
            tombstone = children.filter(child -> child.getFileName().toString().startsWith(".retiring-")).findFirst().orElseThrow();
        }
        Path payload = tombstone.resolve("version");
        assertTrue(Files.isRegularFile(payload.resolve(old.appJar().getFileName())));
        assertTrue(Files.isRegularFile(payload.resolve(old.folder().relativize(hook))));
        assertTrue(InstalledVersion.at(tombstone).isEmpty());

        new VersionRetirement().collect(versions, List.of());
        assertFalse(Files.exists(tombstone));
    }
}
