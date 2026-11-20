// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.board.SessionBoardService;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON this area writes, from records and not from text put together by hand. What they say is what the identity area stores.
 *
 * <pre>
 * s1  open_session's delivery     the text the identity area has always stored for a session nothing can wake: the same bytes as the old literal
 * s2  the board's role source      the lower-case words of the published contract; no source is null
 * </pre>
 */
class SessionRecordsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void s1TheDeliveryOfASessionNothingCanWakeIsTheOldText() {
        assertEquals("{\"wakeVisibility\":\"none\",\"canWakeSilently\":false,\"idleWake\":\"none\",\"supportedInjection\":[]}",
                MAPPER.writeValueAsString(new SessionTools.Delivery("none", false, "none", List.of())));
    }

    @Test
    void s2TheRoleSourceIsSaidInLowerCase() {
        assertEquals("\"signed\"", MAPPER.writeValueAsString(SessionBoardService.RoleSource.SIGNED));
        assertEquals("\"self\"", MAPPER.writeValueAsString(SessionBoardService.RoleSource.SELF));
        assertEquals("null", MAPPER.writeValueAsString((SessionBoardService.RoleSource) null));
    }
}
