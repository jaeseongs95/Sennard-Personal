// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.List;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.service.store.RootState;

/** Documents of the root kinds written from code, for the tests that check them against the contract files. */
final class RootSamples {
    static final Instant CREATED = Instant.parse("2026-11-11T09:00:00Z");

    private RootSamples() {
    }

    static String digest(String seed) {
        return "sha256:" + Digests.sha256Hex(seed);
    }

    /** A task envelope with every field the schema lists, the optional artifact contract included when asked. */
    static TaskEnvelope task(String taskId, boolean withArtifactContract) {
        List<TaskEnvelope.ArtifactContract> contracts = withArtifactContract
                ? List.of(new TaskEnvelope.ArtifactContract("independent-audit", List.of(new TaskEnvelope.ProducedArtifact("gate-verdict", "schema-1", digest("verdict")))))
                : null;
        return new TaskEnvelope("1.0.0", taskId, "Make the build pass", new TaskEnvelope.Scope(List.of("src/a.java", "src/b.java"), List.of("docs")), List.of("The build passes"), RiskLevel.HIGH,
                List.of(new TaskEnvelope.WorkUnit("w1", "Fix a", List.of(), List.of("src/a.java")), new TaskEnvelope.WorkUnit("w2", "Fix b", List.of("w1"), List.of("src/b.java"))), contracts,
                List.of("independent-audit"), List.of("no network"), new TaskEnvelope.Authorization(List.of("edit"), List.of("deploy"), List.of("delete")),
                new TaskEnvelope.Decision(TaskEnvelope.Complexity.COMPLEX, true), new TaskEnvelope.Orchestration(true, true));
    }

    static ConvergenceFrame frame(String locator) {
        return new ConvergenceFrame("1.0.0", new ConvergenceFrame.Workspace("w", locator),
                List.of(new ConvergenceFrame.ControlArtifact("criteria", ConvergenceFrame.ControlRole.PASS_CONDITION, "taskEnvelope.acceptanceCriteria", digest("criteria")),
                        new ConvergenceFrame.ControlArtifact("oracle", ConvergenceFrame.ControlRole.EVALUATION_INPUT, "oracle.json", digest("oracle"))),
                List.of(new ConvergenceFrame.TargetArtifact("target-1", ConvergenceFrame.TargetRole.TARGET, "src/a.java", digest("a")),
                        new ConvergenceFrame.TargetArtifact("target-2", ConvergenceFrame.TargetRole.CANDIDATE, "src/b.java", digest("b"))),
                new ConvergenceFrame.OperationalSettings(3, 2, 300));
    }

    /** A root as the service writes it at opening, with the digests of its own inputs. */
    static ConvergenceRoot root(DocumentDigests digests, String rootId, String parentRootId, Instant at) {
        TaskEnvelope task = task("task-1", true);
        ConvergenceFrame frame = frame("/w");
        ConvergenceDigests inputs = digests.convergence(task, frame);
        return new ConvergenceRoot("1.0.0", rootId, parentRootId, 0, RootState.OPEN, 1, task, frame, inputs.taskDigest(), inputs.frameDigest(), inputs.workspaceDigest(), inputs.controlDigest(),
                inputs.targetDigest(), inputs.operationalDigest(), List.of(), at, at, null, null);
    }
}
