// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rewriting one field of a stored JSON document (DBMS 3.2 {@code json_set} row). The documents are the service layer's contracts (a convergence root, a lease, an
 * approval record): their shape is not this store's, so no record can describe them, and Jackson's tree is the API for JSON whose structure is not known at compile time.
 * The document is parsed into an order-preserving tree with exact decimals (the mapper of the context, rebuilt with those two settings), one field is replaced in place
 * and the tree is written back, so the other fields keep their order and their number text. Service code keeps the digest-relevant parts out of what it rewrites.
 */
final class JsonRecords {
    private final JsonMapper mapper;

    /** @param context the application's {@code JsonMapper} bean; this class only adds the two settings that keep number text */
    JsonRecords(JsonMapper context) {
        this.mapper = context.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    }

    String withText(String json, String field, String value) {
        ObjectNode root = (ObjectNode) mapper.readTree(json);
        root.put(field, value);
        return mapper.writeValueAsString(root);
    }

    /** Replaces the {@code state} field with an enum constant, written the way Jackson writes an enum: by name. */
    String withState(String json, Enum<?> state) {
        ObjectNode root = (ObjectNode) mapper.readTree(json);
        root.set("state", mapper.valueToTree(state));
        return mapper.writeValueAsString(root);
    }

    String withNumber(String json, String field, long value) {
        ObjectNode root = (ObjectNode) mapper.readTree(json);
        root.put(field, value);
        return mapper.writeValueAsString(root);
    }
}
