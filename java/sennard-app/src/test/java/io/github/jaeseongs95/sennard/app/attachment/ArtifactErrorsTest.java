// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.Category;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.ErrorCode;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The failures of the attachments (design 3.2 and the "retryable" supplement of edition 9): every code of the shared table is made by {@link ArtifactErrors} and carries
 * {@code details.retryable} with the design's value, and is published under its own name.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 2/9/10/13/16/19  e1  every code carries retryable   every factory of the table makes an exception whose details hold a boolean {@code retryable}
 * 10/17/20         e2  ARTIFACT_LIMIT by limit        file-size false, file-count false, attachment-budget false, staging-budget true, open-uploads true; details.limit names which (and requested, allowed)
 * 3.2              e3  the values of the table        ARTIFACT_STORAGE_FULL false; ARTIFACT_BUSY true; ARTIFACT_INCOMPLETE, IO_ERROR, UNSAFE, KEY_MISSING, EXPIRED, NOT_FOUND, the hash
 *                                                    codes, the conflicts, the damage and the purpose false
 * 3.2              e4  the name on the wire            the wire code of every attachment code is the constant's own name (no category name, no reason code)
 * 3.2              e5  the details of the others       STORAGE_FULL carries requestedBytes, usableBytes and marginBytes; KEY_MISSING the key id; the I/O error keeps its cause
 * 3.2              e6  a command argument that cannot be used   an input error (INVALID_INPUT, reason VALIDATION_FAILED, details.violations), retryable false, never ARTIFACT_STORAGE_UNSAFE
 * 3.2/6            e7  a begin that lost the race      ARTIFACT_BUSY (not the store's DUPLICATE), retryable true, details.reason begin-in-progress, the store's failure only as the cause
 * </pre>
 */
class ArtifactErrorsTest {
    private static void expect(SennardException failure, Category category, ErrorCode code, boolean retryable) {
        assertEquals(category, failure.category());
        assertEquals(code, failure.code());
        assertEquals(Boolean.valueOf(retryable), failure.details().get("retryable"), code + " retryable");
        assertEquals(code.name(), code.wireCode(), "published under its own name");
    }

    @Test
    void everyCodeOfTheTableCarriesItsRetryableValue() {
        expect(ArtifactErrors.operationConflict(), Category.CONFLICT, ConflictCode.ARTIFACT_OPERATION_CONFLICT, false);
        expect(ArtifactErrors.chunkConflict("order"), Category.CONFLICT, ConflictCode.ARTIFACT_CHUNK_CONFLICT, false);
        expect(ArtifactErrors.busy(), Category.CONFLICT, ConflictCode.ARTIFACT_BUSY, true);
        expect(ArtifactErrors.beginInProgress(new IllegalStateException("duplicate key")), Category.CONFLICT, ConflictCode.ARTIFACT_BUSY, true);
        expect(ArtifactErrors.incomplete("bytes-missing"), Category.CONFLICT, ConflictCode.ARTIFACT_INCOMPLETE, false);
        expect(ArtifactErrors.expired(), Category.CONFLICT, ConflictCode.ARTIFACT_EXPIRED, false);
        expect(ArtifactErrors.hashMismatch("sha256"), Category.INTEGRITY, IntegrityCode.ARTIFACT_HASH_MISMATCH, false);
        expect(ArtifactErrors.chunkHashMismatch(), Category.INTEGRITY, IntegrityCode.CHUNK_HASH_MISMATCH, false);
        expect(ArtifactErrors.corrupted("segment-tag"), Category.INTEGRITY, IntegrityCode.ARTIFACT_CORRUPTED, false);
        expect(ArtifactErrors.notFound(), Category.NOT_FOUND, NotFoundCode.ARTIFACT_NOT_FOUND, false);
        expect(ArtifactErrors.unsupportedPurpose("upload"), Category.INPUT, InputCode.ARTIFACT_UNSUPPORTED_PURPOSE, false);
        expect(ArtifactErrors.storageFull(10, 20, 30), Category.UNAVAILABLE, UnavailableCode.ARTIFACT_STORAGE_FULL, false);
        expect(ArtifactErrors.storageIo(new IOException("disk")), Category.UNAVAILABLE, UnavailableCode.ARTIFACT_STORAGE_IO_ERROR, false);
        expect(ArtifactErrors.storageUnsafe("owner"), Category.UNAVAILABLE, UnavailableCode.ARTIFACT_STORAGE_UNSAFE, false);
        expect(ArtifactErrors.keyMissing("primary"), Category.UNAVAILABLE, UnavailableCode.ARTIFACT_KEY_MISSING, false);
        expect(ArtifactErrors.limit(ArtifactErrors.Limit.FILE_SIZE, 2, 1), Category.UNAVAILABLE, UnavailableCode.ARTIFACT_LIMIT, false);
    }

    @Test
    void theExceptionsAreOfTheCategoriesTheTableNames() {
        assertInstanceOf(ConflictException.class, ArtifactErrors.busy());
        assertInstanceOf(IntegrityException.class, ArtifactErrors.corrupted("x"));
        assertInstanceOf(NotFoundException.class, ArtifactErrors.notFound());
        assertInstanceOf(InvalidInputException.class, ArtifactErrors.unsupportedPurpose("staged"));
        assertInstanceOf(UnavailableException.class, ArtifactErrors.keyMissing("k"));
    }

    @Test
    void theLimitCodeSaysWhichLimitAndWhetherWaitingFreesIt() {
        Map<ArtifactErrors.Limit, Boolean> expected = new LinkedHashMap<>();
        expected.put(ArtifactErrors.Limit.FILE_SIZE, false);
        expected.put(ArtifactErrors.Limit.FILE_COUNT, false);
        expected.put(ArtifactErrors.Limit.ATTACHMENT_BUDGET, false);
        expected.put(ArtifactErrors.Limit.STAGING_BUDGET, true);
        expected.put(ArtifactErrors.Limit.OPEN_UPLOADS, true);
        assertEquals(ArtifactErrors.Limit.values().length, expected.size(), "a limit added later has its value stated here");

        Map<ArtifactErrors.Limit, String> wire = Map.of(ArtifactErrors.Limit.FILE_SIZE, "file-size", ArtifactErrors.Limit.FILE_COUNT, "file-count", ArtifactErrors.Limit.ATTACHMENT_BUDGET, "attachment-budget",
                ArtifactErrors.Limit.STAGING_BUDGET, "staging-budget", ArtifactErrors.Limit.OPEN_UPLOADS, "open-uploads");
        for (Map.Entry<ArtifactErrors.Limit, Boolean> entry : expected.entrySet()) {
            UnavailableException failure = ArtifactErrors.limit(entry.getKey(), 5, 4);
            assertEquals(UnavailableCode.ARTIFACT_LIMIT, failure.code());
            assertEquals(entry.getValue(), failure.details().get("retryable"), entry.getKey() + " retryable");
            assertEquals(entry.getValue().booleanValue(), entry.getKey().retryable());
            assertEquals(wire.get(entry.getKey()), entry.getKey().wire());
            assertEquals(wire.get(entry.getKey()), failure.details().get("limit"), "details.limit tells which limit it is");
            assertEquals(wire.get(entry.getKey()), failure.arguments().get("limit"));
            assertEquals(5L, failure.details().get("requested"));
            assertEquals(4L, failure.details().get("allowed"));
        }
    }

    @Test
    void anOpenUploadCountOverTheLimitIsTheOpenUploadsLimit() {
        UnavailableException failure = ArtifactErrors.openUploads(4, 4);

        assertEquals(UnavailableCode.ARTIFACT_LIMIT, failure.code());
        assertEquals("open-uploads", failure.details().get("limit"));
        assertEquals(Boolean.TRUE, failure.details().get("retryable"), "an upload that ends, or the cleaner, frees it");
        assertEquals(5L, failure.details().get("requested"), "the upload that was refused would have been the fifth");
        assertEquals(4L, failure.details().get("allowed"));
    }

    @Test
    void theDiskIsFullCodeCarriesTheSpaceItJudgedFrom() {
        UnavailableException failure = ArtifactErrors.storageFull(100, 150, 64);

        assertEquals(Boolean.FALSE, failure.details().get("retryable"), "the owner has to free the disk");
        assertEquals(100L, failure.details().get("requestedBytes"));
        assertEquals(150L, failure.details().get("usableBytes"));
        assertEquals(64L, failure.details().get("marginBytes"));
    }

    @Test
    void theKeyMissingCodeNamesTheKeyAndTheIoCodeKeepsItsCause() {
        assertEquals("secondary", ArtifactErrors.keyMissing("secondary").details().get("keyId"));
        IOException cause = new IOException("disk");
        UnavailableException io = ArtifactErrors.storageIo(cause);
        assertSame(cause, io.getCause(), "the cause is for the log and is never shown");
        assertNotNull(io.details().get("retryable"));
        assertEquals("owner", ArtifactErrors.storageUnsafe("owner").details().get("reason"));
        UnavailableException unusable = ArtifactErrors.keyUnusable("secondary", "empty");
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, unusable.code());
        assertEquals("secondary", unusable.details().get("keyId"));
        assertEquals("empty", unusable.details().get("reason"));
        assertEquals(Boolean.FALSE, unusable.details().get("retryable"));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_IO_ERROR, ArtifactErrors.privateStorage(
                new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED), "x").code(), "a refusal of the port that is none of the three is an I/O error");
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_IO_ERROR, ArtifactErrors.privateStorage(
                new UnavailableException(UnavailableCode.PRIVATE_STORAGE_EMPTY), "x").code(), "an empty secret file is judged by the key reader (ARTIFACT_KEY_MISSING), not by the folder code");
        assertEquals("order", ArtifactErrors.chunkConflict("order").details().get("reason"));
        assertEquals("upload", ArtifactErrors.unsupportedPurpose("upload").details().get("purpose"));
    }

    @Test
    void aBeginThatLostTheRaceIsBusyAndRetryableNamesItsReasonAndKeepsTheStoresFailureForTheLogOnly() {
        IllegalStateException duplicate = new IllegalStateException("duplicate key value violates unique constraint");
        ConflictException busy = ArtifactErrors.beginInProgress(duplicate);

        assertEquals(ConflictCode.ARTIFACT_BUSY, busy.code(), "a code of the shared table, not the store's DUPLICATE");
        assertEquals(Boolean.TRUE, busy.details().get("retryable"), "the same begin sent again answers with the winner's uploadId");
        assertEquals("begin-in-progress", busy.details().get("reason"));
        assertSame(duplicate, busy.getCause(), "the cause is for the log and is never shown");
        assertEquals(Map.of(), busy.arguments());
    }

    @Test
    void anArgumentTheCommandCannotUseIsAnInputErrorWithItsViolationAndNeverAStorageError() {
        InvalidInputException failure = ArtifactErrors.invalidArgument("--from", "it is the active key");

        assertEquals(Category.INPUT, failure.category());
        assertEquals(InputCode.VALIDATION_FAILED, failure.code(), "the family of the upload entrance's refusals of an argument, not ARTIFACT_STORAGE_UNSAFE");
        assertEquals(Boolean.FALSE, failure.details().get("retryable"), "the same command is refused again");
        assertEquals(List.of("--from: it is the active key"), failure.details().get("violations"));
    }
}
