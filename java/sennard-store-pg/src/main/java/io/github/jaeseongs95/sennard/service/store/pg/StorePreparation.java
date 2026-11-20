// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The start-up state machine (design B.3): the server opens its listener first and reaches the database behind it, so a hook that cannot wait (its budget is
 * 1.5 s) gets an answer with the phase and when to ask again. The phases are {@link StorePhase}: FAILED with the reason after three failed attempts in a row;
 * {@link #run()} from {@code sennard prepare --retry} starts three more. Every step the provider takes is idempotent, so an attempt after a
 * failure resumes instead of starting over.
 *
 * <p>{@link #run()} does the work on the calling thread: the server calls it from an {@code @Async} task. The old pipe service
 * calls {@link #start()}, which goes away with it.
 */
public final class StorePreparation {
    private static final Logger LOG = LoggerFactory.getLogger(StorePreparation.class);

    /** Attempts in a row before FAILED. */
    static final int MAX_ATTEMPTS = 3;

    /**
     * @param reason       the fixed code of the last failure while FAILED (and while retrying), else null
     * @param detail       what that failure said, with its causes: the code alone does not tell which command failed or why; else null
     * @param retryAfterMs when to ask again; 0 once the state cannot change by waiting (READY, FAILED)
     */
    public record Status(StorePhase phase, Instant startedAt, long retryAfterMs, String reason, String detail, int attempt) {
    }

    /** The failure's message and each cause's class and message, at most five links and 800 characters. No SQL, no values, no credentials. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable link = failure;
        for (int depth = 0; link != null && depth < 5; depth++, link = link.getCause()) {
            if (depth > 0) {
                text.append(" <- ").append(link.getClass().getSimpleName()).append(": ");
            }
            text.append(link.getMessage());
        }
        return text.length() <= 800 ? text.toString() : text.substring(0, 800);
    }

    private final Function<PhaseListener, DatabaseConfigProvider> providerFactory;
    private final Duration retryDelay;
    private final Clock clock;
    private Status status;
    private SennardDatabaseConfig config;
    private boolean running;
    private int attempts;
    private String lastReason;
    private String lastDetail;

    /**
     * @param providerFactory makes the provider that reports its phases to the listener it is given
     * @param retryDelay      the pause between two attempts
     * @param clock           the application's clock: the start time of a preparation
     */
    public StorePreparation(Function<PhaseListener, DatabaseConfigProvider> providerFactory, Duration retryDelay, Clock clock) {
        this.providerFactory = providerFactory;
        this.retryDelay = retryDelay;
        this.clock = clock;
        this.status = new Status(StorePhase.NOT_CONFIGURED, clock.instant(), 0, null, null, 0);
    }

    /** Starts {@link #run()} on a virtual thread and returns at once; does nothing when it is running or ready. */
    public void start() {
        if (begin()) {
            Thread.ofVirtual().name("sennard-store-prepare").start(this::prepare);
        }
    }

    /** Three more attempts after FAILED. */
    public synchronized void retry() {
        if (status.phase() == StorePhase.FAILED) {
            start();
        }
    }

    public synchronized Status status() {
        return status;
    }

    /** The database settings once READY; before that STORE_PREPARING, never a half-prepared database. */
    public synchronized SennardDatabaseConfig config() {
        if (status.phase() != StorePhase.READY) {
            throw new StoreException(StoreCode.STORE_PREPARING, "The database is not ready (" + status.phase() + ").");
        }
        return config;
    }

    /** Waits until READY or FAILED; returns the status then, or the status at the deadline. */
    public Status awaitSettled(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (status.phase() != StorePhase.READY && status.phase() != StorePhase.FAILED) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return status;
        }
    }

    /** Prepares the store on this thread: up to three attempts, a pause between them. Does nothing when already running or ready. */
    public void run() {
        if (begin()) {
            prepare();
        }
    }

    /** Leaves FAILED before the caller goes on: whoever waits right after a retry waits for that retry. */
    private synchronized boolean begin() {
        if (running || status.phase() == StorePhase.READY) {
            return false;
        }
        running = true;
        status = new Status(StorePhase.CONNECTING, clock.instant(), 1000, null, null, 0);
        attempts = 0;
        lastReason = null;
        lastDetail = null;
        return true;
    }

    private void prepare() {
        Instant startedAt = status().startedAt();
        RetryTemplate retry = new RetryTemplate(RetryPolicy.builder().maxRetries(MAX_ATTEMPTS - 1).delay(retryDelay).build());
        try {
            SennardDatabaseConfig prepared = retry.execute(() -> attempt(startedAt));
            // READY is set together with the config: a reader never sees READY before it can get the settings.
            finish(new Status(StorePhase.READY, startedAt, 0, null, null, attempts), prepared);
        } catch (RetryException exhausted) {
            LOG.warn("The business database was not prepared after {} attempts: {} {}", MAX_ATTEMPTS, lastReason, lastDetail, exhausted);
            finish(new Status(StorePhase.FAILED, startedAt, 0, lastReason, lastDetail, MAX_ATTEMPTS), null);
        }
    }

    private SennardDatabaseConfig attempt(Instant startedAt) {
        int current;
        String reason;
        String detail;
        synchronized (this) {
            current = ++attempts;
            reason = lastReason;
            detail = lastDetail;
            set(new Status(StorePhase.CONNECTING, startedAt, 1000, reason, detail, current));
        }
        try {
            return providerFactory.apply(phase -> {
                if (phase != StorePhase.READY) {
                    set(new Status(phase, startedAt, 1000, reason, detail, current));
                }
            }).provide();
        } catch (StoreException failure) {
            remember(failure.code().name(), describe(failure));
            throw failure;
        } catch (RuntimeException failure) {
            remember("STORE_PREPARE_FAILED", describe(failure));
            throw failure;
        }
    }

    private synchronized void remember(String reason, String detail) {
        lastReason = reason;
        lastDetail = detail;
    }

    private synchronized void finish(Status next, SennardDatabaseConfig prepared) {
        config = prepared;
        running = false;
        status = next;
        notifyAll();
    }

    private synchronized void set(Status next) {
        status = next;
        notifyAll();
    }
}
