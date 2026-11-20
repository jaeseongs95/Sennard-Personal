// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.ALICE;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.refused;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * What the server's own records read from an attachment they point at (a stage result's output file and frozen request, the audited attachments of a finish) on the real business
 * database and the real attachment folder: {@link AttachmentContents} over the product's own service and file store, by the id only, never by a path of the caller's computer
 * (AGS3-D-0264).
 *
 * <pre>
 * c1  readWhole    the plain bytes of an attachment of several chunks, and of one longer than a page of the store, are returned whole
 * c2  readWhole    an attachment over the limit is ARTIFACT_LIMIT (file-size, not retryable) and nothing is read; an unknown id is ARTIFACT_NOT_FOUND
 * c3  standing     an attachment with its digest and a whole file is INTACT; an unknown id is MISSING; another digest is DIFFERENT
 * c4  standing     a changed byte, and a file that is gone, are UNREADABLE: the finish of an audit is refused on them, and nothing is deleted
 *
 * An attachment is a bundle of files; a file is read by (attachment id, number), and a single-file attachment is file 0 (c1 to c4 read file 0). A number the attachment has not is the
 * same as an attachment that is not there.
 * c5  bundle read  every file of a bundle of three is read whole and named (readWhole, readNamed) by its number; number 3 is ARTIFACT_NOT_FOUND for both
 * c6  bundle standing  each file's standing is judged by its own digest and its own stored copy: the digest of another file is DIFFERENT, number 3 is MISSING, a changed byte in file 1 makes file 1
 *                  UNREADABLE and files 0 and 2 stay INTACT and readable
 * </pre>
 */
@BusinessDbTest
class AttachmentContentsTest {
    @TempDir
    Path scratch;

    private AttachmentWorld world;
    private AttachmentContents contents;

    @BeforeEach
    void start() {
        world = AttachmentWorld.open(scratch);
        contents = world.bean(AttachmentContents.class);
    }

    @AfterEach
    void stop() {
        world.close();
    }

    @Test
    void readWholeReturnsThePlainBytesOfAnAttachmentOfSeveralChunksAndOfOneLongerThanAPage() {
        byte[] small = world.bytes(220);
        AttachmentService.Kept severalChunks = world.upload(ALICE, "small.bin", small, 100);
        assertArrayEquals(small, contents.readWhole(severalChunks.attachmentId(), 0, 1024));

        byte[] large = world.bytes(AttachmentFileStore.MAX_CHUNK_BYTES * 2 + 17);
        AttachmentService.Kept longer = world.upload(ALICE, "large.bin", large, AttachmentFileStore.MAX_CHUNK_BYTES);
        assertArrayEquals(large, contents.readWhole(longer.attachmentId(), 0, large.length));
    }

