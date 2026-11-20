// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * One evidence item of a stage result ({@code stage-result.v1.schema.json#/$defs/evidence}). {@code verified} is the caller's declaration only.
 *
 * <p>{@code attachmentId} is the attachment that keeps the bytes the item names (docs/contributing/attachments.md, 5): the caller uploads them with the artifact tools and names the
 * attachment here, and the server never reads a path on the caller's computer. The attachment must be the author's. {@code digest} is the sha256 the caller declares for it; the server
 * compares it with the attachment's own ({@link StageGates#checkArtifacts}). Two items may name the same attachment.
 */
public record Evidence(String artifactId, EvidenceKind kind, String attachmentId, @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonSetter(nulls = Nulls.AS_EMPTY) int seq, String digest, boolean verified, String note) {
    /** An item that names the first file of its attachment (seq 0), which is the only file of an attachment that has one. */
    public Evidence(String artifactId, EvidenceKind kind, String attachmentId, String digest, boolean verified, String note) {
        this(artifactId, kind, attachmentId, 0, digest, verified, note);
    }
}
