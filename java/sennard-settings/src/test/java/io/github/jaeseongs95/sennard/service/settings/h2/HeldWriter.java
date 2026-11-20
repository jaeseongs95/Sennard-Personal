// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

/**
 * How a settings store test makes two callers meet in the database whatever the number of CPUs, and sees from the database that they do: the contract of the store tests'
 * {@code LockWaits} and of the server tests' {@code HookRaceLocks}. The test holds a transaction of its own on a connection (the writer that is ahead of the callers), starts the
 * callers, and looks at the database until the callers are waiting for that holder ({@code INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID}, which H2 documents: the wait that counts is one
 * this holder blocks); only then it lets the holder commit. Nothing sleeps for a time to let the callers meet, and a wait that is not seen by the deadline fails the test: the deadline
 * is only ever reached by a failure.
 */
final class HeldWriter implements AutoCloseable {
    static final String LARGER_POOL = "sennard.settings.pool.maximum-size=8";
    private static final Duration LIMIT = Duration.ofSeconds(120);
    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    private final Connection connection;
    private final long session;
    private boolean open = true;

    private HeldWriter(Connection connection, long session) {
        this.connection = connection;
        this.session = session;
    }

    /** Runs the statements in a transaction of the test's own and keeps it open: what they wrote or locked stays so until {@link #release}. */
    static HeldWriter holding(DataSource source, String... statements) throws SQLException {
        Connection connection = source.getConnection();
        try {
            connection.setAutoCommit(false);
            long session;
            try (PreparedStatement id = connection.prepareStatement("SELECT SESSION_ID()"); ResultSet rows = id.executeQuery()) {
                assertTrue(rows.next(), "the database gave no session id");
                session = rows.getLong(1);
            }
            for (String sql : statements) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
            return new HeldWriter(connection, session);
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /**
     * Waits until at least {@code count} sessions wait for what this holder has. A wait that is not seen by the deadline fails with {@code failure}, the number of sessions that did wait
     * and what each of the callers did (done or not, its result or its exception), so that the next failure can be told apart at once: callers that finished without waiting are the
     * database refusing at once, callers that are not done and not waiting are somewhere else.
     */
    void awaitBlocked(DataSource source, int count, String failure, Future<?>... calls) throws SQLException {
        long deadline = System.nanoTime() + LIMIT.toNanos();
        long seen;
        while ((seen = blocked(source)) < count) {
            // Every call has ended while the writer is still ahead: they did not wait for it, so waiting longer cannot make them (the database refused them at once, or they failed).
            boolean ended = calls.length > 0 && java.util.Arrays.stream(calls).allMatch(Future::isDone);
            if (ended || System.nanoTime() >= deadline) {
                fail(failure + " (waiting for this writer: " + seen + " of " + count + "; the calls: " + describe(calls) + ")");
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    /** The sessions that wait for what this holder has now. */
    long blocked(DataSource source) throws SQLException {
        // Each look is made on a connection of its own, which is neither the holder's nor a caller's.
        try (Connection observer = source.getConnection(); PreparedStatement statement = observer.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID = ?")) {
            statement.setLong(1, session);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the sessions view gave no count");
                return rows.getLong(1);
            }
        }
    }

    /** Commits what the holder did and lets the waiting statements go on. */
    void release() throws SQLException {
        if (open) {
            open = false;
            try {
                connection.commit();
            } finally {
                connection.close();
            }
        }
    }

    /** Ends the holder without committing when the test leaves before it released. */
    @Override
    public void close() throws SQLException {
        if (open) {
            open = false;
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** What each call has done so far: running, its result, or the exception it failed with. */
    static String describe(Future<?>... calls) {
        List<String> states = new ArrayList<>();
        for (Future<?> call : calls) {
            states.add(switch (call.state()) {
                case RUNNING -> "running";
                case SUCCESS -> "succeeded with " + call.resultNow();
                case FAILED -> "failed with " + call.exceptionNow();
                case CANCELLED -> "cancelled";
            });
        }
        return states.toString();
    }

    static <T> T within(Future<T> call) throws InterruptedException, ExecutionException, TimeoutException {
        return call.get(LIMIT.toSeconds(), TimeUnit.SECONDS);
    }

    static long count(DataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    static void execute(DataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    static List<String> column(DataSource source, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
