// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * The error a provider's result, a stage result and a run's receipt carry ({@code stage-result.v1.schema.json#/$defs/error}): a public code, a sentence and the
 * details the provider gave. The details are what the provider chose to say, a JSON object of no fixed shape, so they stay a tree; the schema requires the key,
 * {@code null} when there are none. The receipt schema also lists {@code messageKey} and {@code params}, but nothing writes them: the receipt's error is the stage
 * result's error.
 */
public record RecordedError(WorkflowCode code, String message, @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode details) {
}
