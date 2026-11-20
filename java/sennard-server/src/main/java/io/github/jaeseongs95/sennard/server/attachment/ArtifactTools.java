// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.attachment;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads;
import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCall;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * Attachment tools begin and finish bundles, inspect their status and read completed files.
 * A client sends each file through the multipart entrance using its upload ID.
 * The author of an upload is the session that begins it ({@link WorkflowCall#sessionActor}).
 *
 * <p>The input records' components are the contracts' keys (contracts/artifact-*-request.v1.schema.json); the contract itself is enforced and published by {@code ContractGuard}.
 * Every tool goes through {@link AttachmentUploads}, which does the disk work and calls the use cases of the attachment service one at a time.
 */
@Configuration(proxyBeanMethods = false)
public class ArtifactTools {
    /** One file of a begin; the files are numbered 0, 1, 2 ... in the order of the list. */
    public record FileInput(String fileName, String mediaType, long size, String sha256) {
    }

    public record BeginInput(String schemaVersion, String operationKey, List<FileInput> files, String purpose) {
    }

    /** A finish is bound to the bundle by the id of the attachment it makes: {@code artifact_begin} answered it. */
    public record FinishInput(String schemaVersion, String attachmentId) {
    }

    /** Exactly one of {@code uploadId} (a file of a bundle in progress) and {@code attachmentId} (a kept attachment), as the contract says. */
    public record StatusInput(String schemaVersion, String uploadId, String attachmentId) {
    }

    /** {@code seq} is the number of the file in the attachment; 0 when it is left out. */
    public record ReadInput(String schemaVersion, String attachmentId, Integer seq, Long offsetBytes, Integer maxBytes) {
    }

    /** The upload of one file of the bundle {@code artifact_begin} made. */
    public record UploadAnswer(int seq, String uploadId) {
    }

    /** What {@code artifact_begin} answers: the id of the attachment the finish makes, and the upload of each file in the order of the files. */
    public record BeginAnswer(String attachmentId, List<UploadAnswer> files, int maxChunkBytes, String expiresAt) {
    }

    /** A file of a kept attachment: its number, name, media type, size and sha256. */
    public record FileAnswer(int seq, String fileName, String mediaType, long size, String sha256) {
    }

    /** What {@code artifact_finish} answers: the attachment and its files. */
    public record FinishAnswer(String attachmentId, List<FileAnswer> files) {
    }

    /**
     * What {@code artifact_status} answers; nothing in it is a path or a storage key. {@code kind} is {@code upload} for a file of a bundle in progress (the fields of the file, with
     * {@code expiresAt}, {@code received} and {@code sequence}) and {@code attachment} for a kept attachment (its {@code files}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusAnswer(String kind, String uploadId, String attachmentId, Integer seq, String state, String fileName, String mediaType, Long size, String sha256, String expiresAt,
            Long received, Long sequence, List<FileAnswer> files) {
    }

    /** What {@code artifact_read} answers: one page of the bytes of file {@code seq}, in base64, and the same page as text when the file is text and the page is whole UTF-8. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadAnswer(String attachmentId, int seq, long offsetBytes, int bytes, long size, boolean end, String data, String text) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactTools.class);

    /** What a read without a size asks for: the largest chunk, the contract's constant. */
    static final int DEFAULT_PAGE_BYTES = AttachmentFileStore.MAX_CHUNK_BYTES;

    // @Bean: a List of the MCP SDK's SyncToolSpecification (library types), made by the tool entrance from the declarations below.
    @Bean
    List<SyncToolSpecification> artifactTools(ToolEntrance entrance, AttachmentUploads uploads, io.github.jaeseongs95.sennard.service.identity.SessionRegistry sessions) {
        return List.of(
                entrance.tool("artifact_begin", BeginInput.class, hints(false), (session, input) -> begin(uploads, sessions, session, input)),
                // Finish is bound to the attachment ID and may be called without a session.
                entrance.toolWithOptionalSession("artifact_finish", FinishInput.class, hints(false), (binding, input) -> finish(uploads, input)),
                // The state of an upload is read by its id alone as well; an attachment's is read as before, by a session.
                entrance.toolWithOptionalSession("artifact_status", StatusInput.class, hints(true), (binding, input) -> status(uploads, binding, input)),
                entrance.tool("artifact_read", ReadInput.class, hints(true), (session, input) -> read(uploads, input)));
    }

    private static BeginAnswer begin(AttachmentUploads uploads, io.github.jaeseongs95.sennard.service.identity.SessionRegistry sessions, SelfSession session, BeginInput input) {
        List<AttachmentService.BeginFile> files = input.files().stream().map(file -> new AttachmentService.BeginFile(file.fileName(), file.mediaType(), file.size(), file.sha256())).toList();
        // A CLI names the server-issued session reference. Use its registered identity for ownership without claiming host observation.
        String author = session.hostSessionId() != null ? WorkflowCall.sessionActor(session) : sessions.identityOf(session.sessionRef())
                .map(identity -> WorkflowCall.actorId(identity.host(), identity.hostSessionId() == null ? session.sessionRef() : identity.hostSessionId(), null))
                .orElseThrow(io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors::notFound);
        AttachmentService.Begun begun = uploads.begin(author, new AttachmentService.BeginRequest(input.operationKey(), files, input.purpose()));
        return new BeginAnswer(begun.attachmentId(), begun.files().stream().map(file -> new UploadAnswer(file.seq(), file.uploadId())).toList(), begun.maxChunkBytes(),
                begun.expiresAt().toString());
    }

    private static FinishAnswer finish(AttachmentUploads uploads, FinishInput input) {
        AttachmentService.Kept kept = uploads.finish(input.attachmentId());
        return new FinishAnswer(kept.attachmentId(), fileAnswers(kept.files()));
    }

    private static List<FileAnswer> fileAnswers(List<AttachmentService.KeptFile> files) {
        return files.stream().map(file -> new FileAnswer(file.seq(), file.fileName(), file.mediaType(), file.size(), file.sha256())).toList();
    }

    private static StatusAnswer status(AttachmentUploads uploads, SessionBinding binding, StatusInput input) {
        if (input.uploadId() != null) {
            AttachmentService.UploadStatus status = uploads.uploadStatus(input.uploadId());
            return new StatusAnswer("upload", status.uploadId(), status.attachmentId(), status.seq(), status.state(), status.fileName(), status.mediaType(), status.size(), status.sha256(),
                    status.expiresAt().toString(), status.received(), status.sequence(), null);
        }
        binding.require();
        AttachmentService.AttachmentStatus status = uploads.attachmentStatus(input.attachmentId());
        return new StatusAnswer("attachment", null, status.attachmentId(), null, "COMPLETED", null, null, null, null, null, null, null, fileAnswers(status.files()));
    }

    private static ReadAnswer read(AttachmentUploads uploads, ReadInput input) {
        int seq = input.seq() == null ? 0 : input.seq();
        long offset = input.offsetBytes() == null ? 0 : input.offsetBytes();
        int max = input.maxBytes() == null ? DEFAULT_PAGE_BYTES : input.maxBytes();
        AttachmentUploads.Read read = uploads.read(input.attachmentId(), seq, offset, max);
        byte[] bytes = read.page().data();
        String text = read.view().file().mediaType().startsWith("text/") ? strictUtf8(bytes) : null;
        return new ReadAnswer(read.view().attachment().id(), seq, offset, bytes.length, read.page().size(), read.page().end(), Base64.getEncoder().encodeToString(bytes), text);
    }

    /** The bytes as text when they are whole UTF-8 (a page may cut a character in two: then there is no text, and the base64 is the answer); null otherwise. */
    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notText) {
            LOG.debug("A page of an attachment is not whole UTF-8; its base64 is the answer.", notText);
            return null;
        }
    }

    private static ToolAnnotations hints(boolean readOnly) {
        // Beginning with the same operation key or finishing the same attachment is idempotent.
        return ToolAnnotations.builder().readOnlyHint(readOnly).idempotentHint(true).destructiveHint(false).openWorldHint(false).build();
    }
}
