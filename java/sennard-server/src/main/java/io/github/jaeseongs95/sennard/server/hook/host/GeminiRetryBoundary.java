// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.hook.HookBoundary;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookRetryState;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;

/** Correlates Gemini's AfterAgent retry answer with its BeforeAgent callback. */
@Component
final class GeminiRetryBoundary implements HookBoundary {
    private static final HostId HOST = GeminiHost.ID;
    private final HookRetryState retries;

    GeminiRetryBoundary(HookRetryState retries) {
        this.retries = retries;
    }

    @Override
    public HostEvent prepareEvent(HostEvent event) {
        if (!HOST.equals(event.context().hostLabel())) {
            return event;
        }
        String session = event.context().sessionId();
        return switch (event) {
            case HostEvent.SessionStarted ignored -> {
                retries.clear(HOST, session);
                yield event;
            }
            case HostEvent.SessionEnded ignored -> {
                retries.clear(HOST, session);
                yield event;
            }
            // A retry callback starts another model call inside the same user turn.
            case HostEvent.UserPromptSubmitted prompt when retries.consume(HOST, session, Digests.sha256Hex(prompt.prompt())) ->
                new HostEvent.ModelCallStarting(event.context());
            case HostEvent.TurnEnded ended when !ended.alreadyContinued() && retries.continued(HOST, session) ->
                new HostEvent.TurnEnded(event.context(), true, ended.continuable());
            default -> event;
        };
    }

    @Override
    public Optional<HookVerdict> before(HostEvent event) {
        return Optional.empty();
    }

    @Override
    public void afterResponse(HostEvent event, HookVerdict verdict, HookOutput output) {
        if (!(event instanceof HostEvent.TurnEnded ended) || !(output instanceof GeminiHookOutput answer)) {
            return;
        }
        String session = ended.context().sessionId();
        switch (verdict) {
            case HookVerdict.ContinueTurn ignored -> retries.issue(HOST, session, Digests.sha256Hex(answer.reason()));
            case HookVerdict.TerminateTurn ignored -> retries.clear(HOST, session);
            default -> { }
        }
    }
}
