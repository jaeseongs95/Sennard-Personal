// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;

/**
 * How a chunk is appended to the file of an upload (design 4.4): the lock on the one byte beyond the end of the file, the tail a lost transaction leaves, and a reader that is not
 * stopped by an appender. Real files and real {@link FileChannel}s in the one JVM of the test; two threads are lined up with latches, never with a wait for a time.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 9   b1  the lock is held       another channel of the same JVM holds the append lock (the overlap of two FileChannels): an append and a close are ARTIFACT_BUSY (retryable) and change nothing;
 *                                once the lock is released the same append goes through
 * 9   b2  two threads            one thread holds the lock until the test lets it go (CountDownLatch); the other thread's append is ARTIFACT_BUSY at once, never waits
 * 9   b3  the tail, same chunk   a chunk appended whose row was not updated (the tail): sent again, it is recognised and appends nothing; the row then takes it in
 * 9   b4  the tail, another chunk  another content, the same content at another number, bytes that are no segment, a tail longer than a chunk: ARTIFACT_CHUNK_CONFLICT, and the tail is not cut off
 * 13  b5  a finish with a tail    a finish while a tail is there is ARTIFACT_INCOMPLETE; after the same chunk is sent again the finish passes
 * 4   b6  the file is missing     an append to a file the row says is there is ARTIFACT_CORRUPTED (file-missing); the first chunk may find an empty file made by another request of the upload
 * 15  b7  reading while appending  while the append lock is held, a read of another attachment, a verify, the size and the data range of the same file all answer, with no ARTIFACT_BUSY
 * 15  b8  readers and an appender  while one thread appends chunk after chunk, another thread reads another attachment and asks the size of the appending file; none fails
 * </pre>
 */
class AttachmentFileStoreAppendTest {
    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;
    private static final int OVERHEAD = SegmentCipher.OVERHEAD;

    @TempDir
    Path scratch;

    private AttachmentTestKit kit;
    private AttachmentFileStore store;

    @BeforeEach
    void open() {
        kit = AttachmentTestKit.prepared(scratch);
        store = kit.store();
    }

    private static ConflictException assertBusy(Runnable call) {
        ConflictException busy = assertThrows(ConflictException.class, call::run);
        assertEquals(ConflictCode.ARTIFACT_BUSY, busy.code());
        assertEquals(Boolean.TRUE, busy.details().get("retryable"), "the same request sent again later can succeed");
        return busy;
    }

    // ------------------------------------------------------------------------------------------------------------ b1, b2

