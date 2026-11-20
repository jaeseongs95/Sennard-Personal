// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import io.github.jaeseongs95.sennard.serverit.HookAdapters;

/**
 * Antigravity sends the registered server {@code sennard} as {@code sennard_sennard} (its own prefix, measured), so the workflow gate, which lets a Sennard tool through
 * by {@link ToolCall#sennardTool()}, must read that name as Sennard's. Both the wrapped call ({@code call_mcp_tool} with {@code ServerName}) and the direct tool name
 * ({@code mcp_<server>_<tool>}) are read; the names of the 5.x product are no longer Sennard's.
 */
class AntigravitySennardToolTest {
    private static final AntigravityHookAdapter ADAPTER = HookAdapters.antigravity();

    @ParameterizedTest
    @ValueSource(strings = {"sennard_sennard", "sennard"})
    void aWrappedCallOfTheSennardServerIsASennardTool(String server) {
        assertEquals("update_session_status", wrapped(server).sennardTool());
    }

    @Test
    void theWrapperNameWithTheHostPrefixIsReadTheSame() {
        ToolCall call = tool("{\"name\":\"default_api:call_mcp_tool\",\"args\":{\"ServerName\":\"sennard_sennard\",\"ToolName\":\"record_turn_recap\",\"Arguments\":{\"k\":1}}}");
        assertEquals("record_turn_recap", call.sennardTool());
        assertEquals(1, call.input().path("k").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp_sennard_sennard_update_session_status", "default_api:mcp_sennard_sennard_update_session_status", "mcp_sennard_update_session_status"})
    void aDirectToolNameOfTheSennardServerIsASennardTool(String name) {
        assertEquals("update_session_status", tool("{\"name\":\"" + name + "\",\"args\":{}}").sennardTool());
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent-governance-suite", "agent_governance_suite", "agent-governance-suite_agent-governance-suite", "plugin_agent_governance_suite_sennard",
            "plugin_sennard_sennard", "other_sennard", "probe"})
    void aWrappedCallOfAnotherNameIsNotASennardTool(String server) {
        assertNull(wrapped(server).sennardTool());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp_agent-governance-suite_update_session_status", "mcp_agent_governance_suite_agent_governance_suite_update_session_status",
            "mcp_plugin_agent_governance_suite_sennard_x", "mcp_plugin_sennard_sennard_x", "mcp_probe_update_session_status"})
    void aDirectToolNameOfAnotherNameIsNotASennardTool(String name) {
        assertNull(tool("{\"name\":\"" + name + "\",\"args\":{}}").sennardTool());
    }

    private static ToolCall wrapped(String server) {
        return tool("{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"" + server + "\",\"ToolName\":\"update_session_status\",\"Arguments\":{}}}");
    }

    private static ToolCall tool(String toolCall) {
        String body = "{\"conversationId\":\"c1\",\"toolCall\":" + toolCall + "}";
        HostEvent read = ADAPTER.read("PreToolUse", HookBody.posted(body.getBytes(StandardCharsets.UTF_8)));
        return assertInstanceOf(HostEvent.ToolRequested.class, read).tool();
    }
}
