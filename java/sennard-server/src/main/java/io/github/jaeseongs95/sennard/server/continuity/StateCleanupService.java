// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes old finished state in two steps: a plan the caller can read, and a one-use signed token that executes exactly that plan. Both
 * stores are one transaction, so the two sides commit together and a failure leaves both unchanged (no lock is taken: the candidates are computed again at the start of the
 * execution and compared with the plan's digest, the plan's claim is a primary key, and a row another transaction changed or referenced meanwhile is refused by the row lock or the
 * foreign key; a root that becomes active between the digest and the delete is a limit this accepts);
 * the deleted rows stay in each module's backup table ({@code continuity_cleanup_backup}, {@code workflow_cleanup_backup}), keyed by the plan.
 *
 * <p>The plan, the token's signed document and the receipt are records ({@code contracts/state-cleanup-plan.v1} and {@code state-cleanup-receipt.v1}):
 * the tool returns them as they are, and the token's document is bound back to its record with Jackson.
 */
@Service
public class StateCleanupService {
    private static final Duration WORKFLOW_RETENTION = Duration.ofDays(180);
    private static final Duration PAYLOAD_RETENTION = Duration.ofDays(30);
    private static final Duration RECORD_RETENTION = Duration.ofDays(180);
    private static final int MAX_PLAN_TOKEN_BYTES = 64 * 1024;
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String PROTECTION = "os-managed-unverified";
    private static final String BACKUP_RETENTION = "manual-deletion-only";
    private static final String SNAPSHOT_KIND_CURRENT = "current";
    private static final String SIGNING_KEY = "state-cleanup-signing-key";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    /** The retention each kind of state is kept for, as the plan states it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(long workflowRetentionDays, long continuityPayloadRetentionDays, long continuityRecordRetentionDays) {
    }

    /** Where each kind of state's retention ends: the instants the plan's candidates were chosen with. A missing one reads as empty. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cutoffs(String workflow, String continuityPayload, String continuityRecord) {
        public Cutoffs {
            workflow = Objects.requireNonNullElse(workflow, "");
            continuityPayload = Objects.requireNonNullElse(continuityPayload, "");
            continuityRecord = Objects.requireNonNullElse(continuityRecord, "");
        }
    }

    /** How many roots the plan left alone and why ({@code rootProtection} of both contracts). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProtectionSummary(String previewCreatedAt, long activeRoots, long linkedNonTerminalRoots, long linkedRecentRoots, long linkedMissingReceiptRoots, long totalProtectedRoots) {
        static ProtectionSummary of(String createdAt, WorkflowStore.RootProtection protection) {
            return new ProtectionSummary(createdAt, protection.activeRoots(), protection.linkedNonTerminalRoots(), protection.linkedRecentRoots(), protection.linkedMissingReceiptRoots(),
                    protection.totalProtectedRoots());
        }
    }

    /** A continuity snapshot the plan would delete; {@code kind} is always {@code current}. */
    public record SnapshotCandidate(String kind, String taskCorrelation, String rootId, long epoch, String stateToken, String memoryDigest, String updatedAt) {
        static SnapshotCandidate of(ContinuityStore.CleanupSnapshot snapshot) {
            return new SnapshotCandidate(SNAPSHOT_KIND_CURRENT, snapshot.taskCorrelation(), snapshot.rootId(), snapshot.epoch(), snapshot.stateToken(), snapshot.memoryDigest(), snapshot.updatedAt());
        }
    }

    /** What the plan would delete; the store's own rows already have the plan's wire shape for roots, runs and tasks. */
    public record Candidates(List<WorkflowStore.CleanupRoot> workflowRoots, List<WorkflowStore.CleanupRun> standaloneWorkflowRuns, List<SnapshotCandidate> continuitySnapshots,
            List<ContinuityStore.CleanupTask> continuityTasks) {
    }

