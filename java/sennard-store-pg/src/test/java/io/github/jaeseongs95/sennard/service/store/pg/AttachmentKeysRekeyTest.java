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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFile;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentStorage;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * Key replacement and re-encryption (design 4.3 and 7): the active key changes, the keys of the rows keep reading, the start check finds a missing key file, and {@code rekey} writes
 * the current files under the active key. Two runs of the application one after the other over the same folders and the same database ({@link AttachmentWorld}): the first with
 * {@code active-key-id} A, the second with B, as an owner who changed the setting.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * A file's stored copies are the rows of attachment_storage, (attachment id, seq of the file, rev of the copy): rev 0 is written by the finish, each re-encryption writes the next rev, the
 * current copy is the row of the highest rev, and no row is ever changed.
 *
 * 14  k1  key replacement     a file written with key A reads after the active key is B; a new upload is written with B (the upload row and the attachment's storage row name B)
 * 14  k2  a missing key       the start check ({@code prepare}) fails with ARTIFACT_KEY_MISSING (naming the key) when the key file of a current attachment row is gone; the same for the key of an
 *                             upload in progress; a key that no current row points at may be gone. The start check reads the key file and judges it like a request (edition 11): a folder in
 *                             its place is ARTIFACT_STORAGE_UNSAFE, an empty file or one that is not 256 bits is ARTIFACT_KEY_MISSING, for the key of a current row and for that of an upload in
 *                             progress, with the same reason a read of the attachment gives
 * 16  k3  rekey               rekey("A") writes every current copy of key A again under key B in a new storage key: it reads with the same plain hash, the attachment and file rows are
 *                             unchanged, the new copy row is (attachment, seq 0, rev 1) and rev 0 stays as written; the old file is untouched, and after orphan-grace it is moved to orphans/
 *                             (not before); a second rekey finds nothing; rekeying from the active key is refused as an input error (VALIDATION_FAILED, --from), not as a storage path that
 *                             cannot be used
 * 16  k4  a failure before the row   the new file is written and the row never added (only the file store's rewrite runs): the attachment reads from the old copy as before, the new file is a
 *                             file with no row, and a rekey sent again completes
 * 16  k5  a bundle of two files, both under A   rekey("A") writes rev 1 for each of the two files (it answers 2), under B; the current copy of each file is its rev 1; the old copy of each is REPLACED
 *                             (useOf, with the time the replacing row was written) and the new one IN_USE; attachment_storage keeps four rows, and the two rev 0 rows are as the finish wrote
 *                             them; no attachment_file row is added; a second rekey finds nothing; after orphan-grace both replaced files leave the folder and both files read
 * 16  k6  a bundle whose files are under different keys   file 0 under A and file 1 under B (a copy of file 1 was added by hand, as a run that stopped half way leaves it): rekey("A") writes
 *                             file 0 again only (it answers 1); file 1 keeps its rev 1 and its storage key; each file has two copy rows; the replaced copies are REPLACED; the current copies of A
 *                             are none and those of B are two
 * </pre>
 */
@BusinessDbTest
class AttachmentKeysRekeyTest {
    private static final Duration GRACE = Duration.ofHours(24);

    @TempDir
    Path scratch;

    private AttachmentWorld first;
    private AttachmentWorld second;

    @AfterEach
    void stop() {
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
    }

    /** The application run with key A active, with the key files A and B made by the owner. */
    private AttachmentWorld openWithA() {
        first = AttachmentWorld.open(scratch, Map.of("active-key-id", "A"), "A", "B");
        return first;
    }

    /** The application run again with key B active, over the same folders and database. */
    private AttachmentWorld openWithB() {
        first.close();
        first = null;
        second = AttachmentWorld.open(scratch, Map.of("active-key-id", "B"), "A", "B");
        return second;
    }

    /** The key the current copy of file {@code seq} of the attachment is written with. */
    private static String keyOf(AttachmentWorld world, String attachmentId, int seq) {
        return world.service().view(attachmentId, seq).orElseThrow().current().keyId();
    }

    private static AttachmentStorage currentOf(AttachmentWorld world, String attachmentId, int seq) {
        return world.service().view(attachmentId, seq).orElseThrow().current();
    }

    // ------------------------------------------------------------------------------------------------------------ k1

    @Test
    void aFileWrittenWithKeyAReadsAfterTheActiveKeyIsBAndANewUploadIsWrittenWithB() throws SQLException {
        AttachmentWorld withA = openWithA();
        byte[] content = withA.bytes(300);
        AttachmentService.Kept old = withA.upload(ALICE, "old.bin", content, 100);
        assertEquals("A", keyOf(withA, old.attachmentId(), 0));
        AttachmentWorld.Single open = withA.begin(ALICE, "open-with-a.bin", withA.bytes(10));
        assertEquals("A", withA.staging(open.uploadId()).keyId());

        AttachmentWorld withB = openWithB();

        assertArrayEquals(content, withB.readAll(old.attachmentId(), 64), "the row's key picks the key: A, though B is active");
        assertEquals("A", keyOf(withB, old.attachmentId(), 0));
        byte[] fresh = withB.bytes(200);
        AttachmentService.Kept created = withB.upload(ALICE, "new.bin", fresh, 100);
        assertEquals("B", keyOf(withB, created.attachmentId(), 0), "a new upload is written with the active key");
        AttachmentWorld.Single another = withB.begin(ALICE, "open-with-b.bin", withB.bytes(10));
        assertEquals("B", withB.staging(another.uploadId()).keyId());
        assertArrayEquals(fresh, withB.readAll(created.attachmentId(), 64));
        assertEquals("A", withB.staging(open.uploadId()).keyId(), "an upload begun under A keeps A until it ends");
        assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE key_id = 'A'"));
        assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE key_id = 'B'"));
    }

    // ------------------------------------------------------------------------------------------------------------ k2

    @Test
    void theStartCheckFailsWhenTheKeyFileOfACurrentAttachmentRowIsGone() throws IOException {
        AttachmentWorld withA = openWithA();
        AttachmentService.Kept old = withA.upload(ALICE, "old.bin", withA.bytes(50), 50);

        AttachmentWorld withB = openWithB(); // B is active and its key is there: only the rows can say that A is still needed
        withB.maintenance().prepare(); // control: every key a current row points at is there
        Files.delete(withB.keyDirectory().resolve("A"));

        UnavailableException missing = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, withB.maintenance()::prepare);
        assertEquals("A", missing.details().get("keyId"));
        UnavailableException read = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, () -> withB.uploads().read(old.attachmentId(), 0, 0, 10));
        assertEquals("A", read.details().get("keyId"), "a read of the attachment names the key too");
    }

    @Test
    void theStartCheckAlsoLooksAtTheKeyOfAnUploadInProgress() throws IOException {
        AttachmentWorld withA = openWithA();
        withA.begin(ALICE, "open.bin", withA.bytes(20));

        AttachmentWorld withB = openWithB();
        withB.maintenance().prepare();
        Files.delete(withB.keyDirectory().resolve("A"));

        UnavailableException missing = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, withB.maintenance()::prepare);
        assertEquals("A", missing.details().get("keyId"));
    }

    @Test
    void theStartCheckJudgesTheKeyFileOfACurrentRowLikeARequestDoesAFolderIsUnsafeAndAnEmptyOrShortFileIsAMissingKey() throws IOException {
        AttachmentWorld withA = openWithA();
        AttachmentService.Kept old = withA.upload(ALICE, "old.bin", withA.bytes(50), 50);
        AttachmentWorld withB = openWithB();
        Path keyA = withB.keyDirectory().resolve("A");
        Files.delete(keyA);

        Files.write(keyA, new byte[0]);
        UnavailableException empty = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, withB.maintenance()::prepare);
        assertEquals("A", empty.details().get("keyId"));
        assertEquals("empty", empty.details().get("reason"));
        UnavailableException emptyRead = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, () -> withB.uploads().read(old.attachmentId(), 0, 0, 10));
        assertEquals("empty", emptyRead.details().get("reason"), "the start check and the first request give the same answer");

        Files.write(keyA, new byte[16]);
        UnavailableException shortKey = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, withB.maintenance()::prepare);
        assertEquals("wrong-length", shortKey.details().get("reason"));
        UnavailableException shortRead = refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, () -> withB.uploads().read(old.attachmentId(), 0, 0, 10));
        assertEquals("wrong-length", shortRead.details().get("reason"));

        Files.delete(keyA);
        Files.createDirectory(keyA);
        UnavailableException folder = refused(UnavailableException.class, UnavailableCode.ARTIFACT_STORAGE_UNSAFE, false, withB.maintenance()::prepare);
        assertEquals("key-not-a-file", folder.details().get("reason"));
        UnavailableException folderRead = refused(UnavailableException.class, UnavailableCode.ARTIFACT_STORAGE_UNSAFE, false, () -> withB.uploads().read(old.attachmentId(), 0, 0, 10));
        assertEquals("key-not-a-file", folderRead.details().get("reason"));
        assertTrue(Files.isDirectory(keyA), "the start check changes nothing");
    }

    @Test
    void theStartCheckJudgesTheKeyFileOfAnUploadInProgressLikeARequestDoes() throws IOException {
        AttachmentWorld withA = openWithA();
        withA.begin(ALICE, "open.bin", withA.bytes(20));
        AttachmentWorld withB = openWithB();
        Path keyA = withB.keyDirectory().resolve("A");
        Files.delete(keyA);

        Files.write(keyA, new byte[0]);
        assertEquals("empty", refused(UnavailableException.class, UnavailableCode.ARTIFACT_KEY_MISSING, false, withB.maintenance()::prepare).details().get("reason"));
        Files.delete(keyA);
        Files.createDirectory(keyA);
        assertEquals("key-not-a-file", refused(UnavailableException.class, UnavailableCode.ARTIFACT_STORAGE_UNSAFE, false, withB.maintenance()::prepare).details().get("reason"));
    }

    @Test
    void aKeyNoCurrentRowPointsAtMayBeGone() throws IOException {
        AttachmentWorld withA = openWithA();
        byte[] content = withA.bytes(50);
        AttachmentService.Kept kept = withA.upload(ALICE, "old.bin", content, 50);

        AttachmentWorld withB = openWithB();
        assertEquals(1, withB.maintenance().rekey("A"));
        Files.delete(withB.keyDirectory().resolve("A")); // no current row points at A any more: the replaced file is not read

        withB.maintenance().prepare();
        assertArrayEquals(content, withB.readAll(kept.attachmentId(), 100));
    }

    // ------------------------------------------------------------------------------------------------------------ k3, k4

    @Test
    void rekeyWritesTheCurrentFilesOfAKeyAgainUnderTheActiveKeyAndTheOldFileLeavesAfterTheGrace() throws SQLException {
        AttachmentWorld withA = openWithA();
        byte[] content = withA.bytes(400);
        AttachmentService.Kept kept = withA.upload(ALICE, "rekey.bin", content, 150);
        AttachmentStorage oldFile = currentOf(withA, kept.attachmentId(), 0);
        assertEquals(0, oldFile.seq());
        assertEquals(0, oldFile.rev());
        assertEquals("A", oldFile.keyId());
        Path oldPath = withA.pathOf(oldFile.storageKey());
        byte[] oldBytes = AttachmentWorld.read(oldPath);
        AttachmentFile fileBefore = withA.service().filesOf(kept.attachmentId()).get(0);
        var attachmentBefore = withA.service().attachment(kept.attachmentId()).orElseThrow();

        AttachmentWorld withB = openWithB();
        withB.clock().set(T0);
        assertEquals(1, withB.maintenance().rekey("A"));

        AttachmentStorage current = currentOf(withB, kept.attachmentId(), 0);
        assertEquals(0, current.seq(), "the file keeps its number");
        assertEquals(1, current.rev(), "the next copy number after the copy that was replaced");
        assertEquals("B", current.keyId());
        assertNotEquals(oldFile.storageKey(), current.storageKey());
        assertEquals(AttachmentWorld.hex(content), withB.service().filesOf(kept.attachmentId()).get(0).contentSha256(), "the file row is unchanged");
        assertEquals(fileBefore, withB.service().filesOf(kept.attachmentId()).get(0));
        assertEquals(attachmentBefore, withB.service().attachment(kept.attachmentId()).orElseThrow(), "and so is the attachment row");
        assertArrayEquals(content, withB.readAll(kept.attachmentId(), 100), "it reads from the new file with the same plain hash");
        assertEquals(AttachmentWorld.sha(content), withB.uploads().attachmentStatus(kept.attachmentId()).files().get(0).sha256());
        assertArrayEquals(oldBytes, AttachmentWorld.read(oldPath), "the old file is not touched");
        assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_file WHERE attachment_id = '" + kept.attachmentId() + "'"), "no file row is added");
        assertEquals(2, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + kept.attachmentId() + "'"), "a copy row is added, none is changed");
        assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + kept.attachmentId() + "' AND seq = 0 AND rev = 0 AND key_id = 'A' AND storage_key = '"
                + oldFile.storageKey() + "'"), "rev 0 is as the finish wrote it");
        assertEquals(0, withB.maintenance().rekey("A"), "nothing is left to write again");
        assertEquals(List.of(), withB.maintenance().check());
        InvalidInputException sameKey = refused(InvalidInputException.class, InputCode.VALIDATION_FAILED, false, () -> withB.maintenance().rekey("B"));
        assertTrue(sameKey.details().get("violations").toString().startsWith("[--from: "), "rekey from the active key is a misuse of the command, not a storage path that cannot be used");

        // the old file is a replaced file: after orphan-grace from the new row it is moved to orphans/, not before
        withB.clock().set(T0.plus(GRACE).minusMillis(1));
        AttachmentMaintenance.Reconciled before = withB.maintenance().reconcileFolder();
        assertEquals(0, before.quarantined());
        assertTrue(Files.exists(oldPath));
        withB.clock().set(T0.plus(GRACE));
        AttachmentMaintenance.Reconciled after = withB.maintenance().reconcileFolder();
        assertEquals(1, after.quarantined());
        assertFalse(Files.exists(oldPath));
        assertEquals(List.of(oldFile.storageKey()), withB.store().quarantinedKeys());
        assertArrayEquals(content, withB.readAll(kept.attachmentId(), 100), "the attachment still reads from its current file");
        assertEquals(List.of(), withB.maintenance().check());
    }

    @Test
    void aNewFileWrittenAndNeverGivenARowLeavesTheAttachmentReadingFromTheOldFileAndARekeySentAgainCompletes() throws IOException {
        AttachmentWorld withA = openWithA();
        byte[] content = withA.bytes(250);
        AttachmentService.Kept kept = withA.upload(ALICE, "failed-rekey.bin", content, 100);
        AttachmentStorage oldFile = currentOf(withA, kept.attachmentId(), 0);
        AttachmentWorld withB = openWithB();
        withB.clock().set(T0);

        // the entrance's file step runs and the service's step never does (the run died between them)
        AttachmentFileStore.Written written = withB.store().rewrite(oldFile.storageKey(), oldFile.keyId(), content.length, AttachmentWorld.hex(content));
        Path newPath = withB.pathOf(written.storageKey());
        assertTrue(Files.exists(newPath));

        AttachmentStorage current = currentOf(withB, kept.attachmentId(), 0);
        assertEquals(oldFile, current, "no row was added: the attachment's current copy is the old one");
        assertArrayEquals(content, withB.readAll(kept.attachmentId(), 100), "it keeps reading from the old file");
        assertEquals(1, withB.service().currentCopiesOfKey("A").size());
        assertEquals(AttachmentService.UseKind.UNREFERENCED, withB.service().useOf(written.storageKey()).kind(), "the new file is a file with no row");

        Files.setLastModifiedTime(newPath, FileTime.from(T0));
        withB.clock().set(T0.plus(GRACE));
        assertEquals(1, withB.maintenance().reconcileFolder().quarantined(), "the file with no row is moved to orphans/ by the comparison, not deleted");
        assertEquals(List.of(written.storageKey()), withB.store().quarantinedKeys());
        assertTrue(Files.exists(withB.pathOf(oldFile.storageKey())), "the old file is the attachment's");

        assertEquals(1, withB.maintenance().rekey("A"), "a rekey sent again does the whole step");
        assertEquals(1, currentOf(withB, kept.attachmentId(), 0).rev());
        assertArrayEquals(content, withB.readAll(kept.attachmentId(), 100));
    }

    // ------------------------------------------------------------------------------------------------------------ k5, k6: a bundle of two files

    @Test
    void aBundleOfTwoFilesWrittenWithKeyAIsRewrittenFileByFileUnderBAndEveryCopyKeepsItsRow() throws SQLException {
        AttachmentWorld withA = openWithA();
        List<AttachmentWorld.Item> items = withA.items(2, 300);
        AttachmentService.Kept kept = withA.uploadBundle(ALICE, items, 100);
        String id = kept.attachmentId();
        AttachmentStorage[] old = {currentOf(withA, id, 0), currentOf(withA, id, 1)};
        Path[] oldPath = {withA.pathOf(old[0].storageKey()), withA.pathOf(old[1].storageKey())};
        byte[][] oldBytes = {AttachmentWorld.read(oldPath[0]), AttachmentWorld.read(oldPath[1])};
        for (int seq = 0; seq < 2; seq++) {
            assertEquals(0, old[seq].rev());
            assertEquals("A", old[seq].keyId());
        }

        AttachmentWorld withB = openWithB();
        withB.clock().set(T0);
        assertEquals(2, withB.service().currentCopiesOfKey("A").size(), "both files are current under A");
        assertEquals(2, withB.maintenance().rekey("A"), "each file of the bundle is written again, so two");

        for (int seq = 0; seq < 2; seq++) {
            AttachmentStorage current = currentOf(withB, id, seq);
            assertEquals(1, current.rev(), "file " + seq + ": the copy after rev 0");
            assertEquals("B", current.keyId());
            assertNotEquals(old[seq].storageKey(), current.storageKey());
            assertArrayEquals(items.get(seq).content(), withB.readAll(id, seq, 100), "file " + seq + " reads from its new copy");
            assertArrayEquals(oldBytes[seq], AttachmentWorld.read(oldPath[seq]), "the old copy is not touched");
            assertEquals(new AttachmentService.KeyUse(AttachmentService.UseKind.REPLACED, T0), withB.service().useOf(old[seq].storageKey()), "the old copy is replaced");
            assertEquals(AttachmentService.UseKind.IN_USE, withB.service().useOf(current.storageKey()).kind(), "the new copy is in use");
            assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = " + seq + " AND rev = 0 AND key_id = 'A' AND storage_key = '"
                    + old[seq].storageKey() + "'"), "rev 0 of file " + seq + " was never changed");
            assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = " + seq + " AND rev = 1 AND key_id = 'B'"));
        }
        assertEquals(4, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "'"), "two copies of each of two files");
        assertEquals(2, withB.count("SELECT count(*) FROM sennard.attachment_file WHERE attachment_id = '" + id + "'"), "no file row was added");
        assertEquals(kept.files(), withB.uploads().attachmentStatus(id).files(), "the facts of the files are as they were sent");
        assertEquals(List.of(), withB.service().currentCopiesOfKey("A"));
        assertEquals(2, withB.service().currentCopiesOfKey("B").size());
        assertEquals(0, withB.maintenance().rekey("A"), "nothing is left to write again");
        assertEquals(List.of(), withB.maintenance().check());

        withB.clock().set(T0.plus(GRACE));
        assertEquals(2, withB.maintenance().reconcileFolder().quarantined(), "after orphan-grace both replaced copies leave the folder");
        assertFalse(Files.exists(oldPath[0]));
        assertFalse(Files.exists(oldPath[1]));
        assertArrayEquals(items.get(0).content(), withB.readAll(id, 0, 100));
        assertArrayEquals(items.get(1).content(), withB.readAll(id, 1, 100));
        assertEquals(List.of(), withB.maintenance().check());
    }

    @Test
    void aBundleWhoseFilesAreWrittenWithDifferentKeysIsRewrittenOnlyWhereTheKeyIsTheOneToLeave() throws SQLException {
        AttachmentWorld withA = openWithA();
        List<AttachmentWorld.Item> items = withA.items(2, 200);
        String id = withA.uploadBundle(ALICE, items, 100).attachmentId();
        AttachmentStorage zero = currentOf(withA, id, 0);
        AttachmentStorage one = currentOf(withA, id, 1);

        // under B the run that wrote file 1 again went as far as its row, and file 0 was left (the run was stopped): file 0 is under A, file 1 under B
        AttachmentWorld withB = openWithB();
        withB.clock().set(T0);
        AttachmentFileStore.Written written = withB.store().rewrite(one.storageKey(), one.keyId(), 200, AttachmentWorld.hex(items.get(1).content()));
        assertEquals(1, withB.service().addCopy(id, 1, written.storageKey(), written.keyId()));
        assertEquals("A", keyOf(withB, id, 0));
        assertEquals("B", keyOf(withB, id, 1));
        assertEquals(List.of(0), withB.service().currentCopiesOfKey("A").stream().map(AttachmentStorage::seq).toList());
        assertEquals(List.of(1), withB.service().currentCopiesOfKey("B").stream().map(AttachmentStorage::seq).toList());

        assertEquals(1, withB.maintenance().rekey("A"), "only file 0 is current under A");

        assertEquals(1, currentOf(withB, id, 0).rev());
        assertEquals("B", keyOf(withB, id, 0));
        assertEquals(1, currentOf(withB, id, 1).rev(), "file 1 was not written again");
        assertEquals(written.storageKey(), currentOf(withB, id, 1).storageKey());
        assertEquals(2, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = 0"));
        assertEquals(2, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE attachment_id = '" + id + "' AND seq = 1"));
        assertEquals(1, withB.count("SELECT count(*) FROM sennard.attachment_storage WHERE storage_key = '" + zero.storageKey() + "' AND rev = 0 AND key_id = 'A'"));
        assertEquals(AttachmentService.UseKind.REPLACED, withB.service().useOf(zero.storageKey()).kind());
        assertEquals(AttachmentService.UseKind.REPLACED, withB.service().useOf(one.storageKey()).kind());
        assertEquals(List.of(), withB.service().currentCopiesOfKey("A"));
        assertEquals(2, withB.service().currentCopiesOfKey("B").size());
        assertArrayEquals(items.get(0).content(), withB.readAll(id, 0, 100));
        assertArrayEquals(items.get(1).content(), withB.readAll(id, 1, 100));
        assertEquals(List.of(), withB.maintenance().check());
    }
}
