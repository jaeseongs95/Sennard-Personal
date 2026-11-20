// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.store.Encrypted;
import io.github.jaeseongs95.sennard.service.store.ProposalState;
import io.github.jaeseongs95.sennard.service.store.SqlMerge;

/**
 * Shared context layers and the admin page's state. The stored strings ({@code intent}, role bodies) are compared byte for byte by their
 * readers, so nothing here normalizes them. Every method joins the caller's {@code @Transactional} service method.
 */
@Repository
public class ContextLayerStore extends ModuleStore {
    private static final int HISTORY_PER_ROLE = 50;
    public static final long RETAIN_MS = 30L * 24 * 3600_000;
    private static final String SCHEMA = "sennard.";

    // The rows as the tables have them. Spring's row mapper fills a record by its component names, reads the state column's name back into the {@link ProposalState} constant and a
    // TIMESTAMP column into a LocalDateTime (the stored UTC fields, never the driver's text form); the public records turn the times into ISO text.
    private record RoleRow(String roleId, String body, String stateToken, String digest, LocalDateTime updatedAt, String updatedBy) {
        RoleContext toRole() {
            return role(roleId, body, stateToken, digest, StoreValues.isoOrNull(updatedAt), updatedBy);
        }
    }

    private record LogRow(long seq, String target, String intent, String credentialId, String authenticatorData, String clientDataJson, String signature, String recordHash,
            LocalDateTime createdAt) {
        LogRecord toRecord() {
            return new LogRecord(seq, target, intent, credentialId, authenticatorData, clientDataJson, signature, recordHash, StoreValues.isoOrNull(createdAt));
        }
    }

    private record ProposalRow(String id, String target, String changeText, String baseHash, String host, String sessionId, String actor, LocalDateTime createdAt, ProposalState state,
            LocalDateTime decidedAt) {
        Proposal toProposal() {
            return new Proposal(id, target, changeText, baseHash, host, sessionId, actor, StoreValues.isoOrNull(createdAt), state, StoreValues.isoOrNull(decidedAt));
        }
    }

    private record ResetRow(long id, String resetOf, long archived, LocalDateTime at) {
        Reset toReset() {
            return new Reset(id, resetOf, archived, StoreValues.isoOrNull(at));
        }
    }

    /**
     * The row of {@code jev_keys}. {@code secret} is the plain key in this record and the sealed text in the column {@code sealed} (the query names it {@code sealed AS secret}): the sealer's name is the
     * key's name and the data is the scope and the fingerprint, which is what the value has been bound to since it was first stored, so a stored value opens as it always did. The
     * record leaves the secret out of {@code toString}. Package-private so that the test that a value sealed the way it was before the annotation opens through this declaration names the real one.
     */
    record JevKeyRow(String name, String fingerprint, @Encrypted(scope = "sennard.jev-key", name = "name", bind = "fingerprint", column = "sealed") String secret, LocalDateTime storedAt) {
        @Override
        public String toString() {
            return "JevKeyRow[name=" + name + ", fingerprint=" + fingerprint + "]";
        }

        JevKey toKey() {
            return new JevKey(name, fingerprint, SecretValue.of(secret), StoreValues.isoOrNull(storedAt));
        }
    }

    private record SeenRow(String host, String sessionId, LocalDateTime lastSeenAt) {
    }

    private final SecureRandom random;
    private final SqlBuilder limit;

    /** @param random the application's one {@link SecureRandom}, which makes the state tokens */
    public ContextLayerStore(@Qualifier("storeJdbc") JdbcClient jdbc, SecureRandom random, SqlBuilder limit) {
        super(jdbc);
        this.random = random;
        this.limit = limit;
    }

    public record LogRecord(long seq, String target, String intent, String credentialId, String authenticatorData, String clientDataJson,
            String signature, String recordHash, String createdAt) {
    }

    public record Proposal(String id, String target, String change, String baseHash, String host, String sessionId, String actor, String createdAt,
            ProposalState state, String decidedAt) {
    }

    public record Reset(long id, String resetOf, long archived, String at) {
    }

    /** A JEV key as the services see it: the value is the plain secret (its text never appears in {@code toString}); the store seals it when it writes and opens it when it reads. */
    public record JevKey(String name, String fingerprint, SecretValue secret, String storedAt) {
    }

    public record RoleContext(String roleId, String body, String stateToken, String digest, long bytes, String updatedAt, String updatedBy) {
    }

    /** What a role save did: replaced the body, or found another state token than the caller's. */
    public sealed interface RoleSave permits RoleSaved, RoleStale {
    }

