// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;
import org.springframework.validation.annotation.Validated;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
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
import io.github.jaeseongs95.sennard.domain.messaging.DeliveryState;
import io.github.jaeseongs95.sennard.domain.messaging.Generation;
import io.github.jaeseongs95.sennard.domain.messaging.MessageRules;
import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.MessageStatus;
import io.github.jaeseongs95.sennard.domain.messaging.ObservationState;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.domain.messaging.PeerWait;
import io.github.jaeseongs95.sennard.domain.messaging.PeerWaitRecord;
import io.github.jaeseongs95.sennard.domain.messaging.Prepared;
import io.github.jaeseongs95.sennard.domain.messaging.RecallRefusal;
import io.github.jaeseongs95.sennard.domain.messaging.RecallResult;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;
import jakarta.validation.constraints.NotBlank;

/**
 * The session message queue: a sender prepares a draft (the server issues the ID), sends it (one place in the recipient's queue and the first receipt), the
 * recipient claims and acknowledges it, the sender may take it back before it was claimed. The service opens the transactions ({@code @Transactional}), the time comes
 * from the injected clock, the IDs from the injected {@link IdGenerator}, the limits from {@link MessagingProperties}. It knows no MCP and no HTTP: a refusal is an
 * {@code SennardException} and a result is a record of {@code sennard-domain}.
 *
 * <p>The transaction of a method is the method's own, at the database's default isolation (READ COMMITTED), and a transaction that lost a race is never run again: the
 * writer that lost has not decided anything yet, it reads what the winner left and answers from that. Who wins is told by what a write did, not by an exception:
 * <ul>
 * <li>An existing row is changed by one conditional UPDATE and the number of rows it changed is the verdict ({@link MessageRepository#claim} with the message still
 * claimable in its WHERE, {@link MessageRepository#acknowledge} with the holder and the receipt in its WHERE): of two claimers of one message exactly one gets 1, and the other takes nothing
 * from that message. The recall is one conditional DELETE, so it and a claim cannot both win; a recall that finds the message already recalled answers with that recall's
 * result (a duplicate).</li>
 * <li>A message ID is the sender's own and is sent once: the send first turns the draft into its receipt ({@link PreparedMessageRepository#submit}, a conditional UPDATE); a
 * second send of the same ID waits, gets 0 rows, and answers with the first receipt without doing the rest. The queue order {@code seq} is the recipient's highest plus one,
 * read after the recipient's session row was incremented ({@link SessionRefRepository#addPending}), so the senders to one recipient make their numbers one after the other.
 * A UNIQUE key or version violation is not caught and not run again; it goes to the caller as the translated failure it is. The one place it can happen by design is the
 * first creation of a row that nothing made before (a session's observation row, a generation's wake path row): the use case that needs it updates, and when no row changed it
 * inserts, all in its one transaction, and of two that insert at the same moment the key lets one win and the other ends with {@link ConflictCode#DUPLICATE}.</li>
 * <li>The limits. The counters of one session change by conditional UPDATEs judged by the rows they changed ({@link SessionRefRepository}), so one sender never passes its own
 * limit. The limits over the whole queue (the bytes of all records, the numbers of messages, drafts and receipts the tables hold) are judged on counts read from the tables
 * ({@link MessageRepository#countUnacknowledged}, {@link PreparedMessageRepository#countDrafts} and the like) before the write, with no lock: several writers that all see the
 * last free place may pass it together, and the queue holds a few more than its limit then. That is harmless here (these are capacity limits of a personal tool, not a
 * promise to the byte), so no counter row or lock is kept for it.</li>
 * </ul>
 * There is no advisory lock, no explicit lock on rows and no database function. The rows a transaction writes (the conditional UPDATEs of the counters
 * are row writes and wait for a concurrent writer of the same row) are written in one order in every method: message and draft rows first, then the session rows in the order
 * of their values. Two transactions that need the same rows therefore queue behind each other and cannot wait for each other in a circle.
 */
@Service
@Validated
public class MessagingService {
    private final PreparedMessageRepository prepared;
    private final MessageRepository queue;
    private final WakePathRepository wakePaths;
    private final InputObservationRepository observations;
    private final PeerWaitSnapshotRepository snapshots;
    private final SessionRefRepository sessions;
    private final MessagingProperties properties;
    private final Clock clock;
    private final IdGenerator ids;
    private final OperationReceipts receipts;

    public MessagingService(PreparedMessageRepository prepared, MessageRepository queue, WakePathRepository wakePaths, InputObservationRepository observations,
            PeerWaitSnapshotRepository snapshots, SessionRefRepository sessions, MessagingProperties properties, Clock clock, IdGenerator ids, OperationReceipts receipts) {
        this.receipts = receipts;
        this.prepared = prepared;
        this.queue = queue;
        this.wakePaths = wakePaths;
        this.observations = observations;
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.ids = ids;
    }

    // ----------------------------------------------------------------------------------------------------------------- time

