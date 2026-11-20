// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import tools.jackson.databind.json.JsonMapper;

/**
 * The text a host gives the model for received peer messages. The acknowledgement line travels with the messages: a host may show the model
 * neither the server's instructions nor a deferred tool's description. The BEGIN and END lines are fixed delimiters that connectors match.
 */
@Component
public class PeerEnvelope {
    private static final int MAX_BYTES = 8192;
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static final String END = "[sennard peer message END]";

    /** The JSON a block carries between its delimiters; the key order is the wire order. */
    public record Content(Party sender, Party recipient, String message, String messageEncoding, Receipt receipt) {
    }

    /** One end of a message, with the host's child identifier when present. */
    public record Party(String host, String sessionId, @JsonInclude(JsonInclude.Include.NON_NULL) String actorId) {
    }

    /** What the recipient acknowledges with; {@code claimReceipt} is absent when the message was not claimed. */
    public record Receipt(String messageId, String sourceReceiptId, String contentDigest, String sentAt, String expiresAt, long deliveryAttempt, String firstDeliveredAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String claimReceipt) {
    }

    private final MessageSource messages;
    private final JsonMapper mapper;

    public PeerEnvelope(MessageSource messages, JsonMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    /** @throws IllegalStateException if the messages do not fit a hook's context even encoded */
    public String render(List<Delivered> delivered) {
        String guidance = messages.getMessage("peer.envelope.ackGuidance", null, Locale.ENGLISH);
        String warning = messages.getMessage("peer.envelope.warning", null, Locale.ENGLISH);
        List<String> lines = new ArrayList<>(1 + 4 * delivered.size());
        lines.add(guidance);
        // The size of the text as it will be joined: each further line adds its bytes and one newline, so no message is measured twice.
        long size = utf8Length(guidance);
        for (Delivered message : delivered) {
            List<String> encoded = block(message, warning, message.body(), "plain-json");
            long added = utf8Length(encoded);
            if (size + added > MAX_BYTES) {
                encoded = block(message, warning, Base64.getEncoder().encodeToString(message.body().getBytes(StandardCharsets.UTF_8)), "base64-utf8");
                added = utf8Length(encoded);
            }
            lines.addAll(encoded);
            size += added;
        }
        if (size > MAX_BYTES) {
            throw new IllegalStateException("Peer messages do not fit " + MAX_BYTES + " bytes of a hook's context even with the bodies encoded.");
        }
        return String.join("\n", lines);
    }

    private static long utf8Length(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The bytes the lines add to a text they are joined to: each line and the newline before it. */
    private static long utf8Length(List<String> lines) {
        long bytes = 0;
        for (String line : lines) {
            bytes += 1 + utf8Length(line);
        }
        return bytes;
    }

    private List<String> block(Delivered message, String warning, String body, String encoding) {
        Content content = new Content(new Party(message.senderHost(), message.senderSessionId(), message.senderActorId()),
                new Party(message.recipientHost(), message.recipientSessionId(), message.recipientActorId()), body, encoding,
                new Receipt(message.messageId(), message.sourceReceiptId(), message.contentDigest(), message.createdAt(), message.expiresAt(), message.deliveryAttempt(),
                        message.firstDeliveredAt(), message.claimReceipt()));
        return List.of(BEGIN, warning, mapper.writeValueAsString(content), END);
    }
}
