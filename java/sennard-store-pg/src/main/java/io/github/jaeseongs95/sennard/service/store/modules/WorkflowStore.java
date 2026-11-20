// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import tools.jackson.databind.json.JsonMapper;

import io.github.jaeseongs95.sennard.service.store.AttemptState;
import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.SqlMerge;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.UpdateComparison;

/**
 * Workflow runs, convergence roots and leases. The contract objects are JSON text written and validated by the service layer; this store owns their
 * indexed columns, the compare-and-set writes, and the few field edits it has to do itself (lease state, root revision).
 * The domain planning (planRootInsertion, activeRootIdentity, plugin version comparison) belongs to the service layer and arrives here as a function argument.
 */
@Repository
public class WorkflowStore extends ModuleStore {
    private static final String T = "sennard.";
    private static final String RUN_SEQUENCE = "run-sequence";

    /** A stored root's revision, state and body, the three columns the compare-and-set writes read back. */
    private record StoredRoot(String rootJson, long revision, RootState state) {
    }

    private record StoredLease(String rootId, LeaseState state, long rootRevision, long epoch, long ordinal, String leaseJson, String proposalJson) {
    }

    private record RevisionReceipt(long revision, String receiptJson) {
    }

    /** A run's identity columns as the database answers them: the revision and state are null when the LEFT JOIN found no run, the time is a TIMESTAMP value. */
    private record RunRevision(String runId, Long revision, RunState state, LocalDateTime updatedAt) {
        String updatedAtIso() {
            return StoreValues.isoOrNull(updatedAt);
        }
    }

    private record RootRevision(long revision, RootState state, LocalDateTime updatedAt) {
        String updatedAtIso() {
            return StoreValues.isoOrNull(updatedAt);
        }
    }

    private record CandidateRoot(String rootId, long revision, RootState state, LocalDateTime updatedAt) {
    }

    // The rows as the tables have them. Spring's row mapper fills a record by its component names, turns a stored enum name back into the constant (EnumMappingTest, E3) and reads a
    // TIMESTAMP column into a LocalDateTime (the stored UTC fields, never the driver's text form); the public records turn the times into ISO text.
    private record ApprovalRow(String approvalId, String scopeDigest, String connectionId, String requestId, String recordJson, LocalDateTime createdAt, LocalDateTime revokedAt) {
        RootApproval toApproval() {
            return new RootApproval(approvalId, scopeDigest, connectionId, requestId, recordJson, StoreValues.isoOrNull(createdAt), StoreValues.isoOrNull(revokedAt));
        }
    }

    private record PluginStateRow(String targetId, String currentVersion, String latestVersion, String latestTag, String latestCommit, String etag, UpdateComparison comparison,
            LocalDateTime lastAttemptAt, LocalDateTime lastSuccessfulCheckAt, LocalDateTime nextCheckAt, String lastNotifiedVersion, LocalDateTime lastNotifiedAt, String lastErrorCode) {
        PluginUpdateState toState() {
            return new PluginUpdateState(targetId, currentVersion, latestVersion, latestTag, latestCommit, etag, comparison, StoreValues.isoOrNull(lastAttemptAt),
                    StoreValues.isoOrNull(lastSuccessfulCheckAt), StoreValues.isoOrNull(nextCheckAt), lastNotifiedVersion, StoreValues.isoOrNull(lastNotifiedAt), lastErrorCode);
        }
    }

    private record CleanupRunRow(String runId, long revision, RunState state, LocalDateTime updatedAt) {
        CleanupRun toRun() {
            return new CleanupRun(runId, revision, state, StoreValues.isoOrNull(updatedAt));
        }
    }

    private record PendingRow(String metaKey, String value, LocalDateTime updatedAt) {
    }

    private record LeaseDocuments(String leaseJson, String proposalJson) {
    }

    // The states a query groups are asked of the constants (isActive, isTerminal), never listed in SQL text, so the enum stays the one definition.
    private static final List<RootState> ACTIVE_ROOTS = Arrays.stream(RootState.values()).filter(RootState::isActive).toList();
    private static final List<RootState> SETTLED_ROOTS = Arrays.stream(RootState.values()).filter(state -> !state.isActive()).toList();
    private static final List<RunState> TERMINAL_RUNS = Arrays.stream(RunState.values()).filter(RunState::isTerminal).toList();

    /** The names a column holds for these constants (Spring Data JDBC's mapping of an enum): what a named list parameter binds for {@code IN (:states)}, which Spring expands to one placeholder per value. */
    private static List<String> names(List<? extends Enum<?>> constants) {
        return constants.stream().map(Enum::name).toList();
    }

    /** The first row of the query read as {@code type} (a record Spring fills by column name, or a single value), or null. */
    private <V> V one(String sql, Class<V> type, Object... params) {
        return firstOrNull(jdbc.sql(sql).params(StoreValues.bind(params)).query(type).list());
    }

    private <V> List<V> many(String sql, Class<V> type, Object... params) {
        return jdbc.sql(sql).params(StoreValues.bind(params)).query(type).list();
    }

    private String textOf(String sql, Object... params) {
        return one(sql, String.class, params);
    }

    private List<String> textsOf(String sql, Object... params) {
        return many(sql, String.class, params);
    }

    private final JsonRecords records;
    private final JsonMapper mapper;
    private final SqlBuilder limit;

    public WorkflowStore(@Qualifier("storeJdbc") JdbcClient jdbc, JsonMapper mapper, SqlBuilder limit) {
        super(jdbc);
        this.limit = limit;
        this.records = new JsonRecords(mapper);
        this.mapper = mapper;
    }

