// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.ALICE;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.BOB;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.refused;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads;
import io.github.jaeseongs95.sennard.app.attachment.UploadStaging;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;

/**
 * The upload tools of the attachments (design 3, 4.4, 6) on the real business database and the real attachment folder: {@code AttachmentUploads} over {@code AttachmentService} and the
 * file store, the way the tools call them, with the application's own context ({@link AttachmentWorld}). Where a test needs "the service fails after the file was appended" it calls the
 * product's own steps in the order the entrance does and stops where the transaction would have been lost: nothing of the product is bypassed or replaced.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * An attachment is a bundle of files (V21): one begin lists the files and answers one attachment id and one upload id per file (numbers 0, 1, 2 ... in the order of the list); one finish
 * of the attachment id ends them all. A begin of one file is a bundle of one file, so f1 to f12 are bundles of one file (file 0); f13 to f16 are the bundles of several files (the expiry of a bundle is c11 of AttachmentCleanerTest, its re-encryption k5 and k6 of AttachmentKeysRekeyTest and r10 of AttachmentRaceTest, its limits l6 to l8 of AttachmentLimitsTest).
 *
 * 1   f1  normal              begin, four chunks (100, 1, 250 and 349 bytes), upload status in between, finish, attachment status, read in pages: the plain hash is the declared one; the
 *                             attachment id is not the upload's id; the rows are attachment 1, attachment_file 1, attachment_storage 1 (rev 0); status has no path and no storage key
 * 1   f2  the largest chunk   a chunk of 262144 bytes and a small one; a chunk over the largest, and a base64 that is none, are VALIDATION_FAILED and take nothing in
 * 2   f3  order and replay    a chunk out of order is ARTIFACT_CHUNK_CONFLICT; the last chunk sent again with the same content is answered as before and appends nothing; with another content
 *                             it is ARTIFACT_CHUNK_CONFLICT; a number before the last one is a conflict even with the same content; a wrong chunk hash is CHUNK_HASH_MISMATCH and takes nothing in;
 *                             a chunk of an unknown upload id is ARTIFACT_NOT_FOUND (a chunk and a finish are bound to the upload by its id alone, no author is checked: see f3b)
 * 2   f3b a chunk and a finish by id alone   the chunks (by upload id) and the finish (by attachment id) of two authors' bundles are taken whoever sends them (the call carries no author);
 *                             each attachment's author stays the owner of its begin; an unknown id is ARTIFACT_NOT_FOUND for a chunk and a finish; a finish sent again after the bundle is finished
 *                             answers the same attachment (the rows gone as well: see AttachmentCleanerTest), and the id of an upload is no attachment id for a finish
 * 3   f4  declared hash/size  a declared sha256 that is not the bytes' hash: finish is ARTIFACT_HASH_MISMATCH and the bytes stay; more bytes than declared: refused at the chunk
 *                             (nothing appended) and, when the row has them anyway, at the finish
 * 4   f5  tampered files      one changed byte in every segment, segments in another order, an end mark cut off, the end mark and the last chunk cut off, a mid-segment cut, bytes
 *                             after the end mark: read says ARTIFACT_CORRUPTED, the check reports the attachment as damaged, and nothing is deleted
 * 5   f6  another file        the bytes of another attachment of the same size put in a file's place: ARTIFACT_CORRUPTED; the other attachment is read as before
 * 6   f7  names and content   the file of an attachment is named by its random storage key alone (no extension, no original name, a folder of two characters), and neither the plain bytes
 *                             nor the original file name are in any file of the folder
 * 13  f8  a lost chunk        a chunk appended but never taken into the row: finish is ARTIFACT_INCOMPLETE (not retryable); sending the same chunk again takes it in; then the finish passes
 * 18  f9  a lost finish       the file closed (the end mark appended) but the attachment not made: the finish sent again passes, the end mark is in the file once, and a third finish answers
 *                             the same attachment
 * 19  f10 finish checks       too few bytes: ARTIFACT_INCOMPLETE, the rest sent, the finish passes; a declared hash that is wrong: ARTIFACT_HASH_MISMATCH; in both the file has no end mark
 *                             and the row is STAGING
 * 19  f10b a finish before any chunk   ARTIFACT_INCOMPLETE (bytes-missing), the row STAGING; the finish passes once the bytes arrive (the file does not exist yet at the first finish)
 * 3   f11 begin             the same author and operationKey with the same content give the same attachment and upload, with another content ARTIFACT_OPERATION_CONFLICT (another author may use
 *                             the key); a purpose that Sennard does not keep is ARTIFACT_UNSUPPORTED_PURPOSE, evidence or none is kept; an unknown upload or attachment is ARTIFACT_NOT_FOUND
 * 1   f12 an empty file       a file of no bytes is kept through one empty chunk
 * 1   f13 a bundle of files   one begin of three files: one attachment id and upload ids of the numbers 0, 1, 2; every file chunked; ONE finish answers a Kept with the three files (number, name,
 *                             media type, size, sha256); the rows are attachment 1, attachment_file 3, attachment_storage 3 all rev 0, upload_staging 3 COMPLETED; the artifact read of file 0, 1
 *                             and 2 gives each file's own bytes (the files of one size differ); a read of a number the bundle has not (3) and of file 0 of an unknown attachment are
 *                             ARTIFACT_NOT_FOUND; the check finds nothing wrong, and the bytes of the files are in three files of the folder named by three storage keys
 * 19  f14 a file that fails the finish   the second of three files has fewer bytes than declared (ARTIFACT_INCOMPLETE) or another sha256 than declared (ARTIFACT_HASH_MISMATCH): the finish fails
 *                             and NO attachment, attachment_file or attachment_storage row exists, every upload row is STAGING; after the missing chunk of the second file the same finish
 *                             passes (resume), and the rows are all there; a wrong sha256 stays refused as often as the finish is sent
 * 3   f15 idempotence of a bundle   the same begin again (same author, operationKey, files) answers the same attachment id and upload ids; another list (a file more, a file fewer, the files in
 *                             another order, another size or name or sha256 of one file) under the key is ARTIFACT_OPERATION_CONFLICT; another author may use the key; a chunk sent again
 *                             answers as before; a finish sent twice answers the same Kept; a finish sent after the cleaner removed the COMPLETED upload rows answers the same Kept
 * 3   f16 a chunk of a file  a chunk of file 1 is taken whoever sends it and does not touch file 0's upload rows (received, next sequence); the chunk of an upload of a finished bundle is
 *                             ARTIFACT_CHUNK_CONFLICT; the upload status of each file names its attachment and its number
 * </pre>
 */
