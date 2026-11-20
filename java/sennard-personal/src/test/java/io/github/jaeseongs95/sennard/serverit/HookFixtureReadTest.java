// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;

/**
 * Host stdin as really recorded on each OS, read into {@link HostEvent}. The fixtures are bodies from the host measurements with
 * identifiers replaced; what each case expects is what the host wrote, not what the adapter happens to do.
 */
class HookFixtureReadTest {
    private static final Map<String, HostHookAdapter> ADAPTERS = Map.of("claude", HookAdapters.claude(), "codex", HookAdapters.codex(), "agy", HookAdapters.antigravity());

    /** What a host wrote, as the transport hands it to an adapter. */
    private static HookBody parse(String text) {
        return HookBody.posted(text.getBytes(StandardCharsets.UTF_8));
    }

    private static HostEvent read(String os, String host, String event, String file) {
        return ADAPTERS.get(host).read(event, parse(HookTestSupport.fixtureText(os, host, file)));
    }

    static Stream<Arguments> everyFixture() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String os : new String[] {"linux", "windows"}) {
            for (String host : new String[] {"claude", "codex"}) {
                rows.add(Arguments.of(os, host, "SessionStart", "SessionStart", HostEvent.SessionStarted.class));
                rows.add(Arguments.of(os, host, "UserPromptSubmit", "UserPromptSubmit", HostEvent.UserPromptSubmitted.class));
                rows.add(Arguments.of(os, host, "PreToolUse", "PreToolUse", HostEvent.ToolRequested.class));
                rows.add(Arguments.of(os, host, "PostToolUse", "PostToolUse", HostEvent.ToolFinished.class));
                rows.add(Arguments.of(os, host, "Stop", "Stop", HostEvent.TurnEnded.class));
                rows.add(Arguments.of(os, host, "SessionEnd", "SessionEnd", HostEvent.SessionEnded.class));
            }
            rows.add(Arguments.of(os, "agy", "SessionStart", "SessionStart", HostEvent.SessionStarted.class));
            rows.add(Arguments.of(os, "agy", "PreInvocation", "PreInvocation", HostEvent.TurnStarted.class));
            rows.add(Arguments.of(os, "agy", "PreInvocation", "PreInvocation-next", HostEvent.ModelCallStarting.class));
            rows.add(Arguments.of(os, "agy", "PreToolUse", "PreToolUse", HostEvent.ToolRequested.class));
            rows.add(Arguments.of(os, "agy", "PostToolUse", "PostToolUse", HostEvent.ToolFinished.class));
            rows.add(Arguments.of(os, "agy", "PostInvocation", "PostInvocation", HostEvent.ModelCallFinished.class));
            rows.add(Arguments.of(os, "agy", "Stop", "Stop", HostEvent.TurnEnded.class));
        }
        return rows.build();
    }

    @ParameterizedTest(name = "{0} {1} {2} ({3})")
    @MethodSource("everyFixture")
    void everyRecordedInputIsReadAsItsEvent(String os, String host, String event, String file, Class<? extends HostEvent> expected) {
        HostEvent read = read(os, host, event, file);

        assertInstanceOf(expected, read);
        HostContext context = read.context();
        assertEquals(HookTestSupport.SESSION, context.sessionId());
        assertEquals(host, context.hostLabel().label());
        assertEquals(HostContext.Role.ROOT, context.actor().role(), "a recorded top-level call has no child marker");
        assertEquals(host.equals("agy") ? HostContext.Basis.PROVISIONAL : HostContext.Basis.INFERRED, context.actor().basis());
        assertTrue(context.workspace().contains("proj"), context.workspace());
    }

    /** The same tool call as each host writes it. */
    @Test
    void aClaudeToolCallKeepsItsIdAndInput() {
        ToolCall tool = assertInstanceOf(HostEvent.ToolRequested.class, read("linux", "claude", "PreToolUse", "PreToolUse")).tool();
        assertEquals("mcp__plugin_sennard-probe_probe__probe_echo", tool.name());
        assertTrue(tool.callId().startsWith("toolu_fixture_"), tool.callId());
        assertNull(tool.sennardTool(), "the probe's tool is not a Sennard tool");
        assertEquals("A1", tool.input().path("note").stringValue(""));
    }

    @Test
    void aCodexToolCallKeepsItsIdAndInput() {
        ToolCall tool = assertInstanceOf(HostEvent.ToolRequested.class, read("linux", "codex", "PreToolUse", "PreToolUse")).tool();
        assertEquals("mcp__probe__probe_echo", tool.name());
        assertTrue(tool.callId().startsWith("exec-"), tool.callId());
        assertEquals("A1", tool.input().path("note").stringValue(""));
    }

    /** Antigravity reports every MCP call through one wrapper and has no call id. */
    @Test
    void anAntigravityWrapperCallHasNoIdAndKeepsTheWrapperArguments() {
        ToolCall tool = assertInstanceOf(HostEvent.ToolRequested.class, read("windows", "agy", "PreToolUse", "PreToolUse")).tool();
        assertEquals("call_mcp_tool", tool.name());
        assertNull(tool.callId());
        assertNull(tool.sennardTool(), "its server is the probe");
        assertEquals("probe_echo", tool.input().path("ToolName").stringValue(""));
        assertEquals("A1", tool.input().path("Arguments").path("note").stringValue(""));
    }

    /** Not recorded: the measurement had no Sennard server, so this one input is the recorded call with the server's name changed. */
    @Test
    void anSennardToolCallUnderTheWrapperIsKnownAsAnSennardTool() {
        String recorded = HookTestSupport.fixtureText("windows", "agy", "PreToolUse");
        HookBody changed =parse(recorded.replace("\"ServerName\":\"probe\"", "\"ServerName\":\"sennard\""));
        assertTrue(recorded.contains("\"ServerName\":\"probe\""), "the fixture must still contain what this test changes");

        ToolCall tool = assertInstanceOf(HostEvent.ToolRequested.class, HookAdapters.antigravity().read("PreToolUse", changed)).tool();

        assertEquals("probe_echo", tool.sennardTool());
        assertEquals("A1", tool.input().path("note").stringValue(""), "the arguments are the wrapped call's");
    }

    @Test
    void anSennardToolIsRecognisedByTheHostPrefixAloneOnClaudeAndCodex() {
        HookBody input = parse("{\"session_id\":\"s\",\"tool_name\":\"mcp__plugin_sennard_sennard__load_context\",\"tool_input\":{}}");
        ToolCall tool = assertInstanceOf(HostEvent.ToolRequested.class, HookAdapters.claude().read("PreToolUse", input)).tool();
        assertEquals("load_context", tool.sennardTool());

        HookBody foreign =parse("{\"session_id\":\"s\",\"tool_name\":\"mcp__some-sennard_lookalike__load_context\",\"tool_input\":{}}");
        assertNull(assertInstanceOf(HostEvent.ToolRequested.class, HookAdapters.codex().read("PreToolUse", foreign)).tool().sennardTool());
    }

    @Test
    void aFinishedToolKeepsOnlyWhatTheHostReported() {
        HostEvent.ToolFinished codex = assertInstanceOf(HostEvent.ToolFinished.class, read("linux", "codex", "PostToolUse", "PostToolUse"));
        assertTrue(codex.resultTail().contains("PROBE-OK"));
        assertFalse(codex.reportedFailure());
        assertTrue(codex.stepBoundary(), "PostToolUse context reaches the next model call");

        HostEvent.ToolFinished antigravity = assertInstanceOf(HostEvent.ToolFinished.class, read("linux", "agy", "PostToolUse", "PostToolUse"));
        assertNull(antigravity.resultTail(), "this host's hook has no tool output");
        assertFalse(antigravity.stepBoundary(), "this host delivers context from the invocation boundary");

        HostEvent.ToolFinished claude = assertInstanceOf(HostEvent.ToolFinished.class, read("windows", "claude", "PostToolUse", "PostToolUse"));
        assertTrue(claude.resultTail().contains("PROBE-OK note=A1"));
    }

    @Test
    void aClaudeReadToolResultIsNotKeptAsAnOutcome() {
        HookBody input = parse("{\"session_id\":\"s\",\"tool_name\":\"Read\",\"tool_input\":{},\"tool_response\":\"file text\",\"tool_use_id\":\"t\"}");
        assertNull(assertInstanceOf(HostEvent.ToolFinished.class, HookAdapters.claude().read("PostToolUse", input)).resultTail());
    }

    /**
     * AGS3-D-0169: a Write that succeeded answers with the file it wrote, and a file that talks about a missing path or a refused permission is not a failed run.
     * The shape is the host's documented Write response (type, filePath, content); no Write PostToolUse is among the recorded fixtures.
     */
    @Test
    void aClaudeFileToolSuccessIsNotKeptAsAnOutcomeButItsFailureIs() {
        HookBody written = parse("{\"session_id\":\"s\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"/p/plan.md\"},\"tool_response\":{\"type\":\"update\",\"filePath\":\"/p/plan.md\","
                + "\"content\":\"ENOENT and Permission denied are what the plan is about\"},\"tool_use_id\":\"t\"}");
        HostEvent.ToolFinished success = assertInstanceOf(HostEvent.ToolFinished.class, HookAdapters.claude().read("PostToolUse", written));
        assertNull(success.resultTail());
        assertFalse(success.reportedFailure());

        HookBody refused = parse("{\"session_id\":\"s\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"/p/plan.md\"},\"error\":\"EACCES: permission denied\",\"tool_use_id\":\"t\"}");
        HostEvent.ToolFinished failure = assertInstanceOf(HostEvent.ToolFinished.class, HookAdapters.claude().read("PostToolUseFailure", refused));
        assertTrue(failure.reportedFailure());
        assertEquals("EACCES: permission denied", failure.resultTail());

        HookBody shell = parse("{\"session_id\":\"s\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"x\"},\"tool_response\":{\"stdout\":\"\",\"stderr\":\"command not found\"},\"tool_use_id\":\"t\"}");
        assertTrue(assertInstanceOf(HostEvent.ToolFinished.class, HookAdapters.claude().read("PostToolUse", shell)).resultTail().contains("command not found"));
    }

    @Test
    void aLongToolResultIsKeptByItsEnd() {
        String result = "x".repeat(70_000) + "END-OF-THE-RESULT";
        HookBody input = parse("{\"session_id\":\"s\",\"tool_name\":\"Bash\",\"tool_response\":\"" + result + "\"}");
        String tail = assertInstanceOf(HostEvent.ToolFinished.class, HookAdapters.codex().read("PostToolUse", input)).resultTail();
        assertEquals(65_536, tail.length());
        assertTrue(tail.endsWith("END-OF-THE-RESULT"));
    }

    @Test
    void aSubagentIsTheChildItSaysItIs() {
        HookBody marked =parse("{\"session_id\":\"s\",\"agent_id\":\"child-7\",\"tool_name\":\"Bash\"}");
        HostContext context = HookAdapters.claude().read("PreToolUse", marked).context();
        assertEquals(HostContext.Role.CHILD, context.actor().role());
        assertEquals("child-7", context.actor().agentKey());

        HookBody malformed =parse("{\"session_id\":\"s\",\"agent_id\":\"  \",\"tool_name\":\"Bash\"}");
        assertEquals(HostContext.Role.INDETERMINATE, HookAdapters.codex().read("PreToolUse", malformed).context().actor().role(),
                "a marker that is present but unusable must never be read as the root");
    }

    /** Claude Code sets agent_type on every hook of a --agent session and agent_id only inside a subagent. */
    @Test
    void anAgentTypeWithoutAnAgentIdIsARootThatTheMainListLeavesOut() {
        HostContext.SessionActor preset = HookAdapters.claude().read("UserPromptSubmit", parse("{\"session_id\":\"s\",\"agent_type\":\"claude\",\"prompt\":\"p\"}")).context().actor();
        assertEquals(HostContext.Role.ROOT, preset.role(), "still the root of its session: who may call what does not change");
        assertEquals(HostContext.Lead.PRESET_AGENT, preset.lead());

        HostContext.SessionActor plain = HookAdapters.claude().read("UserPromptSubmit", parse("{\"session_id\":\"s\",\"prompt\":\"p\"}")).context().actor();
        assertEquals(HostContext.Role.ROOT, plain.role());
        assertEquals(HostContext.Lead.MAIN, plain.lead());

        HostContext.SessionActor child = HookAdapters.claude().read("PreToolUse", parse("{\"session_id\":\"s\",\"agent_id\":\"c\",\"agent_type\":\"Explore\",\"tool_name\":\"Bash\"}")).context().actor();
        assertEquals(HostContext.Role.CHILD, child.role(), "inside a subagent the subagent's marker decides");

        HostContext.SessionActor blank = HookAdapters.claude().read("UserPromptSubmit", parse("{\"session_id\":\"s\",\"agent_type\":\" \",\"prompt\":\"p\"}")).context().actor();
        assertEquals(HostContext.Role.INDETERMINATE, blank.role(), "a marker that is present but unusable is not read as a root");
    }

    @Test
    void aSubagentEventWithoutItsMarkerIsNotTheRoot() {
        HookBody input = parse("{\"session_id\":\"s\"}");
        assertEquals(HostContext.Role.INDETERMINATE, HookAdapters.claude().read("SubagentStart", input).context().actor().role());
        assertEquals(HostContext.Role.INDETERMINATE, HookAdapters.codex().read("SubagentStop", input).context().actor().role());
    }

    @Test
    void modelAndEffortAreReportedAsTheHostWroteThem() {
        assertEquals("gemini-4-argon", read("linux", "agy", "SessionStart", "SessionStart").context().model());
        assertEquals("medium", read("linux", "agy", "SessionStart", "SessionStart").context().effort());
        assertEquals("medium", read("linux", "claude", "PreToolUse", "PreToolUse").context().effort());
        assertEquals("gpt-6.1-sol", read("linux", "codex", "SessionStart", "SessionStart").context().model());
    }

    @Test
    void aTurnThatWasAlreadyContinuedSaysSo() {
        HookBody input = parse("{\"session_id\":\"s\",\"stop_hook_active\":true}");
        assertTrue(assertInstanceOf(HostEvent.TurnEnded.class, HookAdapters.claude().read("Stop", input)).alreadyContinued());
    }

    @Test
    void aMissingSessionIdIsRefusedInsteadOfGuessed() {
        for (String body : new String[] {"{}", "{\"session_id\":\"  \"}", "{\"session_id\":7}"}) {
            HookRejected rejected = assertThrows(HookRejected.class, () -> HookAdapters.claude().read("SessionStart", parse(body)));
            assertEquals("HOOK_SESSION_ID_MISSING", rejected.reason());
            assertEquals(400, rejected.status());
        }
        assertEquals("HOOK_SESSION_ID_MISSING", assertThrows(HookRejected.class, () -> HookAdapters.antigravity().read("SessionStart", parse("{}"))).reason());
    }

    @Test
    void anEventTheHostDoesNotHaveIsRefused() {
        HookBody input = parse("{\"session_id\":\"s\",\"conversationId\":\"s\"}");
        assertEquals("HOOK_EVENT_UNKNOWN", assertThrows(HookRejected.class, () -> HookAdapters.codex().read("PreInvocation", input)).reason());
        assertEquals("HOOK_EVENT_UNKNOWN", assertThrows(HookRejected.class, () -> HookAdapters.antigravity().read("UserPromptSubmit", input)).reason());
    }
}
