// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCall;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCode;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The workflow gate on the assembled server with real PostgreSQL, through the hooks of the three hosts and the MCP tools of the kinds: a request that no workflow instance serves is held at
 * its prompt, at each tool and at its end; an instance the agent opens or resumes serves it; an instance of the kind that closes with the turn is closed by the server; the kinds that do
 * not edit files refuse the editing tools. Bodies are the probe's recordings with this test's session in them.
 */
@Tag("business-db")
class WorkflowGateServerTest {
    private static final String GATE = "[Sennard workflow gate]";
    private static RunningServer server;
    private static Path workspace;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
        workspace = Path.of("build", "workflow-gate-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(workspace);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    // ------------------------------------------------------------------------------------------------------------------ helpers

    private static String promptEvent(String host) {
        return host.equals("agy") ? "PreInvocation" : "UserPromptSubmit";
    }

    private static String module(String host) {
        return host.equals("agy") ? null : "continuity";
    }

    private static RawHttp.Response hook(String host, String event, String module, String session, Consumer<ObjectNode> edit) throws IOException {
        return DomainServerSupport.hook(server.port, "windows", host, event, module, session, edit);
    }

    private static JsonNode ok(RawHttp.Response response) {
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.MAPPER.readTree(response.body());
    }

    private static String newSession(String host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        ok(hook(host, "SessionStart", null, session, body -> {
        }));
        return session;
    }

    /** The prompt of a new turn: what its context says. */
    private static String prompt(String host, String session) throws IOException {
        JsonNode output = ok(hook(host, promptEvent(host), null, session, body -> {
        }));
        return host.equals("agy") ? output.toString() : output.path("hookSpecificOutput").path("additionalContext").asString();
    }

    private static JsonNode preToolUse(String host, String session) throws IOException {
        return ok(hook(host, "PreToolUse", module(host), session, body -> {
        }));
    }

    private static JsonNode preToolUse(String host, String session, String tool, ObjectNode input) throws IOException {
        return ok(hook(host, "PreToolUse", module(host), session, DomainServerSupport.tool(tool, input)));
    }

    private static JsonNode stop(String host, String session) throws IOException {
        return ok(hook(host, "Stop", null, session, body -> {
        }));
    }

    private static boolean denied(String host, JsonNode output) {
        return host.equals("agy") ? output.path("decision").asString().equals("deny") : output.path("hookSpecificOutput").path("permissionDecision").asString().equals("deny");
    }

    private static String reason(String host, JsonNode output) {
        return host.equals("agy") ? output.path("reason").asString() : output.path("hookSpecificOutput").path("permissionDecisionReason").asString();
    }

    private static ObjectNode object() {
        return DomainServerSupport.MAPPER.createObjectNode();
    }

    /** The arguments of a tool call, as the tool's schema takes them (no schemaVersion: the workflow tools do not declare one). */
    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    /**
     * A Sennard tool called as the host's main agent calls it; the result envelope, whether the call succeeded or not. Every start needs the execution observation of its host
     * and consumes it, so each call is made as its own host call with the observation that host leaves ({@link ChangeWorkflows#callAs}).
     */
    private static JsonNode call(String host, String session, String tool, Map<String, Object> arguments) throws IOException {
        return ChangeWorkflows.callAs(server, host, session, tool, arguments);
    }

    private static Map<String, Object> inquiry(String goal) {
        return args("goal", goal, "workspace", workspace.toString());
    }

    private static Map<String, Object> operation(String goal, Object... more) {
        Map<String, Object> arguments = args("goal", goal, "target", "branch of the test repository", "workspace", workspace.toString());
        for (int index = 0; index + 1 < more.length; index += 2) {
            arguments.put((String) more[index], more[index + 1]);
        }
        return arguments;
    }

    private static List<Map<String, String>> skills() {
        return List.of(Map.of("skillId", "minimal-implementation", "reason", "the operation of this test"));
    }

    // ------------------------------------------------------------------------------------------------------------------ the tools

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void everyKindHasItsToolAndTheResumeToolIsThere(WorkflowKind kind) {
        try (McpSyncClient client = DomainServerSupport.client(server.port, "codex")) {
            Set<String> names = new HashSet<>();
            client.listTools().tools().forEach(tool -> names.add(tool.name()));
            assertTrue(names.contains(kind.toolName()), kind + " is registered under " + kind.toolName() + ": " + names);
            assertTrue(names.contains("resume_workflow"), names.toString());
        }
    }

    @Test
    void skillsAreRequiredOrOptionalAsTheKindSays() throws IOException {
        String session = newSession("codex");
        JsonNode withoutSkills = call("codex", session, WorkflowKind.OPERATION.toolName(), operation("Run the tests of the repository"));
        assertFalse(withoutSkills.path("ok").asBoolean(), withoutSkills.toString());
        assertEquals("SKILLS_REQUIRED", withoutSkills.path("error").path("details").path("reason").asString(), withoutSkills.toString());
        JsonNode withSkills = call("codex", session, WorkflowKind.OPERATION.toolName(), operation("Run the tests of the repository", "skills", skills()));
        assertTrue(withSkills.path("ok").asBoolean(), withSkills.toString());
        JsonNode inquiryWithoutSkills = call("codex", session, WorkflowKind.INQUIRY.toolName(), inquiry("Answer where the gate is"));
        assertTrue(inquiryWithoutSkills.path("ok").asBoolean(), inquiryWithoutSkills.toString());
        JsonNode target = call("codex", session, WorkflowKind.INQUIRY.toolName(), args("goal", "Answer", "target", "not a field of this kind", "workspace", workspace.toString()));
        assertFalse(target.path("ok").asBoolean(), "a kind that acts on nothing takes no target: " + target);
    }

    /**
     * AGS3-D-0313: session-board (shared state of one computer: merge, push, install) and orchestrator have no stage to supply, so the registry holds no entry for them; they are installed
     * skills all the same, and a start that names one of them alone names a skill. A name that is no installed skill (a placeholder) is still no choice.
     */
    @Test
    void aSkillWithNoRegistryEntryIsAnInstalledSkillAnOperationMayNameAlone() throws IOException {
        String session = newSession("codex");
        for (String installed : List.of("session-board", "orchestrator")) {
            JsonNode started = call("codex", session, WorkflowKind.OPERATION.toolName(),
                    operation("Merge and push " + installed, "skills", List.of(Map.of("skillId", installed, "reason", "shared state of this computer"))));
            assertTrue(started.path("ok").asBoolean(), installed + ": " + started);
        }
        JsonNode prefixed = call("codex", session, WorkflowKind.OPERATION.toolName(),
                operation("Push with the plugin prefix", "skills", List.of(Map.of("skillId", "sennard:session-board", "reason", "shared state of this computer"))));
        assertTrue(prefixed.path("ok").asBoolean(), prefixed.toString());
        JsonNode placeholder = call("codex", session, WorkflowKind.OPERATION.toolName(), operation("Push with a placeholder", "skills", List.of(Map.of("skillId", "none", "reason", "no skill"))));
        assertFalse(placeholder.path("ok").asBoolean(), placeholder.toString());
        assertEquals("SKILLS_REQUIRED", placeholder.path("error").path("details").path("reason").asString(), placeholder.toString());
    }

    @Test
    void anOperationNeedsItsTarget() throws IOException {
        String session = newSession("codex");
        JsonNode answer = call("codex", session, WorkflowKind.OPERATION.toolName(), args("goal", "Push", "skills", skills(), "workspace", workspace.toString()));
        assertFalse(answer.path("ok").asBoolean(), answer.toString());
    }

    @Test
    void instancesOfTheSameKindInOneWorkspaceDoNotBlockEachOther() throws IOException {
        JsonNode first = call("codex", newSession("codex"), WorkflowKind.INQUIRY.toolName(), inquiry("First answer"));
        JsonNode second = call("codex", newSession("codex"), WorkflowKind.INQUIRY.toolName(), inquiry("Second answer"));
        assertTrue(first.path("ok").asBoolean(), first.toString());
        assertTrue(second.path("ok").asBoolean(), second.toString());
    }

    // ------------------------------------------------------------------------------------------------------------------ the gate, on the three hosts

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aPromptWithNoWorkflowNamesTheGateAndEveryKind(String host) throws IOException {
        String context = prompt(host, newSession(host));
        assertTrue(context.contains(GATE), context);
        for (WorkflowKind kind : WorkflowKind.values()) {
            assertTrue(context.contains(kind.toolName()), kind + " is offered: " + context);
        }
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aToolIsRefusedUntilTheRequestHasAnInstance(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode refused = preToolUse(host, session);
        assertTrue(denied(host, refused), refused.toString());
        assertTrue(reason(host, refused).contains(GATE), refused.toString());
        assertTrue(reason(host, refused).contains(WorkflowKind.INQUIRY.toolName()), "the refusal names the next call: " + refused);
        JsonNode opened = call(host, session, WorkflowKind.INQUIRY.toolName(), inquiry("Answer for " + host));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        assertFalse(denied(host, preToolUse(host, session)), "an instance serves the request now");
    }

    @ParameterizedTest
    @CsvSource({
            "codex, multi_agent_v1send_input, false",
            "codex, send_message, false",
            "codex, followup_task, false",
            "claude, SendMessage, false",
            "claude, SendMessage, true",
            "claude, SubagentHandback, true"
    })
    void blocksNativePeerSendsWhenAWorkflowIsOpen(String host, String nativeTool, boolean child) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        String childId = "child-peer";
        String inquiryTool = WorkflowKind.INQUIRY.toolName();
        JsonNode opened = child ? childCall(session, childId, inquiryTool, inquiry("The child answers"))
                : call(host, session, inquiryTool, inquiry("The parent answers"));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        JsonNode ordinary = child ? childTool(session, childId) : preToolUse(host, session);
        assertFalse(denied(host, ordinary), ordinary.toString());

        JsonNode refusal = ok(hook(host, "PreToolUse", "native-peer", session, body -> {
            DomainServerSupport.tool(nativeTool, object()).accept(body);
            body.remove("mcp_server");
            if (child) {
                body.put("agent_id", childId).put("agent_type", "general-purpose");
            }
        }));
        assertTrue(denied(host, refusal), refusal.toString());
        assertTrue(reason(host, refusal).contains("prepare_session_message"), refusal.toString());
        assertTrue(reason(host, refusal).contains("send_session_message"), refusal.toString());

        if (!child) {
            JsonNode otherMcp = preToolUse(host, session, "mcp__another_server__" + nativeTool, object());
            assertFalse(denied(host, otherMcp), otherMcp.toString());
            String sennardTool = (host.equals("claude") ? "mcp__plugin_sennard_sennard__" : "mcp__sennard__") + "send_session_message";
            JsonNode peer = preToolUse(host, session, sennardTool, object());
            assertFalse(denied(host, peer), peer.toString());
        }
    }

    @ParameterizedTest
    @CsvSource({"send_message", "default_api:send_message"})
    void antigravityNativePeerSendsAreDenied(String nativeTool) throws IOException {
        Consumer<ObjectNode> direct = body -> body.putObject("toolCall").put("name", nativeTool)
                .putObject("args").put("Recipient", "another-agent").put("Message", "fixture body");
        String session = newSession("agy");
        prompt("agy", session);
        JsonNode opened = call("agy", session, WorkflowKind.INQUIRY.toolName(), inquiry("The parent answers"));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        JsonNode refusal = ok(hook("agy", "PreToolUse", "native-peer", session, direct));
        assertTrue(denied("agy", refusal), refusal.toString());
        assertTrue(reason("agy", refusal).contains("prepare_session_message"), refusal.toString());
        assertTrue(reason("agy", refusal).contains("send_session_message"), refusal.toString());

        String unbound = SessionServerSupport.uniqueSession();
        JsonNode deniedUnbound = ok(hook("agy", "PreToolUse", "native-peer", unbound, direct));
        assertTrue(denied("agy", deniedUnbound), deniedUnbound.toString());
        assertTrue(reason("agy", deniedUnbound).contains("send_session_message"), deniedUnbound.toString());
        JsonNode deniedByWildcard = ok(hook("agy", "PreToolUse", null, session, direct));
        assertTrue(denied("agy", deniedByWildcard), deniedByWildcard.toString());

        JsonNode wrappedPeer = ok(hook("agy", "PreToolUse", null, session, body -> body.putObject("toolCall").put("name", "call_mcp_tool")
                .putObject("args").put("ServerName", "sennard_sennard").put("ToolName", "send_session_message").putObject("Arguments")));
        assertFalse(denied("agy", wrappedPeer), wrappedPeer.toString());
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aTurnEndIsHeldForTheMissingInstanceWithALimit(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        for (int hold = 0; hold < 3; hold += 1) {
            JsonNode held = stop(host, session);
            assertTrue(held.toString().contains(GATE), "turn end " + hold + " is held: " + held);
            assertTrue(held.toString().contains("Currently open workflow gates: 0"), held.toString());
            assertFalse(held.toString().contains("start_workflow"), held.toString());
            assertFalse(held.toString().contains("resume_workflow"), held.toString());
        }
        assertFalse(stop(host, session).toString().contains(GATE), "the limit of holds of one request is reached");
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void promptAndToolFreeStopOfferFourProcedures(String host) throws IOException {
        String session = newSession(host);
        String guidance = prompt(host, session);
        String held = stop(host, session).toString();
        for (String procedure : List.of("INQUIRY", "CHANGE", "EXECUTION", "TRANSITION")) {
            assertTrue(guidance.contains(procedure), "prompt missing " + procedure + ": " + guidance);
            assertTrue(held.contains(procedure), "Stop missing " + procedure + ": " + held);
        }
        for (String tool : List.of("start_inquiry_workflow", "start_workflow", "start_operation_workflow", "list_session_workflows",
                "pause_workflow", "finalize_workflow", "abort_workflow", "release_convergence_root")) {
            assertTrue(guidance.contains(tool), "prompt missing " + tool + ": " + guidance);
        }
        assertFalse(guidance.contains("start_transition_workflow"), guidance);
        assertFalse(held.contains("start_workflow"), held);
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void stopSummarizesOpenWorkflowsWithoutRepeatingTheirInstructions(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode first = call(host, session, WorkflowKind.OPERATION.toolName(), operation("First operation", "skills", skills()));
        JsonNode second = call(host, session, WorkflowKind.OPERATION.toolName(), operation("Second operation", "skills", skills()));
        assertTrue(first.path("ok").asBoolean() && second.path("ok").asBoolean());
        prompt(host, session);
        String held = stop(host, session).toString();
        assertTrue(held.contains("Currently open workflow gates: 2"), held);
        for (String omitted : List.of(first.path("data").path("runId").asString(), second.path("data").path("runId").asString(),
                "First operation", "Second operation", "start_workflow", "resume_workflow", "start_operation_workflow")) {
            assertFalse(held.contains(omitted), held);
        }
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void anUnreadableEarlierRootDoesNotEraseTheStopHold(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode earlier = call(host, session, WorkflowKind.OPERATION.toolName(), operation("Previous request", "skills", skills()));
        assertTrue(earlier.path("ok").asBoolean(), earlier.toString());
        String rootId = earlier.path("data").path("rootId").asString();
        String original = (String) SessionServerSupport.row(server, "SELECT root_json FROM sennard.convergence_roots WHERE root_id = ?", rootId).get("root_json");
        ObjectNode damaged = (ObjectNode) DomainServerSupport.MAPPER.readTree(original);
        damaged.put("state", "UNREADABLE_STOP_FIXTURE");
        String update = "UPDATE sennard.convergence_roots SET root_json = ? WHERE root_id = ?";
        prompt(host, session);
        try {
            SessionServerSupport.update(server, update, DomainServerSupport.MAPPER.writeValueAsString(damaged), rootId);
            for (int attempt = 0; attempt < 3; attempt++) {
                JsonNode held = stop(host, session);
                String instruction = switch (host) {
                    case "agy" -> {
                        assertEquals("continue", held.path("decision").asString(), held.toString());
                        yield held.path("reason").asString();
                    }
                    case "codex" -> {
                        assertEquals("block", held.path("decision").asString(), held.toString());
                        yield held.path("reason").asString();
                    }
                    default -> held.path("hookSpecificOutput").path("additionalContext").asString();
                };
                assertTrue(instruction.contains(GATE + " Could not verify the workflow state for this request."), instruction);
            }
            assertFalse(stop(host, session).toString().contains(GATE), "the same request still has a bounded workflow gate");
        } finally {
            SessionServerSupport.update(server, update, original, rootId);
        }
        prompt(host, session);
        JsonNode healthy = stop(host, session);
        assertTrue(healthy.toString().contains("Currently open workflow gates: 1"), healthy.toString());
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aFailedWorkflowStartKeepsTheGateClosedUntilASuccessfulSelection(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        assertTrue(denied(host, preToolUse(host, session)));
        server.bean(WorkflowService.class).recordStartFailure(new WorkflowCall("claude-code", session, null, null, null, null, "en"), WorkflowCode.MCP_UNAVAILABLE);
        JsonNode ordinaryTool = preToolUse(host, session);
        JsonNode ended = stop(host, session);
        assertAll(
                () -> assertTrue(denied(host, ordinaryTool), "a failed start grants no tool admission: " + ordinaryTool),
                () -> assertTrue(ended.toString().contains(GATE), "a failed start grants no Stop credit: " + ended),
                () -> assertTrue(ended.toString().contains("MCP_UNAVAILABLE"), "the recorded failure is still diagnosed: " + ended));
        JsonNode recovered = call(host, session, WorkflowKind.INQUIRY.toolName(), inquiry("Recover after the failed start"));
        assertTrue(recovered.path("ok").asBoolean(), recovered.toString());
        assertFalse(denied(host, preToolUse(host, session)), "the successful inquiry serves the request");
        assertFalse(stop(host, session).toString().contains(GATE));
        prompt(host, session);
        assertTrue(denied(host, preToolUse(host, session)), "the next request needs its own instance");
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void anInquiryIsClosedWhenItsTurnEnds(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode opened = call(host, session, WorkflowKind.INQUIRY.toolName(), inquiry("Answer for " + host));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String runId = opened.path("data").path("runId").asString();
        stop(host, session);
        JsonNode status = call(host, session, "get_workflow_status", args("runId", runId, "detail", "compact"));
        assertEquals("PASSED", status.path("data").path("state").asString(), status.toString());
        prompt(host, session);
        assertTrue(denied(host, preToolUse(host, session)), "the next request needs an instance of its own");
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void anOpenOperationIsShownAtEveryPromptAndResumedByTheAgent(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode opened = call(host, session, WorkflowKind.OPERATION.toolName(), operation("Run the tests of the repository", "skills", skills()));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String runId = opened.path("data").path("runId").asString();
        stop(host, session);
        String next = prompt(host, session);
        assertTrue(next.contains(runId) && next.contains("OPERATION"), "the open instance is listed with its id and kind: " + next);
        assertTrue(denied(host, preToolUse(host, session)), "a listed instance does not serve the request until the agent takes it");
        JsonNode resumed = call(host, session, "resume_workflow", args("runId", runId));
        assertTrue(resumed.path("ok").asBoolean(), resumed.toString());
        assertEquals("OPERATING", resumed.path("data").path("phase").asString(), resumed.toString());
        assertFalse(denied(host, preToolUse(host, session)));
    }

    @ParameterizedTest
    @CsvSource({
            "claude, pause_workflow, PAUSED", "claude, finalize_workflow, PASSED", "claude, abort_workflow, BLOCKED",
            "codex, pause_workflow, PAUSED", "codex, finalize_workflow, PASSED", "codex, abort_workflow, BLOCKED",
            "agy, pause_workflow, PAUSED", "agy, finalize_workflow, PASSED", "agy, abort_workflow, BLOCKED"
    })
    void aLifecycleTransitionServesOnlyItsRequest(String host, String tool, String state) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode opened = call(host, session, WorkflowKind.OPERATION.toolName(), operation("Operation for " + tool, "skills", skills()));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String runId = opened.path("data").path("runId").asString();
        long revision = opened.path("data").path("revision").asLong();
        prompt(host, session);

        Map<String, Object> transition = args("runId", runId, "expectedRevision", revision + 1);
        if (tool.equals("pause_workflow")) {
            transition.put("reason", "Wait for input");
            transition.put("resumeCondition", "The user explicitly resumes");
        }
        JsonNode stale = call(host, session, tool, transition);
        assertEquals("STALE_REVISION", stale.path("error").path("code").asString(), stale.toString());
        assertTrue(stop(host, session).toString().contains(GATE), "a stale transition leaves the request unserved");

        transition.put("expectedRevision", revision);
        transition.put("operationKey", OperationKeys.fresh());
        JsonNode changed = call(host, session, tool, transition);
        assertTrue(changed.path("ok").asBoolean(), changed.toString());
        assertEquals(state, changed.path("data").path("state").asString(), changed.toString());
        assertEquals(runId, changed.path("data").path("runId").asString(), changed.toString());
        assertEquals(revision + 1, changed.path("data").path("revision").asLong(), changed.toString());
        assertTrue(denied(host, preToolUse(host, session)), "a transition grants no tool execution");
        assertFalse(stop(host, session).toString().contains(GATE), "the committed transition served this request");

        prompt(host, session);
        JsonNode replayed = call(host, session, tool, transition);
        assertEquals(changed.path("data"), replayed.path("data"), replayed.toString());
        assertTrue(stop(host, session).toString().contains(GATE), "a receipt from an earlier request cannot serve this request");
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aRootReleaseServesOnlyItsRequest(String host) throws IOException {
        String session = newSession(host);
        prompt(host, session);
        JsonNode opened = call(host, session, WorkflowKind.OPERATION.toolName(), operation("Operation to release", "skills", skills()));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String rootId = opened.path("data").path("rootId").asString();
        String runId = opened.path("data").path("runId").asString();
        String tool = "release_convergence_root";
        prompt(host, session);
        JsonNode status = call(host, session, "get_convergence_status", args("rootId", rootId));
        assertTrue(status.path("ok").asBoolean(), status.toString());
        long revision = status.path("data").path("root").path("revision").asLong();
        Map<String, Object> release = args("rootId", rootId, "expectedRevision", revision + 1, "reason", "The user asked to release the root.");
        JsonNode stale = call(host, session, tool, release);
        assertEquals("STALE_REVISION", stale.path("error").path("code").asString(), stale.toString());
        assertTrue(stop(host, session).toString().contains(GATE), "a stale release leaves the request unserved");

        release.put("expectedRevision", revision);
        release.put("operationKey", OperationKeys.fresh());
        JsonNode released = call(host, session, tool, release);
        assertTrue(released.path("ok").asBoolean(), released.toString());
        JsonNode data = released.path("data");
        assertEquals(rootId, data.path("rootId").asString(), released.toString());
        assertEquals(revision + 1, data.path("revision").asLong(), released.toString());
        assertEquals("ABANDONED", data.path("state").asString(), released.toString());
        assertEquals(runId, data.path("closedRun").path("runId").asString(), released.toString());
        assertEquals("BLOCKED", data.path("closedRun").path("state").asString(), released.toString());
        assertTrue(denied(host, preToolUse(host, session)), "a root release grants no tool execution");
        assertFalse(stop(host, session).toString().contains(GATE), "the committed release served this request");

        prompt(host, session);
        JsonNode replayed = call(host, session, tool, release);
        assertEquals(data, replayed.path("data"), replayed.toString());
        assertTrue(stop(host, session).toString().contains(GATE), "an earlier release receipt does not serve this request");
    }

    // ------------------------------------------------------------------------------------------------------------------ what a kind allows

    @Test
    void anInquiryRefusesEditsAndAnOperationRefusesThemButRunsCommands() throws IOException {
        String path = workspace.resolve("edited-" + UUID.randomUUID() + ".txt").toString();
        for (WorkflowKind kind : List.of(WorkflowKind.INQUIRY, WorkflowKind.OPERATION)) {
            String session = newSession("claude");
            prompt("claude", session);
            Map<String, Object> arguments = kind == WorkflowKind.OPERATION ? operation("Run something", "skills", skills()) : inquiry("Answer something");
            assertTrue(call("claude", session, kind.toolName(), arguments).path("ok").asBoolean());
            JsonNode edit = preToolUse("claude", session, "Write", object().put("file_path", path));
            assertTrue(denied("claude", edit), kind + ": " + edit);
            assertTrue(reason("claude", edit).contains("does not change files"), kind + ": " + edit);
            assertEquals(kind.allowsCommands(), !denied("claude", preToolUse("claude", session, "Bash", object().put("command", "echo " + kind.name()))), kind.toString());
        }
    }

    @Test
    void aSubagentIsHeldToTheSameRule() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        assertTrue(call("claude", session, WorkflowKind.INQUIRY.toolName(), inquiry("The parent answers")).path("ok").asBoolean());
        JsonNode child = ok(hook("claude", "PreToolUse", "continuity", session, DomainServerSupport.tool("Bash", object().put("command", "echo child")).andThen(body -> body.put("agent_id", "child-1").put("agent_type", "general-purpose"))));
        assertTrue(denied("claude", child), "the parent's instance does not serve a subagent: " + child);
    }

    // ------------------------------------------------------------------------------------------------------------------ a subagent's instance

    /** A Sennard tool called as a Claude Code subagent calls it: its PreToolUse hook carries {@code agent_id}, and the call is bound to the row that hook left. */
    private static JsonNode childCall(String session, String agentId, String tool, Map<String, Object> arguments) throws IOException {
        return ChangeWorkflows.callAs(server, "claude", session, tool, arguments, agentId);
    }

    /** The PreToolUse hook of a command run by a Claude Code subagent. */
    private static JsonNode childBash(String session, String agentId) throws IOException {
        return ok(hook("claude", "PreToolUse", "continuity", session,
                DomainServerSupport.tool("Bash", object().put("command", "echo " + agentId)).andThen(body -> body.put("agent_id", agentId).put("agent_type", "general-purpose"))));
    }

    /** The PreToolUse hook of a subagent's call that runs no command (what the fixture holds), for a kind that allows none. */
    private static JsonNode childTool(String session, String agentId) throws IOException {
        return ok(hook("claude", "PreToolUse", "continuity", session, body -> body.put("agent_id", agentId).put("agent_type", "general-purpose")));
    }

    private static JsonNode parentBash(String session) throws IOException {
        return preToolUse("claude", session, "Bash", object().put("command", "echo parent"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"agy", "codex"})
    void separateConversationUsesItsOwnWorkflowAtTheHook(String host) throws IOException {
        String root = newSession(host);
        String child = SessionServerSupport.uniqueSession();
        String sibling = SessionServerSupport.uniqueSession();
        boolean agy = host.equals("agy");
        if (agy) {
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.observeAgy(server, child, root, root);
            DomainServerSupport.observeAgy(server, sibling, root, root);
        } else {
            SessionRegistry sessions = server.bean(SessionRegistry.class);
            String rootRef = sessions.findRef("codex", root).orElseThrow();
            long generation = ((Number) SessionServerSupport.row(server,
                    "SELECT generation FROM sennard.active_generation WHERE session_ref = ?", rootRef).get("generation")).longValue();
            assertTrue(sessions.bindManagedChild("codex", root, rootRef, generation, child));
            assertTrue(sessions.bindManagedChild("codex", root, rootRef, generation, sibling));
        }
        prompt(host, root);
        assertTrue(denied(host, commandHook(host, child)), "the child needs a workflow");

        String start = WorkflowKind.OPERATION.toolName();
        Map<String, Object> arguments = operation("Run a command in a child conversation", "skills", skills());
        JsonNode opened = agy ? ChangeWorkflows.callAsAgyChild(server, root, child, start, arguments) : call(host, child, start, arguments);
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        JsonNode served = commandHook(host, child);
        assertFalse(denied(host, served), "the child's next command uses its own MCP workflow: " + served);
        assertFalse(denied(host, commandHook(host, root)), "the root can use its child's workflow");
        assertTrue(denied(host, commandHook(host, sibling)), "a sibling cannot use the child's workflow");

        Map<String, Object> resume = args("runId", opened.path("data").path("runId").asString());
        JsonNode resumed = agy ? ChangeWorkflows.callAsAgyChild(server, root, child, "resume_workflow", resume)
                : call(host, child, "resume_workflow", resume);
        assertTrue(resumed.path("ok").asBoolean(), resumed.toString());
        assertFalse(denied(host, commandHook(host, child)), "the resumed workflow also serves the physical child");
    }

    private static JsonNode commandHook(String host, String session) throws IOException {
        if (host.equals("agy")) {
            return ok(hook(host, "PreToolUse", null, session, body -> body.putObject("toolCall")
                    .put("name", "run_command").putObject("args").put("CommandLine", "echo workflow")));
        }
        return preToolUse(host, session, "exec_command", object().put("cmd", "echo workflow"));
    }

    /**
     * A subagent's MCP call and its hooks name the same agent, in one form: the instance a subagent opens serves its next command (the refusal "this request is not handled by any
     * workflow" came after an ok start), the parent works in it too, and another subagent does not.
     */
    @Test
    void anInstanceASubagentOpensServesItsNextCommandAndItsParentButNotAnotherSubagent() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        assertTrue(denied("claude", childBash(session, "child-opener")), "no instance serves the request yet");
        JsonNode opened = childCall(session, "child-opener", WorkflowKind.OPERATION.toolName(), operation("The subagent runs a command", "skills", skills()));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        JsonNode served = childBash(session, "child-opener");
        assertFalse(denied("claude", served), "the instance the subagent opened serves its command: " + served);
        assertFalse(denied("claude", parentBash(session)), "the parent works in the instance its subagent opened");
        JsonNode other = childBash(session, "child-other");
        assertTrue(denied("claude", other), "another subagent has not taken the instance: " + other);
    }

    @Test
    void aNewPromptOfTheParentDoesNotTakeAwayTheInstanceASubagentOpenedAndTheParentChoosesAgain() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        JsonNode opened = childCall(session, "child-resumer", WorkflowKind.OPERATION.toolName(), operation("The subagent runs on", "skills", skills()));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        String runId = opened.path("data").path("runId").asString();
        assertFalse(denied("claude", parentBash(session)), "the parent works in the instance its subagent opened");
        // A new prompt of the parent (the user's, or the notice of a finished background task) is a new request of the parent only: the instances are independent.
        prompt("claude", session);
        assertFalse(denied("claude", childBash(session, "child-resumer")), "the subagent keeps the instance it opened");
        assertTrue(denied("claude", parentBash(session)), "the parent chooses again for its new request");
        // Resuming what it opened is still allowed to the subagent, and the parent takes the instance by resuming it.
        assertTrue(childCall(session, "child-resumer", "resume_workflow", args("runId", runId)).path("ok").asBoolean());
        assertFalse(denied("claude", childBash(session, "child-resumer")));
        assertTrue(call("claude", session, "resume_workflow", args("runId", runId)).path("ok").asBoolean());
        assertFalse(denied("claude", parentBash(session)));
    }

    /** Background subagents work side by side: the parent's prompts (the notices of the others' completions) do not stop either of them. */
    @Test
    void twoSubagentsWorkingInParallelEachInTheirOwnInstanceAreNotStoppedByThePromptsOfTheirParent() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        assertTrue(childCall(session, "child-one", WorkflowKind.OPERATION.toolName(), operation("The first subagent works", "skills", skills())).path("ok").asBoolean());
        assertTrue(childCall(session, "child-two", WorkflowKind.INQUIRY.toolName(), inquiry("The second subagent answers")).path("ok").asBoolean());
        for (int notice = 0; notice < 2; notice += 1) {
            prompt("claude", session);
            assertFalse(denied("claude", childBash(session, "child-one")), "the first subagent after the parent's prompt " + notice);
            assertFalse(denied("claude", childTool(session, "child-two")), "the second subagent after the parent's prompt " + notice);
        }
        assertTrue(denied("claude", childBash(session, "child-three")), "a subagent that took no instance is still held");
    }

    @Test
    void aSubagentCannotTakeTheInstanceOfItsParentAndTheParentTakesTheOneOfItsSubagent() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        JsonNode parents = call("claude", session, WorkflowKind.OPERATION.toolName(), operation("The parent runs a command", "skills", skills()));
        assertTrue(parents.path("ok").asBoolean(), parents.toString());
        JsonNode refused = childCall(session, "child-taker", "resume_workflow", args("runId", parents.path("data").path("runId").asString()));
        assertFalse(refused.path("ok").asBoolean(), "a subagent takes only the instances it opened: " + refused);
        assertEquals("INSTANCE_NOT_OWNED", refused.path("error").path("details").path("reason").asString(), refused.toString());
        assertTrue(denied("claude", childBash(session, "child-taker")), "the parent's instance does not serve the subagent");

        String second = newSession("claude");
        prompt("claude", second);
        JsonNode childs = childCall(second, "child-owner", WorkflowKind.OPERATION.toolName(), operation("The subagent runs a command", "skills", skills()));
        assertTrue(childs.path("ok").asBoolean(), childs.toString());
        // A new request of the parent: it takes the subagent's instance by resuming it.
        prompt("claude", second);
        assertTrue(denied("claude", parentBash(second)), "a listed instance does not serve the request until the agent takes it");
        JsonNode resumed = call("claude", second, "resume_workflow", args("runId", childs.path("data").path("runId").asString()));
        assertTrue(resumed.path("ok").asBoolean(), resumed.toString());
        assertFalse(denied("claude", parentBash(second)));
    }

    // ------------------------------------------------------------------------------------------------------------------ the end of an inquiry is its agent's

    /** The hook of the end of a Claude Code subagent. */
    private static JsonNode subagentStop(String session, String agentId) throws IOException {
        return ok(DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStop", null, session, body -> body.put("agent_id", agentId).put("agent_type", "general-purpose")));
    }

    private static String stateOf(String session, String runId) throws IOException {
        JsonNode status = call("claude", session, "get_workflow_status", args("runId", runId, "detail", "compact"));
        return status.path("data").path("state").asString();
    }

    private static String openInquiry(String session, String agentId, String goal) throws IOException {
        JsonNode opened = agentId == null ? call("claude", session, WorkflowKind.INQUIRY.toolName(), inquiry(goal)) : childCall(session, agentId, WorkflowKind.INQUIRY.toolName(), inquiry(goal));
        assertTrue(opened.path("ok").asBoolean(), opened.toString());
        return opened.path("data").path("runId").asString();
    }

    /**
     * The turn end of the main agent closes the inquiry the main agent opened and not the one of a subagent: the instances are independent, and the subagent goes on in its own, which
     * still serves its commands after the main agent's Stop.
     */
    @Test
    void theMainAgentsTurnEndClosesItsOwnInquiryAndLeavesTheOneOfASubagentOpen() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        String mine = openInquiry(session, null, "The main agent answers");
        String childs = openInquiry(session, "child-inquirer", "The subagent investigates");
        JsonNode ended = stop("claude", session);
        assertFalse(ended.toString().contains(GATE), "the request had an instance: " + ended);
        assertEquals("PASSED", stateOf(session, mine), "the inquiry of the main agent is closed with its turn");
        assertFalse("PASSED".equals(stateOf(session, childs)), "the inquiry of the subagent is not the main agent's turn's");
        assertFalse(denied("claude", childBash(session, "child-inquirer")), "the subagent goes on in its own instance");
    }

    /** The subagent's own end closes its inquiry; then it is a request with no instance again, and another subagent's instance is untouched. */
    @Test
    void aSubagentsEndClosesItsInquiryAndItChoosesAgainAfterIt() throws IOException {
        String session = newSession("claude");
        prompt("claude", session);
        String first = openInquiry(session, "child-ending", "The first subagent investigates");
        String other = openInquiry(session, "child-staying", "The other subagent investigates");
        assertFalse(subagentStop(session, "child-ending").toString().contains(GATE), "the subagent had an instance, so its end is not held");
        assertEquals("PASSED", stateOf(session, first), "the inquiry of the subagent is closed with the subagent");
        assertFalse("PASSED".equals(stateOf(session, other)), "the other subagent's inquiry stays open");
        assertTrue(denied("claude", childBash(session, "child-ending")), "a closed instance serves no request: the subagent chooses again");
        assertFalse(denied("claude", childBash(session, "child-staying")));
        openInquiry(session, "child-ending", "The first subagent investigates again");
        assertFalse(denied("claude", childBash(session, "child-ending")), "the new instance serves it");
    }
}
