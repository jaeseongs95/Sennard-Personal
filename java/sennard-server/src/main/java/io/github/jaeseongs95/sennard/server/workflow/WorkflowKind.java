// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The kinds of workflow. A kind is a class and an open workflow is one instance of it: nothing here says how many instances a session or a kind may hold, and the state of an
 * instance (its root, its run, its recorded kind) is stored and found by the instance's own id, never by the session's "current" workflow.
 *
 * <p>Each constant carries everything the common code needs to know about its kind, so that the judgments (the start, the edit gate, the hook enforcement, the end) read a property
 * and never branch on a kind: the MCP tool that opens it, whether {@code skills} is required, what the kind's process allows at each of its phases, when it closes, whether the
 * JEV classification runs before it starts, and the prefix of its catalog keys.
 *
 * <p>To add a kind: (1) add one constant here; (2) if it takes fields of its own, add them to its input schema in {@link WorkflowToolSchemas}; (3) add its catalog keys
 * ({@link #REQUIRED_KEYS}) to {@code locales/en/workflow.json}, {@code locales/ko/workflow.json} and the lock, and the tool's description and input descriptions under
 * {@code tools.<toolName>.*}; (4) add the name of the kind to the admin page's workflow statistics, {@code admin.page.workflows.kind.<CONSTANT>}, in both admin catalogs (a kind
 * without one is shown by its constant's name, and {@code WorkflowStatsPageTest} fails). The tool is registered by {@link WorkflowToolConfiguration} from the constants, so nothing else changes.
 */
public enum WorkflowKind {
    /** An answer or an investigation: read only, and closed by the server when the turn of its request ends. */
    INQUIRY("start_inquiry_workflow", "workflow.kind.inquiry", false, Closing.TURN_END, false, false,
            List.of(new Phase("ANSWERING", EnumSet.of(Action.READ, Action.COMMAND), null, false))),

    /** A change of files: its process is the planning phase (read only) and the executing phase (edits of the files of its change plan). */
    CHANGE("start_workflow", "workflow.kind.change", true, Closing.FINALIZE, true, false,
            List.of(new Phase("PLANNING", EnumSet.of(Action.READ, Action.COMMAND), "submit_change_plan", false), new Phase("EXECUTING", EnumSet.of(Action.READ, Action.EDIT, Action.COMMAND), null, true))),

    /** An effect that is not an edit: a test run, a push, an install, a deployment. Commands are its work; files are not edited. */
    OPERATION("start_operation_workflow", "workflow.kind.operation", true, Closing.FINALIZE, true, true,
            List.of(new Phase("OPERATING", EnumSet.of(Action.READ, Action.COMMAND, Action.OPAQUE_EXECUTION), null, false)));

    /** The suffixes of the catalog keys every kind has under its {@link #messagePrefix()}: what the kind is for, and the acceptance criterion a kind without its own writes records. */
    public static final List<String> REQUIRED_KEYS = List.of("purpose", "acceptance");

    /** What a call of the agent does, as the process of a kind allows it. */
    public enum Action {
        /** A tool that reads or asks (files, search, the MCP tools): allowed whenever any instance is open. */
        READ,
        /** A tool that changes files. */
        EDIT,
        /** A tool that runs a command. */
        COMMAND,
        /** Code execution the hook cannot inspect, with no observed file edit; explicit editing still requires EDIT. */
        OPAQUE_EXECUTION
    }

    /** When an instance of the kind ends. */
    public enum Closing {
        /** The server closes it when the turn of the request that opened it ends. */
        TURN_END,
        /** The agent finishes it ({@code finalize_workflow}) or aborts it. */
        FINALIZE
    }

    /**
     * One phase of a kind's process: what it allows, and the tool that moves an instance to the next phase ({@code null} for the last phase).
     *
     * @param id the phase's name; for a kind whose phases are the change journal's it is the name of that {@code WorkflowPhase}
     * @param recordsStage whether the work of the phase is recorded as the result of a stage of the run: the stages an example start shows are one per such phase
     */
    public record Phase(String id, Set<Action> allows, String advancedBy, boolean recordsStage) {
        public Phase {
            allows = Set.copyOf(allows);
        }
    }

    private final String toolName;
    private final String messagePrefix;
    private final boolean skillsRequired;
    private final Closing closing;
    private final boolean jevPreStage;
    private final boolean takesTarget;
    private final List<Phase> process;

    WorkflowKind(String toolName, String messagePrefix, boolean skillsRequired, Closing closing, boolean jevPreStage, boolean takesTarget, List<Phase> process) {
        this.toolName = toolName;
        this.messagePrefix = messagePrefix;
        this.skillsRequired = skillsRequired;
        this.closing = closing;
        this.jevPreStage = jevPreStage;
        this.takesTarget = takesTarget;
        this.process = List.copyOf(process);
    }

    /** The name of the MCP tool that opens an instance of this kind. */
    public String toolName() {
        return toolName;
    }

    /** The prefix of the catalog keys of the kind ({@link #key}). */
    public String messagePrefix() {
        return messagePrefix;
    }

    /** The catalog key {@code <prefix>.<suffix>} of this kind. */
    public String key(String suffix) {
        return messagePrefix + "." + suffix;
    }

    /** Whether the start must declare {@code skills}; a start of a kind that does not require them may leave them out (declaring none is not a refusal). */
    public boolean skillsRequired() {
        return skillsRequired;
    }

    public Closing closing() {
        return closing;
    }

    /** Whether the JEV classification runs, when JEV is on, before this kind starts. */
    public boolean jevPreStage() {
        return jevPreStage;
    }

    /** Whether the start names what the work acts on (a repository, a branch, an install location). */
    public boolean takesTarget() {
        return takesTarget;
    }

    /** The phases of the kind's process in order; the first is the phase an instance starts in. */
    public List<Phase> process() {
        return process;
    }

    public Phase firstPhase() {
        return process.getFirst();
    }

    /** The phase of this name, or empty when the kind's process has none. */
    public Optional<Phase> phase(String id) {
        return process.stream().filter(phase -> phase.id().equals(id)).findFirst();
    }

    /** Whether the kind's process allows the action at some phase: for an edit, whether the kind owns files at all (and so a write surface, a plan of changes, stages). */
    public boolean allows(Action action) {
        return process.stream().anyMatch(phase -> phase.allows().contains(action));
    }

    public boolean allowsFileEdits() {
        return allows(Action.EDIT);
    }

    public boolean allowsCommands() {
        return allows(Action.COMMAND);
    }

    /** Whether the phase allows the action; a phase this kind's process does not have allows nothing. */
    public boolean allows(String phaseId, Action action) {
        return phase(phaseId).map(phase -> phase.allows().contains(action)).orElse(false);
    }

    private static final String SENTINEL_DIRECTORY = ".sennard-workflow";

    /**
     * The write-surface entry of an instance of a kind that owns no files. A root needs a place for its surface; this one is made of the instance's own name and nothing is ever written
     * there, so two instances never share a surface and no change workflow's files lie in it.
     */
    public static String sentinelEntry(WorkflowKind kind, String instanceName) {
        return java.nio.file.Path.of(SENTINEL_DIRECTORY, kind.name().toLowerCase(java.util.Locale.ROOT), instanceName).toString();
    }

    /** Whether the surface entry is the one {@link #sentinelEntry} makes. */
    public static boolean isSentinel(String entry) {
        return entry != null && entry.startsWith(SENTINEL_DIRECTORY);
    }

    /** The kind whose tool this is. */
    public static Optional<WorkflowKind> ofTool(String toolName) {
        return Arrays.stream(values()).filter(kind -> kind.toolName.equals(toolName)).findFirst();
    }

    /** The names of the tools that open an instance, one per kind. */
    public static List<String> toolNames() {
        return Arrays.stream(values()).map(WorkflowKind::toolName).toList();
    }
}
