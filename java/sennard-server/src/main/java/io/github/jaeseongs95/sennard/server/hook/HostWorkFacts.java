// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one host's tool calls mean for the continuity and messaging domains: which of its tools change files, how it loads a skill, where its
 * own notes are, and where a message can be injected. Vendor knowledge lives in the implementations; a host without one is watched by
 * nothing here and every default below is the answer for it.
 */
public interface HostWorkFacts {
    /** What a hook of a host nobody described is answered with: every default of this interface. */
    HostWorkFacts NONE = () -> HostId.UNDESCRIBED;

    /** The host this describes. */
    HostId host();

    /**
     * @param paths the paths the tool names, as it names them
     * @param incomplete the call also writes somewhere it does not name
     * @param shellRooted the normalized paths of the POSIX shell's own root on Windows ({@code /tmp/x}) the call writes: not a place a file can be looked
     *            up at, so each counts as an unread write unless a declared exception path covers it
     * @param unreadFolders where the call was when it wrote something it could not read: the folders a shell followed to (absolute, or relative to the hook's working directory,
     *            {@code ""} being that directory); empty for a call that does not run in a shell
     * @param unreadUnplaced some unread write happened where the folder is not known; no workflow then covers it
     * @param opaqueExecutionOnly the only unread effect is code execution the observation does not inspect, with no observed file edit; a workflow decides whether that execution is allowed
     */
    record EditTargets(List<String> paths, boolean incomplete, List<String> shellRooted, List<String> unreadFolders, boolean unreadUnplaced, boolean opaqueExecutionOnly) {
        public EditTargets {
            if (opaqueExecutionOnly && (!incomplete || !paths.isEmpty() || !shellRooted.isEmpty())) {
                throw new IllegalArgumentException("Opaque execution cannot name an observed write");
            }
        }

        /** Existing file-edit observations are never an opaque execution. */
        public EditTargets(List<String> paths, boolean incomplete, List<String> shellRooted, List<String> unreadFolders, boolean unreadUnplaced) {
            this(paths, incomplete, shellRooted, unreadFolders, unreadUnplaced, false);
        }

        /** A call that names its files and writes nowhere else; {@code incomplete} says it writes somewhere it does not name, at a place that is not known. */
        public EditTargets(List<String> paths, boolean incomplete) {
            this(paths, incomplete, List.of(), List.of(), incomplete, false);
        }
    }

    /** A boundary at which a waiting message can be given to the model. */
    enum Injection {
        TOOL_BOUNDARY, TURN_END
    }

    /** One session a native wait is for, in the host's own naming. */
    record PeerTarget(String host, String sessionId) {
    }

    /**
     * A native wait on other sessions.
     *
     * @param queryRevision changes when the same targets are asked about from a different point
     */
    record PeerWait(List<PeerTarget> targets, Duration timeout, String queryRevision) {
    }

    /**
     * What this host's model calls a Sennard MCP tool by. It is a fact of the host's naming, so that a sentence that tells the model to call a Sennard tool can say the name the
     * model has to use; which tool, and the sentence, are the caller's.
     *
     * @param name the tool's name in the host's call, or, when {@code wrapper} is set, the tool's own name given to the wrapper
     * @param wrapper the host's tool that calls an MCP tool by the names of its server and tool, or null when the host calls the tool by {@code name} directly
     * @param server the MCP server's name as the host registers it (given to {@code wrapper}), or null without a wrapper
     * @param loader the host's tool that loads a tool the host lists by name only, so that it can be called, or null when a tool needs no loading
     * @param loaderQuery what {@code loader} is asked to load this tool, or null without a loader
     */
    record SennardToolName(String name, String wrapper, String server, String loader, String loaderQuery) {
    }

    /** The name this host's model calls the Sennard tool {@code tool} by, or empty for a host whose naming is not described. */
    default Optional<SennardToolName> sennardToolName(String tool) {
        return Optional.empty();
    }

