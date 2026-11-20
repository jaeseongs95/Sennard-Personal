// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * The entrance of the upload tools (the design's 6): what a use case judges from a file is done here, outside any transaction, and the use case is handed the result. It opens no
 * transaction of its own: each step that touches the rows is one method of {@link AttachmentService}, and each step that touches the disk is {@link AttachmentFileStore}'s.
 *
 * <ul>
 * <li>begin: the disk is asked once whether the declared sizes of all the files fit; the rows decide the limits.</li>
 * <li>chunk: the segment hash is checked; the rows say whether the chunk is the next one or one already taken in; the segment is appended under the file
 * lock; the row takes the chunk in with one conditional statement.</li>
 * <li>finish: every file of the bundle is checked whole and closed, one after the other, and only when all of them passed the use case makes the attachment, its files and their
 * stored copies, and marks the rows COMPLETED, in one transaction. A file that fails ends the finish before any row is written, so no attachment is made and the uploads go on (a
 * file already closed stays closed: closing is checked again by the next finish and appends nothing the second time).</li>
 * <li>read: the page's segments are opened and their tags verified; the rows are only read.</li>
 * </ul>
 *
 * Nothing here is sent again by the server. A lost write is found by the sizes and the tags the next request meets, and the client chooses whether to send again.
 */
@Component
public class AttachmentUploads {
    /** What a read answers: the facts of the file and one page of its bytes. */
    public record Read(AttachmentService.FileView view, AttachmentFileStore.Page page) {
    }

    private final AttachmentService service;
    private final AttachmentFileStore store;

    public AttachmentUploads(AttachmentService service, AttachmentFileStore store) {
        this.service = service;
        this.store = store;
    }

    /**
     * Two begins of the same author with the same operationKey that both find no upload insert at the same moment, and the store lets one of them through: the other one's insert is
     * the duplicate of the (owner, operation key) unique key, which the services' translation makes a {@link ConflictCode#DUPLICATE} conflict. That is the only unique key a begin can
     * meet (the other two are an id and a storage key of 128 random bits). The loser is answered with the use case's defined error, ARTIFACT_BUSY (retryable: the begin sent again
     * answers with the winner's uploadId), and never with the store's raw conflict. Nothing is sent again here and the failed transaction is not continued: this is the answer of the
     * entrance, after the use case's transaction has ended.
     */
    public AttachmentService.Begun begin(String owner, AttachmentService.BeginRequest request) {
        long declared = 0;
        for (AttachmentService.BeginFile file : request.files()) {
            // A sum that would overflow saturates: a size that large is refused by the disk, and by the use case's limits after it.
            long size = Math.max(0, file.size());
            declared = declared > Long.MAX_VALUE - size ? Long.MAX_VALUE : declared + size;
        }
        store.requireSpace(declared);
        try {
            return service.begin(owner, request);
        } catch (ConflictException lost) {
            if (lost.code() == ConflictCode.DUPLICATE) {
                throw ArtifactErrors.beginInProgress(lost);
            }
            throw lost;
        }
    }

    /** Stores an encrypted segment of the file received through the multipart entrance. */
    public AttachmentService.Progress chunk(String uploadId, long sequence, byte[] plain, String chunkSha256) {
        if (plain == null || plain.length > AttachmentFileStore.MAX_CHUNK_BYTES) throw refusal("chunk: too large");
        if (!Sha256Text.isText(chunkSha256)) {
            throw refusal("chunkSha256: a sha256 as sha256:<64 hex digits>");
        }
        String chunkHex = Sha256Text.hex(chunkSha256);
        if (!Digests.sha256Hex(plain).equals(chunkHex)) {
            throw ArtifactErrors.chunkHashMismatch();
        }
        return switch (service.planChunk(uploadId, sequence, chunkSha256, plain.length)) {
            case AttachmentService.ChunkPlan.Replayed replayed -> replayed.progress();
            case AttachmentService.ChunkPlan.Next next -> {
                UploadStaging row = next.row();
                long stored = store.append(row.storageKey(), row.keyId(), sequence, row.storedBytes(), plain, chunkHex);
                yield service.takeInChunk(uploadId, sequence, stored, row.receivedBytes() + plain.length, chunkSha256);
            }
        };
    }

    /** A whole multipart file, continuing from the bytes already committed. No file IO holds a database transaction. */
    public AttachmentService.UploadStatus transfer(String uploadId, InputStream input, long size) throws IOException {
        AttachmentService.UploadStatus status = uploadStatus(uploadId);
        if (size != status.size()) throw ArtifactErrors.invalidArgument("file", "size differs from the declared file");
        if (UploadStaging.State.COMPLETED.name().equals(status.state())) return status;
        input.skipNBytes(status.received());
        long offset = status.received();
        long sequence = status.sequence();
        boolean more = offset < size || size == 0 && sequence == 0;
        while (more) {
            int length = (int) Math.min(AttachmentFileStore.MAX_CHUNK_BYTES, size - offset);
            byte[] plain = input.readNBytes(length);
            if (plain.length != length) throw ArtifactErrors.incomplete("multipart-file-shorter-than-declared");
            chunk(uploadId, sequence++, plain, Sha256Text.of(Digests.sha256Hex(plain)));
            offset += length;
            more = offset < size;
        }
        if (input.read() != -1) throw ArtifactErrors.invalidArgument("file", "size exceeds the declared file");
        return uploadStatus(uploadId);
    }

    public AttachmentService.Kept finish(String attachmentId) {
        return switch (service.planFinish(attachmentId)) {
            case AttachmentService.FinishPlan.Done done -> done.kept();
            case AttachmentService.FinishPlan.Pending pending -> {
                List<AttachmentFileStore.Closed> closed = new ArrayList<>();
                for (UploadStaging row : pending.rows()) {
                    closed.add(store.close(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(), row.declaredSize(), row.declaredSha256()));
                }
                yield service.completeFinish(attachmentId, List.copyOf(closed));
            }
        };
    }

    /** The state of an upload in progress: one file of a bundle. */
    public AttachmentService.UploadStatus uploadStatus(String uploadId) {
        return service.uploadStatus(uploadId).orElseThrow(ArtifactErrors::notFound);
    }

    /** The facts of a kept attachment: its files. */
    public AttachmentService.AttachmentStatus attachmentStatus(String attachmentId) {
        return service.attachmentStatus(attachmentId).orElseThrow(ArtifactErrors::notFound);
    }

    public Read read(String attachmentId, int seq, long offsetBytes, int maxBytes) {
        AttachmentService.FileView view = service.view(attachmentId, seq).orElseThrow(ArtifactErrors::notFound);
        AttachmentStorage copy = view.current();
        return new Read(view, store.read(copy.storageKey(), copy.keyId(), offsetBytes, maxBytes, view.file().sizeBytes()));
    }

    private static InvalidInputException refusal(String violation) {
        return new InvalidInputException(InputCode.VALIDATION_FAILED, Map.of(), Map.of("violations", List.of(violation)));
    }
}