    @Test
    void readWholeRefusesAnAttachmentOverTheLimitAndAnUnknownId() {
        byte[] content = world.bytes(300);
        AttachmentService.Kept kept = world.upload(ALICE, "over.bin", content, 300);
        UnavailableException limit = refused(UnavailableException.class, UnavailableCode.ARTIFACT_LIMIT, false, () -> contents.readWhole(kept.attachmentId(), 0, 299));
        assertEquals("file-size", limit.details().get("limit"));
        assertArrayEquals(content, contents.readWhole(kept.attachmentId(), 0, 300), "control: at the limit exactly it is read");

        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> contents.readWhole(UUID.randomUUID().toString(), 0, 1024));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> contents.readWhole(kept.attachmentId(), 1, 1024));
    }

    @Test
    void anAttachmentWithItsDigestAndAWholeFileIsIntactAndAnotherDigestOrNoAttachmentIsNot() {
        byte[] content = world.bytes(220);
        AttachmentService.Kept kept = world.upload(ALICE, "audit.bin", content, 100);
        assertEquals(AttachmentContents.Standing.INTACT, contents.standing(kept.attachmentId(), 0, AttachmentWorld.sha(content)));
        assertEquals(AttachmentContents.Standing.INTACT, contents.standing(kept.attachmentId(), 0, AttachmentWorld.hex(content)), "the bare hex is the same digest");
        assertEquals(AttachmentContents.Standing.DIFFERENT, contents.standing(kept.attachmentId(), 0, AttachmentWorld.sha(world.bytes(220))));
        assertEquals(AttachmentContents.Standing.MISSING, contents.standing(UUID.randomUUID().toString(), 0, AttachmentWorld.sha(content)));
        assertEquals(AttachmentContents.Standing.MISSING, contents.standing(kept.attachmentId(), 1, AttachmentWorld.sha(content)), "a number the attachment has not is missing");
    }

    @Test
    void aChangedByteAndAFileThatIsGoneAreUnreadableAndNothingIsDeleted() {
        byte[] content = world.bytes(220);
        AttachmentService.Kept kept = world.upload(ALICE, "damaged.bin", content, 100);
        Path file = world.pathOfAttachment(kept.attachmentId());
        byte[] original = AttachmentWorld.read(file);
        assertEquals(AttachmentContents.Standing.INTACT, contents.standing(kept.attachmentId(), 0, AttachmentWorld.sha(content)), "control: the file is whole");

        world.replace(file, AttachmentWorld.flipped(original, 4 + 12));
        assertEquals(AttachmentContents.Standing.UNREADABLE, contents.standing(kept.attachmentId(), 0, AttachmentWorld.sha(content)));
        assertEquals(original.length, AttachmentWorld.size(file), "the standing is a question: it changes nothing");

        world.remove(file);
        assertEquals(AttachmentContents.Standing.UNREADABLE, contents.standing(kept.attachmentId(), 0, AttachmentWorld.sha(content)));
    }

    @Test
    void everyFileOfABundleIsReadWholeAndNamedByItsNumberAndAnUnknownNumberIsNotFound() {
        List<AttachmentWorld.Item> items = world.items(3, 150);
        AttachmentService.Kept kept = world.uploadBundle(ALICE, items, 64);

        for (int seq = 0; seq < 3; seq++) {
            assertArrayEquals(items.get(seq).content(), contents.readWhole(kept.attachmentId(), seq, 1024), "file " + seq);
            AttachmentContents.Named named = contents.readNamed(kept.attachmentId(), seq, 1024);
            assertEquals(items.get(seq).fileName(), named.fileName());
            assertArrayEquals(items.get(seq).content(), named.bytes());
        }
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> contents.readWhole(kept.attachmentId(), 3, 1024));
        refused(NotFoundException.class, NotFoundCode.ARTIFACT_NOT_FOUND, false, () -> contents.readNamed(kept.attachmentId(), 3, 1024));
    }

    @Test
    void theStandingOfAFileOfABundleIsJudgedByItsOwnDigestAndItsOwnStoredCopy() {
        List<AttachmentWorld.Item> items = world.items(3, 150);
        AttachmentService.Kept kept = world.uploadBundle(ALICE, items, 64);
        String id = kept.attachmentId();
        for (int seq = 0; seq < 3; seq++) {
            assertEquals(AttachmentContents.Standing.INTACT, contents.standing(id, seq, AttachmentWorld.sha(items.get(seq).content())), "file " + seq);
        }
        assertEquals(AttachmentContents.Standing.DIFFERENT, contents.standing(id, 1, AttachmentWorld.sha(items.get(0).content())), "the digest of another file of the bundle is another digest");
        assertEquals(AttachmentContents.Standing.MISSING, contents.standing(id, 3, AttachmentWorld.sha(items.get(0).content())));

        Path file = world.pathOfAttachment(id, 1);
        world.replace(file, AttachmentWorld.flipped(AttachmentWorld.read(file), 4 + 12));

        assertEquals(AttachmentContents.Standing.UNREADABLE, contents.standing(id, 1, AttachmentWorld.sha(items.get(1).content())), "the damaged file is unreadable");
        assertEquals(AttachmentContents.Standing.INTACT, contents.standing(id, 0, AttachmentWorld.sha(items.get(0).content())), "and the other files of the bundle are not");
        assertEquals(AttachmentContents.Standing.INTACT, contents.standing(id, 2, AttachmentWorld.sha(items.get(2).content())));
        assertArrayEquals(items.get(0).content(), contents.readWhole(id, 0, 1024));
    }
}
