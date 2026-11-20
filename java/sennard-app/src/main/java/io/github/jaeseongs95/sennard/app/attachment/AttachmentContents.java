// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * What a record of the server itself reads from an attachment it points at (a stage result's output file, the frozen request of a stage, the audited attachments of a finish): the
 * entrance reads here, outside any transaction, and hands the use case the value. It opens no transaction of its own; the rows are read by {@link AttachmentService}'s read-only
 * methods and the bytes by {@link AttachmentFileStore}. Nothing here is a path of the caller's computer: an attachment is read by its id only.
 */
@Component
public class AttachmentContents {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentContents.class);

    /** What a record that is bound to an attachment's digest finds when it asks again for the attachment (the finish of a run). */
    public enum Standing {
        /** The attachment is there, has the digest, and its file is read whole with every tag, its end mark and its size and sha256 right. */
        INTACT,
        /** No attachment has the id. */
        MISSING,
        /** The attachment has another sha256 than the one the record is bound to. */
        DIFFERENT,
        /** The attachment's file cannot be read whole now: it is damaged, missing, or its key or folder cannot be used. */
        UNREADABLE
    }

    /** An attachment's file name as it was uploaded, and its plain bytes. */
    public record Named(String fileName, byte[] bytes) {
    }

    private final AttachmentService service;
    private final AttachmentFileStore store;

    public AttachmentContents(AttachmentService service, AttachmentFileStore store) {
        this.service = service;
        this.store = store;
    }

    /**
     * The plain bytes of file {@code seq} of an attachment of at most {@code limitBytes}, read page by page with every segment's tag verified.
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.NotFoundException ARTIFACT_NOT_FOUND when no attachment has the id, or it has no file of that number
     * @throws io.github.jaeseongs95.sennard.domain.error.UnavailableException ARTIFACT_LIMIT (file-size) for a file over the limit
     * @throws IntegrityException ARTIFACT_CORRUPTED for a damaged file
     */
    public byte[] readWhole(String attachmentId, int seq, int limitBytes) {
        return readNamed(attachmentId, seq, limitBytes).bytes();
    }

    /** As {@link #readWhole}, with the file name the file was uploaded under. */
    public Named readNamed(String attachmentId, int seq, int limitBytes) {
        AttachmentService.FileView view = service.view(attachmentId, seq).orElseThrow(ArtifactErrors::notFound);
        long size = view.file().sizeBytes();
        if (size > limitBytes) {
            throw ArtifactErrors.limit(ArtifactErrors.Limit.FILE_SIZE, size, limitBytes);
        }
        AttachmentStorage copy = view.current();
        ByteArrayOutputStream whole = new ByteArrayOutputStream((int) size);
        long offset = 0;
        AttachmentFileStore.Page page;
        do {
            page = store.read(copy.storageKey(), copy.keyId(), offset, AttachmentFileStore.MAX_CHUNK_BYTES, size);
            whole.writeBytes(page.data());
            offset += page.data().length;
        } while (!page.end());
        return new Named(view.file().fileName(), whole.toByteArray());
    }

    /** Whether the file is there with the sha256 a record is bound to and its stored copy is whole (a finish asks it again of what an audit was recorded on). */
    public Standing standing(String attachmentId, int seq, String sha256) {
        Optional<AttachmentService.FileView> found = service.view(attachmentId, seq);
        if (found.isEmpty()) {
            return Standing.MISSING;
        }
        AttachmentFile file = found.get().file();
        if (!file.contentSha256().equals(Sha256Text.hex(sha256))) {
            return Standing.DIFFERENT;
        }
        AttachmentStorage copy = found.get().current();
        try {
            store.verify(copy.storageKey(), copy.keyId(), file.sizeBytes(), file.contentSha256());
            return Standing.INTACT;
        } catch (IntegrityException | UnavailableException unreadable) {
            // The answer of this method is the fact that the file is not whole, which the use case judges; the cause is logged, not lost.
            LOG.warn("The file {} of the attachment {} cannot be read whole: {}", seq, attachmentId, unreadable.code(), unreadable);
            return Standing.UNREADABLE;
        }
    }
}
