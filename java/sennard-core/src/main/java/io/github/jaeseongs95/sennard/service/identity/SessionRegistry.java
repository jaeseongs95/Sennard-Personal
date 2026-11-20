// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Decision;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Event;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Phase;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Relay;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Source;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.State;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Verdict;
import io.github.jaeseongs95.sennard.service.session.SessionKind;

/**
 * The server-issued session identity: {@code session.register}, the two forms of {@code session.end} and the generation judgment. Each operation is one READ COMMITTED
 * transaction, and none of them locks a row by hand: the database's own row lock that every {@code UPDATE} takes, and the table's keys, decide who wins.
 *
 * <p>An operation reads the session and its current generation, decides on them ({@link GenerationTable}) and applies the decision with conditional statements whose changed-rows
 * number says whether this call won: a new generation is made only by the call whose {@code UPDATE session_ref SET last_generation = ? WHERE last_generation = ?} changed a row, a
 * generation is closed only by the call whose {@code WHERE} still finds it live, and a lease boundary is judged in the {@code WHERE} of the statement that acts on it (a generation
 * is closed for its expiry only while {@code lease_until} is still not past), so a renewal that got in first is never overwritten. A call that loses writes nothing; it reads what
 * the winner committed and answers from that, as the call that came second would have. It makes nothing again: a call that loses twice ends with {@code STORE_CONFLICT}.
 *
 * <p>The row of a host session is made by the operation that first needs it ({@link #register}, {@link #judge}, and {@link #markKind} for a kind other than {@code MAIN}): it reads the
 * row by the natural key and, when there is none, inserts it in the same transaction, then goes on with what it came to do. Two first calls of one host session both find no row and
 * both insert; the second one waits for the first one's commit and ends with the store's translated unique-key failure ({@code STORE_UNIQUE_VIOLATION}) on the natural key of the
 * table. That is a limit this design accepts: nothing catches it and nothing runs the call again, and a session that has its row is never refused for it.
 */
@Service
public class SessionRegistry {
    private static final String REF_COLUMNS = "session_ref, last_generation, pepper_version, host, local_session_id, kind";
    private static final String GENERATION_COLUMNS = "generation, origin, client_started_at, client_ended_at, process_key, "
            + "client_instance_id, relay_id, lease_until, ended_at, superseded_at";
    private static final String NO_DELIVERY = "{\"wakeVisibility\":\"none\",\"canWakeSilently\":false,\"idleWake\":\"none\",\"supportedInjection\":[]}";
    /** The generation is live: neither ended nor replaced. */
    private static final String LIVE = "ended_at IS NULL AND superseded_at IS NULL";
    private static final String LIVE_GENERATION_MATCH = " FROM sennard.active_generation a JOIN sennard.session_generation g"
            + " ON g.session_ref = a.session_ref AND g.generation = a.generation"
            + " WHERE a.session_ref = ? AND a.generation = ? AND g.ended_at IS NULL AND g.superseded_at IS NULL AND g.lease_until > ?";

    /**
     * The parameters of {@code session.register}, already validated by the caller.
     *
     * @param processKey the owning client's key for the host process, null for a caller with no owning client: such a
     *     generation has no relay and lives on hooks and tool calls
     */
    public record RegisterRequest(String hostSessionId, long clientStartedAtMs, String processKey, UUID clientInstanceId, String codeDigest,
            Long expectedGeneration, String transport, String deliveryJson) {
    }

    /** A successful registration; the protected case (R4) is a {@code ConflictException} {@code CURRENT_GENERATION_PROTECTED} whose details carry {@code retryAfterMs}. */
    public record Registration(String sessionRef, long generation, UUID relayId, boolean kept, Instant leaseUntil, Instant serverNow) {
    }

    public enum JudgeMode { JUDGE, NOTIFY }

    /** @param reason the refusal, null when accepted */
    public record Judgment(boolean accepted, String sessionRef, long generation, String reason) {
    }

    /** A judgment on a session found by its reference, with what is stored about it. */
    public record RefJudgment(Judgment judgment, String host, String hostSessionId) {
    }

    /** A live generation with an owning client, for binding its host process again after a restart. */
    public record OwnedGeneration(String sessionRef, long generation, String host, String hostSessionId, String processKey, UUID relayId) {
    }

    /** The row of {@code session_ref} as the table has it: Spring's row mapper fills the record by the column names ({@code session_ref} is {@code sessionRef}) and reads the kind's name into the constant. */
    private record RefRow(String sessionRef, long lastGeneration, int pepperVersion, String host, String localSessionId, SessionKind kind) {
    }

