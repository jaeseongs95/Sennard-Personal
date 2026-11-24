// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.NativeSend;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Claude Code's tools say about a message to another agent: {@code SendMessage} names its recipient in {@code to} (the {@code agentId} the host gave the subagent, the {@code name}
 * its {@code Agent} call gave it, or {@code main} for the agent that started the sender), and the {@code Agent} call that starts a subagent gives that name in {@code name}. The host's facts only say where the call names its recipient, whether it
 * names the parent, and by what a started subagent can be named; that the recipient is a subagent of the session is the server's record, not the shape of the text ({@code WorkflowGateServerTest}).
 */
class ClaudeSendMessageAddressTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SennardProperties SETTINGS = new Binder(new MapConfigurationPropertySource(Map.of("sennard.hosts.claude-config-dir", "claude-config", "sennard.hosts.user-home", "home")))
            .bindOrCreate("sennard", SennardProperties.class);
    private final HostWorkFacts claude = new ClaudeWorkFacts(SETTINGS, new MsysShellPaths());

    private ToolCall call(String tool, String field, String value) {
        String json = value == null ? "{}" : MAPPER.createObjectNode().put(field, value).put("message", "continue").toString();
        return new ToolCall("toolu_1", tool, null, MAPPER.readTree(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a0123456789abcdef", "researcher", "wf_1a2b3c4d-5e6-3", "abadcafe", "6d3777e3-c387-47da-bbde-252409176730"})
    void aSendMessageNamesItsRecipientInToWhateverItsText(String to) {
        assertEquals(Optional.of(new NativeSend(to, false, false)), claude.nativeSend(call("SendMessage", "to", to)), to);
    }

    /** The host's notation for the main agent is a fact of its adapter; the server turns it into a pass only for a sender that is a subagent. */
    @Test
    void aSendMessageToMainNamesTheParent() {
        assertEquals(Optional.of(new NativeSend("main", true, false)), claude.nativeSend(call("SendMessage", "to", "main")));
        for (String notParent : List.of("Main", "main-agent", "team-lead")) {
            assertFalse(claude.nativeSend(call("SendMessage", "to", notParent)).orElseThrow().toParent(), notParent);
        }
    }

    /** Nothing in this repository says what the hand-back tool takes or whom it reaches: it states nothing, so only a recipient the server recorded could pass (none is named). */
    @Test
    void theHandbackToolStatesNothing() {
        assertEquals(Optional.of(NativeSend.unstated()), claude.nativeSend(call("SubagentHandback", "to", "main")));
        assertEquals(Optional.of(NativeSend.unstated()), claude.nativeSend(call("SubagentHandback", "to", "a0123456789abcdef")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Agent", "sendmessage", "Read"})
    void onlyTheTwoSendToolsAreNativeSends(String tool) {
        assertEquals(Optional.empty(), claude.nativeSend(call(tool, "to", "a0123456789abcdef")), tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aSendMessageThatNamesNoRecipientHasNone(String to) {
        assertEquals(Optional.of(NativeSend.unstated()), claude.nativeSend(call("SendMessage", "to", to)));
        assertEquals(Optional.of(NativeSend.unstated()), claude.nativeSend(call("SendMessage", "to", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"researcher", "a0123456789abcdef"})
    void anAgentCallThatNamesItsSubagentGivesThatNameToMessageItBy(String name) {
        assertEquals(Set.of(name), claude.subagentAddresses(call("Agent", "name", name), "agentId: ae5d09c7a1b24f601"), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendMessage", "SubagentHandback", "Read", "agent"})
    void aToolThatStartsNoSubagentGivesNoAddress(String tool) {
        assertEquals(Set.of(), claude.subagentAddresses(call(tool, "name", "researcher"), null), tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void anAgentCallWithoutANameGivesNoAddress(String name) {
        assertEquals(Set.of(), claude.subagentAddresses(call("Agent", "name", name), null));
        assertEquals(Set.of(), claude.subagentAddresses(call("Agent", "name", null), null));
        assertEquals(Optional.empty(), claude.nativeSend(call("Agent", "name", "researcher")));
    }
}
