// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The failures of the attachments, one factory each, so that every one carries the same {@code details.retryable} (docs/contributing/attachments.md, 3.2): true when the same
 * request, sent again later, can succeed. The product never sends it again itself: a retry is for a failure of communication only. Every code of the shared table is made here
 * and nowhere else, so none can leave without its value.
 */
public final class ArtifactErrors {
    /** Which limit {@code ARTIFACT_LIMIT} names ({@code details.limit}) and whether waiting can free it. */
    public enum Limit {
        /** One file is larger than {@code max-file-bytes}. */
        FILE_SIZE("file-size", false),
        /** One attachment is asked to have more files than {@link AttachmentService#MAX_FILES}; {@code requested} and {@code allowed} are counts of files here, not bytes. */
        FILE_COUNT("file-count", false),
        /** The kept attachments and the open uploads are at {@code attachment-budget-bytes}: the owner has to clean up attachments. */
        ATTACHMENT_BUDGET("attachment-budget", false),
        /** The open uploads are at {@code staging-budget-bytes}: an upload that ends, or the cleaner, frees it. */
        STAGING_BUDGET("staging-budget", true),
        /** The author has {@code max-open-per-owner} uploads open: one that ends, or the cleaner, frees it. */
        OPEN_UPLOADS("open-uploads", true);

        private final String wire;
        private final boolean retryable;

        Limit(String wire, boolean retryable) {
            this.wire = wire;
            this.retryable = retryable;
        }

        /** The value of {@code details.limit}. */
        public String wire() {
            return wire;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private ArtifactErrors() {
    }

    private static Map<String, Object> details(boolean retryable, Object... pairs) {
        Map<String, Object> details = new LinkedHashMap<>(SennardException.args(pairs));
        details.put("retryable", retryable);
        return details;
    }

    public static ConflictException operationConflict() {
        return new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT, Map.of(), details(false));
    }

    public static ConflictException chunkConflict(String reason) {
        return new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, Map.of(), details(false, "reason", reason));
    }

    public static ConflictException busy() {
        return new ConflictException(ConflictCode.ARTIFACT_BUSY, Map.of(), details(true));
    }

    /**
     * The begin that lost a race: another begin of the same author with the same operationKey inserted its upload first, and the store refused this one's insert as a duplicate of the
     * (owner, operation key) unique key. It is the use case's own defined answer, not the store's raw unique violation ({@code cause} is only for the log): retryable, because the same
     * begin sent again finds the winner's upload and answers with its uploadId (or with ARTIFACT_OPERATION_CONFLICT when the content differs). {@code details.reason} is
     * {@code begin-in-progress}.
     */
    public static ConflictException beginInProgress(Throwable cause) {
        return new ConflictException(ConflictCode.ARTIFACT_BUSY, Map.of(), details(true, "reason", "begin-in-progress"), cause);
    }

    /** Fewer bytes than declared, or a tail the row has not taken in: the missing chunks are to be sent, so the same finish sent again does not pass. */
    public static ConflictException incomplete(String reason) {
        return new ConflictException(ConflictCode.ARTIFACT_INCOMPLETE, Map.of(), details(false, "reason", reason));
    }

    public static ConflictException expired() {
        return new ConflictException(ConflictCode.ARTIFACT_EXPIRED, Map.of(), details(false));
    }

    public static IntegrityException hashMismatch(String reason) {
        return new IntegrityException(IntegrityCode.ARTIFACT_HASH_MISMATCH, Map.of(), details(false, "reason", reason));
    }

    public static IntegrityException chunkHashMismatch() {
        return new IntegrityException(IntegrityCode.CHUNK_HASH_MISMATCH, Map.of(), details(false));
    }

    public static IntegrityException corrupted(String reason) {
        return new IntegrityException(IntegrityCode.ARTIFACT_CORRUPTED, Map.of(), details(false, "reason", reason));
    }

    public static NotFoundException notFound() {
        return new NotFoundException(NotFoundCode.ARTIFACT_NOT_FOUND, Map.of(), details(false));
    }

    public static InvalidInputException unsupportedPurpose(String purpose) {
        return new InvalidInputException(InputCode.ARTIFACT_UNSUPPORTED_PURPOSE, Map.of(), details(false, "purpose", purpose));
    }

