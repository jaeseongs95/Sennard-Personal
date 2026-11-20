// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserializers for the fields of a tool input that no schema checked first. Jackson binds a number to a text field and a text to a number by default
 * ({@code "goal": 5} reads as {@code "5"}, {@code "expectedRevision": "3"} as 3) and reads {@code null} as a value that is not there, which the tool's published
 * schema does not accept; a field that carries one of these takes only the JSON type the schema names. A field that is left out is still not there.
 */
final class StrictInput {
    private StrictInput() {
    }

    /** A text field: only a JSON string. */
    static final class Text extends StdDeserializer<String> {
        Text() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getString();
        }

        @Override
        public String getNullValue(DeserializationContext context) {
            return (String) context.reportInputMismatch(String.class, "null is not text");
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /** An item of a list of texts: only a JSON string, or a {@code null} that the item's own constraint then refuses. */
    static final class Item extends StdDeserializer<String> {
        Item() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getString();
        }
    }

    /** A flag field: only a JSON {@code true} or {@code false}, never the text {@code "true"} or a number. A flag that is left out or {@code null} is not there. */
    static final class Flag extends StdDeserializer<Boolean> {
        Flag() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
                return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            }
            return parser.getBooleanValue();
        }
    }

    /** An integer field: only a JSON number without a fraction or exponent. */
    static final class Integral extends StdDeserializer<Long> {
        Integral() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }

        @Override
        public Long getNullValue(DeserializationContext context) {
            return (Long) context.reportInputMismatch(Long.class, "null is not an integer");
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
