// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.limits.ResourceLimitSource;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;

/**
 * The workflow's view of session continuity: which session bound which root, and the edit window that counts changes made without a
 * workflow. Same correlation and event names as the session-owned continuity code, so both read the same rows.
 */
@Component
public class WorkflowContinuity {
    static final String PHASE_DENY = "phase-deny";
    static final String PHASE_START = "phase-workflow-start";
    static final String PHASE_CHECK = "phase-check";
    static final String PHASE_FILE = "phase-file";
    static final String START_FAILED_PREFIX = "workflow-start-failed:";
    private static final String PHASE_CHILD_ROOT = "phase-child-root:";
    private static final String PHASE_ROOT_ACTOR = "phase-root-actor:";

    private final ContinuityStore store;
    private final WorkflowSettlement settlement;
    private final ResourceLimitSource limits;
    private final WorkflowDocuments documents;

    private final Clock clock;

    public WorkflowContinuity(ContinuityStore store, WorkflowSettlement settlement, Clock clock, ResourceLimitSource limits, WorkflowDocuments documents) {
        this.clock = clock;
        this.store = store;
        this.settlement = settlement;
        this.limits = limits;
        this.documents = documents;
    }

    ContinuityStore store() {
        return store;
    }

    String correlate(String sessionId) {
        return store.opaqueDigest("session", sessionId);
    }

    String hashOpaque(String kind, String value) {
        return store.opaqueDigest(kind, value);
    }

    boolean ownsRoot(WorkflowCall call, String rootId) {
        return store.ownsRoot(correlate(call.sessionId()), rootId);
    }

    boolean ownedByOtherSession(WorkflowCall call, String rootId) {
        String correlation = correlate(call.sessionId());
        return store.ownedElsewhere(correlation, rootId) && !store.ownsRoot(correlation, rootId);
    }

    boolean rootBound(String rootId) {
        return store.rootBound(rootId);
    }

    private long windowStart(String task, long epoch) {
        return Math.max(last(task, epoch, PHASE_CHECK), last(task, epoch, PHASE_START));
    }

    private long last(String task, long epoch, String event) {
        Long id = store.lastObservationId(task, epoch, event, Long.MAX_VALUE);
        return id == null ? 0 : id;
    }

    private boolean locked(String task, long epoch) {
        return last(task, epoch, PHASE_DENY) > last(task, epoch, PHASE_START);
    }

    /** Distinct product files edited in the session's current window before the start. */
    int priorFileCount(WorkflowCall call) {
        ContinuityStore.Task task = store.readTask(correlate(call.sessionId()));
        if (task == null) {
            return 0;
        }
        String c = task.taskCorrelation();
        long e = task.currentEpoch();
        long from = windowStart(c, e);
        if (locked(c, e)) {
            // The lock began at the first refusal after the last start; nothing was counted after it.
            long lastStart = last(c, e, PHASE_START);
            Long firstDeny = store.firstObservationIdAfter(c, e, PHASE_DENY, lastStart);
            Long checkBeforeDeny = store.lastObservationId(c, e, PHASE_CHECK, firstDeny == null ? 0 : firstDeny);
            from = Math.max(checkBeforeDeny == null ? 0 : checkBeforeDeny, lastStart);
        }
        Set<String> files = new LinkedHashSet<>(store.observationHashesAfter(c, e, PHASE_FILE, from));
        return files.size();
    }

    /** Moves a session whose every bound root has settled to a new epoch, as a new prompt would, so a new start can bind. */
    void settle(WorkflowCall call) {
        String now = clock.instant().toString();
        settlement.settle(store.ensureTask(correlate(call.sessionId()), now), now);
    }

