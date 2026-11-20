// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tests' own way to write and read the JSON documents they feed a check: the fixtures are plain JSON and a case is a copy with one member changed. The tests' one
 * {@link JsonMapper} is also the context's bean (see {@link SkillCheckContext}).
 */
public final class TestJson {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TestJson() {
    }

    /** An object from name/value pairs, each value converted the way Jackson converts it; insertion order is kept. */
    public static ObjectNode object(Object... pairs) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.set((String) pairs[i], tree(pairs[i + 1]));
        }
        return node;
    }

    public static ArrayNode array(Collection<?> values) {
        ArrayNode node = MAPPER.createArrayNode();
        values.forEach(value -> node.add(tree(value)));
        return node;
    }

    public static JsonNode tree(Object value) {
        return value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
    }

    public static List<JsonNode> items(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }

    public static List<String> strings(JsonNode array) {
        return items(array).stream().map(JsonNode::stringValue).toList();
    }

    public static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    public static String hashBytes(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    public static String hashText(String text) {
        return hashBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String hashJson(JsonNode value) {
        return CanonicalJson.digest(value);
    }
}
