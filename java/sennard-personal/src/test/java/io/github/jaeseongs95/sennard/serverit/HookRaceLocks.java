// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;

/**
 * How a server test makes requests overlap in the database whatever the number of CPUs, and sees from the database that they do: the contract of the store tests' {@code LockWaits}
 * (the holder is a connection the test itself holds; the wait that counts is one the database says this holder blocks, so another connection's wait never makes a test pass), for
 * the requests of an assembled server. The test takes a lock on one connection of its own ({@link #holding}), sends the requests, and looks at the database until the waits it
 * expects are there ({@link #awaitBlocked}, {@link #awaitQueuedBehindBlocked}); only then it lets the lock go ({@link #release}). A wait that is not seen by the deadline fails the
 * test: the deadline is only ever reached by a failure, and nothing here sleeps for a time to let the requests meet. Before the requests are sent the server's pool is made to hold
 * the connections they will wait with ({@link #warm}), and a request that is answered while it is still expected to wait fails the test at once with its answer (the waits the database
 * shows are those of requests that are in it: a request that never reached a statement cannot be seen there).
 *
 * <p>The database is PostgreSQL: the waits are read from {@code pg_stat_activity} and {@code pg_blocking_pids}, and a table is locked in {@code SHARE ROW EXCLUSIVE} mode, which
 * blocks every {@code INSERT}, {@code UPDATE} and {@code DELETE} of it and leaves reads alone. Any other product fails the test.
 */
final class HookRaceLocks implements AutoCloseable {
    private static final Duration LIMIT = Duration.ofSeconds(120);
    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    private final Connection holder;
    private final long backend;
    private boolean open = true;

    private HookRaceLocks(Connection holder, long backend) {
        this.holder = holder;
        this.backend = backend;
    }

