// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.upload_staging}: an upload in progress, the one table of the attachments that changes. An upload is one file of a bundle: {@code attachmentId} is the id of
 * the attachment the finish makes (the uploads begun together share it) and {@code seq} the file's number in it. The key is the upload's own id. {@code owner} is the author;
 * {@code nextSequence} the number the next chunk must have; {@code storedBytes} the length of the file the row has taken in; {@code receivedBytes} the plain bytes received. The rows are
 * changed by one conditional statement each ({@link UploadStagingRepository}), so the entity is read and never saved.
 */
@Table(schema = "sennard", value = "upload_staging")
public record UploadStaging(@Id String id, String attachmentId, int seq, String owner, String operationKey, String fileName, String mediaType, long declaredSize, String declaredSha256,
        long receivedBytes, long storedBytes, long nextSequence, String lastChunkSha256, String storageKey, String keyId, State state, Instant expiresAt, Instant createdAt, Instant updatedAt) {
    /** What the {@code state} column holds (a CHECK lists the two). */
    public enum State {
        STAGING, COMPLETED
    }
}
