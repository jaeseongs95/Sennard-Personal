// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.server.context.ChildSessionRoots;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HookModule;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.Injection;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import io.github.jaeseongs95.sennard.server.session.HookDomain;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;

/** Gives waiting messages to a session at the boundaries its host can take them, and answers a host's native wait on other sessions. */
@Component
@Order(20)
public class MessagingHookHandler implements HookDomain {
    private static final Logger LOG = LoggerFactory.getLogger(MessagingHookHandler.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();

    private final SessionMessageService messages;
    private final SessionRegistry sessions;
    private final ChildSessionRoots childRoots;
    private final PeerEnvelope envelope;
    private final MessageSource catalog;
    private final Map<HostId, HostWorkFacts> facts;
    private final WakeWaiters waiters;

    public MessagingHookHandler(SessionMessageService messages, SessionRegistry sessions, ChildSessionRoots childRoots, PeerEnvelope envelope, MessageSource catalog,
            List<HostWorkFacts> facts, WakeWaiters waiters) {
        this.waiters = waiters;
        this.messages = messages;
        this.sessions = sessions;
        this.childRoots = childRoots;
        this.envelope = envelope;
        this.catalog = catalog;
        this.facts = facts.stream().collect(Collectors.toUnmodifiableMap(HostWorkFacts::host, Function.identity()));
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        HostEvent event = call.event();
        HostContext context = event.context();
        HostWorkFacts host = facts.getOrDefault(context.hostLabel(), HostWorkFacts.NONE);
        if (!call.isForAnotherThan(HookModule.NATIVE_PEER) && event instanceof HostEvent.ToolRequested request && host.sendsNativePeerMessage(request.tool())) {
            return new HookVerdict.Refuse(catalog.getMessage("peer.send.useSennard", null, Locale.ENGLISH));
        }
        if (call.isForAnotherThan(HookModule.PEER_WAIT)) {
            return PROCEED;
        }
        String sessionHost = context.hostLabel().sessionHost();
        if (context.actor().role() == Role.CHILD && context.actor().basis() == Basis.OBSERVED) {
            return atChildBoundary(event, host, sessionHost);
        }
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

    private HookVerdict atChildBoundary(HostEvent event, HostWorkFacts host, String sessionHost) {
        boolean continueTurn = switch (event) {
            case HostEvent.SubagentEnded ended -> !ended.alreadyContinued() && host.injections().contains(Injection.TURN_END);
            case HostEvent.TurnEnded ended -> ended.continuable() && !ended.alreadyContinued()
                    && (host.restoresAtTurnStart() || host.injections().contains(Injection.TURN_END));
            default -> false;
        };
        boolean addContext = switch (event) {
            case HostEvent.SubagentStarted ignored -> host.injections().contains(Injection.TOOL_BOUNDARY) && !host.restoresAtTurnStart();
            case HostEvent.ToolFinished tool -> tool.stepBoundary() && host.injections().contains(Injection.TOOL_BOUNDARY);
            case HostEvent.ModelCallFinished model -> model.carriesContext() && host.restoresAtTurnStart();
            case HostEvent.TurnStarted ignored -> host.restoresAtTurnStart();
            case HostEvent.ModelCallStarting ignored -> host.restoresAtTurnStart();
            default -> false;
        };
        if (!continueTurn && !addContext) {
            return PROCEED;
        }
        HostContext context = event.context();
        String actorId = context.actor().agentKey();
        if (actorId == null || actorId.isBlank()) {
            return PROCEED;
        }
        // A host with a distinct child conversation uses that conversation as its actor id. Its hook needs the observed link to the root.
        Optional<String> root = context.sessionId().equals(actorId) ? childRoots.rootOf(sessionHost, context.sessionId()) : Optional.of(context.sessionId());
        Optional<String> ref = root.flatMap(sessionId -> sessions.findRef(sessionHost, sessionId));
        if (ref.isEmpty()) {
            return PROCEED;
        }
        PeerAddress address;
        try {
            address = new PeerAddress(ref.orElseThrow(), actorId);
        } catch (IllegalArgumentException invalid) {
            LOG.debug("PEER_CHILD_ADDRESS_INVALID", SafeFailure.capture(invalid));
            return PROCEED;
        }
        return give(messages.claimActor(sessionHost, root.orElseThrow(), address), continueTurn);
    }

    /**
     * ceiling: the policy's other answers (refuse a long wait, one snapshot per state) need an observed resume, and that comes from the server
     * running a host's wake CLI, which is not moved here. Until then every wait is the bounded one.
     */
    private HookVerdict peerWait(HostWorkFacts host, HostEvent.ToolRequested request) {
        return host.peerWait(request.tool()).<HookVerdict>map(wait -> context(catalog.getMessage("peer.wait.boundedGuidance", null, Locale.ENGLISH))).orElse(PROCEED);
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
            // A turn already continued gets no second continuation: a stream of messages cannot extend one turn without end.
            case HostEvent.TurnEnded ended when ended.alreadyContinued() -> {
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