    /** Called under the same start guard that serializes the new run, before creating it. */
    void requireInquirySlot(WorkflowCall call) {
        ContinuityStore.Task task = store.readTask(correlate(call.sessionId()));
        if (task == null) {
            return;
        }
        for (String rootId : store.boundRoots(task.taskCorrelation(), task.currentEpoch())) {
            if (kindOf(task, rootId) != WorkflowKind.INQUIRY) {
                continue;
            }
            switch (documents.readCurrent(rootId)) {
                case WorkflowDocuments.Readable read -> {
                    WorkflowReceipt run = read.latestRun();
                    if (run != null && !run.state().isTerminal()) {
                        throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION,
                                msg("workflow.service.inquiryAlreadyOpen", "runId", run.runId()),
                                details("reason", "INQUIRY_ALREADY_OPEN", "runId", run.runId(), "nextAction", "resume_workflow"));
                    }
                }
                case WorkflowDocuments.Unavailable unavailable -> throw unavailable.cause();
            }
        }
    }

    /** The longer paths that start a run ({@code start_guarded_workflow}) open a change. */
    void prepareWorkflowStart(WorkflowCall call, String rootId, boolean childOwner) {
        prepareWorkflowStart(call, rootId, childOwner, WorkflowKind.CHANGE);
    }

    /**
     * Binds the session to the root before the run commits, in the caller's transaction, and records what the instance is: its kind (found by the root's id) and that the request in
     * progress is handled by it, for the agent that opened it. Roots bound earlier do not conflict: overlapping paths are the convergence guard's ROOT_CONFLICT. A subagent's root also
     * stays recorded as its own.
     */
    void prepareWorkflowStart(WorkflowCall call, String rootId, boolean childOwner, WorkflowKind kind) {
        String now = clock.instant().toString();
        ContinuityStore.Task task = store.ensureTask(correlate(call.sessionId()), now);
        if (store.ownedElsewhere(task.taskCorrelation(), rootId)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("continuity.binding.rootOwnedElsewhere", "rootId", rootId), details("reason", "ROOT_NOT_OWNED", "rootId", rootId));
        }
        admitRoot(task, rootId);
        if (!store.bindRoot(task.taskCorrelation(), task.currentEpoch(), rootId, now)) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("continuity.binding.stale"), details("reason", "CONTINUITY_EPOCH_CHANGED"));
        }
        markRootOwner(task.taskCorrelation(), task.currentEpoch(), rootId, childOwner ? call.actorId() : null, now);
        // A start ends the window and its lock; success marks a start that followed a refused edit.
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), PHASE_START, null, locked(task.taskCorrelation(), task.currentEpoch()), now);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), WorkflowEvents.kindEvent(kind, rootId), null, true, now);
        attribute(task, call, rootId, now);
    }

    /** The request in progress is handled by this instance, for this agent: the record the hooks look for before they let a tool run. Found by the instance's id, with the agent's. */
    private void attribute(ContinuityStore.Task task, WorkflowCall call, String rootId, String now) {
        long turnStart = turnStart(task);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), WorkflowEvents.attributedEvent(hashOpaque("actor", call.actorId()), rootId), null, true, now);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), WorkflowEvents.turnSelected(turnStart), null, true, now);
    }

    /**
     * Whether this agent may take the instance for its request: the session's own agent may take any instance of the session (one a subagent opened too); a subagent only the instances
     * it opened itself. Whether the caller is a subagent is the host's fact, carried by the call.
     */
    boolean mayTake(WorkflowCall call, String rootId, boolean child) {
        ContinuityStore.Task task = store.readTask(correlate(call.sessionId()));
        return mayTake(task, call, rootId, child);
    }

    private boolean mayTake(ContinuityStore.Task task, WorkflowCall call, String rootId, boolean child) {
        if (task == null || !store.ownsRoot(task.taskCorrelation(), rootId)) {
            return false;
        }
        return !child || store.hasObservation(task.taskCorrelation(), task.currentEpoch(), PHASE_ROOT_ACTOR + hashOpaque("actor", call.actorId()) + ":" + rootId);
    }

    /** The agent takes an open instance for its request: the second way, besides opening one, to have a request handled by a workflow. */
    void resume(WorkflowCall call, String rootId) {
        String now = clock.instant().toString();
        ContinuityStore.Task task = store.ensureTask(correlate(call.sessionId()), now);
        attribute(task, call, rootId, now);
    }

    /** Capture the served turn before mutating the run so a later prompt cannot inherit its transition. */
    Optional<TransitionTurn> transitionTurn(WorkflowCall call, String rootId, boolean child) {
        ContinuityStore.Task task = store.readTask(correlate(call.sessionId()));
        if (!mayTake(task, call, rootId, child)) {
            return Optional.empty();
        }
        return Optional.of(transitionTurn(task, call, child));
    }

    /** The caller's request for a release whose own authorization has already been decided. */
    Optional<TransitionTurn> releaseTurn(WorkflowCall call, boolean child) {
        return Optional.ofNullable(store.readTask(correlate(call.sessionId())))
                .map(task -> transitionTurn(task, call, child));
    }

    private TransitionTurn transitionTurn(ContinuityStore.Task task, WorkflowCall call, boolean child) {
        Long turnStart = child ? null : turnStart(task);
        return new TransitionTurn(task.taskCorrelation(), task.currentEpoch(),
                WorkflowEvents.transitionPrefix(child ? hashOpaque("actor", call.actorId()) : null, turnStart == null ? 0 : turnStart), turnStart);
    }

    private long turnStart(ContinuityStore.Task task) {
        ContinuityStore.ObservationAt start = store.lastObservationAnyEpoch(task.taskCorrelation(), WorkflowEvents.TURN_START);
        return start == null ? 0 : start.id();
    }

    record TransitionTurn(String correlation, long epoch, String prefix, Long rootTurnStart) {
    }

    /** Written in the same transaction as the resulting revision and its operation receipt. */
    void recordTransition(TransitionTurn turn, WorkflowEvents.Transition transition, String subjectId, long revision) {
        String now = clock.instant().toString();
        store.recordObservation(turn.correlation(), turn.epoch(), turn.prefix() + transition.name() + ":" + subjectId + ":" + revision, null, true, now);
        if (turn.rootTurnStart() != null) {
            store.recordObservation(turn.correlation(), turn.epoch(), WorkflowEvents.turnSelected(turn.rootTurnStart()), null, true, now);
        }
    }

    /** The kind an instance was opened as; an instance with no record was opened by a longer path and is a change. */
    WorkflowKind kindOf(WorkflowCall call, String rootId) {
        ContinuityStore.Task task = store.readTask(correlate(call.sessionId()));
        return task == null ? WorkflowKind.CHANGE : kindOf(task, rootId);
    }

    private WorkflowKind kindOf(ContinuityStore.Task task, String rootId) {
        for (WorkflowKind kind : WorkflowKind.values()) {
            if (store.hasObservation(task.taskCorrelation(), task.currentEpoch(), WorkflowEvents.kindEvent(kind, rootId))) {
                return kind;
            }
        }
        return WorkflowKind.CHANGE;
    }

    /**
     * Binds the session's task to the root that this transaction just inserted. It runs in the transaction of the use case that opens the root, so a failure here is a failure of that
     * use case: nothing is caught, the exception ends the transaction, and the root, its surface row and the abandoning of a parent root roll back with it. (A statement that fails in
     * a transaction on PostgreSQL leaves the transaction aborted, so carrying on after it would end in 25P02 at the next statement; on another database it would leave a committed root
     * that no task holds.) Of two first calls of a session that find no task, the second {@code ensureTask} INSERT fails with {@code STORE_UNIQUE_VIOLATION}, which is the loser's answer.
     */
    void bindOpenedRoot(WorkflowCall call, String rootId, boolean childOwner) {
        String now = clock.instant().toString();
        ContinuityStore.Task task = store.ensureTask(correlate(call.sessionId()), now);
        admitRoot(task, rootId);
        if (!store.bindRoot(task.taskCorrelation(), task.currentEpoch(), rootId, now)) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("continuity.binding.stale"), details("reason", "CONTINUITY_EPOCH_CHANGED"));
        }
        markRootOwner(task.taskCorrelation(), task.currentEpoch(), rootId, childOwner ? call.actorId() : null, now);
    }

    /** Continuing an existing root uses its slot; only binding a new root consumes capacity. */
    private void admitRoot(ContinuityStore.Task task, String rootId) {
        if (store.ownsRoot(task.taskCorrelation(), rootId)) {
            return;
        }
        if (!store.serializeRootAdmission(task.taskCorrelation(), task.currentEpoch())) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("continuity.binding.stale"), details("reason", "CONTINUITY_EPOCH_CHANGED"));
        }
        Capacity capacity = capacity(task.taskCorrelation());
        if (capacity.remaining() == 0) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION,
                    msg("workflow.capacity.reached", "openCount", capacity.openCount(), "limit", capacity.limit()),
                    details("reason", "SESSION_WORKFLOW_LIMIT_REACHED", "limit", capacity.limit(), "openCount", capacity.openCount(), "remaining", 0,
                            "nextAction", "resume_workflow", "changePlanTool", "submit_change_plan",
                            "recoveryTool", "list_session_workflows", "recoveryArgs", Map.of("openRootAfter", "")));
        }
    }

    /** Snapshot returned to the agent; replayed start receipts retain the original snapshot. */
    public record Capacity(int limit, long openCount, long remaining) { }

    Capacity capacity(WorkflowCall call) {
        return capacity(correlate(call.sessionId()));
    }

    private Capacity capacity(String correlation) {
        int limit = limits.current().maxOpenWorkflowsPerSession();
        long open = store.openRootCount(correlation);
        return new Capacity(limit, open, Math.max(0L, (long) limit - open));
    }

    void recordStartFailure(WorkflowCall call, WorkflowCode code) {
        String now = clock.instant().toString();
        // The failure of a start is recorded on the session's task, which this use case makes when the session has none: the start that failed rolled back with whatever it made, and a
        // session whose first call is the refused start has no task before this one. The phase check reports the failure code.
        ContinuityStore.Task task = store.ensureTask(correlate(call.sessionId()), now);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), START_FAILED_PREFIX + code, null, false, now);
    }

    private void markRootOwner(String correlation, long epoch, String rootId, String owner, String now) {
        if (owner == null) {
            return;
        }
        store.recordObservation(correlation, epoch, PHASE_CHILD_ROOT + rootId, null, true, now);
        store.recordObservation(correlation, epoch, PHASE_ROOT_ACTOR + hashOpaque("actor", owner) + ":" + rootId, null, true, now);
    }
}
