// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance.Finding;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentProperties;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsContext;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsRequest;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsResult;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * {@code sennard attachments} against the real business database, with no server: the command's own Spring context reads the settings the way an installation gives them
 * (the link in the settings database of the state folder, {@code sennard.attachments.*}) and opens the store the way the server does; the test makes no connection, no transaction and no store of its own.
 * The attachment tables are the migrated ones (the class starts from a database migrated anew); no row is written, so every check starts from an empty database.
 *
 * <pre>
 * c1  check, empty      an empty database and an empty folder: no finding
 * c2  check, a file     a file that no row names is found as file-without-row and stays where it is (nothing is deleted or moved)
 * c3  keygen            the key file is made, 256 bits, under the id; the answer is the id only; the same id again is refused as an input error (VALIDATION_FAILED, --id) and the first key stays
 * c4  rekey             from the active key: refused as an input error (VALIDATION_FAILED, --from); from a key that no attachment has: 0 written again
 * c5  purge, listed     without confirmation the names in orphans/ are listed and the files stay
 * c6  purge, confirmed  with confirmation the files are deleted and listed, and the names are in the command's audit log
 * c7  purge, compares   without confirmation the folder is compared with the rows first: a file with no row whose grace (24 h) has passed is moved to orphans/ and listed (never deleted), one within
 *                       the grace stays; a second run changes nothing
 * c8  purge, no compare with confirmation only what is in orphans/ is deleted: a file with no row past the grace is neither moved nor deleted by that run
 * </pre>
 */
@Tag("business-db")
class AttachmentsContextTest {
    private static final String FIRST = "aa" + "0".repeat(30);
    private static final String SECOND = "bb" + "1".repeat(30);

    @BeforeAll
    static void migrated() {
        SharedDatabase.resetForTheClass();
    }

    /**
     * The settings of an installation whose state folder is {@code state}, as the command line arguments of the command's context. The installation's business database is the link
     * in the settings database of that folder (where the page saves it), made here before the command's context opens it.
     */
    private static String[] settings(Path state) {
        SharedDatabase.linkInto(state.resolve("settings"));
        List<String> all = new ArrayList<>();
        all.add("--sennard.state-dir=" + state);
        all.add("--sennard.attachments.directory=" + state.resolve("attachments"));
        all.add("--sennard.attachments.key-directory=" + state.resolve("keys"));
        return all.toArray(String[]::new);
    }

    private static AttachmentsResult run(AttachmentsRequest request, Path state) {
        return AttachmentsContext.run(request, state, settings(state));
    }

    @Test
    void c1AnEmptyDatabaseAndAnEmptyFolderHaveNothingToFind(@TempDir Path state) throws IOException {
        Files.createDirectories(state.resolve("attachments"));

        assertEquals(new AttachmentsResult.Checked(List.of()), run(new AttachmentsRequest.Check(), state));
    }

    @Test
    void c2AFileThatNoRowNamesIsFoundAndLeftWhereItIs(@TempDir Path state) throws IOException {
        Path file = state.resolve("attachments").resolve(FIRST.substring(0, 2)).resolve(FIRST);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not an attachment");

        AttachmentsResult.Checked checked = assertInstanceOf(AttachmentsResult.Checked.class, run(new AttachmentsRequest.Check(), state));

        assertEquals(1, checked.findings().size(), checked.toString());
        Finding finding = checked.findings().get(0);
        assertEquals("file-without-row", finding.kind());
        assertEquals(FIRST, finding.id());
        assertTrue(Files.isRegularFile(file), "check deletes and moves nothing");
        assertFalse(Files.exists(state.resolve("attachments").resolve("orphans")), "check moves nothing to the quarantine");
    }

