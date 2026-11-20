// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * What a session message may be, as pure judgements (no database, no clock, no framework): the body, the time to live, the shape of an ID and of a claim
 * receipt, what an acknowledgement and a claim may ask for. A violation is an {@link InvalidInputException} with the code of the rule; the limits come from
 * the caller (the application's settings), the shapes are fixed by the public contract (contracts/*-session-message*.schema.json).
 */
public final class MessageRules {
    /** The shortest time to live a message may ask for (the contract's {@code ttlSeconds} minimum). */
    public static final Duration MIN_TTL = Duration.ofSeconds(30);
    /** An acknowledgement names 1 to this many messages, each ID at most {@link #MAX_ACK_ID_LENGTH} characters (the contract's {@code messageIds}). */
    public static final int MAX_ACK_MESSAGE_IDS = 50;
    public static final int MAX_ACK_ID_LENGTH = 128;
    /** At most this many claim receipts come with an acknowledgement (the contract's {@code receipts}). */
    public static final int MAX_ACK_CLAIMS = 50;
    /** The bytes a draft or a receipt counts for besides its text: a fixed share for the row itself (ID, times, state), so the limit is on records and not only on text. */
    public static final int RECORD_OVERHEAD_BYTES = 160;

    private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");
    private static final Pattern CLAIM_RECEIPT = Pattern.compile("^[1-9][0-9]{0,15}:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    /** The doubling of a claim lease stops after this many redeliveries. */
    private static final int LEASE_DOUBLINGS = 4;
    /** The envelope around the messages of a claim answer: {@code {"ok":true,"data":{"messages":[]}}} and a line break. */
    private static final int ANSWER_FRAME_BYTES = "{\"ok\":true,\"data\":{\"messages\":[]}}".length() + 1;

    private MessageRules() {
    }

    /** What a claim may take, resolved against the defaults: at most {@code maxMessages} messages whose bodies together have at most {@code maxBodyChars} UTF-16 units. */
    public record ClaimBudget(int maxMessages, int maxBodyChars) {
    }

    // ------------------------------------------------------------------------------------------------------------------ the message

    public static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The body: not blank (what {@link String#isBlank()} says: empty, or only Java whitespace), at most {@code maxBytes} UTF-8 bytes, and no NUL character. */
    public static void body(String body, int maxBytes) {
        if (body == null || body.isBlank() || utf8Bytes(body) > maxBytes) {
            throw new InvalidInputException(InputCode.MESSAGE_BODY_SIZE, SennardException.args("maxBytes", maxBytes));
        }
        if (body.indexOf('\0') >= 0) {
            throw new InvalidInputException(InputCode.MESSAGE_BODY_NUL);
        }
    }

    /**
     * The time to live: the default when none was asked for, else from {@link #MIN_TTL} to {@code max}. The request carries whole seconds (the contract's {@code ttlSeconds}),
     * and the refusal names the maximum in seconds ({@code maxSeconds}), as the contract does.
     */
    public static Duration ttl(Integer requestedSeconds, Duration defaultTtl, Duration max) {
        Duration ttl = requestedSeconds == null ? defaultTtl : Duration.ofSeconds(requestedSeconds);
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(max) > 0) {
            throw new InvalidInputException(InputCode.MESSAGE_TTL_RANGE, SennardException.args("maxSeconds", Math.toIntExact(max.toSeconds())));
        }
        return ttl;
    }

    /** A message ID: 8 to 128 characters, letters and digits to begin with, then also {@code . _ : -}. */
    public static void messageId(String id) {
        if (id == null || !MESSAGE_ID.matcher(id).matches()) {
            throw new InvalidInputException(InputCode.MESSAGE_ID_SHAPE);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ acknowledge

    /** A claim receipt as the claim issues it: {@code <attempt>:<random UUID>}. */
    public static boolean isClaimReceipt(String receipt) {
        return receipt != null && CLAIM_RECEIPT.matcher(receipt).matches();
    }

    /** The receipt of a claim: the delivery attempt it belongs to, and a random UUID the claimer cannot guess. */
    public static String claimReceipt(long deliveryAttempt, UUID random) {
        return deliveryAttempt + ":" + random;
    }

    /**
     * An acknowledgement's own shape: 1 to 50 bounded IDs and at most 50 claim receipts, each receipt for one of the IDs, of the issued shape, one per ID.
     *
     * @return the receipt of each message that came with one, by message ID
     */
    public static Map<String, String> acknowledgement(List<String> messageIds, List<ClaimReceipt> claims) {
        if (messageIds == null || messageIds.isEmpty() || messageIds.size() > MAX_ACK_MESSAGE_IDS || messageIds.stream().anyMatch(id -> id == null || id.length() > MAX_ACK_ID_LENGTH)) {
            throw new InvalidInputException(InputCode.ACK_MESSAGE_IDS_RANGE);
        }
        List<ClaimReceipt> given = claims == null ? List.of() : claims;
        if (given.size() > MAX_ACK_CLAIMS) {
            throw new InvalidInputException(InputCode.ACK_CLAIMS_RANGE);
        }
        Map<String, String> receipts = new HashMap<>();
        for (ClaimReceipt claim : given) {
            if (claim == null || !messageIds.contains(claim.messageId()) || !isClaimReceipt(claim.claimReceipt()) || receipts.containsKey(claim.messageId())) {
                throw new InvalidInputException(InputCode.ACK_CLAIM_RECEIPT_INVALID);
            }
            receipts.put(claim.messageId(), claim.claimReceipt());
        }
        return receipts;
    }

    // ---------------------------------------------------------------------------------------------------------------- claim

    /**
     * What a claim asks for, resolved: both fields default to the most the settings allow, and an explicit value must be from 1 to that.
     *
     * @param maxMessages the most messages one claim may take (setting)
     * @param maxResponseBytes the most bytes one claim answer may have, and the default for {@code maxBodyChars} (setting)
     */
    public static ClaimBudget claimBudget(ClaimLimits requested, int maxMessages, int maxResponseBytes) {
        ClaimLimits limits = requested == null ? ClaimLimits.DEFAULTS : requested;
        int messages = limits.maxMessages() == null ? maxMessages : limits.maxMessages();
        int bodyChars = limits.maxBodyChars() == null ? maxResponseBytes : limits.maxBodyChars();
        if (messages < 1 || messages > maxMessages || bodyChars < 1 || bodyChars > maxResponseBytes) {
            throw new InvalidInputException(InputCode.CLAIM_LIMIT_RANGE, SennardException.args("maxMessages", maxMessages, "maxBodyChars", maxResponseBytes));
        }
        return new ClaimBudget(messages, bodyChars);
    }

    /** The lease of a claim: {@code base} doubled for each earlier delivery attempt, up to four doublings, and never more than {@code max}. */
    public static Duration claimLease(long previousAttempts, Duration base, Duration max) {
        Duration lease = base.multipliedBy(1L << (int) Math.min(previousAttempts, LEASE_DOUBLINGS));
        return lease.compareTo(max) > 0 ? max : lease;
    }

    /**
     * What the answer to a claim takes, as JSON text, in UTF-8 bytes: the frame, and for each message its fields (the ID, the two session references, the host, the
     * body, the two times as the tools print them, the attempt, the first delivery, the receipt). Strings are counted as a JSON writer writes them: quotes and backslash
     * and control characters escaped, every other character as its UTF-8 bytes.
     */
    public static int claimAnswerBytes(List<ClaimedMessage> messages) {
        int bytes = ANSWER_FRAME_BYTES + Math.max(0, messages.size() - 1); // the commas between the messages
        for (ClaimedMessage message : messages) {
            bytes += "{\"messageId\":,\"senderRef\":,\"senderHost\":,\"recipientRef\":,\"body\":,\"createdAt\":,\"expiresAt\":,\"deliveryAttempt\":,\"firstDeliveredAt\":,\"claimReceipt\":}".length()
                    + json(message.messageId()) + json(message.senderRef()) + json(message.senderHost()) + json(message.recipientRef()) + json(message.body())
                    + json(Timestamps.format(message.createdAt())) + json(Timestamps.format(message.expiresAt())) + Long.toString(message.deliveryAttempt()).length()
                    + (message.firstDeliveredAt() == null ? "null".length() : json(Timestamps.format(message.firstDeliveredAt()))) + json(message.claimReceipt());
            if (!message.senderActorId().isEmpty()) {
                bytes += ",\"senderActorId\":".length() + json(message.senderActorId());
            }
            if (!message.recipientActorId().isEmpty()) {
                bytes += ",\"recipientActorId\":".length() + json(message.recipientActorId());
            }
        }
        return bytes;
    }

    private static int json(String text) {
        int bytes = 2;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '"' || c == '\\' || c == '\b' || c == '\t' || c == '\n' || c == '\f' || c == '\r') {
                bytes += 2;
            } else if (c < 0x20) {
                bytes += 6;
            } else if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    // ------------------------------------------------------------------------------------------------------- what a record counts

    /**
     * What a draft or a receipt counts against the record limit: {@link #RECORD_OVERHEAD_BYTES} for the row and the UTF-8 bytes of its ID, the two session
     * references and its body ({@code null} once the message is sent: only the receipt is kept). The Java side states it; the database only stores the number.
     */
    public static long preparedRecordBytes(String messageId, String senderRef, String targetRef, String bodyOrNull) {
        return RECORD_OVERHEAD_BYTES + utf8Bytes(messageId) + utf8Bytes(senderRef) + utf8Bytes(targetRef) + (bodyOrNull == null ? 0 : utf8Bytes(bodyOrNull));
    }

    public static long preparedRecordBytes(String messageId, PeerAddress sender, PeerAddress target, String bodyOrNull) {
        return preparedRecordBytes(messageId, sender.sessionRef(), target.sessionRef(), bodyOrNull) + utf8Bytes(sender.actorId()) + utf8Bytes(target.actorId());
    }

    /** The sender's view of a queue row, from its own times: acknowledged, else claimed (delivered), else waiting. */
    public static MessageState queueState(Instant claimedAt, Instant acknowledgedAt) {
        return acknowledgedAt != null ? MessageState.ACKNOWLEDGED : claimedAt != null ? MessageState.DELIVERED : MessageState.QUEUED;
    }
}
