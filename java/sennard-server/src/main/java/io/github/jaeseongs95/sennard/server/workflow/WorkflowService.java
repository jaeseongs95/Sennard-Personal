// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Validator;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.server.approval.ApprovalSourceKind;
import io.github.jaeseongs95.sennard.server.approval.RootAction;
import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.approval.RootReleaseService;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.ScanBasis;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.ActiveRootRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanKey;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanOrigin;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.GuardedBinding;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.IdentityRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.LeaseRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.Outcome;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.ReviewRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.RootInsertionPlan;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.RootRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.RunRow;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.Snapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The convergence and workflow rules behind the thirteen workflow tools. Every public method is one transaction, so a failure at any step leaves nothing behind (the exceptions are named
 * on their method). Two callers that meet are decided by the database, not by a lock: a run changes by the CAS on its revision, a root by the CAS on its revision, a lease is consumed
 * by one {@code UPDATE ... WHERE state = ISSUED}, an observation is claimed once by its primary key, and the loser of each is answered with the stale or conflict code of the call and is
 * not run again. No lock is taken. Two roots of the same write surface that are opened at the same moment are decided by the primary key of {@code convergence_active_surfaces} (a row for each active root): the
 * second one ends in the store's translated unique violation. The one limit this accepts: two roots that only overlap (a folder and a file of it) and are opened at the same moment may
 * both open, because each one's check of the active roots does not see the other's uncommitted row.
 *
 * <p>No transaction of this service reads a file or an attachment's bytes. What a use case judges from them (the output file and the frozen request a stage result names by attachment id, the
 * check of a review bundle over the result's attachments, the attachments an audit was recorded on, the schemas a plan froze, the physical locations of a root's write surface) is asked for by a read-only {@code plan...Reads} method of the use case, which reads rows and answers {@link ReadPlan}; the
 * entry ({@link WorkflowTools}) answers it with {@link WorkflowFileReader} outside any transaction; the use case then takes {@link FileReads}, made for the revision it plans on. A run that
 * moved between the plan and the use case is refused as stale by the same revision condition the use case writes under, and is not read again.
 */
