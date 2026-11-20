// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.jaeseongs95.sennard.server.hook.HookOutput;

/**
 * What a hook of Claude Code or Codex prints: nothing to say ({@code {}}), text for the model or a refusal of a tool in {@code hookSpecificOutput}, or a
 * {@code decision} that blocks a prompt or a turn's end with its {@code reason}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"hookSpecificOutput", "decision", "reason"})
public record SessionIdOutput(Specific hookSpecificOutput, String decision, String reason) implements HookOutput {
    /** Says nothing: the host goes on. */
    static final SessionIdOutput NOTHING = new SessionIdOutput(null, null, null);

    /** Text the model reads, at the event the hook is registered under. */
    static SessionIdOutput additionalContext(String eventName, String text) {
        return new SessionIdOutput(new Specific(eventName, text, null, null), null, null);
    }

    /** The tool does not run. */
    static SessionIdOutput denyTool(String reason) {
        return new SessionIdOutput(new Specific("PreToolUse", null, "deny", reason), null, null);
    }

    /** The prompt is not taken, or the turn does not end. */
    static SessionIdOutput block(String reason) {
        return new SessionIdOutput(null, "block", reason);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"hookEventName", "additionalContext", "permissionDecision", "permissionDecisionReason"})
    public record Specific(String hookEventName, String additionalContext, String permissionDecision, String permissionDecisionReason) {
    }
}
