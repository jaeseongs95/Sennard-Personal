// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * How a host's input record reads what the host wrote. A host's JSON is not ours to type: a field the host leaves out, writes as null, or writes as another
 * type than the one its documentation names has always been the same as an empty field here (it must never fail a hook the host waits for). Jackson
 * would coerce a number into a text, and refuse an object where a text is expected, so the record components carry one of these instead of the default.
 *
 * <p>What each reads, for a field that is a text ({@link Text}), a flag ({@link Flag}), a number ({@link Count}), an object ({@link Section}) or the first
 * of a list of texts ({@link FirstText}): the value when it is of that type, otherwise null, which the component's reader treats as absent. A text that
 * is of another type is the empty text instead, for a field whose very presence a host's rule asks about ({@link Marker}).
 */
public final class HostInput {
    private HostInput() {
    }

    /** A text; a value of another type is the empty text, and a null or an absent field stays null. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = TextReader.class)
    public @interface Text {
    }

    /** A text whose presence is the information: written as null or as another type than a text, it is still there, as the empty text. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = MarkerReader.class)
    public @interface Marker {
    }

    /** A flag: true or false, as JSON writes them or as a number (zero is false) or a text ({@code "true"}, {@code "false"}); null for anything else. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = FlagReader.class)
    public @interface Flag {
    }

    /** A whole number, as a number or a text that holds one; null for anything else. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = CountReader.class)
    public @interface Count {
    }

    /** An object read into the component's record; a value of another type is null. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = SectionReader.class)
    public @interface Section {
    }

    /**
     * The first element of a list, when it is a text; every other value, and the other elements, are not read. It is written back as a list of that one
     * text, so that what the Sennard hook client sends reads as what the host wrote.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @JacksonAnnotationsInside
    @JsonDeserialize(using = FirstTextReader.class)
    @JsonSerialize(using = FirstTextWriter.class)
    public @interface FirstText {
    }

    private static String readText(JsonParser parser) {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return parser.getString();
        }
        parser.skipChildren();
        return "";
    }

    public static final class TextReader extends StdDeserializer<String> {
        public TextReader() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            return readText(parser);
        }
    }

    public static final class MarkerReader extends StdDeserializer<String> {
        public MarkerReader() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            return readText(parser);
        }

        @Override
        public Object getNullValue(DeserializationContext context) {
            return "";
        }

        /** A field the host left out is not there; by default Jackson would read it as the null value. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    public static final class FlagReader extends StdDeserializer<Boolean> {
        public FlagReader() {
            super(Boolean.class);
        }

        /** The value Jackson reads it as, or null when the token has no flag in it: a default that differs between two reads was used, not read. */
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            boolean ifAbsentFalse = parser.getValueAsBoolean(false);
            boolean ifAbsentTrue = parser.getValueAsBoolean(true);
            parser.skipChildren();
            return ifAbsentFalse == ifAbsentTrue ? Boolean.valueOf(ifAbsentFalse) : null;
        }
    }

    public static final class CountReader extends StdDeserializer<Integer> {
        public CountReader() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            int ifAbsentZero = parser.getValueAsInt(0);
            int ifAbsentOne = parser.getValueAsInt(1);
            parser.skipChildren();
            return ifAbsentZero == ifAbsentOne ? Integer.valueOf(ifAbsentZero) : null;
        }
    }

    /** Reads the property's own type from an object and skips any other value. */
    public static final class SectionReader extends StdDeserializer<Object> {
        private final ValueDeserializer<Object> record;

        public SectionReader() {
            super(Object.class);
            this.record = null;
        }

        private SectionReader(JavaType type, ValueDeserializer<Object> record) {
            super(type);
            this.record = record;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            JavaType type = property.getType();
            return new SectionReader(type, context.findContextualValueDeserializer(type, property));
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) {
            if (record != null && parser.hasToken(JsonToken.START_OBJECT)) {
                return record.deserialize(parser, context);
            }
            parser.skipChildren();
            return null;
        }
    }

    /** Writes the one text a {@link FirstText} component holds as a list of it. */
    public static final class FirstTextWriter extends StdSerializer<String> {
        public FirstTextWriter() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator generator, SerializationContext context) {
            generator.writeStartArray();
            generator.writeString(value);
            generator.writeEndArray();
        }
    }

    public static final class FirstTextReader extends StdDeserializer<String> {
        public FirstTextReader() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.START_ARRAY)) {
                parser.skipChildren();
                return null;
            }
            JsonToken token = parser.nextToken();
            String first = token == JsonToken.VALUE_STRING ? parser.getString() : null;
            while (token != JsonToken.END_ARRAY) {
                parser.skipChildren();
                token = parser.nextToken();
            }
            return first;
        }
    }
}
