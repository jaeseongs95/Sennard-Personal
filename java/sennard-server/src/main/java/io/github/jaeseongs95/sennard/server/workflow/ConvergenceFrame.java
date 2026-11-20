// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A convergence frame ({@code convergence-frame.v1.schema.json}): the workspace, the artifacts that decide pass or fail (control) and the ones that change
 * (target), and the operating limits of the attempts. A change to anything but the targets' content needs an independent frame review.
 */
public record ConvergenceFrame(String schemaVersion, Workspace workspace, List<ControlArtifact> controlArtifacts, List<TargetArtifact> targetArtifacts, OperationalSettings operationalSettings) {

    /** The workspace the work happens in: its name and where it is. */
    public record Workspace(String workspaceId, String locator) {
    }

    /** An artifact that decides whether the work passed. */
    public record ControlArtifact(String artifactId, ControlRole role, String locator, String digest) {
    }

    /** An artifact the work changes or produces. */
    public record TargetArtifact(String artifactId, TargetRole role, String locator, String digest) {
    }

    /** The limits of the attempts of a root; {@code maxAttemptsPerEpoch} and {@code maxEpochs} are the {@link ConvergenceLimits} the root was opened with and never change. */
    public record OperationalSettings(int maxAttemptsPerEpoch, int maxEpochs, long leaseTtlSeconds) {
    }

    /** This frame with the given limits in place of the ones it carries; nothing else of it changes. */
    public ConvergenceFrame withLimits(ConvergenceLimits limits) {
        return new ConvergenceFrame(schemaVersion, workspace, controlArtifacts, targetArtifacts,
                new OperationalSettings(limits.maxAttemptsPerEpoch(), limits.maxEpochs(), operationalSettings.leaseTtlSeconds()));
    }

    /** The part a control artifact plays; the frame contract spells the wire text. */
    public enum ControlRole {
        VALIDATOR("validator"),
        RUBRIC("rubric"),
        ORACLE("oracle"),
        AGGREGATION("aggregation"),
        PASS_CONDITION("pass-condition"),
        EVALUATION_INPUT("evaluation-input");

        private final String wire;

        ControlRole(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The part a target artifact plays; the frame contract spells the wire text. */
    public enum TargetRole {
        TARGET("target"),
        CANDIDATE("candidate");

        private final String wire;

        TargetRole(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
