// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The one definition of an execution id: what the runner makes is what the retention of the log files takes for the name of an execution's folder, and nothing else is.
 *
 * <pre>
 * i1  made = accepted   every id that {@link ExecutionIds#next()} makes is accepted, and they differ
 * i2  the format        the canonical lower-case text of a UUID is accepted; a name that is not, whatever it looks like, is not (a short or long name, upper case, a UUID written loosely, white
 *                       space around it, a path, null)
 * </pre>
 */
class ExecutionIdsTest {
    @Test
    void everyIdThatIsMadeIsAcceptedAndTheyDiffer() {
        Set<String> made = new HashSet<>();
        for (int count = 0; count < 200; count++) {
            String id = ExecutionIds.next();
            assertTrue(ExecutionIds.isExecutionId(id), id);
            assertEquals(id, UUID.fromString(id).toString(), "it is a UUID as the platform writes it");
            made.add(id);
        }
        assertEquals(200, made.size());
    }

    @Test
    void aNameThatIsNotTheCanonicalTextOfAUuidIsNotAnExecutionId() {
        for (String notAnId : new String[] {null, "", "one", "executions", "00000000-0000-0000-0000-00000000000", "00000000-0000-0000-0000-0000000000000", "1-1-1-1-1",
                "ABCDEF01-0000-4000-8000-000000000000", " 00000000-0000-4000-8000-000000000000", "00000000-0000-4000-8000-000000000000 ", "00000000-0000-4000-8000-000000000000.old",
                "../00000000-0000-4000-8000-000000000000", "0000000g-0000-4000-8000-000000000000", "00000000000040008000000000000000"}) {
            assertFalse(ExecutionIds.isExecutionId(notAnId), "[" + notAnId + "]");
        }
        assertTrue(ExecutionIds.isExecutionId("00000000-0000-4000-8000-000000000000"));
    }
}