    public record RoleSaved(RoleContext record) implements RoleSave {
    }

    public record RoleStale(String actualStateToken) implements RoleSave {
    }

    public record Delivery(String globalToken, String role, String roleToken) {
    }

    public static String bodyDigest(String body) {
        return "sha256:" + StoreValues.sha256Hex(body);
    }

    private static RoleContext role(String roleId, String body, String token, String digest, String at, String by) {
        return new RoleContext(roleId, body, token, digest, body.getBytes(StandardCharsets.UTF_8).length, at, by);
    }

    // --- signed admin log -----------------------------------------------------------------------------------------------

    public List<LogRecord> logRecords() {
        return list(LogRow.class, "SELECT * FROM " + SCHEMA + "admin_log ORDER BY seq").stream().map(LogRow::toRecord).toList();
    }

    public LogRecord recordByRequest(String requestId) {
        LogRow row = single(LogRow.class, "SELECT l.* FROM " + SCHEMA + "admin_log_requests q JOIN " + SCHEMA + "admin_log l ON l.seq = q.seq WHERE q.request_id = ?", requestId);
        return row == null ? null : row.toRecord();
    }

    public LogRecord appendRecord(LogRecord record, String requestId) {
        long seq = insertReturningId("INSERT INTO " + SCHEMA + "admin_log (target, intent, credential_id, authenticator_data, client_data_json, signature, "
                + "record_hash, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "seq",
                record.target(), record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature(),
                record.recordHash(), requestId, StoreValues.ts(record.createdAt()));
        if (requestId != null) {
            // the primary key of this table is what lets one request id sign one record
            update("INSERT INTO " + SCHEMA + "admin_log_requests (request_id, seq) VALUES (?, ?)", requestId, seq);
        }
        return new LogRecord(seq, record.target(), record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(), record.signature(),
                record.recordHash(), record.createdAt());
    }

    /** Every record moves to the archive; the page keeps showing that a reset happened. */
    public Reset resetLog(String resetOf, String now) {
        long archived = jdbc.sql("SELECT count(*) FROM " + SCHEMA + "admin_log").query(Long.class).single();
        long id = insertReturningId("INSERT INTO " + SCHEMA + "admin_resets (reset_of, archived, at) VALUES (?, ?, ?)", "id", resetOf, archived, StoreValues.ts(now));
        update("INSERT INTO " + SCHEMA + "admin_log_archive (seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, request_id, created_at, reset_id) "
                + "SELECT seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, request_id, created_at, ? "
                + "FROM " + SCHEMA + "admin_log", id);
        update("DELETE FROM " + SCHEMA + "admin_log_requests");
        update("DELETE FROM " + SCHEMA + "admin_log");
        // Every signed key fingerprint went with the log, so no stored key can be used again.
        update("DELETE FROM " + SCHEMA + "jev_keys");
        update("UPDATE " + SCHEMA + "admin_proposals SET state = ?, decided_at = ? WHERE state = ?", ProposalState.REJECTED, StoreValues.ts(now), ProposalState.PENDING);
        return new Reset(id, resetOf, archived, now);
    }

    public Long latestResetId() {
        return firstOrNull(jdbc.sql("SELECT max(id) FROM " + SCHEMA + "admin_resets").query(Long.class).list());
    }

    public List<Reset> resets() {
        return list(ResetRow.class, "SELECT id, reset_of, archived, at FROM " + SCHEMA + "admin_resets ORDER BY id DESC").stream().map(ResetRow::toReset).toList();
    }

    // --- JEV keys ---------------------------------------------------------------------------------------------------------

    /** The key under this name with its secret opened (a value that does not open under the name and fingerprint of its row is a {@code SecretSealException}); null when there is none. */
    public JevKey jevKey(String name) {
        JevKeyRow row = single(JevKeyRow.class, "SELECT name, fingerprint, sealed AS secret, stored_at FROM " + SCHEMA + "jev_keys WHERE name = ?", name);
        return row == null ? null : row.toKey();
    }

    /** The fingerprint stored under this name, null when there is none. The secret is not read, so nothing is opened. */
    public String jevKeyFingerprint(String name) {
        return firstOrNull(jdbc.sql("SELECT fingerprint FROM " + SCHEMA + "jev_keys WHERE name = ?").params(StoreValues.bind(name)).query(String.class).list());
    }

    /** Writes the key; the secret is sealed by the common row path ({@code @Encrypted} on the row), not here. */
    public void putJevKey(JevKey record) {
        char[] secret = record.secret().reveal();
        try {
            upsertRow(SCHEMA + "jev_keys", new JevKeyRow(record.name(), record.fingerprint(), new String(secret), StoreValues.ts(record.storedAt())), "name");
        } finally {
            SecretValue.clear(secret);
        }
    }

