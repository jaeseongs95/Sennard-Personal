// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * An artifact a provider's result names ({@code stage-result.v1.schema.json#/$defs/providerArtifact}). {@code attachmentId} is the attachment that keeps its bytes and {@code digest}
 * the sha256 the provider declares for it. {@code verified} is the provider's own declaration; the gates decide on what the server checked ({@link ArtifactCheckEntry}).
 */
public record ProviderArtifact(String artifactId, String schemaId, String attachmentId, @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonSetter(nulls = Nulls.AS_EMPTY) int seq, String digest, String targetDigest, boolean verified) {
    /** An artifact that is the first file of its attachment (seq 0), which is the only file of an attachment that has one. */
    public ProviderArtifact(String artifactId, String schemaId, String attachmentId, String digest, String targetDigest, boolean verified) {
        this(artifactId, schemaId, attachmentId, 0, digest, targetDigest, verified);
    }
}
