// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.ChangeSource;
import io.github.jaeseongs95.sennard.app.journal.FileDigest;
import io.github.jaeseongs95.sennard.app.journal.HookBasis;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.ScanBasis;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;

/**
 * Cases planned before execution: revisions and classification; hook/rescan deduplication;
 * metadata hash avoidance; creation/deletion; failed reads; UTC; and no stored file contents.
 * The public service and actual Spring Data repositories use the test JVM's pool on the business database (PostgreSQL).
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeJournalTest {
    private RewrittenAreasContext context;

    @TempDir
    Path directory;

    @BeforeAll
    void start() {
        context = RewrittenAreasContext.appConnection(Instant.parse("2026-03-04T05:06:07.089Z"));
    }

    @AfterAll
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private JournalFlow journal() {
        return JournalFlow.of(context);
    }

    @Test
    void schemaRejectsUnknownEnumValues() throws Exception {
        journal().submitPlan("enum-test", Set.of());
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            SQLException state = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO sennard.journal_workflow (workflow_id, baseline_state, version) VALUES ('bad-enum', 'INVALID', 0)"));
            assertCheckViolation(state);
            SQLException source = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO sennard.change_event (change_event_id, workflow_id, plan_revision, path, path_hash, recorded_at, source, version) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', 'enum-test', 1, 'path', 'hash', TIMESTAMP '2026-01-01 00:00:00', 'INVALID', 0)"));
            assertCheckViolation(source);
        }
    }

    /** The failure of a CHECK constraint: PostgreSQL's state 23514 (check_violation). */
    private static void assertCheckViolation(SQLException failure) {
        assertEquals("23514", failure.getSQLState());
    }

    @Test
    void planPathsOutsideWriteRootsAreRejectedBeforeBaselineAndRevision() throws Exception {
        Path inside = directory.resolve("inside.txt");
        Path outside = directory.getParent().resolve("outside-journal.txt");
        journal().submitPlan("outside-plan", Set.of(inside, outside));
        IllegalArgumentException baseline = assertThrows(IllegalArgumentException.class,
                () -> journal().captureBaseline("outside-plan", Set.of(directory)));
        assertTrue(baseline.getMessage().contains(outside.toString()));
        journal().submitPlan("outside-plan", Set.of(inside));
        assertTrue(journal().captureBaseline("outside-plan", Set.of(directory)).isEmpty());
        IllegalArgumentException revision = assertThrows(IllegalArgumentException.class,
                () -> journal().submitPlan("outside-plan", Set.of(inside, outside)));
        assertTrue(revision.getMessage().contains(outside.toString()));
        assertEquals(3, journal().submitPlan("outside-plan", Set.of(directory.resolve("sub").resolve("..").resolve("inside.txt"))));
    }

    @Test
    void revisionsClassifyHookAndRescanWithoutDuplicatingTheSameChange() throws Exception {
        Path planned = Files.writeString(directory.resolve("planned.txt"), "old");
        Path other = Files.writeString(directory.resolve("other.txt"), "old");
        Path untouched = Files.writeString(directory.resolve("untouched.txt"), "old");
        Path beforeRevision = Files.writeString(directory.resolve("before.txt"), "old");
        assertEquals(1, journal().submitPlan("wf", Set.of(planned, untouched)));
        assertTrue(journal().captureBaseline("wf", Set.of(directory)).isEmpty());
        Files.writeString(planned, "changed and longer");
        assertTrue(journal().recordHook("wf", planned).isEmpty());
        Files.writeString(other, "also changed and longer");
        var scan = journal().rescan("wf");
        assertTrue(scan.failures().isEmpty());
        assertEquals(Set.of(planned), scan.changes().plannedChanges());
        assertEquals(Set.of(other), scan.changes().unplannedChanges());
        assertEquals(Set.of(untouched), scan.changes().plannedUnchanged());
        assertEquals(2, scan.changes().events().size());
        assertEquals(Set.of(ChangeSource.HOOK, ChangeSource.RESCAN),
                scan.changes().events().stream().map(event -> event.source()).collect(java.util.stream.Collectors.toSet()));
        Files.writeString(beforeRevision, "changed before the next plan");
        assertEquals(2, journal().submitPlan("wf", Set.of(other, untouched, beforeRevision)));
        assertEquals(Set.of(planned), journal().changes("wf").plannedChanges());
        assertEquals(Set.of(other, beforeRevision), journal().changes("wf").unplannedChanges());
        assertEquals(Map.of(other, 2L, beforeRevision, 2L), journal().changes("wf").laterPlanRevisions());
        assertTrue(journal().changes("wf").events().stream().anyMatch(event -> event.path().equals(beforeRevision.toString())
                && event.planRevision() == 1 && event.source() == ChangeSource.RESCAN));
        assertEquals(Set.of(untouched), journal().changes("wf").plannedUnchanged());
        assertEquals(2, count("SELECT count(*) FROM sennard.plan_revision"));
    }

    @Test
    void unchangedMetadataDoesNotHashAndNewAndDeletedFilesAreReported() throws Exception {
        Path old = Files.writeString(directory.resolve("old.txt"), "old");
        CountingDigest digest = new CountingDigest();
        JournalScanner scanner = new JournalScanner(digest);
        var initial = scanner.scan(List.of(directory), Map.of(), Set.of(old), true);
        assertEquals(1, digest.calls);
        scanner.scan(List.of(directory), initial.files(), Set.of(old), false);
        assertEquals(1, digest.calls, "identical size and timestamp do not open a file for hashing");
        journal().submitPlan("wf", Set.of(old));
        assertTrue(journal().captureBaseline("wf", Set.of(directory)).isEmpty());
        Files.delete(old);
        Path created = Files.writeString(directory.resolve("new.txt"), "new content");
        var result = journal().rescan("wf");
        assertTrue(result.failures().isEmpty());
        assertEquals(Set.of(old), result.changes().plannedChanges());
        assertEquals(Set.of(created), result.changes().unplannedChanges());
        assertEquals(2, result.changes().events().size());
        assertTrue(result.changes().events().stream().anyMatch(event -> event.path().equals(old.toString()) && event.sha256() == null));
        assertTrue(result.changes().events().stream().anyMatch(event -> event.path().equals(created.toString()) && event.sha256() != null));
    }

    @Test
    void aWriteRootThatIsGoneIsRecordedAsDeletionsNotAsUnreadable() throws Exception {
        Path root = Files.createDirectory(directory.resolve("deleted-root"));
        Path file = Files.writeString(root.resolve("file"), "content");
        journal().submitPlan("wf", Set.of(file));
        assertTrue(journal().captureBaseline("wf", Set.of(root)).isEmpty());
        Files.delete(file);
        Files.delete(root);
        // A write root that does not exist is a root with no files (the journal's rule for a root the work has yet to create, and for one it removed): what was in it is deleted.
        var result = journal().rescan("wf");
        assertTrue(result.failures().isEmpty(), "an absent root is not an unreadable one");
        assertEquals(Set.of(file), result.changes().plannedChanges());
        assertTrue(result.changes().plannedUnchanged().isEmpty(), "a deleted file is not unchanged");
        assertEquals(1, result.changes().events().size());
        assertTrue(result.changes().events().stream().anyMatch(event -> event.path().equals(file.toString()) && event.sha256() == null));
        assertTrue(journal().changes("wf").failures().isEmpty());
        Files.createDirectory(root);
        var again = journal().rescan("wf");
        assertTrue(again.failures().isEmpty());
        assertEquals(1, again.changes().events().size(), "the deletion is recorded once");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unreadableFileIsReportedWithoutAFalseEvent() throws Exception {
        Path file = Files.writeString(directory.resolve("protected"), "contents");
        journal().submitPlan("wf", Set.of(file));
        assertTrue(journal().captureBaseline("wf", Set.of(directory)).isEmpty());
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(acl);
        {
            List<AclEntry> original = acl.getAcl();
            List<AclEntry> restricted = new ArrayList<>(original);
            restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(file))
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            try {
                acl.setAcl(restricted);
                assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertFalse(journal().recordHook("wf", file).isEmpty());
                assertTrue(journal().changes("wf").events().isEmpty());
                var rescan = journal().rescan("wf");
                assertAll(() -> assertThrows(IOException.class, () -> Files.readAllBytes(file)),
                        () -> assertFalse(rescan.failures().isEmpty(), "failed read must persist"),
                        () -> assertFalse(rescan.changes().plannedUnchanged().contains(file), "failed read cannot prove unchanged"));
            } finally {
                acl.setAcl(original);
            }
        }
        var recovered = journal().rescan("wf");
        assertTrue(recovered.failures().isEmpty(), "successful read resolves failure");
        assertTrue(recovered.changes().plannedUnchanged().contains(file));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void ancestorFailureDoesNotForgetFailedFile() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root-failure"));
        Path file = Files.writeString(root.resolve("file"), "contents");
        journal().submitPlan("ancestor", Set.of(file));
        assertTrue(journal().captureBaseline("ancestor", Set.of(root)).isEmpty());
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        AclFileAttributeView rootAcl = Files.getFileAttributeView(root, AclFileAttributeView.class);
        assertNotNull(acl);
        assertNotNull(rootAcl);
        List<AclEntry> original = acl.getAcl();
        List<AclEntry> restricted = new ArrayList<>(original);
        restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(file))
                .setPermissions(AclEntryPermission.READ_DATA).build());
        List<AclEntry> originalRoot = rootAcl.getAcl();
        List<AclEntry> restrictedRoot = new ArrayList<>(originalRoot);
        restrictedRoot.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(root))
                .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
        try {
            acl.setAcl(restricted);
            assertFalse(journal().recordHook("ancestor", file).isEmpty());
            // The root exists but cannot be listed (an absent root would be a deletion): the failure of the root covers the file, so the file's own failure is not forgotten.
            rootAcl.setAcl(restrictedRoot);
            var blocked = journal().rescan("ancestor");
            assertTrue(blocked.failures().stream().anyMatch(f -> f.path().equals(root)), "the root's own read failure is reported");
            assertTrue(blocked.failures().stream().anyMatch(f -> f.path().equals(file)));
            rootAcl.setAcl(originalRoot);
            var rescan = journal().rescan("ancestor");
            assertTrue(rescan.failures().stream().anyMatch(f -> f.path().equals(file)));
            assertFalse(rescan.changes().plannedUnchanged().contains(file));
        } finally {
            Files.getFileAttributeView(root, AclFileAttributeView.class).setAcl(originalRoot);
            Files.getFileAttributeView(file, AclFileAttributeView.class).setAcl(original);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletedFailedFileClearsFailureAfterRescan() throws Exception {
        Path file = Files.writeString(directory.resolve("deleted-protected"), "contents");
        journal().submitPlan("deleted", Set.of(file));
        assertTrue(journal().captureBaseline("deleted", Set.of(directory)).isEmpty());
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(acl);
        List<AclEntry> original = acl.getAcl();
        List<AclEntry> restricted = new ArrayList<>(original);
        restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(file))
                .setPermissions(AclEntryPermission.READ_DATA).build());
        try {
            acl.setAcl(restricted);
            assertFalse(journal().recordHook("deleted", file).isEmpty());
        } finally {
            acl.setAcl(original);
        }
        Files.delete(file);
        var result = journal().rescan("deleted");
        assertTrue(result.failures().isEmpty());
        assertFalse(result.changes().plannedUnchanged().contains(file));
        assertFalse(result.changes().events().isEmpty());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void planRevisionMustPreserveScanFailureCauseOfAJunctionRoot() throws Exception {
        assertRevisionPreservesTheCauseOfLinkRoots(this::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void planRevisionMustPreserveScanFailureCauseOfASymbolicLinkRoot() throws Exception {
        assertRevisionPreservesTheCauseOfLinkRoots(Files::createSymbolicLink);
    }

    /** Two roots are links whose targets are gone after the baseline: they exist but cannot be scanned, so each is a read failure with its cause, and a revision of the plan is refused. */
    private void assertRevisionPreservesTheCauseOfLinkRoots(LinkFactory links) throws Exception {
        Path root = directory.resolve("missing-root");
        Path other = directory.resolve("missing-other");
        Path rootTarget = Files.createDirectory(directory.resolve("missing-root-target"));
        Path otherTarget = Files.createDirectory(directory.resolve("missing-other-target"));
        links.create(root, rootTarget);
        links.create(other, otherTarget);
        try {
            journal().submitPlan("cause", Set.of());
            assertTrue(journal().captureBaseline("cause", Set.of(root, other)).isEmpty());
            Files.delete(rootTarget);
            Files.delete(otherTarget);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> journal().submitPlan("cause", Set.of()));
            assertTrue(failure.getCause() instanceof IOException, "scan failure cause must be retained");
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].getCause() instanceof IOException);
        } finally {
            Files.deleteIfExists(root);
            Files.deleteIfExists(other);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionRootIsScannedUnderItsOwnSpelling() throws Exception {
        assertLinkRootIsScanned(this::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkRootIsScannedUnderItsOwnSpelling() throws Exception {
        assertLinkRootIsScanned(Files::createSymbolicLink);
    }

    /**
     * A write root that is itself a link is the folder it points at: the baseline lists its files (not a skipped link and no file), the hook and the rescan record them under the
     * spelling of the root, and a link below the root is still not followed.
     */
    private void assertLinkRootIsScanned(LinkFactory links) throws Exception {
        Path target = Files.createDirectory(directory.resolve("linked-target"));
        Path outside = Files.createDirectory(directory.resolve("linked-outside"));
        Files.writeString(outside.resolve("outside.txt"), "outside");
        Path planned = Files.writeString(target.resolve("planned.txt"), "one");
        Path root = directory.resolve("linked-root");
        links.create(root, target);
        Path inside = root.resolve("below");
        try {
            links.create(target.resolve("below"), outside);
            Path viaRoot = root.resolve("planned.txt");
            journal().submitPlan("linked", Set.of(viaRoot));
            var failures = journal().captureBaseline("linked", Set.of(root));
            assertTrue(failures.stream().allMatch(failure -> failure.reason() == JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED && failure.path().equals(inside)),
                    "only the link below the root is skipped: " + failures);
            assertEquals(1, failures.size());
            assertEquals(List.of(viaRoot), journal().baseline("linked").stream().map(ChangeJournalService.BaselineEntry::path).toList(), "the root's files are listed under the root's spelling");
            Files.writeString(planned, "one and more");
            Path created = Files.writeString(target.resolve("created.txt"), "new");
            assertTrue(journal().recordHook("linked", viaRoot).isEmpty());
            var scan = journal().rescan("linked");
            assertEquals(Set.of(viaRoot), scan.changes().plannedChanges());
            assertEquals(Set.of(root.resolve(created.getFileName())), scan.changes().unplannedChanges());
            assertTrue(scan.failures().stream().allMatch(failure -> failure.path().equals(inside)), "the link below the root is the only skipped path: " + scan.failures());
            assertTrue(scan.changes().events().stream().noneMatch(event -> event.path().contains("outside")), "nothing behind the link below the root is recorded");
            // A hook target behind that link is still refused.
            var behind = journal().recordHook("linked", inside.resolve("outside.txt"));
            assertEquals(1, behind.size());
            assertEquals(JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED, behind.getFirst().reason());
        } finally {
            Files.deleteIfExists(target.resolve("below"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionRootThatCannotBeScannedIsAFailureAndNeverASkippedLink() throws Exception {
        assertUnscannableLinkRoot(this::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkRootThatCannotBeScannedIsAFailureAndNeverASkippedLink() throws Exception {
        assertUnscannableLinkRoot(Files::createSymbolicLink);
    }

    /**
     * A root whose target is gone cannot be told to be absent (the link exists) and cannot be read: at the baseline it keeps the run in its planning phase, and after it the
     * rescan reports it as a read failure (the comparison turns that into INCONCLUSIVE). A skipped link is not one, which is why the scanner must never report a root as skipped.
     */
    private void assertUnscannableLinkRoot(LinkFactory links) throws Exception {
        Path target = Files.createDirectory(directory.resolve("gone-target"));
        Path planned = Files.writeString(target.resolve("planned.txt"), "one");
        Path root = directory.resolve("gone-root");
        links.create(root, target);
        Path never = directory.resolve("never-root");
        Path neverTarget = Files.createDirectory(directory.resolve("never-target"));
        links.create(never, neverTarget);
        try {
            Files.delete(neverTarget);
            journal().submitPlan("never", Set.of());
            var unscanned = journal().captureBaseline("never", Set.of(never));
            assertEquals(1, unscanned.size());
            assertEquals(never, unscanned.getFirst().path());
            assertNotEquals(JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED, unscanned.getFirst().reason());
            assertEquals(WorkflowPhase.PLANNING, journal().phase("never").orElseThrow(), "no baseline is kept for a root that was not read");

            Path viaRoot = root.resolve("planned.txt");
            journal().submitPlan("gone", Set.of(viaRoot));
            assertTrue(journal().captureBaseline("gone", Set.of(root)).isEmpty());
            Files.delete(planned);
            Files.delete(target);
            var rescan = journal().rescan("gone");
            assertEquals(1, rescan.failures().size());
            assertEquals(root, rescan.failures().getFirst().path());
            assertNotEquals(JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED, rescan.failures().getFirst().reason());
            assertTrue(rescan.changes().events().isEmpty(), "what cannot be read is not reported as deleted");
            assertTrue(rescan.changes().plannedUnchanged().isEmpty(), "what cannot be read is not unchanged");
        } finally {
            Files.deleteIfExists(root);
            Files.deleteIfExists(never);
        }
    }

    /** Creates {@code link} pointing at {@code target}: a symbolic link, or on Windows a junction (a symbolic link needs a privilege there). */
    @FunctionalInterface
    private interface LinkFactory {
        void create(Path link, Path target) throws IOException;
    }

    private void createJunction(Path junction, Path target) throws IOException {
        // Java has no API to create a Windows junction; cmd is used only to prepare this test fixture.
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction fixture creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while a junction was created", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * A rescan whose basis was read before a hook recorded the file: its walk saw the file gone, the hook saw it written. The hook's write moved the journal's version on, so the
     * rescan's conditional UPDATE changes no row and it ends with the conflict, with nothing written and no second try.
     */
    @Test
    void staleScanCannotOverwriteAConcurrentHook() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "old");
        journal().submitPlan("wf", Set.of(file));
        assertTrue(journal().captureBaseline("wf", Set.of(directory)).isEmpty());
        ChangeJournalService service = journal().service();
        ScanBasis stale = service.rescanBasis("wf");
        Files.delete(file);
        JournalScanner.Rescan staleWalk = journal().scanner().rescan(stale);
        Files.writeString(file, "new content");
        assertTrue(journal().recordHook("wf", file).isEmpty());
        ConflictException lost = assertThrows(ConflictException.class, () -> service.recordRescan(stale, staleWalk));
        assertEquals(ConflictCode.CONCURRENT_UPDATE, lost.code());
        assertEquals(1, journal().changes("wf").events().size());
        assertNotNull(journal().changes("wf").events().getFirst().sha256());
    }

    /**
     * Two writers that read the same version: the one whose UPDATE comes second waits for the row of the first (the holder here, whose transaction moved the version on and is
     * still open), the database judges its WHERE again after the first one's commit, and it ends with the conflict and has written nothing. The wait is seen from the database.
     */
    @Test
    void theWriterThatWaitedOnTheJournalsRowAndLostEndsWithTheConflict() throws Exception {
        Path file = Files.writeString(directory.resolve("race-file"), "old");
        journal().submitPlan("race", Set.of(file));
        assertTrue(journal().captureBaseline("race", Set.of(directory)).isEmpty());
        ChangeJournalService service = journal().service();
        Files.writeString(file, "changed and longer");
        ScanBasis basis = service.rescanBasis("race");
        JournalScanner.Rescan found = journal().scanner().rescan(basis);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection winner = BusinessDb.connect(); Statement statement = winner.createStatement()) {
            winner.setAutoCommit(false);
            long winnerBackend = LockWaits.backendOf(winner);
            assertEquals(1, statement.executeUpdate("UPDATE sennard.journal_workflow SET version = version + 1 WHERE workflow_id = 'race'"));
            Future<ChangeJournalService.ScanResult> loser = executor.submit(() -> service.recordRescan(basis, found));
            LockWaits.awaitWaiterBlockedBy(winnerBackend, Duration.ofSeconds(120), "the second writer did not wait for the journal's row");
            winner.commit();
            ExecutionException failed = assertThrows(ExecutionException.class, () -> loser.get(120, TimeUnit.SECONDS));
            ConflictException conflict = assertInstanceOf(ConflictException.class, failed.getCause());
            assertEquals(ConflictCode.CONCURRENT_UPDATE, conflict.code());
        } finally {
            executor.shutdownNow();
        }
        assertTrue(journal().changes("race").events().isEmpty(), "the writer that lost wrote nothing");
    }

    @Test
    void aPlanOfAJournalWhoseBaselineWasCapturedIsRefusedWithTheConflictAndAFileOutsideTheRootsHasNoBasis() throws Exception {
        Path file = Files.writeString(directory.resolve("captured"), "old");
        journal().submitPlan("captured", Set.of(file));
        assertTrue(journal().captureBaseline("captured", Set.of(directory)).isEmpty());
        ChangeJournalService service = journal().service();
        ConflictException refused = assertThrows(ConflictException.class, () -> service.submitPlan("captured", Set.of(file)));
        assertEquals(ConflictCode.CONCURRENT_UPDATE, refused.code());
        assertEquals(1, count("SELECT count(*) FROM sennard.plan_revision WHERE workflow_id = 'captured'"));
        assertTrue(service.hookBasis("captured", directory.getParent().resolve("elsewhere.txt")).isEmpty());
        HookBasis inside = service.hookBasis("captured", file).orElseThrow();
        assertEquals(file, inside.file());
        assertEquals(directory, inside.root());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void inaccessibleNestedJunctionAllowsBaselineAndPlanRevision() throws Throwable {
        Path root = Files.createDirectory(directory.resolve("junction-root"));
        Path target = Files.createDirectory(directory.resolve("junction-target"));
        Path file = Files.writeString(root.resolve("planned.txt"), "contents");
        Path junction = root.resolve("nested-junction");
        createJunction(junction, target);
        try {
            JournalFlow flow = journal();
            flow.submitPlan("denied-junction", Set.of(file));
            withDirectoryListingDenied(target, junction, () -> {
                assertEquals(List.of(new JournalScanner.ReadFailure(junction, JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED)),
                        flow.captureBaseline("denied-junction", Set.of(root)));
                assertEquals(List.of(file), flow.baseline("denied-junction").stream().map(ChangeJournalService.BaselineEntry::path).toList());
                assertEquals(2, flow.submitPlan("denied-junction", Set.of(file, root.resolve("next.txt"))));
            });
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void inaccessibleRegularDirectoryStillBlocksBaseline() throws Throwable {
        Path root = Files.createDirectory(directory.resolve("regular-root"));
        Path blocked = Files.createDirectory(root.resolve("regular-directory"));
        Path file = Files.writeString(root.resolve("planned.txt"), "contents");
        JournalFlow flow = journal();
        flow.submitPlan("denied-directory", Set.of(file));
        withDirectoryListingDenied(blocked, blocked, () -> {
            var failures = flow.captureBaseline("denied-directory", Set.of(root));
            assertEquals(1, failures.size());
            var failure = failures.getFirst();
            assertEquals(blocked, failure.path());
            assertEquals(JournalScanner.FailureReason.ACCESS_DENIED, failure.reason());
            assertInstanceOf(AccessDeniedException.class, failure.cause());
            assertEquals(WorkflowPhase.PLANNING, flow.phase("denied-directory").orElseThrow());
        });
    }

    private static void withDirectoryListingDenied(Path target, Path entry, Executable check) throws Throwable {
        AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
        assertNotNull(acl);
        List<AclEntry> original = acl.getAcl();
        List<AclEntry> restricted = new ArrayList<>(original);
        restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(target))
                .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
        try {
            acl.setAcl(restricted);
            assertThrows(AccessDeniedException.class, () -> {
                try (var entries = Files.newDirectoryStream(entry)) {
                    entries.iterator().hasNext();
                }
            });
            check.execute();
        } finally {
            acl.setAcl(original);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionIsReportedAndNeverTraversed() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("outside.txt"), "outside");
        Path junction = root.resolve("junction");
        // Java has no API to create a Windows junction; cmd is used only to prepare this test fixture.
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction fixture creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            JournalScanner scanner = new JournalScanner(new FileDigest());
            var scan = scanner.scan(List.of(root), Map.of(), Set.of(), true);
            assertTrue(scan.files().isEmpty());
            assertTrue(scan.failures().stream().anyMatch(failure -> failure.path().equals(junction) && failure.reason() == JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED));
            var nested = scanner.scanUnder(root, junction.resolve("outside.txt"), Map.of());
            assertTrue(nested.files().isEmpty());
            assertTrue(nested.failures().stream().anyMatch(failure -> failure.path().equals(junction)));
            journal().submitPlan("wf", Set.of(root.resolve("new.txt")));
            assertFalse(journal().captureBaseline("wf", Set.of(root)).isEmpty(), "skipped link is reported");
            assertFalse(journal().changes("wf").failures().isEmpty(), "the baseline remains available and records the skipped link");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(junction);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void symbolicLinkIsReportedAndNeverTraversed() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("outside.txt"), "outside");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, target);
        JournalScanner scanner = new JournalScanner(new FileDigest());
        var scan = scanner.scan(List.of(root), Map.of(), Set.of(), true);
        assertTrue(scan.files().isEmpty());
        assertTrue(scan.failures().stream().anyMatch(failure -> failure.path().equals(link) && failure.reason() == JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED));
        assertTrue(scanner.scanUnder(root, link.resolve("outside.txt"), Map.of()).files().isEmpty());
    }

    @Test
    void timestampsAreUtcAndFileContentsNeverEnterTheJournal() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            String secret = "unique-file-payload-never-store-7419";
            Path file = Files.writeString(directory.resolve("file.txt"), secret);
            journal().submitPlan("wf", Set.of(file));
            assertTrue(journal().captureBaseline("wf", Set.of(directory)).isEmpty());
            Files.writeString(file, secret + " changed");
            assertTrue(journal().recordHook("wf", file).isEmpty());
            assertEquals(Instant.parse("2026-03-04T05:06:07.089Z"), journal().changes("wf").events().getFirst().recordedAt());
            try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement();
                    // The text of the stored time: CAST to VARCHAR (PostgreSQL's CHAR cast pads).
                    ResultSet rows = statement.executeQuery("SELECT CAST(recorded_at AS VARCHAR(40)) FROM sennard.change_event")) {
                assertTrue(rows.next());
                assertEquals("2026-03-04 05:06:07.089", rows.getString(1));
            }
            try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement();
                    ResultSet tables = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'sennard'")) {
                while (tables.next()) {
                    String table = tables.getString(1);
                    try (Statement contents = connection.createStatement(); ResultSet rows = contents.executeQuery("SELECT * FROM sennard." + table)) {
                        while (rows.next()) {
                            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                                String value = rows.getString(column);
                                if (value != null) {
                                    assertFalse(value.contains(secret), table + " contains file contents");
                                }
                            }
                        }
                    }
                }
            }
            assertNotNull(journal().changes("wf").events().getFirst().sha256());
        } finally {
            TimeZone.setDefault(before);
        }
    }

    private long count(String sql) throws Exception {
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static final class CountingDigest extends FileDigest {
        private int calls;

        @Override
        public String sha256(Path path) throws IOException {
            calls++;
            return super.sha256(path);
        }
    }
}
