// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * The rules of a session message as pure judgements (no database, no framework). Cases (each a violation is an {@link InvalidInputException} with the rule's code):
 *
 * <pre>
 * b1  blank        what String.isBlank says: empty or only Java whitespace (space, tab, line breaks, line and paragraph separators, the Unicode space separators that are not no-break,
 *                  the four information separators); the no-break spaces, the byte order mark and a zero width space are not whitespace to Java
 * b2  body         4096 bytes exactly passes (counted in UTF-8 bytes, not characters), 4097 is MESSAGE_BODY_SIZE with maxBytes, blank is MESSAGE_BODY_SIZE, NUL is MESSAGE_BODY_NUL,
 *                  a body that is both too long and has NUL is the size
 * t1  ttl          none takes the default; 30 and the maximum pass; 29 and above the maximum are MESSAGE_TTL_RANGE with maxSeconds
 * m1  message ID   8 to 128 characters, alphanumeric first, then . _ : -; everything else is MESSAGE_ID_SHAPE
 * r1  claim receipt  attempt (no leading zero, at most 16 digits) colon version-4 UUID
 * k1  acknowledgement  1 to 50 IDs each at most 128 characters; at most 50 receipts; a receipt for an ID that is asked for, of the issued shape, once
 * g1  claim budget defaults to the settings' maximum, an explicit value is 1 to the maximum, else CLAIM_LIMIT_RANGE
 * l1  claim lease  doubles for each redelivery up to four times and never exceeds the cap
 * n1  answer bytes the size of a claim answer is the JSON text's UTF-8 length (escapes counted), with the frame and the commas
 * s1  record bytes a draft and a receipt count the row's overhead and the UTF-8 bytes of the ID, the references and the body (a receipt has none)
 * q1  queue state  acknowledged, else claimed, else queued
 * </pre>
 */
class MessageRulesTest {
    private static InputCode codeOf(Runnable rule) {
        return assertThrows(InvalidInputException.class, rule::run).code() instanceof InputCode code ? code : null;
    }

    /** A body is blank when {@link String#isBlank()} says so: the standard, where the contract once followed a JavaScript trim. */
    @Test
    void aBodyIsBlankWhenStringIsBlankSaysSo() {
        for (String blank : List.of("", " ", "\t\n\u000B\f\r", " ", " ", "　", " ", "\u001C\u001D\u001E\u001F", " 　\t ")) {
            assertCode(InputCode.MESSAGE_BODY_SIZE, blank);
        }
        for (String text : List.of("a", " a ", "​", "\u0085", "가")) {
            assertDoesNotThrow(() -> MessageRules.body(text, 4096), text);
        }
    }

    /**
     * What differs from the JavaScript trim the contract once copied, in both directions: a body of only no-break spaces, a byte order mark or a narrow no-break space is a body
     * now (Java does not call them whitespace), and a body of only the four information separators is blank now.
     */
    @Test
    void theInputsThatMeantSomethingElseUnderAJavaScriptTrimAreNowWhatStringIsBlankSays() {
        for (String text : List.of(" ", "﻿", " ", " ", "   ﻿\t ")) {
            assertDoesNotThrow(() -> MessageRules.body(text, 4096), "a body now: " + text.length() + " chars");
        }
        for (String text : List.of("\u001C", "\u001D", "\u001E", "\u001F")) {
            assertCode(InputCode.MESSAGE_BODY_SIZE, text);
        }
    }

    private static void assertCode(InputCode expected, String body) {
        assertEquals(expected, codeOf(() -> MessageRules.body(body, 4096)), "blank: " + body.length() + " chars");
    }

