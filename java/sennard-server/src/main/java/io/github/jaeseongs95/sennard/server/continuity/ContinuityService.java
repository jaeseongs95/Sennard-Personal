// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.message.SessionText;
import io.github.jaeseongs95.sennard.server.workflow.ArtifactCheck;
import io.github.jaeseongs95.sennard.server.workflow.CheckSource;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowEvents;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.service.store.ContextSource;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.MemoryStatus;
import io.github.jaeseongs95.sennard.service.store.RequestReceiptKind;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.CheckpointResult;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Expected;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.ObservationAt;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.PurgeResult;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Snapshot;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Task;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A session's own record of its work and the window of edits it has made. Every method is one transaction; the tool and hook edges never hold one across a call.
 *
 * <p>The session's task is made by the use case that first needs it, in that use case's own transaction: a use case that reads and changes the task reads it and, when the session has
 * none, makes it with the same transaction ({@link ContinuityStore#ensureTask}). Of two first requests of one session that both find none, the primary key lets one insert; the other
 * ends in the store's translated unique violation, which nothing catches or runs again. The two use cases that refuse with the state token the caller is to name
 * ({@link #checkpoint} and {@link #purge}) make no task: a refusal rolls back, and the token of a task made inside it would be gone with it. They refuse a session that has no task
 * without a token; {@code load_context} (or the session's start) makes the task, and its answer is committed.
 */
@Service
public class ContinuityService {
    static final int WORKFLOW_FILE_LIMIT = 3;
    static final int CURRENT_MEMORY_MAX_BYTES = 2000;
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int TOKEN_VERSION = 1;
    private static final long CANDIDATE_TOKEN_TTL_SECONDS = 3600;
    private static final String FORK_START_EVENT = "fork-start";
    private static final String FORK_PARENT_EVENT = "fork-parent:";
    private static final String PHASE_EDIT_EVENT = "phase-edit";
    private static final String PHASE_CHECK_EVENT = "phase-check";
    private static final String PHASE_CHILD_ROOT_EVENT = "phase-child-root:";
    private static final String PHASE_ROOT_ACTOR_EVENT = "phase-root-actor:";
    private static final String SITUATION_HELD_EVENT = "situation-held:";
    /** An event of a subagent's write outside the writeTargets: this, the actor's hash, a colon and the path; the column holds 512 characters. */
    private static final String OUTSIDE_WRITE_PREFIX = "outside-write@";
    private static final String PHASE_DENY_EVENT = "phase-deny";
    private static final String TURN_WORK_PREFIX = "turn-work:";
    private static final String PHASE_START_EVENT = "phase-workflow-start";
    private static final String START_FAILED_PREFIX = "workflow-start-failed:";
    private static final String GATE_UNAVAILABLE_PREFIX = "phase-gate-unavailable:";
    private static final String START_FAILURE_PASS_EVENT = "phase-start-failure-pass";
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** What the contract has always shown for a stale state token, beside the reason (the refusals that say it name no reason code of their own). */
    private static final String STATE_STALE = "CONTINUITY_STATE_STALE";
    private static final int WORKFLOW_CARD_EVIDENCE_LIMIT = 6;
    private static final Logger LOG = LoggerFactory.getLogger(ContinuityService.class);
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    /**
     * What the turn-check cue says about the task's work record. The catalog message selects its wording on the wire text, so the text is part of
     * the message contract ({@code hooks.checkpointCue.status}).
     */
    private enum CueState {
        MISSING("missing"),
        SAVED("saved"),
        NONE("none");

        private final String wire;

        CueState(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    /**
     * The observation events of a turn's work. The stored text is the event name: the work a tool call showed is written after the prefix by
     * its {@code TurnWork} constant's name, the two marks by their own word.
     */
    private enum TurnEvent {
        START(TURN_WORK_PREFIX + "turn"),
        CONTINUED(TURN_WORK_PREFIX + "continued"),
        EDIT(TURN_WORK_PREFIX + HostWorkFacts.TurnWork.EDIT.name()),
        RUN(TURN_WORK_PREFIX + HostWorkFacts.TurnWork.RUN.name()),
        SKILL_READ(TURN_WORK_PREFIX + HostWorkFacts.TurnWork.SKILL_READ.name());

        private final String event;

        TurnEvent(String event) {
            this.event = event;
        }

        String event() {
            return event;
        }

        static TurnEvent of(HostWorkFacts.TurnWork work) {
            return switch (work) {
                case EDIT -> EDIT;
                case RUN -> RUN;
                case SKILL_READ -> SKILL_READ;
            };
        }

        /** The event a stored text names, or null for a text with the prefix that is none of them. */
        static TurnEvent parse(String event) {
            for (TurnEvent known : values()) {
                if (known.event.equals(event)) {
                    return known;
                }
            }
            return null;
        }
    }

    /** A tool call's continuity scope: the task key of the root session, and who in it called. */
    public record Caller(String sessionId, String actorId) {
    }

    /** The request of checkpoint_context, as the contract has already accepted it. */
    public record Checkpoint(String operationKey,String expectedStateToken, MemoryStatus status, ContinuityTools.Core core, List<ContinuityTools.EvidenceRef> evidenceRefs) {
    }

    /** One edit target of a gate check: the normalized key and the path as the refusal shows it. */
    public record Refusal(MessageRef reason) {
    }

    // ------------------------------------------------------------------------------------------------------------ tool results

    /** What load_context answers: the session's own record or the workflow card. */
    public sealed interface Restored permits SnapshotView, WorkflowCard {
    }

    /** The memory a task keeps: the body of checkpoint_context as stored, whose canonical JSON the byte limit and the digest cover. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Memory(MemoryStatus status, ContinuityTools.Core core, List<ContinuityTools.EvidenceRef> evidenceRefs) {
    }

    /** The session's own record as checkpoint_context and load_context show it; {@code memory} and {@code memoryDigest} are null when it holds none. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SnapshotView(String schemaVersion, ContextSource source, String taskCorrelation, long epoch, String stateToken, Memory memory, String memoryDigest, String createdAt,
            String updatedAt) implements Restored {
    }

    /** What inspect_context answers: {@code DEFER} with a summary and a signed token, or {@code REJECT} with neither. */
    public enum Decision {
        DEFER,
        REJECT
    }

    public enum ReasonCode {
        NO_TASK_BINDING,
        RESTORE_SUPPRESSED,
        NO_RESTORE_CANDIDATE,
        EXPLICIT_LOAD_REQUIRED,
        FORK_PARENT_NOT_PINNED,
        FORK_PARENT_PIN_INVALID
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(String schemaVersion, Decision decision, List<ReasonCode> reasonCodes, Summary summary, String restoreToken) {
    }

    /** What a restore offers, by where it comes from. */
    public sealed interface Summary permits DirectSummary, WorkflowSummary {
        ContextSource source();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DirectSummary(String schemaVersion, ContextSource source, String taskCorrelation, long epoch, String stateToken, String status, String memoryDigest, String updatedAt) implements Summary {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkflowSummary(String schemaVersion, ContextSource source, String taskCorrelation, long epoch, long revision, String status, String snapshotDigest, String updatedAt) implements Summary {
    }

    /** What load_context answers for a bound root: a bounded card of the workflow. {@code snapshotDigest} is the digest of the card without it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkflowCard(String schemaVersion, ContextSource source, String taskCorrelation, long epoch, long revision, String rootId, RootState rootState, String taskId, String objective,
            List<String> includedScope, List<String> excludedScope, List<String> acceptanceCriteria, List<String> constraints, Authorization authorization, RunState workflowState,
            String currentStageId, String nextStageId, List<String> blockers, List<String> unresolved, List<ContinuityTools.EvidenceRef> evidenceRefs, int evidenceRefsOmitted, String updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String snapshotDigest) implements Restored {
        WorkflowCard withDigest(String digest) {
            return new WorkflowCard(schemaVersion, source, taskCorrelation, epoch, revision, rootId, rootState, taskId, objective, includedScope, excludedScope, acceptanceCriteria, constraints,
                    authorization, workflowState, currentStageId, nextStageId, blockers, unresolved, evidenceRefs, evidenceRefsOmitted, updatedAt, digest);
        }
    }

    public record Authorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    public record SuppressResult(String schemaVersion, boolean suppressed, long epoch) {
    }

    /** The answer of purge_direct_context, which the store also keeps as the receipt of the request, so a replay reads it back. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurgeReceipt(String schemaVersion, boolean purged, long epoch, String stateToken, String emptyStateToken, String tombstoneDigest, String purgedAt) {
    }

    // ----------------------------------------------------------------------------------------------- documents the digests are made over

    /** The request of checkpoint_context as its command digest sees it. */
    record CheckpointCommand(String schemaVersion, String operationKey,String expectedStateToken, MemoryStatus status, ContinuityTools.Core core, List<ContinuityTools.EvidenceRef> evidenceRefs) {
    }

    record PurgeCommand(String schemaVersion, String operationKey,String expectedStateToken) {
    }

    /** {@code payloadDigest} is null for a task that kept no memory; the digest sees the null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record TombstoneInput(String taskCorrelation, long epoch, String stateToken, String payloadDigest, String purgeRequestHash) {
    }

    /**
     * The signed restore token's payload, under the one-letter names of its wire form. A DIRECT token carries {@code t}, a WORKFLOW token {@code r};
     * {@code f} and {@code g} name the parent of a fork. {@code s} is the text of a {@link ContextSource} constant: it is read from a document that
     * only the signature vouches for, so a text naming none is a refusal and not an exception.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RestoreToken(@JsonProperty("v") int version, @JsonProperty("c") String correlation, @JsonProperty("e") Long epoch, @JsonProperty("s") String source,
            @JsonProperty("t") String stateToken, @JsonProperty("d") String digest, @JsonProperty("r") Long revision, @JsonProperty("x") Long expiresAt,
            @JsonProperty("f") String forkCorrelation, @JsonProperty("g") Long forkEpoch) {
    }

    record VerifiedToken(RestoreToken token, ContextSource source) {
    }

    // -------------------------------------------------------------------------------------------- documents read, written by others

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckpointReceipt(RequestReceiptKind kind, Long epoch, String stateToken, String memoryDigest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemoryStatusDoc(MemoryStatus status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RootStateDoc(RootState state) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunStateDoc(RunState state) {
    }

    /** The convergence root document of the workflow, as far as a restore card and the edit gate read it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RootDoc(RootState state, Long revision, String rootId, String updatedAt, TaskEnvelopeDoc taskEnvelope) {
        RootDoc {
            revision = revision == null ? 0L : revision; // a boxed component: a missing number is no binding error (the primitive's is), and reads as 0
            rootId = orEmpty(rootId);
            updatedAt = orEmpty(updatedAt);
            taskEnvelope = taskEnvelope == null ? new TaskEnvelopeDoc(null, null, null, null, null, null) : taskEnvelope;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskEnvelopeDoc(String taskId, String objective, ScopeDoc scope, List<String> acceptanceCriteria, List<String> constraints, AuthorizationDoc authorization) {
        TaskEnvelopeDoc {
            taskId = orEmpty(taskId);
            objective = orEmpty(objective);
            scope = scope == null ? new ScopeDoc(null, null) : scope;
            acceptanceCriteria = orNone(acceptanceCriteria);
            constraints = orNone(constraints);
            authorization = authorization == null ? new AuthorizationDoc(null, null, null) : authorization;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScopeDoc(List<String> included, List<String> excluded) {
        ScopeDoc {
            included = orNone(included);
            excluded = orNone(excluded);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationDoc(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
        AuthorizationDoc {
            allowedActions = orNone(allowedActions);
            prohibitedActions = orNone(prohibitedActions);
            approvalRequired = orNone(approvalRequired);
        }
    }

    /** The receipt of a workflow run, as far as a restore card and the phase check read it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunDoc(RunState state, Long revision, List<StageResultDoc> stageResults, PlanDoc plan, List<String> blockers, List<String> unresolved) {
        RunDoc {
            revision = revision == null ? 0L : revision; // the run's own revision, which a plan submitted for it must name
            stageResults = stageResults == null ? List.of() : stageResults;
            plan = plan == null ? new PlanDoc(null, null, null) : plan;
            blockers = orNone(blockers);
            unresolved = orNone(unresolved);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanDoc(List<StageDoc> stages, String currentStageId, String nextStageId) {
        PlanDoc {
            stages = stages == null ? List.of() : stages;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StageDoc(String stageId) {
        StageDoc {
            stageId = orEmpty(stageId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StageResultDoc(String stageId, RunState state, OutputDoc output, List<CheckDoc> artifactChecks) {
        StageResultDoc {
            stageId = orEmpty(stageId);
            output = output == null ? new OutputDoc(null) : output;
            artifactChecks = artifactChecks == null ? List.of() : artifactChecks;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OutputDoc(List<ArtifactDoc> artifacts) {
        OutputDoc {
            artifacts = artifacts == null ? List.of() : artifacts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArtifactDoc(String artifactId, String attachmentId, String digest) {
        ArtifactDoc {
            artifactId = orEmpty(artifactId);
            attachmentId = orEmpty(attachmentId);
            digest = orEmpty(digest);
        }
    }

    /** What the server found when it hashed an artifact: {@code source} and {@code check} are the texts of {@link CheckSource} and {@link ArtifactCheck} constants. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckDoc(String source, String check, String artifactId, String attachmentId) {
        CheckDoc {
            artifactId = orEmpty(artifactId);
            attachmentId = orEmpty(attachmentId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LeaseDoc(String leaseId, LeaseState state, Long epoch, Long ordinal) {
        LeaseDoc {
            epoch = epoch == null ? 0L : epoch;
            ordinal = ordinal == null ? 0L : ordinal;
            leaseId = orEmpty(leaseId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OutcomeDoc(String leaseId, String rootId, Long epoch, Long ordinal) {
        OutcomeDoc {
            epoch = epoch == null ? 0L : epoch;
            ordinal = ordinal == null ? 0L : ordinal;
            leaseId = orEmpty(leaseId);
            rootId = orEmpty(rootId);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> orNone(List<String> values) {
        return values == null ? List.of() : values;
    }

    private final ContinuityStore store;
    private final WorkflowStore workflows;
    private final JsonMapper mapper;
    private final Messages messages;
    private final SurfaceIdentity surfaces;

    private final TurnCheckService turnChecks;
    private final Clock clock;
    /** The receipts of the write tools that are not kept in {@code continuity_requests} (suppress_context_restore). */
    private final OperationReceipts receipts;
    private final SecureRandom random;
    private final ChangeJournalService journal;

    public ContinuityService(ContinuityStore store, WorkflowStore workflows, JsonMapper mapper, Messages messages, SurfaceIdentity surfaces, Clock clock, SecureRandom random, TurnCheckService turnChecks,
            ChangeJournalService journal, OperationReceipts receipts) {
        this.receipts = receipts;
        this.journal = journal;
        this.turnChecks = turnChecks;
        this.clock = clock;
        this.random = random;
        this.store = store;
        this.workflows = workflows;
        this.mapper = mapper;
        this.messages = messages;
        this.surfaces = surfaces;
    }

    // ------------------------------------------------------------------------------------------------------------------------ start

    /** The server's start: makes the secret that the session keys and the restore tokens are made with. It exists once in the whole server, so no request makes it. */
    @Transactional
    public void prepareSecret() {
        store.createSecretIfAbsent();
    }

    // ------------------------------------------------------------------------------------------------------------------ tools

    @Transactional
    public SnapshotView checkpoint(Caller caller, Checkpoint request) {
        Task task = existingSession(caller.sessionId()).orElseThrow(() -> noStateToName(ConflictCode.CHECKPOINT_STATE_CHANGED, request.expectedStateToken()));
        Memory memory = new Memory(request.status(), request.core(), request.evidenceRefs());
        String memoryJson = serialized(mapper.valueToTree(memory));
        String operationKeyHash = hashOpaque("operationKey", request.operationKey());
        String commandDigest = CanonicalJson.digest(mapper.valueToTree(new CheckpointCommand(SCHEMA_VERSION, request.operationKey(),request.expectedStateToken(), request.status(), request.core(),
                request.evidenceRefs())));
        // ceiling: anchors of a fork's lineage are not recorded; a fork's parent is found through its host-recorded origin only.
        CheckpointResult stored = store.checkpoint(task.taskCorrelation(), task.currentEpoch(), request.expectedStateToken(), operationKeyHash, commandDigest, memoryJson, now(), List.of());
        switch (stored) {
            case ContinuityStore.EpochMismatch mismatch -> throw new BindingException(BindingCode.CONTINUITY_EPOCH_STALE);
            case ContinuityStore.Conflict conflict -> throw new ConflictException(ConflictCode.CHECKPOINT_REQUEST_CONFLICT);
            case ContinuityStore.Stale stale -> throw new ConflictException(ConflictCode.CHECKPOINT_STATE_CHANGED, Map.of(), SennardException.args("reasonCode", STATE_STALE,
                    "expectedStateToken", request.expectedStateToken(), "actualStateToken", stale.actualStateToken()));
            case ContinuityStore.Replay first -> {
                CheckpointReceipt receipt = storedDocument(mapper, first.request().resultJson(), CheckpointReceipt.class);
                Snapshot replay = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
                if (receipt == null || receipt.kind() != RequestReceiptKind.CHECKPOINT || receipt.epoch() == null || receipt.epoch() != task.currentEpoch()
                        || replay == null || replay.memoryJson() == null || !replay.stateToken().equals(receipt.stateToken()) || !replay.memoryDigest().equals(receipt.memoryDigest())) {
                    throw new ConflictException(ConflictCode.CHECKPOINT_REPLAY_UNAVAILABLE);
                }
                verify(replay);
                return snapshotView(replay, mapper.readValue(replay.memoryJson(), Memory.class));
            }
            case ContinuityStore.Stored written -> {
                store.recordObservation(task.taskCorrelation(), task.currentEpoch(), situationHeldEvent(caller.actorId(), written.snapshot().stateToken()), null, true, now());
                turnChecks.saved(caller.sessionId(), caller.actorId());
                return snapshotView(written.snapshot(), memory);
            }
        }
    }

    @Transactional
    public Candidate inspect(Caller caller) {
        Task task = session(caller.sessionId());
        Candidate own = candidateFor(task.taskCorrelation(), task.currentEpoch());
        if (own.reasonCodes().getFirst() != ReasonCode.NO_RESTORE_CANDIDATE) {
            return own;
        }
        if (store.hasObservation(task.taskCorrelation(), task.currentEpoch(), FORK_START_EVENT) && task.rootId() == null) {
            String pinned = store.firstObservationEvent(task.taskCorrelation(), task.currentEpoch(), FORK_PARENT_EVENT);
            List<ReasonCode> reasons = new ArrayList<>(own.reasonCodes());
            reasons.add(pinned == null ? ReasonCode.FORK_PARENT_NOT_PINNED : ReasonCode.FORK_PARENT_PIN_INVALID);
            return new Candidate(own.schemaVersion(), own.decision(), reasons, own.summary(), own.restoreToken());
        }
        return own;
    }

    @Transactional
    public Restored load(Caller caller, String candidateToken) {
        Task task = session(caller.sessionId());
        if (task.suppressed()) {
            throw new NotFoundException(NotFoundCode.RESTORE_SUPPRESSED);
        }
        if (candidateToken == null) {
            Snapshot current = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
            if (current == null) {
                throw new NotFoundException(NotFoundCode.RESTORE_DIRECT_NOT_FOUND);
            }
            verify(current);
            return snapshotView(current);
        }
        VerifiedToken verified = verifyCandidate(candidateToken);
        RestoreToken candidate = verified.token();
        boolean fork = candidate.forkCorrelation() != null;
        if (fork ? !task.taskCorrelation().equals(candidate.forkCorrelation()) || candidate.forkEpoch() != task.currentEpoch()
                : !task.taskCorrelation().equals(candidate.correlation()) || candidate.epoch() != task.currentEpoch()) {
            throw new BindingException(BindingCode.CONTINUITY_CANDIDATE_MISMATCH);
        }
        if (verified.source() == ContextSource.DIRECT) {
            Snapshot current = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
            if (current == null || current.memoryJson() == null || !current.stateToken().equals(candidate.stateToken()) || !current.memoryDigest().equals(candidate.digest())) {
                throw new ConflictException(ConflictCode.RESTORE_DIRECT_STALE, Map.of(), SennardException.args("reasonCode", STATE_STALE));
            }
            verify(current);
            return snapshotView(current);
        }
        if (task.rootId() == null) {
            throw new ConflictException(ConflictCode.SNAPSHOT_SOURCE_CHANGED);
        }
        WorkflowCard card = workflowProjection(task);
        if (card == null || card.revision() != candidate.revision() || !card.snapshotDigest().equals(candidate.digest())) {
            throw new ConflictException(ConflictCode.RESTORE_WORKFLOW_STALE);
        }
        return card;
    }

    /** The suppression of the context restore, once per operationKey (AGS3-D-0267); the write is the private method below, which runs inside this transaction. */
    @Transactional
    public SuppressResult suppress(Caller caller, long expectedEpoch, OperationKey operation) {
        return receipts.once(operation, SuppressResult.class, () -> suppress(caller, expectedEpoch)).value();
    }

    private SuppressResult suppress(Caller caller, long expectedEpoch) {
        Task task = session(caller.sessionId());
        if (expectedEpoch != task.currentEpoch()) {
            throw new ConflictException(ConflictCode.CONTINUITY_EPOCH_CHANGED, Map.of(), SennardException.args("actualEpoch", task.currentEpoch()));
        }
        if (!store.setSuppressed(task.taskCorrelation(), task.currentEpoch(), now())) {
            throw new ConflictException(ConflictCode.CONTINUITY_EPOCH_CHANGED);
        }
        return new SuppressResult(SCHEMA_VERSION, true, task.currentEpoch());
    }

    @Transactional
    public PurgeReceipt purge(Caller caller, String operationKey, String expectedStateToken) {
        Task task = existingSession(caller.sessionId()).orElseThrow(() -> noStateToName(ConflictCode.PURGE_STATE_CHANGED, expectedStateToken));
        Snapshot current = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        String operationKeyHash = hashOpaque("operationKey", operationKey);
        String commandDigest = CanonicalJson.digest(mapper.valueToTree(new PurgeCommand(SCHEMA_VERSION, operationKey, expectedStateToken)));
        String tombstoneDigest = CanonicalJson.digest(mapper.valueToTree(new TombstoneInput(task.taskCorrelation(), task.currentEpoch(), expectedStateToken,
                current == null ? null : current.memoryDigest(), operationKeyHash)));
        String now = now();
        PurgeResult purged = store.purge(task.taskCorrelation(), task.currentEpoch(), expectedStateToken, operationKeyHash, commandDigest, tombstoneDigest, now);
        switch (purged) {
            case ContinuityStore.EpochMismatch mismatch -> throw new BindingException(BindingCode.CONTINUITY_EPOCH_STALE);
            case ContinuityStore.Conflict conflict -> throw new ConflictException(ConflictCode.PURGE_REQUEST_CONFLICT);
            case ContinuityStore.Stale stale -> throw new ConflictException(ConflictCode.PURGE_STATE_CHANGED, Map.of(), SennardException.args("reasonCode", STATE_STALE,
                    "expectedStateToken", expectedStateToken, "actualStateToken", stale.actualStateToken()));
            case ContinuityStore.Replay first -> {
                PurgeReceipt replay = storedDocument(mapper, first.request().resultJson(), PurgeReceipt.class);
                ContinuityStore.Tombstone tombstone = store.getTombstone(task.taskCorrelation(), task.currentEpoch());
                Snapshot empty = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
                if (replay == null || !replay.purged() || tombstone == null || replay.epoch() != task.currentEpoch()
                        || !expectedStateToken.equals(replay.stateToken()) || !tombstone.stateToken().equals(replay.stateToken())
                        || !tombstone.emptyStateToken().equals(replay.emptyStateToken()) || !tombstone.payloadDigest().equals(replay.tombstoneDigest())
                        || !tombstone.purgedAt().equals(replay.purgedAt()) || empty == null || !empty.stateToken().equals(replay.emptyStateToken()) || empty.memoryJson() != null) {
                    throw new ConflictException(ConflictCode.PURGE_REPLAY_UNAVAILABLE);
                }
                return replay;
            }
            case ContinuityStore.Purged done -> {
                return new PurgeReceipt(SCHEMA_VERSION, true, task.currentEpoch(), expectedStateToken, done.snapshot().stateToken(), tombstoneDigest, now);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- workflow side

    // ---------------------------------------------------------------------------------------------------------- turn work

    /** The evidence of earlier turns stops counting here; a session's skill read does not. */
    @Transactional
    public void startTurnWork(String rawSessionId) {
        Task task = session(rawSessionId);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), TurnEvent.START.event(), null, true, now());
    }

    @Transactional
    public void recordTurnWork(String rawSessionId, HostWorkFacts.TurnWork work) {
        Task task = session(rawSessionId);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), TurnEvent.of(work).event(), null, true, now());
    }

    /**
     * What a turn that changed files did not show, or null: a command run after the last change and a skill read. It asks once per turn, because the host
     * has no way of its own to say the turn was already continued for this.
     */
    @Transactional
    public List<MessageRef> turnWorkCheck(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return null;
        }
        String c = task.taskCorrelation();
        long e = task.currentEpoch();
        int edits = 0;
        int runsBeforeEdit = 0;
        int runsAfterEdit = 0;
        boolean continued = false;
        for (String stored : store.observationEventsAfter(c, e, TURN_WORK_PREFIX, lastId(c, e, TurnEvent.START.event()))) {
            TurnEvent event = TurnEvent.parse(stored);
            if (event == null) {
                continue;
            }
            switch (event) {
                case EDIT -> {
                    edits++;
                    runsAfterEdit = 0;
                }
                case RUN -> {
                    if (edits == 0) {
                        runsBeforeEdit++;
                    } else {
                        runsAfterEdit++;
                    }
                }
                case CONTINUED -> continued = true;
                case START, SKILL_READ -> {
                }
            }
        }
        boolean skillRead = store.hasObservation(c, e, TurnEvent.SKILL_READ.event());
        boolean missingRun = runsAfterEdit == 0;
        if (edits == 0 || continued || (skillRead && !missingRun)) {
            return null;
        }
        List<MessageRef> lines = new ArrayList<>();
        lines.add(MessageRef.of("connector.turnEvidence.head", "edits", edits));
        if (missingRun) {
            lines.add(MessageRef.of("connector.turnEvidence.rerun"));
        }
        if (!skillRead) {
            lines.add(MessageRef.of("connector.turnEvidence.skill"));
        }
        if (runsBeforeEdit == 0) {
            lines.add(MessageRef.of("connector.turnEvidence.noRepro"));
        }
        store.recordObservation(c, e, TurnEvent.CONTINUED.event(), null, true, now());
        return lines;
    }

    // ---------------------------------------------------------------------------------------------------------- lifecycle hooks

    /**
     * A new session's start: makes its task, and nothing else. The task is made here, by the start's own use case, and not by the first tool call that needs it: a tool call that
     * the contract refuses (checkpoint_context with a stale state token) throws, its transaction rolls back, and a task made inside it would be gone with the {@code actualStateToken}
     * the refusal has just shown. A session that no start reached has no task until a use case that does not refuse makes it ({@link #session}: {@code load_context},
     * {@code inspect_context}); {@code checkpoint_context} and {@code purge_direct_context} refuse such a session without a token ({@link #existingSession}).
     */
    @Transactional
    public void startSession(String rawSessionId) {
        store.ensureTask(correlate(rawSessionId), now());
    }

    @Transactional
    public void startFork(String rawSessionId) {
        Task task = session(rawSessionId);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), FORK_START_EVENT, null, true, now());
    }

    @Transactional
    public void clear(String rawSessionId) {
        String now = now();
        store.rotateEpoch(store.ensureTask(correlate(rawSessionId), now).taskCorrelation(), now, null);
    }

    @Transactional
    public void markPreCompact(String rawSessionId) {
        Task task = session(rawSessionId);
        if (task.suppressed()) {
            return;
        }
        Summary summary = summary(task);
        if (summary == null) {
            return;
        }
        String now = now();
        switch (summary) {
            case DirectSummary direct -> store.setDirectPendingMarker(task.taskCorrelation(), task.currentEpoch(), direct.stateToken(), direct.memoryDigest(), now);
            case WorkflowSummary workflow -> store.setWorkflowPendingMarker(task.taskCorrelation(), task.currentEpoch(), workflow.revision(), workflow.snapshotDigest(), task.rootId(), now);
        }
    }

    @Transactional
    public void recordPostCompact(String rawSessionId, String turnId, boolean success) {
        Task task = session(rawSessionId);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), "post-compact", turnId == null ? null : hashOpaque("turn", turnId), success, now());
    }

    @Transactional
    public List<MessageRef> resumeContext(String rawSessionId) {
        Task task = session(rawSessionId);
        List<MessageRef> restored = restoreCurrent(task);
        if (restored != null) {
            return restored;
        }
        if (task.suppressed() || !(summary(task) instanceof WorkflowSummary workflow)) {
            return null;
        }
        return List.of(MessageRef.of("continuity.restore.candidate", "source", workflow.source().name(), "epoch", workflow.epoch(), "revision", workflow.revision(), "digest", workflow.snapshotDigest(),
                "candidateToken", restoreToken(task, workflow)));
    }

    @Transactional
    public List<MessageRef> compactContext(String rawSessionId) {
        Task task = session(rawSessionId);
        if (task.suppressed()) {
            return null;
        }
        Restorable restorable = restorable(task);
        if (restorable == null) {
            return checkpointCue(task, true);
        }
        return switch (restorable.summary()) {
            case DirectSummary direct -> compactDirect(task);
            case WorkflowSummary workflow -> compactWorkflow(task, workflow, restorable.card());
        };
    }

    private List<MessageRef> compactDirect(Task task) {
        Snapshot snapshot = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        List<MessageRef> restored = null;
        if (snapshot != null && snapshot.memoryJson() != null && task.pendingSource() == ContextSource.DIRECT && !task.pendingConsumed()
                && snapshot.stateToken().equals(task.pendingStateToken()) && snapshot.memoryDigest().equals(task.pendingDigest())) {
            verify(snapshot);
            if (store.consumeDirectMarker(task.taskCorrelation(), task.currentEpoch(), snapshot.stateToken(), snapshot.memoryDigest(), now())) {
                restored = new ArrayList<>(List.of(MessageRef.of("continuity.restore.current", "stateToken", snapshot.stateToken(), "body", SessionText.quoted(snapshot.memoryJson()))));
            }
        }
        WorkflowCard card = task.rootId() == null ? null : workflowProjection(task);
        if (restored != null && card != null) {
            restored.add(MessageRef.of("continuity.restore.workflowCard", "card", SessionText.quoted(mapper.writeValueAsString(card))));
        }
        return restored;
    }

    private List<MessageRef> compactWorkflow(Task task, WorkflowSummary summary, WorkflowCard card) {
        if (task.pendingConsumed() || task.pendingSource() != ContextSource.WORKFLOW || task.pendingRevision() == null || task.pendingRevision() != summary.revision()
                || !summary.snapshotDigest().equals(task.pendingDigest())) {
            return null;
        }
        if (!card.rootId().equals(task.pendingRootId())
                || !store.consumeWorkflowMarker(task.taskCorrelation(), task.currentEpoch(), card.rootId(), card.revision(), card.snapshotDigest(), now())) {
            return null;
        }
        return List.of(MessageRef.of("continuity.restore.workflowCard", "card", SessionText.quoted(mapper.writeValueAsString(card))));
    }

    /** The turn check: {@code restore} adds the saved body first, for hosts whose turn start is the context start. */
    @Transactional
    public List<MessageRef> turnStart(String rawSessionId, boolean restore) {
        Task task = session(rawSessionId);
        List<MessageRef> lines = new ArrayList<>();
        if (restore) {
            List<MessageRef> restored = restoreCurrent(task);
            if (restored != null) {
                lines.addAll(restored);
            }
        }
        List<MessageRef> cue = checkpointCue(task, false);
        if (cue != null) {
            lines.addAll(cue);
        }
        return lines.isEmpty() ? null : lines;
    }

    /**
     * What a main agent's prompt does, as one use case in one transaction: the turn's start is recorded for the evidence of its work (hosts that check turn work), the saved state
     * and the checkpoint cue are told, and the previous turn that ended with no record is named. The lines of both are in order; null when there are none.
     */
    @Transactional
    public List<MessageRef> onPromptSubmitted(String rawSessionId, boolean checksTurnWork, boolean restore, String rootActor) {
        if (checksTurnWork) {
            startTurnWork(rawSessionId);
        }
        // The prompt opens the turn the workflow gate counts: an instance the agent takes after it (opens or resumes) is what the request is handled by.
        startTurn(rawSessionId);
        return joined(turnStart(rawSessionId, restore), turnChecks.promptSubmitted(rawSessionId, rootActor));
    }

    /**
     * What a main agent's turn end does, as one use case in one transaction: the stage check, the turn evidence (hosts that check turn work, when the turn was not continued
     * already) and the save check each find what they find, and are said in one continuation. A turn is continued once, so what one of them says must not use up what the others
     * would. A paused task keeps its unfinished work, but does not ask the user to continue it until a later checkpoint changes that status.
     */
    @Transactional
    public List<MessageRef> onTurnEnded(String rawSessionId, String rootActor, boolean alreadyContinued, boolean continuable, boolean checksTurnWork) {
        boolean paused = paused(rawSessionId);
        List<MessageRef> phase = paused || !continuable ? null : phaseCheck(rawSessionId, alreadyContinued, null);
        List<MessageRef> work = !paused && !alreadyContinued && checksTurnWork ? turnWorkCheck(rawSessionId) : null;
        List<MessageRef> save = turnChecks.turnEnded(rawSessionId, rootActor, alreadyContinued, continuable);
        return joined(joined(phase, work), save);
    }

    /** A prompt or workflow attribution does not resume the task's saved pause; only a later checkpoint changes its status. */
    private boolean paused(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        Snapshot snapshot = task == null ? null : store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        if (snapshot == null || snapshot.memoryJson() == null) {
            return false;
        }
        verify(snapshot);
        return mapper.readValue(snapshot.memoryJson(), MemoryStatusDoc.class).status() == MemoryStatus.PAUSED;
    }

    /** The lines of both, in order; null when there are none. */
    private static List<MessageRef> joined(List<MessageRef> first, List<MessageRef> second) {
        List<MessageRef> all = new ArrayList<>();
        if (first != null) {
            all.addAll(first);
        }
        if (second != null) {
            all.addAll(second);
        }
        return all.isEmpty() ? null : all;
    }

    /** After a progress tool of Sennard, once per call id. */
    @Transactional
    public List<MessageRef> checkpointCueAfterProgress(String rawSessionId, String callId) {
        Task task = session(rawSessionId);
        if (task.suppressed()) {
            return null;
        }
        if (callId != null) {
            String hash = hashOpaque("turn", callId);
            // Once per call id: the statement that writes the observation is the one that tells.
            if (!store.recordObservationOnce(task.taskCorrelation(), task.currentEpoch(), "checkpoint-progress:" + hash, hash, true, now())) {
                return null;
            }
        }
        return checkpointCue(task, false);
    }

    /** A run failed for lack of a program, path or permission: once per distinct failure in this epoch. */
    @Transactional
    public List<MessageRef> environmentFailureCue(String rawSessionId, String failureKey) {
        String hash = hashOpaque("turn", failureKey);
        Task task = session(rawSessionId);
        // The observation is written by one statement that finds out whether the epoch has it already, and the request whose statement wrote it is the one that tells: parallel
        // subagents that hit the same failure do not wait for each other and tell it once.
        if (!store.recordObservationOnce(task.taskCorrelation(), task.currentEpoch(), "environment-failure:" + hash, hash, true, now())) {
            return null;
        }
        return List.of(MessageRef.of("hooks.environmentFailure.check"));
    }

    /** The session's current body for an actor that does not hold it yet; a body {@code fits} refuses is not marked held. */
    @Transactional
    public List<MessageRef> situationForActor(String rawSessionId, String actorId, Predicate<String> fits) {
        // Every step boundary of a subagent asks, and an actor that holds the body (or a session that has none) writes nothing.
        Task task = store.readTask(correlate(rawSessionId));
        Snapshot snapshot = unheldSnapshot(task, actorId);
        if (snapshot == null) {
            return null;
        }
        verify(snapshot);
        List<MessageRef> text = List.of(MessageRef.of("hooks.contextLayers.parentSituation", "stateToken", snapshot.stateToken(), "body", SessionText.quoted(snapshot.memoryJson())));
        if (!fits.test(messages.renderLines(text))) {
            return null;
        }
        // The held mark of this actor for this state is written by one statement: of the requests that both found the body unheld only the one whose statement wrote it gives it.
        return store.recordObservationOnce(task.taskCorrelation(), task.currentEpoch(), situationHeldEvent(actorId, snapshot.stateToken()), null, true, now()) ? text : null;
    }

    /** The current snapshot of the task when the actor still has to be given it; null when the task is absent, suppressed or has no body, or the actor already holds it. */
    private Snapshot unheldSnapshot(Task task, String actorId) {
        if (task == null || task.suppressed()) {
            return null;
        }
        Snapshot snapshot = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        if (snapshot == null || snapshot.memoryJson() == null) {
            return null;
        }
        return store.hasObservation(task.taskCorrelation(), task.currentEpoch(), situationHeldEvent(actorId, snapshot.stateToken())) ? null : snapshot;
    }

    /**
     * The card of the workflow this session is bound to, for a subagent that starts in it: where the run stands, its scope and its criteria, which the saved state of the session does not carry.
     * Null when the session has no bound workflow, or when the card does not {@code fit} the room the start text has left.
     */
    @Transactional(readOnly = true)
    public List<MessageRef> workflowCardForSubagent(String rawSessionId, Predicate<String> fits) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null || task.suppressed() || task.rootId() == null) {
            return null;
        }
        WorkflowCard card = workflowProjection(task);
        if (card == null) {
            return null;
        }
        List<MessageRef> text = List.of(MessageRef.of("continuity.restore.workflowCard", "card", SessionText.quoted(mapper.writeValueAsString(card))));
        return fits.test(messages.renderLines(text)) ? text : null;
    }

    // ---------------------------------------------------------------------------------------------------------- edit window

    /**
     * Before a product file edit. {@code targets} are normalized non-exempt keys, {@code shown} the same paths as given and
     * {@code unread} are the targets that stand for a write whose file could not be read; they have no place of their own, so they count inside a running workflow only when
     * one of this session's workflows covers every folder in {@code unreadPlaces} (where the call wrote what it could not read: the folder its shell followed to, not the
     * session's working directory) and every file the call does name. No folder (an empty list) is covered by no workflow. An opaque execution with no observed edit is allowed only
     * when an open instance of this request permits that action, before recording any edit. Returns the refusal, or null.
     */
    @Transactional
    public MessageRef gateEdit(String rawSessionId, List<String> targets, List<String> shown, String example, String actor, Set<String> unread, List<String> unreadPlaces, HostWorkFacts host,
            boolean opaqueExecutionOnly) {
        String now = now();
        // The edit is recorded on the session's task, which this use case makes when the session has none (the first hook of a session may be an edit).
        Task task = store.ensureTask(correlate(rawSessionId), now);
        String c = task.taskCorrelation();
        long e = task.currentEpoch();
        // attribution() filters finished instances and keeps the current request and actor; raw attributionOf() alone is not an execution permission.
        try {
            if (opaqueExecutionOnly && unread.containsAll(targets) && attribution(rawSessionId, actor).allows(WorkflowKind.Action.OPAQUE_EXECUTION)) {
                return null;
            }
        } catch (DataAccessException | JacksonException | IllegalStateException failure) {
            LOG.warn("The edit gate could not read what serves the request", failure);
            return unavailable(c, e, windowStart(c, e), now, failureCode(failure));
        }
        if (!targets.isEmpty()) {
            recordEdit(c, e, now, actor);
        }
        long windowStart = windowStart(c, e);
        List<SurfaceIdentity.ActiveRoot> roots;
        try {
            roots = surfaces.activeRoots();
        } catch (DataAccessException | JacksonException | IllegalStateException | InvalidPathException failure) {
            // The store, a stored root that binds to nothing, or a stored surface that is no path: what reading the roots can throw.
            LOG.warn("The edit gate could not read the active roots", failure);
            return unavailable(c, e, windowStart, now, failureCode(failure));
        }
        List<String> checked = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            checked.add(index < shown.size() ? shown.get(index) : targets.get(index));
        }
        List<Path> identities = new ArrayList<>();
        for (String given : checked) {
            identities.add(roots.isEmpty() ? null : surfaces.identify(given));
        }
        for (int index = 0; index < identities.size(); index++) {
            Path target = identities.get(index);
            if (target == null) {
                continue;
            }
            for (SurfaceIdentity.ActiveRoot root : roots) {
                if (root.state().isGated() && root.covers(target)) {
                    return MessageRef.of("hooks.editGate.gatedRoot", "path", checked.get(index), "rootId", root.rootId(), "state", root.state());
                }
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        List<ActiveRun> activity;
        try {
            activity = workflowActivity(task, Owner.ANY);
        } catch (DataAccessException | JacksonException | IllegalStateException failure) {
            LOG.warn("The edit gate could not read the session's workflows", failure);
            return unavailable(c, e, windowStart, now, failureCode(failure));
        }
        // An edit belongs to the instance whose change plan has its file, among the instances the agent took for this request (opened or resumed) and whose kind edits files.
        Attribution attributed = attributionOf(task, actor);
        Set<String> active = new LinkedHashSet<>();
        activity.stream().map(ActiveRun::rootId).filter(attributed::allowsEditsIn).forEach(active::add);
        MessageRef planning;
        try {
            planning = planningRefusal(activity, roots, identities, checked, targets, unread, unreadPlaces, host);
        } catch (DataAccessException failure) {
            LOG.warn("The edit gate could not read the phase of the session's workflows", failure);
            return unavailable(c, e, windowStart, now, failureCode(failure));
        }
        if (planning != null) {
            return planning;
        }
        List<Integer> outside = new ArrayList<>();
        boolean namedOutside = false;
        for (int index = 0; index < targets.size(); index++) {
            Path target = identities.get(index);
            boolean covered = target != null && roots.stream().anyMatch(root -> active.contains(root.rootId()) && root.covers(target));
            if (!covered) {
                outside.add(index);
                namedOutside |= !unread.contains(targets.get(index));
            }
        }
        // A file the call names that no running workflow covers keeps an unread write of the same call from being taken as inside a workflow that covers the whole workspace.
        if (!namedOutside && outside.stream().anyMatch(index -> unread.contains(targets.get(index))) && coversPlaces(roots, active, unreadPlaces)) {
            outside.removeIf(index -> unread.contains(targets.get(index)));
        }
        if (outside.isEmpty()) {
            return null;
        }
        // No instance of this request owns the file: it is refused at once, and the refusal says which of the three it is (no instance of the request, an instance of a kind that does not
        // edit files, an instance whose change plan lacks the file) and the call that opens or resumes the one that owns it.
        store.recordObservation(c, e, PHASE_DENY_EVENT, null, false, now);
        List<String> paths = new ArrayList<>();
        outside.forEach(index -> paths.add(index < shown.size() ? shown.get(index) : targets.get(index)));
        boolean unreadOutside = outside.stream().anyMatch(index -> unread.contains(targets.get(index)));
        if (opaqueExecutionOnly) {
            return MessageRef.of("hooks.workflowGate.opaqueRefused", "paths", String.join(", ", paths), "kinds", attributed.kindNames());
        }
        String reason = !attributed.any() ? "none" : attributed.kinds().stream().noneMatch(WorkflowKind::allowsFileEdits) ? "kind" : unreadOutside ? "unread" : "plan";
        return MessageRef.of("hooks.workflowGate.editRefused", "reason", reason, "paths", String.join(", ", paths), "kinds", attributed.kindNames(), "example", example);
    }

    /**
     * What a request in progress is handled by, for one agent: the instances it took (opened or resumed) since the last prompt, by instance id, with each one's kind. The session's own agent
     * counts every instance of the session taken in the turn (an instance a subagent opened too, which the session's agent may work on); a subagent counts only its own, so an instance
     * the session's agent opened is no instance a subagent can work in.
     */
    public record Attribution(Map<String, WorkflowKind> instances) {
        static final Attribution NONE = new Attribution(Map.of());

        public Attribution {
            instances = Map.copyOf(instances);
        }

        public boolean any() {
            return !instances.isEmpty();
        }

        public Set<WorkflowKind> kinds() {
            return new LinkedHashSet<>(instances.values());
        }

        /** Whether some instance taken for the request has a kind whose process allows the action. */
        public boolean allows(WorkflowKind.Action action) {
            return instances.values().stream().anyMatch(kind -> kind.allows(action));
        }

        boolean allowsEditsIn(String rootId) {
            WorkflowKind kind = instances.get(rootId);
            return kind != null && kind.allowsFileEdits();
        }

        String kindNames() {
            return kinds().stream().map(WorkflowKind::name).collect(Collectors.joining(", "));
        }
    }

    /** One open instance of the session, as a prompt shows it: its id, its kind, the phase of its process and its goal. */
    public record OpenInstance(String runId, WorkflowKind kind, String phase, String goal) {
    }

    private static final int OPEN_INSTANCES_SHOWN = 20;
    private static final int GOAL_SHOWN_CHARACTERS = 200;

    private long lastTurnStart(String c) {
        ObservationAt start = store.lastObservationAnyEpoch(c, WorkflowEvents.TURN_START);
        return start == null ? 0 : start.id();
    }

    private WorkflowKind kindOf(String c, long e, String rootId) {
        for (WorkflowKind kind : WorkflowKind.values()) {
            if (store.hasObservation(c, e, WorkflowEvents.kindEvent(kind, rootId))) {
                return kind;
            }
        }
        // An instance the longer path opened has no record of its kind and is a change.
        return WorkflowKind.CHANGE;
    }

    /**
     * What serves the agent's request. The session's own agent ({@code actor} null) answers the user's prompts, so only what it took since the last prompt counts, and each new request has it
     * choose again. A subagent does not answer the prompts of its parent: every run of it has an agent id of its own, and the workflow instances are independent, so what it took stays its own for
     * as long as its epoch lasts, whatever prompt (a user's, or the notice of a finished background task) the parent receives meanwhile.
     */
    private Attribution attributionOf(Task task, String actor) {
        String c = task.taskCorrelation();
        long e = task.currentEpoch();
        String prefix = actor == null ? WorkflowEvents.ATTRIBUTED_PREFIX : WorkflowEvents.attributedPrefix(hashOpaque("actor", actor));
        Map<String, WorkflowKind> found = new LinkedHashMap<>();
        for (String event : store.observationEventsAfter(c, e, prefix, actor == null ? lastTurnStart(c) : 0)) {
            found.computeIfAbsent(event.substring(event.lastIndexOf(':') + 1), rootId -> kindOf(c, e, rootId));
        }
        return found.isEmpty() ? Attribution.NONE : new Attribution(found);
    }

    /** A prompt opens a turn: the instances taken before it no longer stand for the request. */
    @Transactional
    public void startTurn(String rawSessionId) {
        Task task = session(rawSessionId);
        store.recordObservation(task.taskCorrelation(), task.currentEpoch(), WorkflowEvents.TURN_START, null, true, now());
    }

    /**
     * What the agent's request is handled by, for the session's own agent ({@code actor} null) or for a subagent ({@code actor} its id): the instances it took that are still open. An instance
     * that finished or was closed serves no request, as the edit gate already counts only the running ones.
     */
    @Transactional(readOnly = true)
    public Attribution attribution(String rawSessionId, String actor) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return Attribution.NONE;
        }
        Attribution taken = attributionOf(task, actor);
        if (!taken.any()) {
            return taken;
        }
        Set<String> open = workflowActivity(task, Owner.ANY).stream().filter(run -> run.receipt().state() == RunState.RUNNING)
                .map(ActiveRun::rootId).collect(Collectors.toSet());
        Map<String, WorkflowKind> serving = new LinkedHashMap<>(taken.instances());
        serving.keySet().retainAll(open);
        return serving.isEmpty() ? Attribution.NONE : new Attribution(serving);
    }

    /** A completed or explicitly held instance still handled this request; it grants no further tool execution. */
    @Transactional(readOnly = true)
    public boolean handledInTurn(String rawSessionId, String actor) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return false;
        }
        long turnStart = actor == null ? lastTurnStart(task.taskCorrelation()) : 0;
        String transition = WorkflowEvents.transitionPrefix(actor == null ? null : hashOpaque("actor", actor), turnStart);
        return attributionOf(task, actor).any() || !store.observationEventsAfter(task.taskCorrelation(), task.currentEpoch(), transition, turnStart).isEmpty();
    }

    /** The actual number of open instances, without the prompt list's display limit. */
    @Transactional(readOnly = true)
    public int openInstanceCount(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        return task == null ? 0 : workflowActivity(task, Owner.ANY).size();
    }

    /** The open instances of the session, up to a limit, for the prompt to show. */
    @Transactional(readOnly = true)
    public List<OpenInstance> openInstances(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return List.of();
        }
        return workflowActivity(task, Owner.ANY).stream().limit(OPEN_INSTANCES_SHOWN).map(run -> {
            WorkflowKind kind = kindOf(task.taskCorrelation(), task.currentEpoch(), run.rootId());
            String phase = run.receipt().state() == RunState.RUNNING
                    ? kind.allowsFileEdits() ? journal.phase(run.runId()).map(Enum::name).orElse(kind.firstPhase().id()) : kind.firstPhase().id()
                    : run.receipt().state().name();
            return new OpenInstance(run.runId(), kind, phase, boundedText(WHITESPACE.matcher(run.root().taskEnvelope().objective()).replaceAll(" ").trim(), GOAL_SHOWN_CHARACTERS));
        }).toList();
    }

    /**
     * The open instances the session's own agent opened for this request whose kind closes when the turn ends: what the server closes at the main agent's turn end. An instance a subagent
     * opened is independent of the main agent's turns and is closed at the end of that subagent ({@link #subagentEndInstances}).
     */
    @Transactional(readOnly = true)
    public List<String> turnEndInstances(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return List.of();
        }
        return attribution(rawSessionId, null).instances().entrySet().stream().filter(entry -> entry.getValue().closing() == WorkflowKind.Closing.TURN_END)
                .map(Map.Entry::getKey).filter(rootId -> !store.hasObservation(task.taskCorrelation(), task.currentEpoch(), PHASE_CHILD_ROOT_EVENT + rootId)).toList();
    }

    /** The open instances this subagent took whose kind closes when the turn ends: what the server closes at the end of the subagent. */
    @Transactional(readOnly = true)
    public List<String> subagentEndInstances(String rawSessionId, String actor) {
        return attribution(rawSessionId, actor).instances().entrySet().stream().filter(entry -> entry.getValue().closing() == WorkflowKind.Closing.TURN_END).map(Map.Entry::getKey).toList();
    }

    /** Whether the turn end may be held once more for the missing workflow: the question is counted per prompt, so it has a limit. */
    @Transactional
    public boolean claimStopBlock(String rawSessionId, int limit) {
        // The hold belongs to the current prompt even if an older root cannot be read.
        String c = correlate(rawSessionId);
        String now = now();
        Task task = store.ensureTask(c, now);
        long e = task.currentEpoch();
        if (store.observationEventsAfter(c, e, WorkflowEvents.STOP_BLOCKED, lastTurnStart(c)).size() >= limit) {
            return false;
        }
        store.recordObservation(c, e, WorkflowEvents.STOP_BLOCKED, null, true, now);
        return true;
    }

    /**
     * A write inside the write surface of a running workflow that is still in its planning phase is refused: that phase is for reading and investigation, and the files it
     * will change are submitted first ({@code submit_change_plan}). A write whose file could not be read counts when the run's surface covers the whole working directory
     * of the call. Which phase a run is in is the change journal's (a run with no journal has none and is not held back).
     *
     * <p>The refusal says what the model does next as a call it can make as it stands: the arguments of {@code submit_change_plan} with the run's current revision and the files
     * this edit was blocked on, and the name the host's model calls that tool by ({@code host}). A refusal that only named the tool had the model look up the revision and the
     * tool's name before it could act, and an agent that did not do that tried the same edit again.
     */
    private MessageRef planningRefusal(List<ActiveRun> activity, List<SurfaceIdentity.ActiveRoot> roots, List<Path> identities, List<String> checked, List<String> targets, Set<String> unread,
            List<String> unreadPlaces, HostWorkFacts host) {
        for (ActiveRun run : activity) {
            if (journal.phase(run.runId()).filter(phase -> phase == WorkflowPhase.PLANNING).isEmpty()) {
                continue;
            }
            List<SurfaceIdentity.ActiveRoot> own = roots.stream().filter(root -> root.rootId().equals(run.rootId())).toList();
            List<String> blocked = new ArrayList<>();
            // The files a plan can name: a write whose file could not be read is no path, and stands for nothing a plan lists.
            List<String> plannable = new ArrayList<>();
            for (int index = 0; index < identities.size(); index++) {
                Path target = identities.get(index);
                boolean unreadWrite = unread.contains(targets.get(index));
                boolean covered = target != null ? own.stream().anyMatch(root -> root.covers(target)) : unreadWrite && coversPlaces(own, Set.of(run.rootId()), unreadPlaces);
                if (covered) {
                    blocked.add(checked.get(index));
                    if (!unreadWrite) {
                        plannable.add(checked.get(index));
                    }
                }
            }
            if (!blocked.isEmpty()) {
                return planningRefusalOf(run, blocked, plannable, host);
            }
        }
        return null;
    }

    /** The planning-phase refusal of one run: the call to make (as the JSON of its arguments) and the host's name for the tool, with the sentence of the form that name has. */
    private MessageRef planningRefusalOf(ActiveRun run, List<String> blocked, List<String> plannable, HostWorkFacts host) {
        List<String> files = plannable.isEmpty() ? List.of(messages.render(MessageRef.of("hooks.editGate.writeTargetPlaceholder"))) : plannable;
        String arguments = mapper.writeValueAsString(new SubmitPlanExample(run.runId(), run.receipt().revision(), files, messages.render(MessageRef.of("hooks.editGate.operationKeyPlaceholder"))));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", run.runId());
        params.put("paths", String.join(", ", blocked));
        params.put("revision", String.valueOf(run.receipt().revision()));
        params.put("arguments", arguments);
        Optional<HostWorkFacts.SennardToolName> name = host.sennardToolName("submit_change_plan");
        params.put("call", name.map(called -> called.wrapper() != null ? "wrapped" : called.loader() != null ? "loaded" : "named").orElse("none"));
        name.ifPresent(called -> {
            params.put("tool", called.name());
            params.put("wrapper", orEmpty(called.wrapper()));
            params.put("server", orEmpty(called.server()));
            params.put("loader", orEmpty(called.loader()));
            params.put("query", orEmpty(called.loaderQuery()));
        });
        return MessageRef.of("hooks.editGate.planning", params);
    }

    /** The arguments of the {@code submit_change_plan} that a planning-phase refusal tells the model to make, in the order the tool lists them. */
    record SubmitPlanExample(String runId, long expectedRevision, List<String> files, String operationKey) {
    }

    /** The runs of this session's active workflows, for recording an edit in their change journals. */
    @Transactional(readOnly = true)
    public List<String> activeRunIds(String rawSessionId) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return List.of();
        }
        return workflowActivity(task, Owner.ANY).stream().map(ActiveRun::runId).toList();
    }

    private String outsideWritePrefix(String actor) {
        return OUTSIDE_WRITE_PREFIX + hashOpaque("actor", actor) + ":";
    }

    /**
     * Each folder where the call wrote what it could not read lies in the write surface of one of this session's running workflows. No folder is not covered: where the
     * shell was is not known then, so the write may be anywhere.
     */
    private boolean coversPlaces(List<SurfaceIdentity.ActiveRoot> roots, Set<String> active, List<String> places) {
        return !places.isEmpty() && places.stream().allMatch(given -> {
            Path place = surfaces.identify(given);
            return place != null && roots.stream().anyMatch(root -> active.contains(root.rootId()) && root.covers(place));
        });
    }

    private MessageRef unavailable(String c, long e, long windowStart, String now, String code) {
        String event = GATE_UNAVAILABLE_PREFIX + code;
        if (store.observationEventsAfter(c, e, event, windowStart).isEmpty()) {
            store.recordObservation(c, e, event, null, false, now);
        }
        return MessageRef.of("hooks.gateRefused", "reason", code);
    }

    private void recordEdit(String c, long e, String now, String actor) {
        String editEvent = actorEvent(PHASE_EDIT_EVENT, actor);
        Long edit = store.lastObservationId(c, e, editEvent, Long.MAX_VALUE);
        Long check = store.lastObservationId(c, e, actorEvent(PHASE_CHECK_EVENT, actor), Long.MAX_VALUE);
        if (edit != null && edit > (check == null ? 0 : check)) {
            return;
        }
        store.recordObservation(c, e, editEvent, null, true, now);
    }

    private long windowStart(String c, long e) {
        return Math.max(lastId(c, e, PHASE_CHECK_EVENT), lastId(c, e, PHASE_START_EVENT));
    }

    private boolean locked(String c, long e) {
        return lastId(c, e, PHASE_DENY_EVENT) > lastId(c, e, PHASE_START_EVENT);
    }

    private long lastId(String c, long e, String event) {
        Long id = store.lastObservationId(c, e, event, Long.MAX_VALUE);
        return id == null ? 0 : id;
    }

    /** Whose roots {@link #workflowActivity} looks at: every bound root, or those of one actor ({@code actor} null for the session's own, otherwise a subagent). */
    private record Owner(boolean any, String actor) {
        static final Owner ANY = new Owner(true, null);

        static Owner of(String actor) {
            return new Owner(false, actor);
        }
    }

    private record ActiveRun(String rootId, String runId, RootDoc root, RunDoc receipt) {
    }

    private List<ActiveRun> workflowActivity(Task task, Owner owner) {
        List<ActiveRun> active = new ArrayList<>();
        for (String rootId : store.boundRoots(task.taskCorrelation(), task.currentEpoch())) {
            WorkflowStore.Snapshot snapshot = workflows.getConvergenceSnapshot(rootId);
            if (snapshot == null) {
                continue;
            }
            RootDoc root = mapper.readValue(snapshot.rootJson(), RootDoc.class);
            if (!root.state().isActive()) {
                continue;
            }
            if (!owner.any()) {
                boolean other = owner.actor() == null ? store.hasObservation(task.taskCorrelation(), task.currentEpoch(), PHASE_CHILD_ROOT_EVENT + rootId)
                        : !store.hasObservation(task.taskCorrelation(), task.currentEpoch(), PHASE_ROOT_ACTOR_EVENT + hashOpaque("actor", owner.actor()) + ":" + rootId);
                if (other) {
                    continue;
                }
            }
            List<String> runs = snapshot.workflowRunIds();
            String runId = runs.isEmpty() ? null : runs.getLast();
            String receiptJson = runId == null ? null : workflows.getRun(runId);
            if (receiptJson == null) {
                continue;
            }
            RunDoc receipt = mapper.readValue(receiptJson, RunDoc.class);
            if (!receipt.state().isTerminal()) {
                active.add(new ActiveRun(rootId, runId, root, receipt));
            }
        }
        return active;
    }

    /** A running instance of this request needs an explicit disposition, even with no stages or after every stage passed. */
    @Transactional
    public List<MessageRef> phaseCheck(String rawSessionId, boolean quiet, String actor) {
        Task task = store.readTask(correlate(rawSessionId));
        if (task == null) {
            return null;
        }
        String c = task.taskCorrelation();
        long e = task.currentEpoch();
        String checkEvent = actorEvent(PHASE_CHECK_EVENT, actor);
        long lastCheck = lastId(c, e, checkEvent);
        long edit = lastId(c, e, actorEvent(PHASE_EDIT_EVENT, actor));
        List<String> failures = new ArrayList<>();
        if (actor == null) {
            failures.addAll(store.observationEventsAfter(c, e, START_FAILED_PREFIX, lastCheck));
            failures.addAll(store.observationEventsAfter(c, e, GATE_UNAVAILABLE_PREFIX, lastCheck));
        }
        boolean editWindow = edit != 0 && edit > lastCheck;
        Attribution taken = attributionOf(task, actor);
        List<ActiveRun> activity = null;
        try {
            activity = workflowActivity(task, Owner.of(actor));
        } catch (DataAccessException | JacksonException | IllegalStateException failure) {
            LOG.warn("The phase check could not read the session's workflows", failure);
            failures.add(GATE_UNAVAILABLE_PREFIX + failureCode(failure));
        }
        int pendingCount = 0;
        if (activity != null) {
            for (ActiveRun run : activity) {
                if (run.receipt().state() != RunState.RUNNING || run.root().state() != RootState.OPEN
                        || kindOf(c, e, run.rootId()).closing() == WorkflowKind.Closing.TURN_END
                        || !taken.instances().containsKey(run.rootId())) {
                    continue;
                }
                pendingCount++;
            }
        }
        if (!editWindow && pendingCount == 0 && failures.isEmpty()) {
            return null;
        }
        // What a subagent wrote that no running workflow's writeTargets cover, since its last check: a fact the gate saw, told at its end.
        String outsidePrefix = actor == null ? null : outsideWritePrefix(actor);
        List<String> outsideWrites = outsidePrefix == null ? List.of()
                : store.observationEventsAfter(c, e, outsidePrefix, lastCheck).stream().map(event -> event.substring(outsidePrefix.length())).toList();
        store.recordObservation(c, e, checkEvent, null, pendingCount == 0 && failures.isEmpty() && outsideWrites.isEmpty(), now());
        boolean unchecked = failures.stream().anyMatch(code -> code.startsWith(GATE_UNAVAILABLE_PREFIX)) || !store.observationEventsAfter(c, e, START_FAILURE_PASS_EVENT, lastCheck).isEmpty();
        List<MessageRef> lines = new ArrayList<>();
        if (!failures.isEmpty()) {
            String codes = String.join(", ", new LinkedHashSet<>(failures));
            lines.add(MessageRef.of(unchecked ? "hooks.phaseCheck.unavailable" : "hooks.phaseCheck.startFailed", "codes", codes));
        }
        if (!outsideWrites.isEmpty()) {
            lines.add(MessageRef.of("hooks.phaseCheck.outsideScope", "paths", String.join(", ", outsideWrites)));
        }
        if (pendingCount > 0) {
            lines.add(MessageRef.of("hooks.phaseCheck.summary", "count", pendingCount));
        }
        // Repeated Stop must not bypass an undisposed run. Explicit PAUSED/user gates and host cancellation are exits, not fabricated completion.
        return lines.isEmpty() || quiet && pendingCount == 0 ? null : lines;
    }

    // ------------------------------------------------------------------------------------------------------------- internals

    /**
     * The session's task, made by this transaction when the session has none, with its epoch moved on when every root bound to it has settled (one running root keeps all of them
     * bound). The move is {@link ContinuityStore#rotateEpoch}'s statement, whose {@code WHERE} names the epoch and the root,
     * so of the requests that find the roots settled together one moves the epoch and the others read the task as the winner left it. No lock is taken: the settled test reads the
     * workflow's rows, and a root that turns active between that read and the statement keeps the epoch only if the task's root changed, which is the statement's condition;
     * the one case it does not cover is the same root continued in that instant, whose start then finds the epoch moved and fails with CONTINUITY_EPOCH_CHANGED.
     */
    private Task session(String rawSessionId) {
        String correlation = correlate(rawSessionId);
        return settled(correlation, store.ensureTask(correlation, now()));
    }

    /**
     * The session's task as it is committed, or empty when the session has none: nothing is made. For a use case whose refusal tells the caller the state token it must name (a stale
     * {@code checkpoint_context} or {@code purge_direct_context}): the refusal rolls its transaction back, so a task made inside it would be gone with the token that refusal has
     * just shown, and the next call would make another with another random token. A session with no task has no state token a save or a purge could name, so these use cases refuse
     * it without making one ({@link #noStateToName}); the task is made by the session's start, or by a use case that does not refuse (inspect_context, load_context).
     */
    private Optional<Task> existingSession(String rawSessionId) {
        String correlation = correlate(rawSessionId);
        return Optional.ofNullable(store.readTask(correlation)).map(task -> settled(correlation, task));
    }

    /** The refusal of a save or a purge by a session that has no task: the state moved on (there is none), with no {@code actualStateToken} because none exists to be shown. */
    private static ConflictException noStateToName(ConflictCode code, String expectedStateToken) {
        return new ConflictException(code, Map.of(), SennardException.args("reasonCode", STATE_STALE, "expectedStateToken", expectedStateToken));
    }

    private Task settled(String correlation, Task task) {
        if (task.rootId() == null) {
            return task;
        }
        long epoch = task.currentEpoch();
        return store.rotateEpoch(correlation, now(), new Expected(epoch, task.rootId(),
                () -> store.boundRoots(correlation, epoch).stream().allMatch(this::rootSettled), true));
    }

    private boolean rootSettled(String rootId) {
        WorkflowStore.Snapshot snapshot = workflows.getConvergenceSnapshot(rootId);
        if (snapshot == null) {
            return false;
        }
        boolean terminal = !mapper.readValue(snapshot.rootJson(), RootStateDoc.class).state().isActive();
        if (!terminal && snapshot.workflowRunIds().isEmpty()) {
            return false;
        }
        for (String runId : snapshot.workflowRunIds()) {
            String receipt = workflows.getRun(runId);
            if (receipt == null || !mapper.readValue(receipt, RunStateDoc.class).state().isTerminal()) {
                return false;
            }
        }
        Map<String, OutcomeDoc> outcomes = new LinkedHashMap<>();
        snapshot.outcomes().forEach(text -> {
            OutcomeDoc outcome = mapper.readValue(text, OutcomeDoc.class);
            outcomes.put(outcome.leaseId(), outcome);
        });
        for (String text : snapshot.leases()) {
            LeaseDoc lease = mapper.readValue(text, LeaseDoc.class);
            if (lease.state() != LeaseState.CONSUMED) {
                continue;
            }
            OutcomeDoc outcome = outcomes.get(lease.leaseId());
            if (outcome == null || !rootId.equals(outcome.rootId()) || !outcome.epoch().equals(lease.epoch()) || !outcome.ordinal().equals(lease.ordinal())) {
                return false;
            }
        }
        return true;
    }

    private List<MessageRef> checkpointCue(Task task, boolean missingOnly) {
        if (task.suppressed()) {
            return null;
        }
        Snapshot snapshot = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        boolean saved = snapshot != null && snapshot.memoryJson() != null;
        if (!saved && store.getTombstone(task.taskCorrelation(), task.currentEpoch()) != null) {
            return null;
        }
        if (missingOnly && saved) {
            return null;
        }
        return List.of(MessageRef.of("hooks.checkpointCue.status", "state", (missingOnly ? CueState.MISSING : saved ? CueState.SAVED : CueState.NONE).wire()), MessageRef.of("hooks.checkpointCue.skills"),
                MessageRef.of("hooks.checkpointCue.save"), MessageRef.of("hooks.checkpointCue.mark"), MessageRef.of("hooks.checkpointCue.resume"));
    }

    private List<MessageRef> restoreCurrent(Task task) {
        if (task.suppressed()) {
            return null;
        }
        Snapshot snapshot = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        if (snapshot == null || snapshot.memoryJson() == null) {
            return null;
        }
        verify(snapshot);
        return List.of(MessageRef.of("continuity.restore.current", "stateToken", snapshot.stateToken(), "body", SessionText.quoted(snapshot.memoryJson())));
    }

    private Candidate candidateFor(String correlation, long expectedEpoch) {
        Task task = store.readTask(correlation);
        if (task == null || task.currentEpoch() != expectedEpoch) {
            return rejected(ReasonCode.NO_TASK_BINDING);
        }
        if (task.suppressed()) {
            return rejected(ReasonCode.RESTORE_SUPPRESSED);
        }
        Summary summary = summary(task);
        if (summary == null) {
            return rejected(ReasonCode.NO_RESTORE_CANDIDATE);
        }
        return new Candidate(SCHEMA_VERSION, Decision.DEFER, List.of(ReasonCode.EXPLICIT_LOAD_REQUIRED), summary, restoreToken(task, summary));
    }

    private static Candidate rejected(ReasonCode reason) {
        return new Candidate(SCHEMA_VERSION, Decision.REJECT, List.of(reason), null, null);
    }

    /** The token that lets load_context take the summary's record: its source and digest, bound to the task and epoch, for an hour. */
    private String restoreToken(Task task, Summary summary) {
        long expiresAt = clock.instant().getEpochSecond() + CANDIDATE_TOKEN_TTL_SECONDS;
        RestoreToken payload = switch (summary) {
            case DirectSummary direct -> new RestoreToken(TOKEN_VERSION, task.taskCorrelation(), task.currentEpoch(), ContextSource.DIRECT.name(), direct.stateToken(), direct.memoryDigest(), null,
                    expiresAt, null, null);
            case WorkflowSummary workflow -> new RestoreToken(TOKEN_VERSION, task.taskCorrelation(), task.currentEpoch(), ContextSource.WORKFLOW.name(), null, workflow.snapshotDigest(),
                    workflow.revision(), expiresAt, null, null);
        };
        return sign(payload);
    }

    /** A summary and, for a workflow's, the card it was made from. */
    private record Restorable(Summary summary, WorkflowCard card) {
    }

    private Summary summary(Task task) {
        Restorable restorable = restorable(task);
        return restorable == null ? null : restorable.summary();
    }

    /** The session's own record comes first; a bound root without one is restored from the workflow projection. */
    private Restorable restorable(Task task) {
        Snapshot snapshot = store.getCurrent(task.taskCorrelation(), task.currentEpoch());
        if (snapshot != null && snapshot.memoryJson() != null) {
            verify(snapshot);
            MemoryStatus status = mapper.readValue(snapshot.memoryJson(), MemoryStatusDoc.class).status();
            return new Restorable(new DirectSummary(SCHEMA_VERSION, ContextSource.DIRECT, task.taskCorrelation(), task.currentEpoch(), snapshot.stateToken(), status == null ? "" : status.name(),
                    snapshot.memoryDigest(), snapshot.updatedAt()), null);
        }
        WorkflowCard card = task.rootId() == null ? null : workflowProjection(task);
        if (card == null) {
            return null;
        }
        return new Restorable(new WorkflowSummary(SCHEMA_VERSION, ContextSource.WORKFLOW, task.taskCorrelation(), task.currentEpoch(), card.revision(),
                card.rootState() == null ? "" : card.rootState().name(), card.snapshotDigest(), card.updatedAt()), card);
    }

    /** A bounded card of the workflow a task is bound to; {@code verified} is what the server itself hashed, never a provider's claim. */
    private WorkflowCard workflowProjection(Task task) {
        WorkflowStore.Snapshot snapshot = workflows.getConvergenceSnapshot(task.rootId());
        if (snapshot == null) {
            return null;
        }
        RootDoc root = mapper.readValue(snapshot.rootJson(), RootDoc.class);
        String latestRunId = snapshot.workflowRunIds().isEmpty() ? null : snapshot.workflowRunIds().getLast();
        String receiptJson = latestRunId == null ? null : workflows.getRun(latestRunId);
        RunDoc receipt = receiptJson == null ? null : mapper.readValue(receiptJson, RunDoc.class);
        return card(mapper, task.taskCorrelation(), task.currentEpoch(), root, receipt);
    }

    /** The card of a root and the receipt of its latest run (null when it has none); its digest is that of the canonical form of the card without it. */
    static WorkflowCard card(JsonMapper mapper, String taskCorrelation, long epoch, RootDoc root, RunDoc receipt) {
        TaskEnvelopeDoc envelope = root.taskEnvelope();
        List<ContinuityTools.EvidenceRef> evidence = new ArrayList<>(WORKFLOW_CARD_EVIDENCE_LIMIT);
        int total = 0;
        if (receipt != null) {
            for (StageResultDoc result : receipt.stageResults()) {
                for (ArtifactDoc artifact : result.output().artifacts()) {
                    if (total++ < WORKFLOW_CARD_EVIDENCE_LIMIT) {
                        evidence.add(new ContinuityTools.EvidenceRef(boundedText(artifact.artifactId(), 80), boundedText(artifact.attachmentId(), 160), artifact.digest(), hashed(result, artifact)));
                    }
                }
            }
        }
        WorkflowCard draft = new WorkflowCard(SCHEMA_VERSION, ContextSource.WORKFLOW, taskCorrelation, epoch, root.revision(), boundedText(root.rootId(), 160), root.state(),
                boundedText(envelope.taskId(), 160), boundedText(envelope.objective(), 800), bounded(envelope.scope().included(), 4, 120), bounded(envelope.scope().excluded(), 4, 120),
                bounded(envelope.acceptanceCriteria(), 4, 240), bounded(envelope.constraints(), 4, 240),
                new Authorization(bounded(envelope.authorization().allowedActions(), 4, 120), bounded(envelope.authorization().prohibitedActions(), 4, 120),
                        bounded(envelope.authorization().approvalRequired(), 4, 120)),
                receipt == null ? null : receipt.state(), receipt == null ? null : receipt.plan().currentStageId(), receipt == null ? null : receipt.plan().nextStageId(),
                receipt == null ? List.of() : bounded(receipt.blockers(), 4, 200), receipt == null ? List.of() : bounded(receipt.unresolved(), 4, 200), evidence,
                Math.max(0, total - WORKFLOW_CARD_EVIDENCE_LIMIT), root.updatedAt(), null);
        return draft.withDigest(CanonicalJson.digest(mapper.valueToTree(draft)));
    }

    /** The attachment this artifact names has the digest the stage recorded. */
    private static boolean hashed(StageResultDoc result, ArtifactDoc artifact) {
        for (CheckDoc check : result.artifactChecks()) {
            if (lookup(CheckSource.class, check.source()) == CheckSource.ARTIFACT && lookup(ArtifactCheck.class, check.check()) == ArtifactCheck.SHA256_MATCHED
                    && artifact.artifactId().equals(check.artifactId()) && artifact.attachmentId().equals(check.attachmentId())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> bounded(List<String> values, int maxItems, int maxLength) {
        int size = values.size();
        List<String> result = new ArrayList<>(Math.min(size, maxItems));
        for (int index = 0; index < Math.min(size, maxItems); index++) {
            String omitted = size > maxItems && index == maxItems - 1 ? " [+" + (size - maxItems) + " omitted]" : "";
            result.add(boundedText(orEmpty(values.get(index)), maxLength - omitted.length()) + omitted);
        }
        return result;
    }

    static String boundedText(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    /** The stored form of a body: the byte limit covers its JSON keys and evidence references too. */
    private String serialized(JsonNode memory) {
        String json = CanonicalJson.of(memory);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > CURRENT_MEMORY_MAX_BYTES) {
            throw new InvalidInputException(InputCode.CONTINUITY_MEMORY_TOO_LARGE, SennardException.args("bytes", bytes, "limit", CURRENT_MEMORY_MAX_BYTES),
                    SennardException.args("actualBytes", bytes, "maxBytes", CURRENT_MEMORY_MAX_BYTES));
        }
        return json;
    }

    private void verify(Snapshot snapshot) {
        if (snapshot.memoryJson() == null) {
            if (snapshot.memoryDigest() == null) {
                return;
            }
        } else {
            byte[] bytes = snapshot.memoryJson().getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= CURRENT_MEMORY_MAX_BYTES && ("sha256:" + Digests.sha256Hex(snapshot.memoryJson())).equals(snapshot.memoryDigest())) {
                return;
            }
        }
        throw new IntegrityException(IntegrityCode.SNAPSHOT_DIGEST_INVALID);
    }

    private SnapshotView snapshotView(Snapshot snapshot) {
        return snapshotView(snapshot, snapshot.memoryJson() == null ? null : mapper.readValue(snapshot.memoryJson(), Memory.class));
    }

    /** {@code memory} is the parsed {@code snapshot.memoryJson()}, null when the snapshot holds none. */
    private static SnapshotView snapshotView(Snapshot snapshot, Memory memory) {
        return new SnapshotView(SCHEMA_VERSION, ContextSource.DIRECT, snapshot.taskCorrelation(), snapshot.epoch(), snapshot.stateToken(), memory, memory == null ? null : snapshot.memoryDigest(),
                snapshot.createdAt(), snapshot.updatedAt());
    }

    /**
     * A document the store kept, or null when it is no document of that shape (a damaged row), which the caller answers as an unavailable replay. Jackson has no
     * way to ask whether a text binds without binding it, so the one place that catches its refusal is here, and it says so in the log.
     */
    static <T> T storedDocument(JsonMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException damaged) {
            LOG.warn("A stored {} could not be read", type.getSimpleName(), damaged);
            return null;
        }
    }

    /** The constant of {@code type} a stored or signed text names, or null for a text that names none: no exception, no state held. */
    private static <E extends Enum<E>> E lookup(Class<E> type, String name) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    static ContextSource sourceOf(String name) {
        return lookup(ContextSource.class, name);
    }

    private String actorEvent(String event, String actor) {
        return actor == null ? event : event + "@" + hashOpaque("actor", actor);
    }

    private String situationHeldEvent(String actorId, String stateToken) {
        return SITUATION_HELD_EVENT + hashOpaque("actor", actorId) + ":" + stateToken;
    }

    private static String failureCode(RuntimeException failure) {
        return failure instanceof StoreException store ? store.code().name() : "UNEXPECTED_FAILURE";
    }

    private String now() {
        return StoreValues.iso(clock.millis());
    }

    private SecretValue secret() {
        return store.signingSecret();
    }

    private String correlate(String rawSessionId) {
        return store.opaqueDigest("session", rawSessionId);
    }

    private String hashOpaque(String kind, String value) {
        return store.opaqueDigest(kind, value);
    }

    private static String encode(byte[] bytes) {
        return BASE64_URL.encodeToString(bytes);
    }

    private byte[] hmac(String value) {
        return Digests.hmacSha256(secret(), value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(RestoreToken payload) {
        JsonNode tree = mapper.valueToTree(payload);
        String body = encode(CanonicalJson.of(tree).getBytes(StandardCharsets.UTF_8));
        return body + "." + encode(hmac(body));
    }

    /** The token is {@code body.signature}, each base64url. The signature is checked before the body is read: nothing unsigned is parsed. */
    private VerifiedToken verifyCandidate(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            throw malformed();
        }
        String body = token.substring(0, dot);
        byte[] signature;
        byte[] payload;
        try {
            signature = BASE64_URL_DECODER.decode(token.substring(dot + 1));
            payload = BASE64_URL_DECODER.decode(body);
        } catch (IllegalArgumentException notBase64) {
            throw malformed(notBase64);
        }
        if (!MessageDigest.isEqual(hmac(body), signature)) {
            throw new BindingException(BindingCode.CONTINUITY_TOKEN_SIGNATURE_INVALID);
        }
        return checkedToken(mapper, payload, clock.instant().getEpochSecond());
    }

    /** The payload of a token whose signature held: its shape must be that of a token the server issues, and it must not be past {@code expiresAt}. */
    static VerifiedToken checkedToken(JsonMapper mapper, byte[] payload, long nowEpochSecond) {
        RestoreToken token;
        try {
            token = mapper.readValue(payload, RestoreToken.class);
        } catch (JacksonException notToken) {
            throw malformed(notToken);
        }
        if (token == null) {
            throw malformed();
        }
        ContextSource source = sourceOf(token.source());
        boolean valid = token.version() == TOKEN_VERSION && source != null && token.correlation() != null && token.epoch() != null && token.expiresAt() != null && token.digest() != null
                && DIGEST.matcher(token.digest()).matches()
                && (source == ContextSource.DIRECT ? token.stateToken() != null && !token.stateToken().isEmpty() : token.revision() != null)
                && (token.forkCorrelation() == null || token.forkEpoch() != null);
        if (!valid) {
            throw new BindingException(BindingCode.CONTINUITY_TOKEN_INVALID);
        }
        if (token.expiresAt() < nowEpochSecond) {
            throw new BindingException(BindingCode.CONTINUITY_TOKEN_EXPIRED);
        }
        return new VerifiedToken(token, source);
    }

    private static BindingException malformed() {
        return new BindingException(BindingCode.CONTINUITY_TOKEN_MALFORMED);
    }

    /** The same refusal for a token that did not decode or bind: the failure stays as its cause. */
    private static BindingException malformed(Throwable cause) {
        return new BindingException(BindingCode.CONTINUITY_TOKEN_MALFORMED, Map.of(), Map.of(), cause);
    }
}
