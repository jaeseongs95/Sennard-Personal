// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;

/**
 * What every host adapter reads and answers, fixed as it was before the hook boundary moved from JSON trees to records. A case is one line,
 * {@code id | what the adapter returned}: the {@link HostEvent} it read from an input, the JSON text it answered a verdict with, the rejection
 * (status and reason) it raised, or the digest of the content of a generated hook definition (its path and its JSON as compact text, not the bytes of a file, whose line
 * separator is the platform's). The inputs are the host's own field names, the right types and the
 * wrong ones: a field that is absent or of another type must still read as empty. {@code hook-adapter-golden.txt} keeps, per group of cases
 * (read, respond or definition of one host), the number of cases and the SHA-256 digest of their lines; a difference shows every line. Only the
 * {@code seam} methods know how an adapter is called, so the lines stay comparable across a change of that call.
 */
class HookAdapterGoldenTest {
    static final Map<String, HostHookAdapter> ADAPTERS = Map.of("claude", HookAdapters.claude(), "codex", HookAdapters.codex(),
            "agy", HookAdapters.antigravity(), "gemini", HookAdapters.gemini());

    /** One read case; {@link HostInputReductionTest} reads the same inputs through the hook client's reduction. */
    record Read(String host, String event, String input) {
        String id() {
            return "read " + host + " " + event + " " + input;
        }
    }

    private static Read in(String host, String event, String input) {
        return new Read(host, event, input);
    }

    private static final String CLAUDE_SESSION = "\"session_id\":\"s1\"";

