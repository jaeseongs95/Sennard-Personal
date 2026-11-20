// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * How a test that makes two transactions overlap sees, from the database, that one of them waits for a row lock of the other. The question is always about the holder the test
 * itself made: the backend identifier of the holder's connection (PostgreSQL {@code pg_backend_pid()}) is taken from that connection
 * (a connection the test holds, or the transaction the holder's code runs in), and the wait that counts is one the database says this holder blocks. A wait of some other
 * connection on the same server (another test, another class) blocks on someone else and is not counted, so it can never make a test pass that has not seen its own wait.
 *
 * <p>The waiter's connection is borrowed by the service from the pool and its identifier is not known to the test; that is why the holder is what is named. The observing
 * statement runs on a connection of its own, borrowed from the shared pool for each look, which is neither the holder's nor the waiter's.
 *
 * <p>PostgreSQL reads {@code pg_stat_activity} for the backends whose {@code pg_blocking_pids} name the holder. The loop waits on that condition, not on a clock: the pause between two looks is a short park
 * (the tests run on one CPU, so no spinning), and the deadline is only ever reached by a failure.
 */
final class LockWaits {
    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    private LockWaits() {
    }

    /** The short rest between two looks of a loop that waits for a condition (a park, not a spin: the tests run on one CPU). */
    static void pause() {
        LockSupport.parkNanos(POLL_NANOS);
    }

    /** The backend identifier of a connection the test holds itself (the holder of a lock the test took with it). */
    static long backendOf(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(backendSql())) {
            assertTrue(rows.next(), backendSql());
            return rows.getLong(1);
        }
    }

    /**
     * The backend identifier of the connection the transaction of the calling thread runs on: for a holder whose connection is the pool's, taken inside the transaction that holds
     * the lock (the statement joins the transaction of {@code dataSource}, the one the transaction manager was made with, and runs on its connection). Called outside such a
     * transaction it would answer for some other connection, so it fails instead.
     */
    static long backendOfThisTransaction(DataSource dataSource) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("the holder's backend is taken inside the transaction that holds the lock, on the data source of its transaction manager");
        }
        Long backend = new JdbcTemplate(dataSource).queryForObject(backendSql(), Long.class);
        if (backend == null) {
            throw new IllegalStateException("the database gave no backend identifier: " + backendSql());
        }
        return backend;
    }

    /**
     * Waits, on the condition and not on a clock, until some statement waits for a lock the holder has (a deadline only a failure reaches: then the test fails with
     * {@code failure}). Each look borrows a connection of its own from the shared pool, which is neither the holder's nor the waiter's.
     */
    static void awaitWaiterBlockedBy(long holder, Duration limit, String failure) throws SQLException {
        String sql = waitersSql(holder); // the product is asked once, not at every look
        long deadline = System.nanoTime() + limit.toNanos();
        while (count(sql) == 0) {
            assertTrue(System.nanoTime() < deadline, failure);
            pause();
        }
    }

    /**
     * For a waiter whose connection the test did not make (a runner that opens its own): waits, on the condition and not on a clock, until exactly one statement waits for a lock the
     * holder has, and answers that statement's backend identifier, which the database names. The holder is the test's own connection, so the waiter found is one the holder blocks and
     * no other connection's; two waiters at once fail the test (a deadline only a failure reaches: then it fails with {@code failure}). PostgreSQL only.
     */
    static long awaitWaiterBackendBlockedBy(long holder, Duration limit, String failure) throws SQLException {
        String sql = waiterBackendsSql(holder);
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            List<Long> waiters = backends(sql);
            if (waiters.size() == 1) {
                return waiters.getFirst();
            }
            assertTrue(waiters.isEmpty(), "one waiter was expected behind the holder " + holder + ", the database lists " + waiters);
            assertTrue(System.nanoTime() < deadline, failure);
            pause();
        }
    }

    private static List<Long> backends(String sql) throws SQLException {
        List<Long> found = new ArrayList<>();
        try (Connection observer = BusinessDb.connect(); Statement statement = observer.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(rows.getLong(1));
            }
        }
        return found;
    }

    private static String waiterBackendsSql(long holder) {
        // The holder is a number the database itself gave, so it is written into the statement.
        return "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND " + holder + " = ANY(pg_blocking_pids(pid))";
    }

    private static long count(String sql) throws SQLException {
        try (Connection observer = BusinessDb.connect(); Statement statement = observer.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    private static String backendSql() {
        return "SELECT pg_backend_pid()";
    }

    private static String waitersSql(long holder) {
        // The holder is a number the database itself gave, so it is written into the statement.
        return "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND " + holder + " = ANY(pg_blocking_pids(pid))";
    }
}
