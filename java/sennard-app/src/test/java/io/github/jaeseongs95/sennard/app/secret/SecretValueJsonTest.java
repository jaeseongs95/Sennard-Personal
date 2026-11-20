// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.protocol.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A secret is refused by a JSON writer and does not appear in a log line (D-0269). The one JSON use is reading what a person typed (the admin page's key registration). The
 * product's mapper ({@link Json#MAPPER}) and a plain one are both tried: neither writes the value, and neither writes a masked stand-in that could be read back as a new secret.
 */
class SecretValueJsonTest {
    private static final String TEXT = "hunter2-token-0269";

    private record Registration(String name, SecretValue secret) {
    }

    private static List<JsonMapper> mappers() {
        return List.of(Json.MAPPER, JsonMapper.builder().build());
    }

    @Test
    void aJsonWriterRefusesASecretWhateverHoldsIt() {
        SecretValue secret = SecretValue.of(TEXT);
        for (JsonMapper mapper : mappers()) {
            for (Object holder : new Object[] {secret, new Registration("jev", secret), List.of(secret), Map.of("k", secret)}) {
                JacksonException refused = assertThrows(JacksonException.class, () -> mapper.writeValueAsString(holder), holder.getClass().getName());
                assertFalse(String.valueOf(refused.getMessage()).contains("hunter2"), "the refusal does not carry the value");
                assertFalse(String.valueOf(refused.getOriginalMessage()).contains("hunter2"));
            }
            assertThrows(RuntimeException.class, () -> mapper.valueToTree(new Registration("jev", secret)));
            assertThrows(JacksonException.class, () -> mapper.writeValueAsBytes(secret));
        }
    }

    @Test
    void aSecretTypedByAPersonIsReadFromAJsonString() throws Exception {
        for (JsonMapper mapper : mappers()) {
            Registration read = mapper.readValue("{\"name\":\"jev\",\"secret\":\"" + TEXT + "\"}", Registration.class);
            assertEquals("jev", read.name());
            assertEquals(TEXT, new String(read.secret().reveal()));
            assertFalse(read.toString().contains("hunter2"));
        }
    }

    /**
     * An empty text on the wire is read, not refused by the reader: the field is refused by the request's own validation (the minimum length of {@code @SecretText} of sennard-server, tested with
     * the real request record in sennard-personal's {@code CommitRequestSecretTest}, which names the field {@code secret}). What is tested here is the reader's half: the empty value is read
     * as an empty value, it never becomes a secret with content, and no other way makes an empty secret.
     */
    @Test
    void anEmptySecretOnTheWireIsReadAsEmptyAndLeftToTheValidationOfTheRequest() throws Exception {
        for (JsonMapper mapper : mappers()) {
            Registration read = mapper.readValue("{\"name\":\"jev\",\"secret\":\"\"}", Registration.class);
            assertEquals(0, read.secret().length());
            assertEquals("SecretValue[redacted]", read.secret().toString());
        }
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of(""), "no other way makes an empty secret");
        assertThrows(IllegalArgumentException.class, () -> SecretValue.ofBytes(new byte[0]));
    }

    @Test
    void theLogLineOfASecretHasNoValue() {
        SecretValue secret = SecretValue.of(TEXT);
        assertFalse(MessageFormatter.format("secret {}", secret).getMessage().contains("hunter2"));
        assertFalse(MessageFormatter.arrayFormat("secret {} {}", new Object[] {secret, new Registration("jev", secret)}).getMessage().contains("hunter2"));

        Logger logger = (Logger) LoggerFactory.getLogger("sennard.secret.value.test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.info("secret {}", secret);
            logger.warn("holder {}", new Registration("jev", secret));
            logger.error("failed with " + secret, new IllegalStateException("cause " + secret));
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(3, appender.list.size());
        for (ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains("hunter2"), event.getFormattedMessage());
            assertTrue(event.getFormattedMessage().contains("SecretValue[redacted]"), event.getFormattedMessage());
        }
        assertFalse(appender.list.get(2).getThrowableProxy().getMessage().contains("hunter2"));
    }
}
