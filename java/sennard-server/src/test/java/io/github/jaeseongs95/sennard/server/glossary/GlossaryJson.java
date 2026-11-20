// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import java.util.Collection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The glossary tests' way to write the JSON documents they compare: one mapper, objects from pairs, arrays from collections. */
final class GlossaryJson {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private GlossaryJson() {
    }

    /** An object from name/value pairs, each value converted the way Jackson converts it; insertion order is kept. */
    static ObjectNode object(Object... pairs) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.set((String) pairs[i], tree(pairs[i + 1]));
        }
        return node;
    }

    static ArrayNode array(Collection<?> values) {
        ArrayNode node = MAPPER.createArrayNode();
        values.forEach(value -> node.add(tree(value)));
        return node;
    }

    static JsonNode tree(Object value) {
        return value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
    }
}
