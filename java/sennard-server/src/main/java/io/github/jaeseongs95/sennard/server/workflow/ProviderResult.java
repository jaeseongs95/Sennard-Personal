// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * What a provider (a skill) returns for a stage ({@code stage-result.v1.schema.json#/$defs/providerResult}). The envelope is fixed; {@code output} is what the
 * skill produced under the schema its own descriptor declares, so it is the one JSON tree the server does not give a shape: a JSON object, or {@code null} when
 * the result is an adapter error or the output sits in a file. Both {@code output} and {@code error} are written as {@code null} when there is none, because the
 * schema requires the keys.
 */
public record ProviderResult(String schemaVersion, OutputKind kind, @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode output, List<ProviderArtifact> artifacts,
        @JsonInclude(JsonInclude.Include.ALWAYS) RecordedError error) {

    /** True when the result carries an output of its own. */
    boolean hasOutput() {
        return output != null && !output.isNull();
    }

    /** The same result with another output: the one loaded from a stage output file. */
    ProviderResult withOutput(JsonNode other) {
        return new ProviderResult(schemaVersion, kind, other, artifacts, error);
    }

    /**
     * The value a JSON Pointer names in the result as it is written. The pointers a skill's descriptor declares (the state selector, the actor IDs) are relative
     * to the whole result, and nearly all name a place in the output, which is read where it is; a pointer to another part reads the result written out. A pointer
     * that names nothing gives a missing node.
     */
    JsonNode at(String pointer, JsonMapper mapper) {
        if (pointer == null || pointer.isEmpty()) {
            return mapper.valueToTree(this);
        }
        JsonPointer compiled = JsonPointer.compile(pointer);
        if (compiled.matchesProperty("output")) {
            return hasOutput() ? output.at(compiled.tail()) : MissingNode.getInstance();
        }
        return mapper.valueToTree(this).at(compiled);
    }
}