    /**
     * The tools of this host a model needs to call before any workflow is open, besides the Sennard tools themselves: the host's tool that loads an MCP tool, and the host's skill tool
     * (skills are not forced, so the gate does not stand between the model and them). A call of an MCP tool, wrapped or not, is a Sennard tool call by its {@code sennardTool}.
     */
    default Set<String> toolsBeforeWorkflow() {
        Set<String> names = new java.util.LinkedHashSet<>();
        sennardToolName("start_workflow").ifPresent(name -> {
            if (name.loader() != null) {
                names.add(name.loader());
            }
        });
        return names;
    }

    /** Whether this tool call runs a command (a shell). */
    default boolean runsCommand(ToolCall tool) {
        return false;
    }

    /**
     * A call of one of the host's own tools that sends a message to another agent or session, as the host's facts classify it. Whether the message stays inside the sender's delegation (the
     * session that started it, and the subagents that session started) is decided by the server from these facts and what it recorded; the text of the recipient decides nothing.
     *
     * @param recipient how the call names its recipient (the id the host gave a subagent, the name it was started with, a thread), or null when the call names none or the host does not
     *            say where the tool names it (a record component is never an Optional)
     * @param toParent the call names the agent that started the sender, in the host's own notation for it
     * @param insideDelegation the host itself delivers this tool only inside the sender's delegation, whatever the recipient says
     */
    record NativeSend(String recipient, boolean toParent, boolean insideDelegation) {
        /** A send whose recipient and reach the host does not state: it is let through only for a recipient the server recorded. */
        public static NativeSend unstated() {
            return new NativeSend(null, false, false);
        }
    }

    /** Present when this tool call is a send of one of the host's native inter-agent or inter-session tools. */
    default Optional<NativeSend> nativeSend(ToolCall tool) {
        return Optional.empty();
    }

    /**
     * What a later send can call the subagents that this finished tool call started: the ids the host returns and the names it was given. Empty for a call that started none.
     *
     * @param result the end of what the tool returned, or null when it returned nothing
     */
    default Set<String> subagentAddresses(ToolCall tool, String result) {
        return Set.of();
    }

    /** Whether this call uses a host tool that executes a script with access to other tools. */
    default boolean runsToolScript(ToolCall tool) {
        return false;
    }


    /** Present when this tool call edits files; a tool that edits but whose target cannot be read gives an empty list. */
    default Optional<EditTargets> editTargets(ToolCall tool) {
        return Optional.empty();
    }

    /** The Sennard skills a finished tool call loaded. */
    default List<String> skillLoads(ToolCall tool) {
        return List.of();
    }

    /** How this host loads a skill, recorded with the skill's usage: its own skill tool, or a read of the skill's file. */
    default String skillLoadBasis() {
        return "skill-file-read";
    }

    /** What one finished tool call shows about the work of its turn. */
    enum TurnWork {
        EDIT, SKILL_READ, RUN
    }

    /** Whether a turn that changed files is checked at its end for a run of the changed thing and for a skill read. */
    default boolean checksTurnWork() {
        return false;
    }

    /**
     * Present when this finished tool call is evidence of the turn's work.
     *
     * @param failed the host's own report that the call failed
     */
    default Optional<TurnWork> turnWork(ToolCall tool, boolean failed) {
        return Optional.empty();
    }

    /** Directories of the host's own notes, which are not product files; a {@code *} segment matches one directory. */
    default List<String> noteRoots() {
        return List.of();
    }

    default Set<Injection> injections() {
        return Set.of();
    }

    /** The host gives a turn start as a message that does not stay in the conversation, so the saved state is shown again at each one. */
    default boolean restoresAtTurnStart() {
        return false;
    }

    /** The catalog key of the line that says how this host invokes a skill, for a host whose way needs saying at a context start. */
    default Optional<String> skillInvocationMessage() {
        return Optional.empty();
    }

    /** Present when this tool call is the host's wait on other sessions. */
    default Optional<PeerWait> peerWait(ToolCall tool) {
        return Optional.empty();
    }
}
