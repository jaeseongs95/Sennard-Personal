// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.messaging.WakeController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What each host's adapter writes for an installation ({@link HostHookAdapter#registrationFiles}) and what a plugin package ships ({@link HostHookAdapter#shippedFiles}): the hook
 * definition against the table of hook kinds (which hook is a command of the Sennard hook client, which is an MCP tool call), the failure behaviour, and the registration of the MCP
 * server (the Sennard relay as a stdio server). The command lines are compared exactly, because the hook client's options are the contract between a definition and
 * {@code HookClientMain}; that the hook client then does what they say is {@link HookLauncherProcessTest}.
 *
 * <p>A definition names no port, no URL and no address: the server's port is the operating system's choice at each start, so what a definition holds is the installation's own program
 * (its Java, its jar, the file where the server announces its address), which finds the server. Nothing here depends on the OS: the same definition is correct on every OS.
 *
 * <p>Case table (input: an installation whose two programs are the words below, the program first; expected: how each host's definition carries them; judged by comparing the strings
 * and the lists of the definition's JSON):
 * <ol>
 * <li>Claude Code (exec form): every hook is a command: {@code command} is the first word as it is and {@code args} are the other words as they are, then the call's arguments; the same
 * with spaces in the words; tool gates ask the host to block when the hook fails</li>
 * <li>Codex, Gemini CLI, Antigravity (a command string): the first word, the other words and the call's arguments joined by spaces; a word with a space in double quotes, and a
 * word that only has {@code =}, {@code :} or {@code ,} not; a plain program leaves no double quote in Antigravity's definition</li>
 * <li>a word no quoting survives (a quote, a percent sign) after the first: refused for the three string hosts when the definition is generated; Claude's list has no quoting to survive</li>
 * <li>the MCP registration of every host is the relay's command and its argument list (no shell, no quoting), with the host's label and the wait for the server's start; Gemini CLI's is a
 * member of its manifest, the others' a file</li>
 * <li>the files a package ships are the whole files with nothing registered, and no host's package holds a port, an address or a program</li>
 * </ol>
 */
class HookRegistrationFileTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String JAVA = "C:/Sennard/jdk/bin/java.exe";
    private static final String HOOK_MAIN = "io.github.jaeseongs95.sennard.hookclient.HookClientMain";
    private static final String RELAY_MAIN = "io.github.jaeseongs95.sennard.hookclient.McpRelayMain";
    private static final String SERVER_FILE = "--server-file=C:/Users/me/.sennard/server.properties";
    private static final String JAR = "--jar=C:/Sennard/versions/6.0.0/sennard-6.0.0.jar";
    /** An installation's own programs, none of them with a space or a shell operator: {@code =} and {@code :} and {@code ,} are in words after the first. */
    private static final List<String> HOOK_WORDS = List.of(JAVA, "-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:AOTCache=C:/Sennard/sennard-hook.aot", "-cp",
            "C:/Sennard/lib/sennard-hook-client-6.0.0.jar", HOOK_MAIN, SERVER_FILE, JAR);
    private static final List<String> MCP_WORDS = List.of(JAVA, "-XX:+UseSerialGC", "-Xmx128m", "-cp", "C:/Sennard/lib/sennard-hook-client-6.0.0.jar", RELAY_MAIN, SERVER_FILE, JAR);
    private static final HookInstallation INSTALLATION = new HookInstallation(HOOK_WORDS, MCP_WORDS);
    private static final String HOOK_LINE = String.join(" ", HOOK_WORDS);
    private static final List<String> HOOK_TAIL = HOOK_WORDS.subList(1, HOOK_WORDS.size());
    private static final List<String> MCP_TAIL = MCP_WORDS.subList(1, MCP_WORDS.size());
    /** The same shape with a space in the program, in a value of an option and in the class path. */
    private static final List<String> SPACED_WORDS = List.of("C:\\Program Files\\Java\\bin\\java.exe", "-XX:AOTCache=C:\\Program Files\\Sennard\\sennard-hook.aot", "-cp",
            "C:\\Program Files\\Sennard\\lib\\sennard-hook-client-6.0.0.jar", HOOK_MAIN, "--server-file=C:\\Users\\Some One\\.sennard\\server.properties", "--jar=C:\\Program Files\\Sennard\\sennard-6.0.0.jar");
    private static final String SPACED_LINE = "\"C:\\Program Files\\Java\\bin\\java.exe\" \"-XX:AOTCache=C:\\Program Files\\Sennard\\sennard-hook.aot\" -cp "
            + "\"C:\\Program Files\\Sennard\\lib\\sennard-hook-client-6.0.0.jar\" " + HOOK_MAIN + " \"--server-file=C:\\Users\\Some One\\.sennard\\server.properties\" \"--jar=C:\\Program Files\\Sennard\\sennard-6.0.0.jar\"";
    private static final String ATTENDED = "CLAUDE_CODE_SESSION_ATTENDED=1";
    /** What only a session start asks of the hook client: the installed server is started when no live server is announced. */
    private static final String START_SERVER = "--start-server=" + HookClientCall.START_SERVER_SECONDS;

    private static List<HostRegistrationFile> claude() {
        return HookAdapters.claude().registrationFiles(INSTALLATION);
    }

    private static List<HostRegistrationFile> codex() {
        return HookAdapters.codex().registrationFiles(INSTALLATION);
    }

    private static List<HostRegistrationFile> antigravity() {
        return HookAdapters.antigravity().registrationFiles(INSTALLATION);
    }

    private static List<HostRegistrationFile> gemini() {
        return HookAdapters.gemini().registrationFiles(INSTALLATION);
    }

    /** The hook definition of a host's files: the whole file named {@code hooks.json}. */
    private static HostRegistrationFile.WholeFile hooks(List<HostRegistrationFile> files) {
        List<HostRegistrationFile.WholeFile> found = files.stream().filter(HostRegistrationFile.WholeFile.class::isInstance).map(HostRegistrationFile.WholeFile.class::cast)
                .filter(file -> file.relativePath().getFileName().toString().equals("hooks.json")).toList();
        assertEquals(1, found.size(), files.toString());
        return found.get(0);
    }

    private static JsonNode entry(HostRegistrationFile.WholeFile file, String event, int index) {
        JsonNode node = file.content().path("hooks").path(event).path(index);
        assertFalse(node.isMissingNode(), event + "[" + index + "] of " + file.relativePath());
        return node;
    }

    private static JsonNode handler(HostRegistrationFile.WholeFile file, String event, int index) {
        return entry(file, event, index).path("hooks").path(0);
    }

    /** The one handler of a hook of a bundle in agy's definition, whether or not the bundle wraps it in a matcher entry. */
    private static JsonNode agyHandler(HostRegistrationFile.WholeFile file, String bundle, String event) {
        JsonNode hooks = file.content().path(bundle).path(event).path(0);
        return hooks.has("hooks") ? hooks.path("hooks").path(0) : hooks;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue("")));
        return values;
    }

    /** What each event of a Claude or Codex definition is made of: the type of every handler, in the order the event lists them. */
    private static Map<String, List<String>> kinds(HostRegistrationFile.WholeFile file) {
        Map<String, List<String>> kinds = new LinkedHashMap<>();
        file.content().path("hooks").properties().forEach(event -> {
            List<String> types = new ArrayList<>();
            event.getValue().forEach(group -> {
                assertEquals(1, group.path("hooks").size(), event.getKey() + ": " + group);
                types.add(group.path("hooks").path(0).path("type").stringValue(""));
            });
            kinds.put(event.getKey(), types);
        });
        return kinds;
    }

    /** Every handler of a definition, wherever it sits: the objects that have a {@code type}. */
    private static List<JsonNode> handlers(JsonNode node) {
        List<JsonNode> found = new ArrayList<>();
        collectHandlers(node, found);
        return found;
    }

    private static void collectHandlers(JsonNode node, List<JsonNode> found) {
        if (node.isObject()) {
            if (node.path("type").isString()) {
                found.add(node);
            }
            node.values().forEach(child -> collectHandlers(child, found));
        } else if (node.isArray()) {
            node.forEach(child -> collectHandlers(child, found));
        }
    }

    /** Every string a definition holds, keys not included. */
    private static List<String> allStrings(JsonNode node) {
        List<String> found = new ArrayList<>();
        collectStrings(node, found);
        return found;
    }

    private static void collectStrings(JsonNode node, List<String> found) {
        if (node.isString()) {
            found.add(node.stringValue());
        } else if (node.isObject()) {
            node.values().forEach(child -> collectStrings(child, found));
        } else if (node.isArray()) {
            node.forEach(child -> collectStrings(child, found));
        }
    }

    private static Map<String, String> input(JsonNode mcpHandler) {
        Map<String, String> input = new LinkedHashMap<>();
        mcpHandler.path("input").properties().forEach(field -> input.put(field.getKey(), field.getValue().stringValue("")));
        return input;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private record HostCase(String host, HostHookAdapter adapter) {
        @Override
        public String toString() {
            return host;
        }
    }

    static Stream<HostCase> everyHost() {
        return Stream.of(new HostCase("claude", HookAdapters.claude()), new HostCase("codex", HookAdapters.codex()),
                new HostCase("antigravity", HookAdapters.antigravity()), new HostCase("gemini", HookAdapters.gemini()));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Claude

    /**
     * Every event is a command of the hook client: Claude Code's own HTTP hook takes a fixed URL, and the server's port is the operating system's choice at each start.
     * SessionStart accepts no HTTP hook anyway, and the wake is a command's field.
     */
    @Test
    void claudeEveryEventIsACommand() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("SessionStart", List.of("command", "command"));
        for (String event : List.of("SubagentStart", "SubagentStop", "PreCompact", "PostCompact", "PostModelSwitch")) {
            expected.put(event, List.of("command"));
        }
        expected.put("PreToolUse", List.of("command", "command", "command"));
        for (String event : List.of("UserPromptSubmit", "PostToolUseFailure", "PostToolUse")) {
            expected.put(event, List.of("command"));
        }
        expected.put("Stop", List.of("command", "command"));
        expected.put("SessionEnd", List.of("command"));
        assertEquals(expected, kinds(hooks(claude())));
        assertEquals(Path.of("hooks", "hooks.json"), hooks(claude()).relativePath());
    }

    @Test
    void claudeHooksAreTheHookClientInExecFormWithTheEventsOptionsAndNoPort() {
        HostRegistrationFile.WholeFile file = hooks(claude());
        JsonNode prompt = handler(file, "UserPromptSubmit", 0);
        assertEquals("command", prompt.path("type").stringValue());
        assertEquals(JAVA, prompt.path("command").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "UserPromptSubmit")), strings(prompt.path("args")));
        assertEquals(10, prompt.path("timeout").intValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "PostToolUseFailure")), strings(handler(file, "PostToolUseFailure", 0).path("args")));
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "PreToolUse", "--module=continuity", "--on-failure=block-stderr")),
                strings(handler(file, "PreToolUse", 1).path("args")));
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "Stop")), strings(handler(file, "Stop", 0).path("args")));
        for (JsonNode command : handlers(file.content())) {
            assertTrue(allStrings(command).stream().noneMatch(text -> text.contains("--port") || text.contains("http")), "no port and no URL: " + command);
        }
    }

    @Test
    void claudeSennardAndNativePeerHooksBlockOnFailure() {
        HostRegistrationFile.WholeFile file = hooks(claude());
        JsonNode governance = entry(file, "PreToolUse", 0);
        assertEquals("^mcp__plugin_sennard_sennard__.+$", governance.path("matcher").stringValue());
        assertEquals("command", governance.path("hooks").path(0).path("type").stringValue());
        assertEquals("block", governance.path("hooks").path(0).path("onFailure").stringValue(""), "a Sennard tool without its admission record is refused");
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "PreToolUse", "--module=governance", "--on-failure=block-stderr")),
                strings(governance.path("hooks").path(0).path("args")));

        String continuityMatcher = entry(file, "PreToolUse", 1).path("matcher").stringValue();
        assertTrue("Read".matches(continuityMatcher));
        assertTrue("Bash".matches(continuityMatcher));
        assertTrue("mcp__files__read".matches(continuityMatcher));
        assertFalse("mcp__plugin_sennard_sennard__start_inquiry_workflow".matches(continuityMatcher));
        assertEquals("block", handler(file, "PreToolUse", 1).path("onFailure").stringValue());
        JsonNode nativePeer = entry(file, "PreToolUse", 2);
        assertEquals("^(SendMessage|SubagentHandback)$", nativePeer.path("matcher").stringValue());
        JsonNode nativeHandler = nativePeer.path("hooks").path(0);
        assertEquals("block", nativeHandler.path("onFailure").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "PreToolUse", "--module=native-peer", "--on-failure=block-stderr")), strings(nativeHandler.path("args")));
        assertFalse(handler(file, "Stop", 0).has("onFailure"));
        assertFalse(handler(file, "SessionStart", 0).has("onFailure"), "the session start answers a down server through the hook client, not through the host");
    }

    @Test
    void claudeSessionEndIsBoundedByTheHostsExitBudgetAndTheClientGivesUpBeforeTheHost() {
        JsonNode sessionEnd = handler(hooks(claude()), "SessionEnd", 0);
        assertEquals("command", sessionEnd.path("type").stringValue());
        assertEquals(5, sessionEnd.path("timeout").intValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "SessionEnd", "--timeout=4")), strings(sessionEnd.path("args")), "the client's wait is a second less than the host's limit");
    }

    /**
     * The session start is a command in exec form (the host's own words: only command and MCP tool hooks run at SessionStart), so the hook client finds the host's process,
     * starts the server when none is running, and answers a down server with the guidance on stderr and exit 2: shown to the user, blocks nothing.
     */
    @Test
    void claudeSessionStartIsTheHookClientInExecFormAndShowsTheGuidanceWhenTheServerIsDown() {
        JsonNode start = handler(hooks(claude()), "SessionStart", 0);
        assertEquals("command", start.path("type").stringValue());
        assertEquals(JAVA, start.path("command").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host", START_SERVER)), strings(start.path("args")));
        assertEquals(HookClientCall.SESSION_START_LIMIT_SECONDS, start.path("timeout").intValue(), "the host's limit holds the server start's wait");
        assertFalse(start.has("asyncRewake"));
    }

    /**
     * The wake is a command because {@code asyncRewake} is a field of a command hook. The session is idle from its start and from the end of each turn, so both events carry it:
     * registered only at the start, it woke a session once and within the first minute (Linux real-host run of f1c9d06a). The hook client itself checks that Claude Code marks the
     * session as attended, so a {@code -p} session is not held by the wait.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SessionStart", "Stop"})
    void claudeWakeIsAnAsyncRewakeAtEveryIdlePointThatOnlyAnAnsweredWaitCanWake(String event) {
        JsonNode wake = handler(hooks(claude()), event, 1);
        assertEquals("command", wake.path("type").stringValue());
        assertTrue(wake.path("asyncRewake").booleanValue());
        assertEquals(JAVA, wake.path("command").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", event, "--module=wake", "--timeout=86400", "--wake", "--only-if-env=" + ATTENDED)), strings(wake.path("args")));
        assertEquals(86_460, wake.path("timeout").intValue());
    }

    /** The server answers before the hook client gives up, and the host, which enforces the timeout of this kind of hook, waits longer than the hook client. */
    @ParameterizedTest
    @ValueSource(strings = {"SessionStart", "Stop"})
    void theServersHoldEndsBeforeTheHookClientsWaitAndTheHostsTimeoutAfterIt(String event) {
        JsonNode wake = handler(hooks(claude()), event, 1);
        long clientSeconds = strings(wake.path("args")).stream().filter(argument -> argument.startsWith("--timeout=")).mapToLong(argument -> Long.parseLong(argument.substring("--timeout=".length()))).findFirst().orElseThrow();
        assertTrue(WakeController.DEFAULT_HOLD_MS / 1000 < clientSeconds, WakeController.DEFAULT_HOLD_MS + " ms < " + clientSeconds + " s");
        assertTrue(clientSeconds < wake.path("timeout").longValue(), clientSeconds + " < " + wake.path("timeout"));
    }

    @Test
    void claudeRegistersTheRelayAsAStdioServerInThePluginsMcpJson() {
        HostRegistrationFile mcp = claude().get(1);

        assertEquals(new HostRegistrationFile.WholeFile(Path.of(".mcp.json"), mcp.content()), mcp);
        JsonNode server = mcp.content().path("mcpServers").path("sennard");
        assertEquals(Set.of("mcpServers"), Set.copyOf(mcp.content().propertyNames()));
        assertEquals(JAVA, server.path("command").stringValue());
        assertEquals(concat(MCP_TAIL, List.of("--host=claude", START_SERVER)), strings(server.path("args")));
        assertEquals(List.of("command", "args"), List.copyOf(server.propertyNames()), "a stdio server: no url, no headers (the relay sends the host's label)");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Codex

    /**
     * The host's documentation fixes SessionStart (it can run before an MCP server is ready) and SessionEnd (no MCP tool hooks) as commands. An mcp_tool hook can name only fields
     * its event always has (see {@link #codexMcpToolPlaceholdersAreFieldsTheEventAlwaysHas}), and the host leaves {@code agent_id} out of every event of the main session but
     * SubagentStart and SubagentStop, so every event that may carry it is a command, which reads the whole event. Stop also uses a command to renew the wake binding.
     */
    @Test
    void codexTheEventsWithAnOptionalAgentIdAreCommandsAndTheOthersAreMcpToolCalls() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("SessionStart", List.of("command"));
        expected.put("UserPromptSubmit", List.of("command"));
        expected.put("SubagentStart", List.of("mcp_tool"));
        expected.put("SubagentStop", List.of("mcp_tool"));
        expected.put("PreCompact", List.of("command"));
        expected.put("PostCompact", List.of("command"));
        expected.put("PreToolUse", List.of("command", "command", "command"));
        expected.put("PostToolUse", List.of("command"));
        expected.put("Stop", List.of("command"));
        expected.put("PreModelRequest", List.of("command"));
        expected.put("PostModelRequest", List.of("command"));
        expected.put("SessionEnd", List.of("command"));
        assertEquals(expected, kinds(hooks(codex())));
        assertEquals(Path.of("hooks", "hooks.json"), hooks(codex()).relativePath());
    }

    /** A down server leaves the guidance as this hook's stdout, which the host takes as context; the hook client says so itself. */
    @Test
    void codexSessionStartIsTheHookClientLineThatPrintsTheGuidanceOnStdoutWhenTheServerIsDown() {
        JsonNode start = handler(hooks(codex()), "SessionStart", 0);
        assertEquals(HOOK_LINE + " hook codex SessionStart --on-failure=guidance-stdout --bind-host " + START_SERVER, start.path("command").stringValue());
        assertEquals(HookClientCall.SESSION_START_LIMIT_SECONDS, start.path("timeout").intValue(), "the host's limit holds the server start's wait");
        assertEquals(8192, start.path("additionalContextLimit").intValue());
        assertFalse(start.has("commandWindows"), "one command line for every OS: the hook client is a program, not a shell form");
    }

    @Test
    void codexSessionEndIsBoundedByTheHostsExitBudget() {
        JsonNode sessionEnd = handler(hooks(codex()), "SessionEnd", 0);
        assertEquals(HOOK_LINE + " hook codex SessionEnd --timeout=1", sessionEnd.path("command").stringValue());
        assertEquals(3, sessionEnd.path("timeout").intValue());
        assertFalse(sessionEnd.has("additionalContextLimit"));
    }

    @Test
    void codexMcpToolHooksCallTheSennardToolWithTheEventAndTheFieldsTheHostFills() {
        HostRegistrationFile.WholeFile file = hooks(codex());
        assertEquals(Map.of("event", "SubagentStart", "session_id", "${session_id}", "transcript_path", "${transcript_path}", "cwd", "${cwd}", "model", "${model}",
                "agent_id", "${agent_id}"), input(handler(file, "SubagentStart", 0)));
        assertEquals(Map.of("event", "SubagentStop", "session_id", "${session_id}", "transcript_path", "${transcript_path}", "cwd", "${cwd}", "model", "${model}",
                "agent_id", "${agent_id}", "stop_hook_active", "${stop_hook_active}"), input(handler(file, "SubagentStop", 0)));
    }

    /**
     * The defect this guards (Codex: "hook input placeholder `${agent_id}` was not found"): the host fails an mcp_tool hook when a placeholder names a field its event lacks, with no
     * default or optional form, and the main session's events carry no agent_id. The table is what the host's source says each event always has (openai/codex, codex-rs/hooks/src/schema.rs
     * and events/model_request.rs), for the events that are tool calls; every placeholder of the generated definition must be in it.
     */
    private static final Map<String, Set<String>> CODEX_ALWAYS = Map.of(
            "SubagentStart", Set.of("session_id", "transcript_path", "cwd", "model", "turn_id", "agent_id"),
            "SubagentStop", Set.of("session_id", "transcript_path", "cwd", "model", "turn_id", "agent_id", "stop_hook_active"));

    private static List<String> placeholders(JsonNode mcpHandler) {
        List<String> names = new ArrayList<>();
        mcpHandler.path("input").properties().forEach(field -> {
            String value = field.getValue().stringValue("");
            if (value.startsWith("${")) {
                assertTrue(value.endsWith("}") && value.indexOf('.') < 0, "a whole-value placeholder of a top-level field: " + value);
                names.add(value.substring(2, value.length() - 1));
            }
        });
        return names;
    }

    @Test
    void codexMcpToolPlaceholdersAreFieldsTheEventAlwaysHas() {
        int checked = 0;
        for (JsonNode handler : handlers(hooks(codex()).content())) {
            if (!handler.path("type").stringValue().equals("mcp_tool")) {
                continue;
            }
            String event = handler.path("input").path("event").stringValue();
            Set<String> always = CODEX_ALWAYS.get(event);
            assertTrue(always != null, event + " is an mcp_tool hook, but the table of what the host always sends has no row for it");
            for (String name : placeholders(handler)) {
                assertTrue(always.contains(name), event + ": ${" + name + "} is not a field the host always sends: " + always);
            }
            checked++;
        }
        assertEquals(CODEX_ALWAYS.size(), checked);
    }

    /** The command receives the recorded root Stop body, including its turn identity and continuation flag. */
    @ParameterizedTest
    @ValueSource(strings = {"linux", "windows"})
    void codexStopBindsTheHostUsingTheRecordedMainSessionInput(String os) {
        JsonNode stop = handler(hooks(codex()), "Stop", 0);
        assertEquals("command", stop.path("type").stringValue());
        assertEquals(HOOK_LINE + " hook codex Stop --bind-host", stop.path("command").stringValue());
        assertEquals(10, stop.path("timeout").intValue());
        assertFalse(stop.has("input"));
        JsonNode recorded = MAPPER.readTree(HookTestSupport.fixture(os, "codex", "Stop"));
        assertFalse(recorded.has("agent_id"));
        assertTrue(recorded.path("turn_id").isString());
        assertTrue(recorded.path("stop_hook_active").isBoolean());
    }

    /** What the main session's input leaves out is read from a command's stdin, so a subagent is still told from it: no event whose agent_id is optional is a tool call. */
    @Test
    void codexTheEventsThatMayCarryAnAgentIdAreCommandsOfTheHookClient() {
        HostRegistrationFile.WholeFile file = hooks(codex());
        for (String event : List.of("UserPromptSubmit", "PreCompact", "PostCompact", "PostToolUse", "PreModelRequest", "PostModelRequest")) {
            JsonNode handler = handler(file, event, 0);
            assertEquals("command", handler.path("type").stringValue(), event);
            assertEquals(HOOK_LINE + " hook codex " + event, handler.path("command").stringValue());
            assertEquals(10, handler.path("timeout").intValue(), event);
            assertFalse(handler.has("input"), event);
        }
    }

    @Test
    void codexPreToolUseRegistersWaitContinuityAndNativePeerCommands() {
        HostRegistrationFile.WholeFile file = hooks(codex());
        assertEquals("^mcp__codex_app__wait_threads$", entry(file, "PreToolUse", 0).path("matcher").stringValue());
        assertEquals(HOOK_LINE + " hook codex PreToolUse --module=peer-wait", handler(file, "PreToolUse", 0).path("command").stringValue());
        assertFalse(entry(file, "PreToolUse", 1).has("matcher"), "continuity must run for every PreToolUse event Codex emits");
        assertEquals(HOOK_LINE + " hook codex PreToolUse --module=continuity --on-failure=deny-pre-tool-use", handler(file, "PreToolUse", 1).path("command").stringValue());
        assertEquals("^(multi_agent_v1send_input|send_message|followup_task)$", entry(file, "PreToolUse", 2).path("matcher").stringValue());
        assertEquals(HOOK_LINE + " hook codex PreToolUse --module=native-peer --on-failure=deny-pre-tool-use", handler(file, "PreToolUse", 2).path("command").stringValue());
        assertEquals("^(manual|auto)$", entry(file, "PreCompact", 0).path("matcher").stringValue());
        assertEquals("^(manual|auto)$", entry(file, "PostCompact", 0).path("matcher").stringValue());
        assertFalse(handler(file, "UserPromptSubmit", 0).path("command").stringValue().contains("--module"), "an event without a module names none");
    }

    @Test
    void codexMcpToolHooksCarryNoCommandAndNoOtherServerOrTool() {
        for (JsonNode handler : handlers(hooks(codex()).content())) {
            if (handler.path("type").stringValue().equals("mcp_tool")) {
                assertFalse(handler.has("command") || handler.has("commandWindows") || handler.has("args"), handler.toString());
                assertEquals("sennard", handler.path("server").stringValue());
                assertEquals("codex_hook", handler.path("tool").stringValue());
            }
        }
    }

    /** The {@code mcp_tool} hooks reach the server through this registration: the relay, which finds the server at the port it announced. */
    @Test
    void codexRegistersTheRelayAsAStdioServerInThePackagesMcpJson() {
        HostRegistrationFile mcp = codex().get(1);

        assertEquals(Path.of(".mcp.json"), mcp.relativePath());
        JsonNode server = mcp.content().path("mcpServers").path("sennard");
        assertEquals(JAVA, server.path("command").stringValue());
        assertEquals(concat(MCP_TAIL, List.of("--host=codex", START_SERVER)), strings(server.path("args")));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Antigravity

    /** The host documents only command handlers, so every hook is the hook client; the failure answers are the hook client's, not a shell line. */
    @Test
    void antigravityEveryHookIsTheHookClientWithItsOwnFailureAnswer() {
        HostRegistrationFile.WholeFile file = hooks(antigravity());
        assertEquals(Path.of("hooks.json"), file.relativePath());
        assertEquals(HOOK_LINE + " hook agy SessionStart --on-failure=guidance-steps --bind-host " + START_SERVER,
                agyHandler(file, "sennard-session-lifecycle", "SessionStart").path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy PreInvocation", agyHandler(file, "sennard-session-lifecycle", "PreInvocation").path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy PostInvocation", agyHandler(file, "sennard-session-lifecycle", "PostInvocation").path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy Stop --bind-host", agyHandler(file, "sennard-session-lifecycle", "Stop").path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy PreToolUse --on-failure=deny-decision --on-refusal=deny-decision", agyHandler(file, "sennard-tool-boundary", "PreToolUse").path("command").stringValue());
        JsonNode nativePeer = file.content().path("sennard-tool-boundary").path("PreToolUse").path(1);
        assertEquals("^(default_api:)?send_message$", nativePeer.path("matcher").stringValue());
        assertEquals(HOOK_LINE + " hook agy PreToolUse --module=native-peer --timeout=5 --on-failure=deny-decision", nativePeer.path("hooks").path(0).path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy PostToolUse", agyHandler(file, "sennard-tool-boundary", "PostToolUse").path("command").stringValue());
        for (JsonNode handler : handlers(file.content())) {
            assertEquals("command", handler.path("type").stringValue());
            boolean sessionStart = handler.path("command").stringValue().contains(" hook agy SessionStart ");
            assertEquals(sessionStart ? HookClientCall.SESSION_START_LIMIT_SECONDS : 10, handler.path("timeout").intValue());
        }
        assertEquals(7, handlers(file.content()).size());
    }

    /** What the Windows host's {@code cmd /c "..."} cannot carry (a double quote) is not in the form an installation writes for a plain program path. */
    @Test
    void antigravityCommandsOfAPlainProgramPathHoldNoDoubleQuote() {
        for (String text : allStrings(hooks(antigravity()).content())) {
            assertFalse(text.contains("\""), "a double quote inside this host's Windows command does not survive: " + text);
        }
    }

    /** The failure answers used to be files of JSON and shell text next to the definition; now the hook client prints them, so no file is named. */
    @Test
    void antigravityNamesNoFileOfJsonAndNoShellLineForItsFailureBranches() {
        for (String text : allStrings(hooks(antigravity()).content())) {
            assertFalse(text.contains(".json") || text.contains("||") || text.contains("&&"), text);
        }
    }

    @Test
    void antigravityPreToolUseHooksEveryToolAndTheLifecycleBundleIsEnabled() {
        HostRegistrationFile.WholeFile file = hooks(antigravity());
        assertEquals("*", file.content().path("sennard-tool-boundary").path("PreToolUse").path(0).path("matcher").stringValue());
        assertTrue(file.content().path("sennard-session-lifecycle").path("enabled").booleanValue());
        assertTrue(file.content().path("sennard-tool-boundary").path("enabled").booleanValue());
    }

    @Test
    void antigravityRegistersTheRelayAsAStdioServerInMcpConfigJson() {
        HostRegistrationFile mcp = antigravity().get(1);

        assertEquals(Path.of("mcp_config.json"), mcp.relativePath());
        JsonNode server = mcp.content().path("mcpServers").path("sennard");
        assertEquals(JAVA, server.path("command").stringValue());
        assertEquals(concat(MCP_TAIL, List.of("--host=agy", START_SERVER)), strings(server.path("args")));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- Gemini

    @Test
    void geminiHooksJsonRegistersNativeAgentResultsInTheirOwnAfterToolEntry() {
        HostRegistrationFile.WholeFile file = hooks(gemini());
        assertEquals(Path.of("hooks", "hooks.json"), file.relativePath());
        assertEquals(Set.of("hooks"), Set.copyOf(file.content().propertyNames()));
        List<String> events = List.of("SessionStart", "SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "BeforeTool", "AfterTool", "PreCompress");
        assertEquals(events, List.copyOf(file.content().path("hooks").propertyNames()));
        for (String event : events) {
            JsonNode group = entry(file, event, 0);
            assertEquals(event.equals("AfterTool") ? 2 : 1, file.content().path("hooks").path(event).size(), event);
            assertEquals(event.equals("AfterTool") ? "^(?!invoke_agent$).*$" : "*", group.path("matcher").stringValue(), event);
            assertEquals(1, group.path("hooks").size(), event);
            JsonNode handler = group.path("hooks").path(0);
            assertEquals("sennard-" + event, handler.path("name").stringValue());
            assertEquals("command", handler.path("type").stringValue());
            assertEquals(event.equals("SessionStart") ? HookClientCall.SESSION_START_LIMIT_SECONDS * 1000 : 10_000, handler.path("timeout").intValue(), "this host's timeout is in milliseconds");
        }
        JsonNode nativeResult = entry(file, "AfterTool", 1);
        assertEquals("^invoke_agent$", nativeResult.path("matcher").stringValue());
        assertEquals(1, nativeResult.path("hooks").size());
        JsonNode handler = handler(file, "AfterTool", 1);
        assertEquals("sennard-native-peer-result", handler.path("name").stringValue());
        assertEquals("command", handler.path("type").stringValue());
        assertEquals(10_000, handler.path("timeout").intValue());
    }

    @Test
    void geminiOnlyTheSessionStartBindsTheHostAndAnswersADownServerWithTheGuidance() {
        HostRegistrationFile.WholeFile file = hooks(gemini());
        assertEquals(HOOK_LINE + " hook gemini SessionStart --on-failure=guidance-system-message --bind-host " + START_SERVER, handler(file, "SessionStart", 0).path("command").stringValue());
        for (String event : List.of("SessionEnd", "BeforeAgent", "AfterAgent", "BeforeModel", "AfterTool", "PreCompress")) {
            assertEquals(HOOK_LINE + " hook gemini " + event, handler(file, event, 0).path("command").stringValue());
        }
        assertEquals(HOOK_LINE + " hook gemini BeforeTool --on-failure=deny-decision --on-refusal=deny-decision", handler(file, "BeforeTool", 0).path("command").stringValue());
        assertEquals(HOOK_LINE + " hook gemini AfterTool --on-failure=deny-result", handler(file, "AfterTool", 1).path("command").stringValue());
    }

    /** The manifest also holds the extension's name and version, so the registration is the one member Sennard writes, not the file. */
    @Test
    void geminiRegistersTheRelayAsTheMcpServersMemberOfItsManifest() {
        HostRegistrationFile mcp = gemini().get(1);

        HostRegistrationFile.Member member = assertInstanceOf(HostRegistrationFile.Member.class, mcp);
        assertEquals(Path.of("gemini-extension.json"), member.relativePath());
        assertEquals("/mcpServers", member.jsonPointer());
        assertEquals(Set.of("sennard"), Set.copyOf(member.content().propertyNames()), "the value of the member: the servers by name");
        JsonNode server = member.content().path("sennard");
        assertEquals(JAVA, server.path("command").stringValue());
        assertEquals(concat(MCP_TAIL, List.of("--host=gemini", START_SERVER)), strings(server.path("args")));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- every host

    @Test
    void everyHostWritesItsHookDefinitionThenItsMcpRegistrationInTheFormItsHostReads() {
        assertEquals(List.of(Path.of("hooks", "hooks.json"), Path.of(".mcp.json")), claude().stream().map(HostRegistrationFile::relativePath).toList());
        assertEquals(List.of(Path.of("hooks", "hooks.json"), Path.of(".mcp.json")), codex().stream().map(HostRegistrationFile::relativePath).toList());
        assertEquals(List.of(Path.of("hooks.json"), Path.of("mcp_config.json")), antigravity().stream().map(HostRegistrationFile::relativePath).toList());
        assertEquals(List.of(Path.of("hooks", "hooks.json"), Path.of("gemini-extension.json")), gemini().stream().map(HostRegistrationFile::relativePath).toList());
        for (List<HostRegistrationFile> files : List.of(claude(), codex(), antigravity())) {
            files.forEach(file -> assertInstanceOf(HostRegistrationFile.WholeFile.class, file));
        }
    }

    /** A package cannot hold a definition that works: it names the installation's own program and file. What it ships is the whole files with nothing registered. */
    @ParameterizedTest
    @MethodSource("everyHost")
    void thePackagesShippedFilesAreTheWholeFilesWithNothingRegisteredAndNameNoPortAndNoProgram(HostCase each) {
        List<HostRegistrationFile.WholeFile> shipped = each.adapter().shippedFiles();
        List<HostRegistrationFile> written = each.adapter().registrationFiles(INSTALLATION);

        assertEquals(written.stream().filter(HostRegistrationFile.WholeFile.class::isInstance).map(HostRegistrationFile::relativePath).toList(),
                shipped.stream().map(HostRegistrationFile::relativePath).toList(), "the whole files an installation writes, each shipped empty");
        for (HostRegistrationFile.WholeFile file : shipped) {
            assertTrue(allStrings(file.content()).isEmpty(), "no word, no path and no address in a shipped file: " + file.content());
            assertTrue(file.content().isObject(), file.content().toString());
            assertTrue(file.content().size() <= 1, file.content().toString());
            file.content().values().forEach(member -> assertTrue(member.isObject() && member.size() == 0, "an empty object, not a missing member: " + file.content()));
        }
    }

    @Test
    void theShippedFilesOfEachHostAreTheEmptyDefinitionAndTheEmptyRegistrationItsAdapterWrites() {
        assertEquals(List.of("{\"hooks\":{}}", "{\"mcpServers\":{}}"), HookAdapters.claude().shippedFiles().stream().map(file -> file.content().toString()).toList());
        assertEquals(List.of("{\"hooks\":{}}", "{\"mcpServers\":{}}"), HookAdapters.codex().shippedFiles().stream().map(file -> file.content().toString()).toList());
        assertEquals(List.of("{}", "{\"mcpServers\":{}}"), HookAdapters.antigravity().shippedFiles().stream().map(file -> file.content().toString()).toList());
        assertEquals(List.of("{\"hooks\":{}}"), HookAdapters.gemini().shippedFiles().stream().map(file -> file.content().toString()).toList(), "Gemini CLI's registration is a member of its manifest: not shipped");
    }

    @ParameterizedTest
    @MethodSource("everyHost")
    void anInstallationsFilesNameItsOwnProgramsAndNoPortAndNoUrl(HostCase each) {
        List<String> strings = new ArrayList<>();
        each.adapter().registrationFiles(INSTALLATION).forEach(file -> strings.addAll(allStrings(file.content())));

        assertTrue(strings.stream().anyMatch(text -> text.contains(JAVA)), "an installation's files name its own Java: " + strings);
        assertTrue(strings.stream().anyMatch(text -> text.contains(SERVER_FILE)), "and the file where its server announces its address: " + strings);
        assertFalse(strings.stream().anyMatch(text -> text.contains("--port") || text.contains("127.0.0.1") || text.contains("http://")),
                "no port, no address: the server's port is the operating system's choice at each start: " + strings);
    }

    /** A program path with a space is quoted for the hosts that give a command string to a shell, and the exec forms (Claude's hooks, every MCP registration) take it as it is. */
    @Test
    void aProgramPathWithASpaceIsQuotedInACommandLineAndLeftAsItIsInExecForm() {
        String spaced = "C:\\Program Files\\Java (1)\\bin\\java.exe";
        HookInstallation installation = new HookInstallation(List.of(spaced, "-cp", "client.jar", HOOK_MAIN), List.of(spaced, "-cp", "client.jar", RELAY_MAIN));

        HostRegistrationFile.WholeFile codexHooks = hooks(HookAdapters.codex().registrationFiles(installation));
        assertEquals("\"" + spaced + "\" -cp client.jar " + HOOK_MAIN + " hook codex SessionStart --on-failure=guidance-stdout --bind-host " + START_SERVER,
                handler(codexHooks, "SessionStart", 0).path("command").stringValue());
        assertTrue(agyHandler(hooks(HookAdapters.antigravity().registrationFiles(installation)), "sennard-tool-boundary", "PreToolUse").path("command").stringValue()
                .startsWith("\"" + spaced + "\" -cp client.jar "));
        assertTrue(handler(hooks(HookAdapters.gemini().registrationFiles(installation)), "BeforeTool", 0).path("command").stringValue().startsWith("\"" + spaced + "\" -cp client.jar "));
        assertEquals(spaced, handler(hooks(HookAdapters.claude().registrationFiles(installation)), "SessionStart", 0).path("command").stringValue());
        for (HostCase each : everyHost().toList()) {
            JsonNode server = each.adapter().registrationFiles(installation).get(1).content();
            JsonNode sennard = server.has("mcpServers") ? server.path("mcpServers").path("sennard") : server.path("sennard");
            assertEquals(spaced, sennard.path("command").stringValue(), each.host() + ": a host starts an MCP server from its command and arguments, with no shell to quote for");
        }
    }

    /** A program path no quoting survives (a quote, a percent sign, nothing) is refused when the definition is generated, instead of written. */
    @Test
    void aProgramPathNoCommandLineCanCarryIsRefusedWhenTheDefinitionIsGenerated() {
        for (String path : new String[] {"C:\\a%b\\java.exe", "C:\\a\"b\\java.exe", ""}) {
            HookInstallation installation = new HookInstallation(List.of(path), List.of("relay"));
            assertThrows(IllegalArgumentException.class, () -> HookAdapters.codex().registrationFiles(installation), path);
            assertThrows(IllegalArgumentException.class, () -> HookAdapters.antigravity().registrationFiles(installation), path);
            assertThrows(IllegalArgumentException.class, () -> HookAdapters.gemini().registrationFiles(installation), path);
        }
    }

    /** Claude Code's exec form: the program is the first word, the arguments are the program's other words and then the call's, and nothing is quoted. */
    @Test
    void aProgramOfSeveralWordsIsTheFirstWordAsTheCommandAndTheRestThenTheCallsArgumentsAsTheArgsInClaude() {
        HostRegistrationFile.WholeFile file = hooks(claude());

        JsonNode start = handler(file, "SessionStart", 0);
        assertEquals(HOOK_WORDS.get(0), start.path("command").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host", START_SERVER)), strings(start.path("args")));

        JsonNode wake = handler(file, "Stop", 1);
        assertEquals(HOOK_WORDS.get(0), wake.path("command").stringValue());
        assertEquals(concat(HOOK_TAIL, List.of("hook", "claude", "Stop", "--module=wake", "--timeout=86400", "--wake", "--only-if-env=" + ATTENDED)), strings(wake.path("args")));
        assertTrue(wake.path("asyncRewake").booleanValue());
    }

    @Test
    void aProgramOfSeveralWordsWithSpacesIsLeftAsItIsInClaudesExecForm() {
        HostRegistrationFile.WholeFile file = hooks(HookAdapters.claude().registrationFiles(new HookInstallation(SPACED_WORDS, MCP_WORDS)));

        JsonNode start = handler(file, "SessionStart", 0);
        assertEquals(SPACED_WORDS.get(0), start.path("command").stringValue(), "no shell reads it: no quote");
        assertEquals(concat(SPACED_WORDS.subList(1, SPACED_WORDS.size()), List.of("hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host", START_SERVER)),
                strings(start.path("args")));
        for (JsonNode command : handlers(file.content())) {
            assertTrue(allStrings(command).stream().noneMatch(text -> text.contains("\"")), "no word of the exec form is quoted: " + command);
        }
    }

    /** The hosts that give a command string to a shell of their own choice read one line: the words, then the call's, with a double quote only where a word needs one. */
    @Test
    void aProgramOfSeveralWordsIsOneCommandLineInCodexGeminiAndAntigravity() {
        JsonNode codexStart = handler(hooks(codex()), "SessionStart", 0);
        assertEquals(HOOK_LINE + " hook codex SessionStart --on-failure=guidance-stdout --bind-host " + START_SERVER, codexStart.path("command").stringValue());
        assertFalse(codexStart.has("args"), "a command string has no argument list");
        assertEquals(HOOK_LINE + " hook codex SessionEnd --timeout=1", handler(hooks(codex()), "SessionEnd", 0).path("command").stringValue());

        HostRegistrationFile.WholeFile gemini = hooks(gemini());
        assertEquals(HOOK_LINE + " hook gemini SessionStart --on-failure=guidance-system-message --bind-host " + START_SERVER, handler(gemini, "SessionStart", 0).path("command").stringValue());
        assertEquals(HOOK_LINE + " hook gemini BeforeModel", handler(gemini, "BeforeModel", 0).path("command").stringValue());

        HostRegistrationFile.WholeFile antigravity = hooks(antigravity());
        assertEquals(HOOK_LINE + " hook agy SessionStart --on-failure=guidance-steps --bind-host " + START_SERVER, agyHandler(antigravity, "sennard-session-lifecycle", "SessionStart").path("command").stringValue());
        assertEquals(HOOK_LINE + " hook agy PreToolUse --on-failure=deny-decision --on-refusal=deny-decision", agyHandler(antigravity, "sennard-tool-boundary", "PreToolUse").path("command").stringValue());
        for (String text : allStrings(antigravity.content())) {
            assertFalse(text.contains("\""), "a plain program of several words leaves this host's Windows command without a double quote: " + text);
        }
    }

    @Test
    void aProgramOfSeveralWordsWithSpacesIsQuotedWordByWordInACommandLine() {
        HookInstallation installation = new HookInstallation(SPACED_WORDS, MCP_WORDS);

        assertEquals(SPACED_LINE + " hook codex SessionStart --on-failure=guidance-stdout --bind-host " + START_SERVER,
                handler(hooks(HookAdapters.codex().registrationFiles(installation)), "SessionStart", 0).path("command").stringValue());
        assertEquals(SPACED_LINE + " hook gemini BeforeTool --on-failure=deny-decision --on-refusal=deny-decision",
                handler(hooks(HookAdapters.gemini().registrationFiles(installation)), "BeforeTool", 0).path("command").stringValue());
        assertEquals(SPACED_LINE + " hook agy PostToolUse",
                agyHandler(hooks(HookAdapters.antigravity().registrationFiles(installation)), "sennard-tool-boundary", "PostToolUse").path("command").stringValue());
    }

    @Test
    void aWordAfterTheFirstThatNoCommandLineCanCarryIsRefusedForTheStringHostsAndNotForClaudesList() {
        HookInstallation installation = new HookInstallation(List.of("C:/Sennard/java.exe", "-cp", "C:/a%b/app.jar"), MCP_WORDS);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> HookAdapters.codex().registrationFiles(installation));
        assertEquals("This program path cannot be written in a hook command: C:/a%b/app.jar", refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> HookAdapters.antigravity().registrationFiles(installation));
        assertThrows(IllegalArgumentException.class, () -> HookAdapters.gemini().registrationFiles(installation));
        assertEquals(List.of("-cp", "C:/a%b/app.jar", "hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host", START_SERVER),
                strings(handler(hooks(HookAdapters.claude().registrationFiles(installation)), "SessionStart", 0).path("args")), "exec form: no shell reads a word, so there is nothing to refuse");
    }
}
