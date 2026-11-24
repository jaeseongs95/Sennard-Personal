// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.prompt.RootSessions;
import io.github.jaeseongs95.sennard.server.session.HookDomain;

/**
 * JEV's advice on the skills for the user's request, without the agent having to ask for it. At a prompt of a main session the server starts the classification itself
 * ({@link SkillClassificationService#startAdvice}: when JEV can be asked, on its own thread, so the hook does not wait), and at the next point where the host takes context (a model call
 * that finished, a finished tool whose context reaches the next call: the same points as the session summary's question in {@code RecapNudgeHook}) it gives the result once, as a few
 * lines: the skills JEV judged needed, with its confidence, at most {@link SkillClassificationService#MAX_ADVISED}. Neither the request nor the inventory comes back. A turn end carries
 * no context, so it gives nothing and takes nothing. The advice is for reference only; the agent makes the selection. When JEV is off, cannot be asked or did not advise, there is
 * no context from here and nothing to read in the log (a call that was made and failed is logged where it is met).
 */
@Component
@Order(35)
public class JevAdviceHook implements HookDomain {
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();
    private static final String HEADER = "hooks.jevAdvice.header";
    private static final String SKILL = "hooks.jevAdvice.skill";

    private final SkillClassificationService classification;
    private final RootSessions roots;
    private final Messages messages;

    public JevAdviceHook(SkillClassificationService classification, RootSessions roots, Messages messages) {
        this.classification = classification;
        this.roots = roots;
        this.messages = messages;
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        if (call.module() != null) {
            return PROCEED;
        }
        return switch (call.event()) {
            // The advice judges the user's request; a prompt the host wrote itself is not one.
            case HostEvent.UserPromptSubmitted submitted when submitted.origin() == HostEvent.PromptOrigin.USER -> {
                roots.of(submitted.context()).ifPresent(session -> classification.startAdvice(session.host(), session.sessionId(), submitted.prompt()));
                yield PROCEED;
            }
            case HostEvent.ModelCallFinished finished when finished.carriesContext() -> given(finished.context());
            case HostEvent.ToolFinished finished when finished.stepBoundary() -> given(finished.context());
            default -> PROCEED;
        };
    }

    private HookVerdict given(HostContext context) {
        return roots.of(context).<HookVerdict>map(session -> {
            List<SkillClassificationService.Advised> advised = classification.takeAdvice(session.host(), session.sessionId());
            if (advised.isEmpty()) {
                return PROCEED;
            }
            List<MessageRef> lines = new ArrayList<>();
            lines.add(MessageRef.of(HEADER));
            // The score is formatted here, not by the message pattern, so that it reads the same in every locale.
            advised.forEach(skill -> lines.add(MessageRef.of(SKILL, "skill", skill.skillId(), "score", String.format(Locale.ROOT, "%.2f", skill.score()))));
            return new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(messages.renderLines(lines), false)));
        }).orElse(PROCEED);
    }
}
