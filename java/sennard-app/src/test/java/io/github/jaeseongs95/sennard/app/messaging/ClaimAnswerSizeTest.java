// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimedMessage;
import io.github.jaeseongs95.sennard.domain.messaging.MessageRules;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code MessageRules.claimAnswerBytes} (sennard-domain, which has no JSON library) against a real JSON writer: the number it states is the UTF-8 length of the text Jackson
 * writes for the same answer, for ordinary text, for escapes (quote, backslash, line break, control characters), for Korean and for an emoji, for no message, one and several.
 */
class ClaimAnswerSizeTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The wire form of one claimed message and of the answer that holds them: records, so Jackson writes the fields in this order. */
    private record Item(String messageId, String senderRef, String senderHost, String recipientRef, String body, String createdAt, String expiresAt, long deliveryAttempt,
            String firstDeliveredAt, String claimReceipt) {
    }

    private record Data(List<Item> messages) {
    }

    private record Answer(boolean ok, Data data) {
    }

    private static int written(List<ClaimedMessage> messages) {
        List<Item> items = new ArrayList<>();
        for (ClaimedMessage message : messages) {
            items.add(new Item(message.messageId(), message.senderRef(), message.senderHost(), message.recipientRef(), message.body(), Timestamps.format(message.createdAt()),
                    Timestamps.format(message.expiresAt()), message.deliveryAttempt(), message.firstDeliveredAt() == null ? null : Timestamps.format(message.firstDeliveredAt()),
                    message.claimReceipt()));
        }
        return MAPPER.writeValueAsString(new Answer(true, new Data(items))).getBytes(StandardCharsets.UTF_8).length + 1;
    }

    private static ClaimedMessage message(String body, long attempt, boolean delivered) {
        Instant at = Instant.parse("2026-05-06T07:08:09.010Z");
        return new ClaimedMessage("message-" + attempt, "ref-sender", "claude", "ref-target", body, at, at.plusSeconds(3600), attempt, delivered ? at : null, attempt + ":123e4567-e89b-42d3-a456-426614174000");
    }

    @Test
    void theStatedSizeIsTheSizeOfTheJsonJacksonWrites() {
        List<String> bodies = List.of("hello", "", "a\"b\\c", "line\nbreak\ttab\rreturn", "\u0001\u001f\b\f", "가나다라 한글", "emoji 😀 and é and €", "x".repeat(4096), "  ", "/slash/");
        List<ClaimedMessage> all = new ArrayList<>();
        for (int index = 0; index < bodies.size(); index++) {
            ClaimedMessage one = message(bodies.get(index), index + 1, index % 2 == 0);
            assertEquals(written(List.of(one)), MessageRules.claimAnswerBytes(List.of(one)), "one message: " + bodies.get(index).length() + " chars");
            all.add(one);
            assertEquals(written(all), MessageRules.claimAnswerBytes(all), "the first " + all.size() + " messages");
        }
        assertEquals(written(List.of()), MessageRules.claimAnswerBytes(List.of()));
    }
}
