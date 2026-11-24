// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HookBoundary;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;

/** Wake recognition precedes PromptCollector, SessionObservations, advice and every other native-input domain. */
@Component
public class WakeHookBoundary implements HookBoundary {
    private static final Logger LOG = LoggerFactory.getLogger(WakeHookBoundary.class);
    private final IdleWakeService wake;
    private final SessionMessageService messages;
    private final PeerEnvelope envelope;

    public WakeHookBoundary(IdleWakeService wake, SessionMessageService messages, PeerEnvelope envelope) {
        this.wake = wake;
        this.messages = messages;
        this.envelope = envelope;
    }

    @Override
    public boolean bootstrap(HostEvent event, HookVerdict verdict, String wakeKey) {
        if (!NativeWakeProtocol.validKey(wakeKey)) {
            return false;
        }
        return switch (event) {
            case HostEvent.SessionStarted started -> wake.bootstrap(started.context(), wakeKey);
            case HostEvent.TurnEnded ended when ended.continuable() && !ended.alreadyContinued() && verdict instanceof HookVerdict.Proceed ->
                wake.registerIdle(ended.context(), wakeKey);
            default -> false;
        };
    }

    @Override
    public Optional<HookVerdict> before(HostEvent event) {
        if (!event.startsTurn()) {
            return Optional.empty();
        }
        Optional<String> nonce = IdleWakeService.markerNonce(event.turnInputText());
        if (nonce.isEmpty()) {
            wake.nativeInput(event.context());
            return Optional.empty();
        }
        List<SessionMessageService.Delivered> delivered;
        try {
            delivered = messages.claimWakeInput(event.context(), nonce.orElseThrow());
        } catch (ConflictException lost) {
            if (lost.code() != ConflictCode.NATIVE_WAKE_CLAIM_CHANGED) {
                throw lost;
            }
            // The transactional service has rolled back its body and source receipt; the reserved syntax stays intercepted.
            LOG.debug("NATIVE_WAKE_CLAIM_LOST", SafeFailure.capture(lost));
            return Optional.of(new HookVerdict.Proceed());
        }
        return Optional.of(delivered.isEmpty() ? new HookVerdict.Proceed()
                : new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(envelope.render(delivered), false))));
    }

    @Override
    public void after(HostEvent event, HookVerdict verdict) {
        wake.boundary(event, verdict);
    }
}