    /**
     * Runs {@code sql} on a connection of the test's own and keeps its transaction open, so that what the statement locked or inserted stays so until {@link #release} (a lock of a
     * table, a row taken {@code FOR NO KEY UPDATE}, a row inserted and not committed).
     */
    static HookRaceLocks holding(String sql, Object... params) throws SQLException {
        Connection connection = BusinessDb.connect();
        try {
            connection.setAutoCommit(false);
            long backend;
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the database gave no backend identifier");
                backend = rows.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < params.length; index += 1) {
                    statement.setObject(index + 1, params[index]);
                }
                statement.execute();
            }
            return new HookRaceLocks(connection, backend);
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** Locks the table so that no statement can write to it until {@link #release}. */
    static HookRaceLocks lockingWritesOf(String table) throws SQLException {
        return holding("LOCK TABLE " + table + " IN SHARE ROW EXCLUSIVE MODE");
    }

    /**
     * Makes the pool of the server hold {@code connections} connections before the requests are sent. A request that waits for a row holds a connection of the server's pool for as long
     * as it waits, and the pool opens the connections it lacks one at a time on one thread, each of which the product gives one second to be there
     * ({@code DataSourceFactory#pool}, the connection timeout): on a computer that is busy with other work a request that needs a connection the pool has not opened yet is answered
     * "no connection" at once and never comes to wait, which no wait of the database can show. The connections are borrowed together and given back, so that they are open and idle when the
     * requests arrive; a borrow that the pool could not answer in its second is made again (the pool goes on opening in between) until the deadline.
     */
    static void warm(RunningServer server, int connections) throws SQLException {
        DataSource serverPool = server.context.getBean("store", DataSource.class);
        long deadline = System.nanoTime() + LIMIT.toNanos();
        while (true) {
            List<Connection> borrowed = new ArrayList<>();
            try {
                for (int index = 0; index < connections; index += 1) {
                    borrowed.add(serverPool.getConnection());
                }
                return;
            } catch (SQLTransientConnectionException notYet) {
                assertTrue(System.nanoTime() < deadline, "the pool of the server did not open " + connections + " connections: " + notYet.getMessage());
            } finally {
                for (Connection connection : borrowed) {
                    connection.close();
                }
            }
        }
    }

    /**
     * Waits, on the condition, until at least {@code count} statements wait for what this holder has. {@code calls} are the requests the test expects to wait: one that is answered while
     * the holder still has the lock did not wait, and the failure says what it was answered (see {@link #await}).
     */
    void awaitBlocked(int count, String failure, Calls<?>... calls) throws SQLException {
        await("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND " + backend + " = ANY(pg_blocking_pids(pid))", count, failure, calls);
    }

    /**
     * Waits until at least {@code count} statements wait for a statement that itself waits for what this holder has: the requests that meet behind the one that holds a row it
     * has inserted and not committed, while that one is held by this lock.
     */
    void awaitQueuedBehindBlocked(int count, String failure, Calls<?>... calls) throws SQLException {
        await("SELECT count(*) FROM pg_stat_activity queued WHERE queued.datname = current_database() AND EXISTS (SELECT 1 FROM pg_stat_activity ahead "
                + "WHERE ahead.pid = ANY(pg_blocking_pids(queued.pid)) AND " + backend + " = ANY(pg_blocking_pids(ahead.pid)))", count, failure, calls);
    }

    /**
     * A request that the test expects to wait and that has been answered while the lock is still held did not wait: the database refused it at once or the server did not come to the
     * statement (no connection in its pool, a failure on the way). Waiting longer cannot make it wait, so the test fails at that look, with the number of waits seen and what each request
     * was answered, instead of at the deadline with nothing but the number.
     */
    private static void await(String sql, int count, String failure, Calls<?>[] calls) throws SQLException {
        long deadline = System.nanoTime() + LIMIT.toNanos();
        long seen;
        while ((seen = observed(sql)) < count) {
            long waits = seen;
            for (Calls<?> started : calls) {
                assertTrue(!started.anyAnswered(), () -> failure + " (waits seen: " + waits + " of " + count + "; a request was answered before the lock was released: " + started.describe() + ")");
            }
            assertTrue(System.nanoTime() < deadline, () -> failure + " (waits seen: " + waits + " of " + count + "; the requests: " + java.util.Arrays.stream(calls).map(Calls::describe).toList() + ")");
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    /** Each look is made on a connection of its own, which is neither the holder's nor a request's. */
    private static long observed(String sql) throws SQLException {
        try (Connection observer = BusinessDb.connect(); PreparedStatement statement = observer.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    /** Commits what the holder did and lets the waiting statements go on. */
    void release() throws SQLException {
        if (open) {
            open = false;
            try {
                holder.commit();
            } finally {
                holder.close();
            }
        }
    }

    /** A holder not released by the test (it failed before) ends with its transaction rolled back. */
    @Override
    public void close() throws SQLException {
        if (open) {
            open = false;
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
        }
    }

    /** The requests of a test, each started on a thread of its own at once; the answers are taken after {@link HookRaceLocks#release}. */
    static final class Calls<T> implements AutoCloseable {
        private final ExecutorService pool;
        private final List<Future<T>> futures = new ArrayList<>();

        private Calls(List<Callable<T>> calls) {
            this.pool = Executors.newFixedThreadPool(calls.size());
            calls.forEach(call -> futures.add(pool.submit(call)));
        }

        static <T> Calls<T> start(List<Callable<T>> calls) {
            return new Calls<>(calls);
        }

        /** The answers in the order of the calls; a call that has not answered by the deadline fails the test. */
        List<T> answers() throws Exception {
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get(LIMIT.toSeconds(), TimeUnit.SECONDS));
            }
            return answers;
        }

        /** Whether a request has been answered (or has ended with an exception) already. */
        boolean anyAnswered() {
            return futures.stream().anyMatch(Future::isDone);
        }

        /** What each request is now: not answered, its answer (status and text of an HTTP answer) or its exception. */
        String describe() {
            List<String> states = new ArrayList<>();
            for (Future<T> future : futures) {
                if (!future.isDone()) {
                    states.add("waiting");
                    continue;
                }
                try {
                    T answer = future.get();
                    states.add(answer instanceof RawHttp.Response response ? response.status() + " " + response.text() : String.valueOf(answer));
                } catch (ExecutionException failure) {
                    states.add(String.valueOf(failure.getCause()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    states.add("interrupted");
                }
            }
            return states.toString();
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }
}
