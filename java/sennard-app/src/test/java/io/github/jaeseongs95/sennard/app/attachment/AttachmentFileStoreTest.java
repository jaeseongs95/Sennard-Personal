// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The bodies of the attachments as files (design 4.3, 4.4, 7): writing, closing, reading, tampering, re-encrypting, keeping a local file, and the folder's own operations. The real file
 * system and the real cipher, no database. The appending rules (the file lock, the tail) are in {@link AttachmentFileStoreAppendTest}.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 1   a1  normal file         chunks of 100, 1, 262144 (the largest) and 37 bytes, closed: read in pages of any size gives the plain bytes, the hash is the plain bytes' hash, the end flag is
 *                             set on the last page only, and {@code verify} agrees
 * 1   a2  segment format      the file is one segment per chunk and an end mark, each with its length prefix; the end mark costs only the overhead
 * 4   a3  a changed byte      one changed byte in the IV, the ciphertext, the tag or the length prefix of any segment: read and verify say ARTIFACT_CORRUPTED
 * 4   a4  segments reordered  two segments swapped: ARTIFACT_CORRUPTED
 * 4   a5  a cut end           the end mark removed; the end mark and the last chunk removed; a cut in the middle of a segment; an empty file; bytes after the end mark: ARTIFACT_CORRUPTED
 * 5   a6  another file's body  the bytes of another attachment put in its place: ARTIFACT_CORRUPTED (the storage key is in the additional data)
 * 6   a7  names and content   the storage key is 128 random bits in hex, the file is named by it alone (no extension, no original name), in a folder of its first two characters;
 *                             no run of the plain bytes is in the file
 * 3/19 a8 a wrong file is not closed   a wrong sha256 or size is ARTIFACT_HASH_MISMATCH, too few bytes ARTIFACT_INCOMPLETE; none of them appends the end mark; the upload then goes on and is closed
 * 18  a9  closing again       a file whose end mark is already appended (the transaction of the finish was lost) is checked and closed again without a second end mark; a tail that is not the
 *                             end mark (a chunk, an end mark of another number or without the last mark) is ARTIFACT_INCOMPLETE
 * 4   a10 the row says more than the file  a file shorter than the row says, and a segment count that is not the row's, are ARTIFACT_CORRUPTED
 * 16  a11 re-encryption       rewrite opens the old file with its key, writes the same plain segments under the active key in a file of a new storage key, which reads the same and has
 *                             the same hash; the old file is not touched; a wrong expectation writes nothing and leaves no new file
 * 8   a13 the folder          delete, quarantine, restore, purge, and the lists; a missing file is -1, false or empty, never an error; a storage key that is not 32 hex digits is refused
 * 3.1 a14 the disk margin     a write that would leave less than the margin is ARTIFACT_STORAGE_FULL with the space it judged from
 * 4   a15 a missing file      reading a file that is not there is ARTIFACT_CORRUPTED, reason file-missing
 * </pre>
 */
class AttachmentFileStoreTest {
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
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

