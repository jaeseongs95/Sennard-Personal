// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** The attachment that holds a stage result's {@code output.output} as a JSON object when it is too large for the tool arguments, and the sha256 it must have. */
public record OutputFile(String attachmentId, @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonSetter(nulls = Nulls.AS_EMPTY) int seq, String digest) {
    /** The output is the first file of the attachment (seq 0), which is the only file of an attachment that has one. */
    public OutputFile(String attachmentId, String digest) {
        this(attachmentId, 0, digest);
    }
}
