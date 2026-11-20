// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * The observations of a session's task that say what a workflow instance is and which request it serves. The start of an instance, the resume of one and the hooks write and read
 * the same names, so they are kept here. Each name ends with the id of the instance it is about, so that an instance is found by its own id and no instance shares a record with another.
 */
public final class WorkflowEvents {
    /**
     * {@code workflow-kind:<KIND>:<rootId>}: the instance was opened as this kind, for the gate that finds the kind of an instance by its id ({@link WorkflowContinuity#kindOf}); an instance with no such
     * record was opened by the longer path and is a change. The workflow statistics do not read it: the kind of a run is stored with the run (V22, the column {@code workflow_runs.kind}).
     */
    private static final String KIND_PREFIX = "workflow-kind:";

    /** {@code workflow-attributed:<actorHash>:<rootId>}: the request in progress is handled by this instance, for this actor (an instance opened or resumed since the last prompt). */
    public static final String ATTRIBUTED_PREFIX = "workflow-attributed:";

    /** The user's prompt that opened the turn in progress: what an attribution has to follow to count for the turn. */
    public static final String TURN_START = "workflow-turn:start";

    /** A turn end the workflow gate continued: counted per prompt, so that the question has a limit. */
    public static final String STOP_BLOCKED = "workflow-gate:stop-blocked";

    /** Lifecycle changes that finish handling a request without granting permission to run tools. */
    public enum Transition {
        PAUSE("pause_workflow"),
        FINALIZE("finalize_workflow"),
        ABORT("abort_workflow"),
        RELEASE_ROOT("release_convergence_root");

        private final String toolName;

        Transition(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }
    }

    private WorkflowEvents() {
    }

    public static String kindEvent(WorkflowKind kind, String rootId) {
        return KIND_PREFIX + kind.name() + ":" + rootId;
    }

    public static String attributedEvent(String actorHash, String rootId) {
        return ATTRIBUTED_PREFIX + actorHash + ":" + rootId;
    }

    /** The prefix of the attributions of one actor. */
    public static String attributedPrefix(String actorHash) {
        return ATTRIBUTED_PREFIX + actorHash + ":";
    }

    public static String transitionPrefix(String actorHash, long turnStart) {
        return "workflow-transition:" + (actorHash == null ? "root" : actorHash) + ":" + turnStart + ":";
    }
}
