// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * One hook call in the server's own words (design A.2, 3). Each adapter reads its host's wire format into one of these; everything past
 * the adapter sees only this type and handles it with an exhaustive {@code switch}, so a vendor's event name never reaches common code.
 */
public sealed interface HostEvent {
    HostContext context();

    default HostEvent withContext(HostContext replacement) {
        return switch (this) {
            case SessionStarted event -> new SessionStarted(replacement, event.kind(), event.restoresContext());
            case SessionEnded event -> new SessionEnded(replacement, event.reason());
            case UserPromptSubmitted event -> new UserPromptSubmitted(replacement, event.prompt(), event.origin(), event.restoresContext());
            case TurnStarted event -> new TurnStarted(replacement, event.provisionalInput(), event.restoresContext());
            case ModelCallStarting event -> new ModelCallStarting(replacement);
            case ModelCallFinished event -> new ModelCallFinished(replacement, event.carriesContext());
            case ToolRequested event -> new ToolRequested(replacement, event.tool());
            case ToolFinished event -> new ToolFinished(replacement, event.tool(), event.resultTail(), event.reportedFailure(), event.stepBoundary());
            case TurnEnded event -> new TurnEnded(replacement, event.alreadyContinued(), event.continuable());
            case SubagentStarted event -> new SubagentStarted(replacement);
            case SubagentEnded event -> new SubagentEnded(replacement, event.alreadyContinued());
            case CompactionStarting event -> new CompactionStarting(replacement);
            case CompactionFinished event -> new CompactionFinished(replacement, event.turnId(), event.successful());
            case ModelSwitched event -> new ModelSwitched(replacement);
        };
    }

    /** The session began, or a compaction, resume, clear or fork restarted it. */
    record SessionStarted(HostContext context, StartKind kind, boolean restoresContext) implements HostEvent {
        public SessionStarted(HostContext context, StartKind kind) {
            this(context, kind, kind == StartKind.STARTUP || kind == StartKind.RESUME || kind == StartKind.COMPACT);
        }
    }

    /** The host is ending the session. */
    record SessionEnded(HostContext context, String reason) implements HostEvent {
    }

    /**
     * Who wrote the text of a prompt, as the host says it in the hook's own input. A host that does not say is {@link #USER}: it is the host's fact, never read from the text, which a user can
     * paste.
     */
    enum PromptOrigin {
        /** The person (or a program acting for them, such as an SDK client) typed it: it is a new request. */
        USER,
        /** The host made it, such as a notice that a background task finished: it continues the request already served and is not a new one. */
        HOST
    }

    /**
     * A prompt opens a turn.
     *
     * @param origin who wrote it; only a {@link PromptOrigin#USER} prompt is a request of the user ({@link #startsRequest})
     */
    record UserPromptSubmitted(HostContext context, String prompt, PromptOrigin origin, boolean restoresContext) implements HostEvent {
        public UserPromptSubmitted(HostContext context, String prompt) {
            this(context, prompt, PromptOrigin.USER, false);
        }

        public UserPromptSubmitted(HostContext context, String prompt, PromptOrigin origin) {
            this(context, prompt, origin, false);
        }
    }

    /** A turn began. Any text is provisional until the raw conversation's actor is known. */
    record TurnStarted(HostContext context, String provisionalInput, boolean restoresContext) implements HostEvent {
        public TurnStarted(HostContext context, String provisionalInput) {
            this(context, provisionalInput, false);
        }

        public TurnStarted(HostContext context) {
            this(context, null);
        }

        @Override
        public String toString() {
            return "TurnStarted[context=" + context + "]";
        }
    }

    default boolean startsTurn() {
        return this instanceof UserPromptSubmitted || this instanceof TurnStarted;
    }

    /** Text reported at a turn-start boundary, or null when the hook has no input text. */
    default String turnInputText() {
        return switch (this) {
            case UserPromptSubmitted input -> input.prompt();
            case TurnStarted started -> started.provisionalInput();
            default -> null;
        };
    }

    /** A request starts at any prompt boundary, including host notifications. */
    default boolean startsRequest() {
        return startsTurn();
    }

    /** Whether this boundary calls for restoring the current full context. */
    default boolean restoresContext() {
        return false;
    }

    /** The model is about to be called again inside a turn, on a host that reports each model call. */
    record ModelCallStarting(HostContext context) implements HostEvent {
    }

    /**
     * A model call of a turn finished.
     *
     * @param carriesContext whether context returned here reaches the next model call. A host whose model-request hook has no place for text (Codex's {@code PostModelRequest}) says
     *     false, and a domain that has context to give waits for a boundary that takes it instead of asking for one the host cannot deliver (which the adapter refuses as a 500)
     */
    record ModelCallFinished(HostContext context, boolean carriesContext) implements HostEvent {
    }

    /** A tool is about to run; the answer can refuse it. */
    record ToolRequested(HostContext context, ToolCall tool) implements HostEvent {
    }

    /**
     * A tool finished.
     *
     * @param resultTail the end of what the tool returned, or null when it returned nothing
     * @param reportedFailure whether the host says the call failed; a host that does not report it says false
     * @param stepBoundary whether context returned here reaches the next model call
     */
    record ToolFinished(HostContext context, ToolCall tool, String resultTail, boolean reportedFailure, boolean stepBoundary) implements HostEvent {
    }

    /**
     * The turn ended.
     *
     * @param alreadyContinued the host continued this turn once already; a second continuation could loop with no host limit
     * @param continuable whether the host would take a continuation at this turn end. False for a turn end the host closes for good (canceled by the user, failed, past the host's own limit):
     *     whatever a domain asks, the host does not continue it, so a domain that keeps a one-time question must not spend it here. A host that cannot tell says true. It is the host's fact
     *     carried in the event, so that no domain has to know which host it is
     */
    record TurnEnded(HostContext context, boolean alreadyContinued, boolean continuable) implements HostEvent {
    }

    record SubagentStarted(HostContext context) implements HostEvent {
    }

    /** @param alreadyContinued the host reports that its turn-end hook already continued this child; the same meaning as on {@link TurnEnded} */
    record SubagentEnded(HostContext context, boolean alreadyContinued) implements HostEvent {
    }

    record CompactionStarting(HostContext context) implements HostEvent {
    }

    /** @param turnId the host's turn identifier, or null */
    record CompactionFinished(HostContext context, String turnId, boolean successful) implements HostEvent {
    }

    record ModelSwitched(HostContext context) implements HostEvent {
    }

    enum StartKind {
        STARTUP, RESUME, CLEAR, COMPACT, FORK, OTHER
    }
}
