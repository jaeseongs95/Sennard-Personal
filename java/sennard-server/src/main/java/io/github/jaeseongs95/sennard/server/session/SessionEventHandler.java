// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ErrorCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter.HookAdmission;
import io.github.jaeseongs95.sennard.service.session.SessionKind;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The server's answer to a hook: the session side first, then whatever the domains add. When the store cannot answer, for
 * instance while it is being prepared, the hook fails with 503 and the host's own failure behaviour applies; nothing here
 * makes up an allow.
 */
@Component
public class SessionEventHandler implements HostEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SessionEventHandler.class);
    /** The refusals of the session registry (sennard-core) and of the binding of a call to its session, which a hook answers with HOOK_HANDLER_FAILED and the code as the reason. */
    private static final Set<ErrorCode> IDENTITY_REFUSALS = Set.of(ConflictCode.CURRENT_GENERATION_PROTECTED, ConflictCode.GENERATION_CHANGED, ConflictCode.GENERATION_STALE,
            ConflictCode.STORE_CONFLICT, NotFoundCode.SESSION_NOT_FOUND, BindingCode.BINDING_INVALID, BindingCode.BINDING_REQUIRED);

    private final HookSessions sessions;
    private final SessionLifecycle lifecycle;
    private final Map<HostId, HostCallerAdapter> adapters;
    private final List<HookDomain> domains;

    private final Clock clock;

    public SessionEventHandler(HookSessions sessions, SessionLifecycle lifecycle, List<HostCallerAdapter> adapters, List<HookDomain> domains, Clock clock) {
        this.clock = clock;
        this.sessions = sessions;
        this.lifecycle = lifecycle;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(HostCallerAdapter::host, Function.identity()));
        this.domains = domains;
    }

    @Override
    public HookVerdict handle(HookCall call) {
        long receivedAtMs = clock.millis();
        try {
            return answer(call, receivedAtMs);
        } catch (StoreException failure) {
            // The hook is answered 503 and the host's client does not retry, so this line is the only place that says why. The code and the message are all of it: a store failure's message
            // never carries SQL text or values (StoreException), and the cause, which can, is not attached.
            LOG.warn("A hook was rejected because the store could not answer: code={} message={}", failure.code().name(), failure.getMessage());
            // The unique key refused a first creation (the session's row) that another request made at the same moment: a conflict of this call, not a store that cannot answer.
            HookRejection rejection = failure.code() == StoreCode.STORE_UNIQUE_VIOLATION ? HookRejection.HOOK_HANDLER_CONFLICT : HookRejection.HOOK_HANDLER_FAILED;
            throw new HookRejected(rejection, failure.code().name(), failure);
        } catch (SennardException failure) {
            // The same refusal of a first creation, as the application layer states it: the services of sennard-app (the messaging queue's first row of a session, the recap question of a prompt, the
            // change journal) translate the unique violation to DUPLICATE (ErrorTranslationAdvisor), while the stores of sennard-core and sennard-store-pg state it as STORE_UNIQUE_VIOLATION above.
            // Every domain of the hook path ends here alike: a call that lost the key is a conflict of this call (409), not a plain problem answer without the hook's reason.
            if (failure.code() == ConflictCode.DUPLICATE) {
                LOG.warn("A hook lost a first creation to another request: code={}", failure.code().name());
                throw new HookRejected(HookRejection.HOOK_HANDLER_CONFLICT, failure.code().name(), failure);
            }
            // What the session registry and the caller binding refuse is told to the host's launcher by its code, which it reads as the reason. Any other refusal passes as it is.
            if (IDENTITY_REFUSALS.contains(failure.code())) {
                throw new HookRejected(HookRejection.HOOK_HANDLER_FAILED, failure.code().name(), failure);
            }
            throw failure;
        }
    }

    private HookVerdict answer(HookCall call, long receivedAtMs) {
        HostEvent event = call.event();
        HostContext context = event.context();
        HostCallerAdapter adapter = adapters.get(context.hostLabel());
        String host = context.hostLabel().sessionHost();
        keepSession(event, call.hostCandidates(), host, receivedAtMs);
        if (event instanceof HostEvent.ToolRequested request && request.tool().sennardTool() != null && adapter != null) {
            return admit(adapter, host, request).orElseGet(() -> askDomains(call));
        }
        return askDomains(call);
    }

    /**
     * What a hook does to the session list. Only a main agent's hook registers a session or keeps it alive; a subagent's, and
     * one whose marker the host gave in a form that cannot be read, leave it as it is. A session the host runs as an agent chosen at launch is
     * marked so that its own tool calls do not list it either. The end of a session needs no such care: it ends a registered session and
     * creates nothing. The processes above the hook's launcher come with the session start only; a start that names some has the host decided
     * among them and the session owned by it ({@link HookSessions#started}), any other hook leaves the ownership as it is.
     */
    private void keepSession(HostEvent event, List<Long> hostCandidates, String host, long receivedAtMs) {
        HostContext context = event.context();
        if (event instanceof HostEvent.SessionEnded ended) {
            sessions.ended(host, context.sessionId(), ended.reason().isBlank() ? "session-end" : ended.reason(), receivedAtMs);
            return;
        }
        if (context.actor().role() != HostContext.Role.ROOT) {
            return;
        }
        switch (context.actor().lead()) {
            case MAIN -> {
                if (event instanceof HostEvent.SessionStarted) {
                    sessions.started(host, context.sessionId(), hostCandidates, receivedAtMs);
                } else {
                    sessions.observed(host, context.sessionId(), receivedAtMs);
                }
                sessions.kindSeen(host, context.sessionId(), SessionKind.MAIN);
            }
            case PRESET_AGENT -> sessions.kindSeen(host, context.sessionId(), SessionKind.PRESET_AGENT);
        }
    }

    private Optional<HookVerdict> admit(HostCallerAdapter adapter, String host, HostEvent.ToolRequested request) {
        switch (adapter.admission(request)) {
            case HookAdmission.None ignored -> {
                return Optional.empty();
            }
            case HookAdmission.Refused refused -> {
                return Optional.of(new HookVerdict.Refuse(refused.reason()));
            }
            case HookAdmission.Row row -> {
                HostContext context = request.context();
                lifecycle.admit(new SessionLifecycle.CallAdmission(host, row.callId(), request.tool().sennardTool(), context.sessionId(),
                        context.actor().agentKey(), context.model(), context.effort(), context.turnId(), context.transcriptPath()));
                return Optional.empty();
            }
        }
    }

    private HookVerdict askDomains(HookCall call) {
        List<HookVerdict.ContextBlock> blocks = new ArrayList<>();
        for (HookDomain domain : domains) {
            switch (domain.onEvent(call)) {
                case HookVerdict.Proceed ignored -> {
                }
                case HookVerdict.AddContext context -> blocks.addAll(context.blocks());
                case HookVerdict.Refuse refuse -> {
                    return refuse;
                }
                case HookVerdict.ContinueTurn turn -> {
                    return turn;
                }
                case HookVerdict.TerminateTurn turn -> {
                    return turn;
                }
            }
        }
        return blocks.isEmpty() ? new HookVerdict.Proceed() : new HookVerdict.AddContext(blocks);
    }
}
