// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;

/**
 * The order in which the writes of the roots, the leases and the write surfaces take their rows (AGS D-0400), on two transactions of two connections that the test makes meet at the place
 * where they once crossed. The rule is {@code WorkflowStore#takeRootRows}: the identities, then the root rows in root id order (the caller's own root among them), then the rows of the
 * surfaces; the lease rows of a root after its own root row.
 *
 * <p>Nothing here waits for a time and nothing is left to who arrives first. A transaction of the test (the gate) holds one row of the database and does not commit; the transaction that
 * the test starts first runs into it, and the test looks at the database until it says that transaction waits ({@link LockWaits}); only then the second one starts, and the test looks
 * again, now for the second one waiting behind the first (it waits for the row the first one took before the gate: the rule's order). A second transaction that took its rows in another
 * order would wait for the gate or hold what the first one wants next, so the look for its wait behind the first one is never satisfied (a deadline only a failure reaches) or the two end
 * in the database's deadlock. Then the gate lets go and what both ended in is asserted, one answer for each. The database is PostgreSQL (the waits are read from its catalog).
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowSurfaceLockOrderTest {
    private static final String T0 = "2026-01-01T00:00:00.000Z";
    private static final String T1 = "2026-01-02T00:00:00.000Z";
    private static final String SHARED = "surface-shared";
    private static final Duration LOOK = Duration.ofSeconds(30);

    private TestStores store;
    private WorkflowStore w;

    @BeforeAll
    void start() {
        store = TestStores.of(BusinessDb.pool());
        w = store.repository(WorkflowStore.class);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private static WorkflowStore.RootRow root(String id, long revision, RootState state) {
        return new WorkflowStore.RootRow(id, revision, state, "ws-1", "/w", "{\"rootId\":\"" + id + "\",\"revision\":" + revision + ",\"state\":\"" + state + "\",\"updatedAt\":\"" + T0 + "\"}", T0, T0, 1, "sha256:frame");
    }

    private static WorkflowStore.IdentityRow identity(String id) {
        return new WorkflowStore.IdentityRow(id, "{\"root\":\"" + id + "\"}", "surface-" + id);
    }

    private static WorkflowStore.RootInsertionPlan<String> plan(String id, String surface, WorkflowStore.Yield... giving) {
        return new WorkflowStore.RootInsertionPlan<>(null, List.of(), identity(id), surface, List.of(giving));
    }

    private static WorkflowStore.LeaseRow lease(String id, String root, long revision, long ordinal) {
        return new WorkflowStore.LeaseRow(id, root, revision, 1, ordinal, LeaseState.ISSUED, "{\"leaseId\":\"" + id + "\",\"state\":\"" + LeaseState.ISSUED + "\"}", "{\"proposal\":\"" + id + "\"}", T0, T1);
    }

    private static WorkflowStore.RunRow run(String id) {
        return new WorkflowStore.RunRow(id, 1, RunState.RUNNING, "{\"runId\":\"" + id + "\",\"revision\":1}");
    }

    /** An active root that holds the shared surface: inserted, with the row of the surface under its own key. */
    private void insertHolding(String id, String surface) {
        store.plain(() -> w.insertConvergenceRoot(root(id, 1, RootState.OPEN), null, active -> plan(id, surface)));
    }

    /**
     * An active root that holds no row (revision 2) and a free {@link #SHARED} surface: the root gave its row up to a root that has ended since. This is the root whose owner takes
     * the surface back with its next claim.
     */
    private void insertIdleWithoutRow(String id) {
        insertHolding(id, SHARED);
        String taker = "taker-of-" + id;
        store.plain(() -> w.insertConvergenceRoot(root(taker, 1, RootState.OPEN), null, active -> plan(taker, SHARED, new WorkflowStore.Yield(id, 1))));
        assertTrue((boolean) store.plain(() -> w.updateConvergenceRoot(root(taker, 2, RootState.COMPLETED), 1, 1, null)), "the taker has ended");
        assertEquals(2, revisionOf(id));
        assertEquals(0, count("convergence_active_surfaces", "root_id = '" + id + "'"));
        assertEquals(0, count("convergence_active_surfaces", "active_surface = '" + SHARED + "'"));
    }

    private long count(String table, String where) {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); java.sql.ResultSet rows = s.executeQuery("SELECT count(*) FROM sennard." + table + " WHERE " + where)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long revisionOf(String rootId) {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); java.sql.ResultSet rows = s.executeQuery("SELECT revision FROM sennard.convergence_roots WHERE root_id = '" + rootId + "'")) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** One transaction of the test's own that has done a write and does not commit until {@link #release}: the row it wrote is the one a transaction of the product waits for. */
    private static final class Gate implements AutoCloseable {
        private final Connection connection;
        private final long backend;

        Gate(String... statements) throws SQLException {
            connection = BusinessDb.connect();
            try {
                connection.setAutoCommit(false);
                backend = LockWaits.backendOf(connection);
                try (Statement s = connection.createStatement()) {
                    for (String statement : statements) {
                        s.execute(statement);
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                connection.close();
                throw failure;
            }
        }

        long backend() {
            return backend;
        }

        /** Rolls back what the gate wrote and lets the waiting transactions go on. */
        void release() throws SQLException {
            if (!connection.isClosed()) {
                try {
                    connection.rollback();
                } finally {
                    connection.close();
                }
            }
        }

        @Override
        public void close() throws SQLException {
            release();
        }
    }

    /** Whether a transaction of another connection holds the row of the root: a lock that does not wait answers at once, so the probe changes nothing and waits for nothing. */
    private boolean rowIsHeldByAnother(String rootId) throws SQLException {
        try (Connection probe = BusinessDb.connect()) {
            probe.setAutoCommit(false);
            try (Statement s = probe.createStatement()) {
                s.execute("SELECT 1 FROM sennard.convergence_roots WHERE root_id = '" + rootId + "' FOR UPDATE NOWAIT");
                return false;
            } catch (SQLException busy) {
                assertEquals("55P03", busy.getSQLState(), "the probe was refused for another reason: " + busy.getMessage());
                return true;
            } finally {
                probe.rollback();
            }
        }
    }

    /** Whether a transaction of another connection holds the row of the surface of the root (a deleted or locked row): the same probe as {@link #rowIsHeldByAnother}, on the surface table. */
    private boolean surfaceRowIsHeldByAnother(String rootId) throws SQLException {
        try (Connection probe = BusinessDb.connect()) {
            probe.setAutoCommit(false);
            try (Statement s = probe.createStatement()) {
                s.execute("SELECT 1 FROM sennard.convergence_active_surfaces WHERE root_id = '" + rootId + "' FOR UPDATE NOWAIT");
                return false;
            } catch (SQLException busy) {
                assertEquals("55P03", busy.getSQLState(), "the probe was refused for another reason: " + busy.getMessage());
                return true;
            } finally {
                probe.rollback();
            }
        }
    }

    private static <T> T answer(Future<T> run) throws Exception {
        return run.get(60, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> run) throws Exception {
        return assertThrows(ExecutionException.class, () -> run.get(60, TimeUnit.SECONDS)).getCause();
    }

    // -------------------------------------------------------------------------------------------------------------------- cases

    /**
     * The crossing of D-0400. A new root that takes the files of an idle root writes that root's row and then the surface row; the owner of the idle root, which holds no row of the
     * surface, takes the surface and writes its own root. The new root is held at the surface row by the gate (it holds the idle root's row). The claim that follows must wait for the
     * idle root's row, which is its own root's, and must not have taken the surface row first (it would then wait for the gate with the new root waiting for it). When the gate lets go the
     * new root commits and the claim finds its root moved: nothing is taken, and the call ends the way the service ends it (it throws, so the transaction rolls back).
     */
    @Test
    void aClaimWaitsForItsOwnRootRowBeforeItTakesTheSurfaceRowOfANewRootThatHoldsThatRow() throws Exception {
        insertIdleWithoutRow("idle");
        store.plain(() -> w.insertConvergenceRoot(root("ghost", 1, RootState.COMPLETED), null, active -> plan("ghost", "surface-ghost")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Gate surface = new Gate("INSERT INTO sennard.convergence_active_surfaces (active_surface, root_id) VALUES ('" + SHARED + "', 'ghost')")) {
            Future<String> newRoot = pool.submit(() -> store.plain(() -> w.insertConvergenceRoot(root("newcomer", 1, RootState.OPEN), null, active -> plan("newcomer", SHARED, new WorkflowStore.Yield("idle", 2)))));
            long newRootBackend = LockWaits.awaitWaiterBackendBlockedBy(surface.backend(), LOOK, "the new root did not come to wait for the surface row of the gate");
            assertTrue(rowIsHeldByAnother("idle"), "the new root holds the row of the idle root it takes the surface from while it waits for the surface row");
            Future<WorkflowStore.SurfaceTake<String>> claim = pool.submit(() -> store.plain(() -> {
                WorkflowStore.SurfaceTake<String> take = w.holdSurface("idle", 2, active -> plan("idle", SHARED), T1);
                if (!take.rootAtRevision()) {
                    throw new IllegalStateException("root moved");
                }
                return take;
            }));
            LockWaits.awaitWaiterBlockedBy(newRootBackend, LOOK, "the claim did not wait for the row of its own root, which the new root holds: it took another row first");
            surface.release();
            assertNull(answer(newRoot), "the new root was inserted");
            assertEquals("root moved", failureOf(claim).getMessage(), "the claim found its root moved by the new root's take");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("convergence_active_surfaces", "root_id = 'newcomer' AND active_surface = '" + SHARED + "'"), "the new root holds the surface");
        assertEquals(0, count("convergence_active_surfaces", "root_id = 'idle'"), "the claim took nothing");
        assertEquals(3, revisionOf("idle"), "the idle root was written once, by the new root's take");
    }

    /** Two idle roots (a, c) and the claiming root b between them, all on the shared surface; b holds no row. */
    private void rootsAbcWithBAskingForTheSurface() {
        insertHolding("root-a", "surface-a");
        insertHolding("root-c", "surface-c");
        insertIdleWithoutRow("root-b");
    }

    private static WorkflowStore.SurfaceTake<String> holdAsRootB(WorkflowStore w) {
        WorkflowStore.SurfaceTake<String> take = w.holdSurface("root-b", 2, active -> plan("root-b", SHARED, new WorkflowStore.Yield("root-c", 1), new WorkflowStore.Yield("root-a", 1)), T1);
        assertTrue(take.rootAtRevision());
        return take;
    }

    /**
     * The root rows are taken in root id order, the claiming root's own among them, whatever order the plan lists them in (it lists c before a). With the gate on the largest row (c) the
     * write holds a and b while it waits: the roots before it in the order.
     */
    @Test
    void aSurfaceWriteHoldsTheRootRowsBeforeTheOneItWaitsForInRootIdOrder() throws Exception {
        rootsAbcWithBAskingForTheSurface();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Gate largest = new Gate("UPDATE sennard.convergence_roots SET revision = revision WHERE root_id = 'root-c'")) {
            Future<WorkflowStore.SurfaceTake<String>> write = pool.submit(() -> store.plain(() -> holdAsRootB(w)));
            LockWaits.awaitWaiterBackendBlockedBy(largest.backend(), LOOK, "the write did not come to wait for the row of root-c");
            assertTrue(rowIsHeldByAnother("root-a"), "the write holds root-a, the smallest, before it waits for root-c");
            assertTrue(rowIsHeldByAnother("root-b"), "and its own root-b, between them");
            assertEquals(0, count("convergence_active_surfaces", "root_id = 'root-b'"), "it has taken no surface row yet");
            largest.release();
            assertNotNull(answer(write));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("convergence_active_surfaces", "root_id = 'root-b' AND active_surface = '" + SHARED + "'"));
        assertEquals(0, count("convergence_active_surfaces", "root_id IN ('root-a', 'root-c')"), "the idle roots gave their rows up");
    }

    /**
     * With the gate on the smallest row (a) the write holds nothing while it waits, not even its own root's row: the own root is not taken first, it has its place in the order (b after
     * a). A write that took its own root first would keep every other write that wants root-b out until the gate is gone.
     */
    @Test
    void aSurfaceWriteHoldsNoRowWhileItWaitsForTheSmallestRootRowAndItsOwnRootHasNoPlaceBeforeIt() throws Exception {
        rootsAbcWithBAskingForTheSurface();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Gate smallest = new Gate("UPDATE sennard.convergence_roots SET revision = revision WHERE root_id = 'root-a'")) {
            Future<WorkflowStore.SurfaceTake<String>> write = pool.submit(() -> store.plain(() -> holdAsRootB(w)));
            LockWaits.awaitWaiterBackendBlockedBy(smallest.backend(), LOOK, "the write did not come to wait for the row of root-a");
            assertFalse(rowIsHeldByAnother("root-b"), "the write waits for root-a and holds neither its own root-b nor root-c");
            assertFalse(rowIsHeldByAnother("root-c"));
            smallest.release();
            assertNotNull(answer(write));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("convergence_active_surfaces", "root_id = 'root-b'"));
    }

    /**
     * Two roots of one file that both gave their rows up (x, y) are claimed by their two owners together; each asks the other's root to give its row up. They meet on the smaller root
     * row (x) whichever of them started first: the first claim is held at y by the gate after it took x, the second waits behind it at x. (Each taking its own root first would send them
     * to x and y in opposite orders.) When the gate lets go the first claim commits and the second finds its own root (y) written by the first one's give-up: its call ends the way the
     * service ends it, and the give-up of x that it had made before it saw that rolls back with it.
     */
    @Test
    void twoClaimsOfRootsWithoutRowsThatAskEachOtherToGiveTheSurfaceUpMeetOnTheSmallerRootRow() throws Exception {
        insertHolding("root-x", "surface-x");
        insertHolding("root-y", "surface-y");
        // Both gave their rows up to a root that has ended.
        store.plain(() -> w.insertConvergenceRoot(root("taker", 1, RootState.OPEN), null, active -> plan("taker", "surface-x", new WorkflowStore.Yield("root-x", 1))));
        store.plain(() -> w.updateConvergenceRoot(root("taker", 2, RootState.COMPLETED), 1, 1, null));
        store.plain(() -> w.insertConvergenceRoot(root("taker-y", 1, RootState.OPEN), null, active -> plan("taker-y", "surface-y", new WorkflowStore.Yield("root-y", 1))));
        store.plain(() -> w.updateConvergenceRoot(root("taker-y", 2, RootState.COMPLETED), 1, 1, null));
        assertEquals(0, count("convergence_active_surfaces", "root_id IN ('root-x', 'root-y')"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Gate second = new Gate("UPDATE sennard.convergence_roots SET revision = revision WHERE root_id = 'root-y'")) {
            Future<WorkflowStore.SurfaceTake<String>> claimOfX = pool.submit(() -> store.plain(() -> w.holdSurface("root-x", 2,
                    active -> plan("root-x", SHARED, new WorkflowStore.Yield("root-y", 2)), T1)));
            long claimOfXBackend = LockWaits.awaitWaiterBackendBlockedBy(second.backend(), LOOK, "the claim of root-x did not come to wait for the row of root-y");
            // The database also lists a statement that waits for the same row behind another as blocked by that one, so the wait behind the first claim is not enough to tell the order:
            // the first claim must hold root-x, the smaller row, by now.
            assertTrue(rowIsHeldByAnother("root-x"), "the claim of root-x waits for root-y holding root-x, the smaller row, and not holding nothing");
            Future<WorkflowStore.SurfaceTake<String>> claimOfY = pool.submit(() -> store.plain(() -> {
                WorkflowStore.SurfaceTake<String> take = w.holdSurface("root-y", 2, active -> plan("root-y", SHARED, new WorkflowStore.Yield("root-x", 2)), T1);
                if (!take.rootAtRevision()) {
                    throw new IllegalStateException("root moved");
                }
                return take;
            }));
            LockWaits.awaitWaiterBlockedBy(claimOfXBackend, LOOK, "the claim of root-y did not wait behind the claim of root-x: it did not take the smaller root row first");
            second.release();
            assertTrue(answer(claimOfX).rootAtRevision(), "the first claim took the surface");
            assertEquals("root moved", failureOf(claimOfY).getMessage(), "the second claim found its own root-y written by the first one's give-up");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("convergence_active_surfaces", "root_id = 'root-x' AND active_surface = '" + SHARED + "'"));
        assertEquals(0, count("convergence_active_surfaces", "root_id = 'root-y'"));
        assertEquals(2, revisionOf("root-x"), "the second claim wrote nothing: the transaction that found its root moved rolled back (root-x was not given up)");
    }

    /**
     * The crossing of the claim of the next attempt and the start of the attempt that was claimed before: the start consumes the lease and writes the root, the claim writes the root
     * and expires the lease. Both write the root row first and the lease rows after. The start is held at the lease row by the gate (it holds the root's row); the claim must wait behind
     * it for the root row, and must not have taken the lease row first. When the gate lets go the start runs, and the claim finds the root moved: it issues no lease.
     */
    @Test
    void theStartOfALeaseAndTheClaimOfTheNextAttemptTakeTheRootRowBeforeTheLeaseRows() throws Exception {
        insertHolding("root-1", "surface-1");
        assertTrue((boolean) store.plain(() -> w.insertAttemptLease(root("root-1", 2, RootState.OPEN), 1, lease("lease-1", "root-1", 2, 1))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Gate leaseRow = new Gate("UPDATE sennard.convergence_leases SET state = state WHERE lease_id = 'lease-1'")) {
            Future<WorkflowStore.GuardedBinding> start = pool.submit(() -> store.plain(() -> w.insertGuardedRun(run("run-1"), "lease-1", 2, T1, "CHANGE", List.of())));
            long startBackend = LockWaits.awaitWaiterBackendBlockedBy(leaseRow.backend(), LOOK, "the start did not come to wait for the lease row of the gate");
            assertTrue(rowIsHeldByAnother("root-1"), "the start holds the root row while it waits for the lease row");
            Future<Boolean> claim = pool.submit(() -> store.plain(() -> w.insertAttemptLease(root("root-1", 3, RootState.OPEN), 2, lease("lease-2", "root-1", 3, 2))));
            LockWaits.awaitWaiterBlockedBy(startBackend, LOOK, "the claim did not wait for the root row the start holds: it took the lease row first");
            leaseRow.release();
            assertNotNull(answer(start), "the start consumed the lease");
            assertFalse(answer(claim), "the claim found the root moved by the start and issued nothing");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, count("convergence_leases", "lease_id = 'lease-2'"));
        assertEquals(1, count("convergence_leases", "lease_id = 'lease-1' AND state = 'CONSUMED'"));
        assertEquals(1, count("workflow_runs", "run_id = 'run-1'"));
    }

    /**
     * The parent that a new root replaces is rewritten with the root rows, and its row of the surface ends with the other surface rows after them: the replaced parent's own step takes no
     * surface row. With the gate on the idle root that gives its row up (root-q, which comes after the parent root-p), the write holds root-p while it waits; it must not hold root-p's row
     * of the surface by then, or a transaction that wants that row and holds root-q would wait for this one with this one waiting for it. When the gate lets go the new root is inserted,
     * the parent is abandoned and neither the parent nor the idle root holds a row.
     */
    @Test
    void aReplacedParentsRowOfTheSurfaceEndsAfterEveryRootRowAndNotInTheParentsOwnStep() throws Exception {
        insertHolding("root-p", "surface-p");
        insertHolding("root-q", "surface-q");
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Gate idle = new Gate("UPDATE sennard.convergence_roots SET revision = revision WHERE root_id = 'root-q'")) {
            Future<String> replacing = pool.submit(() -> store.plain(() -> w.insertConvergenceRoot(root("newcomer", 1, RootState.OPEN), "root-p",
                    active -> plan("newcomer", "surface-new", new WorkflowStore.Yield("root-q", 1)))));
            LockWaits.awaitWaiterBackendBlockedBy(idle.backend(), LOOK, "the write did not come to wait for the row of root-q");
            assertTrue(rowIsHeldByAnother("root-p"), "the write holds the replaced parent's root row: it is rewritten before the larger root row it waits for");
            assertFalse(surfaceRowIsHeldByAnother("root-p"), "and has not touched the parent's row of the surface: that row ends after every root row");
            idle.release();
            assertNull(answer(replacing), "the new root was inserted");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, count("convergence_active_surfaces", "root_id IN ('root-p', 'root-q')"), "the parent and the idle root hold no row");
        assertEquals(1, count("convergence_active_surfaces", "root_id = 'newcomer' AND active_surface = 'surface-new'"));
        assertEquals(1, count("convergence_roots", "root_id = 'root-p' AND state = 'ABANDONED'"), "the parent was abandoned");
    }
}
