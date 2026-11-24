// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.continuity.SubagentRoster;
import io.github.jaeseongs95.sennard.server.hook.HookModule;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFactsRegistry;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.Injection;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import io.github.jaeseongs95.sennard.server.session.HookDomain;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;

/** Gives waiting messages to a session at the boundaries its host can take them, and answers a host's native wait on other sessions. */
@Component
@Order(20)
public class MessagingHookHandler implements HookDomain {
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();

    private final SessionMessageService messages;
    private final SessionRegistry sessions;
    private final PeerEnvelope envelope;
    private final Messages texts;
    private final HostWorkFactsRegistry facts;
    private final WakeWaiters waiters;
    private final SubagentRoster roster;

    public MessagingHookHandler(SessionMessageService messages, SessionRegistry sessions, PeerEnvelope envelope, Messages texts,
            HostWorkFactsRegistry facts, WakeWaiters waiters, SubagentRoster roster) {
        this.roster = roster;
        this.waiters = waiters;
        this.messages = messages;
        this.sessions = sessions;
        this.envelope = envelope;
        this.texts = texts;
        this.facts = facts;
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        HostEvent event = call.event();
        HostContext context = event.context();
        HostWorkFacts host = facts.forHost(context.hostLabel());
        if (!call.isForAnotherThan(HookModule.NATIVE_PEER) && event instanceof HostEvent.ToolRequested request) {
            Optional<HostWorkFacts.NativeSend> send = host.nativeSend(request.tool());
            if (send.isPresent()) {
                return nativeSend(context, send.orElseThrow(), request.tool());
            }
        }
        if (call.isForAnotherThan(HookModule.PEER_WAIT)) {
            return PROCEED;
        }
        String sessionHost = context.hostLabel().sessionHost();
        if (!context.actor().rootEstablished()) {
            return PROCEED;
        }
        if (event instanceof HostEvent.ToolRequested request) {
            return peerWait(host, request);
        }
        if (event.startsTurn()) {
            // The session works from here on and takes its messages at the boundaries below; a hook held since it went idle ends.
            waiters.release(sessionHost, context.sessionId());
        }
        Optional<String> ref = sessions.findRef(sessionHost, context.sessionId());
        return ref.map(target -> atBoundary(event, host, sessionHost, target)).orElse(PROCEED);
    }

    private HookVerdict nativeSend(HostContext context, HostWorkFacts.NativeSend send, ToolCall tool) {
        return switch (roster.nativeRoute(context, send)) {
            case SAME_SESSION -> PROCEED;
            case FROM_MAIN -> new HookVerdict.Refuse(texts.render(send.recipient() == null
                    ? MessageRef.of("peer.send.refusedUnaddressed", "tool", tool.name())
                    : MessageRef.of("peer.send.refused", "tool", tool.name(), "to", MessageRef.of("peer.send.toRecipient", "recipient", send.recipient()))));
            case UNVERIFIED -> new HookVerdict.Refuse(texts.render(MessageRef.of("peer.send.sameSessionOnly")));
        };
    }

    /**
     * ceiling: the policy's other answers (refuse a long wait, one snapshot per state) need an observed resume, and that comes from the server
     * running a host's wake CLI, which is not moved here. Until then every wait is the bounded one.
     */
    private HookVerdict peerWait(HostWorkFacts host, HostEvent.ToolRequested request) {
        return host.peerWait(request.tool()).<HookVerdict>map(wait -> context(texts.render(MessageRef.of("peer.wait.boundedGuidance")))).orElse(PROCEED);
    }

    private HookVerdict atBoundary(HostEvent event, HostWorkFacts host, String sessionHost, String targetRef) {
        String sessionId = event.context().sessionId();
        Set<Injection> injections = host.injections();
        return switch (event) {
            case HostEvent ignored when event.startsTurn() && host.restoresAtTurnStart() -> claimAndGive(sessionHost, sessionId, targetRef, Boundary.TOOL, false);
            case HostEvent ignored when event.startsTurn() -> {
                messages.observeNativeInput(targetRef);
                yield PROCEED;
            }
            // The boundaries a host takes context at differ: from a model call on one host, from a finished tool on the others.
            case HostEvent.ModelCallStarting ignored when host.restoresAtTurnStart() -> claimAndGive(sessionHost, sessionId, targetRef, Boundary.TOOL, false);
            case HostEvent.ModelCallFinished ignored when host.restoresAtTurnStart() -> claimAndGive(sessionHost, sessionId, targetRef, Boundary.TOOL, false);
            case HostEvent.ToolFinished ignored when !host.restoresAtTurnStart() && injections.contains(Injection.TOOL_BOUNDARY) ->
                claimAndGive(sessionHost, sessionId, targetRef, Boundary.TOOL, false);
            // A terminal turn or a turn already continued cannot receive another continuation.
            case HostEvent.TurnEnded ended when !ended.continuable() || ended.alreadyContinued() -> {
                messages.clearDeferred(targetRef);
                yield PROCEED;
            }
            case HostEvent.TurnEnded ignored when injections.contains(Injection.TURN_END) -> claimAndGive(sessionHost, sessionId, targetRef, Boundary.TURN_END, true);
            case HostEvent.TurnEnded ignored when host.restoresAtTurnStart() -> claimAndGive(sessionHost, sessionId, targetRef, Boundary.TOOL, false);
            case HostEvent.TurnEnded ignored -> {
                messages.clearDeferred(targetRef);
                yield PROCEED;
            }
            default -> PROCEED;
        };
    }

    private HookVerdict claimAndGive(String sessionHost, String sessionId, String targetRef, Boundary boundary, boolean continueTurn) {
        return give(messages.claim(sessionHost, sessionId, targetRef, boundary), continueTurn);
    }

    private HookVerdict give(List<Delivered> delivered, boolean continueTurn) {
        if (delivered.isEmpty()) {
            return PROCEED;
        }
        String text = envelope.render(delivered);
        return continueTurn ? new HookVerdict.ContinueTurn(text) : context(text);
    }

    private static HookVerdict context(String text) {
        return new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(text, false)));
    }
}