    @Test
    void anAppendOrACloseWhileAnotherChannelHoldsTheAppendLockIsBusyAndChangesNothing() throws IOException {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        byte[] next = kit.bytes(20);
        long size = AttachmentTestKit.size(upload.path());

        try (FileChannel holder = FileChannel.open(upload.path(), StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = holder.tryLock(LOCK_POSITION, 1, false)) {
            assertNotNull(lock, "the test took the lock");
            assertBusy(() -> upload.appendLost(next));
            assertBusy(upload::close);
            assertEquals(size, AttachmentTestKit.size(upload.path()), "a busy answer appended nothing");
        }

        upload.append(next);
        assertTrue(AttachmentTestKit.size(upload.path()) > size, "once the lock is released the same chunk is appended");
        assertEquals(60, upload.close().plainBytes());
    }

    @Test
    void anAppendOfAnotherThreadIsBusyAtOnceWhileOneThreadHoldsTheLock() throws Exception {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        byte[] next = kit.bytes(20);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = pool.submit(() -> {
                try (FileChannel channel = FileChannel.open(upload.path(), StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = channel.tryLock(LOCK_POSITION, 1, false)) {
                    assertNotNull(lock);
                    locked.countDown();
                    return release.await(60, TimeUnit.SECONDS);
                }
            });
            assertTrue(locked.await(60, TimeUnit.SECONDS), "the other thread holds the lock");

            assertBusy(() -> upload.appendLost(next));

            release.countDown();
            assertTrue(holder.get(60, TimeUnit.SECONDS), "the holder was let go by the test");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        upload.append(next);
        assertEquals(60, upload.close().plainBytes());
    }

    // ------------------------------------------------------------------------------------------------------------ b3, b4, b5

    @Test
    void aChunkAppendedButNotTakenInIsRecognisedWhenItIsSentAgainAndTheRowThenTakesItIn() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        byte[] chunk = kit.bytes(30);
        long before = upload.stored();

        long lost = upload.appendLost(chunk);
        assertEquals(AttachmentTestKit.size(upload.path()), lost);
        assertEquals(before + OVERHEAD + chunk.length, lost);
        assertEquals(lost, upload.appendLost(chunk), "the same chunk again: the tail is recognised, nothing is appended");
        assertEquals(lost, AttachmentTestKit.size(upload.path()));

        upload.append(chunk); // the row takes the tail in: the answer is the length that includes it
        assertEquals(lost, upload.stored());
        assertEquals(lost, AttachmentTestKit.size(upload.path()), "the chunk is in the file once");
        assertEquals(70, upload.close().plainBytes());
    }

    @Test
    void aTailThatIsAnotherChunkIsAConflictAndIsNotCutOff() throws IOException {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        byte[] chunk = kit.bytes(30);
        upload.appendLost(chunk);
        long withTail = AttachmentTestKit.size(upload.path());

        assertTailConflict(upload, () -> upload.appendLost(kit.bytes(30)));
        assertTailConflict(upload, () -> store.append(upload.storageKey(), upload.keyId(), upload.next() + 1, upload.stored(), chunk, AttachmentTestKit.sha(chunk)));
        assertEquals(withTail, AttachmentTestKit.size(upload.path()), "the tail may be the segment of a request that is still running: it is never cut off");

        AttachmentTestKit.UploadFile shortTail = kit.uploadOf(kit.bytes(40));
        Files.write(shortTail.path(), kit.bytes(3), StandardOpenOption.APPEND);
        assertTailConflict(shortTail, () -> shortTail.appendLost(kit.bytes(5)));

        AttachmentTestKit.UploadFile longTail = kit.uploadOf(kit.bytes(40));
        Files.write(longTail.path(), kit.bytes(AttachmentFileStore.MAX_CHUNK_BYTES + OVERHEAD + 1), StandardOpenOption.APPEND);
        assertTailConflict(longTail, () -> longTail.appendLost(kit.bytes(5)));

        AttachmentTestKit.UploadFile noSegment = kit.uploadOf(kit.bytes(40));
        Files.write(noSegment.path(), kit.bytes(60), StandardOpenOption.APPEND);
        assertTailConflict(noSegment, () -> noSegment.appendLost(kit.bytes(5)));
    }

    private void assertTailConflict(AttachmentTestKit.UploadFile upload, Runnable append) {
        long size = AttachmentTestKit.size(upload.path());
        ConflictException conflict = assertThrows(ConflictException.class, append::run);
        assertEquals(ConflictCode.ARTIFACT_CHUNK_CONFLICT, conflict.code());
        assertEquals("tail", conflict.details().get("reason"));
        assertEquals(Boolean.FALSE, conflict.details().get("retryable"));
        assertEquals(size, AttachmentTestKit.size(upload.path()), "nothing was cut off and nothing appended");
    }

    @Test
    void aFinishWithATailIsIncompleteAndPassesAfterTheSameChunkIsSentAgain() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        byte[] chunk = kit.bytes(30);
        upload.appendLost(chunk);
        long withTail = AttachmentTestKit.size(upload.path());

        ConflictException incomplete = assertThrows(ConflictException.class, upload::close);
        assertEquals(ConflictCode.ARTIFACT_INCOMPLETE, incomplete.code());
        assertEquals(Boolean.FALSE, incomplete.details().get("retryable"), "the chunk has to be sent again; the same finish does not pass");
        assertEquals(withTail, AttachmentTestKit.size(upload.path()));

        upload.append(chunk);
        AttachmentFileStore.Closed closed = upload.close();
        assertEquals(70, closed.plainBytes());
        assertEquals(AttachmentTestKit.sha(upload.plain()), closed.sha256());
    }

