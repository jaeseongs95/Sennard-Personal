// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.jaeseongs95.sennard.server.hook.HookOutput;

/** What a Gemini CLI hook prints: nothing to say ({@code {}}), text for the model in {@code hookSpecificOutput.additionalContext}, or {@code decision: deny} with its reason. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"hookSpecificOutput", "decision", "continue", "reason"})
public record GeminiHookOutput(Specific hookSpecificOutput, String decision, @JsonProperty("continue") Boolean continueLoop, String reason) implements HookOutput {
    static final GeminiHookOutput NOTHING = new GeminiHookOutput(null, null, null, null);

    static GeminiHookOutput additionalContext(String text) {
        return new GeminiHookOutput(new Specific(text), null, null, null);
    }

    static GeminiHookOutput deny(String reason) {
        return new GeminiHookOutput(null, "deny", null, reason);
    }

    static GeminiHookOutput terminate(String reason) {
        return new GeminiHookOutput(null, null, false, reason);
    }

    public record Specific(String additionalContext) {
    }
}