@Service
public class WorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);
    private static final String PLAN_SIGNING_KEY = "plan-signing-key";
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };

    private final WorkflowStore store;
    private final PlanBuilder planner;
    private final ContractValidator validator;
    private final StageGates gates;
    private final ExecutionAssurance assurance;
    private final WorkflowContinuity continuity;
    private final RootOwnerPresence presence;
    private final BoardService board;
    private final JsonMapper mapper;
    private final ObjectReader receiptReader;
    private final DocumentDigests digests;
    private final Validator inputRules;
    private final Messages messages;
    private final Clock clock;
    private final SecureRandom random;
    private final ConvergenceLimitSource limitSource;
    private final ChangeJournalService journal;
    private final ChangeJournalScope journalScopes;
    /** The receipts of the write tools: a request that comes again with the same operationKey is answered with the first success (see {@link WorkflowReceipts}). */
    private final WorkflowReceipts receipts;
    private final EvidenceAttachments evidenceAttachments;
    private final PlaceholderTexts placeholders;

    public WorkflowService(WorkflowStore store, PlanBuilder planner, ContractValidator validator, StageGates gates, ExecutionAssurance assurance,
            WorkflowContinuity continuity, RootOwnerPresence presence, BoardService board, JsonMapper mapper, DocumentDigests digests,
            Validator inputRules, Messages messages, Clock clock, SecureRandom random, ConvergenceLimitSource limitSource,
            ChangeJournalService journal, ChangeJournalScope journalScopes, WorkflowReceipts receipts, EvidenceAttachments evidenceAttachments, PlaceholderTexts placeholders) {
        this.placeholders = placeholders;
        this.receipts = receipts;
        this.evidenceAttachments = evidenceAttachments;
        this.limitSource = limitSource;
        this.journal = journal;
        this.journalScopes = journalScopes;
        this.clock = clock;
        this.digests = digests;
        this.inputRules = inputRules;
        this.random = random;
        this.store = store;
        this.planner = planner;
        this.validator = validator;
        this.gates = gates;
        this.assurance = assurance;
        this.continuity = continuity;
        this.presence = presence;
        this.board = board;
        this.mapper = mapper;
        this.receiptReader = mapper.readerFor(WorkflowReceipt.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.messages = messages;
    }

    // ---- plan_workflow

    /**
     * @param providers the skill registry as the entry read it before this transaction opened: a file is never read inside a transaction of this service, so what a use case needs
     *            from files is read by the entry ({@link WorkflowTools}) and passed in
     */
    @Transactional
    public WorkflowPlan planWorkflow(JsonNode rawTask, WorkflowCall call, List<SkillProvider> providers, OperationKey operation) {
        return receipts.once(operation, WorkflowPlan.class, () -> plan(planRequest(rawTask), call, providers));
    }

    /** The caller's plan input: checked as the caller wrote it, then bound as the wrapper it is, or as the bare task envelope it stands for. */
    private PlanWorkflowRequest planRequest(JsonNode raw) {
        validator.planWorkflowRequest(raw);
        String name = "plan-workflow-request.v1.schema.json";
        return raw.has("taskEnvelope") ? validator.bind(name, raw, PlanWorkflowRequest.class) : PlanWorkflowRequest.bare(validator.bind(name, raw, TaskEnvelope.class));
    }

    /** The plan of a request. The providers are the registry's as the entry read them. */
    private WorkflowPlan plan(PlanWorkflowRequest request, WorkflowCall call, List<SkillProvider> providers) {
        TaskEnvelope task = request.taskEnvelope();
        EvaluationAuditPurpose purpose = request.evaluationAuditPurpose();
        if (task.requiredCapabilities().contains(KnownCapability.EVALUATION_VALIDITY_AUDIT.wire()) && purpose == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.evaluationValidityPlanningRequiresStructured"));
        }
        ExecutionContext bootstrap = null;
        if (task.orchestration().requested()) {
            ExecutionAssurance.Binding binding = ExecutionAssurance.Binding.bootstrap(task.taskId());
            bootstrap = assurance.observeTrusted(call, binding, ExecutionAssurance.BOOTSTRAP);
            assurance.assertTrusted(ExecutionRequirement.SEMANTIC, bootstrap, ExecutionAssurance.BOOTSTRAP, binding);
        }
        PlanBuilder.validateWorkUnitGraph(task);
        WorkflowPlan plan = planner.build(task, providers, purpose, bootstrap, request.requiredAuditPhase(), signingKey());
        validator.workflowPlan(plan);
        if (bootstrap != null) {
            consumeObservation(bootstrap, ExecutionAssurance.BOOTSTRAP);
        }
        return plan;
    }

    /** The server's start: makes the key the plans are signed with and the run number counter. Each exists once in the whole server, so no request makes it. */
    @Transactional
    public void prepareSigningKey() {
        String now = clock.instant().toString();
        store.createSecretIfAbsent(PLAN_SIGNING_KEY, newSigningKey(), now);
        store.createRunSequenceIfAbsent(now);
    }

    private byte[] signingKey() {
        byte[] encoded = store.secret(PLAN_SIGNING_KEY).revealBytes();
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(encoded);
        } finally {
            SecretValue.clear(encoded);
        }
        if (key.length != 32) {
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.service.storedPlanSigningKeyInvalid"), details("reason", "PLAN_SIGNING_KEY_INVALID"));
        }
        return key;
    }

    private String newSigningKey() {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private void consumeObservation(ExecutionContext context, ExecutionAssurance.Subject subject) {
        String observationId = context.observationId();
        if (observationId == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.attestationClaimFieldsMissing", subject.params()), details("reasonCode", "EXECUTION_OBSERVATION_ID_MISSING", "observationId", null));
        }
        try {
            if (store.claimExecutionObservation(observationId, clock.instant().toString())) {
                return;
            }
        } catch (StoreException together) {
            // Of two callers that claim one observation together, the second one's INSERT meets the first one's primary key: the observation is consumed, and this call fails with it.
            if (together.code() != StoreCode.STORE_UNIQUE_VIOLATION) {
                throw together;
            }
            throw consumed(subject, observationId, together);
        }
        throw consumed(subject, observationId, null);
    }

    private static WorkflowFailure consumed(ExecutionAssurance.Subject subject, String observationId, Throwable cause) {
        return new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.attestationConsumed", subject.params()), details("reasonCode", "EXECUTION_OBSERVATION_CONSUMED", "observationId", observationId), cause);
    }

    // ---- open_convergence_root and release_convergence_root, with the root approval

    /** What a root operation decided: done, or stored for the user's decision on the admin page. */
    public sealed interface RootOutcome<T> {
        record Done<T>(T result) implements RootOutcome<T> {
        }

        record ApprovalRequired<T>(RootApprovalRecords.Pending pending, String scopeDigest) implements RootOutcome<T> {
        }

        /** Refused after the use case wrote something that has to commit (the identities it observed): the failure is a value here, and the entry answers it after the commit. */
        record Refused<T>(WorkflowFailure failure) implements RootOutcome<T> {
        }
    }

    /** A root operation's request, the root it targets, the scope the user decides, and the digest that scope is reused by. */
    private record RootContext(ConvergenceRoot root, RootRequest request, RootApprovalScope scope, String scopeDigest, OpenConvergenceRootRequest replacement) {
    }

    /**
     * Opens a root; with a parent root the replacement needs the user's decision. A conflict with an active root is a value ({@link RootOutcome.Refused}), so the transaction commits what
     * the store observed about the existing roots' identities and the entry answers the conflict after the commit. A failure thrown here rolls the whole transaction back, the abandoning
     * of a parent root with it: among them {@code ROOT_SURFACE_HELD} (the primary key of {@code convergence_active_surfaces} for a root opened at the same moment) and the failure of
     * binding the session's task to the new root ({@link WorkflowContinuity#bindOpenedRoot}), which is not caught, so no root is left that no task holds.
     */
    @Transactional
    public RootOutcome<ConvergenceRoot> openConvergenceRoot(JsonNode raw, WorkflowCall call, FileReads reads, OperationKey operation) {
        Optional<ConvergenceRoot> stored = receipts.replay(operation, ConvergenceRoot.class);
        if (stored.isPresent()) {
            return new RootOutcome.Done<>(stored.get());
        }
        RootOutcome<ConvergenceRoot> outcome = openRoot(raw, call, reads);
        // Only a root that was opened has a receipt: a request that waits for the user's decision changed nothing a repeat could double, and it is judged again.
        if (outcome instanceof RootOutcome.Done<ConvergenceRoot> done) {
            receipts.record(operation, done.result());
        }
        return outcome;
    }

    private RootOutcome<ConvergenceRoot> openRoot(JsonNode raw, WorkflowCall call, FileReads reads) {
        OpenConvergenceRootRequest sent = validator.read("open-convergence-root-request.v1.schema.json", raw, OpenConvergenceRootRequest.class);
        // The limits of a new root are the user's setting as it is now, whatever the caller wrote: the server, not the model, decides how many attempts a root may take.
        OpenConvergenceRootRequest request = new OpenConvergenceRootRequest(sent.schemaVersion(), sent.parentRootId(), sent.taskEnvelope(), sent.frame().withLimits(limitSource.current()), sent.userApprovalRefs());
        PlanBuilder.validateWorkUnitGraph(request.taskEnvelope());
        assertConvergenceFrame(request.frame());
        assertMcpBacked(request.taskEnvelope());
        RootApprovalRecords.Decision approval = null;
        if (request.parentRootId() != null) {
            RootContext context = replacementContext(request, call);
            RootApprovalRecords.Decision decided = lookupRootApproval(context, request.userApprovalRefs());
            if (decided == null) {
                return new RootOutcome.ApprovalRequired<>(savePending(context, call), context.scopeDigest());
            }
            approval = decided;
        }
        return switch (insertRoot(request, approval, RootConflictTerms.Entry.OPEN, call, reads)) {
            case Inserted.Opened opened -> {
                continuity.bindOpenedRoot(call, opened.root().rootId(), isChild(call));
                yield new RootOutcome.Done<>(opened.root());
            }
            case Inserted.Blocked blocked -> new RootOutcome.Refused<>(blocked.conflict());
        };
    }

    /** What inserting a root came to: the root, or the conflict with an active root (the identities the check observed are saved either way). */
    private sealed interface Inserted {
        record Opened(ConvergenceRoot root) implements Inserted {
        }

        record Blocked(WorkflowFailure conflict) implements Inserted {
        }
    }

    /**
     * What opening a root resolves from the file system, as the entry reads it before the opening's transaction: where the new root's write surface lives, and where the active roots'
     * do whose identity was not stored for the same inputs. Reads the rows only, and refuses what the opening refuses first (the input that is not the contract's).
     */
    @Transactional(readOnly = true)
    public ReadPlan planOpenRootReads(JsonNode raw) {
        OpenConvergenceRootRequest sent = validator.read("open-convergence-root-request.v1.schema.json", raw, OpenConvergenceRootRequest.class);
        return new ReadPlan(-1, rootIdentityRequests(sent.taskEnvelope(), sent.frame()));
    }

    /**
     * What a start of a new root resolves from the file system ({@link #planOpenRootReads}); a start that continues a root opens none, but the root may have to take its write surface
     * back from the others ({@link #planActiveRootReads}).
     */
    @Transactional(readOnly = true)
    public ReadPlan planStartReads(JsonNode raw, WorkflowCall call) {
        MinimalStartRequest request = minimalStartRequest(raw);
        if (request.rootId() != null) {
            return planActiveRootReads();
        }
        Optional<String> workspace = startWorkspace(request, call);
        if (workspace.isEmpty()) {
            return ReadPlan.NONE;
        }
        Fresh fresh = minimalTask(request, workspace.get(), 0);
        return new ReadPlan(-1, rootIdentityRequests(fresh.task(), fresh.frame()));
    }

    /**
     * What a claim, a guarded start or the continuing of a root resolves from the file system: where the write surfaces of the active roots whose identity is not stored for the same inputs
     * physically are. The root that continues takes its surface back by them, in the same transaction, without reading a file.
     */
    @Transactional(readOnly = true)
    public ReadPlan planActiveRootReads() {
        return new ReadPlan(-1, activeRootRequests());
    }

    private List<ReadRequest> rootIdentityRequests(TaskEnvelope task, ConvergenceFrame frame) {
        List<ReadRequest> requests = new ArrayList<>();
        requests.add(new ReadRequest.HostId());
        requests.add(new ReadRequest.RootIdentity(frame.workspace().locator(), ConvergenceLogic.writeSurface(task, frame), false));
        requests.addAll(activeRootRequests());
        return requests;
    }

    private List<ReadRequest> activeRootRequests() {
        List<ReadRequest> requests = new ArrayList<>();
        for (ActiveRootRow row : store.readActiveRoots()) {
            ConvergenceRoot active = mapper.readValue(row.rootJson(), ConvergenceRoot.class);
            ConvergenceLogic.Stored stored = row.identityJson() == null ? null : ConvergenceLogic.storedIdentity(mapper, row.identityJson(), row.surfaceDigest());
            if (ConvergenceLogic.needsDerivation(digests, active, stored)) {
                String locator = active.frame().workspace().locator();
                List<String> entries = ConvergenceLogic.writeSurface(active);
                requests.add(new ReadRequest.RootIdentity(locator, entries, false));
                requests.add(new ReadRequest.RootIdentity(locator, entries, true));
            }
        }
        return requests;
    }

    private Inserted insertRoot(OpenConvergenceRootRequest request, RootApprovalRecords.Decision approval, RootConflictTerms.Entry path, WorkflowCall call, FileReads reads) {
        Instant now = clock.instant();
        ConvergenceDigests inputs = digests.convergence(request.taskEnvelope(), request.frame());
        ConvergenceRoot root = new ConvergenceRoot(ContractValidator.CONTRACT_VERSION, "root-" + UUID.randomUUID(), request.parentRootId(), 0, RootState.OPEN, 1, request.taskEnvelope(), request.frame(),
                inputs.taskDigest(), inputs.frameDigest(), inputs.workspaceDigest(), inputs.controlDigest(), inputs.targetDigest(), inputs.operationalDigest(),
                approval != null ? List.of(approval.approvalId()) : request.userApprovalRefs(), now, now, null, null);
        validator.convergenceRoot(root);
        String host = reads.hostId();
        ConvergenceLogic.Conflict conflict;
        try {
            conflict = store.insertConvergenceRoot(rootRow(root), root.parentRootId(), actives -> planInsertion(root, actives, host, reads, call));
        } catch (StoreException lost) {
            throw surfaceHeld(lost);
        }
        if (conflict != null) {
            return new Inserted.Blocked(rootConflict(ConvergenceLogic.conflictDetails(conflict), new Hold(call, reads, path)));
        }
        return new Inserted.Opened(root);
    }

    /**
     * The failure of a take of a write surface that ended in the store's ROOT_CONFLICT; anything else the store threw is returned as it is. The store answers every lost race of the take
     * with that code, the same set of failures that it answers a lost race of a lease with LEASE_CONFLICT ({@link #leaseRaceLost}): another root took the surface at the same moment (a
     * root opened with the same key, or the owner of an idle root started it before the give-up), the database was busy, or the take was the victim of a deadlock. The transaction is past
     * reading, so this answer names no root; the next call finds the root that holds the files in the check of the active roots and answers with its details. This call ends here: it is
     * not run again.
     */
    private static RuntimeException surfaceHeld(StoreException lost) {
        if (lost.code() != StoreCode.ROOT_CONFLICT) {
            return lost;
        }
        return new WorkflowFailure(WorkflowCode.ROOT_CONFLICT, msg("workflow.service.rootSurfaceHeld"), details("reason", "ROOT_SURFACE_HELD"), lost);
    }

    /** The active roots as a caller meets them: the ones that hold files against it, the idle ones it may take files from, and the identities derived on the way (saved by the store). */
    private record Judged(List<ConvergenceLogic.Identified> blocking, List<ConvergenceLogic.Identified> idle, List<IdentityRow> fresh, ConvergenceLogic.Identified candidate) {
    }

    /**
     * What a start or a claim is judged against: the active roots, except those nobody is running that another session holds. A root with no run in progress (none running or paused),
     * held by a session other than the caller's, does not keep the caller from the files it covers; its owner session stays bound to it (the root is still active for the owner, who
     * continues it or releases it with {@code release_convergence_root}), and a replaced parent is never left out. A root that waits for a review or for the user is never left out: it
     * holds its files until its gate is resolved. {@code candidateRootId} is the root that asks (when it exists already): it is neither blocking nor idle, it is returned as {@code candidate}.
     */
    private Judged judge(List<ActiveRootRow> rows, String candidateRootId, String parentRootId, FileReads reads, WorkflowCall call) {
        // Where the locations are is what the entry resolved before this transaction; a root that is active now and was not (or had another identity) then is refused as stale.
        ConvergenceLogic.IdentityResolver resolver = reads::identity;
        List<ConvergenceLogic.Identified> blocking = new ArrayList<>();
        List<ConvergenceLogic.Identified> idle = new ArrayList<>();
        List<IdentityRow> fresh = new ArrayList<>();
        ConvergenceLogic.Identified candidate = null;
        for (ActiveRootRow row : rows) {
            ConvergenceRoot activeRoot = mapper.readValue(row.rootJson(), ConvergenceRoot.class);
            ConvergenceLogic.Stored stored = row.identityJson() == null ? null : ConvergenceLogic.storedIdentity(mapper, row.identityJson(), row.surfaceDigest());
            ConvergenceLogic.Identified identified = ConvergenceLogic.activeRootIdentity(digests, activeRoot, stored, resolver);
            if (identified.fresh()) {
                fresh.add(new IdentityRow(row.rootId(), ConvergenceLogic.identityJson(mapper, identified.identity()), identified.surfaceDigest()));
            }
            if (row.rootId().equals(candidateRootId)) {
                candidate = identified;
            } else if (isIdleElsewhere(activeRoot, parentRootId, call)) {
                idle.add(identified);
            } else {
                blocking.add(identified);
            }
        }
        return new Judged(blocking, idle, fresh, candidate);
    }

    private RootInsertionPlan<ConvergenceLogic.Conflict> planInsertion(ConvergenceRoot root, List<ActiveRootRow> rows, String host, FileReads reads, WorkflowCall call) {
        Judged judged = judge(rows, null, root.parentRootId(), reads, call);
        ConvergenceLogic.Plan plan = ConvergenceLogic.planRootInsertion(digests, root, judged.blocking(), host, reads::identity);
        return new RootInsertionPlan<>(plan.conflict(), judged.fresh(),
                new IdentityRow(root.rootId(), ConvergenceLogic.identityJson(mapper, plan.identity()), plan.surfaceDigest()), plan.surfaceKey(), yielding(root, plan.identity(), plan.surfaceKey(), judged));
    }

    /** The idle roots that give their rows up to the candidate: the ones its files overlap. The give-up is conditional on the revision the root was read at. */
    private List<WorkflowStore.Yield> yielding(ConvergenceRoot candidate, WorkspaceIdentity.Identity identity, String surfaceKey, Judged judged) {
        return ConvergenceLogic.takenFrom(digests, candidate, identity, surfaceKey, judged.idle()).stream().map(taken -> new WorkflowStore.Yield(taken.root().rootId(), taken.root().revision())).toList();
    }

    /**
     * Whether the root is one the caller may take the files from: no run in progress (none running or paused), not waiting for a review or for the user, not the caller's session's. A root
     * that is gated holds its files until its gate is resolved, so a start is refused as long as it waits. A call with no session reading blocks as before.
     */
    private boolean isIdleElsewhere(ConvergenceRoot active, String parentRootId, WorkflowCall call) {
        return call != null && !active.rootId().equals(parentRootId) && !active.state().isGated() && !continuity.ownsRoot(call, active.rootId()) && !store.hasLiveRun(active.rootId());
    }

    /** The hold a start of a root asks for: whose call it is, what the entry read before the transaction, and which tool path the refusal is worded for. */
    private record Hold(WorkflowCall call, FileReads reads, RootConflictTerms.Entry path) {
    }

    /**
     * An attempt of a root (a claim, or the start of a run) works on the files of the root's write surface only while the root holds its row of that surface. A root that nobody
     * runs and that another session started on the same files gave the row up to that session's root: its owner may take it back only when no other root holds those files now
     * (ROOT_CONFLICT otherwise, naming the root that does), and it takes it from the idle ones that overlap, as a new root would. The take is in the transaction of the call, so a refusal
     * that is thrown leaves nothing, and the unique key of the surface is the last guard when two take it at the same moment. A refusal that is returned as a value commits what it recorded
     * (a rejected retry, a root moved to review), so a claim takes only where it issues its lease or gates the root, after those refusals: {@link #assertSurfaceFree} answers the conflict before.
     * The order in which the rows are taken is the store's ({@code WorkflowStore#holdSurface}), the same for every caller; this method orders nothing.
     *
     * @return false when the root was no longer at the revision this call read: nothing was taken, and the caller refuses with the answer of its own write of the root that lost the same race
     */
    private boolean holdSurface(ConvergenceRoot root, Hold hold) {
        if (holdsOrNeedsNoSurface(root)) {
            return true;
        }
        WorkflowStore.SurfaceTake<ConvergenceLogic.Conflict> take;
        try {
            take = store.holdSurface(root.rootId(), root.revision(), rows -> planHold(root, rows, hold), clock.instant().toString());
        } catch (StoreException lost) {
            throw surfaceHeld(lost);
        }
        if (take.conflict() != null) {
            throw rootConflict(ConvergenceLogic.conflictDetails(take.conflict()), hold);
        }
        return take.rootAtRevision();
    }

    /**
     * Whether the continue of the caller's own open root would hold its files now: no root holds them against it (the same read as the take, nothing written). This only decides what the
     * refusal offers: when the read cannot be made because the root moved on in the meantime (it is no longer among the active roots), the continue is not offered and the refusal is
     * answered as it stands, with the root that blocks, like a refusal whose ownership cannot be read ({@link #rootConflict}).
     */
    private boolean continuingHoldsFiles(String rootId, Hold hold) {
        try {
            return surfaceConflict(requireSnapshot(rootId).root(), hold) == null;
        } catch (WorkflowFailure moved) {
            if (moved.code() != WorkflowCode.STALE_REVISION) {
                throw moved;
            }
            LOG.debug("Root {} moved while the refusal checked whether continuing it would hold its files; the continue is not offered", rootId, moved);
            return false;
        }
    }

    /** A root that is not open has no take to make (a gated root is not given up), and one that holds its row asks for nothing. */
    private boolean holdsOrNeedsNoSurface(ConvergenceRoot root) {
        return root.state() != RootState.OPEN || store.holdsSurface(root.rootId());
    }

    /**
     * The conflict that refuses the take of the root's write surface now, found by a read that writes nothing; null when the root needs no take or takes the files freely. It is the plan of
     * {@link #holdSurface} without its write, so a claim that ends in a refusal that is recorded (a rejected retry) is answered ROOT_CONFLICT as before and takes nothing from the idle roots.
     */
    private ConvergenceLogic.Conflict surfaceConflict(ConvergenceRoot root, Hold hold) {
        return holdsOrNeedsNoSurface(root) ? null : planHold(root, store.readActiveRoots(), hold).conflict();
    }

    private void assertSurfaceFree(ConvergenceRoot root, Hold hold) {
        ConvergenceLogic.Conflict conflict = surfaceConflict(root, hold);
        if (conflict != null) {
            throw rootConflict(ConvergenceLogic.conflictDetails(conflict), hold);
        }
    }

    /**
     * Moves an open root to its review (NEEDS_REVIEW) as a claim is refused. A root that waits for a review holds its files until the gate is resolved (the other sessions' starts are refused
     * for it, see {@link #judge}), so it holds its row of the surface first, and the take is refused with ROOT_CONFLICT while another root holds the files: a gated root that held no row
     * would let the root that holds it run on the files the gate keeps.
     */
    private void gateRootForReview(ConvergenceRoot root, Hold hold) {
        if (!holdSurface(root, hold)) {
            throw rootMovedWhileApplying(root);
        }
        moveRootToReview(root);
    }

    private RootInsertionPlan<ConvergenceLogic.Conflict> planHold(ConvergenceRoot root, List<ActiveRootRow> rows, Hold hold) {
        Judged judged = judge(rows, root.rootId(), root.parentRootId(), hold.reads(), hold.call());
        if (judged.candidate() == null) {
            throw staleRoot(root.revision(), null);
        }
        WorkspaceIdentity.Identity identity = judged.candidate().identity();
        String surfaceKey = ConvergenceLogic.surfaceKey(digests, identity);
        return new RootInsertionPlan<>(ConvergenceLogic.findRootConflict(root, identity, judged.blocking()), judged.fresh(), null, surfaceKey, yielding(root, identity, surfaceKey, judged));
    }

    /** Releases a root: the owner session directly, another session only with no owner online and the user's decision. */
    @Transactional
    public RootOutcome<ReleaseResult> releaseConvergenceRoot(JsonNode raw, WorkflowCall call, OperationKey operation) {
        Optional<ReleaseResult> stored = receipts.replay(operation, ReleaseResult.class);
        if (stored.isPresent()) {
            return new RootOutcome.Done<>(stored.get());
        }
        RootOutcome<ReleaseResult> outcome = releaseRoot(raw, call);
        // Only a release that was done has a receipt; a request that waits for the user's decision is judged again.
        if (outcome instanceof RootOutcome.Done<ReleaseResult> done) {
            receipts.record(operation, done.result());
        }
        return outcome;
    }

    private RootOutcome<ReleaseResult> releaseRoot(JsonNode raw, WorkflowCall call) {
        ReleaseRequest request = releaseRequest(raw);
        RootContext context = releaseContext(request, call);
        if (context == null) {
            return new RootOutcome.Done<>(executeRelease(request, call, null));
        }
        RootApprovalRecords.Decision decided = lookupRootApproval(context, request.userApprovalRefs());
        if (decided == null) {
            return new RootOutcome.ApprovalRequired<>(savePending(context, call), context.scopeDigest());
        }
        return new RootOutcome.Done<>(executeRelease(request, call, decided));
    }

    /** The actor a release made on the admin page carries: the page, not a session; the signed record and its credential are in the decision the release cites. */
    static final String ADMIN_PAGE_ACTOR = "admin-page";

    /**
     * Releases a root for the user who signed the release on the admin page. The owner session may be online or gone: the page warns the user and the release records the presence
     * that was read, so a release over a live owner is possible but never silent. The decision is written here, for the signed record, and the release cites it.
     */
    @Transactional
    public RootReleaseService.Result releaseByAdmin(String rootId, long expectedRevision, String reason, RootApprovalService.Source source) {
        return releaseRefusal(rootId, expectedRevision).<RootReleaseService.Result>map(RootReleaseService.Result.Refused::new)
                .orElseGet(() -> releaseSigned(rootId, expectedRevision, reason, source));
    }

    /** The release of a root that can be released: the decision is written, then the root is closed citing it. */
    private RootReleaseService.Result releaseSigned(String rootId, long expectedRevision, String reason, RootApprovalService.Source source) {
        ConvergenceRoot root = requireSnapshot(rootId).root();
        RootApprovalScope scope = new RootApprovalScope(RootAction.RELEASE_ROOT, rootId, ADMIN_PAGE_ACTOR, digests.of(semanticSurface(root.taskEnvelope(), root.frame())), null);
        String scopeDigest = digests.of(scope);
        Instant now = clock.instant();
        String approvalId = "approval-" + UUID.randomUUID();
        RootApprovalRecords.Decision decision = new RootApprovalRecords.Decision(approvalId, scope, scopeDigest,
                new RootApprovalRecords.Source(source.kind(), source.recordHash(), source.credentialId()), now, null);
        store.insertRootApproval(new WorkflowStore.RootApproval(approvalId, scopeDigest, source.kind().name(), source.recordHash(), mapper.writeValueAsString(decision), now.toString(), null));
        ReleaseResult result = closeRoot(rootId, expectedRevision, reason.strip(), List.of(approvalId), new Releaser(ADMIN_PAGE_ACTOR, false, presence.read(rootId)));
        return new RootReleaseService.Result.Done(new RootReleaseService.Released(result.rootId(), result.revision(), result.closedRun() == null ? null : result.closedRun().runId(),
                result.release().ownerPresence().wire(), approvalId));
    }

    /** Whether a release of this root at this revision can be made now; empty when it can. Writes nothing. */
    @Transactional(readOnly = true)
    public Optional<RootReleaseService.Refusal> releaseRefusal(String rootId, long expectedRevision) {
        Snapshot snapshot = store.getConvergenceSnapshot(rootId);
        if (snapshot == null) {
            return Optional.of(RootReleaseService.Refusal.ROOT_NOT_FOUND);
        }
        ConvergenceRoot root = mapper.readValue(snapshot.rootJson(), ConvergenceRoot.class);
        if (!root.state().isActive()) {
            return Optional.of(RootReleaseService.Refusal.ROOT_NOT_ACTIVE);
        }
        return root.revision() == expectedRevision ? Optional.empty() : Optional.of(RootReleaseService.Refusal.ROOT_REVISION_STALE);
    }

    /** Whether the sessions that own this root are online, as the release reads it. */
    RootOwnerPresence.View ownerPresence(String rootId) {
        return presence.read(rootId);
    }

    private RootApprovalRecords.Pending savePending(RootContext context, WorkflowCall call) {
        RootApprovalRecords.Pending pending = new RootApprovalRecords.Pending(context.scopeDigest(), context.scope(), context.root().revision(), context.request(),
                rootApprovalMessage(context.scope(), context.root(), context.replacement()), digests.canonical(semanticSurface(context.root().taskEnvelope(), context.root().frame())),
                context.replacement() == null ? null : digests.canonical(semanticSurface(context.replacement().taskEnvelope(), context.replacement().frame())), call.actorId());
        store.savePendingRootApproval(context.scopeDigest(), mapper.writeValueAsString(pending), clock.instant().toString());
        return pending;
    }

    private String rootApprovalMessage(RootApprovalScope scope, ConvergenceRoot root, OpenConvergenceRootRequest replacement) {
        RootAction action = scope.action();
        String currentScope = digests.canonical(semanticSurface(root.taskEnvelope(), root.frame()));
        // The agent reads this sentence in a tool result, so it is rendered in the model's locale; one catalog key is one whole sentence per action.
        return switch (action) {
            case RELEASE_ROOT -> messages.render(msg("workflow.service.rootApprovalReleaseDescription", "action", action.name(), "rootId", scope.rootId(), "actorId", scope.actorId(),
                    "currentScope", currentScope));
            case REPLACE_ROOT -> messages.render(msg("workflow.service.rootApprovalReplaceDescription", "action", action.name(), "rootId", scope.rootId(), "actorId", scope.actorId(),
                    "currentScope", currentScope, "replacementScope", digests.canonical(semanticSurface(Objects.requireNonNull(replacement, "replacement").taskEnvelope(), replacement.frame()))));
        };
    }

    /** What the user approves of a task and a frame: the task without its generated ID, and the frame. */
    private static SemanticSurface semanticSurface(TaskEnvelope task, ConvergenceFrame frame) {
        return SemanticSurface.of(task, frame);
    }

    private RootApprovalScope rootApprovalScope(RootAction action, ConvergenceRoot root, String actorId, OpenConvergenceRootRequest replacement) {
        if (actorId == null) {
            throw rootApprovalFailure(RootApprovalProblem.ACTOR_UNAVAILABLE);
        }
        return new RootApprovalScope(action, root.rootId(), actorId, digests.of(semanticSurface(root.taskEnvelope(), root.frame())),
                replacement == null ? null : digests.of(semanticSurface(replacement.taskEnvelope(), replacement.frame())));
    }

    /** Why the user's decision cannot be used for a call; the wire reason is {@code USER_APPROVAL_} and the name. */
    enum RootApprovalProblem {
        ACTOR_UNAVAILABLE, REFERENCE_INVALID, REFERENCE_UNBOUND
    }

    /** The refusal says what the caller does instead: the decision is the user's on the admin page, and the same call is repeated after it. */
    static WorkflowFailure rootApprovalFailure(RootApprovalProblem problem) {
        MessageRef sentence = switch (problem) {
            case ACTOR_UNAVAILABLE -> msg("workflow.service.rootApprovalActorUnavailable");
            case REFERENCE_INVALID -> msg("workflow.service.rootApprovalReferenceInvalid");
            case REFERENCE_UNBOUND -> msg("workflow.service.rootApprovalReferenceUnbound");
        };
        return new WorkflowFailure(WorkflowCode.BINDING_INVALID, sentence, details("reason", "USER_APPROVAL_" + problem.name(), "field", "userApprovalRefs"));
    }

    private RootApprovalRecords.Decision lookupRootApproval(RootContext context, List<String> references) {
        String digest = context.scopeDigest();
        if (references.stream().anyMatch(String::isBlank) || new HashSet<>(references).size() != references.size()) {
            throw rootApprovalFailure(RootApprovalProblem.REFERENCE_INVALID);
        }
        if (references.isEmpty()) {
            return validApproval(store.findRootApproval(digest), digest);
        }
        RootApprovalRecords.Decision first = null;
        for (String reference : references) {
            RootApprovalRecords.Decision record = validApproval(store.getRootApproval(reference), digest);
            if (record == null) {
                throw rootApprovalFailure(RootApprovalProblem.REFERENCE_UNBOUND);
            }
            if (first == null) {
                first = record;
            }
        }
        return first;
    }

    /** A decision made on the admin page, for this exact scope and not revoked; model-supplied text cannot produce one. */
    private RootApprovalRecords.Decision validApproval(WorkflowStore.RootApproval approval, String digest) {
        if (approval == null || approval.revokedAt() != null) {
            return null;
        }
        RootApprovalRecords.Decision record = mapper.readValue(approval.recordJson(), RootApprovalRecords.Decision.class);
        boolean valid = record.source().kind() == ApprovalSourceKind.ADMIN_PAGE && Objects.equals(digest, record.scopeDigest()) && Objects.equals(digest, digests.of(record.scope()));
        return valid ? record : null;
    }

    /** Replacing a gated parent root: only a session that may touch the parent, with the user deciding on the exact replacement. */
    private RootContext replacementContext(OpenConvergenceRootRequest request, WorkflowCall call) {
        ConvergenceRoot root = requireSnapshot(request.parentRootId()).root();
        if (continuity.ownedByOtherSession(call, root.rootId())) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.replaceRootNotOwned", "rootId", root.rootId()), details("field", "parentRootId", "reason", "ROOT_NOT_OWNED", "rootId", root.rootId()));
        }
        if (!root.state().isGated()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.gatedConvergenceRootReplaced"), details("parentRootId", root.rootId(), "parentState", root.state()));
        }
        RootApprovalScope scope = rootApprovalScope(RootAction.REPLACE_ROOT, root, call.actorId(), request);
        return new RootContext(root, request, scope, digests.of(scope), request);
    }

    /** Releasing a root: null when the session owns it and needs no decision, otherwise the scope the user decides on. */
    private RootContext releaseContext(ReleaseRequest request, WorkflowCall call) {
        ConvergenceRoot root = requireSnapshot(request.rootId()).root();
        if (request.expectedRevision() != root.revision()) {
            throw staleRoot(request.expectedRevision(), root.revision());
        }
        if (!root.state().isActive()) {
            Map<String, Object> failure = details("reason", "ROOT_NOT_ACTIVE", "rootId", root.rootId(), "state", root.state());
            if (root.release() != null) {
                failure.put("release", root.release());
            }
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.releaseRootNotActive", "rootId", root.rootId(), "state", root.state()), failure);
        }
        if (continuity.ownsRoot(call, root.rootId())) {
            return null;
        }
        RootOwnerPresence.View view = presence.read(root.rootId(), call);
        if (view.unavailable() != null) {
            throw new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("workflow.service.releaseRootPresenceUnavailable", "rootId", root.rootId()), details("reason", "OWNER_PRESENCE_UNAVAILABLE", "rootId", root.rootId(), "cause", view.unavailable()));
        }
        if (view.state() == PresenceState.ONLINE) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.releaseRootOwnerOnline", "rootId", root.rootId()), details("reason", "OWNER_SESSION_ONLINE", "rootId", root.rootId(), "ownerPresence", view));
        }
        RootApprovalScope scope = rootApprovalScope(RootAction.RELEASE_ROOT, root, call.actorId(), null);
        return new RootContext(root, request, scope, digests.of(scope), null);
    }

    private void assertMcpBacked(TaskEnvelope task) {
        if (!task.orchestration().requested() || !task.orchestration().mcpAvailable()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.convergenceRootsRequireMCPBacked"));
        }
    }

    /** The release input as the tool schema states it: bound as the record it is, then held to the record's constraints. */
    private ReleaseRequest releaseRequest(JsonNode raw) {
        ReleaseRequest request;
        try {
            request = ReleaseRequest.reader(mapper).readValue(raw);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.releaseRootInputInvalid"), details("reason", "RELEASE_INPUT_INVALID"), unbound);
        }
        if (request == null || !inputRules.validate(request).isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.releaseRootInputInvalid"), details("reason", "RELEASE_INPUT_INVALID"));
        }
        return request.stripped();
    }

    private ReleaseResult executeRelease(ReleaseRequest request, WorkflowCall call, RootApprovalRecords.Decision approval) {
        boolean owned = continuity.ownsRoot(call, request.rootId());
        Releaser releaser = new Releaser(call.actorId(), owned, owned ? null : presence.read(request.rootId(), call));
        var turn = continuity.releaseTurn(call, isChild(call));
        ReleaseResult released = closeRoot(request.rootId(), request.expectedRevision(), request.reason(),
                approval != null ? List.of(approval.approvalId()) : request.userApprovalRefs(), releaser);
        turn.ifPresent(served -> continuity.recordTransition(served, WorkflowEvents.Transition.RELEASE_ROOT, released.rootId(), released.revision()));
        return released;
    }

    /**
     * Who releases a root. {@code presence} is what was read of the owner sessions, and null when the owner itself releases.
     *
     * @param actorId the persisted form of the releaser
     */
    private record Releaser(String actorId, boolean owner, RootOwnerPresence.View presence) {
    }

    /**
     * Closes a root and the run that is still in progress on it as blocked, and records who released it, why and on which approval. The one place a root is released, for a session
     * through the tool and for the user on the admin page alike.
     */
    private ReleaseResult closeRoot(String rootId, long expectedRevision, String reason, List<String> approvalRefs, Releaser releaser) {
        SnapshotDocs snapshot = requireSnapshot(rootId);
        ConvergenceRoot root = snapshot.root();
        if (root.revision() != expectedRevision) {
            throw staleRoot(expectedRevision, root.revision());
        }
        boolean owned = releaser.owner();
        RootOwnerPresence.View view = releaser.presence();
        Instant releasedAt = clock.instant();
        String latestRunId = snapshot.runIds().isEmpty() ? null : snapshot.runIds().getLast();
        WorkflowReceipt latest = latestRunId == null ? null : requireRun(latestRunId);
        WorkflowReceipt running = latest != null && !latest.state().isTerminal() ? latest : null;

        Set<String> ownerActors = new LinkedHashSet<>();
        if (view != null) {
            view.sessions().forEach(session -> ownerActors.add(session.actorId()));
        }
        ConvergenceRoot.Release release = new ConvergenceRoot.Release(releasedAt, releaser.actorId(), owned, root.state(), view == null ? null : view.state(), view == null ? null : view.observedAt(),
                List.copyOf(ownerActors), approvalRefs, reason, running == null ? null : running.runId());

        ConvergenceRoot updated;
        ReleaseResult.ClosedRun closedRun = null;
        if (running != null) {
            long expected = running.revision();
            WorkflowReceipt closed = running.withState(RunState.BLOCKED).withPlan(running.plan().blocked()).withBlocked(List.of(AbortBlocker.ROOT_RELEASED.wire())).withError(null).withRevision(expected + 1);
            assertReceipt(closed);
            GuardedRun guarded = guardedBinding(closed.runId());
            if (guarded != null && guarded.root().revision() != expectedRevision) {
                throw staleRoot(expectedRevision, guarded.root().revision());
            }
            ConvergenceOutcome outcome = guarded != null && guarded.outcome() == null ? convergenceOutcome(closed, guarded) : null;
            updated = (outcome != null ? outcome.root() : root.bumped(releasedAt)).withState(RootState.ABANDONED).withRelease(release);
            validator.convergenceRoot(updated);
            boolean stored = outcome != null
                    ? store.updateRun(runRow(closed), expected, rootRow(updated), expectedRevision, outcomeRow(outcome.document()), clock.instant().toString())
                    : store.updateRun(runRow(closed), expected, null, null, null, clock.instant().toString()) && store.updateConvergenceRoot(rootRow(updated), expectedRevision, root.currentEpoch(), null);
            if (!stored) {
                throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("runId", closed.runId(), "expectedRevision", expected));
            }
            closedRun = new ReleaseResult.ClosedRun(closed.runId(), closed.state(), closed.revision());
        } else {
            updated = root.bumped(releasedAt).withState(RootState.ABANDONED).withRelease(release);
            validator.convergenceRoot(updated);
            if (!store.updateConvergenceRoot(rootRow(updated), expectedRevision, root.currentEpoch(), null)) {
                throw staleRoot(expectedRevision, null);
            }
        }
        return new ReleaseResult(ContractValidator.CONTRACT_VERSION, updated.rootId(), updated.revision(), RootState.ABANDONED, release, closedRun);
    }

    private static WorkflowFailure staleRoot(long expected, Long actual) {
        return new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchConvergenceRoot"), details("expectedRevision", expected, "actualRevision", actual));
    }

    /**
     * ROOT_CONFLICT with what the caller can do next, on both start paths. Ownership that cannot be read leaves only the actions that need none. {@code hold} is the call that was refused:
     * its session, the reads of the entry and the tool path; the answer to continue the caller's own root is given only when that continue would take the files now.
     */
    private WorkflowFailure rootConflict(Map<String, Object> conflict, Hold hold) {
        RootConflictTerms.Entry path = hold.path();
        WorkflowCall call = hold.call();
        String rootId = String.valueOf(conflict.getOrDefault("rootId", ""));
        RootState state = conflict.get("blockerState") instanceof RootState known ? known : null;
        boolean owned;
        try {
            owned = !rootId.isEmpty() && continuity.ownsRoot(call, rootId);
        } catch (DataAccessException unreadable) {
            LOG.warn("The ownership of root {} could not be read", rootId, unreadable);
            owned = false;
        }
        boolean gated = state != null && state.isGated();
        Snapshot snapshot = rootId.isEmpty() ? null : store.getConvergenceSnapshot(rootId);
        String latestRunId = snapshot == null || snapshot.workflowRunIds().isEmpty() ? null : snapshot.workflowRunIds().getLast();
        String latestText = latestRunId == null ? null : store.getRun(latestRunId);
        RunHeader latest = latestText == null ? null : mapper.readValue(latestText, RunHeader.class);
        Map<String, Object> latestRun = null;
        if (latest != null) {
            latestRun = new LinkedHashMap<>();
            latestRun.put("runId", latest.runId());
            latestRun.put("state", latest.state());
            latestRun.put("updatedAt", store.getRunUpdatedAt(latestRunId));
        }
        String runId = owned && state == RootState.OPEN && latest != null && !latest.state().isTerminal() ? latest.runId() : null;
        RootOwnerPresence.View view;
        if (owned) {
            view = new RootOwnerPresence.View(PresenceState.ONLINE, true, true, List.of(), null, clock.instant().toString());
        } else if (call == null) {
            view = RootOwnerPresence.View.notRead("PRESENCE_NOT_READ", clock);
        } else {
            try {
                view = presence.read(rootId, call);
            } catch (DataAccessException cause) {
                LOG.warn("The presence of the owner of root {} could not be read", rootId, cause);
                view = RootOwnerPresence.View.notRead("PRESENCE_UNAVAILABLE", clock);
            }
        }
        // Continuing the caller's own root is offered only when that continue would hold the files now: the root holds its row, or the take of its files is free (the root that took them ended
        // or is idle). One that gave them up to a root that holds them still would be refused the same way (ROOT_CONFLICT) on the continue.
        RootConflictTerms.Standing action = gated ? RootConflictTerms.Standing.RESOLVE : !owned || state != RootState.OPEN ? RootConflictTerms.Standing.DISJOINT
                : runId != null ? RootConflictTerms.Standing.RUNNING : continuingHoldsFiles(rootId, hold) ? RootConflictTerms.Standing.CONTINUE : RootConflictTerms.Standing.DISJOINT;
        RootConflictTerms.ReleasePath release = owned ? RootConflictTerms.ReleasePath.OWN : view.unavailable() != null ? RootConflictTerms.ReleasePath.NONE
                : view.state() == PresenceState.ONLINE ? RootConflictTerms.ReleasePath.ONLINE : RootConflictTerms.ReleasePath.APPROVAL;
        Map<String, Object> releaseArguments = details("rootId", rootId, "expectedRevision", conflict.get("rootRevision"));
        boolean start = path == RootConflictTerms.Entry.START;
        List<Map<String, Object>> nextActions = new ArrayList<>();
        nextActions.add(details("action", RootConflictTerms.NextAction.RESTART_WITH_DISJOINT_WRITE_TARGETS, "tool", start ? "start_workflow" : "open_convergence_root"));
        if (action == RootConflictTerms.Standing.RUNNING) {
            nextActions.add(details("action", RootConflictTerms.NextAction.CONTINUE_RUNNING_WORKFLOW, "tool", "get_workflow_status", "arguments", details("runId", runId)));
        }
        if (action == RootConflictTerms.Standing.CONTINUE) {
            nextActions.add(details("action", RootConflictTerms.NextAction.CONTINUE_OWN_ROOT, "tool", start ? "start_workflow" : "claim_workflow_attempt", "arguments", details("rootId", rootId)));
        }
        if (gated) {
            nextActions.add(details("action", RootConflictTerms.NextAction.RESOLVE_GATE, "tool", "resolve_convergence_gate", "arguments", details("rootId", rootId)));
        }
        switch (release) {
            case OWN -> nextActions.add(details("action", RootConflictTerms.NextAction.RELEASE_OWN_ROOT, "tool", "release_convergence_root", "arguments", releaseArguments));
            case APPROVAL -> nextActions.add(details("action", RootConflictTerms.NextAction.RELEASE_WITH_USER_APPROVAL, "tool", "release_convergence_root", "arguments", releaseArguments,
                    "requires", List.of("userApprovalRefs")));
            case ONLINE -> view.sessions().stream().filter(session -> session.state() == PresenceState.ONLINE).findFirst().ifPresent(owner ->
                    nextActions.add(details("action", RootConflictTerms.NextAction.ASK_OWNER_SESSION, "tool", "prepare_session_message", "arguments", details("targetHost", owner.host(), "targetSessionId", owner.sessionId()))));
            case NONE -> {
            }
        }
        MessageRef context = msg("workflow.service.rootConflictContext", "createdAt", String.valueOf(conflict.getOrDefault("rootCreatedAt", "")), "updatedAt", String.valueOf(conflict.getOrDefault("rootUpdatedAt", "")),
                "latestRun", latestRun != null ? "some" : "none", "latestRunId", latestRun != null ? latestRun.get("runId") : "", "latestRunState", latestRun != null ? latestRun.get("state") : "",
                "latestRunUpdatedAt", latestRun != null ? String.valueOf(latestRun.get("updatedAt")) : "", "presence", view.state().wire());
        String existing = String.valueOf(conflict.containsKey("existingPath") ? conflict.get("existingPath") : conflict.getOrDefault("existingEntry", ""));
        String renderedContext = messages.render(context);
        MessageRef ref = start
                ? msg("workflow.service.startWorkflowRootConflict", "requested", String.valueOf(conflict.getOrDefault("requestedEntry", "")), "existing", existing, "rootId", rootId, "state", state == null ? "" : state,
                        "owned", owned ? "yes" : "no", "action", action.wire(), "runId", runId == null ? "" : runId, "context", renderedContext, "release", release.wire())
                : msg("workflow.service.activeConvergenceRootCoversWorkspace", "existing", existing, "rootId", rootId, "state", state == null ? "" : state, "context", renderedContext, "release", release.wire());
        Map<String, Object> out = new LinkedHashMap<>(conflict);
        out.put("ownedBySession", owned);
        out.put("latestRun", latestRun);
        out.put("ownerPresence", view);
        out.put("nextActions", nextActions);
        return new WorkflowFailure(WorkflowCode.ROOT_CONFLICT, ref, out);
    }

    // ---- claim_workflow_attempt

    /** What a claim decided: the lease, or a refusal that recorded something (a rejected retry, a root moved to review), which therefore commits with the transaction. */
    public sealed interface ClaimOutcome {
        record Leased(AttemptLease lease) implements ClaimOutcome {
        }

        /** The failure is a value here: the entry answers it after the commit. A failure that wrote nothing is thrown and rolls the transaction back. */
        record Refused(WorkflowFailure failure) implements ClaimOutcome {
        }
    }

    /**
     * rejectRetry and moveRootToReview write to the database and the claim is refused: those refusals are returned as {@link ClaimOutcome.Refused}, so the transaction commits what
     * they wrote and the entry answers the failure. A refusal that wrote nothing is thrown and rolls everything back. Only a lease has a receipt.
     *
     * @param providers the skill registry, read by the entry before this transaction
     * @param reads where the active roots' locations physically are, resolved by the entry before this transaction ({@link #planActiveRootReads}); a root that gave its write surface up takes it back from them
     */
    @Transactional
    public ClaimOutcome claimWorkflowAttempt(JsonNode raw, WorkflowCall call, List<SkillProvider> providers, FileReads reads, OperationKey operation) {
        Optional<AttemptLease> stored = receipts.replay(operation, AttemptLease.class);
        if (stored.isPresent()) {
            return new ClaimOutcome.Leased(stored.get());
        }
        ClaimOutcome outcome = claim(proposalFrom(raw), call, true, providers, new Hold(call, reads, RootConflictTerms.Entry.OPEN));
        if (outcome instanceof ClaimOutcome.Leased leased) {
            receipts.record(operation, leased.lease());
        }
        return outcome;
    }

    /** Claims the lease for a proposal whose task envelope and frame are in place. The providers are what the entry read before the transaction; the prior failure's evidence is read from the attachment rows here. */
    private ClaimOutcome claim(AttemptProposal proposal, WorkflowCall call, boolean checkAccess, List<SkillProvider> providers, Hold hold) {
        String actorId = call.actorId();
        WorkflowPlan plan = proposal.plan();
        byte[] key = signingKey();
        planner.assertIntegrity(plan, key);
        assurance.assertStrictPlan(plan, "workflowAttempt");
        assertConvergenceFrame(proposal.frame());
        if (plan.executionMode() != ExecutionMode.ORCHESTRATED || plan.state() != RunState.READY) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.readyOrchestratedPlanClaimAttempt"));
        }
        TaskEnvelope task = proposal.taskEnvelope();
        String rootId = proposal.rootId();
        String taskId = task.taskId();
        if (!Objects.equals(plan.taskId(), taskId)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.attemptTaskWorkflowPlanIDs"));
        }
        String taskDigest = digests.of(task);
        if (!Objects.equals(plan.taskDigest(), taskDigest)) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.workflowPlanBoundDifferentTask"), details("rootId", rootId, "taskId", taskId));
        }
        EvaluationAuditPurpose purpose = null;
        AuditPhase phase = null;
        for (WorkflowPlan.Stage stage : plan.stages()) {
            if (purpose == null && KnownCapability.EVALUATION_VALIDITY_AUDIT.is(stage.requiredCapability())) {
                purpose = stage.evaluationAuditPurpose();
            }
            if (phase == null && StageGates.mandatoryAudit(stage)) {
                phase = stage.requiredAuditPhase();
            }
        }
        WorkflowPlan expectedPlan = planner.build(task, providers, purpose, plan.bootstrapExecution().context(), phase, key);
        // The plan's token was checked as the HMAC of the plan, so the token the registry and the task would sign is the plan's own exactly when the two plans are the same document.
        if (!MessageDigest.isEqual(expectedPlan.integrityToken().getBytes(StandardCharsets.UTF_8), plan.integrityToken().getBytes(StandardCharsets.UTF_8))) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.workflowPlanProducedProposedTask"), details("rootId", rootId, "taskId", taskId));
        }
        // An issued but unstarted lease does not block: the store supersedes it when this claim commits.
        SnapshotDocs snapshot = requireSnapshot(rootId);
        ConvergenceRoot root = snapshot.root();
        // A root that another session owns is not this session's to claim an attempt of, whatever the claim would change: refused before anything is read of the files or written (a lease
        // that supersedes the owner's, the revision, the files of an idle root, a move to review). The owner is the one the start of a run binds (WorkflowContinuity); a root nobody owns is
        // claimed as before.
        if (checkAccess && continuity.ownedByOtherSession(call, rootId)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.claimRootNotOwned", "rootId", rootId), details("field", "rootId", "reason", "ROOT_NOT_OWNED", "rootId", rootId));
        }
        // A root that gave its files up to another session's root cannot be leased while another root holds those files: that is answered first, from a read that writes nothing. The take of
        // the files itself waits for the point where the claim is not refused any more (see the gate and the lease below), so a refused claim leaves the other idle roots their rows.
        assertSurfaceFree(root, hold);
        long revision = root.revision();
        if (proposal.expectedRevision() != revision) {
            throw staleRoot(proposal.expectedRevision(), revision);
        }
        // A proposal that carries the root's own task and frame has the root's digests; the records compare in place of digesting them again.
        ConvergenceDigests proposed = task.equals(root.taskEnvelope()) && proposal.frame().equals(root.frame()) ? root.digests() : digests.convergence(taskDigest, proposal.frame());
        boolean frameChanged = proposed.changesMeaningOf(root.digests()) || artifactRolesChanged(root.frame(), proposal.frame());
        if (frameChanged) {
            if (root.state() == RootState.OPEN) {
                gateRootForReview(root, hold);
            }
            Map<String, Object> expected = details("taskDigest", root.taskDigest(), "workspaceDigest", root.workspaceDigest(), "controlDigest", root.controlDigest(), "operationalDigest", root.operationalDigest());
            return new ClaimOutcome.Refused(new WorkflowFailure(WorkflowCode.FRAME_REVIEW_REQUIRED, msg("workflow.service.taskControlWorkspaceOperationalArtifact"), details("rootId", rootId, "expected", expected, "proposed", proposed)));
        }
        if (root.state() != RootState.OPEN) {
            throw rootGate(root, snapshot);
        }
        long epoch = root.currentEpoch();
        List<AttemptLease> attempts = snapshot.consumedLeases(epoch);
        List<AttemptOutcome> currentOutcomes = snapshot.outcomes().stream().filter(outcome -> outcome.epoch() == epoch).toList();
        AttemptOutcome latestOutcome = currentOutcomes.isEmpty() ? null : currentOutcomes.getLast();
        if (currentOutcomes.size() < attempts.size()) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.previousFullAttemptStillActive"), details("rootId", rootId, "epoch", epoch));
        }
        if (attempts.size() >= root.frame().operationalSettings().maxAttemptsPerEpoch()) {
            gateRootForReview(root, hold);
            return new ClaimOutcome.Refused(new WorkflowFailure(WorkflowCode.ATTEMPT_BUDGET_EXHAUSTED, msg("workflow.service.attemptBudgetStartedConvergence", "attempts", root.frame().operationalSettings().maxAttemptsPerEpoch()),
                    details("rootId", rootId, "epoch", epoch, "attemptsUsed", attempts.size())));
        }
        Set<String> priorEvidence = new LinkedHashSet<>();
        AttemptProposal.PriorFailure priorFailure = proposal.priorFailure();
        boolean hasPrior = priorFailure != null;
        if (attempts.isEmpty()) {
            if (hasPrior) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.firstAttemptEpochClaimPrior"));
            }
        } else {
            if (latestOutcome == null || latestOutcome.state() == AttemptState.PASSED) {
                throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.previousFullAttemptProducedRetryable"), details("rootId", rootId));
            }
            String fingerprint = latestOutcome.failureFingerprint();
            if (!hasPrior || !Objects.equals(fingerprint, priorFailure.fingerprint())) {
                return rejectRetry(root, retryRejection(epoch, RetryRejectionReason.STALE_FINGERPRINT, actorId, fingerprint, proposed.targetDigest(), hasPrior ? priorFailure.evidenceRefs() : List.of()),
                        msg("workflow.service.retryMustBindLatestFingerprint"), details("expectedFingerprint", fingerprint));
            }
            AttemptLease priorLease = attempts.getLast();
            boolean sameTarget = Objects.equals(proposed.targetDigest(), priorLease.targetDigest());
            // Evidence burnt by an earlier rejection stays burnt for the whole root: a new epoch forces priorFailure to null, so an epoch-scoped set would let it back in.
            // New evidence means new bytes the server holds, never a new string: each reference is an attachment of the caller and the digest is the one its row has.
            for (String attachmentId : priorFailure.evidenceRefs()) {
                priorEvidence.add(evidenceAttachments.priorEvidenceDigest(attachmentId, call, rootId));
            }
            Set<String> oldEvidence = new HashSet<>();
            snapshot.leases().stream().filter(lease -> lease.state() == LeaseState.CONSUMED).forEach(lease -> lease.priorEvidenceDigests().forEach(item -> oldEvidence.add(StageGates.evidenceKey(item))));
            snapshot.outcomes().forEach(outcome -> outcome.evidenceRefs().forEach(item -> oldEvidence.add(StageGates.evidenceKey(item))));
            root.retryRejections().stream().filter(item -> item.reason() == RetryRejectionReason.STALE_EVIDENCE)
                    .forEach(item -> item.evidenceRefs().forEach(ref -> oldEvidence.add(StageGates.evidenceKey(ref))));
            boolean hasNew = priorEvidence.stream().anyMatch(digest -> !oldEvidence.contains(digest));
            if (sameTarget && !hasNew) {
                return rejectRetry(root, retryRejection(epoch, RetryRejectionReason.STALE_EVIDENCE, actorId, fingerprint, proposed.targetDigest(), new ArrayList<>(priorEvidence)),
                        msg("workflow.service.retryChangesNothing"), details("rootId", rootId, "route", "diagnose"));
            }
        }
        // Every refusal that writes is behind: a lease is issued from here, and only a lease works on the files, so only here the root takes its row of the surface back.
        if (!holdSurface(root, hold)) {
            throw rootMovedWhileLeasing(rootId);
        }
        Instant issuedAt = clock.instant();
        ConvergenceRoot updatedRoot = root.bumped(issuedAt);
        // One writing of the proposal serves its digest and its stored text.
        DocumentDigests.Sealed sealedProposal = digests.seal(proposal);
        AttemptLease lease = new AttemptLease(ContractValidator.CONTRACT_VERSION, "lease-" + UUID.randomUUID(), rootId, updatedRoot.revision(), epoch, attempts.size() + 1,
                sealedProposal.digest(), proposed.frameDigest(), proposed.workspaceDigest(), proposed.controlDigest(), proposed.targetDigest(), proposed.operationalDigest(),
                proposed.taskDigest(), digests.of(proposal.outputTargets()), plan.integrityToken(), actorId, List.copyOf(priorEvidence), proposal.outputTargets(), issuedAt,
                issuedAt.plusSeconds(proposal.frame().operationalSettings().leaseTtlSeconds()), LeaseState.ISSUED);
        validator.attemptLease(lease);
        LeaseRow row = new LeaseRow(lease.leaseId(), rootId, lease.rootRevision(), epoch, lease.ordinal(), LeaseState.ISSUED, mapper.writeValueAsString(lease), sealedProposal.json(),
                lease.issuedAt().toString(), lease.expiresAt().toString());
        boolean issued;
        try {
            issued = store.insertAttemptLease(rootRow(updatedRoot), revision, row);
        } catch (StoreException lost) {
            throw leaseRaceLost(lost, msg("workflow.service.convergenceRootChangedWhileLease"), details("rootId", rootId));
        }
        if (!issued) {
            throw rootMovedWhileLeasing(rootId);
        }
        return new ClaimOutcome.Leased(lease);
    }

    private static WorkflowFailure rootMovedWhileLeasing(String rootId) {
        return new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.convergenceRootChangedWhileLease"), details("rootId", rootId));
    }

    /**
     * The failure of a write of a lease that the store ended as a lost race (its LEASE_CONFLICT: a lease was just issued, the database was busy, or this call was the victim of a deadlock),
     * as the caller is told of a lost race: LEASE_CONFLICT, the transaction rolled back whole. The store answers a lost race of the take of a write surface with ROOT_CONFLICT for the same
     * set of failures ({@link #surfaceHeld}). Anything else the store threw is returned as it is.
     */
    private static RuntimeException leaseRaceLost(StoreException lost, MessageRef message, Map<String, Object> details) {
        return lost.code() == StoreCode.LEASE_CONFLICT ? new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, message, details, lost) : lost;
    }

    private ConvergenceRoot.RetryRejection retryRejection(long epoch, RetryRejectionReason reason, String actorId, String fingerprint, String targetDigest, List<String> evidence) {
        return new ConvergenceRoot.RetryRejection(epoch, reason, actorId, fingerprint, targetDigest, List.copyOf(evidence), clock.instant());
    }

    /** Records a rejected retry on the root and refuses with it, so its evidence cannot return as new. The refusal is a value: the record commits with the transaction. */
    private ClaimOutcome.Refused rejectRetry(ConvergenceRoot root, ConvergenceRoot.RetryRejection entry, MessageRef message, Map<String, Object> failure) {
        long rootRevision = root.revision();
        // A rejection that differs from an earlier one only in when it happened is the same rejection, recorded once.
        boolean known = root.retryRejections().stream().anyMatch(item -> item.at(Instant.EPOCH).equals(entry.at(Instant.EPOCH)));
        if (!known) {
            ConvergenceRoot updated = root.bumped(entry.rejectedAt()).withRetryRejection(entry);
            validator.convergenceRoot(updated);
            if (!store.updateConvergenceRoot(rootRow(updated), rootRevision, root.currentEpoch(), null)) {
                throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.convergenceRootChangedWhileRecording2"), details("rootId", root.rootId()));
            }
            rootRevision = updated.revision();
        }
        failure.put("rootRevision", rootRevision);
        return new ClaimOutcome.Refused(new WorkflowFailure(WorkflowCode.NEW_EVIDENCE_REQUIRED, message, failure));
    }

    private void moveRootToReview(ConvergenceRoot root) {
        ConvergenceRoot updated = root.bumped(clock.instant()).withState(RootState.NEEDS_REVIEW);
        if (!store.updateConvergenceRoot(rootRow(updated), root.revision(), root.currentEpoch(), null)) {
            throw rootMovedWhileApplying(root);
        }
    }

    private static WorkflowFailure rootMovedWhileApplying(ConvergenceRoot root) {
        return new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.convergenceRootChangedWhileApplying"), details("rootId", root.rootId()));
    }

    private static WorkflowFailure releasedRootError(ConvergenceRoot root, ConvergenceRoot.Release release) {
        return new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.rootReleased", "rootId", root.rootId(), "releasedAt", release.releasedAt().toString(),
                "byOwner", release.releasedByOwner() ? "yes" : "no", "reason", release.reason()),
                details("reason", "ROOT_RELEASED", "rootId", root.rootId(), "state", root.state(), "release", release));
    }

    private static WorkflowFailure rootGate(ConvergenceRoot root, SnapshotDocs snapshot) {
        if (root.release() != null) {
            return releasedRootError(root, root.release());
        }
        long epoch = root.currentEpoch();
        if (root.state() == RootState.NEEDS_REVIEW && snapshot.consumedLeases(epoch).size() >= root.frame().operationalSettings().maxAttemptsPerEpoch()) {
            return new WorkflowFailure(WorkflowCode.ATTEMPT_BUDGET_EXHAUSTED, msg("workflow.service.independentReviewRequiredBeforeAnother"), details("rootId", root.rootId(), "epoch", epoch));
        }
        return new WorkflowFailure(WorkflowCode.FRAME_REVIEW_REQUIRED, msg("workflow.service.convergenceRootGatedIssueAnother"), details("rootId", root.rootId(), "state", root.state()));
    }

    /**
     * The proposal a caller sent: checked against its schema as the caller wrote it, then bound. A caller may leave out the task envelope and the
     * frame; the root's own are put in before the check, so that a key the schema does not know is refused either way.
     */
    private AttemptProposal proposalFrom(JsonNode raw) {
        JsonNode candidate = raw == null ? NullNode.getInstance() : raw;
        boolean hasTask = candidate.has("taskEnvelope");
        boolean hasFrame = candidate.has("frame");
        if (hasTask != hasFrame) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.attemptProposalProvideBothTaskEnvelope"));
        }
        String name = "attempt-proposal.v1.schema.json";
        if (hasTask) {
            return validator.read(name, candidate, AttemptProposal.class);
        }
        // The root the proposal names is read first (a call that names none asks for the root that is not there); the proposal that is bound from the caller's keys
        // alone (a key the record does not list is refused) takes the root's documents, and the schema judges the whole.
        AttemptProposal.RootReference reference = validator.bind(name, candidate, AttemptProposal.RootReference.class);
        ConvergenceRoot root = requireSnapshot(reference == null || reference.rootId() == null ? "" : reference.rootId()).root();
        // The schema judges what the caller wrote, with the root's documents put in: a key the caller left out is then named as missing, where the record bound from the keys alone
        // would hold it as null and be refused for its value ("schemaVersion must be 1.0.0" for a schemaVersion that was never sent). priorFailure is the one key a caller may leave out.
        if (candidate instanceof ObjectNode written) {
            ObjectNode whole = written.deepCopy();
            whole.set("taskEnvelope", mapper.valueToTree(root.taskEnvelope()));
            whole.set("frame", mapper.valueToTree(root.frame()));
            if (!whole.has("priorFailure")) {
                whole.putNull("priorFailure");
            }
            validator.assertValid(name, whole);
        }
        return withRootDocuments(validator.bind(name, candidate, AttemptProposal.class), root);
    }

    /** A proposal built in code takes the task envelope and the frame of the root it names, and is checked against its schema. */
    private AttemptProposal withRootDocuments(AttemptProposal partial, ConvergenceRoot root) {
        AttemptProposal filled = partial.withTaskAndFrame(root.taskEnvelope(), root.frame());
        validator.attemptProposal(filled);
        return filled;
    }

    // ---- start_guarded_workflow and start_workflow

    /**
     * @param installed the skills a start may name ({@link SkillRegistry#installedSkillIds}), read by the entry before this transaction
     * @param reads where the active roots' locations physically are, resolved by the entry before this transaction ({@link #planActiveRootReads})
     */
    @Transactional
    public WorkflowReceipt startGuardedWorkflow(JsonNode raw, WorkflowCall call, Set<String> installed, FileReads reads, OperationKey operation) {
        return receipts.once(operation, WorkflowReceipt.class, () -> startGuarded(raw, call, installed, reads));
    }

    private WorkflowReceipt startGuarded(JsonNode raw, WorkflowCall call, Set<String> installed, FileReads reads) {
        WorkflowReceipt receipt = guardedStart(raw, installed, new Hold(call, reads, RootConflictTerms.Entry.OPEN));
        GuardedRun binding = guardedBinding(receipt.runId());
        if (binding == null) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.startedWorkflowGuardedRootBinding"));
        }
        // The correlation commits with the run: a correlation failure rolls the run back.
        continuity.prepareWorkflowStart(call, binding.root().rootId(), isChild(call));
        return receipt;
    }

    private static boolean isChild(WorkflowCall call) {
        return !call.observedCaller().parentChain().isEmpty();
    }

    /** A guarded start from the caller's input. */
    private WorkflowReceipt guardedStart(JsonNode raw, Set<String> installed, Hold hold) {
        // Checked before the contract so that every malformed list answers SKILLS_REQUIRED with what to submit.
        List<SkillDeclaration> skills = declaredSkills(skillsOf(raw), installed);
        assertNoPlaceholders(skills);
        GuardedInput input = guardedRequest(raw);
        // The longer path opens a change: the kind it is counted under, as the observation of its start is (WorkflowContinuity.prepareWorkflowStart).
        store.requireBlockedPlanGuardForUpdate();
        return startRun(input.request(), skills, input.issued(), null, WorkflowKind.CHANGE, hold, true);
    }

    /**
     * Starts the run a lease was issued for. The plan is the one the lease is bound to: its token is the one the server's key makes of the plan (checked here) and
     * the one the lease holds, and the token is the signature of the whole plan, so the plans are the same document when the tokens are.
     *
     * @param issued the attempt, when finding the plan in the request already had to read it
     * @param kind the kind the run is opened as; it and the skills the start declared are stored with the run in this transaction, which is what the statistics count (V22)
     * @param hold the root must hold its write surface when its run starts ({@link #holdSurface}); a lease issued before another root took the files is no leave to write them
     */
    private WorkflowReceipt startRun(GuardedStartRequest request, List<SkillDeclaration> skills, IssuedAttempt issued, ClassificationAdvice.StartAdvice advice, WorkflowKind kind, Hold hold, boolean checkBlockedPlan) {
        WorkflowPlan plan = request.plan();
        planner.assertIntegrity(plan, signingKey());
        assurance.assertStrictPlan(plan, "guardedStart");
        if (plan.executionMode() != ExecutionMode.ORCHESTRATED || plan.state() != RunState.READY) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.readyOrchestratedWorkflowUseConvergence"));
        }
        String leaseId = request.leaseId();
        IssuedAttempt attempt = issued != null ? issued : issuedAttempt(leaseId);
        if (attempt == null || attempt.lease().state() != LeaseState.ISSUED) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.attemptLeaseMissingConsumedSuperseded"), details("leaseId", leaseId));
        }
        AttemptLease lease = attempt.lease();
        if (!holdSurface(attempt.root(), hold)) {
            throw leaseNotConsumed(leaseId);
        }
        long expected = request.expectedRootRevision();
        if (expected != attempt.root().revision() || expected != lease.rootRevision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.guardedStartTargetCurrentConvergence"), details("expectedRevision", expected, "actualRevision", attempt.root().revision()));
        }
        if (!Objects.equals(plan.integrityToken(), lease.planIntegrityToken())) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.attemptLeaseBoundDifferentWorkflow"), details("leaseId", leaseId));
        }
        if (checkBlockedPlan) {
            assertPlanUsable(planComparison(attempt.root(), kind, plan, Set.of()));
        }
        WorkflowReceipt receipt = newRunningReceipt(plan, skillDeclaration(skills, request.classificationOperationKey(), advice));
        GuardedBinding started;
        try {
            started = store.insertGuardedRun(runRow(receipt), leaseId, expected, clock.instant().toString(), kind.name(), skills.stream().map(SkillDeclaration::bareId).toList());
        } catch (StoreException lost) {
            throw leaseRaceLost(lost, msg("workflow.service.attemptLeaseCouldConsumedAtomically"), details("leaseId", leaseId));
        }
        if (started == null) {
            throw leaseNotConsumed(leaseId);
        }
        return receipt;
    }

    private static WorkflowFailure leaseNotConsumed(String leaseId) {
        return new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.attemptLeaseCouldConsumedAtomically"), details("leaseId", leaseId));
    }

    /** A guarded start's request, and the issued attempt when finding the plan in it had to read that already. */
    private record GuardedInput(GuardedStartRequest request, IssuedAttempt issued) {
    }

    /** A lease as the store holds it with the proposal it was issued for and the root it is bound to. */
    private record IssuedAttempt(AttemptLease lease, AttemptProposal proposal, ConvergenceRoot root) {
    }

    private IssuedAttempt issuedAttempt(String leaseId) {
        GuardedBinding row = store.getAttemptLease(leaseId);
        return row == null ? null : new IssuedAttempt(mapper.readValue(row.leaseJson(), AttemptLease.class), mapper.readValue(row.proposalJson(), AttemptProposal.class), mapper.readValue(row.rootJson(), ConvergenceRoot.class));
    }

    /**
     * The request of a guarded start as the caller sent it: checked against the contract as written, then bound. A caller may leave out the plan, and then the
     * plan of the lease's proposal is put in before the check, so that a key the schema does not know is refused either way.
     */
    private GuardedInput guardedRequest(JsonNode raw) {
        JsonNode candidate = raw == null ? NullNode.getInstance() : raw;
        String name = "guarded-workflow-start-request.v1.schema.json";
        if (candidate.has("plan")) {
            return new GuardedInput(validator.read(name, candidate, GuardedStartRequest.class), null);
        }
        GuardedStartRequest.LeaseReference reference = validator.bind(name, candidate, GuardedStartRequest.LeaseReference.class);
        String leaseId = reference == null || reference.leaseId() == null ? "" : reference.leaseId();
        IssuedAttempt issued = issuedAttempt(leaseId);
        if (issued == null) {
            throw new WorkflowFailure(WorkflowCode.LEASE_CONFLICT, msg("workflow.service.attemptLeaseMissingConsumedSuperseded"), details("leaseId", leaseId));
        }
        // The request that is bound from the caller's keys alone (a key the record does not list is refused) takes the plan of the lease's proposal, and the schema judges the whole.
        GuardedStartRequest filled = validator.bind(name, candidate, GuardedStartRequest.class).withPlan(issued.proposal().plan());
        validator.guardedWorkflowStartRequest(filled);
        return new GuardedInput(filled, issued);
    }

    /** The skills a start input names, read before anything else of the input: a list that is not what the tool schema states is SKILLS_REQUIRED. */
    private List<SkillDeclaration> skillsOf(JsonNode raw) {
        if (raw == null) {
            throw skillsRequired(null);
        }
        SkillDeclaration.Declared declared;
        try {
            declared = mapper.readerFor(SkillDeclaration.Declared.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        } catch (JacksonException unbound) {
            throw skillsRequired(unbound);
        }
        if (declared == null || !inputRules.validate(declared).isEmpty()) {
            throw skillsRequired(null);
        }
        return declared.skills();
    }

    private static WorkflowFailure skillsRequired(Throwable cause) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.skillsRequired"), details("field", "skills", "reason", "SKILLS_REQUIRED"), cause);
    }

    /** The skills a start names, without the space around their texts; at least one must be a skill Sennard knows, so a placeholder such as "none" does not stand for a choice. */
    private static List<SkillDeclaration> declaredSkills(List<SkillDeclaration> declared, Set<String> known) {
        List<SkillDeclaration> skills = declared.stream().map(SkillDeclaration::stripped).toList();
        List<String> names = skills.stream().map(SkillDeclaration::bareId).toList();
        if (new HashSet<>(names).size() != names.size() || names.stream().noneMatch(known::contains)) {
            throw skillsRequired(null);
        }
        return skills;
    }

    /** The advice a classification gave is owned by the skill-classification migration; until it is on this server a start records no advice to compare. */
    private static SkillDeclaration.Recorded skillDeclaration(List<SkillDeclaration> skills, String operationKey, ClassificationAdvice.StartAdvice advice) {
        if (skills.isEmpty()) {
            // A kind whose skills are optional may start with none: the receipt then records no declaration (its schema requires at least one skill in one).
            return null;
        }
        SkillDeclaration.Advice recorded = advice != null ? SkillDeclaration.Advice.compared(advice, skills) : operationKey == null ? null : new SkillDeclaration.Advice(operationKey, false);
        return new SkillDeclaration.Recorded(skills, recorded, null);
    }

    /** The receipt of a run that has just started. */
    private WorkflowReceipt newRunningReceipt(WorkflowPlan plan, SkillDeclaration.Recorded declaration) {
        String runId = "run-" + plan.taskId() + "-" + store.nextRunSequence(clock.instant().toString());
        WorkflowReceipt receipt = WorkflowReceipt.started(runId, plan.withState(RunState.RUNNING).withRunningPointers(), declaration);
        assertReceipt(receipt);
        return receipt;
    }

    /**
     * Plans, opens a root, claims a lease and starts the run in one transaction: a failure at any step leaves nothing.
     *
     * @param providers the skill registry; one read of it serves the whole start (the known skills, the plan and the claim's check of that plan). The entry reads it before this
     *            transaction opens
     * @param reads where the new root's locations physically are, resolved by the entry before this transaction ({@link #planStartReads})
     * @param baseline the first walk of the write roots, made by the entry before this transaction ({@link #planStartBaseline}); {@link StartBaseline.None} for a start with no change plan. This transaction
     *            writes its rows (the baseline is part of the start, so a failure of it leaves no run) and reads no file
     */
    @Transactional
    public MinimalStartResult startMinimalWorkflow(JsonNode raw, WorkflowCall call, List<SkillProvider> providers, Set<String> installed, FileReads reads, StartBaseline baseline, OperationKey operation) {
        return receipts.once(operation, MinimalStartResult.class, () -> startMinimal(raw, call, providers, installed, reads, baseline, null));
    }

    /** A start with the advice JEV gave for its request, which the run records and the answer carries. */
    @Transactional
    public MinimalStartResult startMinimalWorkflow(JsonNode raw, WorkflowCall call, List<SkillProvider> providers, Set<String> installed, FileReads reads, StartBaseline baseline,
            ClassificationAdvice.StartAdvice advice, OperationKey operation) {
        return receipts.once(operation, MinimalStartResult.class, () -> startMinimal(raw, call, providers, installed, reads, baseline, advice));
    }

    /** Where a start's baseline is read from: the write roots to walk and the files of its change plan. */
    public record StartScope(List<Path> roots, Set<Path> plan) {
    }

    /** What the entry read for a start before its transaction: the walk it made, or that it made none because the start has no baseline to take. */
    public sealed interface StartBaseline {

        /** The scope the entry walked and what the walk found. */
        record Taken(StartScope scope, JournalScanner.Scan scan) implements StartBaseline {
        }

        /** No walk: the start has no change plan, or names no root and workspace the start itself would accept (it refuses those in its own order). */
        record None() implements StartBaseline {
        }
    }

    /**
     * The roots and the change plan of a start that has one, for the entry to walk before the start's transaction ({@link #planStartReads}): rows and paths only, no file. Empty when the
     * start has no change plan (it stays in its planning phase and takes no baseline) or names a root this session does not own or no workspace (the start refuses those itself, in its
     * own order). What the start finds different from this when it runs (a root that moved between the two) is refused there as a conflict, and the walk is not used.
     */
    @Transactional(readOnly = true)
    public Optional<StartScope> planStartBaseline(JsonNode raw, WorkflowCall call) {
        MinimalStartRequest request = minimalStartRequest(raw);
        if (request.changePlan() == null || request.changePlan().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> workspace;
        List<String> included;
        if (request.rootId() == null) {
            workspace = startWorkspace(request, call);
            included = request.writeTargets();
        } else if (continuity.ownsRoot(call, request.rootId())) {
            ConvergenceRoot root = requireSnapshot(request.rootId()).root();
            workspace = Optional.of(root.frame().workspace().locator());
            included = root.taskEnvelope().scope().included();
        } else {
            workspace = Optional.empty();
            included = List.of();
        }
        return workspace.map(located -> {
            ChangeJournalScope.Scope scope = journalScopes.scope(located, included);
            return new StartScope(scope.roots(), journalScopes.plan(scope, request.changePlan(), "changePlan"));
        });
    }

    /**
     * The baseline this start writes: empty for a start without a change plan; otherwise the entry's walk, which must be of the same roots and plan the start found. A start that has a
     * plan and no walk, or another's walk (the continued root moved after the entry's read), is refused as a conflict.
     */
    private Optional<StartBaseline.Taken> baselineOf(StartBaseline baseline, StartScope expected, Set<Path> changePlan) {
        if (changePlan.isEmpty()) {
            return Optional.empty();
        }
        return switch (baseline) {
            case StartBaseline.Taken taken when taken.scope().equals(expected) -> Optional.of(taken);
            case StartBaseline.Taken taken -> throw journalChangedDuringScan();
            case StartBaseline.None none -> throw journalChangedDuringScan();
        };
    }

    private static WorkflowFailure journalChangedDuringScan() {
        return new WorkflowFailure(WorkflowCode.REQUEST_CONFLICT, msg("workflow.journal.changedDuringScan"), details("reason", "JOURNAL_CHANGED_DURING_SCAN"));
    }

    private MinimalStartResult startMinimal(JsonNode raw, WorkflowCall call, List<SkillProvider> providers, Set<String> installed, FileReads reads, StartBaseline baseline,
            ClassificationAdvice.StartAdvice advice) {
        MinimalStartRequest request = minimalStartRequest(raw);
        WorkflowKind kind = request.effectiveKind();
        List<SkillDeclaration> skills = request.skills() == null || request.skills().isEmpty() ? List.of() : declaredSkills(request.skills(), installed);
        Optional<String> workspace = startWorkspace(request, call);
        String rootId = request.rootId();
        if (rootId != null && !continuity.ownsRoot(call, rootId)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.startWorkflowRootNotOwned", "rootId", rootId), details("field", "rootId", "reason", "ROOT_NOT_OWNED", "rootId", rootId));
        }
        if (workspace.isEmpty() && rootId == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workspaceRequiredHostReportedSession"), details("field", "workspace"));
        }
        store.requireBlockedPlanGuardForUpdate();
        int priorFileCount = continuity.priorFileCount(call);
        continuity.settle(call);
        if (kind == WorkflowKind.INQUIRY) {
            continuity.requireInquirySlot(call);
        }
        Fresh fresh = rootId == null ? minimalTask(request, workspace.orElseThrow(), priorFileCount) : null;
        Continued continued = rootId == null ? null : continuedRoot(rootId, request);
        TaskEnvelope task = continued != null ? continued.root().taskEnvelope() : fresh.task();
        if (fresh != null) {
            validator.planWorkflowRequest(task);
        }
        // The plan is checked against the write roots before anything is written: a plan outside them refuses the start and leaves nothing.
        ChangeJournalScope.Scope journalScope = journalScopes.scope(continued != null ? continued.root().frame().workspace().locator() : workspace.orElseThrow(), task.scope().included());
        Set<Path> changePlan = journalScopes.plan(journalScope, request.changePlan() == null ? List.of() : request.changePlan(), "changePlan");
        // The walk the entry made belongs to the roots and the plan it was made for; a start that finds others (the continued root moved after the entry's read) is not given another's baseline.
        Optional<StartBaseline.Taken> taken = baselineOf(baseline, new StartScope(journalScope.roots(), changePlan), changePlan);
        WorkflowPlan plan = plan(PlanWorkflowRequest.bare(task), call, providers);
        List<String> needTargets = plan.stages().stream().filter(stage -> !stage.requiredArtifacts().isEmpty()).map(WorkflowPlan.Stage::requiredCapability).toList();
        if (!needTargets.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.startWorkflowFixArtifactTargets"), details("field", "stages", "capabilities", needTargets));
        }
        if (plan.state() != RunState.READY) {
            throw bodyError(plan.errors().get(0));
        }
        String minimalTarget = request.target() == null ? "" : request.target().strip();
        if (continued == null) {
            ConvergenceFrame frame = fresh.frame();
            List<String> surface = ConvergenceLogic.writeSurface(task, frame);
            Path physical = reads.identity(frame.workspace().locator(), surface, false).workspacePhysical();
            assertPlanUsable(planComparison(kind, task, plan, frame, physical, changePlan, surface, minimalTarget));
        } else {
            assertPlanUsable(planComparison(continued.root(), kind, plan, changePlan));
        }
        ConvergenceRoot root = continued != null ? continued.root() : openForStart(fresh, call, reads);
        if (continued == null) {
            store.recordPlanTarget(root.rootId(), minimalTarget);
        }
        AttemptProposal proposal = new AttemptProposal(ContractValidator.CONTRACT_VERSION, root.rootId(), root.revision(), null, null, plan,
                continued != null ? continued.outputTargets() : request.writeTargets(), continued != null ? continued.priorFailure() : null);
        // A start is one transaction: a claim that is refused here rolls the whole start back.
        // A root this start continues may have given its files up to another session's root: the claim takes them back or is refused with ROOT_CONFLICT.
        Hold hold = new Hold(call, reads, RootConflictTerms.Entry.START);
        AttemptLease lease = switch (claim(withRootDocuments(proposal, root), call, false, providers, hold)) {
            case ClaimOutcome.Leased leased -> leased.lease();
            case ClaimOutcome.Refused refused -> throw refused.failure();
        };
        WorkflowReceipt receipt = startRun(new GuardedStartRequest(ContractValidator.CONTRACT_VERSION, lease.leaseId(), lease.rootRevision(), plan, skills, request.classificationOperationKey()), skills, null, advice, kind, hold, false);
        continuity.prepareWorkflowStart(call, root.rootId(), isChild(call), kind);
        // Only database rows here (no file is read): the journal row makes the run's phase planning, and the baseline the entry walked before this transaction is written with it, so a start
        // whose baseline cannot be written leaves no run. A start without a change plan has none to take and stays in planning.
        long planRevision = journal.submitPlan(receipt.runId(), changePlan);
        List<String> unreadable = taken.map(walk -> {
            ScanBasis basis = journal.baselineBasis(receipt.runId(), new LinkedHashSet<>(journalScope.roots()));
            return journal.captureBaseline(basis, walk.scan()).stream().map(ChangePlanService::describe).sorted().toList();
        }).orElse(List.of());
        MinimalStartResult.ChangeJournalState journalState = new MinimalStartResult.ChangeJournalState(journal.phase(receipt.runId()).orElseThrow(), planRevision, unreadable);
        MinimalStartResult.LateAdoption adoption = null;
        if (priorFileCount > 0) {
            MessageRef label = msg("workflowRecord.lateAdoption.label");
            adoption = new MinimalStartResult.LateAdoption(messages.render(label, call.locale()), label.key(), Map.of(), priorFileCount);
        }
        return new MinimalStartResult(ContractValidator.CONTRACT_VERSION, task.taskId(), root.rootId(), receipt.runId(), receipt.revision(), receipt.state(),
                plan.stages().stream().map(stage -> new MinimalStartResult.StageRef(stage.stageId(), stage.requiredCapability())).toList(), fresh == null ? List.of() : fresh.defaultsUsed(), adoption, journalState, advice,
                continuity.capacity(call));
    }

    WorkflowContinuity.Capacity sessionCapacity(WorkflowCall call) {
        return continuity.capacity(call);
    }

    /** Where the run's files are, and the revision the caller must have seen: the run must be running. */
    public record RunScope(String runId, long revision, ChangeJournalScope.Scope scope) {
    }

    /**
     * The workspace and write roots of a running run, for the change journal. Reads rows only: the file reads of the journal happen outside any transaction.
     *
     * @param expectedRevision the revision the caller last saw, or null to take the current one
     */
    @Transactional(readOnly = true)
    public RunScope runScope(String runId, Long expectedRevision) {
        WorkflowReceipt receipt = requireRun(runId);
        if (expectedRevision != null && expectedRevision != receipt.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", receipt.revision()));
        }
        if (receipt.state() != RunState.RUNNING) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.journal.runNotRunning"), details("state", receipt.state()));
        }
        GuardedRun binding = guardedBinding(runId);
        if (binding == null) {
            throw new WorkflowFailure(WorkflowCode.RUN_NOT_FOUND, msg("workflow.service.runFoundWorkflowStorage"), details("runId", runId));
        }
        ConvergenceRoot root = binding.root();
        return new RunScope(runId, receipt.revision(), journalScopes.scope(root.frame().workspace().locator(), root.taskEnvelope().scope().included()));
    }

    /** Linearizes the plan submission with Stop and abort, after the file walk has completed. */
    @Transactional
    public <T> T writeChangePlan(RunScope run, Set<Path> files, Supplier<T> write) {
        store.requireBlockedPlanGuardForUpdate();
        store.readRunIdForUpdate(run.runId());
        runScope(run.runId(), run.revision());
        ConvergenceRoot root = guardedBinding(run.runId()).root();
        WorkflowReceipt receipt = requireRun(run.runId());
        assertPlanUsable(planComparison(root, storedKind(run.runId()), receipt.plan(), files));
        return write.get();
    }

    /** One failed start: a Sennard infrastructure failure must not leave the session's edits refused. Runs in its own transaction, after the start rolled back. */
    @Transactional
    public void recordStartFailure(WorkflowCall call, WorkflowCode code) {
        continuity.recordStartFailure(call, code);
    }

    /** The working directory the request names, or the one the host reported for this session; the server's own directory says nothing about the caller's. */
    private Optional<String> startWorkspace(MinimalStartRequest request, WorkflowCall call) {
        return Optional.ofNullable(request.workspace())
                .or(() -> board.readEntry(call.host(), call.sessionId()).map(BoardEntry::cwd).filter(cwd -> Path.of(cwd).isAbsolute()));
    }

    private ConvergenceRoot openForStart(Fresh fresh, WorkflowCall call, FileReads reads) {
        OpenConvergenceRootRequest request = new OpenConvergenceRootRequest(ContractValidator.CONTRACT_VERSION, null, fresh.task(), fresh.frame(), List.of());
        validator.openConvergenceRootRequest(request);
        PlanBuilder.validateWorkUnitGraph(request.taskEnvelope());
        assertConvergenceFrame(request.frame());
        assertMcpBacked(request.taskEnvelope());
        // A start is one transaction: a conflict here rolls the whole start back.
        return switch (insertRoot(request, null, RootConflictTerms.Entry.START, call, reads)) {
            case Inserted.Opened opened -> opened.root();
            case Inserted.Blocked blocked -> throw blocked.conflict();
        };
    }

    private record Fresh(TaskEnvelope task, ConvergenceFrame frame, List<String> defaultsUsed) {
    }

    private record Continued(ConvergenceRoot root, List<String> outputTargets, AttemptProposal.PriorFailure priorFailure) {
    }

    /**
     * The input of {@code start_workflow}, bound to its record and held to the rules of its constraints. The first refusal names the field it is about and the
     * catalog sentence of the constraint; where Jackson refuses the input (a key the tool does not take, a value of another JSON type than the schema names) the
     * field is the one the failing value is at.
     */
    private MinimalStartRequest minimalStartRequest(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.ROOT));
        }
        boolean skillsRequired = kindNamedIn(raw).skillsRequired();
        MinimalStartRequest request;
        try {
            request = MinimalStartRequest.reader(mapper).readValue(raw);
        } catch (JacksonException unbound) {
            if (skillsRequired) {
                skillsOf(raw);
            }
            String field = unbound instanceof UnrecognizedPropertyException unknown ? unknown.getPropertyName() : failingField(unbound);
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.ROOT), details("field", field), unbound);
        }
        Set<ConstraintViolation<MinimalStartRequest>> violations = inputRules.validate(request);
        if (!violations.isEmpty()) {
            // Whatever else is wrong, a list of skills that is not what the tool schema states answers SKILLS_REQUIRED with what to submit (for a kind that requires them).
            if (skillsRequired) {
                skillsOf(raw);
            }
            throw refusal(violations.stream().min(Comparator.comparing((ConstraintViolation<MinimalStartRequest> violation) -> violation.getPropertyPath().toString())).orElseThrow());
        }
        assertKindInput(request);
        if (request.priorFailure() != null && request.rootId() == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.PRIOR), details("field", "priorFailure"));
        }
        if (request.workspace() != null) {
            assertAbsoluteWorkspace(request.workspace());
        }
        assertNoPlaceholders(request);
        return request;
    }

    /** The kind a start input names (the one the kind's tool put there); an input that names none is a change, and a name that is no kind is refused by the binding. */
    private static WorkflowKind kindNamedIn(JsonNode raw) {
        JsonNode named = raw.path("kind");
        return named.isString() ? Arrays.stream(WorkflowKind.values()).filter(kind -> kind.name().equals(named.asString())).findFirst().orElse(WorkflowKind.CHANGE) : WorkflowKind.CHANGE;
    }

    /**
     * What the kind's properties say the input must and must not carry: a kind that requires {@code skills} refuses a start without them, a kind with files of its own has stages, a kind
     * that acts on a target names it (and one that does not takes none), and a kind without files of its own has no change plan, no root to continue and no earlier failure.
     */
    private static void assertKindInput(MinimalStartRequest request) {
        WorkflowKind kind = request.effectiveKind();
        if (kind.skillsRequired() && (request.skills() == null || request.skills().isEmpty())) {
            throw skillsRequired(null);
        }
        if (kind.allowsFileEdits() && request.stages().isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.LIST, "field", "stages", "size", "nonEmpty"), details("field", "stages"));
        }
        if (kind.takesTarget() != (request.target() != null && !request.target().isBlank())) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.TARGET), details("field", "target"));
        }
        if (!kind.allowsFileEdits() && (request.changePlan() != null || request.rootId() != null || request.priorFailure() != null)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg(MinimalStartRequest.ROOT), details("field", request.changePlan() != null ? "changePlan" : request.rootId() != null ? "rootId" : "priorFailure"));
        }
    }

    /**
     * A value an example printed for the model to replace with one of its own is not a value of the start. The input passed its shape, so it would be started with the example's
     * words as its goal, criterion, path or skill reason; it is refused here, before anything of the start is read, and the refusal says what to put there.
     */
    private void assertNoPlaceholders(MinimalStartRequest request) {
        placeholders.assertNone("goal", PlaceholderTexts.Need.GOAL, request.goal());
        assertNoPlaceholders(request.skills());
        placeholders.assertNone("acceptanceCriteria", PlaceholderTexts.Need.CRITERION, request.acceptanceCriteria());
        placeholders.assertNone("writeTargets", PlaceholderTexts.Need.PATH, request.writeTargets());
        placeholders.assertNone("changePlan", PlaceholderTexts.Need.PATH, request.changePlan());
        placeholders.assertNone("stages", PlaceholderTexts.Need.STAGE, request.stages());
        placeholders.assertNone("target", PlaceholderTexts.Need.TARGET, request.target());
    }

    private void assertNoPlaceholders(List<SkillDeclaration> skills) {
        if (skills == null) {
            return;
        }
        for (SkillDeclaration skill : skills) {
            placeholders.assertNone("skills", PlaceholderTexts.Need.SKILL, skill.skillId());
            placeholders.assertNone("skills", PlaceholderTexts.Need.REASON, skill.reason());
        }
    }

    /** The name of the field the failing value of a binding is at: the first step of the path Jackson reports. */
    private static String failingField(JacksonException unbound) {
        return unbound instanceof DatabindException bound && !bound.getPath().isEmpty() ? bound.getPath().getFirst().getPropertyName() : null;
    }

    /** The refusal of an input whose constraint failed: the constraint's message is the catalog key, and the field is the property path without the positions in a list. */
    private static WorkflowFailure refusal(ConstraintViolation<?> violation) {
        List<String> names = new ArrayList<>();
        violation.getPropertyPath().forEach(node -> {
            if (node.getKind() == ElementKind.PROPERTY) {
                names.add(node.getName());
            }
        });
        String field = String.join(".", names);
        String key = violation.getMessage();
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, MinimalStartRequest.NAMES_FIELD.contains(key) ? msg(key, "field", field, "size", "nonEmpty") : msg(key), details("field", field));
    }

    private static void assertAbsoluteWorkspace(String workspace) {
        boolean absolute;
        try {
            absolute = Path.of(workspace).isAbsolute();
        } catch (InvalidPathException invalid) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workspaceAbsolutePath"), details("field", "workspace"), invalid);
        }
        if (!absolute) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.workspaceAbsolutePath"), details("field", "workspace"));
        }
    }

    /** What a minimal start derives a target artifact's digest from: the objective and where the target is. */
    private record TargetBinding(String objective, String locator) {
    }

    /** The task envelope and the frame a minimal start fills in for what the caller did not state. */
    private Fresh minimalTask(MinimalStartRequest request, String workspace, int priorFileCount) {
        String goal = request.goal().strip();
        List<String> writeTargets = request.writeTargets();
        List<String> acceptanceCriteria = request.acceptanceCriteria();
        // Stored in the root locale like every stored message; readers map it back to its key.
        List<String> constraints = new ArrayList<>();
        if (priorFileCount > 0) {
            constraints.add(messages.render(msg("workflowRecord.lateAdoption.constraint", "priorFileCount", priorFileCount)));
        }
        if (request.target() != null) {
            // What the work acts on (a repository, a branch, an install location): kept in the task, where the run's reader finds it.
            constraints.add(messages.render(msg("workflow.kind.targetConstraint", "target", request.target().strip())));
        }
        TaskEnvelope task = new TaskEnvelope(ContractValidator.CONTRACT_VERSION, "task-" + UUID.randomUUID(), goal, new TaskEnvelope.Scope(writeTargets, List.of()), acceptanceCriteria, RiskLevel.MEDIUM,
                List.of(new TaskEnvelope.WorkUnit("work", goal, List.of(), writeTargets)), null, request.stages(), constraints,
                new TaskEnvelope.Authorization(List.of(), List.of(), List.of()), new TaskEnvelope.Decision(TaskEnvelope.Complexity.COMPLEX, false), new TaskEnvelope.Orchestration(true, true));

        java.nio.file.Path name = java.nio.file.Path.of(workspace).getFileName();
        List<ConvergenceFrame.TargetArtifact> targets = new ArrayList<>();
        for (int i = 0; i < writeTargets.size(); i++) {
            String locator = writeTargets.get(i);
            targets.add(new ConvergenceFrame.TargetArtifact("target-" + (i + 1), ConvergenceFrame.TargetRole.TARGET, locator, digests.of(new TargetBinding(goal, locator))));
        }
        ConvergenceLimits limits = limitSource.current();
        ConvergenceFrame frame = new ConvergenceFrame(ContractValidator.CONTRACT_VERSION, new ConvergenceFrame.Workspace(name == null ? "workspace" : name.toString(), workspace),
                List.of(new ConvergenceFrame.ControlArtifact("acceptance-criteria", ConvergenceFrame.ControlRole.PASS_CONDITION, "taskEnvelope.acceptanceCriteria", digests.of(acceptanceCriteria))),
                targets, new ConvergenceFrame.OperationalSettings(limits.maxAttemptsPerEpoch(), limits.maxEpochs(), 300));
        List<String> defaults = new ArrayList<>(List.of("taskId", "scope", "riskLevel", "workUnits", "constraints", "authorization", "decision", "orchestration", "frame", "outputTargets"));
        if (request.workspace() == null) {
            defaults.add("workspace");
        }
        return new Fresh(task, frame, defaults);
    }

    private Continued continuedRoot(String rootId, MinimalStartRequest request) {
        SnapshotDocs snapshot = requireSnapshot(rootId);
        ConvergenceRoot root = snapshot.root();
        if (root.release() != null) {
            throw releasedRootError(root, root.release());
        }
        if (root.state() != RootState.OPEN) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.startWorkflowRootNotOpen", "rootId", rootId, "state", root.state()), details("field", "rootId", "reason", "ROOT_NOT_OPEN", "rootId", rootId, "state", root.state()));
        }
        String field = continuedTaskMismatch(request, root);
        if (field != null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.startWorkflowRootTaskMismatch", "field", field), details("field", field, "reason", "ROOT_TASK_MISMATCH", "rootId", rootId));
        }
        long epoch = root.currentEpoch();
        List<AttemptOutcome> outcomes = snapshot.outcomes().stream().filter(item -> item.epoch() == epoch).toList();
        AttemptOutcome latest = outcomes.isEmpty() ? null : outcomes.getLast();
        AttemptOutcome failed = latest != null && latest.state() != AttemptState.PASSED ? latest : null;
        boolean hasPrior = request.priorFailure() != null;
        if ((failed != null) != hasPrior) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.startWorkflowPriorFailure", "required", failed != null ? "yes" : "no"), details("field", "priorFailure", "reason", failed != null ? "PRIOR_FAILURE_REQUIRED" : "PRIOR_FAILURE_UNEXPECTED", "rootId", rootId));
        }
        List<AttemptLease> consumed = snapshot.leases().stream().filter(lease -> lease.state() == LeaseState.CONSUMED).toList();
        List<String> outputTargets = consumed.isEmpty() ? root.taskEnvelope().scope().included() : consumed.getLast().outputTargets();
        AttemptProposal.PriorFailure priorFailure = null;
        if (failed != null) {
            MinimalStartRequest.PriorFailure named = request.priorFailure();
            priorFailure = new AttemptProposal.PriorFailure(failed.failureFingerprint() == null ? "" : failed.failureFingerprint(), named.hypothesis(), named.changeSummary(), named.discriminator(),
                    named.evidenceRefs());
        }
        return new Continued(root, outputTargets, priorFailure);
    }

    private static String continuedTaskMismatch(MinimalStartRequest request, ConvergenceRoot root) {
        TaskEnvelope task = root.taskEnvelope();
        if (!Objects.equals(request.goal().strip(), task.objective().strip())) {
            return "goal";
        }
        if (!Objects.equals(request.stages(), task.requiredCapabilities())) {
            return "stages";
        }
        if (!Objects.equals(request.acceptanceCriteria(), task.acceptanceCriteria())) {
            return "acceptanceCriteria";
        }
        if (!Objects.equals(request.writeTargets(), task.scope().included())) {
            return "writeTargets";
        }
        String workspace = request.workspace();
        return workspace != null && !Objects.equals(workspace, root.frame().workspace().locator()) ? "workspace" : null;
    }

    /** The failure a plan recorded as an error of its own, raised again for the caller who asked to start it. */
    private WorkflowFailure bodyError(ApiResult.ErrorBody body) {
        MessageRef ref = body.messageKey() != null ? new MessageRef(body.messageKey(), body.params() == null ? Map.of() : body.params()) : msg("workflow.service.unkeyedFailure", "message", body.message());
        Map<String, Object> details = body.details() == null ? null : mapper.convertValue(body.details(), DETAILS);
        return new WorkflowFailure(WorkflowCode.valueOf(body.code()), ref, details);
    }

    // ---- get_convergence_status and resolve_convergence_gate

    @Transactional(readOnly = true)
    public ConvergenceStatus getConvergenceStatus(String rootId) {
        ConvergenceStatus status = buildStatus(requireSnapshot(rootId));
        validator.convergenceStatus(status);
        return status;
    }

    /** The run's current root, for state-aware MCP continuation advice. */
    @Transactional(readOnly = true)
    public Optional<ConvergenceRoot> workflowRoot(String runId) {
        GuardedRun binding = guardedBinding(runId);
        return binding == null ? Optional.empty() : Optional.of(binding.root());
    }

    @Transactional
    public ConvergenceStatus resolveConvergenceGate(JsonNode raw, WorkflowCall call, OperationKey operation) {
        return receipts.once(operation, ConvergenceStatus.class, () -> resolveGate(raw, call));
    }

    private ConvergenceStatus resolveGate(JsonNode raw, WorkflowCall call) {
        ResolveConvergenceGateRequest request = validator.read("resolve-convergence-gate-request.v1.schema.json", raw, ResolveConvergenceGateRequest.class);
        ConvergenceReview review = request.review();
        if (review.observedReviewer() != null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.observedReviewerServerOwnedCallersSend"), details("reason", "SERVER_OWNED_FIELD"));
        }
        SnapshotDocs snapshot = requireSnapshot(request.rootId());
        ConvergenceRoot root = snapshot.root();
        long expected = request.expectedRevision();
        long actual = root.revision();
        if (expected != actual || review.rootRevision() != actual) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.convergenceReviewTargetCurrentRoot"), details("expectedRevision", expected, "reviewRevision", review.rootRevision(), "actualRevision", actual));
        }
        if (!Objects.equals(review.rootId(), root.rootId()) || review.epoch() != root.currentEpoch()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.convergenceReviewTargetsDifferentRoot"));
        }
        if (root.state() != RootState.NEEDS_REVIEW) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.gatedConvergenceRootResolved"), details("state", root.state()));
        }
        // Fresh context stays the root's declaration. A declared reviewer naming an actor Sennard observed as a lease claimer or a recorder without host-observed identity contradicts itself.
        if (!review.freshContext().confirmed() || review.freshContext().evidenceRef().isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.frameReviewDeclaresMadeFresh"));
        }
        Set<String> observedActors = new HashSet<>();
        snapshot.leases().stream().filter(lease -> lease.state() == LeaseState.CONSUMED && lease.actorId() != null).forEach(lease -> observedActors.add(lease.actorId()));
        if (!call.observedCaller().hostObserved()) {
            observedActors.add(call.actorId());
        }
        if (observedActors.contains(review.reviewerActorId())) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.declaredFrameReviewerActorSennard"), details("reason", "REVIEWER_IS_OBSERVED_ROOT"));
        }
        List<ObservedCaller> implementers = new ArrayList<>();
        snapshot.runIds().forEach(runId -> {
            String text = store.getRun(runId);
            if (text != null) {
                WorkflowReceipt otherRun = parseReceipt(text);
                implementers.addAll(StageGates.implementationRecorders(otherRun, otherRun.plan()));
            }
        });
        Independence reviewerIndependence = StageGates.independence(call.observedCaller(), implementers);
        if (reviewerIndependence == Independence.VIOLATED) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.observedReviewerImplementedDelegatedWork"), details("reason", "REVIEWER_NOT_INDEPENDENT"));
        }
        ConvergenceReview recorded = review.withObservedReviewer(ConvergenceReview.ObservedReviewer.of(call.observedCaller(), reviewerIndependence));
        assertReviewRoute(recorded);

        ConvergenceRoot updated = root.bumped(recorded.reviewedAt());
        long previousEpoch = root.currentEpoch();
        ReviewRoute route = recorded.route();
        if (route.resultingState() != null) {
            // A stopped root keeps holding its write surface; only an approved replacement releases it.
            updated = updated.withState(route.resultingState());
        } else {
            if (updated.currentEpoch() >= updated.frame().operationalSettings().maxEpochs()) {
                updated = updated.withState(RootState.NEEDS_USER);
            } else {
                ConvergenceFrame proposedFrame = recorded.proposedFrame();
                assertConvergenceFrame(proposedFrame);
                ConvergenceDigests next = digests.convergence(root.taskDigest(), proposedFrame);
                if (!Objects.equals(next.workspaceDigest(), root.workspaceDigest()) || !Objects.equals(next.operationalDigest(), root.operationalDigest())) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.semanticsPreservingReviewChangeWorkspace"));
                }
                if (Objects.equals(next.targetDigest(), root.targetDigest())) {
                    throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.newConvergenceEpochRequiresVerified"));
                }
                updated = updated.nextEpoch(proposedFrame, next);
            }
        }
        validator.convergenceRoot(updated);
        ReviewRow reviewRow = new ReviewRow(recorded.reviewId(), root.rootId(), recorded.epoch(), mapper.writeValueAsString(recorded), recorded.reviewedAt().toString());
        if (!store.updateConvergenceRoot(rootRow(updated), actual, previousEpoch, reviewRow)) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.convergenceRootChangedWhileRecording"));
        }
        return buildStatus(requireSnapshot(root.rootId()));
    }

    private static void assertReviewRoute(ConvergenceReview review) {
        if (review.evidenceRefs().isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.service.convergenceReviewRequiresEvidence"));
        }
        ReviewRoute route = review.route();
        ReviewClassification classification = review.classification();
        boolean hasFrame = review.proposedFrame() != null;
        if (!classification.allowedRoutes().contains(route)) {
            throw switch (classification) {
                case SEMANTICS_PRESERVING -> new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.semanticsPreservingReviewIncompatibleRoute"), details("route", route));
                case SEMANTICS_CHANGING -> new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.semanticsChangingReviewsReturnProposed"));
                case AMBIGUOUS -> new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.ambiguousFrameReviewsRoutePanel"));
            };
        }
        if (classification == ReviewClassification.SEMANTICS_PRESERVING && route == ReviewRoute.RESUME_NEW_EPOCH
                && (!review.comparability().comparable() || !hasFrame)) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.resumingNewEpochRequiresComparable"));
        }
        if (classification == ReviewClassification.SEMANTICS_CHANGING && !hasFrame) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.semanticsChangingReviewsReturnProposed"));
        }
    }

    private ConvergenceStatus buildStatus(SnapshotDocs snapshot) {
        ConvergenceRoot root = snapshot.root();
        long epoch = root.currentEpoch();
        int attemptsUsed = snapshot.consumedLeases(epoch).size();
        ApiResult.ErrorBody gateError = switch (root.state()) {
            case NEEDS_REVIEW -> attemptsUsed >= root.frame().operationalSettings().maxAttemptsPerEpoch()
                    ? WorkflowResults.errorBody(messages, new WorkflowFailure(WorkflowCode.ATTEMPT_BUDGET_EXHAUSTED, msg("workflow.service.independentReviewRequiredBeforeAnother"), details("rootId", root.rootId(), "epoch", epoch)))
                    : WorkflowResults.errorBody(messages, new WorkflowFailure(WorkflowCode.FRAME_REVIEW_REQUIRED, msg("workflow.service.taskControlFrameChangeRequires"), details("rootId", root.rootId(), "epoch", epoch)));
            case NEEDS_USER -> WorkflowResults.errorBody(messages, new WorkflowFailure(WorkflowCode.FRAME_REVIEW_REQUIRED, msg("workflow.service.proposedChangeRequiresNewUser"), details("rootId", root.rootId(), "epoch", epoch)));
            case OPEN, COMPLETED, ABANDONED -> null;
        };
        ConvergenceFrame.OperationalSettings limits = root.frame().operationalSettings();
        return new ConvergenceStatus(ContractValidator.CONTRACT_VERSION, root, epoch, limits.maxAttemptsPerEpoch(), limits.maxEpochs(), attemptsUsed, Math.max(0, limits.maxAttemptsPerEpoch() - attemptsUsed), snapshot.proposals(), snapshot.leases(), snapshot.outcomes(), snapshot.reviews(),
                snapshot.runIds(), gateError, UnverifiedClaims.forConvergence(root, snapshot.reviews(), snapshot.leases()));
    }

    // ---- record_stage_result, get_workflow_status, finalize_workflow, abort_workflow

    /** The caller's stage result: what the server owns is refused as written, and the input is bound to its record. */
    private StageResult stageResultOf(JsonNode raw) {
        // What the server owns is refused as the caller wrote it: a key that is there, whatever its value, is the caller's claim.
        for (String field : List.of("artifactChecks", "recordedBy")) {
            if (raw != null && raw.has(field)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.serverOwnedField", "field", field), details("reason", "SERVER_OWNED_FIELD", "field", field));
            }
        }
        if (raw != null && raw.has("executionContext")) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.strictMCPStageExecutionRejects"), details("reasonCode", "EXECUTION_CONTEXT_CALLER_SUPPLIED"));
        }
        StageResult result = validator.read("stage-result.v1.schema.json", raw, StageResult.class);
        assertNoPlaceholders(result);
        if (result.hasOutputFile() && result.output().hasOutput()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.outputOutputNullWhenOutputFile"));
        }
        return result;
    }

    /**
     * The free text a person writes in a stage result is a statement of what was done or found, never a placeholder: the evidence notes, the findings, the blockers and the
     * {@code summary} or {@code note} of an inline output. A placeholder is refused as the start tools refuse theirs (INVALID_INPUT, PLACEHOLDER_RETURNED) and nothing is recorded.
     */
    private void assertNoPlaceholders(StageResult result) {
        List<Evidence> evidence = result.evidence();
        for (int i = 0; evidence != null && i < evidence.size(); i++) {
            placeholders.assertNoneInResult("evidence[" + i + "].note", evidence.get(i).note());
        }
        assertNoPlaceholders("findings", result.findings());
        assertNoPlaceholders("blockers", result.blockers());
        JsonNode output = result.output() == null ? null : result.output().output();
        if (output != null && output.isObject()) {
            for (String key : List.of("summary", "note")) {
                JsonNode text = output.get(key);
                if (text != null && text.isString()) {
                    placeholders.assertNoneInResult("output.output." + key, text.stringValue());
                }
            }
        }
    }

    private void assertNoPlaceholders(String field, List<String> texts) {
        for (int i = 0; texts != null && i < texts.size(); i++) {
            placeholders.assertNoneInResult(field + "[" + i + "]", texts.get(i));
        }
    }

    /** The stage a result may be recorded for: the run is running, the stage is its next to record, and a stage with a receipt policy keeps its output inline. */
    private static WorkflowPlan.Stage recordableStage(WorkflowReceipt receipt, StageResult result) {
        String stageIdValue = result.stageId();
        RunState current = receipt.state();
        if (current != RunState.RUNNING) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.stageResultsRequireRunningWorkflow"), details("state", current));
        }
        WorkflowPlan plan = receipt.plan();
        WorkflowPlan.Stage target = plan.stage(stageIdValue).orElseThrow(() -> new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.stageBelongRun"), details("stageId", stageIdValue)));
        if (target.state() != RunState.READY) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.stageResultRecordedOnce"), details("stageId", stageIdValue, "stageState", target.state()));
        }
        int order = target.order();
        for (WorkflowPlan.Stage stage : plan.stages()) {
            if (stage.order() < order && stage.state() != RunState.PASSED) {
                throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.stagesRecordedPlanOrder"), details("requiredStageId", stage.stageId(), "requestedStageId", stageIdValue));
            }
        }
        // Receipt policies compare actors across stages through the stored output, so policy stages stay inline.
        if (result.hasOutputFile() && target.receiptPolicy() != null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.stagesReceiptPolicyRecordTheir"), details("stageId", stageIdValue));
        }
        return target;
    }

    private static void assertRevision(WorkflowReceipt receipt, long expectedRevision) {
        if (expectedRevision != receipt.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", receipt.revision()));
        }
    }

    /**
     * What recording a stage result will judge from files and attachments, as the entry reads it before the recording's transaction: the output file, the frozen request, the
     * schemas the plan froze. Reads the rows only, and refuses what the recording refuses before it reads a file (the same checks, in the same order).
     */
    @Transactional(readOnly = true)
    public ReadPlan planStageReads(JsonNode raw) {
        StageResult result = stageResultOf(raw);
        WorkflowReceipt receipt = requireRun(result.runId());
        assertRevision(receipt, result.expectedRevision());
        WorkflowPlan.Stage target = recordableStage(receipt, result);
        return new ReadPlan(receipt.revision(), gates.requestsToRecord(target, result, receipt.plan().taskDigest()));
    }

    /**
     * Records a stage result. The files it names were read by the entry before this transaction opened ({@link #planStageReads}); the answers are {@code reads}, made for the revision
     * the result is recorded on, and a run that has moved since is refused as stale, not read again.
     */
    @Transactional
    public WorkflowReceipt recordStageResult(JsonNode raw, WorkflowCall call, FileReads reads, OperationKey operation) {
        return receipts.once(operation, WorkflowReceipt.class, () -> recordStage(raw, call, reads));
    }

    private WorkflowReceipt recordStage(JsonNode raw, WorkflowCall call, FileReads reads) {
        StageResult submitted = stageResultOf(raw);
        String stageIdValue = submitted.stageId();
        return change(submitted.runId(), submitted.expectedRevision(), original -> {
            WorkflowReceipt receipt = original;
            assertReadsOf(receipt, reads);
            // Every attachment the result points at (the output file, the artifacts, the evidence) is looked at in this transaction, from its row: it must be there and be the author's.
            Map<AttachmentFileRef, String> attachmentDigests = evidenceAttachments.resolve(submitted, call);
            StageResult result = submitted;
            WorkflowPlan plan = receipt.plan();
            WorkflowPlan.Stage target = recordableStage(receipt, result);
            JsonNode loaded = result.hasOutputFile() ? gates.loadOutputFile(result.outputFile(), reads) : null;
            RunState state = result.state();
            boolean passed = result.passed();
            if (passed && target.executionRequirement() == null && !PlanBuilder.deterministic(target.satisfiedCapabilities())) {
                throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.service.stageWithoutExecutionRequirementStrict", "stageId", stageIdValue));
            }
            ExecutionContext trusted = null;
            if (target.executionRequirement() != null && target.executionRequirement().semantic()) {
                ExecutionAssurance.Binding binding = new ExecutionAssurance.Binding("stage", plan.taskId(), receipt.runId(), stageIdValue, result.expectedRevision());
                ExecutionAssurance.Subject subject = ExecutionAssurance.stageSubject(stageIdValue);
                // Only a passing stage has to be observed. Requiring attestation on a failure would push legitimate unattested failures
                // into abort_workflow, which drops the failure fingerprint. Whatever is recorded keeps the observed model, never refused.
                trusted = passed ? assurance.observeTrusted(call, binding, subject) : assurance.observe(call, binding);
                if (trusted != null) {
                    assurance.assertTrusted(target.executionRequirement(), trusted, subject, binding);
                }
            }
            // What the server owns is written into the result: the observed model, what the server hashed, and the caller as observed.
            StageResult recorded = result.withExecutionContext(trusted);
            recorded = recorded.withArtifactChecks(gates.checkArtifacts(target, recorded, attachmentDigests)).withRecordedBy(call.observedCaller());
            StageResult checked = loaded == null ? recorded : gates.withLoadedOutput(recorded, loaded);
            gates.assertPlannedInputsAvailable(receipt, plan, target, passed);
            gates.assertResultSemantics(target, checked, plan.taskId(), plan.taskDigest(), reads);
            gates.assertStageBundles(target, checked, plan.taskDigest(), reads);
            gates.assertExecutionAssurance(target, recorded, assurance);
            gates.assertDeclaredReceiptPolicy(receipt, plan, target, checked, reads);
            Independence checkerIndependence = passed && StageGates.independentCheck(target) ? StageGates.independence(call.observedCaller(), StageGates.implementationRecorders(receipt, plan)) : Independence.UNOBSERVED;
            if (checkerIndependence == Independence.VIOLATED) {
                throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.observedAuditorImplementedRunDelegated"), details("stageId", stageIdValue, "reason", "AUDITOR_NOT_INDEPENDENT"));
            }
            boolean conditionalConsensus = false;
            if (passed) {
                gates.assertRequiredArtifacts(target, checked);
                conditionalConsensus = gates.assertDeliberationGate(target, checked, reads);
                List<String> actors = new ArrayList<>();
                if (!call.observedCaller().hostObserved()) {
                    actors.add(call.actorId());
                }
                GuardedRun bound = guardedBinding(receipt.runId());
                if (bound != null && bound.lease().actorId() != null) {
                    actors.add(bound.lease().actorId());
                }
                gates.assertMandatoryAuditGate(target, checked, actors);
                gates.assertEvaluationValidityGate(target, checked);
                assertBaselineLineage(receipt, plan, target, checked);
            }
            WorkflowPlan updated = plan.withStageState(stageIdValue, state);
            if (trusted != null) {
                consumeObservation(trusted, ExecutionAssurance.stageSubject(stageIdValue));
            }
            receipt = receipt.withStageResult(recorded)
                    .withClaims(UnverifiedClaims.forStage(target, checked, trusted != null, UnverifiedClaims.externalInputs(plan, target), checkerIndependence == Independence.VERIFIED, conditionalConsensus))
                    .withOpen(result.blockers(), result.blockers());
            if (!passed) {
                receipt = receipt.withState(state).withError(result.error()).withOpen(List.of(), List.of(stageIdValue + ":" + state));
                updated = updated.withState(state).withPointers(state.isUserGate() ? stageIdValue : null, null);
            } else {
                updated = updated.withRunningPointers();
            }
            return receipt.withPlan(updated);
        });
    }

    @Transactional(readOnly = true)
    public WorkflowReceipt getWorkflowStatus(String runId) {
        return requireRun(runId);
    }

    public record SessionRun(String runId, String rootId, long revision, RunState state, String kind) { }

    public record SessionRoot(String rootId, long revision, RootState state) { }

    public record SessionWorkflows(String schemaVersion, List<SessionRun> runs, List<SessionRoot> openRoots) { }

    @Transactional(readOnly = true)
    public SessionWorkflows listSessionWorkflows(WorkflowCall call, String openRootAfter) {
        String correlation = continuity.correlate(call.sessionId());
        var rows = openRootAfter == null
                ? continuity.store().listSessionWorkflows(correlation, call.host(), call.sessionId())
                : continuity.store().listSessionRecoveryPage(correlation, call.host(), call.sessionId(), openRootAfter);
        List<SessionRun> runs = new ArrayList<>();
        List<SessionRoot> openRoots = new ArrayList<>();
        for (var row : rows) {
            if (row.rootId() == null) {
                throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("continuity.layers.sessionInvalid"), details("reason", "SESSION_CORRELATION_AMBIGUOUS"));
            }
            if (row.runId() == null) {
                openRoots.add(new SessionRoot(row.rootId(), row.revision(), RootState.valueOf(row.state())));
            } else {
                runs.add(new SessionRun(row.runId(), row.rootId(), row.revision(), RunState.valueOf(row.state()), row.kind()));
            }
        }
        return new SessionWorkflows(ContractValidator.CONTRACT_VERSION, List.copyOf(runs), List.copyOf(openRoots));
    }

    /** The answers were made for the run as it is: a run that moved after the plan is refused as stale, so the files read are the files of the receipt judged. */
    private static void assertReadsOf(WorkflowReceipt receipt, FileReads reads) {
        if (reads.revision() != receipt.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", reads.revision(), "actualRevision", receipt.revision()));
        }
    }

    /** The state and the open items a run must be free of to finish. */
    private static void assertFinishable(WorkflowReceipt receipt) {
        if (receipt.state() != RunState.RUNNING) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.runningWorkflowFinalized"), details("state", receipt.state()));
        }
        if (receipt.isOpen()) {
            throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.workflowUnresolvedBlockersFindings"), details("blockers", receipt.blockers(), "unresolved", receipt.unresolved()));
        }
    }

    /**
     * What the finish of a run will judge from files again, as the entry reads it before the finishing transaction: for each stage that has a recorded result, what the gates read of
     * it. Reads the rows only, and refuses what the finish refuses before it reads a file.
     */
    @Transactional(readOnly = true)
    public ReadPlan planFinalizeReads(String runId, long expectedRevision) {
        WorkflowReceipt receipt = requireRun(runId);
        assertRevision(receipt, expectedRevision);
        assertFinishable(receipt);
        WorkflowPlan plan = receipt.plan();
        List<ReadRequest> requests = new ArrayList<>();
        for (WorkflowPlan.Stage stage : plan.stages()) {
            receipt.resultOf(stage.stageId()).filter(StageResult::passed).ifPresent(result -> requests.addAll(gates.requestsToFinalize(stage, result, plan.taskDigest())));
        }
        return new ReadPlan(receipt.revision(), requests);
    }

    /**
     * @param observedSkillCalls the skills seen called this session, or null when that record could not be read
     * @param reads the files the finish judges, read by the entry before this transaction ({@link #planFinalizeReads})
     * @param unplanned the files the change journal found changed outside the plan, from the one judgment of {@link ChangePlanService#observe} that the entry made before this transaction
     *        (a scan reads files); a finish with any of them is refused
     */
    @Transactional
    public WorkflowReceipt finalizeWorkflow(String runId, long expectedRevision, Set<String> observedSkillCalls, FileReads reads, List<String> unplanned, WorkflowCall call, OperationKey operation) {
        return receipts.once(operation, WorkflowReceipt.class, () -> observeTransition(runId, call, WorkflowEvents.Transition.FINALIZE,
                () -> finalizeRun(runId, expectedRevision, observedSkillCalls, reads, unplanned)));
    }

    private WorkflowReceipt finalizeRun(String runId, long expectedRevision, Set<String> observedSkillCalls, FileReads reads, List<String> unplanned) {
        return change(runId, expectedRevision, receipt -> {
            assertReadsOf(receipt, reads);
            assertFinishable(receipt);
            // A change outside the plan is the caller's to answer for before the run closes: the plan names it (submit_change_plan), or the change is undone. Abort is not refused for it.
            // It is checked before the stages: a scope violation is told whatever the stage progress is, so that it is not learned only after every stage has passed.
            if (!unplanned.isEmpty()) {
                throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.unplannedChangesBlockFinalize", "count", unplanned.size(), "paths", String.join(", ", unplanned.subList(0, Math.min(ChangePlanService.UNPLANNED_SHOWN, unplanned.size())))),
                        details("reason", "UNPLANNED_CHANGES", "unplanned", unplanned));
            }
            WorkflowPlan plan = receipt.plan();
            for (WorkflowPlan.Stage stage : plan.stages()) {
                String stageId = stage.stageId();
                Optional<StageResult> recorded = receipt.resultOf(stageId);
                StageResult result = recorded.filter(StageResult::passed).orElseThrow(() -> new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.everyPlannedStagePassBefore"),
                        details("stageId", stageId, "stageState", recorded.map(StageResult::state).orElse(RunState.READY))));
                gates.assertPlannedInputsAvailable(receipt, plan, stage, true);
                gates.assertRequiredArtifacts(stage, result);
                if (StageGates.rechecksBundles(stage)) {
                    StageResult checked = result.hasOutputFile() ? gates.withLoadedOutput(result, gates.loadOutputFile(result.outputFile(), reads)) : result;
                    gates.assertResultSemantics(stage, checked, plan.taskId(), plan.taskDigest(), reads);
                    gates.assertStageBundles(stage, checked, plan.taskDigest(), reads);
                    gates.assertMandatoryAuditGate(stage, checked, List.of());
                    gates.assertAuditedAttachmentsIntact(stage, result, reads);
                }
                gates.assertExecutionAssurance(stage, result, assurance);
                gates.assertDeclaredReceiptPolicy(receipt, plan, stage, result, reads);
                gates.assertEvaluationValidityGate(stage, result);
            }
            for (WorkflowPlan.Stage stage : plan.stages()) {
                if (stage.riskGate() == RiskGate.MANDATORY) {
                    if (stage.state() != RunState.PASSED) {
                        throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.service.mandatoryAuditStageDidPass"), details("stageId", stage.stageId()));
                    }
                    break;
                }
            }
            // Shown, never a gate: a host does not show every call to its hooks (a direct slash call, a skill outside Sennard).
            SkillDeclaration.Recorded declaration = receipt.skillDeclaration();
            if (declaration != null) {
                List<String> notObserved = new ArrayList<>();
                if (observedSkillCalls != null) {
                    for (SkillDeclaration skill : declaration.skills()) {
                        String skillId = skill.skillId();
                        if (!observedSkillCalls.contains(skillId.substring(skillId.lastIndexOf(':') + 1).strip())) {
                            notObserved.add(skillId);
                        }
                    }
                }
                receipt = receipt.withSkillDeclaration(declaration.withCallsNotObserved(observedSkillCalls == null ? CallsNotObserved.UNREAD : new CallsNotObserved(List.copyOf(notObserved))));
            }
            return receipt.withState(RunState.PASSED).withPlan(plan.withState(RunState.PASSED).withPointers(null, null)).withError(null);
        });
    }

    /**
     * What {@code resume_workflow} answers: the instance the agent took for its request, as it stands.
     *
     * @param kind the instance's {@link WorkflowKind}
     * @param phase the phase of the kind's process the instance is in
     */
    public record ResumeResult(String schemaVersion, String runId, String rootId, String kind, String phase, String goal, long revision, RunState state) {
    }

    /**
     * The agent takes an open instance for the request it is handling: the way, besides opening a new one, to have a request handled by a workflow. The session's own agent may take
     * any instance of the session, and a subagent only those it opened itself (the host's fact of who the caller is arrives in the call). A finished instance is not taken.
     */
    @Transactional
    public ResumeResult resumeWorkflow(String runId, WorkflowCall call) {
        return resumeRun(runId, null, call);
    }

    /** Explicit PAUSED to RUNNING transition; the stored operation answer makes retries exact. */
    @Transactional
    public ResumeResult resumeWorkflow(String runId, Long expectedRevision, WorkflowCall call, OperationKey operation) {
        if (expectedRevision == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.resumeRevisionRequired"), details("field", "expectedRevision", "reason", "RESUME_REVISION_REQUIRED"));
        }
        return receipts.once(operation, ResumeResult.class, () -> resumeRun(runId, expectedRevision, call));
    }

    private ResumeResult resumeRun(String runId, Long expectedRevision, WorkflowCall call) {
        WorkflowReceipt receipt = requireRun(runId);
        GuardedRun binding = guardedBinding(runId);
        if (binding == null) {
            throw new WorkflowFailure(WorkflowCode.RUN_NOT_FOUND, msg("workflow.service.runFoundWorkflowStorage"), details("runId", runId));
        }
        if (receipt.state().isTerminal()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.resumeRunFinished", "runId", runId, "state", receipt.state()), details("field", "runId", "reason", "RUN_FINISHED", "state", receipt.state()));
        }
        String rootId = binding.root().rootId();
        if (!continuity.mayTake(call, rootId, isChild(call))) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.resumeNotOwned", "runId", runId), details("field", "runId", "reason", "INSTANCE_NOT_OWNED", "runId", runId));
        }
        if (receipt.state() == RunState.PAUSED) {
            if (expectedRevision == null) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.resumeRevisionRequired"), details("field", "expectedRevision", "reason", "RESUME_REVISION_REQUIRED"));
            }
            receipt = lifecycleChange(receipt, expectedRevision, binding, RunState.RUNNING, null);
        } else if (expectedRevision != null && expectedRevision != receipt.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", receipt.revision()));
        }
        continuity.resume(call, rootId);
        WorkflowKind kind = continuity.kindOf(call, rootId);
        String phase = kind.allowsFileEdits() ? journal.phase(runId).map(Enum::name).orElse(kind.firstPhase().id()) : kind.firstPhase().id();
        return new ResumeResult(ContractValidator.CONTRACT_VERSION, runId, rootId, kind.name(), phase, binding.root().taskEnvelope().objective(), receipt.revision(), receipt.state());
    }

    /** A hold is not an attempt result: it retains its lease, stage progress and active root scope. */
    @Transactional
    public WorkflowReceipt pauseWorkflow(String runId, long expectedRevision, String reason, String resumeCondition, WorkflowCall call, OperationKey operation) {
        return receipts.once(operation, WorkflowReceipt.class, () -> {
            WorkflowReceipt receipt = requireRun(runId);
            GuardedRun binding = guardedBinding(runId);
            WorkflowContinuity.TransitionTurn turn = ownedTurn(runId, binding, call);
            WorkflowReceipt paused = lifecycleChange(receipt, expectedRevision, binding, RunState.PAUSED, new WorkflowReceipt.Pause(reason, resumeCondition, clock.instant()));
            continuity.recordTransition(turn, WorkflowEvents.Transition.PAUSE, paused.runId(), paused.revision());
            return paused;
        });
    }

    /**
     * The served turn of a caller that may take the run's instance: the session that opened it, or for a subagent the one that opened it itself. A hold or an abort of a run the caller
     * may not take is refused, so that no session ends or holds another's work.
     */
    private WorkflowContinuity.TransitionTurn ownedTurn(String runId, GuardedRun binding, WorkflowCall call) {
        if (binding == null) {
            throw new WorkflowFailure(WorkflowCode.RUN_NOT_FOUND, msg("workflow.service.runFoundWorkflowStorage"), details("runId", runId));
        }
        return continuity.transitionTurn(call, binding.root().rootId(), isChild(call))
                .orElseThrow(() -> new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.resumeNotOwned", "runId", runId),
                        details("field", "runId", "reason", "INSTANCE_NOT_OWNED", "runId", runId)));
    }

    /** Run and root revisions fence the hold against cancellation and concurrent root gates in the same transaction. */
    private WorkflowReceipt lifecycleChange(WorkflowReceipt before, long expectedRevision, GuardedRun binding, RunState target, WorkflowReceipt.Pause pause) {
        if (expectedRevision != before.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", before.revision()));
        }
        if ((target == RunState.PAUSED && before.state() != RunState.RUNNING) || (target == RunState.RUNNING && before.state() != RunState.PAUSED)
                || binding.outcome() != null || binding.root().state() != RootState.OPEN) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.pauseResumeOpenRequired"), details("state", before.state(), "rootState", binding.root().state(), "reason", "PAUSE_RESUME_NOT_ALLOWED"));
        }
        WorkflowReceipt receipt = before.withState(target).withPlan(before.plan().withState(target)).withPause(pause).withRevision(before.revision() + 1);
        assertReceipt(receipt);
        ConvergenceRoot root = binding.root().bumped(clock.instant());
        try {
            if (!store.updateRun(runRow(receipt), expectedRevision, rootRow(root), binding.root().revision(), null, clock.instant().toString())) {
                throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision));
            }
        } catch (StoreException conflict) {
            if (conflict.code() != StoreCode.STALE_REVISION) {
                throw conflict;
            }
            throw staleRoot(binding.root().revision(), null);
        }
        return receipt;
    }

    /**
     * Closes the instance of a kind that closes when the turn of its request ends: the run finishes as a finalize finishes one (such a kind plans no stage, so nothing is left to pass).
     * Empty when the instance has no run, or its run has ended already.
     */
    @Transactional
    public Optional<String> closeInstance(String rootId) {
        List<String> runs = storedSnapshot(rootId).workflowRunIds();
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        String runId = runs.getLast();
        WorkflowReceipt receipt = requireRun(runId);
        if (receipt.state().isTerminal() || receipt.state() == RunState.PAUSED) {
            return Optional.empty();
        }
        finalizeRun(runId, receipt.revision(), null, new FileReads(receipt.revision(), Map.of()), List.of());
        return Optional.of(runId);
    }

    /**
     * Ends the attempt of a run the caller may take. Only the attempt ends: it counts toward the root's attempt limit, and the root is left as the attempt leaves it (open for a retry, in
     * review at the limit). A root is given up with {@code release_convergence_root}, never by an abort.
     */
    @Transactional
    public WorkflowReceipt abortWorkflow(String runId, long expectedRevision, WorkflowCall call, OperationKey operation) {
        return receipts.once(operation, WorkflowReceipt.class, () -> {
            WorkflowContinuity.TransitionTurn turn = ownedTurn(runId, guardedBinding(runId), call);
            WorkflowReceipt aborted = abortRun(runId, expectedRevision);
            continuity.recordTransition(turn, WorkflowEvents.Transition.ABORT, aborted.runId(), aborted.revision());
            return aborted;
        });
    }

    private static final String STOP_RECHECK_LIMIT_EXHAUSTED = "STOP_RECHECK_LIMIT_EXHAUSTED";
    private static final String ABORTED_AFTER_STOP_VIOLATION = "ABORTED_AFTER_STOP_VIOLATION";

    public enum StopRecheck { SETTLED, RECHECK, LAST_RECHECK, BLOCKED }

    /** Charges a workflow's open run once. A pause, completion or a different run is not charged by a stale Stop. */
    @Transactional
    public StopRecheck recheckAtStop(String rootId, String runId, WorkflowKind kind, int limit) {
        store.requireBlockedPlanGuardForUpdate();
        if (store.readRunIdForUpdate(runId) == null) {
            return StopRecheck.SETTLED;
        }
        WorkflowReceipt receipt = requireRun(runId);
        if (receipt.state() == RunState.BLOCKED && receipt.blockers().contains(STOP_RECHECK_LIMIT_EXHAUSTED)) {
            return StopRecheck.BLOCKED;
        }
        GuardedRun binding = guardedBinding(runId);
        if (receipt.state() != RunState.RUNNING || !openForStop(binding, rootId)) {
            return StopRecheck.SETTLED;
        }
        int claimed = store.claimStopRecheck(rootId, limit);
        if (claimed != 0) {
            store.markStopViolation(runId);
            return claimed == limit ? StopRecheck.LAST_RECHECK : StopRecheck.RECHECK;
        }
        binding = guardedBinding(runId);
        if (!openForStop(binding, rootId)) {
            return StopRecheck.SETTLED;
        }
        PlanComparison plan = planComparison(binding.root(), kind, receipt.plan(), submittedFiles(runId));
        store.markStopViolation(runId);
        change(runId, receipt.revision(), current -> current.withState(RunState.BLOCKED).withPlan(current.plan().blocked())
                .withBlocked(List.of(STOP_RECHECK_LIMIT_EXHAUSTED)).withError(null));
        recordBlockedPlan(plan, runId, STOP_RECHECK_LIMIT_EXHAUSTED);
        return StopRecheck.BLOCKED;
    }

    private static boolean openForStop(GuardedRun binding, String rootId) {
        return binding != null && binding.outcome() == null && binding.root().state() == RootState.OPEN && binding.root().rootId().equals(rootId);
    }

    private WorkflowReceipt observeTransition(String runId, WorkflowCall call, WorkflowEvents.Transition action, Supplier<WorkflowReceipt> mutate) {
        Optional<WorkflowContinuity.TransitionTurn> turn = Optional.ofNullable(guardedBinding(runId))
                .flatMap(binding -> continuity.transitionTurn(call, binding.root().rootId(), isChild(call)));
        WorkflowReceipt changed = mutate.get();
        turn.ifPresent(served -> continuity.recordTransition(served, action, changed.runId(), changed.revision()));
        return changed;
    }

    private WorkflowReceipt abortRun(String runId, long expectedRevision) {
        store.requireBlockedPlanGuardForUpdate();
        store.readRunIdForUpdate(runId);
        GuardedRun binding = guardedBinding(runId);
        WorkflowReceipt aborted = change(runId, expectedRevision, receipt -> {
            RunState state = receipt.state();
            if (!state.canMoveTo(RunState.BLOCKED)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.terminalWorkflowAborted"), details("state", state));
            }
            return receipt.withState(RunState.BLOCKED).withPlan(receipt.plan().blocked()).withBlocked(List.of(AbortBlocker.ABORTED_BY_CALLER.wire())).withError(null);
        });
        if (binding != null && store.hasStopViolation(runId)) {
            PlanComparison plan = planComparison(binding.root(), storedKind(runId), aborted.plan(), submittedFiles(runId));
            recordBlockedPlan(plan, runId, ABORTED_AFTER_STOP_VIOLATION);
        }
        return aborted;
    }

    private Set<Path> submittedFiles(String runId) {
        return journal.phase(runId).isPresent() ? journal.plan(runId).files() : Set.of();
    }

    WorkflowKind storedKind(String runId) {
        String kind = store.runKind(runId);
        return kind == null ? WorkflowKind.CHANGE : WorkflowKind.valueOf(kind);
    }

    private record PlanComparison(BlockedPlanKey key, String workspacePath) {
    }

    private PlanComparison planComparison(ConvergenceRoot root, WorkflowKind kind, WorkflowPlan plan, Set<Path> files) {
        ConvergenceFrame frame = root.frame();
        IdentityRow identity = store.rootIdentity(root.rootId());
        // New and already resolved roots carry the JVM's physical workspace identity.
        Path physical = identity == null ? WorkspaceIdentity.identify(frame.workspace().locator())
                : ConvergenceLogic.storedIdentity(mapper, identity.identityJson(), identity.surfaceDigest()).identity().workspacePhysical();
        return planComparison(kind, root.taskEnvelope(), plan, frame, physical, files, ConvergenceLogic.writeSurface(root), store.planTarget(root.rootId()));
    }

    private PlanComparison planComparison(WorkflowKind kind, TaskEnvelope task, WorkflowPlan plan, ConvergenceFrame frame, Path physical,
            Set<Path> files, List<String> surface, String minimalTarget) {
        Path workspace = Path.of(frame.workspace().locator()).normalize();
        String digest = WorkflowPlanDigest.of(mapper, kind, task, plan, workspace, files, surface, minimalTarget);
        String physicalPath = physical.toString();
        return new PlanComparison(new BlockedPlanKey(Digests.sha256Hex(physicalPath), kind.name(), digest), physicalPath);
    }

    private void assertPlanUsable(PlanComparison plan) {
        BlockedPlanOrigin blocked = store.blockedPlan(plan.key());
        if (blocked != null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.identicalBlockedPlan", "runId", blocked.runId(), "reason", blocked.reason()),
                    details("reason", blocked.reason(), "blockedRunId", blocked.runId(), "planDigest", plan.key().planDigest()));
        }
    }

    private void recordBlockedPlan(PlanComparison plan, String runId, String reason) {
        store.recordBlockedPlan(plan.key(), plan.workspacePath(), runId, reason, clock.instant().toString());
    }

    /** One change of a run: the receipt the caller saw is changed once, and what comes of it is stored as the next revision, with the outcome of the attempt when the change ends it. */
    private WorkflowReceipt change(String runId, long expectedRevision, UnaryOperator<WorkflowReceipt> mutate) {
        WorkflowReceipt before = requireRun(runId);
        if (expectedRevision != before.revision()) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", before.revision()));
        }
        WorkflowReceipt receipt = mutate.apply(before).withRevision(before.revision() + 1);
        assertReceipt(receipt);
        GuardedRun binding = guardedBinding(runId);
        ConvergenceOutcome convergence = binding != null && binding.outcome() == null && (receipt.state().isTerminal() || receipt.state().isUserGate()) ? convergenceOutcome(receipt, binding) : null;
        boolean stored = convergence == null ? store.updateRun(runRow(receipt), expectedRevision, null, null, null, clock.instant().toString())
                : store.updateRun(runRow(receipt), expectedRevision, rootRow(convergence.root()), convergence.expectedRootRevision(), outcomeRow(convergence.document()), clock.instant().toString());
        if (!stored) {
            String current = store.getRun(runId);
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.service.expectedRevisionMatchCurrentRunRevision"), details("expectedRevision", expectedRevision, "actualRevision", current == null ? null : mapper.readValue(current, RunRevision.class).revision()));
        }
        return receipt;
    }

    /** The revision of a stored run, read without the rest of the receipt. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RunRevision(long revision) {
    }

    private record ConvergenceOutcome(ConvergenceRoot root, long expectedRootRevision, AttemptOutcome document) {
    }

    private record GuardedRun(ConvergenceRoot root, AttemptLease lease, AttemptOutcome outcome) {
    }

    private GuardedRun guardedBinding(String runId) {
        GuardedBinding row = store.getGuardedRunBinding(runId);
        return row == null ? null : new GuardedRun(mapper.readValue(row.rootJson(), ConvergenceRoot.class), mapper.readValue(row.leaseJson(), AttemptLease.class),
                row.outcomeJson() == null ? null : mapper.readValue(row.outcomeJson(), AttemptOutcome.class));
    }

    private ConvergenceOutcome convergenceOutcome(WorkflowReceipt receipt, GuardedRun binding) {
        Instant recordedAt = clock.instant();
        long expectedRootRevision = binding.root().revision();
        ConvergenceRoot bumped = binding.root().bumped(recordedAt);
        boolean aborted = AbortBlocker.anyIn(receipt.blockers());
        RunState receiptState = receipt.state();
        boolean passed = receiptState == RunState.PASSED;
        AttemptState state = passed ? AttemptState.PASSED : aborted ? AttemptState.ABORTED : AttemptState.FAILED;
        int attemptsUsed = consumedLeaseCount(bumped.rootId(), bumped.currentEpoch());
        ConvergenceRoot root = bumped;
        if (bumped.state() != RootState.NEEDS_USER && bumped.state() != RootState.ABANDONED) {
            // A user decision ends the attempt, so it escalates even a pending frame review.
            if (receiptState.isUserGate()) {
                root = bumped.withState(RootState.NEEDS_USER);
            } else if (bumped.state() != RootState.NEEDS_REVIEW) {
                root = bumped.withState(passed ? RootState.COMPLETED : attemptsUsed >= bumped.frame().operationalSettings().maxAttemptsPerEpoch() ? RootState.NEEDS_REVIEW : RootState.OPEN);
            }
        }
        // Only bytes the server hashed count as evidence a later retry must move past.
        Set<String> evidence = new LinkedHashSet<>();
        for (StageResult result : receipt.stageResults()) {
            for (ArtifactCheckEntry check : result.artifactChecks() == null ? List.<ArtifactCheckEntry>of() : result.artifactChecks()) {
                if (check.check() == ArtifactCheck.SHA256_MATCHED) {
                    String digest = StageGates.recordedDigest(result, check);
                    if (digest != null) {
                        evidence.add(StageGates.evidenceKey(digest));
                    }
                }
            }
        }
        AttemptOutcome outcome = new AttemptOutcome(ContractValidator.CONTRACT_VERSION, "outcome-" + UUID.randomUUID(), root.rootId(), root.revision(), binding.lease().leaseId(),
                binding.lease().epoch(), binding.lease().ordinal(), receipt.runId(), state, digests.of(receipt),
                passed ? null : ConvergenceLogic.failureFingerprint(digests, receipt), List.copyOf(evidence), recordedAt);
        validator.attemptOutcome(outcome);
        return new ConvergenceOutcome(root, expectedRootRevision, outcome);
    }

    /** The first passed workspace baseline in a convergence lineage is the baseline for the whole lineage, so a re-captured one cannot absorb the task's own changes. */
    private void assertBaselineLineage(WorkflowReceipt receipt, WorkflowPlan plan, WorkflowPlan.Stage stage, StageResult result) {
        List<String> capabilities = stage.satisfiedCapabilities();
        boolean capture = capabilities.contains("change-scope-baseline-capture");
        boolean assuranceStage = capabilities.contains("change-scope-assurance");
        if (!capture && !assuranceStage) {
            return;
        }
        ProviderOutputs.Baseline output = baselineOf(result, stage.stageId());
        String recorded = output == null ? null : capture ? output.manifestSha256() : output.baselineDigest();
        String pinned = lineageBaseline(receipt.runId());
        if (pinned == null && assuranceStage) {
            pinned = runBaseline(receipt, plan);
        }
        if (pinned != null && !Objects.equals(pinned, recorded)) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.workspaceBaselineDiffersFirstBaseline"), details("stageId", stage.stageId(), "reason", "BASELINE_REBOUND"));
        }
    }

    /** The digest a workspace baseline stage of this run recorded, or null when no such stage passed. */
    private String runBaseline(WorkflowReceipt receipt, WorkflowPlan plan) {
        for (StageResult result : receipt.stageResults()) {
            if (result.passed() && plan.stage(result.stageId()).filter(stage -> stage.satisfiedCapabilities().contains("change-scope-baseline-capture")).isPresent()) {
                ProviderOutputs.Baseline output = baselineOf(result, result.stageId());
                return output == null ? null : output.manifestSha256();
            }
        }
        return null;
    }

    /**
     * The baseline digest a stage's output states. The output is the skill's own document, so a baseline stage whose output is not even of this shape has
     * recorded no baseline the lineage can hold to: that is the stage's binding failure, with the reading failure as the cause.
     */
    private ProviderOutputs.Baseline baselineOf(StageResult result, String stageId) {
        if (!result.output().hasOutput()) {
            return null;
        }
        try {
            return mapper.treeToValue(result.output().output(), ProviderOutputs.Baseline.class);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.workspaceBaselineDiffersFirstBaseline"), details("stageId", stageId, "reason", "BASELINE_REBOUND"), unbound);
        }
    }

    private String lineageBaseline(String runId) {
        GuardedRun binding = guardedBinding(runId);
        List<String> chain = new ArrayList<>();
        String rootId = binding == null ? null : binding.root().rootId();
        while (rootId != null && !chain.contains(rootId)) {
            chain.addFirst(rootId);
            Snapshot snapshot = store.getConvergenceSnapshot(rootId);
            rootId = snapshot == null ? null : mapper.readValue(snapshot.rootJson(), ConvergenceRoot.class).parentRootId();
        }
        for (String id : chain) {
            Snapshot snapshot = store.getConvergenceSnapshot(id);
            if (snapshot == null) {
                continue;
            }
            for (String other : snapshot.workflowRunIds()) {
                if (Objects.equals(other, runId)) {
                    continue;
                }
                String text = store.getRun(other);
                WorkflowReceipt otherRun = text == null ? null : parseReceipt(text);
                String baseline = otherRun == null ? null : runBaseline(otherRun, otherRun.plan());
                if (baseline != null) {
                    return baseline;
                }
            }
        }
        return null;
    }

    // ---- shared

    /**
     * Whether an artifact the proposed frame names plays another part than it does in the baseline frame. A control role and a target role are different
     * constants, so an artifact that moves between the two counts as changed.
     */
    private static boolean artifactRolesChanged(ConvergenceFrame baseline, ConvergenceFrame proposed) {
        Map<String, Enum<?>> roles = new LinkedHashMap<>();
        baseline.controlArtifacts().forEach(item -> roles.put(item.artifactId(), item.role()));
        baseline.targetArtifacts().forEach(item -> roles.put(item.artifactId(), item.role()));
        return proposed.controlArtifacts().stream().anyMatch(item -> changed(roles, item.artifactId(), item.role())) || proposed.targetArtifacts().stream().anyMatch(item -> changed(roles, item.artifactId(), item.role()));
    }

    private static boolean changed(Map<String, Enum<?>> roles, String artifactId, Enum<?> role) {
        Enum<?> known = roles.get(artifactId);
        return known != null && known != role;
    }

    /**
     * The semantic rule of a frame the schema does not state: artifact IDs unique across control and target. The schema, checked with the document that holds the frame, is what
     * requires the limits to be integers of at least 1; the values themselves are the root's own (a new root gets the user's setting in {@link #openConvergenceRoot}).
     */
    private void assertConvergenceFrame(ConvergenceFrame frame) {
        List<String> ids = new ArrayList<>();
        frame.controlArtifacts().forEach(item -> ids.add(item.artifactId()));
        frame.targetArtifacts().forEach(item -> ids.add(item.artifactId()));
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.convergenceArtifactIDsUniqueAcross"));
        }
    }

    private record SnapshotDocs(ConvergenceRoot root, List<AttemptProposal> proposals, List<AttemptLease> leases, List<AttemptOutcome> outcomes, List<ConvergenceReview> reviews, List<String> runIds) {
        List<AttemptLease> consumedLeases(long epoch) {
            return leases.stream().filter(lease -> lease.consumedIn(epoch)).toList();
        }
    }

    private Snapshot storedSnapshot(String rootId) {
        Snapshot snapshot = store.getConvergenceSnapshot(rootId);
        if (snapshot == null) {
            throw new WorkflowFailure(WorkflowCode.RUN_NOT_FOUND, msg("workflow.service.convergenceRootFound"), details("rootId", rootId));
        }
        return snapshot;
    }

    private SnapshotDocs requireSnapshot(String rootId) {
        Snapshot snapshot = storedSnapshot(rootId);
        return new SnapshotDocs(mapper.readValue(snapshot.rootJson(), ConvergenceRoot.class), parse(snapshot.proposals(), AttemptProposal.class), parse(snapshot.leases(), AttemptLease.class),
                parse(snapshot.outcomes(), AttemptOutcome.class), parse(snapshot.reviews(), ConvergenceReview.class), snapshot.workflowRunIds());
    }

    /** How many attempts of the epoch have started: only the leases are read, not the root, the proposals and the rest the whole snapshot would parse. */
    private int consumedLeaseCount(String rootId, long epoch) {
        return (int) parse(storedSnapshot(rootId).leases(), AttemptLease.class).stream().filter(lease -> lease.consumedIn(epoch)).count();
    }

    private <T> List<T> parse(List<String> texts, Class<T> type) {
        List<T> out = new ArrayList<>(texts.size());
        texts.forEach(text -> out.add(mapper.readValue(text, type)));
        return out;
    }

    /** The stored receipt of a run, read as the record it is. */
    private WorkflowReceipt requireRun(String runId) {
        String text = store.getRun(runId);
        if (text == null) {
            throw new WorkflowFailure(WorkflowCode.RUN_NOT_FOUND, msg("workflow.service.runFoundWorkflowStorage"), details("runId", runId));
        }
        return parseReceipt(text);
    }

    /**
     * Reads a stored receipt. It was checked against its contract when it was stored, so it is not checked again as a tree; but a receipt written under an
     * earlier contract (it has a key the record does not list, or a value of another type) is refused on this read with a contract mismatch, the binding failure
     * as the cause, instead of being read as something it is not.
     */
    private WorkflowReceipt parseReceipt(String text) {
        try {
            return receiptReader.readValue(text);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.contractMismatch", "name", "workflow-receipt.v1.schema.json"), details("validationErrors", unbound.getOriginalMessage()), unbound);
        }
    }

    /** The one check of a receipt against its contract: the receipt that is about to be stored (the plan in it is part of that contract). */
    private void assertReceipt(WorkflowReceipt receipt) {
        validator.workflowReceipt(receipt);
    }

    private RootRow rootRow(ConvergenceRoot root) {
        ConvergenceFrame.Workspace workspace = root.frame().workspace();
        return new RootRow(root.rootId(), root.revision(), root.state(), workspace.workspaceId(), workspace.locator(), mapper.writeValueAsString(root), root.createdAt().toString(),
                root.updatedAt().toString(), root.currentEpoch(), root.frameDigest());
    }

    private RunRow runRow(WorkflowReceipt receipt) {
        return new RunRow(receipt.runId(), receipt.revision(), receipt.state(), mapper.writeValueAsString(receipt));
    }

    private Outcome outcomeRow(AttemptOutcome document) {
        return new Outcome(document.state(), mapper.writeValueAsString(document), document.recordedAt().toString());
    }
}
