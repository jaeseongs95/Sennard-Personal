// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a host wrote for a hook, as the transport received it and before anything has read it: the adapter that owns the host binds it to that host's
 * input record, so a call is read once and no tree of the JSON is built on the way. How the call arrived is the only difference between the two forms.
 */
public sealed interface HookBody {
    /**
     * @param type the input record of the host the call is for
     * @return the input; a field the host left out, or wrote as another type than the record's, is as the record says for an absent field
     * @throws HookRejected when the body is empty, is not UTF-8, or is not a JSON object
     */
    <T> T bind(JsonMapper mapper, Class<T> type);

    /** The body of an HTTP request: the host's own JSON, or the JSON the Sennard hook client read from the host's stdin. */
    static HookBody posted(byte[] body) {
        return new Posted(body);
    }

    /** The arguments of an MCP tool call, which the MCP layer has already read as a map of the call's fields. */
    static HookBody fields(Map<String, Object> fields) {
        return new Fields(fields);
    }

    record Posted(byte[] bytes) implements HookBody {
        /** An empty body must never reach a verdict: an earlier measured defect let one through as an allow. */
        @Override
        public <T> T bind(JsonMapper mapper, Class<T> type) {
            if (bytes == null || bytes.length == 0) {
                throw new HookRejected(HookRejection.HOOK_BODY_EMPTY);
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException notUtf8) {
                throw new HookRejected(HookRejection.HOOK_BODY_NOT_UTF8, notUtf8);
            }
            T input;
            try {
                input = mapper.readValue(text, type);
            } catch (JacksonException notAnObject) {
                throw new HookRejected(HookRejection.HOOK_BODY_NOT_A_JSON_OBJECT, notAnObject);
            }
            if (input == null) {
                throw new HookRejected(HookRejection.HOOK_BODY_NOT_A_JSON_OBJECT);
            }
            return input;
        }
    }

    record Fields(Map<String, Object> fields) implements HookBody {
        @Override
        public <T> T bind(JsonMapper mapper, Class<T> type) {
            try {
                return mapper.convertValue(fields == null ? Map.of() : fields, type);
            } catch (JacksonException notAnObject) {
                throw new HookRejected(HookRejection.HOOK_BODY_NOT_A_JSON_OBJECT, notAnObject);
            }
        }
    }
}