    private static void assertCorrupted(Runnable call) {
        IntegrityException failure = assertThrows(IntegrityException.class, call::run);
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, failure.code());
        assertEquals(Boolean.FALSE, failure.details().get("retryable"));
    }

    private AttachmentFileStore.Page readAll(AttachmentTestKit.UploadFile upload, int total) {
        return store.read(upload.storageKey(), upload.keyId(), 0, total + 1000, total);
    }

    /** Reads the whole file in pages of {@code pageBytes}; fails the test when a page before the last says it is the end. */
    private byte[] readInPages(AttachmentTestKit.UploadFile upload, long total, int pageBytes) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        long offset = 0;
        boolean ended = false;
        while (!ended) {
            AttachmentFileStore.Page page = store.read(upload.storageKey(), upload.keyId(), offset, pageBytes, total);
            assertEquals(offset, page.offset());
            assertEquals(total, page.size());
            whole.writeBytes(page.data());
            offset += page.data().length;
            ended = page.end();
            assertEquals(offset >= total, ended, "the end flag is set on the page that reaches the end of the file, and on no other");
        }
        return whole.toByteArray();
    }

    // ------------------------------------------------------------------------------------------------------------ a1, a2

    @Test
    void aFileOfSeveralChunksIsReadBackWholeAndInPagesOfAnySize() {
        byte[][] chunks = {kit.bytes(100), kit.bytes(1), kit.bytes(AttachmentFileStore.MAX_CHUNK_BYTES), kit.bytes(37)};
        AttachmentTestKit.UploadFile upload = kit.uploadOf(chunks);
        byte[] plain = upload.plain();
        AttachmentFileStore.Closed closed = upload.close();

        assertEquals(plain.length, closed.plainBytes());
        assertEquals(AttachmentTestKit.sha(plain), closed.sha256());
        assertEquals(AttachmentTestKit.size(upload.path()), closed.storedBytes());
        assertEquals(upload.stored() + OVERHEAD, closed.storedBytes(), "closing adds the end mark and nothing else");
        for (int pageBytes : new int[] {4096, 65537, 262144, 262145, plain.length, plain.length + 5000}) {
            assertArrayEquals(plain, readInPages(upload, plain.length, pageBytes), "pages of " + pageBytes);
        }
        AttachmentFileStore.Page whole = readAll(upload, plain.length);
        assertTrue(whole.end());
        AttachmentFileStore.Page middle = store.read(upload.storageKey(), upload.keyId(), 99, 3, plain.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(plain, 99, 102), middle.data(), "a page across the boundary of two segments");
        assertFalse(middle.end());
        AttachmentFileStore.Page atEnd = store.read(upload.storageKey(), upload.keyId(), plain.length, 10, plain.length);
        assertEquals(0, atEnd.data().length);
        assertTrue(atEnd.end());
        assertEquals(closed, store.verify(upload.storageKey(), upload.keyId(), plain.length, AttachmentTestKit.sha(plain)));
    }

    @Test
    void aSmallFileIsReadInPagesOfOneByteAndSevenBytes() {
        AttachmentTestKit.UploadFile upload = kit.closedUploadOf(kit.bytes(100), kit.bytes(1), kit.bytes(37));
        byte[] plain = upload.plain();

        for (int pageBytes : new int[] {1, 7, 100, 101, 138}) {
            assertArrayEquals(plain, readInPages(upload, plain.length, pageBytes), "pages of " + pageBytes);
        }
    }

    @Test
    void aFileIsOneSegmentPerChunkAndAnEndMarkEachWithItsLengthPrefix() {
        byte[] first = kit.bytes(10);
        byte[] second = kit.bytes(250);
        AttachmentTestKit.UploadFile upload = kit.closedUploadOf(first, second);

        List<byte[]> segments = AttachmentTestKit.segments(AttachmentTestKit.read(upload.path()));
        assertEquals(3, segments.size(), "two chunks and the end mark");
        assertEquals(OVERHEAD + first.length, segments.get(0).length);
        assertEquals(OVERHEAD + second.length, segments.get(1).length);
        assertEquals(OVERHEAD, segments.get(2).length, "the end mark has no plain bytes");
        for (byte[] segment : segments) {
            assertEquals(segment.length - SegmentCipher.LENGTH_BYTES, ByteBuffer.wrap(segment).getInt());
        }
    }

    // ------------------------------------------------------------------------------------------------------------ a3, a4, a5, a6

    private AttachmentTestKit.UploadFile threeChunks() {
        return kit.closedUploadOf(kit.bytes(100), kit.bytes(50), kit.bytes(70));
    }

    private void assertBothSayCorrupted(AttachmentTestKit.UploadFile upload, long total, String sha) {
        assertCorrupted(() -> store.read(upload.storageKey(), upload.keyId(), 0, (int) total + 100, total));
        assertCorrupted(() -> store.verify(upload.storageKey(), upload.keyId(), total, sha));
    }

    @Test
    void oneChangedByteInAnySegmentIsCorrupted() {
        AttachmentTestKit.UploadFile upload = threeChunks();
        long total = upload.plain().length;
        String sha = upload.sha256();
        byte[] original = AttachmentTestKit.read(upload.path());
        List<byte[]> segments = AttachmentTestKit.segments(original);
        int offset = 0;
        for (byte[] segment : segments) {
            int[] places = {0, 3, SegmentCipher.LENGTH_BYTES, SegmentCipher.LENGTH_BYTES + SegmentCipher.IV_BYTES, segment.length - 1};
            for (int place : places) {
                kit.replace(upload.path(), AttachmentTestKit.flipped(original, offset + place));
                assertBothSayCorrupted(upload, total, sha);
            }
            offset += segment.length;
        }
        kit.replace(upload.path(), original);
        assertEquals(total, readAll(upload, (int) total).data().length, "control: the original bytes are read again");
    }

    @Test
    void segmentsInAnotherOrderAreCorrupted() {
        AttachmentTestKit.UploadFile upload = threeChunks();
        long total = upload.plain().length;
        String sha = upload.sha256();
        byte[] original = AttachmentTestKit.read(upload.path());
        List<byte[]> segments = AttachmentTestKit.segments(original);

        kit.replace(upload.path(), AttachmentTestKit.join(List.of(segments.get(1), segments.get(0), segments.get(2), segments.get(3))));
        assertBothSayCorrupted(upload, total, sha);
        kit.replace(upload.path(), AttachmentTestKit.join(List.of(segments.get(0), segments.get(0), segments.get(2), segments.get(3))));
        assertBothSayCorrupted(upload, total, sha);
        kit.replace(upload.path(), AttachmentTestKit.join(List.of(segments.get(0), segments.get(2), segments.get(1), segments.get(3))));
        assertBothSayCorrupted(upload, total, sha);
    }

    @Test
    void aFileWhoseEndIsCutIsCorrupted() {
        AttachmentTestKit.UploadFile upload = threeChunks();
        long total = upload.plain().length;
        String sha = upload.sha256();
        byte[] original = AttachmentTestKit.read(upload.path());
        List<byte[]> segments = AttachmentTestKit.segments(original);

        kit.replace(upload.path(), AttachmentTestKit.head(original, original.length - OVERHEAD));
        assertBothSayCorrupted(upload, total, sha);
        IntegrityException noMark = assertThrows(IntegrityException.class, () -> readAll(upload, (int) total));
        assertEquals("end-mark", noMark.details().get("reason"), "the last segment is a chunk, not the end mark");

        kit.replace(upload.path(), AttachmentTestKit.head(original, original.length - OVERHEAD - segments.get(2).length));
        assertBothSayCorrupted(upload, total, sha);

        kit.replace(upload.path(), AttachmentTestKit.head(original, original.length - 5));
        assertBothSayCorrupted(upload, total, sha);

        kit.replace(upload.path(), AttachmentTestKit.head(original, 2));
        assertBothSayCorrupted(upload, total, sha);

        kit.replace(upload.path(), new byte[0]);
        assertBothSayCorrupted(upload, total, sha);
        IntegrityException empty = assertThrows(IntegrityException.class, () -> readAll(upload, (int) total));
        assertEquals("end-mark-missing", empty.details().get("reason"));

        kit.replace(upload.path(), AttachmentTestKit.concat(original, kit.bytes(10)));
        assertBothSayCorrupted(upload, total, sha);
    }

    @Test
    void theBytesOfAnotherAttachmentPutInItsPlaceAreCorrupted() {
        byte[] sameSize = kit.bytes(150);
        AttachmentTestKit.UploadFile mine = kit.closedUploadOf(sameSize);
        AttachmentTestKit.UploadFile other = kit.closedUploadOf(kit.bytes(150));
        byte[] othersBytes = AttachmentTestKit.read(other.path());

        kit.replace(mine.path(), othersBytes);
        assertCorrupted(() -> readAll(mine, 150));
        assertCorrupted(() -> store.verify(mine.storageKey(), mine.keyId(), 150, AttachmentTestKit.sha(sameSize)));
        assertEquals(150, readAll(other, 150).data().length, "the other attachment is read as before");
    }

    // ------------------------------------------------------------------------------------------------------------ a7

    @Test
    void theStorageKeyIsRandomAndTheFileHasNoExtensionNoOriginalNameAndNoPlainBytes() throws IOException {
        byte[] marker = "TOP-SECRET-PLAINTEXT-0123456789-ABCDEFGHIJKLMNOP".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int index = 0; index < 20; index++) {
            body.writeBytes(marker);
        }
        AttachmentTestKit.UploadFile upload = kit.closedUploadOf(body.toByteArray());

        assertTrue(HEX32.matcher(upload.storageKey()).matches(), "128 bits in lower-case hex: " + upload.storageKey());
        Path file = upload.path();
        assertEquals(upload.storageKey(), file.getFileName().toString(), "the file is named by its storage key alone");
        assertFalse(file.getFileName().toString().contains("."), "no extension");
        assertEquals(upload.storageKey().substring(0, 2), file.getParent().getFileName().toString(), "the folder is the first two characters");
        assertFalse(AttachmentTestKit.contains(AttachmentTestKit.read(file), marker), "no run of the plain bytes is in the file");
        assertFalse(AttachmentTestKit.contains(AttachmentTestKit.read(file), java.util.Arrays.copyOf(marker, 16)), "not even a part of it");
        try (Stream<Path> walk = Files.walk(kit.properties().directory())) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size());
            for (Path each : files) {
                assertTrue(HEX32.matcher(each.getFileName().toString()).matches(), "every file of the folder is named by a storage key: " + each.getFileName());
            }
        }
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            keys.add(store.newStorageKey());
        }
        assertEquals(500, keys.size(), "the storage keys are random, not counted");
    }

    // ------------------------------------------------------------------------------------------------------------ a8, a9, a10

    @Test
    void aFileWhoseHashIsWrongIsNotClosedAndTheUploadGoesOn() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(100), kit.bytes(40));
        long before = AttachmentTestKit.size(upload.path());
        String otherSha = AttachmentTestKit.sha(kit.bytes(140));

        IntegrityException wrongSha = assertThrows(IntegrityException.class, () -> upload.close(140, otherSha));
        assertEquals(IntegrityCode.ARTIFACT_HASH_MISMATCH, wrongSha.code());
        assertEquals("sha256", wrongSha.details().get("reason"));
        assertEquals(Boolean.FALSE, wrongSha.details().get("retryable"));
        IntegrityException tooMany = assertThrows(IntegrityException.class, () -> upload.close(139, upload.sha256()));
        assertEquals(IntegrityCode.ARTIFACT_HASH_MISMATCH, tooMany.code());
        assertEquals("size", tooMany.details().get("reason"));
        assertEquals(before, AttachmentTestKit.size(upload.path()), "no end mark was appended: the bytes are left as they were");

        upload.append(kit.bytes(10)); // the file is still open for appending
        assertEquals(150, upload.close().plainBytes());
    }

    @Test
    void aFileWithFewerBytesThanDeclaredIsIncompleteAndIsClosedOnceTheRestArrives() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(100));
        long before = AttachmentTestKit.size(upload.path());
        byte[] rest = kit.bytes(50);
        String wholeSha = AttachmentTestKit.sha(AttachmentTestKit.concat(upload.plain(), rest));

        ConflictException incomplete = assertThrows(ConflictException.class, () -> upload.close(150, wholeSha));
        assertEquals(ConflictCode.ARTIFACT_INCOMPLETE, incomplete.code());
        assertEquals("bytes-missing", incomplete.details().get("reason"));
        assertEquals(Boolean.FALSE, incomplete.details().get("retryable"), "the same finish sent again does not pass: the missing chunks are to be sent");
        assertEquals(before, AttachmentTestKit.size(upload.path()));

        upload.append(rest);
        AttachmentFileStore.Closed closed = upload.close(150, wholeSha);
        assertEquals(150, closed.plainBytes());
        assertEquals(wholeSha, closed.sha256());
    }

    @Test
    void aFileWhoseEndMarkIsAlreadyAppendedIsClosedAgainWithoutASecondEndMark() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(100), kit.bytes(60));
        AttachmentFileStore.Closed first = upload.close();
        long afterFirst = AttachmentTestKit.size(upload.path());

        AttachmentFileStore.Closed again = upload.close(); // the facts of the row are those from before the end mark: the transaction of the first finish was lost
        assertEquals(first, again);
        assertEquals(afterFirst, AttachmentTestKit.size(upload.path()), "no second end mark");
        assertEquals(upload.stored() + OVERHEAD, afterFirst);
        assertEquals(160, readAll(upload, 160).data().length);
    }

    @Test
    void aTailThatIsNotTheEndMarkIsIncompleteForAFinish() throws IOException {
        AttachmentTestKit.UploadFile chunkTail = kit.uploadOf(kit.bytes(100));
        chunkTail.appendLost(kit.bytes(30));
        assertTailIncomplete(chunkTail);

        AttachmentTestKit.UploadFile wrongNumber = kit.uploadOf(kit.bytes(100));
        byte[] key = kit.keys().key(wrongNumber.keyId());
        Files.write(wrongNumber.path(), kit.cipher().seal(key, wrongNumber.storageKey(), wrongNumber.next() + 1, true, new byte[0]), java.nio.file.StandardOpenOption.APPEND);
        assertTailIncomplete(wrongNumber);

        AttachmentTestKit.UploadFile notLast = kit.uploadOf(kit.bytes(100));
        byte[] notLastKey = kit.keys().key(notLast.keyId());
        Files.write(notLast.path(), kit.cipher().seal(notLastKey, notLast.storageKey(), notLast.next(), false, new byte[0]), java.nio.file.StandardOpenOption.APPEND);
        assertTailIncomplete(notLast);

        AttachmentTestKit.UploadFile garbage = kit.uploadOf(kit.bytes(100));
        Files.write(garbage.path(), kit.bytes(OVERHEAD), java.nio.file.StandardOpenOption.APPEND);
        assertTailIncomplete(garbage);
    }

    private void assertTailIncomplete(AttachmentTestKit.UploadFile upload) {
        long size = AttachmentTestKit.size(upload.path());
        ConflictException incomplete = assertThrows(ConflictException.class, upload::close);
        assertEquals(ConflictCode.ARTIFACT_INCOMPLETE, incomplete.code());
        assertEquals("tail", incomplete.details().get("reason"));
        assertEquals(size, AttachmentTestKit.size(upload.path()), "nothing was cut off and nothing appended");
    }

    @Test
    void aFileShorterThanTheRowSaysAndASegmentCountThatIsNotTheRowsAreCorrupted() {
        AttachmentTestKit.UploadFile upload = kit.uploadOf(kit.bytes(100), kit.bytes(60));

        IntegrityException shortFile = assertThrows(IntegrityException.class,
                () -> store.close(upload.storageKey(), upload.keyId(), upload.stored() + 1, upload.next(), 160, upload.sha256()));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, shortFile.code());
        assertEquals("file-short", shortFile.details().get("reason"));
        IntegrityException count = assertThrows(IntegrityException.class,
                () -> store.close(upload.storageKey(), upload.keyId(), upload.stored(), upload.next() + 1, 160, upload.sha256()));
        assertEquals("segment-count", count.details().get("reason"));
        IntegrityException appendShort = assertThrows(IntegrityException.class,
                () -> store.append(upload.storageKey(), upload.keyId(), upload.next(), upload.stored() + 1, kit.bytes(5), AttachmentTestKit.sha(kit.bytes(5))));
        assertEquals("file-short", appendShort.details().get("reason"));
    }

    // ------------------------------------------------------------------------------------------------------------ a11

    @Test
    void aFileIsWrittenAgainUnderTheActiveKeyAndTheOldFileIsNotTouched() {
        AttachmentTestKit withA = AttachmentTestKit.prepared(scratch.resolve("rekey"), "A", "A", "B");
        byte[][] chunks = {withA.bytes(120), withA.bytes(80), withA.bytes(3)};
        AttachmentTestKit.UploadFile old = withA.closedUploadOf(chunks);
        byte[] plain = old.plain();
        byte[] oldBytes = AttachmentTestKit.read(old.path());
        AttachmentTestKit withB = withA.withActiveKey("B");

        AttachmentFileStore.Written written = withB.store().rewrite(old.storageKey(), "A", plain.length, old.sha256());

        assertEquals("B", written.keyId());
        assertNotEquals(old.storageKey(), written.storageKey());
        assertTrue(HEX32.matcher(written.storageKey()).matches());
        assertEquals(plain.length, written.size());
        assertEquals(old.sha256(), written.sha256(), "the plain bytes' hash is the same");
        byte[] copied = withB.store().read(written.storageKey(), "B", 0, plain.length + 10, plain.length).data();
        assertArrayEquals(plain, copied);
        assertEquals(old.sha256(), withB.store().verify(written.storageKey(), "B", plain.length, old.sha256()).sha256());
        assertEquals(oldBytes.length, AttachmentTestKit.size(old.path()), "the old file is not touched");
        assertArrayEquals(oldBytes, AttachmentTestKit.read(old.path()));
        assertArrayEquals(plain, withB.store().read(old.storageKey(), "A", 0, plain.length + 10, plain.length).data(), "and still reads with its own key");
        assertEquals(oldBytes.length, AttachmentTestKit.size(withB.fileOf(written.storageKey())), "the same segments, only sealed again");
        assertFalse(AttachmentTestKit.contains(AttachmentTestKit.read(withB.fileOf(written.storageKey())), plain), "no plain bytes");
        IntegrityException wrongKey = assertThrows(IntegrityException.class, () -> withB.store().read(written.storageKey(), "A", 0, 10, plain.length));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, wrongKey.code());
    }

    @Test
    void aRewriteThatFindsTheOldFileOrItsExpectationWrongWritesNothingAndLeavesNoNewFile() {
        AttachmentTestKit withA = AttachmentTestKit.prepared(scratch.resolve("rekey"), "A", "A", "B");
        AttachmentTestKit.UploadFile old = withA.closedUploadOf(withA.bytes(120), withA.bytes(80));
        AttachmentTestKit withB = withA.withActiveKey("B");
        List<String> before = withB.store().storedKeys();

        IntegrityException wrongSha = assertThrows(IntegrityException.class, () -> withB.store().rewrite(old.storageKey(), "A", 200, AttachmentTestKit.sha(withB.bytes(200))));
        assertEquals("sha256", wrongSha.details().get("reason"));
        IntegrityException wrongSize = assertThrows(IntegrityException.class, () -> withB.store().rewrite(old.storageKey(), "A", 199, old.sha256()));
        assertEquals("size", wrongSize.details().get("reason"));
        assertEquals(before, withB.store().storedKeys(), "the half-written new file is removed");

        byte[] oldBytes = AttachmentTestKit.read(old.path());
        withB.replace(old.path(), AttachmentTestKit.flipped(oldBytes, 20));
        IntegrityException damaged = assertThrows(IntegrityException.class, () -> withB.store().rewrite(old.storageKey(), "A", 200, old.sha256()));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, damaged.code(), "a damaged old file is not written again");
        assertEquals(before, withB.store().storedKeys());
    }

    // ------------------------------------------------------------------------------------------------------------ a13, a14, a15

    @Test
    void theFolderDeletesQuarantinesRestoresAndPurgesAndAMissingFileIsNeverAnError() {
        AttachmentTestKit.UploadFile kept = kit.closedUploadOf(kit.bytes(30));
        AttachmentTestKit.UploadFile stray = kit.closedUploadOf(kit.bytes(30));
        String missing = store.newStorageKey();

        assertTrue(store.exists(kept.storageKey()));
        assertEquals(AttachmentTestKit.size(kept.path()), store.sizeOf(kept.storageKey()));
        assertEquals(-1, store.sizeOf(missing));
        assertFalse(store.exists(missing));
        assertFalse(store.delete(missing), "nothing to delete");
        assertFalse(store.quarantine(missing));
        assertFalse(store.restore(missing));
        assertEquals(List.of(kept.storageKey(), stray.storageKey()).stream().sorted().toList(), store.storedKeys());
        assertEquals(List.of(), store.quarantinedKeys());

        assertTrue(store.quarantine(stray.storageKey()), "a file that has no row is moved, not deleted");
        assertFalse(store.exists(stray.storageKey()));
        assertEquals(List.of(stray.storageKey()), store.quarantinedKeys());
        assertEquals(List.of(kept.storageKey()), store.storedKeys(), "the quarantine folder is not the attachment folder's content");
        assertTrue(Files.isRegularFile(kit.properties().directory().resolve(AttachmentFileStore.ORPHANS).resolve(stray.storageKey())), "the file keeps its name");

        assertTrue(store.restore(stray.storageKey()), "a storage key that has a row again is moved back");
        assertTrue(store.exists(stray.storageKey()));
        assertEquals(List.of(), store.quarantinedKeys());
        assertEquals(30, store.read(stray.storageKey(), stray.keyId(), 0, 100, 30).data().length);

        assertTrue(store.quarantine(stray.storageKey()));
        assertEquals(List.of(stray.storageKey()), store.purgeQuarantine(), "the names of what was deleted are returned, for the audit log");
        assertEquals(List.of(), store.quarantinedKeys());
        assertTrue(store.delete(kept.storageKey()), "a closed (read-only) file is deleted");
        assertFalse(store.exists(kept.storageKey()));
    }

    @Test
    void aStorageKeyThatIsNotMadeOf32HexDigitsIsRefusedAsAPath() {
        for (String bad : new String[] {"../escape", "0123", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcdef.bin", "zz23456789abcdef0123456789abcdef"}) {
            UnavailableException failure = assertThrows(UnavailableException.class, () -> store.exists(bad), bad);
            assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, failure.code());
            assertEquals("storage-key", failure.details().get("reason"));
        }
    }

    @Test
    void aWriteThatWouldLeaveTheDiskUnderTheMarginIsRefusedWithTheSpaceItJudgedFrom() {
        AttachmentTestKit tight = AttachmentTestKit.prepared(
                AttachmentTestKit.properties(scratch.resolve("tight"), "primary", DataSize.ofMegabytes(64), DataSize.ofTerabytes(1_000_000)), scratch.resolve("tight"), "primary");

        UnavailableException full = assertThrows(UnavailableException.class, () -> tight.store().requireSpace(1));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_FULL, full.code());
        assertEquals(Boolean.FALSE, full.details().get("retryable"), "the owner has to free the disk");
        assertEquals(1L, full.details().get("requestedBytes"));
        assertEquals(DataSize.ofTerabytes(1_000_000).toBytes(), full.details().get("marginBytes"));
        assertTrue((Long) full.details().get("usableBytes") > 0, "the usable space is what the disk said");
        UnavailableException append = assertThrows(UnavailableException.class, () -> tight.uploadOf(tight.bytes(10)));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_FULL, append.code(), "an append is refused the same way");
    }

    @Test
    void readingAFileThatIsNotThereIsCorruptedAndNamesTheMissingFile() {
        AttachmentTestKit.UploadFile upload = kit.closedUploadOf(kit.bytes(20));
        kit.remove(upload.path());

        IntegrityException failure = assertThrows(IntegrityException.class, () -> readAll(upload, 20));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, failure.code());
        assertEquals("file-missing", failure.details().get("reason"));
        IntegrityException verify = assertThrows(IntegrityException.class, () -> store.verify(upload.storageKey(), upload.keyId(), 20, upload.sha256()));
        assertEquals("file-missing", verify.details().get("reason"));
    }
}
