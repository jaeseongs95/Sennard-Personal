// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;


import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter.CallKeys;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter.CallerFacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key a call of an Antigravity tool is matched by is made of two digests. The expected hex values were computed outside this code
 * ({@code printf 'conv\0tool' | sha256sum} and the same over the sorted, compact text), so a change of the sorted mapper or of the digest helper shows here as a different key.
 */
class AntigravityCallerKeysTest {
    private static final JsonMapper APPLICATION = JsonMapper.builder().build();
    private static final String CALLER = "5bc1bbd39f0d3fd37ca9ef2ece9dafe1bd5d79da5c1ab07a81e6c08ffc16d9a0";
    private static final String ARGUMENTS = "f8445706abaebee1caef12eaa4237866d3091798bc3e34a5c8e9b0cb045b8c13";

    private final AntigravityCallerAdapter adapter = new AntigravityCallerAdapter(new SortedJsonConfiguration().sortedJsonMapper(APPLICATION));

    @Test
    void theKeyIsTheDigestOfTheSortedCompactArguments() throws Exception {
        // sorted text: {"a":{"y":[1,2.5],"z":true},"b":1,"ké":"한"}
        JsonNode arguments = APPLICATION.readTree("{\"b\":1,\"ké\":\"한\",\"a\":{\"z\":true,\"y\":[1,2.5]}}");
        CallKeys keys = adapter.callKeys(observed(), "tool", arguments).orElseThrow();
        assertEquals(CALLER, keys.caller());
        assertEquals(CALLER + ":" + ARGUMENTS, keys.call());
    }

    @Test
    void theSameArgumentsInAnotherOrderGiveTheSameKey() throws Exception {
        JsonNode one = APPLICATION.readTree("{\"a\":{\"y\":[1,2.5],\"z\":true},\"b\":1,\"ké\":\"한\"}");
        JsonNode other = APPLICATION.readTree("{\"ké\":\"한\",\"b\":1,\"a\":{\"z\":true,\"y\":[1,2.5]}}");
        assertEquals(adapter.callKeys(observed(), "tool", one), adapter.callKeys(observed(), "tool", other));
    }

    @Test
    void theApplicationMapperItselfStaysUnsortedSoOnlyThisOneBeanSorts() throws Exception {
        JsonNode arguments = APPLICATION.readTree("{\"b\":1,\"a\":2}");
        assertEquals("{\"b\":1,\"a\":2}", APPLICATION.writeValueAsString(arguments));
        assertEquals("{\"a\":2,\"b\":1}", new SortedJsonConfiguration().sortedJsonMapper(APPLICATION).writeValueAsString(arguments));
    }

    private static CallerFacts.Observed observed() {
        return new CallerFacts.Observed("conv", "conv", null, null, null, null);
    }
}
