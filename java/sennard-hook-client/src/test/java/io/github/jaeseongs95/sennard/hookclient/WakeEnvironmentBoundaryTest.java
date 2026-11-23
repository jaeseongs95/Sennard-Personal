// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WakeEnvironmentBoundaryTest {
    @Test
    void presentEmptyValuesKeepTheirPrecedence() throws Exception {
        Map<String, String> environment = Map.of("SENNARD_CODEX_QUEUE_WAKE", "", "PATH", "");
        assertEquals(environment, WakeEnvironment.forHost("codex", environment).orElseThrow());
    }

    @Test
    void localEnvironmentSelectionPreservesUnicodeAndExcludesUnrelatedValues() throws Exception {
        String path = " C:\\한글\\quoted\"=a:b";
        assertEquals(Map.of("PATH", path), WakeEnvironment.forHost("codex", Map.of(
                "PATH", path, "UNRELATED_SECRET", "fixture", "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "0")).orElseThrow());
        assertEquals(Map.of("PATH", path, "CLAUDE_CONFIG_DIR", "/fixture/claude"), WakeEnvironment.forHost("claude", Map.of(
                "PATH", path, "CLAUDE_CONFIG_DIR", "/fixture/claude", "CODEX_HOME", "/fixture/codex",
                "UNRELATED_SECRET", "fixture")).orElseThrow());
        assertTrue(WakeEnvironment.forHost("gemini", Map.of("PATH", path)).isEmpty());
        assertTrue(WakeEnvironment.forHost("../agy", Map.of("PATH", path)).isEmpty());
    }
}
