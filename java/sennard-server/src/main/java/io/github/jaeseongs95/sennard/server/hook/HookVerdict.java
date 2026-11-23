// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;

import io.github.jaeseongs95.sennard.server.hook.HostEvent.SessionEnded;
import io.github.jaeseongs95.sennard.server.hook.HostEvent.ToolRequested;
import io.github.jaeseongs95.sennard.server.hook.HostEvent.TurnEnded;
import io.github.jaeseongs95.sennard.server.hook.HostEvent.UserPromptSubmitted;

/** The server's answer to a hook call, in the server's own words; each adapter renders it as its host's JSON. */
public sealed interface HookVerdict {
    /** Nothing to add, nothing refused. */
    record Proceed() implements HookVerdict {
    }

    /** Text for the model to see. */
    record AddContext(List<ContextBlock> blocks) implements HookVerdict {
        public AddContext {
            blocks = List.copyOf(blocks);
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("A context verdict needs at least one block.");
            }
        }
    }

    /** A tool, prompt or native peer result is refused at its delivery boundary. */
    record Refuse(String reason) implements HookVerdict {
    }

    /** The turn goes on, with this as its next instruction. */
    record ContinueTurn(String instruction) implements HookVerdict {
    }

    /** End the current turn with a reason, taking precedence over another continuation. */
    record TerminateTurn(String reason) implements HookVerdict {
    }

    /**
     * One piece of context.
     *
     * @param repeatsEachTurn the text comes back every turn, so a host that can keep it out of the transcript should
     */
    record ContextBlock(String text, boolean repeatsEachTurn) {
    }

    /** A verdict the event has no meaning for is a defect in whoever produced it, and is reported instead of being rendered as something. */
    default boolean appliesTo(HostEvent event) {
        return switch (this) {
            case Proceed ignored -> true;
            case AddContext ignored -> !(event instanceof SessionEnded);
            case Refuse ignored -> event instanceof ToolRequested || event instanceof UserPromptSubmitted || event instanceof HostEvent.NativePeerResult;
            case ContinueTurn ignored -> event instanceof TurnEnded || event instanceof HostEvent.SubagentEnded;
            case TerminateTurn ignored -> event instanceof TurnEnded || event instanceof HostEvent.SubagentEnded;
        };
    }
}
