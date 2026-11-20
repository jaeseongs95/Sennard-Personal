// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.ALICE;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.T0;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.refused;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRepository;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentReaperRounds;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentRepository;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentStorage;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentStorageRepository;
import io.github.jaeseongs95.sennard.app.attachment.UploadStaging;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;

/**
 * The cleaner of the uploads and the comparison of the folder with the rows (design 7), at the time of the test's own clock ({@link RewrittenAreasContext.TestClock}), which the test
 * moves; no real time is waited for. The real database and the real folder.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 7   c1  the TTL counts again from the last chunk   an upload expires ten minutes after its last chunk, not after its begin; the status shows the new time; the cleaner leaves it until then
 * 7   c2  the cleaner deletes what expired            at the very expiry (the condition is "expires_at &lt;= now") the row and the file are deleted; one millisecond before they are not;
 *                                                      after the expiry and before the cleaner a chunk or a finish is ARTIFACT_EXPIRED (not retryable)
 * 7   c3  a done upload's row                         a COMPLETED row is removed by the cleaner and the attachment and its file stay
 * 7   c3b a finish after the row is gone               a finish sent after the cleaner removed the rows (it carries no author: the attachment id alone) answers the same attachment, whose
 *                                                      author is still the owner of the begin; a chunk of the upload id is ARTIFACT_NOT_FOUND
 * 8   c4  a file with no row, before and after the grace  not moved before orphan-grace (24 h from the file's time), moved to orphans/ at it (never deleted); kept there; an explicit purge
 *                                                      deletes it and returns its name
 * 8   c5  a file that got its row back                a file moved to orphans/ whose storage key has a row again (a restored database) is put back in its place and reads
 * 8   c6  a row with no file                          an attachment whose file is gone: the check reports file-missing, a read is ARTIFACT_CORRUPTED (file-missing), the row is not deleted, not even
 *                                                      by a cleaner round long after; an open upload whose file is gone is reported, and the cleaner deletes its row at its expiry
 * 8   c7  an attachment nothing points at             stays, with its file, through a cleaner round long after; it is listed and the check finds nothing wrong with it
 * 4   c8  damage is reported, never deleted           a damaged file is reported as damaged and neither it nor its row is deleted by the cleaner
 * 7   c9  the cleaner does not walk the folder        the cleaner's round (the expired and the done upload rows) leaves a file with no row and a replaced file where they are, however late; the
 *                                                      comparison of the folder with the rows ({@code reconcileFolder}) moves both once the grace has passed
 * 7   c10 the two rounds on their own intervals       the registered tasks are the cleaner at {@code reaper-interval} and the comparison at {@code reconcile-interval}; each task's own run does
 *                                                      its own work only (no clock is waited for: the test runs the tasks' runnables)
 * 7   c11 a bundle expires as a whole                  a bundle of three files: a chunk of file 1 gives the upload rows of ALL three files the new expiry (file 0 and file 2, which got no
 *                                                      chunk, are not cleaned at the time the begin gave); a chunk of file 0 does it again; at the expiry the cleaner deletes the rows
 *                                                      of all three files together (and the files that exist), nothing is left of the bundle; before the cleaner a finish and a chunk of the
 *                                                      expired bundle are ARTIFACT_EXPIRED, after it ARTIFACT_NOT_FOUND; no attachment, attachment_file or attachment_storage row was made
 * </pre>
 */
@BusinessDbTest
class AttachmentCleanerTest {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration GRACE = Duration.ofHours(24);

    @TempDir
    Path scratch;

    private AttachmentWorld world;

