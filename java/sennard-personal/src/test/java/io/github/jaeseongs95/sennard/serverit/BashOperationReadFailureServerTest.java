// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A tool needs readable workflow attribution before it can run.
 * The test changes only its own root's JSON in the named business test database.
 * The command gate binds that real row through the assembled HTTP server.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class BashOperationReadFailureServerTest {
    private static final String UNAVAILABLE = "phase-gate-unavailable:UNEXPECTED_FAILURE";
    private static final String OPAQUE = "./gradlew test";
    private static final String WRITE = "echo changed > outside.txt";

    private static RunningServer server;
    private static ListAppender<ILoggingEvent> logged;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
        // Spring Boot resets Logback at startup: capture only after the assembled server is ready.
        logged = new ListAppender<>();
        logged.start();
        ((Logger) LoggerFactory.getLogger(HookPipeline.class)).addAppender(logged);
    }

    @AfterAll
    static void stop() {
        try {
            if (logged != null) {
                ((Logger) LoggerFactory.getLogger(HookPipeline.class)).detachAppender(logged);
                logged.stop();
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    private record Request(String host, String session, Path workspace, String rootId, String correlation, long epoch) {
    }

    private static Request open(String host) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        Path workspace = Path.of("build", "bash-read-failure", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspace);
        JsonNode started = response(DomainServerSupport.hook(server.port, "windows", host, "SessionStart", null, session, body -> {
            if ("agy".equals(host)) {
                body.putArray("workspacePaths").add(workspace.toString());
            } else {
                body.put("cwd", workspace.toString());
            }
        }));
        assertFalse(denied(host, started), started.toString());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "Verify attribution read failure for " + host);
        arguments.put("workspace", workspace.toString());
        arguments.put("target", workspace.toString());
        arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "Exercise the real command gate")));
        JsonNode opened = ChangeWorkflows.callAs(server, host, session, "start_operation_workflow", arguments);
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String rootId = opened.path("data").path("rootId").asString();
        assertFalse(rootId.isBlank(), opened.toString());
        ContinuityStore store = server.bean(ContinuityStore.class);
        List<String> owners = store.rootOwners(rootId);
        assertEquals(1, owners.size(), "only the fixture session owns its new root");
        String correlation = owners.getFirst();
        return new Request(host, session, workspace, rootId, correlation, store.readTask(correlation).currentEpoch());
    }

    private static JsonNode response(RawHttp.Response answer) throws Exception {
        assertEquals(200, answer.status(), DomainServerSupport.text(answer));
        return DomainServerSupport.MAPPER.readTree(answer.body());
    }

    private static boolean denied(String host, JsonNode answer) {
        return "agy".equals(host) ? "deny".equals(answer.path("decision").asString())
                : "deny".equals(answer.path("hookSpecificOutput").path("permissionDecision").asString());
    }

    private static String refusalReason(String host, JsonNode answer) {
        return "agy".equals(host) ? answer.path("reason").asString()
                : answer.path("hookSpecificOutput").path("permissionDecisionReason").asString();
    }

    private static JsonNode claudeTool(Request request, String tool, ObjectNode input) throws Exception {
        return response(DomainServerSupport.hook(server.port, "windows", request.host(), "PreToolUse", "continuity", request.session(), body -> {
            body.put("cwd", request.workspace().toString()).put("tool_use_id", UUID.randomUUID().toString());
            DomainServerSupport.tool(tool, input).accept(body);
        }));
    }

    private static JsonNode command(Request request, String command) throws Exception {
        ObjectNode input = DomainServerSupport.MAPPER.createObjectNode();
        String tool = switch (request.host()) {
            case "codex" -> {
                input.put("cmd", command).put("shell", "/bin/bash");
                yield "exec_command";
            }
            case "agy" -> {
                input.put("CommandLine", command);
                yield "run_command";
            }
            case "claude" -> {
                input.put("command", command);
                yield "Bash";
            }
            default -> throw new IllegalArgumentException(request.host());
        };
        return response(DomainServerSupport.hook(server.port, "windows", request.host(), "PreToolUse",
                "agy".equals(request.host()) ? null : "continuity", request.session(), body -> {
                    if ("agy".equals(request.host())) {
                        body.putArray("workspacePaths").add(request.workspace().toString());
                        body.putObject("toolCall").put("name", tool).set("args", input);
                    } else {
                        body.put("cwd", request.workspace().toString()).put("tool_use_id", UUID.randomUUID().toString());
                        DomainServerSupport.tool(tool, input).accept(body);
                    }
                }));
    }

    private static String rootJson(Request request) {
        return (String) SessionServerSupport.row(server, "SELECT root_json FROM sennard.convergence_roots WHERE root_id = ?", request.rootId()).get("root_json");
    }

    private static long unavailable(Request request) {
        return SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.continuity_observations WHERE task_correlation = ? AND epoch = ? AND event = ? AND outcome = 'FAILED'",
                request.correlation(), request.epoch(), UNAVAILABLE);
    }

    private static boolean hasSafeJacksonCause(ILoggingEvent event) {
        for (IThrowableProxy cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
            if (SafeFailure.class.getName().equals(cause.getClassName()) && cause.getMessage().startsWith("tools.jackson.")) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "attribution-read-failure: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void anUnreadableAttributionRefusesToolsAndLeavesWorkflowEntryAvailable(String host) throws Exception {
        Request request = open(host);
        assertFalse(denied(host, command(request, OPAQUE)), "healthy OP permits opaque execution before the fault");
        assertTrue(denied(host, command(request, WRITE)), "healthy OP refuses a named write before the fault");
        long before = unavailable(request);
        String original = rootJson(request);
        String marker = "BASH_READ_FAILURE_" + UUID.randomUUID();
        ObjectNode damaged = (ObjectNode) DomainServerSupport.MAPPER.readTree(original);
        // The JSON remains valid. Real RootDoc/RootState binding, not an injected normal result, must fail.
        damaged.put("state", marker);
        List<JsonNode> answers = new ArrayList<>();
        logged.list.clear();
        long after;
        try {
            SessionServerSupport.update(server, "UPDATE sennard.convergence_roots SET root_json = ? WHERE root_id = ?",
                    DomainServerSupport.MAPPER.writeValueAsString(damaged), request.rootId());
            assertEquals(DomainServerSupport.MAPPER.writeValueAsString(damaged), rootJson(request), "only this fixture's root carries the fault");
            for (int attempt = 0; attempt < 2; attempt++) {
                answers.add(command(request, OPAQUE));
            }
            if ("claude".equals(host)) {
                answers.add(claudeTool(request, "Read", DomainServerSupport.MAPPER.createObjectNode().put("file_path", request.workspace().resolve("read.txt").toString())));
                JsonNode workflowTool = claudeTool(request, "mcp__plugin_sennard_sennard__get_workflow_status", DomainServerSupport.MAPPER.createObjectNode());
                assertFalse(denied(host, workflowTool), "the workflow entry point remains available: " + workflowTool);
            }
            after = unavailable(request);
        } finally {
            SessionServerSupport.update(server, "UPDATE sennard.convergence_roots SET root_json = ? WHERE root_id = ?", original, request.rootId());
        }
        List<ILoggingEvent> warnings = logged.list.stream().filter(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("HOOK_GATE_REFUSED HOOK_HANDLER_FAILED")).toList();
        assertAll(
                () -> assertEquals(original, rootJson(request), "the fixture restores the exact root JSON"),
                () -> {
                    for (JsonNode answer : answers) {
                        assertTrue(denied(host, answer), host + " must refuse this tool while attribution is unreadable: " + answer);
                        assertTrue(refusalReason(host, answer).contains("HOOK_HANDLER_FAILED"), answer.toString());
                    }
                },
                () -> assertEquals(before, after, "the request gate refuses before reaching the edit gate"),
                () -> assertEquals(answers.size(), warnings.size(), "the hook logs each refusal"),
                () -> {
                    for (ILoggingEvent warning : warnings) {
                        assertTrue(hasSafeJacksonCause(warning), "the sanitized cause retains the Jackson exception class");
                        String rendered = warning.getFormattedMessage() + ThrowableProxyUtil.asString(warning.getThrowableProxy());
                        assertFalse(rendered.contains(marker), "the refusal log does not expose the invalid input");
                    }
                },
                () -> assertFalse(denied(host, command(request, OPAQUE)), "restored OP still permits opaque execution"),
                () -> assertTrue(denied(host, command(request, WRITE)), "restored OP still refuses a named write"),
                () -> assertEquals(before, unavailable(request), "normal calls after restoration add no unavailable event"));
    }
}
