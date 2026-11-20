// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.jaeseongs95.sennard.domain.messaging.MessageRules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * The settings of session messaging ({@code sennard.messaging.*}), bound the way Spring Boot binds them and validated by Jakarta Validation, with no database.
 *
 * <pre>
 * d1  defaults       every limit and period is what the old store had: 4096 bytes, 3600 and 86400 seconds, 32 KiB answer, 10 messages per claim, a ten minute draft, a one hour
 *                    receipt retention, lease 2 and 30 minutes, 1000 and 100 drafts, 1000 and 250 receipts, 4 MiB of records, 1000 messages and 4 MiB of queue, 1000 snapshots, 20 rows
 * c1  contract       the three numbers of the public contract (contracts/prepare-session-message-request.v1.schema.json) equal the defaults, and cannot be raised
 * v1  validation     a limit below 1, a default time to live above the maximum, a lease base above its cap, and the contract numbers raised are refused; lowering is allowed
 * b1  binding        a relaxed name and a Duration unit bind ({@code sennard.messaging.claim-lease-base=90s})
 * </pre>
 */
class MessagingPropertiesTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The record bound from these settings (at least one must be under the prefix: a prefix with nothing under it binds nothing) and validated as the context does. */
    private static MessagingProperties bind(Map<String, String> settings) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new Binder(new MapConfigurationPropertySource(settings)).bind("sennard.messaging", Bindable.of(MessagingProperties.class), new ValidationBindHandler(validator)).get();
    }

    @Test
    void theDefaultsAreWhatTheOldStoreHad() {
        MessagingProperties properties = bind(Map.of("sennard.messaging.body-max-bytes", "4096"));
        assertEquals(4096, properties.bodyMaxBytes());
        assertEquals(Duration.ofSeconds(3600), properties.ttlDefault());
        assertEquals(Duration.ofSeconds(86400), properties.ttlMax());
        assertEquals(32768, properties.claimAnswerMaxBytes());
        assertEquals(10, properties.claimMaxMessages());
        assertEquals(Duration.ofMinutes(10), properties.draftTtl());
        assertEquals(Duration.ofHours(1), properties.receiptRetention());
        assertEquals(Duration.ofMinutes(2), properties.claimLeaseBase());
        assertEquals(Duration.ofMinutes(30), properties.claimLeaseMax());
        assertEquals(1000, properties.draftLimit());
        assertEquals(100, properties.senderDraftLimit());
        assertEquals(1000, properties.receiptLimit());
        assertEquals(250, properties.senderReceiptLimit());
        assertEquals(4L * 1024 * 1024, properties.recordBytesLimit());
        assertEquals(1000, properties.messageLimit());
        assertEquals(4L * 1024 * 1024, properties.messageBytesLimit());
        assertEquals(1000, properties.peerWaitSnapshotLimit());
        assertEquals(20, properties.peerRelationRows());
    }

    @Test
    void theNumbersOfThePublicContractAreTheDefaultsAndCannotBeRaised() throws IOException {
        RequestSchema contract = contract("prepare-session-message-request.v1.schema.json");
        MessagingProperties properties = bind(Map.of("sennard.messaging.body-max-bytes", "4096"));
        assertEquals(contract.properties().body().maxLength(), properties.bodyMaxBytes(), "the contract's body limit");
        Ttl ttl = contract.properties().ttlSeconds();
        assertEquals(Duration.ofSeconds(ttl.defaultValue()), properties.ttlDefault());
        assertEquals(Duration.ofSeconds(ttl.maximum()), properties.ttlMax());
        assertEquals(MessageRules.MIN_TTL, Duration.ofSeconds(ttl.minimum()), "MessageRules.MIN_TTL is the contract's minimum");

        for (Map<String, String> raised : java.util.List.of(Map.of("sennard.messaging.body-max-bytes", "4097"), Map.of("sennard.messaging.ttl-max", "86401"),
                Map.of("sennard.messaging.ttl-default", "86401", "sennard.messaging.ttl-max", "86400"), Map.of("sennard.messaging.claim-answer-max-bytes", "32769"))) {
            assertThrows(BindException.class, () -> bind(raised), raised.toString());
        }
        MessagingProperties lowered = bind(Map.of("sennard.messaging.body-max-bytes", "100", "sennard.messaging.ttl-max", "600", "sennard.messaging.ttl-default", "300"));
        assertEquals(100, lowered.bodyMaxBytes());
        assertEquals(Duration.ofSeconds(600), lowered.ttlMax(), "a plain number is seconds, the contract's unit");
        assertEquals(Duration.ofMinutes(5), bind(Map.of("sennard.messaging.ttl-default", "5m")).ttlDefault(), "a unit is understood too");
    }

    @Test
    void aLimitBelowOneAndAnInconsistentPairAreRefused() {
        for (Map<String, String> bad : java.util.List.of(Map.of("sennard.messaging.message-limit", "0"), Map.of("sennard.messaging.sender-draft-limit", "-1"), Map.of("sennard.messaging.ttl-default", "29"),
                Map.of("sennard.messaging.ttl-default", "7200", "sennard.messaging.ttl-max", "3600"), Map.of("sennard.messaging.claim-lease-base", "1h", "sennard.messaging.claim-lease-max", "30m"),
                Map.of("sennard.messaging.record-bytes-limit", "0"))) {
            BindException refused = assertThrows(BindException.class, () -> bind(bad), bad.toString());
            assertTrue(refused.getMessage().contains("sennard.messaging"), refused.getMessage());
        }
    }

    @Test
    void aRelaxedNameAndADurationUnitBind() {
        MessagingProperties properties = bind(Map.of("sennard.messaging.claim-lease-base", "90s", "sennard.messaging.DRAFT-TTL", "5m", "sennard.messaging.receiptRetention", "2h"));
        assertEquals(Duration.ofSeconds(90), properties.claimLeaseBase());
        assertEquals(Duration.ofMinutes(5), properties.draftTtl());
        assertEquals(Duration.ofHours(2), properties.receiptRetention());
    }

    /** The contract files are the repository's: this module's tests run in {@code java/sennard-app}, two folders below the repository root. */
    private static RequestSchema contract(String file) throws IOException {
        Path path = Path.of("..", "..", "contracts", file);
        assertTrue(Files.isRegularFile(path), "the contract file " + path.toAbsolutePath().normalize());
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, RequestSchema.class);
        }
    }

    /** The three numbers of the contract's JSON Schema this test compares, bound by Jackson; the rest of the schema is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RequestSchema(Properties properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Properties(Body body, Ttl ttlSeconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Body(int maxLength) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Ttl(@JsonProperty("default") int defaultValue, int maximum, int minimum) {
    }
}
