// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real processes, no substitutes: what HostWatch reports is what the JDK's ProcessHandle says, on every OS alike. */
class HostWatchTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Process process;

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static <T> T await(Supplier<Optional<T>> condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        do {
            Optional<T> value = condition.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + what);
    }

    @Test
    void reportsTheEndOfAWatchedProcessAndWakesTheCaller() throws Exception {
        process = HostProcessFixture.start();
        CountDownLatch woken = new CountDownLatch(1);
        HostWatch watch = HostWatch.bind(process.pid(), now() + 60_000, woken::countDown);
        assertFalse(watch.startToken().isBlank());
        assertEquals(Optional.empty(), watch.endReason());
        process.destroyForcibly();
        assertTrue(woken.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(HostWatch.PROCESS_ENDED, await(watch::endReason, "the end reason"));
    }

    @Test
    void refusesAPidNothingUses() throws Exception {
        process = HostProcessFixture.start();
        long pid = process.pid();
        process.destroyForcibly();
        process.waitFor();
        await(() -> ProcessHandle.of(pid).isEmpty() ? Optional.of(true) : Optional.empty(), "the process to be gone");
        HostWatch.BindFailed failed = assertThrows(HostWatch.BindFailed.class, () -> HostWatch.bind(pid, now(), () -> { }));
        assertEquals("HOST_PROCESS_GONE", failed.reason());
    }

    @Test
    void refusesAProcessBornAfterTheHookStarted() throws Exception {
        long before = now() - 30_000;
        process = HostProcessFixture.start();
        HostWatch.BindFailed failed = assertThrows(HostWatch.BindFailed.class, () -> HostWatch.bind(process.pid(), before, () -> { }));
        assertEquals("HOST_BORN_AFTER_BOUND", failed.reason());
    }

    /** The same intent as the earlier read of the OS's own start field: the token that identifies a birth is what the JDK reports as the process's start time. */
    @Test
    void theStartTokenIsTheJdksStartInstantOfTheBoundProcess() throws Exception {
        process = HostProcessFixture.start();
        HostWatch watch = HostWatch.bind(process.pid(), now() + 60_000, () -> { });
        assertEquals(ProcessHandle.of(process.pid()).orElseThrow().info().startInstant().orElseThrow().toString(), watch.startToken());
        assertEquals(watch.startToken(), HostWatch.bind(process.pid(), now() + 60_000, () -> { }).startToken(), "two binds of one process read one birth");
    }

    /**
     * The JDK says a process id may be reused at an unpredictable time, so a watch never asks the id again: once the bound process has ended, the end
     * stays the answer, however many times and however long after it is read.
     */
    @Test
    void anEndStaysEndedWithoutAskingTheProcessIdAgain() throws Exception {
        process = HostProcessFixture.start();
        CountDownLatch woken = new CountDownLatch(1);
        HostWatch watch = HostWatch.bind(process.pid(), now() + 60_000, woken::countDown);
        process.destroyForcibly();
        assertTrue(woken.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(HostWatch.PROCESS_ENDED, await(watch::endReason, "the end reason"));
        Thread.sleep(200);
        assertEquals(Optional.of(HostWatch.PROCESS_ENDED), watch.endReason());
        assertEquals(Optional.of(HostWatch.PROCESS_ENDED), watch.endReason());
    }

    /** Closing a watch gives up the notification; it is not an end of the host. */
    @Test
    void closingAWatchOfALiveHostReportsNoEnd() throws Exception {
        process = HostProcessFixture.start();
        HostWatch watch = HostWatch.bind(process.pid(), now() + 60_000, () -> { });
        watch.close();
        assertEquals(Optional.empty(), watch.endReason());
    }

    @Test
    void identityOfARunningFixtureIsStable() throws IOException, Exception {
        process = HostProcessFixture.start();
        HostWatch watch = HostWatch.bind(process.pid(), now() + 60_000, () -> { });
        Thread.sleep(100);
        assertEquals(Optional.empty(), watch.endReason());
    }
}