    @Test
    void theBodyIsCountedInUtf8BytesAndRefusedWithItsCode() {
        assertDoesNotThrow(() -> MessageRules.body("x".repeat(4096), 4096));
        assertDoesNotThrow(() -> MessageRules.body("가".repeat(1365), 4096)); // 4095 bytes
        assertDoesNotThrow(() -> MessageRules.body("​", 4096));
        InvalidInputException big = assertThrows(InvalidInputException.class, () -> MessageRules.body("x".repeat(4097), 4096));
        assertEquals(InputCode.MESSAGE_BODY_SIZE, big.code());
        assertEquals(4096, big.arguments().get("maxBytes"));
        assertEquals(InputCode.MESSAGE_BODY_SIZE, codeOf(() -> MessageRules.body("가".repeat(1366), 4096)), "4098 bytes in 1366 characters");
        assertEquals(InputCode.MESSAGE_BODY_SIZE, codeOf(() -> MessageRules.body("  \t", 4096)));
        assertEquals(InputCode.MESSAGE_BODY_SIZE, codeOf(() -> MessageRules.body(null, 4096)));
        assertEquals(InputCode.MESSAGE_BODY_NUL, codeOf(() -> MessageRules.body("a\0b", 4096)));
        assertEquals(InputCode.MESSAGE_BODY_SIZE, codeOf(() -> MessageRules.body("x\0".repeat(2049), 4096)), "too long and a NUL: the size is told first");
        assertEquals(InputCode.MESSAGE_BODY_SIZE, codeOf(() -> MessageRules.body("abcde", 4)), "a lower setting is honoured");
    }

    @Test
    void theTimeToLiveHasADefaultAndRange() {
        Duration hour = Duration.ofSeconds(3600);
        Duration day = Duration.ofSeconds(86400);
        assertEquals(hour, MessageRules.ttl(null, hour, day));
        assertEquals(Duration.ofSeconds(30), MessageRules.ttl(30, hour, day));
        assertEquals(day, MessageRules.ttl(86400, hour, day));
        InvalidInputException low = assertThrows(InvalidInputException.class, () -> MessageRules.ttl(29, hour, day));
        assertEquals(InputCode.MESSAGE_TTL_RANGE, low.code());
        assertEquals(86400, low.arguments().get("maxSeconds"));
        assertEquals(InputCode.MESSAGE_TTL_RANGE, codeOf(() -> MessageRules.ttl(86401, hour, day)));
        assertEquals(InputCode.MESSAGE_TTL_RANGE, codeOf(() -> MessageRules.ttl(0, hour, day)));
        assertEquals(InputCode.MESSAGE_TTL_RANGE, codeOf(() -> MessageRules.ttl(601, hour, Duration.ofSeconds(600))), "a lower maximum");
    }

    @Test
    void aMessageIdHasTheContractsShape() {
        for (String ok : List.of("message-aa", "abcdefgh", "A".repeat(128), UUID.randomUUID().toString(), "1.2_3:4-5x", "x".repeat(8))) {
            assertDoesNotThrow(() -> MessageRules.messageId(ok), ok);
        }
        for (String bad : List.of("bad id", "short", "abcdefg", "A".repeat(129), ".abcdefgh", "-abcdefgh", "abcdefg\n", "abcdefgh!", "")) {
            assertEquals(InputCode.MESSAGE_ID_SHAPE, codeOf(() -> MessageRules.messageId(bad)), bad);
        }
        assertEquals(InputCode.MESSAGE_ID_SHAPE, codeOf(() -> MessageRules.messageId(null)));
    }

    @Test
    void aClaimReceiptIsTheAttemptAndAVersionFourUuid() {
        String uuid = "123e4567-e89b-42d3-a456-426614174000";
        assertTrue(MessageRules.isClaimReceipt("1:" + uuid));
        assertTrue(MessageRules.isClaimReceipt("9999999999999999:" + uuid));
        assertFalse(MessageRules.isClaimReceipt("0:" + uuid), "attempts start at 1");
        assertFalse(MessageRules.isClaimReceipt("01:" + uuid));
        assertFalse(MessageRules.isClaimReceipt("12345678901234567:" + uuid), "at most 16 digits");
        assertFalse(MessageRules.isClaimReceipt("1:123e4567-e89b-32d3-a456-426614174000"), "version 3");
        assertFalse(MessageRules.isClaimReceipt("1:123e4567-e89b-42d3-c456-426614174000"), "variant");
        assertFalse(MessageRules.isClaimReceipt("1:" + uuid.toUpperCase()));
        assertFalse(MessageRules.isClaimReceipt("not-a-receipt"));
        assertFalse(MessageRules.isClaimReceipt(null));
        UUID random = UUID.randomUUID();
        assertTrue(MessageRules.isClaimReceipt(MessageRules.claimReceipt(3, random)), "what the claim issues has the shape the acknowledgement expects");
        assertEquals("3:" + random, MessageRules.claimReceipt(3, random));
    }