    /**
     * An argument of {@code sennard attachments} that the command cannot use: the key to re-encrypt from is the active key, a key id that is no file name, a key that exists is to be made
     * again. It is an input error ({@code INVALID_INPUT}, reason {@code VALIDATION_FAILED}, {@code details.violations}, the family the upload entrance's refusals of an argument are
     * in), never ARTIFACT_STORAGE_UNSAFE: that code only says that a storage path cannot be used (the design's 3.2, edition 11), and the owner fixes the command line and not the
     * storage. Not retryable: the same command, sent again, is refused again.
     *
     * @param option the option the argument was given to, as on the command line ({@code --from}, {@code --id})
     * @param problem what is wrong with it
     */
    public static InvalidInputException invalidArgument(String option, String problem) {
        return new InvalidInputException(InputCode.VALIDATION_FAILED, Map.of(), details(false, "violations", List.of(option + ": " + problem)));
    }

    public static UnavailableException limit(Limit limit, long requestedBytes, long limitBytes) {
        return new UnavailableException(UnavailableCode.ARTIFACT_LIMIT, SennardException.args("limit", limit.wire()),
                details(limit.retryable(), "limit", limit.wire(), "requested", requestedBytes, "allowed", limitBytes), null);
    }

    /** An open-upload count over the limit. */
    public static UnavailableException openUploads(long open, long allowed) {
        return limit(Limit.OPEN_UPLOADS, open + 1, allowed);
    }

    public static UnavailableException storageFull(long requestedBytes, long usableBytes, long marginBytes) {
        return new UnavailableException(UnavailableCode.ARTIFACT_STORAGE_FULL, Map.of(),
                details(false, "requestedBytes", requestedBytes, "usableBytes", usableBytes, "marginBytes", marginBytes), null);
    }

    public static UnavailableException storageIo(Throwable cause) {
        return new UnavailableException(UnavailableCode.ARTIFACT_STORAGE_IO_ERROR, Map.of(), details(false), cause);
    }

    public static UnavailableException storageUnsafe(String reason) {
        return new UnavailableException(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, Map.of(), details(false, "reason", reason), null);
    }

    /**
     * A refusal of the {@code PrivateStorage} port (AGS3-D-0268, design edition 11) in the attachments' own codes. ARTIFACT_STORAGE_UNSAFE means that the storage path cannot be used
     * for this purpose, and the owner fixes the storage location: a path that is not a regular file or a folder (PRIVATE_STORAGE_NOT_PLAIN, reason {@code notPlainReason}), a file
     * system that cannot make a path owner-only (PRIVATE_STORAGE_UNSUPPORTED, reason {@code filesystem-unsupported}) and an account the system does not know
     * (PRIVATE_STORAGE_USER_UNKNOWN, reason {@code account-unknown}). An empty secret file (PRIVATE_STORAGE_EMPTY) is the reader's to judge, because ARTIFACT_KEY_MISSING names a key id
     * ({@link #keyUnusable}); it is listed so that no code of the port is left to the default, and a caller that reaches it here gets an I/O error, as for any other refusal.
     */
    public static UnavailableException privateStorage(UnavailableException refusal, String notPlainReason) {
        return switch (refusal.code()) {
            case UnavailableCode.PRIVATE_STORAGE_NOT_PLAIN -> storageUnsafe(notPlainReason);
            case UnavailableCode.PRIVATE_STORAGE_EMPTY -> storageIo(refusal);
            case UnavailableCode.PRIVATE_STORAGE_UNSUPPORTED -> storageUnsafe("filesystem-unsupported");
            case UnavailableCode.PRIVATE_STORAGE_USER_UNKNOWN -> storageUnsafe("account-unknown");
            default -> storageIo(refusal);
        };
    }

    /** A key file that is there and is no key (empty, or not 256 bits): ARTIFACT_KEY_MISSING, with the reason ({@code empty} or {@code wrong-length}); the owner restores the key file. */
    public static UnavailableException keyUnusable(String keyId, String reason) {
        return new UnavailableException(UnavailableCode.ARTIFACT_KEY_MISSING, Map.of(), details(false, "keyId", keyId, "reason", reason), null);
    }

    public static UnavailableException keyMissing(String keyId) {
        return new UnavailableException(UnavailableCode.ARTIFACT_KEY_MISSING, Map.of(), details(false, "keyId", keyId), null);
    }
}
