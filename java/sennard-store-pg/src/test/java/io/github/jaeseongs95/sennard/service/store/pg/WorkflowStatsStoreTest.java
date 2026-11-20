// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStatsStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;

/**
 * The workflow statistics counted by the database (V22), on PostgreSQL: the kind of a run is a column of workflow_runs and the skills its start declared are rows written with the run, and the statistics are
 * {@code GROUP BY} statements over them. Cases (written first):
 * <pre>
 * t1  by kind       the three kinds and a run made before the column (an old run is given CHANGE by the column's default) are counted under their kind, and the kinds with no start are absent
 * t2  skills        the skills are counted per kind and once per run (a skill named twice by a start counts once), most declared first and then by name; the runs without skills
 *                   (an answer, an investigation) are counted apart by kind, and a skill of one kind is never added to another
 * t3  period        a run at the very start of the period is in it, one millisecond before is not, in the counts and in the newest list
 * t4  newest        of 55 runs the 50 newest come, newest first (equal times by run ID, descending), each with its goal, host, kind, state and skills; the counts still cover the 55
 * t5  atomic        a start whose skill row fails (a skill the column cannot hold) or whose kind is null leaves no run, no skill row, and its lease unconsumed
 * t6  cleanup       a cleanup that deletes a run deletes its skill rows (and backs them up first)
 * t7  backfill      a database that ran the scripts up to V21 with a run in it has that run counted as a CHANGE with no skills after V22
 * </pre>
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowStatsStoreTest {
    private static final Instant NOW = Instant.parse("2026-02-10T00:00:00.000Z");
    /** The start of a period of seven days that ends at {@link #NOW}. */
    private static final String SINCE = "2026-02-03T00:00:00.000Z";
    private static final String BEFORE_SINCE = "2026-02-02T23:59:59.999Z";
    private static final String MIDDLE = "2026-02-06T00:00:00.000Z";

    private TestStores store;
    private WorkflowStore workflow;
    private WorkflowStatsStore stats;

    @BeforeAll
    void start() {
        store = TestStores.of(BusinessDb.pool());
        workflow = store.repository(WorkflowStore.class);
        stats = store.repository(WorkflowStatsStore.class);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private static WorkflowStore.RootRow root(String rootId, long revision, RootState state, String at, String objective) {
        String json = "{\"rootId\":\"" + rootId + "\",\"revision\":" + revision + ",\"state\":\"" + state + "\",\"updatedAt\":\"" + at + "\",\"taskEnvelope\":{\"objective\":\"" + objective + "\"}}";
        return new WorkflowStore.RootRow(rootId, revision, state, "ws-1", "/w", json, at, at, 1, "sha256:frame");
    }

    private static WorkflowStore.LeaseRow lease(String leaseId, String rootId, String at, String actor) {
        String json = "{\"leaseId\":\"" + leaseId + "\",\"state\":\"" + LeaseState.ISSUED + "\"" + (actor == null ? "" : ",\"actorId\":\"" + actor + "\"") + "}";
        return new WorkflowStore.LeaseRow(leaseId, rootId, 2, 1, 1, LeaseState.ISSUED, json, "{\"proposal\":\"" + leaseId + "\"}", at, at);
    }

    private static WorkflowStore.RunRow run(String id) {
        return run(id, 1, RunState.RUNNING);
    }

    private static WorkflowStore.RunRow run(String id, long revision, RunState state) {
        return new WorkflowStore.RunRow(id, revision, state, "{\"runId\":\"" + id + "\",\"revision\":" + revision + "}");
    }

    /** The root and its issued lease, which a guarded start consumes (the longer path of the product binds a run to both). */
    private void openRootAndLease(String id, String at, String objective, String actor) {
        String rootId = "root-" + id;
        WorkflowStore.IdentityRow identity = new WorkflowStore.IdentityRow(rootId, "{\"root\":\"" + rootId + "\"}", "surface-" + rootId);
        store.write(workflow, () -> workflow.insertConvergenceRoot(root(rootId, 1, RootState.OPEN, at, objective), null, active -> new WorkflowStore.RootInsertionPlan<>(null, List.of(), identity, "surface-" + rootId)));
        assertTrue(store.write(workflow, () -> workflow.insertAttemptLease(root(rootId, 2, RootState.OPEN, at, objective), 1, lease("lease-" + id, rootId, at, actor))));
    }

    /** One start as the service makes it: the run, its kind and its skills in one transaction, bound to a root and a lease. */
    private void started(String id, String kind, String at, List<String> skills) {
        openRootAndLease(id, at, "goal " + id, "codex:session-" + id);
        assertTrue(store.write(workflow, () -> workflow.insertGuardedRun(run(id), "lease-" + id, 2, at, kind, skills)) != null);
    }

    private WorkflowStatsStore.Stats stats(int days, int recentLimit) {
        return store.snapshot(() -> stats.stats(days, NOW, recentLimit));
    }

    private static int started(WorkflowStatsStore.Stats counted, String kind) {
        return counted.kinds().stream().filter(entry -> entry.kind().equals(kind)).mapToInt(WorkflowStatsStore.KindTotal::started).sum();
    }

    private static int withoutSkills(WorkflowStatsStore.Stats counted, String kind) {
        return counted.kinds().stream().filter(entry -> entry.kind().equals(kind)).mapToInt(WorkflowStatsStore.KindTotal::withoutSkills).sum();
    }

    private static List<String> skillsOf(WorkflowStatsStore.Stats counted, String kind) {
        return counted.skills().stream().filter(entry -> entry.kind().equals(kind)).map(entry -> entry.skillId() + ":" + entry.workflows()).toList();
    }

    private long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------- cases

    @Test
    void t1_runsAreCountedUnderTheKindTheyWereStartedAs() {
        started("a1", "INQUIRY", MIDDLE, List.of());
        started("a2", "INQUIRY", MIDDLE, List.of());
        started("o1", "OPERATION", MIDDLE, List.of("x"));
        started("c1", "CHANGE", MIDDLE, List.of("x"));
        // A run an earlier build made, as V22 leaves it: the column holds CHANGE, its default, and there is no skill row.
        store.write(workflow, () -> {
            workflow.insertRun(run("old"), MIDDLE, "CHANGE", List.of());
            return null;
        });
        WorkflowStatsStore.Stats counted = stats(7, 50);
        assertEquals(2, started(counted, "INQUIRY"));
        assertEquals(1, started(counted, "OPERATION"));
        assertEquals(2, started(counted, "CHANGE"));
        assertEquals(3, counted.kinds().size(), "only the kinds that had a start");
        assertEquals(0, started(stats(7, 50), "NOT_A_KIND"));
    }

    @Test
    void t2_skillsAreCountedPerKindOncePerRunAndRunsWithoutSkillsApart() {
        started("a1", "INQUIRY", MIDDLE, List.of());
        started("a2", "INQUIRY", MIDDLE, List.of());
        started("o1", "OPERATION", MIDDLE, List.of("a", "b"));
        started("c1", "CHANGE", MIDDLE, List.of("a"));
        started("c2", "CHANGE", MIDDLE, List.of("b", "a", "b"));
        started("c3", "CHANGE", MIDDLE, List.of("c"));
        store.write(workflow, () -> {
            workflow.insertRun(run("old"), MIDDLE, "CHANGE", List.of());
            return null;
        });
        WorkflowStatsStore.Stats counted = stats(7, 50);
        assertEquals(List.of("a:2", "b:1", "c:1"), skillsOf(counted, "CHANGE"), "most declared first, then by name; b twice in one start counts once");
        assertEquals(List.of("a:1", "b:1"), skillsOf(counted, "OPERATION"));
        assertEquals(List.of(), skillsOf(counted, "INQUIRY"));
        assertEquals(2, withoutSkills(counted, "INQUIRY"));
        assertEquals(1, withoutSkills(counted, "CHANGE"), "the old run declared none");
        assertEquals(0, withoutSkills(counted, "OPERATION"));
    }

    @Test
    void t3_aRunAtTheStartOfThePeriodIsInItAndOneMillisecondBeforeIsNot() {
        started("in", "CHANGE", SINCE, List.of("x"));
        started("out", "CHANGE", BEFORE_SINCE, List.of("x"));
        WorkflowStatsStore.Stats counted = stats(7, 50);
        assertEquals(1, started(counted, "CHANGE"));
        assertEquals(List.of("x:1"), skillsOf(counted, "CHANGE"));
        assertEquals(List.of("in"), counted.newest().stream().map(WorkflowStatsStore.Run::runId).toList());
        assertEquals(0, started(stats(1, 50), "CHANGE"), "the period of a day, which starts at 2026-02-09");
    }

    @Test
    void t4_theNewestListHasTheLimitNewestRunsWithTheirDocumentsAndTheCountsCoverAll() {
        for (int i = 0; i < 55; i++) {
            String id = String.format("run-%02d", i);
            started(id, "INQUIRY", String.format("2026-02-05T00:%02d:00.000Z", i), i % 2 == 0 ? List.of("s") : List.of());
        }
        // Two runs at one moment are ordered by their ID, the later ID first.
        started("tie-a", "OPERATION", "2026-02-09T00:00:00.000Z", List.of("y", "x"));
        started("tie-b", "OPERATION", "2026-02-09T00:00:00.000Z", List.of());
        WorkflowStatsStore.Stats counted = stats(7, 50);
        assertEquals(55, started(counted, "INQUIRY"), "the counts cover every run of the period, not the newest list");
        assertEquals(2, started(counted, "OPERATION"));
        assertEquals(50, counted.newest().size());
        assertEquals(List.of("tie-b", "tie-a", "run-54", "run-53"), counted.newest().subList(0, 4).stream().map(WorkflowStatsStore.Run::runId).toList());
        assertEquals("run-07", counted.newest().getLast().runId(), "the 50th is run-07: the two runs of one moment and run-54 down to run-07 (48 runs)");
        WorkflowStatsStore.Run tie = counted.newest().get(1);
        assertEquals("OPERATION", tie.kind());
        assertEquals("RUNNING", tie.state());
        assertEquals("goal tie-a", tie.goal());
        assertEquals("codex", tie.host());
        assertEquals("2026-02-09T00:00:00.000Z", tie.startedAt());
        assertEquals(List.of("x", "y"), tie.skills(), "a run's skills are in alphabetical order");
        assertEquals(List.of("s"), counted.newest().get(2).skills(), "run-54 declared s");
        assertEquals(List.of(), counted.newest().get(3).skills(), "run-53 declared none");
        assertEquals(3, stats(7, 3).newest().size());
    }

    @Test
    void t5_aStartThatFailsAfterItsRunWasInsertedLeavesNeitherRunNorKindNorSkillsNorAConsumedLease() throws Exception {
        openRootAndLease("bad", MIDDLE, "goal bad", null);
        String tooLong = "s".repeat(256); // the column holds 255 characters: the skill row is the statement that fails, after the run was written
        assertThrows(RuntimeException.class, () -> store.write(workflow, () -> workflow.insertGuardedRun(run("bad"), "lease-bad", 2, MIDDLE, "CHANGE", List.of("ok", tooLong))));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_runs"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.convergence_leases WHERE lease_id = 'lease-bad' AND state = '" + LeaseState.ISSUED + "'"), "the lease was not consumed");
        assertThrows(RuntimeException.class, () -> store.write(workflow, () -> workflow.insertGuardedRun(run("bad"), "lease-bad", 2, MIDDLE, null, List.of("ok"))), "a run has a kind");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_runs"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
        assertTrue(store.write(workflow, () -> workflow.insertGuardedRun(run("bad"), "lease-bad", 2, MIDDLE, "CHANGE", List.of("ok"))) != null, "the same start succeeds when it is whole");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.workflow_runs WHERE run_id = 'bad' AND kind = 'CHANGE'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
    }

    @Test
    void t6_aCleanupThatDeletesARunDeletesItsSkillRows() throws Exception {
        started("gone", "CHANGE", MIDDLE, List.of("x", "y"));
        store.write(workflow, () -> {
            workflow.insertRun(run("alone", 1, RunState.PASSED), MIDDLE, "INQUIRY", List.of("z"));
            return null;
        });
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.workflow_runs"));
        assertEquals(3, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
        // The run finishes and its root completes (the root is at revision 3 after the start), so both are old enough for a cleanup.
        assertTrue((boolean) store.write(workflow, () -> workflow.updateRun(run("gone", 2, RunState.PASSED), 1, root("root-gone", 4, RootState.COMPLETED, MIDDLE, "goal gone"), 3L,
                new WorkflowStore.Outcome(AttemptState.PASSED, "{}", MIDDLE), MIDDLE)));
        WorkflowStore.CleanupPreview preview = store.write(workflow, () -> workflow.previewCleanup("2026-03-01T00:00:00.000Z"));
        assertTrue((boolean) store.write(workflow, () -> workflow.claimCleanupPlan("plan-1", "digest", "2026-04-01T00:00:00.000Z")));
        WorkflowStore.CleanupResult result = store.write(workflow, () -> workflow.executeCleanup(preview, "plan-1", "2026-04-01T00:00:00.000Z"));
        assertEquals(new WorkflowStore.CleanupResult(1, 2), result);
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_runs"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
        assertEquals(3, ownerLong("SELECT count(*) FROM sennard.workflow_cleanup_backup WHERE plan_id = 'plan-1' AND table_name = 'workflow_run_skills'"), "the skill rows were kept in the backup first");
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.workflow_cleanup_backup WHERE plan_id = 'plan-1' AND table_name = 'workflow_runs'"), "the runs, with their kind, were kept in the backup first");
    }

    @Test
    void t7_aRunThatWasThereBeforeV22IsCountedAsAChangeWithNoSkills() throws Exception {
        BusinessDb.dropBusinessSchemas();
        SennardDatabaseConfig config = BusinessDb.config();
        MigrationRunner runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
        List<MigrationScript> all = MigrationScript.load("db/expand");
        assertTrue(all.stream().anyMatch(script -> script.version() == 22), "V22 is a script; this case is about it");
        assertEquals(21, runner.run(config, MigrationRunner.Phase.EXPAND, all.stream().filter(script -> script.version() <= 21).toList()));
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            s.execute("INSERT INTO sennard.workflow_runs (run_id, revision, state, receipt_json, created_at, updated_at) VALUES ('before', 1, 'PASSED', '{}', TIMESTAMP '2026-02-06 00:00:00', TIMESTAMP '2026-02-06 00:00:00')");
        }
        assertEquals(1, runner.run(config, MigrationRunner.Phase.EXPAND, all.stream().filter(script -> script.version() <= 22).toList()), "only V22 is applied: the ALTER TABLE adds the column to the table that has the run");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.workflow_runs WHERE run_id = 'before' AND kind = 'CHANGE' AND created_at = TIMESTAMP '2026-02-06 00:00:00'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.workflow_run_skills"));
        assertEquals(9, ownerLong("SELECT version FROM sennard.module_schema WHERE module = 'workflow'"));
        WorkflowStatsStore.Stats counted = stats(7, 50);
        assertEquals(1, started(counted, "CHANGE"));
        assertEquals(1, withoutSkills(counted, "CHANGE"));
        assertEquals(List.of(), counted.skills());
    }
}
