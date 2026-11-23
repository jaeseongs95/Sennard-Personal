// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.util.FileSystemUtils;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/** Retires only versions whose paths were never written into a host definition or generated for manual placement. */
final class VersionRetirement {
    static final String UNEXPOSED = ".definitions-unexposed";
    private static final String PREFIX = ".retiring-";
    private static final String RECEIPT = ".retirement";
    private static final String PAYLOAD = "version";

    private final AtomicMove move;
    private final DeleteTree delete;

    VersionRetirement() {
        this((source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE), FileSystemUtils::deleteRecursively);
    }

    VersionRetirement(AtomicMove move, DeleteTree delete) {
        this.move = move;
        this.delete = delete;
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface DeleteTree {
        void delete(Path folder) throws IOException;
    }

    static void protect(InstalledVersion version) throws IOException {
        // Once a definition could escape to a host or user, its absence from the current inventory proves nothing.
        Files.deleteIfExists(version.folder().resolve(UNEXPOSED));
    }

    /** Publication, placement and retirement share this lock. Keep the lock file so two processes always lock the same inode. */
    static void withLock(Path versions, Runnable action) {
        Path file = versions.resolve(".placement.lock");
        try {
            Files.createDirectories(versions);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Another installation command holds " + file);
                }
                action.run();
            }
        } catch (IOException | OverlappingFileLockException unavailable) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unwritable", "file", file), unavailable);
        }
    }

    void collect(Path versions, List<InstalledVersion> older) {
        retry(versions);
        for (InstalledVersion old : older) {
            Path folder = old.folder();
            if (!Files.isRegularFile(folder.resolve(UNEXPOSED), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Path retired = Files.createTempDirectory(versions.toRealPath(), PREFIX);
                Files.writeString(retired.resolve(RECEIPT), retired.getFileName().toString(), StandardOpenOption.CREATE_NEW);
                move.move(folder, retired.resolve(PAYLOAD));
                finish(retired);
            } catch (IOException failure) {
                throw failed(folder, failure);
            }
        }
    }

    private void retry(Path versions) {
        List<Path> pending;
        try (Stream<Path> paths = Files.list(versions.toRealPath())) {
            pending = paths.filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        } catch (IOException failure) {
            throw failed(versions, failure);
        }
        for (Path retired : pending) {
            try {
                if (isDirectDirectory(retired) && ownsRetirement(retired)) {
                    finish(retired);
                }
            } catch (IOException failure) {
                throw failed(retired, failure);
            }
        }
    }

    private static boolean ownsRetirement(Path retired) throws IOException {
        Path receipt = retired.resolve(RECEIPT);
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        byte[] expected = retired.getFileName().toString().getBytes(StandardCharsets.UTF_8);
        try (var input = Files.newInputStream(receipt, LinkOption.NOFOLLOW_LINKS)) {
            return Arrays.equals(expected, input.readNBytes(expected.length + 1));
        }
    }

    private void finish(Path retired) throws IOException {
        Path version = retired.resolve(PAYLOAD);
        if (!Files.notExists(version, LinkOption.NOFOLLOW_LINKS)) {
            if (!isDirectDirectory(version)) {
                return;
            }
            delete.delete(version);
        }
        // The receipt lives outside the tree so a partial deletion cannot remove the evidence needed for retry.
        Files.delete(retired.resolve(RECEIPT));
        Files.delete(retired);
    }

    private static boolean isDirectDirectory(Path path) throws IOException {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path);
    }

    private static UnavailableException failed(Path folder, IOException cause) {
        return new UnavailableException(UnavailableCode.DELETE_FAILED, SennardException.args("folder", folder, "cause", cause.toString()), cause);
    }
}
