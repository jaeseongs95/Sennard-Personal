// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.journal.ChangeEvent;
import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.ChangeSource;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.personal.skills.TestJson;
import io.github.jaeseongs95.sennard.server.approval.ApprovalSourceKind;
import io.github.jaeseongs95.sennard.server.approval.RootAction;
import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.workflow.PresenceState;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCall;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCode;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import io.github.jaeseongs95.sennard.server.workflow.RootOwnerPresence;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The thirteen workflow tools through a real MCP client against the assembled server with real PostgreSQL, and the edit gate through the hook
 * endpoint. The host traffic is the recorded fixtures with this test's identifiers put in.
 */
@Tag("business-db")
class WorkflowToolsServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> TOOLS = List.of("plan_workflow", "open_convergence_root", "claim_workflow_attempt", "start_guarded_workflow", "get_convergence_status", "resolve_convergence_gate", "start_workflow",
            "record_stage_result", "get_workflow_status", "list_session_workflows", "finalize_workflow", "abort_workflow", "release_convergence_root", "submit_change_plan");
    private static final List<String> CALLER_KEYS = List.of("_sessionBinding", "_continuityBinding", "_hostAttestation", "_sessionProof");
    private static final List<String> COMBINATORS = List.of("oneOf", "anyOf", "allOf", "not", "if", "then", "else");

    private static RunningServer server;
    private static Path productBase;
    /** One client per host label for the whole class, not one per call: the SDK's transport keeps a JDK HttpClient of its own, so a client made for each call piles up connections on the server. */
    private static DomainServerSupport.Clients clients;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        // The gate does not count the OS temporary directory, so the product files live in this checkout's build directory.
        productBase = Path.of("build", "workflow-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(productBase);
        List<Class<?>> sources = new ArrayList<>(SessionServerSupport.sources());
        sources.add(OnlineOwnerStandIn.class);
        // skills-root is where classify_skills reads the skills tree from (skills-dir is the deployed folder the hooks point at); without it the tree is looked for under the server's home.
        server = SessionServerSupport.started(sources, "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"), "--sennard.skills-root=" + repository);
        clients = new DomainServerSupport.Clients(server.port, Duration.ofSeconds(120));
    }

    @AfterAll
    static void stop() {
        try {
            clients.close();
        } finally {
            server.close();
        }
    }

    // ---- harness

    /** The class's one initialized client of this host label (closed in {@link #stop()}). */
    private static McpSyncClient client(String label) {
        return clients.of(label);
    }

    private static Map<String, Object> codexMeta(String session) {
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        // Every call is its own host call, and an execution observation is consumed once.
        meta.put("callId", "exec-" + UUID.randomUUID());
        return meta;
    }

    private static JsonNode body(McpSchema.CallToolResult result) {
        return MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    private static JsonNode call(String tool, Map<String, Object> arguments, String session) {
        return call(tool, arguments, codexMeta(session));
    }

    private static JsonNode call(String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        return call(client("codex"), tool, arguments, meta);
    }

    private static JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        return body(client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(OperationKeys.withKey(tool, arguments)).meta(meta).build()));
    }

    /**
     * A client of its own for each thread of a concurrent test, built before the threads start and closed after they end: the SDK's source was not at hand to
     * confirm that one McpSyncClient may be called from several threads at once, and these tests exist to race the callers.
     */
    private static final class CallerClients implements AutoCloseable {
        final List<McpSyncClient> clients = new ArrayList<>();

        CallerClients(int count) {
            try {
                for (int i = 0; i < count; i++) {
                    clients.add(DomainServerSupport.client(server.port, "codex", Duration.ofSeconds(120)));
                }
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            clients.forEach(McpSyncClient::close);
        }
    }

    /** The call as a host reports it with this reasoning effort, spelled as that host spells it; null for a host that reports none. */
    private static JsonNode callWithEffort(String effort, String tool, Map<String, Object> arguments, String session) {
        Map<String, Object> meta = codexMeta(session);
        Map<String, Object> turn = new HashMap<>(MAPPER.convertValue(meta.get("x-codex-turn-metadata"), new TypeReference<Map<String, Object>>() { }));
        if (effort == null) {
            turn.remove("reasoning_effort");
        } else {
            turn.put("reasoning_effort", effort);
        }
        meta.put("x-codex-turn-metadata", turn);
        return call(tool, arguments, meta);
    }

    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.get("data");
    }

    private static String code(JsonNode result) {
        return result.path("error").path("code").asString("");
    }

    private static String reason(JsonNode result) {
        return result.path("error").path("details").path("reason").asString("");
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** An attachment of this session with text of its own: the author of an upload is the session, so the same session can name it in the stage result it records. */
    private static ToolServerSupport.Uploaded uploaded(String session) {
        return ToolServerSupport.uploadEvidence(server.port, (tool, input, caller) -> call(tool, input, caller), session);
    }

    /** The evidence item a stage result of this session records: it names an attachment of the session and its digest. */
    private static Map<String, Object> evidenceOf(String session) {
        return ToolServerSupport.observedEvidence(uploaded(session));
    }

    private static Path product() throws Exception {
        Path directory = productBase.resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        return directory;
    }

    /** A start with a change plan (its first write target, or a file of the workspace when that is the whole workspace): the run starts in its executing phase, so its edits pass the gate. */
    private static Map<String, Object> start(Path workspace, List<String> writeTargets) {
        Map<String, Object> arguments = planningStart(workspace, writeTargets);
        arguments.put("changePlan", List.of(".".equals(writeTargets.getFirst()) ? "plan.txt" : writeTargets.getFirst()));
        return arguments;
    }

    /** A start with no change plan: the run starts in its planning phase. */
    private static Map<String, Object> planningStart(Path workspace, List<String> writeTargets) {
        return arguments("goal", "Change " + writeTargets + " in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test")),
                "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(), "writeTargets", writeTargets);
    }

    private static Map<String, Object> startAll(Path workspace) {
        return start(workspace, List.of("."));
    }

    private static String session() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode root(String rootId, String session) {
        return ok(call("get_convergence_status", arguments("rootId", rootId), session)).get("root");
    }

    private static Map<String, Object> releaseArguments(String rootId, String session) {
        return arguments("rootId", rootId, "expectedRevision", root(rootId, session).get("revision").asLong(), "reason", "The work moved elsewhere.");
    }

    private static long count(String table, String where, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql("SELECT COUNT(*) FROM sennard." + table + " WHERE " + where).params(params).query(Long.class).single());
    }

    private static long rootsOf(Path workspace) {
        return count("convergence_roots", "root_json LIKE ?", "%" + workspace.getFileName() + "%");
    }

    private static RootApprovalService approvals() {
        return server.bean(RootApprovalService.class);
    }

    /** The waiting request's identifier the admin page shows: the digest's hex. */
    private static String pageId(String scopeDigest) {
        return scopeDigest.substring("sha256:".length());
    }

    private static String approveOnAdminPage(String scopeDigest) {
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> {
            assertTrue(approvals().approve(pageId(scopeDigest), scopeDigest, new RootApprovalService.Source(ApprovalSourceKind.ADMIN_PAGE, "sha256:" + UUID.randomUUID(), "credential-in-the-test")), "the request is waiting");
            return server.bean(WorkflowStore.class).findRootApproval(scopeDigest).approvalId();
        });
    }

    /** The recorded hook body of this host, with this test's session, working directory and tool in place of the recorded ones. */
    private static byte[] hookBody(String host, String session, Path cwd, String tool, Object input, String agentId) {
        ObjectNode body = (ObjectNode) MAPPER.readTree(HookTestSupport.fixtureText("windows", host, "PreToolUse"));
        if (host.equals("agy")) {
            body.put("conversationId", session);
            body.putArray("workspacePaths").add(cwd.toString());
            body.putObject("toolCall").put("name", tool).set("args", MAPPER.valueToTree(input));
        } else {
            body.put("session_id", session).put("cwd", cwd.toString()).put("tool_name", tool).put("tool_use_id", "call-" + UUID.randomUUID());
            body.set("tool_input", MAPPER.valueToTree(input));
            if (agentId != null) {
                body.put("agent_id", agentId).put("agent_type", "general-purpose");
            }
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String hookPath(String host) {
        return "/hooks/v1/" + host + "/PreToolUse" + (host.equals("agy") ? "" : "/continuity");
    }

    private static JsonNode hook(String host, String session, Path cwd, String tool, Object input, String agentId) throws Exception {
        RawHttp.Response response = SessionServerSupport.post(server.port, hookPath(host), hookBody(host, session, cwd, tool, input, agentId));
        assertEquals(200, response.status(), () -> new String(response.body(), StandardCharsets.UTF_8));
        return SessionServerSupport.json(response);
    }

    /** The refusal text, or null when the edit may run. */
    private static String refusal(String host, JsonNode output) {
        if (host.equals("agy")) {
            return output.path("decision").asString("").equals("deny") ? output.path("reason").asString("") : null;
        }
        JsonNode specific = output.path("hookSpecificOutput");
        return specific.path("permissionDecision").asString("").equals("deny") ? specific.path("permissionDecisionReason").asString("") : null;
    }

    private static JsonNode patch(String session, Path cwd, String name) throws Exception {
        return hook("codex", session, cwd, "apply_patch", Map.of("command", "*** Begin Patch\n*** Add File: " + name + "\n*** End Patch"), null);
    }

    private static boolean denied(JsonNode output) {
        return refusal("codex", output) != null;
    }

    // ---- the tool contract, seen by a real client

    private static Map<String, McpSchema.Tool> listed() {
        Map<String, McpSchema.Tool> tools = new LinkedHashMap<>();
        client("codex").listTools().tools().forEach(tool -> tools.put(tool.name(), tool));
        return tools;
    }

    private static JsonNode schema(McpSchema.Tool tool) {
        return MAPPER.valueToTree(tool.inputSchema());
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.propertyNames().forEach(out::add);
        return out;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(item -> out.add(item.asString()));
        return out;
    }

    @Test
    void everyWorkflowToolIsListedAsAHostAcceptsIt() {
        Map<String, McpSchema.Tool> tools = listed();
        assertTrue(tools.keySet().containsAll(TOOLS), tools.keySet().toString());
        for (String name : TOOLS) {
            McpSchema.Tool tool = tools.get(name);
            JsonNode schema = schema(tool);
            assertEquals("object", schema.path("type").asString(), name);
            assertNotNull(tool.description(), name);
            for (String combinator : COMBINATORS) {
                assertFalse(schema.has(combinator), name + " " + combinator);
            }
            String text = schema.toString();
            assertFalse(text.contains("$ref"), name);
            assertFalse(text.contains("$defs"), name);
            for (String field : CALLER_KEYS) {
                assertFalse(text.contains(field), name + " " + field);
            }
        }
    }

    static Stream<Arguments> closedSchemas() {
        return Stream.of(
                Arguments.of("start_workflow", Set.of("goal", "stages", "skills", "acceptanceCriteria", "writeTargets", "operationKey"), Set.of("goal", "stages", "skills", "classificationOperationKey", "acceptanceCriteria", "workspace", "writeTargets", "rootId", "priorFailure", "changePlan", "operationKey")),
                Arguments.of("submit_change_plan", Set.of("runId", "expectedRevision", "files", "operationKey"), Set.of("runId", "expectedRevision", "files", "operationKey")),
                Arguments.of("release_convergence_root", Set.of("rootId", "expectedRevision", "reason", "operationKey"), Set.of("rootId", "expectedRevision", "reason", "userApprovalRefs", "operationKey")),
                Arguments.of("finalize_workflow", Set.of("runId", "expectedRevision", "operationKey"), Set.of("runId", "expectedRevision", "responseMode", "operationKey")),
                Arguments.of("abort_workflow", Set.of("runId", "expectedRevision", "operationKey"), Set.of("runId", "expectedRevision", "responseMode", "operationKey")),
                Arguments.of("get_convergence_status", Set.of("rootId"), Set.of("rootId", "detail")),
                Arguments.of("get_workflow_status", Set.of("runId"), Set.of("runId", "detail")),
                Arguments.of("list_session_workflows", Set.of(), Set.of("openRootAfter")));
    }

    @ParameterizedTest
    @MethodSource("closedSchemas")
    void aToolPublishesItsInput(String name, Set<String> required, Set<String> properties) {
        JsonNode schema = schema(listed().get(name));
        assertEquals(new TreeSet<>(required), texts(schema.get("required")), name);
        assertEquals(new TreeSet<>(properties), names(schema.get("properties")), name);
        assertFalse(schema.path("additionalProperties").asBoolean(true), name);
    }

    @Test
    void contractDerivedToolsKeepTheirSpecialRules() {
        Map<String, McpSchema.Tool> tools = listed();
        JsonNode plan = schema(tools.get("plan_workflow"));
        assertTrue(names(plan.get("properties")).containsAll(List.of("taskEnvelope", "evaluationAuditPurpose", "requiredAuditPhase", "taskId")), plan.toString());
        JsonNode record = schema(tools.get("record_stage_result"));
        assertFalse(record.get("properties").has("executionContext"));
        assertFalse(texts(record.get("required")).contains("executionContext"));
        assertTrue(record.get("properties").has("responseMode"));
        JsonNode claim = schema(tools.get("claim_workflow_attempt"));
        assertFalse(texts(claim.get("required")).contains("taskEnvelope"));
        assertEquals(Set.of("frame"), new java.util.HashSet<>(texts(claim.path("dependentRequired").get("taskEnvelope"))));
        JsonNode guarded = schema(tools.get("start_guarded_workflow"));
        assertTrue(texts(guarded.get("required")).contains("skills"));
        assertFalse(texts(guarded.get("required")).contains("plan"));
        assertTrue(schema(tools.get("open_convergence_root")).get("properties").has("responseMode"));
        assertEquals(Boolean.TRUE, tools.get("get_workflow_status").annotations().readOnlyHint());
        assertEquals(Boolean.TRUE, tools.get("get_convergence_status").annotations().readOnlyHint());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"schemaVersion\":\"1.0.0\"}", "{\"schemaVersion\":\"1.0.0\",\"taskEnvelope\":{\"schemaVersion\":\"1.0.0\"}}", "{\"taskId\":\"mixed\",\"taskEnvelope\":{}}"})
    @SuppressWarnings("unchecked")
    void aPlanInputThatFitsNeitherFormIsInvalidInput(String input) {
        assertEquals("INVALID_INPUT", code(call("plan_workflow", MAPPER.readValue(input, Map.class), session())));
    }

    @Test
    void anUnknownRunOrRootIsNotFound() {
        assertEquals("RUN_NOT_FOUND", code(call("get_workflow_status", arguments("runId", "run-" + UUID.randomUUID()), session())));
        assertEquals("RUN_NOT_FOUND", code(call("get_convergence_status", arguments("rootId", "root-" + UUID.randomUUID()), session())));
        assertEquals("RUN_NOT_FOUND", code(call("abort_workflow", arguments("runId", "run-" + UUID.randomUUID(), "expectedRevision", 0), session())));
    }

    @Test
    void anInvalidResponseModeIsRefusedByTheTool() throws Exception {
        JsonNode started = ok(call("start_workflow", startAll(product()), session()));
        JsonNode refused = call("get_workflow_status", arguments("runId", started.get("runId").asString(), "detail", "verbose"), session());
        assertEquals("INVALID_INPUT", code(refused));
    }

    @Test
    void aCallerFieldInTheArgumentsIsNeverRead() throws Exception {
        String forger = session();
        McpSchema.CallToolResult refused = client("codex").callTool(McpSchema.CallToolRequest.builder("start_workflow")
                .arguments(arguments("_sessionBinding", Map.of("host", "forged-host", "sessionId", "forged-session"))).meta(codexMeta(forger)).build());
        assertEquals(Boolean.TRUE, refused.isError());
    }

    @Test
    void aHostWithNoAdapterIsAskedToBindNotServed() throws Exception {
        JsonNode result = body(client("a-host-no-one-has-written-an-adapter-for").callTool(McpSchema.CallToolRequest.builder("get_workflow_status").arguments(Map.of("runId", "run-x")).build()));
        assertEquals("BINDING_REQUIRED", code(result), result.toString());
    }

    // ---- start_workflow

    @Test
    void aMinimalStartOpensOneRunAndLiftsTheGate() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(workspace), owner));
        assertEquals("RUNNING", started.get("state").asString());
        assertTrue(started.get("lateAdoption").isNull());
        assertTrue(texts(started.get("defaultsUsed")).containsAll(List.of("taskId", "riskLevel", "authorization", "frame")), started.toString());
        assertFalse(texts(started.get("defaultsUsed")).contains("workspace"));
        assertFalse(texts(started.get("defaultsUsed")).contains("writeTargets"));
        assertEquals(1, rootsOf(workspace));
        for (String name : List.of("a.ts", "b.ts", "c.ts", "d.ts")) {
            assertFalse(denied(patch(owner, workspace, name)), name);
        }
    }

    /** The size of stages is the kind's: the change plans one or more (an empty list is refused, as the schema says), the kinds with no files of their own plan none and start with no stage. */
    @Test
    void aChangeStartWithNoStageIsRefusedWhileAnInquiryPlansNoneAndStarts() throws Exception {
        Path workspace = product();
        Map<String, Object> arguments = new LinkedHashMap<>(startAll(workspace));
        arguments.put("stages", List.of());
        JsonNode refused = call("start_workflow", arguments, session());
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals(0, rootsOf(workspace), "a refused start leaves nothing");
        JsonNode inquiry = ok(call("start_inquiry_workflow", arguments("goal", "Answer where the gate is", "workspace", product().toString()), session()));
        assertEquals("RUNNING", inquiry.get("state").asString());
    }

    @Test
    void onlySuccessfulWorkflowEntriesIncludeTheInstalledIntakeAndSkillTargets() throws Exception {
        String owner = session();
        List<String> started = contents("start_workflow", startAll(product()), owner);
        assertEntryIntake(started);
        String runId = MAPPER.readTree(started.getFirst()).path("data").path("runId").asString();
        assertFalse(runId.isBlank(), started.toString());
        assertEntryIntake(contents("resume_workflow", arguments("runId", runId), owner));
        long revision = MAPPER.readTree(started.getFirst()).path("data").path("revision").asLong();
        JsonNode paused = ok(call("pause_workflow", arguments("runId", runId, "expectedRevision", revision, "reason", "Waiting for input", "resumeCondition", "Input supplied"), owner));
        assertEntryIntake(contents("resume_workflow", arguments("runId", runId, "expectedRevision", paused.path("revision").asLong(), "operationKey", OperationKeys.fresh()), owner));
        assertEntryIntake(contents("start_inquiry_workflow", arguments("goal", "Investigate", "workspace", product().toString()), session()));
        assertEntryIntake(contents("start_operation_workflow", arguments("goal", "Check the environment", "target", "test workspace", "workspace", product().toString(),
                "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test"))), session()));
        for (List<String> response : List.of(contents("get_workflow_status", arguments("runId", runId), owner),
                contents("resume_workflow", arguments("runId", "run-missing"), owner))) {
            assertFalse(String.join("\n", response).contains("Sennard intake ("), response.toString());
        }
    }

    private static void assertEntryIntake(List<String> contents) {
        assertTrue(MAPPER.readTree(contents.getFirst()).path("ok").asBoolean(), contents.toString());
        assertTrue(contents.size() > 1, contents.toString());
        String notes = contents.get(1);
        assertTrue(notes.contains("Sennard intake (") && notes.contains("Skill targets.") && notes.contains("- orchestrator:"), notes);
        assertFalse(notes.contains("[Sennard global context") || notes.contains("[Sennard role context") || notes.contains("[Sennard current memory"), notes);
    }

    // ---- the change plan and the change journal

    private static ChangeJournalService journal() {
        return server.bean(ChangeJournalService.class);
    }

    /** What a call answered besides its JSON: the notes after a workflow change. */
    private static List<String> contents(String tool, Map<String, Object> arguments, String session) {
        McpSchema.CallToolResult result = client("codex").callTool(McpSchema.CallToolRequest.builder(tool).arguments(OperationKeys.withKey(tool, arguments)).meta(codexMeta(session)).build());
        return result.content().stream().map(content -> ((McpSchema.TextContent) content).text()).toList();
    }

    /** Codex's apply_patch that added a file, as its PostToolUse hook reports it once the tool finished. */
    private static void finishedPatch(String session, Path cwd, String name) throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(HookTestSupport.fixtureText("windows", "codex", "PostToolUse"));
        body.put("session_id", session).put("cwd", cwd.toString()).put("tool_name", "apply_patch").put("tool_use_id", "call-" + UUID.randomUUID());
        body.set("tool_input", MAPPER.valueToTree(Map.of("command", "*** Begin Patch\n*** Add File: " + name + "\n*** End Patch")));
        RawHttp.Response response = SessionServerSupport.post(server.port, "/hooks/v1/codex/PostToolUse", body.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.status(), () -> new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void aStartWithNoChangePlanIsPlanningAndRefusesEditsInsideItsWriteTargets() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of(".")), owner));
        assertEquals("PLANNING", started.path("changeJournal").path("phase").asString(), started.toString());
        assertEquals(1, started.path("changeJournal").path("planRevision").asLong());
        String refused = refusal("codex", patch(owner, workspace, "a.ts"));
        assertNotNull(refused, "a write inside the write targets waits for the change plan");
        assertTrue(refused.contains("planning phase") && refused.contains(started.get("runId").asString()) && refused.contains("submit_change_plan"), refused);
        Map<String, String> read = Map.of("cmd", "git status --short", "shell", "/bin/bash");
        assertEquals(null, refusal("codex", hook("codex", owner, workspace, "exec_command", read, null)), "reading and investigating stay open in the planning phase");
        assertEquals(Optional.of(WorkflowPhase.PLANNING), journal().phase(started.get("runId").asString()));
    }

    /** Each host, a file tool of it, and what its refusal says the host's model calls submit_change_plan by. */
    static Stream<Arguments> planningRefusalOfEachHost() {
        return Stream.of(Arguments.of("codex", "apply_patch", "patch", List.of("Your host calls this tool mcp__sennard__submit_change_plan.")),
                Arguments.of("claude", "Write", "file_path", List.of("Your host calls this tool mcp__plugin_sennard_sennard__submit_change_plan;",
                        "load it first with ToolSearch (select:mcp__plugin_sennard_sennard__submit_change_plan).")),
                Arguments.of("agy", "write_to_file", "TargetFile", List.of("Your host calls it through call_mcp_tool: ServerName sennard_sennard, ToolName submit_change_plan,")));
    }

    /**
     * The refusal of an edit in a run's planning phase (an agent that was refused it eight times in a row had to look up the run's revision and the tool's name, and only then
     * called the tool): for each host it gives the call that opens editing as it stands, with the run's current revision and the file this edit was blocked on, and the name this
     * host's model calls the tool by. The arguments it gives are the ones the tool takes, and the edit passes after that call.
     */
    @ParameterizedTest
    @MethodSource("planningRefusalOfEachHost")
    void theRefusalInThePlanningPhaseGivesTheCallThatOpensEditingAsTheHostMakesIt(String host, String tool, String form, List<String> hostWording) throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of(".")), owner));
        String runId = started.get("runId").asString();
        long revision = started.get("revision").asLong();
        // The refusal names the file as the hook resolved it, against the working directory.
        String blocked = workspace.resolve("a.ts").toString();
        String refused = refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "a.ts"), null));
        assertNotNull(refused, "a write inside the write targets waits for the change plan");
        Map<String, Object> plan = arguments("runId", runId, "expectedRevision", revision, "files", List.of(blocked), "operationKey", "<operationKey you make, 16-128 characters>");
        int begin = refused.indexOf("with the arguments {") + "with the arguments ".length();
        assertTrue(begin > "with the arguments ".length(), refused);
        int end = begin;
        for (int depth = 0; end == begin || depth > 0; end++) {
            depth += refused.charAt(end) == '{' ? 1 : refused.charAt(end) == '}' ? -1 : 0;
        }
        assertEquals(MAPPER.readTree(MAPPER.writeValueAsString(plan)), MAPPER.readTree(refused.substring(begin, end)), refused);
        assertTrue(refused.contains("revision is " + revision), refused);
        hostWording.forEach(wording -> assertTrue(refused.contains(wording), wording + " in " + refused));
        // The call the refusal gives is accepted as it stands (the key is the one thing the model makes), and the same edit passes after it.
        ok(call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision, "files", List.of(blocked)), owner));
        assertEquals(null, refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "a.ts"), null)));
    }

    /** A run that starts in its planning phase says so in its result, with the values the call that opens editing takes, and that a change plan given to the start skips the phase. */
    @Test
    void aStartWithNoChangePlanSaysItIsPlanningAndWhatToCallNext() throws Exception {
        Path workspace = product();
        List<String> contents = contents("start_workflow", planningStart(workspace, List.of(".")), session());
        String notes = String.join("\n", contents.subList(1, contents.size()));
        JsonNode started = ok(MAPPER.readTree(contents.getFirst()));
        assertTrue(notes.contains("Run " + started.get("runId").asString() + " started in its planning phase"), notes);
        assertTrue(notes.contains("submit_change_plan with runId " + started.get("runId").asString() + ", expectedRevision " + started.get("revision").asLong()), notes);
        assertTrue(notes.contains("gives changePlan starts the run executing at once"), notes);
        List<String> executing = contents("start_workflow", start(product(), List.of(".")), session());
        assertFalse(String.join("\n", executing).contains("planning phase"), "a start with a change plan is executing and has nothing to submit");
    }

    @Test
    void aStartWithAChangePlanIsExecutingAtOnceAndItsBaselineIsTaken() throws Exception {
        Path workspace = product();
        Files.writeString(workspace.resolve("kept.txt"), "kept");
        String owner = session();
        Map<String, Object> request = start(workspace, List.of("."));
        request.put("changePlan", List.of("kept.txt", "new/created.txt"));
        JsonNode started = ok(call("start_workflow", request, owner));
        String runId = started.get("runId").asString();
        assertEquals("EXECUTING", started.path("changeJournal").path("phase").asString(), started.toString());
        assertTrue(started.path("changeJournal").path("unreadable").isEmpty(), started.toString());
        assertEquals(null, refusal("codex", patch(owner, workspace, "a.ts")));
        assertEquals(Optional.of(WorkflowPhase.EXECUTING), journal().phase(runId));
        List<ChangeJournalService.BaselineEntry> baseline = journal().baseline(runId);
        assertEquals(List.of(workspace.resolve("kept.txt")), baseline.stream().map(ChangeJournalService.BaselineEntry::path).toList(), "only files that exist are in the baseline");
        assertNotNull(baseline.getFirst().sha256(), "a planned file is hashed");
        assertEquals(Set.of(workspace.resolve("kept.txt"), workspace.resolve("new/created.txt")), journal().plan(runId).files());
    }

    @Test
    void aPlanOutsideTheWriteTargetsRefusesTheStartAndLeavesNothing() throws Exception {
        Path workspace = product();
        Map<String, Object> request = planningStart(workspace, List.of("src"));
        request.put("changePlan", List.of("src/a.ts", "other/b.ts"));
        JsonNode refused = call("start_workflow", request, session());
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("PLAN_OUTSIDE_WRITE_TARGETS", reason(refused), refused.toString());
        assertEquals(0, rootsOf(workspace));
    }

    @Test
    void submittingTheChangePlanOpensTheExecutingPhaseAndLaterPlansAreRevisions() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of("src")), owner));
        String runId = started.get("runId").asString();
        long revision = started.get("revision").asLong();
        assertNotNull(refusal("codex", patch(owner, workspace, "src/a.ts")));
        assertEquals("STALE_REVISION", code(call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision + 7, "files", List.of("src/a.ts")), owner)));
        JsonNode outside = call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision, "files", List.of("elsewhere/a.ts")), owner);
        assertEquals("INVALID_INPUT", code(outside), outside.toString());
        assertEquals("PLAN_OUTSIDE_WRITE_TARGETS", reason(outside), outside.toString());
        assertEquals(Optional.of(WorkflowPhase.PLANNING), journal().phase(runId), "a refused plan opens nothing");

        JsonNode submitted = ok(call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision, "files", List.of("src/a.ts")), owner));
        assertEquals("EXECUTING", submitted.path("phase").asString(), submitted.toString());
        assertEquals(2, submitted.path("planRevision").asLong(), "revision 1 is the empty plan the planning phase began with");
        assertEquals(null, refusal("codex", patch(owner, workspace, "src/a.ts")));

        JsonNode revised = ok(call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision, "files", List.of("src/a.ts", "src/b.ts")), owner));
        assertEquals(3, revised.path("planRevision").asLong(), revised.toString());
        assertEquals(Set.of(workspace.resolve("src/a.ts"), workspace.resolve("src/b.ts")), journal().plan(runId).files());
    }

    /** AGS3-D-0267: the receipt of a change plan keeps its revision number, written in the transaction of the journal; the file reads stay outside it. */
    @Test
    void aChangePlanThatComesAgainWithItsKeyIsAnsweredWithTheRevisionTheFirstOneWroteAndIsNotWrittenTwice() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of("src")), owner));
        String runId = started.get("runId").asString();
        long revision = started.get("revision").asLong();
        String key = OperationKeys.fresh();
        Map<String, Object> submit = keyed(arguments("runId", runId, "expectedRevision", revision, "files", List.of("src/a.ts")), key);
        JsonNode first = ok(call("submit_change_plan", submit, owner));
        assertEquals(2, first.path("planRevision").asLong(), first.toString());
        assertEquals(1, receiptsOf("submit_change_plan", key));
        JsonNode again = ok(call("submit_change_plan", submit, owner));
        assertEquals(2, again.path("planRevision").asLong(), "the first revision again, not a third: " + again);
        assertEquals("EXECUTING", again.path("phase").asString(), again.toString());
        assertEquals(1, receiptsOf("submit_change_plan", key));
        JsonNode underNewKey = ok(call("submit_change_plan", keyed(submit, OperationKeys.fresh()), owner));
        assertEquals(3, underNewKey.path("planRevision").asLong(), "the same plan under a new key is a revision of its own: " + underNewKey);
        Map<String, Object> other = keyed(arguments("runId", runId, "expectedRevision", revision, "files", List.of("src/a.ts", "src/b.ts")), key);
        assertKeyConflict(call("submit_change_plan", other, owner));
        assertEquals(1, receiptsOf("submit_change_plan", key));
    }

    /**
     * AGS3-D-0267: the first answer of a change plan is what a request that comes again gets, as it was given, and not an answer made again from the run as it has become: after the run
     * moved on (the run was aborted, so its revision is another and it is no longer active), the repeat still says the revision the first answer said.
     */
    @Test
    void aChangePlanThatComesAgainAfterTheRunMovedOnIsAnsweredWithTheFirstAnswerAsItWasGiven() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of("src")), owner));
        String runId = started.get("runId").asString();
        String key = OperationKeys.fresh();
        Map<String, Object> submit = keyed(arguments("runId", runId, "expectedRevision", started.get("revision").asLong(), "files", List.of("src/a.ts")), key);
        JsonNode first = ok(call("submit_change_plan", submit, owner));
        JsonNode status = ok(call("get_workflow_status", arguments("runId", runId), owner));
        ok(call("abort_workflow", arguments("runId", runId, "expectedRevision", status.get("revision").asLong()), owner));
        assertNotEquals(first.path("revision").asLong(), ok(call("get_workflow_status", arguments("runId", runId), owner)).path("revision").asLong(), "the run is not at the revision of the first answer any more");
        assertEquals(first, ok(call("submit_change_plan", submit, owner)), "the first answer as it was given");
        assertEquals(1, receiptsOf("submit_change_plan", key));
    }

    @Test
    void aFinishedEditIsRecordedByTheHookAndAChangeOutsideThePlanIsReportedAtTheStageResultAndRefusesTheFinishUntilThePlanNamesIt() throws Exception {
        Path workspace = product();
        Files.createDirectories(workspace.resolve("src"));
        String owner = session();
        Map<String, Object> request = planningStart(workspace, List.of("src"));
        request.put("changePlan", List.of("src/a.ts"));
        JsonNode started = ok(call("start_workflow", request, owner));
        String runId = started.get("runId").asString();

        Files.writeString(workspace.resolve("src/a.ts"), "planned");
        finishedPatch(owner, workspace, "src/a.ts");
        List<ChangeEvent> events = journal().changes(runId).events();
        assertEquals(List.of(workspace.resolve("src/a.ts").toString()), events.stream().map(ChangeEvent::path).toList(), "the hook recorded the edit it was told of");
        assertEquals(List.of(ChangeSource.HOOK), events.stream().map(ChangeEvent::source).toList());
        assertNotNull(events.getFirst().sha256());

        // A write no hook reported (a shell the hook could not read): found by the comparison of the write roots.
        Files.writeString(workspace.resolve("src/b.ts"), "not in the plan");
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        List<String> staged = contents("record_stage_result", passedStage(owner, runId, receipt), owner);
        String note = String.join("\n", staged.subList(1, staged.size()));
        assertTrue(note.contains("outside the submitted change plan") && note.contains(workspace.resolve("src/b.ts").toString()), note);
        assertFalse(note.contains(workspace.resolve("src/a.ts").toString()), "the planned file is not reported: " + note);
        assertEquals(Set.of(workspace.resolve("src/b.ts")), journal().changes(runId).unplannedChanges());
        assertEquals(Set.of("HOOK:" + workspace.resolve("src/a.ts"), "RESCAN:" + workspace.resolve("src/b.ts")),
                journal().changes(runId).events().stream().map(event -> event.source() + ":" + event.path()).collect(java.util.stream.Collectors.toSet()), "the comparison recorded only what the hook had not");

        // (a) The finish is refused while a change is outside the plan: the refusal names every such path (details.unplanned) and says how to go on; the run stays open.
        long revision = ok(call("get_workflow_status", arguments("runId", runId), owner)).get("revision").asLong();
        JsonNode refused = call("finalize_workflow", arguments("runId", runId, "expectedRevision", revision), owner);
        assertEquals("GATE_FAILED", code(refused), refused.toString());
        assertEquals("UNPLANNED_CHANGES", reason(refused), refused.toString());
        assertEquals(List.of(workspace.resolve("src/b.ts").toString()), MAPPER.convertValue(refused.path("error").path("details").path("unplanned"), new TypeReference<List<String>>() { }), refused.toString());
        String sentence = refused.path("error").path("message").asString();
        assertTrue(sentence.contains(workspace.resolve("src/b.ts").toString()) && sentence.contains("submit_change_plan") && sentence.contains("finalize_workflow"), sentence);
        assertEquals("workflow.service.unplannedChangesBlockFinalize", refused.path("error").path("messageKey").asString(), refused.toString());
        JsonNode stillOpen = ok(call("get_workflow_status", arguments("runId", runId), owner));
        assertEquals("RUNNING", stillOpen.path("state").asString(), "the refused finish changed nothing");
        assertEquals(revision, stillOpen.get("revision").asLong());

        // (b) The plan that names the file answers for the change: the same finish, with the same revision, passes.
        ok(call("submit_change_plan", arguments("runId", runId, "expectedRevision", revision, "files", List.of("src/a.ts", "src/b.ts")), owner));
        List<String> finished = contents("finalize_workflow", arguments("runId", runId, "expectedRevision", revision), owner);
        assertTrue(finished.getFirst().contains("\"state\":\"PASSED\""), finished.getFirst());
        String finishNote = String.join("\n", finished.subList(1, finished.size()));
        assertFalse(finishNote.contains("outside the submitted change plan: "), "nothing is left outside the plan: " + finishNote);
        assertTrue(finishNote.contains("No change outside the submitted change plan"), finishNote);
    }

    /** (c) A run whose every change is inside its plan finishes as it always did, and says the journal found nothing outside the plan. */
    @Test
    void aFinishWithNoChangeOutsideThePlanPassesAndSaysTheJournalIsClean() throws Exception {
        Path workspace = product();
        Files.createDirectories(workspace.resolve("src"));
        String owner = session();
        Map<String, Object> request = planningStart(workspace, List.of("src"));
        request.put("changePlan", List.of("src/a.ts"));
        String runId = ok(call("start_workflow", request, owner)).get("runId").asString();
        Files.writeString(workspace.resolve("src/a.ts"), "planned");
        finishedPatch(owner, workspace, "src/a.ts");
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        ok(call("record_stage_result", passedStage(owner, runId, receipt), owner));
        long revision = ok(call("get_workflow_status", arguments("runId", runId), owner)).get("revision").asLong();
        List<String> finished = contents("finalize_workflow", arguments("runId", runId, "expectedRevision", revision), owner);
        assertTrue(finished.getFirst().contains("\"state\":\"PASSED\""), finished.getFirst());
        String finishNote = String.join("\n", finished.subList(1, finished.size()));
        assertTrue(finishNote.contains("No change outside the submitted change plan"), finishNote);
    }

    /** An abort is the way out of a run that is not to be finished, so a change outside the plan does not refuse it. */
    @Test
    void anAbortIsNotRefusedForAChangeOutsideThePlan() throws Exception {
        Path workspace = product();
        Files.createDirectories(workspace.resolve("src"));
        String owner = session();
        Map<String, Object> request = planningStart(workspace, List.of("src"));
        request.put("changePlan", List.of("src/a.ts"));
        String runId = ok(call("start_workflow", request, owner)).get("runId").asString();
        Files.writeString(workspace.resolve("src/b.ts"), "not in the plan");
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        JsonNode refused = call("finalize_workflow", arguments("runId", runId, "expectedRevision", receipt.get("revision").asLong()), owner);
        assertEquals("UNPLANNED_CHANGES", reason(refused), refused.toString());
        JsonNode aborted = ok(call("abort_workflow", arguments("runId", runId, "expectedRevision", receipt.get("revision").asLong()), owner));
        assertNotEquals("RUNNING", aborted.get("state").asString(), aborted.toString());
    }

    @Test
    void editsInsideTheWriteTargetsOfAnotherSessionsPlanningRunAreRefusedToo() throws Exception {
        Path workspace = product();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of(".")), session()));
        assertEquals("PLANNING", started.path("changeJournal").path("phase").asString());
        // A session with no workflow of its own is refused for having none, not for this run's phase: the planning refusal belongs to the session that owns the run.
        String refused = refusal("codex", patch(session(), workspace, "a.ts"));
        assertNotNull(refused);
        assertTrue(refused.contains("no workflow handles this request"), refused);
        assertFalse(refused.contains("planning phase"), refused);
    }

    @Test
    void aFailedStartDoesNotMakeRefusedWritesLateAdoption() throws Exception {
        Path workspace = product();
        String owner = session();
        server.bean(WorkflowService.class).recordStartFailure(new WorkflowCall("codex", owner, null, null, null, null, "en"), WorkflowCode.MCP_UNAVAILABLE);
        boolean beforeStart = denied(patch(owner, workspace, "a.ts"));
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("c.ts")), owner));
        boolean outside = denied(patch(owner, workspace, "b.ts"));
        assertTrue(started.path("lateAdoption").isNull(), "a refused attempt is not late adoption: " + started);
        assertTrue(beforeStart && outside, "a failed start grants no edit permission");
        assertFalse(denied(patch(owner, workspace, "c.ts")));
    }

    /** The {@code start_workflow} arguments a refusal prints as its minimal call. */
    private static Map<String, Object> exampleOf(String refused) {
        int at = refused.indexOf("start_workflow {");
        assertTrue(at >= 0, refused);
        int depth = 0;
        int end = at + "start_workflow ".length();
        int begin = end;
        do {
            char next = refused.charAt(end++);
            depth += next == '{' ? 1 : next == '}' ? -1 : 0;
        } while (depth > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> example = new HashMap<>(MAPPER.readValue(refused.substring(begin, end), Map.class));
        return example;
    }

    /** The refusal of the first file an edit tool of this host changes without a workflow (a request that no workflow handles has no file it may change): the text with the example in it. */
    private static String firstEditRefusal(String host, String tool, String form, Path workspace, String owner) throws Exception {
        String refused = refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "a.ts"), null));
        assertNotNull(refused, "the first edit of a request with no workflow is refused");
        return refused;
    }

    /** A tool call as the model of this host makes it: through its own client with its own recorded call identity. */
    private static JsonNode callAs(String host, String tool, Map<String, Object> arguments, String session) throws Exception {
        if (host.equals("agy")) {
            return ChangeWorkflows.callAs(server, host, session, tool, arguments);
        }
        String toolUse = "toolu_" + UUID.randomUUID();
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", host, session, toolUse));
        if (host.equals("codex")) {
            // Every call is its own host call, as in codexMeta.
            meta.put("callId", "exec-" + UUID.randomUUID());
        }
        if (host.equals("claude")) {
            // Claude's call carries only its tool-use id: the session is the one its PreToolUse hook of that id admitted (the hook is why the server knows whose call it is).
            String hookBody = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8)
                    .replace("mcp__plugin_sennard-probe_probe__probe_echo", "mcp__plugin_sennard_sennard__" + tool);
            assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.getBytes(StandardCharsets.UTF_8)).status());
        }
        return call(client(host), tool, arguments, meta);
    }

    @Test
    void theRefusalsStartExampleIsAStartThatWorks() throws Exception {
        Path workspace = product();
        String owner = session();
        // The first edit of a request that no workflow handles is the one that is refused, and its refusal carries the example.
        String refused = refusal("codex", patch(owner, workspace, "c.ts"));
        assertNotNull(refused, "an edit with no workflow is refused at once");
        Map<String, Object> example = exampleOf(refused);
        example.put("workspace", workspace.toString());
        // What the model decides from its work is a placeholder in the example, never a value of the example's own: the write target, the skill (the session made no classification) and
        // the stage, one for each phase of the change's process that records a stage's result.
        assertEquals(List.of("<path to change>"), example.get("writeTargets"), refused);
        example.put("writeTargets", List.of("c.ts"));
        assertEquals(List.of(arguments("skillId", "<skill you chose after classify_skills>", "reason", "<why this skill applies>")), example.get("skills"), refused);
        String stagePlaceholder = "<registry capability that records this phase's result>";
        assertEquals(List.of(stagePlaceholder), example.get("stages"), refused);
        example.put("goal", "Change c.ts in the example's workspace.");
        example.put("acceptanceCriteria", List.of("c.ts holds the change."));
        example.put("skills", List.of(arguments("skillId", "minimal-implementation", "reason", "The example makes one small change to one file.")));
        // The key is the caller's to make: the example names it as a placeholder, and a start without one is refused.
        assertTrue(example.containsKey("operationKey"), refused);
        example.put("operationKey", "example-start-" + UUID.randomUUID());
        // (a) Sent back as it is, the stage's placeholder is refused as a placeholder (every other field is filled), and nothing is started.
        JsonNode copied = call("start_workflow", example, owner);
        assertEquals("INVALID_INPUT", code(copied), copied.toString());
        assertEquals("PLACEHOLDER_RETURNED", reason(copied), copied.toString());
        assertEquals("stages", copied.path("error").path("details").path("field").asString(), copied.toString());
        assertEquals(0, rootsOf(workspace));
        // (b) Filled with a capability of the registry, the same example is a start that works.
        example.put("stages", List.of("minimal-implementation"));
        assertEquals("RUNNING", ok(call("start_workflow", example, owner)).get("state").asString());
    }

    /**
     * The example the refusal of each host prints names no skill: an agent that copied its one skill (minimal-implementation) was the only one the server ever saw called. And what
     * is printed in the place of the choices of the model is refused when it comes back, with the reason and what to put there, so a copy cannot pass as a start.
     */
    @ParameterizedTest
    @MethodSource("editToolsOfEachHost")
    void theRefusalsExampleHoldsNoSkillOfItsOwnAndItsPlaceholdersAreRefusedWhenTheyComeBack(String host, String tool, String form) throws Exception {
        Path workspace = product();
        String owner = session();
        String refused = firstEditRefusal(host, tool, form, workspace, owner);
        Map<String, Object> example = exampleOf(refused);
        assertEquals(List.of(arguments("skillId", "<skill you chose after classify_skills>", "reason", "<why this skill applies>")), example.get("skills"), refused);
        assertFalse(example.containsKey("classificationOperationKey"), "the session made no classification, so the example cites none: " + refused);
        assertFalse(MAPPER.writeValueAsString(example.get("skills")).contains("minimal-implementation"), refused);
        // Sent back as it is (with the two values the example asks the caller for), the example is refused for its first placeholder, and nothing is started.
        example.put("workspace", workspace.toString());
        example.put("operationKey", "example-copy-" + UUID.randomUUID());
        JsonNode copied = call("start_workflow", example, owner);
        assertEquals("INVALID_INPUT", code(copied), copied.toString());
        assertEquals("PLACEHOLDER_RETURNED", reason(copied), copied.toString());
        assertEquals("goal", copied.path("error").path("details").path("field").asString(), copied.toString());
        assertEquals(0, rootsOf(workspace));
    }

    /** The example is filled from the classification of the session that is refused, with its key, and from nothing else. */
    @ParameterizedTest
    @MethodSource("editToolsOfEachHost")
    void theRefusalsExampleTakesItsSkillsFromTheClassificationOfTheSession(String host, String tool, String form) throws Exception {
        Path workspace = product();
        String owner = session();
        String key = "op-example-" + UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>(ToolContractServerTest.classifyInput(key, "req-" + key, "Change the files of the example."));
        input.put("explicitSkillIds", List.of("test-engineering"));
        JsonNode classified = callAs(host, "classify_skills", input, owner);
        assertTrue(classified.path("ok").asBoolean(false), classified.toString());
        Map<String, Object> example = exampleOf(firstEditRefusal(host, tool, form, workspace, owner));
        assertEquals(List.of(arguments("skillId", "test-engineering", "reason", "<why this skill applies>")), example.get("skills"));
        assertEquals(key, example.get("classificationOperationKey"));
        // A classification belongs to its session: the example another session is refused with does not take it.
        Map<String, Object> other = exampleOf(firstEditRefusal(host, tool, form, product(), session()));
        assertEquals(List.of(arguments("skillId", "<skill you chose after classify_skills>", "reason", "<why this skill applies>")), other.get("skills"));
    }

    static Stream<Arguments> placeholdersOfAStart() {
        String reason = "Why it applies.";
        return Stream.of(Arguments.of("goal", "goal", "<goal>"),
                // The general placeholders (a value that says nothing of the work by its form) are refused wherever the catalog's own examples are.
                Arguments.of("goal", "goal", " todo "),
                Arguments.of("acceptanceCriteria", "acceptanceCriteria", List.of("N/A")),
                Arguments.of("writeTargets", "writeTargets", List.of("<anything at all>")),
                Arguments.of("skills", "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "<why this skill applies>"))),
                Arguments.of("skills", "skills", List.of(arguments("skillId", "minimal-implementation", "reason", reason), arguments("skillId", "<skill you chose after classify_skills>", "reason", reason))),
                Arguments.of("acceptanceCriteria", "acceptanceCriteria", List.of("<acceptance criterion>")),
                Arguments.of("writeTargets", "writeTargets", List.of("<path to change>")),
                Arguments.of("changePlan", "changePlan", List.of("<path to change>")));
    }

    @ParameterizedTest
    @MethodSource("placeholdersOfAStart")
    void aPlaceholderAnExamplePrintedIsRefusedWhereverItComesBackInAStart(String field, String key, Object placeholder) throws Exception {
        Path workspace = product();
        Map<String, Object> arguments = new LinkedHashMap<>(startAll(workspace));
        arguments.put(key, placeholder);
        JsonNode refused = call("start_workflow", arguments, session());
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("PLACEHOLDER_RETURNED", reason(refused), refused.toString());
        assertEquals(field, refused.path("error").path("details").path("field").asString(), refused.toString());
        String message = refused.path("error").path("message").asString();
        assertTrue(message.contains("the placeholder an example printed"), message);
        assertTrue(message.contains("nothing was started or planned"), message);
        assertEquals(0, rootsOf(workspace), "a refused start leaves nothing");
    }

    /** The planning-phase refusal of a write it cannot read puts a placeholder in files; a plan that sends it back lists no file of the work and is refused with what to put there. */
    @Test
    void aPlanWhoseFilesAreThePlaceholderIsRefusedAndTheRunStaysInPlanning() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", planningStart(workspace, List.of(".")), owner));
        String runId = started.get("runId").asString();
        JsonNode refused = call("submit_change_plan", arguments("runId", runId, "expectedRevision", started.get("revision").asLong(), "files", List.of("<path to change>")), owner);
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("PLACEHOLDER_RETURNED", reason(refused), refused.toString());
        assertEquals("files", refused.path("error").path("details").path("field").asString(), refused.toString());
        assertTrue(refused.path("error").path("message").asString().contains("the path, relative to the workspace, of each file this work changes"), refused.toString());
        assertEquals(Optional.of(WorkflowPhase.PLANNING), journal().phase(runId));
    }

    @Test
    void aMissingWorkspaceWithNoReportedDirectoryIsRefusedAndLeavesNothing() throws Exception {
        Path workspace = product();
        Map<String, Object> unnamed = new LinkedHashMap<>(startAll(workspace));
        unnamed.remove("workspace");
        JsonNode refused = call("start_workflow", unnamed, session());
        assertEquals("INVALID_INPUT", code(refused));
        assertEquals("workspace", refused.path("error").path("details").path("field").asString());
        assertEquals(0, rootsOf(workspace));
    }

    @Test
    void invalidStagesAndMissingSkillsAreRefusedWithoutAnyRow() throws Exception {
        Path workspace = product();
        Map<String, Object> noSkills = new LinkedHashMap<>(startAll(workspace));
        noSkills.remove("skills");
        JsonNode missing = call("start_workflow", noSkills, session());
        assertEquals("INVALID_INPUT", code(missing));
        assertEquals("SKILLS_REQUIRED", reason(missing));

        Map<String, Object> noTargets = new LinkedHashMap<>(startAll(workspace));
        noTargets.remove("writeTargets");
        JsonNode targets = call("start_workflow", noTargets, session());
        assertEquals("INVALID_INPUT", code(targets));
        assertEquals("writeTargets", targets.path("error").path("details").path("field").asString());

        Map<String, Object> unknownStage = new LinkedHashMap<>(startAll(workspace));
        unknownStage.put("stages", List.of("no-such-capability"));
        assertEquals("GATE_FAILED", code(call("start_workflow", unknownStage, session())));

        Map<String, Object> artifacts = new LinkedHashMap<>(startAll(workspace));
        artifacts.put("stages", List.of("minimal-implementation", "acceptance-evidence-validation"));
        JsonNode unfixable = call("start_workflow", artifacts, session());
        assertEquals("INVALID_INPUT", code(unfixable));
        assertEquals("workflow.service.startWorkflowFixArtifactTargets", unfixable.path("error").path("messageKey").asString());
        assertEquals(0, rootsOf(workspace));
    }

    /** A start is held to the types its tool schema states: Jackson would convert a number to the text it is written as, and the start must refuse it instead. */
    @Test
    void aStartInputOfAnotherJsonTypeThanItsToolSchemaNamesIsRefusedWithTheFieldAndOpensNothing() throws Exception {
        Path workspace = product();
        Map<String, Map<String, Object>> broken = new LinkedHashMap<>();
        broken.put("goal", new LinkedHashMap<>(startAll(workspace)));
        broken.get("goal").put("goal", 5);
        broken.put("stages", new LinkedHashMap<>(startAll(workspace)));
        broken.get("stages").put("stages", "minimal-implementation");
        broken.put("acceptanceCriteria", new LinkedHashMap<>(startAll(workspace)));
        broken.get("acceptanceCriteria").put("acceptanceCriteria", List.of(1));
        broken.put("rootId", new LinkedHashMap<>(startAll(workspace)));
        broken.get("rootId").put("rootId", 7);
        for (Map.Entry<String, Map<String, Object>> entry : broken.entrySet()) {
            JsonNode refused = call("start_workflow", entry.getValue(), session());
            assertEquals("INVALID_INPUT", code(refused), entry.getKey() + " -> " + refused);
            assertEquals(entry.getKey(), refused.path("error").path("details").path("field").asString(), refused.toString());
        }
        Map<String, Object> numberedSkill = new LinkedHashMap<>(startAll(workspace));
        numberedSkill.put("skills", List.of(arguments("skillId", 5, "reason", "test")));
        JsonNode skills = call("start_workflow", numberedSkill, session());
        assertEquals("SKILLS_REQUIRED", reason(skills), skills.toString());
        Map<String, Object> repeated = new LinkedHashMap<>(startAll(workspace));
        repeated.put("writeTargets", List.of("a", "a"));
        JsonNode duplicates = call("start_workflow", repeated, session());
        assertEquals("writeTargets", duplicates.path("error").path("details").path("field").asString(), duplicates.toString());
        assertEquals("workflow.service.distinctStringArray", duplicates.path("error").path("messageKey").asString());
        assertEquals(0, rootsOf(workspace));
    }

    @Test
    void twoWorkflowsOnDisjointPathsRunSideBySideAndAnOverlapNamesItsOwner() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode first = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        JsonNode second = ok(call("start_workflow", start(workspace, List.of("beta")), owner));
        assertNotEquals(first.get("rootId").asString(), second.get("rootId").asString());
        for (String name : List.of("alpha/1.ts", "alpha/2.ts", "beta/1.ts", "beta/2.ts")) {
            assertFalse(denied(patch(owner, workspace, name)), name);
        }

        JsonNode own = call("start_workflow", start(workspace, List.of("alpha/inner")), owner);
        assertEquals("ROOT_CONFLICT", code(own));
        assertEquals("workflow.service.startWorkflowRootConflict", own.path("error").path("messageKey").asString());
        JsonNode details = own.path("error").path("details");
        assertEquals(first.get("rootId").asString(), details.path("rootId").asString());
        assertTrue(details.path("ownedBySession").asBoolean(false));
        List<String> actions = new ArrayList<>();
        details.path("nextActions").forEach(action -> actions.add(action.path("action").asString()));
        assertEquals(List.of("restart-with-disjoint-writeTargets", "continue-running-workflow", "release-own-root"), actions);

        JsonNode foreign = call("start_workflow", start(workspace, List.of("alpha")), session());
        assertEquals("ROOT_CONFLICT", code(foreign));
        assertFalse(foreign.path("error").path("details").path("ownedBySession").asBoolean(true));
        List<String> foreignActions = new ArrayList<>();
        foreign.path("error").path("details").path("nextActions").forEach(action -> foreignActions.add(action.path("action").asString()));
        assertEquals(List.of("restart-with-disjoint-writeTargets", "release-with-user-approval"), foreignActions);
    }

    @Test
    void openConvergenceRootCarriesTheSameConflictGuidance() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(workspace), owner));
        JsonNode blocking = root(started.get("rootId").asString(), owner);
        ObjectNode task = (ObjectNode) blocking.get("taskEnvelope").deepCopy();
        task.put("taskId", "task-other-session");
        JsonNode opened = call("open_convergence_root", arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", MAPPER.convertValue(task, Map.class), "frame", MAPPER.convertValue(blocking.get("frame"), Map.class), "userApprovalRefs", List.of()), session());
        assertEquals("ROOT_CONFLICT", code(opened), opened.toString());
        assertEquals("workflow.service.activeConvergenceRootCoversWorkspace", opened.path("error").path("messageKey").asString());
        JsonNode details = opened.path("error").path("details");
        assertEquals("restart-with-disjoint-writeTargets", details.path("nextActions").get(0).path("action").asString());
        assertEquals("open_convergence_root", details.path("nextActions").get(0).path("tool").asString());
        assertFalse(details.path("ownedBySession").asBoolean(true));
    }

    @Test
    void onlyTheOwnerContinuesAnOpenRootAndOnlyWithThePriorFailure() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode first = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        ok(call("abort_workflow", arguments("runId", first.get("runId").asString(), "expectedRevision", first.get("revision").asLong()), owner));
        // The evidence of the failure is an attachment of the session that continues the root, never a path of this computer.
        String evidence = uploaded(owner).attachmentId();
        Map<String, Object> priorFailure = arguments("hypothesis", "h", "changeSummary", "c", "discriminator", "d", "evidenceRefs", List.of(evidence));
        Map<String, Object> continued = new LinkedHashMap<>(start(workspace, List.of("alpha")));
        continued.put("rootId", first.get("rootId").asString());

        assertEquals("ROOT_NOT_OWNED", reason(call("start_workflow", continued, session())));
        assertEquals("PRIOR_FAILURE_REQUIRED", reason(call("start_workflow", continued, owner)));
        Map<String, Object> otherGoal = new LinkedHashMap<>(continued);
        otherGoal.put("goal", "Something else.");
        otherGoal.put("priorFailure", priorFailure);
        assertEquals("ROOT_TASK_MISMATCH", reason(call("start_workflow", otherGoal, owner)));

        Map<String, Object> resumedArguments = new LinkedHashMap<>(continued);
        resumedArguments.put("priorFailure", priorFailure);
        resumedArguments.put("skills", List.of(arguments("skillId", "blocker-diagnostician", "reason", "the first attempt failed")));
        JsonNode resumed = ok(call("start_workflow", resumedArguments, owner));
        assertEquals(first.get("rootId").asString(), resumed.get("rootId").asString());
        assertNotEquals(first.get("runId").asString(), resumed.get("runId").asString());
    }

    @Test
    void concurrentStartsOnTheSameSurfaceOpenExactlyOneRoot() throws Exception {
        Path workspace = product();
        Map<String, Object> request = startAll(workspace);
        int callers = 6;
        try (ExecutorService pool = Executors.newFixedThreadPool(callers); CallerClients own = new CallerClients(callers)) {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String caller = session();
                McpSyncClient callerClient = own.clients.get(i);
                results.add(pool.submit(() -> call(callerClient, "start_workflow", request, codexMeta(caller))));
            }
            int started = 0;
            int conflicts = 0;
            for (Future<JsonNode> result : results) {
                JsonNode outcome = result.get();
                if (outcome.path("ok").asBoolean(false)) {
                    started++;
                } else {
                    // Either came after the winner's commit and met its root in the check of the active roots, or passed the check together with the winner and lost on the unique key of the write surface.
                    assertEquals("ROOT_CONFLICT", code(outcome), outcome.toString());
                    conflicts++;
                }
            }
            assertEquals(1, started);
            assertEquals(callers - 1, conflicts);
        }
        assertEquals(1, rootsOf(workspace));
    }

    /** The same write targets named in another order are the same surface: of the callers, whichever order they named, exactly one root opens. */
    @Test
    void concurrentStartsOfTheSameTargetsInAnotherOrderOpenExactlyOneRoot() throws Exception {
        Path workspace = product();
        List<Map<String, Object>> requests = List.of(start(workspace, List.of("alpha", "beta")), start(workspace, List.of("beta", "alpha")));
        int callers = 6;
        try (ExecutorService pool = Executors.newFixedThreadPool(callers); CallerClients own = new CallerClients(callers)) {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Map<String, Object> request = requests.get(i % requests.size());
                String caller = session();
                McpSyncClient callerClient = own.clients.get(i);
                results.add(pool.submit(() -> call(callerClient, "start_workflow", request, codexMeta(caller))));
            }
            int started = 0;
            int conflicts = 0;
            for (Future<JsonNode> result : results) {
                JsonNode outcome = result.get();
                if (outcome.path("ok").asBoolean(false)) {
                    started++;
                } else {
                    assertEquals("ROOT_CONFLICT", code(outcome), outcome.toString());
                    conflicts++;
                }
            }
            assertEquals(1, started);
            assertEquals(callers - 1, conflicts);
        }
        assertEquals(1, rootsOf(workspace));
    }

    // ---- a stage, then the end of the run

    @Test
    void aPassedStageFinalizesTheRun() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", started.get("runId").asString()), owner));
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        Map<String, Object> result = arguments("schemaVersion", "1.0.0", "runId", started.get("runId").asString(), "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", "PASSED",
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(evidenceOf(owner)), "findings", List.of(), "blockers", List.of(), "error", null);
        JsonNode recorded = ok(call("record_stage_result", result, owner));
        JsonNode finalized = ok(call("finalize_workflow", arguments("runId", started.get("runId").asString(), "expectedRevision", recorded.get("revision").asLong()), owner));
        assertEquals("PASSED", finalized.get("state").asString());
        assertEquals("STALE_REVISION", code(call("abort_workflow", arguments("runId", started.get("runId").asString(), "expectedRevision", recorded.get("revision").asLong()), owner)));
    }

    static Stream<Arguments> placeholdersOfAStageResult() {
        return Stream.of(Arguments.of("evidence[0].note", "TODO"), Arguments.of("evidence[0].note", "  x "), Arguments.of("evidence[0].note", "<note>"), Arguments.of("output.output.summary", "TODO"),
                Arguments.of("output.output.summary", "N/A"), Arguments.of("output.output.note", "..."), Arguments.of("findings[0]", "tbd"), Arguments.of("blockers[0]", "…"));
    }

    /** A free text of a stage result that is a placeholder is refused as the start tools refuse theirs, and the stage stays unrecorded: the same call with real text is then recorded. */
    @ParameterizedTest
    @MethodSource("placeholdersOfAStageResult")
    void aPlaceholderInTheFreeTextOfAStageResultIsRefusedAndNothingIsRecorded(String field, String placeholder) throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", started.get("runId").asString()), owner));
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        Map<String, Object> evidence = new LinkedHashMap<>(evidenceOf(owner));
        Map<String, Object> output = new LinkedHashMap<>(Map.of("status", "passed"));
        List<String> findings = List.of();
        List<String> blockers = List.of();
        switch (field) {
            case "evidence[0].note" -> evidence.put("note", placeholder);
            case "output.output.summary" -> output.put("summary", placeholder);
            case "output.output.note" -> output.put("note", placeholder);
            case "findings[0]" -> findings = List.of(placeholder);
            default -> blockers = List.of(placeholder);
        }
        Map<String, Object> result = arguments("schemaVersion", "1.0.0", "runId", started.get("runId").asString(), "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", "PASSED",
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", output, "artifacts", List.of(), "error", null),
                "evidence", List.of(evidence), "findings", findings, "blockers", blockers, "error", null);
        JsonNode refused = call("record_stage_result", result, owner);
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("PLACEHOLDER_RETURNED", reason(refused), refused.toString());
        assertEquals(field, refused.path("error").path("details").path("field").asString(), refused.toString());
        assertTrue(refused.path("error").path("message").asString().contains("the result was not recorded"), refused.toString());
        JsonNode after = ok(call("get_workflow_status", arguments("runId", started.get("runId").asString()), owner));
        assertEquals(receipt.get("revision").asLong(), after.get("revision").asLong(), "a refused result leaves the run as it was");
        // The same call with the free text made from the work is recorded.
        evidence.put("note", "Uploaded with the artifact tools.");
        output.remove("summary");
        output.remove("note");
        Map<String, Object> real = new LinkedHashMap<>(result);
        real.put("output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", output, "artifacts", List.of(), "error", null));
        real.put("evidence", List.of(evidence));
        real.put("findings", List.of());
        real.put("blockers", List.of());
        ok(call("record_stage_result", real, owner));
    }

    // ---- a real failure of the implementation stage is recorded as failed (AGS3-D-0084)

    /** The stage result of the implementation stage with the given state, its provider's status and its error code (null: no error). */
    private static Map<String, Object> implementationResult(String owner, String runId, JsonNode receipt, String state, String status, String errorCode) {
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        Map<String, Object> error = errorCode == null ? null : arguments("code", errorCode, "message", "The test run exited 1.", "details", null);
        return arguments("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", state,
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", status), "artifacts", List.of(), "error", error),
                "evidence", List.of(evidenceOf(owner)), "findings", List.of(), "blockers", List.of(), "error", error);
    }

    @Test
    void aFailedTestRunOfTheImplementationStageIsRecordedAsFailedNotAsBlocked() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        JsonNode recorded = ok(call("record_stage_result", implementationResult(owner, runId, receipt, "FAILED", "failed", "GATE_FAILED"), owner));
        assertEquals("FAILED", recorded.get("state").asString(), recorded.toString());
        assertEquals("FAILED", ok(call("get_workflow_status", arguments("runId", runId), owner)).get("state").asString());
    }

    @Test
    void theImplementationStagesFailedAndPassedAreHeldToTheirProvidersMapping() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        // The provider says failed, so a stage recorded as passed or blocked does not match it; an error code the mapping does not allow for failed is refused.
        assertEquals("INVALID_TRANSITION", code(call("record_stage_result", implementationResult(owner, runId, receipt, "PASSED", "failed", "GATE_FAILED"), owner)));
        assertEquals("INVALID_TRANSITION", code(call("record_stage_result", implementationResult(owner, runId, receipt, "FAILED", "failed", "MISSING_EVIDENCE"), owner)));
        assertEquals("INVALID_TRANSITION", code(call("record_stage_result", implementationResult(owner, runId, receipt, "FAILED", "passed", null), owner)));
        // None of the refusals left the run changed: the failure can still be recorded.
        assertEquals("FAILED", ok(call("record_stage_result", implementationResult(owner, runId, receipt, "FAILED", "failed", "GATE_FAILED"), owner)).get("state").asString());
    }

    @Test
    void anImplementationResultWithAMissingOrUnmappedStatusExplainsHowToRetry() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        List<Object> evidence = List.of(evidenceOf(owner));
        for (Map<String, Object> output : List.of(Map.<String, Object>of(), Map.<String, Object>of("other", "passed"),
                Map.<String, Object>of("status", "PASS"), Map.<String, Object>of("status", 1), arguments("status", null))) {
            Map<String, Object> result = arguments("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", "PASSED",
                    "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", output, "artifacts", List.of(), "error", null),
                    "evidence", evidence, "findings", List.of(), "blockers", List.of(), "error", null);
            JsonNode refused = call("record_stage_result", result, owner);
            assertEquals("INVALID_TRANSITION", code(refused), refused.toString());
            JsonNode error = refused.path("error");
            assertEquals("workflow.service.providerVerdictMappedDescriptor", error.path("messageKey").asString(), refused.toString());
            JsonNode details = error.path("details");
            assertEquals(stageId, details.path("stageId").asString(), refused.toString());
            assertEquals("/output/status", details.path("selector").asString(), refused.toString());
            assertEquals("output.output.status", details.path("inputPath").asString(), refused.toString());
            assertEquals(Set.of("passed", "failed", "blocked"), texts(details.path("allowedValues")), refused.toString());
            String message = error.path("message").asString();
            for (String hint : List.of("output.output.status", "case-sensitive", "passed", "failed", "blocked")) {
                assertTrue(message.contains(hint), message);
            }
            if (output.containsKey("status")) {
                assertFalse(details.path("valueMissing").asBoolean(true), refused.toString());
                JsonNode submitted = MAPPER.valueToTree(output.get("status"));
                assertEquals(submitted, details.get("receivedValue"), refused.toString());
                assertTrue(message.contains("Received " + submitted), message);
            } else {
                assertTrue(details.path("valueMissing").asBoolean(), refused.toString());
                assertFalse(details.has("receivedValue"), refused.toString());
                assertTrue(message.contains("No value was supplied"), message);
            }
        }
        // Nothing was recorded: the run is unchanged and a result that carries its status still passes. A passed stage does not end the run (finalize_workflow does), so the
        // run stays RUNNING and the stage is the one recorded PASSED.
        JsonNode recorded = ok(call("record_stage_result", passedStage(owner, runId, receipt), owner));
        assertEquals("RUNNING", recorded.get("state").asString(), recorded.toString());
        assertEquals("PASSED", recorded.path("plan").path("stages").path(0).path("state").asString(), recorded.toString());
    }

    @Test
    void aPassedStageWithoutEvidenceExplainsHowToSubmitACompletedAttachment() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        Map<String, Object> result = passedStage(owner, runId, receipt);
        result.put("evidence", List.of());

        JsonNode refused = call("record_stage_result", result, owner);
        assertEquals("MISSING_EVIDENCE", code(refused), refused.toString());
        JsonNode error = refused.path("error");
        assertEquals("workflow.service.passedStageRequiresEvidence", error.path("messageKey").asString(), refused.toString());
        assertEquals(result.get("stageId"), error.path("details").path("stageId").asString(), refused.toString());
        String message = error.path("message").asString();
        for (String hint : List.of("session recording the stage", "artifact_begin", "POST /attachments/upload", "artifact_finish", "evidence[]", "attachmentId", "seq", "digest")) {
            assertTrue(message.contains(hint), message);
        }
        JsonNode unchanged = ok(call("get_workflow_status", arguments("runId", runId), owner));
        assertEquals(receipt.get("revision"), unchanged.get("revision"), unchanged.toString());
        assertTrue(unchanged.path("stageResults").isEmpty(), unchanged.toString());
    }

    // ---- the model and the effort are recorded as the host reports them, never judged

    private static Map<String, Object> passedStage(String owner, String runId, JsonNode receipt) {
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        return arguments("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", "PASSED",
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(evidenceOf(owner)), "findings", List.of(), "blockers", List.of(), "error", null);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"none", "xhigh", "ultra", "turbo-9", "Ultra Max 2", "x"})
    void anEffortOfAnySpellingOrNoneIsRecordedAsTheHostReportedItAndTheRunFinishes(String effort) throws Exception {
        String owner = session();
        JsonNode started = ok(callWithEffort(effort, "start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(callWithEffort(effort, "get_workflow_status", arguments("runId", runId), owner));
        JsonNode bootstrap = receipt.path("plan").path("bootstrapExecution").path("context");
        assertEquals(effort, bootstrap.path("reasoningEffort").isNull() ? null : bootstrap.path("reasoningEffort").asString(), bootstrap.toString());
        List<String> notes = new ArrayList<>();
        bootstrap.path("policyNotes").forEach(note -> notes.add(note.asString()));
        assertEquals(effort == null, notes.contains("HOST_EFFORT_UNREPORTED"), notes.toString());
        assertFalse(notes.contains("REASONING_EFFORT_UNRECOGNIZED"), notes.toString());
        JsonNode recorded = ok(callWithEffort(effort, "record_stage_result", passedStage(owner, runId, receipt), owner));
        JsonNode stageContext = recorded.findValue("executionContext");
        assertNotNull(stageContext, recorded.toString());
        assertEquals(effort, stageContext.path("reasoningEffort").isNull() ? null : stageContext.path("reasoningEffort").asString(), stageContext.toString());
        JsonNode finalized = ok(callWithEffort(effort, "finalize_workflow", arguments("runId", runId, "expectedRevision", recorded.get("revision").asLong()), owner));
        assertEquals("PASSED", finalized.get("state").asString());
    }

    @Test
    void aHighRiskPlanAsksForNoModelOrEffortFloor() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        ObjectNode task = (ObjectNode) root(started.get("rootId").asString(), owner).get("taskEnvelope").deepCopy();
        for (String risk : List.of("low", "high", "critical")) {
            task.put("riskLevel", risk);
            JsonNode plan = ok(call("plan_workflow", MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), owner));
            List<JsonNode> requirements = new ArrayList<>(List.of(plan.path("bootstrapExecution").path("requirement")));
            plan.path("stages").forEach(stage -> requirements.add(stage.path("executionRequirement")));
            assertTrue(requirements.size() > 1, plan.toString());
            for (JsonNode requirement : requirements) {
                List<String> names = new ArrayList<>(requirement.propertyNames());
                assertEquals(new TreeSet<>(List.of("kind", "observationRequired", "policyId")), new TreeSet<>(names), risk + ": " + requirement);
            }
        }
    }

    /**
     * What a run that an earlier version stored (its plan still carries the two removed floor fields) meets in this version: the contract no longer
     * has the fields, so the plan is refused on read. The release notes tell the user; this case is what they stand on.
     */
    @Test
    void aRunStoredWithTheRemovedFloorFieldsIsRefusedOnReadWithAContractMismatch() throws Exception {
        String owner = session();
        String runId = ok(call("start_workflow", startAll(product()), owner)).get("runId").asString();
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        transactions.execute(status -> {
            String stored = jdbc.sql("SELECT receipt_json FROM sennard.workflow_runs WHERE run_id = ?").param(runId).query(String.class).single();
            ObjectNode receipt = (ObjectNode) MAPPER.readTree(stored);
            ((ObjectNode) receipt.path("plan").path("bootstrapExecution").path("requirement")).putNull("minimumModelClass").putNull("minimumReasoningEffort");
            return jdbc.sql("UPDATE sennard.workflow_runs SET receipt_json = ? WHERE run_id = ?").param(receipt.toString()).param(runId).update();
        });
        JsonNode refused = call("get_workflow_status", arguments("runId", runId), owner);
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertTrue(refused.path("error").path("details").toString().contains("minimumModelClass"), refused.toString());
    }

    /** The host's call as it reports it when it gives no model for the call (Codex: no {@code model} in the turn metadata); a child's when {@code child}. */
    private static Map<String, Object> metaWithoutModel(String session, boolean child) {
        Map<String, Object> meta = codexMeta(session);
        Map<String, Object> turn = new HashMap<>(MAPPER.convertValue(meta.get("x-codex-turn-metadata"), new TypeReference<Map<String, Object>>() { }));
        turn.remove("model");
        if (child) {
            String thread = UUID.randomUUID().toString();
            meta.put("threadId", thread);
            turn.put("thread_id", thread);
            turn.put("parent_thread_id", session);
        }
        meta.put("x-codex-turn-metadata", turn);
        return meta;
    }

    /**
     * AGS3-D-0168: a subagent whose model the host did not report for the call has a passing stage result refused BINDING_REQUIRED, and the refusal tells it to hand the result
     * to the agent that started it. That agent, in its own observed session, records the same result, and the run goes on to its end. The result of the subagent changed nothing.
     */
    @Test
    void aSubagentWhoseModelWasNotObservedHandsItsResultToTheParentWhichRecordsIt() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        Map<String, Object> result = passedStage(owner, runId, receipt);

        JsonNode refused = call("record_stage_result", result, metaWithoutModel(owner, true));
        assertEquals("BINDING_REQUIRED", code(refused), refused.toString());
        assertTrue(refused.toString().contains("CODEX_EXECUTION_META_MISSING"), "the refusal names why the call was not observed: " + refused);
        assertTrue(refused.path("error").path("message").asString().contains("agent that started you"), "the refusal says who records it: " + refused);
        assertEquals(receipt.get("revision").asLong(), ok(call("get_workflow_status", arguments("runId", runId), owner)).get("revision").asLong(), "the refused result changed nothing");

        JsonNode recorded = ok(call("record_stage_result", result, owner));
        assertTrue(recorded.get("revision").asLong() > receipt.get("revision").asLong(), recorded.toString());
        JsonNode finalized = ok(call("finalize_workflow", arguments("runId", runId, "expectedRevision", recorded.get("revision").asLong()), owner));
        assertEquals("PASSED", finalized.get("state").asString());
    }

    /**
     * The same refusal for the root session: a passing semantic stage needs the host's observation of the model whoever records it, so the tool description does not promise a
     * root session a result without one. The root has no one above it, so its refusal is the attestation one.
     */
    @Test
    void aRootWhoseModelWasNotObservedIsRefusedAPassingStageToo() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));

        JsonNode refused = call("record_stage_result", passedStage(owner, runId, receipt), metaWithoutModel(owner, false));
        assertEquals("BINDING_REQUIRED", code(refused), refused.toString());
        assertFalse(refused.path("error").path("message").asString().contains("agent that started you"), refused.toString());
        assertEquals(receipt.get("revision").asLong(), ok(call("get_workflow_status", arguments("runId", runId), owner)).get("revision").asLong());
    }

    private static JsonNode runToFinalize(String owner, String responseMode) throws Exception {
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        String stageId = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
        JsonNode recorded = ok(call("record_stage_result", arguments("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", receipt.get("revision").asLong(), "stageId", stageId, "state", "PASSED",
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(evidenceOf(owner)), "findings", List.of(), "blockers", List.of(), "error", null), owner));
        return ok(call("finalize_workflow", arguments("runId", runId, "expectedRevision", recorded.get("revision").asLong(), "responseMode", responseMode), owner));
    }

    /**
     * AGS3-D-0139: a subagent that starts in a session with a running workflow is given the workflow card and where the installed intake is, which the host may
     * hand it only after a tool search (the intake's own text is the MCP instructions'; it is not repeated in the start).
     */
    @Test
    void aSubagentStartInASessionWithAWorkflowGetsTheCardAndThePointerToTheIntake() throws Exception {
        String owner = session();
        ok(call("start_workflow", startAll(product()), owner));
        var response = DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStart", null, owner,
                body -> body.put("hook_event_name", "SubagentStart").put("agent_id", "child-d0139").put("agent_type", "general-purpose"));
        assertEquals(200, response.status());
        String context = MAPPER.readTree(response.body()).path("hookSpecificOutput").path("additionalContext").asString();
        assertTrue(context.contains("[Sennard subagent start check]"), context);
        assertTrue(context.contains("Sennard intake ("), "the pointer to the installed intake: " + context);
        assertTrue(context.contains("[Task continuity workflow card"), "the card of the session's workflow: " + context);
        assertTrue(context.contains("rootId"), context);

        var bare = DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStart", null, session(),
                body -> body.put("hook_event_name", "SubagentStart").put("agent_id", "child-none").put("agent_type", "general-purpose"));
        String bareContext = MAPPER.readTree(bare.body()).path("hookSpecificOutput").path("additionalContext").asString();
        assertTrue(bareContext.contains("[Sennard subagent start check]"), bareContext);
        assertFalse(bareContext.contains("[Task continuity workflow card"), "a session with no workflow has no card to give: " + bareContext);
    }

    /** The hook the session's own shell read of the skill file produced; the only source of what {@code finalize_workflow} shows. */
    private static void hookSawTheSkillRead(String session) throws Exception {
        ObjectNode input = MAPPER.createObjectNode().put("command", "cat " + server.context.getEnvironment().getRequiredProperty("sennard.skills-dir") + "/minimal-implementation/SKILL.md");
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "PostToolUse", null, session, DomainServerSupport.tool("Bash", input)).status());
    }

    @Test
    void aDeclaredSkillNoHookSawIsShownAtFinishAndDoesNotBlockIt() throws Exception {
        JsonNode full = runToFinalize(session(), "full");
        assertEquals("PASSED", full.get("state").asString());
        assertEquals(List.of("minimal-implementation"), full.path("skillDeclaration").path("callsNotObserved").valueStream().map(JsonNode::asString).toList());
        JsonNode compact = runToFinalize(session(), "compact");
        assertEquals("[\"minimal-implementation\"]", compact.path("declaredSkillsNotObserved").toString(), compact.toString());
    }

    @Test
    void aDeclaredSkillAHookSawIsNotShown() throws Exception {
        String owner = session();
        hookSawTheSkillRead(owner);
        assertEquals("[]", runToFinalize(owner, "full").path("skillDeclaration").path("callsNotObserved").toString());
        String compacted = session();
        hookSawTheSkillRead(compacted);
        assertFalse(runToFinalize(compacted, "compact").has("declaredSkillsNotObserved"), "nothing to show");
    }

    @Test
    void aStaleRevisionIsRefusedAndAbortBlocksTheRun() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        assertEquals("STALE_REVISION", code(call("finalize_workflow", arguments("runId", runId, "expectedRevision", started.get("revision").asLong() + 7), owner)));
        JsonNode aborted = ok(call("abort_workflow", arguments("runId", runId, "expectedRevision", started.get("revision").asLong(), "responseMode", "compact"), owner));
        assertEquals(runId, aborted.get("runId").asString());
        assertEquals("INVALID_TRANSITION", code(call("abort_workflow", arguments("runId", runId, "expectedRevision", aborted.get("revision").asLong()), owner)));
    }

    // ---- releasing a root and the user's decision

    @Test
    void theOwnerReleasesItsOwnRootTheRunIsBlockedAndTheScopeIsFree() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String rootId = started.get("rootId").asString();
        JsonNode released = ok(call("release_convergence_root", releaseArguments(rootId, owner), owner));
        assertEquals("ABANDONED", released.get("state").asString());
        assertEquals("BLOCKED", released.path("closedRun").path("state").asString());
        assertTrue(released.path("release").path("releasedByOwner").asBoolean(false));
        assertEquals("BLOCKED", ok(call("get_workflow_status", arguments("runId", started.get("runId").asString()), owner)).get("state").asString());

        assertTrue(call("start_workflow", start(workspace, List.of("alpha")), session()).path("ok").asBoolean(false));
        JsonNode repeated = call("release_convergence_root", arguments("rootId", rootId, "expectedRevision", root(rootId, owner).get("revision").asLong(), "reason", "again"), owner);
        assertEquals("INVALID_TRANSITION", code(repeated));
        assertEquals("ROOT_NOT_ACTIVE", reason(repeated));
    }

    /** A release is held to the rules its tool schema states; the check runs before any root is read, so a made-up root ID is enough. */
    @Test
    void aReleaseThatBreaksTheRulesOfItsToolSchemaIsInvalidInputWithItsOwnReason() {
        String owner = session();
        List<Map<String, Object>> broken = List.of(
                arguments("rootId", "root-x", "expectedRevision", 1.5, "reason", "r"),
                arguments("rootId", "root-x", "expectedRevision", -1, "reason", "r"),
                arguments("rootId", "", "expectedRevision", 1, "reason", "r"),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", "   "),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", "x".repeat(2001)),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", "r", "userApprovalRefs", List.of("a", "a")),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", "r", "userApprovalRefs", List.of("")),
                // A value of another JSON type than the schema names is refused, not converted: "1" is not the revision 1, and 5 is not the text "5".
                arguments("rootId", "root-x", "expectedRevision", "1", "reason", "r"),
                arguments("rootId", "root-x", "expectedRevision", 1.0, "reason", "r"),
                arguments("rootId", 5, "expectedRevision", 1, "reason", "r"),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", 7),
                arguments("rootId", "root-x", "expectedRevision", 1, "reason", "r", "userApprovalRefs", List.of(1)));
        for (Map<String, Object> arguments : broken) {
            JsonNode refused = call("release_convergence_root", arguments, owner);
            assertEquals("INVALID_INPUT", code(refused), arguments.toString() + " -> " + refused);
            assertEquals("RELEASE_INPUT_INVALID", reason(refused), arguments.toString() + " -> " + refused);
        }
        JsonNode unknownRoot = call("release_convergence_root", arguments("rootId", "root-x", "expectedRevision", 1, "reason", "r"), owner);
        assertEquals("RUN_NOT_FOUND", code(unknownRoot), "a release that keeps to the rules goes on to look for the root: " + unknownRoot);
    }

    @Test
    void anotherSessionMayReleaseOnlyWithTheUsersDecisionAndTheModelCannotMakeOne() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        JsonNode started = ok(call("start_workflow", startAll(workspace), owner));
        String rootId = started.get("rootId").asString();

        Map<String, Object> asked = releaseArguments(rootId, other);
        JsonNode required = call("release_convergence_root", asked, other);
        assertEquals("INVALID_INPUT", code(required));
        assertEquals("USER_APPROVAL_REQUIRED", reason(required));
        JsonNode details = required.path("error").path("details");
        assertTrue(details.path("adminPage").asString("").startsWith("http"), details.toString());
        String scopeDigest = details.path("scopeDigest").asString();
        assertTrue(required.path("error").path("message").asString().contains(details.path("adminPage").asString()));
        assertEquals("workflow.service.rootApprovalAdminPageRequired", required.path("error").path("messageKey").asString());

        // Text the model wrote is never a decision, and the pending row is not one either.
        Map<String, Object> typed = new LinkedHashMap<>(asked);
        typed.put("userApprovalRefs", List.of("user-message: release the stale alpha root"));
        JsonNode unbound = call("release_convergence_root", typed, other);
        assertEquals("BINDING_INVALID", code(unbound));
        assertEquals("USER_APPROVAL_REFERENCE_UNBOUND", reason(unbound));
        assertEquals("OPEN", root(rootId, owner).get("state").asString());
        List<RootApprovalService.Pending> pending = approvals().pending();
        assertTrue(pending.stream().anyMatch(item -> item.scopeDigest().equals(scopeDigest) && item.action() == RootAction.RELEASE_ROOT && item.rootId().equals(rootId)), pending.toString());

        String approvalId = approveOnAdminPage(scopeDigest);
        List<RootApprovalService.Pending> after = approvals().pending();
        assertTrue(after.stream().noneMatch(item -> item.scopeDigest().equals(scopeDigest)));

        // The same call now finds the decision without naming it.
        JsonNode released = ok(call("release_convergence_root", asked, other));
        assertEquals(List.of(approvalId), MAPPER.convertValue(released.path("release").path("userApprovalRefs"), new TypeReference<List<String>>() { }));
        assertFalse(released.path("release").path("releasedByOwner").asBoolean(true));
        assertEquals("unknown", released.path("release").path("ownerPresence").asString());
    }

    /**
     * AGS3-D-0163: a pointer into a file or a message of the user is not a decision; the refusal says what is (the user on the admin page, then the same call with the
     * references left empty) in place of a bare "cannot confirm", and the tool's own words say the same.
     */
    @Test
    void aReferenceThatIsNotTheUsersDecisionIsRefusedWithWhatToDoInstead() throws Exception {
        String owner = session();
        String other = session();
        String rootId = ok(call("start_workflow", startAll(product()), owner)).get("rootId").asString();
        Map<String, Object> pointer = new LinkedHashMap<>(releaseArguments(rootId, other));
        pointer.put("userApprovalRefs", List.of("/taskEnvelope.json#/approval/userMessage"));
        JsonNode unbound = call("release_convergence_root", pointer, other);
        assertEquals("BINDING_INVALID", code(unbound));
        assertEquals("USER_APPROVAL_REFERENCE_UNBOUND", reason(unbound));
        assertEquals("workflow.service.rootApprovalReferenceUnbound", unbound.path("error").path("messageKey").asString(), unbound.toString());
        String sentence = unbound.path("error").path("message").asString();
        assertTrue(sentence.contains("admin page") && sentence.contains("empty"), sentence);
        assertEquals("OPEN", root(rootId, owner).get("state").asString());
    }

    @Test
    void aDecisionIsReusedOnlyForTheSameActionRootActorAndScope() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String thirdSession = session();
        JsonNode firstRun = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        JsonNode secondRun = ok(call("start_workflow", start(workspace, List.of("beta")), owner));
        String firstRoot = firstRun.get("rootId").asString();
        String secondRoot = secondRun.get("rootId").asString();

        JsonNode required = call("release_convergence_root", releaseArguments(firstRoot, other), other);
        String digest = required.path("error").path("details").path("scopeDigest").asString();
        String approvalId = approveOnAdminPage(digest);

        // Another actor, and another root, each need their own decision.
        assertEquals("USER_APPROVAL_REQUIRED", reason(call("release_convergence_root", releaseArguments(firstRoot, thirdSession), thirdSession)));
        Map<String, Object> wrongRoot = new LinkedHashMap<>(releaseArguments(secondRoot, other));
        wrongRoot.put("userApprovalRefs", List.of(approvalId));
        JsonNode unbound = call("release_convergence_root", wrongRoot, other);
        assertEquals("USER_APPROVAL_REFERENCE_UNBOUND", reason(unbound));
        assertEquals("OPEN", root(secondRoot, other).get("state").asString());

        // A declined request stays undecided.
        JsonNode secondRequired = call("release_convergence_root", releaseArguments(secondRoot, other), other);
        String secondDigest = secondRequired.path("error").path("details").path("scopeDigest").asString();
        assertTrue(approvals().reject(pageId(secondDigest)));
        assertEquals("USER_APPROVAL_REQUIRED", reason(call("release_convergence_root", releaseArguments(secondRoot, other), other)));
        assertEquals("OPEN", root(secondRoot, other).get("state").asString());

        assertEquals("ABANDONED", ok(call("release_convergence_root", releaseArguments(firstRoot, other), other)).get("state").asString());
    }

    @Test
    void anOnlineOwnerKeepsItsRootEvenAfterAnApproval() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        JsonNode started = ok(call("start_workflow", startAll(workspace), owner));
        String rootId = started.get("rootId").asString();
        String digest = call("release_convergence_root", releaseArguments(rootId, other), other).path("error").path("details").path("scopeDigest").asString();
        approveOnAdminPage(digest);

        OnlineOwnerStandIn.onlineOwner = owner;
        try {
            JsonNode refused = call("release_convergence_root", releaseArguments(rootId, other), other);
            assertEquals("INVALID_TRANSITION", code(refused));
            assertEquals("OWNER_SESSION_ONLINE", reason(refused));
            assertEquals("online", refused.path("error").path("details").path("ownerPresence").path("state").asString());
            assertEquals("OPEN", root(rootId, owner).get("state").asString());

            JsonNode conflict = call("start_workflow", startAll(workspace), other);
            JsonNode details = conflict.path("error").path("details");
            assertEquals("online", details.path("ownerPresence").path("state").asString(), conflict.toString());
            List<String> actions = new ArrayList<>();
            details.path("nextActions").forEach(action -> actions.add(action.path("action").asString()));
            assertTrue(actions.contains("ask-owner-session"), actions.toString());
            assertFalse(actions.contains("release-with-user-approval"), actions.toString());
        } finally {
            OnlineOwnerStandIn.onlineOwner = null;
        }
    }

    /**
     * The personal server no longer reads which sessions are online from their host processes (that needed the OS connection table), so its presence reader
     * answers "unknown" for a root's owner. A stand-in for a reader that answers online keeps the refusal that answer causes under test.
     */
    @Configuration(proxyBeanMethods = false)
    static class OnlineOwnerStandIn {
        static volatile String onlineOwner;

        @Bean
        static BeanPostProcessor onlineOwnerStandInProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RootOwnerPresence real)) {
                        return bean;
                    }
                    return (RootOwnerPresence) (rootId, call) -> {
                        String owner = onlineOwner;
                        if (owner == null) {
                            return real.read(rootId, call);
                        }
                        return new RootOwnerPresence.View(PresenceState.ONLINE, false, true,
                                List.of(new RootOwnerPresence.OwnerSession("codex", owner, "codex:" + owner, PresenceState.ONLINE, null, null)), null, java.time.Instant.now().toString());
                    };
                }
            };
        }
    }

    /** The root's task with a changed objective: a claim with it changes the frame. */
    private static JsonNode claimChanged(String rootId, String session, String owner) {
        JsonNode current = root(rootId, owner);
        ObjectNode task = (ObjectNode) current.get("taskEnvelope").deepCopy();
        task.put("objective", task.get("objective").asString() + " Also more.");
        JsonNode planned = ok(call("plan_workflow", MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), session));
        return call("claim_workflow_attempt", arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "taskEnvelope", MAPPER.convertValue(task, Map.class),
                "frame", MAPPER.convertValue(current.get("frame"), Map.class), "plan", MAPPER.convertValue(planned, Map.class), "outputTargets", List.of("alpha"), "priorFailure", null), session);
    }

    /** A root waiting for its review is not closed by the abort of the run that is still live on it: an abort ends the attempt, and the review is the root's own decision. */
    @Test
    void anAbortOfTheRunOfARootInReviewEndsTheAttemptAndLeavesTheRootInReview() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String rootId = started.get("rootId").asString();
        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner, owner)));
        assertEquals("NEEDS_REVIEW", root(rootId, owner).get("state").asString());

        JsonNode aborted = ok(call("abort_workflow", arguments("runId", started.get("runId").asString(), "expectedRevision", started.get("revision").asLong()), owner));

        assertEquals("BLOCKED", aborted.get("state").asString());
        JsonNode after = root(rootId, owner);
        assertEquals("NEEDS_REVIEW", after.get("state").asString(), "the root still waits for its review: " + after);
        assertTrue(after.path("release").isNull() || after.path("release").isMissingNode(), after.toString());
    }

    /**
     * A claim that leaves out the task and the frame (the root's own are used) is judged by the schema on what the caller wrote, with those two put in: a schemaVersion that was never
     * sent is named as a missing member, not refused for a value ("must be 1.0.0") it never had; and the validator's sentences are English whatever the language of the machine.
     */
    @Test
    void aClaimWithoutTheTaskAndTheFrameNamesAMissingSchemaVersionAsMissing() throws Exception {
        Path workspace = product();
        String owner = session();
        String rootId = ok(call("start_workflow", start(workspace, List.of("alpha")), owner)).get("rootId").asString();
        JsonNode current = root(rootId, owner);
        JsonNode planned = ok(call("plan_workflow", MAPPER.convertValue(current.get("taskEnvelope"), new TypeReference<Map<String, Object>>() { }), owner));
        Map<String, Object> claim = arguments("rootId", rootId, "expectedRevision", current.get("revision").asLong(), "plan", MAPPER.convertValue(planned, Map.class), "outputTargets", List.of("alpha"),
                "priorFailure", null);
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.KOREAN);
            JsonNode refused = call("claim_workflow_attempt", claim, owner);
            assertEquals("INVALID_INPUT", code(refused), refused.toString());
            String errors = refused.path("error").path("details").path("validationErrors").asString();
            assertTrue(errors.contains("schemaVersion") && errors.contains("required"), "the member is named as missing: " + errors);
            assertFalse(errors.contains("1.0.0"), "not the refusal of a constant the caller never sent: " + errors);
            assertFalse(errors.chars().anyMatch(letter -> letter >= 0xAC00 && letter <= 0xD7A3), "English, not the language of the machine: " + errors);
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void anotherSessionsFrameChangeAndReplacementAreRefusedAndTheOwnersGoThroughTheDecision() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String rootId = started.get("rootId").asString();

        JsonNode foreign = claimChanged(rootId, other, owner);
        assertEquals("INVALID_INPUT", code(foreign), foreign.toString());
        assertEquals("ROOT_NOT_OWNED", reason(foreign));
        assertEquals("OPEN", root(rootId, owner).get("state").asString());

        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner, owner)));
        JsonNode parent = root(rootId, owner);
        assertEquals("NEEDS_REVIEW", parent.get("state").asString());

        ObjectNode replacement = (ObjectNode) parent.get("taskEnvelope").deepCopy();
        replacement.put("taskId", "task-replacement");
        Map<String, Object> replace = arguments("schemaVersion", "1.0.0", "parentRootId", rootId, "taskEnvelope", MAPPER.convertValue(replacement, Map.class), "frame", MAPPER.convertValue(parent.get("frame"), Map.class), "userApprovalRefs", List.of());
        assertEquals("ROOT_NOT_OWNED", reason(call("open_convergence_root", replace, other)));

        JsonNode required = call("open_convergence_root", replace, owner);
        assertEquals("BINDING_INVALID", code(required), required.toString());
        assertEquals("USER_APPROVAL_REQUIRED", reason(required));
        String approvalId = approveOnAdminPage(required.path("error").path("details").path("scopeDigest").asString());
        JsonNode opened = ok(call("open_convergence_root", replace, owner));
        assertEquals(approvalId, opened.path("userApprovalRefs").get(0).asString());
        assertEquals(rootId, opened.path("parentRootId").asString());
    }

    @Test
    void aGateReviewMustTargetTheCurrentRevisionAndIsRecordedWhenItDoes() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String rootId = started.get("rootId").asString();
        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner, owner)));
        JsonNode status = ok(call("get_convergence_status", arguments("rootId", rootId), owner));
        JsonNode current = status.get("root");
        Map<String, Object> review = arguments("schemaVersion", "1.0.0", "reviewId", "review-" + UUID.randomUUID(), "rootId", rootId, "rootRevision", current.get("revision").asLong(), "epoch", status.get("currentEpoch").asLong(),
                "reviewerActorId", "fresh-frame-auditor", "freshContext", arguments("confirmed", true, "evidenceRef", "review-evidence:1"), "classification", "semantics-changing",
                "comparability", arguments("comparable", false, "rationale", "The evaluation meaning is changed or unresolved."), "route", "needs-user", "proposedFrame", MAPPER.convertValue(current.get("frame"), Map.class),
                "evidenceRefs", List.of("review-evidence:1"), "userApprovalRefs", List.of(), "reviewedAt", java.time.Instant.now().toString());
        Map<String, Object> stale = arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong() + 1, "review", review);
        assertEquals("STALE_REVISION", code(call("resolve_convergence_gate", stale, owner)));
        JsonNode resolved = ok(call("resolve_convergence_gate", arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "review", review), owner));
        assertEquals("NEEDS_USER", resolved.path("root").path("state").asString());
    }

    // ---- the bundle review stages are judged from attachments (AGS3-D-0264)
    // The checks of test-sensitivity-review and change-code-review read a bundle of files. The server never reads the caller's computer, so the bundle is the attachments the recording
    // session uploaded: the manifest and the report are artifacts of the result, every raw file is an evidence item, and the file name an attachment was uploaded under is its path under
    // the bundle's folder. The server puts them in a folder of its own, runs the skill's real check there, and removes the folder whether the check accepts or refuses. The tests build the
    // files as a caller would (the skills' own checks, through run_skill_check), upload each one through the artifact tools, and record the result through the real server.
    // The CS review (cs-implementation-review) has no end-to-end case here: its plan needs the bootstrap stage's constraint report as an input and the frozen task of the cs-engineering
    // example, and no helper builds that plan; StageBundleChecksTest and SkillBackedStageGatesTest cover its verdict logic.

    private static String artifactSchemaOf(String resultId) {
        return "https://skill-suite.local/contracts/" + resultId + ".v1.schema.json";
    }

    /** The folders of the server's own that a bundle check makes in the OS temporary directory ({@code sennard-bundle-*}), by name. */
    private static Set<String> bundleFolders() throws IOException {
        Set<String> names = new TreeSet<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "sennard-bundle-*")) {
            folders.forEach(folder -> names.add(folder.getFileName().toString()));
        }
        return names;
    }

    /** No folder made after {@code before} was taken is still there: the server removes the folder of a check when it has answered, accepted or refused. */
    private static void assertNoBundleFolderLeft(Set<String> before) throws IOException {
        Set<String> left = bundleFolders();
        left.removeAll(before);
        assertTrue(left.isEmpty(), "bundle folders that appeared during the test and were not removed: " + left);
    }

    /** What a skill's check answers (its {@code value}) for the bundle builder, asked through the real {@code run_skill_check} tool as this session. */
    private static JsonNode skillValue(String session, String skill, String command, Object... pairs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            inputs.put((String) pairs[i], value instanceof JsonNode node ? MAPPER.convertValue(node, Object.class) : value);
        }
        JsonNode answer = call("run_skill_check", arguments("skill", skill, "command", command, "arguments", inputs), session).path("data");
        assertTrue(answer.has("value"), "the check of " + skill + " " + command + " answered a verdict: " + answer);
        return answer.get("value");
    }

    /** The task and the frame of a root this session made and released: what a run of a bundle review stage is planned from (a start_workflow has no way to give a stage its artifact target). */
    private record RootBase(Map<String, Object> task, Map<String, Object> frame) {
    }

    private static RootBase releasedRootOf(String owner, Path workspace) throws Exception {
        JsonNode first = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String firstRootId = first.get("rootId").asString();
        JsonNode current = root(firstRootId, owner);
        ok(call("release_convergence_root", arguments("rootId", firstRootId, "expectedRevision", current.get("revision").asLong(), "reason", "The test opens its own root."), owner));
        return new RootBase(new LinkedHashMap<>(MAPPER.convertValue(current.get("taskEnvelope"), new TypeReference<Map<String, Object>>() { })),
                MAPPER.convertValue(current.get("frame"), new TypeReference<Map<String, Object>>() { }));
    }

    /** The run of one bundle review stage, and the bundle its files make (not yet uploaded). {@code receipt} is the run as it was before anything was recorded. */
    private record BundleRun(String runId, JsonNode receipt, EngineeringBundleFiles.Bundle bundle) {
    }

    /**
     * A run of one bundle review stage on a root of its own that this session opened, claimed and started: planned with plan_workflow from a task that declares the artifact the stage
     * produces, bound to the candidate the bundle's files make. The task the bundle's {@code task.json} holds is that same task, so its digest is the plan's task digest.
     */
    private static BundleRun bundleRunOf(String owner, String capability, boolean incompleteReview) throws Exception {
        RootBase base = releasedRootOf(owner, product());
        EngineeringBundleFiles.Pending pending = EngineeringBundleFiles.begin((skill, command, pairs) -> skillValue(owner, skill, command, pairs), product(), capability);
        Map<String, Object> task = new LinkedHashMap<>(base.task());
        task.put("taskId", "task-" + UUID.randomUUID());
        task.put("requiredCapabilities", List.of(capability));
        task.put("artifactContracts", List.of(arguments("capability", capability, "produced",
                List.of(arguments("artifactId", pending.reportArtifactId(), "schemaId", artifactSchemaOf(pending.reportArtifactId()), "targetDigest", pending.target())))));
        ObjectNode taskDocument = (ObjectNode) MAPPER.valueToTree(task);
        EngineeringBundleFiles.Bundle bundle = incompleteReview ? pending.sealIncompleteReview(taskDocument) : pending.seal(taskDocument);
        JsonNode opened = ok(call("open_convergence_root", arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", task, "frame", base.frame(), "userApprovalRefs", List.of()), owner));
        JsonNode planned = ok(call("plan_workflow", task, owner));
        assertEquals(TestJson.hashJson(taskDocument), planned.path("taskDigest").asString(), "the task the bundle holds is the task the plan was made from: " + planned);
        JsonNode lease = ok(call("claim_workflow_attempt", arguments("schemaVersion", "1.0.0", "rootId", opened.get("rootId").asString(), "expectedRevision", opened.get("revision").asLong(), "taskEnvelope", task,
                "frame", base.frame(), "plan", MAPPER.convertValue(planned, new TypeReference<Map<String, Object>>() { }), "outputTargets", List.of("alpha"), "priorFailure", null), owner));
        String skill = EngineeringBundleFiles.TEST_STAGE.equals(capability) ? "test-engineering" : "code-review";
        JsonNode run = ok(call("start_guarded_workflow", arguments("schemaVersion", "1.0.0", "leaseId", lease.get("leaseId").asString(), "expectedRootRevision", lease.get("rootRevision").asLong(),
                "skills", List.of(arguments("skillId", skill, "reason", "test"))), owner));
        String runId = run.get("runId").asString();
        return new BundleRun(runId, ok(call("get_workflow_status", arguments("runId", runId), owner)), bundle);
    }

    /** One file as an attachment of this session, uploaded under {@code fileName} (the attachment's file name is the file's path under the bundle's folder). */
    private static ToolServerSupport.Uploaded uploadFile(String session, String fileName, byte[] bytes) {
        String mediaType = fileName.endsWith(".json") ? "application/json" : "text/plain";
        return ToolServerSupport.upload(server.port, (tool, input, caller) -> call(tool, input, caller), session, bytes, mediaType, fileName);
    }

    /** Every file of the bundle as an attachment of this session, by the path of the file. */
    private static Map<String, ToolServerSupport.Uploaded> uploadBundle(String session, EngineeringBundleFiles.Bundle bundle) {
        Map<String, ToolServerSupport.Uploaded> uploaded = new LinkedHashMap<>();
        bundle.files().forEach((path, bytes) -> uploaded.put(path, uploadFile(session, path, bytes)));
        return uploaded;
    }

    /** An evidence item for a raw file of the bundle: the attachment and the digest the caller declares for it. */
    private static Map<String, Object> rawFileEvidence(String path, ToolServerSupport.Uploaded file) {
        Map<String, Object> item = ToolServerSupport.observedEvidence(file);
        item.put("artifactId", path);
        item.put("kind", "file");
        item.put("note", "A raw file of the bundle, uploaded as " + path);
        return item;
    }

    /** The raw files of the bundle as evidence: every file but the manifest and the report, which the result names as its artifacts. */
    private static List<Map<String, Object>> rawFilesOf(EngineeringBundleFiles.Bundle bundle, Map<String, ToolServerSupport.Uploaded> uploaded) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        uploaded.forEach((path, file) -> {
            if (!path.equals(bundle.manifestPath()) && !path.equals(bundle.reportPath())) {
                evidence.add(rawFileEvidence(path, file));
            }
        });
        return evidence;
    }

    /**
     * The result of the run's one stage: the skill's result as the output, the manifest and the report as artifacts, the raw files as evidence. {@code error} is null for a passing result;
     * a result that does not pass carries the error its mapping asks for, in the output and in the stage.
     */
    private static Map<String, Object> bundleStageResult(BundleRun run, ToolServerSupport.Uploaded manifest, ToolServerSupport.Uploaded report, List<Map<String, Object>> evidence, String state,
            Map<String, Object> error) {
        EngineeringBundleFiles.Bundle bundle = run.bundle();
        String stageId = run.receipt().path("plan").path("currentStageId").asString(run.receipt().path("plan").path("nextStageId").asString());
        Map<String, Object> manifestArtifact = arguments("artifactId", EngineeringBundleFiles.BUNDLE_ARTIFACT, "schemaId", artifactSchemaOf(EngineeringBundleFiles.BUNDLE_ARTIFACT),
                "attachmentId", manifest.attachmentId(), "digest", manifest.sha256(), "targetDigest", bundle.target(), "verified", true);
        Map<String, Object> reportArtifact = arguments("artifactId", bundle.reportArtifactId(), "schemaId", artifactSchemaOf(bundle.reportArtifactId()), "attachmentId", report.attachmentId(),
                "digest", report.sha256(), "targetDigest", bundle.target(), "verified", true);
        return arguments("schemaVersion", "1.0.0", "runId", run.runId(), "expectedRevision", run.receipt().get("revision").asLong(), "stageId", stageId, "state", state,
                "output", arguments("schemaVersion", "1.0.0", "kind", "output", "output", MAPPER.convertValue(bundle.result(), new TypeReference<Map<String, Object>>() { }),
                        "artifacts", List.of(manifestArtifact, reportArtifact), "error", error),
                "evidence", evidence, "findings", List.of(), "blockers", error == null ? List.of() : List.of("The review is incomplete."), "error", error);
    }

    private static Map<String, Object> passingBundleResult(BundleRun run, Map<String, ToolServerSupport.Uploaded> uploaded) {
        EngineeringBundleFiles.Bundle bundle = run.bundle();
        return bundleStageResult(run, uploaded.get(bundle.manifestPath()), uploaded.get(bundle.reportPath()), rawFilesOf(bundle, uploaded), "PASSED", null);
    }

    private static void assertRunUnchanged(String owner, BundleRun run) {
        JsonNode unchanged = ok(call("get_workflow_status", arguments("runId", run.runId()), owner));
        assertEquals(run.receipt().get("revision").asLong(), unchanged.get("revision").asLong(), "the refused result changed nothing: " + unchanged);
        assertEquals(0, unchanged.path("stageResults").size(), unchanged.toString());
    }

    private static void assertRefusedWith(JsonNode refused, String expectedCode, String messageKey) {
        assertEquals(expectedCode, code(refused), refused.toString());
        assertEquals(messageKey, refused.path("error").path("messageKey").asString(), refused.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundleFiles.TEST_STAGE, EngineeringBundleFiles.CODE_STAGE})
    void aPassingBundleReviewOfUploadedFilesIsRecordedFinalizedAndLeavesNoFolderBehind(String capability) throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        BundleRun run = bundleRunOf(owner, capability, false);
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, run.bundle());

        JsonNode recorded = ok(call("record_stage_result", passingBundleResult(run, uploaded), owner));

        assertEquals("PASSED", recorded.path("plan").path("stages").path(0).path("state").asString(), recorded.toString());
        JsonNode checks = recorded.path("stageResults").get(0).path("artifactChecks");
        assertEquals(uploaded.size(), checks.size(), "the manifest, the report and every raw file are checked against their attachment: " + checks);
        checks.forEach(check -> assertEquals("SHA256_MATCHED", check.path("check").asString(), checks.toString()));
        // The finish judges the bundle again, over the same attachments.
        JsonNode finalized = ok(call("finalize_workflow", arguments("runId", run.runId(), "expectedRevision", recorded.get("revision").asLong()), owner));
        assertEquals("PASSED", finalized.get("state").asString(), finalized.toString());
        assertNoBundleFolderLeft(foldersBefore);
    }

    @ParameterizedTest
    @CsvSource({"test-sensitivity-review,plan.json", "test-sensitivity-review,sample/page.txt", "change-code-review,request.json", "change-code-review,src/a.txt"})
    void aRawFileChangedAfterTheManifestWasMadeIsRefusedByTheSkillsCheckAndTheFolderIsRemoved(String capability, String changedPath) throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        BundleRun run = bundleRunOf(owner, capability, false);
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, run.bundle());
        // The caller uploads other bytes for one raw file than the ones the manifest names the digest of; the attachment's own digest is its bytes', so the server's pointer check holds
        // and what refuses is the skill's check of the bundle.
        byte[] original = run.bundle().files().get(changedPath);
        byte[] changed = new String(original, StandardCharsets.UTF_8).concat("\n").getBytes(StandardCharsets.UTF_8);
        Map<String, ToolServerSupport.Uploaded> tampered = new LinkedHashMap<>(uploaded);
        tampered.put(changedPath, uploadFile(owner, changedPath, changed));

        JsonNode refused = call("record_stage_result", passingBundleResult(run, tampered), owner);

        assertRefusedWith(refused, "INTEGRITY_FAILED", "workflow.validatorProcess.childFailure");
        assertEquals("INTEGRITY_FAILED", refused.path("error").path("details").path("childCode").asString(), refused.toString());
        assertRunUnchanged(owner, run);
        // The same files, unchanged, are accepted: the changed file was the cause.
        assertEquals("PASSED", ok(call("record_stage_result", passingBundleResult(run, uploaded), owner)).path("plan").path("stages").path(0).path("state").asString());
        assertNoBundleFolderLeft(foldersBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundleFiles.TEST_STAGE, EngineeringBundleFiles.CODE_STAGE})
    void aReportArtifactUploadedUnderAnotherFileNameThanThePathTheCheckReadIsRefused(String capability) throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        BundleRun run = bundleRunOf(owner, capability, false);
        EngineeringBundleFiles.Bundle bundle = run.bundle();
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, bundle);
        // The report's bytes are the same, but the attachment the result names as the report is named for another path; the file the check reads under the report's path is another
        // attachment of the same bytes (an evidence item), so the check accepts the bundle and the report artifact is not the file it read.
        ToolServerSupport.Uploaded renamed = uploadFile(owner, "copy/" + bundle.reportPath(), bundle.files().get(bundle.reportPath()));
        List<Map<String, Object>> evidence = rawFilesOf(bundle, uploaded);
        evidence.add(rawFileEvidence(bundle.reportPath(), uploaded.get(bundle.reportPath())));

        JsonNode refused = call("record_stage_result", bundleStageResult(run, uploaded.get(bundle.manifestPath()), renamed, evidence, "PASSED", null), owner);

        assertRefusedWith(refused, "INTEGRITY_FAILED", "workflow.engineering.engineeringArtifactsBindValidatedResult");
        assertRunUnchanged(owner, run);
        assertNoBundleFolderLeft(foldersBefore);
    }

    @Test
    void anAttachmentWhoseFileNameIsNotAPortableRelativePathIsRefusedAndTheFolderIsRemoved() throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        BundleRun run = bundleRunOf(owner, EngineeringBundleFiles.CODE_STAGE, false);
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, run.bundle());
        for (String fileName : List.of("../x", "a\\b", "/rooted", "a/../b", "dir//file")) {
            ToolServerSupport.Uploaded extra = uploadFile(owner, fileName, ("one more file named " + fileName + "\n").getBytes(StandardCharsets.UTF_8));
            List<Map<String, Object>> evidence = rawFilesOf(run.bundle(), uploaded);
            evidence.add(rawFileEvidence(fileName, extra));

            JsonNode refused = call("record_stage_result", bundleStageResult(run, uploaded.get(run.bundle().manifestPath()), uploaded.get(run.bundle().reportPath()), evidence, "PASSED", null), owner);

            assertRefusedWith(refused, "INVALID_INPUT", "workflow.bundle.fileNotUsable");
            assertEquals("PATH_NOT_PORTABLE", reason(refused), fileName + ": " + refused);
            assertEquals(extra.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), fileName + ": " + refused);
            assertRunUnchanged(owner, run);
        }
        assertNoBundleFolderLeft(foldersBefore);
    }

    @Test
    void twoAttachmentsOfTheSameFileNameInOneBundleAreRefusedAndTheFolderIsRemoved() throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        BundleRun run = bundleRunOf(owner, EngineeringBundleFiles.CODE_STAGE, false);
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, run.bundle());
        // Another attachment under the name of a file the bundle has already, with other bytes (so it is not the same attachment twice).
        String taken = "task.json";
        byte[] other = new String(run.bundle().files().get(taken), StandardCharsets.UTF_8).concat("\n").getBytes(StandardCharsets.UTF_8);
        ToolServerSupport.Uploaded second = uploadFile(owner, taken, other);
        List<Map<String, Object>> evidence = rawFilesOf(run.bundle(), uploaded);
        evidence.add(rawFileEvidence(taken, second));

        JsonNode refused = call("record_stage_result", bundleStageResult(run, uploaded.get(run.bundle().manifestPath()), uploaded.get(run.bundle().reportPath()), evidence, "PASSED", null), owner);

        assertRefusedWith(refused, "INVALID_INPUT", "workflow.bundle.fileNotUsable");
        assertEquals("DUPLICATE_PATH", reason(refused), refused.toString());
        assertEquals(second.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        assertRunUnchanged(owner, run);
        assertNoBundleFolderLeft(foldersBefore);
    }

    @Test
    void anotherSessionsAttachmentAsAFileOfTheBundleIsRefusedByThePointerCheck() throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        String stranger = session();
        BundleRun run = bundleRunOf(owner, EngineeringBundleFiles.CODE_STAGE, false);
        EngineeringBundleFiles.Bundle bundle = run.bundle();
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, bundle);

        // A raw file whose attachment another session uploaded (the same bytes): the check of the bundle accepts the files, and the pointer check in the recording transaction refuses it.
        Map<String, ToolServerSupport.Uploaded> foreignRaw = new LinkedHashMap<>(uploaded);
        ToolServerSupport.Uploaded foreignTask = uploadFile(stranger, "task.json", bundle.files().get("task.json"));
        foreignRaw.put("task.json", foreignTask);
        JsonNode rawRefused = call("record_stage_result", passingBundleResult(run, foreignRaw), owner);
        assertEquals("BINDING_INVALID", code(rawRefused), rawRefused.toString());
        assertEquals("ATTACHMENT_AUTHOR_MISMATCH", reason(rawRefused), rawRefused.toString());
        assertEquals("workflow.service.evidenceAttachmentOtherAuthor", rawRefused.path("error").path("messageKey").asString(), rawRefused.toString());
        assertEquals("EVIDENCE", rawRefused.path("error").path("details").path("source").asString(), rawRefused.toString());
        assertEquals(foreignTask.attachmentId(), rawRefused.path("error").path("details").path("attachmentId").asString(), rawRefused.toString());
        assertRunUnchanged(owner, run);

        // The same for the manifest, which is an artifact of the result.
        Map<String, ToolServerSupport.Uploaded> foreignManifest = new LinkedHashMap<>(uploaded);
        ToolServerSupport.Uploaded manifest = uploadFile(stranger, bundle.manifestPath(), bundle.files().get(bundle.manifestPath()));
        foreignManifest.put(bundle.manifestPath(), manifest);
        JsonNode manifestRefused = call("record_stage_result", passingBundleResult(run, foreignManifest), owner);
        assertEquals("BINDING_INVALID", code(manifestRefused), manifestRefused.toString());
        assertEquals("ATTACHMENT_AUTHOR_MISMATCH", reason(manifestRefused), manifestRefused.toString());
        assertEquals("ARTIFACT", manifestRefused.path("error").path("details").path("source").asString(), manifestRefused.toString());
        assertEquals(manifest.attachmentId(), manifestRefused.path("error").path("details").path("attachmentId").asString(), manifestRefused.toString());
        assertRunUnchanged(owner, run);
        assertNoBundleFolderLeft(foldersBefore);
    }

    @Test
    void aBundleReviewResultThatDoesNotPassIsJudgedByTheSameCheckAndRecordedLikeAnyStage() throws Exception {
        Set<String> foldersBefore = bundleFolders();
        String owner = session();
        // The report leaves a changed file unreviewed: the real check judges the review INCOMPLETE (a verdict that does not pass, over files that are whole), and the mapping of the stage
        // records it as BLOCKED with the error it asks for.
        BundleRun run = bundleRunOf(owner, EngineeringBundleFiles.CODE_STAGE, true);
        EngineeringBundleFiles.Bundle bundle = run.bundle();
        Map<String, ToolServerSupport.Uploaded> uploaded = uploadBundle(owner, bundle);
        Map<String, Object> incomplete = arguments("code", "MISSING_EVIDENCE", "message", "The review is incomplete.", "details", null);

        JsonNode recorded = ok(call("record_stage_result", bundleStageResult(run, uploaded.get(bundle.manifestPath()), uploaded.get(bundle.reportPath()), rawFilesOf(bundle, uploaded), "BLOCKED", incomplete), owner));

        assertEquals(1, recorded.path("stageResults").size(), recorded.toString());
        assertEquals("BLOCKED", recorded.path("plan").path("stages").path(0).path("state").asString(), recorded.toString());
        recorded.path("stageResults").get(0).path("artifactChecks").forEach(check -> assertEquals("SHA256_MATCHED", check.path("check").asString(), check.toString()));
        assertNoBundleFolderLeft(foldersBefore);
    }

    // ---- the operationKey of the write tools (AGS3-D-0267)
    // Each of the ten write tools of the workflow requires the caller's operationKey; the server makes none. A request that comes again with the same key and the same content is
    // answered with the first success (and the work is not done twice); the same key with other content is REQUEST_CONFLICT with the reason OPERATION_CONFLICT. Only a success is
    // stored: the same content under a new key meets the state the first success left (STALE_REVISION and the like), which shows that the key did the answering.

    static Stream<String> keyedTools() {
        return OperationKeys.WORKFLOW_WRITE_TOOLS.stream().sorted();
    }

    private static Map<String, Object> keyed(Map<String, Object> arguments, String key) {
        Map<String, Object> keyed = new LinkedHashMap<>(arguments);
        keyed.put("operationKey", key);
        return keyed;
    }

    private static long receiptsOf(String tool, String key) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql("SELECT COUNT(*) FROM sennard.operation_receipt WHERE tool = ? AND operation_key = ?").params(tool, key).query(Long.class).single());
    }

    private static long receiptsOf(String tool) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql("SELECT COUNT(*) FROM sennard.operation_receipt WHERE tool = ?").params(tool).query(Long.class).single());
    }

    /** A call that adds no key of its own, as a caller that forgot the key sends it. */
    private static JsonNode callAsSent(String tool, Map<String, Object> arguments, String session) {
        return body(client("codex").callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).meta(codexMeta(session)).build()));
    }

    private static void assertKeyConflict(JsonNode refused) {
        assertEquals("REQUEST_CONFLICT", code(refused), refused.toString());
        assertEquals("OPERATION_CONFLICT", reason(refused), refused.toString());
        assertEquals("services.operation.keyConflict", refused.path("error").path("messageKey").asString(), refused.toString());
    }

    @ParameterizedTest
    @MethodSource("keyedTools")
    void aWriteToolPublishesItsOperationKeyAsRequiredWithTheCommonPattern(String tool) {
        JsonNode schema = schema(listed().get(tool));
        assertTrue(texts(schema.get("required")).contains("operationKey"), tool);
        assertEquals("^[A-Za-z0-9_.:-]{16,128}$", schema.get("properties").get("operationKey").get("pattern").asString(), tool);
    }

    /** Legacy selection needs only runId; an explicit PAUSED transition supplies revision and key together. */
    @Test
    void aResumeKeepsLegacySelectionAndPairsTheExplicitTransitionRevisionAndKey() {
        JsonNode schema = schema(listed().get("resume_workflow"));
        assertFalse(OperationKeys.takesKey("resume_workflow"), "the test helper must preserve legacy keyless selection");
        assertEquals(Set.of("runId", "expectedRevision", "operationKey"), Set.copyOf(schema.get("properties").propertyNames()), schema.toString());
        assertEquals(Set.of("runId"), Set.copyOf(texts(schema.get("required"))), schema.toString());
        assertEquals(Set.of("operationKey"), Set.copyOf(texts(schema.path("dependentRequired").path("expectedRevision"))), schema.toString());
        assertEquals(Set.of("expectedRevision"), Set.copyOf(texts(schema.path("dependentRequired").path("operationKey"))), schema.toString());
    }

    @ParameterizedTest
    @MethodSource("keyedTools")
    void aWriteToolRefusesAMissingOrMalformedOperationKeyAndTheServerMakesNone(String tool) {
        String owner = session();
        long before = receiptsOf(tool);
        Map<String, Object> content = declaredArguments(tool);
        Set<String> published = Set.copyOf(schema(listed().get(tool)).get("properties").propertyNames());
        assertTrue(published.containsAll(content.keySet()), tool + " is called with arguments its schema declares (the entrance refuses the others before the tool): " + content.keySet() + " in " + published);
        JsonNode missing = callAsSent(tool, content, owner);
        assertEquals("INVALID_INPUT", code(missing), missing.toString());
        assertEquals("INVALID_OPERATION_KEY", reason(missing), missing.toString());
        assertEquals("workflow.service.operationKeyInvalid", missing.path("error").path("messageKey").asString(), missing.toString());
        for (Object malformed : java.util.Arrays.asList(null, "", "short-key", "a".repeat(129), "has space in the operation key", "ünïcode-operation-key-1", 12345678901234567L)) {
            JsonNode refused = callAsSent(tool, withKeyValue(content, malformed), owner);
            assertEquals("INVALID_INPUT", code(refused), malformed + " " + refused);
            assertEquals("INVALID_OPERATION_KEY", reason(refused), malformed + " " + refused);
        }
        assertEquals(before, receiptsOf(tool), "a refusal leaves no receipt, and the server made no key");
    }

    /**
     * A call of the tool made of arguments it declares and no operationKey. The entrance (UndeclaredArgumentGuard) refuses an argument the schema does not declare before the tool runs, so
     * a call that is to reach the key's check carries the arguments of its own tool: the key is judged before the content is, so the values need only be of the declared kind.
     */
    private static Map<String, Object> declaredArguments(String tool) {
        return switch (tool) {
            case "plan_workflow" -> arguments("schemaVersion", "1.0.0", "objective", "A plan.");
            case "open_convergence_root" -> arguments("schemaVersion", "1.0.0", "parentRootId", "root-1");
            case "claim_workflow_attempt" -> arguments("schemaVersion", "1.0.0", "rootId", "root-1", "expectedRevision", 0);
            case "start_guarded_workflow" -> arguments("schemaVersion", "1.0.0", "leaseId", "lease-1", "expectedRootRevision", 0);
            case "resolve_convergence_gate" -> arguments("schemaVersion", "1.0.0", "rootId", "root-1", "expectedRevision", 0);
            case "start_workflow", "start_inquiry_workflow" -> arguments("goal", "A goal.");
            // The operation names its target. resume_workflow stays out of this required-key suite:
            // legacy selection is keyless; the explicit PAUSED transition pairs its optional revision and key.
            case "start_operation_workflow" -> arguments("goal", "A goal.", "target", "A target.");
            case "record_stage_result" -> arguments("schemaVersion", "1.0.0", "runId", "run-1", "stageId", "stage-1", "expectedRevision", 0, "state", "passed");
            case "finalize_workflow", "abort_workflow", "submit_change_plan" -> arguments("runId", "run-1", "expectedRevision", 0);
            case "pause_workflow" -> arguments("runId", "run-1", "expectedRevision", 0, "reason", "Waiting for the requested user input.", "resumeCondition", "The user supplies the input and explicitly resumes.");
            case "release_convergence_root" -> arguments("rootId", "root-1", "expectedRevision", 0, "reason", "The work moved elsewhere.");
            default -> throw new IllegalArgumentException("No declared arguments for " + tool + ": a new keyed tool needs its own case here");
        };
    }

    private static Map<String, Object> withKeyValue(Map<String, Object> arguments, Object value) {
        Map<String, Object> keyed = new LinkedHashMap<>(arguments);
        keyed.put("operationKey", value);
        return keyed;
    }

    @Test
    void theOldRequestIdIsNotAnAliasOfTheOperationKey() {
        String owner = session();
        long runsBefore = count("workflow_runs", "1 = 1");
        long rootsBefore = count("convergence_roots", "1 = 1");
        JsonNode refused = callAsSent("start_workflow", arguments("requestId", OperationKeys.fresh()), owner);
        // The entrance refuses an argument the schema does not declare before the tool runs, so the old name is no key and not even read as one.
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("UNDECLARED_ARGUMENT", refused.path("error").path("details").path("reasonCode").asString(""), refused.toString());
        assertTrue(refused.path("error").path("message").asString("").contains("requestId"), refused.toString());
        assertEquals(runsBefore, count("workflow_runs", "1 = 1"), "no run was made");
        assertEquals(rootsBefore, count("convergence_roots", "1 = 1"), "no root was opened");
    }

    @Test
    void aStartThatComesAgainWithItsKeyIsAnsweredWithTheFirstStartAndMakesNoSecondRoot() throws Exception {
        Path workspace = product();
        String owner = session();
        String key = OperationKeys.fresh();
        Map<String, Object> arguments = keyed(start(workspace, List.of("alpha")), key);
        JsonNode first = ok(call("start_workflow", arguments, owner));
        assertEquals(1, receiptsOf("start_workflow", key));
        JsonNode again = ok(call("start_workflow", arguments, owner));
        assertEquals(first.get("runId"), again.get("runId"));
        assertEquals(first.get("rootId"), again.get("rootId"));
        assertEquals(1, rootsOf(workspace));
        assertEquals(1, receiptsOf("start_workflow", key));
        // the same start under a new key meets the root the first one opened: the key is what answered
        assertEquals("ROOT_CONFLICT", code(call("start_workflow", keyed(arguments, OperationKeys.fresh()), owner)));
        // the same key with other content
        assertKeyConflict(call("start_workflow", keyed(start(workspace, List.of("beta")), key), owner));
        assertEquals(1, receiptsOf("start_workflow", key));
    }

    /** The key is the caller's own (AGS3-D-0267, scope): another session's plan with the same key and content is its own request with its own receipt, and its own conflict. */
    @Test
    void aPlanOfAnotherSessionWithTheSameKeyAndContentIsItsOwnOperation() throws Exception {
        String owner = session();
        String other = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        ObjectNode task = (ObjectNode) root(started.get("rootId").asString(), owner).get("taskEnvelope").deepCopy();
        String key = OperationKeys.fresh();
        Map<String, Object> plan = keyed(MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), key);
        JsonNode byOwner = ok(call("plan_workflow", plan, owner));
        JsonNode byOther = ok(call("plan_workflow", plan, other));
        assertEquals(2, receiptsOf("plan_workflow", key), "one receipt for each session");
        assertEquals(byOwner, ok(call("plan_workflow", plan, owner)));
        assertEquals(byOther, ok(call("plan_workflow", plan, other)));
        assertEquals(2, receiptsOf("plan_workflow", key));
        ObjectNode changedTask = task.deepCopy();
        changedTask.put("objective", task.get("objective").asString() + " Also more.");
        assertKeyConflict(call("plan_workflow", keyed(MAPPER.convertValue(changedTask, new TypeReference<Map<String, Object>>() { }), key), other));
        assertEquals(2, receiptsOf("plan_workflow", key));
    }

    @Test
    void aStageResultAndTheFinishThatComeAgainWithTheirKeysAreAnsweredWithTheFirstSuccess() throws Exception {
        String owner = session();
        JsonNode started = ok(call("start_workflow", startAll(product()), owner));
        String runId = started.get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));

        String recordKey = OperationKeys.fresh();
        Map<String, Object> result = keyed(passedStage(owner, runId, receipt), recordKey);
        JsonNode recorded = ok(call("record_stage_result", result, owner));
        JsonNode recordedAgain = ok(call("record_stage_result", result, owner));
        assertEquals(recorded.get("revision"), recordedAgain.get("revision"), "the run's revision moved on, and the answer is still the first one");
        assertEquals(1, receiptsOf("record_stage_result", recordKey));
        JsonNode otherKey = call("record_stage_result", keyed(result, OperationKeys.fresh()), owner);
        assertEquals("STALE_REVISION", code(otherKey), otherKey.toString());
        Map<String, Object> changed = new LinkedHashMap<>(result);
        changed.put("findings", List.of("another finding"));
        assertKeyConflict(call("record_stage_result", changed, owner));

        String finalizeKey = OperationKeys.fresh();
        Map<String, Object> finish = keyed(arguments("runId", runId, "expectedRevision", recorded.get("revision").asLong()), finalizeKey);
        JsonNode finalized = ok(call("finalize_workflow", finish, owner));
        JsonNode finalizedAgain = ok(call("finalize_workflow", finish, owner));
        assertEquals("PASSED", finalizedAgain.get("state").asString());
        assertEquals(finalized.get("revision"), finalizedAgain.get("revision"));
        assertEquals(1, receiptsOf("finalize_workflow", finalizeKey));
        assertEquals("STALE_REVISION", code(call("finalize_workflow", keyed(finish, OperationKeys.fresh()), owner)));
        assertKeyConflict(call("finalize_workflow", keyed(arguments("runId", runId, "expectedRevision", recorded.get("revision").asLong() + 1), finalizeKey), owner));
    }

    @Test
    void anAbortThatComesAgainWithItsKeyIsAnsweredWithTheFirstSuccess() throws Exception {
        String owner = session();
        String runId = ok(call("start_workflow", startAll(product()), owner)).get("runId").asString();
        JsonNode receipt = ok(call("get_workflow_status", arguments("runId", runId), owner));
        String key = OperationKeys.fresh();
        Map<String, Object> abort = keyed(arguments("runId", runId, "expectedRevision", receipt.get("revision").asLong()), key);
        JsonNode aborted = ok(call("abort_workflow", abort, owner));
        JsonNode abortedAgain = ok(call("abort_workflow", abort, owner));
        assertEquals(aborted.get("revision"), abortedAgain.get("revision"));
        assertEquals(aborted.get("state"), abortedAgain.get("state"));
        assertEquals(1, receiptsOf("abort_workflow", key));
        assertEquals("STALE_REVISION", code(call("abort_workflow", keyed(abort, OperationKeys.fresh()), owner)));
        assertKeyConflict(call("abort_workflow", keyed(arguments("runId", runId, "expectedRevision", receipt.get("revision").asLong() + 1), key), owner));
    }

    /**
     * The first {@code open_convergence_root} of a session that has no task meets the first {@code load_context} of the same session (the race the audit found: nothing serializes the
     * creation of the task). The test holds the table of the tasks' current memory, so {@code load_context} inserts the task and stops with its row not committed; the open then
     * finds no task either, makes its root, and waits at the task's primary key (the database shows both waits before the lock goes). When the key is free the open's
     * {@code ensureTask} ends in {@code STORE_UNIQUE_VIOLATION}; binding the session to the root is part of the open's own transaction and nothing catches that failure, so the whole open
     * is rolled back: no root, no surface row, no receipt, and the answer carries the store's code as its reason. The same request sent again opens the root and the session's task holds it.
     */
    @Test
    void anOpenThatLosesTheFirstCreationOfTheSessionsTaskIsRolledBackWholeAndAnswersTheStoresCode() throws Exception {
        Path workspace = product();
        String author = session();
        String firstRootId = ok(call("start_workflow", start(workspace, List.of("alpha")), author)).get("rootId").asString();
        JsonNode current = root(firstRootId, author);
        ok(call("release_convergence_root", releaseArguments(firstRootId, author), author));
        ObjectNode task = (ObjectNode) current.get("taskEnvelope").deepCopy();
        task.put("taskId", "task-" + UUID.randomUUID());
        Map<String, Object> frame = MAPPER.convertValue(current.get("frame"), new TypeReference<Map<String, Object>>() { });
        String key = OperationKeys.fresh();
        Map<String, Object> open = keyed(arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", MAPPER.convertValue(task, Map.class), "frame", frame, "userApprovalRefs", List.of()), key);

        String loser = session();
        String correlation = server.bean(ContinuityStore.class).opaqueDigest("session", loser);
        String tasks = "SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?";
        assertEquals(0, SessionServerSupport.count(server, tasks, correlation), "the session has no task");
        long roots = count("convergence_roots", "1 = 1");
        long surfaces = count("convergence_active_surfaces", "1 = 1");
        JsonNode loaded;
        JsonNode lost;
        try (CallerClients callers = new CallerClients(2); HookRaceLocks memory = HookRaceLocks.lockingWritesOf("sennard.continuity_current_memory")) {
            List<Callable<JsonNode>> loadCall = new ArrayList<>();
            // This class's arguments(...) adds nothing: load_context requires schemaVersion, and a call without it is refused at the entrance before any use case runs (no task, no write).
            loadCall.add(() -> call(callers.clients.get(0), "load_context", arguments("schemaVersion", "1.0.0"), codexMeta(loser)));
            List<Callable<JsonNode>> openCall = new ArrayList<>();
            openCall.add(() -> call(callers.clients.get(1), "open_convergence_root", open, codexMeta(loser)));
            try (HookRaceLocks.Calls<JsonNode> load = HookRaceLocks.Calls.start(loadCall)) {
                memory.awaitBlocked(1, "load_context did not come to make the task and wait for its current memory");
                try (HookRaceLocks.Calls<JsonNode> opening = HookRaceLocks.Calls.start(openCall)) {
                    memory.awaitQueuedBehindBlocked(1, "the open does not wait for the task row that load_context inserted: the statements did not meet");
                    memory.release();
                    loaded = load.answers().getFirst();
                    lost = opening.answers().getFirst();
                }
            }
        }
        assertTrue(loaded.path("ok").asBoolean(false), loaded.toString());
        assertFalse(lost.path("ok").asBoolean(true), lost.toString());
        assertEquals("MCP_UNAVAILABLE", code(lost), lost.toString());
        assertEquals("STORE_UNIQUE_VIOLATION", reason(lost), lost.toString());
        assertEquals(roots, count("convergence_roots", "1 = 1"), "the open that lost left no root row");
        assertEquals(surfaces, count("convergence_active_surfaces", "1 = 1"), "the open that lost left no surface row");
        assertEquals(0, receiptsOf("open_convergence_root", key), "the open that lost left no receipt");
        assertEquals(1, SessionServerSupport.count(server, tasks, correlation), "load_context made the one task");
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ? AND root_id IS NOT NULL", correlation), "the task holds no root");

        JsonNode opened = ok(call("open_convergence_root", open, loser));
        assertEquals(roots + 1, count("convergence_roots", "1 = 1"));
        assertEquals(surfaces + 1, count("convergence_active_surfaces", "1 = 1"));
        assertEquals(1, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ? AND root_id = ?", correlation, opened.get("rootId").asString()),
                "the task of the session holds the root that the repeated open made");
    }

    @Test
    void theRootPlanAndLeaseToolsAnswerARequestThatComesAgainWithTheFirstSuccess() throws Exception {
        Path workspace = product();
        String owner = session();
        String firstRootId = ok(call("start_workflow", start(workspace, List.of("alpha")), owner)).get("rootId").asString();
        JsonNode current = root(firstRootId, owner);

        // release_convergence_root
        String releaseKey = OperationKeys.fresh();
        Map<String, Object> release = keyed(arguments("rootId", firstRootId, "expectedRevision", current.get("revision").asLong(), "reason", "The test opens its own root."), releaseKey);
        JsonNode released = ok(call("release_convergence_root", release, owner));
        assertEquals(released, ok(call("release_convergence_root", release, owner)));
        assertEquals(1, receiptsOf("release_convergence_root", releaseKey));
        assertKeyConflict(call("release_convergence_root", keyed(arguments("rootId", firstRootId, "expectedRevision", current.get("revision").asLong(), "reason", "Another reason."), releaseKey), owner));

        // open_convergence_root: a second open of the same write surface would be a ROOT_CONFLICT
        ObjectNode task = (ObjectNode) current.get("taskEnvelope").deepCopy();
        task.put("taskId", "task-" + UUID.randomUUID());
        Map<String, Object> frame = MAPPER.convertValue(current.get("frame"), new TypeReference<Map<String, Object>>() { });
        String openKey = OperationKeys.fresh();
        Map<String, Object> open = keyed(arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", MAPPER.convertValue(task, Map.class), "frame", frame, "userApprovalRefs", List.of()), openKey);
        JsonNode opened = ok(call("open_convergence_root", open, owner));
        assertEquals(opened, ok(call("open_convergence_root", open, owner)));
        assertEquals(1, receiptsOf("open_convergence_root", openKey));
        ObjectNode otherTask = task.deepCopy();
        otherTask.put("taskId", "task-" + UUID.randomUUID());
        assertKeyConflict(call("open_convergence_root", keyed(arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", MAPPER.convertValue(otherTask, Map.class), "frame", frame, "userApprovalRefs", List.of()), openKey), owner));

        // plan_workflow
        String planKey = OperationKeys.fresh();
        Map<String, Object> plan = keyed(MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), planKey);
        JsonNode planned = ok(call("plan_workflow", plan, owner));
        assertEquals(planned, ok(call("plan_workflow", plan, owner)));
        assertEquals(1, receiptsOf("plan_workflow", planKey));
        ObjectNode changedTask = task.deepCopy();
        changedTask.put("objective", task.get("objective").asString() + " Also more.");
        assertKeyConflict(call("plan_workflow", keyed(MAPPER.convertValue(changedTask, new TypeReference<Map<String, Object>>() { }), planKey), owner));

        // claim_workflow_attempt: the lease is issued once; a second claim of the same revision would be refused
        String claimKey = OperationKeys.fresh();
        Map<String, Object> claim = keyed(arguments("schemaVersion", "1.0.0", "rootId", opened.get("rootId").asString(), "expectedRevision", opened.get("revision").asLong(), "taskEnvelope", MAPPER.convertValue(task, Map.class),
                "frame", frame, "plan", MAPPER.convertValue(planned, Map.class), "outputTargets", List.of("alpha"), "priorFailure", null), claimKey);
        JsonNode lease = ok(call("claim_workflow_attempt", claim, owner));
        assertEquals(lease, ok(call("claim_workflow_attempt", claim, owner)));
        assertEquals(1, receiptsOf("claim_workflow_attempt", claimKey));
        Map<String, Object> otherClaim = new LinkedHashMap<>(claim);
        otherClaim.put("outputTargets", List.of("beta"));
        assertKeyConflict(call("claim_workflow_attempt", otherClaim, owner));

        // start_guarded_workflow: the lease is used once
        String guardedKey = OperationKeys.fresh();
        Map<String, Object> guarded = keyed(arguments("schemaVersion", "1.0.0", "leaseId", lease.get("leaseId").asString(), "expectedRootRevision", lease.get("rootRevision").asLong(),
                "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test"))), guardedKey);
        List<String> guardedStart = contents("start_guarded_workflow", guarded, owner);
        assertEntryIntake(guardedStart);
        JsonNode run = ok(MAPPER.readTree(guardedStart.getFirst()));
        JsonNode runAgain = ok(call("start_guarded_workflow", guarded, owner));
        assertEquals(run.get("runId"), runAgain.get("runId"));
        assertEquals(1, receiptsOf("start_guarded_workflow", guardedKey));
        assertFalse(call("start_guarded_workflow", keyed(guarded, OperationKeys.fresh()), owner).path("ok").asBoolean(true), "the lease was used by the first start: the same content under a new key is refused");
        assertKeyConflict(call("start_guarded_workflow", keyed(arguments("schemaVersion", "1.0.0", "leaseId", lease.get("leaseId").asString(), "expectedRootRevision", lease.get("rootRevision").asLong(),
                "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "another reason"))), guardedKey), owner));
    }

    @Test
    void aGateResolutionThatComesAgainWithItsKeyIsAnsweredWithTheFirstSuccess() throws Exception {
        String owner = session();
        String rootId = ok(call("start_workflow", start(product(), List.of("alpha")), owner)).get("rootId").asString();
        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner, owner)));
        JsonNode status = ok(call("get_convergence_status", arguments("rootId", rootId), owner));
        JsonNode current = status.get("root");
        Map<String, Object> review = arguments("schemaVersion", "1.0.0", "reviewId", "review-" + UUID.randomUUID(), "rootId", rootId, "rootRevision", current.get("revision").asLong(), "epoch", status.get("currentEpoch").asLong(),
                "reviewerActorId", "fresh-frame-auditor", "freshContext", arguments("confirmed", true, "evidenceRef", "review-evidence:1"), "classification", "semantics-changing",
                "comparability", arguments("comparable", false, "rationale", "The evaluation meaning is changed or unresolved."), "route", "needs-user", "proposedFrame", MAPPER.convertValue(current.get("frame"), Map.class),
                "evidenceRefs", List.of("review-evidence:1"), "userApprovalRefs", List.of(), "reviewedAt", java.time.Instant.now().toString());
        String key = OperationKeys.fresh();
        Map<String, Object> resolve = keyed(arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "review", review), key);
        JsonNode resolved = ok(call("resolve_convergence_gate", resolve, owner));
        assertEquals(resolved, ok(call("resolve_convergence_gate", resolve, owner)));
        assertEquals(1, receiptsOf("resolve_convergence_gate", key));
        assertEquals("STALE_REVISION", code(call("resolve_convergence_gate", keyed(resolve, OperationKeys.fresh()), owner)));
        Map<String, Object> otherReview = new LinkedHashMap<>(review);
        otherReview.put("reviewerActorId", "another-auditor");
        assertKeyConflict(call("resolve_convergence_gate", keyed(arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "review", otherReview), key), owner));
    }

    // ---- the edit gate

    static Stream<Arguments> editToolsOfEachHost() {
        return Stream.of(Arguments.of("codex", "apply_patch", "patch"), Arguments.of("claude", "Write", "file_path"), Arguments.of("agy", "write_to_file", "TargetFile"));
    }

    private static Object edit(String form, Path workspace, String name) {
        return switch (form) {
            case "patch" -> Map.of("command", "*** Begin Patch\n*** Update File: " + name + "\n*** End Patch");
            default -> Map.of(form, workspace.resolve(name).toString());
        };
    }

    @ParameterizedTest
    @MethodSource("editToolsOfEachHost")
    void anEditOfARequestWithNoWorkflowIsRefusedAtTheFirstFileNamingItAndEveryFileAfterIt(String host, String tool, String form) throws Exception {
        Path workspace = product();
        String owner = session();
        String first = refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "src/a.ts"), null));
        assertNotNull(first, "the first file is refused: no file of a request is free of a workflow");
        assertTrue(first.contains("src") && first.contains("a.ts"), "the edit tool of this host is read as a write of the file it names: " + first);
        assertTrue(first.contains("no workflow handles this request") && first.contains("start_workflow"), "the words say what is missing and the call that opens it: " + first);
        assertNotNull(refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "src/a.ts"), null)), "the same file again is refused as well: nothing lets it through by itself");
        String second = refusal(host, hook(host, owner, workspace, tool, edit(form, workspace, "src/b.ts"), null));
        assertNotNull(second, "another file is refused at once, not counted");
        assertTrue(second.contains("b.ts"), second);
    }

    static Stream<Arguments> shellToolsOfEachHost() {
        return Stream.of(Arguments.of("codex", "exec_command", Map.of("cmd", "printf a > src/a.ts; printf b >> src/b.ts; printf c > 'src/c file.ts'", "shell", "/bin/bash")),
                Arguments.of("claude", "Bash", Map.of("command", "printf a > src/a.ts; printf b >> src/b.ts; printf c > 'src/c file.ts'")),
                Arguments.of("claude", "Bash", Map.of("command", "cat > src/a.ts <<'EOF'\na\nEOF\ncat > src/b.ts <<'EOF'\nb\nEOF\ncat > src/c.ts <<'EOF'\nc\nEOF")),
                Arguments.of("agy", "run_command", Map.of("CommandLine", "printf a > src/a.ts; printf b >> src/b.ts; printf c > 'src/c file.ts'")));
    }

    @ParameterizedTest
    @MethodSource("shellToolsOfEachHost")
    void oneShellCallThatWritesThreeFilesWithNoWorkflowIsRefusedBeforeItRunsAndNamesEachFile(String host, String tool, Map<String, String> input) throws Exception {
        String refused = refusal(host, hook(host, session(), product(), tool, input, null));
        assertNotNull(refused);
        // The redirections of the command are read as the writes they are, whatever form the host's shell tool gives the command in: each file is named, none is "unknown".
        assertTrue(refused.contains("a.ts") && refused.contains("b.ts") && (refused.contains("c.ts") || refused.contains("c file.ts")), refused);
        assertFalse(refused.contains("path unknown"), refused);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude", "codex"})
    void aReadOnlyShellCallNeverCounts(String host) throws Exception {
        Path workspace = product();
        String owner = session();
        Map<String, String> input = host.equals("codex") ? Map.of("cmd", "git status --short", "shell", "/bin/bash") : Map.of("command", "git status --short");
        for (int i = 0; i < 4; i++) {
            assertEquals(null, refusal(host, hook(host, owner, workspace, host.equals("codex") ? "exec_command" : "Bash", input, null)));
        }
    }

    @Test
    void anUnnamedShellWriteIsRefusedEveryTimeWithNoWorkflowAndPassesInAWorkflowOfTheWholeWorkspace() throws Exception {
        Path workspace = product();
        String owner = session();
        Map<String, String> write = Map.of("cmd", "python3 -c \"open('src/a.ts','w').write('x')\"", "shell", "/bin/bash");
        for (int call = 0; call < 3; call++) {
            String refused = refusal("codex", hook("codex", owner, workspace, "exec_command", write, null));
            assertNotNull(refused, "call " + call + ": a write that names no file is refused each time, not counted once");
            assertTrue(refused.contains("path unknown: exec_command") && refused.contains("no workflow handles this request"), refused);
        }
        // The same command, in the session of a change workflow that covers the whole workspace, is a write inside it.
        String covered = session();
        ok(call("start_workflow", startAll(workspace), covered));
        assertEquals(null, refusal("codex", hook("codex", covered, workspace, "exec_command", write, null)));
    }

    @Test
    void aFileToolAndAShellCallAreJudgedByTheSameInstanceOfTheRequest() throws Exception {
        Path workspace = product();
        String owner = session();
        // With no workflow both are refused at the first call.
        assertNotNull(refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve("src/a.ts").toString()), null)));
        assertNotNull(refusal("claude", hook("claude", owner, workspace, "Bash", Map.of("command", "printf a > src/a.ts; printf b > src/b.ts"), null)));
        // With a change workflow of the request whose writeTargets are src, the file tool and the shell's redirections are inside it, and a file outside it is refused by either.
        String served = session();
        ok(call("start_workflow", start(workspace, List.of("src")), served));
        assertEquals(null, refusal("claude", hook("claude", served, workspace, "Write", Map.of("file_path", workspace.resolve("src/a.ts").toString()), null)));
        assertEquals(null, refusal("claude", hook("claude", served, workspace, "Bash", Map.of("command", "printf a > src/a.ts; printf b > src/b.ts"), null)));
        assertNotNull(refusal("claude", hook("claude", served, workspace, "Write", Map.of("file_path", workspace.resolve("lib/c.ts").toString()), null)));
        assertNotNull(refusal("claude", hook("claude", served, workspace, "Bash", Map.of("command", "printf c > lib/c.ts"), null)));
    }

    @Test
    void aSubagentsEditIsNotServedByTheInstanceOfItsParent() throws Exception {
        Path workspace = product();
        String owner = session();
        ok(call("start_workflow", startAll(workspace), owner));
        assertEquals(null, refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve("a.ts").toString()), null)), "the parent's own edit is inside its instance");
        // A subagent counts only the instances it took itself, so the same edit made by it, and a shell write of its own, are refused at the first call.
        String refused = refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve("a.ts").toString()), "child-agent"));
        assertNotNull(refused);
        assertTrue(refused.contains("no workflow handles this request"), refused);
        assertNotNull(refusal("claude", hook("claude", owner, workspace, "Bash", Map.of("command", "printf c > c.ts"), "child-agent")));
    }

    @Test
    void aFailedStartDoesNotPermitOrReportChildEdits() throws Exception {
        Path workspace = product();
        String owner = session();
        ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String child = "child-d0101";
        server.bean(WorkflowService.class).recordStartFailure(new WorkflowCall("claude-code", owner, null, null, null, null, "en"), WorkflowCode.MCP_UNAVAILABLE);
        String parentScope = refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve("alpha/parent-only.txt").toString()), child));
        String outside = refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve("beta/outside.txt").toString()), child));
        String end = subagentEnd(owner, child);
        assertFalse(end.contains("[Sennard scope check]"), "refused attempts produced a scope report: " + end);
        assertNotNull(parentScope, "a failed start does not grant the child access to the parent's scope");
        assertNotNull(outside, "a failed start does not grant the child access outside any scope");
    }

    private static String subagentEnd(String session, String agentId) throws Exception {
        var response = DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStop", null, session, body -> body.put("agent_id", agentId).put("agent_type", "general-purpose"));
        assertEquals(200, response.status());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void aRunningWorkflowsWriteScopeIsEnforcedOutsideIt() throws Exception {
        Path workspace = product();
        String owner = session();
        ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        assertFalse(denied(patch(owner, workspace, "alpha/inside.ts")));
        // A file outside the writeTargets of the instances of the request is refused at the first file, and at every one after it (no free files are counted before the refusal).
        String refused = refusal("codex", patch(owner, workspace, "other/one.ts"));
        assertNotNull(refused);
        assertTrue(refused.contains("change plan") && refused.contains("one.ts"), refused);
        assertNotNull(refusal("codex", patch(owner, workspace, "other/two.ts")));
        assertFalse(denied(patch(owner, workspace, "alpha/inside-again.ts")), "the refusals of files outside leave the instance's own scope open");
    }

    // ---- a write whose file the hook cannot read

    private static final Map<String, String> UNREAD_WRITE = Map.of("command", "cat > \"$SCRATCH/x.py\"");

    private static String unreadWrite(String owner, Path workspace, Map<String, String> command) throws Exception {
        return refusal("claude", hook("claude", owner, workspace, "Bash", command, null));
    }

    @Test
    void anUnreadShellWriteInsideAWorkflowThatCoversTheWholeWorkspaceIsNeverRefused() throws Exception {
        Path workspace = product();
        String owner = session();
        ok(call("start_workflow", startAll(workspace), owner));
        // The case of the session that met it: a workflow on "." and a Bash write to a path held in a variable, again and again.
        for (int call = 0; call < 6; call++) {
            assertEquals(null, unreadWrite(owner, workspace, UNREAD_WRITE), "call " + call);
        }
    }

    @Test
    void anUnreadShellWriteWithNoWorkflowIsRefusedAtTheFirstCallAndEveryOneAfterItAndTheWordsSayNoWorkflowHandlesTheRequest() throws Exception {
        Path workspace = product();
        String owner = session();
        for (int call = 0; call < 3; call++) {
            String refused = unreadWrite(owner, workspace, UNREAD_WRITE);
            assertNotNull(refused, "call " + call);
            assertTrue(refused.contains("path unknown: Bash") && refused.contains("no workflow handles this request"), refused);
            assertTrue(refused.contains("start_workflow {"), "the words give the call that opens a change workflow: " + refused);
        }
    }

    @Test
    void anUnreadShellWriteInsideAWorkflowOfANarrowerScopeIsRefusedAtTheFirstCallAndTheWordsDoNotSendTheAgentToAnotherWorkflow() throws Exception {
        Path workspace = product();
        String owner = session();
        ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        for (int call = 0; call < 3; call++) {
            String refused = unreadWrite(owner, workspace, UNREAD_WRITE);
            assertNotNull(refused, "call " + call);
            // The instance the request took edits files, but a write that names no file is in no change plan: the words say the write cannot be read and to name the path.
            assertTrue(refused.contains("cannot be read") && refused.contains("file tool that names the path"), refused);
            assertFalse(refused.contains("no workflow handles this request"), "the request has a workflow: " + refused);
            assertFalse(refused.contains("non-overlapping"), refused);
        }
    }

    @Test
    void aFileOutsideTheWorkflowNamedInTheSameCallKeepsTheUnreadWriteRefused() throws Exception {
        Path workspace = product();
        Path elsewhere = product();
        String owner = session();
        ok(call("start_workflow", startAll(workspace), owner));
        // The named file is outside the workflow (another checkout directory): the call is refused naming it, and the unread write of the same call is refused with it, not excused as
        // inside the workflow that covers the whole workspace.
        Map<String, String> mixed = Map.of("command", "printf a > " + elsewhere.resolve("a.ts").toString().replace('\\', '/') + "; cat > \"$SCRATCH/x.py\"");
        String refused = unreadWrite(owner, workspace, mixed);
        assertNotNull(refused);
        assertTrue(refused.contains("a.ts") && refused.contains("path unknown: Bash"), refused);
        // The same call with the named file inside the workflow is wholly inside it.
        Map<String, String> inside = Map.of("command", "printf a > " + workspace.resolve("a.ts").toString().replace('\\', '/') + "; cat > \"$SCRATCH/x.py\"");
        assertEquals(null, unreadWrite(owner, workspace, inside));
    }

    @Test
    void aWorkflowThatCoversTheWholeWorkspaceRefusesAFileNamedOutsideItAtOnce() throws Exception {
        Path workspace = product();
        Path elsewhere = product();
        String owner = session();
        ok(call("start_workflow", startAll(workspace), owner));
        assertEquals(null, unreadWrite(owner, workspace, Map.of("command", "printf a > " + workspace.resolve("a.ts").toString().replace('\\', '/'))), "a file named inside the workspace is inside the workflow");
        for (String name : List.of("a.ts", "b.ts")) {
            String refused = unreadWrite(owner, workspace, Map.of("command", "printf a > " + elsewhere.resolve(name).toString().replace('\\', '/')));
            assertNotNull(refused, name + " is named, and lies outside every writeTargets of the request's instances");
            assertTrue(refused.contains(name) && refused.contains("change plan"), refused);
        }
    }

    @Test
    void aGatedRootsWriteSurfaceIsClosedToEveryEdit() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", start(workspace, List.of("alpha")), owner));
        String rootId = started.get("rootId").asString();
        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner, owner)));
        String refused = refusal("codex", patch(owner, workspace, "alpha/x.ts"));
        assertNotNull(refused);
        assertTrue(refused.contains(rootId), refused);
        // Outside the gated surface the ordinary rule applies: the instance of the request has no such file in its scope, so the edit is refused for that, not for the gated root.
        String outside = refusal("codex", patch(owner, workspace, "beta/first.ts"));
        assertNotNull(outside, "beta is no part of the instance's scope");
        assertFalse(outside.contains(rootId), outside);
        assertTrue(outside.contains("change plan"), outside);
    }

    @Test
    void theTemporaryDirectoryIsNotProduct(@TempDir Path scratch) throws Exception { // JUnit's folder lies in the OS temporary directory, which is what is tested
        String owner = session();
        for (String name : List.of("a.ts", "b.ts", "c.ts", "d.ts")) {
            assertEquals(null, refusal("claude", hook("claude", owner, scratch, "Write", Map.of("file_path", scratch.resolve(name).toString()), null)), name);
        }
    }

    // ---- which model made the call

    @Test
    void aHostThatReportsNoModelCannotAttestOrProceedWithEdits() throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        String hookBody = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8).replace("mcp__plugin_sennard-probe_probe__probe_echo",
                "mcp__plugin_sennard_sennard__start_workflow");
        // The transcript the hook names does not exist, so no model can be read.
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.getBytes(StandardCharsets.UTF_8)).status());
        JsonNode refused = body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build()));
        assertFalse(refused.path("ok").asBoolean(true), refused.toString());
        assertTrue(Set.of("BINDING_REQUIRED", "BINDING_INVALID", "MCP_UNAVAILABLE").contains(code(refused)), refused.toString());
        assertEquals(0, rootsOf(workspace));
        for (String name : List.of("a.ts", "b.ts", "c.ts")) {
            assertNotNull(refusal("claude", hook("claude", owner, workspace, "Write", Map.of("file_path", workspace.resolve(name).toString()), null)),
                    "an unattested start grants no edit permission for " + name);
        }
    }

    @ParameterizedTest
    @CsvSource({"xhigh,,xhigh", "xhigh,high,xhigh", ",high,high", ",,"})
    void claudeRecordsEffortWhenTheModelComesFromTheTranscript(String hookEffort, String transcriptEffort, String expectedEffort,
            @TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        Map<String, Object> issuer = arguments("type", "assistant", "sessionId", owner, "isSidechain", false,
                "message", arguments("model", "a-model-on-no-list", "content", List.of(arguments("type", "tool_use", "id", toolUse, "name", "start_workflow", "input", Map.of()))));
        if (transcriptEffort != null) {
            issuer.put("effort", transcriptEffort);
        }
        String line = MAPPER.writeValueAsString(issuer);
        Files.writeString(transcript, "{\"type\":\"user\"}\n" + line + "\n");
        ObjectNode hookBody = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8));
        hookBody.put("tool_name", "mcp__plugin_sennard_sennard__start_workflow").put("transcript_path", transcript.toString());
        hookBody.remove("model");
        hookBody.remove("effort");
        if (hookEffort != null) {
            hookBody.putObject("effort").put("level", hookEffort);
        }
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.toString().getBytes(StandardCharsets.UTF_8)).status());
        JsonNode started = ok(body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build())));
        assertEquals("RUNNING", started.get("state").asString());
        JsonNode receipt = ok(callAs("claude", "get_workflow_status", arguments("runId", started.get("runId").asString()), owner));
        JsonNode bootstrap = receipt.path("plan").path("bootstrapExecution").path("context");
        assertEquals("a-model-on-no-list", bootstrap.path("model").asString(), bootstrap.toString());
        assertEquals(expectedEffort, bootstrap.path("reasoningEffort").asString(null), bootstrap.toString());
        List<String> policyNotes = new ArrayList<>();
        bootstrap.path("policyNotes").forEach(note -> policyNotes.add(note.asString()));
        assertEquals(expectedEffort == null, policyNotes.contains("HOST_EFFORT_UNREPORTED"), bootstrap.toString());
    }

    /** A refusal for a model the transcript does not give says why it was not found (a code and numbers, never the transcript's text). */
    @Test
    void aBindingRefusedForANotFoundModelSaysWhyTheTranscriptGaveNone(@TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        String line = MAPPER.writeValueAsString(arguments("type", "assistant", "sessionId", owner, "isSidechain", false,
                "message", arguments("content", List.of(arguments("type", "tool_use", "id", toolUse, "name", "start_workflow", "input", Map.of())))));
        Files.writeString(transcript, "{\"type\":\"user\"}\n" + line + "\n");
        ObjectNode hookBody = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8));
        hookBody.put("tool_name", "mcp__plugin_sennard_sennard__start_workflow").put("transcript_path", transcript.toString());
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.toString().getBytes(StandardCharsets.UTF_8)).status());
        JsonNode refused = body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build()));
        assertEquals("BINDING_REQUIRED", code(refused), refused.toString());
        JsonNode observation = refused.path("error").path("details").path("observation");
        assertEquals("HOOK_MODEL_NOT_REPORTED", observation.path("reasonCode").asString(), refused.toString());
        assertEquals("MODEL_FIELD_MISSING", observation.path("transcriptReason").asString(), refused.toString());
        assertTrue(observation.has("linesSeen"), refused.toString());
        assertEquals(0, observation.path("waitedMs").asLong(-1), "the line of the call was in the file: nothing was waited for; " + refused);
    }

    /**
     * A call made in a message with several blocks has no line in the transcript until its tools have finished, so the model behind it is the one the host sent the request
     * with, which the host writes when it sends it. The line of the call is not in the file here; the request record of the main thread is, with another answer's model before it.
     */
    @Test
    void aParallelCallIsObservedFromTheRequestRecord(@TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        Map<String, Object> request = arguments("type", "api-request", "querySource", "repl_main_thread", "sessionId", owner,
                "params", arguments("model", "a-request-model", "output_config", arguments("effort", "high")));
        Map<String, Object> earlierAnswer = arguments("type", "assistant", "sessionId", owner, "isSidechain", false,
                "message", arguments("model", "an-answer-model", "content", List.of(arguments("type", "text", "text", "t"))));
        Files.writeString(transcript, "{\"type\":\"user\"}\n" + MAPPER.writeValueAsString(earlierAnswer) + "\n" + MAPPER.writeValueAsString(request) + "\n");
        ObjectNode hookBody = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8));
        hookBody.put("tool_name", "mcp__plugin_sennard_sennard__start_workflow").put("transcript_path", transcript.toString());
        hookBody.remove("model");
        hookBody.remove("effort");
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.toString().getBytes(StandardCharsets.UTF_8)).status());
        JsonNode started = ok(body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build())));
        assertEquals("RUNNING", started.get("state").asString());
        JsonNode receipt = ok(callAs("claude", "get_workflow_status", arguments("runId", started.get("runId").asString()), owner));
        JsonNode bootstrap = receipt.path("plan").path("bootstrapExecution").path("context");
        assertEquals("a-request-model", bootstrap.path("model").asString(), bootstrap.toString());
        assertEquals("high", bootstrap.path("reasoningEffort").asString(null), bootstrap.toString());
        List<String> notes = new ArrayList<>();
        bootstrap.path("policyNotes").forEach(note -> notes.add(note.asString()));
        assertTrue(notes.contains("MODEL_FROM_REQUEST_RECORD"), bootstrap.toString());
    }

    /**
     * A call by a subagent of a workflow, whose hook gave the effort and the subagent's id ({@code agent_id}) but no model: its own file is under the run's folder of the session's
     * {@code subagents/workflows} ({@code wf_<hex>-<hex>-<n>} is the form of a workflow subagent's id), and holds its request record. The main agent's request records are another thread's.
     */
    private static void workflowSubagentCall(Path transcriptFolder, String owner, String agentId, String toolUse, String hookEffort, String requestEffort, Path transcript) throws Exception {
        Path file = transcriptFolder.resolve(owner).resolve("subagents").resolve("workflows").resolve("run-1").resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(file.getParent());
        Map<String, Object> request = arguments("type", "api-request", "querySource", "agent:builtin:general-purpose", "sessionId", owner, "agentId", agentId, "isSidechain", true,
                "params", arguments("model", "a-subagent-model", "output_config", arguments("effort", requestEffort)));
        Files.writeString(file, MAPPER.writeValueAsString(request) + "\n");
        Map<String, Object> mainRequest = arguments("type", "api-request", "querySource", "repl_main_thread", "sessionId", owner, "params", arguments("model", "the-main-agents-model", "output_config", arguments("effort", "high")));
        Files.writeString(transcript, MAPPER.writeValueAsString(mainRequest) + "\n");
        ObjectNode hookBody = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8));
        hookBody.put("tool_name", "mcp__plugin_sennard_sennard__start_workflow").put("transcript_path", transcript.toString()).put("agent_id", agentId).put("agent_type", "general-purpose");
        hookBody.remove("model");
        hookBody.putObject("effort").put("level", hookEffort);
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.toString().getBytes(StandardCharsets.UTF_8)).status());
    }

    @Test
    void aWorkflowSubagentsCallIsObservedFromItsOwnFilesRequestWithTheEffortOfTheHook(@TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        String agentId = "wf_0a1b2c3d-4e5-6";
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        workflowSubagentCall(transcriptFolder, owner, agentId, toolUse, "high", "high", transcript);
        JsonNode started = ok(body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build())));
        JsonNode bootstrap = ok(callAs("claude", "get_workflow_status", arguments("runId", started.get("runId").asString()), owner)).path("plan").path("bootstrapExecution").path("context");
        assertEquals("a-subagent-model", bootstrap.path("model").asString(), bootstrap.toString());
        assertEquals("high", bootstrap.path("reasoningEffort").asString(null), bootstrap.toString());
        List<String> notes = new ArrayList<>();
        bootstrap.path("policyNotes").forEach(note -> notes.add(note.asString()));
        assertTrue(notes.contains("MODEL_FROM_REQUEST_RECORD"), bootstrap.toString());
    }

    @Test
    void aSubagentsRequestThatNamesAnotherEffortThanTheHookIsRefusedAndSaysWhy(@TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        workflowSubagentCall(transcriptFolder, owner, "wf_0a1b2c3d-4e5-7", toolUse, "low", "high", transcript);
        JsonNode refused = body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build()));
        assertEquals("BINDING_REQUIRED", code(refused), refused.toString());
        JsonNode observation = refused.path("error").path("details").path("observation");
        assertEquals("REQUEST_EFFORT_MISMATCH", observation.path("transcriptReason").asString(), refused.toString());
        assertEquals(250, observation.path("waitedMs").asLong(-1), refused.toString());
    }

    /** The line of a subagent's call is in the subagent's own file (under a run's folder): the line is found at the first look, so nothing is waited for, whatever the line says (here: no model). */
    @Test
    void aWorkflowSubagentsCallLineInItsOwnFileIsFoundWithoutWaiting(@TempDir Path transcriptFolder) throws Exception {
        Path workspace = product();
        String owner = session();
        String toolUse = "toolu_" + UUID.randomUUID();
        String agentId = "wf_0a1b2c3d-4e5-8";
        Path transcript = Files.createFile(transcriptFolder.resolve("transcript.jsonl"));
        Path file = transcriptFolder.resolve(owner).resolve("subagents").resolve("workflows").resolve("run-1").resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(file.getParent());
        Map<String, Object> issuer = arguments("type", "assistant", "sessionId", owner, "agentId", agentId, "isSidechain", true,
                "message", arguments("content", List.of(arguments("type", "tool_use", "id", toolUse, "name", "start_workflow", "input", Map.of()))));
        Files.writeString(file, MAPPER.writeValueAsString(issuer) + "\n");
        ObjectNode hookBody = (ObjectNode) MAPPER.readTree(new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", owner, toolUse), StandardCharsets.UTF_8));
        hookBody.put("tool_name", "mcp__plugin_sennard_sennard__start_workflow").put("transcript_path", transcript.toString()).put("agent_id", agentId).put("agent_type", "general-purpose");
        hookBody.remove("model");
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", hookBody.toString().getBytes(StandardCharsets.UTF_8)).status());
        JsonNode refused = body(client("claude").callTool(McpSchema.CallToolRequest.builder("start_workflow").arguments(OperationKeys.withKey("start_workflow", startAll(workspace))).meta(SessionServerSupport.meta("windows", "claude", owner, toolUse)).build()));
        assertEquals("BINDING_REQUIRED", code(refused), refused.toString());
        JsonNode observation = refused.path("error").path("details").path("observation");
        assertEquals("MODEL_FIELD_MISSING", observation.path("transcriptReason").asString(), refused.toString());
        assertEquals(0, observation.path("waitedMs").asLong(-1), "the line of the call was in the subagent's file: nothing was waited for; " + refused);
    }

    private static long rowsOf(String table, String where, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where).params(params).query(Long.class).single());
    }

    /**
     * The failure that left a run behind: the baseline of a start was written after the start committed, so a baseline that could not be written (in the field, the transaction time of a
     * workspace with tens of thousands of files) answered a failure and left the run, in its planning phase. The baseline is part of the start's transaction now. The write is made to
     * fail by the database itself (a check on the baseline table that the one file of this workspace breaks); the start must leave no root, lease, run or journal row, and the same
     * start, once the database accepts the file, must open (a root left behind would refuse it as a conflict on its write surface).
     */
    @Test
    void aStartWhoseBaselineCannotBeWrittenLeavesNoRootLeaseRunOrJournal() throws Exception {
        Path workspace = product();
        Files.writeString(workspace.resolve("poison-file.txt"), "the baseline row of this file is refused");
        String name = "%" + workspace.getFileName() + "%";
        String owner = session();
        Map<String, Object> request = start(workspace, List.of("."));
        SessionServerSupport.update(server, "ALTER TABLE sennard.baseline_file ADD CONSTRAINT start_atomicity_probe CHECK (path NOT LIKE '%poison-file%')");
        try {
            JsonNode failed = call("start_workflow", request, owner);
            assertFalse(failed.path("ok").asBoolean(true), failed.toString());
            assertEquals(0, rootsOf(workspace), "no root");
            assertEquals(0, rowsOf("sennard.convergence_leases", "proposal_json LIKE ?", name), "no lease");
            assertEquals(0, rowsOf("sennard.workflow_runs", "receipt_json LIKE ?", name), "no run");
            assertEquals(0, rowsOf("sennard.write_root", "path LIKE ?", name), "no write root of the journal");
            assertEquals(0, rowsOf("sennard.plan_file", "path LIKE ?", name), "no plan of the journal");
        } finally {
            SessionServerSupport.update(server, "ALTER TABLE sennard.baseline_file DROP CONSTRAINT start_atomicity_probe");
        }
        JsonNode started = ok(call("start_workflow", request, owner));
        String runId = started.get("runId").asString();
        assertEquals("EXECUTING", started.path("changeJournal").path("phase").asString(), started.toString());
        assertEquals(List.of(workspace.resolve("poison-file.txt")), journal().baseline(runId).stream().map(ChangeJournalService.BaselineEntry::path).toList());
    }

    /**
     * A workspace of more files than one batch of the baseline's rows holds ({@code BaselineRows.BATCH} is 500): every file is in the baseline of the run the start opened, and the run
     * is executing in the same answer, with the baseline written by the start's own transaction.
     */
    @Test
    void aStartOverAWorkspaceOfManyFilesTakesTheBaselineOfEveryFileInItsOwnTransaction() throws Exception {
        Path workspace = product();
        int files = 1200;
        for (int i = 0; i < files; i++) {
            Path file = workspace.resolve("dir-" + i % 12).resolve("file-" + i + ".txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content " + i);
        }
        JsonNode started = ok(call("start_workflow", start(workspace, List.of(".")), session()));
        String runId = started.get("runId").asString();
        assertEquals("EXECUTING", started.path("changeJournal").path("phase").asString(), started.toString());
        assertTrue(started.path("changeJournal").path("unreadable").isEmpty(), started.toString());
        assertEquals(files, journal().baseline(runId).size());
        assertEquals(files, rowsOf("sennard.observed_file", "workflow_id = ?", runId));
    }
}
