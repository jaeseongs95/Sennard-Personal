// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Identity;
import tools.jackson.databind.JsonNode;

/**
 * The answers to a use case's {@link ReadPlan}, as the entry read them before the use case's transaction opened. The use case judges on these and never opens a file: what it asks
 * for here is what the plan asked for, and a request that was not asked for is the use case's own mistake ({@link IllegalStateException}), not an answer.
 */
final class FileReads {
    /** The reads of a use case that has none. */
    static final FileReads NONE = new FileReads(-1, Map.of());

    private final long revision;
    private final Map<ReadRequest, FileRead<?>> answers;

    FileReads(long revision, Map<ReadRequest, FileRead<?>> answers) {
        this.revision = revision;
        this.answers = Map.copyOf(answers);
    }

    /** The revision the plan was made for. */
    long revision() {
        return revision;
    }

    /** The JSON object of the file of the attachment, whose bytes have the digest; the refusal of the read when it had one. */
    JsonNode document(AttachmentFileRef file, String expectedDigest) {
        return take(new ReadRequest.Document(file, expectedDigest), JsonNode.class);
    }

    /** Whether the file of the attachment is there with the sha256 and its stored copy is read whole, as the entry found it. */
    AttachmentContents.Standing stored(AttachmentFileRef file, String sha256) {
        return take(new ReadRequest.Stored(file, sha256), AttachmentContents.Standing.class);
    }

    /** The schema the plan froze, as the file now says it; the refusal of the read when it had one. */
    ContractValidator.BoundSchema schema(WorkflowPlan.SchemaReference reference, String label) {
        return take(new ReadRequest.DeclaredSchema(reference, label), ContractValidator.BoundSchema.class);
    }

    /** What the check over the bundle found. */
    ReadRequest.CheckAnswer check(ReadRequest.BundleCheck request) {
        return take(request, ReadRequest.CheckAnswer.class);
    }

    /** The identity of this machine, as the entry read it. */
    String hostId() {
        return take(new ReadRequest.HostId(), String.class);
    }

    /**
     * Where the root's write surface physically lives. A location nobody read is one of a root that was not active, or was not the same, when the plan was made: the roots changed
     * between the plan and this transaction, and the call is refused as stale.
     */
    Identity identity(String workspaceLocator, List<String> entries, boolean physicalOnly) {
        ReadRequest request = new ReadRequest.RootIdentity(workspaceLocator, entries, physicalOnly);
        if (!answers.containsKey(request)) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchConvergenceRoot"), details("reason", "ROOTS_CHANGED_AFTER_READ", "workspace", workspaceLocator));
        }
        return take(request, Identity.class);
    }

    private <T> T take(ReadRequest request, Class<T> type) {
        FileRead<?> answer = answers.get(request);
        if (answer == null) {
            throw new IllegalStateException("The use case judged a read it did not ask the entry for: " + request);
        }
        return type.cast(answer.get());
    }
}
