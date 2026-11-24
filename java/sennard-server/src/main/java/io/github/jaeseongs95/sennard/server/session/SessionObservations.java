// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFactsRegistry;
import io.github.jaeseongs95.sennard.server.updates.PluginUpdateService;
import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a hook observes for later reading: the prompt a session received (board), the skills it loaded (usage), that it is still in use
 * (the 30-day role binding), and the once-per-version update notice. Each observation is advisory: a failed write is logged and the hook
 * answers as if it had not been observed.
 */
@Component
@Order(5)
public class SessionObservations implements HookDomain {
    private static final Logger log = LoggerFactory.getLogger(SessionObservations.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();

    private final BoardService board;
    private final SkillUsageService usage;
    private final ContextLayerService context;
    private final PluginUpdateService updates;
    private final JsonMapper mapper;
    private final HostWorkFactsRegistry facts;
    private final Clock clock;

    public SessionObservations(BoardService board, SkillUsageService usage, ContextLayerService context, PluginUpdateService updates,
            JsonMapper mapper, HostWorkFactsRegistry facts, Clock clock) {
        this.clock = clock;
        this.board = board;
        this.usage = usage;
        this.context = context;
        this.updates = updates;
        this.mapper = mapper;
        this.facts = facts;
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        HostEvent event = call.event();
        HostContext actorContext = event.context();
        if (actorContext.sessionId().isEmpty()) {
            return PROCEED;
        }
        String host = actorContext.hostLabel().sessionHost();
        boolean root = actorContext.actor().role() == Role.ROOT && actorContext.actor().basis() != Basis.UNAVAILABLE;
        advisory("session-seen", () -> context.recordSessionUse(host, actorContext.sessionId(), root && (event instanceof HostEvent.SessionStarted || event.startsTurn())));
        if (event instanceof HostEvent.ToolFinished finished && !finished.reportedFailure()) {
            advisory("skill-usage", () -> recordSkillLoads(host, finished));
        }
        // The working folder from the start on: a session that has not had a prompt yet is on the board too, and showed none (L6).
        if (event instanceof HostEvent.SessionStarted && root && actorContext.workspace() != null) {
            advisory("board", () -> board.recordInput(host, actorContext.sessionId(), actorContext.workspace(), false));
        }
        return event.startsTurn() && root ? onTurnStart(host, actorContext) : PROCEED;
    }

    private void recordSkillLoads(String host, HostEvent.ToolFinished finished) {
        HostContext actorContext = finished.context();
        HostWorkFacts hostFacts = facts.forHost(actorContext.hostLabel());
        boolean subagent = actorContext.actor().role() == Role.CHILD && actorContext.actor().basis() == Basis.OBSERVED;
        for (String skillId : hostFacts.skillLoads(finished.tool())) {
            String callId = finished.tool().callId() == null ? null : finished.tool().callId() + ":" + skillId;
            usage.record(new SkillUsageStore.Call(host, actorContext.sessionId(), skillId, subagent, hostFacts.skillLoadBasis(), callId, Instant.now(clock)));
        }
    }

    private HookVerdict onTurnStart(String host, HostContext actorContext) {
        advisory("board", () -> board.recordInput(host, actorContext.sessionId(), actorContext.workspace(), true));
        return updateNotice();
    }

    /** The notice reaches the model at a turn start; a store that cannot answer here just means no notice this turn. */
    private HookVerdict updateNotice() {
        try {
            Optional<PluginUpdateService.Notice> notice = updates.cachedStatus().flatMap(updates::takeNotice);
            return notice.<HookVerdict>map(value -> new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(mapper.writeValueAsString(value), false)))).orElse(PROCEED);
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): the notice is optional and must not fail the hook's answer; the failure is logged with its cause.
            logFailure("update-notice", failure);
            return PROCEED;
        }
    }

    private void advisory(String observation, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): an observation is advisory and must not fail the hook's answer; the failure is logged with its cause.
            logFailure(observation, failure);
        }
    }

    private static void logFailure(String observation, RuntimeException failure) {
        log.warn("A hook observation was not recorded: observation={}", observation, failure);
    }
}
