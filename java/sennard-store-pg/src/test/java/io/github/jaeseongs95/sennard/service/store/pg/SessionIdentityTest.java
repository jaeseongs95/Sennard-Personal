// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.ResourceHolderSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.service.identity.CallAdmissions;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Source;
import io.github.jaeseongs95.sennard.service.identity.LeasePolicy;
import io.github.jaeseongs95.sennard.service.identity.LeaseService;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.JudgeMode;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.Judgment;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.RegisterRequest;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.Registration;
import io.github.jaeseongs95.sennard.service.session.LeaseOutcome;
import io.github.jaeseongs95.sennard.service.session.SessionKind;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The server-issued identity against the database the test run names (PostgreSQL). Expiry is made by writing {@code lease_until} on a plain connection
 * (no sleeping), races start from a barrier, and every result is checked against the rows read on such a connection. The business tables
 * are emptied before each test, so every test starts with no session.
 */
@BusinessDbTest
class SessionIdentityTest {
    private static final String HOST = "host-under-test";
    private static final String DELIVERY = "{\"wakeVisibility\":\"none\",\"canWakeSilently\":false,\"idleWake\":\"none\",\"supportedInjection\":[]}";
    private static final SessionKeys KEYS = new SessionKeys(List.of(new SessionKeys.Pepper(1, io.github.jaeseongs95.sennard.domain.secret.SecretValue.ofBytes(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}))), new java.security.SecureRandom());
    private static final Duration RACE_DEADLINE = Duration.ofSeconds(60);

    private static TestStores store;
    private static SessionRegistry registry;
    private static LeaseService leases;
    private static CallAdmissions admissions;

    /** The services run on the test JVM's one pool, which the checks' own connections (BusinessDb.connect()) and the lock holders borrow from as well; the races queue for it. */
    @BeforeEach
    void connect() throws Exception {
        if (store == null) {
            store = TestStores.of(BusinessDb.pool());
            registry = store.registry(KEYS, LeasePolicy.LOCAL);
            admissions = store.callAdmissions();
            leases = store.leases(LeasePolicy.LOCAL);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private static String sessionId() {
        return "host-session-" + UUID.randomUUID();
    }

    private static RegisterRequest request(String id, String processKey, UUID instance, Long expected, long startedAt) {
        return new RegisterRequest(id, startedAt, processKey, instance, null, expected, "stdio", DELIVERY);
    }

    private static Registration register(String id, String processKey) {
        return registry.register(HOST, request(id, processKey, UUID.randomUUID(), null, 1_000));
    }

    private void execute(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }

    private long active(String ref) throws SQLException {
        return number("SELECT count(*) FROM sennard.session_generation WHERE session_ref = '" + ref + "' AND ended_at IS NULL AND superseded_at IS NULL");
    }

    private long generations(String ref) throws SQLException {
        return number("SELECT count(*) FROM sennard.session_generation WHERE session_ref = '" + ref + "'");
    }

    private void expire(String ref, long generation) throws SQLException {
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(-1)) + " WHERE session_ref = '" + ref + "' AND generation = " + generation);
    }

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * A TIMESTAMP literal of the moment {@code offset} from now, in the UTC fields the columns hold. The clock is Java's, the service's own (the Clock bean of {@link TestStores}),
     * so no function of time of the DBMS is asked: they are not the same in every one.
     */
    private static String utc(Duration offset) {
        return "TIMESTAMP '" + TIMESTAMP_FORMAT.format(Instant.now().plus(offset)) + "'";
    }

    /**
     * The instant a column of {@code sennard.session_generation} holds, in epoch milliseconds. The client's own times are stored as TIMESTAMPs from 1970 on, UTC fields; they
     * are read with a UTC calendar, so that the zone of the JVM moves nothing, and without a function of the DBMS that turns a time into a number.
     */
    private long epochMillis(String column, String where) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery("SELECT " + column + " FROM sennard.session_generation WHERE " + where)) {
            assertTrue(rows.next(), where);
            return rows.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).getTime();
        }
    }

    private Instant leaseUntil(String ref, long generation) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery("SELECT lease_until FROM sennard.session_generation WHERE session_ref = '" + ref + "' AND generation = " + generation)) {
            assertTrue(rows.next());
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static String failureOf(Runnable call) {
        return assertThrows(SennardException.class, call::run).code().name();
    }

    private static LeaseService.ItemResult renew(String ref, long generation, UUID relay) {
        return leases.renew(List.of(new LeaseService.Item(ref, generation, relay))).items().get(0);
    }

    /** A recovered (unowned) generation made by a tool call for a new session. */
    private static Judgment recoverByTool(String id) {
        return registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.JUDGE);
    }

    /** A recovered generation started by a hook at {@code startedAt}. */
    private static Judgment recoverByHook(String id, long startedAt) {
        Judgment judgment = registry.judge(HOST, id, Source.HOOK, null, startedAt, JudgeMode.JUDGE);
        assertTrue(judgment.accepted(), judgment.reason());
        return judgment;
    }

    /**
     * One attempt each, all started by one barrier, and no second attempt: a call's answer is a value, the code of the {@link SennardException} it ended with, or the store's
     * {@code STORE_UNIQUE_VIOLATION} (a first call of a row that another first call made: the one failure a creating call may lose with). Any other failure
     * (a full pool, a lock wait that ran out, a store that is gone) is not a result of the race and fails the test as it is.
     */
    private static <T> List<Attempt<T>> race(List<Callable<T>> calls) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return attempt(call);
                }));
            }
            List<Attempt<T>> results = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Attempt<T> attempt(Callable<T> call) throws Exception {
        try {
            return new Attempt<>(call.call(), null);
        } catch (SennardException failure) {
            return new Attempt<>(null, failure.code().name());
        } catch (StoreException failure) {
            if (failure.code() != StoreCode.STORE_UNIQUE_VIOLATION) {
                throw failure;
            }
            return new Attempt<>(null, UNIQUE_VIOLATION);
        }
    }

    /** The failure of the call that lost the key of a row another first call made. */
    private static final String UNIQUE_VIOLATION = StoreCode.STORE_UNIQUE_VIOLATION.name();

    private record Attempt<T>(T value, String failure) {
    }

    private record Overlap<T>(T winner, Attempt<T> loser) {
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(RACE_DEADLINE.toSeconds(), TimeUnit.SECONDS), "a latch of the test was not released");
    }

    /**
     * Two transactions on two connections, in two threads, made to overlap by the database and not by a clock: the winner runs its call inside a transaction of the test and holds
     * it open; the loser runs its call (its own transaction) and the test waits on the condition that the database reports a statement waiting for a row lock ({@link
     * #awaitARowLockWaiter}); the loser has not answered while the winner is uncommitted; then the winner commits and the loser's answer is read. Nothing is tried again.
     */
    private <T> Overlap<T> overlapped(Callable<T> winner, Callable<T> loser) throws Exception {
        CountDownLatch winnerHoldsItsRows = new CountDownLatch(1);
        CountDownLatch winnerMayCommit = new CountDownLatch(1);
        AtomicLong winnerBackend = new AtomicLong(); // the database's identifier of the winner's connection, taken inside its transaction: the loser's wait is the one it blocks
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> won = executor.submit(() -> new TransactionTemplate(store.manager()).execute(status -> {
                winnerBackend.set(LockWaits.backendOfThisTransaction(BusinessDb.pool()));
                try {
                    T result = winner.call();
                    winnerHoldsItsRows.countDown();
                    await(winnerMayCommit);
                    return result;
                } catch (Exception failure) {
                    winnerHoldsItsRows.countDown();
                    throw new IllegalStateException(failure);
                }
            }));
            await(winnerHoldsItsRows);
            Future<Attempt<T>> lost = executor.submit(() -> attempt(loser));
            awaitARowLockWaiter(winnerBackend.get());
            assertFalse(lost.isDone(), "the loser waits for the winner's commit");
            winnerMayCommit.countDown();
            return new Overlap<>(won.get(RACE_DEADLINE.toSeconds(), TimeUnit.SECONDS), lost.get(RACE_DEADLINE.toSeconds(), TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------------- the R table

    @Test
    void theFirstRegistrationIssuesAServerReferenceGenerationOneAndALeaseFromTheDatabaseClock() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        assertTrue(SessionKeys.SESSION_REF.matcher(first.sessionRef()).matches(), first.sessionRef());
        assertEquals(1, first.generation());
        assertFalse(first.kept());
        assertEquals(first.serverNow().plusSeconds(30), first.leaseUntil(), "the first lease is max(30 s, leaseMs) from the database clock");
        assertEquals(id, text("SELECT local_session_id FROM sennard.session_ref WHERE session_ref = '" + first.sessionRef() + "'"));
        assertEquals(64, number("SELECT char_length(host_session_digest) FROM sennard.session_ref WHERE session_ref = '" + first.sessionRef() + "'"), "the digest is its 64 hex characters");
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void idKept1_aRetransmissionKeepsTheGenerationAndTheRelay() {
        String id = sessionId();
        UUID instance = UUID.randomUUID();
        Registration first = registry.register(HOST, request(id, "process-1", instance, null, 1_000));
        Registration again = registry.register(HOST, request(id, "process-1", instance, null, 1_000));
        assertEquals(first.sessionRef(), again.sessionRef());
        assertEquals(first.generation(), again.generation());
        assertEquals(first.relayId(), again.relayId());
        assertTrue(again.kept());
    }

    @Test
    void idKept2_aNewClientInstanceGetsANewRelayAndTheLeaseNeverMovesBack() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofHours(1)) + " WHERE session_ref = '" + first.sessionRef() + "'");
        Instant far = leaseUntil(first.sessionRef(), 1);
        Registration taken = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        assertTrue(taken.kept());
        assertEquals(first.generation(), taken.generation());
        assertNotEquals(first.relayId(), taken.relayId());
        assertEquals(far, leaseUntil(first.sessionRef(), 1), "GREATEST: a keep never shortens the lease");
    }

    @Test
    void r3_anExpectedGenerationTakesOverAndClosesTheOldRow() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        Registration second = registry.register(HOST, request(id, "process-2", UUID.randomUUID(), first.generation(), 2_000));
        assertEquals(first.sessionRef(), second.sessionRef());
        assertEquals(2, second.generation());
        assertEquals(1, active(first.sessionRef()));
        assertEquals(1, number("SELECT count(*) FROM sennard.session_generation WHERE session_ref = '" + first.sessionRef() + "' AND generation = 1 AND superseded_at IS NOT NULL"));
    }

    @Test
    void r4_aSecondProcessIsRefusedWithTheTimeToAskAgain() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        ConflictException refused = assertThrows(ConflictException.class, () -> register(id, "process-2"));
        assertEquals(ConflictCode.CURRENT_GENERATION_PROTECTED, refused.code());
        assertEquals(first.sessionRef(), refused.details().get("sessionRef"));
        long retryAfter = ((Number) refused.details().get("retryAfterMs")).longValue();
        assertTrue(retryAfter > 1_000 && retryAfter <= 31_000, "the lease left plus one second: " + retryAfter);
        assertEquals(1, generations(first.sessionRef()));
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void r6_aClientTakesAnUnownedGenerationOver() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        assertEquals(1, recovered.generation());
        Registration owned = register(id, "process-1");
        assertEquals(recovered.sessionRef(), owned.sessionRef());
        assertEquals(2, owned.generation());
        assertEquals(1, active(owned.sessionRef()));
    }

    @Test
    void r10_aLeaseThatExpiredEndsTheGenerationEvenForTheSameProcess() throws Exception {
        String id = sessionId();
        UUID instance = UUID.randomUUID();
        Registration first = registry.register(HOST, request(id, "process-1", instance, null, 1_000));
        expire(first.sessionRef(), 1);
        Registration again = registry.register(HOST, request(id, "process-1", instance, null, 1_000));
        assertFalse(again.kept(), "R2 needs a live lease");
        assertEquals(2, again.generation());
        assertNotEquals(first.relayId(), again.relayId());
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void r11_anExpectedGenerationThatIsNotCurrentIsToldTheCurrentOne() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        expire(first.sessionRef(), 1);
        ConflictException changed = assertThrows(ConflictException.class,
                () -> registry.register(HOST, request(id, "process-2", UUID.randomUUID(), 9L, 1_000)));
        assertEquals(ConflictCode.GENERATION_CHANGED, changed.code());
        assertEquals(1L, ((Number) changed.details().get("generation")).longValue());
        assertEquals(1, generations(first.sessionRef()));
    }

    @Test
    void idResume1_aRegistrationAfterTheEndKeepsTheReferenceAndAdvances() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        assertTrue(registry.endByClient(first.sessionRef(), 1, first.relayId(), "exit", 5_000L).accepted());
        Registration second = register(id, "process-1");
        assertEquals(first.sessionRef(), second.sessionRef());
        assertEquals(2, second.generation());
        assertEquals(1, active(first.sessionRef()));
        assertEquals(5_000, epochMillis("client_ended_at", "session_ref = '" + first.sessionRef() + "' AND generation = 1"));
    }

    @Test
    void aSessionRowIsMadeOnce() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        Registration again = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        assertTrue(again.kept());
        assertEquals(first.sessionRef(), again.sessionRef());
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref"));
    }

    // ----------------------------------------------------------------------------------------------------------- races

    /** The retransmissions of a registered session, fifty at once: every one finds the row and the generation and answers as a retransmission of it. */
    @Test
    void idRace1_fiftyRetransmissionsOfARegisteredSessionAreAllKeptOnOneReference() throws Exception {
        String id = sessionId();
        UUID instance = UUID.randomUUID();
        Registration first = registry.register(HOST, request(id, "process-1", instance, null, 1_000));
        List<Callable<Registration>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i += 1) {
            calls.add(() -> registry.register(HOST, request(id, "process-1", instance, null, 1_000)));
        }
        for (Attempt<Registration> attempt : race(calls)) {
            assertNull(attempt.failure(), attempt.failure());
            assertEquals(first.sessionRef(), attempt.value().sessionRef());
            assertEquals(1, attempt.value().generation());
            assertTrue(attempt.value().kept());
        }
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals(1, generations(first.sessionRef()));
    }

    /**
     * The first registrations of a session nobody registered, fifty at once: each call finds no row and inserts it in its own transaction, the key lets one row through, and a call
     * that lost it ends with the translated unique-key failure (nothing catches it and nothing runs it again). A call that came after the winner's commit finds the row and the
     * generation and answers as a retransmission. The table has one row and one generation.
     */
    @Test
    void idRace1a_fiftyFirstRegistrationsOfANewSessionMakeOneReferenceAndTheLosersEndWithTheUniqueKeyFailure() throws Exception {
        String id = sessionId();
        UUID instance = UUID.randomUUID();
        List<Callable<Registration>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i += 1) {
            calls.add(() -> registry.register(HOST, request(id, "process-1", instance, null, 1_000)));
        }
        Set<String> references = new HashSet<>();
        long made = 0;
        for (Attempt<Registration> attempt : race(calls)) {
            if (attempt.failure() != null) {
                assertEquals(UNIQUE_VIOLATION, attempt.failure());
                continue;
            }
            assertEquals(1, attempt.value().generation());
            references.add(attempt.value().sessionRef());
            made += attempt.value().kept() ? 0 : 1;
        }
        assertEquals(1, made, "one call made the row and the generation");
        assertEquals(1, references.size(), references.toString());
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals(1, generations(references.iterator().next()));
    }

    /**
     * The first notifications of a session nobody registered, all at once: one row of the host session, one generation 1, every call that answers is accepted, and a call that lost
     * the key of the row ends with the translated unique-key failure. Once the row is there every notification is accepted.
     */
    @Test
    void idRace4_eightFirstNotificationsOfANewSessionMakeOneReferenceOneGenerationAndTheLosersEndWithTheUniqueKeyFailure() throws Exception {
        String id = sessionId();
        List<Callable<Judgment>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i += 1) {
            calls.add(() -> registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY));
        }
        Set<String> references = new HashSet<>();
        for (Attempt<Judgment> attempt : race(calls)) {
            if (attempt.failure() != null) {
                assertEquals(UNIQUE_VIOLATION, attempt.failure());
                continue;
            }
            assertTrue(attempt.value().accepted());
            assertEquals(1, attempt.value().generation());
            references.add(attempt.value().sessionRef());
        }
        assertEquals(1, references.size(), "a call answered: the row was made by one of them, and no call made a second");
        String ref = references.iterator().next();
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals(1, generations(ref));
        assertEquals(1, active(ref));
        Judgment later = registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY);
        assertTrue(later.accepted());
        assertEquals(ref, later.sessionRef());
    }

    /**
     * The first calls of a new session on two real connections, made to overlap by the database ({@link LockWaits}): the first call has inserted the row and not committed, the second
     * finds no row, its insert waits for the key of the first, and after the first one's commit it ends with the translated unique-key failure. The table has the first call's row and
     * generation only.
     */
    @Test
    void aSecondFirstNotificationOfANewSessionWaitsForTheKeyOfTheFirstAndEndsWithTheUniqueKeyFailure() throws Exception {
        String id = sessionId();
        Overlap<Judgment> overlap = overlapped(() -> registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY),
                () -> registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY));
        assertTrue(overlap.winner().accepted());
        assertEquals(1, overlap.winner().generation());
        assertNull(overlap.loser().value(), overlap.loser().toString());
        assertEquals(UNIQUE_VIOLATION, overlap.loser().failure());
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals(1, generations(overlap.winner().sessionRef()));
        assertEquals(1, active(overlap.winner().sessionRef()));
    }

    /** The same for a kind marked on a session nobody registered: the row is made by the marking, and the second marking ends with the translated unique-key failure. */
    @Test
    void aSecondFirstMarkingOfANewSessionWaitsForTheKeyOfTheFirstAndEndsWithTheUniqueKeyFailure() throws Exception {
        String id = sessionId();
        Overlap<Boolean> overlap = overlapped(() -> {
            registry.markKind(HOST, id, SessionKind.CHILD);
            return true;
        }, () -> {
            registry.markKind(HOST, id, SessionKind.CHILD);
            return true;
        });
        assertTrue(overlap.winner());
        assertNull(overlap.loser().value(), overlap.loser().toString());
        assertEquals(UNIQUE_VIOLATION, overlap.loser().failure());
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
        assertEquals("CHILD", text("SELECT kind FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
    }

    /** An operation that needs the row of a host session makes it in its own transaction; one that has nothing to say for a session it does not know makes none. */
    @Test
    void anOperationThatNeedsTheRowOfAHostSessionMakesItAndOneThatDoesNotMakesNone() throws Exception {
        String judged = sessionId();
        Judgment judgment = registry.judge(HOST, judged, Source.TOOL, null, null, JudgeMode.NOTIFY);
        assertTrue(judgment.accepted());
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + judged + "'"));
        assertEquals(1, generations(judgment.sessionRef()), "a notification of a session with no generation recovers one");

        String registered = sessionId();
        Registration registration = registry.register(HOST, unowned(registered, 1_000));
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + registered + "'"));
        assertEquals(1, generations(registration.sessionRef()));

        String marked = sessionId();
        registry.markKind(HOST, marked, SessionKind.CHILD);
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + marked + "'"));
        assertEquals(0, number("SELECT count(*) FROM sennard.session_generation g JOIN sennard.session_ref r ON r.session_ref = g.session_ref WHERE r.local_session_id = '" + marked + "'"),
                "a marking makes the row and no generation");

        String unknown = sessionId();
        registry.markKind(HOST, unknown, SessionKind.MAIN);
        assertFalse(registry.endByHook(HOST, unknown, 1_000, "exit").accepted());
        assertTrue(registry.findRef(HOST, unknown).isEmpty());
        assertEquals(0, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + unknown + "'"));
    }

    /**
     * No lock by hand: an operation that changes nothing writes no row of the session, so rows that another transaction holds locked do not stop it. (An {@code UPDATE x = x}
     * to take a lock would wait here.) The session is owned and live, and a tool call is accepted without a write.
     */
    @Test
    void aNotificationThatChangesNothingWaitsForNoRowOfTheSession() throws Exception {
        String id = sessionId();
        Registration owner = register(id, "process-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = BusinessDb.connect(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.executeQuery("SELECT session_ref FROM sennard.session_ref WHERE session_ref = '" + owner.sessionRef() + "' FOR UPDATE").close();
            statement.executeQuery("SELECT session_ref FROM sennard.session_generation WHERE session_ref = '" + owner.sessionRef() + "' FOR UPDATE").close();
            Future<Judgment> judged = executor.submit(() -> registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY));
            Judgment judgment = judged.get(RACE_DEADLINE.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(judgment.accepted());
            assertEquals(owner.generation(), judgment.generation());
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Sixteen processes of a session a tool call has recovered (its row and its unowned generation are there), all registering at once: one takes the session over, the others are refused. */
    @Test
    void idRace2_differentProcessesAtOnceLeaveOneActiveGeneration() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        List<Callable<Registration>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i += 1) {
            String process = "process-" + i;
            calls.add(() -> register(id, process));
        }
        List<Attempt<Registration>> attempts = race(calls);
        List<Registration> won = attempts.stream().filter(attempt -> attempt.value() != null).map(Attempt::value).toList();
        assertEquals(1, won.size(), attempts.toString());
        assertEquals(15, attempts.stream().filter(attempt -> "CURRENT_GENERATION_PROTECTED".equals(attempt.failure())).count(), attempts.toString());
        assertEquals(recovered.sessionRef(), won.get(0).sessionRef());
        assertEquals(2, won.get(0).generation());
        assertEquals(1, active(won.get(0).sessionRef()));
        assertEquals(2, generations(won.get(0).sessionRef()), "the recovered generation and the one that replaced it");
    }

    /** I1 and I2 are held by the database itself: code that forgets to close a row or finds no row fails there, not silently. */
    @Test
    void theDatabaseRefusesASecondActiveGenerationAndASecondRowForOneHostSession() throws Exception {
        Registration owner = register(sessionId(), "process-1");
        // The live generation of a session has its row in active_generation, whose primary key is the session: a second live generation of the session cannot be listed there.
        execute("INSERT INTO sennard.session_generation (session_ref, generation, origin, started_at, lease_until, heartbeat_at, delivery) "
                + "VALUES ('" + owner.sessionRef() + "', 99, 'REGISTER', now(), now(), now(), '{}')");
        SQLException active = assertThrows(SQLException.class, () -> execute("INSERT INTO sennard.active_generation (session_ref, generation) VALUES ('" + owner.sessionRef() + "', 99)"));
        // The kind of violation and the name of the key are PostgreSQL's: 23505 and the constraint's name.
        assertEquals("23505", active.getSQLState(), active.getMessage());
        assertTrue(active.getMessage().contains("active_generation_pkey"), active.getMessage());
        SQLException natural = assertThrows(SQLException.class, () -> execute("INSERT INTO sennard.session_ref (session_ref, host, host_session_digest, pepper_version, created_at, last_seen_at) "
                + "SELECT 'as1_other', host, host_session_digest, pepper_version, now(), now() FROM sennard.session_ref WHERE session_ref = '" + owner.sessionRef() + "'"));
        assertEquals("23505", natural.getSQLState(), natural.getMessage());
        assertTrue(natural.getMessage().contains("session_ref_natural"), natural.getMessage());
    }

    private enum Path { TAKE_OVER_R3, UNOWNED_R6, EXPIRED_R10, ENDED_R10 }

    /** ID-RACE-3: every path that makes a new generation, repeated, with concurrent registrations: one winner, one active row. */
    @Test
    void idRace3_everyPathToANewGenerationKeepsOneActiveRow() throws Exception {
        String id = sessionId();
        Registration current = register(id, "seed");
        String ref = current.sessionRef();
        for (int round = 0; round < 12; round += 1) {
            Path path = Path.values()[round % Path.values().length];
            Long expected = null;
            switch (path) {
                case TAKE_OVER_R3 -> expected = current.generation();
                case UNOWNED_R6 -> {
                    expire(ref, current.generation());
                    Judgment recovered = registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.JUDGE);
                    assertTrue(recovered.accepted());
                }
                case EXPIRED_R10 -> expire(ref, current.generation());
                case ENDED_R10 -> assertTrue(registry.endByClient(ref, current.generation(), current.relayId(), "exit", null).accepted());
            }
            long before = number("SELECT last_generation FROM sennard.session_ref WHERE session_ref = '" + ref + "'");
            Long expectation = expected;
            List<Callable<Registration>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i += 1) {
                String process = "round-" + round + "-" + i;
                calls.add(() -> registry.register(HOST, request(id, process, UUID.randomUUID(), expectation, 1_000)));
            }
            List<Attempt<Registration>> attempts = race(calls);
            List<Registration> won = attempts.stream().filter(attempt -> attempt.value() != null).map(Attempt::value).toList();
            assertEquals(1, won.size(), path + " " + attempts);
            assertEquals(7, attempts.stream().filter(attempt -> "CURRENT_GENERATION_PROTECTED".equals(attempt.failure())).count(), path + " " + attempts);
            assertEquals(1, active(ref), path.toString());
            assertEquals(before + 1, won.get(0).generation(), path.toString());
            current = won.get(0);
        }
    }

    /**
     * A takeover (R3) that loses to another takeover, on two real connections: the loser's first statement is the one that closes the generation, it waits for the winner's row lock,
     * and after the winner's commit its WHERE no longer finds the generation live and it changes no row. It writes nothing, reads what the winner committed and answers from that
     * (protected, as the call that came second would have), without making a generation.
     */
    @Test
    void aTakeOverThatLosesWaitsForTheWinnersCommitWritesNothingAndAnswersFromTheWinnersGeneration() throws Exception {
        String id = sessionId();
        Registration seed = register(id, "seed");
        Overlap<Registration> overlap = overlapped(() -> registry.register(HOST, request(id, "process-2", UUID.randomUUID(), seed.generation(), 2_000)),
                () -> registry.register(HOST, request(id, "process-3", UUID.randomUUID(), seed.generation(), 3_000)));
        assertEquals(2, overlap.winner().generation());
        assertNull(overlap.loser().value(), overlap.loser().toString());
        assertEquals("CURRENT_GENERATION_PROTECTED", overlap.loser().failure());
        assertEquals(2, generations(seed.sessionRef()), "the loser made no generation");
        assertEquals(1, active(seed.sessionRef()));
        assertEquals(2, number("SELECT last_generation FROM sennard.session_ref WHERE session_ref = '" + seed.sessionRef() + "'"));
    }

    /**
     * R7a on two real connections: two starts of a generation a tool call recovered. The winner's single statement fills the start time and renews the lease; the loser waits for it,
     * and after the winner's commit its WHERE ({@code client_started_at IS NULL}) finds nothing, so it changed no row and answers as a retransmission of the same start (kept).
     */
    @Test
    void aStartThatLosesTheFillOfARecoveredGenerationChangesNoRowAndAnswersAsARetransmission() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        Overlap<Registration> overlap = overlapped(() -> registry.register(HOST, unowned(id, 7_000)), () -> registry.register(HOST, unowned(id, 7_000)));
        assertTrue(overlap.winner().kept());
        assertEquals(recovered.generation(), overlap.winner().generation());
        assertNull(overlap.loser().failure(), overlap.loser().toString());
        assertTrue(overlap.loser().value().kept());
        assertEquals(recovered.generation(), overlap.loser().value().generation());
        assertEquals(7_000, epochMillis("client_started_at", "session_ref = '" + recovered.sessionRef() + "'"));
        assertEquals("REGISTER", text("SELECT origin FROM sennard.session_generation WHERE session_ref = '" + recovered.sessionRef() + "'"));
        assertEquals(1, generations(recovered.sessionRef()));
        assertEquals(1, active(recovered.sessionRef()));
    }

    // --------------------------------------------------------- a caller with no owning client (R5, R7, R7a, R8, R9, R10)

    private static RegisterRequest unowned(String id, long startedAt) {
        return new RegisterRequest(id, startedAt, null, null, null, null, "http", DELIVERY);
    }

    @Test
    void r1_aCallerWithNoOwningClientGetsAGenerationWithNoRelayAndTheUnownedLease() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, unowned(id, 1_000));
        assertEquals(1, first.generation());
        assertNull(first.relayId());
        assertFalse(first.kept());
        assertEquals(first.serverNow().plusMillis(LeasePolicy.LOCAL.unownedLeaseMs()), first.leaseUntil());
        assertNull(text("SELECT relay_id FROM sennard.session_generation WHERE session_ref = '" + first.sessionRef() + "'"));
        assertNull(text("SELECT process_key FROM sennard.session_generation WHERE session_ref = '" + first.sessionRef() + "'"));
        assertEquals("REGISTER", text("SELECT origin FROM sennard.session_generation WHERE session_ref = '" + first.sessionRef() + "'"));
    }

    @Test
    void r5_aCallerWithNoOwningClientCannotTakeALiveOwnedGeneration() throws Exception {
        String id = sessionId();
        Registration owner = register(id, "process-1");
        assertEquals("CURRENT_GENERATION_PROTECTED", failureOf(() -> registry.register(HOST, unowned(id, 2_000))));
        assertEquals(1, generations(owner.sessionRef()));
        assertEquals(1, active(owner.sessionRef()));
    }

    @Test
    void r7_theSameStartIsARetransmissionAndExtendsTheLease() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, unowned(id, 1_000));
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(1)) + " WHERE session_ref = '" + first.sessionRef() + "'");
        Instant before = leaseUntil(first.sessionRef(), 1);
        Registration again = registry.register(HOST, unowned(id, 1_000));
        assertTrue(again.kept());
        assertEquals(1, again.generation());
        assertNull(again.relayId());
        assertTrue(leaseUntil(first.sessionRef(), 1).isAfter(before));
    }

    @Test
    void r7a_aStartFillsTheGenerationAToolCallRecovered() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        assertEquals("RECOVERY", text("SELECT origin FROM sennard.session_generation WHERE session_ref = '" + recovered.sessionRef() + "'"));
        Registration filled = registry.register(HOST, unowned(id, 7_000));
        assertTrue(filled.kept());
        assertEquals(recovered.generation(), filled.generation());
        assertEquals(7_000, epochMillis("client_started_at", "session_ref = '" + recovered.sessionRef() + "'"));
        assertEquals("REGISTER", text("SELECT origin FROM sennard.session_generation WHERE session_ref = '" + recovered.sessionRef() + "'"));
        assertEquals("http", text("SELECT transport FROM sennard.session_generation WHERE session_ref = '" + recovered.sessionRef() + "'"));
    }

    @Test
    void r8_aLaterStartReplacesTheGeneration() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, unowned(id, 1_000));
        Registration later = registry.register(HOST, unowned(id, 2_000));
        assertFalse(later.kept());
        assertEquals(2, later.generation());
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void r9_anEarlierStartIsStaleAndChangesNothing() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, unowned(id, 5_000));
        assertEquals("GENERATION_STALE", failureOf(() -> registry.register(HOST, unowned(id, 4_999))));
        assertEquals(1, generations(first.sessionRef()));
        assertEquals(5_000, epochMillis("client_started_at", "session_ref = '" + first.sessionRef() + "'"));
    }

    @Test
    void r10_aStartNoLaterThanTheEndCannotBringASessionBackButALaterOneCan() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, unowned(id, 1_000));
        assertTrue(registry.endByHook(HOST, id, 2_000, "exit").accepted());
        assertEquals("GENERATION_STALE", failureOf(() -> registry.register(HOST, unowned(id, 2_000))));
        assertEquals(1, generations(first.sessionRef()));
        Registration next = registry.register(HOST, unowned(id, 2_001));
        assertEquals(2, next.generation());
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void r6_anOwningClientTakesOverWhatACallerWithNoClientOpened() throws Exception {
        String id = sessionId();
        Registration opened = registry.register(HOST, unowned(id, 1_000));
        Registration owned = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 2_000));
        assertEquals(2, owned.generation());
        assertEquals(opened.sessionRef(), owned.sessionRef());
        assertEquals(1, active(opened.sessionRef()));
    }

    /**
     * I6, the limit of a lock wait: the wait on a row another transaction holds ends with the waiting transaction's time (there is no lock timeout of
     * its own; Spring's transaction timeout is handed to the statement as the JDBC query timeout), and the store reports it as STORE_TIMEOUT. The
     * transaction here is given one second (the default is ten), through the store's own JdbcClient and transaction manager, with a statement that
     * needs the session's row {@code FOR UPDATE}. Once the holder lets the row go, the same statement runs, and a registration (with
     * the default time) of the same session is a retransmission. The registration that waits for a renewal in flight and judges it is
     * {@code leaseLock1}; this test is about how long a wait may last.
     */
    @Test
    void aWaitForARowThatStaysHeldEndsAtTheTransactionTimeoutAsStoreTimeoutAndTheSameStatementRunsOnceTheRowIsFree() throws Exception {
        String id = sessionId();
        Registration opened = registry.register(HOST, unowned(id, 1_000));
        JdbcClient jdbc = store.context().getBean("storeJdbcClient", JdbcClient.class);
        String lockTheRow = "SELECT session_ref FROM sennard.session_ref WHERE session_ref = ? FOR UPDATE";
        try (Connection holder = BusinessDb.connect(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.executeQuery("SELECT session_ref FROM sennard.session_ref WHERE session_ref = '" + opened.sessionRef() + "' FOR UPDATE").close();
            // The limit the transaction carries is read where Spring keeps it (the connection holder's time to live, an upper bound that only shrinks), not measured by waiting: the
            // statement that is cut is the one the transaction's own limit cuts, and the answer is the code of that cut (a refusal at once would be another code).
            AtomicInteger limitSeconds = new AtomicInteger(-1);
            StoreException timedOut = assertThrows(StoreException.class, () -> store.transaction(TxOptions.write().withTimeout(Duration.ofSeconds(1)), tx -> {
                limitSeconds.set(TransactionSynchronizationManager.getResourceMap().values().stream().filter(ResourceHolderSupport.class::isInstance).map(ResourceHolderSupport.class::cast)
                        .filter(ResourceHolderSupport::hasTimeout).mapToInt(ResourceHolderSupport::getTimeToLiveInSeconds).min().orElse(-1));
                return jdbc.sql(lockTheRow).params(opened.sessionRef()).query(String.class).list();
            }));
            assertEquals(StoreCode.STORE_TIMEOUT, timedOut.code(), timedOut.getMessage());
            assertTrue(limitSeconds.get() >= 0 && limitSeconds.get() <= 1, "the one second of the transaction is its limit, not the default ten: " + limitSeconds);
            holder.rollback();
        }
        List<String> locked = store.transaction(TxOptions.write().withTimeout(Duration.ofSeconds(1)),
                tx -> jdbc.sql(lockTheRow).params(opened.sessionRef()).query(String.class).list());
        assertEquals(List.of(opened.sessionRef()), locked, "the same statement runs once the holder has let the row go");
        assertTrue(registry.register(HOST, unowned(id, 1_000)).kept(), "the same start is a retransmission");
        assertEquals(1, generations(opened.sessionRef()));
        assertEquals(1, active(opened.sessionRef()));
    }

    @Test
    void manyIdenticalStartsAtOnceMakeOneGeneration() throws Exception {
        String id = sessionId();
        List<Callable<Registration>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i += 1) {
            calls.add(() -> registry.register(HOST, unowned(id, 1_000)));
        }
        List<Attempt<Registration>> attempts = race(calls);
        assertTrue(attempts.stream().allMatch(attempt -> attempt.value() != null), attempts.toString());
        assertEquals(1, attempts.stream().filter(attempt -> !attempt.value().kept()).count(), attempts.toString());
        String ref = attempts.get(0).value().sessionRef();
        assertEquals(1, generations(ref));
        assertEquals(1, active(ref));
    }

    @Test
    void aToolNamingAReferenceExtendsAnUnownedSessionAndSaysWhatIsStored() throws Exception {
        String id = sessionId();
        Registration opened = registry.register(HOST, unowned(id, 1_000));
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(1)) + " WHERE session_ref = '" + opened.sessionRef() + "'");
        Instant before = leaseUntil(opened.sessionRef(), 1);
        SessionRegistry.RefJudgment judged = registry.notifyToolByRef(opened.sessionRef()).orElseThrow();
        assertTrue(judged.judgment().accepted());
        assertEquals(1, judged.judgment().generation());
        assertEquals(HOST, judged.host());
        assertEquals(id, judged.hostSessionId());
        assertTrue(leaseUntil(opened.sessionRef(), 1).isAfter(before));
    }

    /** A reference the tables do not have answers as no session, and that empty answer changes no row. */
    @Test
    void aReferenceTheTablesDoNotHaveIsEmpty() throws Exception {
        Registration opened = registry.register(HOST, unowned(sessionId(), 1_000));
        String unknown = KEYS.newSessionRef();
        assertTrue(registry.notifyToolByRef(unknown).isEmpty());
        assertTrue(registry.identityOf(unknown).isEmpty());
        assertTrue(registry.findRef(HOST, sessionId()).isEmpty());
        assertEquals(LeaseOutcome.ENDED, renew(unknown, 1, UUID.randomUUID()).state());
        assertEquals("SESSION_NOT_FOUND", failureOf(() -> registry.endByClient(unknown, 1, UUID.randomUUID(), "exit", null)));
        assertEquals(0, number("SELECT count(*) FROM sennard.session_ref WHERE session_ref = '" + unknown + "'"));
        assertEquals(1, number("SELECT count(*) FROM sennard.session_ref"));
        assertEquals(1, active(opened.sessionRef()));
    }

    @Test
    void onlyLiveOwnedGenerationsAreListedForRestart() throws Exception {
        String ownedId = sessionId();
        Registration owned = registry.register(HOST, request(ownedId, "process-listed", UUID.randomUUID(), null, 1_000));
        Registration unowned = registry.register(HOST, unowned(sessionId(), 1_000));
        Registration ended = register(sessionId(), "process-ended");
        assertTrue(registry.endByClient(ended.sessionRef(), 1, ended.relayId(), "exit", null).accepted());

        List<SessionRegistry.OwnedGeneration> listed = registry.activeOwned();

        SessionRegistry.OwnedGeneration found = listed.stream().filter(row -> row.sessionRef().equals(owned.sessionRef())).findFirst().orElseThrow();
        assertEquals(new SessionRegistry.OwnedGeneration(owned.sessionRef(), 1, HOST, ownedId, "process-listed", owned.relayId()), found);
        assertTrue(listed.stream().noneMatch(row -> row.sessionRef().equals(unowned.sessionRef())), "no owner to bind");
        assertTrue(listed.stream().noneMatch(row -> row.sessionRef().equals(ended.sessionRef())), "ended");
        assertEquals(1, listed.size(), "the one live owned generation");
    }

    // --------------------------------------------------------------------------------------------------------- the lease

    @Test
    void leaseFence1_aReplacedOwnerNeitherRenewsNorEnds() throws Exception {
        String id = sessionId();
        Registration first = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        Registration taken = registry.register(HOST, request(id, "process-1", UUID.randomUUID(), null, 1_000));
        Instant lease = leaseUntil(first.sessionRef(), 1);
        LeaseService.ItemResult stale = renew(first.sessionRef(), 1, first.relayId());
        assertEquals(LeaseOutcome.FENCED, stale.state());
        assertNull(stale.leaseUntil());
        assertEquals(lease, leaseUntil(first.sessionRef(), 1), "a fenced renewal changes no row");
        Judgment end = registry.endByClient(first.sessionRef(), 1, first.relayId(), "exit", null);
        assertFalse(end.accepted());
        assertEquals("RELAY_FENCED", end.reason());
        assertEquals(1, active(first.sessionRef()));
        assertEquals(LeaseOutcome.RENEWED, renew(first.sessionRef(), 1, taken.relayId()).state());
    }

    /** LEASE-CLOCK-1: what a client says about its own clock moves nothing. The service reads no clock of its own on this path. */
    @Test
    void leaseClock1_aClientClockAnHourOffChangesNoLease() {
        long hour = 3_600_000;
        long now = System.currentTimeMillis();
        for (long started : new long[] {now - hour, now + hour}) {
            Registration registered = registry.register(HOST, request(sessionId(), "process-1", UUID.randomUUID(), null, started));
            assertEquals(registered.serverNow().plusSeconds(30), registered.leaseUntil());
            LeaseService.Renewal renewal = leases.renew(List.of(new LeaseService.Item(registered.sessionRef(), registered.generation(), registered.relayId())));
            assertEquals(LeaseOutcome.RENEWED, renewal.items().get(0).state());
            assertEquals(registered.leaseUntil(), renewal.items().get(0).leaseUntil(), "GREATEST keeps the longer first lease");
        }
    }

    @Test
    void aRenewalExtendsFromTheDatabaseClockAndReportsTheExpiredGeneration() throws Exception {
        Registration registered = register(sessionId(), "process-1");
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(2)) + " WHERE session_ref = '" + registered.sessionRef() + "'");
        LeaseService.Renewal renewal = leases.renew(List.of(new LeaseService.Item(registered.sessionRef(), 1, registered.relayId())));
        LeaseService.ItemResult item = renewal.items().get(0);
        assertEquals(LeaseOutcome.RENEWED, item.state());
        long left = Duration.between(renewal.serverNow(), item.leaseUntil()).toMillis();
        assertTrue(left > 14_000 && left <= 15_000, "leaseMs from the database clock: " + left);
        assertEquals(0, item.pending());
        expire(registered.sessionRef(), 1);
        assertEquals(LeaseOutcome.EXPIRED, renew(registered.sessionRef(), 1, registered.relayId()).state());
    }

    @Test
    void renewalsLockInSessionOrderSoTwoReversedBatchesFinish() throws Exception {
        List<LeaseService.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i += 1) {
            Registration registered = register(sessionId(), "process-1");
            items.add(new LeaseService.Item(registered.sessionRef(), registered.generation(), registered.relayId()));
        }
        List<LeaseService.Item> reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        List<Callable<LeaseService.Renewal>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i += 1) {
            List<LeaseService.Item> batch = i % 2 == 0 ? items : reversed;
            calls.add(() -> leases.renew(batch));
        }
        for (Attempt<LeaseService.Renewal> attempt : race(calls)) {
            assertNull(attempt.failure(), attempt.failure());
            assertEquals(8, attempt.value().items().stream().filter(item -> item.state() == LeaseOutcome.RENEWED).count());
        }
    }

    @Test
    void leaseExpire1_aRenewalAndARegistrationAtTheExpiryBoundaryNeverBothSucceed() throws Exception {
        for (int round = 0; round < 40; round += 1) {
            String id = sessionId();
            Registration owner = register(id, "process-1");
            // The boundary falls at different distances from the start of the race, on purpose.
            execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofMillis((round % 8) * 5)) + " WHERE session_ref = '" + owner.sessionRef() + "'");
            List<Attempt<Object>> attempts = race(List.of(() -> renew(owner.sessionRef(), 1, owner.relayId()), () -> register(id, "process-2")));
            LeaseService.ItemResult renewal = (LeaseService.ItemResult) attempts.get(0).value();
            Registration registered = (Registration) attempts.get(1).value();
            assertEquals(1, active(owner.sessionRef()), "round " + round);
            if (registered != null) {
                assertEquals(2, registered.generation());
                assertNotEquals(LeaseOutcome.RENEWED, renewal.state(), "round " + round + ": the old owner kept a lease on a closed generation");
            } else {
                // Refused while the lease was live; the renewal then found it live or already past.
                assertEquals("CURRENT_GENERATION_PROTECTED", attempts.get(1).failure());
                assertTrue(renewal.state() == LeaseOutcome.RENEWED || renewal.state() == LeaseOutcome.EXPIRED, renewal.state().name());
            }
        }
    }

    @Test
    void leaseEnd1_aRenewalNeverRevivesAnEndedGeneration() throws Exception {
        for (int round = 0; round < 40; round += 1) {
            Registration owner = register(sessionId(), "process-1");
            List<Attempt<Object>> attempts = race(List.of(() -> renew(owner.sessionRef(), 1, owner.relayId()),
                    () -> registry.endByClient(owner.sessionRef(), 1, owner.relayId(), "exit", null)));
            assertTrue(((Judgment) attempts.get(1).value()).accepted(), "round " + round);
            assertEquals(0, active(owner.sessionRef()), "round " + round);
            assertEquals(LeaseOutcome.ENDED, renew(owner.sessionRef(), 1, owner.relayId()).state(), "round " + round);
        }
    }

    /**
     * A renewal still in flight holds the row and its new lease is not visible yet. The registration reads the old lease and decides to close the generation; the statement that
     * closes it names the expired lease in its {@code WHERE} (the database's own row lock makes it wait for the renewal and test the condition again), so it finds the lease renewed
     * and closes nothing: the registration is refused, as the one that came after the renewal. Judged from the old row alone, it would close a generation whose owner has just been
     * told the lease was renewed. LEASE-EXPIRE-1 only reaches this order by chance, so here the renewal's transaction is held open.
     */
    @Test
    void leaseLock1_aRegistrationWaitsForARenewalInFlightAndJudgesItsLease() throws Exception {
        String id = sessionId();
        Registration owner = register(id, "process-1");
        String ref = owner.sessionRef();
        expire(ref, owner.generation());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection renewal = BusinessDb.connect(); Statement statement = renewal.createStatement()) {
            renewal.setAutoCommit(false);
            long renewalBackend = LockWaits.backendOf(renewal); // the holder's connection is the test's own: its identifier names the wait that counts
            assertEquals(1, statement.executeUpdate("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofHours(1)) + " WHERE session_ref = '"
                    + ref + "' AND generation = " + owner.generation()));
            Future<String> registration = executor.submit(() -> failureOf(() -> register(id, "process-2")));
            awaitARowLockWaiter(renewalBackend);
            renewal.commit();
            assertEquals("CURRENT_GENERATION_PROTECTED", registration.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, generations(ref));
        assertEquals(1, active(ref));
    }

    /**
     * Some backend is blocked on a row of the holder, the connection of the transaction this test holds open ({@link LockWaits}: a wait that another connection of the shared
     * server blocks does not count).
     */
    private void awaitARowLockWaiter(long holderBackend) throws Exception {
        LockWaits.awaitWaiterBlockedBy(holderBackend, RACE_DEADLINE, "no statement came to wait for the row lock");
    }

    // ------------------------------------------------------------------------------------------------------ late events

    @Test
    void late1_anEventOfAnEarlierGenerationIsStale() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        Registration second = registry.register(HOST, request(id, "process-2", UUID.randomUUID(), first.generation(), 2_000));
        Judgment late = registry.judge(HOST, id, Source.HOOK, first.generation(), 3_000L, JudgeMode.JUDGE);
        assertFalse(late.accepted());
        assertEquals("GENERATION_STALE", late.reason());
        assertEquals(second.generation(), number("SELECT last_generation FROM sennard.session_ref WHERE session_ref = '" + first.sessionRef() + "'"));
        assertEquals(1, active(first.sessionRef()));
    }

    @Test
    void late2And3And5_aHookStartedBeforeTheGenerationIsStaleWithNoMarginWhateverTheClockOffset() throws Exception {
        for (long offset : new long[] {0, 3_600_000, -3_600_000}) {
            long start = 1_800_000_000_000L + offset;
            String id = sessionId();
            Judgment recovered = recoverByHook(id, start);
            Judgment late = registry.endByHook(HOST, id, start - 1, "exit");
            assertFalse(late.accepted());
            assertEquals("GENERATION_STALE", late.reason());
            assertEquals(1, active(recovered.sessionRef()));
            assertTrue(registry.endByHook(HOST, id, start, "exit").accepted(), "the same millisecond is not late");
        }
    }

    @Test
    void late4_aClockThatStepsBackIsRefusedOnAnUnownedGenerationAndDoesNothingToAnOwnedOne() throws Exception {
        long start = 1_800_000_000_000L;
        String id = sessionId();
        Judgment recovered = recoverByHook(id, start);
        assertEquals("GENERATION_STALE", registry.endByHook(HOST, id, start - 3_000, "exit").reason());
        assertEquals(1, active(recovered.sessionRef()));
        String owned = sessionId();
        Registration registered = register(owned, "process-1");
        assertEquals("OWNED_BY_CLIENT", registry.endByHook(HOST, owned, 1, "exit").reason());
        assertEquals(1, active(registered.sessionRef()));
    }

    @Test
    void late6_aLateEndOfTheOldGenerationLeavesTheNewOne() throws Exception {
        long newStart = 1_800_000_000_000L;
        String id = sessionId();
        Judgment old = recoverByHook(id, newStart - 60_000);
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(-1)) + " WHERE session_ref = '" + old.sessionRef() + "'");
        Judgment next = recoverByHook(id, newStart);
        assertEquals(2, next.generation());
        Judgment late = registry.endByHook(HOST, id, newStart - 2_000, "exit");
        assertEquals("GENERATION_STALE", late.reason());
        assertEquals(1, active(old.sessionRef()));
    }

    @Test
    void ownEnd1_aHookCannotEndAnOwnedGenerationEvenFromTheSecondProcess() throws Exception {
        String id = sessionId();
        Registration owner = register(id, "process-1");
        assertEquals("CURRENT_GENERATION_PROTECTED", failureOf(() -> register(id, "process-2")));
        Judgment end = registry.endByHook(HOST, id, 9_000_000_000_000L, "exit");
        assertFalse(end.accepted());
        assertEquals("OWNED_BY_CLIENT", end.reason());
        assertEquals(1, active(owner.sessionRef()));
        assertEquals("GENERATION_REQUIRED", registry.judge(HOST, id, Source.HOOK, null, 9_000_000_000_000L, JudgeMode.JUDGE).reason());
    }

    @Test
    void genTable2_anEndedGenerationIsRevivedOnlyByAHookThatStartedAfterItsEnd() throws Exception {
        String id = sessionId();
        Registration first = register(id, "process-1");
        String ref = first.sessionRef();
        assertTrue(registry.endByClient(ref, 1, first.relayId(), "exit", null).accepted());
        // E is NULL.
        assertEquals("GENERATION_ENDED", registry.judge(HOST, id, Source.HOOK, 1L, 5_000L, JudgeMode.JUDGE).reason());
        assertEquals("GENERATION_STALE", registry.judge(HOST, id, Source.HOOK, 0L, 5_000L, JudgeMode.JUDGE).reason());
        assertEquals("GENERATION_STALE", registry.judge(HOST, id, Source.HOOK, 2L, 5_000L, JudgeMode.JUDGE).reason());
        assertEquals("GENERATION_ENDED", registry.judge(HOST, id, Source.HOOK, null, 5_000L, JudgeMode.JUDGE).reason());
        assertEquals(1, generations(ref));
        // E is known.
        execute("UPDATE sennard.session_generation SET client_ended_at = TIMESTAMP '1970-01-01 00:00:05' WHERE session_ref = '" + ref + "'");
        assertEquals("GENERATION_STALE", registry.judge(HOST, id, Source.HOOK, null, 5_000L, JudgeMode.JUDGE).reason());
        assertEquals("GENERATION_STALE", registry.judge(HOST, id, Source.HOOK, 3L, 5_001L, JudgeMode.JUDGE).reason());
        assertEquals(1, generations(ref));
        Judgment revived = registry.judge(HOST, id, Source.HOOK, null, 5_001L, JudgeMode.JUDGE);
        assertTrue(revived.accepted());
        assertEquals(2, revived.generation());
        assertEquals("RECOVERY", text("SELECT origin FROM sennard.session_generation WHERE session_ref = '" + ref + "' AND generation = 2"));
        assertEquals(5_001, epochMillis("client_started_at", "session_ref = '" + ref + "' AND generation = 2"));
        assertEquals(1, active(ref));
    }

    @Test
    void genTable3_aParallelEarlierHookIsAcceptedOnARecoveredGenerationButCannotEndIt() throws Exception {
        long start = 1_800_000_000_000L;
        String id = sessionId();
        Judgment recovered = recoverByHook(id, start);
        assertTrue(registry.judge(HOST, id, Source.HOOK, null, start - 10, JudgeMode.JUDGE).accepted());
        assertEquals(start, epochMillis("client_started_at", "session_ref = '" + recovered.sessionRef() + "'"), "S is unchanged");
        assertEquals("GENERATION_STALE", registry.endByHook(HOST, id, start - 10, "exit").reason());
        assertEquals(1, active(recovered.sessionRef()));
    }

    @Test
    void aNotificationRecoversAndExtendsButARefusalChangesNothing() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofSeconds(1)) + " WHERE session_ref = '" + recovered.sessionRef() + "'");
        Instant before = leaseUntil(recovered.sessionRef(), 1);
        assertTrue(registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY).accepted());
        assertTrue(leaseUntil(recovered.sessionRef(), 1).isAfter(before), "touchUnowned");
        assertNull(text("SELECT client_started_at FROM sennard.session_generation WHERE session_ref = '" + recovered.sessionRef() + "'"),
                "a notification does not fill S");
    }

    /** Design 5.3: a lease never moves back, row by row. A longer lease left by an earlier policy or a clock that stepped back stays. */
    @Test
    void aTouchNeverShortensALeaseAlreadyFurtherOut() throws Exception {
        String id = sessionId();
        Judgment recovered = recoverByTool(id);
        execute("UPDATE sennard.session_generation SET lease_until = " + utc(Duration.ofHours(1)) + " WHERE session_ref = '" + recovered.sessionRef() + "'");
        Instant far = leaseUntil(recovered.sessionRef(), 1);
        assertTrue(registry.judge(HOST, id, Source.TOOL, null, null, JudgeMode.NOTIFY).accepted());
        assertEquals(far, leaseUntil(recovered.sessionRef(), 1), "GREATEST: a touch never shortens the lease");
    }

    @Test
    void anUnknownSessionEndedByAHookDoesNothing() throws Exception {
        String id = sessionId();
        Judgment end = registry.endByHook(HOST, id, 1_000, "exit");
        assertFalse(end.accepted());
        assertNull(end.reason());
        assertEquals(0, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + id + "'"));
    }

    // ------------------------------------------------------------------------------------- session kind and call admissions

    /** A kind is marked on a row that exists or made before the session registers; a child stays a child, and MAIN corrects nothing that does not exist. */
    @Test
    void aKindIsMarkedOnTheRowOfTheHostSessionAndAChildStaysAChild() throws Exception {
        String id = sessionId();
        Registration owner = register(id, "process-1");
        assertEquals("MAIN", text("SELECT kind FROM sennard.session_ref WHERE session_ref = '" + owner.sessionRef() + "'"));
        registry.markKind(HOST, id, SessionKind.CHILD);
        assertEquals("CHILD", text("SELECT kind FROM sennard.session_ref WHERE session_ref = '" + owner.sessionRef() + "'"));
        registry.markKind(HOST, id, SessionKind.PRESET_AGENT);
        assertEquals("CHILD", text("SELECT kind FROM sennard.session_ref WHERE session_ref = '" + owner.sessionRef() + "'"), "a child stays a child");

        String unregistered = sessionId();
        registry.markKind(HOST, unregistered, SessionKind.MAIN);
        assertEquals(0, number("SELECT count(*) FROM sennard.session_ref WHERE local_session_id = '" + unregistered + "'"), "MAIN makes no row");
        registry.markKind(HOST, unregistered, SessionKind.PRESET_AGENT);
        assertEquals("PRESET_AGENT", text("SELECT kind FROM sennard.session_ref WHERE local_session_id = '" + unregistered + "'"));
    }

    /**
     * The row a PreToolUse hook leaves for the call it precedes: one claim per row (the claim deletes it), the same call id seen again replaces the
     * row, a prefix claim takes every row under it and answers with the newest, and the rows nobody claimed go by age when a new row is written.
     */
    @Test
    void aCallAdmissionIsClaimedOnceReplacedByTheSameCallAndSweptByAge() throws Exception {
        String host = "host-admission-" + UUID.randomUUID();
        String callId = "call-" + UUID.randomUUID();
        String oldCallId = "old-" + UUID.randomUUID();
        admissions.leave(host, new CallAdmissions.Admission(callId, "first-tool", "host-session", null, null, null, null, null));
        admissions.leave(host, new CallAdmissions.Admission(oldCallId, "old-tool", "host-session", null, null, null, null, null));
        String tool = "SELECT tool FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '";
        execute("UPDATE sennard.call_admission SET created_at = " + utc(Duration.ofDays(-2)) + " WHERE host = '" + host + "' AND call_id = '" + oldCallId + "'");

        admissions.leave(host, new CallAdmissions.Admission(callId, "second-tool", "host-session", null, null, null, null, null));
        assertEquals("second-tool", text(tool + callId + "'"), "the same call id replaces the row");
        assertEquals(1, number("SELECT count(*) FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + callId + "'"));
        admissions.leave(host, new CallAdmissions.Admission("fresh-" + UUID.randomUUID(), "fresh-tool", "host-session", null, null, null, null, null));
        assertEquals(0, number("SELECT count(*) FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + oldCallId + "'"), "the old row went by age");

        assertEquals("second-tool", inTransaction(() -> admissions.claim(host, callId)).orElseThrow().tool());
        assertTrue(inTransaction(() -> admissions.claim(host, callId)).isEmpty(), "one claim per row");

        admissions.leave(host, new CallAdmissions.Admission("caller:args:1", "tool", "host-session", null, "model-1", null, null, null));
        admissions.leave(host, new CallAdmissions.Admission("caller:args:2", "tool", "host-session", null, "model-2", null, null, null));
        var matched = inTransaction(() -> admissions.claimConversationCall(host, "host-session", "tool", "caller", "caller:args"));
        assertEquals("model-2", matched.orElseThrow().model(), "the newest row answers");
        assertTrue(inTransaction(() -> admissions.claimConversationCall(host, "host-session", "tool", "caller", "caller:args")).isEmpty(),
                "a later claim has no observation of this call");
    }

    /**
     * The same call written for the first time by many writers at once: one row, every writer that answers wrote it, and a writer that lost the key of the row ends with the
     * translated unique-key failure (nothing catches it and nothing runs it again). Once the row is there, every writer replaces its facts and none fails.
     */
    @Test
    void manyFirstWritersOfOneCallLeaveOneRowAndTheLosersEndWithTheUniqueKeyFailure() throws Exception {
        String host = "host-admission-" + UUID.randomUUID();
        String callId = "call-" + UUID.randomUUID();
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i += 1) {
            String tool = "tool-" + i;
            calls.add(() -> {
                admissions.leave(host, new CallAdmissions.Admission(callId, tool, "host-session", null, null, null, null, null));
                return true;
            });
        }
        long written = 0;
        for (Attempt<Boolean> attempt : race(calls)) {
            if (attempt.failure() != null) {
                assertEquals(UNIQUE_VIOLATION, attempt.failure());
                continue;
            }
            assertTrue(attempt.value());
            written += 1;
        }
        assertTrue(written >= 1, "the first writer wrote the row");
        assertEquals(1, number("SELECT count(*) FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + callId + "'"));
        for (Attempt<Boolean> attempt : race(calls)) {
            assertNull(attempt.failure(), "the row is there: a writer replaces its facts");
        }
        assertEquals(1, number("SELECT count(*) FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + callId + "'"));
    }

    /**
     * Two first writers of one call on two real connections, made to overlap by the database ({@link LockWaits}): the first has inserted the row and not committed, the second finds
     * no row, its insert waits for the key of the first, and after the first one's commit it ends with the translated unique-key failure. The row keeps the first writer's facts.
     */
    @Test
    void aSecondFirstWriterOfOneCallWaitsForTheKeyOfTheFirstAndEndsWithTheUniqueKeyFailure() throws Exception {
        String host = "host-admission-" + UUID.randomUUID();
        String callId = "call-" + UUID.randomUUID();
        Overlap<Boolean> overlap = overlapped(() -> {
            admissions.leave(host, new CallAdmissions.Admission(callId, "winner-tool", "host-session", null, null, null, null, null));
            return true;
        }, () -> {
            admissions.leave(host, new CallAdmissions.Admission(callId, "loser-tool", "host-session", null, null, null, null, null));
            return true;
        });
        assertTrue(overlap.winner());
        assertNull(overlap.loser().value(), overlap.loser().toString());
        assertEquals(UNIQUE_VIOLATION, overlap.loser().failure());
        assertEquals("winner-tool", text("SELECT tool FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + callId + "'"));
        assertEquals(1, number("SELECT count(*) FROM sennard.call_admission WHERE host = '" + host + "' AND call_id = '" + callId + "'"));
    }

    private <T> T inTransaction(Supplier<T> body) {
        return new TransactionTemplate(store.manager()).execute(status -> body.get());
    }

    // ---------------------------------------------------------------------------------------------------- the delivery of a registration

    @Test
    void theDeliveryOfARegistrationIsKeptAsItsJsonText() throws Exception {
        String delivery = "{\"wakeVisibility\":\"none\",\"canWakeSilently\":false,\"idleWake\":\"none\",\"supportedInjection\":[\"session-start\"]}";
        Registration registered = registry.register(HOST, new RegisterRequest(sessionId(), 1_000, "process-1", UUID.randomUUID(), null, null, "stdio", delivery));
        assertTrue(SessionKeys.SESSION_REF.matcher(registered.sessionRef()).matches());
        assertEquals(1, registered.generation());
        assertEquals(delivery, text("SELECT delivery FROM sennard.session_generation WHERE session_ref = '" + registered.sessionRef() + "'"), "the column is text: no database normalizes the JSON");
    }
}