    @BeforeEach
    void start() {
        world = AttachmentWorld.open(scratch);
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private void at(Instant instant) {
        world.clock().set(instant);
    }

    /** One round of the cleaner (the expired and the done upload rows). */
    private AttachmentMaintenance.Reaped reap() {
        return world.maintenance().reap();
    }

    /** One comparison of the folder with the rows (files with no row, replaced files, the quarantine folder). */
    private AttachmentMaintenance.Reconciled reconcile() {
        return world.maintenance().reconcileFolder();
    }

    private void setFileTime(Path file, Instant instant) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(instant));
    }

    // ------------------------------------------------------------------------------------------------------------ c1, c2, c3

    @Test
    void theTimeToLiveCountsAgainFromTheLastChunkAndTheCleanerLeavesTheUploadUntilThen() {
        byte[] first = world.bytes(30);
        byte[] second = world.bytes(30);
        AttachmentWorld.Single begun = world.begin(ALICE, "ttl.bin", AttachmentWorld.concat(first, second));
        String id = begun.uploadId();
        assertEquals(T0.plus(TTL), begun.expiresAt());

        at(T0.plus(Duration.ofMinutes(9)));
        world.chunk(id, 0, first);
        Path file = world.pathOfUpload(id);
        assertEquals(T0.plus(Duration.ofMinutes(19)), world.uploads().uploadStatus(id).expiresAt(), "ten minutes after the last chunk, not after the begin");

        at(T0.plus(Duration.ofMinutes(10))); // the time the begin gave: the upload has had a chunk since
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap());
        assertTrue(Files.exists(file));
        assertTrue(world.hasStaging(id));

        at(T0.plus(Duration.ofMinutes(18)));
        world.chunk(id, 1, second);
        assertEquals(T0.plus(Duration.ofMinutes(28)), world.uploads().uploadStatus(id).expiresAt(), "a second chunk counts again");
        at(T0.plus(Duration.ofMinutes(27)));
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap());
        assertTrue(Files.exists(file));
    }

    @Test
    void anUploadIsDeletedWithItsFileAtItsExpiryAndNotOneMillisecondBeforeAndIsExpiredForAChunkOrAFinishUntilThen() {
        byte[] content = world.bytes(40);
        AttachmentWorld.Single begun = world.begin(ALICE, "expire.bin", content);
        String id = begun.uploadId();
        world.chunk(id, 0, content);
        Path file = world.pathOfUpload(id);
        Instant expires = world.uploads().uploadStatus(id).expiresAt();
        assertEquals(T0.plus(TTL), expires);

        at(expires.minusMillis(1));
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap());
        assertTrue(Files.exists(file));
        assertTrue(world.hasStaging(id));

        at(expires);
        refused(ConflictException.class, ConflictCode.ARTIFACT_EXPIRED, false, () -> world.chunk(id, 1, content));
        refused(ConflictException.class, ConflictCode.ARTIFACT_EXPIRED, false, () -> world.uploads().finish(begun.attachmentId()));
        assertTrue(world.hasStaging(id), "until the cleaner runs the row is there");

        AttachmentMaintenance.Reaped reaped = reap();
        assertEquals(1, reaped.expiredUploads());
        assertFalse(world.hasStaging(id));
        assertFalse(Files.exists(file), "the file of the expired upload is deleted after the row");
        assertTrue(world.service().uploadStatus(id).isEmpty());
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.chunk(id, 1, content));
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap(), "a second round finds nothing");
    }

    @Test
    void anUploadThatHadNoChunkExpiresAtTheTimeOfItsBeginAndItsRowIsDeletedAlthoughThereIsNoFile() {
        AttachmentWorld.Single begun = world.begin(ALICE, "never.bin", world.bytes(10));

        at(begun.expiresAt());
        assertEquals(1, reap().expiredUploads());
        assertFalse(world.hasStaging(begun.uploadId()));
    }

    @Test
    void aFinishedUploadsRowIsRemovedByTheCleanerAndTheAttachmentAndItsFileStay() throws SQLException {
        byte[] content = world.bytes(90);
        AttachmentService.Kept kept = world.upload(ALICE, "done.bin", content, 30);
        Path file = world.pathOfAttachment(kept.attachmentId());
        assertEquals(1, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'COMPLETED'"));

        at(T0.plus(Duration.ofDays(30)));
        AttachmentMaintenance.Reaped reaped = reap();

        assertEquals(1, reaped.completedUploads());
        assertEquals(0, reaped.expiredUploads());
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        assertTrue(Files.exists(file), "the file is the attachment's, not the upload's");
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 40));
        assertEquals(kept, world.uploads().finish(kept.attachmentId()), "a finish sent after the row is gone finds the attachment by the same id");
    }

    @Test
    void aFinishOfAnotherAuthorAfterTheRowIsGoneAnswersTheSameAttachmentAndTheAuthorStaysTheOwnerOfTheBegin() throws SQLException {
        byte[] content = world.bytes(90);
        AttachmentWorld.Single begun = world.begin(ALICE, "alices.bin", content);
        world.send(begun.uploadId(), content, 30);
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        at(T0.plus(Duration.ofDays(30)));
        assertEquals(1, reap().completedUploads());
        assertFalse(world.hasStaging(begun.uploadId()), "the staging row is gone");

        // The finish carries no author; it is the one of a client that was handed the id by someone else than the author's session.
        assertEquals(kept, world.uploads().finish(kept.attachmentId()));
        assertEquals(kept, world.uploads().finish(kept.attachmentId()), "and again");
        assertEquals(ALICE, world.service().attachment(kept.attachmentId()).orElseThrow().createdBy());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 40));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.chunk(begun.uploadId(), 3, content));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
    }

    // ------------------------------------------------------------------------------------------------------------ c4, c5

    @Test
    void aFileWithNoRowIsMovedToTheOrphanFolderAtTheGraceNotBeforeAndNeverDeletedUntilAnExplicitPurge() throws IOException {
        String storageKey = world.store().newStorageKey();
        Path stray = world.pathOf(storageKey);
        Files.createDirectories(stray.getParent());
        Files.write(stray, world.bytes(200));
        setFileTime(stray, T0);

        at(T0.plus(GRACE).minusMillis(1));
        assertEquals(new AttachmentMaintenance.Reconciled(0, 0), reconcile(), "before the grace nothing is done");
        assertTrue(Files.exists(stray));
        assertEquals(List.of(storageKey), world.store().storedKeys());
        assertEquals(1, world.maintenance().check().size(), "the check lists it");
        assertEquals("file-without-row", world.maintenance().check().get(0).kind());
        assertEquals(storageKey, world.maintenance().check().get(0).id());

        at(T0.plus(GRACE));
        assertEquals(new AttachmentMaintenance.Reconciled(1, 0), reconcile(), "at the grace it is moved");
        assertFalse(Files.exists(stray));
        assertEquals(List.of(storageKey), world.store().quarantinedKeys(), "moved, not deleted");
        assertTrue(Files.isRegularFile(world.directory().resolve("orphans").resolve(storageKey)));
        at(T0.plus(GRACE).plus(Duration.ofDays(400)));
        assertEquals(new AttachmentMaintenance.Reconciled(0, 0), reconcile(), "the comparison never empties the orphan folder");
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap(), "nor does the cleaner");
        assertEquals(List.of(storageKey), world.store().quarantinedKeys());

        assertEquals(List.of(storageKey), world.maintenance().purgeQuarantine(), "the explicit command deletes it and says what it deleted");
        assertEquals(List.of(), world.store().quarantinedKeys());
    }

    @Test
    void aFileMovedToTheOrphanFolderThatGetsItsRowBackIsPutBackInItsPlaceAndReads() throws IOException {
        byte[] content = world.bytes(120);
        AttachmentService.Kept kept = world.upload(ALICE, "restore.bin", content, 50);
        at(T0.plus(Duration.ofMinutes(1)));
        assertEquals(1, reap().completedUploads());
        AttachmentService.FileView view = world.service().view(kept.attachmentId(), 0).orElseThrow();
        AttachmentStorage current = view.current();
        Path file = world.pathOf(current.storageKey());
        setFileTime(file, T0);

        // the database is restored from an older backup that has not got the attachment: its rows (the three tables') are gone, the file is still there
        deleteRows(kept.attachmentId());
        at(T0.plus(GRACE));
        assertEquals(1, reconcile().quarantined(), "the file looks like one that has no row");
        assertFalse(Files.exists(file));
        assertEquals(List.of(current.storageKey()), world.store().quarantinedKeys());

        // the rows come back (a restore of the newer database): the comparison puts the file back
        // (a repository called with no transaction of its own runs in Spring Data's read-only one, so the rows are written in a transaction of the test)
        new TransactionTemplate(world.transactionManager()).executeWithoutResult(status -> {
            world.bean(AttachmentRepository.class).insert(view.attachment().id(), view.attachment().createdBy(), view.attachment().createdAt());
            world.bean(AttachmentFileRepository.class).insert(view.file().attachmentId(), view.file().seq(), view.file().fileName(), view.file().mediaType(), view.file().sizeBytes(),
                    view.file().contentSha256());
            world.bean(AttachmentStorageRepository.class).insert(current.attachmentId(), current.seq(), current.rev(), current.storageKey(), current.keyId(), current.createdAt());
        });
        assertEquals(new AttachmentMaintenance.Reconciled(0, 1), reconcile());
        assertTrue(Files.exists(file));
        assertEquals(List.of(), world.store().quarantinedKeys());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 40));
        assertEquals(List.of(), world.maintenance().check());
    }

    private void deleteRows(String attachmentId) {
        try (Connection connection = BusinessDb.connect(); PreparedStatement storages = connection.prepareStatement("DELETE FROM sennard.attachment_storage WHERE attachment_id = ?");
                PreparedStatement files = connection.prepareStatement("DELETE FROM sennard.attachment_file WHERE attachment_id = ?");
                PreparedStatement attachments = connection.prepareStatement("DELETE FROM sennard.attachment WHERE id = ?")) {
            storages.setString(1, attachmentId);
            files.setString(1, attachmentId);
            attachments.setString(1, attachmentId);
            assertEquals(1, storages.executeUpdate());
            assertEquals(1, files.executeUpdate());
            assertEquals(1, attachments.executeUpdate());
        } catch (SQLException cause) {
            throw new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ c6, c7, c8

    @Test
    void aRowWithNoFileIsReportedAndReadIsCorruptedAndNoRoundOfTheCleanerDeletesTheRow() throws SQLException {
        AttachmentService.Kept kept = world.upload(ALICE, "gone.bin", world.bytes(80), 40);
        Path file = world.pathOfAttachment(kept.attachmentId());
        world.remove(file);

        List<AttachmentMaintenance.Finding> findings = world.maintenance().check();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("file-missing", findings.get(0).kind());
        assertEquals(kept.attachmentId(), findings.get(0).id(), "a finding about a file names the attachment");
        assertEquals(0, findings.get(0).seq(), "a finding about a file names the number of the file");
        IntegrityException missing = refused(IntegrityException.class, IntegrityCode.ARTIFACT_CORRUPTED, false, () -> world.uploads().read(kept.attachmentId(), 0, 0, 100));
        assertEquals("file-missing", missing.details().get("reason"));

        at(T0.plus(Duration.ofDays(400)));
        reap();
        reap();
        reconcile();
        reconcile();
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"), "a row whose file is gone is reported and never deleted");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        assertEquals(findings, world.maintenance().check());
    }

    @Test
    void anOpenUploadWhoseFileIsGoneIsReportedAndItsRowIsDeletedAtItsExpiry() {
        AttachmentWorld.Single begun = world.begin(ALICE, "lost.bin", world.bytes(60));
        world.chunk(begun.uploadId(), 0, world.bytes(30));
        world.remove(world.pathOfUpload(begun.uploadId()));

        List<AttachmentMaintenance.Finding> findings = world.maintenance().check();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("file-missing", findings.get(0).kind());
        assertEquals(begun.uploadId(), findings.get(0).id());

        at(T0.plus(TTL));
        assertEquals(1, reap().expiredUploads(), "the row is deleted although there is no file to delete");
        assertFalse(world.hasStaging(begun.uploadId()));
    }

    @Test
    void anAttachmentNothingPointsAtStaysWithItsFileThroughALaterRoundAndTheCheckFindsNothingWrongWithIt() {
        byte[] content = world.bytes(70);
        AttachmentService.Kept kept = world.upload(ALICE, "unreferenced.bin", content, 70);
        Path file = world.pathOfAttachment(kept.attachmentId());
        at(T0.plus(Duration.ofDays(400)));

        reap();
        reap();
        reconcile();
        reconcile();

        assertTrue(Files.exists(file));
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 70));
        assertEquals(1, world.service().stored().size(), "it is listed");
        assertEquals(kept.attachmentId(), world.service().stored().get(0).attachment().id());
        assertEquals(List.of(), world.maintenance().check(), "nothing wrong: an attachment that no record points at is kept");
        assertTrue(world.store().quarantinedKeys().isEmpty());
    }

    @Test
    void aDamagedFileIsReportedAsDamagedAndNeitherItNorItsRowIsDeletedByTheCleaner() throws SQLException {
        AttachmentService.Kept kept = world.upload(ALICE, "damaged.bin", world.bytes(80), 40);
        Path file = world.pathOfAttachment(kept.attachmentId());
        world.replace(file, AttachmentWorld.flipped(AttachmentWorld.read(file), 30));

        at(T0.plus(Duration.ofDays(400)));
        reap();
        reconcile();

        List<AttachmentMaintenance.Finding> findings = world.maintenance().check();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("damaged", findings.get(0).kind());
        assertEquals("ARTIFACT_CORRUPTED", findings.get(0).detail());
        assertTrue(Files.exists(file));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
    }

    // ------------------------------------------------------------------------------------------------------------ c9, c10

    @Test
    void theCleanerRoundLeavesTheFolderAloneWhateverTheTimeAndOnlyTheComparisonMovesTheFilesWithNoRowOrReplaced() throws IOException {
        // a file that no row names (its time is T0) and a file that a later file replaced (the later file's row is written at T0)
        String strayKey = world.store().newStorageKey();
        Path stray = world.pathOf(strayKey);
        Files.createDirectories(stray.getParent());
        Files.write(stray, world.bytes(100));
        setFileTime(stray, T0);
        AttachmentService.Kept kept = world.upload(ALICE, "replaced.bin", world.bytes(90), 30);
        AttachmentStorage oldFile = world.service().view(kept.attachmentId(), 0).orElseThrow().current();
        Path oldPath = world.pathOf(oldFile.storageKey());
        assertEquals(1, world.service().addCopy(kept.attachmentId(), 0, world.store().newStorageKey(), oldFile.keyId()), "the replacing copy is rev 1");
        assertEquals(new AttachmentMaintenance.Reaped(0, 1), reap(), "the done upload's row is the cleaner's");

        // long after the grace: every round of the cleaner finds nothing and does nothing to the folder
        at(T0.plus(GRACE).plus(Duration.ofDays(3)));
        for (int round = 0; round < 3; round++) {
            assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap());
        }
        assertTrue(Files.exists(stray), "the cleaner does not move a file with no row");
        assertTrue(Files.exists(oldPath), "the cleaner does not move a replaced file");
        assertEquals(List.of(), world.store().quarantinedKeys());

        // the comparison does, once, with the grace passed
        assertEquals(new AttachmentMaintenance.Reconciled(2, 0), reconcile());
        assertFalse(Files.exists(stray));
        assertFalse(Files.exists(oldPath));
        assertEquals(Set.of(strayKey, oldFile.storageKey()), Set.copyOf(world.store().quarantinedKeys()));
        assertEquals(new AttachmentMaintenance.Reconciled(0, 0), reconcile(), "a second comparison finds nothing to move");
    }

    @Test
    void theRegisteredRoundsRunTheCleanerAtTheReaperIntervalAndTheComparisonAtTheReconcileIntervalAndEachDoesItsOwnWorkOnly() throws IOException {
        world.close();
        world = AttachmentWorld.open(scratch, Map.of("reaper-interval", "2m", "reconcile-interval", "3h"));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        world.bean(AttachmentReaperRounds.class).configureTasks(registrar);

        List<IntervalTask> tasks = registrar.getFixedDelayTaskList();
        assertEquals(2, tasks.size(), "the cleaner and the comparison, and nothing else");
        IntervalTask cleaner = taskOf(tasks, Duration.ofMinutes(2));
        IntervalTask comparison = taskOf(tasks, Duration.ofHours(3));
        assertEquals(Duration.ofMinutes(2), cleaner.getInitialDelayDuration(), "the start's own comparison is the store-ready task, so the first round is one interval after the start");
        assertEquals(Duration.ofHours(3), comparison.getInitialDelayDuration());

        String strayKey = world.store().newStorageKey();
        Path stray = world.pathOf(strayKey);
        Files.createDirectories(stray.getParent());
        Files.write(stray, world.bytes(50));
        setFileTime(stray, T0);
        AttachmentWorld.Single begun = world.begin(ALICE, "expired.bin", world.bytes(10));
        at(T0.plus(GRACE).plus(TTL));

        cleaner.getRunnable().run();
        assertFalse(world.hasStaging(begun.uploadId()), "the cleaner's round deletes the expired upload row");
        assertTrue(Files.exists(stray), "and does not look at the folder");
        assertEquals(List.of(), world.store().quarantinedKeys());

        comparison.getRunnable().run();
        assertFalse(Files.exists(stray), "the comparison's round moves the file with no row");
        assertEquals(List.of(strayKey), world.store().quarantinedKeys());
    }

    // ------------------------------------------------------------------------------------------------------------ c11

    private void assertEveryRowExpiresAt(String attachmentId, Instant expiresAt) {
        List<UploadStaging> rows = world.stagingOf(attachmentId);
        assertEquals(3, rows.size(), "the upload rows of the bundle are all there");
        for (UploadStaging row : rows) {
            assertEquals(expiresAt, row.expiresAt(), "file " + row.seq());
            assertEquals(expiresAt, world.uploads().uploadStatus(row.id()).expiresAt(), "the status of file " + row.seq());
        }
    }

    @Test
    void aChunkOfAnyFileOfABundleKeepsAllItsFilesAliveAndAtTheExpiryTheCleanerRemovesThemTogether() throws SQLException {
        List<AttachmentWorld.Item> items = world.items(3, 40);
        AttachmentService.Begun begun = world.beginBundle(ALICE, items);
        String id = begun.attachmentId();
        String zero = begun.files().get(0).uploadId();
        String one = begun.files().get(1).uploadId();
        String two = begun.files().get(2).uploadId();
        assertEquals(T0.plus(TTL), begun.expiresAt());
        assertEveryRowExpiresAt(id, T0.plus(TTL));

        at(T0.plus(Duration.ofMinutes(9)));
        world.chunk(one, 0, items.get(1).content()); // only file 1 is sent
        assertEveryRowExpiresAt(id, T0.plus(Duration.ofMinutes(19))); // file 0 and file 2, which got no chunk, live on with it

        at(T0.plus(TTL)); // the time the begin gave
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap(), "a file that was not sent is not cleaned while another file of its bundle is");
        assertTrue(world.hasStaging(zero));
        assertTrue(world.hasStaging(two));

        at(T0.plus(Duration.ofMinutes(18)));
        world.chunk(zero, 0, items.get(0).content()); // a chunk of file 0 counts them all again
        assertEveryRowExpiresAt(id, T0.plus(Duration.ofMinutes(28)));
        at(T0.plus(Duration.ofMinutes(27)));
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap());
        Path fileOfZero = world.pathOfUpload(zero);
        Path fileOfOne = world.pathOfUpload(one);
        assertTrue(Files.exists(fileOfZero));
        assertTrue(Files.exists(fileOfOne));

        at(T0.plus(Duration.ofMinutes(28))); // the expiry of the bundle: file 2 never had a chunk
        refused(ConflictException.class, ConflictCode.ARTIFACT_EXPIRED, false, () -> world.uploads().finish(id));
        refused(ConflictException.class, ConflictCode.ARTIFACT_EXPIRED, false, () -> world.chunk(two, 0, items.get(2).content()));
        assertEquals(3, world.stagingOf(id).size(), "until the cleaner runs the rows are there");

        AttachmentMaintenance.Reaped reaped = reap();

        assertEquals(new AttachmentMaintenance.Reaped(3, 0), reaped, "the rows of all three files go in one round");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging"));
        assertFalse(Files.exists(fileOfZero), "the files that were written are deleted after their rows");
        assertFalse(Files.exists(fileOfOne));
        assertEquals(List.of(), world.store().storedKeys(), "nothing is left of the bundle in the folder");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().finish(id));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.chunk(one, 1, items.get(1).content()));
        assertEquals(new AttachmentMaintenance.Reaped(0, 0), reap(), "a second round finds nothing");
    }

    private static IntervalTask taskOf(List<IntervalTask> tasks, Duration interval) {
        List<IntervalTask> found = tasks.stream().filter(task -> task.getIntervalDuration().equals(interval)).toList();
        assertEquals(1, found.size(), "one task of " + interval + " in " + tasks);
        return found.get(0);
    }
}