    public void deleteJevKey(String name) {
        update("DELETE FROM " + SCHEMA + "jev_keys WHERE name = ?", name);
    }

    // --- agent proposals ------------------------------------------------------------------------------------------------

    /** Decided requests older than the retention go, and the pending request of the same session and target is replaced. */
    public Proposal addProposal(Proposal record) {
        update("DELETE FROM " + SCHEMA + "admin_proposals WHERE state <> ? AND decided_at < ?",
                ProposalState.PENDING, StoreValues.ts(StoreValues.parseIso(record.createdAt()) - RETAIN_MS));
        update("UPDATE " + SCHEMA + "admin_proposals SET state = ?, decided_at = ? WHERE state = ? AND target = ? AND host = ? AND session_id = ?",
                ProposalState.REJECTED, StoreValues.ts(record.createdAt()), ProposalState.PENDING, record.target(), record.host(), record.sessionId());
        update("INSERT INTO " + SCHEMA + "admin_proposals (id, target, change_text, base_hash, host, session_id, actor, created_at, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.id(), record.target(), record.change(), record.baseHash(), record.host(), record.sessionId(), record.actor(), StoreValues.ts(record.createdAt()), ProposalState.PENDING);
        return new Proposal(record.id(), record.target(), record.change(), record.baseHash(), record.host(), record.sessionId(), record.actor(), record.createdAt(), ProposalState.PENDING, null);
    }

    public Proposal proposal(String id) {
        ProposalRow row = single(ProposalRow.class, "SELECT * FROM " + SCHEMA + "admin_proposals WHERE id = ?", id);
        return row == null ? null : row.toProposal();
    }

    public List<Proposal> pendingProposals() {
        return list(ProposalRow.class, "SELECT * FROM " + SCHEMA + "admin_proposals WHERE state = ? ORDER BY created_at", ProposalState.PENDING).stream()
                .map(ProposalRow::toProposal).toList();
    }

    /** Moves a pending proposal to a decided state; false when it was already decided. */
    public boolean decideProposal(String id, ProposalState state, String now) {
        if (state == ProposalState.PENDING) {
            throw new IllegalArgumentException("A proposal is decided with APPLIED or REJECTED, not " + state);
        }
        return update("UPDATE " + SCHEMA + "admin_proposals SET state = ?, decided_at = ? WHERE id = ? AND state = ?", state, StoreValues.ts(now), id, ProposalState.PENDING) == 1;
    }

    // --- role bodies -------------------------------------------------------------------------------------------------------

    public RoleContext readRole(String roleId) {
        RoleRow row = single(RoleRow.class, "SELECT * FROM " + SCHEMA + "role_contexts WHERE role_id = ?", roleId);
        return row == null ? null : row.toRole();
    }

    public List<RoleContext> listRoleBodies() {
        return list(RoleRow.class, "SELECT * FROM " + SCHEMA + "role_contexts ORDER BY role_id").stream().map(RoleRow::toRole).toList();
    }

    /**
     * Replaces the whole body when the caller names the current state token (null: never written). The token is the row's own state, so it is the {@code WHERE} of one statement:
     * {@code UPDATE ... WHERE role_id = ? AND state_token = ?} changes one row for the writer that named the current token and none for one that named a token already replaced (the
     * database re-reads the row after the other writer's commit), and the history row is the winner's alone. A first write ({@code expectedStateToken} null) is the {@code INSERT}:
     * of two first writers the second one's {@code INSERT} meets the first one's primary key and fails with {@code STORE_UNIQUE_VIOLATION}, which the service takes as the stale
     * answer (the transaction that failed cannot read the winner's token any more, so that answer names none). No module lock is taken.
     */
    public RoleSave saveRole(String roleId, String expectedStateToken, String body, String updatedBy, String now) {
        String token = "ctx-" + StoreValues.randomBase64Url(random, 18);
        String digest = bodyDigest(body);
        boolean written;
        if (expectedStateToken == null) {
            RoleContext current = readRole(roleId);
            if (current != null) {
                return new RoleStale(current.stateToken());
            }
            update("INSERT INTO " + SCHEMA + "role_contexts (role_id, body, state_token, digest, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?)",
                    roleId, body, token, digest, StoreValues.ts(now), updatedBy);
            written = true;
        } else {
            written = update("UPDATE " + SCHEMA + "role_contexts SET body = ?, state_token = ?, digest = ?, updated_at = ?, updated_by = ? WHERE role_id = ? AND state_token = ?",
                    body, token, digest, StoreValues.ts(now), updatedBy, roleId, expectedStateToken) == 1;
        }
        if (!written) {
            RoleContext current = readRole(roleId);
            return new RoleStale(current == null ? null : current.stateToken());
        }
        update("INSERT INTO " + SCHEMA + "role_context_history (role_id, body, state_token, digest, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?)",
                roleId, body, token, digest, StoreValues.ts(now), updatedBy);
        update("DELETE FROM " + SCHEMA + "role_context_history WHERE role_id = ? AND id NOT IN "
                // The nested result also permits MySQL to materialize the limited selection before deleting from the same table.
                + "(SELECT id FROM (" + limit.applyLimit("SELECT id FROM " + SCHEMA + "role_context_history WHERE role_id = ? ORDER BY id DESC", "?") + ") recent)",
                roleId, roleId, HISTORY_PER_ROLE);
        return new RoleSaved(role(roleId, body, token, digest, now, updatedBy));
    }

    public void deleteRoleBody(String roleId) {
        update("DELETE FROM " + SCHEMA + "role_contexts WHERE role_id = ?", roleId);
        update("DELETE FROM " + SCHEMA + "role_context_history WHERE role_id = ?", roleId);
    }

    // --- session use and deliveries -----------------------------------------------------------------------------------------

    public String sessionSeen(String host, String sessionId) {
        return StoreValues.isoOrNull(firstOrNull(jdbc.sql("SELECT last_seen_at FROM " + SCHEMA + "session_seen WHERE host = ? AND session_id = ?").params(host, sessionId)
                .query(LocalDateTime.class).list()));
    }

    /** Key is host + NUL + session id, as the reader builds it. */
    public Map<String, String> allSessionSeen() {
        return list(SeenRow.class, "SELECT host, session_id, last_seen_at FROM " + SCHEMA + "session_seen")
                .stream().collect(Collectors.toMap(row -> row.host() + '\u0000' + row.sessionId(), row -> StoreValues.isoOrNull(row.lastSeenAt())));
    }

    public void touchSession(String host, String sessionId, String now) {
        update("DELETE FROM " + SCHEMA + "session_seen WHERE last_seen_at < ?", StoreValues.ts(StoreValues.parseIso(now) - RETAIN_MS));
        upsert(SqlMerge.into(SCHEMA + "session_seen").key("host", host).key("session_id", sessionId).set("last_seen_at", StoreValues.ts(now)));
    }

    /**
     * Records the first observed root of a child conversation. Reports naming that root agree with the link.
     * The conditional update protects an existing row when two roots are reported concurrently.
     *
     * @param now the time as ISO text; it also counts as the child's use
     */
    public boolean linkRoot(String host, String sessionId, String rootSessionId, String now) {
        String current = rootOf(host, sessionId);
        if (current != null) {
            return rootSessionId.equals(current);
        }
        var seenAt = StoreValues.ts(now);
        if (insertIfAbsent(SqlMerge.into(SCHEMA + "session_seen").key("host", host).key("session_id", sessionId)
                .set("last_seen_at", seenAt).set("root_session_id", rootSessionId)) > 0) {
            return true;
        }
        return update("UPDATE " + SCHEMA + "session_seen SET root_session_id = ?, last_seen_at = ? WHERE host = ? AND session_id = ? AND root_session_id IS NULL",
                rootSessionId, seenAt, host, sessionId) > 0 || rootSessionId.equals(rootOf(host, sessionId));
    }

    /** The root a child session was linked to, or null when the session is not a known child. */
    public String rootOf(String host, String sessionId) {
        return firstOrNull(jdbc.sql("SELECT root_session_id FROM " + SCHEMA + "session_seen WHERE host = ? AND session_id = ?").params(host, sessionId).query(String.class).list());
    }

    public Delivery delivered(String actorId) {
        return single(Delivery.class, "SELECT global_token, role, role_token FROM " + SCHEMA + "context_deliveries WHERE actor_id = ?", actorId);
    }

    public void recordDelivery(String actorId, Delivery delivery, String now) {
        update("DELETE FROM " + SCHEMA + "context_deliveries WHERE delivered_at < ?", StoreValues.ts(StoreValues.parseIso(now) - RETAIN_MS));
        upsert(SqlMerge.into(SCHEMA + "context_deliveries").key("actor_id", actorId).set("global_token", delivery.globalToken()).set("role", delivery.role())
                .set("role_token", delivery.roleToken()).set("delivered_at", StoreValues.ts(now)));
    }
}
