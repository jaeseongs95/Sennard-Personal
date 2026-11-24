// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class ManagedExchangeDeadlineTest {
    @Test
    void preservesIoFailuresAndIncludesOtherCausesOnlyWhenConfigured() throws Exception {
        var publicMessages = new ManagedExchangeDeadline(Duration.ofSeconds(5), "timeout", "failed", false);
        var diagnosticMessages = new ManagedExchangeDeadline(Duration.ofSeconds(5), "timeout", "failed", true);
        var io = new IOException("protocol rejection");
        var malformed = new IllegalStateException("host detail");

        assertEquals("reply", publicMessages.run(() -> "reply"));
        assertSame(io, assertThrows(IOException.class, () -> publicMessages.run(() -> { throw io; })));
        var sanitized = assertThrows(IOException.class, () -> publicMessages.run(() -> { throw malformed; }));
        assertEquals("failed", sanitized.getMessage());
        assertNull(sanitized.getCause());
        var diagnostic = assertThrows(IOException.class, () -> diagnosticMessages.run(() -> { throw malformed; }));
        assertSame(malformed, diagnostic.getCause());
    }

    @Test
    void timingOutWhileWaitingOnAnExistingExchangeDoesNotCancelIt() throws Exception {
        var deadline = new ManagedExchangeDeadline(Duration.ZERO, "timeout", "failed", true);
        var turn = new FutureTask<>(() -> "reply");

        var timeout = assertThrows(IOException.class, () -> deadline.await(turn));
        assertEquals("timeout", timeout.getMessage());
        assertInstanceOf(TimeoutException.class, timeout.getCause());
        assertFalse(turn.isDone());
        turn.run();
        assertEquals("reply", deadline.await(turn));
    }

    @Test
    void interruptingTheCallerCancelsItsExchangeAndLetsTheCallerRestoreInterrupt() throws Exception {
        var deadline = new ManagedExchangeDeadline(Duration.ofSeconds(10), "timeout", "failed", false);
        var started = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var caller = new FutureTask<>(() -> {
            try {
                deadline.run(() -> {
                    started.countDown();
                    try {
                        released.await();
                    } catch (InterruptedException interrupted) {
                        cancelled.countDown();
                        throw interrupted;
                    }
                    return null;
                });
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Thread.currentThread().isInterrupted();
            }
        });
        Thread waiting = Thread.ofVirtual().start(caller);
        try {
            assertTrue(started.await(5, SECONDS));
            waiting.interrupt();
            assertTrue(caller.get(5, SECONDS));
            assertTrue(cancelled.await(5, SECONDS));
        } finally {
            released.countDown();
        }
    }
}