    public record Counts(int workflowRoots, int workflowRuns, int continuitySnapshots, int continuityTasks, long protectedActiveRoots, long protectedActiveContinuityTasks) {
    }

    /** What {@code prepare_state_cleanup} returns. */
    public record CleanupPlan(String schemaVersion, String planId, String createdAt, Policy policy, Cutoffs cutoffs, Candidates candidates, Counts counts, String candidateDigest, String protection,
            String planToken, ProtectionSummary rootProtection) {
    }

    /** The signed document inside a plan token: what {@code execute} needs to run exactly that plan. Unknown members of a token are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanPayload(String schemaVersion, String planId, String createdAt, Policy policy, Cutoffs cutoffs, String candidateDigest, ProtectionSummary rootProtection) {
        PlanPayload {
            planId = Objects.requireNonNullElse(planId, "");
            candidateDigest = Objects.requireNonNullElse(candidateDigest, "");
        }
    }

    /** One database's part of the receipt: the workflow side has no {@code unavailable} state, but the shape is the same. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkflowResult(CleanupStatus.Database status, String backupPath, int deletedRoots, int deletedRuns, String error) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ContinuityResult(CleanupStatus.Database status, String backupPath, int deletedSnapshots, int deletedTasks, String error) {
    }

    public record DatabaseResults(WorkflowResult workflow, ContinuityResult continuity) {
    }

    /** What {@code execute_state_cleanup} returns; the plan's {@code rootProtection} is carried over when the token has one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CleanupReceipt(String schemaVersion, String planId, CleanupStatus.Receipt status, String executedAt, String candidateDigest, DatabaseResults databases,
            ProtectionSummary rootProtection, String backupRetention, String protection) {
    }

    /** What the candidate digest is taken over: the candidates and the runs linked to the roots, so a plan is refused when either changed. */
    record DigestInput(Candidates candidates, List<WorkflowStore.LinkedRuns> linkedRuns) {
    }

    private record Current(WorkflowStore.CleanupPreview workflow, ContinuityStore.CleanupPreview continuity, long protectedTasks, Candidates candidates, List<WorkflowStore.LinkedRuns> linkedRuns) {
    }

    private final WorkflowStore workflows;
    private final ContinuityStore continuity;
    private final OperationReceipts receipts;
    private final JsonMapper mapper;

    private final Clock clock;
    private final SecureRandom random;

    public StateCleanupService(WorkflowStore workflows, ContinuityStore continuity, JsonMapper mapper, Clock clock, SecureRandom random, OperationReceipts receipts) {
        this.receipts = receipts;
        this.clock = clock;
        this.random = random;
        this.workflows = workflows;
        this.continuity = continuity;
        this.mapper = mapper;
    }

    @Transactional
    public CleanupPlan prepare() {
        Instant created = clock.instant();
        String createdAt = StoreValues.iso(created.toEpochMilli());
        Cutoffs cutoffs = new Cutoffs(StoreValues.iso(created.minus(WORKFLOW_RETENTION).toEpochMilli()), StoreValues.iso(created.minus(PAYLOAD_RETENTION).toEpochMilli()),
                StoreValues.iso(created.minus(RECORD_RETENTION).toEpochMilli()));
        Current current = currentCandidates(cutoffs);
        String candidateDigest = digest(mapper, current.candidates(), current.linkedRuns());
        ProtectionSummary rootProtection = ProtectionSummary.of(createdAt, current.workflow().rootProtection());
        Policy policy = new Policy(WORKFLOW_RETENTION.toDays(), PAYLOAD_RETENTION.toDays(), RECORD_RETENTION.toDays());
        String planId = UUID.randomUUID().toString();
        PlanPayload payload = new PlanPayload(SCHEMA_VERSION, planId, createdAt, policy, cutoffs, candidateDigest, rootProtection);
        int runs = current.workflow().standaloneRuns().size() + current.workflow().roots().stream().mapToInt(root -> root.runIds().size()).sum();
        Counts counts = new Counts(current.workflow().roots().size(), runs, current.continuity().snapshots().size(), current.continuity().tasks().size(), current.workflow().protectedActiveRoots(),
                current.protectedTasks());
        return new CleanupPlan(SCHEMA_VERSION, planId, createdAt, policy, cutoffs, current.candidates(), counts, candidateDigest, PROTECTION, sign(payload), rootProtection);
    }