    /** The injected clock's instant, to the millisecond: the precision the tools show and every database stores. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    // ---------------------------------------------------------------------------------------------------- prepare and send

    // The four write tools of the queue once per operationKey (AGS3-D-0267): the receipt of the key is read first, so a key that succeeded is answered from it before the rules of the
    // message ID (the duplicate answer of send and recall) are asked; the work is the private method of the same name without the key (it has no transaction of its own and is no entry: it runs only inside the keyed one), and the receipt is written last, in the same transaction.

    @Transactional
    public Prepared prepare(@NotBlank String senderRef, @NotBlank String targetRef, String body, Integer ttlSeconds, OperationKey operation) {
        return prepare(PeerAddress.session(senderRef), PeerAddress.session(targetRef), body, ttlSeconds, operation);
    }

    @Transactional
    public Prepared prepare(PeerAddress sender, PeerAddress target, String body, Integer ttlSeconds, OperationKey operation) {
        return receipts.once(peerOperation(sender, operation), Prepared.class, () -> prepare(sender, target, body, ttlSeconds)).value();
    }

    /**
     * The send once per key, with the answer the caller gives made inside the same transaction and kept in the receipt: a request that comes again is answered with that first answer as it
     * was given (what the caller adds at the time of the call, such as an advisory outlook, is in it and is not made again).
     */
    @Transactional
    public <A> Receipted<A> send(@NotBlank String senderRef, @NotBlank String senderHost, String messageId, OperationKey operation, Class<A> answerType, Function<Sent, A> answer) {
        return send(PeerAddress.session(senderRef), senderHost, messageId, operation, answerType, answer);
    }

    @Transactional
    public <A> Receipted<A> send(PeerAddress sender, @NotBlank String senderHost, String messageId, OperationKey operation, Class<A> answerType, Function<Sent, A> answer) {
        return send(sender, senderHost, messageId, operation, answerType, answer, ignored -> { });
    }

    /** Checks the immutable target of an unsent draft before enqueuing it, in the same transaction. */
    @Transactional
    public <A> Receipted<A> send(PeerAddress sender, @NotBlank String senderHost, String messageId, OperationKey operation, Class<A> answerType, Function<Sent, A> answer,
            Consumer<PeerAddress> requireTarget) {
        return receipts.once(peerOperation(sender, operation), answerType, () -> answer.apply(send(sender, senderHost, messageId, requireTarget)));
    }

    @Transactional
    public int acknowledge(@NotBlank String targetRef, List<String> messageIds, List<ClaimReceipt> claims, OperationKey operation) {
        return acknowledge(targetRef, messageIds, claims, operation, ignored -> { });
    }

    /** The callback belongs to the fresh acknowledgement's transaction; replaying its stored count performs no effects. */
    @Transactional
    public int acknowledge(@NotBlank String targetRef, List<String> messageIds, List<ClaimReceipt> claims, OperationKey operation, IntConsumer acknowledged) {
        return acknowledge(PeerAddress.session(targetRef), messageIds, claims, operation, acknowledged);
    }

    @Transactional
    public int acknowledge(PeerAddress target, List<String> messageIds, List<ClaimReceipt> claims, OperationKey operation, IntConsumer acknowledged) {
        return receipts.once(peerOperation(target, operation), Integer.class, () -> {
            int count = acknowledge(target, messageIds, claims);
            acknowledged.accept(count);
            return count;
        }).value();
    }

    /** The recall once per key, with the caller's answer made inside the transaction and kept in the receipt (see {@link #send(String, String, String, OperationKey, Class, Function)}). */
    @Transactional
    public <A> A recall(@NotBlank String senderRef, String messageId, OperationKey operation, Class<A> answerType, Function<RecallResult, A> answer) {
        return recall(PeerAddress.session(senderRef), messageId, operation, answerType, answer);
    }

    @Transactional
    public <A> A recall(PeerAddress sender, String messageId, OperationKey operation, Class<A> answerType, Function<RecallResult, A> answer) {
        return receipts.once(peerOperation(sender, operation), answerType, () -> answer.apply(recall(sender, messageId))).value();
    }

    /** Preserve the original session scope for existing operation receipts. An addressed actor has a separate receipt scope. */
    private static OperationKey peerOperation(PeerAddress caller, OperationKey operation) {
        if (caller.actorId().isEmpty()) {
            return operation;
        }
        String scope = "peer:" + Digests.sha256Hex(caller.sessionRef() + '\0' + caller.actorId());
        return new OperationKey(operation.tool(), scope, operation.key(), operation.digest());
    }

