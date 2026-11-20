// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * The declared skills no call was seen for, as finalize records them: a list, or {@code null} when the record of the session's skill calls could not be read.
 * The receipt leaves the key out until finalize, so "not finalized" (the holder is null), "could not be read" ({@link #UNREAD}) and "a list" are three different
 * documents, and Jackson would read a {@code null} as a missing key; {@link Reading} keeps them apart.
 */
@JsonDeserialize(using = CallsNotObserved.Reading.class)
public record CallsNotObserved(List<String> skills) {

    /** The record of the session's skill calls could not be read: written as {@code null}. */
    public static final CallsNotObserved UNREAD = new CallsNotObserved(null);

    /** The list, or {@code null} for an unread record. */
    @JsonValue
    List<String> json() {
        return skills;
    }

    /** True when there is something to show: a skill without a seen call, or a record that could not be read. */
    boolean worthShowing() {
        return skills == null || !skills.isEmpty();
    }

    /** Reads a list of skill IDs, and a {@code null} as {@link #UNREAD}. */
    static final class Reading extends StdDeserializer<CallsNotObserved> {
        Reading() {
            super(CallsNotObserved.class);
        }

        @Override
        public CallsNotObserved deserialize(JsonParser parser, DeserializationContext context) {
            return new CallsNotObserved(context.readValue(parser, context.getTypeFactory().constructCollectionType(List.class, String.class)));
        }

        @Override
        public CallsNotObserved getNullValue(DeserializationContext context) {
            return UNREAD;
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
