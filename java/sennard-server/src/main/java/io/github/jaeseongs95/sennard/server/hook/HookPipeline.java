// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * What a hook call is, whichever way the host delivered it (the Sennard hook client, or an MCP tool call): the label picks the adapter, the
 * adapter reads the host's JSON into a {@link HostEvent}, the domain answers with a {@link HookVerdict}, and the adapter renders it as the record the
 * host's JSON is written from. The transports differ only in how the JSON gets here and back.
 */
@Component
public class HookPipeline {
    private static final Logger LOG = LoggerFactory.getLogger(HookPipeline.class);
    private final Map<String, ServerHookAdapter> adapters;
    // ceiling: optional until the domain services implement it; without one every hook answers 503 rather than a made-up allow.
    private final ObjectProvider<HostEventHandler> handler;

    private final Messages messages;
    private final List<HookBoundary> boundaries;
    private final SessionRegistry sessions;

    public HookPipeline(List<? extends ServerHookAdapter> adapters, ObjectProvider<HostEventHandler> handler, Messages messages) {
        this(adapters, handler, messages, List.of());
    }

    public HookPipeline(List<? extends ServerHookAdapter> adapters, ObjectProvider<HostEventHandler> handler, Messages messages, List<HookBoundary> boundaries) {
        this(List.copyOf(adapters), handler, messages, boundaries, null);
    }

