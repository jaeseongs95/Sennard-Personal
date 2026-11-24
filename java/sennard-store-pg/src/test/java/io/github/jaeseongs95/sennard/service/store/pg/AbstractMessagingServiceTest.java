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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.messaging.MessageRepository;
import io.github.jaeseongs95.sennard.app.messaging.MessagingProperties;
import io.github.jaeseongs95.sennard.app.messaging.MessagingService;
import io.github.jaeseongs95.sennard.app.messaging.PreparedMessage;
import io.github.jaeseongs95.sennard.app.messaging.PreparedMessageRepository;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimLimits;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimReceipt;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimedMessage;
import io.github.jaeseongs95.sennard.domain.messaging.Generation;
import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.MessageStatus;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.domain.messaging.PeerWait;
import io.github.jaeseongs95.sennard.domain.messaging.PeerWaitRecord;
import io.github.jaeseongs95.sennard.domain.messaging.Prepared;
import io.github.jaeseongs95.sennard.domain.messaging.RecallRefusal;
import io.github.jaeseongs95.sennard.domain.messaging.RecallResult;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;

/**
 * Session messaging (sennard-app: {@link MessagingService}, the repositories of {@code app/messaging}) on the real database (PostgreSQL), through {@link RewrittenAreasContext}. It replaces the
 * messaging cases of the old {@code MessagingStore} tests (AbstractModuleStoresTest: the limits, the receipts, the claim, the acknowledgement, the recall, the peer wait,
 * the prune and the races, written for advisory locks, row locks in a global order, SKIP LOCKED, the database clock and row-level security) with the same meanings on the new
 * mechanism: the primary and UNIQUE keys and conditional UPDATEs whose changed rows tell who won, at the database's default isolation, with no retry (no test
 * here asks again after a failure: every call is answered by itself). The tests that exist only for the removed mechanism have no counterpart (listed below).
 *
 * <p>Only the application's connection is run (AbstractMessagingServiceTest has one subclass). Nothing but the writing of a second subclass keeps Boot's default path
 * (which the board runs too) from running these cases; the case table would not change. The class runs on the database the test run names ({@link BusinessDbTest}), and
 * every test starts from empty tables.
 *
 * <p>The limits of the whole queue (the drafts, the receipts, the unacknowledged messages and their bytes) have no counter: the service counts the rows of the tables
 * against the values of {@link MessagingProperties}. A test therefore brings a limit to its value with rows of the tables (the fixtures {@code fillRecords} and
 * {@code queueFiller} write them as the owner, the counters of the sessions made to match, so the rows and the counters stay equal) and judges the refusal. What remains
 * counted per session ({@code pending_count}, {@code sender_drafts}, {@code sender_receipts} of {@code sennard.session_ref}, changed by conditional UPDATEs) is set
 * directly when a test needs one session at its limit.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * p1  prepare, send, claim, ack  prepare -> PREPARED, no queue effect; send -> QUEUED (and again -> duplicate with the same receipt), counters = rows; claim -> receipt 1:uuid,
 *                                lease hides it; ack without the receipt -> 0; with it -> 1, ACKNOWLEDGED; another sender's status -> empty; its send -> MESSAGE_ID_UNAVAILABLE
 * p2  same ID, N threads         N threads send one prepared ID at once -> exactly one is not a duplicate, one queue row, counters 1/1
 * p3  ID taken, ID already queued a queue row that exists before the receipt: same content -> duplicate and the draft becomes a receipt under the receipt limit (999 receipts in the
 *                                table: the last place; the next one is refused); other content -> ConflictException MESSAGE_ID_TAKEN and nothing changes
 * p4  input                      shape, NUL, 4096 bytes (multi-byte), blank (no-break space, BOM), ttl 29 and 86401 -> InvalidInputException with the rule's code; unknown
 *                                target or sender -> NotFoundException SESSION_NOT_FOUND; blank argument -> VALIDATION_FAILED; counters unchanged
 * p5a limits of one sender        the sender's drafts 100 (MESSAGE_PREPARATION_FULL, scope sender), its receipts 250 (prepare: MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT with the time
 *                                the first place frees; send: MESSAGE_SENDER_RECEIPTS_FULL), set on the session's counters; nothing written
 * p5b receipt limit              999 receipts in the table: a draft is prepared and its send takes the last place; the next send -> MESSAGE_RECEIPTS_FULL and the next prepare ->
 *                                MESSAGE_RECEIPTS_FULL_NO_DRAFT, scope global, release scheduled with the earliestReleaseAt of the first receipt to lapse; nothing queued
 * p5c queue bytes                the queue holds 4 MiB less one byte: a 1-byte message fits exactly, the next -> MESSAGE_SPOOL_FULL (release pending)
 * p5d draft limit                999 drafts in the table: the next prepare is the last, the one after -> MESSAGE_PREPARATION_FULL, scope global, nothing written
 * p5e record bytes               the records' bytes count the other drafts and receipts: prepare -> MESSAGE_PREPARATION_FULL, send -> MESSAGE_RECEIPTS_FULL with the first lapse
 * p6  a limit opens again        a count read from the tables drops and the refusal ends: queue bytes after an acknowledgement and after a recall; receipts and drafts after
 *                                what held them lapsed (by the clock; by prune), counters = rows
 * c1  order                      claim order is seq, not created_at (times swapped by the owner)
 * c2  lease and receipt          lapsed lease -> redelivery with attempt 2 and a new receipt, the first delivery time kept; the old receipt acknowledges nothing, the new
 *                                one does; a late acknowledgement of the same holder is allowed; a malformed receipt -> ACK_CLAIM_RECEIPT_INVALID
 * c3  lease growth               120 s, 240 s, 480 s, 960 s, 1800 s, 1800 s (cap)
 * c4  expired, acknowledged      an expired message is not delivered (attempts stay 0); an acknowledged one is not delivered again
 * c5  budgets                    11 messages -> CLAIM_LIMIT_RANGE; a first message larger than maxBodyChars -> CLAIM_BUDGET_EXCEEDED; maxBodyChars 5 -> only the first; the
 *                                32 KiB answer limit cuts a claim of large bodies short
 * c6  deferral and wake          the observation row and the generation's row are made by the first use case that needs them (observe, a wake input, a claim of a
 *                                generation: an UPDATE that changed no row, then the INSERT, in the one transaction); observe -> the first tool-boundary claim is
 *                                skipped once and the observation is SETTLED; turn end settles it (the row stays, never deleted); the claim boundary of a generation
 *                                only moves forward; no generation writes nothing; a wake input keeps a pending deferral and makes a settled row for a session without one
 * c7  claim races                two claimers of one row -> one wins and the other gets an empty answer, not an error; four claimers drain 12 messages -> each message to one
 *                                claimer; two transactions on two connections claim one row (repository level) -> the second waits while the first is
 *                                uncommitted (the database reports a statement waiting for a lock), and after the first's commit its UPDATE changes 0 rows
 * c9  first creation race         two calls whose first creation of one row overlap (a session's first observation, a generation's first claim boundary): the second's
 *                                INSERT waits for the first's uncommitted key (the database reports it), and after the first's commit it fails with the translated
 *                                ConflictException DUPLICATE; nothing caught it and nothing ran it again, and the first's row is the only row
 * c8  transaction boundary (new) a claim called inside a caller's transaction joins it: when the caller fails, the claim and its claim boundary are rolled back and the message
 *                                is claimed again as the first attempt (what the server's adapter relies on to keep the claim and its source receipt one unit)
 * a1  acknowledge                another session, an unknown ID, an unclaimed message -> 0; counters given back; the first acknowledgement shortens the receipt, never extends
 * a2  concurrent acknowledge     N threads acknowledge one claim -> exactly one counts
 * r1  recall                     UNKNOWN, NOT_SENT, recalled (counters given back, RECALLED, never claimed), a repeated recall (duplicate), DELIVERED after a claim, EXPIRED
 * r2  recall against claim       a recall and a claim of one message race -> exactly one wins, counters = rows
 * s1  status                     per state; another sender's ID -> empty; a sent message whose queue row is gone shows the receipt with deliveryState UNKNOWN
 * u1  prune: acknowledged        the body of an acknowledged message that expired is emptied, the row stays while its receipt lives and goes when the receipt ends
 * u2  prune: lapsed (new)        an expired draft, an expired unacknowledged message and an expired receipt are deleted with their counters (the leak the old code had),
 *                                in foreign key order
 * k1  counters under races       mixed concurrent sends, recalls, claims and acknowledgements -> counters equal the rows
 * k2  capacity race              N threads send under a limit with room for 10 -> every send is answered; 10 to 10 + N-1 are queued (the limit of the whole queue is a plain read:
 *                                it may be passed by the writers in flight, no more); the counters equal the rows
 * k3  order and deadlocks        concurrent sends to one recipient -> seq is 1..n without a gap or a duplicate; opposite-direction sends between two sessions all succeed
 *                                (the rows are written in one order: sessions by value, then the queue row)
 * k4  record bytes (new)         room for exactly one more draft and many threads prepare -> every call is answered, 1 to N are admitted, ten rounds (a plain read, as k2)
 * w1  peer wait state            both directions, a changed fingerprint after a claim
 * w2  peer wait snapshots        the newest 1000 are kept, an owner's can be reset, a lapsed one is not returned
 * e1  enum storage (new)         prepared_messages.state and input_observations.state hold the names; the CHECKs refuse other values and a state's wrong facts
 * e2  foreign keys (new)         unknown session: the service answers NotFoundException first; the foreign key still refuses it when the service does not look (observe
 *                                -> IntegrityException REFERENCE_VIOLATION, and DataIntegrityViolationException at the repository)
 * e3  schema facts (new)         no row-level security, no function and no trigger in sennard; keys led by the message, the session or the query; times TIMESTAMP;
 *                                state VARCHAR; version NOT NULL
 * t1  time zone (new)            with the JVM in Asia/Seoul the clock's UTC instants come back unchanged and the stored wall time is UTC
 * </pre>
 *
 * <h2>The old tests (AbstractModuleStoresTest) and where their meaning went</h2>
 * <pre>
 * preparedMessageIsQueuedOnSubmitDeliveredOnClaimAndClosedByItsReceipt   p1            anExpiredClaimLeaseRedeliversWithANewReceiptAndAnAckNeedsTheCurrentOne  c2
 * theQueueOrderIsTheSequenceNotTheTimestamp                              c1            aMessageIdIsBoundToItsContentAndInputIsValidatedBeforeAnyWrite          p2, p3, p4
 * aRecallTakesBackOnlyWhatNoRecipientHasReceivedAndGivesTheCountersBack  r1            deferredClaimsSkipOnceAndTheClaimBoundaryOnlyMovesForward               c6
 * theFirstAckShortensTheReceiptAndPruneKeepsTheRowItStillBacks           a1, u1        theSenderAndAccountLimitsRejectWithTheirCodesAndWriteNothing            p5a, p5b, p5d
 * aClaimRespectsItsBudgets                                               c5            peerWaitStateCoversBothDirectionsAndChangesWithDelivery                 w1
 * timestampsReadAfterTheLockDoNotRunAgainstTheSequence                   k3 (seq without a gap; the order is seq, so created_at following seq is no longer a promise)
 * concurrentSendersStopExactlyAtTheAccountLimit                          k2            countersMatchTheRowsAfterMixedConcurrentOperations                      k1
 * dialectOffsetRetentionKeepsExactlyTheLimit                             w2            aClaimDoesNotDeliverAnExpiredMessage                                    c4
 * aRedeliveredClaimHoldsItsLeaseLongerUpToTheCap                         c3            theAckNeverExtendsAReceiptAlreadyShorter                                a1
 * theByteLimitAdmitsAnExactFitAndNoMore                                  p5c           aSubmitOfAnAlreadyQueuedMessageStillRespectsTheReceiptLimit             p3
 * theRecordByteLimitCountsTheOtherDraftsAndReceipts                      p5e           parallelClaimsDeliverEachMessageToOneClaimer                            c7
 * everyModuleTable... (the 5 tables left the row-level security lists)   e3
 * sessionRowsAreLockedInValueOrder, theQuotaRowIsLockedAfterTheSessionRows, aClaimSkipsARowAnotherTransactionHolds, anAcknowledgementLocksItsMessagesInIdOrder
 *                                                                        (locks in a global order and SKIP LOCKED are gone: k3 shows the sends in both directions finish, c7 the claims
 *                                                                        divide the queue, a2 the acknowledgements count once)
 * theClockIsReadAfterTheLockWaitNotAtTransactionStart, theStoreTakesItsTimeFromTheDatabaseClock (the messaging part)
 *                                                                        (the time is the injected clock's: every case sets the clock; t1 holds the zone)
 * </pre>
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractMessagingServiceTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int WRITERS = 8;
    private static final Generation GENERATION = new Generation("inst", "gen-1");
    /** What one record of a fixture counts for in the byte limit of the records (a thousand of them stay far below it). */
    private static final long FILLER_RECORD_BYTES = 100;

    private RewrittenAreasContext context;
    private ExecutorService pool;

    /** How the subclass connects the application layer to the database. */
    abstract RewrittenAreasContext open(Instant start);

    @BeforeAll
    void start() throws Exception {
        // the schemas are migrated anew by @BusinessDbTest before this method, and its tables emptied before each test
        context = open(T0);
        pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    void stop() {
        if (pool != null) {
            pool.close();
        }
        if (context != null) {
            context.close();
        }
    }


    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private void owner(String... statements) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String ownerText(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** The column as the database writes it in text: CAST to VARCHAR (PostgreSQL's CHAR(n) pads with blanks). */
    private static String asText(String column) {
        return "CAST(" + column + " AS VARCHAR(40))";
    }

    /** A session, as the identity area keeps it (a fixture only: the identity area owns the table). */
    private String session(String host) throws SQLException {
        String ref = "ref-" + UUID.randomUUID();
        owner("INSERT INTO sennard.session_ref (session_ref, host, host_session_digest, pepper_version, created_at, last_seen_at) VALUES ('" + ref + "', '" + host
                + "', '" + Digests.sha256Hex(ref) + "', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        return ref;
    }

    /** The values the service reads its limits from ({@code sennard.messaging.*}); the tests bring the tables to them. */
    private MessagingProperties limits() {
        return context.bean(MessagingProperties.class);
    }

    private MessagingService service() {
        return context.bean(MessagingService.class);
    }

    private void at(Instant instant) {
        context.clock().set(instant);
    }

    private Prepared prepare(String sender, String target, String body, Integer ttl) {
        return service().prepare(sender, target, body, ttl, Operations.fresh("prepare_session_message", sender));
    }

    private Sent send(String sender, String host, String messageId) {
        return service().send(sender, host, messageId, Operations.fresh("send_session_message", sender), Sent.class, Function.identity()).value();
    }

    /** prepare and send in one: the message's ID. */
    private String sent(String sender, String senderHost, String target, String body, Integer ttl) {
        Prepared prepared = prepare(sender, target, body, ttl);
        send(sender, senderHost, prepared.messageId());
        return prepared.messageId();
    }

    private String sent(PeerAddress sender, PeerAddress target, String body) {
        String ref = sender.sessionRef();
        Prepared draft = service().prepare(sender, target, body, null, Operations.fresh("prepare_session_message", ref));
        service().send(sender, "codex", draft.messageId(), Operations.fresh("send_session_message", ref), Sent.class, Function.identity());
        return draft.messageId();
    }

    @Test
    void legacyChildAddressesAreExcludedFromDeliveryInBothDirections() throws Exception {
        at(T0);
        String ref = session("codex");
        PeerAddress root = PeerAddress.session(ref);
        PeerAddress first = new PeerAddress(ref, "worker-one");
        PeerAddress second = new PeerAddress(ref, "worker-two");
        String toFirst = sent(root, first, "first job");
        String toSecond = sent(root, second, "second job");
        String toRoot = sent(first, root, "first report");
        String canceled = sent(second, root, "follow-up");

        RecallResult recalled = service().recall(second, canceled, Operations.fresh("recall_session_message", ref), RecallResult.class, Function.identity());
        assertTrue(recalled.recalled());
        assertEquals("worker-one", service().status(root, toFirst).orElseThrow().targetActorId());
        assertEquals(MessageState.QUEUED, service().status(first, toRoot).orElseThrow().state());
        assertEquals(0L, pending(ref), "legacy child content is excluded before counting root delivery candidates");

        assertTrue(claim(ref, null, null).isEmpty());
        assertEquals(MessageState.QUEUED, service().status(root, toFirst).orElseThrow().state());
        assertEquals(MessageState.QUEUED, service().status(root, toSecond).orElseThrow().state());
        assertTrue(service().recall(root, toFirst, Operations.fresh("recall_session_message", ref), RecallResult.class, Function.identity()).recalled());
        assertCountersMatchRows();
    }

    @Test
    void aLegacyPhysicalChildCannotDeliverReceiveOrAcknowledgeAfterItIsRecognized() throws Exception {
        at(T0);
        String child = session("codex");
        String root = session("codex");
        String other = session("claude-code");
        String old = sent(child, "codex", root, "already claimed before classification", null);
        ClaimReceipt receipt = receiptOf(claim(root, null, null).getFirst());
        String fromChild = sent(child, "codex", root, "queued from a physical child", null);
        String toChild = sent(root, "codex", child, "queued to a physical child", null);
        String fromMain = sent(other, "claude-code", root, "queued from the other main", null);
        owner("UPDATE sennard.session_ref SET kind = 'CHILD', parent_ref = '" + root + "' WHERE session_ref = '" + child + "'");

        assertEquals(1L, pending(root));
        assertEquals(0L, pending(child));
        assertEquals(List.of(fromMain), claim(root, new ClaimLimits(1, null), null).stream().map(ClaimedMessage::messageId).toList());
        assertTrue(claim(child, null, null).isEmpty());
        assertEquals(0, service().acknowledge(root, List.of(old), List.of(receipt), Operations.fresh("acknowledge_session_messages", root), ignored -> { }));
        assertTrue(service().recall(child, fromChild, Operations.fresh("recall_session_message", child), RecallResult.class, Function.identity()).recalled());
        assertTrue(service().recall(root, toChild, Operations.fresh("recall_session_message", root), RecallResult.class, Function.identity()).recalled());
        assertCountersMatchRows();
    }

    @Test
    void legacyActorOperationReceiptsStaySeparateAndRootClaimsRemainExclusive() throws Exception {
        at(T0);
        String ref = session("codex");
        PeerAddress root = PeerAddress.session(ref);
        PeerAddress first = new PeerAddress(ref, "worker-one");
        PeerAddress second = new PeerAddress(ref, "worker-two");
        OperationKey prepare = Operations.fresh("prepare_session_message", ref);
        Prepared firstDraft = service().prepare(first, root, "first report", null, prepare);
        assertEquals(firstDraft, service().prepare(first, root, "first report", null, prepare));
        Prepared secondDraft = service().prepare(second, root, "second report", null, prepare);
        assertNotEquals(firstDraft.messageId(), secondDraft.messageId());

        OperationKey send = Operations.fresh("send_session_message", ref);
        Sent sent = service().send(first, "codex", firstDraft.messageId(), send, Sent.class, Function.identity()).value();
        assertEquals(sent, service().send(first, "codex", firstDraft.messageId(), send, Sent.class, Function.identity()).value());
        String otherRoot = session("codex");
        String rootMessage = sent(PeerAddress.session(otherRoot), root, "main session message");
        List<ClaimedMessage> claimed = parallel(WRITERS, index -> claim(ref, null, null)).stream().flatMap(List::stream).toList();
        assertEquals(List.of(rootMessage), claimed.stream().map(ClaimedMessage::messageId).toList());
        assertEquals("", claimed.getFirst().senderActorId());
        assertCountersMatchRows();
    }

    private List<ClaimedMessage> claim(String target, ClaimLimits limits, Generation generation) {
        return service().claim(target, limits, generation);
    }

    private int acknowledge(String target, List<String> ids, List<ClaimReceipt> receipts) {
        return service().acknowledge(target, ids, receipts, Operations.fresh("acknowledge_session_messages", target));
    }

    private static ClaimReceipt receiptOf(ClaimedMessage message) {
        return new ClaimReceipt(message.messageId(), message.claimReceipt());
    }

    private Optional<MessageStatus> status(String sender, String messageId) {
        return service().status(sender, messageId);
    }

    private RecallResult recall(String sender, String messageId) {
        return service().recall(sender, messageId, Operations.fresh("recall_session_message", sender), RecallResult.class, Function.identity());
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A TIMESTAMP literal of an instant, UTC wall time (for a fixture the owner writes). */
    private static String ts(Instant instant) {
        return "TIMESTAMP '" + DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(instant) + "'";
    }

    /** A TIMESTAMP column the owner reads, as the instant it holds (the column is UTC wall time). */
    private Instant ownerInstant(String sql) throws SQLException {
        return Instant.parse(ownerText(sql).replace(' ', 'T') + "Z");
    }

    private long pending(String target) {
        return service().pendingCount(target);
    }

    private long prune() {
        return service().prune();
    }

    /** Runs {@code n} tasks at the same moment and returns their results. */
    private <R> List<R> parallel(int n, IntFunction<R> work) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<R>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            tasks.add(pool.submit(() -> {
                go.await();
                return work.apply(index);
            }));
        }
        go.countDown();
        List<R> results = new ArrayList<>();
        for (Future<R> task : tasks) {
            results.add(task.get(120, TimeUnit.SECONDS));
        }
        return results;
    }

    /**
     * The counters of the sessions equal what the rows say (the meaning of I15: they change with the rows, in the same transaction). The limits of the whole queue have no
     * counter, so there is nothing of them to compare: the service counts the rows.
     */
    private void assertCountersMatchRows() throws SQLException {
        assertEquals(ownerLong("SELECT count(*) FROM sennard.messages WHERE acknowledged_at IS NULL"), ownerLong("SELECT coalesce(sum(pending_count), 0) FROM sennard.session_ref"),
                "pending_count");
        long drafts = ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE state = 'PREPARED'");
        long receipts = ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE state <> 'PREPARED'");
        assertEquals(drafts, ownerLong("SELECT coalesce(sum(sender_drafts), 0) FROM sennard.session_ref"), "sender_drafts");
        assertEquals(receipts, ownerLong("SELECT coalesce(sum(sender_receipts), 0) FROM sennard.session_ref"), "sender_receipts");
    }

    private long receiptCount() throws SQLException {
        return ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE state <> 'PREPARED'");
    }

    private long draftCount() throws SQLException {
        return ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE state = 'PREPARED'");
    }

    private long queueBytes() throws SQLException {
        return ownerLong("SELECT coalesce(sum(body_bytes), 0) FROM sennard.messages WHERE acknowledged_at IS NULL");
    }

    private static void setTime(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setObject(index, utc(instant));
        }
    }

    /**
     * Brings the number of drafts (or of receipts) in the table up by {@code count}: records of idle senders, each holding as many as one sender may, with the counter of the
     * sender set to match, none of them due before {@code lapse}. The refusals of the limits of the whole queue are judged on rows like these (fixture, written as the owner).
     */
    private void fillRecords(boolean drafts, int count, Instant lapse) throws SQLException {
        String idleTarget = session("codex");
        int perSender = drafts ? limits().senderDraftLimit() : limits().senderReceiptLimit();
        for (int placed = 0; placed < count; placed += perSender) {
            int here = Math.min(perSender, count - placed);
            String idleSender = session("claude");
            try (Connection owner = BusinessDb.connect(); PreparedStatement insert = owner.prepareStatement(
                    "INSERT INTO sennard.prepared_messages (message_id, sender_ref, target_ref, body, ttl_seconds, prepared_at, expires_at, state, sent_at, message_expires_at, "
                            + "recalled_at, record_bytes, version) VALUES (?, ?, ?, ?, 3600, ?, ?, ?, ?, ?, NULL, ?, 0)")) {
                for (int number = 0; number < here; number++) {
                    insert.setString(1, "fill-" + UUID.randomUUID());
                    insert.setString(2, idleSender);
                    insert.setString(3, idleTarget);
                    insert.setString(4, drafts ? "x" : null);
                    setTime(insert, 5, T0);
                    setTime(insert, 6, lapse);
                    insert.setString(7, (drafts ? MessageState.PREPARED : MessageState.SUBMITTED).name());
                    setTime(insert, 8, drafts ? null : T0);
                    setTime(insert, 9, drafts ? null : T0.plusSeconds(3600));
                    insert.setLong(10, FILLER_RECORD_BYTES);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            owner("UPDATE sennard.session_ref SET " + (drafts ? "sender_drafts" : "sender_receipts") + " = " + here + " WHERE session_ref = '" + idleSender + "'");
        }
    }

    /**
     * A message that waits in the queue of a session nobody reads, with a body of {@code bodyBytes} bytes: its sender's receipt and the queue row (a day of life, as long as
     * the receipt's hour after it), and the counters of the two sessions as the service would have left them (fixture, written as the owner). It is what brings the queue's
     * bytes to its limit.
     */
    private void queueFiller(long bodyBytes) throws SQLException {
        String idleSender = session("claude");
        String idleTarget = session("codex");
        String messageId = "fill-queued-" + UUID.randomUUID();
        try (Connection owner = BusinessDb.connect()) {
            try (PreparedStatement record = owner.prepareStatement("INSERT INTO sennard.prepared_messages (message_id, sender_ref, target_ref, body, ttl_seconds, prepared_at, "
                    + "expires_at, state, sent_at, message_expires_at, recalled_at, record_bytes, version) VALUES (?, ?, ?, NULL, 86400, ?, ?, 'SUBMITTED', ?, ?, NULL, ?, 0)")) {
                record.setString(1, messageId);
                record.setString(2, idleSender);
                record.setString(3, idleTarget);
                setTime(record, 4, T0);
                setTime(record, 5, T0.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
                setTime(record, 6, T0);
                setTime(record, 7, T0.plus(Duration.ofDays(1)));
                record.setLong(8, FILLER_RECORD_BYTES);
                record.executeUpdate();
            }
            try (PreparedStatement queued = owner.prepareStatement("INSERT INTO sennard.messages (message_id, sender_ref, sender_host, target_ref, seq, body, body_bytes, "
                    + "created_at, expires_at, version) VALUES (?, ?, 'claude', ?, 1, ?, ?, ?, ?, 0)")) {
                queued.setString(1, messageId);
                queued.setString(2, idleSender);
                queued.setString(3, idleTarget);
                queued.setString(4, "x".repeat(Math.toIntExact(bodyBytes)));
                queued.setLong(5, bodyBytes);
                setTime(queued, 6, T0);
                setTime(queued, 7, T0.plus(Duration.ofDays(1)));
                queued.executeUpdate();
            }
        }
        owner("UPDATE sennard.session_ref SET sender_receipts = 1 WHERE session_ref = '" + idleSender + "'",
                "UPDATE sennard.session_ref SET pending_count = 1 WHERE session_ref = '" + idleTarget + "'");
    }

    // ------------------------------------------------------------------------------------------------------------------ p

    @Test
    void preparedMessageIsQueuedOnSendDeliveredOnClaimAndClosedByItsReceipt() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared prepared = prepare(sender, target, "hello", 120);
        assertEquals(T0, prepared.preparedAt());
        assertEquals(T0.plus(Duration.ofMinutes(10)), prepared.expiresAt(), "a draft lives ten minutes");
        assertEquals(MessageState.PREPARED, status(sender, prepared.messageId()).orElseThrow().state());
        assertEquals(0, pending(target), "a preparation has no queue effect");
        Sent sent = send(sender, "claude", prepared.messageId());
        assertFalse(sent.duplicate());
        assertEquals(T0, sent.createdAt());
        assertEquals(T0.plusSeconds(120), sent.expiresAt());
        Sent again = send(sender, "claude", prepared.messageId());
        assertTrue(again.duplicate());
        assertEquals(sent.createdAt(), again.createdAt(), "the first receipt again");
        assertEquals(sent.expiresAt(), again.expiresAt());
        assertEquals(1, pending(target));
        assertCountersMatchRows();
        MessageStatus queued = status(sender, prepared.messageId()).orElseThrow();
        assertEquals(MessageState.QUEUED, queued.state());
        assertEquals("codex", queued.targetHost());
        assertEquals(1, receiptCount());
        assertEquals(0, draftCount());
        assertEquals(1, ownerLong("SELECT sender_receipts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertEquals(0, ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertTrue(status(target, prepared.messageId()).isEmpty(), "another sender's ID answers like an unknown one");

        List<ClaimedMessage> claimed = claim(target, null, GENERATION);
        assertEquals(1, claimed.size());
        ClaimedMessage message = claimed.get(0);
        assertEquals("claude", message.senderHost());
        assertEquals(sender, message.senderRef());
        assertEquals(1, message.deliveryAttempt());
        assertEquals(sent.createdAt(), message.createdAt());
        assertEquals(T0, message.firstDeliveredAt());
        assertTrue(message.claimReceipt().startsWith("1:"));
        assertEquals(List.of(), claim(target, null, null), "the claim lease hides it");
        assertEquals(MessageState.DELIVERED, status(sender, prepared.messageId()).orElseThrow().state());
        assertEquals(0, acknowledge(target, List.of(message.messageId()), List.of()), "a claimed message needs its receipt");
        assertEquals(1, acknowledge(target, List.of(message.messageId()), List.of(receiptOf(message))));
        assertEquals(MessageState.ACKNOWLEDGED, status(sender, prepared.messageId()).orElseThrow().state());
        assertCountersMatchRows();
        NotFoundException unavailable = assertThrows(NotFoundException.class, () -> send(target, "codex", prepared.messageId()));
        assertEquals(NotFoundCode.MESSAGE_ID_UNAVAILABLE, unavailable.code());
    }

    @Test
    void oneIdSentByManyThreadsIsQueuedOnceAndTheOthersAreDuplicates() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared prepared = prepare(sender, target, "once", null);
        List<Sent> results = parallel(WRITERS, worker -> service().send(sender, "claude", prepared.messageId(), Operations.fresh("send_session_message", sender), Sent.class, Function.identity()).value());
        assertEquals(1, results.stream().filter(sent -> !sent.duplicate()).count(), "exactly one send queued it: " + results);
        assertEquals(WRITERS - 1, results.stream().filter(Sent::duplicate).count());
        assertEquals(1, new HashSet<>(results.stream().map(Sent::createdAt).toList()).size(), "every answer carries the first receipt");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.messages"));
        assertCountersMatchRows();
    }

    @Test
    void aQueueRowThatExistsBeforeTheReceiptAnswersAsItsOwnDuplicateAndAnotherContentIsATakenId() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared same = prepare(sender, target, "a", 120);
        Prepared other = prepare(sender, target, "b", 120);
        Prepared third = prepare(sender, target, "c", 120);
        queueRow(same.messageId(), sender, target, "a", 120, 1);
        queueRow(other.messageId(), sender, target, "another body", 120, 2);
        queueRow(third.messageId(), sender, target, "c", 120, 3);
        // one place is left under the receipt limit
        fillRecords(false, limits().receiptLimit() - 1, T0.plus(Duration.ofHours(1)));
        Sent first = send(sender, "claude", same.messageId());
        assertTrue(first.duplicate(), "the message was already there");
        assertEquals(T0, first.createdAt(), "its own receipt: the queue row's times");
        assertEquals(limits().receiptLimit(), receiptCount(), "the draft became a receipt, the last place");

        ConflictException taken = assertThrows(ConflictException.class, () -> send(sender, "claude", other.messageId()));
        assertEquals(ConflictCode.MESSAGE_ID_TAKEN, taken.code());
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + other.messageId() + "'"), "nothing changed");

        // a receipt is under the receipt limit even when the row was already queued
        UnavailableException full = assertThrows(UnavailableException.class, () -> send(sender, "claude", third.messageId()));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL, full.code());
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + third.messageId() + "'"), "nothing changed");
    }

    /** A queue row the queue itself would not make: a message already queued under a draft's ID (fixture, written as the owner; the counters of the sessions are not touched). */
    private void queueRow(String messageId, String sender, String target, String body, int ttl, int seq) throws SQLException {
        owner("INSERT INTO sennard.messages (message_id, sender_ref, sender_host, target_ref, seq, body, body_bytes, created_at, expires_at, version) VALUES ('" + messageId
                + "', '" + sender + "', 'claude', '" + target + "', " + seq + ", '" + body + "', " + body.length() + ", " + ts(T0) + ", " + ts(T0.plusSeconds(ttl)) + ", 0)");
    }

    @Test
    void inputIsRefusedWithTheRulesCodeBeforeAnyWrite() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared draft = prepare(sender, target, "kept", null);
        assertEquals(InputCode.MESSAGE_ID_SHAPE, assertThrows(InvalidInputException.class, () -> send(sender, "claude", "bad id")).code());
        assertEquals(InputCode.MESSAGE_BODY_NUL, assertThrows(InvalidInputException.class, () -> prepare(sender, target, "a\0b", null)).code());
        InvalidInputException big = assertThrows(InvalidInputException.class, () -> prepare(sender, target, "x".repeat(4097), null));
        assertEquals(InputCode.MESSAGE_BODY_SIZE, big.code());
        assertEquals(4096, big.arguments().get("maxBytes"), "the limit the message tells is the setting's");
        prepare(sender, target, "x".repeat(4096), null);
        prepare(sender, target, "가".repeat(1365), null); // 4095 bytes
        assertEquals(InputCode.MESSAGE_BODY_SIZE, assertThrows(InvalidInputException.class, () -> prepare(sender, target, "가".repeat(1366), null)).code(), "4098 bytes");
        // Blank is what String.isBlank says: Java whitespace only. (The list this replaced, a JavaScript trim's, also held the no-break space and the byte order mark, which are bodies now.)
        for (String blank : List.of(" \t ", " ", "　", " ", "\u001F", "")) { // former list:" \t ", " ", "﻿", " ", "　", "")) {
            assertEquals(InputCode.MESSAGE_BODY_SIZE, assertThrows(InvalidInputException.class, () -> prepare(sender, target, blank, null)).code(), "blank: " + blank.length());
        }
        prepare(sender, target, "​", null); // a zero width space is not blank
        assertEquals(InputCode.MESSAGE_TTL_RANGE, assertThrows(InvalidInputException.class, () -> prepare(sender, target, "a", 29)).code());
        assertEquals(InputCode.MESSAGE_TTL_RANGE, assertThrows(InvalidInputException.class, () -> prepare(sender, target, "a", 86401)).code());
        prepare(sender, target, "a", 30);
        prepare(sender, target, "a", 86400);
        assertEquals(NotFoundCode.SESSION_NOT_FOUND, assertThrows(NotFoundException.class, () -> prepare(sender, "ref-none", "a", null)).code());
        assertEquals(NotFoundCode.SESSION_NOT_FOUND, assertThrows(NotFoundException.class, () -> prepare("ref-none", target, "a", null)).code());
        InvalidInputException blankRef = assertThrows(InvalidInputException.class, () -> prepare("", target, "a", null));
        assertEquals(InputCode.VALIDATION_FAILED, blankRef.code());
        assertTrue(blankRef.details().containsKey("violations"), blankRef.details().toString());
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + draft.messageId() + "'"));
        assertCountersMatchRows();
    }

    /** The limits of one sender are counters of its session row ({@code sender_drafts}, {@code sender_receipts}), changed by conditional UPDATEs; a test sets one at its limit. */
    @Test
    void theLimitsOfOneSenderRefuseWithTheirCodesAndWriteNothing() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        owner("UPDATE sennard.session_ref SET sender_drafts = " + limits().senderDraftLimit() + " WHERE session_ref = '" + sender + "'");
        UnavailableException draftsFull = assertThrows(UnavailableException.class, () -> prepare(sender, target, "a", null));
        assertEquals(UnavailableCode.MESSAGE_PREPARATION_FULL, draftsFull.code());
        assertEquals("sender", draftsFull.arguments().get("scope"));
        assertEquals("pending", draftsFull.arguments().get("release"), "no receipt that could free a place is known");
        assertEquals("", draftsFull.arguments().get("earliestReleaseAt"));
        assertEquals("sender", draftsFull.details().get("scope"));
        assertNull(draftsFull.details().get("earliestReleaseAt"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.prepared_messages"), "no draft");
        assertEquals(limits().senderDraftLimit(), ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"), "the counter did not move");

        // a receipt exists: the refusal says when the first place frees
        owner("UPDATE sennard.session_ref SET sender_drafts = 0 WHERE session_ref = '" + sender + "'");
        String first = sent(sender, "claude", target, "first", 600);
        Instant firstLapses = T0.plusSeconds(600).plus(Duration.ofHours(1));
        owner("UPDATE sennard.session_ref SET sender_receipts = " + limits().senderReceiptLimit() + " WHERE session_ref = '" + sender + "'");
        UnavailableException senderFull = assertThrows(UnavailableException.class, () -> prepare(sender, target, "a", null));
        assertEquals(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT, senderFull.code());
        assertEquals("sender", senderFull.arguments().get("scope"));
        assertEquals("scheduled", senderFull.arguments().get("release"));
        assertEquals(Timestamps.format(firstLapses), senderFull.arguments().get("earliestReleaseAt"));
        assertEquals(Timestamps.format(firstLapses), senderFull.details().get("earliestReleaseAt"));
        owner("UPDATE sennard.session_ref SET sender_receipts = 1 WHERE session_ref = '" + sender + "'");
        Prepared p = prepare(sender, target, "a", null);
        owner("UPDATE sennard.session_ref SET sender_receipts = " + limits().senderReceiptLimit() + " WHERE session_ref = '" + sender + "'");
        UnavailableException sendFull = assertThrows(UnavailableException.class, () -> send(sender, "claude", p.messageId()));
        assertEquals(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL, sendFull.code());
        assertEquals("sender", sendFull.arguments().get("scope"));
        assertEquals(first, ownerText("SELECT message_id FROM sennard.messages"), "the refused send queued nothing (only the first message is there)");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.messages"));
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + p.messageId() + "'"));
    }

    @Test
    void theReceiptLimitOfAllSendersAdmitsTheLastPlaceAndRefusesTheNextPrepareAndSend() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Instant firstLapses = T0.plus(Duration.ofHours(1));
        fillRecords(false, limits().receiptLimit() - 1, firstLapses);
        Prepared a = prepare(sender, target, "a", null);
        Prepared b = prepare(sender, target, "b", null);
        assertFalse(send(sender, "claude", a.messageId()).duplicate(), "the last place of the receipts");
        assertEquals(limits().receiptLimit(), receiptCount());

        UnavailableException sendFull = assertThrows(UnavailableException.class, () -> send(sender, "claude", b.messageId()));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL, sendFull.code());
        assertEquals("global", sendFull.arguments().get("scope"));
        assertEquals("scheduled", sendFull.arguments().get("release"));
        assertEquals(Timestamps.format(firstLapses), sendFull.arguments().get("earliestReleaseAt"));
        UnavailableException prepareFull = assertThrows(UnavailableException.class, () -> prepare(sender, target, "c", null));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, prepareFull.code());
        assertEquals("global", prepareFull.arguments().get("scope"));
        assertEquals(Timestamps.format(firstLapses), prepareFull.details().get("earliestReleaseAt"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.messages"), "the refused send queued nothing (only the first message is there)");
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + b.messageId() + "'"));
        assertEquals(1, draftCount(), "and the refused prepare made no draft");
        assertCountersMatchRows();
    }

    @Test
    void theByteLimitOfTheQueueAdmitsAnExactFitAndNoMore() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        queueFiller(limits().messageBytesLimit() - 1);
        sent(sender, "claude", target, "a", null);
        assertEquals(limits().messageBytesLimit(), queueBytes());
        Prepared next = prepare(sender, target, "a", null);
        UnavailableException spool = assertThrows(UnavailableException.class, () -> send(sender, "claude", next.messageId()));
        assertEquals(UnavailableCode.MESSAGE_SPOOL_FULL, spool.code());
        assertEquals("global", spool.arguments().get("scope"));
        assertEquals("pending", spool.arguments().get("release"));
        assertEquals(limits().messageBytesLimit(), queueBytes(), "nothing was queued");
        assertEquals("PREPARED", ownerText("SELECT state FROM sennard.prepared_messages WHERE message_id = '" + next.messageId() + "'"));
        assertCountersMatchRows();
    }

    @Test
    void theDraftLimitOfAllSendersAdmitsTheLastPlaceAndRefusesTheNextPrepare() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        fillRecords(true, limits().draftLimit() - 1, T0.plus(Duration.ofHours(1)));
        prepare(sender, target, "last", null);
        assertEquals(limits().draftLimit(), draftCount(), "the last place of the drafts");
        UnavailableException full = assertThrows(UnavailableException.class, () -> prepare(sender, target, "a", null));
        assertEquals(UnavailableCode.MESSAGE_PREPARATION_FULL, full.code());
        assertEquals("global", full.arguments().get("scope"));
        assertEquals("pending", full.arguments().get("release"));
        assertEquals("global", full.details().get("scope"));
        assertEquals(limits().draftLimit(), draftCount(), "no draft was made");
        assertEquals(1, ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"), "and the sender's counter did not move for the refused one");
        assertCountersMatchRows();
    }

    @Test
    void theRecordByteLimitCountsTheOtherDraftsAndReceipts() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared first = prepare(sender, target, "a", null);
        Prepared other = prepare(sender, target, "b", null);
        owner("UPDATE sennard.prepared_messages SET record_bytes = " + limits().recordBytesLimit() + " WHERE message_id = '" + other.messageId() + "'");
        UnavailableException prepareFull = assertThrows(UnavailableException.class, () -> prepare(sender, target, "c", null));
        assertEquals(UnavailableCode.MESSAGE_PREPARATION_FULL, prepareFull.code());
        UnavailableException sendFull = assertThrows(UnavailableException.class, () -> send(sender, "claude", first.messageId()));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL, sendFull.code());
        assertEquals(Timestamps.format(other.expiresAt()), sendFull.arguments().get("earliestReleaseAt"), "the first other record to lapse is the other draft");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages"), "the refused send queued nothing");
    }

    /**
     * A limit is judged on what the tables hold now, so when the count drops the refusal ends: the queue's bytes drop with an acknowledgement and with a recall (the bodies
     * of the messages nobody acknowledged are what is counted).
     */
    @Test
    void theQueueByteLimitOpensAgainWhenAnAcknowledgementOrARecallTakesAMessageOutOfTheCount() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        long limit = limits().messageBytesLimit();
        String largeBody = "x".repeat(4096);
        queueFiller(limit - 4096);
        String first = sent(sender, "claude", target, largeBody, null);
        assertEquals(limit, queueBytes(), "the queue is full");
        Prepared blocked = prepare(sender, target, "a", null);
        assertEquals(UnavailableCode.MESSAGE_SPOOL_FULL, assertThrows(UnavailableException.class, () -> send(sender, "claude", blocked.messageId())).code());

        // an acknowledged message is no longer counted
        ClaimedMessage claimed = claim(target, null, null).get(0);
        assertEquals(first, claimed.messageId());
        assertEquals(1, acknowledge(target, List.of(first), List.of(receiptOf(claimed))));
        assertEquals(limit - 4096, queueBytes());
        assertFalse(send(sender, "claude", blocked.messageId()).duplicate(), "the send that was refused is admitted now");

        // a recalled message is no longer counted
        Prepared large = prepare(sender, target, largeBody, null);
        assertEquals(UnavailableCode.MESSAGE_SPOOL_FULL, assertThrows(UnavailableException.class, () -> send(sender, "claude", large.messageId())).code());
        assertTrue(recall(sender, blocked.messageId()).recalled());
        assertEquals(limit - 4096, queueBytes());
        assertFalse(send(sender, "claude", large.messageId()).duplicate());
        assertEquals(limit, queueBytes(), "the queue is full again, to the byte");
        assertCountersMatchRows();
    }

    /** The receipts and the drafts that lapse leave the count: before a limit is judged the service removes what lapsed, so a limit is never held by what is gone. */
    @Test
    void theReceiptAndDraftLimitsOpenAgainWhenWhatHeldThemLapses() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Instant receiptsLapse = T0.plus(Duration.ofHours(1));
        fillRecords(false, limits().receiptLimit(), receiptsLapse);
        UnavailableException full = assertThrows(UnavailableException.class, () -> prepare(sender, target, "a", null));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, full.code());
        assertEquals(Timestamps.format(receiptsLapse), full.arguments().get("earliestReleaseAt"));
        at(receiptsLapse.minusMillis(1));
        assertEquals(UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, assertThrows(UnavailableException.class, () -> prepare(sender, target, "a", null)).code(), "not yet");
        at(receiptsLapse);
        Prepared first = prepare(sender, target, "a", null); // the lapsed receipts left before the limit was judged
        assertEquals(0, receiptCount());
        assertEquals(1, draftCount());
        assertCountersMatchRows();

        // the same for the drafts, removed by the prune
        Instant draftsLapse = first.expiresAt();
        fillRecords(true, limits().draftLimit() - 1, draftsLapse);
        assertEquals(limits().draftLimit(), draftCount());
        UnavailableException draftsFull = assertThrows(UnavailableException.class, () -> prepare(sender, target, "b", null));
        assertEquals(UnavailableCode.MESSAGE_PREPARATION_FULL, draftsFull.code());
        assertEquals("global", draftsFull.arguments().get("scope"));
        at(draftsLapse);
        assertEquals(limits().draftLimit(), prune(), "every draft was due");
        assertEquals(0, draftCount());
        prepare(sender, target, "b", null);
        assertEquals(1, draftCount());
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ c

    @Test
    void theQueueOrderIsTheSequenceNotTheTimestamp() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String first = sent(sender, "claude", target, "a", null);
        String second = sent(sender, "claude", target, "b", null);
        owner("UPDATE sennard.messages SET created_at = TIMESTAMP '2026-01-02 00:00:00' WHERE message_id = '" + first + "'",
                "UPDATE sennard.messages SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE message_id = '" + second + "'");
        assertEquals(List.of(first, second), claim(target, null, null).stream().map(ClaimedMessage::messageId).toList());
    }

    @Test
    void aLapsedLeaseRedeliversWithANewReceiptAndTheAcknowledgementNeedsTheCurrentOne() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "hello", null);
        ClaimedMessage first = claim(target, null, null).get(0);
        at(T0.plus(Duration.ofMinutes(2))); // the lease of the first attempt is two minutes
        ClaimedMessage second = claim(target, null, null).get(0);
        assertEquals(2, second.deliveryAttempt());
        assertTrue(second.claimReceipt().startsWith("2:"));
        assertNotEquals(first.claimReceipt(), second.claimReceipt());
        assertEquals(first.firstDeliveredAt(), second.firstDeliveredAt(), "the first delivery stays the first");
        assertEquals(0, acknowledge(target, List.of(id), List.of(receiptOf(first))), "the receipt of a later claim replaced it");
        assertEquals(1, acknowledge(target, List.of(id), List.of(receiptOf(second))));
        assertEquals(InputCode.ACK_CLAIM_RECEIPT_INVALID, assertThrows(InvalidInputException.class, () -> acknowledge(target, List.of("x"), List.of(new ClaimReceipt("x", "not-a-receipt")))).code());
        assertEquals(0, acknowledge(target, List.of(id), List.of()), "nothing left to acknowledge");
        assertCountersMatchRows();

        // a lapsed lease with no new claim: the late acknowledgement of the same holder is allowed
        String late = sent(sender, "claude", target, "late", null);
        ClaimedMessage held = claim(target, null, null).get(0);
        at(T0.plus(Duration.ofMinutes(12))); // the lease (two minutes from T0 + 2 minutes) lapsed and nobody claimed again
        assertEquals(1, acknowledge(target, List.of(late), List.of(receiptOf(held))));
        assertEquals(InputCode.ACK_MESSAGE_IDS_RANGE, assertThrows(InvalidInputException.class, () -> acknowledge(target, List.of(), List.of())).code());
    }

    @Test
    void aRedeliveredClaimHoldsItsLeaseLongerUpToTheCap() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        sent(sender, "claude", target, "x", 86400);
        Instant now = T0;
        for (long expectedSeconds : new long[] {120, 240, 480, 960, 1800, 1800}) {
            at(now);
            assertEquals(1, claim(target, null, null).size());
            try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                    ResultSet rows = s.executeQuery("SELECT claimed_at, claim_until FROM sennard.messages")) {
                assertTrue(rows.next());
                assertEquals(expectedSeconds * 1000, rows.getTimestamp(2).getTime() - rows.getTimestamp(1).getTime());
            }
            now = now.plusSeconds(expectedSeconds);
        }
    }

    @Test
    void anExpiredMessageIsNotDeliveredAndAnAcknowledgedOneIsNotDeliveredAgain() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        sent(sender, "claude", target, "old", 30);
        at(T0.plusSeconds(31));
        assertEquals(List.of(), claim(target, null, null));
        assertEquals(0, ownerLong("SELECT delivery_attempts FROM sennard.messages"));
        at(T0.plusSeconds(100));
        String id = sent(sender, "claude", target, "new", null);
        ClaimedMessage claimed = claim(target, null, null).get(0);
        assertEquals(id, claimed.messageId());
        acknowledge(target, List.of(id), List.of(receiptOf(claimed)));
        at(T0.plusSeconds(100).plus(Duration.ofMinutes(5)));
        assertEquals(List.of(), claim(target, null, null));
    }

    @Test
    void aClaimRespectsItsBudgets() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String first = sent(sender, "claude", target, "aaaa", null);
        sent(sender, "claude", target, "bbbb", null);
        assertEquals(InputCode.CLAIM_LIMIT_RANGE, assertThrows(InvalidInputException.class, () -> claim(target, new ClaimLimits(11, null), null)).code());
        assertEquals(InputCode.CLAIM_LIMIT_RANGE, assertThrows(InvalidInputException.class, () -> claim(target, new ClaimLimits(null, 0), null)).code());
        assertEquals(InputCode.CLAIM_BUDGET_EXCEEDED, assertThrows(InvalidInputException.class, () -> claim(target, new ClaimLimits(null, 3), null)).code());
        assertEquals(List.of(first), claim(target, new ClaimLimits(null, 5), null).stream().map(ClaimedMessage::messageId).toList());
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages WHERE delivery_attempts > 1"));

        // the answer of one claim is at most 32 KiB: ten bodies of 4096 bytes do not fit it, so the claim stops before the message that would pass the limit
        String bigSender = session("claude");
        String bigTarget = session("codex");
        for (int number = 0; number < 10; number++) {
            sent(bigSender, "claude", bigTarget, "x".repeat(4096), null);
        }
        List<ClaimedMessage> claimed = claim(bigTarget, null, null);
        assertTrue(claimed.size() >= 1 && claimed.size() < 10, "fewer than ten fit 32 KiB: " + claimed.size());
        assertEquals(10 - claimed.size(), pending(bigTarget), "the rest wait");
    }

    @Test
    void deferredClaimsSkipOnceAndTheClaimBoundaryOnlyMovesForward() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "a", null);
        // The rows of the session's observation and of the generation are made by the first use case that needs them, in its own transaction; everything after is an UPDATE of that row.
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.input_observations"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.wake_paths"));

        service().observeNativeInput(target);
        assertEquals("DEFERRED", ownerText("SELECT state FROM sennard.input_observations"), "the first input made the row, already deferred");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"));
        assertEquals(List.of(), service().claimDeferred(target, null, GENERATION), "the first tool boundary after a prompt is skipped");
        assertEquals("SETTLED", ownerText("SELECT state FROM sennard.input_observations"), "the skip was used");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.wake_paths"), "a skipped claim is no claim boundary and makes no row");
        at(T0.plusSeconds(5));
        assertEquals(List.of(id), service().claimDeferred(target, null, GENERATION).stream().map(ClaimedMessage::messageId).toList());
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.wake_paths WHERE claim_boundary_at IS NOT NULL"), "the first claim boundary of the generation made its row");
        assertEquals(T0.plusSeconds(5), ownerInstant("SELECT " + asText("claim_boundary_at") + " FROM sennard.wake_paths"));

        owner("UPDATE sennard.wake_paths SET claim_boundary_at = TIMESTAMP '9999-01-01 00:00:00'");
        service().observeNativeInput(target);
        service().claimTurnEnd(target, null, GENERATION);
        assertEquals("9999-01-01 00:00:00", ownerText("SELECT " + asText("claim_boundary_at") + " FROM sennard.wake_paths"), "the later boundary is kept");
        assertEquals("SETTLED", ownerText("SELECT state FROM sennard.input_observations"), "the turn end forgot the deferral");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"), "and kept the row");
        service().claimWakeInput(target, null, null);
        assertEquals("SETTLED", ownerText("SELECT state FROM sennard.input_observations"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.wake_paths"), "no generation, nothing written");
        // a wake input does not undo a deferral that is still pending
        service().observeNativeInput(target);
        service().claimWakeInput(target, null, null);
        assertEquals("DEFERRED", ownerText("SELECT state FROM sennard.input_observations"));
        service().clearDeferred(target);
        assertEquals("SETTLED", ownerText("SELECT state FROM sennard.input_observations"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"), "the row is never deleted");
    }

    @Test
    void aWakeInputMakesTheSettledObservationRowOfASessionWithNoneAndTheGenerationsFirstClaimMakesItsRow() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        sent(sender, "claude", target, "a", null);
        assertEquals(1, claim(target, null, null).size(), "without a generation nothing needs a row of the wake paths");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.wake_paths"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.input_observations"), "a claim that records no input makes no observation row");
        at(T0.plusSeconds(3));
        service().claimWakeInput(target, null, null);
        assertEquals("SETTLED", ownerText("SELECT state FROM sennard.input_observations WHERE session_ref = '" + target + "'"), "a wake input defers nothing");
        assertEquals(T0.plusSeconds(3), ownerInstant("SELECT " + asText("observed_at") + " FROM sennard.input_observations WHERE session_ref = '" + target + "'"));
        at(T0.plusSeconds(4));
        service().claimWakeInput(target, null, null);
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"), "the second input moved the time of the one row");
        assertEquals(T0.plusSeconds(4), ownerInstant("SELECT " + asText("observed_at") + " FROM sennard.input_observations WHERE session_ref = '" + target + "'"));
        assertEquals(List.of(), service().claim(target, null, GENERATION), "nothing waits, and the generation's row is made all the same by its claim boundary");
        assertEquals(T0.plusSeconds(4), ownerInstant("SELECT " + asText("claim_boundary_at") + " FROM sennard.wake_paths"));
    }

    /**
     * Two calls whose first creation of one row overlap: the first holds its transaction open after its INSERT, the second finds no row, its INSERT waits for the first's
     * uncommitted key (the database says so, not a clock), and when the first commits the second fails with the translated duplicate. It is not caught and not run again.
     */
    private ConflictException loserOfTwoFirstCreations(Runnable useCase) throws Exception {
        TransactionTemplate first = new TransactionTemplate(context.transactionManager());
        CountDownLatch firstHasInserted = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicLong firstBackend = new AtomicLong();
        Future<?> firstCall = pool.submit(() -> first.execute(status -> {
            firstBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
            useCase.run();
            firstHasInserted.countDown();
            awaitInsideTransaction(firstMayCommit);
            return null;
        }));
        await(firstHasInserted);
        Future<?> secondCall = pool.submit(useCase);
        awaitAStatementWaitingForALock(firstBackend.get());
        assertFalse(secondCall.isDone(), "the second call waits for the first one's key");
        firstMayCommit.countDown();
        firstCall.get(120, TimeUnit.SECONDS);
        ExecutionException lost = assertThrows(ExecutionException.class, () -> secondCall.get(120, TimeUnit.SECONDS));
        return assertInstanceOf(ConflictException.class, lost.getCause(), lost.getCause().toString());
    }

    @Test
    void ofTwoFirstInputsOfOneSessionTheSecondFailsWithTheTranslatedDuplicateAndTheFirstRowIsTheOnlyRow() throws Exception {
        String target = session("codex");
        at(T0);
        ConflictException lost = loserOfTwoFirstCreations(() -> service().observeNativeInput(target));
        assertEquals(ConflictCode.DUPLICATE, lost.code(), "the primary key of the session's row decided");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"));
        assertEquals("DEFERRED", ownerText("SELECT state FROM sennard.input_observations"));
        // the row is there now: the same call is an UPDATE and succeeds
        service().observeNativeInput(target);
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.input_observations"));
    }

    @Test
    void ofTwoFirstClaimBoundariesOfOneGenerationTheSecondFailsWithTheTranslatedDuplicateAndTheFirstRowIsTheOnlyRow() throws Exception {
        String target = session("codex");
        at(T0);
        ConflictException lost = loserOfTwoFirstCreations(() -> service().claim(target, null, GENERATION));
        assertEquals(ConflictCode.DUPLICATE, lost.code(), "the primary key of the generation's row decided");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.wake_paths"));
        // the row is there now: the same claim only moves its boundary and succeeds
        at(T0.plusSeconds(2));
        assertEquals(List.of(), service().claim(target, null, GENERATION));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.wake_paths"));
        assertEquals(T0.plusSeconds(2), ownerInstant("SELECT " + asText("claim_boundary_at") + " FROM sennard.wake_paths"));
    }

    @Test
    void ofTwoClaimersOfOneMessageOnlyOneGetsItAndManyClaimersShareTheQueue() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        sent(sender, "claude", target, "single", null);
        List<List<ClaimedMessage>> claims = parallel(WRITERS, worker -> service().claim(target, null, null));
        assertEquals(1, claims.stream().filter(list -> !list.isEmpty()).count(), "one claimer holds the claim");
        assertEquals(1, claims.stream().mapToInt(List::size).sum());

        String manySender = session("claude");
        String manyTarget = session("codex");
        for (int i = 0; i < 12; i++) {
            sent(manySender, "claude", manyTarget, "m" + i, null);
        }
        List<List<String>> drained = parallel(4, worker -> {
            List<String> mine = new ArrayList<>();
            while (true) {
                List<ClaimedMessage> batch = service().claim(manyTarget, new ClaimLimits(3, null), null);
                if (batch.isEmpty()) {
                    return mine;
                }
                batch.forEach(message -> mine.add(message.messageId()));
            }
        });
        List<String> all = drained.stream().flatMap(List::stream).toList();
        assertEquals(12, all.size(), all.toString());
        assertEquals(12, new HashSet<>(all).size(), "no message went to two claimers: " + all);
    }

    /**
     * The transaction boundary of a hook's claim: the server's adapter opens the transaction (it also records the source receipt of the trust area in it) and the claim
     * joins it. A claim whose caller then fails (the receipt could not be recorded) is rolled back with it: the message was not taken and is claimed at the next boundary.
     */
    @Test
    void aClaimThatJoinsACallersTransactionIsRolledBackWithIt() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "x", null);
        TransactionTemplate caller = new TransactionTemplate(context.transactionManager());
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> caller.execute(status -> {
            assertEquals(1, service().claim(target, null, GENERATION).size(), "the claim saw the message inside the caller's transaction");
            throw new IllegalStateException("the source receipt could not be recorded");
        }));
        assertEquals("the source receipt could not be recorded", failed.getMessage());
        assertEquals(0, ownerLong("SELECT delivery_attempts FROM sennard.messages WHERE message_id = '" + id + "'"), "nothing of the claim was kept");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.wake_paths WHERE claim_boundary_at IS NOT NULL"), "nor its claim boundary");
        List<ClaimedMessage> again = claim(target, null, null);
        assertEquals(1, again.size());
        assertEquals(1, again.get(0).deliveryAttempt(), "the first attempt, as if the rolled back claim had not been");
        // and a claim whose caller succeeds keeps it, with the caller's own writes
        String second = sent(sender, "claude", target, "y", null);
        List<ClaimedMessage> kept = caller.execute(status -> service().claim(target, null, GENERATION));
        assertEquals(List.of(second), kept.stream().map(ClaimedMessage::messageId).toList());
        assertEquals(1, ownerLong("SELECT delivery_attempts FROM sennard.messages WHERE message_id = '" + second + "'"));
    }

    @Test
    void ofTwoTransactionsThatClaimOneRowTheSecondWaitsForTheFirstCommitAndThenChangesNoRow() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "x", null);
        MessageRepository queue = context.bean(MessageRepository.class);
        TransactionTemplate first = new TransactionTemplate(context.transactionManager());
        TransactionTemplate second = new TransactionTemplate(context.transactionManager());
        CountDownLatch firstHoldsTheRow = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicLong firstBackend = new AtomicLong(); // the database's identifier of the first transaction's connection, taken inside it (the waiter is told from other waits by it)
        // Two transactions on two connections of the database, in two threads. The first takes the row and holds its transaction open; the second runs the same claim.
        Future<Integer> firstClaim = pool.submit(() -> first.execute(status -> {
            firstBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
            int changed = queue.claim(id, target, 0, T0, T0.plusSeconds(120), T0, "1:first");
            firstHoldsTheRow.countDown();
            awaitInsideTransaction(firstMayCommit);
            return changed;
        }));
        await(firstHoldsTheRow);
        Future<Integer> secondClaim = pool.submit(() -> second.execute(status -> queue.claim(id, target, 0, T0, T0.plusSeconds(120), T0, "1:second")));
        // The second claimer waits for the first one's row lock: the database itself says a statement waits for a lock the first transaction holds, and the second has not answered.
        awaitAStatementWaitingForALock(firstBackend.get());
        assertFalse(secondClaim.isDone(), "the second claimer waits for the first one's commit");
        firstMayCommit.countDown();
        assertEquals(1, firstClaim.get(120, TimeUnit.SECONDS), "the first claimer changed the row");
        assertEquals(0, secondClaim.get(120, TimeUnit.SECONDS), "after the first one's commit the second one's WHERE no longer holds (a live lease): no row changed, no exception");
        assertEquals("1:first", ownerText("SELECT claim_receipt FROM sennard.messages WHERE message_id = '" + id + "'"), "the loser did not write over the winner");
        assertEquals(1, ownerLong("SELECT delivery_attempts FROM sennard.messages WHERE message_id = '" + id + "'"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(120, TimeUnit.SECONDS), "a latch of the test was not released");
    }

    /** {@link #await} for a transaction callback, which declares no checked exception: an interruption keeps its flag and fails the callback. */
    private static void awaitInsideTransaction(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the transaction waited for the test to release it", interrupted);
        }
    }

    /**
     * Waits, on the condition and not on a clock, until the database reports a statement that waits for a row lock the holder (the backend of the first transaction) has: a wait of
     * another connection on the shared server does not count (a deadline only a failure reaches).
     */
    private void awaitAStatementWaitingForALock(long holderBackend) throws Exception {
        LockWaits.awaitWaiterBlockedBy(holderBackend, Duration.ofSeconds(120), "no statement came to wait for the row lock");
    }

    // ------------------------------------------------------------------------------------------------------------------ the operationKey (AGS3-D-0267)

    /**
     * Two first requests of one caller with one key that overlap. The first one does its work and writes its receipt in a transaction it holds open; the second one's work waits on what
     * the first holds, and the database itself says so (the overlap is told by the lock wait, not by a clock). After the first one's commit the second one's work is done and its
     * receipt meets the first one's primary key: it ends with the retryable OPERATION_KEY_IN_PROGRESS and its work is rolled back, so the effect happened once and one receipt exists.
     * The same request sent again is answered with the first one's result.
     */
    @Test
    void twoOverlappingFirstRequestsOfOneCallerAndKeyDoTheWorkOnceAndTheSecondIsToldToSendItAgain() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        OperationKey operation = Operations.fresh("prepare_session_message", sender);
        TransactionTemplate first = new TransactionTemplate(context.transactionManager());
        CountDownLatch firstWorked = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        AtomicLong firstBackend = new AtomicLong();
        Future<Prepared> winner = pool.submit(() -> first.execute(status -> {
            firstBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
            Prepared made = service().prepare(sender, target, "the same content", null, operation); // joins this transaction: the work and the receipt, not yet committed
            firstWorked.countDown();
            awaitInsideTransaction(firstMayCommit);
            return made;
        }));
        await(firstWorked);
        Future<Prepared> loser = pool.submit(() -> service().prepare(sender, target, "the same content", null, operation));
        awaitAStatementWaitingForALock(firstBackend.get());
        assertFalse(loser.isDone(), "the second request waits for what the first one holds");
        firstMayCommit.countDown();
        Prepared made = winner.get(120, TimeUnit.SECONDS);
        ExecutionException lost = assertThrows(ExecutionException.class, () -> loser.get(120, TimeUnit.SECONDS));
        ConflictException conflict = assertInstanceOf(ConflictException.class, lost.getCause());
        assertEquals(ConflictCode.OPERATION_KEY_IN_PROGRESS, conflict.code());
        assertEquals("OPERATION_IN_PROGRESS", conflict.details().get("reason"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE sender_ref = '" + sender + "'"), "the effect happened once: the second one's draft was rolled back");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'prepare_session_message' AND operation_key = '" + operation.key() + "'"));
        assertEquals(made, service().prepare(sender, target, "the same content", null, operation), "the same request sent again is answered with the first one's result");
        assertCountersMatchRows();
    }

    /**
     * The same key of two callers is two operations: while the first one holds its work and receipt uncommitted, the second one's request (the same tool, the same key, the same
     * content, another caller) is not made to wait and is not refused; both effects happen and each caller has its own receipt.
     */
    @Test
    void theSameKeyOfTwoCallersAtTheSameTimeIsTwoOperationsAndBothHaveTheirEffect() throws Exception {
        String senderA = session("claude");
        String senderB = session("claude");
        String target = session("codex");
        at(T0);
        OperationKey operationA = Operations.fresh("prepare_session_message", senderA);
        OperationKey operationB = new OperationKey(operationA.tool(), senderB, operationA.key(), operationA.digest());
        TransactionTemplate first = new TransactionTemplate(context.transactionManager());
        CountDownLatch firstWorked = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        Future<Prepared> byA = pool.submit(() -> first.execute(status -> {
            Prepared made = service().prepare(senderA, target, "the same content", null, operationA);
            firstWorked.countDown();
            awaitInsideTransaction(firstMayCommit);
            return made;
        }));
        await(firstWorked);
        Prepared madeForB = pool.submit(() -> service().prepare(senderB, target, "the same content", null, operationB)).get(120, TimeUnit.SECONDS);
        assertFalse(byA.isDone(), "the first one still holds its transaction: the second one finished without waiting for it");
        firstMayCommit.countDown();
        Prepared madeForA = byA.get(120, TimeUnit.SECONDS);
        assertNotEquals(madeForA.messageId(), madeForB.messageId(), "each caller has a draft of its own");
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'prepare_session_message' AND operation_key = '" + operationA.key() + "'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE sender_ref = '" + senderA + "'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE sender_ref = '" + senderB + "'"));
        assertEquals(madeForA, service().prepare(senderA, target, "the same content", null, operationA));
        assertEquals(madeForB, service().prepare(senderB, target, "the same content", null, operationB));
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ a

    @Test
    void anAcknowledgementNeedsTheHoldersClaimGivesTheCountersBackAndShortensTheReceiptOnce() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "x", 3600);
        assertEquals(0, acknowledge(target, List.of(id), List.of()), "not claimed yet");
        ClaimedMessage claimed = claim(target, null, null).get(0);
        assertEquals(0, acknowledge(sender, List.of(id), List.of(receiptOf(claimed))), "another session is not the recipient");
        assertEquals(0, acknowledge(target, List.of("message-unknown"), List.of()), "an unknown ID");
        String receiptExpiry = "SELECT " + asText("expires_at") + " FROM sennard.prepared_messages WHERE message_id = '" + id + "'";
        assertEquals(T0.plusSeconds(3600).plus(Duration.ofHours(1)), ownerInstant(receiptExpiry), "the receipt outlives the message by an hour");
        at(T0.plusSeconds(60));
        assertEquals(1, acknowledge(target, List.of(id), List.of(receiptOf(claimed))));
        assertEquals(T0.plusSeconds(60).plus(Duration.ofHours(1)), ownerInstant(receiptExpiry), "the first acknowledgement shortened the receipt to an hour from it");
        assertEquals(0, acknowledge(target, List.of(id), List.of(receiptOf(claimed))), "a second acknowledgement counts for nothing");
        assertEquals(T0.plusSeconds(60).plus(Duration.ofHours(1)), ownerInstant(receiptExpiry));
        assertCountersMatchRows();

        // a receipt that is already shorter is not extended
        String other = sent(sender, "claude", target, "y", 3600);
        ClaimedMessage second = claim(target, null, null).get(0);
        Instant shorter = T0.plusSeconds(60).plus(Duration.ofMinutes(10));
        owner("UPDATE sennard.prepared_messages SET expires_at = " + ts(shorter) + " WHERE message_id = '" + other + "'");
        acknowledge(target, List.of(other), List.of(receiptOf(second)));
        assertEquals(shorter, ownerInstant("SELECT " + asText("expires_at") + " FROM sennard.prepared_messages WHERE message_id = '" + other + "'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.messages WHERE message_id = '" + other + "' AND acknowledged_at IS NOT NULL"));
    }

    @Test
    void acknowledgementsOfOneMessageCountOnce() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "x", null);
        ClaimedMessage claimed = claim(target, null, null).get(0);
        List<Integer> counted = parallel(WRITERS, worker -> service().acknowledge(target, List.of(id), List.of(receiptOf(claimed)), Operations.fresh("acknowledge_session_messages", target)));
        assertEquals(1, counted.stream().mapToInt(Integer::intValue).sum(), counted.toString());
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ r

    @Test
    void aRecallTakesBackOnlyWhatNoRecipientHasReceivedAndGivesTheCountersBack() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        assertEquals(RecallRefusal.UNKNOWN, recall(sender, "none-none-none").reason());
        assertNull(recall(sender, "none-none-none").status());
        Prepared draft = prepare(sender, target, "draft", null);
        RecallResult notSent = recall(sender, draft.messageId());
        assertEquals(RecallRefusal.NOT_SENT, notSent.reason());
        assertEquals(MessageState.PREPARED, notSent.status().state());
        send(sender, "claude", draft.messageId());
        assertCountersMatchRows();
        at(T0.plusSeconds(10));
        RecallResult recalled = recall(sender, draft.messageId());
        assertTrue(recalled.recalled());
        assertFalse(recalled.duplicate());
        assertEquals(MessageState.RECALLED, recalled.status().state());
        assertEquals(T0.plusSeconds(10), recalled.status().recalledAt());
        assertNull(recalled.status().deliveryState(), "a recalled message has none");
        RecallResult again = recall(sender, draft.messageId());
        assertTrue(again.recalled() && again.duplicate());
        assertEquals(recalled.status().recalledAt(), again.status().recalledAt());
        assertEquals(List.of(), claim(target, null, null), "a recalled message is never delivered");
        assertCountersMatchRows();
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages"));
        assertTrue(send(sender, "claude", draft.messageId()).duplicate(), "the same ID sent again is the old receipt, not a new message");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages"));

        Prepared other = prepare(sender, target, "taken", null);
        send(sender, "claude", other.messageId());
        claim(target, null, null);
        RecallResult refused = recall(sender, other.messageId());
        assertFalse(refused.recalled());
        assertEquals(RecallRefusal.DELIVERED, refused.reason());
        assertEquals(MessageState.DELIVERED, refused.status().state());
        assertCountersMatchRows();

        Prepared lapsed = prepare(sender, target, "lapsed", 30);
        send(sender, "claude", lapsed.messageId());
        at(T0.plusSeconds(10 + 31));
        RecallResult expired = recall(sender, lapsed.messageId());
        assertFalse(expired.recalled());
        assertEquals(RecallRefusal.EXPIRED, expired.reason());
    }

    @Test
    void aRecallAndAClaimOfOneMessageCannotBothWin() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        for (int round = 0; round < 12; round++) {
            String id = sent(sender, "claude", target, "round-" + round, null);
            CyclicBarrier line = new CyclicBarrier(2);
            Future<RecallResult> recall = pool.submit(() -> {
                line.await();
                return service().recall(sender, id, Operations.fresh("recall_session_message", sender), RecallResult.class, Function.identity());
            });
            Future<List<ClaimedMessage>> claim = pool.submit(() -> {
                line.await();
                return service().claim(target, null, null);
            });
            boolean recalled = recall.get(60, TimeUnit.SECONDS).recalled();
            boolean claimed = claim.get(60, TimeUnit.SECONDS).stream().anyMatch(message -> message.messageId().equals(id));
            assertTrue(recalled ^ claimed, "exactly one wins in round " + round + ": recalled=" + recalled + " claimed=" + claimed);
            if (claimed) {
                assertEquals(RecallRefusal.DELIVERED, recall(sender, id).reason());
            }
        }
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ s

    @Test
    void theSendersViewFollowsTheMessageThroughItsStates() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared draft = prepare(sender, target, "x", 120);
        MessageStatus prepared = status(sender, draft.messageId()).orElseThrow();
        assertEquals(MessageState.PREPARED, prepared.state());
        assertEquals(T0, prepared.preparedAt());
        assertEquals(draft.expiresAt(), prepared.expiresAt());
        assertNull(prepared.targetRef());
        send(sender, "claude", draft.messageId());
        ClaimedMessage claimed = claim(target, null, null).get(0);
        MessageStatus delivered = status(sender, draft.messageId()).orElseThrow();
        assertEquals(MessageState.DELIVERED, delivered.state());
        assertEquals(target, delivered.targetRef());
        assertEquals(T0, delivered.claimedAt());
        assertEquals(1L, delivered.deliveryAttempts());
        assertTrue(status(target, draft.messageId()).isEmpty(), "the recipient is not the sender");
        assertTrue(status(sender, "no-such-message").isEmpty());
        acknowledge(target, List.of(draft.messageId()), List.of(receiptOf(claimed)));
        assertEquals(MessageState.ACKNOWLEDGED, status(sender, draft.messageId()).orElseThrow().state());

        // the queue row is gone (an hour after the acknowledgement): the receipt is what is left, and it cannot say whether the recipient took it
        at(T0.plus(Duration.ofMinutes(30)));
        String second = sent(sender, "claude", target, "y", 120);
        at(T0.plus(Duration.ofMinutes(30)).plusSeconds(200));
        prune();
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages WHERE message_id = '" + second + "'"), "the lapsed queue row went");
        MessageStatus receipt = status(sender, second).orElseThrow();
        assertEquals(MessageState.SUBMITTED, receipt.state());
        assertEquals(io.github.jaeseongs95.sennard.domain.messaging.DeliveryState.UNKNOWN, receipt.deliveryState());
        assertEquals(T0.plus(Duration.ofMinutes(30)).plusSeconds(120), receipt.expiresAt());
    }

    // ------------------------------------------------------------------------------------------------------------------ u

    @Test
    void aLapsedAcknowledgedMessageLosesItsBodyAndTheRowGoesWhenItsReceiptEnds() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        String id = sent(sender, "claude", target, "x", 3600);
        ClaimedMessage claimed = claim(target, null, null).get(0);
        at(T0.plus(Duration.ofMinutes(10)));
        acknowledge(target, List.of(id), List.of(receiptOf(claimed))); // the receipt now lives until T0 + 70 minutes; the message lapses at T0 + 60 minutes
        at(T0.plus(Duration.ofMinutes(62)));
        prune();
        assertEquals("", ownerText("SELECT body FROM sennard.messages WHERE message_id = '" + id + "'"), "expired and acknowledged: the text goes");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE message_id = '" + id + "'"), "its receipt is alive");
        at(T0.plus(Duration.ofMinutes(70)));
        prune();
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages WHERE message_id = '" + id + "'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE message_id = '" + id + "'"));
        assertCountersMatchRows();
    }

    @Test
    void whatLapsedWithoutAcknowledgementLeavesWithItsCounters() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared draft = prepare(sender, target, "never sent", null);
        String queued = sent(sender, "claude", target, "never read", 60);
        assertEquals(1, ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertEquals(1, ownerLong("SELECT pending_count FROM sennard.session_ref WHERE session_ref = '" + target + "'"));

        at(T0.plus(Duration.ofMinutes(11))); // the draft lapsed after ten minutes, the message after a minute, its receipt after an hour and a minute
        assertEquals(2, prune(), "the draft and the unacknowledged message");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE message_id = '" + draft.messageId() + "'"));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.messages WHERE message_id = '" + queued + "'"));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.prepared_messages WHERE message_id = '" + queued + "'"), "the receipt is alive");
        assertEquals(0, ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertEquals(0, ownerLong("SELECT pending_count FROM sennard.session_ref WHERE session_ref = '" + target + "'"));
        assertCountersMatchRows();

        at(T0.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(2)));
        assertEquals(1, prune(), "the receipt");
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.prepared_messages"));
        assertEquals(0, ownerLong("SELECT sender_receipts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ k

    @Test
    void countersMatchTheRowsAfterMixedConcurrentOperations() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(sent(sender, "claude", target, "body", null));
        }
        parallel(4, worker -> {
            if (worker == 0) {
                for (String id : ids) {
                    service().recall(sender, id, Operations.fresh("recall_session_message", sender), RecallResult.class, Function.identity());
                }
            } else {
                for (ClaimedMessage message : service().claim(target, new ClaimLimits(3, null), null)) {
                    service().acknowledge(target, List.of(message.messageId()), List.of(receiptOf(message)), Operations.fresh("acknowledge_session_messages", target));
                }
            }
            return null;
        });
        assertCountersMatchRows();
        assertEquals(12, receiptCount());
    }

    /**
     * Room for exactly ten more bytes in the queue (a filler message holds the rest), and forty one-byte drafts sent by eight threads. The limit of the whole queue is a plain
     * read before the write, with no lock, so senders that all saw the last free place may pass it together: every send is answered (queued, or refused with
     * MESSAGE_SPOOL_FULL, and no other failure), at least the ten that fit are queued, and the queue holds at most one more byte per thread that was in flight (the writers
     * of one moment), not a place more. What the sender and the recipient count is what the rows say.
     */
    @Test
    void concurrentSendersFillTheQueueToItsLimitAndPassItByNoMoreThanTheSendersInFlight() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        List<List<String>> drafts = new ArrayList<>();
        for (int worker = 0; worker < WRITERS; worker++) {
            List<String> mine = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                mine.add(prepare(sender, target, "x", null).messageId());
            }
            drafts.add(mine);
        }
        queueFiller(limits().messageBytesLimit() - 10);
        List<Integer> sent = parallel(WRITERS, worker -> {
            int ok = 0;
            for (String id : drafts.get(worker)) {
                try {
                    service().send(sender, "claude", id, Operations.fresh("send_session_message", sender), Sent.class, Function.identity());
                    ok++;
                } catch (UnavailableException full) {
                    assertEquals(UnavailableCode.MESSAGE_SPOOL_FULL, full.code());
                }
            }
            return ok;
        });
        int queued = sent.stream().mapToInt(Integer::intValue).sum();
        assertTrue(queued >= 10 && queued <= 10 + WRITERS - 1, "the ten that fit, and at most one more for each of the other writers in flight: " + queued);
        assertEquals(queued, ownerLong("SELECT count(*) FROM sennard.messages WHERE target_ref = '" + target + "'"));
        assertEquals(limits().messageBytesLimit() - 10 + queued, queueBytes());
        assertEquals(queued, ownerLong("SELECT pending_count FROM sennard.session_ref WHERE session_ref = '" + target + "'"));
        assertEquals(40 - queued, draftCount(), "the refused drafts stay drafts");
        assertEquals(40 - queued, ownerLong("SELECT sender_drafts FROM sennard.session_ref WHERE session_ref = '" + sender + "'"));
        assertCountersMatchRows();
    }

    /**
     * The limit of the records' bytes is judged by a read (the sum of the live records) followed by the write of the new draft, with no lock: preparations that all read the
     * same sum may all be admitted. Room for exactly one more draft and many threads: every preparation is answered (admitted, or refused with MESSAGE_PREPARATION_FULL, and no
     * other failure), at least one is admitted, no more than the threads in flight, and the counters equal the rows.
     */
    @Test
    void theRecordByteLimitIsPassedByNoMoreThanThePreparersInFlightWhenManyThreadsPrepareAtOnce() throws Exception {
        at(T0);
        for (int round = 0; round < 10; round++) {
            BusinessDb.clearData(); // every round starts from the same empty tables
            String sender = session("claude");
            String target = session("codex");
            Prepared filler = prepare(sender, target, "filler", null);
            long one = io.github.jaeseongs95.sennard.domain.messaging.MessageRules.preparedRecordBytes(filler.messageId(), sender, target, "x");
            owner("UPDATE sennard.prepared_messages SET record_bytes = " + (limits().recordBytesLimit() - one - 10) + " WHERE message_id = '" + filler.messageId() + "'");
            List<Boolean> admitted = parallel(WRITERS, worker -> {
                try {
                    service().prepare(sender, target, "x", null, Operations.fresh("prepare_session_message", sender));
                    return true;
                } catch (UnavailableException full) {
                    assertEquals(UnavailableCode.MESSAGE_PREPARATION_FULL, full.code());
                    return false;
                }
            });
            long taken = admitted.stream().filter(Boolean::booleanValue).count();
            assertTrue(taken >= 1 && taken <= WRITERS, "round " + round + ": room for one, never none: " + admitted);
            assertEquals(1 + taken, ownerLong("SELECT count(*) FROM sennard.prepared_messages"));
            assertCountersMatchRows();
        }
    }

    @Test
    void sendsToOneRecipientGetSequenceNumbersWithoutAGapAndSendsBothWaysBetweenTwoSessionsFinish() throws Exception {
        String one = session("claude");
        String two = session("codex");
        at(T0);
        parallel(WRITERS, worker -> {
            for (int i = 0; i < 6; i++) {
                // the senders alternate: one to two, two to one, so the transactions of opposite directions overlap
                String from = worker % 2 == 0 ? one : two;
                String to = worker % 2 == 0 ? two : one;
                Prepared prepared = service().prepare(from, to, "x", null, Operations.fresh("prepare_session_message", from));
                service().send(from, "claude", prepared.messageId(), Operations.fresh("send_session_message", from), Sent.class, Function.identity());
            }
            return null;
        });
        for (String recipient : List.of(one, two)) {
            long count = ownerLong("SELECT count(*) FROM sennard.messages WHERE target_ref = '" + recipient + "'");
            assertEquals(24, count);
            assertEquals(1, ownerLong("SELECT min(seq) FROM sennard.messages WHERE target_ref = '" + recipient + "'"));
            assertEquals(count, ownerLong("SELECT max(seq) FROM sennard.messages WHERE target_ref = '" + recipient + "'"), "no gap");
            assertEquals(count, ownerLong("SELECT count(DISTINCT seq) FROM sennard.messages WHERE target_ref = '" + recipient + "'"), "no duplicate");
        }
        assertCountersMatchRows();
    }

    // ------------------------------------------------------------------------------------------------------------------ w

    @Test
    void peerWaitStateCoversBothDirectionsAndChangesWithDelivery() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        assertFalse(service().peerWaitState(sender, target).related());
        sent(target, "codex", sender, "a", null);
        PeerWait before = service().peerWaitState(sender, target);
        assertTrue(before.related());
        assertEquals(before, service().peerWaitState(sender, target));
        assertEquals(before.fingerprint(), service().peerWaitState(target, sender).fingerprint(), "either direction asks the same question");
        claim(sender, null, null);
        assertNotEquals(before.fingerprint(), service().peerWaitState(sender, target).fingerprint());
    }

    @Test
    void peerWaitSnapshotsKeepTheNewestThousandAndAnOwnersCanBeReset() throws Exception {
        at(T0);
        Instant base = Instant.parse("2090-01-01T00:00:00Z");
        try (Connection owner = BusinessDb.connect(); PreparedStatement insert = owner.prepareStatement(
                "INSERT INTO sennard.peer_wait_snapshots (query_key, owner_key, fingerprint, expires_at, version) VALUES (?, ?, ?, ?, 0)")) {
            for (int number = 0; number < 999; number++) {
                insert.setString(1, "key-" + number);
                insert.setString(2, "owner");
                insert.setString(3, "fp");
                insert.setObject(4, utc(base.plusMillis(number)));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        // key-999 fills the limit; key-1000 to key-1002 each push out the oldest expiry: key-0, key-1 and key-2
        for (int number = 999; number < 1003; number++) {
            service().setPeerWaitSnapshot("key-" + number, new PeerWaitRecord("owner", "fp", base.plusMillis(number)));
        }
        assertEquals(1000, ownerLong("SELECT count(*) FROM sennard.peer_wait_snapshots"));
        assertTrue(service().getPeerWaitSnapshot("key-0").isEmpty(), "the oldest expiry left");
        assertTrue(service().getPeerWaitSnapshot("key-2").isEmpty(), "the third oldest expiry left");
        assertTrue(service().getPeerWaitSnapshot("key-3").isPresent(), "the oldest kept expiry stays");
        PeerWaitRecord newest = service().getPeerWaitSnapshot("key-1002").orElseThrow();
        assertEquals(base.plusMillis(1002), newest.expiresAt());
        long reset = service().resetPeerWaitOwner("owner");
        assertEquals(1000L, reset);
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.peer_wait_snapshots"));

        // a snapshot that lapsed is not returned and goes
        service().setPeerWaitSnapshot("soon", new PeerWaitRecord("owner", "fp", T0.plusSeconds(10)));
        assertTrue(service().getPeerWaitSnapshot("soon").isPresent());
        at(T0.plusSeconds(10));
        assertTrue(service().getPeerWaitSnapshot("soon").isEmpty());
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.peer_wait_snapshots"));
    }

    // ------------------------------------------------------------------------------------------------------------------ e

    @Test
    void theStatesAreStoredByNameAndTheChecksRefuseOtherValuesAndWrongFacts() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        at(T0);
        Prepared draft = prepare(sender, target, "x", null);
        String state = "SELECT state FROM sennard.prepared_messages WHERE message_id = '" + draft.messageId() + "'";
        assertEquals("PREPARED", ownerText(state));
        send(sender, "claude", draft.messageId());
        assertEquals("SUBMITTED", ownerText(state));
        recall(sender, draft.messageId());
        assertEquals("RECALLED", ownerText(state));
        service().observeNativeInput(target);
        assertEquals("DEFERRED", ownerText("SELECT state FROM sennard.input_observations"));

        // a name that is no constant of the three (the list CHECK, and the facts CHECK that names the same three, both refuse; the database names the first by name)
        assertRefused("UPDATE sennard.prepared_messages SET state = 'prepared' WHERE message_id = '" + draft.messageId() + "'", "prepared_messages_");
        assertRefused("UPDATE sennard.prepared_messages SET state = 'QUEUED' WHERE message_id = '" + draft.messageId() + "'", "prepared_messages_");
        assertRefused("UPDATE sennard.input_observations SET state = 'deferred'", "input_observations_state");
        // the facts of a state: a receipt has no body, a recalled message has its time, a draft has no send time
        assertRefused("UPDATE sennard.prepared_messages SET body = 'x' WHERE message_id = '" + draft.messageId() + "'", "prepared_messages_facts");
        assertRefused("UPDATE sennard.prepared_messages SET recalled_at = NULL WHERE message_id = '" + draft.messageId() + "'", "prepared_messages_facts");
        assertRefused("UPDATE sennard.prepared_messages SET state = 'PREPARED', body = 'x' WHERE message_id = '" + draft.messageId() + "'", "prepared_messages_facts");
        // a claim has its receipt, and the number before the colon is the attempt count
        String id = sent(sender, "claude", target, "y", null);
        assertRefused("UPDATE sennard.messages SET claim_receipt = '1:" + UUID.randomUUID() + "' WHERE message_id = '" + id + "'", "messages_claim_receipt");
        ClaimedMessage claimed = claim(target, null, null).get(0);
        assertRefused("UPDATE sennard.messages SET delivery_attempts = 2 WHERE message_id = '" + id + "'", "messages_claim_receipt");
        assertRefused("UPDATE sennard.messages SET acknowledged_at = TIMESTAMP '2026-01-01 00:00:00', acknowledgement_receipt = '1:other' WHERE message_id = '" + id + "'", "messages_acknowledgement_receipt");
        assertEquals(1, acknowledge(target, List.of(id), List.of(receiptOf(claimed))));
    }

    /**
     * The database refuses it with a CHECK violation of one of the table's constraints (PostgreSQL reports the first that fails, by name, with the state 23514).
     */
    private void assertRefused(String sql, String constraintPrefix) {
        SQLException refused = assertThrows(SQLException.class, () -> owner(sql), sql);
        assertEquals("23514", refused.getSQLState(), refused.getMessage());
        assertTrue(refused.getMessage().contains(constraintPrefix), constraintPrefix + ": " + refused.getMessage());
    }

    @Test
    void anUnknownSessionIsRefusedByTheServiceFirstAndByTheForeignKeyWhenTheServiceDoesNotLook() throws Exception {
        String known = session("claude");
        at(T0);
        // the service looks first: the caller is told which session was not found
        assertEquals(NotFoundCode.SESSION_NOT_FOUND, assertThrows(NotFoundException.class, () -> prepare(known, "ref-none", "a", null)).code());
        // observeNativeInput does not look (it is a hook's cheap write): the foreign key of the INSERT is what refuses, as an IntegrityException
        IntegrityException refused = assertThrows(IntegrityException.class, () -> service().observeNativeInput("ref-none"));
        assertEquals(IntegrityCode.REFERENCE_VIOLATION, refused.code());
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.input_observations"));
        // and at the repository: Spring's own exception, from the foreign key
        PreparedMessageRepository drafts = context.bean(PreparedMessageRepository.class);
        PlatformTransactionManager manager = context.transactionManager();
        String messageId = UUID.randomUUID().toString();
        assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(manager).execute(status -> drafts.save(
                PreparedMessage.draft(messageId, known, "ref-none", "x", 60, T0, T0.plusSeconds(600), 100))));
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.prepared_messages"));
    }

    /** The five tables of the messaging module, as the list of an SQL {@code IN}: the schema {@code sennard} holds the tables of every module, so a check of the messaging names its tables. */
    private static final String MESSAGING_TABLES = "('messages', 'prepared_messages', 'wake_paths', 'input_observations', 'peer_wait_snapshots')";

    @Test
    void theMessagingTablesHaveNoPolicyNoLogicAreKeyedByTheirMessageSessionOrQueryAndStoreTimesAndStatesAsStandardTypes() throws Exception {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sennard' AND table_type = 'BASE TABLE' AND table_name IN " + MESSAGING_TABLES)) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
            }
            try (ResultSet rows = s.executeQuery("SELECT c.relname, c.relrowsecurity, (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND c.relkind = 'r' AND c.relname IN " + MESSAGING_TABLES)) {
                int tables = 0;
                while (rows.next()) {
                    assertFalse(rows.getBoolean(2), rows.getString(1) + ": no row-level security");
                    assertEquals(0, rows.getInt(3), rows.getString(1));
                    tables++;
                }
                assertEquals(5, tables);
            }
            try (ResultSet rows = s.executeQuery("SELECT (SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'), "
                    + "(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema = 'sennard')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "no function or procedure");
                assertEquals(0, rows.getInt(2), "no trigger");
            }
            try (ResultSet rows = s.executeQuery("SELECT k.table_name, k.column_name FROM information_schema.key_column_usage k JOIN information_schema.table_constraints t "
                    + "ON t.constraint_schema = k.constraint_schema AND t.constraint_name = k.constraint_name AND t.table_schema = k.table_schema AND t.table_name = k.table_name "
                    + "WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_schema = 'sennard' AND k.table_name IN " + MESSAGING_TABLES + " ORDER BY k.table_name, k.ordinal_position")) {
                Map<String, List<String>> keys = new TreeMap<>();
                while (rows.next()) {
                    keys.computeIfAbsent(rows.getString(1), table -> new ArrayList<>()).add(rows.getString(2));
                }
                assertEquals(Map.of("messages", List.of("message_id"), "prepared_messages", List.of("message_id"), "input_observations", List.of("session_ref"),
                        "peer_wait_snapshots", List.of("query_key"), "wake_paths", List.of("session_ref", "instance_id", "birth_generation")), keys, "the primary keys of the five tables");
            }
            // the standard's TIMESTAMP without a zone and VARCHAR are PostgreSQL's 'timestamp without time zone' and 'character varying'
            String noZone = "timestamp without time zone";
            String varchar = "character varying";
            try (ResultSet rows = s.executeQuery("SELECT column_name, data_type, table_name FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name IN " + MESSAGING_TABLES + " AND column_name IN "
                    + "('created_at', 'expires_at', 'claimed_at', 'claim_until', 'first_delivered_at', 'acknowledged_at', 'prepared_at', 'sent_at', 'message_expires_at', 'recalled_at', "
                    + "'claim_boundary_at', 'updated_at', 'observed_at')")) {
                int times = 0;
                while (rows.next()) {
                    assertEquals(noZone, rows.getString(2), rows.getString(3) + "." + rows.getString(1));
                    times++;
                }
                assertEquals(15, times, "the time columns of the five tables: six, five, two, one and one");
            }
            try (ResultSet rows = s.executeQuery("SELECT table_name, data_type FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name IN " + MESSAGING_TABLES + " AND column_name = 'state'")) {
                int states = 0;
                while (rows.next()) {
                    assertEquals(varchar, rows.getString(2), rows.getString(1));
                    states++;
                }
                assertEquals(2, states, "prepared_messages and input_observations");
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name IN " + MESSAGING_TABLES + " AND column_name = 'version' AND is_nullable = 'NO'")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1), "every table has its version");
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.table_constraints WHERE constraint_schema = 'sennard' AND constraint_name = 'messages_target_seq' AND constraint_type = 'UNIQUE'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "the order of a queue is kept by a UNIQUE key");
            }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM information_schema.table_constraints WHERE constraint_schema = 'sennard' AND table_name IN " + MESSAGING_TABLES + " AND constraint_type = 'FOREIGN KEY'")) {
                assertTrue(rows.next());
                assertEquals(6 + 1, rows.getInt(1), "six to the sessions, one from the queue to its draft");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ t

    @Test
    void aTimeIsStoredAsTheUtcInstantWhateverZoneTheJvmIsIn() throws Exception {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            String sender = session("claude");
            String target = session("codex");
            Instant at = Instant.parse("2026-03-04T05:06:07.089Z");
            at(at);
            Prepared prepared = prepare(sender, target, "tz", 120);
            assertEquals(at, prepared.preparedAt());
            Sent sent = send(sender, "claude", prepared.messageId());
            assertEquals(at, sent.createdAt());
            ClaimedMessage claimed = claim(target, null, null).get(0);
            assertEquals(at, claimed.createdAt());
            assertEquals(at.plusSeconds(120), claimed.expiresAt());
            assertEquals(at, status(sender, prepared.messageId()).orElseThrow().claimedAt(), "the instant comes back as it went in");
            assertEquals("2026-03-04 05:06:07.089", ownerText("SELECT " + asText("created_at") + " FROM sennard.messages WHERE message_id = '" + prepared.messageId() + "'"),
                    "the stored value is the UTC wall time, not Seoul's (14:06)");
            assertEquals("2026-03-04 05:06:07.089", ownerText("SELECT " + asText("prepared_at") + " FROM sennard.prepared_messages WHERE message_id = '" + prepared.messageId() + "'"));
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }
}
