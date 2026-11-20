// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.Objects;

/**
 * One thing a transcript holds after the user's message, in the shape every host's transcript is reduced to. The kinds are what an agent needs to know of its own earlier
 * turn: what the user said, what the agent said, which tool it called and what came back. A host's own bookkeeping entries (summaries, snapshots, a model's private
 * reasoning) are not in it.
 *
 * <p>A text is cut at {@link #TEXT_MAX_CHARACTERS} characters and marked cut ({@code truncated}); build one with {@code of}, which does the cut, or with the constructor when the text
 * is already within the size.
 */
public sealed interface TranscriptEntry {
    /** The most characters of a text an entry carries. */
    int TEXT_MAX_CHARACTERS = 2000;

    /** What the user said. */
    record UserMessage(String text, boolean truncated) implements TranscriptEntry {
        public UserMessage {
            text = CutText.within(text);
        }

        public static UserMessage of(String whole) {
            CutText cut = CutText.of(whole);
            return new UserMessage(cut.text(), cut.truncated());
        }
    }

    /** What the agent wrote in its answer. */
    record AgentMessage(String text, boolean truncated) implements TranscriptEntry {
        public AgentMessage {
            text = CutText.within(text);
        }

        public static AgentMessage of(String whole) {
            CutText cut = CutText.of(whole);
            return new AgentMessage(cut.text(), cut.truncated());
        }
    }

    /** A tool the agent called; {@code callId} is the host's identifier of the call, the one its {@link ToolResult} carries. */
    record ToolCall(String callId, String toolName) implements TranscriptEntry {
        public ToolCall {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    /** What a tool answered, for the call of that {@code callId}. */
    record ToolResult(String callId, String text, boolean truncated) implements TranscriptEntry {
        public ToolResult {
            Objects.requireNonNull(callId, "callId");
            text = CutText.within(text);
        }

        public static ToolResult of(String callId, String whole) {
            CutText cut = CutText.of(whole);
            return new ToolResult(callId, cut.text(), cut.truncated());
        }
    }
}
