// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.AttemptState;

/**
 * What a finished attempt left ({@code attempt-outcome.v1.schema.json}). {@code receiptDigest} and {@code failureFingerprint} are written as
 * {@code null} when they do not apply, because the schema requires both keys.
 */
record AttemptOutcome(String schemaVersion, String outcomeId, String rootId, long rootRevision, String leaseId, long epoch, long ordinal, String workflowRunId,
        AttemptState state, @JsonInclude(JsonInclude.Include.ALWAYS) String receiptDigest, @JsonInclude(JsonInclude.Include.ALWAYS) String failureFingerprint,
        List<String> evidenceRefs, Instant recordedAt) {
}
