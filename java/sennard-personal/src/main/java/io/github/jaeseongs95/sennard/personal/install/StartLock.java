// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The right to start the server, held by one process at a time: a lock on a file in the state folder
 * ({@link FileChannel#tryLock()}, a lock of the operating system that is released when the channel is closed or the process ends). Two session starts that find no
 * server would otherwise each start one. The file stays in the state folder; what is held is the lock, which the operating system drops with the process that held
 * it, so a start that dies leaves nothing locked.
 */
final class StartLock {
    /** The lock's file in the state folder. */
    static final String FILE = "server-start.lock";

    private StartLock() {
    }

    /**
     * Runs {@code work} while this process holds the lock, and lets go of it after. The state folder is made through the port of the storage of secrets when it is not there, so that
     * it is private to its owner from its first instant. The lock's file is opened here and not made by the port: it is empty, it holds nothing and it is in that folder, and what is
     * held is the lock of the operating system, not the file.
     *
     * @param storage makes the state folder
     * @return false, without running {@code work}, when another process holds the lock
     */
    static boolean runHolding(Path stateDirectory, PrivateStorage storage, Runnable work) {
        Path file = stateDirectory.resolve(FILE);
        try {
            storage.createFolder(stateDirectory);
            // Closing the channel releases the lock it holds.
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                if (channel.tryLock() == null) {
                    return false;
                }
                work.run();
                return true;
            }
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.START_FAILED, SennardException.args("what", "lockFailed", "file", file, "cause", cause.getMessage()), cause);
        }
    }
}
