// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.message.SennardMessageResolvable;
import io.github.jaeseongs95.sennard.app.messaging.MessagingService;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimLimits;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimReceipt;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimedMessage;
import io.github.jaeseongs95.sennard.domain.messaging.DeliveryState;
import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.MessageStatus;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.domain.messaging.Prepared;
import io.github.jaeseongs95.sennard.domain.messaging.RecallRefusal;
import io.github.jaeseongs95.sennard.domain.messaging.RecallResult;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters.MessageQueued;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.TrustStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter of the session message tools and hooks. The queue itself (the drafts, the receipts, the claims, the limits) is {@link MessagingService} of sennard-app; this class
 * keeps what belongs to the server around it: finding a session's reference through the identity area, the source receipt of the trust area that a body is handed over
 * with, the event that rings a held wake hook, and the shape of the tools' answers (the records below; a tool's result is the record itself).
 *
 * <p>The transaction of a claim: a body is handed to a host only together with its source receipt, so the claim and the receipt are one transaction, the one of
 * {@link #claim}; {@code MessagingService} joins it, and a claim whose receipt cannot be recorded rolls back and the message stays claimable. No lock is taken: a message goes
 * to the one claimer whose conditional UPDATE changed its row, a claimer that lost a message takes nothing from it and is not run again, a receipt is one row under the UNIQUE
 * key of its (host, session, event), and the signing key it is signed with is read, not made (the start of the server makes it, {@code TrustService#createSigningKeyIfAbsent}).
 * {@code prepare} and {@code send} open no transaction here: their transaction is the service's own, and the wake signal is sent after the send returned, that is, after
 * it committed.
 */
@Service
public class SessionMessageService {
    private static final ClaimLimits HOOK_CLAIM = new ClaimLimits(1, 4096);
    private static final Attestation ATTESTATION = new Attestation("broker-peer-envelope", "session-message-hook", "1.0.0");
    /** The authority a message gives its receiver: none, on the wire as on every receipt. */
    private static final String NO_AUTHORITY = "none";
    /** The capacity refusals: they have their own sentence and details; every other refusal of a send is told as a rejection of the send. */
    private static final Set<UnavailableCode> CAPACITY = EnumSet.of(UnavailableCode.MESSAGE_PREPARATION_FULL, UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT,
            UnavailableCode.MESSAGE_RECEIPTS_FULL_NO_DRAFT, UnavailableCode.MESSAGE_SENDER_RECEIPTS_FULL, UnavailableCode.MESSAGE_RECEIPTS_FULL, UnavailableCode.MESSAGE_SPOOL_FULL);

    /** A message as the hook gives it to the model, with its receipt. */
    public record Delivered(String messageId, String senderHost, String senderSessionId, String recipientHost, String recipientSessionId, String body, String createdAt, String expiresAt,
            long deliveryAttempt, String firstDeliveredAt, String claimReceipt, String sourceReceiptId, String contentDigest, String senderActorId, String recipientActorId) {
        public Delivered(String messageId, String senderHost, String senderSessionId, String recipientHost, String recipientSessionId, String body, String createdAt, String expiresAt,
                long deliveryAttempt, String firstDeliveredAt, String claimReceipt, String sourceReceiptId, String contentDigest) {
            this(messageId, senderHost, senderSessionId, recipientHost, recipientSessionId, body, createdAt, expiresAt, deliveryAttempt, firstDeliveredAt, claimReceipt, sourceReceiptId,
                    contentDigest, null, null);
        }
    }

    /** The answer of {@code prepare_session_message}. */
    public record PreparedView(String messageId, String preparedAt, String expiresAt) {
    }

    /** The answer of {@code send_session_message}; {@code duplicate} is on the wire only when true. */
    public record SendResult(String messageId, String createdAt, String expiresAt, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean duplicate, AutoWake autoWake) {
    }

    /** The answer of {@code acknowledge_session_messages}. */
    public record AcknowledgeResult(int acknowledged) {
    }

    /** The answer of {@code get_session_message_status}; the guidance is on the wire only when the message is unknown. */
    public record StatusResult(StatusView status, @JsonInclude(JsonInclude.Include.NON_NULL) String guidance, @JsonInclude(JsonInclude.Include.NON_NULL) String guidanceKey,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> guidanceParams) {
    }

    /** The answer of {@code recall_session_message}; {@code duplicate} is on the wire only when true, {@code reason} only when nothing was taken back. */
    public record RecallView(boolean recalled, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean duplicate, @JsonInclude(JsonInclude.Include.NON_NULL) RecallRefusal reason,
            StatusView status) {
    }

    /** The sender's view of one message; which fields it has depends on how far the message got. */
    public sealed interface StatusView {
        /** A draft that was not sent. */
        record Draft(String messageId, MessageState state, String preparedAt, String expiresAt) implements StatusView {
        }

        /** A sent message whose queue row is gone, or that was recalled. */
        record Submission(String messageId, String createdAt, String expiresAt, @JsonInclude(JsonInclude.Include.NON_NULL) String recalledAt, MessageState state,
                @JsonInclude(JsonInclude.Include.NON_NULL) DeliveryState deliveryState, String receiptExpiresAt) implements StatusView {
        }

        /**
         * A message that is in the queue, was claimed, or was acknowledged.
         *
         * @param wake always null: the wire has the key, and nothing reads a wake outcome here
         */
        record InQueue(String messageId, TargetView target, String createdAt, String expiresAt, String claimedAt, String acknowledgedAt, Long deliveryAttempts, String firstDeliveredAt,
                MessageState state, Object wake, AutoWake autoWake) implements StatusView {
        }
    }

    /** The recipient as the sender sees it. */
    public record TargetView(String host, String sessionId, @JsonInclude(JsonInclude.Include.NON_NULL) String actorId) {
        public TargetView(String host, String sessionId) {
            this(host, sessionId, null);
        }
    }

    /** The advisory outlook of the recipient's idle wake; never delivery, completion or permission evidence. */
    public record AutoWake(WakeOutlook.State state, WakeOutlook.Reason reason, String basisAt, String checkedAt, String authorityEffect) {
    }

    /** Which claim boundary a hook stands at. */
    public enum Boundary {
        TOOL, TURN_END, WAKE
    }

    /** What the attestation of a source receipt says about who made it. */
    public record Attestation(String kind, String adapter, String capabilityVersion) {
    }

    /**
     * The receipt of the source of a peer message, in the order of its stored text. The integrity token is made over the canonical form of the receipt without it, and is
     * absent (null) until then.
     */
    public record SourceReceipt(String schemaVersion, String receiptId, String originKind, String host, String sessionId, String eventId, String contentDigest, String observedAt,
            String expiresAt, String authorityEffect, Attestation attestation, @JsonInclude(JsonInclude.Include.NON_NULL) String integrityToken,
            @JsonInclude(JsonInclude.Include.NON_NULL) String senderActorId, @JsonInclude(JsonInclude.Include.NON_NULL) String recipientActorId) {
        public SourceReceipt(String schemaVersion, String receiptId, String originKind, String host, String sessionId, String eventId, String contentDigest, String observedAt,
                String expiresAt, String authorityEffect, Attestation attestation, String integrityToken) {
            this(schemaVersion, receiptId, originKind, host, sessionId, eventId, contentDigest, observedAt, expiresAt, authorityEffect, attestation, integrityToken, null, null);
        }

        SourceReceipt sealed(String token) {
            return new SourceReceipt(schemaVersion, receiptId, originKind, host, sessionId, eventId, contentDigest, observedAt, expiresAt, authorityEffect, attestation, token,
                    senderActorId, recipientActorId);
        }
    }

    /**
     * What is read back of a stored receipt: the fields that say what it is about. The attestation stays a tree because it is compared in its canonical form, whatever it holds.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredReceipt(String receiptId, String originKind, String authorityEffect, String contentDigest, JsonNode attestation, String senderActorId, String recipientActorId) {
    }

    private final MessagingService messaging;
    private final TrustStore trust;
    private final SessionRegistry sessions;
    private final ApplicationEventPublisher events;
    private final WakeWaiters waiters;
    private final IdleWakeService idleWake;
    private final WakeReservationService wakeReservations;
    private final MessageSource messages;
    private final JsonMapper mapper;
    private final Clock clock;
    /** The canonical form of the attestation every receipt of this server carries; it does not change, so it is made once. */
    private final String attestationText;

    public SessionMessageService(MessagingService messaging, TrustStore trust, SessionRegistry sessions, ApplicationEventPublisher events, WakeWaiters waiters,
            MessageSource messages, JsonMapper mapper, Clock clock, IdleWakeService idleWake, WakeReservationService wakeReservations) {
        this.messaging = messaging;
        this.trust = trust;
        this.sessions = sessions;
        this.events = events;
        this.waiters = waiters;
        this.idleWake = idleWake;
        this.wakeReservations = wakeReservations;
        this.messages = messages;
        this.mapper = mapper;
        this.clock = clock;
        this.attestationText = CanonicalJson.of(mapper.valueToTree(ATTESTATION));
    }

    public PreparedView prepare(String senderRef, String targetHost, String targetSessionId, String body, Integer ttlSeconds, OperationKey operation) {
        return prepare(PeerAddress.session(senderRef), targetHost, targetSessionId, null, body, ttlSeconds, operation);
    }

    public PreparedView prepare(PeerAddress sender, String targetHost, String targetSessionId, String targetActorId, String body, Integer ttlSeconds, OperationKey operation) {
        if (targetActorId != null) {
            throw new BindingException(BindingCode.PEER_MAIN_SESSION_REQUIRED);
        }
        String targetRef = sessions.findRef(targetHost, targetSessionId).orElseThrow(() -> new NotFoundException(NotFoundCode.SESSION_NOT_FOUND));
        PeerAddress target = PeerAddress.session(targetRef);
        requireMainPeerTarget(sender, target);
        Prepared prepared = messaging.prepare(sender, target, body, ttlSeconds, operation);
        return new PreparedView(prepared.messageId(), Timestamps.format(prepared.preparedAt()), Timestamps.format(prepared.expiresAt()));
    }

    public SendResult send(String senderRef, String senderHost, String messageId, OperationKey operation) {
        return send(PeerAddress.session(senderRef), senderHost, messageId, operation);
    }

    public SendResult send(PeerAddress sender, String senderHost, String messageId, OperationKey operation) {
        Receipted<SendResult> receipted;
        try {
            // The answer, the advisory autoWake in it, is made inside the transaction of the send and kept in the receipt, so a request that comes again with its key is answered with the first answer as it was given.
            receipted = messaging.send(sender, senderHost, messageId, operation, SendResult.class, sent -> sendAnswer(sender, sent),
                    target -> requireMainPeerTarget(sender, target));
        } catch (SennardException | DataAccessException | TransactionException failure) {
            throw refusal(failure);
        }
        // The wake rings for the send this call made, once: a request that comes again with its key is answered with the first send, whose wake rang when it was made.
        if (receipted instanceof Receipted.Fresh<SendResult> && receipted.value().duplicate() == null) {
            messaging.status(sender, messageId).flatMap(this::target)
                    .ifPresent(found -> events.publishEvent(new MessageQueued(found.host(), found.sessionId(), found.actorId())));
        }
        return receipted.value();
    }

    private SendResult sendAnswer(PeerAddress sender, Sent sent) {
        Optional<Target> target = messaging.status(sender, sent.messageId()).flatMap(this::target);
        return new SendResult(sent.messageId(), Timestamps.format(sent.createdAt()), Timestamps.format(sent.expiresAt()), sent.duplicate() ? Boolean.TRUE : null,
                target.map(this::autoWake).orElseGet(this::noTargetWake));
    }

    private void requireMainPeerTarget(PeerAddress sender, PeerAddress target) {
        if (!sender.actorId().isEmpty() || !target.actorId().isEmpty() || sender.sessionRef().equals(target.sessionRef())
                || !sessions.isRegisteredMain(sender.sessionRef()) || !sessions.isRegisteredMain(target.sessionRef())) {
            throw new BindingException(BindingCode.PEER_MAIN_SESSION_REQUIRED);
        }
    }

    /**
     * What the sender is told when a send did not happen: a capacity refusal as it is; the conflict of the operationKey as it is; a refusal of the queue's rules as {@code SEND_REJECTED} with the reason's sentence
     * (the sender retries only the known ID); a failure of the store or anything unforeseen as {@code SEND_FAILED} (the outcome may be unknown).
     */
    private RuntimeException refusal(RuntimeException failure) {
        return switch (failure) {
            case BindingException binding when binding.code() == BindingCode.PEER_MAIN_SESSION_REQUIRED -> binding;
            case UnavailableException capacity when CAPACITY.contains(capacity.code()) -> capacity;
            // The conflicts of the operationKey are the caller's to see as they are (REQUEST_CONFLICT with the reason, the second one retryable), not a rejection of the send by the queue.
            case ConflictException keyConflict when keyConflict.isOperationKey() -> keyConflict;
            case UnavailableException store -> new UnavailableException(UnavailableCode.SEND_FAILED, Map.of(), SennardException.args("reason", store.code().name(), "cause", causeName(store)), store);
            case SennardException rejected -> new UnavailableException(UnavailableCode.SEND_REJECTED,
                    SennardException.args("reason", messages.getMessage(SennardMessageResolvable.of(rejected), Locale.ENGLISH)), Map.of(), rejected);
            default -> new UnavailableException(UnavailableCode.SEND_FAILED, Map.of(), SennardException.args("reason", "INTERNAL_FAILURE", "cause", failure.getClass().getSimpleName()), failure);
        };
    }

    private static String causeName(Throwable failure) {
        return (failure.getCause() == null ? failure : failure.getCause()).getClass().getSimpleName();
    }

    public AcknowledgeResult acknowledge(String targetRef, List<String> messageIds, List<ClaimReceipt> receipts, OperationKey operation) {
        return acknowledge(PeerAddress.session(targetRef), messageIds, receipts, operation);
    }

    public AcknowledgeResult acknowledge(PeerAddress target, List<String> messageIds, List<ClaimReceipt> receipts, OperationKey operation) {
        int acknowledged = messaging.acknowledge(target, messageIds, receipts, operation, count -> {
            if (count > 0 && target.actorId().isEmpty()) {
                wakeReservations.bodyObserved(target.sessionRef());
            }
        });
        return new AcknowledgeResult(acknowledged);
    }

    public StatusResult status(String senderRef, String messageId) {
        return status(PeerAddress.session(senderRef), messageId);
    }

    public StatusResult status(PeerAddress sender, String messageId) {
        return messaging.status(sender, messageId).map(status -> new StatusResult(view(status), null, null, null)).orElseGet(() -> {
            String key = "services.sessionMessage.status.deliveryUnknown";
            return new StatusResult(null, messages.getMessage(key, null, Locale.ENGLISH), key, Map.of());
        });
    }

    public RecallView recall(String senderRef, String messageId, OperationKey operation) {
        return recall(PeerAddress.session(senderRef), messageId, operation);
    }

    public RecallView recall(PeerAddress sender, String messageId, OperationKey operation) {
        // As the send: the receipt keeps the result with the outlook that was told with it (a refused recall of a message in the queue shows the status with its autoWake).
        RecallAnswer answer = messaging.recall(sender, messageId, operation, RecallAnswer.class,
                result -> new RecallAnswer(result, result.status() == null ? null : queueOutlookOf(result.status())));
        RecallResult recall = answer.result();
        return new RecallView(recall.recalled(), recall.duplicate() ? Boolean.TRUE : null, recall.reason(), view(recall.status(), answer.outlook()));
    }

    /** What a recall's receipt keeps: the result, and the outlook of the status in it as it was told (null when the status is not of a message in the queue). */
    public record RecallAnswer(RecallResult result, QueueOutlook outlook) {
    }

    /**
     * What a message in the queue shows of its recipient: the target as the session's identity names it, and the advisory autoWake (null once acknowledged). A recall keeps it in its receipt, so a request
     * that comes again is answered with the target as it was (the identity of a session whose row lapsed in the retention is not read again) and not with one made from what has changed since.
     */
    public record QueueOutlook(TargetView target, AutoWake autoWake) {
    }

    /** The outlook of a message status as it is now; only a message that is in the queue has one. */
    private QueueOutlook queueOutlookOf(MessageStatus status) {
        return switch (status.state()) {
            case QUEUED, DELIVERED, ACKNOWLEDGED -> {
                Optional<Target> target = target(status);
                String actorId = actorIdOrNull(status.targetActorId());
                TargetView targetView = target.map(resolved -> new TargetView(status.targetHost(), resolved.sessionId(), actorId))
                        .orElseGet(() -> new TargetView(status.targetHost(), null, actorId));
                yield new QueueOutlook(targetView,
                        status.acknowledgedAt() == null ? target.map(this::autoWake).orElseGet(this::noTargetWake) : null);
            }
            case PREPARED, SUBMITTED, RECALLED -> null;
        };
    }

    /** A native input was seen on the session: the deferral and, for the session's first input, its observation row are one use case of the queue ({@link MessagingService#observeNativeInput}). */
    public void observeNativeInput(String targetRef) {
        messaging.observeNativeInput(targetRef);
    }

    public void clearDeferred(String targetRef) {
        messaging.clearDeferred(targetRef);
    }

    /**
     * Takes what waits for the session at a claim boundary and records its source receipts, in one transaction. It takes no lock: a claimer that loses a message to another
     * (its conditional UPDATE changes no row) takes nothing from that message and is not run again; with nothing to take, what the boundary does besides taking (the pruning,
     * the deferral after a native input) is all that is written. The receipt's rows are one INSERT under the UNIQUE key, and a failure of any of it rolls the claim back with it.
     */
    @Transactional
    public List<Delivered> claim(String host, String sessionId, String targetRef, Boundary boundary) {
        List<Delivered> delivered = takeWithReceipts(host, sessionId, targetRef, boundary);
        if (!delivered.isEmpty()) {
            wakeReservations.bodyObserved(targetRef);
        }
        return delivered;
    }

    /** Messages and source receipts precede the reservation CAS, the same lock order as ordinary claims and acknowledgements. */
    @Transactional
    public List<Delivered> claimWakeInput(HostContext context, String nonce) {
        Optional<IdleWakeService.Input> verified = idleWake.verify(context, nonce);
        if (verified.isEmpty()) {
            return List.of();
        }
        IdleWakeService.Input target = verified.orElseThrow();
        List<Delivered> delivered = target.address().actorId().isEmpty()
                ? takeWithReceipts(target.host(), target.sessionId(), target.address().sessionRef(), Boundary.WAKE)
                : List.of();
        if (idleWake.consume(context, nonce).filter(target::equals).isEmpty()) {
            throw new ConflictException(ConflictCode.NATIVE_WAKE_CLAIM_CHANGED);
        }
        return delivered;
    }

    private List<Delivered> takeWithReceipts(String host, String sessionId, String targetRef, Boundary boundary) {
        // ceiling: no generation is passed, so the wake path's claim boundary is not recorded; only the wake outlook of a server-run wake would read it.
        List<ClaimedMessage> claimed = switch (boundary) {
            case TOOL -> messaging.claimDeferred(targetRef, HOOK_CLAIM, null);
            case TURN_END -> messaging.claimTurnEnd(targetRef, HOOK_CLAIM, null);
            case WAKE -> messaging.claimWakeInput(targetRef, HOOK_CLAIM, null);
        };
        List<Delivered> delivered = new ArrayList<>();
        for (ClaimedMessage message : claimed) {
            delivered.add(withReceipt(host, sessionId, message));
        }
        return delivered;
    }

    private Delivered withReceipt(String host, String sessionId, ClaimedMessage message) {
        String contentDigest = "sha256:" + Digests.sha256Hex(message.body());
        String now = Timestamps.format(clock.instant());
        String expiresAt = Timestamps.format(message.expiresAt());
        String senderActorId = actorIdOrNull(message.senderActorId());
        String recipientActorId = actorIdOrNull(message.recipientActorId());
        SourceReceipt unsigned = new SourceReceipt("1.0.0", "source-" + UUID.randomUUID(), "peer", host, sessionId, message.messageId(), contentDigest, now, expiresAt, NO_AUTHORITY, ATTESTATION,
                null, senderActorId, recipientActorId);
        // The one place the receipt becomes a tree: the token is made over its canonical form.
        String token = CanonicalJson.integrityToken(mapper.valueToTree(unsigned), trust.requireSigningKey());
        // A receipt already stored for this message (a redelivery) is read once, by the check that accepts it, and says which receipt the body goes with.
        AtomicReference<StoredReceipt> earlier = new AtomicReference<>();
        trust.recordInputSource(new TrustStore.Receipt(unsigned.receiptId(), host, sessionId, message.messageId(), unsigned.originKind(), unsigned.authorityEffect(), now, expiresAt,
                mapper.writeValueAsString(unsigned.sealed(token))), prior -> {
                    StoredReceipt stored = mapper.readValue(prior, StoredReceipt.class);
                    earlier.set(stored);
                    return sameSecurityMetadata(stored, unsigned);
                });
        String sourceReceiptId = earlier.get() == null ? unsigned.receiptId() : earlier.get().receiptId();
        String senderSession = sessions.identityOf(message.senderRef()).flatMap(SessionRegistry.RefIdentity::hostSession).orElse(message.senderRef());
        return new Delivered(message.messageId(), message.senderHost(), senderSession, host, sessionId, message.body(), Timestamps.format(message.createdAt()), expiresAt, message.deliveryAttempt(),
                message.firstDeliveredAt() == null ? null : Timestamps.format(message.firstDeliveredAt()), message.claimReceipt(), sourceReceiptId, contentDigest, senderActorId,
                recipientActorId);
    }

    /** The stored receipt of this message says the same about its content, origin and authority as the one being recorded now. */
    private boolean sameSecurityMetadata(StoredReceipt prior, SourceReceipt recorded) {
        return recorded.contentDigest().equals(prior.contentDigest()) && recorded.originKind().equals(prior.originKind()) && recorded.authorityEffect().equals(prior.authorityEffect())
                && Objects.equals(recorded.senderActorId(), prior.senderActorId()) && Objects.equals(recorded.recipientActorId(), prior.recipientActorId())
                && prior.attestation() != null && attestationText.equals(CanonicalJson.of(prior.attestation()));
    }

    private static String actorIdOrNull(String actorId) {
        return actorId == null || actorId.isEmpty() ? null : actorId;
    }

    private record Target(String host, String sessionId, String actorId) {
    }

    /** The host and the session id the target of a message is known by, when it has them (a session the server minted has no host session id). */
    private Optional<Target> target(MessageStatus status) {
        if (status.targetRef() == null) {
            return Optional.empty();
        }
        return sessions.identityOf(status.targetRef()).flatMap(identity -> identity.hostSession().map(id -> new Target(identity.host(), id, actorIdOrNull(status.targetActorId()))));
    }

    private boolean waiting(Target target) {
        return waiters.isWaiting(target.host(), target.sessionId());
    }

    private static String wire(Instant instant) {
        return instant == null ? null : Timestamps.format(instant);
    }

    private StatusView view(MessageStatus status) {
        return status == null ? null : view(status, queueOutlookOf(status));
    }

    /** The view of a status; {@code outlook} is the target and the autoWake of a message in the queue (as it is now for a read, as it was told for a stored answer; unused for another state). */
    private StatusView view(MessageStatus status, QueueOutlook outlook) {
        if (status == null) {
            return null;
        }
        return switch (status.state()) {
            case PREPARED -> new StatusView.Draft(status.messageId(), status.state(), wire(status.preparedAt()), wire(status.expiresAt()));
            case SUBMITTED, RECALLED -> new StatusView.Submission(status.messageId(), wire(status.createdAt()), wire(status.expiresAt()), wire(status.recalledAt()), status.state(),
                    status.deliveryState(), wire(status.receiptExpiresAt()));
            case QUEUED, DELIVERED, ACKNOWLEDGED -> {
                yield new StatusView.InQueue(status.messageId(), outlook.target(), wire(status.createdAt()),
                        wire(status.expiresAt()), wire(status.claimedAt()), wire(status.acknowledgedAt()), status.deliveryAttempts(), wire(status.firstDeliveredAt()), status.state(), null,
                        outlook.autoWake());
            }
        };
    }

    /** Advisory only: whether a wake hook of the target is being held now. */
    private AutoWake autoWake(Target target) {
        if (target.actorId() != null || sessions.isChild(target.host(), target.sessionId())) {
            return noTargetWake();
        }
        String now = Timestamps.format(clock.instant());
        boolean waiting = waiting(target);
        if (!waiting) {
            Optional<IdleWakeService.Advisory> nativeWake = idleWake.outlook(target.host(), target.sessionId());
            if (nativeWake.isPresent()) {
                IdleWakeService.Advisory advisory = nativeWake.orElseThrow();
                return new AutoWake(advisory.state(), advisory.reason(), now, now, NO_AUTHORITY);
            }
        }
        return new AutoWake(waiting ? WakeOutlook.State.AVAILABLE : WakeOutlook.State.NO_LIVE_RELAY, waiting ? WakeOutlook.Reason.RELAY_LIVE : WakeOutlook.Reason.PRESENCE_UNKNOWN,
                waiting ? now : null, now, NO_AUTHORITY);
    }

    private AutoWake noTargetWake() {
        String now = Timestamps.format(clock.instant());
        return new AutoWake(WakeOutlook.State.NO_LIVE_RELAY, WakeOutlook.Reason.PRESENCE_UNKNOWN, null, now, NO_AUTHORITY);
    }
}
