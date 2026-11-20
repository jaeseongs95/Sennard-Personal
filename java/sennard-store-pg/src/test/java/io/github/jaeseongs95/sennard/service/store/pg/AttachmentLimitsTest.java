// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.ALICE;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.BOB;
import static io.github.jaeseongs95.sennard.service.store.pg.AttachmentWorld.refused;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The limits of {@code artifact_begin} (design 3 and 3.1): a request over a limit is refused whole and never cut, with {@code ARTIFACT_LIMIT}, {@code details.limit} naming which limit,
 * {@code details.retryable} saying whether waiting frees it, and {@code details.requested} and {@code details.allowed} giving the numbers. The real database and folder, the settings
 * given to the context as an operator's {@code application.yaml} would.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 10  l1  file size           max-file-bytes 1 KiB: 1025 bytes is refused (file-size, not retryable, requested 1025, allowed 1024) and no row is made; exactly 1024 is accepted
 * 10  l2  open uploads        max-open-per-owner 2: a third open upload of the same author is refused (open-uploads, retryable); another author may begin; once one of the two is finished
 *                             the author may begin again; the same operationKey with the same content is the upload that is there, not a new one
 * 10  l3  staging budget      staging-budget-bytes 3 KiB: the open uploads declare 3072 bytes together; one more byte is refused (staging-budget, retryable); a finished upload frees it
 * 17  l4  attachment budget   attachment-budget-bytes 3 KiB: three kept attachments of 1024 bytes; one more byte is refused (attachment-budget, not retryable, requested 3073, allowed 3072),
 *                             the attachments are not touched and read as before; a begin that brings the sum to exactly the budget is accepted
 * 20  l5  kept and open       the same budget counts the kept attachments, the sizes the open uploads declared and the new declaration: 1024 kept + 1024 open + 1024 new is the budget, and one
 *                             byte more is refused as attachment-budget (not as staging-budget, which is much larger here)
 *
 * An attachment is a bundle of files, and a begin of several files is refused or taken whole (V21, AttachmentService.MAX_FILES = 64):
 * 10  l6  file count          65 files are refused (file-count, not retryable, requested 65, allowed 64) and no row is made; exactly 64 files are accepted (64 upload rows, one attachment id)
 * 10  l7  open bundles        max-open-per-owner 1: a bundle of three files is one open bundle (accepted, though it makes three upload rows); a second begin of the author is refused
 *                             (open-uploads, retryable, requested 2, allowed 1); another author may begin; once the bundle is finished the author may begin again
 * 10  l8  sum of the files    staging-budget-bytes 3 KiB and max-file-bytes 1 KiB: the budget counts the sum of the files' declared sizes: a bundle of 1024 + 1024 is accepted, a bundle of
 *                             1024 + 1 over the rest (3073) is refused whole as staging-budget (no row of it is made), a bundle of 1024 to the exact budget is accepted
 * 17  l9  sum of the kept     attachment-budget-bytes 3 KiB and max-file-bytes 1 KiB: a kept bundle of 1024 + 1024 counts 2048 (the sum of its files' sizes in attachment_file); a bundle of
 *                             512 + 512 brings the sum to exactly the budget and is accepted; a bundle of 1 + 1 over the rest is refused whole as attachment-budget (requested 3074, allowed 3072)
 * </pre>
 */
@BusinessDbTest
class AttachmentLimitsTest {
    @TempDir
    Path scratch;

    private AttachmentWorld world;

    @AfterEach
    void stop() {
        if (world != null) {
            world.close();
        }
    }

    private void open(Map<String, String> settings) {
        world = AttachmentWorld.open(scratch, settings);
    }

    private UnavailableException limit(String owner, String name, long size, String expectedLimit, boolean retryable) {
        UnavailableException failure = refused(UnavailableException.class, UnavailableCode.ARTIFACT_LIMIT, retryable,
                () -> world.uploads().begin(owner, world.request(AttachmentWorld.operationKey(), name, size, AttachmentWorld.sha(world.bytes(1)))));
        assertEquals(expectedLimit, failure.details().get("limit"), "details.limit tells which limit it is");
        assertEquals(expectedLimit, failure.arguments().get("limit"));
        return failure;
    }

    /** A begin that is accepted: only the declared size matters to the limits, so the hash is that of a stand-in. */
    private AttachmentService.Begun begin(String owner, String name, long size) {
        return world.uploads().begin(owner, world.request(AttachmentWorld.operationKey(), name, size, AttachmentWorld.sha(world.bytes(1))));
    }

    @Test
    void aFileOverTheSizeLimitIsRefusedWholeAndMakesNoRow() throws SQLException {
        open(Map.of("max-file-bytes", "1KB"));

        UnavailableException failure = limit(ALICE, "big.bin", 1025, "file-size", false);
        assertEquals(1025L, failure.details().get("requested"));
        assertEquals(1024L, failure.details().get("allowed"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging"), "a refused begin is not cut to the limit and makes no upload");

        assertNotNull(begin(ALICE, "fits.bin", 1024), "exactly the limit is accepted");
        assertEquals(1, world.count("SELECT count(*) FROM sennard.upload_staging"));
    }

    @Test
    void aThirdOpenUploadOfOneAuthorIsRefusedAndFinishingOneFreesIt() throws SQLException {
        open(Map.of("max-open-per-owner", "2"));
        byte[] content = world.bytes(100);
        String operationKey = AttachmentWorld.operationKey();
        AttachmentService.Begun first = world.uploads().begin(ALICE, world.request(operationKey, "one.bin", 100, AttachmentWorld.sha(content)));
        String firstUpload = first.files().get(0).uploadId();
        begin(ALICE, "two.bin", 100);

        UnavailableException failure = limit(ALICE, "three.bin", 100, "open-uploads", true);
        assertEquals(3L, failure.details().get("requested"), "the upload that was refused would have been the third");
        assertEquals(2L, failure.details().get("allowed"));
        assertEquals(2, world.count("SELECT count(*) FROM sennard.upload_staging"));
        assertNotNull(begin(BOB, "bobs.bin", 100), "the count is the author's own");
        assertEquals(first, world.uploads().begin(ALICE, world.request(operationKey, "one.bin", 100, AttachmentWorld.sha(content))),
                "the same operationKey with the same content is the upload that is there: it is not counted again");

        world.chunk(firstUpload, 0, content);
        world.uploads().finish(first.attachmentId());
        assertNotNull(begin(ALICE, "three.bin", 100), "an upload that ended no longer counts: the retry can succeed");
        assertEquals(2, world.count("SELECT count(*) FROM sennard.upload_staging WHERE owner = 'alice' AND state = 'STAGING'"));
    }

    @Test
    void theOpenUploadsMayDeclareNoMoreThanTheStagingBudgetAndAFinishedUploadFreesIt() {
        open(Map.of("staging-budget-bytes", "3KB", "max-file-bytes", "1KB", "max-open-per-owner", "10"));
        byte[] content = world.bytes(1024);
        AttachmentWorld.Single first = world.begin(ALICE, "a.bin", content);
        begin(ALICE, "b.bin", 1024);
        begin(BOB, "c.bin", 1024);

        UnavailableException failure = limit(ALICE, "d.bin", 1, "staging-budget", true);
        assertEquals(3073L, failure.details().get("requested"));
        assertEquals(3072L, failure.details().get("allowed"));

        world.chunk(first.uploadId(), 0, content);
        world.uploads().finish(first.attachmentId());
        assertNotNull(begin(ALICE, "d.bin", 1024), "a finished upload is no longer open: the same request now passes");
    }

    @Test
    void aBeginThatWouldTakeTheKeptAttachmentsOverTheBudgetIsRefusedAndTheAttachmentsAreKept() throws SQLException {
        open(Map.of("attachment-budget-bytes", "3KB", "max-file-bytes", "1KB"));
        byte[][] contents = {world.bytes(1024), world.bytes(1024), world.bytes(1024)};
        String[] ids = new String[3];
        for (int index = 0; index < 3; index++) {
            ids[index] = world.upload(ALICE, "kept" + index + ".bin", contents[index], 512).attachmentId();
        }
        assertEquals(3072, world.count("SELECT sum(size_bytes) FROM sennard.attachment_file"));

        UnavailableException failure = limit(ALICE, "one-more.bin", 1, "attachment-budget", false);
        assertEquals(3073L, failure.details().get("requested"));
        assertEquals(3072L, failure.details().get("allowed"));
        limit(BOB, "other-author.bin", 1, "attachment-budget", false);

        for (int index = 0; index < 3; index++) {
            assertArrayEquals(contents[index], world.readAll(ids[index], 300), "the attachment is kept and reads as before");
        }
        assertEquals(3, world.count("SELECT count(*) FROM sennard.attachment"), "none was deleted to make room");
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'STAGING'"));
        assertEquals(List.of(), world.maintenance().check());
    }

    @Test
    void theAttachmentBudgetCountsKeptAttachmentsTheDeclaredSizeOfOpenUploadsAndTheNewDeclaration() {
        open(Map.of("attachment-budget-bytes", "3KB", "max-file-bytes", "1KB"));
        world.upload(ALICE, "kept.bin", world.bytes(1024), 1024); // kept: 1024
        AttachmentService.Begun open = begin(ALICE, "open.bin", 1024); // open, nothing sent yet: its declared size counts: 2048
        assertNotNull(open);

        assertNotNull(begin(ALICE, "new.bin", 1024), "1024 kept + 1024 open + 1024 new is exactly the budget");
        UnavailableException failure = limit(BOB, "one-byte.bin", 1, "attachment-budget", false);
        assertEquals(3073L, failure.details().get("requested"), "kept + declared by the open uploads + the new declaration");
        assertEquals(3072L, failure.details().get("allowed"));
        assertEquals(2, world.service().openUploads().size(), "the two open uploads are untouched");
    }

    // ------------------------------------------------------------------------------------------------------------ l6, l7, l8, l9: bundles

    /** A bundle's files, each of {@code sizes[i]} bytes: only the declared sizes matter to the limits, so the content is zeros. */
    private static List<AttachmentWorld.Item> sized(long... sizes) {
        List<AttachmentWorld.Item> items = new ArrayList<>();
        for (int index = 0; index < sizes.length; index++) {
            items.add(new AttachmentWorld.Item("file-" + index + ".bin", new byte[(int) sizes[index]]));
        }
        return List.copyOf(items);
    }

    private UnavailableException bundleLimit(String owner, List<AttachmentWorld.Item> items, String expectedLimit, boolean retryable) {
        UnavailableException failure = refused(UnavailableException.class, UnavailableCode.ARTIFACT_LIMIT, retryable,
                () -> world.uploads().begin(owner, world.bundleRequest(AttachmentWorld.operationKey(), items)));
        assertEquals(expectedLimit, failure.details().get("limit"), "details.limit tells which limit it is");
        assertEquals(expectedLimit, failure.arguments().get("limit"));
        return failure;
    }

    @Test
    void sixtyFiveFilesAreRefusedWholeAndSixtyFourAreAccepted() throws SQLException {
        open(Map.of());
        List<AttachmentWorld.Item> tooMany = world.items(AttachmentService.MAX_FILES + 1, 1);

        UnavailableException failure = bundleLimit(ALICE, tooMany, "file-count", false);
        assertEquals(65L, failure.details().get("requested"), "the numbers of file-count are counts of files");
        assertEquals(64L, failure.details().get("allowed"));
        assertEquals(0, world.count("SELECT count(*) FROM sennard.upload_staging"), "a refused begin is not cut to 64 files and makes no upload");

        AttachmentService.Begun begun = world.uploads().begin(ALICE, world.bundleRequest(AttachmentWorld.operationKey(), world.items(AttachmentService.MAX_FILES, 1)));
        assertEquals(64, begun.files().size(), "exactly the limit is accepted");
        assertEquals(64, world.count("SELECT count(*) FROM sennard.upload_staging WHERE attachment_id = '" + begun.attachmentId() + "'"));
        assertEquals(64, world.count("SELECT count(*) FROM sennard.upload_staging"));
    }

    @Test
    void aBundleOfThreeFilesCountsOnceAgainstTheOpenUploadsOfAnAuthorAndFinishingItFreesIt() throws SQLException {
        open(Map.of("max-open-per-owner", "1"));
        List<AttachmentWorld.Item> items = world.items(3, 100);

        AttachmentService.Begun bundle = world.uploads().begin(ALICE, world.bundleRequest(AttachmentWorld.operationKey(), items));
        assertEquals(3, world.count("SELECT count(*) FROM sennard.upload_staging WHERE owner = 'alice' AND state = 'STAGING'"), "three upload rows");

        UnavailableException failure = limit(ALICE, "next.bin", 100, "open-uploads", true);
        assertEquals(2L, failure.details().get("requested"), "the bundle is one open bundle, so the refused begin would have been the second");
        assertEquals(1L, failure.details().get("allowed"));
        bundleLimit(ALICE, world.items(2, 10), "open-uploads", true);
        assertEquals(3, world.count("SELECT count(*) FROM sennard.upload_staging"), "a refused begin made no row");
        assertNotNull(begin(BOB, "bobs.bin", 100), "the count is the author's own");

        for (AttachmentService.BegunFile file : bundle.files()) {
            world.send(file.uploadId(), items.get(file.seq()).content(), 100);
        }
        world.uploads().finish(bundle.attachmentId());
        assertNotNull(begin(ALICE, "next.bin", 100), "a finished bundle no longer counts");
    }

    @Test
    void theStagingBudgetCountsTheSumOfTheFilesOfAnOpenBundleAndARefusedBundleMakesNoRow() throws SQLException {
        open(Map.of("staging-budget-bytes", "3KB", "max-file-bytes", "1KB", "max-open-per-owner", "10"));

        assertNotNull(world.uploads().begin(ALICE, world.bundleRequest(AttachmentWorld.operationKey(), sized(1024, 1024))), "1024 + 1024 are 2048 of 3072");
        UnavailableException failure = bundleLimit(ALICE, sized(1024, 1), "staging-budget", true);
        assertEquals(3073L, failure.details().get("requested"), "the open bundle's two files and the sum of the new bundle's two");
        assertEquals(3072L, failure.details().get("allowed"));
        assertEquals(2, world.count("SELECT count(*) FROM sennard.upload_staging"), "the refused bundle is not cut: none of its files has a row");

        assertNotNull(world.uploads().begin(BOB, world.bundleRequest(AttachmentWorld.operationKey(), sized(1024))), "1024 more is exactly the budget");
        assertEquals(3, world.count("SELECT count(*) FROM sennard.upload_staging"));
        limit(BOB, "one-byte.bin", 1, "staging-budget", true);
    }

    @Test
    void theAttachmentBudgetCountsTheSumOfTheFilesOfKeptBundlesAndOpenOnesAndARefusedBundleMakesNoRow() throws SQLException {
        open(Map.of("attachment-budget-bytes", "3KB", "max-file-bytes", "1KB"));
        world.uploadBundle(ALICE, List.of(new AttachmentWorld.Item("kept-0.bin", world.bytes(1024)), new AttachmentWorld.Item("kept-1.bin", world.bytes(1024))), 512);
        assertEquals(2048, world.count("SELECT sum(size_bytes) FROM sennard.attachment_file"), "the kept bundle counts the sum of its files");

        assertNotNull(world.uploads().begin(ALICE, world.bundleRequest(AttachmentWorld.operationKey(), sized(512, 512))), "2048 kept + 1024 declared is exactly the budget");
        UnavailableException failure = bundleLimit(BOB, sized(1, 1), "attachment-budget", false);
        assertEquals(3074L, failure.details().get("requested"), "kept + declared by the open bundle + the sum of the new bundle's files");
        assertEquals(3072L, failure.details().get("allowed"));
        assertEquals(2, world.count("SELECT count(*) FROM sennard.upload_staging WHERE state = 'STAGING'"), "only the accepted bundle's two files are open");
        assertEquals(2, world.count("SELECT count(*) FROM sennard.attachment_file"), "the kept files were not touched");
    }
}