    @Test
    void c3AKeyIsMadeUnderItsIdAndANewKeyDoesNotReplaceOne(@TempDir Path state) throws IOException {
        Path keys = Files.createDirectories(state.resolve("keys"));

        assertEquals(new AttachmentsResult.KeyCreated("key-2"), run(new AttachmentsRequest.Keygen("key-2"), state));

        Path key = keys.resolve("key-2");
        assertEquals(32, Files.size(key), "a 256-bit key");
        byte[] made = Files.readAllBytes(key);
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> run(new AttachmentsRequest.Keygen("key-2"), state));
        assertEquals(InputCode.VALIDATION_FAILED, refused.code(), "a key that exists is a misuse of the command, not a storage path that cannot be used");
        assertEquals(List.of("--id: the key folder already has a path of this id; a key is never replaced"), refused.details().get("violations"));
        assertArrayEquals(made, Files.readAllBytes(key), "the key that was there stays");
    }

    @Test
    void c4ARekeyFromTheActiveKeyIsRefusedAndFromAKeyNoAttachmentHasWritesNothing(@TempDir Path state) throws IOException {
        Files.createDirectories(state.resolve("attachments"));

        // The active key is the one the settings do not name: the first key's id.
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> run(new AttachmentsRequest.Rekey(AttachmentProperties.FIRST_KEY_ID), state));
        assertEquals(InputCode.VALIDATION_FAILED, refused.code(), "the active key as the key to leave is a misuse of the command, not a storage path that cannot be used");
        List<?> violations = assertInstanceOf(List.class, refused.details().get("violations"));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).toString().startsWith("--from: "), violations.toString());

        assertEquals(new AttachmentsResult.Rekeyed("old", 0), run(new AttachmentsRequest.Rekey("old"), state));
    }

    @Test
    void c5WithoutConfirmationThePurgeOnlyListsTheFilesOfTheQuarantine(@TempDir Path state) throws IOException {
        Path orphans = Files.createDirectories(state.resolve("attachments").resolve("orphans"));
        Files.writeString(orphans.resolve(SECOND), "b");
        Files.writeString(orphans.resolve(FIRST), "a");

        assertEquals(new AttachmentsResult.Orphans(List.of(FIRST, SECOND), false), run(new AttachmentsRequest.PurgeOrphans(false), state));

        assertTrue(Files.isRegularFile(orphans.resolve(FIRST)) && Files.isRegularFile(orphans.resolve(SECOND)), "nothing is deleted without confirmation");
        assertFalse(Files.exists(state.resolve("logs")), "an unconfirmed run writes no audit log");
    }

    @Test
    void c6AConfirmedPurgeDeletesTheFilesAndWritesTheirNamesToTheAuditLog() throws IOException {
        // Not a @TempDir: the run's log file is open in the logging system until the next start of one, and on Windows a folder with an open file cannot be deleted by JUnit.
        Path state = Files.createTempDirectory("sennard-attachments-purge-");
        Path orphans = Files.createDirectories(state.resolve("attachments").resolve("orphans"));
        Files.writeString(orphans.resolve(FIRST), "a");
        Files.writeString(orphans.resolve(SECOND), "b");

        assertEquals(new AttachmentsResult.Orphans(List.of(FIRST, SECOND), true), run(new AttachmentsRequest.PurgeOrphans(true), state));

        try (var left = Files.list(orphans)) {
            assertEquals(List.of(), left.toList(), "the quarantine folder is empty");
        }
        String audit = Files.readString(state.resolve("logs").resolve("sennard-attachments.log"));
        assertTrue(audit.contains("ATTACHMENT_QUARANTINE_PURGED") && audit.contains(FIRST) && audit.contains(SECOND), audit);
    }

    @Test
    void c7WithoutConfirmationThePurgeFirstComparesTheFolderWithTheRowsAndListsWhatIsInTheQuarantine(@TempDir Path state) throws IOException {
        Path folder = state.resolve("attachments");
        Path old = folder.resolve(FIRST.substring(0, 2)).resolve(FIRST);
        Path fresh = folder.resolve(SECOND.substring(0, 2)).resolve(SECOND);
        Files.createDirectories(old.getParent());
        Files.createDirectories(fresh.getParent());
        Files.writeString(old, "no row names this file");
        Files.writeString(fresh, "no row names this one either");
        // a file with no row is moved to the quarantine folder once the grace (24 hours) has passed since it was written, not before
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        AttachmentsResult.Orphans listed = assertInstanceOf(AttachmentsResult.Orphans.class, run(new AttachmentsRequest.PurgeOrphans(false), state));

        assertEquals(new AttachmentsResult.Orphans(List.of(FIRST), false), listed, "the file past the grace is in the list because the comparison moved it there");
        assertTrue(Files.isRegularFile(folder.resolve("orphans").resolve(FIRST)), "moved, not deleted");
        assertFalse(Files.exists(old));
        assertTrue(Files.isRegularFile(fresh), "a file within the grace stays where it is");
        assertEquals(new AttachmentsResult.Orphans(List.of(FIRST), false), run(new AttachmentsRequest.PurgeOrphans(false), state), "a second run moves nothing more and lists the same");
    }

    @Test
    void c8AConfirmedPurgeDeletesWhatIsInTheQuarantineAndDoesNotCompareTheFolderFirst() throws IOException {
        // Not a @TempDir: the run's audit log file is open until the next start of the logging system (see c6).
        Path state = Files.createTempDirectory("sennard-attachments-purge-only-");
        Path folder = state.resolve("attachments");
        Path orphans = Files.createDirectories(folder.resolve("orphans"));
        Files.writeString(orphans.resolve(SECOND), "b");
        Path pending = folder.resolve(FIRST.substring(0, 2)).resolve(FIRST);
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "a file with no row, past the grace: the comparison would move it now, after the listing");
        Files.setLastModifiedTime(pending, FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        assertEquals(new AttachmentsResult.Orphans(List.of(SECOND), true), run(new AttachmentsRequest.PurgeOrphans(true), state));

        assertFalse(Files.exists(orphans.resolve(SECOND)));
        assertTrue(Files.isRegularFile(pending), "the confirmed run does not compare the folder: it deleted the file that was listed and neither moved nor deleted this one");
        assertFalse(Files.exists(orphans.resolve(FIRST)));
    }
}
