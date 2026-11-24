// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public HTTP hooks and MCP on the real test database. Host envelopes are recordings, not model invocations. */
@Tag("business-db")
@Execution(ExecutionMode.SAME_THREAD)
class WorkflowCompletionHookServerTest {
    private static RunningServer server;

    record Session(TurnSaveCheckServerTest.Host host, String id, Path workspace) {
    }

    static Stream<TurnSaveCheckServerTest.Host> hosts() {
        return Stream.of(new TurnSaveCheckServerTest.Host("codex", "codex", "UserPromptSubmit"),
                new TurnSaveCheckServerTest.Host("claude", "claude-code", "UserPromptSubmit"),
                new TurnSaveCheckServerTest.Host("agy", "antigravity", "PreInvocation"));
    }


    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
    }

    @AfterAll
    static void close() {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void anOperationWithoutStagesMustBeExplicitlyFinalized(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        JsonNode opened = operation(session);
        String runId = opened.path("runId").asString();
        String continued = continuation(session, stop(session, false));
        assertEquals(pendingWorkflows(1), continued);
        assertFalse(continued.contains(runId), continued);
        // The first Stop leaves the unfinished operation running.
        JsonNode status = call(session, "get_workflow_status", Map.of("runId", runId, "detail", "compact"));
        assertEquals("RUNNING", status.path("state").asString(), status.toString());
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void anOperationLeftRunningIsBlockedWhenItsStopRechecksRunOut(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        String runId = operation(session).path("runId").asString();

        JsonNode ended = exhaustStopRechecks(session, runId);
        String reason = DomainServerSupport.englishMessage(server, "hooks.phaseCheck.exhausted", Map.of("runs", runId));
        if (host.agy()) {
            assertEquals(server.bean(JsonMapper.class).createObjectNode(), ended);
            String notice = DomainServerSupport.englishMessage(server, "hooks.workflowGate.deferredStop", Map.of("reason", reason));
            JsonNode nextInput = prompt(session, "Handle the next request");
            assertEquals(notice, nextInput.path("injectSteps").path(0).path("ephemeralMessage").asString().stripTrailing(), nextInput.toString());
            JsonNode laterInput = prompt(session, "Handle a later request");
            assertFalse(laterInput.toString().contains("Sennard stopped processing the previous input"), laterInput.toString());
        } else {
            assertTrue(ended.path("continue").isBoolean(), ended.toString());
            assertFalse(ended.path("continue").asBoolean(), ended.toString());
            assertEquals(reason, ended.path("stopReason").asString(), ended.toString());
        }
    }

    static Stream<TurnSaveCheckServerTest.Host> hostsReportingContinuedStop() {
        return hosts().filter(host -> !host.agy());
    }

    @ParameterizedTest
    @MethodSource("hostsReportingContinuedStop")
    void anAlreadyActiveStopDoesNotSpendAnotherRecheck(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        String runId = operation(session).path("runId").asString();

        assertEquals(pendingWorkflows(1), continuation(session, stop(session, false)));
        JsonNode before = status(session, runId, "compact");
        assertEquals("RUNNING", before.path("state").asString(), before.toString());
        assertEquals(server.bean(JsonMapper.class).createObjectNode(), stop(session, true));
        assertEquals(before, status(session, runId, "compact"));
        assertEquals(pendingWorkflows(1), continuation(session, stop(session, false)));
        assertEquals(finalRecheck(runId), continuation(session, stop(session, false)));
        assertRunning(session, runId);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aRunOfAnotherRequestOrAnotherSessionIsUnaffectedByThisStop(TurnSaveCheckServerTest.Host host) throws Exception {
        Session mine = session(host);
        Session other = session(host);
        String theirs = operation(other).path("runId").asString();
        String earlier = operation(mine).path("runId").asString();
        JsonNode theirStatus = status(other, theirs, "compact");
        JsonNode earlierStatus = status(mine, earlier, "compact");
        assertEquals("RUNNING", theirStatus.path("state").asString(), theirStatus.toString());
        assertEquals("RUNNING", earlierStatus.path("state").asString(), earlierStatus.toString());

        prompt(mine);
        String current = operation(mine).path("runId").asString();
        exhaustStopRechecks(mine, current);

        assertEquals(theirStatus, status(other, theirs, "compact"), "another session's run is untouched");
        assertEquals(earlierStatus, status(mine, earlier, "compact"), "the current request did not take the earlier run");
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aFinishedRequestDoesNotNeedAReplacementWorkflow(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        JsonNode opened = operation(session);
        JsonNode finished = call(session, "finalize_workflow", Map.of("runId", opened.path("runId").asString(), "expectedRevision", opened.path("revision").asLong()));
        assertEquals("PASSED", finished.path("state").asString(), finished.toString());
        JsonNode root = call(session, "get_convergence_status", Map.of("rootId", opened.path("rootId").asString(), "detail", "compact"));
        assertEquals("COMPLETED", root.path("root").path("state").asString(), root.toString());
        assertEquals("", continuation(session, stop(session, false)), "A finalized request is already served");
        prompt(session);
        String next = continuation(session, stop(session, false));
        assertTrue(next.contains("workflow gate"), next);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void passingEveryStageStillRequiresFinalization(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        JsonNode opened = call(session, "start_workflow", Map.of("goal", "A change awaiting its final disposition", "workspace", session.workspace().toString(),
                "stages", List.of("minimal-implementation"), "skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "test receipt")),
                "acceptanceCriteria", List.of("The change is complete"), "writeTargets", List.of("done.txt"), "changePlan", List.of("done.txt")));
        String runId = opened.path("runId").asString();
        JsonNode run = call(session, "get_workflow_status", Map.of("runId", runId, "detail", "full"));
        JsonNode recorded = call(session, "record_stage_result",
                DomainServerSupport.passedStageArguments(server, host.label(), session.id(), run));
        assertEquals("RUNNING", recorded.path("state").asString(), recorded.toString());
        assertEquals(1, recorded.path("stageResults").size(), recorded.toString());
        assertEquals("PASSED", recorded.path("stageResults").path(0).path("state").asString(), recorded.toString());
        String continued = continuation(session, stop(session, false));
        assertEquals(pendingWorkflows(1), continued);
        assertFalse(continued.contains(runId), continued);
        assertFalse(continued.contains("stages not yet passed"), continued);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aPausedWorkflowWaitsWithoutResuming(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        JsonNode opened = operation(session);
        String runId = opened.path("runId").asString();
        JsonNode paused = call(session, "pause_workflow", Map.of("runId", runId, "expectedRevision", opened.path("revision").asLong(),
                "reason", "Waiting for the user", "resumeCondition", "The user explicitly asks to resume", "operationKey", OperationKeys.fresh()));
        assertEquals("PAUSED", paused.path("state").asString(), paused.toString());
        assertEquals("", continuation(session, stop(session, false)));
        JsonNode status = call(session, "get_workflow_status", Map.of("runId", runId, "detail", "compact"));
        assertEquals("PAUSED", status.path("state").asString(), status.toString());
        assertEquals(paused.path("revision").asLong(), status.path("revision").asLong());
        prompt(session);
        call(session, "start_inquiry_workflow", Map.of("goal", "Explain why work is paused", "workspace", session.workspace().toString()));
        assertEquals("", continuation(session, stop(session, false)), "An explanation does not resume the paused operation");
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void anEarlierOperationDoesNotHijackAnotherRequestsTurnEnd(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        JsonNode earlier = operation(session);
        prompt(session);
        call(session, "start_inquiry_workflow", Map.of("goal", "Explain the current status", "workspace", session.workspace().toString()));
        String next = continuation(session, stop(session, false));
        assertFalse(next.contains(earlier.path("runId").asString()), next);
        assertFalse(next.contains("finalize_workflow"), next);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void multipleRunningOperationsAreCountedWithoutTheirDetails(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = session(host);
        operation(session);
        operation(session);
        assertEquals(pendingWorkflows(2), continuation(session, stop(session, false)));
    }

    private static Session session(TurnSaveCheckServerTest.Host host) throws Exception {
        String id = SessionServerSupport.uniqueSession();
        Session session = new Session(host, id, Files.createDirectories(Path.of("build", "workflow-completion", id).toAbsolutePath()));
        post(session, "SessionStart", body -> { });
        prompt(session);
        return session;
    }

    private static void prompt(Session session) throws Exception {
        prompt(session, "Handle this request");
    }

    private static JsonNode prompt(Session session, String input) throws Exception {
        if (session.host().label().equals("agy")) {
            DomainServerSupport.observeAgyRoot(server, session.id());
        }
        JsonNode answer = post(session, session.host().promptEvent(), session.host().prompt(input));
        // These fixtures satisfy independent recap/summary/save gates so only lifecycle behavior is observed.
        String promptId = (String) SessionServerSupport.row(server,
                "SELECT user_prompt_id FROM sennard.user_prompt WHERE host = ? AND session_id = ? ORDER BY received_at DESC FETCH FIRST 1 ROWS ONLY",
                session.host().sessionHost(), session.id()).get("user_prompt_id");
        SessionServerSupport.update(server, "INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (?, ?, ?, 0)",
                promptId, "lifecycle fixture; no model invoked", Timestamps.toTimestamp(server.bean(Clock.class).instant()));
        DomainServerSupport.writeSessionSummary(server, session.host().label(), session.id(), "lifecycle fixture " + session.id());
        call(session, "mark_turn_unchanged", DomainServerSupport.arguments("operationKey", "lifecycle-mark-" + UUID.randomUUID()));
        return answer;
    }

    private static JsonNode exhaustStopRechecks(Session session, String runId) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(pendingWorkflows(1), continuation(session, stop(session, false)));
            assertRunning(session, runId);
        }
        assertEquals(finalRecheck(runId), continuation(session, stop(session, false)));
        assertRunning(session, runId);

        JsonNode ended = stop(session, false);
        assertEquals("", continuation(session, ended));
        JsonNode compact = status(session, runId, "compact");
        assertEquals("BLOCKED", compact.path("state").asString(), compact.toString());
        assertEquals(1, compact.path("blockerCount").asInt(), compact.toString());
        JsonNode full = status(session, runId, "full");
        assertEquals("BLOCKED", full.path("state").asString(), full.toString());
        JsonNode blockers = full.path("blockers");
        assertEquals(1, blockers.size(), full.toString());
        assertEquals("STOP_RECHECK_LIMIT_EXHAUSTED", blockers.path(0).asString(), full.toString());
        return ended;
    }

    private static String finalRecheck(String runId) {
        String warning = DomainServerSupport.englishMessage(server, "hooks.phaseCheck.lastRecheck", Map.of("runs", runId));
        return pendingWorkflows(1) + "\n" + warning;
    }

    private static void assertRunning(Session session, String runId) throws IOException {
        JsonNode compact = status(session, runId, "compact");
        assertEquals("RUNNING", compact.path("state").asString(), compact.toString());
    }

    private static JsonNode status(Session session, String runId, String detail) throws IOException {
        return call(session, "get_workflow_status", Map.of("runId", runId, "detail", detail));
    }

    private static JsonNode operation(Session session) throws IOException {
        String workspace = session.workspace().toString();
        return call(session, "start_operation_workflow", DomainServerSupport.operationArguments("Inspect the test operation",
                workspace, workspace, "session-board", "Inspect shared test work"));
    }

    private static String pendingWorkflows(int count) {
        return DomainServerSupport.englishMessage(server, "hooks.phaseCheck.summary", Map.of("count", count));
    }

    private static JsonNode call(Session session, String tool, Map<String, Object> args) throws IOException {
        JsonNode result = ChangeWorkflows.callAs(server, session.host().label(), session.id(), tool, args);
        assertTrue(result.path("ok").asBoolean(), tool + ": " + result);
        return result.path("data");
    }

    private static JsonNode post(Session session, String event, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", session.host().label(), event, null, session.id(), body -> {
            if (session.host().agy()) {
                body.putArray("workspacePaths").add(session.workspace().toString());
            } else {
                body.put("cwd", session.workspace().toString());
            }
            edit.accept(body);
        });
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return server.bean(JsonMapper.class).readTree(response.body());
    }

    private static JsonNode stop(Session session, boolean continued) throws IOException {
        return post(session, "Stop", session.host().stop(continued));
    }

    private static String continuation(Session session, JsonNode result) {
        return session.host().continuation(result).orElse("");
    }
}
