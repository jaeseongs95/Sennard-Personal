// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.ALICE;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.refused;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.Advised;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentStorageRepository;
import io.github.jaeseongs95.sennard.app.attachment.UploadStaging;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;

/**
 * Two requests of one upload at the same moment, and the cleaner against a chunk or a finish (design 4.4, 7 and test 11), on the real PostgreSQL and the real folder.
 *
 * <p>The overlap of test 11 is made the way the other race tests of this module make it ({@code AbstractBoardServiceTest}, {@code ContinuityFirstTaskRaceTest}): one of the two
 * statements is the <em>holder</em>, run inside a transaction of the test that has not committed, so that its row lock is held; the other is run on another thread and the test waits
 * until the database itself says (through {@link LockWaits}) that this statement is blocked by the holder's connection, never for a time. Then the holder commits and the database decides
 * what the blocked statement finds. The statements are the product's own service methods ({@code takeInChunk}, {@code deleteExpired}, {@code completeFinish}) and its own cleaner round
 * ({@code reap}), run in the order the entrances run them; nothing of the product is bypassed.
 *
 * <p>The outcome is judged by the invariants of the design: no row is left without its file (an upload in progress that took bytes in has its file, an attachment has its current file),
 * and nothing is completed and then gone. Two orders per pair, so that each of the two statements is the one that wins once.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 9   r1  a held lock              the append lock is held by another channel: a chunk is ARTIFACT_BUSY (retryable) and so is a finish; nothing is appended; afterwards the same chunk passes
 * 9   r2  the same chunk twice, same content   both requests saw the same next number: the second one's file step finds the first's segment as its tail and appends nothing, and its row
 *                                  statement changes no row and answers as the first (idempotent); the file has the segment once; the finish passes
 * 9   r3  the same chunk twice, other content  the second one's file step is ARTIFACT_CHUNK_CONFLICT (the tail is not cut), and a row statement of the other content is a conflict (lost-race);
 *                                  the first content stays and the finish passes with its hash
 * 9   r4  two threads              the same chunk sent by two threads released together ten times: each answers as the first or is ARTIFACT_BUSY; at least one passes; the row has the chunk once
 *                                  and the file is what the row says; a BUSY one sent again is idempotent; the finish passes
 * 11  r5  chunk wins, cleaner second   a chunk taken in (its transaction holds the row) while the cleaner's DELETE waits: the DELETE finds the new expiry and deletes nothing; the upload and its
 *                                  file stay and the finish passes
 * 11  r6  cleaner wins, chunk second   the cleaner's DELETE (holding) while the chunk's row statement waits: the chunk finds no row (ARTIFACT_NOT_FOUND); after the file step of the cleaner nothing
 *                                  is left of the upload
 * 11  r7  finish wins, cleaner second  a finish completed (holding) while the cleaner's DELETE waits: the DELETE finds COMPLETED and deletes nothing; the attachment and its file stay and read
 * 11  r8  cleaner wins, finish second  the cleaner's DELETE (holding) while the finish's statement waits: the finish ends ARTIFACT_EXPIRED; no attachment is made and nothing is left of the upload
 * 6/10 r9  two begins, same key        the first begin (holding the (owner, operation key) unique key) while the second one's insert waits: the loser is ARTIFACT_BUSY (retryable, reason begin-in-progress),
 *                                  never the store's raw conflict; one upload exists; the same begin sent again answers with the winner's uploadId (and attachment id)
 * 16  r10 two copies of one file   (an attachment is a bundle of files, its copies are attachment_storage rows (attachment, seq, rev)) two re-encryptions of file 1 that read the same highest rev: the
 *                                  first one's addCopy (holding) writes rev 1, the second one's insert of the same key triple waits and ends in the translated duplicate-key conflict
 *                                  (ConflictCode.DUPLICATE); rev 0 and the first one's rev 1 are the only rows of the file, the current copy is the first one's, file 0 is untouched, and a
 *                                  later addCopy is rev 2
 * 11  r11 finish during folder comparison   the first copy lookup sees no row, then finish commits; the new current copy stays in place and reads back intact
 * </pre>
 *
 * In r8 the finish's own "now" must be one millisecond before the expiry (a finish at the expiry is expired by its own statement, which does not wait for anyone) and the cleaner's the expiry:
 * the clock is moved back for the finish after the cleaner's statement was made. It stands for the finish that read its time just before the expiry and was then held up (a pause of the
 * process, a slow connection); the order of the two statements in the database is the part the test is about.
 */
