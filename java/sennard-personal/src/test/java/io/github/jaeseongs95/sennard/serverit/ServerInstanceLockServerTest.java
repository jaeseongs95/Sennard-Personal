// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * One server for one state folder, with two real servers: no fixed port keeps a second one from starting over the first's state (the settings database, the attachments, the address
 * file), so {@code ServerInstanceLock} does. Real Tomcat, real files, the JDK's file locks. Case table (input: a server running on a state folder, a second started on the same folder;
 * expected: the second does not start and the first is as it was):
 * <pre>
 * i1  second refused   the second server's start fails with a ConflictException of ConflictCode.SERVER_ALREADY_RUNNING among its causes, whose folder argument names the folder; it opens no port of its own
 *                      (the first's address file still names the first, whose port still answers)
 * i2  after the stop   when the first has stopped, a server starts on the folder and announces itself
 * </pre>
 */
class ServerInstanceLockServerTest {
    private static String causes(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    /** The start's failure wraps the refusal in the container's exceptions: the {@link ConflictException} is in the cause chain. */
    private static ConflictException conflictIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConflictException conflict) {
                return conflict;
            }
        }
        throw new AssertionError("no ConflictException in the causes of the start's failure: " + causes(failure));
    }

    @Test
    void aSecondServerOnTheSameStateFolderDoesNotStartAndTheFirstIsAsItWas() throws Exception {
        Path folder = Files.createTempDirectory("sennard-lock-state-");
        try {
            try (RunningServer first = RunningServer.startAt(folder, List.of())) {
                Optional<ServerAddress> announced = ServerAddress.read(folder.resolve(ServerAddress.FILE_NAME));
                assertTrue(announced.isPresent());

                RuntimeException refused = assertThrows(RuntimeException.class, () -> RunningServer.startAt(folder, List.of()));

                ConflictException conflict = conflictIn(refused);
                assertEquals(ConflictCode.SERVER_ALREADY_RUNNING, conflict.code(), causes(refused));
                assertEquals(folder.toAbsolutePath().normalize().toString(), conflict.arguments().get("folder"), "the folder is named: " + causes(refused));
                assertEquals(announced, ServerAddress.read(folder.resolve(ServerAddress.FILE_NAME)), "the second server wrote nothing and the first's address is still the file's");
                assertEquals(first.port, announced.orElseThrow().port());
            }
            assertFalse(Files.exists(folder.resolve(ServerAddress.FILE_NAME)), "the first server removed its address when it stopped");

            try (RunningServer later = RunningServer.startAt(folder, List.of())) {
                assertEquals(later.port, ServerAddress.read(folder.resolve(ServerAddress.FILE_NAME)).orElseThrow().port(), "the folder is free for a server once the first has stopped");
            }
        } finally {
            RunningServer.deleteTree(folder);
        }
    }
}
