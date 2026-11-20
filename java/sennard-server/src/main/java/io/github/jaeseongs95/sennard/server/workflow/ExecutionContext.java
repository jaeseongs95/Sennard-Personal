// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the host observed about the model that did a piece of work ({@code execution-context.v1.schema.json}): the model, its class and its reasoning effort as the
 * host spelled them, and what the observation is bound to. The server writes it ({@link ExecutionAssurance#observe}); a caller never supplies one.
 *
 * <p>{@code modelClass} and {@code reasoningEffort} are written as {@code null} when the host reported none, and {@code runId}, {@code stageId} and
 * {@code revision} as {@code null} where the observation is not bound to one (the bootstrap), because the server always writes those keys. The schema makes
 * {@code observationId}, {@code taskId}, {@code actorId} and {@code expiresAt} optional, so they are left out when null; the server never writes an expiry.
 */
public record ExecutionContext(String schemaVersion, String model, @JsonInclude(JsonInclude.Include.ALWAYS) String modelClass, @JsonInclude(JsonInclude.Include.ALWAYS) String reasoningEffort,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> policyNotes, ExecutionSource source, Instant observedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String observationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String taskId, @JsonInclude(JsonInclude.Include.ALWAYS) String runId, @JsonInclude(JsonInclude.Include.ALWAYS) String stageId,
        @JsonInclude(JsonInclude.Include.ALWAYS) Long revision, @JsonInclude(JsonInclude.Include.NON_NULL) String actorId, @JsonInclude(JsonInclude.Include.NON_NULL) Instant expiresAt) {
}
