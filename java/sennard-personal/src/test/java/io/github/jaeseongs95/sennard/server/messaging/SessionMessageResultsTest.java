// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.messaging.DeliveryState;
import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.RecallRefusal;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.AcknowledgeResult;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Attestation;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.AutoWake;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.RecallView;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.SendResult;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.SourceReceipt;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.StatusResult;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.StatusView;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.StoredReceipt;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.TargetView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools' answers of the session message service are records and the wire is what the published contracts have: the key order, the null a field may be, and the keys
 * that are there only sometimes. The receipt of a message's source is made from a record and its token is made over its canonical form.
 *
 * <pre>
 * r1  send, first time        no duplicate key; the outlook with a null basisAt
 * r2  send, repeated          duplicate true after the times
 * r3  status, unknown id      status null and the guidance with its key and empty params
 * r4  status, a draft         the draft's four members; no guidance keys
 * r5  status, a recalled receipt / a submitted one   recalledAt and deliveryState only when present; receiptExpiresAt always
 * r6  status, in the queue    the target, the times that are null, wake null, autoWake null once acknowledged
 * r7  recall, refused / done  reason only when refused; duplicate only when true
 * r8  acknowledge             the count
 * r9  source receipt          unsigned has no token; the token is the same from the unsigned and from the sealed receipt; sealed text ends with it
 * r10 stored receipt          unknown members are ignored, a missing attestation is null, a member of the wrong shape is an error
 * </pre>
 */
class SessionMessageResultsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final AutoWake OUTLOOK = new AutoWake(WakeOutlook.State.NO_LIVE_RELAY, WakeOutlook.Reason.PRESENCE_UNKNOWN, null, "2026-11-11T00:00:00.000Z", "none");
    private static final String OUTLOOK_JSON = "{\"state\":\"no-live-relay\",\"reason\":\"presence-unknown\",\"basisAt\":null,\"checkedAt\":\"2026-11-11T00:00:00.000Z\",\"authorityEffect\":\"none\"}";

    private static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @Test
    void r1AFirstSendHasNoDuplicateKey() {
        assertEquals("{\"messageId\":\"m1\",\"createdAt\":\"c\",\"expiresAt\":\"e\",\"autoWake\":" + OUTLOOK_JSON + "}", json(new SendResult("m1", "c", "e", null, OUTLOOK)));
    }

    @Test
    void r2ARepeatedSendSaysDuplicate() {
        assertEquals("{\"messageId\":\"m1\",\"createdAt\":\"c\",\"expiresAt\":\"e\",\"duplicate\":true,\"autoWake\":" + OUTLOOK_JSON + "}", json(new SendResult("m1", "c", "e", Boolean.TRUE, OUTLOOK)));
    }

    @Test
    void r3AnUnknownMessageIsStatusNullWithGuidance() {
        assertEquals("{\"status\":null,\"guidance\":\"g\",\"guidanceKey\":\"k\",\"guidanceParams\":{}}", json(new StatusResult(null, "g", "k", Map.of())));
    }

    @Test
    void r4ADraftHasFourMembersAndNoGuidance() {
        assertEquals("{\"status\":{\"messageId\":\"m1\",\"state\":\"PREPARED\",\"preparedAt\":\"p\",\"expiresAt\":\"e\"}}",
                json(new StatusResult(new StatusView.Draft("m1", MessageState.PREPARED, "p", "e"), null, null, null)));
    }

    @Test
    void r5ASubmissionShowsRecalledAtAndDeliveryStateOnlyWhenTheyAreThere() {
        assertEquals("{\"messageId\":\"m1\",\"createdAt\":\"c\",\"expiresAt\":\"e\",\"recalledAt\":\"r\",\"state\":\"RECALLED\",\"receiptExpiresAt\":null}",
                json(new StatusView.Submission("m1", "c", "e", "r", MessageState.RECALLED, null, null)));
        assertEquals("{\"messageId\":\"m1\",\"createdAt\":\"c\",\"expiresAt\":\"e\",\"state\":\"SUBMITTED\",\"deliveryState\":\"UNKNOWN\",\"receiptExpiresAt\":\"x\"}",
                json(new StatusView.Submission("m1", "c", "e", null, MessageState.SUBMITTED, DeliveryState.UNKNOWN, "x")));
    }

    @Test
    void r6AQueuedMessageShowsItsTargetAndTheNullsOfWhatHasNotHappened() {
        assertEquals("{\"messageId\":\"m1\",\"target\":{\"host\":\"claude-code\",\"sessionId\":null},\"createdAt\":\"c\",\"expiresAt\":\"e\",\"claimedAt\":null,\"acknowledgedAt\":null,"
                + "\"deliveryAttempts\":0,\"firstDeliveredAt\":null,\"state\":\"QUEUED\",\"wake\":null,\"autoWake\":" + OUTLOOK_JSON + "}",
                json(new StatusView.InQueue("m1", new TargetView("claude-code", null), "c", "e", null, null, 0L, null, MessageState.QUEUED, null, OUTLOOK)));
        String acknowledged = json(new StatusView.InQueue("m1", new TargetView("claude-code", "s"), "c", "e", "a", "b", 1L, "f", MessageState.ACKNOWLEDGED, null, null));
        assertTrue(acknowledged.endsWith("\"state\":\"ACKNOWLEDGED\",\"wake\":null,\"autoWake\":null}"), acknowledged);
    }

    @Test
    void r7ARecallShowsTheReasonOnlyWhenRefusedAndDuplicateOnlyWhenTrue() {
        assertEquals("{\"recalled\":false,\"reason\":\"DELIVERED\",\"status\":null}", json(new RecallView(false, null, RecallRefusal.DELIVERED, null)));
        assertEquals("{\"recalled\":true,\"duplicate\":true,\"status\":{\"messageId\":\"m1\",\"state\":\"PREPARED\",\"preparedAt\":\"p\",\"expiresAt\":\"e\"}}",
                json(new RecallView(true, Boolean.TRUE, null, new StatusView.Draft("m1", MessageState.PREPARED, "p", "e"))));
    }

    @Test
    void r8AnAcknowledgementIsTheCount() {
        assertEquals("{\"acknowledged\":1}", json(new AcknowledgeResult(1)));
    }

    @Test
    void r9TheTokenIsMadeOverTheCanonicalFormOfTheReceiptWithoutIt() {
        Attestation attestation = new Attestation("broker-peer-envelope", "session-message-hook", "1.0.0");
        SourceReceipt unsigned = new SourceReceipt("1.0.0", "source-1", "peer", "codex", "s1", "m1", "sha256:abc", "2026-11-11T00:00:00.000Z", "2026-11-11T01:00:00.000Z", "none", attestation, null);
        byte[] key = new byte[32];
        String fromUnsigned = CanonicalJson.integrityToken(MAPPER.valueToTree(unsigned), key);
        SourceReceipt sealed = unsigned.sealed(fromUnsigned);
        assertEquals(fromUnsigned, CanonicalJson.integrityToken(MAPPER.valueToTree(sealed), key));
        String text = json(sealed);
        assertEquals("{\"schemaVersion\":\"1.0.0\",\"receiptId\":\"source-1\",\"originKind\":\"peer\",\"host\":\"codex\",\"sessionId\":\"s1\",\"eventId\":\"m1\",\"contentDigest\":\"sha256:abc\","
                + "\"observedAt\":\"2026-11-11T00:00:00.000Z\",\"expiresAt\":\"2026-11-11T01:00:00.000Z\",\"authorityEffect\":\"none\","
                + "\"attestation\":{\"kind\":\"broker-peer-envelope\",\"adapter\":\"session-message-hook\",\"capabilityVersion\":\"1.0.0\"},\"integrityToken\":\"" + fromUnsigned + "\"}", text);
        assertEquals(32, Base64.getUrlDecoder().decode(fromUnsigned).length);
        assertEquals(CanonicalJson.of(MAPPER.valueToTree(attestation)), CanonicalJson.of(MAPPER.readValue(text, StoredReceipt.class).attestation()));
    }

    @Test
    void r10AStoredReceiptReadsItsFieldsAndRefusesAWrongShape() {
        StoredReceipt receipt = MAPPER.readValue("{\"receiptId\":\"source-1\",\"originKind\":\"peer\",\"authorityEffect\":\"none\",\"contentDigest\":\"sha256:abc\",\"unknown\":[1,2]}", StoredReceipt.class);
        assertEquals("source-1", receipt.receiptId());
        assertEquals("sha256:abc", receipt.contentDigest());
        assertNull(receipt.attestation());
        assertNotNull(MAPPER.readValue("{\"attestation\":{\"kind\":\"x\"}}", StoredReceipt.class).attestation());
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"receiptId\":{\"a\":1}}", StoredReceipt.class));
    }
}