    // ------------------------------------------------------------------------------------------------------------ b6

    @Test
    void anAppendToAFileThatIsNotThereIsCorruptedAndTheFirstChunkMayFindAnEmptyFile() throws IOException {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(40));
        kit.remove(upload.path());

        IntegrityException missing = assertThrows(IntegrityException.class, () -> upload.appendLost(kit.bytes(5)));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, missing.code());
        assertEquals("file-missing", missing.details().get("reason"));

        AttachmentTestKit.UploadFile first = kit.upload();
        kit.folders().ensureSubfolder(first.path().getParent());
        kit.folders().createOwnerOnlyFile(first.path()); // another request of the same upload made the file first
        first.append(kit.bytes(10));
        assertEquals(10, first.close().plainBytes());
    }

    // ------------------------------------------------------------------------------------------------------------ b7, b8

    @Test
    void whileTheAppendLockIsHeldAReadAVerifyTheSizeAndTheDataRangeAllAnswer() throws IOException {
        byte[] otherPlain = kit.bytes(500);
        AttachmentTestKit.UploadFile other = kit.closedUploadOf(otherPlain);
        AttachmentTestKit.UploadFile appending = kit.uploadOf(kit.bytes(40), kit.bytes(60));
        long size = AttachmentTestKit.size(appending.path());

        try (FileChannel holder = FileChannel.open(appending.path(), StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = holder.tryLock(LOCK_POSITION, 1, false)) {
            assertNotNull(lock, "an append to the upload is in progress: it holds the lock");
            assertArrayEquals(otherPlain, store.read(other.storageKey(), other.keyId(), 0, 1000, 500).data(), "the read of another attachment");
            assertEquals(AttachmentTestKit.sha(otherPlain), store.verify(other.storageKey(), other.keyId(), 500, AttachmentTestKit.sha(otherPlain)).sha256());
            assertEquals(size, store.sizeOf(appending.storageKey()), "the size of the appending file");
            assertTrue(store.exists(appending.storageKey()));
            assertNotNull(store.modifiedAt(appending.storageKey()));
            assertEquals(size, Files.readAllBytes(appending.path()).length, "the data range of the appending file itself is read: only the byte beyond its end is locked");
        }
    }

    @Test
    void whileOneThreadAppendsChunkAfterChunkAnotherThreadReadsAndAsksTheSizeWithNoBusyAnswer() throws Exception {
        byte[] otherPlain = kit.bytes(3000);
        AttachmentTestKit.UploadFile other = kit.closedUploadOf(otherPlain);
        AttachmentTestKit.UploadFile appending = kit.uploadOf(kit.bytes(10));
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> appender = pool.submit(() -> {
                try {
                    for (int chunk = 0; chunk < 30; chunk++) {
                        appending.append(kit.bytes(4096));
                    }
                    return appending.stored();
                } finally {
                    done.set(true);
                }
            });
            Future<Integer> reader = pool.submit(() -> {
                int reads = 0;
                long lastSize = -1;
                do {
                    assertArrayEquals(otherPlain, store.read(other.storageKey(), other.keyId(), 0, 4000, 3000).data());
                    long size = store.sizeOf(appending.storageKey());
                    assertTrue(size >= lastSize, "the file only grows");
                    lastSize = size;
                    reads++;
                } while (!done.get());
                return reads;
            });
            long storedAtTheEnd = appender.get(120, TimeUnit.SECONDS);
            assertEquals(storedAtTheEnd, appending.stored());
            assertTrue(reader.get(120, TimeUnit.SECONDS) >= 1);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(AttachmentTestKit.size(appending.path()), appending.stored());
        assertEquals(10 + 30 * 4096, appending.close().plainBytes());
    }
}
