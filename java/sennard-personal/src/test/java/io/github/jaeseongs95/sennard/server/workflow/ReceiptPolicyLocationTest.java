// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The location a refused free text is reported at is a JSON Pointer (RFC 6901), so a key that holds a "/" or a "~" is escaped and the location names one place only. */
class ReceiptPolicyLocationTest {
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static String locationOfTheRefusal(String json) {
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> ReceiptPolicy.safeValue(MAPPER.readTree(json), Set.of(), "/output/output"));
        return String.valueOf(refused.details().get("location"));
    }

    @Test
    void anArrayItemAndAnObjectKeyAreAppendedAsPointerSegments() {
        assertEquals("/output/output/0", locationOfTheRefusal("[\"free text\"]"));
        assertEquals("/output/output/note", locationOfTheRefusal("{\"note\":\"free text\"}"));
        assertEquals("/output/output/items/1/note", locationOfTheRefusal("{\"items\":[\"urn:run:aaaaaaaa\",{\"note\":\"free text\"}]}"));
    }

    @Test
    void aKeyWithASlashOrATildeIsEscapedInTheLocation() {
        assertEquals("/output/output/a~1b", locationOfTheRefusal("{\"a/b\":\"free text\"}"));
        assertEquals("/output/output/a~0b", locationOfTheRefusal("{\"a~b\":\"free text\"}"));
    }
}
