// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs protocol work within the caller's deadline while the adapter owns process cleanup. */
record ManagedExchangeDeadline(Duration duration, String timeoutMessage, String failureMessage, boolean retainCause) {
    <T> T run(Callable<T> exchange) throws IOException, InterruptedException {
        var task = new FutureTask<>(exchange);
        Thread.ofVirtual().start(task);
        try {
            return await(task);
        } finally {
            task.cancel(true);
        }
    }

    /** Observes an existing exchange without taking ownership of its cancellation. */
    <T> T await(Future<T> exchange) throws IOException, InterruptedException {
        try {
            return exchange.get(duration.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException(timeoutMessage, retainCause ? timeout : null);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(failureMessage, retainCause ? cause : null);
        }
    }
}
