// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The peer envelope a host gives the model, made from records and not from a hand-built tree. The wire text is what connectors match, so it is held exactly.
 *
 * <pre>
 * e1  one message, plain       the guidance, BEGIN, the warning, the content JSON (key order, null first delivery, claim receipt), END
 * e2  no claim receipt         the receipt has no claimReceipt key at all (not null)
 * e3  a body that grows when escaped    sent base64 (messageEncoding base64-utf8) and decodes to the body
 * e4  too much even encoded    IllegalStateException, not a truncated envelope
 * e5  two messages             both are in one text, in order, one BEGIN/END pair each
 * </pre>
 */
class PeerEnvelopeTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static final String END = "[sennard peer message END]";

    private static PeerEnvelope envelope() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("peer.envelope.ackGuidance", Locale.ENGLISH, "ack guidance");
        messages.addMessage("peer.envelope.warning", Locale.ENGLISH, "warning");
        return new PeerEnvelope(messages, MAPPER);
    }

    private static Delivered message(String id, String body, String claimReceipt, String firstDeliveredAt) {
        return new Delivered(id, "codex", "s1", "claude-code", "s2", body, "2026-11-11T00:00:00.000Z", "2026-11-11T01:00:00.000Z", 1, firstDeliveredAt, claimReceipt, "source-1", "sha256:abc");
    }

    @Test
    void e1OneMessageIsFramedAndItsContentIsHeldExactly() {
        String text = envelope().render(List.of(message("m1", "hello", "c1", null)));
        assertEquals(String.join("\n", "ack guidance", BEGIN, "warning",
                "{\"sender\":{\"host\":\"codex\",\"sessionId\":\"s1\"},\"recipient\":{\"host\":\"claude-code\",\"sessionId\":\"s2\"},\"message\":\"hello\",\"messageEncoding\":\"plain-json\","
                        + "\"receipt\":{\"messageId\":\"m1\",\"sourceReceiptId\":\"source-1\",\"contentDigest\":\"sha256:abc\",\"sentAt\":\"2026-11-11T00:00:00.000Z\","
                        + "\"expiresAt\":\"2026-11-11T01:00:00.000Z\",\"deliveryAttempt\":1,\"firstDeliveredAt\":null,\"claimReceipt\":\"c1\"}}",
                END), text);
    }

    @Test
    void e2AMessageWithNoClaimReceiptHasNoClaimReceiptKey() {
        String[] lines = envelope().render(List.of(message("m1", "hello", null, "2026-11-11T00:00:01.000Z"))).split("\n");
        JsonNode content = MAPPER.readTree(lines[3]);
        assertTrue(content.path("receipt").has("firstDeliveredAt"));
        assertEquals("2026-11-11T00:00:01.000Z", content.path("receipt").path("firstDeliveredAt").asString());
        assertFalse(content.path("receipt").has("claimReceipt"), lines[3]);
    }

    @Test
    void e3ABodyThatGrowsWhenEscapedIsSentAsBase64() {
        String body = "\u0001".repeat(3000);
        String[] lines = envelope().render(List.of(message("m1", body, "c1", null))).split("\n");
        JsonNode content = MAPPER.readTree(lines[3]);
        assertEquals("base64-utf8", content.path("messageEncoding").asString());
        assertEquals(body, new String(Base64.getDecoder().decode(content.path("message").asString()), StandardCharsets.UTF_8));
    }

    @Test
    void e4MessagesThatDoNotFitEvenEncodedAreAnErrorAndNotACutEnvelope() {
        assertThrows(IllegalStateException.class, () -> envelope().render(List.of(message("m1", "x".repeat(7800), "c1", null))));
    }

    @Test
    void e5TwoMessagesAreOneTextInOrder() {
        String text = envelope().render(List.of(message("m1", "first", "c1", null), message("m2", "second", "c2", null)));
        int first = text.indexOf(BEGIN);
        int second = text.indexOf(BEGIN, first + 1);
        assertTrue(first >= 0 && second > first, text);
        assertEquals(-1, text.indexOf(BEGIN, second + 1), text);
        assertTrue(text.indexOf("\"messageId\":\"m1\"") < text.indexOf("\"messageId\":\"m2\""), text);
        assertTrue(text.endsWith(END));
    }
}
