// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * The result of one stage of a run ({@code stage-result.v1.schema.json}), as a caller sends it and as the run's receipt keeps it. The caller states the stage, the
 * state it reached, the provider's output, the evidence and the findings; the server adds what it owns when it records the result, and a caller never sends
 * these: {@code executionContext} (the model the host observed), {@code artifactChecks} (what the server hashed) and {@code recordedBy} (the caller as observed).
 * They are left out of the JSON while they are null. {@code error} is written as {@code null} when there is none, because the schema requires the key.
 */
public record StageResult(String schemaVersion, String runId, String stageId, long expectedRevision, RunState state, ProviderResult output, List<Evidence> evidence, List<String> findings,
        List<String> blockers, @JsonInclude(JsonInclude.Include.ALWAYS) RecordedError error, @JsonInclude(JsonInclude.Include.NON_NULL) ExecutionContext executionContext,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ArtifactCheckEntry> artifactChecks, @JsonInclude(JsonInclude.Include.NON_NULL) ObservedCaller recordedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) OutputFile outputFile) {

    /** True when the output sits in an attachment the server reads (by its id), instead of in the call. */
    boolean hasOutputFile() {
        return outputFile != null;
    }

    boolean passed() {
        return state == RunState.PASSED;
    }

    StageResult withOutput(ProviderResult other) {
        return new StageResult(schemaVersion, runId, stageId, expectedRevision, state, other, evidence, findings, blockers, error, executionContext, artifactChecks, recordedBy, outputFile);
    }

    StageResult withEvidence(List<Evidence> kept) {
        return new StageResult(schemaVersion, runId, stageId, expectedRevision, state, output, kept, findings, blockers, error, executionContext, artifactChecks, recordedBy, outputFile);
    }

    StageResult withExecutionContext(ExecutionContext context) {
        return new StageResult(schemaVersion, runId, stageId, expectedRevision, state, output, evidence, findings, blockers, error, context, artifactChecks, recordedBy, outputFile);
    }

    StageResult withArtifactChecks(List<ArtifactCheckEntry> checks) {
        return new StageResult(schemaVersion, runId, stageId, expectedRevision, state, output, evidence, findings, blockers, error, executionContext, checks, recordedBy, outputFile);
    }

    StageResult withRecordedBy(ObservedCaller caller) {
        return new StageResult(schemaVersion, runId, stageId, expectedRevision, state, output, evidence, findings, blockers, error, executionContext, artifactChecks, caller, outputFile);
    }
}