    /**
     * The row of {@code session_generation} as the table has it; every time is a TIMESTAMP value (UTC fields, no zone), every UUID is the 36 characters of its text form (the
     * driver reads a CHAR column as text, so the conversion is here).
     */
    private record GenerationRow(long generation, GenerationOrigin origin, LocalDateTime clientStartedAt, LocalDateTime clientEndedAt, String processKey,
            String clientInstanceId, String relayId, LocalDateTime leaseUntil, LocalDateTime endedAt, LocalDateTime supersededAt) {
        Generation toGeneration() {
            return new Generation(generation, origin == GenerationOrigin.RECOVERY, clientMillis(clientStartedAt), clientMillis(clientEndedAt), processKey, uuid(clientInstanceId),
                    uuid(relayId), Timestamps.toInstantOrNull(leaseUntil), Timestamps.toInstantOrNull(endedAt), Timestamps.toInstantOrNull(supersededAt));
        }
    }

    /** The row of the join {@link #activeOwned} reads. */
    private record OwnedRow(String sessionRef, long generation, String host, String localSessionId, String processKey, String relayId) {
    }

    private record Generation(long generation, boolean recoveryOrigin, Long clientStartedAtMs, Long clientEndedAtMs, String processKey,
            UUID clientInstanceId, UUID relayId, Instant leaseUntil, Instant endedAt, Instant supersededAt) {
        boolean active() {
            return endedAt == null && supersededAt == null;
        }

        boolean owned() {
            return relayId != null;
        }
    }

    private final JdbcClient jdbc;
    private final SessionKeys keys;
    private final LeasePolicy policy;
    private final Clock clock;

