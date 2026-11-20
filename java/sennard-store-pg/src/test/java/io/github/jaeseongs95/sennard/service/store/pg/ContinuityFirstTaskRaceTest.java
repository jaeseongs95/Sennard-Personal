// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;

/**
 * The first creation of a session's continuity task. The service method that first needs the task reads it and, when there is none, inserts it in its own transaction
 * ({@link ContinuityStore#ensureTask}); nothing serializes two first requests of one session. One transaction has inserted the task and has not committed; a second one finds no task
 * either, and its {@code INSERT} waits for the first to end and then meets the first one's primary key. The second ends in the translated unique violation (the limit that is accepted):
 * nothing catches it to go on and nothing runs the request again. The tables hold one task and one current memory, as the first request made them.
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContinuityFirstTaskRaceTest {
    private static final String T0 = "2026-01-01T00:00:00.000Z";
    private static final String T1 = "2026-01-02T00:00:00.000Z";
    private static final String SESSION = "first-task-race";

    private TestStores store;

    @BeforeAll
    void start() {
        store = TestStores.of(BusinessDb.pool());
    }

    @Test
    void ofTwoFirstRequestsOfOneSessionTheSecondEndsInTheUniqueViolationAndOneTaskIsLeft() throws Exception {
        ContinuityStore continuity = store.repository(ContinuityStore.class);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the first request's connection, taken inside its transaction
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ContinuityStore.Task> first = pool.submit(() -> store.plain(() -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(BusinessDb.pool()));
                ContinuityStore.Task made = continuity.ensureTask(SESSION, T0);
                holding.countDown();
                awaitRelease(release);
                return made;
            }));
            assertTrue(holding.await(20, TimeUnit.SECONDS), "the first request has made the task and holds it");
            Future<ContinuityStore.Task> second = pool.submit(() -> store.plain(() -> continuity.ensureTask(SESSION, T1)));
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(20), "the second request waits for the task of the first");
            assertFalse(second.isDone(), "the second request waits for the first one's commit");
            release.countDown();
            assertEquals(1L, first.get(30, TimeUnit.SECONDS).currentEpoch());
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS));
            assertEquals(StoreCode.STORE_UNIQUE_VIOLATION, assertInstanceOf(StoreException.class, lost.getCause()).code());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = '" + SESSION + "'"), "one task");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.continuity_current_memory WHERE task_correlation = '" + SESSION + "'"), "one current memory");
        assertEquals(1L, store.plain(() -> continuity.ensureTask(SESSION, T1)).currentEpoch(), "a later request finds the task and makes nothing");
    }

    private static long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(30, TimeUnit.SECONDS), "the test released the holder");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
