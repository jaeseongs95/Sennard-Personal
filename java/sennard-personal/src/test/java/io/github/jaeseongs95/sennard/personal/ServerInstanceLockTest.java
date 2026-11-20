// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;

/**
 * One server for one state folder ({@link ServerInstanceLock}), with real files, the JDK's file locks and a real second process. Case table (input: a state folder, who holds its lock;
 * expected: the lock, or the refusal {@code SERVER_ALREADY_RUNNING}):
 * <pre>
 * k1  free folder    the lock is taken and the lock file {@code server.lock} is in the folder; a lock file that an earlier server left (nobody holds it) is taken too; a folder that is not
 *                    there is made; no {@code sennard.state-dir} is a refusal
 * k2  held in JVM    a second lock on the same folder in the same JVM is refused (the JDK's OverlappingFileLockException is its cause); after the first is closed, the folder is free
 * k3  other folders  folders do not hold each other
 * k4  held elsewhere another process holds the lock: refused, with no cause (tryLock gave null); when that process ends the folder is free (the operating system releases the lock of a process
 *                    however it ended)
 * </pre>
 */
class ServerInstanceLockTest {
    /** The lock as the application makes it: the environment names the state folder ({@code sennard.state-dir}), and the lock makes the folder if it is not there. */
    static ServerInstanceLock lockOn(Path folder) throws IOException {
        AbstractEnvironment environment = emptyEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("sennard.state-dir", folder.toString())));
        return new ServerInstanceLock(environment);
    }

    /** An environment of nothing: not the system properties and the process's variables, where the test run's own {@code SENNARD_STATE_DIR} would be found. */
    private static AbstractEnvironment emptyEnvironment() {
        return new AbstractEnvironment() {
        };
    }

    @TempDir
    Path temporary;

    @Test
    void aFreeFolderIsLockedAndAnEarlierServersLockFileIsNoObstacle() throws IOException {
        Path folder = Files.createDirectory(temporary.resolve("free"));
        Files.writeString(folder.resolve(ServerInstanceLock.FILE_NAME), "left by a server that was killed", StandardCharsets.UTF_8);

        try (ServerInstanceLock lock = lockOn(folder)) {
            assertEquals(folder.toAbsolutePath().normalize(), lock.folder());
            assertTrue(Files.exists(folder.resolve(ServerInstanceLock.FILE_NAME)));
        }
    }

    @Test
    void aSecondLockOnTheSameFolderInTheSameJvmIsRefusedAndTheFolderIsFreeAfterTheFirstIsClosed() throws IOException {
        Path folder = Files.createDirectory(temporary.resolve("held"));
        ServerInstanceLock first = lockOn(folder);
        try {
            ConflictException refused = assertThrows(ConflictException.class, () -> lockOn(folder));

            assertEquals(ConflictCode.SERVER_ALREADY_RUNNING, refused.code());
            assertEquals(folder.toAbsolutePath().normalize().toString(), refused.arguments().get("folder"), "the refusal names the folder");
            assertTrue(refused.getCause() instanceof OverlappingFileLockException, String.valueOf(refused.getCause()));
        } finally {
            first.close();
        }

        try (ServerInstanceLock again = lockOn(folder)) {
            assertEquals(folder.toAbsolutePath().normalize(), again.folder());
        }
    }

    @Test
    void aFolderThatIsNotThereIsMadeAndAnEnvironmentThatNamesNoFolderIsRefused() throws IOException {
        Path notThere = temporary.resolve("not-yet-made");

        try (ServerInstanceLock lock = lockOn(notThere)) {
            assertTrue(Files.isDirectory(notThere));
            assertTrue(Files.exists(notThere.resolve(ServerInstanceLock.FILE_NAME)));
            assertEquals(notThere.toAbsolutePath().normalize(), lock.folder());
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new ServerInstanceLock(emptyEnvironment()));
        assertEquals("sennard.state-dir is not set", refused.getMessage());
    }

    @Test
    void foldersDoNotHoldEachOther() throws IOException {
        try (ServerInstanceLock one = lockOn(Files.createDirectory(temporary.resolve("one"))); ServerInstanceLock two = lockOn(Files.createDirectory(temporary.resolve("two")))) {
            assertTrue(!one.folder().equals(two.folder()));
        }
    }

    @Test
    void aLockHeldByAnotherProcessRefusesAndTheFolderIsFreeWhenThatProcessEnds() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("other-process"));
        String classPath = System.getProperty("sennard.test.classpath");
        String childHeap = System.getProperty("sennard.test.childHeap");
        assertNotNull(classPath, "sennard.test.classpath is set by the build on every test task");
        assertNotNull(childHeap, "sennard.test.childHeap is set by the build on every test task");
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + childHeap, "-cp", classPath, ServerInstanceLockHolder.class.getName(),
                folder.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            BufferedReader told = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(ServerInstanceLockHolder.HELD + " " + folder.getFileName(), told.readLine(), "the other process holds the lock of this folder");

            ConflictException refused = assertThrows(ConflictException.class, () -> lockOn(folder));

            assertEquals(ConflictCode.SERVER_ALREADY_RUNNING, refused.code());
            assertEquals(folder.toAbsolutePath().normalize().toString(), refused.arguments().get("folder"), "the refusal names the folder");
            assertNull(refused.getCause(), "no lock of this JVM is in the way: the other process's is");
        } finally {
            holder.getOutputStream().close(); // its stdin ends, and it ends
            if (!holder.waitFor(120, TimeUnit.SECONDS)) {
                holder.descendants().forEach(ProcessHandle::destroyForcibly);
                holder.destroyForcibly();
            }
        }
        assertEquals(0, holder.exitValue());

        try (ServerInstanceLock free = lockOn(folder)) {
            assertEquals(folder.toAbsolutePath().normalize(), free.folder());
        }
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(List.of(ServerInstanceLock.FILE_NAME), files.map(path -> path.getFileName().toString()).toList(), "the lock file is the folder's only file");
        }
    }
}