    /**
     * The execution of a cleanup plan once per operationKey (AGS3-D-0267): the repeat of an execution whose plan token was used is answered with the first receipt, where a new key meets
     * {@code STATE_CLEANUP_TOKEN_USED}.
     */
    @Transactional
    public CleanupReceipt execute(String planToken, OperationKey operation) {
        return receipts.once(operation, CleanupReceipt.class, () -> execute(planToken)).value();
    }

    private CleanupReceipt execute(String planToken) {
        PlanPayload payload = verify(planToken);
        String planId = payload.planId();
        String expected = payload.candidateDigest();
        String now = StoreValues.iso(clock.millis());
        Current current = currentCandidates(payload.cutoffs());
        String actual = digest(mapper, current.candidates(), current.linkedRuns());
        if (!actual.equals(expected)) {
            throw new ConflictException(ConflictCode.STATE_CLEANUP_CANDIDATES_CHANGED, Map.of(), SennardException.args("planId", planId, "expectedDigest", expected, "actualDigest", actual));
        }
        if (!workflows.claimCleanupPlan(planId, expected, now)) {
            throw new ConflictException(ConflictCode.STATE_CLEANUP_TOKEN_USED, Map.of(), SennardException.args("planId", planId));
        }
        boolean workflowCandidates = !current.workflow().roots().isEmpty() || !current.workflow().standaloneRuns().isEmpty();
        boolean continuityCandidates = !current.continuity().snapshots().isEmpty() || !current.continuity().tasks().isEmpty();
        WorkflowResult workflowResult = new WorkflowResult(CleanupStatus.Database.SKIPPED, null, 0, 0, null);
        ContinuityResult continuityResult = new ContinuityResult(CleanupStatus.Database.SKIPPED, null, 0, 0, null);
        if (workflowCandidates) {
            WorkflowStore.CleanupResult deleted = workflows.executeCleanup(current.workflow(), planId, now);
            workflowResult = new WorkflowResult(CleanupStatus.Database.COMPLETED, null, deleted.roots(), deleted.runs(), null);
        }
        if (continuityCandidates) {
            List<String> guarded = new ArrayList<>();
            current.continuity().tasks().forEach(task -> guarded.addAll(continuity.allBoundRoots(task.taskCorrelation())));
            current.continuity().snapshots().forEach(snapshot -> guarded.addAll(continuity.allBoundRoots(snapshot.taskCorrelation())));
            workflows.assertRootsInactive(guarded);
            ContinuityStore.CleanupResult deleted = continuity.executeCleanup(current.continuity(), planId, now, payload.cutoffs().continuityPayload(), payload.cutoffs().continuityRecord());
            continuityResult = new ContinuityResult(CleanupStatus.Database.COMPLETED, null, deleted.snapshots(), deleted.tasks(), null);
        }
        CleanupStatus.Receipt status = workflowCandidates || continuityCandidates ? CleanupStatus.Receipt.COMPLETED : CleanupStatus.Receipt.NO_OP;
        return new CleanupReceipt(SCHEMA_VERSION, planId, status, now, expected, new DatabaseResults(workflowResult, continuityResult), payload.rootProtection(), BACKUP_RETENTION, PROTECTION);
    }

