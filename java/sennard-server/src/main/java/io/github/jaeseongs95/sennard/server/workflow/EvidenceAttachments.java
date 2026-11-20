// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.attachment.Attachment;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFile;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.Sha256Text;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.message.MessageRef;

/**
 * What the workflow's records do with the attachments they point at, in the transaction that records them (docs/contributing/attachments.md, 5; AGS3-D-0264). Every pointer of a stage
 * result (its output file, the attachment of each provider artifact and of each evidence item) and of a prior failure is an attachment id, never a path of the caller's computer: the
 * attachment must exist and be the author's, the session that makes the record, or the record fails and nothing is recorded. The sha256 each attachment has is read here from its row;
 * whether it is the digest the caller declares is judged by the gates ({@link StageGates#checkArtifacts}). Read-only: the attachments are made by the artifact tools, not here.
 */
@Component
class EvidenceAttachments {
    private final AttachmentService attachments;

    EvidenceAttachments(AttachmentService attachments) {
        this.attachments = attachments;
    }

    /**
     * The sha256 (text form) of every file the result names, by attachment id and file number, after each one was found to exist and its attachment to be the author's. The output
     * file, the provider artifacts and the evidence are looked at in that order; the first pointer that is not a file of an attachment of the author refuses the recording.
     */
    Map<AttachmentFileRef, String> resolve(StageResult result, WorkflowCall call) {
        String author = author(call);
        Map<AttachmentFileRef, String> digests = new LinkedHashMap<>();
        if (result.hasOutputFile()) {
            point(digests, result, "OUTPUT_FILE", null, new AttachmentFileRef(result.outputFile().attachmentId(), result.outputFile().seq()), author);
        }
        for (ProviderArtifact artifact : result.output().artifacts()) {
            point(digests, result, "ARTIFACT", artifact.artifactId(), new AttachmentFileRef(artifact.attachmentId(), artifact.seq()), author);
        }
        for (Evidence item : result.evidence()) {
            point(digests, result, "EVIDENCE", item.artifactId(), new AttachmentFileRef(item.attachmentId(), item.seq()), author);
        }
        return digests;
    }

    /**
     * The sha256 (text form) of an attachment a prior failure names as evidence; the server reads it from the rows itself, so new evidence is new bytes the server holds, never a new
     * string. The attachment must exist and be the author's. An attachment of one file has that file's sha256; one of several files has the sha256 of the lines
     * {@code <seq>:<sha256 of the file>}, one per file in the order of the numbers, so that a change to any file, or to the files, is a different digest.
     */
    String priorEvidenceDigest(String attachmentId, WorkflowCall call, String rootId) {
        Optional<Attachment> found = attachments.attachment(attachmentId);
        if (found.isEmpty() || !found.get().createdBy().equals(author(call))) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.priorFailureEvidenceAttachment"),
                    details("rootId", rootId, "attachmentId", attachmentId, "reason", found.isEmpty() ? "ATTACHMENT_NOT_FOUND" : "ATTACHMENT_AUTHOR_MISMATCH"));
        }
        List<AttachmentFile> files = attachments.filesOf(attachmentId);
        if (files.size() == 1) {
            return Sha256Text.of(files.getFirst().contentSha256());
        }
        StringBuilder lines = new StringBuilder();
        for (AttachmentFile file : files) {
            lines.append(file.seq()).append(':').append(file.contentSha256()).append('\n');
        }
        return Sha256Text.of(Digests.sha256Hex(lines.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** The author of what a call makes: the calling session, so the agents of a session are one author. */
    private static String author(WorkflowCall call) {
        return WorkflowCall.actorId(call.host(), call.sessionId(), null);
    }

    private void point(Map<AttachmentFileRef, String> digests, StageResult result, String source, String artifactId, AttachmentFileRef file, String author) {
        if (digests.containsKey(file)) {
            // Two items may point at the same file; it is looked at once.
            return;
        }
        Optional<AttachmentService.Described> found = attachments.describe(file.attachmentId(), file.seq());
        if (found.isEmpty()) {
            // An attachment that is not there, and an attachment that has no file of that number, are the same refusal: there is no such file of an attachment.
            throw refused(result, source, artifactId, file, "ATTACHMENT_NOT_FOUND", msg("workflow.service.evidenceAttachmentNotFound"));
        }
        if (!found.get().attachment().createdBy().equals(author)) {
            throw refused(result, source, artifactId, file, "ATTACHMENT_AUTHOR_MISMATCH", msg("workflow.service.evidenceAttachmentOtherAuthor"));
        }
        digests.put(file, Sha256Text.of(found.get().file().contentSha256()));
    }

    private static WorkflowFailure refused(StageResult result, String source, String artifactId, AttachmentFileRef file, String reason, MessageRef sentence) {
        return new WorkflowFailure(WorkflowCode.BINDING_INVALID, sentence,
                details("stageId", result.stageId(), "source", source, "artifactId", artifactId, "attachmentId", file.attachmentId(), "seq", file.seq(), "reason", reason));
    }
}
