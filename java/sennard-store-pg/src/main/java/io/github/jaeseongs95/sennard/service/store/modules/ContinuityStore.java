// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.store.ContextSource;
import io.github.jaeseongs95.sennard.service.store.MemoryStatus;
import io.github.jaeseongs95.sennard.service.store.RequestReceiptKind;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.SqlMerge;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Continuity: tasks, the current memory body and its history. Every method joins the transaction of the service method that calls it: what
 * several requests of one session may do at once is decided by the database. A state change is one statement whose condition is in its {@code WHERE} (the epoch, the state token, the
 * pending marker), and its changed rows say who won; a row that must exist once has a key that says so. The task row of a session is made by the use case that first needs it, in
 * that use case's own transaction ({@link #ensureTask}), and the observation, memory and request rows that refer to it have a foreign key, so a cleanup of the task cannot
 * leave them behind. The memory JSON is canonical text produced by the service layer; its digest is over those exact bytes, and it is compared as text.
 *
 * <p>The same-operationKey answer of {@code checkpoint_context} and {@code purge_direct_context} is kept in {@code continuity_requests}, not in the common {@code sennard.operation_receipt}
 * (OperationReceipts) that the other write tools use: these rows are scoped by task and epoch, refer to the task by a foreign key and are removed with the task by the state cleanup
 * (the cleanup's {@code byTask} list and {@link #scrubRequests}, the body-free rewrite of the receipts), which a global (tool, key) table without a foreign key cannot do (D-0267).
 */
@Repository
public class ContinuityStore extends ModuleStore {
    private static final String T = "sennard.";
    private static final String SIGNING_SECRET = "signing-secret";
    private static final String LINEAGE_ANCHOR_EVENT = "memory-anchor:";
    private static final String ROOT_BOUND_EVENT = "workflow-root:";
    private static final String RECEIPT_SCHEMA_VERSION = "1.0.0";
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern ANCHOR_EVENT = Pattern.compile("^([a-f0-9]{48}):(sha256:[0-9a-f]{64})$");
    private static final Logger LOG = LoggerFactory.getLogger(ContinuityStore.class);

    private final JsonMapper mapper;
    private final SqlBuilder limit;
    /** The context's mapper made strict for the receipt shapes: an unknown or missing field, a null, or a value of another JSON type is a failure, never coerced. */
    private final JsonMapper strictMapper;

    private final SecureRandom random;

    /** @param random the application's one {@link SecureRandom}, which makes the state tokens and the store's secret */
    public ContinuityStore(@Qualifier("storeJdbc") JdbcClient jdbc, JsonMapper mapper, SecureRandom random, SqlBuilder limit) {
        super(jdbc);
        this.limit = limit;
        this.random = random;
        this.mapper = mapper;
        this.strictMapper = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    /** Whether the task's memory may be put back into the conversation: the column holds the constant's name (a yes-or-no fact is a name that says what it means, not 0 and 1). */
    private enum Injection { ALLOWED, SUPPRESSED }

    /** Whether the pending marker was taken. */
    private enum PendingMarker { OPEN, CONSUMED }

    /** What an observation says happened. */
    private enum ObservationOutcome { SUCCEEDED, FAILED }

    public record Task(String taskCorrelation, long currentEpoch, String rootId, boolean suppressed, Long lastAutoInjectedRevision, ContextSource pendingSource,
            Long pendingRevision, String pendingStateToken, String pendingDigest, String pendingRootId, boolean pendingConsumed, String updatedAt) {
    }

    /** The current body of a task; {@code memoryJson} and {@code memoryDigest} are null for an empty current. */
    public record Snapshot(String taskCorrelation, long epoch, String stateToken, String memoryJson, String memoryDigest, String createdAt, String updatedAt) {
    }

    public record StoredRequest(String commandDigest, String resultJson) {
    }

    public record Tombstone(String stateToken, String emptyStateToken, String payloadDigest, String purgedAt) {
    }

    /** What a checkpoint did; each outcome carries only what it has, so a caller's {@code switch} over it is exhaustive without a {@code default}. */
    public sealed interface CheckpointResult permits EpochMismatch, Conflict, Stale, Replay, Stored {
    }

    /** What a purge did. */
    public sealed interface PurgeResult permits EpochMismatch, Conflict, Stale, Replay, Purged {
    }

    /** The task is not at the epoch the caller named. */
    public record EpochMismatch() implements CheckpointResult, PurgeResult {
    }

    /** The request id was used before with a different command. */
    public record Conflict() implements CheckpointResult, PurgeResult {
    }

    /** The current state token is not the one the caller expected. */
    public record Stale(String actualStateToken) implements CheckpointResult, PurgeResult {
    }

    /** The same request, same command: the first answer. */
    public record Replay(StoredRequest request) implements CheckpointResult, PurgeResult {
    }

    public record Stored(Snapshot snapshot) implements CheckpointResult {
    }

    public record Purged(Snapshot snapshot) implements PurgeResult {
    }

    public record Expected(long epoch, String rootId, BooleanSupplier isTerminal, boolean carry) {
    }

    // The rows as the tables have them. Spring's row mapper fills a record by its component names ({@code task_correlation} is {@code taskCorrelation}), reads an enum column's name
    // back into the constant, a constant's name into the enum and a TIMESTAMP column into a LocalDateTime (the stored UTC fields, no zone); the public records turn the times into text.
    private record TaskRow(String taskCorrelation, long currentEpoch, String rootId, Injection injection, Long lastAutoInjectedRevision, ContextSource pendingSource, Long pendingRevision,
            String pendingStateToken, String pendingDigest, String pendingRootId, PendingMarker pendingMarker, LocalDateTime updatedAt) {
        Task toTask() {
            return new Task(taskCorrelation, currentEpoch, rootId, injection == Injection.SUPPRESSED, lastAutoInjectedRevision, pendingSource, pendingRevision, pendingStateToken, pendingDigest,
                    pendingRootId, pendingMarker == PendingMarker.CONSUMED, StoreValues.isoOrNull(updatedAt));
        }
    }

    private record SnapshotRow(String taskCorrelation, long epoch, String stateToken, String memoryJson, String memoryDigest, LocalDateTime createdAt, LocalDateTime updatedAt) {
        Snapshot toSnapshot() {
            return new Snapshot(taskCorrelation, epoch, stateToken, memoryJson, memoryDigest, StoreValues.isoOrNull(createdAt), StoreValues.isoOrNull(updatedAt));
        }
    }

    private record TombstoneRow(String stateToken, String emptyStateToken, String payloadDigest, LocalDateTime purgedAt) {
        Tombstone toTombstone() {
            return new Tombstone(stateToken, emptyStateToken, payloadDigest, StoreValues.isoOrNull(purgedAt));
        }
    }

    private record ObservationRow(long observationId, LocalDateTime observedAt) {
    }

    /** The first row a query finds, or null. */
    private <T> T firstOf(Class<T> type, String sql, Object... params) {
        List<T> found = jdbc.sql(sql).params(params).query(type).list();
        return found.isEmpty() ? null : found.get(0);
    }

    /** The first column of the first row, or null when no row matches or the value is null. */
    private <T> T valueOf(Class<T> type, String sql, Object... params) {
        List<T> found = jdbc.sql(sql).params(params).query(type).list();
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean exists(String sql, Object... params) {
        return jdbc.sql("SELECT COUNT(*) FROM (" + sql + ") matched").params(params).query(Long.class).single() > 0;
    }

    private static String digest(String memoryJson) {
        return "sha256:" + StoreValues.sha256Hex(memoryJson);
    }

    /** The task's status, read in Java: json_extract's PostgreSQL equivalents fail on a {@code \u0000} in another field (DBMS 3.2, D6). */
    private boolean statusIsActive(String memoryJson) {
        return memoryJson != null && Optional.ofNullable(mapper.readValue(memoryJson, MemoryHead.class).status()).map(MemoryStatus::isKept).orElse(false);
    }

    /** The one field of a stored memory the cleanup reads; the rest of the memory is the service layer's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MemoryHead(MemoryStatus status) {
    }

    /**
     * A value that is not stored in the clear: HMAC-SHA256 under this store's secret, in hex. One place, because the continuity and
     * the workflow rules find each other's rows by it: a session's task, the files it counted, the actor that owns a root.
     */
    public String opaqueDigest(String kind, String value) {
        return "hmac-sha256:" + Digests.hmacSha256Hex(signingSecret(), kind + "\0" + value);
    }

    /**
     * The store's secret. It is made once, by {@link #createSecretIfAbsent} when the server starts, and a request only reads it: a request that needed to make it would race the
     * others for a key that exists once in the whole server.
     */
    public SecretValue signingSecret() {
        String stored = valueOf(String.class, "SELECT value FROM " + T + "continuity_metadata WHERE meta_key = ?", SIGNING_SECRET);
        if (stored == null) {
            throw new StoreException(StoreCode.CONTINUITY_SECRET_MISSING, "The continuity signing secret has not been made: the server makes it when it starts.");
        }
        return SecretValue.of(stored);
    }

    /** The server's start: stores the secret when there is none. The key is the table's primary key, so a second server starting at the same time cannot store another. */
    public void createSecretIfAbsent() {
        insertIfAbsent(SqlMerge.into(T + "continuity_metadata").key("meta_key", SIGNING_SECRET).set("value", StoreValues.randomBase64Url(random, 32)));
    }

    /**
     * The task of a session, made with its empty current memory when the session has none (two {@code INSERT}s). It joins the transaction of the use case that calls it. Of two
     * first requests of one session that both find none, the second {@code INSERT} meets the first one's primary key and fails with {@code STORE_UNIQUE_VIOLATION}; nothing
     * catches that or runs the use case again.
     */
    public Task ensureTask(String taskCorrelation, String now) {
        Task existing = readTask(taskCorrelation);
        if (existing != null) {
            return existing;
        }
        update("INSERT INTO " + T + "continuity_tasks (task_correlation, current_epoch, updated_at) VALUES (?, ?, ?)", taskCorrelation, 1L, StoreValues.ts(now));
        insertEmptyCurrent(taskCorrelation, 1, now);
        return readTask(taskCorrelation);
    }

    public Task readTask(String taskCorrelation) {
        TaskRow row = firstOf(TaskRow.class, "SELECT * FROM " + T + "continuity_tasks WHERE task_correlation = ?", taskCorrelation);
        return row == null ? null : row.toTask();
    }

    /**
     * Moves the task to its next epoch; null when the task is absent. With {@code expected}, only while that epoch and root are current and the root is settled. The move is the
     * {@code UPDATE} whose {@code WHERE} names the epoch and the root: of two requests that move the same epoch the second one's statement finds the epoch already moved and changes
     * no row, and only the one that changed the row writes the archive and the new memory.
     */
    public Task rotateEpoch(String taskCorrelation, String now, Expected expected) {
        Task current = readTask(taskCorrelation);
        if (current == null) {
            return null;
        }
        if (expected != null && (current.currentEpoch() != expected.epoch() || !java.util.Objects.equals(current.rootId(), expected.rootId())
                || !expected.isTerminal().getAsBoolean())) {
            return current;
        }
        Snapshot previous = getCurrent(taskCorrelation, current.currentEpoch());
        // A root that is NULL is found by IS NULL, not by "= NULL"; the two statements differ in that condition only.
        String rootCondition = current.rootId() == null ? "root_id IS NULL" : "root_id = ?";
        String rotateSql = "UPDATE " + T + "continuity_tasks SET current_epoch = current_epoch + 1, root_id = NULL, injection = 'ALLOWED', "
                + "last_auto_injected_revision = NULL, pending_source = NULL, pending_revision = NULL, pending_state_token = NULL, pending_digest = NULL, pending_root_id = NULL, "
                + "pending_marker = 'OPEN', updated_at = ? WHERE task_correlation = ? AND current_epoch = ? AND " + rootCondition;
        long rotated = current.rootId() == null
                ? update(rotateSql, StoreValues.ts(now), taskCorrelation, current.currentEpoch())
                : update(rotateSql, StoreValues.ts(now), taskCorrelation, current.currentEpoch(), current.rootId());
        if (previous != null && rotated == 1) {
            archiveCurrent(previous, now, expected != null && expected.carry() ? "epoch-carry" : "clear");
            boolean carried = expected != null && expected.carry() && previous.memoryJson() != null;
            writeCurrent(taskCorrelation, current.currentEpoch() + 1, carried ? previous.memoryJson() : null, now, carried ? previous.createdAt() : now);
        }
        return readTask(taskCorrelation);
    }

    /** Binds one more root to the task's epoch; the primary stays the newest. Compare-and-set on the epoch only. */
    public boolean bindRoot(String taskCorrelation, long epoch, String rootId, String now) {
        long updated = update("UPDATE " + T + "continuity_tasks SET root_id = ?, injection = 'ALLOWED', updated_at = ? WHERE task_correlation = ? AND current_epoch = ?",
                rootId, StoreValues.ts(now), taskCorrelation, epoch);
        if (updated != 1) {
            return false;
        }
        recordObservationOnce(taskCorrelation, epoch, ROOT_BOUND_EVENT + rootId, null, true, now);
        return true;
    }

    /** Serializes admission of a new root for this session until the caller's transaction commits. Count in a subsequent statement (READ_COMMITTED). */
    public boolean serializeRootAdmission(String taskCorrelation, long epoch) {
        return update("UPDATE " + T + "continuity_tasks SET updated_at = updated_at WHERE task_correlation = ? AND current_epoch = ?", taskCorrelation, epoch) == 1;
    }

    /** Active roots owned by this session in every epoch, counted once, including a legacy primary without its binding observation. */
    public long openRootCount(String taskCorrelation) {
        List<String> active = java.util.Arrays.stream(RootState.values()).filter(RootState::isActive).map(Enum::name).toList();
        return ownedRootsQuery("SELECT COUNT(*) FROM " + T + "convergence_roots r WHERE r.state IN (:states) AND r.root_id IN (" + OWNED_ROOT_IDS + ")", taskCorrelation)
                .param("states", active).query(Long.class).single();
    }

    private static final String OWNED_ROOT_IDS = "SELECT SUBSTRING(o.event FROM :start) FROM " + T + "continuity_observations o WHERE o.task_correlation = :task "
            + "AND SUBSTRING(o.event FROM 1 FOR :length) = :prefix UNION SELECT t.root_id FROM " + T + "continuity_tasks t WHERE t.task_correlation = :task AND t.root_id IS NOT NULL";

    private JdbcClient.StatementSpec ownedRootsQuery(String sql, String taskCorrelation) {
        return jdbc.sql(sql).param("start", ROOT_BOUND_EVENT.length() + 1).param("length", ROOT_BOUND_EVENT.length()).param("prefix", ROOT_BOUND_EVENT).param("task", taskCorrelation);
    }

    public record SessionWorkflowRow(String rootId, String runId, long revision, String state, String kind) { }

    /**
     * All retained runs and all active roots across every epoch of this session. Legacy continuity keys use the
     * raw session ID, so a collision returns one row with no root ID. Check the binding and select the rows from
     * the same statement snapshot.
     */
    public List<SessionWorkflowRow> listSessionWorkflows(String taskCorrelation, String host, String sessionId) {
        List<String> active = java.util.Arrays.stream(RootState.values()).filter(RootState::isActive).map(Enum::name).toList();
        return ownedRootsQuery("WITH ambiguity AS (SELECT EXISTS (SELECT 1 FROM " + T + "session_ref WHERE local_session_id = :sessionId AND host <> :host) AS present), "
                + "owned (root_id) AS (SELECT root_id FROM (" + OWNED_ROOT_IDS + ") bound(root_id) WHERE NOT (SELECT present FROM ambiguity)) "
                + "SELECT r.root_id, NULL AS run_id, r.revision, r.state, NULL AS kind FROM " + T + "convergence_roots r JOIN owned o ON o.root_id = r.root_id WHERE r.state IN (:states) "
                + "UNION ALL SELECT l.root_id, w.run_id, w.revision, w.state, w.kind FROM " + T + "workflow_attempt_links l "
                + "JOIN owned o ON o.root_id = l.root_id JOIN " + T + "workflow_runs w ON w.run_id = l.run_id "
                + "UNION ALL SELECT NULL, NULL, 0, NULL, NULL FROM ambiguity WHERE present ORDER BY root_id, run_id", taskCorrelation)
                .param("states", active).param("host", host).param("sessionId", sessionId).query(SessionWorkflowRow.class).list();
    }

    /**
     * Records the observation unless the task already has one of this event and hash in this epoch: one statement, so the question and the insert are not two steps. True for the
     * request that wrote it. Two statements that start together can both find none and both write (there is no key over the observations); the rows are then equal, and what
     * reads them takes either, so the request that needs exactly one winner uses a statement whose {@code WHERE} decides it (see {@link #claimTurnAsk}).
     */
    public boolean recordObservationOnce(String taskCorrelation, long epoch, String event, String turnHash, boolean success, String now) {
        // The same hash is found by "=", and no hash by IS NULL. The selected values are cast: a parameter in a select list has no column to take its type from.
        String hashCondition = turnHash == null ? "o.turn_hash IS NULL" : "o.turn_hash = ?";
        List<Object> params = new ArrayList<>(List.of(epoch, event));
        params.add(turnHash);
        params.add(success ? ObservationOutcome.SUCCEEDED : ObservationOutcome.FAILED);
        params.add(StoreValues.ts(now));
        params.add(taskCorrelation);
        params.add(epoch);
        params.add(event);
        if (turnHash != null) {
            params.add(turnHash);
        }
        return update("INSERT INTO " + T + "continuity_observations (task_correlation, epoch, event, turn_hash, outcome, observed_at) "
                + "SELECT t.task_correlation, CAST(? AS BIGINT), CAST(? AS VARCHAR(500)), CAST(? AS VARCHAR(255)), CAST(? AS VARCHAR(16)), CAST(? AS TIMESTAMP) FROM " + T + "continuity_tasks t "
                + "WHERE t.task_correlation = ? AND NOT EXISTS (SELECT 1 FROM " + T + "continuity_observations o WHERE o.task_correlation = t.task_correlation AND o.epoch = ? AND o.event = ? AND "
                + hashCondition + ")", params.toArray()) == 1;
    }

    /**
     * One ask per turn: the task row records the turn (the observation id of its start) the question was put for, and only the statement that moves it forward changes the row.
     * True for the request that asked; the others find the turn already asked.
     */
    public boolean claimTurnAsk(String taskCorrelation, long turnStartId) {
        return update("UPDATE " + T + "continuity_tasks SET turn_asked_start = ? WHERE task_correlation = ? AND (turn_asked_start IS NULL OR turn_asked_start < ?)",
                turnStartId, taskCorrelation, turnStartId) == 1;
    }

    /** Every root bound to this epoch, oldest first; a primary bound without its row is included. */
    public List<String> boundRoots(String taskCorrelation, long epoch) {
        Task task = readTask(taskCorrelation);
        Set<String> roots = new LinkedHashSet<>();
        for (String event : observationEventsAfter(taskCorrelation, epoch, ROOT_BOUND_EVENT, 0)) {
            roots.add(event.substring(ROOT_BOUND_EVENT.length()));
        }
        if (task != null && task.currentEpoch() == epoch && task.rootId() != null) {
            roots.add(task.rootId());
        }
        return new ArrayList<>(roots);
    }

    public boolean ownedElsewhere(String taskCorrelation, String rootId) {
        return exists("SELECT 1 AS one FROM " + T + "continuity_observations WHERE event = ? AND task_correlation <> ? "
                + "UNION ALL SELECT 1 FROM " + T + "continuity_tasks WHERE root_id = ? AND task_correlation <> ?",
                ROOT_BOUND_EVENT + rootId, taskCorrelation, rootId, taskCorrelation);
    }

    public boolean rootBound(String rootId) {
        return exists("SELECT 1 AS one FROM " + T + "continuity_observations WHERE event = ? "
                + "UNION ALL SELECT 1 FROM " + T + "continuity_tasks WHERE root_id = ?",
                ROOT_BOUND_EVENT + rootId, rootId);
    }

    /** The correlations of the tasks that bound this root, in any epoch, sorted: opaque digests, the only reference to an owner that outlives its session. */
    public List<String> rootOwners(String rootId) {
        return jdbc.sql("SELECT task_correlation FROM " + T + "continuity_observations WHERE event = ? "
                + "UNION SELECT task_correlation FROM " + T + "continuity_tasks WHERE root_id = ? ORDER BY task_correlation")
                .params(ROOT_BOUND_EVENT + rootId, rootId).query(String.class).list();
    }

    /** Every root the task ever bound, in any epoch, with its primary. */
    public List<String> allBoundRoots(String taskCorrelation) {
        Set<String> roots = new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT SUBSTRING(event FROM ?) AS root_id FROM " + T + "continuity_observations WHERE task_correlation = ? "
                + "AND SUBSTRING(event FROM 1 FOR ?) = ?").params(ROOT_BOUND_EVENT.length() + 1, taskCorrelation, ROOT_BOUND_EVENT.length(), ROOT_BOUND_EVENT).query(String.class).list());
        Task task = readTask(taskCorrelation);
        if (task != null && task.rootId() != null) {
            roots.add(task.rootId());
        }
        return new ArrayList<>(roots);
    }

    public boolean ownsRoot(String taskCorrelation, String rootId) {
        Task task = readTask(taskCorrelation);
        if (task != null && rootId.equals(task.rootId())) {
            return true;
        }
        return exists("SELECT 1 AS one FROM " + T + "continuity_observations WHERE task_correlation = ? AND event = ?",
                taskCorrelation, ROOT_BOUND_EVENT + rootId);
    }

    public boolean setSuppressed(String taskCorrelation, long epoch, String now) {
        return update("UPDATE " + T + "continuity_tasks SET injection = 'SUPPRESSED', updated_at = ? WHERE task_correlation = ? AND current_epoch = ?",
                StoreValues.ts(now), taskCorrelation, epoch) == 1;
    }

    /** Current only: an old epoch never selects history. Null when absent, or when {@code epoch} is given and differs. */
    public Snapshot getCurrent(String taskCorrelation, Long epoch) {
        SnapshotRow row = firstOf(SnapshotRow.class, "SELECT * FROM " + T + "continuity_current_memory WHERE task_correlation = ?", taskCorrelation);
        if (row == null || (epoch != null && row.epoch() != epoch)) {
            return null;
        }
        Snapshot snapshot = row.toSnapshot();
        String memoryJson = snapshot.memoryJson();
        String memoryDigest = snapshot.memoryDigest();
        if ((memoryJson == null) != (memoryDigest == null) || (memoryJson != null && !digest(memoryJson).equals(memoryDigest))) {
            throw new StoreException(StoreCode.INTEGRITY_FAILED, "CONTINUITY_MEMORY_DIGEST_MISMATCH: the stored memory does not match its digest.");
        }
        return snapshot;
    }

    public StoredRequest getRequest(String taskCorrelation, long epoch, String operationKeyHash) {
        return firstOf(StoredRequest.class, "SELECT command_digest, result_json FROM " + T + "continuity_requests WHERE task_correlation = ? AND epoch = ? AND operation_key_hash = ?",
                taskCorrelation, epoch, operationKeyHash);
    }

    public Tombstone getTombstone(String taskCorrelation, long epoch) {
        TombstoneRow row = firstOf(TombstoneRow.class, "SELECT state_token, empty_state_token, payload_digest, purged_at FROM " + T + "continuity_tombstones WHERE task_correlation = ? AND epoch = ?",
                taskCorrelation, epoch);
        return row == null ? null : row.toTombstone();
    }

    /**
     * Writes the memory when it changes (archiving the old body), and always records the idempotent request. The write is the compare-and-set of {@link #replaceCurrent}: of two
     * checkpoints that name the same state token one changes the row and the other is told the state moved.
     */
    public CheckpointResult checkpoint(String taskCorrelation, long epoch, String expectedStateToken, String operationKeyHash, String commandDigest,
            String memoryJson, String now, List<String> lineageAnchors) {
        Task task = readTask(taskCorrelation);
        if (task == null || task.currentEpoch() != epoch) {
            return new EpochMismatch();
        }
        StoredRequest replay = getRequest(taskCorrelation, epoch, operationKeyHash);
        if (replay != null) {
            return replay.commandDigest().equals(commandDigest) ? new Replay(replay) : new Conflict();
        }
        Snapshot current = getCurrent(taskCorrelation, epoch);
        if (current == null || !current.stateToken().equals(expectedStateToken)) {
            return new Stale(current == null ? null : current.stateToken());
        }
        Snapshot snapshot = current;
        if (current.memoryJson() == null || !current.memoryJson().equals(memoryJson)) {
            snapshot = replaceCurrent(taskCorrelation, epoch, current.stateToken(), memoryJson, now, current.memoryJson() != null ? current.createdAt() : now);
            if (snapshot == null) {
                Snapshot moved = getCurrent(taskCorrelation, epoch);
                return new Stale(moved == null ? null : moved.stateToken());
            }
            archiveCurrent(current, now, "update");
            if (!task.suppressed() && task.pendingSource() == ContextSource.DIRECT && !task.pendingConsumed() && current.memoryDigest() != null
                    && current.stateToken().equals(task.pendingStateToken()) && current.memoryDigest().equals(task.pendingDigest())) {
                update("UPDATE " + T + "continuity_tasks SET pending_state_token = ?, pending_digest = ?, updated_at = ? WHERE task_correlation = ? AND current_epoch = ? "
                        + "AND injection = 'ALLOWED' AND pending_source = ? AND pending_marker = 'OPEN' AND pending_state_token = ? AND pending_digest = ?",
                        snapshot.stateToken(), snapshot.memoryDigest(), StoreValues.ts(now), taskCorrelation, epoch, ContextSource.DIRECT, current.stateToken(), current.memoryDigest());
            }
        }
        String resultJson = mapper.writeValueAsString(new CheckpointReceipt(RECEIPT_SCHEMA_VERSION, RequestReceiptKind.CHECKPOINT, epoch, snapshot.stateToken(), snapshot.memoryDigest()));
        insertRequest(taskCorrelation, epoch, operationKeyHash, commandDigest, resultJson, now);
        insertLineageAnchors(taskCorrelation, epoch, snapshot.stateToken(), snapshot.memoryDigest(), lineageAnchors, now);
        return new Stored(snapshot);
    }

    private void insertRequest(String taskCorrelation, long epoch, String operationKeyHash, String commandDigest, String resultJson, String now) {
        update("INSERT INTO " + T + "continuity_requests (task_correlation, epoch, operation_key_hash, command_digest, result_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                taskCorrelation, epoch, operationKeyHash, commandDigest, resultJson, StoreValues.ts(now));
    }

    private void insertLineageAnchors(String taskCorrelation, long epoch, String stateToken, String digest, List<String> anchors, String now) {
        String event = LINEAGE_ANCHOR_EVENT + stateToken + ":" + digest;
        for (String anchor : anchors) {
            recordObservationOnce(taskCorrelation, epoch, event, anchor, true, now);
        }
    }

    /** Re-anchors the current direct snapshot only while it is still that exact revision. */
    public boolean reanchorCurrent(String taskCorrelation, long epoch, String stateToken, String digest, List<String> anchors, String now) {
        Task task = readTask(taskCorrelation);
        if (task == null || task.currentEpoch() != epoch || task.rootId() != null) {
            return false;
        }
        Snapshot current = getCurrent(taskCorrelation, epoch);
        if (current == null || current.memoryJson() == null || !current.stateToken().equals(stateToken) || !digest.equals(current.memoryDigest())) {
            return false;
        }
        insertLineageAnchors(taskCorrelation, epoch, stateToken, digest, anchors, now);
        return true;
    }

    /** The oldest event with this prefix, or null. */
    public String firstObservationEvent(String taskCorrelation, long epoch, String prefix) {
        return valueOf(String.class, limit.applyLimit("SELECT event FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? "
                + "AND SUBSTRING(event FROM 1 FOR ?) = ? ORDER BY observation_id ASC", "?"), taskCorrelation, epoch, prefix.length(), prefix, 1);
    }

    /** Whether the current epoch holds {@code event} and no row starting with one of the closing prefixes. */
    public boolean hasOpenCurrentEpochEvent(String taskCorrelation, String event, List<String> closingPrefixes) {
        List<Object> params = new ArrayList<>(List.of(event, taskCorrelation));
        for (String prefix : closingPrefixes) {
            params.add(prefix.length());
            params.add(prefix);
        }
        String statement = limit.query("SELECT 1 AS one FROM " + T + "continuity_tasks task JOIN " + T + "continuity_observations pending ON "
                + "pending.task_correlation = task.task_correlation AND pending.epoch = task.current_epoch AND pending.event = ? WHERE task.task_correlation = ? "
                + "AND NOT EXISTS (SELECT 1 FROM " + T + "continuity_observations closing WHERE closing.task_correlation = task.task_correlation "
                + "AND closing.epoch = task.current_epoch AND ")
                .orGroup("SUBSTRING(closing.event FROM 1 FOR ?) = ?", closingPrefixes.size()).append(")").build();
        return exists(statement, params.toArray());
    }

    public boolean hasObservation(String taskCorrelation, long epoch, String event) {
        return exists("SELECT 1 AS one FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ?",
                taskCorrelation, epoch, event);
    }

    public record LineageAnchor(String anchor, String taskCorrelation, long epoch, String stateToken, String digest, long order) {
    }

    /** Every anchor row of other tasks recorded before {@code beforeObservationId}, in store order. */
    public List<LineageAnchor> lineageAnchorRows(String excludeTaskCorrelation, long beforeObservationId) {
        return jdbc.sql("SELECT task_correlation, epoch, event, turn_hash, MIN(observation_id) OVER (PARTITION BY task_correlation, epoch, event) AS saved_order "
                + "FROM " + T + "continuity_observations WHERE task_correlation <> ? AND observation_id < ? AND turn_hash IS NOT NULL AND SUBSTRING(event FROM 1 FOR ?) = ? "
                + "ORDER BY observation_id ASC").params(excludeTaskCorrelation, beforeObservationId, LINEAGE_ANCHOR_EVENT.length(), LINEAGE_ANCHOR_EVENT).query(AnchorRow.class).list().stream()
                .map(AnchorRow::toAnchor).flatMap(Optional::stream).toList();
    }

    /** An anchor event as the table has it; its name carries the state token and the digest ({@link #ANCHOR_EVENT}), a row whose name has another shape is no anchor. */
    private record AnchorRow(String taskCorrelation, long epoch, String event, String turnHash, long savedOrder) {
        Optional<LineageAnchor> toAnchor() {
            Matcher match = ANCHOR_EVENT.matcher(event.substring(LINEAGE_ANCHOR_EVENT.length()));
            return match.matches() ? Optional.of(new LineageAnchor(turnHash, taskCorrelation, epoch, match.group(1), match.group(2), savedOrder)) : Optional.empty();
        }
    }

    public record ObservationAt(long id, String at) {
    }

    public ObservationAt firstObservation(String taskCorrelation, long epoch, String event) {
        ObservationRow row = firstOf(ObservationRow.class, limit.applyLimit("SELECT observation_id, observed_at FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ? "
                + "ORDER BY observation_id ASC", "?"), taskCorrelation, epoch, event, 1);
        return row == null ? null : new ObservationAt(row.observationId(), StoreValues.isoOrNull(row.observedAt()));
    }

    /** The newest row of this event for the task in any of its epochs, or null: a turn does not end where an epoch does. */
    public ObservationAt lastObservationAnyEpoch(String taskCorrelation, String event) {
        ObservationRow row = firstOf(ObservationRow.class, limit.applyLimit("SELECT observation_id, observed_at FROM " + T + "continuity_observations WHERE task_correlation = ? AND event = ? "
                + "ORDER BY observation_id DESC", "?"), taskCorrelation, event, 1);
        return row == null ? null : new ObservationAt(row.observationId(), StoreValues.isoOrNull(row.observedAt()));
    }

    /** Whether the task holds a row of this event with this hash after {@code afterId}, in any of its epochs. */
    public boolean hasObservationAnyEpochAfter(String taskCorrelation, String event, String turnHash, long afterId) {
        return exists(limit.applyLimit("SELECT 1 AS one FROM " + T + "continuity_observations WHERE task_correlation = ? AND event = ? AND turn_hash = ? AND observation_id > ? "
                + "ORDER BY observation_id ASC", "?"), taskCorrelation, event, turnHash, afterId, 1);
    }

    public Long lastObservationId(String taskCorrelation, long epoch, String event, long beforeId) {
        return valueOf(Long.class, "SELECT max(observation_id) AS id FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ? AND observation_id < ?",
                taskCorrelation, epoch, event, beforeId);
    }

    public Long firstObservationIdAfter(String taskCorrelation, long epoch, String event, long afterId) {
        return valueOf(Long.class, "SELECT min(observation_id) AS id FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ? AND observation_id > ?",
                taskCorrelation, epoch, event, afterId);
    }

    public List<String> observationHashesAfter(String taskCorrelation, long epoch, String event, long afterId) {
        return jdbc.sql("SELECT turn_hash FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ? AND observation_id > ? "
                + "AND turn_hash IS NOT NULL ORDER BY observation_id ASC").params(taskCorrelation, epoch, event, afterId).query(String.class).list();
    }

    public List<String> observationEventsAfter(String taskCorrelation, long epoch, String prefix, long afterId) {
        return jdbc.sql("SELECT event FROM " + T + "continuity_observations WHERE task_correlation = ? AND epoch = ? AND SUBSTRING(event FROM 1 FOR ?) = ? "
                + "AND observation_id > ? ORDER BY observation_id ASC").params(taskCorrelation, epoch, prefix.length(), prefix, afterId).query(String.class).list();
    }

    /** Copies a selected parent snapshot into an empty child epoch only; existing child state is never replaced. */
    public Snapshot adoptCurrent(String taskCorrelation, long epoch, Snapshot snapshot, String now) {
        Task task = readTask(taskCorrelation);
        if (task == null || task.currentEpoch() != epoch || task.rootId() != null || task.suppressed() || snapshot.memoryJson() == null) {
            return null;
        }
        Snapshot current = getCurrent(taskCorrelation, epoch);
        if (current == null || current.memoryJson() != null || getTombstone(taskCorrelation, epoch) != null) {
            return null;
        }
        return replaceCurrent(taskCorrelation, epoch, current.stateToken(), snapshot.memoryJson(), now, now);
    }

    public PurgeResult purge(String taskCorrelation, long epoch, String expectedStateToken, String operationKeyHash, String commandDigest, String tombstoneDigest, String now) {
        Task task = readTask(taskCorrelation);
        if (task == null || task.currentEpoch() != epoch) {
            return new EpochMismatch();
        }
        StoredRequest replay = getRequest(taskCorrelation, epoch, operationKeyHash);
        if (replay != null) {
            return replay.commandDigest().equals(commandDigest) ? new Replay(replay) : new Conflict();
        }
        Snapshot current = getCurrent(taskCorrelation, epoch);
        if (current == null || current.memoryJson() == null || !current.stateToken().equals(expectedStateToken)) {
            return new Stale(current == null ? null : current.stateToken());
        }
        Snapshot snapshot = tombstone(current, tombstoneDigest, now);
        if (snapshot == null) {
            Snapshot moved = getCurrent(taskCorrelation, epoch);
            return new Stale(moved == null ? null : moved.stateToken());
        }
        scrubRequests(taskCorrelation, epoch, purgedRequestJson(epoch, expectedStateToken, tombstoneDigest, now));
        String resultJson = mapper.writeValueAsString(new PurgeAnswer(RECEIPT_SCHEMA_VERSION, true, epoch, expectedStateToken, snapshot.stateToken(), tombstoneDigest, now));
        insertRequest(taskCorrelation, epoch, operationKeyHash, commandDigest, resultJson, now);
        return new Purged(snapshot);
    }

    private String purgedRequestJson(long epoch, String stateToken, String tombstoneDigest, String purgedAt) {
        return mapper.writeValueAsString(new PurgedRequestReceipt(RECEIPT_SCHEMA_VERSION, RequestReceiptKind.PURGED_REQUEST, epoch, stateToken, tombstoneDigest, purgedAt));
    }

    // The three shapes of a stored request receipt. A record's component order is the order Jackson writes its fields in, so each is the JSON the service layer reads back.

    /** What a request that saved a memory answers. */
    private record CheckpointReceipt(String schemaVersion, RequestReceiptKind kind, long epoch, String stateToken, String memoryDigest) {
    }

    /** What is left of such an answer after the memory was purged. */
    private record PurgedRequestReceipt(String schemaVersion, RequestReceiptKind kind, long epoch, String stateToken, String tombstoneDigest, String purgedAt) {
    }

    /** What the purge request itself answers (the older shape, which has no {@code kind}). */
    private record PurgeAnswer(String schemaVersion, boolean purged, long epoch, String stateToken, String emptyStateToken, String tombstoneDigest, String purgedAt) {
    }

    /** Marks the bound root's workflow card as the memory to restore after a compaction ({@link ContextSource#WORKFLOW}). */
    public boolean setWorkflowPendingMarker(String taskCorrelation, long epoch, long revision, String digest, String rootId, String now) {
        if (rootId == null) {
            return false;
        }
        return update("UPDATE " + T + "continuity_tasks SET pending_source = ?, pending_revision = ?, pending_state_token = NULL, pending_digest = ?, pending_root_id = ?, pending_marker = 'OPEN', updated_at = ? "
                + "WHERE task_correlation = ? AND current_epoch = ? AND injection = 'ALLOWED' AND root_id = ?", ContextSource.WORKFLOW, revision, digest, rootId, StoreValues.ts(now), taskCorrelation, epoch, rootId) == 1;
    }

    public boolean setDirectPendingMarker(String taskCorrelation, long epoch, String stateToken, String digest, String now) {
        return update("UPDATE " + T + "continuity_tasks SET pending_source = ?, pending_revision = NULL, pending_state_token = ?, pending_digest = ?, pending_root_id = NULL, pending_marker = 'OPEN', updated_at = ? "
                + "WHERE task_correlation = ? AND current_epoch = ? AND injection = 'ALLOWED' "
                + "AND EXISTS (SELECT 1 FROM " + T + "continuity_current_memory memory WHERE "
                + "memory.task_correlation = continuity_tasks.task_correlation AND memory.epoch = continuity_tasks.current_epoch AND memory.state_token = ? AND memory.memory_digest = ?)",
                ContextSource.DIRECT, stateToken, digest, StoreValues.ts(now), taskCorrelation, epoch, stateToken, digest) == 1;
    }

    public boolean consumeDirectMarker(String taskCorrelation, long epoch, String stateToken, String digest, String now) {
        return update("UPDATE " + T + "continuity_tasks SET pending_marker = 'CONSUMED', updated_at = ? WHERE task_correlation = ? AND current_epoch = ? AND injection = 'ALLOWED' "
                + "AND pending_source = ? AND pending_state_token = ? AND pending_digest = ? AND pending_marker = 'OPEN' "
                + "AND EXISTS (SELECT 1 FROM " + T + "continuity_current_memory memory WHERE "
                + "memory.task_correlation = continuity_tasks.task_correlation AND memory.epoch = continuity_tasks.current_epoch AND memory.state_token = ? AND memory.memory_digest = ? AND memory.memory_json IS NOT NULL)",
                StoreValues.ts(now), taskCorrelation, epoch, ContextSource.DIRECT, stateToken, digest, stateToken, digest) == 1;
    }

    public boolean consumeWorkflowMarker(String taskCorrelation, long epoch, String rootId, long revision, String digest, String now) {
        return update("UPDATE " + T + "continuity_tasks SET pending_marker = 'CONSUMED', last_auto_injected_revision = ?, updated_at = ? WHERE task_correlation = ? AND current_epoch = ? AND injection = 'ALLOWED' "
                + "AND root_id = ? AND pending_root_id = ? AND pending_source = ? AND pending_revision = ? AND pending_digest = ? AND pending_marker = 'OPEN'",
                revision, StoreValues.ts(now), taskCorrelation, epoch, rootId, rootId, ContextSource.WORKFLOW, revision, digest) == 1;
    }

    public void recordObservation(String taskCorrelation, long epoch, String event, String turnHash, boolean success, String now) {
        update("INSERT INTO " + T + "continuity_observations (task_correlation, epoch, event, turn_hash, outcome, observed_at) VALUES (?, ?, ?, ?, ?, ?)",
                taskCorrelation, epoch, event, turnHash, success ? ObservationOutcome.SUCCEEDED : ObservationOutcome.FAILED, StoreValues.ts(now));
    }

    // ------------------------------------------------------------------------------------------------------------ state cleanup

    public record CleanupSnapshot(String taskCorrelation, String rootId, long epoch, String stateToken, String memoryDigest, String updatedAt) {
    }

    public record CleanupTask(String taskCorrelation, long currentEpoch, String rootId, String updatedAt) {
    }

    public record CleanupPreview(List<CleanupSnapshot> snapshots, List<CleanupTask> tasks, long protectedActiveTasks) {
    }

    public record CleanupResult(int snapshots, int tasks) {
    }

    public CleanupPreview previewCleanup(String payloadCutoff, String recordCutoff) {
        List<CleanupSnapshot> snapshots = new ArrayList<>();
        List<CleanupTask> tasks = new ArrayList<>();
        long protectedActive = 0;
        record Candidate(String taskCorrelation, long epoch, String stateToken, String memoryJson, String memoryDigest, LocalDateTime updatedAtValue, String rootId) {
            String updatedAt() {
                return StoreValues.isoOrNull(updatedAtValue);
            }
        }
        List<Candidate> candidates = jdbc.sql("SELECT c.task_correlation, c.epoch, c.state_token, c.memory_json, c.memory_digest, c.updated_at AS updated_at_value, t.root_id FROM " + T + "continuity_current_memory c "
                + "JOIN " + T + "continuity_tasks t ON t.task_correlation = c.task_correlation "
                + "ORDER BY c.task_correlation")
                .query(Candidate.class).list();
        for (Candidate row : candidates) {
            String correlation = row.taskCorrelation();
            String memoryJson = row.memoryJson();
            if (statusIsActive(memoryJson)) {
                protectedActive++;
                continue;
            }
            Task task = readTask(correlation);
            if (task.updatedAt().compareTo(recordCutoff) <= 0 && !taskHasRecentState(correlation, recordCutoff)) {
                tasks.add(new CleanupTask(correlation, task.currentEpoch(), task.rootId(), task.updatedAt()));
            } else if (memoryJson != null && row.updatedAt().compareTo(payloadCutoff) <= 0) {
                snapshots.add(new CleanupSnapshot(correlation, row.rootId(), row.epoch(), row.stateToken(), row.memoryDigest(), row.updatedAt()));
            }
        }
        return new CleanupPreview(snapshots, tasks, protectedActive);
    }

    /** The status of the current memory is read in Java (DBMS 3.2 D6); whether any row of the task is newer than the cutoff is five questions, each a row that exists or not. */
    private boolean taskHasRecentState(String taskCorrelation, String cutoffText) {
        LocalDateTime cutoff = StoreValues.ts(cutoffText);
        boolean recent = newerThan("continuity_current_memory", "updated_at", taskCorrelation, cutoff) || newerThan("continuity_memory_history", "archived_at", taskCorrelation, cutoff)
                || newerThan("continuity_requests", "created_at", taskCorrelation, cutoff) || newerThan("continuity_tombstones", "purged_at", taskCorrelation, cutoff)
                || newerThan("continuity_observations", "observed_at", taskCorrelation, cutoff);
        if (recent) {
            return true;
        }
        return statusIsActive(valueOf(String.class, "SELECT memory_json FROM " + T + "continuity_current_memory WHERE task_correlation = ?", taskCorrelation));
    }

    private boolean newerThan(String table, String timeColumn, String taskCorrelation, LocalDateTime cutoff) {
        return exists("SELECT 1 AS one FROM " + T + table + " WHERE task_correlation = ? AND " + timeColumn + " > ?", taskCorrelation, cutoff);
    }

    /**
     * Deletes what the preview selected, verifying it first, and keeps a copy of the deleted rows in {@code continuity_cleanup_backup} under
     * {@code planId} (replacing VACUUM INTO, DBMS 3.2). Runs under the module lock.
     */
    public CleanupResult executeCleanup(CleanupPreview preview, String planId, String now, String payloadCutoff, String recordCutoff) {
        for (CleanupSnapshot candidate : preview.snapshots()) {
            Task task = readTask(candidate.taskCorrelation());
            Snapshot current = getCurrent(candidate.taskCorrelation(), candidate.epoch());
            if (task == null || current == null || current.memoryJson() == null || statusIsActive(current.memoryJson()) || current.updatedAt().compareTo(payloadCutoff) > 0
                    || !java.util.Objects.equals(task.rootId(), candidate.rootId()) || !current.stateToken().equals(candidate.stateToken())
                    || !candidate.memoryDigest().equals(current.memoryDigest()) || !current.updatedAt().equals(candidate.updatedAt())) {
                throw new StoreException(StoreCode.CONTINUITY_CLEANUP_STALE, "The continuity snapshot changed after the preview.");
            }
        }
        for (CleanupTask candidate : preview.tasks()) {
            Task task = readTask(candidate.taskCorrelation());
            if (task == null || task.currentEpoch() != candidate.currentEpoch() || !java.util.Objects.equals(task.rootId(), candidate.rootId()) || !task.updatedAt().equals(candidate.updatedAt())
                    || taskHasRecentState(candidate.taskCorrelation(), recordCutoff)) {
                throw new StoreException(StoreCode.CONTINUITY_CLEANUP_STALE, "The continuity task changed after the preview.");
            }
        }
        long next = 0;
        String backup = T + "continuity_cleanup_backup";
        String by = "t.task_correlation = ?";
        for (CleanupSnapshot candidate : preview.snapshots()) {
            next = backup(mapper, backup, planId, now, T + "continuity_current_memory", by + " AND t.epoch = ?", next, candidate.taskCorrelation(), candidate.epoch());
            Snapshot current = getCurrent(candidate.taskCorrelation(), candidate.epoch());
            if (tombstone(current, candidate.memoryDigest(), now) == null) {
                throw new StoreException(StoreCode.CONTINUITY_CLEANUP_STALE, "The continuity snapshot changed while it was being erased.");
            }
            scrubRequests(candidate.taskCorrelation(), candidate.epoch(), purgedRequestJson(candidate.epoch(), candidate.stateToken(), candidate.memoryDigest(), now));
        }
        String[] byTask = {"continuity_current_memory", "continuity_memory_history", "continuity_requests", "continuity_tombstones", "continuity_observations", "continuity_tasks"};
        for (CleanupTask candidate : preview.tasks()) {
            for (String table : byTask) {
                next = backup(mapper, backup, planId, now, T + table, by, next, candidate.taskCorrelation());
                update("DELETE FROM " + T + table + " WHERE task_correlation = ?", candidate.taskCorrelation());
            }
        }
        return new CleanupResult(preview.snapshots().size(), preview.tasks().size());
    }

    /** Erases the payload of the snapshot, as the compare-and-set of {@link #replaceCurrent}; null when the snapshot is no longer the current one. */
    private Snapshot tombstone(Snapshot current, String payloadDigest, String now) {
        // Explicit erasure is not an update: the payload being removed is not archived.
        Snapshot empty = replaceCurrent(current.taskCorrelation(), current.epoch(), current.stateToken(), null, now, now);
        if (empty == null) {
            return null;
        }
        upsert(SqlMerge.into(T + "continuity_tombstones").key("task_correlation", current.taskCorrelation()).key("epoch", current.epoch()).set("state_token", current.stateToken())
                .set("empty_state_token", empty.stateToken()).set("payload_digest", payloadDigest).set("purged_at", StoreValues.ts(now)));
        return empty;
    }

    private void scrubRequests(String taskCorrelation, long epoch, String replacement) {
        record Stored(String operationKeyHash, String resultJson) {
        }
        for (Stored row : jdbc.sql("SELECT operation_key_hash, result_json FROM " + T + "continuity_requests WHERE task_correlation = ? AND epoch = ?")
                .params(taskCorrelation, epoch).query(Stored.class).list()) {
            if (!isBodyFreeRequestReceipt(row.resultJson())) {
                update("UPDATE " + T + "continuity_requests SET result_json = ? WHERE task_correlation = ? AND epoch = ? AND operation_key_hash = ?",
                        replacement, taskCorrelation, epoch, row.operationKeyHash());
            }
        }
    }

    private static boolean sha256(String digest) {
        return SHA256_DIGEST.matcher(digest).matches();
    }

    /** The JSON bound to the receipt shape with the strict mapper: empty when the text is no JSON, or is not exactly that shape (a field more or less, a null, another JSON type, an unknown constant). */
    private <R> Optional<R> receipt(String json, Class<R> shape) {
        try {
            return Optional.ofNullable(strictMapper.readValue(json, shape));
        } catch (JacksonException notThisShape) {
            LOG.debug("The stored request is not a {}", shape.getSimpleName(), notThisShape);
            return Optional.empty();
        }
    }

    /**
     * A stored request receipt that carries no memory body: exactly one of the three shapes above, with the schema version 1.0.0 and digests that are digests. A value that is not
     * JSON cannot be a receipt, so it counts as carrying a body and is scrubbed.
     */
    boolean isBodyFreeRequestReceipt(String resultJson) {
        return receipt(resultJson, CheckpointReceipt.class)
                .filter(checkpoint -> checkpoint.kind() == RequestReceiptKind.CHECKPOINT && RECEIPT_SCHEMA_VERSION.equals(checkpoint.schemaVersion()) && sha256(checkpoint.memoryDigest())).isPresent()
                || receipt(resultJson, PurgedRequestReceipt.class)
                        .filter(purged -> purged.kind() == RequestReceiptKind.PURGED_REQUEST && RECEIPT_SCHEMA_VERSION.equals(purged.schemaVersion()) && sha256(purged.tombstoneDigest())).isPresent()
                || receipt(resultJson, PurgeAnswer.class)
                        .filter(answer -> answer.purged() && RECEIPT_SCHEMA_VERSION.equals(answer.schemaVersion()) && sha256(answer.tombstoneDigest())).isPresent();
    }

    private void insertEmptyCurrent(String taskCorrelation, long epoch, String now) {
        insertIfAbsent(SqlMerge.into(T + "continuity_current_memory").key("task_correlation", taskCorrelation).set("epoch", epoch).set("state_token", StoreValues.randomHex(random, 24))
                .set("memory_json", null).set("memory_digest", null).set("created_at", StoreValues.ts(now)).set("updated_at", StoreValues.ts(now)));
    }

    /**
     * Replaces the current memory only while its state token is {@code expectedStateToken} (and it is of this epoch): one statement, and its changed rows say who won. Null when the
     * state moved, which is the loser's answer; what the loser had meant to write is dropped. The task's {@code updated_at} only says when the task was last touched, so two
     * writers that both set it leave a time of either.
     */
    private Snapshot replaceCurrent(String taskCorrelation, long epoch, String expectedStateToken, String memoryJson, String now, String createdAt) {
        String digestValue = memoryJson == null ? null : digest(memoryJson);
        String stateToken = StoreValues.randomHex(random, 24);
        int changed = update("UPDATE " + T + "continuity_current_memory SET state_token = ?, memory_json = ?, memory_digest = ?, created_at = ?, updated_at = ? "
                + "WHERE task_correlation = ? AND epoch = ? AND state_token = ?", stateToken, memoryJson, digestValue, StoreValues.ts(createdAt), StoreValues.ts(now), taskCorrelation, epoch, expectedStateToken);
        if (changed != 1) {
            return null;
        }
        update("UPDATE " + T + "continuity_tasks SET updated_at = ? WHERE task_correlation = ?", StoreValues.ts(now), taskCorrelation);
        return new Snapshot(taskCorrelation, epoch, stateToken, memoryJson, digestValue, createdAt, now);
    }

    /** Writes the memory of a task whatever the current one is: the epoch's move, which has already won the task row's {@code UPDATE}, writes the memory it carries. */
    private Snapshot writeCurrent(String taskCorrelation, long epoch, String memoryJson, String now, String createdAt) {
        String digestValue = memoryJson == null ? null : digest(memoryJson);
        String stateToken = StoreValues.randomHex(random, 24);
        upsert(SqlMerge.into(T + "continuity_current_memory").key("task_correlation", taskCorrelation).set("epoch", epoch).set("state_token", stateToken).set("memory_json", memoryJson)
                .set("memory_digest", digestValue).set("created_at", StoreValues.ts(createdAt)).set("updated_at", StoreValues.ts(now)));
        update("UPDATE " + T + "continuity_tasks SET updated_at = ? WHERE task_correlation = ?", StoreValues.ts(now), taskCorrelation);
        return new Snapshot(taskCorrelation, epoch, stateToken, memoryJson, digestValue, createdAt, now);
    }

    private void archiveCurrent(Snapshot current, String now, String reason) {
        if (current.memoryJson() == null) {
            return;
        }
        update("INSERT INTO " + T + "continuity_memory_history (task_correlation, state_token, epoch, memory_json, memory_digest, created_at, updated_at, archived_at, reason, format) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'memory-v1')", current.taskCorrelation(), current.stateToken(), current.epoch(), current.memoryJson(), current.memoryDigest(),
                StoreValues.ts(current.createdAt()), StoreValues.ts(current.updatedAt()), StoreValues.ts(now), reason);
    }
}
