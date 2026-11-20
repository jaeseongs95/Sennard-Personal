// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;

/**
 * A writer commits a new plan and event between two reads of {@code changes}. No snapshot isolation is asked for (the isolation is the edition's setting): the use case reads the
 * events before the plan revisions, so an event never names a revision it lacks.
 */
@BusinessDbTest
class JournalSnapshotTest {
    @Test
    void changesNeverMixAnOldPlanWithANewEvent() throws Exception {
        try (RewrittenAreasContext context = RewrittenAreasContext.appConnection(Instant.parse("2026-03-04T05:06:07.089Z"));
                Connection writer = BusinessDb.connect(); Connection observer = BusinessDb.connect()) {
            var journal = context.bean(ChangeJournalService.class);
            journal.submitPlan("snapshot", Set.of());
            writer.setAutoCommit(false);
            long writerBackend = LockWaits.backendOf(writer); // the holder's identifier: on PostgreSQL only a wait this writer blocks counts as the reader's
            var executor = Executors.newSingleThreadExecutor();
            try (Statement sql = writer.createStatement(); Statement observing = observer.createStatement()) {
                // The writer holds a lock on observed_file that a read of it waits for: the table lock lasts until the writer's transaction ends, and the writer's own
                // statements go on in it. The observer's connection is the one that looks for the waiting reader.
                sql.execute("LOCK TABLE sennard.observed_file IN ACCESS EXCLUSIVE MODE");
                var reading = executor.submit(() -> journal.changes("snapshot"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !blocked) {
                    try (ResultSet rows = observing.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = CAST('sennard.observed_file' AS regclass) AND NOT granted AND "
                            + writerBackend + " = ANY(pg_blocking_pids(pid)))")) {
                        rows.next();
                        blocked = rows.getBoolean(1);
                    }
                    if (!blocked) LockWaits.pause();
                }
                assertTrue(blocked, "reader must reach observed_file after loading the plan");
                sql.executeUpdate("INSERT INTO sennard.plan_revision (plan_revision_id, workflow_id, revision, submitted_at, version) "
                        + "VALUES ('10000000-0000-0000-0000-000000000001', 'snapshot', 2, TIMESTAMP '2026-01-01 00:00:00', 0)");
                sql.executeUpdate("INSERT INTO sennard.change_event (change_event_id, workflow_id, plan_revision, path, path_hash, recorded_at, source, version) "
                        + "VALUES ('20000000-0000-0000-0000-000000000002', 'snapshot', 2, 'file', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', TIMESTAMP '2026-01-01 00:00:00', 'HOOK', 0)");
                writer.commit();
                var seen = reading.get(15, TimeUnit.SECONDS);
                assertTrue(seen.events().isEmpty(), "the read began before the event and the revision were committed");
                assertEquals(2, journal.plan("snapshot").revision());
                var later = journal.changes("snapshot");
                assertEquals(2, later.events().getFirst().planRevision());
            } finally {
                writer.rollback();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }
}
