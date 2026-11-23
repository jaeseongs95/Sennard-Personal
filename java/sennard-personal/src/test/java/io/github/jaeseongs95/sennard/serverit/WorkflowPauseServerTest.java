// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit pause/resume through the public MCP boundary and its real transaction store. */
@Tag("business-db")
class WorkflowPauseServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void close() {
        if (server != null) {
            server.close();
        }
    }

    record Run(String session, Path workspace, JsonNode opened) {
        String id() { return opened.path("runId").asString(); }
        String rootId() { return opened.path("rootId").asString(); }
    }

    private static Run run() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, session, body -> { });
        Path workspace = Files.createDirectories(Path.of("build", "workflow-pause", UUID.randomUUID().toString()).toAbsolutePath());
        return new Run(session, workspace, ChangeWorkflows.open(server, session, workspace, List.of("a.txt")));
    }

    private static JsonNode call(Run run, String tool, Map<String, Object> args) throws Exception {
        return ChangeWorkflows.callAs(server, "codex", run.session(), tool, args);
    }

    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(), result.toString());
        return result.path("data");
    }

    private static Map<String, Object> change(Run run, long revision) {
        return new LinkedHashMap<>(Map.of("runId", run.id(), "expectedRevision", revision, "operationKey", OperationKeys.fresh()));
    }

    private static Map<String, Object> pause(Run run, long revision) {
        Map<String, Object> args = change(run, revision);
        args.put("reason", "Waiting for the requested input");
        args.put("resumeCondition", "The user supplies the input and explicitly resumes this run");
        return args;
    }

    private static JsonNode status(Run run, String detail) throws Exception {
        return ok(call(run, "get_workflow_status", Map.of("runId", run.id(), "detail", detail)));
    }

    private static JsonNode root(Run run) throws Exception {
        return ok(call(run, "get_convergence_status", Map.of("rootId", run.rootId()))).path("root");
    }

    private static String notices(Run run) {
        return notices(run, "get_workflow_status", Map.of("runId", run.id(), "detail", "compact"));
    }

    private static String notices(Run run, String tool, Map<String, Object> args) {
        Map<String, Object> meta = new LinkedHashMap<>(SessionServerSupport.meta("windows", "codex", run.session(), "none"));
        meta.put("callId", "exec-" + UUID.randomUUID());
        try (McpSyncClient client = DomainServerSupport.client(server.port, "codex")) {
            var result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).meta(meta).build());
            assertFalse(Boolean.TRUE.equals(result.isError()), result.toString());
            return result.content().stream().skip(1).filter(McpSchema.TextContent.class::isInstance).map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text).reduce("", (a, b) -> a + "\n" + b);
        }
    }

    @Test
    void sessionListingRetainsTerminalRunsAndFiltersRootsAndSessions() throws Exception {
        Run owner = run();
        Run other = run();
        String tool = "list_session_workflows";
        JsonNode before = ok(call(owner, tool, Map.of()));
        assertEquals(1, before.path("runs").size());
        assertEquals(owner.id(), before.path("runs").get(0).path("runId").asString());
        assertEquals("RUNNING", before.path("runs").get(0).path("state").asString());
        assertEquals(1, before.path("openRoots").size());
        assertEquals(owner.rootId(), before.path("openRoots").get(0).path("rootId").asString());
        assertEquals(other.id(), ok(call(other, tool, Map.of())).path("runs").get(0).path("runId").asString());

        JsonNode currentRoot = root(owner);
        ok(call(owner, "release_convergence_root", Map.of("rootId", owner.rootId(), "expectedRevision", currentRoot.path("revision").asLong(), "reason", "The user cancelled the work")));
        JsonNode after = ok(call(owner, tool, Map.of()));
        assertEquals(1, after.path("runs").size());
        assertEquals(owner.id(), after.path("runs").get(0).path("runId").asString());
        assertEquals("BLOCKED", after.path("runs").get(0).path("state").asString());
        assertTrue(after.path("openRoots").isArray());
        assertTrue(after.path("openRoots").isEmpty());

        String emptySession = SessionServerSupport.uniqueSession();
        DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, emptySession, body -> { });
        JsonNode empty = ok(ChangeWorkflows.callAs(server, "codex", emptySession, tool, Map.of()));
        assertTrue(empty.path("runs").isArray());
        assertTrue(empty.path("runs").isEmpty());
        assertTrue(empty.path("openRoots").isArray());
        assertTrue(empty.path("openRoots").isEmpty());
        assertEquals("INVALID_INPUT", call(owner, tool, Map.of("sessionId", other.session())).path("error").path("code").asString());

        try (McpSyncClient client = DomainServerSupport.client(server.port, "codex")) {
            JsonNode unbound = DomainServerSupport.callAsSent(client, tool, Map.of(), Map.of());
            assertEquals("BINDING_REQUIRED", unbound.path("error").path("code").asString(), unbound.toString());
        }
    }

    @Test
    void sessionListingRefusesAnIdSharedAcrossHosts() throws Exception {
        Run owner = run();
        String tool = "list_session_workflows";
        assertEquals(owner.id(), ok(call(owner, tool, Map.of())).path("runs").get(0).path("runId").asString());
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "claude", "SessionStart", null, owner.session(), body -> { }).status());

        for (String host : List.of("claude", "codex")) {
            JsonNode result = ChangeWorkflows.callAs(server, host, owner.session(), tool, Map.of());
            assertEquals("BINDING_INVALID", result.path("error").path("code").asString(), result.toString());
            assertEquals("SESSION_CORRELATION_AMBIGUOUS", result.path("error").path("details").path("reason").asString(), result.toString());
            assertTrue(result.path("data").isNull(), result.toString());
        }
    }

    @Test
    void pausePersistsWithoutEndingTheAttemptAndOnlyExplicitResumeRestoresExecution() throws Exception {
        Run run = run();
        JsonNode before = status(run, "full");
        Map<String, Object> args = pause(run, before.path("revision").asLong());
        JsonNode pausedEnvelope = call(run, "pause_workflow", args);
        JsonNode paused = ok(pausedEnvelope);
        assertEquals("PAUSED", paused.path("state").asString());
        assertEquals(args.get("reason"), paused.path("pause").path("reason").asString());
        assertEquals(args.get("resumeCondition"), paused.path("pause").path("resumeCondition").asString());
        assertFalse(paused.path("pause").path("pausedAt").asString().isBlank());
        assertEquals(before.path("stageResults"), paused.path("stageResults"));
        assertEquals(before.path("plan").path("currentStageId"), paused.path("plan").path("currentStageId"));
        assertEquals(before.path("plan").path("stages"), paused.path("plan").path("stages"));
        assertEquals(pausedEnvelope, call(run, "pause_workflow", args), "same operation replays its original answer");
        assertEquals(paused.path("pause"), status(run, "compact").path("pause"));
        assertEquals("OPEN", root(run).path("state").asString());
        assertEquals(1L, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.convergence_attempts WHERE run_id = ?", run.id()));
        assertEquals(null, SessionServerSupport.row(server,
                "SELECT outcome_json FROM sennard.convergence_attempts WHERE run_id = ?", run.id()).get("outcome_json"));
        String pausedNotice = notices(run);
        assertTrue(pausedNotice.contains("resume_workflow"), pausedNotice);
        assertTrue(pausedNotice.contains(run.id()), pausedNotice);
        assertTrue(pausedNotice.contains("expectedRevision=" + paused.path("revision").asLong()), pausedNotice);
        Run other = run();
        JsonNode conflict = call(other, "start_workflow", Map.of("goal", "Competing paused scope", "stages", List.of("minimal-implementation"),
                "skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "conflict fixture")), "acceptanceCriteria", List.of("Works"),
                "workspace", run.workspace().toString(), "writeTargets", List.of("a.txt")));
        assertEquals("ROOT_CONFLICT", conflict.path("error").path("code").asString(), conflict.toString());
        assertFalse(call(run, "resume_workflow", Map.of("runId", run.id())).path("ok").asBoolean());
        assertFalse(call(run, "finalize_workflow", change(run, paused.path("revision").asLong())).path("ok").asBoolean());
        Map<String, Object> stage = change(run, paused.path("revision").asLong());
        Map<String, Object> stageError = Map.of("code", "GATE_FAILED", "message", "The implementation check failed.", "details", Map.of());
        stage.put("schemaVersion", "1.0.0");
        stage.put("stageId", paused.path("plan").path("currentStageId").asString());
        stage.put("state", "FAILED");
        stage.put("output", Map.of("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "failed"), "artifacts", List.of(), "error", stageError));
        stage.put("evidence", List.of());
        stage.put("findings", List.of());
        stage.put("blockers", List.of());
        stage.put("error", stageError);
        JsonNode refusedStage = call(run, "record_stage_result", stage);
        assertEquals("INVALID_TRANSITION", refusedStage.path("error").path("code").asString(), refusedStage.toString());
        assertEquals("PAUSED", refusedStage.path("error").path("details").path("state").asString(), refusedStage.toString());
        JsonNode pausedRoot = root(run);
        JsonNode readyPlan = ok(call(run, "plan_workflow", DomainServerSupport.MAPPER.convertValue(pausedRoot.path("taskEnvelope"), new TypeReference<Map<String, Object>>() { })));
        assertEquals("READY", readyPlan.path("state").asString(), readyPlan.toString());
        JsonNode refusedAttempt = call(run, "claim_workflow_attempt", Map.of("schemaVersion", "1.0.0", "rootId", run.rootId(),
                "expectedRevision", pausedRoot.path("revision").asLong(), "plan", DomainServerSupport.MAPPER.convertValue(readyPlan, new TypeReference<Map<String, Object>>() { }), "outputTargets", List.of("a.txt")));
        assertEquals("LEASE_CONFLICT", refusedAttempt.path("error").path("code").asString(), refusedAttempt.toString());
        assertEquals(pausedRoot.path("currentEpoch"), refusedAttempt.path("error").path("details").path("epoch"), refusedAttempt.toString());
        assertEquals(pausedRoot, root(run), "a refused attempt cannot consume a root revision or lease");
        assertEquals(paused, status(run, "full"), "refused stage recording and attempt claim preserve the paused run");
        Map<String, Object> resumeArgs = change(run, paused.path("revision").asLong());
        JsonNode resumedEnvelope = call(run, "resume_workflow", resumeArgs);
        JsonNode resumed = ok(resumedEnvelope);
        assertEquals("RUNNING", resumed.path("state").asString());
        assertEquals(paused.path("revision").asLong() + 1, resumed.path("revision").asLong());
        assertEquals(resumedEnvelope, call(run, "resume_workflow", resumeArgs));
        assertEquals("RUNNING", status(run, "full").path("state").asString());
        assertTrue(call(run, "resume_workflow", Map.of("runId", run.id())).path("ok").asBoolean(), "legacy running selection remains valid");
        assertEquals(null, SessionServerSupport.row(server,
                "SELECT outcome_json FROM sennard.convergence_attempts WHERE run_id = ?", run.id()).get("outcome_json"));
        // The same schema-valid result is accepted after resume, so its earlier rejection was the PAUSED gate.
        stage.put("expectedRevision", resumed.path("revision").asLong());
        stage.put("operationKey", OperationKeys.fresh());
        assertEquals("FAILED", ok(call(run, "record_stage_result", stage)).path("state").asString());
    }

    @Test
    void staleOrInvalidPauseAndAnotherSessionsResumeCannotChangeTheRun() throws Exception {
        Run run = run();
        long revision = status(run, "full").path("revision").asLong();
        Map<String, Object> invalid = pause(run, revision);
        invalid.put("reason", " ");
        assertFalse(call(run, "pause_workflow", invalid).path("ok").asBoolean());
        assertFalse(call(run, "pause_workflow", pause(run, revision + 1)).path("ok").asBoolean());
        JsonNode paused = ok(call(run, "pause_workflow", pause(run, revision)));
        Run other = run();
        assertFalse(call(other, "resume_workflow", change(run, paused.path("revision").asLong())).path("ok").asBoolean());
        assertEquals(paused.path("revision"), status(run, "full").path("revision"));
        // A refused operation does not reserve the key; a successful one does.
        Map<String, Object> original = change(run, paused.path("revision").asLong());
        ok(call(run, "resume_workflow", original));
        Map<String, Object> conflicting = new LinkedHashMap<>(original);
        conflicting.put("expectedRevision", paused.path("revision").asLong() + 1);
        assertEquals("REQUEST_CONFLICT", call(run, "resume_workflow", conflicting).path("error").path("code").asString());
    }

    @Test
    void aPausedRunCannotResumeThroughAUserGate() throws Exception {
        Run run = run();
        JsonNode paused = ok(call(run, "pause_workflow", pause(run, status(run, "full").path("revision").asLong())));
        // A committed administrative gate is represented at the store boundary; this test owns this UUID root only.
        ObjectNode gated = (ObjectNode) root(run).deepCopy();
        gated.put("state", "NEEDS_USER").put("revision", gated.path("revision").asLong() + 1);
        SessionServerSupport.update(server, "UPDATE sennard.convergence_roots SET state = ?, revision = ?, root_json = ? WHERE root_id = ?",
                "NEEDS_USER", gated.path("revision").asLong(), DomainServerSupport.MAPPER.writeValueAsString(gated), run.rootId());
        JsonNode refused = call(run, "resume_workflow", change(run, paused.path("revision").asLong()));
        assertEquals("INVALID_TRANSITION", refused.path("error").path("code").asString(), refused.toString());
        assertEquals(paused, status(run, "full"));
    }

    @Test
    void statusExplainsCompletionCancellationAndAnEndedAttempt() throws Exception {
        Run run = run();
        assertFalse(notices(run).contains("finalize_workflow"), "unfinished stages must not be called passed");
        JsonNode aborted = ok(call(run, "abort_workflow", change(run, status(run, "full").path("revision").asLong())));
        assertEquals("BLOCKED", aborted.path("state").asString());
        String ended = notices(run);
        assertTrue(ended.contains("get_convergence_status"), ended);
        assertTrue(ended.contains("claim_workflow_attempt"), ended);
        assertTrue(ended.contains("OPEN"), ended);
        String session = SessionServerSupport.uniqueSession();
        DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, session, body -> { });
        Path workspace = Files.createDirectories(Path.of("build", "workflow-pause", UUID.randomUUID().toString()).toAbsolutePath());
        Run operation = new Run(session, workspace, ok(ChangeWorkflows.callAs(server, "codex", session, "start_operation_workflow", Map.of(
                "goal", "No-stage operation", "target", workspace.toString(), "workspace", workspace.toString(),
                "skills", List.of(Map.of("skillId", "session-board", "reason", "operation fixture"))))));
        String finish = notices(operation);
        assertTrue(finish.contains("finalize_workflow"), finish);
        assertTrue(finish.contains(operation.id()), finish);
        String preparation = "read that environment's guide and the script of its last successful run";
        assertFalse(finish.contains(preparation), finish);
        JsonNode paused = ok(call(operation, "pause_workflow", pause(operation, status(operation, "full").path("revision").asLong())));
        String resumed = notices(operation, "resume_workflow", change(operation, paused.path("revision").asLong()));
        assertTrue(resumed.contains(preparation), resumed);
        ok(call(operation, "finalize_workflow", change(operation, status(operation, "full").path("revision").asLong())));
        String completed = notices(operation);
        assertTrue(completed.contains("PASSED"), completed);
        assertTrue(completed.contains("COMPLETED"), completed);
        JsonNode currentRoot = root(run);
        ok(call(run, "release_convergence_root", Map.of("rootId", run.rootId(), "expectedRevision", currentRoot.path("revision").asLong(), "reason", "Cancel after stopping the attempt")));
        assertTrue(notices(run).contains("ABANDONED"));
    }

    @Test
    void releasingPausedWorkCancelsTheRootAndFreesItsScope() throws Exception {
        Run run = run();
        JsonNode paused = ok(call(run, "pause_workflow", pause(run, status(run, "full").path("revision").asLong())));
        JsonNode currentRoot = root(run);
        ok(call(run, "release_convergence_root", Map.of("rootId", run.rootId(), "expectedRevision", currentRoot.path("revision").asLong(), "reason", "The user cancelled this work")));
        assertEquals("ABANDONED", root(run).path("state").asString());
        assertEquals("BLOCKED", status(run, "full").path("state").asString());
        assertFalse(call(run, "resume_workflow", change(run, paused.path("revision").asLong() + 1)).path("ok").asBoolean());
        String other = SessionServerSupport.uniqueSession();
        DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, other, body -> { });
        assertFalse(ChangeWorkflows.open(server, other, run.workspace(), List.of("a.txt")).path("runId").asString().isBlank());
    }

    @Test
    void resumeAndCancellationRaceAsOneRootAndRunTransaction() throws Exception {
        Run run = run();
        JsonNode paused = ok(call(run, "pause_workflow", pause(run, status(run, "full").path("revision").asLong())));
        JsonNode currentRoot = root(run);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var resume = executor.submit(() -> { barrier.await(); return call(run, "resume_workflow", change(run, paused.path("revision").asLong())); });
            var cancel = executor.submit(() -> { barrier.await(); return call(run, "release_convergence_root", Map.of("rootId", run.rootId(), "expectedRevision", currentRoot.path("revision").asLong(), "reason", "Cancel raced with resume")); });
            JsonNode a = resume.get(30, TimeUnit.SECONDS);
            JsonNode b = cancel.get(30, TimeUnit.SECONDS);
            assertEquals(1, (a.path("ok").asBoolean() ? 1 : 0) + (b.path("ok").asBoolean() ? 1 : 0), a + " / " + b);
            assertEquals(a.path("ok").asBoolean() ? "RUNNING" : "BLOCKED", status(run, "full").path("state").asString());
            assertEquals(a.path("ok").asBoolean() ? "OPEN" : "ABANDONED", root(run).path("state").asString());
        }
    }
}
