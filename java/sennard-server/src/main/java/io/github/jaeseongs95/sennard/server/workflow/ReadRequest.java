// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;

/**
 * One read that a use case judges on: asked for by the use case's read-only planning method, answered by the entry ({@link WorkflowFileReader}) before the transaction of the use case
 * opens, and handed to it as {@link FileReads}. A use case never opens a file inside its transaction; it takes the answer of the request it asked for. Records, so a request found
 * again in the transaction is the request that was answered. What the server reads of a stage result is an attachment, by its id; it never reads a path of the caller's computer.
 */
sealed interface ReadRequest {
    /** The JSON object a file of an attachment holds, whose bytes must have the sha256 {@code expectedDigest} ({@code sha256:} and 64 hex digits). */
    record Document(AttachmentFileRef file, String expectedDigest) implements ReadRequest {
    }

    /** Whether a file of an attachment is there with the sha256 a record was bound to and its stored copy is read whole ({@link io.github.jaeseongs95.sennard.app.attachment.AttachmentContents.Standing}). */
    record Stored(AttachmentFileRef file, String sha256) implements ReadRequest {
    }

    /** A schema file a plan froze (read from the content root and held to the digest the plan froze), with the schema compiled; {@code label} is the sentence's word for its subject. */
    record DeclaredSchema(WorkflowPlan.SchemaReference reference, String label) implements ReadRequest {
    }

    /**
     * The answer of a skill's check over a bundle of attachment files. The skill is {@code skill}, or, when {@code providerKey} is set, the stage validator the provider of that key
     * declares in the skill registry (the registry is read for it). The entry puts the files {@code files} in a folder of its own, each at the path its file name
     * says, and runs {@code command} there on the bundle file {@code bundle} (its file name is the check's {@code input}; its bytes must have {@code bundleDigest}).
     * {@code capability} is null for the CS review.
     */
    record BundleCheck(String providerKey, String skill, String command, AttachmentFileRef bundle, List<AttachmentFileRef> files, String bundleDigest, String taskDigest, String capability)
            implements ReadRequest {
    }

    /** The identity of this machine, which the process reads from its state folder the first time it is asked. */
    record HostId() implements ReadRequest {
    }

    /** Where the write surface of a root physically lives. {@code physicalOnly}: a location that cannot be read is taken as written instead of refused. */
    record RootIdentity(String workspaceLocator, List<String> entries, boolean physicalOnly) implements ReadRequest {
    }

    /** What a {@link BundleCheck} found: the skill judged and answered ({@link Checked}), the skill that would judge when the skills module is not there ({@link SkillsModuleAbsent}), or no skill at all ({@link NoValidator}). */
    sealed interface CheckAnswer {
    }

    /** The skill that judges, its answer, and the path under the bundle's folder of each file the check ran over, by the file's attachment id and number. */
    record Checked(String skill, SkillChecks.Result result, java.util.Map<AttachmentFileRef, String> paths) implements CheckAnswer {
    }

    /** The skill that judges is known, but the skills module is not there, so nothing was run. */
    record SkillsModuleAbsent(String skill) implements CheckAnswer {
    }

    /** The provider declares no stage validator, or no provider has the key. */
    record NoValidator() implements CheckAnswer {
    }
}
