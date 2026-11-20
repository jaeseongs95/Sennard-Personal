// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.jaeseongs95.sennard.server.hook.HookOutput;

/**
 * What an Antigravity hook prints: nothing to say ({@code {}}), a {@code decision} (allow, deny, or continue the turn) with its {@code reason}, or the
 * steps injected into the conversation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"decision", "reason", "injectSteps"})
public record AntigravityHookOutput(String decision, String reason, List<Step> injectSteps) implements HookOutput {
    static final AntigravityHookOutput NOTHING = new AntigravityHookOutput(null, null, null);
    /** The only allow a tool boundary accepts: {@code {}} is taken as a refusal (measured). */
    static final AntigravityHookOutput ALLOW = new AntigravityHookOutput("allow", null, null);

    static AntigravityHookOutput decision(String decision, String reason) {
        return new AntigravityHookOutput(decision, reason, null);
    }

    static AntigravityHookOutput inject(List<Step> steps) {
        return new AntigravityHookOutput(null, null, steps);
    }

    /** One injected step: a message that is not kept in the transcript, or a system message that is. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"ephemeralMessage", "systemMessage"})
    public record Step(String ephemeralMessage, SystemMessage systemMessage) {
        static Step ephemeral(String text) {
            return new Step(text, null);
        }

        static Step system(String text) {
            return new Step(null, new SystemMessage(text));
        }
    }

    public record SystemMessage(String systemMessage) {
    }
}