@BusinessDbTest
class AttachmentFlowTest {
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    /** The end mark's segment: the 4-byte length, the 12-byte IV and the 16-byte tag. */
    private static final int END_MARK = 4 + 12 + 16;

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

    private static byte[] part(byte[] content, int from, int length) {
        return java.util.Arrays.copyOfRange(content, from, from + length);
    }

    /** An attachment of three chunks (100, 50 and 70 bytes): its id, with the content. */
    private record Stored(String id, byte[] content) {
    }

    private Stored threeChunks() {
        byte[] content = world.bytes(220);
        AttachmentWorld.Single begun = world.begin(ALICE, "three.bin", content);
        world.chunk(begun.uploadId(), 0, part(content, 0, 100));
        world.chunk(begun.uploadId(), 1, part(content, 100, 50));
        world.chunk(begun.uploadId(), 2, part(content, 150, 70));
        assertEquals(begun.attachmentId(), world.uploads().finish(begun.attachmentId()).attachmentId());
        return new Stored(begun.attachmentId(), content);
    }

    // ------------------------------------------------------------------------------------------------------------ f1, f2

    @Test
    void aNormalUploadIsReadBackWithTheSamePlainHash() throws SQLException {
        byte[] content = world.bytes(700);
        AttachmentWorld.Single begun = world.begin(ALICE, "report.txt", content);
        assertEquals(AttachmentFileStore.MAX_CHUNK_BYTES, begun.maxChunkBytes(), "the largest chunk is a constant of the contract");
        assertEquals(AttachmentWorld.T0.plus(10, ChronoUnit.MINUTES), begun.expiresAt(), "the upload TTL is ten minutes from the begin");
        assertNotEquals(begun.attachmentId(), begun.uploadId(), "the attachment the finish makes and the upload of its file are two ids");

        int[] sizes = {100, 1, 250, 349};
        int from = 0;
        for (int sequence = 0; sequence < sizes.length; sequence++) {
            AttachmentService.Progress progress = world.chunk(begun.uploadId(), sequence, part(content, from, sizes[sequence]));
            from += sizes[sequence];
            assertEquals(new AttachmentService.Progress(begun.uploadId(), sequence, from), progress);
            AttachmentService.UploadStatus status = world.uploads().uploadStatus(begun.uploadId());
            assertEquals(begun.uploadId(), status.uploadId());
            assertEquals(begun.attachmentId(), status.attachmentId());
            assertEquals(0, status.seq());
            assertEquals("STAGING", status.state());
            assertEquals("report.txt", status.fileName());
            assertEquals(from, status.received());
            assertEquals(sequence + 1, status.sequence());
            assertEquals(700, status.size());
            assertEquals(AttachmentWorld.sha(content), status.sha256());
        }

        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertEquals(AttachmentWorld.kept(begun.attachmentId(), List.of(new AttachmentWorld.Item("report.txt", content))), kept, "the attachment has the id the begin answered");
        AttachmentService.AttachmentStatus attachment = world.uploads().attachmentStatus(kept.attachmentId());
        assertEquals(kept.attachmentId(), attachment.attachmentId());
        assertEquals(kept.files(), attachment.files(), "the status of an attachment is its files: no path and no storage key");
        assertEquals("report.txt", attachment.files().get(0).fileName());
        assertEquals(AttachmentWorld.sha(content), attachment.files().get(0).sha256());

        for (int pageBytes : new int[] {1, 128, 699, 700, 701, 5000}) {
            byte[] read = world.readAll(kept.attachmentId(), pageBytes);
            assertArrayEquals(content, read, "pages of " + pageBytes);
            assertEquals(AttachmentWorld.hex(content), AttachmentWorld.hex(read), "the plain hash is the declared one");
        }
        var read = world.uploads().read(kept.attachmentId(), 0, 0, 1000);
        assertTrue(read.page().end());
        assertEquals(700, read.view().file().sizeBytes());
        assertEquals(AttachmentWorld.hex(content), read.view().file().contentSha256(), "the row holds the hash of the plain bytes");
        assertEquals(0, read.view().current().rev(), "the copy the finish wrote is rev 0");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_file WHERE seq = 0"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE seq = 0 AND rev = 0"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'COMPLETED'"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'STAGING'"));
    }

    @Test
    void acceptsSegmentsWithinTheLimitAndRejectsLargerSegmentsOrInvalidHashes() {
        byte[] content = world.bytes(AttachmentFileStore.MAX_CHUNK_BYTES + 5);
        AttachmentWorld.Single begun = world.begin(ALICE, "big.bin", content);

        world.chunk(begun.uploadId(), 0, part(content, 0, AttachmentFileStore.MAX_CHUNK_BYTES));
        world.chunk(begun.uploadId(), 1, part(content, AttachmentFileStore.MAX_CHUNK_BYTES, 5));
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 100000));

        byte[] over = world.bytes(AttachmentFileStore.MAX_CHUNK_BYTES + 1);
        AttachmentWorld.Single other = world.begin(ALICE, "over.bin", over);
        InvalidInputException tooLarge = assertThrows(InvalidInputException.class,
                () -> world.uploads().chunk(other.uploadId(), 0, over, AttachmentWorld.sha(over)));
        assertEquals(InputCode.VALIDATION_FAILED, tooLarge.code());
        InvalidInputException noHash = assertThrows(InvalidInputException.class, () -> world.uploads().chunk(other.uploadId(), 0, new byte[3], "not-a-sha256"));
        assertEquals(InputCode.VALIDATION_FAILED, noHash.code());
        assertEquals(0L, world.uploads().uploadStatus(other.uploadId()).received(), "nothing was taken in");
        assertFalse(Files.exists(world.pathOfUpload(other.uploadId())), "nothing was appended");
    }

    // ------------------------------------------------------------------------------------------------------------ f3

    @Test
    void aChunkOutOfOrderIsAConflictTheLastChunkAgainIsAnsweredAsBeforeAndAWrongChunkHashTakesNothingIn() {
        byte[] first = world.bytes(40);
        byte[] second = world.bytes(40);
        byte[] third = world.bytes(40);
        byte[] content = AttachmentWorld.concat(first, second, third);
        AttachmentWorld.Single begun = world.begin(ALICE, "order.bin", content);
        String id = begun.uploadId();

        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(id, 1, second));
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(id, 2, third));
        assertFalse(Files.exists(world.pathOfUpload(id)), "a refused chunk is never appended");

        AttachmentService.Progress zero = world.chunk(id, 0, first);
        long afterFirst = AttachmentWorld.size(world.pathOfUpload(id));
        assertEquals(zero, world.chunk(id, 0, first), "the last chunk again with the same content: answered as before");
        assertEquals(afterFirst, AttachmentWorld.size(world.pathOfUpload(id)), "and appended nowhere");
        assertEquals(40L, world.uploads().uploadStatus(id).received());
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(id, 0, world.bytes(40)));
        assertEquals(afterFirst, AttachmentWorld.size(world.pathOfUpload(id)));

        world.chunk(id, 1, second);
        long afterSecond = AttachmentWorld.size(world.pathOfUpload(id));
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(id, 0, first)); // a number before the last one, with its own content
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(id, 3, third)); // a number that skips one
        refused(IntegrityException.class, IntegrityCode.CHUNK_HASH_MISMATCH, false,
                () -> world.uploads().chunk(id, 2, third, AttachmentWorld.sha(world.bytes(40))));
        assertEquals(afterSecond, AttachmentWorld.size(world.pathOfUpload(id)), "a chunk whose hash is wrong takes nothing in");
        assertEquals(80L, world.uploads().uploadStatus(id).received());

        world.chunk(id, 2, third);
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 64));
    }

    // ------------------------------------------------------------------------------------------------------------ f4

    @Test
    void aDeclaredHashThatIsNotTheBytesHashIsRefusedAtTheFinishAndTheBytesStay() throws SQLException {
        byte[] content = world.bytes(100);
        AttachmentWorld.Single begun = AttachmentWorld.single(world.uploads().begin(ALICE, world.request(AttachmentWorld.operationKey(), "wrong.bin", 100, AttachmentWorld.sha(world.bytes(100)))));
        String id = begun.uploadId();
        world.chunk(id, 0, content);
        Path file = world.pathOfUpload(id);
        long size = AttachmentWorld.size(file);

        IntegrityException mismatch = refused(IntegrityException.class, IntegrityCode.ARTIFACT_HASH_MISMATCH, false, () -> world.uploads().finish(begun.attachmentId()));
        assertEquals("sha256", mismatch.details().get("reason"));
        assertTrue(Files.exists(file), "the bytes stay");
        assertEquals(size, AttachmentWorld.size(file), "and are not closed");
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state());
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_storage"));
    }

    @Test
    void moreBytesThanDeclaredAreRefusedAtTheChunkAndAtTheFinishWhenTheRowHasThemAnyway() throws SQLException {
        byte[] content = world.bytes(100);
        AttachmentWorld.Single refusedAtTheChunk = AttachmentWorld.single(world.uploads().begin(ALICE, world.request(AttachmentWorld.operationKey(), "small.bin", 90, AttachmentWorld.sha(content))));
        IntegrityException early = refused(IntegrityException.class, IntegrityCode.ARTIFACT_HASH_MISMATCH, false, () -> world.chunk(refusedAtTheChunk.uploadId(), 0, content));
        assertEquals("size", early.details().get("reason"));
        assertFalse(Files.exists(world.pathOfUpload(refusedAtTheChunk.uploadId())), "nothing was appended");

        // the same upload, composed the way the entrance runs but past the chunk's own size check: the bytes are in the file and in the row
        AttachmentWorld.Single begun = AttachmentWorld.single(world.uploads().begin(ALICE, world.request(AttachmentWorld.operationKey(), "small2.bin", 90, AttachmentWorld.sha(content))));
        String id = begun.uploadId();
        UploadStaging row = world.staging(id);
        long stored = world.store().append(row.storageKey(), row.keyId(), 0, row.storedBytes(), content, AttachmentWorld.hex(content));
        world.service().takeInChunk(id, 0, stored, content.length, AttachmentWorld.sha(content));
        Path file = world.pathOfUpload(id);
        long size = AttachmentWorld.size(file);

        IntegrityException late = refused(IntegrityException.class, IntegrityCode.ARTIFACT_HASH_MISMATCH, false, () -> world.uploads().finish(begun.attachmentId()));
        assertEquals("size", late.details().get("reason"));
        assertEquals(size, AttachmentWorld.size(file), "the bytes stay, not closed");
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state());
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"));
    }

    // ------------------------------------------------------------------------------------------------------------ f5, f6

    private void assertCorruptedAndReported(Stored stored) {
        refused(IntegrityException.class, IntegrityCode.ARTIFACT_CORRUPTED, false, () -> world.uploads().read(stored.id(), 0, 0, 1000));
        List<AttachmentMaintenance.Finding> findings = world.maintenance().check();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("damaged", findings.get(0).kind());
        assertEquals(stored.id(), findings.get(0).id(), "a finding about a file names the attachment");
        assertEquals(0, findings.get(0).seq(), "a finding about a file names the number of the file");
        assertEquals("ARTIFACT_CORRUPTED", findings.get(0).detail());
        assertTrue(Files.exists(world.pathOfAttachment(stored.id())), "the check reports and deletes nothing");
    }

    @Test
    void aChangedByteReorderedSegmentsAndACutEndAreReportedAsCorruptedAndNothingIsDeleted() throws SQLException {
        Stored stored = threeChunks();
        Path file = world.pathOfAttachment(stored.id());
        byte[] original = AttachmentWorld.read(file);
        List<byte[]> segments = AttachmentWorld.segments(original);
        assertEquals(4, segments.size(), "three chunks and the end mark");
        assertEquals(List.of(), world.maintenance().check(), "control: the file is whole");

        int offset = 0;
        for (byte[] segment : segments) {
            world.replace(file, AttachmentWorld.flipped(original, offset + segment.length - 1)); // the last byte of the segment, its tag
            assertCorruptedAndReported(stored);
            world.replace(file, AttachmentWorld.flipped(original, offset + 4 + 12)); // the first ciphertext byte
            assertCorruptedAndReported(stored);
            offset += segment.length;
        }
        world.replace(file, AttachmentWorld.join(List.of(segments.get(1), segments.get(0), segments.get(2), segments.get(3))));
        assertCorruptedAndReported(stored);
        world.replace(file, AttachmentWorld.join(List.of(segments.get(0), segments.get(2), segments.get(1), segments.get(3))));
        assertCorruptedAndReported(stored);
        assertEquals(END_MARK, segments.get(3).length, "the end mark is a segment of no plain bytes: its length prefix, the IV and the tag");
        world.replace(file, java.util.Arrays.copyOf(original, original.length - END_MARK)); // the end mark cut off
        assertCorruptedAndReported(stored);
        world.replace(file, java.util.Arrays.copyOf(original, original.length - END_MARK - segments.get(2).length)); // the end mark and the last chunk
        assertCorruptedAndReported(stored);
        world.replace(file, java.util.Arrays.copyOf(original, original.length - 9)); // a cut in the middle of the end mark
        assertCorruptedAndReported(stored);
        world.replace(file, AttachmentWorld.concat(original, world.bytes(10))); // bytes after the end mark
        assertCorruptedAndReported(stored);

        world.replace(file, original);
        assertArrayEquals(stored.content(), world.readAll(stored.id(), 1000), "control: the original bytes are read again");
        assertEquals(List.of(), world.maintenance().check());
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"), "no row was deleted");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_file"));
    }

    @Test
    void theBytesOfAnotherAttachmentOfTheSameSizePutInAFilesPlaceAreCorrupted() {
        Stored mine = threeChunks();
        Stored other = threeChunks();
        Path myFile = world.pathOfAttachment(mine.id());
        byte[] othersBytes = AttachmentWorld.read(world.pathOfAttachment(other.id()));
        assertEquals(AttachmentWorld.size(myFile), othersBytes.length, "same layout: the only difference is the storage key in the additional data");

        world.replace(myFile, othersBytes);

        refused(IntegrityException.class, IntegrityCode.ARTIFACT_CORRUPTED, false, () -> world.uploads().read(mine.id(), 0, 0, 1000));
        assertArrayEquals(other.content(), world.readAll(other.id(), 1000), "the other attachment is read as before");
    }

    // ------------------------------------------------------------------------------------------------------------ f7

    @Test
    void theFileIsNamedByItsRandomStorageKeyAloneAndNeitherThePlainBytesNorTheNameAreInTheFolder() throws Exception {
        byte[] marker = "TOP-SECRET-PLAINTEXT-0123456789-ABCDEFGHIJKLMNOP".getBytes(StandardCharsets.US_ASCII);
        byte[] content = AttachmentWorld.concat(marker, marker, marker, marker);
        String name = "secret-report-of-the-board.pdf";
        AttachmentWorld.Single begun = world.begin(ALICE, name, content);
        world.chunk(begun.uploadId(), 0, content);
        world.uploads().finish(begun.attachmentId());

        String storageKey = world.service().view(begun.attachmentId(), 0).orElseThrow().current().storageKey();
        assertTrue(HEX32.matcher(storageKey).matches(), storageKey);
        Path file = world.pathOfAttachment(begun.attachmentId());
        assertEquals(storageKey, file.getFileName().toString());
        assertFalse(file.getFileName().toString().contains("."), "no extension");
        assertEquals(storageKey.substring(0, 2), file.getParent().getFileName().toString());
        try (Stream<Path> walk = Files.walk(world.directory())) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size());
            for (Path each : files) {
                byte[] body = AttachmentWorld.read(each);
                assertTrue(HEX32.matcher(each.getFileName().toString()).matches(), each.getFileName().toString());
                assertFalse(contains(body, marker), "no run of the plain bytes is in the file");
                assertFalse(contains(body, name.getBytes(StandardCharsets.UTF_8)), "the original name is not in the file");
            }
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            int at = 0;
            while (at < needle.length && haystack[start + at] == needle[at]) {
                at++;
            }
            if (at == needle.length) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------ f8, f9, f10

    @Test
    void aChunkAppendedButNeverTakenIntoTheRowMakesTheFinishIncompleteUntilItIsSentAgain() {
        byte[] first = world.bytes(40);
        byte[] second = world.bytes(40);
        byte[] content = AttachmentWorld.concat(first, second);
        AttachmentWorld.Single begun = world.begin(ALICE, "tail.bin", content);
        String id = begun.uploadId();
        world.chunk(id, 0, first);

        // the entrance appends the chunk and the service fails (the lost transaction): only the file steps run
        AttachmentService.ChunkPlan.Next plan = assertInstanceOf(AttachmentService.ChunkPlan.Next.class,
                world.service().planChunk(id, 1, AttachmentWorld.sha(second), second.length));
        UploadStaging row = plan.row();
        long withTail = world.store().append(row.storageKey(), row.keyId(), 1, row.storedBytes(), second, AttachmentWorld.hex(second));
        assertEquals(withTail, AttachmentWorld.size(world.pathOfUpload(id)));
        assertTrue(withTail > world.staging(id).storedBytes(), "the file is longer than the row says");
        assertEquals(40L, world.uploads().uploadStatus(id).received(), "the row does not have the chunk");

        ConflictException incomplete = refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(begun.attachmentId()));
        assertEquals("tail", incomplete.details().get("reason"));
        assertEquals(withTail, AttachmentWorld.size(world.pathOfUpload(id)), "the tail is neither cut nor closed");
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state());

        AttachmentService.Progress sentAgain = world.chunk(id, 1, second);
        assertEquals(new AttachmentService.Progress(id, 1, 80), sentAgain, "the tail was recognised and taken into the row");
        assertEquals(withTail, AttachmentWorld.size(world.pathOfUpload(id)), "the chunk is in the file once");
        assertEquals(withTail, world.staging(id).storedBytes());
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 64));
    }

    @Test
    void aFileClosedButNotMadeAnAttachmentIsClosedAgainByTheFinishSentAgainWithOneEndMark() throws SQLException {
        byte[] first = world.bytes(100);
        byte[] second = world.bytes(60);
        byte[] content = AttachmentWorld.concat(first, second);
        AttachmentWorld.Single begun = world.begin(ALICE, "lost-finish.bin", content);
        String id = begun.uploadId();
        world.chunk(id, 0, first);
        world.chunk(id, 1, second);

        // the entrance of the finish runs its file step and the service fails: only the file step has run
        AttachmentService.FinishPlan.Pending pending = assertInstanceOf(AttachmentService.FinishPlan.Pending.class, world.service().planFinish(begun.attachmentId()));
        assertEquals(1, pending.rows().size());
        UploadStaging row = pending.rows().get(0);
        AttachmentFileStore.Closed closed = world.store().close(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(), row.declaredSize(), row.declaredSha256());
        Path file = world.pathOfUpload(id);
        assertEquals(closed.storedBytes(), AttachmentWorld.size(file), "the end mark is in the file");
        assertEquals(row.storedBytes() + END_MARK, closed.storedBytes());
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state(), "and the row does not know");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"));

        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertEquals(AttachmentWorld.kept(begun.attachmentId(), List.of(new AttachmentWorld.Item("lost-finish.bin", content))), kept);
        assertEquals(closed.storedBytes(), AttachmentWorld.size(file), "the end mark is in the file once");
        assertEquals(UploadStaging.State.COMPLETED, world.staging(id).state());
        assertEquals(closed.storedBytes(), world.staging(id).storedBytes());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 50));
        assertEquals(kept, world.uploads().finish(begun.attachmentId()), "a finish sent again answers the same attachment");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment_storage"));
    }

    @Test
    void aFinishWithTooFewBytesIsIncompleteAndPassesOnceTheRestIsSentAndAWrongHashIsAMismatchAndNeitherClosesTheFile() throws SQLException {
        byte[] first = world.bytes(100);
        byte[] rest = world.bytes(50);
        byte[] content = AttachmentWorld.concat(first, rest);
        AttachmentWorld.Single begun = world.begin(ALICE, "short.bin", content);
        String id = begun.uploadId();
        world.chunk(id, 0, first);
        Path file = world.pathOfUpload(id);
        long size = AttachmentWorld.size(file);

        ConflictException incomplete = refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(begun.attachmentId()));
        assertEquals("bytes-missing", incomplete.details().get("reason"));
        assertEquals(size, AttachmentWorld.size(file), "no end mark");
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state());
        assertEquals(world.staging(id).storedBytes(), AttachmentWorld.size(file), "the file is what the row took in and nothing more");
        refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(begun.attachmentId())); // the same finish sent again does not pass

        world.chunk(id, 1, rest);
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());
        assertArrayEquals(content, world.readAll(kept.attachmentId(), 64));

        AttachmentWorld.Single wrong = AttachmentWorld.single(world.uploads().begin(ALICE, world.request(AttachmentWorld.operationKey(), "wrong.bin", 150, AttachmentWorld.sha(world.bytes(150)))));
        world.chunk(wrong.uploadId(), 0, first);
        world.chunk(wrong.uploadId(), 1, rest);
        Path wrongFile = world.pathOfUpload(wrong.uploadId());
        long wrongSize = AttachmentWorld.size(wrongFile);
        refused(IntegrityException.class, IntegrityCode.ARTIFACT_HASH_MISMATCH, false, () -> world.uploads().finish(wrong.attachmentId()));
        assertEquals(wrongSize, AttachmentWorld.size(wrongFile), "no end mark");
        assertEquals(UploadStaging.State.STAGING, world.staging(wrong.uploadId()).state());
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"), "only the first upload became an attachment");
    }

    @Test
    void aFinishBeforeAnyChunkWasSentIsIncompleteAndPassesOnceTheBytesArrive() {
        byte[] content = world.bytes(50);
        AttachmentWorld.Single begun = world.begin(ALICE, "nothing-yet.bin", content);
        String id = begun.uploadId();

        ConflictException incomplete = refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(begun.attachmentId()));
        assertEquals("bytes-missing", incomplete.details().get("reason"));
        assertEquals(UploadStaging.State.STAGING, world.staging(id).state());

        world.chunk(id, 0, content);
        assertArrayEquals(content, world.readAll(world.uploads().finish(begun.attachmentId()).attachmentId(), 64));
    }

    // ------------------------------------------------------------------------------------------------------------ f11, f12

    @Test
    void theSameOperationKeyOfTheSameAuthorGivesTheSameUploadAndAnotherContentIsAConflict() {
        byte[] content = world.bytes(50);
        String operationKey = AttachmentWorld.operationKey();
        AttachmentService.Begun first = world.uploads().begin(ALICE, world.request(operationKey, "a.bin", 50, AttachmentWorld.sha(content)));

        AttachmentService.Begun again = world.uploads().begin(ALICE, world.request(operationKey, "a.bin", 50, AttachmentWorld.sha(content)));
        assertEquals(first, again, "the same content again: the same attachment and the same upload");
        assertEquals(1, world.service().openUploads().size());
        refused(ConflictException.class, ConflictCode.ARTIFACT_OPERATION_CONFLICT, false, () -> world.uploads().begin(ALICE, world.request(operationKey, "a.bin", 51, AttachmentWorld.sha(content))));
        refused(ConflictException.class, ConflictCode.ARTIFACT_OPERATION_CONFLICT, false, () -> world.uploads().begin(ALICE, world.request(operationKey, "b.bin", 50, AttachmentWorld.sha(content))));
        refused(ConflictException.class, ConflictCode.ARTIFACT_OPERATION_CONFLICT, false,
                () -> world.uploads().begin(ALICE, world.request(operationKey, "a.bin", 50, AttachmentWorld.sha(world.bytes(50)))));
        AttachmentService.Begun bobs = world.uploads().begin(BOB, world.request(operationKey, "a.bin", 50, AttachmentWorld.sha(content)));
        assertNotEquals(first.files().get(0).uploadId(), bobs.files().get(0).uploadId(), "another author may use the same key: it is the author's own");
        assertNotEquals(first.attachmentId(), bobs.attachmentId());
        assertEquals(2, world.service().openUploads().size());
    }

    private AttachmentService.BeginRequest withPurpose(byte[] content, String purpose) {
        return new AttachmentService.BeginRequest(AttachmentWorld.operationKey(),
                List.of(new AttachmentService.BeginFile("p.bin", AttachmentWorld.MEDIA_TYPE, content.length, AttachmentWorld.sha(content))), purpose);
    }

    @Test
    void aPurposeThatSennardDoesNotKeepIsRefusedAndEvidenceOrNoneIsKeptAndWhatIsNotThereIsNotFound() {
        byte[] content = world.bytes(10);
        for (String purpose : new String[] {"upload", "staged", "something-else"}) {
            InvalidInputException refusedPurpose = assertThrows(InvalidInputException.class, () -> world.uploads().begin(ALICE, withPurpose(content, purpose)));
            assertEquals(InputCode.ARTIFACT_UNSUPPORTED_PURPOSE, refusedPurpose.code(), purpose);
            assertEquals(Boolean.FALSE, refusedPurpose.details().get("retryable"));
        }
        assertEquals(0, world.service().openUploads().size());
        world.uploads().begin(ALICE, withPurpose(content, "evidence"));
        world.begin(ALICE, "p2.bin", content);
        assertEquals(2, world.service().openUploads().size());

        String unknown = "00000000-0000-0000-0000-000000000000";
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().uploadStatus(unknown));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().attachmentStatus(unknown));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().read(unknown, 0, 0, 10));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().finish(unknown));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.chunk(unknown, 0, world.bytes(10)));
    }

    // ------------------------------------------------------------------------------------------------------------ f3b

    @Test
    void aChunkAndAFinishAreBoundToTheUploadByItsIdAloneAndEachAttachmentsAuthorIsTheOwnerOfItsBegin() {
        byte[] alicesContent = world.bytes(90);
        byte[] bobsContent = world.bytes(70);
        AttachmentWorld.Single alices = world.begin(ALICE, "alices.bin", alicesContent);
        AttachmentWorld.Single bobs = world.begin(BOB, "bobs.bin", bobsContent);

        // The calls below carry no author: whoever holds an upload's id sends its chunks and whoever holds the attachment's id its finish, in any interleaving; each lands in its own bundle.
        world.chunk(alices.uploadId(), 0, part(alicesContent, 0, 40));
        world.chunk(bobs.uploadId(), 0, part(bobsContent, 0, 30));
        world.chunk(alices.uploadId(), 1, part(alicesContent, 40, 50));
        world.chunk(bobs.uploadId(), 1, part(bobsContent, 30, 40));
        AttachmentService.Kept bobsKept = world.uploads().finish(bobs.attachmentId());
        AttachmentService.Kept alicesKept = world.uploads().finish(alices.attachmentId());

        assertEquals(alices.attachmentId(), alicesKept.attachmentId());
        assertEquals(bobs.attachmentId(), bobsKept.attachmentId());
        assertArrayEquals(alicesContent, world.readAll(alicesKept.attachmentId(), 64));
        assertArrayEquals(bobsContent, world.readAll(bobsKept.attachmentId(), 64));
        assertEquals(ALICE, world.service().attachment(alicesKept.attachmentId()).orElseThrow().createdBy(), "the author is the owner of the begin, not whoever sent the chunks");
        assertEquals(BOB, world.service().attachment(bobsKept.attachmentId()).orElseThrow().createdBy());
    }

    @Test
    void aFinishSentAgainAfterTheUploadIsFinishedAnswersTheSameAttachmentWhoeverSendsIt() {
        byte[] content = world.bytes(60);
        AttachmentWorld.Single begun = world.begin(ALICE, "again.bin", content);
        world.chunk(begun.uploadId(), 0, content);
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());

        assertEquals(kept, world.uploads().finish(begun.attachmentId()), "a finish sent again answers the same attachment");
        assertEquals(ALICE, world.service().attachment(kept.attachmentId()).orElseThrow().createdBy());
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().finish(begun.uploadId()));
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(begun.uploadId(), 1, content));
    }

    @Test
    void aFileOfNoBytesIsKeptThroughOneEmptyChunk() {
        AttachmentWorld.Single begun = world.begin(ALICE, "empty.bin", new byte[0]);

        world.chunk(begun.uploadId(), 0, new byte[0]);
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());

        assertEquals(AttachmentWorld.kept(begun.attachmentId(), List.of(new AttachmentWorld.Item("empty.bin", new byte[0]))), kept);
        assertEquals(0, world.readAll(kept.attachmentId(), 100).length);
        assertTrue(world.uploads().read(kept.attachmentId(), 0, 0, 100).page().end());
    }

    // ------------------------------------------------------------------------------------------------------------ f13: a bundle of several files

    private static AttachmentService.BeginFile declared(AttachmentWorld.Item item) {
        return new AttachmentService.BeginFile(item.fileName(), AttachmentWorld.MEDIA_TYPE, item.content().length, AttachmentWorld.sha(item.content()));
    }

    @Test
    void aBundleOfThreeFilesIsKeptByOneFinishAndEachFileIsReadByItsNumber() throws Exception {
        List<AttachmentWorld.Item> items = world.items(3, 90);
        AttachmentService.Begun begun = world.beginBundle(ALICE, items);

        assertEquals(3, begun.files().size());
        assertEquals(3, begun.files().stream().map(AttachmentService.BegunFile::uploadId).distinct().count(), "one upload id per file");
        List<UploadStaging> rows = world.stagingOf(begun.attachmentId());
        assertEquals(3, rows.size(), "the uploads of one begin carry the attachment id the begin answered");
        for (int seq = 0; seq < 3; seq++) {
            assertEquals(seq, begun.files().get(seq).seq(), "the numbers are 0, 1, 2 in the order of the list");
            assertEquals(begun.files().get(seq).uploadId(), rows.get(seq).id());
            assertEquals(seq, rows.get(seq).seq());
            assertEquals(items.get(seq).fileName(), rows.get(seq).fileName());
            assertEquals(ALICE, rows.get(seq).owner());
        }
        for (AttachmentService.BegunFile file : begun.files()) {
            world.send(file.uploadId(), items.get(file.seq()).content(), 40); // chunks of 40, 40 and 10 bytes
        }
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"), "nothing is kept before the finish");

        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());

        assertEquals(AttachmentWorld.kept(begun.attachmentId(), items), kept, "one finish answers every file: number, name, media type, size, sha256");
        String id = begun.attachmentId();
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment WHERE id = '" + id + "'"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_file WHERE attachment_id = '" + id + "'"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "'"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND rev = 0"), "the first copies are all rev 0");
        assertEquals(3, world.count("SELECT count(DISTINCT storage_key) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "'"), "each file has its own storage key");
        assertEquals(3, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'COMPLETED'"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'STAGING'"));
        for (int seq = 0; seq < 3; seq++) {
            assertArrayEquals(items.get(seq).content(), world.readAll(id, seq, 64), "file " + seq + " reads its own bytes");
            AttachmentUploads.Read read = world.uploads().read(id, seq, 0, 1000);
            assertEquals(items.get(seq).fileName(), read.view().file().fileName());
            assertEquals(seq, read.view().file().seq());
            assertEquals(0, read.view().current().rev());
        }
        assertFalse(Arrays.equals(world.readAll(id, 0, 64), world.readAll(id, 1, 64)), "the files of one bundle are not the same file");
        assertEquals(kept.files(), world.uploads().attachmentStatus(id).files());
        assertEquals(3, world.service().filesOf(id).size());
        assertEquals(List.of(), world.maintenance().check(), "every file of the bundle is whole");
        try (Stream<Path> walk = Files.walk(world.directory())) {
            assertEquals(3, walk.filter(Files::isRegularFile).count(), "three files in the folder, one per file of the bundle");
        }

        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().read(id, 3, 0, 10));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.uploads().read("00000000-0000-0000-0000-000000000000", 0, 0, 10));
        assertTrue(world.service().view(id, 3).isEmpty(), "a number the bundle has not has no view");
    }

    // ------------------------------------------------------------------------------------------------------------ f14: a file that fails the finish

    private void assertNothingKeptAndEveryUploadStaging(String attachmentId, int files) throws SQLException {
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        List<UploadStaging> rows = world.stagingOf(attachmentId);
        assertEquals(files, rows.size());
        for (UploadStaging row : rows) {
            assertEquals(UploadStaging.State.STAGING, row.state(), "file " + row.seq());
        }
    }

    @Test
    void aSecondFileWithFewerBytesThanDeclaredMakesNoBundleAndTheSameFinishPassesOnceItsMissingChunkIsSent() throws Exception {
        List<AttachmentWorld.Item> items = world.items(3, 100);
        AttachmentService.Begun begun = world.beginBundle(ALICE, items);
        String id = begun.attachmentId();
        String first = begun.files().get(0).uploadId();
        String second = begun.files().get(1).uploadId();
        String third = begun.files().get(2).uploadId();
        world.send(first, items.get(0).content(), 50);
        world.chunk(second, 0, part(items.get(1).content(), 0, 60)); // the second file is 40 bytes short
        world.send(third, items.get(2).content(), 50);
        long secondSize = AttachmentWorld.size(world.pathOfUpload(second));

        ConflictException incomplete = refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(id));
        assertEquals("bytes-missing", incomplete.details().get("reason"));
        assertNothingKeptAndEveryUploadStaging(id, 3);
        assertEquals(secondSize, AttachmentWorld.size(world.pathOfUpload(second)), "the short file is not closed");
        refused(ConflictException.class, ConflictCode.ARTIFACT_INCOMPLETE, false, () -> world.uploads().finish(id)); // the same finish sent again does not pass
        assertNothingKeptAndEveryUploadStaging(id, 3);
        long storedOfFirst = world.staging(first).storedBytes();

        world.chunk(second, 1, part(items.get(1).content(), 60, 40)); // resume: only the missing chunk is sent
        AttachmentService.Kept kept = world.uploads().finish(id);

        assertEquals(AttachmentWorld.kept(id, items), kept, "the same finish passes");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_storage WHERE rev = 0"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'COMPLETED'"));
        assertEquals(storedOfFirst + END_MARK, AttachmentWorld.size(world.pathOfUpload(first)), "the file closed by the first finish is checked again and its end mark is in it once");
        for (int seq = 0; seq < 3; seq++) {
            assertArrayEquals(items.get(seq).content(), world.readAll(id, seq, 64));
        }
        assertEquals(List.of(), world.maintenance().check());
    }

    @Test
    void aSecondFileWithAnotherSha256ThanDeclaredMakesNoBundleAndStaysRefused() throws Exception {
        List<AttachmentWorld.Item> items = world.items(3, 100);
        AttachmentService.BeginRequest request = new AttachmentService.BeginRequest(AttachmentWorld.operationKey(),
                List.of(declared(items.get(0)), new AttachmentService.BeginFile(items.get(1).fileName(), AttachmentWorld.MEDIA_TYPE, 100, AttachmentWorld.sha(world.bytes(100))),
                        declared(items.get(2))),
                null);
        AttachmentService.Begun begun = world.uploads().begin(ALICE, request);
        String id = begun.attachmentId();
        for (AttachmentService.BegunFile file : begun.files()) {
            world.send(file.uploadId(), items.get(file.seq()).content(), 50);
        }
        String second = begun.files().get(1).uploadId();
        long secondSize = AttachmentWorld.size(world.pathOfUpload(second));

        for (int attempt = 0; attempt < 2; attempt++) {
            IntegrityException mismatch = refused(IntegrityException.class, IntegrityCode.ARTIFACT_HASH_MISMATCH, false, () -> world.uploads().finish(id));
            assertEquals("sha256", mismatch.details().get("reason"));
            assertNothingKeptAndEveryUploadStaging(id, 3);
            assertEquals(secondSize, AttachmentWorld.size(world.pathOfUpload(second)), "the file with the wrong hash is not closed");
        }
        assertEquals(List.of(), world.maintenance().check(), "no row is left without its file");
    }

    // ------------------------------------------------------------------------------------------------------------ f15: idempotence of a bundle

    private void assertOperationConflict(String operationKey, List<AttachmentWorld.Item> others) {
        refused(ConflictException.class, ConflictCode.ARTIFACT_OPERATION_CONFLICT, false, () -> world.uploads().begin(ALICE, world.bundleRequest(operationKey, others)));
    }

    @Test
    void theSameBundleBegunAgainIsTheSameAttachmentAndAnotherListUnderTheKeyIsAConflict() {
        List<AttachmentWorld.Item> items = world.items(3, 50);
        String operationKey = AttachmentWorld.operationKey();
        AttachmentService.Begun first = world.uploads().begin(ALICE, world.bundleRequest(operationKey, items));

        AttachmentService.Begun again = world.uploads().begin(ALICE, world.bundleRequest(operationKey, items));
        assertEquals(first, again, "the same author, key and files answer the same attachment id and the same upload ids, in the same order");
        assertEquals(3, world.service().openUploads().size(), "nothing was made the second time");

        assertOperationConflict(operationKey, items.subList(0, 2)); // a file fewer
        assertOperationConflict(operationKey, List.of(items.get(0), items.get(1), items.get(2), new AttachmentWorld.Item("file-3.bin", world.bytes(50)))); // a file more
        assertOperationConflict(operationKey, List.of(items.get(1), items.get(0), items.get(2))); // the same files in another order
        assertOperationConflict(operationKey, List.of(items.get(0), new AttachmentWorld.Item("renamed.bin", items.get(1).content()), items.get(2))); // another name
        assertOperationConflict(operationKey, List.of(items.get(0), items.get(1), new AttachmentWorld.Item(items.get(2).fileName(), world.bytes(50)))); // another sha256, the size the same
        assertOperationConflict(operationKey, List.of(items.get(0), items.get(1), new AttachmentWorld.Item(items.get(2).fileName(), world.bytes(51)))); // another size
        assertEquals(first, world.uploads().begin(ALICE, world.bundleRequest(operationKey, items)), "and the refusals changed nothing");
        assertEquals(3, world.service().openUploads().size());

        AttachmentService.Begun bobs = world.uploads().begin(BOB, world.bundleRequest(operationKey, items));
        assertNotEquals(first.attachmentId(), bobs.attachmentId(), "another author may use the same key: it is the author's own");
        assertEquals(6, world.service().openUploads().size());
    }

    @Test
    void aChunkAndAFinishOfABundleSentAgainAnswerAsBeforeAlsoAfterTheCleanerRemovedTheFinishedUploadRows() throws Exception {
        List<AttachmentWorld.Item> items = world.items(3, 50);
        AttachmentService.Begun begun = world.beginBundle(ALICE, items);
        String id = begun.attachmentId();
        String secondUpload = begun.files().get(1).uploadId();
        byte[] secondContent = items.get(1).content();

        AttachmentService.Progress progress = world.chunk(secondUpload, 0, secondContent);
        long size = AttachmentWorld.size(world.pathOfUpload(secondUpload));
        assertEquals(progress, world.chunk(secondUpload, 0, secondContent), "the last chunk of a file sent again is answered as before");
        assertEquals(size, AttachmentWorld.size(world.pathOfUpload(secondUpload)), "and appended nowhere");
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(secondUpload, 0, world.bytes(50)));
        world.chunk(begun.files().get(0).uploadId(), 0, items.get(0).content());
        world.chunk(begun.files().get(2).uploadId(), 0, items.get(2).content());

        AttachmentService.Kept kept = world.uploads().finish(id);
        assertEquals(AttachmentWorld.kept(id, items), kept);
        assertEquals(kept, world.uploads().finish(id), "a finish sent twice answers the same Kept");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_file"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_storage"));

        assertEquals(3, world.maintenance().reap().completedUploads(), "the cleaner removes the COMPLETED upload row of every file");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging"));
        assertEquals(kept, world.uploads().finish(id), "a finish sent after the cleaner finds the attachment by the same id");
        assertEquals(kept, world.uploads().finish(id));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> world.chunk(secondUpload, 1, secondContent));
        assertEquals(1, world.count("SELECT count(*) FROM sennard.attachment"));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment_storage"));
        for (int seq = 0; seq < 3; seq++) {
            assertArrayEquals(items.get(seq).content(), world.readAll(id, seq, 64));
        }
    }

    // ------------------------------------------------------------------------------------------------------------ f16: a chunk of a file of a bundle

    @Test
    void aChunkOfOneFileOfABundleTouchesOnlyThatFilesUploadAndAChunkAfterTheFinishIsAConflict() {
        List<AttachmentWorld.Item> items = world.items(2, 80);
        AttachmentService.Begun begun = world.beginBundle(ALICE, items);
        String zero = begun.files().get(0).uploadId();
        String one = begun.files().get(1).uploadId();

        world.chunk(one, 0, part(items.get(1).content(), 0, 30));

        AttachmentService.UploadStatus statusZero = world.uploads().uploadStatus(zero);
        AttachmentService.UploadStatus statusOne = world.uploads().uploadStatus(one);
        assertEquals(begun.attachmentId(), statusZero.attachmentId());
        assertEquals(begun.attachmentId(), statusOne.attachmentId());
        assertEquals(0, statusZero.seq());
        assertEquals(1, statusOne.seq());
        assertEquals(items.get(1).fileName(), statusOne.fileName());
        assertEquals(0, statusZero.received(), "the chunk of file 1 took nothing into file 0");
        assertEquals(0, statusZero.sequence());
        assertEquals(30, statusOne.received());
        assertEquals(1, statusOne.sequence());
        assertFalse(Files.exists(world.pathOfUpload(zero)), "and appended nothing to its file");

        world.chunk(one, 1, part(items.get(1).content(), 30, 50));
        world.chunk(zero, 0, items.get(0).content());
        AttachmentService.Kept kept = world.uploads().finish(begun.attachmentId());

        assertEquals(AttachmentWorld.kept(begun.attachmentId(), items), kept);
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(one, 2, items.get(1).content()));
        refused(ConflictException.class, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false, () -> world.chunk(zero, 1, items.get(0).content()));
        assertEquals("COMPLETED", world.uploads().uploadStatus(zero).state());
        assertEquals("COMPLETED", world.uploads().uploadStatus(one).state());
    }
}