    private Current currentCandidates(Cutoffs cutoffs) {
        WorkflowStore.CleanupPreview workflow = workflows.previewCleanup(cutoffs.workflow());
        ContinuityStore.CleanupPreview preview = continuity.previewCleanup(cutoffs.continuityPayload(), cutoffs.continuityRecord());
        List<ContinuityStore.CleanupTask> tasks = new ArrayList<>();
        List<ContinuityStore.CleanupSnapshot> snapshots = new ArrayList<>();
        Set<String> protectedTasks = new HashSet<>();
        for (ContinuityStore.CleanupTask task : preview.tasks()) {
            if (bindsActiveRoot(task.taskCorrelation())) {
                protectedTasks.add(task.taskCorrelation());
            } else {
                tasks.add(task);
            }
        }
        for (ContinuityStore.CleanupSnapshot snapshot : preview.snapshots()) {
            if (bindsActiveRoot(snapshot.taskCorrelation())) {
                protectedTasks.add(snapshot.taskCorrelation());
            } else {
                snapshots.add(snapshot);
            }
        }
        ContinuityStore.CleanupPreview filtered = new ContinuityStore.CleanupPreview(snapshots, tasks, preview.protectedActiveTasks());
        Candidates candidates = new Candidates(workflow.roots(), workflow.standaloneRuns(), snapshots.stream().map(SnapshotCandidate::of).toList(), tasks);
        return new Current(workflow, filtered, preview.protectedActiveTasks() + protectedTasks.size(), candidates, workflow.linkedRuns());
    }

    /** A session binds several roots; any active root it ever bound keeps the task and its binding rows. */
    private boolean bindsActiveRoot(String taskCorrelation) {
        return continuity.allBoundRoots(taskCorrelation).stream().anyMatch(workflows::isConvergenceRootActive);
    }

    /** {@code sha256:} and the hex digest of the candidates and linked runs written as the mapper writes them (the bytes the plan's digest is defined over). */
    static String digest(JsonMapper mapper, Candidates candidates, List<WorkflowStore.LinkedRuns> linkedRuns) {
        return "sha256:" + Digests.sha256Hex(mapper.writeValueAsString(new DigestInput(candidates, linkedRuns)));
    }

    /** The server's start: makes the key the cleanup plans are signed with. It exists once in the whole server, so no request makes it. */
    @Transactional
    public void prepareSigningKey() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        workflows.createSecretIfAbsent(SIGNING_KEY, TOKEN_ENCODER.encodeToString(value), StoreValues.iso(clock.millis()));
    }

    private byte[] secret() {
        byte[] encoded = workflows.secret(SIGNING_KEY).revealBytes();
        try {
            return TOKEN_DECODER.decode(encoded);
        } finally {
            SecretValue.clear(encoded);
        }
    }

    private byte[] hmac(String value) {
        return Digests.hmacSha256(secret(), value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(PlanPayload payload) {
        String encoded = TOKEN_ENCODER.encodeToString(mapper.writeValueAsBytes(payload));
        String token = encoded + "." + TOKEN_ENCODER.encodeToString(hmac(encoded));
        if (token.getBytes(StandardCharsets.UTF_8).length > MAX_PLAN_TOKEN_BYTES) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_TOO_LARGE);
        }
        return token;
    }

    /** The token is {@code <payload>.<signature>}, both base64url: the signature is compared as written, so a signature that is not base64 is simply not the expected one. */
    private PlanPayload verify(String token) {
        if (token.getBytes(StandardCharsets.UTF_8).length > MAX_PLAN_TOKEN_BYTES) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_TOO_LARGE);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_MALFORMED);
        }
        String signed = token.substring(0, dot);
        byte[] presented = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(TOKEN_ENCODER.encode(hmac(signed)), presented)) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_SIGNATURE_INVALID);
        }
        PlanPayload payload;
        try {
            payload = mapper.readValue(TOKEN_DECODER.decode(signed), PlanPayload.class);
        } catch (JacksonException malformed) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_PAYLOAD_INVALID, Map.of(), Map.of(), malformed); // the failure stays as the cause
        }
        if (payload == null || payload.cutoffs() == null) {
            throw new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_PAYLOAD_INVALID);
        }
        return payload;
    }
}
