// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.FileDigest;

@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalReviewProbeTest {
    private RewrittenAreasContext context;
    @TempDir Path directory;
    @BeforeAll void start() {
        context = RewrittenAreasContext.appConnection(Instant.parse("2026-03-04T05:06:07.089Z"));
    }
    @AfterAll void close() { if (context != null) context.close(); }
    private JournalFlow journal() { return JournalFlow.of(context); }

    @Test void failedHookMustNotBecomePlannedUnchanged() throws Exception {
        Path file = Files.writeString(directory.resolve("planned.txt"), "before");
        journal().submitPlan("probe", Set.of(file));
        assertTrue(journal().captureBaseline("probe", Set.of(directory)).isEmpty());
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("child"), "not a hook target");
        assertFalse(journal().recordHook("probe", file).isEmpty());
        var changes = journal().changes("probe");
        assertAll(
            () -> assertFalse(changes.failures().isEmpty(), "hook failure must remain visible to later callers"),
            () -> assertFalse(changes.plannedUnchanged().contains(file), "failed observation cannot certify unchanged"),
            () -> assertTrue(changes.events().isEmpty(), "a directory hook must not record its children"));
    }

    @Test void unchangedUnplannedFileMustNotBecomeAChange() throws Exception {
        Path file = Files.writeString(directory.resolve("unplanned.txt"), "unchanged");
        journal().submitPlan("probe", Set.of());
        assertTrue(journal().captureBaseline("probe", Set.of(directory)).isEmpty());
        assertTrue(journal().recordHook("probe", file).isEmpty());
        var changes = journal().changes("probe");
        assertAll(
            () -> assertTrue(changes.events().isEmpty(), "learning an absent baseline hash is not a file edit"),
            () -> assertTrue(changes.unplannedChanges().isEmpty(), "unchanged file must not be an unplanned change"));
        try (var connection = BusinessDb.connect(); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT sha256 FROM sennard.observed_file WHERE workflow_id = 'probe'")) {
            assertTrue(rows.next());
            assertNotNull(rows.getString(1), "the observation retains the newly learned hash");
        }
    }

    @Test void successfulHookClearsOnlyItsOwnFailure() throws Exception {
        Path first = Files.writeString(directory.resolve("first"), "one");
        Path second = Files.writeString(directory.resolve("second"), "two");
        journal().submitPlan("probe", Set.of(first, second));
        assertTrue(journal().captureBaseline("probe", Set.of(directory)).isEmpty());
        Files.delete(first);
        Files.createDirectory(first);
        Files.delete(second);
        Files.createDirectory(second);
        assertFalse(journal().recordHook("probe", first).isEmpty());
        assertFalse(journal().recordHook("probe", second).isEmpty());
        assertEquals(2, journal().changes("probe").failures().size());
        Files.delete(first);
        Files.writeString(first, "restored with new content");
        assertTrue(journal().recordHook("probe", first).isEmpty());
        assertEquals(Set.of(second), journal().changes("probe").failures().stream().map(failure -> failure.path()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(journal().changes("probe").plannedChanges().contains(first));
        assertFalse(journal().changes("probe").plannedUnchanged().contains(second));
    }

    @Test void ioFailureRetainsItsTypedReasonAndCause() throws Exception {
        Path missing = directory.resolve("missing");
        var scan = new JournalScanner(new FileDigest()).scan(java.util.List.of(missing), java.util.Map.of(), Set.of(), false);
        assertEquals(1, scan.failures().size());
        var failure = scan.failures().getFirst();
        assertEquals(JournalScanner.FailureReason.NOT_FOUND, failure.reason());
        assertInstanceOf(java.nio.file.NoSuchFileException.class, failure.cause());
    }

    @Test void schemaRejectsUnknownFailureReason() throws Exception {
        journal().submitPlan("probe", Set.of());
        try (var connection = BusinessDb.connect(); var sql = connection.createStatement()) {
            var error = assertThrows(java.sql.SQLException.class, () -> sql.executeUpdate(
                    "INSERT INTO sennard.scan_failure (scan_failure_id, workflow_id, path, reason, version) "
                    + "VALUES ('30000000-0000-0000-0000-000000000003', 'probe', 'path', 'UNKNOWN', 0)"));
            // a CHECK violation: PostgreSQL's state 23514
            assertEquals("23514", error.getSQLState());
        }
    }
}