    public SessionRegistry(@Qualifier("storeJdbc") JdbcClient jdbc, SessionKeys keys, LeasePolicy policy, Clock clock) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.policy = policy;
        this.clock = clock;
    }

    /** Registers a host session (making its row when it has none) and answers with its generation, kept or new. */
    @Transactional
    public Registration register(String host, RegisterRequest request) {
        RefRow ref = refOrCreate(host, request.hostSessionId());
        Instant now = clock();
        return decided(ref, found -> applyRegister(found, currentOf(found), now, request));
    }

    /**
     * Records what kind of session a host session is. A conversation seen as a {@code CHILD} stays one. A kind other than {@code MAIN} is marked on the row of the host session, made
     * here when it has none (a tool call registers the session later, and must find it already marked); {@code MAIN} only corrects a row that exists, because a main session is
     * created by its registration.
     */
    @Transactional
    public void markKind(String host, String hostSessionId, SessionKind kind) {
        RefRow ref = kind == SessionKind.MAIN ? refOf(host, hostSessionId) : refOrCreate(host, hostSessionId);
        if (ref == null || ref.kind() == kind || ref.kind() == SessionKind.CHILD) {
            return;
        }
        // A child stays a child: the condition is in the statement, so a mark that arrives after another one's CHILD changes nothing.
        update("UPDATE sennard.session_ref SET kind = ? WHERE session_ref = ? AND kind <> ?", kind.name(), ref.sessionRef(), SessionKind.CHILD.name());
    }

    /** A conversation observed as a child keeps that classification after the hook actor cache is lost. */
    public boolean isChild(String host, String hostSessionId) {
        RefRow ref = refOf(host, hostSessionId);
        return ref != null && ref.kind() == SessionKind.CHILD;
    }

    public record ManagedRoot(String rootRef, long rootGeneration) {
    }

    /** Classifies a newly returned native ID and binds its first root before any turn may be submitted. */
    @Transactional
    public boolean bindManagedChild(String host, String rootSessionId, String rootRef, long generation, String childSessionId) {
        RefRow root = refByValue(rootRef);
        if (root == null || !host.equals(root.host()) || !rootSessionId.equals(root.localSessionId()) || root.kind() != SessionKind.MAIN
                || rootSessionId.equals(childSessionId) || !isLiveGeneration(rootRef, generation)) {
            return false;
        }
        RefRow child = refOrCreate(host, childSessionId);
        int bound = update("UPDATE sennard.session_ref SET kind = ? WHERE session_ref = ? AND kind = ? AND last_generation = 0"
                        + " AND EXISTS (SELECT 1" + LIVE_GENERATION_MATCH + ")",
                SessionKind.CHILD.name(), child.sessionRef(), SessionKind.MAIN.name(), rootRef, generation, timestamp(clock()));
        if (bound == 0) {
            return false;
        }
        update("INSERT INTO sennard.managed_child_identity (child_ref, root_ref, root_generation) VALUES (?, ?, ?)", child.sessionRef(), rootRef, generation);
        return true;
    }

    /** A binding can outlive the root. An operation for the child must also check that its root generation still lives. */
    @Transactional(readOnly = true)
    public Optional<ManagedRoot> managedRootOf(String host, String childSessionId) {
        return findRef(host, childSessionId).flatMap(childRef -> jdbc.sql("SELECT root_ref, root_generation FROM sennard.managed_child_identity WHERE child_ref = ?")
                .params(childRef).query(ManagedRoot.class).optional());
    }

    /** Resolves only the original, still-live root of a managed child, without recovering or extending a generation. */
    @Transactional(readOnly = true)
    public Optional<String> liveManagedRootSession(String host, ManagedRoot binding) {
        if (!isLiveGeneration(binding.rootRef(), binding.rootGeneration())) {
            return Optional.empty();
        }
        return identityOf(binding.rootRef()).filter(root -> host.equals(root.host())).flatMap(RefIdentity::hostSession);
    }

    /** @return the registration, or null when another call changed the session first (the caller reads what that call committed) */
    private Registration applyRegister(RefRow ref, Generation current, Instant now, RegisterRequest request) {
        if (current == null) {
            return newGeneration(ref, null, false, now, request); // R1.
        }
        boolean unownedCaller = request.processKey() == null;
        boolean live = current.active() && current.leaseUntil().isAfter(now);
        if (live && current.owned()) {
            if (unownedCaller) {
                throw protectedGeneration(ref, current, now); // R5.
            }
            if (current.processKey() != null && current.processKey().equals(request.processKey())) {
                return keep(ref, current, now, request); // R2.
            }
            if (request.expectedGeneration() != null && request.expectedGeneration() == current.generation()) {
                return newGeneration(ref, current, false, now, request); // R3.
            }
            throw protectedGeneration(ref, current, now); // R4.
        }
        if (live) {
            if (unownedCaller) {
                return registerOverUnowned(ref, current, now, request); // R7, R7a, R8, R9.
            }
            return newGeneration(ref, current, false, now, request); // R6.
        }
        if (request.expectedGeneration() != null && request.expectedGeneration() != current.generation()) {
            throw generationChanged(ref, current); // R11.
        }
        if (unownedCaller && current.clientEndedAtMs() != null && request.clientStartedAtMs() <= current.clientEndedAtMs()) {
            throw generationStale(ref, current); // R10: a late start cannot bring an ended session back.
        }
        return newGeneration(ref, current, true, now, request); // R10, the expired lease (a generation that already ended is not closed again).
    }

    /** Rows R7 to R9: a caller with no owning client against a live generation that has none either. PC times are compared with PC times. */
    private Registration registerOverUnowned(RefRow ref, Generation current, Instant now, RegisterRequest request) {
        Long stored = current.clientStartedAtMs();
        if (stored == null) {
            // R7a, a generation a tool call recovered: the first start that reports its time fills it, and only once. The fill and the lease renewal that every start of a live
            // unowned generation does are one statement, so the statement that decides who wins is the only write: a call that changes no row has written nothing (see decided).
            LocalDateTime lease = leaseUntil(now, policy.unownedLeaseMs());
            int filled = update("UPDATE sennard.session_generation SET client_started_at = ?, transport = ?, delivery = ?, origin = ?, "
                    + "lease_until = CASE WHEN lease_until > ? THEN lease_until ELSE ? END, heartbeat_at = ? "
                    + "WHERE session_ref = ? AND generation = ? AND client_started_at IS NULL AND " + LIVE + " AND lease_until > ?", clientTime(request.clientStartedAtMs()),
                    request.transport(), request.deliveryJson(), GenerationOrigin.REGISTER.name(), lease, lease, timestamp(now), ref.sessionRef(), current.generation(),
                    timestamp(now));
            return filled == 0 ? null : keptUnowned(ref, current, now);
        }
        if (request.clientStartedAtMs() == stored) {
            return keepUnowned(ref, current, now); // R7.
        }
        if (request.clientStartedAtMs() > stored) {
            return newGeneration(ref, current, false, now, request); // R8.
        }
        throw generationStale(ref, current); // R9, a late SessionStart.
    }

    private Registration keepUnowned(RefRow ref, Generation current, Instant now) {
        LocalDateTime lease = leaseUntil(now, policy.unownedLeaseMs());
        int kept = update("UPDATE sennard.session_generation SET lease_until = CASE WHEN lease_until > ? THEN lease_until ELSE ? END, "
                + "heartbeat_at = ? WHERE session_ref = ? AND generation = ? AND " + LIVE + " AND lease_until > ?",
                lease, lease, timestamp(now), ref.sessionRef(), current.generation(), timestamp(now));
        return kept == 0 ? null : keptUnowned(ref, current, now);
    }

    /** The answer for a live unowned generation this call has just written (kept, no relay). */
    private Registration keptUnowned(RefRow ref, Generation current, Instant now) {
        return new Registration(ref.sessionRef(), current.generation(), null, true, leaseUntilOf(ref, current.generation()), now);
    }

    private Registration keep(RefRow ref, Generation current, Instant now, RegisterRequest request) {
        boolean sameClient = current.clientInstanceId() != null && current.clientInstanceId().equals(request.clientInstanceId());
        UUID relay = sameClient ? current.relayId() : UUID.randomUUID();
        LocalDateTime lease = leaseUntil(now, policy.leaseMs());
        int kept = update("UPDATE sennard.session_generation SET relay_id = ?, client_instance_id = ?, code_digest = ?, transport = ?, "
                + "delivery = ?, lease_until = CASE WHEN lease_until > ? THEN lease_until ELSE ? END, heartbeat_at = ? "
                + "WHERE session_ref = ? AND generation = ? AND " + LIVE + " AND lease_until > ?",
                text(relay), text(request.clientInstanceId()), request.codeDigest(), request.transport(), request.deliveryJson(), lease,
                lease, timestamp(now), ref.sessionRef(), current.generation(), timestamp(now));
        return kept == 0 ? null : new Registration(ref.sessionRef(), current.generation(), relay, true, leaseUntilOf(ref, current.generation()), now);
    }

    /**
     * The next generation: owned, with a relay and the first-lease grace, when the request has a process key; otherwise unowned (S3).
     *
     * @param closing the generation this one replaces, null when the session has none
     * @param afterExpiry true when the replaced generation is replaced for its expired lease
     * @return null when another call changed the session first
     */
    private Registration newGeneration(RefRow ref, Generation closing, boolean afterExpiry, Instant now, RegisterRequest request) {
        OptionalLong claimed = claimNext(ref, closing, afterExpiry, now);
        if (claimed.isEmpty()) {
            return null;
        }
        long generation = claimed.getAsLong();
        boolean owned = request.processKey() != null;
        UUID relay = owned ? UUID.randomUUID() : null;
        long firstLease = owned ? Math.max(LeasePolicy.MIN_FIRST_LEASE_MS, policy.leaseMs()) : policy.unownedLeaseMs();
        LocalDateTime leaseUntil = leaseUntil(now, firstLease);
        insertGeneration("INSERT INTO sennard.session_generation (session_ref, generation, origin, started_at, "
                + "client_started_at, process_key, client_instance_id, relay_id, code_digest, lease_until, heartbeat_at, transport, delivery) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.sessionRef(), generation, GenerationOrigin.REGISTER.name(), timestamp(now), clientTime(request.clientStartedAtMs()), request.processKey(),
                text(request.clientInstanceId()), text(relay), request.codeDigest(), leaseUntil, timestamp(now), request.transport(),
                request.deliveryJson());
        return new Registration(ref.sessionRef(), generation, relay, false, Timestamps.toInstant(leaseUntil), now);
    }

    /**
     * Takes the right to make the next generation of the session: closes the generation it replaces (when that is still live) and then moves {@code last_generation} on by the
     * statement {@code UPDATE ... SET last_generation = n + 1 WHERE last_generation = n}. Only one call changes that row from {@code n}, so only one call makes generation
     * {@code n + 1}. The replaced generation is closed first, in the order every closing path takes the rows (generation, then session).
     *
     * <p>A generation closed for its expired lease is closed only while the lease is still past ({@code lease_until <= now} in the statement): a renewal or a touch that wrote
     * the lease first leaves the statement with no row to change, and this call then has nothing to replace.
     *
     * @param afterExpiry true when the replaced generation is replaced for its expired lease
     * @return the number of the new generation, or empty when another call changed the session first (nothing was written)
     */
    private OptionalLong claimNext(RefRow ref, Generation closing, boolean afterExpiry, Instant now) {
        long expected = closing == null ? ref.lastGeneration() : closing.generation();
        boolean closed = false;
        if (closing != null && closing.active()) {
            closed = update("UPDATE sennard.session_generation SET superseded_at = ? WHERE session_ref = ? AND generation = ? AND " + LIVE + (afterExpiry ? " AND lease_until <= ?" : ""),
                    afterExpiry
                            ? new Object[] {timestamp(now), ref.sessionRef(), closing.generation(), timestamp(now)}
                            : new Object[] {timestamp(now), ref.sessionRef(), closing.generation()}) == 1;
            if (!closed && isLive(ref, closing.generation())) {
                return OptionalLong.empty(); // a renewal or a touch moved the lease: the generation is not this call's to replace.
            }
            if (closed) {
                update("DELETE FROM sennard.active_generation WHERE session_ref = ? AND generation = ?", ref.sessionRef(), closing.generation());
            }
        }
        long next = expected + 1;
        if (update("UPDATE sennard.session_ref SET last_generation = ?, last_seen_at = ? WHERE session_ref = ? AND last_generation = ?", next, timestamp(now), ref.sessionRef(), expected) == 1) {
            return OptionalLong.of(next);
        }
        if (closed) {
            throw conflict(); // this call closed a generation that nobody else could have been replacing: the statement rolls back with the transaction.
        }
        return OptionalLong.empty();
    }

    /** Whether the generation is live now (read after this call's own write found nothing to change). */
    private boolean isLive(RefRow ref, long generation) {
        return jdbc.sql("SELECT COUNT(*) FROM sennard.session_generation WHERE session_ref = ? AND generation = ? AND " + LIVE)
                .params(ref.sessionRef(), generation).query(Long.class).single() > 0;
    }

    /** A new live generation: its row, and its row in the table of live generations, whose primary key is the session (one live generation per session). */
    private void insertGeneration(String sql, Object... params) {
        update(sql, params);
        update("INSERT INTO sennard.active_generation (session_ref, generation) VALUES (?, ?)", params[0], params[1]);
    }

    /** The lease of a generation as the table holds it now (read after this transaction wrote it). */
    private Instant leaseUntilOf(RefRow ref, long generation) {
        return Timestamps.toInstant(jdbc.sql("SELECT lease_until FROM sennard.session_generation WHERE session_ref = ? AND generation = ?")
                .params(ref.sessionRef(), generation).query(LocalDateTime.class).single());
    }

    /** The owner's end (5.5): only the current generation and relay; a stale or fenced client changes nothing. */
    @Transactional
    public Judgment endByClient(String sessionRef, long generation, UUID relayId, String reason, Long clientEndedAtMs) {
        RefRow ref = refByValue(sessionRef);
        if (ref == null) {
            throw notFound();
        }
        Instant now = clock();
        return decided(ref, found -> {
            Generation current = currentOf(found);
            Relay relay = current != null && current.relayId() != null && current.relayId().equals(relayId) ? Relay.MATCH : Relay.MISMATCH;
            Event event = new Event(Source.CLIENT, true, generation, relay, null);
            Decision decision = GenerationTable.decide(state(found, current, now), event);
            return finishEnd(found, current, now, decision, event, reason, clientEndedAtMs);
        });
    }

    /** A hook's end (5.5). An owned generation is ended only through its client; an unknown session does nothing. */
    @Transactional
    public Judgment endByHook(String host, String hostSessionId, long hookStartedAtMs, String reason) {
        RefRow ref = refOf(host, hostSessionId);
        if (ref == null) {
            return new Judgment(false, null, 0, null);
        }
        Instant now = clock();
        return decided(ref, found -> {
            Generation current = currentOf(found);
            Event event = new Event(Source.HOOK, true, null, Relay.ABSENT, hookStartedAtMs);
            Decision decision = GenerationTable.decide(state(found, current, now), event);
            return finishEnd(found, current, now, decision, event, reason, null);
        });
    }

    /** @return the judgment, or null when the generation was closed by another call before this one's statement ran */
    private Judgment finishEnd(RefRow ref, Generation current, Instant now, Decision decision, Event event,
            String reason, Long clientEndedAtMs) {
        if (decision.verdict() == Verdict.END) {
            Long ended = event.source() == Source.HOOK ? event.hookStartedAtMs() : clientEndedAtMs;
            Long started = decision.fillStartedAt() ? event.hookStartedAtMs() : current.clientStartedAtMs();
            if (update("UPDATE sennard.session_generation SET ended_at = ?, end_reason = ?, client_ended_at = ?, client_started_at = ? "
                    + "WHERE session_ref = ? AND generation = ? AND " + LIVE, timestamp(now), reason, clientTime(ended), clientTime(started),
                    ref.sessionRef(), current.generation()) == 0) {
                return null;
            }
            update("DELETE FROM sennard.active_generation WHERE session_ref = ? AND generation = ?", ref.sessionRef(), current.generation());
            return new Judgment(true, ref.sessionRef(), current.generation(), null);
        }
        return refused(ref, current, decision);
    }

    /** What a decision that changes nothing answers: the session's current generation (the last one when it has none) and the table's reason. */
    private static Judgment refused(RefRow ref, Generation current, Decision decision) {
        return new Judgment(false, ref.sessionRef(), current == null ? ref.lastGeneration() : current.generation(), decision.reason() == null ? null : decision.reason().name());
    }

    /**
     * The table of 5.5 for a hook, tool or CLI event of a host session; the session's row is made here when it has none. JUDGE refuses with the table's reason; NOTIFY
     * applies only recovery and {@code touchUnowned}, and the request it belongs to runs whatever the answer. An event for a session that has no generation yet recovers one here (4.7).
     *
     * @param generation      {@code generation} from the event, null when absent
     * @param hookStartedAtMs the hook's PC start time, null for a tool or CLI call
     */
    @Transactional
    public Judgment judge(String host, String hostSessionId, Source source, Long generation, Long hookStartedAtMs, JudgeMode mode) {
        RefRow ref = refOrCreate(host, hostSessionId);
        Instant now = clock();
        Event event = new Event(source, false, generation, Relay.ABSENT, hookStartedAtMs);
        return decided(ref, found -> {
            Generation current = currentOf(found);
            Decision decision = GenerationTable.decide(state(found, current, now), event);
            return applyJudgment(found, current, now, decision, event, mode);
        });
    }

    /** @return the judgment, or null when another call changed the session first */
    private Judgment applyJudgment(RefRow ref, Generation current, Instant now, Decision decision, Event event, JudgeMode mode) {
        if (decision.verdict() == Verdict.RECOVER) {
            OptionalLong claimed = claimNext(ref, current, true, now);
            if (claimed.isEmpty()) {
                return null;
            }
            recover(ref, claimed.getAsLong(), now, event.hookStartedAtMs());
            return new Judgment(true, ref.sessionRef(), claimed.getAsLong(), null);
        }
        if (decision.verdict() == Verdict.ACCEPT) {
            if (mode == JudgeMode.JUDGE && decision.fillStartedAt()) {
                // The first report of a start time fills it, and only once; a call that finds it filled has nothing to add.
                update("UPDATE sennard.session_generation SET client_started_at = ? WHERE session_ref = ? AND generation = ? AND client_started_at IS NULL",
                        clientTime(event.hookStartedAtMs()), ref.sessionRef(), current.generation());
            }
            if (decision.touchUnowned()) {
                touchUnowned(ref, current, now);
            }
            return new Judgment(true, ref.sessionRef(), current.generation(), null);
        }
        return refused(ref, current, decision);
    }

    /**
     * The notification of a tool call for a session named by its reference: recovery and {@code touchUnowned} apply, and
     * the call runs whatever the answer. Empty when there is no such session.
     */
    @Transactional
    public Optional<RefJudgment> notifyToolByRef(String sessionRef) {
        RefRow ref = refByValue(sessionRef);
        if (ref == null) {
            return Optional.empty();
        }
        Instant now = clock();
        Event event = new Event(Source.TOOL, false, null, Relay.ABSENT, null);
        Judgment judged = decided(ref, found -> {
            Generation current = currentOf(found);
            Decision decision = GenerationTable.decide(state(found, current, now), event);
            return applyJudgment(found, current, now, decision, event, JudgeMode.NOTIFY);
        });
        return Optional.of(new RefJudgment(judged, ref.host(), ref.localSessionId()));
    }

    /** The reference of a session this server knows, read without touching the session; a message addressed to a session must not revive it. */
    @Transactional(readOnly = true)
    public Optional<String> findRef(String host, String hostSessionId) {
        return refsByDigest(host, keys.digests(host, hostSessionId)).stream().findFirst().map(RefRow::sessionRef);
    }

    /** Read-only fencing: neither delivery nor a wake signal may recover or extend the target's lease. */
    @Transactional(readOnly = true)
    public boolean isLiveGeneration(String sessionRef, long generation) {
        return jdbc.sql("SELECT COUNT(*)" + LIVE_GENERATION_MATCH)
                .params(sessionRef, generation, timestamp(clock())).query(Long.class).single() == 1;
    }

    /** Which host session a reference stands for; {@code hostSessionId} is the host's own id of the session, null for a reference that has none. */
    public record RefIdentity(String host, String hostSessionId) {
        /** The host's own id of the session; empty for a reference that has none. */
        public Optional<String> hostSession() {
            return Optional.ofNullable(hostSessionId);
        }
    }

    @Transactional(readOnly = true)
    public Optional<RefIdentity> identityOf(String sessionRef) {
        return jdbc.sql("SELECT " + REF_COLUMNS + " FROM sennard.session_ref WHERE session_ref = ?").params(sessionRef)
                .query(RefRow.class).optional().map(ref -> new RefIdentity(ref.host(), ref.localSessionId()));
    }

    /** The live generations with an owning client, read once at start-up to bind their host processes again. */
    @Transactional(readOnly = true)
    public List<OwnedGeneration> activeOwned() {
        return jdbc.sql("SELECT r.session_ref, g.generation, r.host, r.local_session_id, g.process_key, g.relay_id FROM sennard.session_generation g "
                + "JOIN sennard.session_ref r ON r.session_ref = g.session_ref "
                + "WHERE g.ended_at IS NULL AND g.superseded_at IS NULL AND g.relay_id IS NOT NULL "
                + "AND g.process_key IS NOT NULL AND r.local_session_id IS NOT NULL ORDER BY r.session_ref")
                .query(OwnedRow.class).list().stream()
                .map(row -> new OwnedGeneration(row.sessionRef(), row.generation(), row.host(), row.localSessionId(), row.processKey(), uuid(row.relayId())))
                .toList();
    }

    /** The first generation of a session that had none, or the one after a generation that ended or lapsed: its row and its row in the table of live generations. */
    private void recover(RefRow ref, long generation, Instant now, Long hookStartedAtMs) {
        insertGeneration("INSERT INTO sennard.session_generation (session_ref, generation, origin, started_at, client_started_at, "
                + "lease_until, heartbeat_at, delivery) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ref.sessionRef(), generation, GenerationOrigin.RECOVERY.name(), timestamp(now), clientTime(hookStartedAtMs), leaseUntil(now, policy.unownedLeaseMs()),
                timestamp(now), NO_DELIVERY);
    }

    /** Extends the lease of a live unowned generation; a generation that was closed in the meantime is left as it is. */
    private void touchUnowned(RefRow ref, Generation current, Instant now) {
        LocalDateTime lease = leaseUntil(now, policy.unownedLeaseMs());
        update("UPDATE sennard.session_generation SET lease_until = CASE WHEN lease_until > ? THEN lease_until ELSE ? END, "
                + "heartbeat_at = ? WHERE session_ref = ? AND generation = ? AND " + LIVE, lease, lease,
                timestamp(now), ref.sessionRef(), current.generation());
    }

    private static State state(RefRow ref, Generation current, Instant now) {
        if (current == null) {
            return new State(Phase.S0, ref.lastGeneration(), null, null, false);
        }
        if (current.endedAt() != null) {
            return new State(Phase.S5, current.generation(), current.clientStartedAtMs(), current.clientEndedAtMs(), current.recoveryOrigin());
        }
        boolean live = current.leaseUntil().isAfter(now);
        Phase phase = current.owned() ? live ? Phase.S1 : Phase.S2 : live ? Phase.S3 : Phase.S4;
        return new State(phase, current.generation(), current.clientStartedAtMs(), current.clientEndedAtMs(), current.recoveryOrigin());
    }

    /**
     * Applies a decision made on what was read, and answers for the call that lost: {@code attempt} returns null when its conditional statement found another call's write in
     * its way, having written nothing. The session is then read again (in READ COMMITTED it shows what that call committed) and the decision is made once more on it, which
     * cannot make a generation again unless that one is already gone; a second loss is a conflict the caller sees, not a third try.
     *
     * <p>The condition for this: an {@code attempt} that returns null must have changed no row. The first write of every branch of it is the conditional statement that decides who
     * wins, and a branch that has won that statement and then fails a later one throws instead of returning null (so its work rolls back with the transaction and is not judged a
     * second time on top of itself). A branch that wrote one row and then returned null would leave the second attempt to decide on a session that this very call had half changed.
     * The row of the session that this transaction inserted itself does not break the condition: no other call can reach it before the commit (a second insert of its key waits for
     * this transaction and fails), so the first attempt on it never loses.
     */
    private <T> T decided(RefRow read, Function<RefRow, T> attempt) {
        T answer = attempt.apply(read);
        if (answer != null) {
            return answer;
        }
        RefRow winner = refByValue(read.sessionRef());
        answer = winner == null ? null : attempt.apply(winner);
        if (answer == null) {
            throw conflict();
        }
        return answer;
    }

    /** The session rows of this host session under any valid pepper, the newest pepper first; a named list parameter is expanded by Spring into the IN list. */
    private List<RefRow> refsByDigest(String host, List<SessionKeys.Digest> digests) {
        return jdbc.sql("SELECT " + REF_COLUMNS + " FROM sennard.session_ref WHERE host = :host "
                + "AND host_session_digest IN (:digests) ORDER BY pepper_version DESC")
                .param("host", host)
                .param("digests", digests.stream().map(SessionKeys.Digest::value).toList()).query(RefRow.class).list();
    }

    /** The session by the natural key, or null; a row under an older pepper is moved to the current one (the statement says which pepper it moves from, so two calls move it once). */
    private RefRow refOf(String host, String hostSessionId) {
        List<SessionKeys.Digest> digests = keys.digests(host, hostSessionId);
        List<RefRow> rows = refsByDigest(host, digests);
        if (rows.isEmpty()) {
            return null;
        }
        RefRow ref = rows.get(0);
        SessionKeys.Digest current = digests.get(0); // the newest pepper first (SessionKeys.digests)
        if (ref.pepperVersion() != current.version()) {
            update("UPDATE sennard.session_ref SET host_session_digest = ?, pepper_version = ? WHERE session_ref = ? AND pepper_version = ?",
                    current.value(), current.version(), ref.sessionRef(), ref.pepperVersion());
        }
        return ref;
    }

    /**
     * The session of a host session, made by this transaction when it has no row under any valid pepper: the one place where a {@code session_ref} row is inserted. The row is read
     * again by its reference after the insert, so its columns are the table's own.
     */
    private RefRow refOrCreate(String host, String hostSessionId) {
        RefRow ref = refOf(host, hostSessionId);
        if (ref != null) {
            return ref;
        }
        SessionKeys.Digest digest = keys.currentDigest(host, hostSessionId);
        LocalDateTime now = timestamp(clock());
        String sessionRef = keys.newSessionRef();
        update("INSERT INTO sennard.session_ref (session_ref, host, host_session_digest, pepper_version, local_session_id, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionRef, host, digest.value(), digest.version(), hostSessionId, now, now);
        return refByValue(sessionRef);
    }

    private RefRow refByValue(String sessionRef) {
        return firstOrNull(jdbc.sql("SELECT " + REF_COLUMNS + " FROM sennard.session_ref WHERE session_ref = ?").params(sessionRef).query(RefRow.class).list());
    }

    /**
     * The current generation row, or null when the session has none yet (S0). The generation is named by a subselect of the session's own {@code last_generation}, so the session
     * and the generation are read by one statement and always belong together.
     */
    private Generation currentOf(RefRow ref) {
        GenerationRow row = firstOrNull(jdbc.sql("SELECT " + GENERATION_COLUMNS + " FROM sennard.session_generation WHERE session_ref = ? "
                + "AND generation = (SELECT last_generation FROM sennard.session_ref WHERE session_ref = ?)")
                .params(ref.sessionRef(), ref.sessionRef()).query(GenerationRow.class).list());
        return row == null ? null : row.toGeneration();
    }

    /** The service's one clock, to the millisecond (what a {@code TIMESTAMP} column keeps). */
    private Instant clock() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** The lease's end, as the column value, {@code leaseMs} after {@code now}. */
    private static LocalDateTime leaseUntil(Instant now, long leaseMs) {
        return timestamp(now.plusMillis(leaseMs));
    }

    private int update(String sql, Object... params) {
        return jdbc.sql(sql).params(params).update();
    }

    private static <T> T firstOrNull(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** A UUID column is CHAR(36) and holds the text form; null stays null. */
    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static UUID uuid(String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private static LocalDateTime timestamp(Instant value) {
        return Timestamps.toTimestamp(value);
    }

    /** The {@code TIMESTAMP} value of a time on the client's own clock (epoch milliseconds); null stays null. */
    private static LocalDateTime clientTime(Long epochMillis) {
        return epochMillis == null ? null : Timestamps.toTimestamp(Instant.ofEpochMilli(epochMillis));
    }

    /** The client's own clock reading (epoch milliseconds) of a stored {@code TIMESTAMP} value; null stays null. */
    private static Long clientMillis(LocalDateTime value) {
        return value == null ? null : Timestamps.toInstant(value).toEpochMilli();
    }

    private static ConflictException protectedGeneration(RefRow ref, Generation current, Instant now) {
        return new ConflictException(ConflictCode.CURRENT_GENERATION_PROTECTED, Map.of(),
                SennardException.args("sessionRef", ref.sessionRef(), "retryAfterMs", Math.max(0, current.leaseUntil().toEpochMilli() - now.toEpochMilli()) + 1000));
    }

    private static ConflictException generationChanged(RefRow ref, Generation current) {
        return new ConflictException(ConflictCode.GENERATION_CHANGED, Map.of(), SennardException.args("sessionRef", ref.sessionRef(), "generation", current.generation()));
    }

    private static ConflictException generationStale(RefRow ref, Generation current) {
        return new ConflictException(ConflictCode.GENERATION_STALE, Map.of(), SennardException.args("sessionRef", ref.sessionRef(), "generation", current.generation()));
    }

    private static NotFoundException notFound() {
        return new NotFoundException(NotFoundCode.SESSION_NOT_FOUND);
    }

    private static ConflictException conflict() {
        return new ConflictException(ConflictCode.STORE_CONFLICT);
    }
}
