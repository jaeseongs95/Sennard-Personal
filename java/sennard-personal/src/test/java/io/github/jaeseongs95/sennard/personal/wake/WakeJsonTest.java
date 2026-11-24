// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WakeJsonTest {
    @Test
    void readsExactlyOneJsonValueFromUtf8Bytes() {
        var reader = WakeJson.strictReader(JsonMapper.builder().build(), JsonNode.class);
        JsonNode frame = reader.readValue("{\"message\":\"한글\"}\n".getBytes(UTF_8));
        assertEquals("한글", frame.path("message").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":1,\"id\":2}", "{\"id\":1}{\"id\":2}"})
    void rejectsDuplicateKeysOrTrailingValues(String payload) {
        var reader = WakeJson.strictReader(JsonMapper.builder().build(), JsonNode.class);
        assertThrows(JacksonException.class, () -> reader.readValue(payload.getBytes(UTF_8)));
    }
}