    @Autowired
    public HookPipeline(List<ServerHookAdapter> adapters, ObjectProvider<HostEventHandler> handler, Messages messages, List<HookBoundary> boundaries, SessionRegistry sessions) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(adapter -> adapter.host().label(), Function.identity()));
        this.handler = handler;
        this.messages = messages;
        this.boundaries = List.copyOf(boundaries);
        this.sessions = sessions;
    }

    /**
     * @param module the registration the call came through, or null
     * @param body what the host wrote for this hook
     * @throws HookRejected when the call cannot be answered; the transport turns it into its own failure. A gate (a tool-call hook that can refuse the tool, see
     *     {@link HookModule#gates}) is never left to that failure once its event is read: a host takes a status above 299 as a hook that failed and runs the tool, so the call the
     *     server refused is answered with the refusal of the tool ({@link #gateRefused}), whatever the status would have been
     */
    public HookOutput answer(String label, String event, String module, HookBody body, List<Long> hostCandidates) {
        return answer(label, event, module, body, hostCandidates, null).output();
    }

    public record Answer(HookOutput output, boolean wakeRegistered) {
    }

    public Answer answer(String label, String event, String module, HookBody body, List<Long> hostCandidates, String wakeKey) {
        ServerHookAdapter adapter = adapters.get(label);
        if (adapter == null) {
            throw new HookRejected(HookRejection.HOOK_HOST_UNKNOWN);
        }
        HostEvent read = adapter.read(event, body);
        if (read instanceof HostEvent.NativePeerResult) {
            // A replacement result cannot deliver context. Do not let consumers claim a waiting peer message here.
            String reason = messages.render(MessageRef.of("peer.result.useSennard"));
            return new Answer(adapter.respond(event, read, new HookVerdict.Refuse(reason)), false);
        }
        if (!(read instanceof HostEvent.ToolRequested) || !HookModule.gates(module)) {
            return answered(adapter, event, module, read, hostCandidates, wakeKey);
        }
        try {
            return answered(adapter, event, module, read, hostCandidates, null);
        } catch (RuntimeException refused) {
            // Boundary (hook entrance): the failure is not swallowed, it becomes the refusal of this tool call and is logged with its cause.
            String code = refusalCode(refused);
            LOG.warn("HOOK_GATE_REFUSED {}", code, SafeFailure.capture(refused));
            return new Answer(adapter.respond(event, read, new HookVerdict.Refuse(gateRefused(code))), false);
        }
    }

    /** What the refusal of a gate says: the code of what the server refused the call with (the {@code reason} a refused hook's body carries), and what the user does. */
    private String gateRefused(String code) {
        return messages.render(MessageRef.of("hooks.gateRefused", "reason", code));
    }

    private static String refusalCode(RuntimeException refused) {
        if (refused instanceof HookRejected rejected) {
            return rejected.reason().equals(rejected.rejection().name()) ? rejected.reason() : rejected.rejection().name() + ":" + rejected.reason();
        }
        if (refused instanceof SennardException failure) {
            // the code's own name, as SessionEventHandler gives it as the reason of the refusals it translates
            return failure.code().name();
        }
        // a failure nobody expected: the hook path's own code for a domain that could not answer (the HTTP boundary answers it 500)
        return HookRejection.HOOK_HANDLER_FAILED.name();
    }

    private Answer answered(ServerHookAdapter adapter, String event, String module, HostEvent read, List<Long> hostCandidates, String wakeKey) {
        HostEvent routed;
        try {
            routed = prepareEvent(read);
        } catch (StoreException failure) {
            throw storeRejected(failure);
        }
        for (HookBoundary boundary : boundaries) {
            Optional<HookVerdict> intercepted = boundary.before(routed);
            if (intercepted.isPresent()) {
                return new Answer(adapter.respond(event, routed, intercepted.orElseThrow()), false);
            }
        }
        HostEventHandler available = handler.getIfAvailable();
        if (available == null) {
            throw new HookRejected(HookRejection.HOOK_HANDLER_UNAVAILABLE);
        }
        HookVerdict verdict = available.handle(new HostEventHandler.HookCall(routed, module, hostCandidates));
        if (!verdict.appliesTo(routed)) {
            throw new HookRejected(HookRejection.HOOK_VERDICT_NOT_APPLICABLE);
        }
        boundaries.forEach(boundary -> boundary.after(routed, verdict));
        HookOutput output = adapter.respond(event, routed, verdict);
        try {
            boundaries.forEach(boundary -> boundary.afterResponse(routed, verdict, output));
        } catch (StoreException failure) {
            throw storeRejected(failure);
        }
        boolean registered = false;
        for (HookBoundary boundary : boundaries) {
            registered |= boundary.bootstrap(routed, verdict, wakeKey);
        }
        return new Answer(output, registered);
    }

    private HostEvent prepareEvent(HostEvent read) {
        HostEvent prepared = managedActor(read);
        for (HookBoundary boundary : boundaries) {
            prepared = boundary.prepareEvent(prepared);
        }
        return prepared;
    }

    private static HookRejected storeRejected(StoreException failure) {
        return new HookRejected(HookRejection.HOOK_HANDLER_FAILED, failure.code().name(), failure);
    }

    /** Independent native threads arrive in the same shape as a root. Route them before any domain sees the event. */
    private HostEvent managedActor(HostEvent read) {
        if (sessions == null) {
            return read;
        }
        HostContext context = read.context();
        String host = context.hostLabel().sessionHost();
        String conversationId = context.sessionId();
        String reportedAgent = context.actor().agentKey();
        if (reportedAgent != null && !conversationId.equals(reportedAgent) && sessions.managedRootOf(host, reportedAgent).isPresent()) {
            throw new HookRejected(HookRejection.HOOK_MANAGED_IDENTITY_INVALID);
        }
        var binding = sessions.managedRootOf(host, conversationId);
        if (binding.isEmpty()) {
            return read;
        }
        // A separate native process can report the shape of a root before its first MCP call classifies it.
        // Only the worker's exact, still-live registration identifies that conversation as a managed child.
        HostContext.SessionActor actor = context.actor();
        boolean rootShape = actor.rootEstablished()
                || (actor.role() == HostContext.Role.ROOT && actor.basis() == HostContext.Basis.PROVISIONAL);
        if (!rootShape || reportedAgent != null || actor.lead() != HostContext.Lead.MAIN
                || sessions.liveManagedRootSession(host, binding.orElseThrow()).isEmpty()) {
            throw new HookRejected(HookRejection.HOOK_MANAGED_IDENTITY_INVALID);
        }
        return read.withContext(new HostContext(context.hostLabel(), conversationId, HostContext.SessionActor.managedChild(conversationId),
                context.workspace(), context.transcriptPath(), context.model(), context.effort(), context.turnId()));
    }
}
