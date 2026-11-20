// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * What the server found for one artifact or evidence item of a stage result when it recorded it ({@code artifactChecks[]}): which list the item is in, which
 * item, the attachment it names, and whether the attachment's sha256 is the digest the item declares.
 */
public record ArtifactCheckEntry(CheckSource source, String artifactId, String attachmentId, @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonSetter(nulls = Nulls.AS_EMPTY) int seq, ArtifactCheck check) {
    /** A check of the first file of its attachment (seq 0), which is the only file of an attachment that has one. */
    public ArtifactCheckEntry(CheckSource source, String artifactId, String attachmentId, ArtifactCheck check) {
        this(source, artifactId, attachmentId, 0, check);
    }
}