    /** The two fields of a stored run receipt the row must agree with; the rest of the receipt is the service layer's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceiptHead(String runId, Long revision) {
    }

    public record RunRow(String runId, long revision, RunState state, String receiptJson) {
    }

    public record RootRow(String rootId, long revision, RootState state, String workspaceId, String workspaceLocator, String rootJson, String createdAt, String updatedAt, long currentEpoch, String frameDigest) {
    }

    public record ReviewRow(String reviewId, String rootId, long epoch, String reviewJson, String reviewedAt) {
    }

    public record LeaseRow(String leaseId, String rootId, long rootRevision, long epoch, long ordinal, LeaseState state, String leaseJson, String proposalJson, String issuedAt, String expiresAt) {
    }

    public record Outcome(AttemptState state, String outcomeJson, String recordedAt) {
    }

    public record ActiveRootRow(String rootId, String rootJson, long revision, String identityJson, String surfaceDigest) {
    }

    public record IdentityRow(String rootId, String identityJson, String surfaceDigest) {
    }

    /**
     * {@code fresh}: identities derived for existing roots, saved even when {@code conflict} is set. {@code own}: for the new root, saved only when there is no conflict.
     * {@code activeSurface}: where the new root writes, the key of its row in {@code convergence_active_surfaces} (the primary key of that table) while the root is active.
     */
    public record RootInsertionPlan<C>(C conflict, List<IdentityRow> fresh, IdentityRow own, String activeSurface) {
    }

    public record GuardedBinding(String rootJson, String proposalJson, String leaseJson, String outcomeJson) {
    }

    public record Snapshot(String rootJson, List<String> proposals, List<String> leases, List<String> outcomes, List<String> reviews, List<String> workflowRunIds) {
    }

    public record PluginUpdateState(String targetId, String currentVersion, String latestVersion, String latestTag, String latestCommit, String etag, UpdateComparison comparison,
            String lastAttemptAt, String lastSuccessfulCheckAt, String nextCheckAt, String lastNotifiedVersion, String lastNotifiedAt, String lastErrorCode) {
    }

    public record RootApproval(String approvalId, String scopeDigest, String connectionId, String requestId, String recordJson, String createdAt, String revokedAt) {
    }

    private static <T> List<T> empty() {
        return new ArrayList<>();
    }

    /**
     * A secret of the workflow module. It is made once by {@link #createSecretIfAbsent} when the server starts and a request only reads it: a key that exists once in the whole
     * server is not made by the requests that race for it.
     */
    public SecretValue secret(String name) {
        String stored = textOf("SELECT value FROM " + T + "workflow_metadata WHERE meta_key = ?", name);
        if (stored == null) {
            throw new StoreException(StoreCode.INTEGRITY_FAILED, "WORKFLOW_SECRET_MISSING: the secret " + name + " has not been made: the server makes it when it starts.");
        }
        return SecretValue.of(stored);
    }

    /** The server's start: stores the secret when there is none; the key is the table's primary key. */
    public void createSecretIfAbsent(String name, String value, String now) {
        insertIfAbsent(SqlMerge.into(T + "workflow_metadata").key("meta_key", name).set("value", value).set("updated_at", StoreValues.ts(now)));
    }

    /** True for the first consumer of an observation. Claims never expire. */
    public boolean claimExecutionObservation(String observationId, String consumedAt) {
        return insertIfAbsent(SqlMerge.into(T + "execution_observation_claims").key("observation_id", observationId).set("consumed_at", StoreValues.ts(consumedAt))) == 1;
    }

    /** The server's start: stores the run number counter at 0 when there is none, so no request makes it. */
    public void createRunSequenceIfAbsent(String now) {
        insertIfAbsent(SqlMerge.into(T + "workflow_metadata").key("meta_key", RUN_SEQUENCE).set("value", "0").set("updated_at", StoreValues.ts(now)));
    }

    /**
     * The next run number. It only keeps the ids of the runs of one task apart ({@code run-<task id>-<number>}; a run's id is also the primary key, so an id used twice fails). It is
     * read and then written without a lock: two starts of different tasks together may take the same number, which makes their ids differ by the task id all the same, and one task
     * cannot start two runs together (the lease is consumed by one {@code UPDATE ... WHERE state = ISSUED}).
     */
    public long nextRunSequence(String now) {
        String value = textOf("SELECT value FROM " + T + "workflow_metadata WHERE meta_key = ?", RUN_SEQUENCE);
        long current = 0;
        if (value != null) {
            if (!value.matches("^(0|[1-9][0-9]*)$") || value.length() > 16) {
                throw new StoreException(StoreCode.INTEGRITY_FAILED, "RUN_SEQUENCE_INVALID: the workflow run sequence is invalid.");
            }
            current = Long.parseLong(value);
        }
        long next = current + 1;
        if (next > (1L << 53) - 1) {
            throw new StoreException(StoreCode.MCP_UNAVAILABLE, "RUN_SEQUENCE_EXHAUSTED: the workflow run sequence is exhausted.");
        }
        upsert(SqlMerge.into(T + "workflow_metadata").key("meta_key", RUN_SEQUENCE).set("value", Long.toString(next)).set("updated_at", StoreValues.ts(now)));
        return next;
    }