    @Test
    void anAcknowledgementHasItsOwnShape() {
        String uuid = "123e4567-e89b-42d3-a456-426614174000";
        assertEquals(Map.of(), MessageRules.acknowledgement(List.of("a"), List.of()));
        assertEquals(Map.of(), MessageRules.acknowledgement(List.of("a"), null));
        assertEquals(Map.of("a", "1:" + uuid), MessageRules.acknowledgement(List.of("a", "b"), List.of(new ClaimReceipt("a", "1:" + uuid))));
        assertDoesNotThrow(() -> MessageRules.acknowledgement(java.util.stream.IntStream.range(0, 50).mapToObj(index -> "id-" + index).toList(), List.of()));
        assertEquals(InputCode.ACK_MESSAGE_IDS_RANGE, codeOf(() -> MessageRules.acknowledgement(List.of(), List.of())));
        assertEquals(InputCode.ACK_MESSAGE_IDS_RANGE, codeOf(() -> MessageRules.acknowledgement(null, List.of())));
        assertEquals(InputCode.ACK_MESSAGE_IDS_RANGE, codeOf(() -> MessageRules.acknowledgement(java.util.stream.IntStream.range(0, 51).mapToObj(index -> "id-" + index).toList(), List.of())));
        assertEquals(InputCode.ACK_MESSAGE_IDS_RANGE, codeOf(() -> MessageRules.acknowledgement(List.of("x".repeat(129)), List.of())));
        List<ClaimReceipt> many = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            many.add(new ClaimReceipt("a", "1:" + uuid));
        }
        assertEquals(InputCode.ACK_CLAIMS_RANGE, codeOf(() -> MessageRules.acknowledgement(List.of("a"), many)));
        assertEquals(InputCode.ACK_CLAIM_RECEIPT_INVALID, codeOf(() -> MessageRules.acknowledgement(List.of("a"), List.of(new ClaimReceipt("a", "not-a-receipt")))));
        assertEquals(InputCode.ACK_CLAIM_RECEIPT_INVALID, codeOf(() -> MessageRules.acknowledgement(List.of("a"), List.of(new ClaimReceipt("other", "1:" + uuid)))), "a receipt for an ID not asked for");
        assertEquals(InputCode.ACK_CLAIM_RECEIPT_INVALID, codeOf(() -> MessageRules.acknowledgement(List.of("a"), List.of(new ClaimReceipt("a", "1:" + uuid), new ClaimReceipt("a", "2:" + uuid)))), "one per ID");
        List<ClaimReceipt> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals(InputCode.ACK_CLAIM_RECEIPT_INVALID, codeOf(() -> MessageRules.acknowledgement(List.of("a"), withNull)));
    }

    @Test
    void aClaimAsksForAtMostWhatTheSettingsAllow() {
        assertEquals(new MessageRules.ClaimBudget(10, 32768), MessageRules.claimBudget(null, 10, 32768));
        assertEquals(new MessageRules.ClaimBudget(10, 32768), MessageRules.claimBudget(ClaimLimits.DEFAULTS, 10, 32768));
        assertEquals(new MessageRules.ClaimBudget(1, 4096), MessageRules.claimBudget(new ClaimLimits(1, 4096), 10, 32768));
        assertEquals(new MessageRules.ClaimBudget(10, 5), MessageRules.claimBudget(new ClaimLimits(null, 5), 10, 32768));
        for (ClaimLimits bad : List.of(new ClaimLimits(0, null), new ClaimLimits(11, null), new ClaimLimits(null, 0), new ClaimLimits(null, 32769), new ClaimLimits(-1, -1))) {
            InvalidInputException refused = assertThrows(InvalidInputException.class, () -> MessageRules.claimBudget(bad, 10, 32768));
            assertEquals(InputCode.CLAIM_LIMIT_RANGE, refused.code(), bad.toString());
            assertEquals(10, refused.arguments().get("maxMessages"));
        }
    }

    @Test
    void aClaimLeaseDoublesForEachRedeliveryUpToTheCap() {
        Duration base = Duration.ofMinutes(2);
        Duration cap = Duration.ofMinutes(30);
        assertEquals(List.of(120L, 240L, 480L, 960L, 1800L, 1800L, 1800L, 1800L),
                java.util.stream.LongStream.range(0, 8).mapToObj(previous -> MessageRules.claimLease(previous, base, cap).getSeconds()).toList());
        assertEquals(Duration.ofSeconds(100), MessageRules.claimLease(10, Duration.ofSeconds(10), Duration.ofSeconds(100)), "the cap of a small setting");
    }

    @Test
    void theSizeOfAClaimAnswerIsTheJsonTextsUtf8Length() {
        Instant at = Instant.parse("2026-01-02T03:04:05.006Z");
        ClaimedMessage plain = new ClaimedMessage("message-aa", "ref-1", "claude", "ref-2", "hello", at, at.plusSeconds(60), 1, at, "1:123e4567-e89b-42d3-a456-426614174000");
        String plainJson = "{\"ok\":true,\"data\":{\"messages\":[{\"messageId\":\"message-aa\",\"senderRef\":\"ref-1\",\"senderHost\":\"claude\",\"recipientRef\":\"ref-2\",\"body\":\"hello\","
                + "\"createdAt\":\"2026-01-02T03:04:05.006Z\",\"expiresAt\":\"2026-01-02T03:05:05.006Z\",\"deliveryAttempt\":1,\"firstDeliveredAt\":\"2026-01-02T03:04:05.006Z\","
                + "\"claimReceipt\":\"1:123e4567-e89b-42d3-a456-426614174000\"}]}}\n";
        assertEquals(plainJson.getBytes(StandardCharsets.UTF_8).length, MessageRules.claimAnswerBytes(List.of(plain)));
        assertEquals("{\"ok\":true,\"data\":{\"messages\":[]}}\n".length(), MessageRules.claimAnswerBytes(List.of()));

        // escapes and non-ASCII: a quote, a backslash, a line break, a control character (six bytes), Korean (three), an emoji (four)
        String body = "a\"b\\c\nd\u0001가😀";
        ClaimedMessage odd = new ClaimedMessage("message-bb", "ref-1", "claude", "ref-2", body, at, at.plusSeconds(60), 12, null, "12:123e4567-e89b-42d3-a456-426614174000");
        String oddJson = "{\"messageId\":\"message-bb\",\"senderRef\":\"ref-1\",\"senderHost\":\"claude\",\"recipientRef\":\"ref-2\",\"body\":\"a\\\"b\\\\c\\nd\\u0001가😀\","
                + "\"createdAt\":\"2026-01-02T03:04:05.006Z\",\"expiresAt\":\"2026-01-02T03:05:05.006Z\",\"deliveryAttempt\":12,\"firstDeliveredAt\":null,"
                + "\"claimReceipt\":\"12:123e4567-e89b-42d3-a456-426614174000\"}";
        String two = "{\"ok\":true,\"data\":{\"messages\":[" + plainJson.substring(plainJson.indexOf("[{") + 1, plainJson.indexOf("]}}")) + "," + oddJson + "]}}\n";
        assertEquals(two.getBytes(StandardCharsets.UTF_8).length, MessageRules.claimAnswerBytes(List.of(plain, odd)), "two messages: the comma between them");
    }

    @Test
    void aRecordCountsItsOverheadAndTheBytesOfItsTexts() {
        assertEquals(MessageRules.RECORD_OVERHEAD_BYTES + 10 + 5 + 5 + 5, MessageRules.preparedRecordBytes("message-aa", "ref-1", "ref-2", "hello"));
        assertEquals(MessageRules.RECORD_OVERHEAD_BYTES + 10 + 5 + 5, MessageRules.preparedRecordBytes("message-aa", "ref-1", "ref-2", null), "a receipt has no body");
        assertEquals(MessageRules.RECORD_OVERHEAD_BYTES + 10 + 5 + 5 + 6, MessageRules.preparedRecordBytes("message-aa", "ref-1", "ref-2", "가나"), "bytes, not characters");
    }

    @Test
    void theQueueStateIsReadFromTheTimes() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(MessageState.QUEUED, MessageRules.queueState(null, null));
        assertEquals(MessageState.DELIVERED, MessageRules.queueState(at, null));
        assertEquals(MessageState.ACKNOWLEDGED, MessageRules.queueState(at, at));
        assertEquals(MessageState.ACKNOWLEDGED, MessageRules.queueState(null, at));
        assertTrue(MessageState.DELIVERED.isReceived() && MessageState.ACKNOWLEDGED.isReceived());
        assertFalse(MessageState.QUEUED.isReceived() || MessageState.SUBMITTED.isReceived() || MessageState.RECALLED.isReceived() || MessageState.PREPARED.isReceived());
    }
}