    static List<Read> reads() {
        List<Read> rows = new ArrayList<>();
        for (String host : new String[] {"claude", "codex"}) {
            String s = "{" + CLAUDE_SESSION;
            for (String source : new String[] {"startup", "resume", "clear", "compact", "fork", "other", "5"}) {
                rows.add(in(host, "SessionStart", s + ",\"source\":" + (source.equals("5") ? source : "\"" + source + "\"") + "}"));
            }
            rows.add(in(host, "SessionStart", s + "}"));
            rows.add(in(host, "SessionStart", s + ",\"cwd\":\"/w\",\"workspace_id\":\" /ws \",\"transcript_path\":\"/t.jsonl\",\"model\":\"m-1\",\"effort\":{\"level\":\"high\"},\"turn_id\":\"t-9\"}"));
            rows.add(in(host, "SessionStart", s + ",\"cwd\":\"/w\",\"workspace_id\":\"  \",\"transcript_path\":5,\"model\":{},\"effort\":\"high\",\"turn_id\":null}"));
            rows.add(in(host, "SessionStart", s + ",\"effort\":{\"level\":5}}"));
            rows.add(in(host, "SessionStart", s + ",\"effort\":[1]}"));
            rows.add(in(host, "SessionStart", s + ",\"cwd\":7}"));
            rows.add(in(host, "SessionEnd", s + ",\"reason\":\"logout\"}"));
            rows.add(in(host, "SessionEnd", s + ",\"reason\":3}"));
            rows.add(in(host, "SessionEnd", s + "}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"prompt\":\"한글 \\\"q\\\" 😀\"}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"prompt\":5}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"prompt\":null}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"prompt\":[\"a\"]}"));
            rows.add(in(host, "PreToolUse", s + ",\"tool_name\":\"Bash\",\"tool_use_id\":\" tu-1 \",\"tool_input\":{\"command\":\"ls\"}}"));
            rows.add(in(host, "PreToolUse", s + ",\"tool_name\":\"Bash\",\"tool_input\":\"ls\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"tool_name\":\"Bash\",\"tool_input\":null}"));
            rows.add(in(host, "PreToolUse", s + ",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"tool_name\":7,\"tool_use_id\":8,\"tool_input\":[1]}"));
            rows.add(in(host, "PreToolUse", s + "}"));
            for (String tool : new String[] {"mcp__plugin_sennard_sennard__load_context", "mcp__sennard__load_context",
                    "mcp__sennard__load_context", "mcp__sennard__", "mcp__other__load_context", "mcp__some-sennard_lookalike__x", "__x", "x__"}) {
                rows.add(in(host, "PreToolUse", s + ",\"tool_name\":\"" + tool + "\",\"tool_input\":{\"a\":{\"b\":[1,2]}}}"));
            }
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":\"done\"}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":{\"content\":[{\"type\":\"text\",\"text\":\"PROBE\"}],\"isError\":false}}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":null}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":\"\"}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":42}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Read\",\"tool_response\":\"file text\"}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"WebFetch\",\"tool_response\":{\"a\":1}}"));
            rows.add(in(host, "PostToolUse", s + ",\"tool_name\":\"Bash\",\"tool_response\":\"" + "x".repeat(70_000) + "END\"}"));
            rows.add(in(host, "PostToolUseFailure", s + ",\"tool_name\":\"Bash\",\"error\":\"boom\"}"));
            rows.add(in(host, "PostToolUseFailure", s + ",\"tool_name\":\"Bash\",\"error\":{\"code\":3}}"));
            rows.add(in(host, "PostToolUseFailure", s + ",\"tool_name\":\"Bash\"}"));
            for (String active : new String[] {"true", "false", "\"true\"", "\"false\"", "\"yes\"", "1", "0", "null", "{}", "[true]"}) {
                rows.add(in(host, "Stop", s + ",\"stop_hook_active\":" + active + "}"));
                rows.add(in(host, "SubagentStop", s + ",\"agent_id\":\"a1\",\"stop_hook_active\":" + active + "}"));
            }
            rows.add(in(host, "Stop", s + "}"));
            rows.add(in(host, "SubagentStart", s + ",\"agent_id\":\"a1\",\"agent_type\":\"Explore\"}"));
            rows.add(in(host, "SubagentStart", s + "}"));
            rows.add(in(host, "SubagentStart", s + ",\"agent_id\":\"  \"}"));
            rows.add(in(host, "SubagentStart", s + ",\"agent_id\":null}"));
            rows.add(in(host, "SubagentStop", s + "}"));
            rows.add(in(host, "SubagentStop", s + ",\"agent_id\":5}"));
            rows.add(in(host, "SubagentStop", s + ",\"agent_id\":null}"));
            rows.add(in(host, "PreCompact", s + ",\"turn_id\":\"t1\"}"));
            rows.add(in(host, "PostCompact", s + ",\"turn_id\":\"t1\"}"));
            rows.add(in(host, "PostCompact", s + ",\"turn_id\":\"t1\",\"error\":null}"));
            rows.add(in(host, "PostCompact", s + ",\"turn_id\":\"t1\",\"error\":\"failed\"}"));
            rows.add(in(host, "PostCompact", s + ",\"error\":{}}"));
            rows.add(in(host, "PostCompact", s + ",\"error\":\"\"}"));
            rows.add(in(host, "PostModelSwitch", s + "}"));
            rows.add(in(host, "PreModelRequest", s + ",\"turn_id\":\"t1\"}"));
            rows.add(in(host, "PostModelRequest", s + ",\"turn_id\":\"t1\",\"status\":\"ok\",\"error\":null}"));
            rows.add(in(host, "BeforeAgent", s + ",\"prompt\":\"p\"}"));
            rows.add(in(host, "Nope", s + "}"));
            rows.add(in(host, "", s + "}"));
            rows.add(in(host, "stop", s + "}"));
            rows.add(in(host, "SessionStart", "{}"));
            rows.add(in(host, "Nope", "{}"));
            rows.add(in(host, "SessionStart", "{\"session_id\":\"  \"}"));
            rows.add(in(host, "SessionStart", "{\"session_id\":7}"));
            rows.add(in(host, "SessionStart", "{\"session_id\":null}"));
            rows.add(in(host, "SessionStart", "{\"session_id\":[\"s\"]}"));
            rows.add(in(host, "SessionStart", "{\"session_id\":\" trimmed \"}"));
            // The root, a preset-agent root, a child and the unusable markers.
            rows.add(in(host, "UserPromptSubmit", s + ",\"agent_type\":\"claude\",\"prompt\":\"p\"}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"agent_type\":\" \",\"prompt\":\"p\"}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"agent_type\":null,\"prompt\":\"p\"}"));
            rows.add(in(host, "UserPromptSubmit", s + ",\"agent_type\":5,\"prompt\":\"p\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":\"c\",\"agent_type\":\"Explore\",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":\"c\",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":\" c \",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":\"\",\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":null,\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":7,\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":{},\"tool_name\":\"Bash\"}"));
            rows.add(in(host, "PreToolUse", s + ",\"agent_id\":\"c\",\"agent_type\":\"\",\"tool_name\":\"Bash\"}"));
        }
        // Antigravity.
        String c = "{\"conversationId\":\"c1\"";
        rows.add(in("agy", "SessionStart", c + "}"));
        rows.add(in("agy", "SessionStart", c + ",\"workspacePaths\":[\"/w1\",\"/w2\"],\"transcriptPath\":\"/t\",\"modelName\":\"gemini-4-argon-medium\"}"));
        for (String model : new String[] {"gemini-4-argon-low", "gemini-4-argon-high", "plain-model", "model-extreme", "-low", "x-medium-y", " model-high ", "5", "null"}) {
            rows.add(in("agy", "SessionStart", c + ",\"modelName\":" + (model.equals("5") || model.equals("null") ? model : "\"" + model + "\"") + "}"));
        }
        for (String paths : new String[] {"[]", "[\"/a\"]", "[5]", "[null,\"/b\"]", "\"/s\"", "{}", "null", "[\"\"]", "[\" \"]"}) {
            rows.add(in("agy", "SessionStart", c + ",\"workspacePaths\":" + paths + "}"));
        }
        rows.add(in("agy", "SessionStart", "{}"));
        rows.add(in("agy", "SessionStart", "{\"conversationId\":\"  \"}"));
        rows.add(in("agy", "SessionStart", "{\"conversationId\":5}"));
        rows.add(in("agy", "Nope", "{}"));
        rows.add(in("agy", "Nope", c + "}"));
        rows.add(in("agy", "UserPromptSubmit", c + "}"));
        for (String number : new String[] {"0", "1", "\"0\"", "0.5", "null", "-3", "true", "[]", "{}", "\"x\""}) {
            rows.add(in("agy", "PreInvocation", c + ",\"invocationNum\":" + number + ",\"lastUserInput\":\"hello\"}"));
        }
        rows.add(in("agy", "PreInvocation", c + ",\"lastUserInput\":\"hello\"}"));
        rows.add(in("agy", "PreInvocation", c + ",\"invocationNum\":0,\"lastUserInput\":5}"));
        rows.add(in("agy", "PostInvocation", c + "}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"read_file\",\"args\":{\"Path\":\"/p\"}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"default_api:read_file\",\"args\":{\"Path\":\"/p\"}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"load_context\",\"Arguments\":{\"k\":1}}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"default_api:call_mcp_tool\",\"args\":{\"ServerName\":\"plugin_sennard_sennard\",\"ToolName\":\"t\",\"Arguments\":{}}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"\",\"Arguments\":{}}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"t\"}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"t\",\"Arguments\":\"text\"}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"probe\",\"ToolName\":\"t\",\"Arguments\":{}}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":5,\"ToolName\":7}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"mcp_sennard_load_context\",\"args\":{\"a\":1}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"default_api:mcp_sennard_load_context\",\"args\":{}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"mcp_plugin_sennard_sennard_x\",\"args\":{}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":\"mcp_probe_x\",\"args\":{}}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":{\"name\":5,\"args\":[]}}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":\"text\"}"));
        rows.add(in("agy", "PreToolUse", c + ",\"toolCall\":null}"));
        rows.add(in("agy", "PreToolUse", c + "}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"run_command\",\"args\":{\"CommandLine\":\"ls\"}},\"error\":\"\"}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"run_command\",\"args\":{\"CommandLine\":\"ls\"}},\"error\":\"exit 1\"}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"run_command\",\"args\":{}},\"artifactDirectoryPath\":\"/brain/c1\"}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"run_command\",\"args\":\"x\"},\"artifactDirectoryPath\":\"/brain/c1\"}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"call_mcp_tool\",\"args\":{\"ServerName\":\"sennard\",\"ToolName\":\"t\",\"Arguments\":{\"z\":1}}},\"artifactDirectoryPath\":\"/brain/c1\"}"));
        rows.add(in("agy", "PostToolUse", c + ",\"toolCall\":{\"name\":\"run_command\",\"args\":{}},\"artifactDirectoryPath\":5,\"error\":null}"));
        rows.add(in("agy", "PostToolUse", c + ",\"error\":{}}"));
        rows.add(in("agy", "Stop", c + "}"));
        for (String field : new String[] {"\"error\":\"e\"", "\"error\":\"\"", "\"error\":null", "\"error\":5", "\"fullyIdle\":false", "\"fullyIdle\":true", "\"fullyIdle\":\"false\"", "\"fullyIdle\":\"true\"",
                "\"fullyIdle\":0", "\"fullyIdle\":1", "\"fullyIdle\":null", "\"fullyIdle\":{}", "\"executionNum\":3", "\"executionNum\":4", "\"executionNum\":\"5\"", "\"executionNum\":4.9",
                "\"executionNum\":null", "\"executionNum\":[]", "\"terminationReason\":\"USER_CANCELED\"", "\"terminationReason\":\"ERROR\"", "\"terminationReason\":\"MAX_INVOCATIONS\"",
                "\"terminationReason\":\"MAX_FORCED_INVOCATIONS\"", "\"terminationReason\":\"MAX_TOKEN_BUDGET_EXCEEDED\"", "\"terminationReason\":\"TERMINAL_STEP_TYPE\"",
                "\"terminationReason\":\"TERMINAL_CUSTOM_HOOK\"", "\"terminationReason\":\"STOP\"", "\"terminationReason\":7", "\"terminationReason\":\" ERROR\""}) {
            rows.add(in("agy", "Stop", c + "," + field + "}"));
        }

        // Gemini.
        String g = "{\"session_id\":\"gem-1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/work\"";
        for (String source : new String[] {"startup", "resume", "clear", "compact", "fork", "x"}) {
            rows.add(in("gemini", "SessionStart", g + ",\"source\":\"" + source + "\"}"));
        }
        rows.add(in("gemini", "SessionStart", g + ",\"source\":5}"));
        rows.add(in("gemini", "SessionStart", g + "}"));
        rows.add(in("gemini", "SessionEnd", g + ",\"reason\":\"exit\"}"));
        rows.add(in("gemini", "SessionEnd", g + ",\"reason\":[]}"));
        rows.add(in("gemini", "BeforeAgent", g + ",\"prompt\":\"한글 \\\"q\\\"\"}"));
        for (String active : new String[] {"true", "false", "\"yes\"", "\"true\"", "1", "null"}) {
            rows.add(in("gemini", "AfterAgent", g + ",\"stop_hook_active\":" + active + "}"));
        }
        rows.add(in("gemini", "AfterAgent", g + "}"));
        rows.add(in("gemini", "BeforeModel", g + ",\"llm_request\":{\"model\":\"gemini-3-pro\",\"messages\":[{\"role\":\"user\"}]}}"));
        rows.add(in("gemini", "BeforeModel", g + ",\"llm_request\":{\"model\":\"  \"}}"));
        rows.add(in("gemini", "BeforeModel", g + ",\"llm_request\":\"x\"}"));
        rows.add(in("gemini", "BeforeModel", g + ",\"llm_request\":{\"model\":7}}"));
        rows.add(in("gemini", "BeforeModel", g + "}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"x\",\"llm_request\":{\"model\":\"gemini-3-pro\"}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"run_shell_command\",\"tool_input\":{\"command\":\"ls\"}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"x\",\"tool_input\":\"s\"}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"t\",\"tool_input\":{},\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"load_context\"}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"t\",\"tool_input\":{},\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"\"}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"t\",\"tool_input\":{},\"mcp_context\":{\"server_name\":\"other\",\"tool_name\":\"load_context\"}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"t\",\"mcp_context\":{\"server_name\":5,\"tool_name\":7}}"));
        rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"t\",\"mcp_context\":\"x\"}"));
        for (String name : new String[] {"mcp_sennard_load_context", "mcp_sennard_x", "sennard__y", "sennard__z", "mcp_sennard_",
                "mcp_other_x"}) {
            rows.add(in("gemini", "BeforeTool", g + ",\"tool_name\":\"" + name + "\",\"tool_input\":{\"a\":1}}"));
        }
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"done\",\"returnDisplay\":\"d\"}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":[{\"text\":\"a\"}],\"returnDisplay\":\"d\"}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"done\",\"error\":{\"message\":\"bad\"}}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"done\",\"error\":\"bad\"}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"done\",\"error\":null}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"error\":\"\"}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{}}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":\"text\"}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":null}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\"}"));
        rows.add(in("gemini", "AfterTool", g + ",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"" + "y".repeat(70_000) + "END\"}}"));
        rows.add(in("gemini", "PreCompress", g + "}"));
        for (String name : new String[] {"AfterModel", "BeforeToolSelection", "Notification", "beforeagent", "SessionStart2"}) {
            rows.add(in("gemini", name, g + "}"));
        }
        rows.add(in("gemini", "Nope", "{}"));
        rows.add(in("gemini", "SessionStart", "{}"));
        rows.add(in("gemini", "SessionStart", "{\"session_id\":\"\"}"));
        rows.add(in("gemini", "SessionStart", "{\"session_id\":3}"));
        return rows;
    }

    private record Respond(String host, String event, String input, String verdictName, HookVerdict verdict) {
        String id() {
            return "respond " + host + " " + event + " " + verdictName + " after " + input;
        }
    }

    private static List<Respond> responds() {
        Map<String, HookVerdict> verdicts = new java.util.LinkedHashMap<>();
        verdicts.put("Proceed", new HookVerdict.Proceed());
        verdicts.put("AddContext1", new HookVerdict.AddContext(List.of(new ContextBlock("hello", false))));
        verdicts.put("AddContext1Repeat", new HookVerdict.AddContext(List.of(new ContextBlock("cue", true))));
        verdicts.put("AddContext3", new HookVerdict.AddContext(List.of(new ContextBlock("a", true), new ContextBlock("b\n", false), new ContextBlock("한글 \"q\" \u2028", false))));
        verdicts.put("Refuse", new HookVerdict.Refuse("R \"quoted\" 😀"));
        verdicts.put("ContinueTurn", new HookVerdict.ContinueTurn("go\non"));
        List<Respond> rows = new ArrayList<>();
        Map<String, List<String>> events = new java.util.LinkedHashMap<>();
        events.put("claude", List.of("SessionStart", "SessionEnd", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure", "Stop", "SubagentStart", "SubagentStop", "PreCompact",
                "PostCompact", "PostModelSwitch"));
        events.put("codex", List.of("SessionStart", "SessionEnd", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SubagentStart", "SubagentStop", "PreCompact", "PostCompact",
                "PreModelRequest", "PostModelRequest", "PostModelSwitch"));
        events.put("agy", List.of("SessionStart", "PreInvocation", "PostInvocation", "PreToolUse", "PostToolUse", "Stop"));
        events.put("gemini", List.of("SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "BeforeTool", "AfterTool", "PreCompress"));
        events.forEach((host, names) -> {
            for (String event : names) {
                String input = switch (host) {
                    case "agy" -> event.equals("PreInvocation") ? "{\"conversationId\":\"c1\",\"invocationNum\":0,\"lastUserInput\":\"p\"}"
                            : "{\"conversationId\":\"c1\",\"toolCall\":{\"name\":\"run_command\",\"args\":{}}}";
                    case "gemini" -> "{\"session_id\":\"g1\",\"tool_name\":\"x\",\"tool_response\":{\"llmContent\":\"r\"}}";
                    default -> "{\"session_id\":\"s1\",\"tool_name\":\"Bash\",\"tool_response\":\"r\",\"agent_id\":\"a1\"}";
                };
                verdicts.forEach((name, verdict) -> rows.add(new Respond(host, event, input, name, verdict)));
            }
        });
        // An antigravity Stop that must not be continued reads as a TurnEnded whatever the flag; a tool refused outside its boundary.
        verdicts.forEach((name, verdict) -> rows.add(new Respond("agy", "Stop", "{\"conversationId\":\"c1\",\"error\":\"e\"}", name, verdict)));
        return rows;
    }

    /** Three installations: a POSIX one, one whose programs have spaces, and one with the shortest words; the definition is the same text for each on every OS. */
    private static final List<HookInstallation> INSTALLATIONS = List.of(
            new HookInstallation(List.of("/opt/jdk/bin/java", "-cp", "/opt/sennard/lib/sennard-hook-client.jar", "io.github.jaeseongs95.sennard.hookclient.HookClientMain",
                    "--server-file=/home/user/.sennard/server.properties", "--jar=/opt/sennard/versions/6.0.0/sennard-6.0.0.jar"),
                    List.of("/opt/jdk/bin/java", "-cp", "/opt/sennard/lib/sennard-hook-client.jar", "io.github.jaeseongs95.sennard.hookclient.McpRelayMain",
                            "--server-file=/home/user/.sennard/server.properties", "--jar=/opt/sennard/versions/6.0.0/sennard-6.0.0.jar")),
            new HookInstallation(List.of("C:\\Program Files\\Java\\bin\\java.exe", "-cp", "C:\\Program Files\\Sennard\\lib\\sennard-hook-client.jar",
                    "io.github.jaeseongs95.sennard.hookclient.HookClientMain", "--server-file=C:\\Users\\Some One\\.sennard\\server.properties", "--jar=C:\\Program Files\\Sennard\\sennard-6.0.0.jar"),
                    List.of("C:\\Program Files\\Java\\bin\\java.exe", "-cp", "C:\\Program Files\\Sennard\\lib\\sennard-hook-client.jar", "io.github.jaeseongs95.sennard.hookclient.McpRelayMain",
                            "--server-file=C:\\Users\\Some One\\.sennard\\server.properties", "--jar=C:\\Program Files\\Sennard\\sennard-6.0.0.jar")),
            new HookInstallation(List.of("java", "HookClientMain", "--server-file=s.properties"), List.of("java", "McpRelayMain", "--server-file=s.properties")));

    // ---- the only place that knows how an adapter is called ------------------------------------------------------------------------------------

    private static HostEvent seamRead(HostHookAdapter adapter, String event, String input) {
        return adapter.read(event, HookBody.posted(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String seamRespond(HostHookAdapter adapter, String event, HostEvent read, HookVerdict verdict) {
        return HookAdapters.MAPPER.writeValueAsString(adapter.respond(event, read, verdict));
    }

    private static String seamDefinition(HostHookAdapter adapter, HookInstallation installation) {
        // The definition's content as compact JSON, not the pretty-printed file text: a file is written with the platform's line separator, which is correct on each OS and not part
        // of what an adapter generates.
        StringBuilder text = new StringBuilder();
        for (HostRegistrationFile file : adapter.registrationFiles(installation)) {
            text.append(file.relativePath().toString().replace('\\', '/'));
            if (file instanceof HostRegistrationFile.Member member) {
                text.append(" member ").append(member.jsonPointer());
            }
            text.append('\n').append(file.content().toString()).append('\n');
        }
        return text.toString();
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------

    private static String line(java.util.function.Supplier<String> call) {
        try {
            return summarize(call.get());
        } catch (HookRejected rejected) {
            return "REJECT " + rejected.status() + " " + rejected.reason();
        }
    }

    /** A long text is kept as its length and digest: the case is the long text, not the line. */
    private static String summarize(String text) {
        if (text.length() <= 800) {
            return text;
        }
        return "len=" + text.length() + " sha256=" + sha256(text);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** The first 32 hex digits of the SHA-256 of a group's lines. */
    private static String digest(String line) {
        return sha256(line).substring(0, 32);
    }

    private static String shorten(String id) {
        return id.length() <= 160 ? id : id.substring(0, 120) + "...#" + sha256(id).substring(0, 12);
    }

    private static List<String> actual() {
        List<String> lines = new ArrayList<>();
        for (Read row : reads()) {
            lines.add(shorten(row.id()) + " | " + line(() -> seamRead(ADAPTERS.get(row.host()), row.event(), row.input()).toString()));
        }
        for (Respond row : responds()) {
            lines.add(shorten(row.id()) + " | " + line(() -> {
                HostHookAdapter adapter = ADAPTERS.get(row.host());
                HostEvent read = seamRead(adapter, row.event(), row.input());
                return seamRespond(adapter, row.event(), read, row.verdict());
            }));
        }
        for (Map.Entry<String, HostHookAdapter> adapter : new java.util.TreeMap<>(ADAPTERS).entrySet()) {
            for (HookInstallation installation : INSTALLATIONS) {
                lines.add("definition " + adapter.getKey() + " " + installation.hookExecutable() + " | "
                        + line(() -> "sha256=" + sha256(seamDefinition(adapter.getValue(), installation))));
            }
        }
        return lines;
    }

    private static List<String> golden() {
        try (InputStream stream = HookAdapterGoldenTest.class.getResourceAsStream("/hook-adapter-golden.txt")) {
            if (stream == null) {
                return List.of();
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    @Test
    void everyAdapterReadsAndAnswersAsItDidBeforeTheRecordBoundary() {
        Map<String, List<String>> groups = new java.util.TreeMap<>();
        for (String line : actual()) {
            String[] words = line.split(" ", 3);
            groups.computeIfAbsent(words[0] + " " + words[1], key -> new ArrayList<>()).add(line);
        }
        List<String> actual = groups.entrySet().stream().map(group -> group.getKey() + " " + group.getValue().size() + " " + digest(String.join("\n", group.getValue()))).toList();
        List<String> golden = golden();
        if (!golden.equals(actual)) {
            throw new AssertionError("The cases differ from the golden.\nactual groups (group, cases, digest):\n" + String.join("\n", actual) + "\nall lines:\n"
                    + groups.values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.joining("\n")));
        }
    }
}