    /**
     * A run, with its kind and the skills its start declared: the run row (which holds its kind, a column added by V22) and the skill rows are written by this one method, in the
     * transaction of the service method that starts the run, so a run has its kind and its skill rows or none of them (the statistics count the runs by them).
     *
     * @param kind the name of the constant of the server's enum of workflow kinds (this module does not know the kinds)
     * @param skills the skills the start declared, each as the skill's own name; a skill named twice is written once
     */
    public void insertRun(RunRow receipt, String createdAt, String kind, List<String> skills) {
        update("INSERT INTO " + T + "workflow_runs (run_id, revision, state, receipt_json, created_at, updated_at, kind) VALUES (?, ?, ?, ?, ?, ?, ?)",
                receipt.runId(), receipt.revision(), receipt.state(), receipt.receiptJson(), StoreValues.ts(createdAt), StoreValues.ts(createdAt), kind);
        for (String skill : new LinkedHashSet<>(skills)) {
            update("INSERT INTO " + T + "workflow_run_skills (run_id, skill_id) VALUES (?, ?)", receipt.runId(), skill);
        }
    }

    public String getRunUpdatedAt(String runId) {
        return StoreValues.isoOrNull(one("SELECT updated_at FROM " + T + "workflow_runs WHERE run_id = ?", LocalDateTime.class, runId));
    }

    /** The stored receipt; INTEGRITY_FAILED when its JSON no longer names the same run and revision as the row. */
    public String getRun(String runId) {
        RevisionReceipt row = one("SELECT revision, receipt_json FROM " + T + "workflow_runs WHERE run_id = ?", RevisionReceipt.class, runId);
        if (row == null) {
            return null;
        }
        String receipt = row.receiptJson();
        ReceiptHead head = mapper.readValue(receipt, ReceiptHead.class);
        if (!runId.equals(head.runId()) || !Objects.equals(head.revision(), row.revision())) {
            throw new StoreException(StoreCode.INTEGRITY_FAILED, "RECEIPT_METADATA_MISMATCH: the stored workflow receipt does not match its row.");
        }
        return receipt;
    }

