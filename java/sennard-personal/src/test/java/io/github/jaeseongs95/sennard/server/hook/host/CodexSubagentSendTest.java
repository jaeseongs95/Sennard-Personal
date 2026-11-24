// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.NativeSend;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Codex's tools say about a message to another agent or thread, as codex 0.171.0's source states it (codex-rs): {@code send_message} and {@code followup_task} name the recipient in
 * {@code target} and the host delivers them only inside the sender's agent tree; {@code send_input} names a thread id the host does not check against the tree; the TUI's
 * {@code mcp__codex_tui__send_message_to_thread} sends a prompt to any thread by {@code threadId}. And {@code spawn_agent} gives the names to send by: the task name it was asked for and what
 * it returned, the {@code agent_id} of the version-1 tool or the canonical {@code task_name} of the version-2 one (core/src/tools/handlers/multi_agents_spec.rs).
 */
class CodexSubagentSendTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final HostWorkFacts codex = new CodexWorkFacts(new SkillFileReads(""), MAPPER, new MsysShellPaths());

    private ToolCall call(String tool, String field, String value) {
        String json = value == null ? "{}" : MAPPER.createObjectNode().put(field, value).put("message", "continue").toString();
        return new ToolCall("call_1", tool, null, MAPPER.readTree(json));
    }

    private static final String THREAD = "019a6d3e-1f6a-7c11-9a55-0d8b2f4e7c10";

    /**
     * The version-2 tools reach only an agent of the sender's own tree: the host refuses a recipient its registry of the tree does not know, a thread id included
     * (agent/control/api.rs {@code send}, {@code ensure_agent_known}), so the facts say the host keeps them inside the delegation, and {@code /root} names the tree's root.
     *
     * <p>The hook's name of a version-2 tool is its namespace and its name with nothing between (core/src/tools/mod.rs {@code flat_tool_name}), and the default namespace is
     * {@code collaboration} (core/src/config/mod.rs): {@code collaborationsend_message}. The bare names are what the hook sees when no namespace is set.
     */
    @ParameterizedTest
    @ValueSource(strings = {"send_message", "followup_task", "collaborationsend_message", "collaborationfollowup_task"})
    void theVersionTwoSendsStayInsideTheDelegationWhateverTheirTarget(String tool) {
        assertEquals(Optional.of(new NativeSend(THREAD, false, true)), codex.nativeSend(call(tool, "target", THREAD)), tool);
        assertEquals(Optional.of(new NativeSend("/root/researcher", false, true)), codex.nativeSend(call(tool, "target", "/root/researcher")), tool);
        assertEquals(Optional.of(new NativeSend("/root", true, true)), codex.nativeSend(call(tool, "target", "/root")), tool);
        assertEquals(Optional.of(new NativeSend(null, false, true)), codex.nativeSend(call(tool, "target", null)), tool);
        assertEquals(Optional.of(new NativeSend(null, false, true)), codex.nativeSend(call(tool, "target", " ")), tool);
    }

    /** The version-1 tool takes a thread id that the host does not check against the tree (it reaches any thread of the process): only a recorded recipient can pass. */
    @Test
    void theVersionOneSendIsNotKeptInsideTheDelegationByTheHost() {
        assertEquals(Optional.of(new NativeSend(THREAD, false, false)), codex.nativeSend(call("multi_agent_v1send_input", "target", THREAD)));
        assertEquals(Optional.of(new NativeSend("/root", false, false)), codex.nativeSend(call("multi_agent_v1send_input", "target", "/root")));
        assertEquals(Optional.of(NativeSend.unstated()), codex.nativeSend(call("multi_agent_v1send_input", "target", null)));
    }

    /**
     * The task tool that sends a prompt to any thread of the app server by {@code threadId}, another session included: a send to the outside of the delegation unless the thread is
     * recorded. The TUI's is {@code mcp__codex_tui__send_message_to_thread}; the app's tool of the same name in the {@code codex_app} namespace, which the host treats as the same tool,
     * is judged the same way. When the app registers {@code codex_app} as a dynamic tool namespace and not as an MCP server, the hook's name joins the two with nothing between,
     * {@code codex_appsend_message_to_thread}: the source does not say which, so both are classified.
     */
    @ParameterizedTest
    @ValueSource(strings = {"mcp__codex_tui__send_message_to_thread", "mcp__codex_app__send_message_to_thread", "codex_appsend_message_to_thread"})
    void theTaskToolsThatMessageAThreadNameItInThreadId(String tool) {
        assertEquals(Optional.of(new NativeSend(THREAD, false, false)), codex.nativeSend(call(tool, "threadId", THREAD)), tool);
        assertEquals(Optional.of(NativeSend.unstated()), codex.nativeSend(call(tool, "target", THREAD)), tool);
    }

    /** The one table decides both the hook's matcher and the judgment: every name the matcher selects is classified, and nothing else is. */
    @ParameterizedTest
    @ValueSource(strings = {"multi_agent_v1send_input", "send_message", "followup_task", "collaborationsend_message", "collaborationfollowup_task", "mcp__codex_tui__send_message_to_thread",
            "mcp__codex_app__send_message_to_thread", "codex_appsend_message_to_thread"})
    void everyNameTheHooksMatcherSelectsIsClassifiedAsASend(String tool) {
        assertTrue(CodexWorkFacts.NATIVE_PEER_SEND.matcher(tool).matches(), tool);
        assertTrue(codex.nativeSend(call(tool, "target", THREAD)).isPresent(), tool);
    }

    /**
     * Not sends (create_thread and fork_thread start a session, which a later unit classifies by kind), and the names that only look like one: a third party's
     * {@code send_message} tool reaches the hook as {@code mcp__<server>__send_message} or {@code <namespace>send_message}, and the flat name of a namespace Sennard does not know
     * cannot be told from it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"spawn_agent", "collaborationspawn_agent", "wait_agent", "collaborationwait_agent", "close_agent", "interrupt_agent", "exec_command", "mcp__codex_app__wait_threads",
            "mcp__codex_tui__create_thread", "mcp__codex_tui__fork_thread", "mcp__codex_app__create_thread", "mcp__codex_app__fork_thread", "mcp__codex_tui__read_thread",
            "mcp__codex_tui__wait_threads", "send_message_to_thread", "codex_appsend_message_to_threads", "mcp__slack__send_message", "slacksend_message"})
    void aToolThatIsNotASendIsNoNativeSend(String tool) {
        assertEquals(Optional.empty(), codex.nativeSend(call(tool, "target", "x")), tool);
        assertFalse(CodexWorkFacts.NATIVE_PEER_SEND.matcher(tool).matches(), tool);
    }

    /** {@code spawn_agent} is the hook's bare name for version 1 (core/src/tools/registry.rs {@code function_hook_tool_name}); version 2 carries its namespace. */
    @ParameterizedTest
    @ValueSource(strings = {"spawn_agent", "collaborationspawn_agent"})
    void spawnAgentGivesTheIdItReturnedAndTheTaskNameItWasAskedFor(String tool) {
        ToolCall spawn = call(tool, "task_name", "researcher");
        assertEquals(Set.of("researcher", "019a6d3e-1f6a-7c11-9a55-0d8b2f4e7c10"), codex.subagentAddresses(spawn, "{\"agent_id\":\"019a6d3e-1f6a-7c11-9a55-0d8b2f4e7c10\",\"nickname\":null}"), tool);
        assertEquals(Set.of("researcher", "/root/researcher"), codex.subagentAddresses(spawn, "{\"task_name\":\"/root/researcher\",\"nickname\":\"Hegel\"}"), tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spawn_agent", "collaborationspawn_agent"})
    void aResultThatIsNotWhatTheToolStatesLeavesOnlyTheTaskNameAsked(String tool) {
        ToolCall spawn = call(tool, "task_name", "researcher");
        assertEquals(Set.of("researcher"), codex.subagentAddresses(spawn, null), tool);
        assertEquals(Set.of("researcher"), codex.subagentAddresses(spawn, "not json at all"), tool);
        assertEquals(Set.of("researcher"), codex.subagentAddresses(spawn, "\"agent spawned\""), tool);
        assertEquals(Set.of(), codex.subagentAddresses(call(tool, "message", "x"), "{\"nickname\":null}"), tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send_message", "followup_task", "collaborationsend_message", "multi_agent_v1send_input", "wait_agent", "exec_command"})
    void aToolThatStartsNoSubagentGivesNoAddress(String tool) {
        assertEquals(Set.of(), codex.subagentAddresses(call(tool, "task_name", "researcher"), "{\"agent_id\":\"019a6d3e-1f6a-7c11-9a55-0d8b2f4e7c10\"}"), tool);
    }
}
