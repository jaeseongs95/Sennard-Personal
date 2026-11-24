// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.context.ChildSessionRoots;
import io.github.jaeseongs95.sennard.server.hook.HookActorObserved;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter.CallerFacts;
import io.github.jaeseongs95.sennard.server.mcp.McpConfiguration;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Execution;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Provenance;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.github.jaeseongs95.sennard.service.identity.CallAdmissions;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Source;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.JudgeMode;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.Judgment;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.ManagedRoot;
import io.github.jaeseongs95.sennard.service.session.SessionKind;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds a tool call to its session. The host adapter chosen by the label says what the call's {@code _meta} tells; what is done
 * with that is the same for every host. Nothing here compares a label or reads a vendor's key, so a host with no adapter is simply a host
 * that names no session.
 */
@Component
public class HostSessionResolver implements McpSessionResolver {
    private final SessionRegistry registry;
    private final CallAdmissions admissions;
    private final JsonMapper mapper;
    private final Map<String, HostCallerAdapter> adapters;
    private final ChildSessionRoots childRoots;
    private final ApplicationEventPublisher events;

    public HostSessionResolver(SessionRegistry registry, CallAdmissions admissions, JsonMapper mapper, List<HostCallerAdapter> adapters, ChildSessionRoots childRoots,
            ApplicationEventPublisher events) {
        this.childRoots = childRoots;
        this.events = events;
        this.registry = registry;
        this.admissions = admissions;
        this.mapper = mapper;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(adapter -> adapter.host().label(), Function.identity()));
    }

    /** The adapter of the request's label, or null when this server has none. */
    HostCallerAdapter adapterOf(McpTransportContext context) {
        return context.get(McpConfiguration.HOST_LABEL) instanceof String label ? adapters.get(label) : null;
    }

    @Override
    public SessionBinding bind(String tool, Map<String, Object> arguments, McpMeta meta, McpTransportContext context, String sessionRef) {
        Map<String, Object> values = meta == null || meta.meta() == null ? Map.of() : meta.meta();
        HostCallerAdapter adapter = adapterOf(context);
        String reason = "HOST_ADAPTER_NOT_AVAILABLE";
        if (adapter != null) {
            switch (adapter.read(values)) {
                case CallerFacts.Observed observed -> {
                    return agreeWithObserved(bindObserved(adapter, observed, tool, arguments, context), sessionRef);
                }
                case CallerFacts.ClaimByCall claim -> {
                    return agreeWithObserved(bindClaimed(adapter, claim, tool), sessionRef);
                }
                case CallerFacts.Unavailable unavailable -> reason = unavailable.reason();
            }
        }
        if (sessionRef == null) {
            return new SessionBinding.Unbound(reason);
        }
        return bindNamed(sessionRef);
    }

    private static SessionBinding agreeWithObserved(SessionBinding binding, String namedRef) {
        if (namedRef != null && binding instanceof SessionBinding.Bound bound && !namedRef.equals(bound.session().sessionRef())) {
            throw new BindingException(BindingCode.BINDING_INVALID, Map.of(), SennardException.args("reasonCode", "SESSION_REF_MISMATCH"));
        }
        return binding;
    }

    private SessionBinding bindObserved(HostCallerAdapter adapter, CallerFacts.Observed observed, String tool, Map<String, Object> arguments, McpTransportContext context) {
        String host = adapter.host().sessionHost();
        Map<String, Object> received = arguments;
        if (context.get(McpConfiguration.TOOL_REQUEST) instanceof CallToolRequest request) {
            if (!tool.equals(request.name())) {
                return new SessionBinding.Unbound("HOOK_OBSERVATION_UNMATCHED");
            }
            received = request.arguments();
        }
        var keys = adapter.callKeys(observed, tool, mapper.valueToTree(received == null ? Map.of() : received));
        var managed = registry.managedRootOf(host, observed.hookSessionId());
        if (differentManagedId(host, observed.hookSessionId(), observed.hostSessionId())
                || differentManagedId(host, observed.hookSessionId(), observed.actor().agentKey())) {
            return new SessionBinding.Unbound("MANAGED_CHILD_IDENTITY_INVALID");
        }
        // A managed child runs a separate host session. Reject conflicting metadata before it is matched to the hook or cached by the host adapter.
        if (managed.isPresent() && (!observed.hookSessionId().equals(observed.hostSessionId())
                || observed.actor().role() != Role.ROOT || observed.actor().basis() != Basis.OBSERVED)) {
            return new SessionBinding.Unbound("MANAGED_CHILD_IDENTITY_INVALID");
        }
        if (keys.isEmpty()) {
            if (managed.isPresent()) {
                return bindManaged(host, observed.hookSessionId(), managed.orElseThrow(), observed.callId(), observed.execution(), null);
            }
            if (registry.isChild(host, observed.hookSessionId())) {
                return new SessionBinding.Unbound("HOST_SESSION_ACTOR_MISMATCH");
            }
            Judgment judged = registry.judge(host, observed.hostSessionId(), Source.TOOL, null, null, JudgeMode.NOTIFY);
            return new SessionBinding.Bound(new SelfSession(judged.sessionRef(), judged.generation(), host, observed.hostSessionId(), Provenance.HOST_OBSERVED,
                    observed.actor(), observed.parentAgentKey(), observed.callId(), observed.execution(), null));
        }
        var key = keys.orElseThrow();
        var claimedCall = admissions.claimConversationCall(host, observed.hookSessionId(), tool, key.caller(), key.call());
        if (claimedCall.isEmpty() || (managed.isEmpty() && registry.isChild(host, observed.hostSessionId()))) {
            return new SessionBinding.Unbound("HOOK_OBSERVATION_UNMATCHED");
        }
        var row = claimedCall.orElseThrow();

        if (managed.isPresent()) {
            return bindManaged(host, observed.hookSessionId(), managed.orElseThrow(), row.callId(), execution(row), row.transcriptPath());
        }
        if (!observed.hookSessionId().equals(observed.hostSessionId())) {
            if (observed.actor().role() != Role.CHILD
                    || !childRoots.link(host, observed.hookSessionId(), observed.parentAgentKey(), observed.hostSessionId())
                    || !adapter.matchedHook(observed)) {
                return new SessionBinding.Unbound("HOOK_OBSERVATION_UNMATCHED");
            }
            return registry.treeMembership(host, observed.hookSessionId())
                    .flatMap(root -> registry.liveMainGeneration(root.rootRef()).<SessionBinding>map(generation -> {
                        events.publishEvent(new HookActorObserved(adapter.host(), observed.hookSessionId()));
                        return new SessionBinding.Bound(new SelfSession(root.rootRef(), generation, host, root.rootSessionId(), Provenance.HOST_OBSERVED,
                                observed.actor(), observed.parentAgentKey(), row.callId(), execution(row), row.transcriptPath()));
                    })).orElseGet(() -> new SessionBinding.Unbound("SESSION_TREE_ROOT_NOT_LIVE"));
        }
        if (!adapter.matchedHook(observed)) {
            return new SessionBinding.Unbound("HOOK_OBSERVATION_UNMATCHED");
        }
        Judgment judged = registry.judge(host, observed.hostSessionId(), Source.TOOL, null, null, JudgeMode.NOTIFY);
        registry.markKind(host, observed.hostSessionId(), SessionKind.MAIN);
        if (!registry.isRegisteredMain(judged.sessionRef())) {
            return new SessionBinding.Unbound("HOST_SESSION_ACTOR_MISMATCH");
        }
        events.publishEvent(new HookActorObserved(adapter.host(), observed.hookSessionId()));
        return new SessionBinding.Bound(new SelfSession(judged.sessionRef(), judged.generation(), host, observed.hostSessionId(), Provenance.HOST_OBSERVED, observed.actor(),
                observed.parentAgentKey(), row.callId(), execution(row), row.transcriptPath()));
    }

    private SessionBinding bindManaged(String host, String physicalId, ManagedRoot binding, String callId, Execution execution, String transcriptPath) {
        return registry.liveManagedRootSession(host, binding)
                .<SessionBinding>map(rootId -> new SessionBinding.Bound(new SelfSession(binding.rootRef(), binding.rootGeneration(), host, rootId, Provenance.HOST_OBSERVED,
                        SessionActor.managedChild(physicalId), null, callId, execution, transcriptPath)))
                .orElseGet(() -> new SessionBinding.Unbound("MANAGED_CHILD_IDENTITY_INVALID"));
    }

    private boolean differentManagedId(String host, String physicalId, String claimedId) {
        return claimedId != null && !physicalId.equals(claimedId) && registry.managedRootOf(host, claimedId).isPresent();
    }

    private SessionBinding bindClaimed(HostCallerAdapter adapter, CallerFacts.ClaimByCall claim, String tool) {
        String host = adapter.host().sessionHost();
        return admissions.claim(host, claim.callId()).<SessionBinding>map(row -> {
            if (!row.tool().equals(tool)) {
                throw new BindingException(BindingCode.BINDING_INVALID, Map.of(), SennardException.args("reasonCode", "CALL_ADMISSION_TOOL_MISMATCH"));
            }
            SessionActor actor = claim.actorFromRow().apply(row.agentId());
            if (differentManagedId(host, row.hostSessionId(), actor.agentKey())) {
                return new SessionBinding.Unbound("MANAGED_CHILD_IDENTITY_INVALID");
            }
            var managed = registry.managedRootOf(host, row.hostSessionId());
            if (managed.isPresent()) {
                // The hook recorded a managed child under its own conversation ID before admitting this call.
                if (!row.hostSessionId().equals(row.agentId()) || actor.role() != Role.CHILD || actor.basis() != Basis.OBSERVED) {
                    return new SessionBinding.Unbound("MANAGED_CHILD_IDENTITY_INVALID");
                }
                return bindManaged(host, row.hostSessionId(), managed.orElseThrow(), row.callId(), execution(row), row.transcriptPath());
            }
            if (registry.isChild(host, row.hostSessionId())) {
                return new SessionBinding.Unbound("HOST_SESSION_ACTOR_MISMATCH");
            }
            Judgment judged = registry.judge(host, row.hostSessionId(), Source.TOOL, null, null, JudgeMode.NOTIFY);
            return new SessionBinding.Bound(new SelfSession(judged.sessionRef(), judged.generation(), host, row.hostSessionId(), Provenance.HOST_OBSERVED,
                    actor, null, claim.callId(), execution(row), row.transcriptPath()));
        }).orElseGet(() -> new SessionBinding.Unbound("CALL_ADMISSION_MISSING"));
    }

    /** Class D: the caller names a session it holds. Nothing observed it, so the result says so and any gate on the caller refuses it. */
    private SessionBinding bindNamed(String sessionRef) {
        if (!SessionKeys.SESSION_REF.matcher(sessionRef).matches()) {
            return new SessionBinding.Unbound("SESSION_REF_INVALID");
        }
        return registry.notifyToolByRef(sessionRef)
                .<SessionBinding>map(judged -> new SessionBinding.Bound(new SelfSession(sessionRef, judged.judgment().generation(), judged.host(), null, Provenance.UNAVAILABLE,
                        SessionActor.indeterminate("CALLER_NOT_OBSERVED"), null, null, new Execution.Unobserved("CALLER_NOT_OBSERVED"), null)))
                .orElseGet(() -> new SessionBinding.Unbound("SESSION_NOT_FOUND"));
    }

    private static Execution execution(CallAdmissions.Admission row) {
        return row.model() != null
                ? new Execution.Observed(row.model(), row.effort(), row.turnId(), Execution.Observed.Evidence.HOST_REPORT)
                : new Execution.Unobserved("HOOK_MODEL_NOT_REPORTED", Map.of(), row.effort());
    }
}