    /** Compare-and-set on the run revision; with {@code convergence}, also on the root, and records the attempt's outcome when one is supplied. {@code now} is the caller's clock reading. */
    public boolean updateRun(RunRow receipt, long expectedRevision, RootRow root, Long expectedRootRevision, Outcome outcome, String now) {
        long updated = update("UPDATE " + T + "workflow_runs SET revision = ?, state = ?, receipt_json = ?, updated_at = ? WHERE run_id = ? AND revision = ?",
                receipt.revision(), receipt.state(), receipt.receiptJson(), StoreValues.ts(now), receipt.runId(), expectedRevision);
        if (updated != 1) {
            return false;
        }
        if (root == null) {
            return true;
        }
        if (!casRoot(root, expectedRootRevision)) {
            throw new StoreException(StoreCode.STALE_REVISION, "The convergence root changed while the run was recorded.");
        }
        if (outcome == null) {
            return true; // A pause/resume fences the root without ending its attempt.
        }
        long attempt = update("UPDATE " + T + "convergence_attempts SET state = ?, outcome_json = ?, updated_at = ? WHERE run_id = ? AND outcome_json IS NULL",
                outcome.state(), outcome.outcomeJson(), StoreValues.ts(outcome.recordedAt()), receipt.runId());
        if (attempt != 1) {
            throw new StoreException(StoreCode.LEASE_CONFLICT, "The guarded attempt's outcome is already recorded or missing.");
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------- root approvals

    public RootApproval getRootApproval(String approvalId) {
        return approval(one("SELECT * FROM " + T + "root_approval_decisions WHERE approval_id = ?", ApprovalRow.class, approvalId));
    }

    public RootApproval findRootApproval(String scopeDigest) {
        return approval(one(limit.applyLimit("SELECT * FROM " + T + "root_approval_decisions WHERE scope_digest = ? AND revoked_at IS NULL ORDER BY created_at, approval_id", "?"), ApprovalRow.class, scopeDigest, 1));
    }

    private static RootApproval approval(ApprovalRow row) {
        return row == null ? null : row.toApproval();
    }

    /** First writer of a (connection, request) wins; a later different scope is USER_APPROVAL_SOURCE_CONFLICT. */
    public RootApproval insertRootApproval(RootApproval record) {
        insertIfAbsent(SqlMerge.into(T + "root_approval_decisions").key("connection_id", record.connectionId()).key("request_id", record.requestId()).set("approval_id", record.approvalId())
                .set("scope_digest", record.scopeDigest()).set("record_json", record.recordJson()).set("created_at", StoreValues.ts(record.createdAt())).set("revoked_at", StoreValues.ts(record.revokedAt())));
        RootApproval stored = approval(one("SELECT * FROM " + T + "root_approval_decisions WHERE connection_id = ? AND request_id = ?", ApprovalRow.class, record.connectionId(), record.requestId()));
        if (!stored.scopeDigest().equals(record.scopeDigest())) {
            throw new StoreException(StoreCode.USER_APPROVAL_SOURCE_CONFLICT, "This request already approved a different scope.");
        }
        return stored;
    }

    public boolean revokeRootApproval(String approvalId, String revokedAt) {
        String stored = textOf("SELECT record_json FROM " + T + "root_approval_decisions WHERE approval_id = ? AND revoked_at IS NULL", approvalId);
        if (stored == null) {
            return false;
        }
        // Rewritten in Java, not with a JSON function: revokedAt is outside the digest and the other fields keep their order and numbers (DBMS 3.2).
        String record = records.withText(stored, "revokedAt", revokedAt);
        return update("UPDATE " + T + "root_approval_decisions SET revoked_at = ?, record_json = ? WHERE approval_id = ? AND revoked_at IS NULL",
                StoreValues.ts(revokedAt), record, approvalId) == 1;
    }

    /** A replace-root or release-root the user has not decided yet, shown on the admin page; {@code recordJson} is the scope and the display text. */
    public record PendingApproval(String scopeDigest, String recordJson, String updatedAt) {
    }

    private static final String PENDING_PREFIX = "root-approval-pending:";

    /** Upsert: asking again for the same scope refreshes the row rather than growing the list. */
    public void savePendingRootApproval(String scopeDigest, String recordJson, String now) {
        upsert(SqlMerge.into(T + "workflow_metadata").key("meta_key", PENDING_PREFIX + scopeDigest).set("value", recordJson).set("updated_at", StoreValues.ts(now)));
    }

    public List<PendingApproval> listPendingRootApprovals() {
        return many("SELECT meta_key, value, updated_at FROM " + T + "workflow_metadata WHERE SUBSTRING(meta_key FROM 1 FOR ?) = ? ORDER BY updated_at, meta_key",
                PendingRow.class, PENDING_PREFIX.length(), PENDING_PREFIX).stream()
                .map(row -> new PendingApproval(row.metaKey().substring(PENDING_PREFIX.length()), row.value(), StoreValues.isoOrNull(row.updatedAt()))).toList();
    }

    /** Takes the request off the waiting list; true for the call whose {@code DELETE} changed the row, false when the row was not there (another decision took it first). */
    public boolean deletePendingRootApproval(String scopeDigest) {
        return update("DELETE FROM " + T + "workflow_metadata WHERE meta_key = ?", PENDING_PREFIX + scopeDigest) == 1;
    }

    // ---------------------------------------------------------------------------------------------------------------- roots

    /**
     * Inserts a root; {@code planner} sees the active roots and returns the plan. Returns the plan's conflict, or null when the root was inserted. The check is an ordinary read of the
     * committed active roots inside the caller's transaction, so it cannot see a root that another transaction has not committed yet. Two roots of the same write surface opened at the same
     * moment are decided by the primary key of {@code convergence_active_surfaces}: the second one's INSERT of its surface row ends in {@code StoreCode.ROOT_CONFLICT}. Two roots that only overlap (a folder and a file of it)
     * and are opened at the same moment may both open (accepted, see {@code WorkflowService}).
     */
    public <C> C insertConvergenceRoot(RootRow root, String parentRootId, Function<List<ActiveRootRow>, RootInsertionPlan<C>> planner) {
        StoredRoot parent = parentRootId == null ? null : rootRow(parentRootId);
        if (parentRootId != null && parent == null) {
            throw new StoreException(StoreCode.INVALID_INPUT, "The parent convergence root was not found.");
        }
        RootInsertionPlan<C> plan = planner.apply(readActiveRoots());
        for (IdentityRow identity : plan.fresh()) {
            saveRootIdentity(identity, root.createdAt());
        }
        if (plan.conflict() != null) {
            return plan.conflict();
        }
        if (parent != null) {
            String abandoned = records.withText(records.withNumber(records.withState(parent.rootJson(), RootState.ABANDONED),
                    "revision", parent.revision() + 1), "updatedAt", root.createdAt());
            casRoot(new RootRow(parentRootId, parent.revision() + 1, RootState.ABANDONED, null, null, abandoned, null, root.createdAt(), 0, null), parent.revision());
        }
        update("INSERT INTO " + T + "convergence_roots (root_id, revision, state, workspace_id, workspace_locator, root_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                root.rootId(), root.revision(), root.state(), root.workspaceId(), root.workspaceLocator(), root.rootJson(), StoreValues.ts(root.createdAt()), StoreValues.ts(root.updatedAt()));
        // An active root holds its write surface in a row of convergence_active_surfaces, so of two roots of one surface that are opened together the second INSERT meets the first one's
        // primary key. A root that is not active has no row.
        if (root.state().isActive()) {
            try {
                update("INSERT INTO " + T + "convergence_active_surfaces (active_surface, root_id) VALUES (?, ?)", plan.activeSurface(), root.rootId());
            } catch (StoreException lost) {
                if (lost.code() != StoreCode.STORE_UNIQUE_VIOLATION) {
                    throw lost;
                }
                // The root id of this row is the root just inserted above, which no other row holds (the table's UNIQUE key on it cannot fail), so a unique violation of this INSERT is
                // the primary key on the surface: a root that is active holds the same write surface already. That is the store's ROOT_CONFLICT; this call ends with it, the root INSERT
                // above rolls back with the transaction, and nothing goes on or runs again.
                throw new StoreException(StoreCode.ROOT_CONFLICT, null, lost.effect(), "An active root holds the same write surface.", lost);
            }
        }
        insertEpoch(root, root.createdAt());
        saveRootIdentity(plan.own(), root.createdAt());
        return null;
    }

    /** Every active root with its stored identity (the planner input and the edit gate's read). */
    public List<ActiveRootRow> readActiveRoots() {
        return jdbc.sql("SELECT r.root_id, r.root_json, r.revision, i.identity_json, i.surface_digest FROM " + T + "convergence_roots r "
                + "LEFT JOIN " + T + "convergence_root_identities i ON i.root_id = r.root_id "
                + "WHERE r.state IN (:states)").param("states", names(ACTIVE_ROOTS)).query(ActiveRootRow.class).list();
    }

    /** The whole root in one read; call inside {@link #snapshot}. Null when the root is absent. */
    public Snapshot getConvergenceSnapshot(String rootId) {
        StoredRoot root = rootRow(rootId);
        if (root == null) {
            return null;
        }
        List<LeaseDocuments> documents = many("SELECT lease_json, proposal_json FROM " + T + "convergence_leases WHERE root_id = ? ORDER BY epoch, ordinal, issued_at",
                LeaseDocuments.class, rootId);
        List<String> proposals = documents.stream().map(LeaseDocuments::proposalJson).collect(Collectors.toCollection(ArrayList::new));
        List<String> leases = documents.stream().map(LeaseDocuments::leaseJson).collect(Collectors.toCollection(ArrayList::new));
        List<String> outcomes = textsOf("SELECT outcome_json FROM " + T + "convergence_attempts WHERE root_id = ? AND outcome_json IS NOT NULL ORDER BY epoch, ordinal", rootId);
        List<String> reviews = textsOf("SELECT review_json FROM " + T + "convergence_reviews WHERE root_id = ? ORDER BY reviewed_at, review_id", rootId);
        List<String> runs = textsOf("SELECT run_id FROM " + T + "workflow_attempt_links WHERE root_id = ? ORDER BY epoch, ordinal", rootId);
        return new Snapshot(root.rootJson(), proposals, leases, outcomes, reviews, runs);
    }

    /** The workflow runs attached to the root, oldest attempt first, without reading the rest of the root's documents. */
    public List<String> workflowRunIds(String rootId) {
        return textsOf("SELECT run_id FROM " + T + "workflow_attempt_links WHERE root_id = ? ORDER BY epoch, ordinal", rootId);
    }

    public boolean updateConvergenceRoot(RootRow root, long expectedRevision, long previousEpoch, ReviewRow review) {
        StoredRoot current = rootRow(root.rootId());
        if (current == null || current.revision() != expectedRevision) {
            return false;
        }
        if (!casRoot(root, expectedRevision)) {
            return false;
        }
        if (root.currentEpoch() != previousEpoch) {
            insertEpoch(root, root.updatedAt());
        }
        if (review != null) {
            update("INSERT INTO " + T + "convergence_reviews (review_id, root_id, epoch, review_json, reviewed_at) VALUES (?, ?, ?, ?, ?)",
                    review.reviewId(), review.rootId(), review.epoch(), review.reviewJson(), StoreValues.ts(review.reviewedAt()));
        }
        return true;
    }

    // --------------------------------------------------------------------------------------------------------------- leases

    /** A busy database or a second issued lease on the root (the unique key of the issued leases, or of the lease rows) is LEASE_CONFLICT. */
    private static <V> V contending(Supplier<V> body) {
        try {
            return body.get();
        } catch (StoreException failure) {
            if (failure.code() == StoreCode.STORE_BUSY || failure.code() == StoreCode.STORE_UNIQUE_VIOLATION) {
                throw new StoreException(StoreCode.LEASE_CONFLICT, null, failure.effect(), "The convergence database is busy or a lease was just issued.", failure);
            }
            throw failure;
        }
    }


    /** Claims the next attempt: CAS on the root, then the previous unstarted lease is superseded. Joins the transaction of the service method; the CAS on the root's revision decides between two claims (the second one's UPDATE changes no row after the first one's commit), so no lock is held. */
    public boolean insertAttemptLease(RootRow root, long expectedRevision, LeaseRow lease) {
        return contending(() -> {
            // No write before the CAS: a false return commits what ran before it.
            if (!casRoot(root, expectedRevision)) {
                return false;
            }
            record IssuedLease(String leaseId, String leaseJson) {
            }
            for (IssuedLease issued : many("SELECT lease_id, lease_json FROM " + T + "convergence_leases WHERE root_id = ? AND state = ?",
                    IssuedLease.class, root.rootId(), LeaseState.ISSUED)) {
                update("UPDATE " + T + "convergence_leases SET state = ?, lease_json = ? WHERE lease_id = ? AND state = ?",
                        LeaseState.EXPIRED, records.withState(issued.leaseJson(), LeaseState.EXPIRED), issued.leaseId(), LeaseState.ISSUED);
            }
            update("DELETE FROM " + T + "convergence_issued_leases WHERE root_id = ?", root.rootId());
            update("INSERT INTO " + T + "convergence_leases (lease_id, root_id, root_revision, epoch, ordinal, state, lease_json, proposal_json, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    lease.leaseId(), lease.rootId(), lease.rootRevision(), lease.epoch(), lease.ordinal(), lease.state(), lease.leaseJson(), lease.proposalJson(), StoreValues.ts(lease.issuedAt()), StoreValues.ts(lease.expiresAt()));
            if (lease.state() == LeaseState.ISSUED) {
                // the primary key of this table is the root: a second issued lease of the root fails here
                update("INSERT INTO " + T + "convergence_issued_leases (root_id, lease_id) VALUES (?, ?)", lease.rootId(), lease.leaseId());
            }
            return true;
        });
    }

    public GuardedBinding getAttemptLease(String leaseId) {
        StoredLease lease = leaseRow(leaseId);
        if (lease == null) {
            return null;
        }
        StoredRoot root = rootRow(lease.rootId());
        return root == null ? null : new GuardedBinding(root.rootJson(), lease.proposalJson(), lease.leaseJson(), null);
    }

    /** Consumes an issued lease and starts the guarded run, all or nothing. Null when the lease or the root moved on. Joins the transaction of the service method; the lease's {@code UPDATE ... WHERE state = ISSUED} and the CAS on the root's revision decide between two starts, so no lock is held. */
    public GuardedBinding insertGuardedRun(RunRow receipt, String leaseId, long expectedRootRevision, String consumedAt, String kind, List<String> skills) {
        return contending(() -> {
            StoredLease leaseRow = leaseRow(leaseId);
            if (leaseRow == null) {
                return null;
            }
            String leaseJson = leaseRow.leaseJson();
            if (leaseRow.state() != LeaseState.ISSUED || leaseRow.rootRevision() != expectedRootRevision) {
                return null;
            }
            String rootId = leaseRow.rootId();
            StoredRoot rootRow = rootRow(rootId);
            if (rootRow == null || rootRow.revision() != expectedRootRevision) {
                return null;
            }
            String consumed = records.withState(leaseJson, LeaseState.CONSUMED);
            if (update("UPDATE " + T + "convergence_leases SET state = ?, lease_json = ? WHERE lease_id = ? AND state = ?",
                    LeaseState.CONSUMED, consumed, leaseId, LeaseState.ISSUED) != 1) {
                return null;
            }
            update("DELETE FROM " + T + "convergence_issued_leases WHERE lease_id = ?", leaseId);
            String rootJson = records.withText(records.withNumber(rootRow.rootJson(), "revision", expectedRootRevision + 1), "updatedAt", consumedAt);
            RootRow root = new RootRow(rootId, expectedRootRevision + 1, rootRow.state(), null, null, rootJson, null, consumedAt, 0, null);
            if (!casRoot(root, expectedRootRevision)) {
                return null;
            }
            insertRun(receipt, consumedAt, kind, skills);
            long epoch = leaseRow.epoch();
            long ordinal = leaseRow.ordinal();
            update("INSERT INTO " + T + "convergence_attempts (root_id, epoch, ordinal, lease_id, run_id, state, outcome_json, started_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                    rootId, epoch, ordinal, leaseId, receipt.runId(), AttemptState.RUNNING, StoreValues.ts(consumedAt), StoreValues.ts(consumedAt));
            update("INSERT INTO " + T + "workflow_attempt_links (run_id, root_id, lease_id, epoch, ordinal) VALUES (?, ?, ?, ?, ?)",
                    receipt.runId(), rootId, leaseId, epoch, ordinal);
            return new GuardedBinding(rootJson, leaseRow.proposalJson(), consumed, null);
        });
    }

    public GuardedBinding getGuardedRunBinding(String runId) {
        record Link(String rootId, String leaseId) {
        }
        Link link = one("SELECT root_id, lease_id FROM " + T + "workflow_attempt_links WHERE run_id = ?", Link.class, runId);
        if (link == null) {
            return null;
        }
        StoredRoot root = rootRow(link.rootId());
        StoredLease lease = leaseRow(link.leaseId());
        String outcome = textOf("SELECT outcome_json FROM " + T + "convergence_attempts WHERE run_id = ?", runId);
        if (root == null || lease == null) {
            return null;
        }
        return new GuardedBinding(root.rootJson(), lease.proposalJson(), lease.leaseJson(), outcome);
    }

    // ------------------------------------------------------------------------------------------------------ plugin update state

    public PluginUpdateState getPluginUpdateState(String targetId) {
        PluginStateRow row = one("SELECT * FROM " + T + "plugin_update_state WHERE target_id = ?", PluginStateRow.class, targetId);
        return row == null ? null : row.toState();
    }

    /** The server's start: the row of the plugin's update state is made once, so that a check and a notice only update it. */
    public void createPluginUpdateStateIfAbsent(PluginUpdateState state) {
        insertIfAbsent(pluginStateRow(state));
    }

    /** {@code merge} (the service's mergePluginUpdateState) sees the row as it is read and returns the row to store; of two writers the last one's row stays. */
    public void putPluginUpdateState(String targetId, UnaryOperator<PluginUpdateState> merge) {
        PluginUpdateState merged = merge.apply(getPluginUpdateState(targetId));
        upsert(pluginStateRow(merged));
    }

    private static SqlMerge pluginStateRow(PluginUpdateState merged) {
        return SqlMerge.into(T + "plugin_update_state").key("target_id", merged.targetId()).set("current_version", merged.currentVersion()).set("latest_version", merged.latestVersion())
                .set("latest_tag", merged.latestTag()).set("latest_commit", merged.latestCommit()).set("etag", merged.etag()).set("comparison", merged.comparison())
                .set("last_attempt_at", StoreValues.ts(merged.lastAttemptAt())).set("last_successful_check_at", StoreValues.ts(merged.lastSuccessfulCheckAt()))
                .set("next_check_at", StoreValues.ts(merged.nextCheckAt())).set("last_notified_version", merged.lastNotifiedVersion())
                .set("last_notified_at", StoreValues.ts(merged.lastNotifiedAt())).set("last_error_code", merged.lastErrorCode());
    }

    /**
     * What a check found: the columns of the check, in one statement, and not the columns of the notice (a notice that was claimed while the check was asking stays claimed).
     * False when the state has no row, which the server's start makes.
     */
    public boolean recordPluginUpdateCheck(PluginUpdateState checked) {
        return update("UPDATE " + T + "plugin_update_state SET current_version = ?, latest_version = ?, latest_tag = ?, latest_commit = ?, etag = ?, comparison = ?, last_attempt_at = ?, "
                + "last_successful_check_at = ?, next_check_at = ?, last_error_code = ? WHERE target_id = ?", checked.currentVersion(), checked.latestVersion(), checked.latestTag(),
                checked.latestCommit(), checked.etag(), checked.comparison(), StoreValues.ts(checked.lastAttemptAt()), StoreValues.ts(checked.lastSuccessfulCheckAt()),
                StoreValues.ts(checked.nextCheckAt()), checked.lastErrorCode(), checked.targetId()) == 1;
    }

    /**
     * At most one notice per latest version, across every session. {@code notNewerThanNotified} compares the stored last-notified version with {@code latestVersion}. The claim is one
     * {@code UPDATE} whose {@code WHERE} names the last-notified version that was read, so of the requests that read the same one only the statement that changes the row claims.
     */
    public boolean claimPluginUpdateNotice(String targetId, String latestVersion, String notifiedAt, java.util.function.Predicate<String> notNewerThanNotified) {
        PluginUpdateState state = getPluginUpdateState(targetId);
        if (state == null || !latestVersion.equals(state.latestVersion()) || (state.lastNotifiedVersion() != null && notNewerThanNotified.test(state.lastNotifiedVersion()))) {
            return false;
        }
        String notified = state.lastNotifiedVersion();
        return update("UPDATE " + T + "plugin_update_state SET last_notified_version = ?, last_notified_at = ? WHERE target_id = ? AND latest_version = ? AND "
                + (notified == null ? "last_notified_version IS NULL" : "last_notified_version = ?"),
                notified == null ? new Object[] {latestVersion, StoreValues.ts(notifiedAt), targetId, latestVersion} : new Object[] {latestVersion, StoreValues.ts(notifiedAt), targetId, latestVersion, notified}) == 1;
    }

    public boolean isConvergenceRootActive(String rootId) {
        return !jdbc.sql("SELECT 1 FROM " + T + "convergence_roots WHERE root_id = :rootId AND state IN (:states)")
                .param("rootId", rootId).param("states", names(ACTIVE_ROOTS)).query(Integer.class).list().isEmpty();
    }

    /** STALE_REVISION when any of these roots is active. Called inside the transaction the guarded operation runs in. */
    public void assertRootsInactive(List<String> rootIds) {
        for (String rootId : new java.util.TreeSet<>(rootIds)) {
            StoredRoot root = rootRow(rootId);
            if (root != null && root.state().isActive()) {
                throw new StoreException(StoreCode.STALE_REVISION, "A root under continuity cleanup became active.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ state cleanup

    public record CleanupRoot(String rootId, long revision, RootState state, String updatedAt, List<String> runIds) {
    }

    public record CleanupRun(String runId, long revision, RunState state, String updatedAt) {
    }

    public record LinkedRuns(String rootId, List<CleanupRun> runs) {
    }

    public record RootProtection(long activeRoots, long linkedNonTerminalRoots, long linkedRecentRoots, long linkedMissingReceiptRoots, long totalProtectedRoots) {
    }

    public record CleanupPreview(List<CleanupRoot> roots, List<CleanupRun> standaloneRuns, long protectedActiveRoots, RootProtection rootProtection, List<LinkedRuns> linkedRuns) {
    }

    public record CleanupResult(int roots, int runs) {
    }

    public CleanupPreview previewCleanup(String cutoff) {
        List<CleanupRoot> rootCandidates = empty();
        List<LinkedRuns> linkedSnapshots = empty();
        long linkedNonTerminal = 0;
        long linkedRecent = 0;
        long linkedMissing = 0;
        long protectedLinked = 0;
        for (CandidateRoot root : jdbc.sql("SELECT root_id, revision, state, updated_at FROM " + T + "convergence_roots WHERE updated_at <= :cutoff AND state IN (:states) ORDER BY root_id")
                .param("cutoff", StoreValues.ts(cutoff)).param("states", names(SETTLED_ROOTS)).query(CandidateRoot.class).list()) {
            String rootId = root.rootId();
            boolean missing = false;
            boolean nonTerminal = false;
            boolean recent = false;
            List<CleanupRun> runs = empty();
            for (RunRevision row : many("SELECT links.run_id, runs.revision, runs.state, runs.updated_at FROM " + T + "workflow_attempt_links links "
                    + "LEFT JOIN " + T + "workflow_runs runs ON runs.run_id = links.run_id "
                    + "WHERE links.root_id = ? ORDER BY links.run_id", RunRevision.class, rootId)) {
                Long revision = row.revision();
                RunState state = row.state();
                String updatedAt = row.updatedAtIso();
                if (revision == null || state == null || updatedAt == null) {
                    missing = true;
                    continue;
                }
                if (!state.isTerminal()) {
                    nonTerminal = true;
                }
                if (updatedAt.compareTo(cutoff) > 0) {
                    recent = true;
                }
                runs.add(new CleanupRun(row.runId(), revision, state, updatedAt));
            }
            if (missing || nonTerminal || recent) {
                linkedNonTerminal += nonTerminal ? 1 : 0;
                linkedRecent += recent ? 1 : 0;
                linkedMissing += missing ? 1 : 0;
                protectedLinked++;
                continue;
            }
            List<String> runIds = empty();
            runs.forEach(run -> runIds.add(run.runId()));
            rootCandidates.add(new CleanupRoot(rootId, root.revision(), root.state(), StoreValues.isoOrNull(root.updatedAt()), runIds));
            linkedSnapshots.add(new LinkedRuns(rootId, runs));
        }
        List<CleanupRun> standalone = jdbc.sql("SELECT run_id, revision, state, updated_at FROM " + T + "workflow_runs r WHERE updated_at <= :cutoff "
                + "AND NOT EXISTS (SELECT 1 FROM " + T + "workflow_attempt_links links WHERE links.run_id = r.run_id) AND state IN (:states) ORDER BY run_id")
                .param("cutoff", StoreValues.ts(cutoff)).param("states", names(TERMINAL_RUNS)).query(CleanupRunRow.class).list().stream().map(CleanupRunRow::toRun).toList();
        long active = jdbc.sql("SELECT count(*) FROM " + T + "convergence_roots WHERE state IN (:states)").param("states", names(ACTIVE_ROOTS)).query(Long.class).single();
        return new CleanupPreview(rootCandidates, standalone, active, new RootProtection(active, linkedNonTerminal, linkedRecent, linkedMissing, active + protectedLinked), linkedSnapshots);
    }

    public boolean claimCleanupPlan(String planId, String planDigest, String claimedAt) {
        return insertIfAbsent(SqlMerge.into(T + "state_cleanup_claims").key("plan_id", planId).set("plan_digest", planDigest).set("claimed_at", StoreValues.ts(claimedAt))) == 1;
    }

    /**
     * Verifies the preview and deletes, keeping a copy of the deleted rows in {@code workflow_cleanup_backup} under {@code planId}
     * (replacing VACUUM INTO, DBMS 3.2). The contract's backupPath is {@code pg-backup:workflow/<planId>}. Runs under the module lock.
     */
    public CleanupResult executeCleanup(CleanupPreview preview, String planId, String now) {
        for (CleanupRoot root : preview.roots()) {
            RootRevision row = one("SELECT revision, state, updated_at FROM " + T + "convergence_roots WHERE root_id = ?", RootRevision.class, root.rootId());
            if (row == null || root.state() != row.state() || root.revision() != row.revision() || !root.updatedAt().equals(row.updatedAtIso())) {
                throw new StoreException(StoreCode.STALE_REVISION, "A cleanup root changed after the preview.");
            }
            List<String> actualRunIds = textsOf("SELECT run_id FROM " + T + "workflow_attempt_links WHERE root_id = ? ORDER BY run_id", root.rootId());
            if (!actualRunIds.equals(root.runIds())) {
                throw new StoreException(StoreCode.STALE_REVISION, "A cleanup root's linked runs changed after the preview.");
            }
            LinkedRuns expected = firstOrNull(preview.linkedRuns().stream().filter(item -> item.rootId().equals(root.rootId())).toList());
            if (expected == null || !expected.runs().stream().map(CleanupRun::runId).toList().equals(root.runIds())) {
                throw new StoreException(StoreCode.STALE_REVISION, "A cleanup root's linked run receipt is missing.");
            }
            for (CleanupRun run : expected.runs()) {
                verifyRun(run);
            }
        }
        for (CleanupRun run : preview.standaloneRuns()) {
            verifyRun(run);
        }
        long next = 0;
        String backup = T + "workflow_cleanup_backup";
        String[] byRoot = {"workflow_attempt_links", "convergence_attempts", "convergence_reviews", "convergence_issued_leases", "convergence_leases", "convergence_epochs", "convergence_root_identities", "convergence_active_surfaces", "convergence_roots"};
        int deletedRuns = 0;
        for (CleanupRoot root : preview.roots()) {
            for (String table : byRoot) {
                next = backup(mapper, backup, planId, now, T + table, "t.root_id = ?", next, root.rootId());
                update("DELETE FROM " + T + table + " WHERE root_id = ?", root.rootId());
            }
            for (String runId : root.runIds()) {
                next = deleteRun(backup, planId, now, runId, next);
                deletedRuns++;
            }
        }
        for (CleanupRun run : preview.standaloneRuns()) {
            next = deleteRun(backup, planId, now, run.runId(), next);
            deletedRuns++;
        }
        return new CleanupResult(preview.roots().size(), deletedRuns);
    }

    /** Deletes a run after the rows that refer to it (its skills, V22), each copied to the backup first; the row number the next copy takes. The kind is a column of the run row. */
    private long deleteRun(String backup, String planId, String now, String runId, long next) {
        long number = next;
        for (String child : new String[] {"workflow_run_skills", "workflow_runs"}) {
            number = backup(mapper, backup, planId, now, T + child, "t.run_id = ?", number, runId);
            update("DELETE FROM " + T + child + " WHERE run_id = ?", runId);
        }
        return number;
    }

    private void verifyRun(CleanupRun run) {
        RunRevision row = one("SELECT '' AS run_id, revision, state, updated_at FROM " + T + "workflow_runs WHERE run_id = ?", RunRevision.class, run.runId());
        if (row == null || run.state() != row.state() || run.revision() != row.revision() || !run.updatedAt().equals(row.updatedAtIso())) {
            throw new StoreException(StoreCode.STALE_REVISION, "A cleanup run changed after the preview.");
        }
    }

    // -------------------------------------------------------------------------------------------------------------- internals

    private StoredRoot rootRow(String rootId) {
        return one("SELECT root_json, revision, state FROM " + T + "convergence_roots WHERE root_id = ?", StoredRoot.class, rootId);
    }

    private StoredLease leaseRow(String leaseId) {
        return one("SELECT root_id, state, root_revision, epoch, ordinal, lease_json, proposal_json FROM " + T + "convergence_leases WHERE lease_id = ?", StoredLease.class, leaseId);
    }

    /** Writes the root only if it is still at expectedRevision; true when exactly one row changed. */
    private boolean casRoot(RootRow root, long expectedRevision) {
        boolean changed = update("UPDATE " + T + "convergence_roots SET revision = ?, state = ?, root_json = ?, updated_at = ? WHERE root_id = ? AND revision = ?",
                root.revision(), root.state(), root.rootJson(), StoreValues.ts(root.updatedAt()), root.rootId(), expectedRevision) == 1;
        // A root that is no longer active gives up its write surface in the same transaction, so the surface can be held by the next root. A write that lost the compare-and-set
        // changed nothing and keeps the surface.
        if (changed && !root.state().isActive()) {
            update("DELETE FROM " + T + "convergence_active_surfaces WHERE root_id = ?", root.rootId());
        }
        return changed;
    }

    private void insertEpoch(RootRow root, String createdAt) {
        update("INSERT INTO " + T + "convergence_epochs (root_id, epoch, frame_digest, created_at) VALUES (?, ?, ?, ?)",
                root.rootId(), root.currentEpoch(), root.frameDigest(), StoreValues.ts(createdAt));
    }

    /** A refreshed identity replaces the stored one. */
    private void saveRootIdentity(IdentityRow identity, String createdAt) {
        upsert(SqlMerge.into(T + "convergence_root_identities").key("root_id", identity.rootId()).set("identity_json", identity.identityJson()).set("surface_digest", identity.surfaceDigest())
                .insertOnly("created_at", StoreValues.ts(createdAt)));
    }
}
