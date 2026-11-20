// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.time.Clock;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.server.context.RoleContextSaved;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.ObservationAt;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Task;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * AGS3-D-0156: whether a turn left a record of what the user said, asked of the store and not left to the agent's own care. A turn runs from the user's prompt (the server records
 * its start for every host) to the turn end. It is covered by a record when the main agent saved after that start ({@code checkpoint_context} or
 * {@code checkpoint_role_context}, which write the record here when they succeed) or said there was nothing to save ({@code mark_turn_unchanged}). The turn end continues a turn
 * that has neither once; a turn that ends without either, whether the host closed it for good, already continued it, or sent no turn end at all, is named at the next prompt.
 *
 * <p>The records are observations of the session's task, read across its epochs: a turn does not end where an epoch does. A save or a mark counts for the actor that made it, so a
 * subagent's does not pass the main agent's turn. Every method is one transaction.
 */
@Service
public class TurnCheckService {
    private static final String START = "turn-check:start";
    private static final String SAVED = "turn-check:saved";
    private static final String UNCHANGED = "turn-check:unchanged";
    private static final String ASKED = "turn-check:asked";
    private static final String CLOSED = "turn-check:closed";

    /** What {@code mark_turn_unchanged} answers; {@code turnStartedAt} is null when the server has seen no prompt of this session to attach the mark to. */
    public record TurnMarked(String schemaVersion, boolean marked, String turnStartedAt, String markedAt) {
    }

    private final ContinuityStore store;
    private final Clock clock;
    private final OperationReceipts receipts;

    public TurnCheckService(ContinuityStore store, Clock clock, OperationReceipts receipts) {
        this.store = store;
        this.clock = clock;
        this.receipts = receipts;
    }

    /** {@code mark_turn_unchanged} once per operationKey (AGS3-D-0267): a repeat does not add a second UNCHANGED observation. */
    @Transactional
    public TurnMarked markUnchanged(ContinuityService.Caller caller, OperationKey operation) {
        return receipts.once(operation, TurnMarked.class, () -> markUnchanged(caller)).value();
    }

    /**
     * A user prompt of the main agent opens a turn. Returns the lines that name the previous turn when it ended with no record, for the turn start's context; nothing for a session
     * whose restore the user suppressed, which is neither asked nor warned.
     */
    @Transactional
    public List<MessageRef> promptSubmitted(String rawSessionId, String rootActor) {
        String now = now();
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null || task.suppressed()) {
            return List.of();
        }
        ObservationAt previous = store.lastObservationAnyEpoch(task.taskCorrelation(), START);
        List<MessageRef> warning = previous != null && !recorded(task.taskCorrelation(), rootActor, previous.id()) ? List.of(MessageRef.of("hooks.turnCheck.previous")) : List.of();
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), START, null, true, now);
        return warning;
    }

    /**
     * The main agent's turn end. Returns the line that says what the turn lacks when it is to be continued, or null. A turn is continued once: when the host reports that this turn
     * was continued already, or this check asked before, it is closed as unconfirmed instead. A turn end the host cannot continue is left open (a later one may carry the
     * question). A turn end with no prompt after the last turn was closed is no user turn (a woken one) and is not asked.
     */
    @Transactional
    public List<MessageRef> turnEnded(String rawSessionId, String rootActor, boolean hostSaysContinued, boolean continuable) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null || task.suppressed()) {
            return null;
        }
        String c = task.taskCorrelation();
        ObservationAt start = store.lastObservationAnyEpoch(c, START);
        ObservationAt closed = store.lastObservationAnyEpoch(c, CLOSED);
        if (start == null || (closed != null && closed.id() > start.id())) {
            return null;
        }
        String now = now();
        if (recorded(c, rootActor, start.id())) {
            store.recordObservation(c, task.currentEpoch(), CLOSED, null, true, now);
            return null;
        }
        if (!continuable) {
            return null;
        }
        // One question per turn: the statement that moves the task's asked turn forward is the one that asks. Of the Stop hooks that arrive together the other statements change
        // no row, and those hooks close the turn as unconfirmed, as a hook after the question does.
        if (hostSaysContinued || !store.claimTurnAsk(c, start.id())) {
            store.recordObservation(c, task.currentEpoch(), CLOSED, null, false, now);
            return null;
        }
        store.recordObservation(c, task.currentEpoch(), ASKED, null, true, now);
        return List.of(MessageRef.of("hooks.turnCheck.missing"));
    }

    /** A save of this actor succeeded: {@code checkpoint_context} calls it inside its own transaction, {@code checkpoint_role_context} through {@link RoleContextSaved}. */
    @Transactional
    public void saved(String rawSessionId, String actorId) {
        record(rawSessionId, SAVED, actorId);
    }

    @EventListener
    @Transactional
    public void roleContextSaved(RoleContextSaved saved) {
        saved(saved.sessionId(), ContinuityTools.actorId(saved.host(), saved.sessionId(), saved.agentKey()));
    }

    /** {@code mark_turn_unchanged}: the calling actor says this turn has nothing to save. It counts for the turn that began at the last prompt, and for no other. */
    private TurnMarked markUnchanged(ContinuityService.Caller caller) {
        String markedAt = record(caller.sessionId(), UNCHANGED, caller.actorId());
        ObservationAt start = store.lastObservationAnyEpoch(correlate(caller.sessionId()), START);
        return new TurnMarked("1.0.0", true, start == null ? null : start.at(), markedAt);
    }

    private String record(String rawSessionId, String event, String actorId) {
        String now = now();
        // A save or a mark by a session that has no task (a role context saved before any hook) has no turn to count for.
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return now;
        }
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), event, store.opaqueDigest("actor", actorId), true, now);
        return now;
    }

    private boolean recorded(String taskCorrelation, String actorId, long afterId) {
        String actor = store.opaqueDigest("actor", actorId);
        return store.hasObservationAnyEpochAfter(taskCorrelation, SAVED, actor, afterId) || store.hasObservationAnyEpochAfter(taskCorrelation, UNCHANGED, actor, afterId);
    }

    private String correlate(String rawSessionId) {
        return store.opaqueDigest("session", rawSessionId);
    }

    private String now() {
        return StoreValues.iso(clock.millis());
    }
}
