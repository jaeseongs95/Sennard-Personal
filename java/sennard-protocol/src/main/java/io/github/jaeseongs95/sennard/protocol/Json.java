// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The one mapper of the protocol, and the JSON type checks the wire format depends on. */
public final class Json {
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    /**
     * Not a bean: this module is the wire library the hook client and the server both link, and it has no Spring dependency to inject one with. It is the one
     * mapper the module makes, so the bytes of the wire format come from one configuration.
     */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** The field as text, or null when it is absent or not a JSON string. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** Number.isSafeInteger: the only integers both ends read identically. */
    public static boolean isSafeInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && Math.abs(value.longValue()) <= MAX_SAFE_INTEGER;
    }
}
