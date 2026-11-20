// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Optional;

import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What an adapter does with a field of a host's input record: a field that is absent, or that the host wrote as another type, is the same as empty; which
 * of those matters is the adapter's rule. The functions that take a {@link JsonNode} and a field name are for what has no record of its own: the arguments
 * of a host's tool call (any tool's, so the host's tool defines them) and the lines of a host's transcript file.
 */
final class HookJson {
    /** A tool result longer than this is kept by its tail, where an error ends up. */
    private static final int TOOL_RESULT_MAX_CHARACTERS = 65_536;

    private HookJson() {
    }

    /** A text field that is not there is empty. */
    static String text(String value) {
        return value == null ? "" : value;
    }

    /** The text of a field with the white space around it removed, or none when nothing is left. */
    static Optional<String> present(String value) {
        String trimmed = text(value).trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /** The same as {@link #present(String)} for a record component that is null when the host said nothing (a record component is never an {@code Optional}). */
    static String presentOrNull(String value) {
        String trimmed = text(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A tool's arguments are an object; anything else the host wrote, or nothing, is no arguments. */
    static JsonNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? value : JsonNodeFactory.instance.objectNode();
    }

    /** The host's session identifier is required: without it no later rule can place the call. */
    static String requiredSession(String value) {
        return present(value).orElseThrow(() -> new HookRejected(HookRejection.HOOK_SESSION_ID_MISSING));
    }

    /** What a tool returned, as one text, from the end; null when it returned nothing (for a record component, which is never an {@code Optional}). */
    static String tailOrNull(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.isString() ? value.stringValue() : value.toString();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > TOOL_RESULT_MAX_CHARACTERS ? text.substring(text.length() - TOOL_RESULT_MAX_CHARACTERS) : text;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : "";
    }

    static Optional<String> present(JsonNode node, String field) {
        return present(text(node, field));
    }

    /** The first of these fields that holds a text (trimmed), or null when none does. */
    static String firstPresentOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            String found = presentOrNull(text(node, field));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isObject() ? value : JsonNodeFactory.instance.objectNode();
    }
}