    /**
     * Preparation is durable but has no queue, peer-relation or wake effect. The server issues the message ID, so the draft is a new row and nothing else can make it. The
     * sender's own count of drafts is one conditional UPDATE; the limits over the whole queue are read first and may be passed by a few writers at once (see the class comment).
     */
    private Prepared prepare(PeerAddress senderAddress, PeerAddress target, String body, Integer ttlSeconds) {
        MessageRules.body(body, properties.bodyMaxBytes());
        Duration ttl = MessageRules.ttl(ttlSeconds, properties.ttlDefault(), properties.ttlMax());
        Instant now = now();
        String messageId = ids.generateId().toString();
        Instant expiresAt = now.plus(properties.draftTtl());
        String senderRef = senderAddress.sessionRef();
        long recordBytes = MessageRules.preparedRecordBytes(messageId, senderAddress, target, body);
        reclaimExpired(now);
        SessionRef sender = requireSessions(senderRef, target.sessionRef());
        // Admission only; send repeats the authoritative check.
        if (sender.senderReceipts() >= properties.senderReceiptLimit()) {
            throw prepared.firstReceiptLapseOfSender(now, senderRef).map(first -> full(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT, "sender", first.expiresAt()))
                    .orElseGet(() -> full(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT, "sender", null));
        }
        if (prepared.countReceipts() >= properties.receiptLimit()) {
            throw prepared.firstReceiptLapse(now).map(first -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, "global", first.expiresAt()))
                    .orElseGet(() -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, "global", null));
        }
        long stored = prepared.liveRecordBytes(now);
        if (stored + recordBytes > properties.recordBytesLimit() || sessions.addSenderDraft(senderRef, properties.senderDraftLimit()) != 1) {
            throw full(UnavailableCode.MESSAGE_PREPARATION_FULL, "sender", null);
        }
        if (prepared.countDrafts() >= properties.draftLimit()) {
            throw full(UnavailableCode.MESSAGE_PREPARATION_FULL, "global", null);
        }
        prepared.save(PreparedMessage.draft(messageId, senderAddress, target, body, ttl.toSeconds(), now, expiresAt, recordBytes));
        return new Prepared(messageId, now, expiresAt);
    }

    /**
     * The send of a prepared message: ID ownership, capacity, the place in the queue and the first receipt, as one transaction. A message that was sent is answered again
     * with its first receipt ({@code duplicate}); a message ID that is not this sender's, never issued or already gone is {@link NotFoundCode#MESSAGE_ID_UNAVAILABLE}.
     */
    private Sent send(PeerAddress sender, String senderHost, String messageId, Consumer<PeerAddress> requireTarget) {
        MessageRules.messageId(messageId);
        Instant now = now();
        reclaimExpired(now);
        PreparedMessage draft = findDraft(sender, messageId, now);
        return switch (draft.state()) {
            case SUBMITTED, RECALLED -> firstReceipt(draft);
            case PREPARED -> {
                requireTarget.accept(draft.target());
                yield enqueue(draft, senderHost, now);
            }
            case QUEUED, DELIVERED, ACKNOWLEDGED -> throw new IntegrityException(IntegrityCode.VALUE_NOT_ALLOWED, SennardException.args("state", draft.state().name()));
        };
    }

    /** The sender's own record of the message, as it is now: a message ID that is not this sender's, never issued or already gone is not found. */
    private PreparedMessage findDraft(PeerAddress sender, String messageId, Instant now) {
        return prepared.findById(messageId).filter(found -> found.sender().equals(sender) && found.expiresAt().isAfter(now))
                .orElseThrow(() -> new NotFoundException(NotFoundCode.MESSAGE_ID_UNAVAILABLE));
    }

    /** The answer to a send of a message that was sent before: its first receipt, again. */
    private static Sent firstReceipt(PreparedMessage record) {
        return switch (record.state()) {
            case SUBMITTED, RECALLED -> new Sent(record.messageId(), record.sentAt(), record.messageExpiresAt(), true);
            case PREPARED, QUEUED, DELIVERED, ACKNOWLEDGED -> throw new IntegrityException(IntegrityCode.VALUE_NOT_ALLOWED, SennardException.args("state", record.state().name()));
        };
    }

    /**
     * The first send of a draft. What can refuse it is read first (the rules of the queue row that may exist, the limits), then the draft becomes its receipt, which is the
     * verdict between sends of one ID, and only the sender that got it writes the rest (the counters of the sessions and the queue row). A send that lost the verdict has
     * written nothing and answers with the receipt the winner made.
     */
    private Sent enqueue(PreparedMessage draft, String senderHost, Instant now) {
        String messageId = draft.messageId();
        String senderRef = draft.senderRef();
        String targetRef = draft.targetRef();
        SessionRef sender = requireSessions(senderRef, targetRef);
        Optional<Message> existing = queue.findById(messageId);
        existing.ifPresent(row -> requireSameMessage(draft, row));
        Instant createdAt = existing.map(Message::createdAt).orElse(now);
        Instant expiresAt = existing.map(Message::expiresAt).orElseGet(() -> now.plusSeconds(draft.ttlSeconds()));
        long bodyBytes = MessageRules.utf8Bytes(draft.body());
        // The limits, read before anything is written, in the order their refusals take: the sender's receipts, the receipts of all, the queue's messages and bytes, the records' bytes.
        if (sender.senderReceipts() >= properties.senderReceiptLimit()) {
            throw senderReceiptsFull(senderRef, now);
        }
        requireReceiptPlace(now);
        if (existing.isEmpty() && (queue.countUnacknowledged() >= properties.messageLimit() || queue.unacknowledgedBytes() + bodyBytes > properties.messageBytesLimit())) {
            throw full(UnavailableCode.MESSAGE_SPOOL_FULL, "global", null);
        }
        long recordBytes = MessageRules.preparedRecordBytes(messageId, draft.sender(), draft.target(), null);
        if (prepared.liveRecordBytesExcept(now, messageId) + recordBytes > properties.recordBytesLimit()) {
            throw prepared.firstLapseExcept(now, messageId).map(first -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL, "global", first.expiresAt()))
                    .orElseGet(() -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL, "global", null));
        }
        if (prepared.submit(messageId, createdAt, expiresAt, expiresAt.plus(properties.receiptRetention()), recordBytes) == 0) {
            return firstReceipt(findDraft(draft.sender(), messageId, now));
        }
        if (existing.isPresent()) {
            senderDraftBecomesReceipt(senderRef, now);
        } else {
            queueNew(draft, senderHost, now, expiresAt, bodyBytes);
        }
        return new Sent(messageId, createdAt, expiresAt, existing.isPresent());
    }

    /** The queue row can exist before the receipt (it was queued by another path): the same message answers as its own duplicate, another one is a taken ID. */
    private static void requireSameMessage(PreparedMessage draft, Message row) {
        boolean same = draft.sender().equals(row.sender()) && draft.target().equals(row.target()) && draft.body().equals(row.body())
                && Duration.between(row.createdAt(), row.expiresAt()).getSeconds() == draft.ttlSeconds();
        if (!same) {
            throw new ConflictException(ConflictCode.MESSAGE_ID_TAKEN);
        }
    }

    /** The message is not queued yet: the sender's draft becomes a receipt and the message joins the target's queue, now. */
    private void queueNew(PreparedMessage draft, String senderHost, Instant now, Instant expiresAt, long bodyBytes) {
        String senderRef = draft.senderRef();
        String targetRef = draft.targetRef();
        // The two session rows are written in the order of their values, then the queue row: every method of this service writes the rows it
        // needs in that order (sessions by value), so two senders (one to two, two to one) never wait for each other in a circle.
        if (senderRef.compareTo(targetRef) <= 0) {
            senderDraftBecomesReceipt(senderRef, now);
            sessions.addPending(targetRef);
        } else {
            sessions.addPending(targetRef);
            senderDraftBecomesReceipt(senderRef, now);
        }
        // The recipient's row was incremented above and stays written until this transaction ends, so no other sender to the recipient has read the highest number between
        // that and the insert below: the numbers of one recipient are made one after the other, and the UNIQUE key never has to refuse one.
        long seq = queue.highestSeq(targetRef) + 1;
        queue.save(Message.queued(draft.messageId(), draft.sender(), senderHost, draft.target(), seq, draft.body(), bodyBytes, now, expiresAt));
    }

    /** The sender's draft becomes a receipt, while the sender has fewer than the limit of them (one conditional UPDATE, judged by the rows it changed). */
    private void senderDraftBecomesReceipt(String senderRef, Instant now) {
        if (sessions.senderDraftBecomesReceipt(senderRef, properties.senderReceiptLimit()) != 1) {
            throw senderReceiptsFull(senderRef, now);
        }
    }

    private UnavailableException senderReceiptsFull(String senderRef, Instant now) {
        return prepared.firstReceiptLapseOfSender(now, senderRef).map(first -> full(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL, "sender", first.expiresAt()))
                .orElseGet(() -> full(UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL, "sender", null));
    }

    /** A draft may become a receipt only while there are fewer than {@code receiptLimit} receipts in all; otherwise the send is refused and says when a place frees. */
    private void requireReceiptPlace(Instant now) {
        if (prepared.countReceipts() >= properties.receiptLimit()) {
            throw prepared.firstReceiptLapse(now).map(first -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL, "global", first.expiresAt()))
                    .orElseGet(() -> full(UnavailableCode.MESSAGE_RECEIPTS_FULL, "global", null));
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the limits

    /** A capacity refusal: nothing was queued or drafted (the transaction ends with it). {@code scope} is sender or global; {@code earliest} is when a place frees. */
    private static UnavailableException full(UnavailableCode code, String scope, Instant earliest) {
        boolean known = earliest != null;
        String at = known ? Timestamps.format(earliest) : null;
        return new UnavailableException(code, SennardException.args("scope", scope, "release", known ? "scheduled" : "pending", "earliestReleaseAt", known ? at : ""),
                SennardException.args("scope", scope, "earliestReleaseAt", at), null);
    }

    /** The sender's session row; both sessions must exist (the foreign keys would refuse a missing one anyway, but the caller is told which refusal it is). */
    private SessionRef requireSessions(String senderRef, String targetRef) {
        SessionRef sender = sessions.findBySessionRef(senderRef).orElseThrow(() -> new NotFoundException(NotFoundCode.SESSION_NOT_FOUND));
        if (!senderRef.equals(targetRef) && !sessions.existsBySessionRef(targetRef)) {
            throw new NotFoundException(NotFoundCode.SESSION_NOT_FOUND);
        }
        return sender;
    }

    // ----------------------------------------------------------------------------------------------------------------- prune

    /**
     * Everything that has lapsed leaves, with its counters: acknowledged rows that are done with, the bodies of acknowledged messages that expired, unacknowledged
     * messages that expired, drafts and receipts that lapsed. {@link #prepare} and {@link #send} do it before they judge a limit, so a limit is never held by what is gone.
     *
     * @return how many rows were removed
     */
    @Transactional
    public long prune() {
        return reclaimExpired(now());
    }

    /** The old claim path's cleanup: acknowledged rows past their retention or without a live receipt, and the bodies of acknowledged messages that expired. */
    private long pruneAcknowledged(Instant now) {
        long removed = queue.deleteFinished(now.minus(properties.receiptRetention()), now);
        queue.emptyFinishedBodies(now);
        return removed;
    }

    /** What lapsed without being acknowledged, and the drafts and receipts that lapsed: removed, and the places they held given back to their sessions. */
    private long reclaimExpired(Instant now) {
        long removed = pruneAcknowledged(now);
        Instant at = now;
        // What each session gives back (pending, drafts, receipts), by session value: the rows are deleted first and the session counters written after, the sessions'
        // rows in the order of their values, as every method of the service does. The limits of the whole queue need no giving back: they are counted from the tables.
        Map<String, long[]> bySession = new TreeMap<>();
        long messages = 0;
        // Each row is removed by a conditional DELETE (one that a concurrent writer already took, or changed, removes nothing and is skipped). The rows go in the order of their
        // IDs, so two transactions that remove the same rows queue behind each other and do not wait for each other in a circle.
        for (Message row : queue.findByAcknowledgedAtIsNullAndExpiresAtLessThanEqual(at).stream().sorted(Comparator.comparing(Message::messageId)).toList()) {
            if (queue.deleteLapsed(row.messageId(), row.version()) == 1) {
                messages++;
                bySession.computeIfAbsent(row.targetRef(), session -> new long[3])[0]++;
            }
        }
        long drafts = 0;
        long receipts = 0;
        for (PreparedMessage record : prepared.findByExpiresAtLessThanEqual(at).stream().sorted(Comparator.comparing(PreparedMessage::messageId)).toList()) {
            if (prepared.deleteLapsed(record.messageId(), record.version()) == 1) {
                long[] counts = bySession.computeIfAbsent(record.senderRef(), session -> new long[3]);
                if (record.state() == MessageState.PREPARED) {
                    drafts++;
                    counts[1]++;
                } else {
                    receipts++;
                    counts[2]++;
                }
            }
        }
        if (messages + drafts + receipts > 0) {
            bySession.forEach((session, counts) -> sessions.release(session, counts[0], counts[1], counts[2]));
            removed += messages + drafts + receipts;
        }
        return removed;
    }

    // ------------------------------------------------------------------------------------------------------------------- claim

    private List<ClaimedMessage> take(String targetRef, ClaimLimits limits, Instant now) {
        MessageRules.ClaimBudget budget = MessageRules.claimBudget(limits, properties.claimMaxMessages(), properties.claimAnswerMaxBytes());
        if (!sessions.existsBySessionRef(targetRef)) {
            return List.of();
        }
        List<ClaimedMessage> claimed = new ArrayList<>();
        long bodyChars = 0;
        for (Message row : queue.findClaimable(targetRef, now, budget.maxMessages())) {
            long previous = row.deliveryAttempts();
            String receipt = MessageRules.claimReceipt(previous + 1, ids.generateId());
            Instant firstDeliveredAt = row.firstDeliveredAt() != null ? row.firstDeliveredAt() : now;
            ClaimedMessage message = new ClaimedMessage(row.messageId(), row.senderRef(), row.senderHost(), row.targetRef(), row.body(), row.createdAt(), row.expiresAt(),
                    previous + 1, firstDeliveredAt, receipt, row.senderActorId(), row.targetActorId());
            List<ClaimedMessage> next = new ArrayList<>(claimed);
            next.add(message);
            if (bodyChars + message.body().length() > budget.maxBodyChars() || MessageRules.claimAnswerBytes(next) > properties.claimAnswerMaxBytes()) {
                if (claimed.isEmpty()) {
                    throw new InvalidInputException(InputCode.CLAIM_BUDGET_EXCEEDED);
                }
                break;
            }
            Instant leaseUntil = now.plus(MessageRules.claimLease(previous, properties.claimLeaseBase(), properties.claimLeaseMax()));
            // The statement's changed rows are the verdict: 0 rows means another claimer took this message (or it was acknowledged) since it was read, so it is theirs and this
            // claimer goes on without it. Nothing is done again for it; the receipt made above is dropped with it.
            if (queue.claim(row.messageId(), targetRef, previous, now, leaseUntil, firstDeliveredAt, receipt) == 1) {
                claimed.add(message);
                bodyChars += message.body().length();
            }
        }
        return claimed;
    }

    /**
     * A real claim boundary of the current live generation, in the claim's own transaction; without a generation nothing is written. The boundary moves by one UPDATE of the
     * generation's row; a generation that has no row yet (0 rows changed) gets it here, by an INSERT that already carries this boundary. Of two claims that make the row at the
     * same moment the primary key lets one win: the other fails with the translated {@link ConflictCode#DUPLICATE} and rolls its claim back (the message stays claimable);
     * nothing catches that or runs the claim again.
     */
    private void markClaimBoundary(String targetRef, Instant now, Generation generation) {
        if (generation == null) {
            return;
        }
        if (wakePaths.moveBoundary(targetRef, generation.instanceId(), generation.birthGeneration(), now) == 0) {
            wakePaths.save(WakePath.created(new WakePath.Key(targetRef, generation.instanceId(), generation.birthGeneration()), now));
        }
    }

    /**
     * The plain claim: the recipient takes what waits for it. One transaction, the caller's when it is called inside one (a hook's claim and its source receipt are one
     * transaction, see {@code SessionMessageService#claim}); it is not run again when it lost a message to another claimer, it takes the ones that are still free.
     */
    @Transactional
    public List<ClaimedMessage> claim(@NotBlank String targetRef, ClaimLimits limits, Generation generation) {
        Instant now = now();
        pruneAcknowledged(now);
        List<ClaimedMessage> claimed = take(targetRef, limits, now);
        markClaimBoundary(targetRef, now, generation);
        return claimed;
    }

    /** The claim at a tool boundary: the first one after a native input is skipped once (see {@link #observeNativeInput}). */
    @Transactional
    public List<ClaimedMessage> claimDeferred(@NotBlank String targetRef, ClaimLimits limits, Generation generation) {
        Instant now = now();
        pruneAcknowledged(now);
        if (settleDeferred(targetRef)) {
            return List.of();
        }
        List<ClaimedMessage> claimed = take(targetRef, limits, now);
        markClaimBoundary(targetRef, now, generation);
        return claimed;
    }

    /** The claim at the end of a turn: nothing is deferred any more. */
    @Transactional
    public List<ClaimedMessage> claimTurnEnd(@NotBlank String targetRef, ClaimLimits limits, Generation generation) {
        Instant now = now();
        pruneAcknowledged(now);
        observations.settle(targetRef);
        List<ClaimedMessage> claimed = take(targetRef, limits, now);
        markClaimBoundary(targetRef, now, generation);
        return claimed;
    }

    /** The claim of a wake input (the session was woken for its messages): it also records that an input was seen, which defers nothing. */
    @Transactional
    public List<ClaimedMessage> claimWakeInput(@NotBlank String targetRef, ClaimLimits limits, Generation generation) {
        Instant now = now();
        pruneAcknowledged(now);
        // Take the observation row before messages, as the tool and turn-end claims do.
        recordWakeInput(targetRef, now);
        List<ClaimedMessage> claimed = take(targetRef, limits, now);
        markClaimBoundary(targetRef, now, generation);
        return claimed;
    }

    /**
     * The first tool-boundary claim after a native input is skipped: the observation is settled by one conditional UPDATE, and true (one row) is the answer for the one claimer
     * that took the skip; a claimer that finds it already settled takes its turn.
     */
    private boolean settleDeferred(String targetRef) {
        return observations.settle(targetRef) == 1;
    }

    /**
     * The observation of a wake input: only the time moves (an observation that defers keeps deferring). It is one UPDATE of the session's row, whose time is a value
     * nothing reads and whose last write may win; a session that has no row yet (0 rows changed) gets it here, settled, by an INSERT. The row is never deleted afterwards, so
     * it is made once for the life of the session. Of two inputs that make it at the same moment the primary key lets one win: the other fails with the translated
     * {@link ConflictCode#DUPLICATE} and rolls its claim back; nothing catches that or runs the claim again. An unknown session is refused by the foreign key.
     */
    private void recordWakeInput(String targetRef, Instant now) {
        if (observations.seenAt(targetRef, now) == 0) {
            observations.save(new InputObservation(targetRef, ObservationState.SETTLED, now, null));
        }
    }

    /**
     * A native input was seen on the session: the next tool-boundary claim ({@link #claimDeferred}) is skipped once. One UPDATE of the session's row; a session that has no
     * row yet (0 rows changed) gets it here, already deferred, by an INSERT, in the same transaction. Of two first inputs of one session at the same moment the primary key
     * lets one win and the other fails with the translated {@link ConflictCode#DUPLICATE}; nothing catches that or runs the input again. An unknown session is refused by the
     * foreign key.
     */
    @Transactional
    public void observeNativeInput(@NotBlank String targetRef) {
        Instant now = now();
        if (observations.defer(targetRef, now) == 0) {
            observations.save(new InputObservation(targetRef, ObservationState.DEFERRED, now, null));
        }
    }

    /** Forgets the session's deferral: nothing is deferred (the row stays, settled). */
    @Transactional
    public void clearDeferred(@NotBlank String targetRef) {
        observations.settle(targetRef);
    }

    // -------------------------------------------------------------------------------------------------------------- acknowledge

    /**
     * Acknowledges the messages this session holds; a claim needs its receipt. A lapsed claim lease does not invalidate a late acknowledgement of the same holder, a
     * message another holder claimed since has another receipt and is not acknowledged. The first acknowledgement shortens the receipt of the message, never lengthens it.
     *
     * @return how many messages were acknowledged
     */
    private int acknowledge(PeerAddress target, List<String> messageIds, List<ClaimReceipt> claims) {
        Map<String, String> receipts = MessageRules.acknowledgement(messageIds, claims);
        Instant now = now();
        int count = 0;
        for (String messageId : new TreeSet<>(messageIds)) {
            if (acknowledgeHeld(target, messageId, receipts.get(messageId), now)) {
                count++;
            }
        }
        if (count > 0) {
            sessions.release(target.sessionRef(), count, 0, 0);
        }
        return count;
    }

    /**
     * Acknowledges one message when the target holds its claim under this receipt: one conditional UPDATE, whose number of changed rows says whether this call is the one that
     * acknowledged it (a second acknowledgement of the same claim, or one of a claim given again since, changes nothing and counts for nothing).
     */
    private boolean acknowledgeHeld(PeerAddress target, String messageId, String claimReceipt, Instant now) {
        if (claimReceipt == null || queue.acknowledge(messageId, target.sessionRef(), target.actorId(), claimReceipt, now) == 0) {
            return false;
        }
        // The receipt now lapses an hour after the first acknowledgement, never later than it did (the WHERE keeps a shorter one as it is).
        prepared.shortenTo(messageId, now.plus(properties.receiptRetention()));
        return true;
    }

    // --------------------------------------------------------------------------------------------------------------- read and recall

    /** The sender's view of one message, or empty for another sender's or an unknown ID (the same answer). */
    @Transactional(readOnly = true)
    public Optional<MessageStatus> status(@NotBlank String senderRef, String messageId) {
        return status(PeerAddress.session(senderRef), messageId);
    }

    @Transactional(readOnly = true)
    public Optional<MessageStatus> status(PeerAddress sender, String messageId) {
        return readStatus(sender, messageId, now());
    }

    private Optional<MessageStatus> readStatus(PeerAddress sender, String messageId, Instant now) {
        return queue.findById(messageId).filter(row -> row.sender().equals(sender))
                .map(row -> queuedStatus(messageId, row))
                .or(() -> prepared.findById(messageId)
                        .filter(record -> record.sender().equals(sender) && record.expiresAt().isAfter(now)).map(MessagingService::statusOf));
    }

    private MessageStatus queuedStatus(String messageId, Message row) {
        String targetHost = sessions.findHost(row.targetRef());
        Instant claimedAt = row.claimedAt();
        Instant acknowledgedAt = row.acknowledgedAt();
        return new MessageStatus(messageId, MessageRules.queueState(claimedAt, acknowledgedAt), row.targetRef(), targetHost, row.createdAt(), row.expiresAt(),
                claimedAt, acknowledgedAt, row.deliveryAttempts(), row.firstDeliveredAt(), null, null, null, null, row.targetActorId());
    }

    private static MessageStatus statusOf(PreparedMessage record) {
        String messageId = record.messageId();
        return switch (record.state()) {
            case PREPARED -> new MessageStatus(messageId, MessageState.PREPARED, null, null, null, record.expiresAt(), null, null, null, null, record.preparedAt(), null, null, null, null);
            case SUBMITTED, RECALLED -> new MessageStatus(messageId, record.state(), null, null, record.sentAt(), record.messageExpiresAt(), null, null, null, null, null,
                    record.state() == MessageState.RECALLED ? null : DeliveryState.UNKNOWN, record.expiresAt(), record.recalledAt(), null);
            case QUEUED, DELIVERED, ACKNOWLEDGED -> throw new IntegrityException(IntegrityCode.VALUE_NOT_ALLOWED, SennardException.args("state", record.state().name()));
        };
    }

    /** The sender takes back a sent message no recipient has received; a claim either took it first (refused) or never sees it. */
    private RecallResult recall(PeerAddress sender, String messageId) {
        Instant now = now();
        return prepared.findById(messageId)
                .filter(record -> record.sender().equals(sender) && record.expiresAt().isAfter(now))
                .map(record -> switch (record.state()) {
                    case PREPARED -> answer(sender, messageId, now, false, false, RecallRefusal.NOT_SENT);
                    case RECALLED -> answer(sender, messageId, now, true, true, null);
                    case SUBMITTED -> takeBack(sender, record, now);
                    case QUEUED, DELIVERED, ACKNOWLEDGED -> throw new IntegrityException(IntegrityCode.VALUE_NOT_ALLOWED, SennardException.args("state", record.state().name()));
                })
                .orElseGet(() -> new RecallResult(false, false, RecallRefusal.UNKNOWN, null));
    }

    /** A recall's answer with the message's status as it stands now; a message that has no status to show is answered without one. */
    private RecallResult answer(PeerAddress sender, String messageId, Instant now, boolean recalled, boolean duplicate, RecallRefusal reason) {
        return readStatus(sender, messageId, now).map(status -> new RecallResult(recalled, duplicate, reason, status)).orElseGet(() -> new RecallResult(recalled, duplicate, reason, null));
    }

    private RecallResult takeBack(PeerAddress sender, PreparedMessage record, Instant now) {
        String messageId = record.messageId();
        requireSessions(sender.sessionRef(), record.targetRef());
        // The delete is one conditional statement: it removes nothing when there is no queue row or a recipient took it first.
        if (queue.deleteUnclaimed(messageId, sender.sessionRef(), sender.actorId(), now) == 0) {
            // Another recall of this message got there first (this one waited for its commit): its result is the answer, as a duplicate.
            if (prepared.findById(messageId).filter(found -> found.state() == MessageState.RECALLED).isPresent()) {
                return answer(sender, messageId, now, true, true, null);
            }
            boolean delivered = readStatus(sender, messageId, now).map(status -> status.state().isReceived()).orElse(false);
            RecallRefusal reason = delivered ? RecallRefusal.DELIVERED : !record.messageExpiresAt().isAfter(now) ? RecallRefusal.EXPIRED : RecallRefusal.UNKNOWN;
            return answer(sender, messageId, now, false, false, reason);
        }
        Instant shorter = now.plus(properties.receiptRetention());
        // The queue row's DELETE above is the verdict (no other recall, claim or acknowledgement of this message can follow it), so the receipt is certainly still SUBMITTED here.
        if (prepared.recalled(messageId, now, shorter) == 0) {
            throw new IllegalStateException("The receipt of a message whose queue row this recall removed is not SUBMITTED: " + messageId);
        }
        sessions.release(record.targetRef(), 1, 0, 0);
        return answer(sender, messageId, now, true, false, null);
    }

    /** How many messages wait for the recipient and are not under a live claim lease. */
    @Transactional(readOnly = true)
    public long pendingCount(@NotBlank String targetRef) {
        return queue.countClaimable(targetRef, now());
    }

    // ------------------------------------------------------------------------------------------------------------------ peer wait

    /** Metadata only; a peer relation is neither work completion nor permission. */
    @Transactional(readOnly = true)
    public PeerWait peerWaitState(@NotBlank String senderRef, @NotBlank String targetRef) {
        List<Message> found = queue.findBetween(now(), senderRef, targetRef, properties.peerRelationRows());
        StringBuilder state = new StringBuilder();
        for (Message row : found) {
            state.append(row.messageId()).append('\t').append(row.senderRef()).append('\t').append(row.targetRef()).append('\t').append(Timestamps.format(row.createdAt())).append('\t')
                    .append(row.claimedAt() == null ? "" : Timestamps.format(row.claimedAt())).append('\t').append(row.acknowledgedAt() == null ? "" : Timestamps.format(row.acknowledgedAt()))
                    .append('\t').append(row.deliveryAttempts()).append('\t').append(row.firstDeliveredAt() == null ? "" : Timestamps.format(row.firstDeliveredAt())).append('\n');
        }
        return new PeerWait(!found.isEmpty(), Digests.sha256Hex(state.toString()));
    }

    /** The snapshot of a query that has not lapsed; the lapsed ones go. */
    @Transactional
    public Optional<PeerWaitRecord> getPeerWaitSnapshot(@NotBlank String queryKey) {
        snapshots.deleteByExpiresAtLessThanEqual(now());
        return snapshots.findById(queryKey)
                .map(snapshot -> new PeerWaitRecord(snapshot.ownerKey(), snapshot.fingerprint(), snapshot.expiresAt()));
    }

    /** Stores the snapshot of a query and keeps the newest {@code peerWaitSnapshotLimit} (by the time they lapse). */
    @Transactional
    public void setPeerWaitSnapshot(@NotBlank String queryKey, PeerWaitRecord record) {
        // One UPDATE of the query's row (the last write wins: it is what the policy saw last); only a query with no row yet is inserted. ceiling: two first writes of one
        // query at the same time meet the primary key (nothing in the product calls this yet; the query has no earlier moment to make its row at).
        if (snapshots.replace(queryKey, record.owner(), record.fingerprint(), record.expiresAt()) == 0) {
            snapshots.save(new PeerWaitSnapshot(queryKey, record.owner(), record.fingerprint(), record.expiresAt(), null));
        }
        List<PeerWaitSnapshot> newestFirst = snapshots.findAllByOrderByExpiresAtDesc();
        for (int index = properties.peerWaitSnapshotLimit(); index < newestFirst.size(); index++) {
            snapshots.deleteByQueryKey(newestFirst.get(index).queryKey());
        }
    }

    /** Forgets every snapshot one owner made. */
    @Transactional
    public long resetPeerWaitOwner(@NotBlank String owner) {
        return snapshots.deleteByOwnerKey(owner);
    }

}
