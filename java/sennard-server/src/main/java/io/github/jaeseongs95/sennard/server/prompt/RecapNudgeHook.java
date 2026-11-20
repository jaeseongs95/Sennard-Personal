// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.app.prompt.RecapNudgeService;
import io.github.jaeseongs95.sennard.app.prompt.SummaryState;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.session.HookDomain;

/**
 * The turn-end check of the recap and of the session's summary: at the end of a main session's turn whose newest prompt has no recap, the turn goes on once with the instruction to
 * record it ({@code record_turn_recap}); when the session has no summary on the session board either (a summary is required), the same continuation also asks for it
 * ({@code update_session_status}), and a prompt that has its recap but whose session has no summary is asked for the summary alone. The board's session list is for a person to know
 * what each session does now, so a summary older than the last prompt is asked for as well ({@link SummaryState}, the board's own judgment of staleness). The recap is asked once
 * per prompt; the summary is asked every time it is missing or stale, not once: at the turn end (not at one the host already continued, which would loop on a session that does not answer)
 * and also at the request's start, at a finished model call and at a finished tool whose context reaches the next call ({@code stepBoundary}), on every host, so that a long first turn is
 * asked as soon as it works (a model call about to start is not asked: the call that finished before it already was). The question stops when the summary is current. It runs last ({@code @Order(30)}), after every domain that may continue the turn for its own reason; a turn one of them continued is not asked
 * here but at its next end, and that works because the question is kept per prompt, not per turn: {@link RecapNudgeService} writes a row for the prompt when it asks, so a
 * prompt is asked about once however many turn ends it has, including the ones the host reports as already continued ({@code alreadyContinued} is no part of the question).
 * A session with no prompt in the archive, a child agent's end and a subagent's end pass, and so does a turn end the host closes for good ({@code continuable} is false: the host
 * would drop the continuation, so the question stays for the next turn end that can carry it).
 *
 * <p>A failure to read or write the question's row is a hook that failed, as in {@link PromptCollector} (logged once, thrown again as it is, answered with its category's status):
 * nothing is made up. A question that two turn ends of one prompt wrote at the same moment is the one failure answered as the hook's own conflict: the loser's
 * {@code DUPLICATE} ends in {@code SessionEventHandler} as 409 {@code HOOK_HANDLER_CONFLICT}, and nothing asks again.
 */
@Component
@Order(30)
public class RecapNudgeHook implements HookDomain {
    private static final Logger LOG = LoggerFactory.getLogger(RecapNudgeHook.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();
    private static final String RECAP_INSTRUCTION = "hooks.promptRecap.nudge";
    private static final String SUMMARY_MISSING_INSTRUCTION = "hooks.sessionSummary.nudge";
    private static final String SUMMARY_STALE_INSTRUCTION = "hooks.sessionSummary.stale";

    private final RecapNudgeService nudges;
    private final BoardService board;
    private final RootSessions roots;
    private final Messages messages;

    public RecapNudgeHook(RecapNudgeService nudges, BoardService board, RootSessions roots, Messages messages) {
        this.nudges = nudges;
        this.board = board;
        this.roots = roots;
        this.messages = messages;
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        if (call.module() != null) {
            return PROCEED;
        }
        return switch (call.event()) {
            case HostEvent.TurnEnded ended when ended.continuable() -> roots.of(ended.context()).<HookVerdict>map(session -> decide(session, ended.alreadyContinued())).orElse(PROCEED);
            // The host closes this turn end for good (canceled, failed, past its limit) and drops any continuation: asking would spend the prompt's one recap question on nothing.
            case HostEvent.TurnEnded ignored -> PROCEED;
            // Every other point at which a host takes context: the request's start, a model call that finished (its context reaches the next call) and a finished tool whose context
            // reaches the next call (a host whose finished tool cannot carry it says so in stepBoundary). Only the summary is asked here, every time it is missing or older than the last prompt, until it is current.
            case HostEvent.UserPromptSubmitted submitted -> summaryAtWork(submitted.context());
            case HostEvent.ModelCallFinished finished when finished.carriesContext() -> summaryAtWork(finished.context());
            case HostEvent.ToolFinished finished when finished.stepBoundary() -> summaryAtWork(finished.context());
            default -> PROCEED;
        };
    }

    private HookVerdict summaryAtWork(HostContext context) {
        return roots.of(context).<HookVerdict>map(session -> {
            try {
                SummaryState state = summaryOf(session);
                return state.due() ? new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(summaryText(state), false))) : PROCEED;
            } catch (SennardException failure) {
                LOG.warn("The session summary question was not answered: host={} reason={}", session.host(), failure.code().name(), failure);
                throw failure;
            }
        }).orElse(PROCEED);
    }

    private SummaryState summaryOf(RootSessions.RootSession session) {
        return board.readEntry(session.host(), session.sessionId()).map(SummaryState::of).orElse(SummaryState.MISSING);
    }

    private HookVerdict decide(RootSessions.RootSession session, boolean alreadyContinued) {
        try {
            // A turn the host already continued once is not continued again for the summary: the host's continuation limit is not known, and a session that does not answer would loop.
            // The summary was asked at the request's start and at each step of the turn, and is asked again at the next turn end the host has not continued. The recap's question is the
            // prompt's own (one row), so it stays whatever the host says.
            SummaryState state = alreadyContinued ? SummaryState.CURRENT : summaryOf(session);
            return nudges.remindNow(session.host(), session.sessionId(), state).<HookVerdict>map(reminder -> new HookVerdict.ContinueTurn(instruction(reminder))).orElse(PROCEED);
        } catch (SennardException failure) {
            LOG.warn("The recap question of a turn end was not answered: host={} reason={}", session.host(), failure.code().name(), failure);
            throw failure;
        }
    }

    /** The recap's sentence, the summary's sentence, or both in one continuation. */
    private String instruction(RecapNudgeService.Reminder reminder) {
        String recap = reminder.recap() ? messages.text(RECAP_INSTRUCTION) : null;
        String summary = reminder.summary().due() ? summaryText(reminder.summary()) : null;
        return recap == null ? summary : summary == null ? recap : recap + " " + summary;
    }

    private String summaryText(SummaryState state) {
        return messages.text(state == SummaryState.MISSING ? SUMMARY_MISSING_INSTRUCTION : SUMMARY_STALE_INSTRUCTION);
    }
}