@BusinessDbTest
class AttachmentRaceTest {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;

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

    // ------------------------------------------------------------------------------------------------------------ helpers

    private AttachmentService.ChunkPlan.Next plan(String id, long sequence, byte[] chunk) {
        return assertInstanceOf(AttachmentService.ChunkPlan.Next.class, world.service().planChunk(id, sequence, AttachmentWorld.sha(chunk), chunk.length));
    }

    private long append(UploadStaging row, long sequence, byte[] chunk) {
        return world.store().append(row.storageKey(), row.keyId(), sequence, row.storedBytes(), chunk, AttachmentWorld.hex(chunk));
    }

    /** What a statement that was blocked by the holder found once the holder ended: its result, or its failure. */
    private record Outcome<W>(W value, ExecutionException failure) {
    }

    private static void hold(CountDownLatch latch) {
        try {
            assertTrue(latch.await(120, TimeUnit.SECONDS), "a latch of the test was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the transaction open", interrupted);
        }
    }

    /**
     * Runs {@code holderStatement} in a transaction of the test that stays open, runs {@code beforeWaiter}, starts {@code waiterCall} on another thread, waits until the database says that
     * the waiter is blocked by the holder's connection, lets the holder commit and returns what the waiter found.
     */
    private <W> Outcome<W> overlap(Runnable holderStatement, Runnable beforeWaiter, Callable<W> waiterCall) throws Exception {
        TransactionTemplate holderTransaction = new TransactionTemplate(world.transactionManager());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the holder's connection, taken inside its transaction
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> holderTransaction.executeWithoutResult(status -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(world.dataSource()));
                holderStatement.run();
                holding.countDown();
                hold(release);
            }));
            assertTrue(holding.await(120, TimeUnit.SECONDS), "the holder has made its statement and holds its row");
            beforeWaiter.run();
            Future<W> waiter = pool.submit(waiterCall);
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(120), "the statement of the other request did not come to wait for the holder's row");
            assertFalse(waiter.isDone(), "the other request waits for the holder's commit");
            release.countDown();
            holder.get(120, TimeUnit.SECONDS);
            try {
                return new Outcome<>(waiter.get(120, TimeUnit.SECONDS), null);
            } catch (ExecutionException failure) {
                return new Outcome<>(null, failure);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** An upload of {@code content} as one chunk that the row has not taken in yet: the chunk's own plan and its file step have run when this returns. */
    private record Prepared(String id, String attachmentId, UploadStaging row, long stored, byte[] content) {
    }

    // ------------------------------------------------------------------------------------------------------------ r1

    @Test
    void anAppendOrAFinishWhileTheAppendLockIsHeldIsBusyAndTheSameChunkPassesAfterwards() throws Exception {
        byte[] first = world.bytes(30);
        byte[] second = world.bytes(50);
        byte[] content = AttachmentWorld.concat(first, second);
        AttachmentWorld.Single begun = world.begin(ALICE, "busy.bin", content);
        String id = begun.uploadId();
        world.chunk(id, 0, first);
        Path file = world.pathOfUpload(id);
        long size = AttachmentWorld.size(file);

        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = holder.tryLock(LOCK_POSITION, 1, false)) {
            assertNotNull(lock, "the lock of an append that is in progress");
            refused(ConflictException.class, ConflictCode.ARTIFACT_BUSY, true, () -> world.chunk(id, 1, second));
            refused(ConflictException.class, ConflictCode.ARTIFACT_BUSY, true, () -> world.uploads().finish(begun.attachmentId()));
            assertEquals(size, AttachmentWorld.size(file), "a busy answer appended nothing");
            assertEquals(30L, world.uploads().uploadStatus(id).received());
            assertEquals(1L, world.uploads().uploadStatus(id).sequence());
        }

        assertEquals(new AttachmentService.Progress(id, 1, 80), world.chunk(id, 1, second));
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 64));
    }

    // ------------------------------------------------------------------------------------------------------------ r2, r3, r4

    @Test
    void ofTwoRequestsThatSawTheSameNextChunkTheSecondIsAnsweredAsTheFirstWhenTheContentIsTheSame() {
        byte[] chunk = world.bytes(40);
        AttachmentWorld.Single begun = world.begin(ALICE, "same.bin", chunk);
        String id = begun.uploadId();
        UploadStaging seenByFirst = plan(id, 0, chunk).row();
        UploadStaging seenBySecond = plan(id, 0, chunk).row(); // the row had no chunk yet when both read it

        long storedByFirst = append(seenByFirst, 0, chunk);
        AttachmentService.Progress first = world.service().takeInChunk(id, 0, storedByFirst, 40, AttachmentWorld.sha(chunk));

        long storedBySecond = append(seenBySecond, 0, chunk); // its file step meets the first one's segment as its tail: the same chunk, nothing is appended
        assertEquals(storedByFirst, storedBySecond);
        AttachmentService.Progress second = world.service().takeInChunk(id, 0, storedBySecond, 40, AttachmentWorld.sha(chunk)); // its statement changes no row

        assertEquals(first, second, "the loser of the same content is answered as the first");
        assertEquals(storedByFirst, AttachmentWorld.size(world.pathOfUpload(id)), "the segment is in the file once");
        UploadStaging row = world.staging(id);
        assertEquals(1L, row.nextSequence());
        assertEquals(40L, row.receivedBytes());
        assertEquals(storedByFirst, row.storedBytes());
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(chunk, world.readAll(kept.attachmentId(), 64));
    }

    @Test
    void ofTwoRequestsThatSawTheSameNextChunkTheSecondOfAnotherContentIsAConflictAndTheFirstContentStays() {
        byte[] chunk = world.bytes(40);
        byte[] other = world.bytes(40);
        AttachmentWorld.Single begun = world.begin(ALICE, "other.bin", chunk);
        String id = begun.uploadId();
        UploadStaging seenByFirst = plan(id, 0, chunk).row();
        UploadStaging seenBySecond = plan(id, 0, other).row();

        long stored = append(seenByFirst, 0, chunk);
        world.service().takeInChunk(id, 0, stored, 40, AttachmentWorld.sha(chunk));
        Path file = world.pathOfUpload(id);

        ConflictException tail = refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> append(seenBySecond, 0, other));
        assertEquals("tail", tail.details().get("reason"));
        assertEquals(stored, AttachmentWorld.size(file), "the first one's segment is not cut off, and the other chunk is not appended");
        ConflictException lost = refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false,
                () -> world.service().takeInChunk(id, 0, stored, 40, AttachmentWorld.sha(other)));
        assertEquals("lost-race", lost.details().get("reason"), "the row statement of the other content changes no row and is not answered as taken in");
        assertEquals(1L, world.staging(id).nextSequence());

        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(chunk, world.readAll(kept.attachmentId(), 64), "the finish passes with the hash of the first content");
    }

    @Test
    void theSameChunkSentByTwoThreadsReleasedTogetherLeavesOneAppendAndEachAnswersAsTheFirstOrIsBusy() throws Exception {
        for (int round = 0; round < 10; round++) {
            byte[] chunk = world.bytes(200);
            AttachmentWorld.Single begun = world.begin(ALICE, "threads-" + round + ".bin", chunk);
            String id = begun.uploadId();
            CyclicBarrier together = new CyclicBarrier(2);
            List<AttachmentService.Progress> answers = new ArrayList<>();
            List<ExecutionException> failures = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                List<Future<AttachmentService.Progress>> sent = new ArrayList<>();
                for (int sender = 0; sender < 2; sender++) {
                    sent.add(pool.submit(() -> {
                        together.await(60, TimeUnit.SECONDS);
                        return world.chunk(id, 0, chunk);
                    }));
                }
                for (Future<AttachmentService.Progress> one : sent) {
                    try {
                        answers.add(one.get(120, TimeUnit.SECONDS));
                    } catch (ExecutionException failure) {
                        failures.add(failure);
                    }
                }
            }
            for (ExecutionException failure : failures) {
                ConflictException busy = assertInstanceOf(ConflictException.class, failure.getCause(), "a request that lost the lock is told so: " + failure.getCause());
                assertEquals(ConflictCode.ARTIFACT_BUSY, busy.code());
                assertEquals(Boolean.TRUE, busy.details().get("retryable"));
            }
            assertFalse(answers.isEmpty(), "one of the two passes");
            for (AttachmentService.Progress answer : answers) {
                assertEquals(new AttachmentService.Progress(id, 0, 200), answer);
            }
            if (!failures.isEmpty()) {
                assertEquals(new AttachmentService.Progress(id, 0, 200), world.chunk(id, 0, chunk), "a request that was told BUSY and sent again is answered as the first");
            }
            UploadStaging row = world.staging(id);
            assertEquals(1L, row.nextSequence(), "the chunk is in the row once");
            assertEquals(200L, row.receivedBytes());
            assertEquals(row.storedBytes(), AttachmentWorld.size(world.pathOfUpload(id)), "the file is what the row says: the segment once");
            AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
            assertArrayEquals(chunk, world.readAll(kept.attachmentId(), 64), "round " + round);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ r5, r6 (test 11a)

    /** The upload of one chunk, planned and appended at the last millisecond before its expiry; the clock then stands at the expiry. */
    private Prepared justBeforeTheExpiry() {
        byte[] content = world.bytes(60);
        AttachmentWorld.Single begun = world.begin(ALICE, "race.bin", content);
        String id = begun.uploadId();
        Instant expires = begun.expiresAt();
        assertEquals(AttachmentWorld.T0.plus(TTL), expires);
        world.clock().set(expires.minusMillis(1));
        UploadStaging row = plan(id, 0, content).row(); // the request arrived just before the expiry: it is not expired for the chunk
        long stored = append(row, 0, content);
        world.clock().set(expires);
        return new Prepared(id, begun.attachmentId(), row, stored, content);
    }

    @Test
    void aChunkTakenInJustBeforeTheCleanerDeletesKeepsTheUploadAndItsFile() throws Exception {
        Prepared upload = justBeforeTheExpiry();
        Instant expires = AttachmentWorld.T0.plus(TTL);

        // the chunk's row statement is the holder (it has the row); the cleaner's round listed the upload as expired and its DELETE waits
        Outcome<AttachmentMaintenance.Reaped> outcome = overlap(
                () -> world.service().takeInChunk(upload.id(), 0, upload.stored(), upload.content().length, AttachmentWorld.sha(upload.content())),
                () -> { },
                () -> world.maintenance().reap());

        assertNull(outcome.failure(), "the cleaner's round ends without failing");
        assertEquals(0, outcome.value().expiredUploads(), "its DELETE saw the new expiry, not the listed one, and deleted nothing");
        UploadStaging row = world.staging(upload.id());
        assertEquals(UploadStaging.State.STAGING, row.state());
        assertEquals(1L, row.nextSequence());
        assertEquals(expires.plus(TTL), row.expiresAt(), "the chunk gave the upload the time it counts again from");
        assertEquals(upload.stored(), row.storedBytes());
        assertTrue(Files.exists(world.pathOfUpload(upload.id())), "the file is there");
        assertEquals(upload.stored(), AttachmentWorld.size(world.pathOfUpload(upload.id())));
        world.assertNoRowWithoutItsFile();
        AttachmentService.Kept kept = world.uploads().finish(upload.attachmentId());
        assertArrayEquals(upload.content(), world.readAll(kept.attachmentId(), 64));
    }

    @Test
    void theCleanerDeletingJustBeforeAChunkTakesItInLeavesNothingOfTheUpload() throws Exception {
        Prepared upload = justBeforeTheExpiry();
        AtomicBoolean deleted = new AtomicBoolean();

        // the cleaner's DELETE is the holder; the chunk's row statement waits for the row
        Outcome<AttachmentService.Progress> outcome = overlap(
                () -> deleted.set(world.service().deleteExpired(upload.id())),
                () -> { },
                () -> world.service().takeInChunk(upload.id(), 0, upload.stored(), upload.content().length, AttachmentWorld.sha(upload.content())));

        assertTrue(deleted.get(), "the cleaner's statement deleted the one row");
        assertTrue(world.store().delete(upload.row().storageKey()), "after a 1 the cleaner deletes the file");
        NotFoundException gone = assertInstanceOf(NotFoundException.class, outcome.failure().getCause(), "the chunk finds no row");
        assertEquals(NotFoundCode.ARTIFACT_NOT_FOUND, gone.code());
        assertFalse(world.hasStaging(upload.id()));
        assertFalse(Files.exists(world.pathOf(upload.row().storageKey())), "no file is left");
        assertEquals(0, countAttachments());
        world.assertNoRowWithoutItsFile();
        assertEquals(List.of(), world.store().storedKeys());
    }

    // ------------------------------------------------------------------------------------------------------------ r7, r8 (test 11b)

    /** The upload of one chunk, sent, whose finish has run its file step at the last millisecond before the expiry; the file is closed and the row does not know. */
    private AttachmentFileStore.Closed closedJustBeforeTheExpiry(String attachmentId, Instant expires) {
        world.clock().set(expires.minusMillis(1));
        AttachmentService.FinishPlan.Pending pending = assertInstanceOf(AttachmentService.FinishPlan.Pending.class, world.service().planFinish(attachmentId));
        assertEquals(1, pending.rows().size(), "a bundle of one file");
        UploadStaging row = pending.rows().get(0);
        return world.store().close(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(), row.declaredSize(), row.declaredSha256());
    }

    @Test
    void aFinishCompletedJustBeforeTheCleanerDeletesKeepsTheAttachmentAndItsFile() throws Exception {
        byte[] content = world.bytes(60);
        AttachmentWorld.Single begun = world.begin(ALICE, "finish-wins.bin", content);
        String id = begun.uploadId();
        String attachmentId = begun.attachmentId();
        world.chunk(id, 0, content);
        Instant expires = begun.expiresAt();
        AttachmentFileStore.Closed closed = closedJustBeforeTheExpiry(attachmentId, expires);
        AtomicReference<AttachmentService.Kept> kept = new AtomicReference<>();

        // the finish's row statement is the holder; the cleaner (its clock now at the expiry) listed the upload as expired and its DELETE waits
        Outcome<AttachmentMaintenance.Reaped> outcome = overlap(
                () -> kept.set(world.service().completeFinish(attachmentId, List.of(closed))),
                () -> world.clock().set(expires),
                () -> world.maintenance().reap());

        assertNull(outcome.failure());
        assertEquals(0, outcome.value().expiredUploads(), "its DELETE found a COMPLETED row and deleted nothing");
        assertEquals(UploadStaging.State.COMPLETED, world.staging(id).state(), "the same round preserves the completed replay row");
        assertEquals(AttachmentWorld.kept(attachmentId, List.of(new AttachmentWorld.Item("finish-wins.bin", content))), kept.get());
        assertEquals(1, countAttachments());
        assertTrue(Files.exists(world.pathOfAttachment(attachmentId)), "the attachment's file is there");
        assertArrayEquals(content, world.readAll(attachmentId, 64), "and reads");
        assertEquals(List.of(), world.maintenance().check());
    }

    @Test
    void theCleanerDeletingJustBeforeAFinishCompletesLeavesNoAttachmentAndNothingOfTheUpload() throws Exception {
        byte[] content = world.bytes(60);
        AttachmentWorld.Single begun = world.begin(ALICE, "cleaner-wins.bin", content);
        String id = begun.uploadId();
        String attachmentId = begun.attachmentId();
        world.chunk(id, 0, content);
        Instant expires = begun.expiresAt();
        AttachmentFileStore.Closed closed = closedJustBeforeTheExpiry(attachmentId, expires);
        String storageKey = world.staging(id).storageKey();
        world.clock().set(expires);
        AtomicBoolean deleted = new AtomicBoolean();

        // the cleaner's DELETE is the holder; the finish (its time read before the expiry) waits for the row
        Outcome<AttachmentService.Kept> outcome = overlap(
                () -> deleted.set(world.service().deleteExpired(id)),
                () -> world.clock().set(expires.minusMillis(1)),
                () -> world.service().completeFinish(attachmentId, List.of(closed)));

        assertTrue(deleted.get());
        assertTrue(world.store().delete(storageKey), "after a 1 the cleaner deletes the file");
        ConflictException expired = assertInstanceOf(ConflictException.class, outcome.failure().getCause(), "the finish finds no row to complete");
        assertEquals(ConflictCode.ARTIFACT_EXPIRED, expired.code());
        assertEquals(Boolean.FALSE, expired.details().get("retryable"));
        assertEquals(0, countAttachments(), "no attachment was made");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        assertFalse(world.hasStaging(id));
        assertFalse(Files.exists(world.pathOf(storageKey)));
        world.assertNoRowWithoutItsFile();
        assertEquals(List.of(), world.store().storedKeys());
    }

    @Test
    void ofTwoBeginsOfTheSameAuthorAndOperationKeyTheLoserIsBusyAndRetryableAndTheSameBeginSentAgainAnswersWithTheWinnersUpload() throws Exception {
        byte[] content = world.bytes(40);
        String operationKey = AttachmentWorld.operationKey();
        AttachmentService.BeginRequest request = world.request(operationKey, "twice.bin", content.length, AttachmentWorld.sha(content));
        AtomicReference<AttachmentService.Begun> winner = new AtomicReference<>();

        // the first begin inserts its row and holds the (owner, operation key) unique key; the second one found no row before that and its insert waits for the holder's commit
        Outcome<AttachmentService.Begun> outcome = overlap(
                () -> winner.set(world.service().begin(ALICE, request)),
                () -> { },
                () -> world.uploads().begin(ALICE, request));

        assertNotNull(winner.get(), "the holder's begin made the upload");
        assertNull(outcome.value(), "the loser made no upload");
        ConflictException busy = assertInstanceOf(ConflictException.class, outcome.failure().getCause(), "a defined tool error, not the store's raw unique violation");
        assertEquals(ConflictCode.ARTIFACT_BUSY, busy.code());
        assertEquals(Boolean.TRUE, busy.details().get("retryable"));
        assertEquals("begin-in-progress", busy.details().get("reason"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.upload_staging WHERE operation_key = '" + operationKey + "'"), "one upload, from the winner");

        AttachmentService.Begun again = world.uploads().begin(ALICE, request);
        assertEquals(winner.get().files().get(0).uploadId(), again.files().get(0).uploadId(), "the same begin sent again answers with the winner's uploadId");
        assertEquals(winner.get().attachmentId(), again.attachmentId(), "and with the winner's attachment id");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.upload_staging WHERE operation_key = '" + operationKey + "'"));
    }

    // ------------------------------------------------------------------------------------------------------------ r10 (two copies of one file)

    @Test
    void ofTwoCopiesAddedToTheSameFileAtTheSameMomentTheSecondEndsInTheDuplicateKeyConflictAndTheFirstIsRevOne() throws Exception {
        List<AttachmentWorld.Item> items = world.items(2, 40);
        AttachmentService.Kept kept = world.uploadBundle(ALICE, items, 40);
        String id = kept.attachmentId();
        String keyId = world.keys().activeKeyId();
        String winnerKey = world.store().newStorageKey();
        String loserKey = world.store().newStorageKey();
        AtomicInteger winnerRev = new AtomicInteger(-1);

        // two re-encryptions of file 1 both read rev 0 as the highest: the first adds (attachment, 1, 1) and holds it; the second one's insert of the same key triple waits for the holder's commit
        Outcome<Integer> outcome = overlap(
                () -> winnerRev.set(world.service().addCopy(id, 1, winnerKey, keyId)),
                () -> { },
                () -> world.service().addCopy(id, 1, loserKey, keyId));

        assertEquals(1, winnerRev.get(), "the holder's copy is the one after rev 0");
        assertNull(outcome.value(), "the second one added no copy");
        ConflictException duplicate = assertInstanceOf(ConflictException.class, outcome.failure().getCause(), "the service lets the translated unique-key error through");
        assertEquals(ConflictCode.DUPLICATE, duplicate.code());
        assertEquals(2, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = 1"), "rev 0 and the first one's rev 1");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = 1 AND rev = 1 AND storage_key = '" + winnerKey + "'"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE storage_key = '" + loserKey + "'"), "the second one's copy has no row");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = 0"), "file 0 was not touched");
        assertEquals(winnerKey, world.service().view(id, 1).orElseThrow().current().storageKey(), "the current copy is the highest rev: the first one's");
        assertEquals(AttachmentService.UseKind.UNREFERENCED, world.service().useOf(loserKey).kind(), "no row names the copy the second one would have added; its file, if it was written, is a file with no row");
        assertEquals(2, world.service().addCopy(id, 1, world.store().newStorageKey(), keyId), "a later copy is the one after the winner's");
    }

    @Test
    void aFinishBetweenTheFirstCopyLookupAndTheStagingLookupKeepsTheCurrentFileInPlace() throws Exception {
        world.close();
        world = AttachmentWorld.open(scratch, Map.of("orphan-grace", "0s"));
        byte[] content = world.bytes(50);
        AttachmentWorld.Single begun = world.begin(ALICE, "finish-during-reconcile.bin", content);
        world.chunk(begun.uploadId(), 0, content);
        Path file = world.pathOfUpload(begun.uploadId());
        String key = world.staging(begun.uploadId()).storageKey();
        Advised repository = assertInstanceOf(Advised.class, world.bean(AttachmentStorageRepository.class));
        AtomicBoolean finished = new AtomicBoolean();

        try (ExecutorService finisher = Executors.newSingleThreadExecutor()) {
            MethodInterceptor finishAfterFirstCopyLookup = invocation -> {
                Object copy = invocation.proceed();
                if ("byStorageKey".equals(invocation.getMethod().getName()) && key.equals(invocation.getArguments()[0]) && finished.compareAndSet(false, true)) {
                    assertEquals(Optional.empty(), copy, "the first copy lookup precedes the finish");
                    finisher.submit(() -> {
                        world.uploads().finish(begun.attachmentId());
                        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));
                        return null;
                    }).get(120, TimeUnit.SECONDS);
                }
                return copy;
            };
            repository.addAdvice(0, finishAfterFirstCopyLookup);
            try {
                assertEquals(new AttachmentMaintenance.Reconciled(0, 0), world.maintenance().reconcileFolder());
                assertTrue(finished.get(), "the finish committed during reconciliation");
            } finally {
                repository.removeAdvice(finishAfterFirstCopyLookup);
            }
        }
        assertEquals(AttachmentService.UseKind.IN_USE, world.service().useOf(key).kind());
        assertTrue(Files.exists(file));
        assertTrue(world.store().quarantinedKeys().isEmpty());
        assertArrayEquals(content, world.readAll(begun.attachmentId(), 64));
    }

    private long countAttachments() {
        try {
            return world.count("SELECT count(*) FROM sennard.attachment");
        } catch (SQLException cause) {
            throw new IllegalStateException(cause);
        }
    }
}
