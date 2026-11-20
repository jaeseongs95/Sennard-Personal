// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one digest and the scope of the operation of a write call (AGS3-D-0267). Cases planned before the code:
 * <ol>
 * <li>the digest leaves out the key, the session reference and the response mode, for the argument map of one tool and for the tree the workflow tools read, so the same content has the
 * same digest whatever the caller added or named;</li>
 * <li>another content has another digest, and the order of the members does not matter (canonical JSON);</li>
 * <li>a tool with no session yet has the global scope, and a key of another shape is refused.</li>
 * </ol>
 */
class OperationKeysTest {
    private static final String KEY = "operation-key-0000000000001";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final OperationKeys keys = new OperationKeys(mapper);

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    @Test
    void theKeyTheSessionReferenceAndTheResponseModeAreNotContent() {
        String plain = OperationKeys.digest(mapper.valueToTree(arguments("summary", "a line")));
        assertEquals(plain, OperationKeys.digest(mapper.valueToTree(arguments("summary", "a line", "operationKey", KEY))));
        assertEquals(plain, OperationKeys.digest(mapper.valueToTree(arguments("operationKey", KEY, "sessionRef", "as1_ABCDEFGHJKMNPQRSTVWXYZ0123", "summary", "a line", "responseMode", "compact"))));
        assertEquals(keys.global("open_session", arguments("operationKey", KEY, "sessionRef", "as1_X", "a", 1)).digest(), keys.global("open_session", arguments("a", 1, "operationKey", KEY)).digest());
    }

    @Test
    void anotherContentIsAnotherDigestAndTheOrderOfMembersIsNot() {
        assertNotEquals(OperationKeys.digest(mapper.valueToTree(arguments("summary", "a line"))), OperationKeys.digest(mapper.valueToTree(arguments("summary", "another line"))));
        assertEquals(OperationKeys.digest(mapper.valueToTree(arguments("a", 1, "b", 2))), OperationKeys.digest(mapper.valueToTree(arguments("b", 2, "a", 1))));
    }

    @Test
    void aCallWithNoSessionYetHasTheGlobalScopeAndAKeyOfAnotherShapeIsRefused() {
        OperationKey operation = keys.global("open_session", arguments("operationKey", KEY));
        assertEquals(OperationKey.GLOBAL_SCOPE, operation.scope());
        assertEquals(KEY, operation.key());
        assertThrows(InvalidInputException.class, () -> keys.global("open_session", arguments("operationKey", "short")));
        assertThrows(InvalidInputException.class, () -> keys.global("open_session", arguments("summary", "no key")));
    }
}
