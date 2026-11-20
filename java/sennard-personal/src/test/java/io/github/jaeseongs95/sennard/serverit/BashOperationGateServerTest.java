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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0317: a command that invokes code the hook cannot inspect is not evidence of an explicit file edit.
 * The real workflow and hook gates are exercised with the three host payloads. Commands are only hook input;
 * no test executes a payload, including the user's Core resume script.
 */
@Tag("business-db")
class BashOperationGateServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private record Request(String host, String session, Path workspace) {
    }

    private static Request open(String host, String kind) throws Exception {
        return open(host, kind, List.of("kept.txt"));
    }

    private static Request open(String host, String kind, List<String> planned) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        Path workspace = Path.of("build", "bash-operation-gate", UUID.randomUUID().toString()).toAbsolutePath();
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
        arguments.put("goal", "Verify the Bash operation gate for " + host);
        arguments.put("workspace", workspace.toString());
        if (!"inquiry".equals(kind)) {
            arguments.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "Exercise the real command gate")));
        }
        if ("operation".equals(kind)) {
            arguments.put("target", workspace.toString());
        } else if ("change".equals(kind)) {
            arguments.put("stages", List.of("minimal-implementation"));
            arguments.put("acceptanceCriteria", List.of("Only kept.txt may change"));
            arguments.put("writeTargets", planned);
            arguments.put("changePlan", planned);
        }
        String tool = switch (kind) {
            case "operation" -> "start_operation_workflow";
            case "inquiry" -> "start_inquiry_workflow";
            case "change" -> "start_workflow";
            default -> throw new IllegalArgumentException(kind);
        };
        JsonNode opened = ChangeWorkflows.callAs(server, host, session, tool, arguments);
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        return new Request(host, session, workspace);
    }

    private static JsonNode response(RawHttp.Response response) throws Exception {
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.MAPPER.readTree(response.body());
    }

    private static boolean denied(String host, JsonNode answer) {
        return "agy".equals(host) ? "deny".equals(answer.path("decision").asString())
                : "deny".equals(answer.path("hookSpecificOutput").path("permissionDecision").asString());
    }

    private static JsonNode command(Request request, String command, boolean powershell) throws Exception {
        ObjectNode input = DomainServerSupport.MAPPER.createObjectNode();
        String tool = switch (request.host()) {
            case "codex" -> {
                input.put("cmd", command).put("shell", powershell ? "powershell.exe" : "/bin/bash");
                yield "exec_command";
            }
            case "agy" -> {
                input.put("CommandLine", command);
                yield "run_command";
            }
            case "claude" -> {
                input.put("command", command);
                yield powershell ? "PowerShell" : "Bash";
            }
            default -> throw new IllegalArgumentException(request.host());
        };
        return response(DomainServerSupport.hook(server.port, "windows", request.host(), "PreToolUse",
                "agy".equals(request.host()) ? null : "continuity", request.session(), body -> {
                    // WorkflowToolsServerTest.hookBody and the host's input contract use this whole object, not tool_name/tool_input.
                    if ("agy".equals(request.host())) {
                        body.putArray("workspacePaths").add(request.workspace().toString());
                        body.putObject("toolCall").put("name", tool).set("args", input);
                    } else {
                        body.put("cwd", request.workspace().toString()).put("tool_use_id", UUID.randomUUID().toString());
                        DomainServerSupport.tool(tool, input).accept(body);
                    }
                }));
    }

    private static void assertDecision(Request request, String command, boolean powershell, boolean refused) throws Exception {
        JsonNode answer = command(request, command, powershell);
        assertEquals(refused, denied(request.host(), answer), request.host() + ": " + command + ": " + answer);
    }

    @ParameterizedTest(name = "opaque-execution: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void opaqueVerificationRunsInOperation(String host) throws Exception {
        Request request = open(host, "operation");
        assertAll(
                () -> assertDecision(request, "./gradlew test", false, false),
                () -> assertDecision(request, "python tools/verify-candidate.py", false, false),
                () -> assertDecision(request, "node tools/check-candidate.mjs", false, false),
                () -> assertDecision(request, "java -jar verifier.jar", false, false),
                () -> assertDecision(request, "pwsh -NoProfile -File ./tools/check-candidate.ps1", true, false),
                () -> assertDecision(request, "& 'D:\\codex\\build\\core110-regression-fixes-20261116\\tools\\resume-reviewed-candidate-2cae09.ps1'", true, false));
    }

    /** D0347: source/result paths do not make an external script's internal writes observable. */
    @ParameterizedTest(name = "reported-python: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void reportedPythonCommandUsesOperationRatherThanIndividualFilePlan(String host) throws Exception {
        String commandLine = "C:/Tools/Python/python.exe -B tools/page_revision_check.py --owner";
        Request change = open(host, "change", List.of("tools/page_revision_check.py", "reports/page-revision-result.json"));
        JsonNode refused = command(change, commandLine, false);
        assertTrue(denied(host, refused), refused.toString());
        assertTrue(refused.toString().contains("start_operation_workflow"), refused.toString());
        assertTrue(refused.toString().contains("resume_workflow"), refused.toString());
        assertFalse(refused.toString().contains("write it with a file tool"), refused.toString());
        assertDecision(open(host, "operation"), commandLine, false, false);
    }

    @ParameterizedTest(name = "operation-edit: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void actualEditsRemainDeniedInOperation(String host) throws Exception {
        Request request = open(host, "operation");
        for (String command : List.of("echo changed > outside.txt", "./verify.sh > outside.txt", "rm *.txt",
                "python -c \"open('outside.txt', 'w').write('changed')\"", "git reset --hard", "./verify.sh; git reset --hard")) {
            assertDecision(request, command, false, true);
        }
        assertDecision(request, "Set-Content -LiteralPath outside.txt -Value changed", true, true);
        assertDecision(request, "Remove-Item -LiteralPath $env:TARGET", true, true);
    }

    @ParameterizedTest(name = "change-boundary: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void changeStillRejectsUnplannedWrites(String host) throws Exception {
        Request request = open(host, "change");
        for (String command : List.of("echo changed > outside.txt", "rm *.txt", "./verify.sh > outside.txt")) {
            assertDecision(request, command, false, true);
        }
        assertDecision(request, "Set-Content -LiteralPath outside.txt -Value changed", true, true);
    }

    @ParameterizedTest(name = "inquiry-boundary: host={0}")
    @ValueSource(strings = {"claude", "codex", "agy"})
    void inquiryStillRejectsCommandsAndWrites(String host) throws Exception {
        Request request = open(host, "inquiry");
        assertDecision(request, "./gradlew test", false, true);
        assertDecision(request, "echo changed > outside.txt", false, true);
        assertDecision(request, "Set-Content -LiteralPath outside.txt -Value changed", true, true);
    }
}
