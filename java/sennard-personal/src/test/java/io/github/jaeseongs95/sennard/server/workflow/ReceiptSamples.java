// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.json.JsonMapper;

/** Receipts and stage results written from code over the repository's real registry and contract files, for the tests that check them. */
final class ReceiptSamples {
    static final JsonMapper MAPPER = PlanSamples.MAPPER;
    static final String DIGEST = "sha256:" + Digests.sha256Hex("artifact");
    static final Instant OBSERVED = PlanSamples.OBSERVED;
    /** The attachment the sample's artifact and evidence name (a UUID, as the contract says; these samples are judged by schema and by shape, no attachment is read). */
    static final String ATTACHMENT = "66666666-6666-4666-8666-666666666666";

    private ReceiptSamples() {
    }

    /** A plan the server would sign for a direct task with one stage, over the repository's real registry. */
    static WorkflowPlan plan() throws IOException {
        PlanSamples.Wiring wiring = PlanSamples.wire();
        return wiring.planner().build(PlanSamples.task("task-1", RiskLevel.LOW, List.of("minimal-implementation"), true), wiring.registry().read(), null, PlanSamples.bootstrap("task-1"), null,
                PlanSamples.KEY);
    }

    /** The receipt of a run that has just started over that plan, with the skills it declared. */
    static WorkflowReceipt started() throws IOException {
        SkillDeclaration.Recorded declaration = new SkillDeclaration.Recorded(List.of(new SkillDeclaration("plugin:minimal-implementation", "the smallest change")), null, null);
        return WorkflowReceipt.started("run-task-1-1", plan().withState(RunState.RUNNING).withRunningPointers(), declaration);
    }

    /** What a caller sends for a stage that passed: an output, an artifact and an evidence item, and nothing the server owns. */
    static StageResult callerResult(String stageId, RunState state) {
        ProviderResult output = new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.createObjectNode().put("summary", "done").put("score", 1.5), List.of(new ProviderArtifact("artifact-1", "artifact.v1",
                ATTACHMENT, DIGEST, DIGEST, true)), null);
        return new StageResult("1.0.0", "run-task-1-1", stageId, 0, state, output, List.of(new Evidence("artifact-1", EvidenceKind.FILE, ATTACHMENT, DIGEST, true, "the file")), List.of("a finding"),
                List.of(), null, null, null, null, null);
    }

    /** The same result as the server records it: with the model the host observed, what the server hashed, and the caller as observed. */
    static StageResult recordedResult(String stageId) {
        ExecutionContext context = new ExecutionContext("1.0.0", "a-model-nobody-listed", null, "high", List.of("MODEL_NOT_IN_CATALOG"), ExecutionSource.RUNTIME, OBSERVED, "observation-0123456789abcdef",
                "task-1", "run-task-1-1", stageId, 0L, "codex:actor-1", null);
        return callerResult(stageId, RunState.PASSED).withExecutionContext(context)
                .withArtifactChecks(List.of(new ArtifactCheckEntry(CheckSource.ARTIFACT, "artifact-1", ATTACHMENT, ArtifactCheck.SHA256_MATCHED),
                        new ArtifactCheckEntry(CheckSource.EVIDENCE, "artifact-1", ATTACHMENT, ArtifactCheck.NOT_CHECKED)))
                .withRecordedBy(new ObservedCaller("codex:actor-1", List.of("codex:root"), Provenance.HOST_OBSERVED));
    }
}
