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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prepared against HEAD 8857; NOT RUN by its author. Real HTTP hook/MCP/PG paths,
 * with recorded host envelopes edited for this test's own sessions.
 * observe* methods cover the unchanged controls; contract* is the user-stop
 * requirement oracle. The former pending-stage baseline is logged separately,
 * without asserting the continuation that this requirement rejects.
 * The stop sentence below is constructed test input, not the missing original prompt.
 * No model response or natural-language stop parser is supplied.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class UserStopHookServerTest {
    private static final Logger LOG = LoggerFactory.getLogger(UserStopHookServerTest.class);
    private static final String SAVE_CHECK = "[Sennard save check]";
    private static final String STAGE_CHECK = "[Sennard stage check]";
    private static final String WORK_CHECK = "[Sennard turn check]";
    private static final String STOP_INPUT = "지금 작업을 중단하고 내 다음 지시를 기다려.";
    private static final String ORDINARY_INPUT = "현재 작업 상태를 설명해 줘.";
    private static final TurnSaveCheckServerTest.Host CLAUDE = new TurnSaveCheckServerTest.Host("claude", "claude-code", "UserPromptSubmit");
    private static final TurnSaveCheckServerTest.Host CODEX = new TurnSaveCheckServerTest.Host("codex", "codex", "UserPromptSubmit");
    private static final TurnSaveCheckServerTest.Host AGY = new TurnSaveCheckServerTest.Host("agy", "antigravity", "PreInvocation");
    private static final Map<String, Object> PAUSED_CORE = Map.of(
            "objective", "작업을 멈추고 사용자의 다음 지시를 기다린다.",
            "completionCriteria", List.of("사용자 재개 지시 전 작업하지 않는다."),
            "constraints", List.of("사용자가 명시적으로 중단했다."),
            "decisions", List.of("사용자의 다음 명시 지시를 기다린다."),
            "progress", List.of(), "blockers", List.of(), "nextActions", List.of());

    private static RunningServer server;

    record Session(TurnSaveCheckServerTest.Host host, String id, Path workspace) {
    }

    record Pending(Session session, String runId) {
    }

    static Stream<TurnSaveCheckServerTest.Host> hosts() {
        return Stream.of(CLAUDE, CODEX, AGY);
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void observeTextStopAndOrdinaryPrompt(TurnSaveCheckServerTest.Host host) throws Exception {
        // Different sessions avoid one prompt inheriting the previous turn's warning.
        for (String input : List.of(STOP_INPUT, ORDINARY_INPUT)) {
            Session session = started(host);
            prompt(session, input);
            inquiry(session);
            JsonNode answer = stop(session, false);
            String continued = continuation(session, answer).orElseThrow(() -> new AssertionError(answer.toString()));
            assertTrue(continued.contains(SAVE_CHECK), answer.toString());
            assertTrue(continued.contains("mark_turn_unchanged"), answer.toString());
            LOG.info("OBS-TEXT host={} constructedStopInput={} saveCheck={} markUnchanged={}",
                    host.label(), STOP_INPUT.equals(input), continued.contains(SAVE_CHECK), continued.contains("mark_turn_unchanged"));
        }
    }

    @Test
    void observeAgyCanceledStopAndFollowingOrdinaryStop() throws Exception {
        Session session = started(AGY);
        prompt(session, STOP_INPUT);
        inquiry(session);
        JsonNode canceled = post(session, "Stop", AGY.stop(false).andThen(body -> body.put("terminationReason", "USER_CANCELED")));
        assertTrue(continuation(session, canceled).isEmpty(), canceled.toString());
        JsonNode ordinary = stop(session, false);
        assertTrue(continuation(session, ordinary).orElse("").contains(SAVE_CHECK), ordinary.toString());
        LOG.info("OBS-CANCEL canceledContinuation={} followingOrdinaryContinuation={}",
                continuation(session, canceled).isPresent(), continuation(session, ordinary).isPresent());
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void observePausedWithoutPendingStages(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = started(host);
        prompt(session, STOP_INPUT);
        inquiry(session);
        paused(session);
        JsonNode answer = stop(session, false);
        assertTrue(continuation(session, answer).isEmpty(), answer.toString());
        LOG.info("OBS-PAUSED-CONTROL host={} continuationPresent={}", host.label(), continuation(session, answer).isPresent());
    }

    private static void logPendingStageObservation(Pending pending, JsonNode answer) {
        // The preparation plan recorded a stage-check continuation as the old baseline.
        // It is diagnostic information, not a second regression oracle opposite to PAUSED.
        String continued = continuation(pending.session(), answer).orElse("");
        LOG.info("OBS-PAUSED-PENDING host={} runId={} stageCheck={} saveCheck={} pendingRunMentioned={}",
                pending.session().host().label(), pending.runId(), continued.contains(STAGE_CHECK),
                continued.contains(SAVE_CHECK), continued.contains(pending.runId()));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void contractPausedMustNotContinueForPendingStages(TurnSaveCheckServerTest.Host host) throws Exception {
        Pending pending = pausedWithPendingStage(host);
        JsonNode answer = stop(pending.session(), false);
        logPendingStageObservation(pending, answer);
        LOG.info("REQ-PAUSED-PENDING host={} runId={} continuationPresent={}",
                host.label(), pending.runId(), continuation(pending.session(), answer).isPresent());
        assertTrue(continuation(pending.session(), answer).isEmpty(),
                "An accepted PAUSED checkpoint must not be overridden by a demand to finish pending stages: " + answer);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void contractPausedSurvivesOrdinaryPromptUntilExplicitResume(TurnSaveCheckServerTest.Host host) throws Exception {
        Pending pending = pausedWithPendingStage(host);
        Session session = pending.session();
        prompt(session, ORDINARY_INPUT);
        // Taking the same workflow attributes this turn; it must not resume the saved task.
        call(session, "resume_workflow", Map.of("runId", pending.runId()));
        JsonNode afterPrompt = call(session, "load_context", DomainServerSupport.arguments());
        assertEquals("PAUSED", afterPrompt.path("memory").path("status").asString(), afterPrompt.toString());

        JsonNode needsSave = stop(session, false);
        String requested = continuation(session, needsSave).orElseThrow(() -> new AssertionError(needsSave.toString()));
        assertTrue(requested.contains(SAVE_CHECK), requested);
        assertTrue(requested.contains("mark_turn_unchanged"), requested);
        assertFalse(requested.contains(STAGE_CHECK), requested);
        assertFalse(requested.contains(WORK_CHECK), requested);

        JsonNode marked = call(session, "mark_turn_unchanged",
                DomainServerSupport.arguments("operationKey", "user-stop-unchanged-" + UUID.randomUUID()));
        assertTrue(marked.path("marked").asBoolean(), marked.toString());
        assertFalse(marked.path("turnStartedAt").asString().isBlank(), marked.toString());
        JsonNode afterMark = call(session, "load_context", DomainServerSupport.arguments());
        assertEquals("PAUSED", afterMark.path("memory").path("status").asString(), afterMark.toString());
        JsonNode answer = stop(session, false);
        assertTrue(continuation(session, answer).isEmpty(),
                "An ordinary prompt and a satisfied save obligation must leave the pending task paused: " + answer);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void contractActiveCheckpointRestoresPendingStageCheck(TurnSaveCheckServerTest.Host host) throws Exception {
        Pending pending = pausedWithPendingStage(host);
        Session session = pending.session();
        JsonNode pausedAnswer = stop(session, false);
        assertTrue(continuation(session, pausedAnswer).isEmpty(), pausedAnswer.toString());
        JsonNode beforeResume = call(session, "load_context", DomainServerSupport.arguments());
        assertEquals("PAUSED", beforeResume.path("memory").path("status").asString(), beforeResume.toString());
        String stateToken = beforeResume.path("stateToken").asString();
        assertFalse(stateToken.isBlank(), beforeResume.toString());
        Map<String, Object> activeCore = Map.of(
                "objective", "사용자가 명시적으로 재개한 draft.txt 작업을 마친다.",
                "completionCriteria", List.of("미완 workflow 단계를 완료한다."),
                "constraints", List.of(), "decisions", List.of("사용자가 작업 재개를 명시했다."),
                "progress", List.of(), "blockers", List.of(), "nextActions", List.of("draft.txt 작업을 검증한다."));
        JsonNode saved = call(session, "checkpoint_context", DomainServerSupport.arguments(
                "operationKey", "user-stop-active-" + UUID.randomUUID(), "expectedStateToken", stateToken,
                "status", "ACTIVE", "core", activeCore, "evidenceRefs", List.of()));
        assertEquals("ACTIVE", saved.path("memory").path("status").asString(), saved.toString());
        JsonNode afterResume = call(session, "load_context", DomainServerSupport.arguments());
        assertEquals("ACTIVE", afterResume.path("memory").path("status").asString(), afterResume.toString());

        JsonNode answer = stop(session, false);
        String continued = continuation(session, answer).orElseThrow(() -> new AssertionError(answer.toString()));
        assertTrue(continued.contains(STAGE_CHECK), continued);
        assertTrue(continued.contains("Workflow gates open for the current request: 1"), continued);
        assertFalse(continued.contains(pending.runId()), continued);
        assertFalse(continued.contains("stage-01-minimal-implementation"), continued);
        assertFalse(continued.contains(SAVE_CHECK), continued);
    }

    private static Session started(TurnSaveCheckServerTest.Host host) throws Exception {
        String id = SessionServerSupport.uniqueSession();
        Path workspace = Files.createDirectories(Path.of("build", "user-stop-contract", id).toAbsolutePath());
        Session session = new Session(host, id, workspace);
        post(session, "SessionStart", body -> {
        });
        return session;
    }

    private static JsonNode post(Session session, String event, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response answer = DomainServerSupport.hook(server.port, "windows", session.host().label(), event, null, session.id(), body -> {
            if (session.host().agy()) {
                body.putArray("workspacePaths").add(session.workspace().toString());
            } else {
                body.put("cwd", session.workspace().toString());
            }
            edit.accept(body);
        });
        assertEquals(200, answer.status(), DomainServerSupport.text(answer));
        return server.bean(JsonMapper.class).readTree(answer.body());
    }

    private static void prompt(Session session, String input) throws Exception {
        if (session.host().agy()) {
            DomainServerSupport.observeAgyRoot(server, session.id());
        }
        post(session, session.host().promptEvent(), session.host().prompt(input));
        String promptId = (String) SessionServerSupport.row(server,
                "SELECT user_prompt_id FROM sennard.user_prompt WHERE host = ? AND session_id = ? ORDER BY received_at DESC FETCH FIRST 1 ROWS ONLY",
                session.host().sessionHost(), session.id()).get("user_prompt_id");
        // Isolate save/stage gates, just as TurnSaveCheckServerTest does. This is
        // explicitly fixture setup, not a claim that a model recorded a recap.
        SessionServerSupport.update(server,
                "INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (?, ?, ?, 0)",
                promptId, "fixture recap; no model was invoked", Timestamps.toTimestamp(server.bean(Clock.class).instant()));
        DomainServerSupport.writeSessionSummary(server, session.host().label(), session.id(), "user-stop fixture " + session.id());
    }

    private static JsonNode call(Session session, String tool, Map<String, Object> arguments) throws IOException {
        // Existing transport/provenance fixture only: ChangeWorkflows supplies
        // recorded _meta / a synthetic tool-use observation, not a model answer.
        JsonNode answer = ChangeWorkflows.callAs(server, session.host().label(), session.id(), tool, arguments);
        assertTrue(answer.path("ok").asBoolean(), tool + ": " + answer);
        return answer.path("data");
    }

    private static void inquiry(Session session) throws IOException {
        call(session, "start_inquiry_workflow", Map.of("goal", "Observe the turn-end hook", "workspace", session.workspace().toString()));
    }

    private static JsonNode stop(Session session, boolean continued) throws IOException {
        return post(session, "Stop", session.host().stop(continued));
    }

    private static Optional<String> continuation(Session session, JsonNode answer) {
        return session.host().continuation(answer);
    }

    private static void paused(Session session) throws IOException {
        String key = "user-stop-paused-" + UUID.randomUUID();
        JsonNode stale = ChangeWorkflows.callAs(server, session.host().label(), session.id(), "checkpoint_context",
                DomainServerSupport.arguments("operationKey", key, "expectedStateToken", "a".repeat(48), "status", "PAUSED", "core", PAUSED_CORE, "evidenceRefs", List.of()));
        assertFalse(stale.path("ok").asBoolean(), stale.toString());
        assertEquals("STALE_REVISION", stale.path("error").path("code").asString(), stale.toString());
        String actual = stale.path("error").path("details").path("actualStateToken").asString();
        assertFalse(actual.isBlank(), stale.toString());
        JsonNode saved = call(session, "checkpoint_context",
                DomainServerSupport.arguments("operationKey", key, "expectedStateToken", actual, "status", "PAUSED", "core", PAUSED_CORE, "evidenceRefs", List.of()));
        assertFalse(saved.path("stateToken").asString().isBlank(), saved.toString());
        JsonNode loaded = call(session, "load_context", DomainServerSupport.arguments());
        assertEquals("PAUSED", loaded.path("memory").path("status").asString(), loaded.toString());
    }

    private static Pending pausedWithPendingStage(TurnSaveCheckServerTest.Host host) throws Exception {
        Session session = started(host);
        prompt(session, ORDINARY_INPUT);
        JsonNode started = call(session, "start_workflow", Map.of(
                "goal", "Prepare draft.txt before the user interrupts",
                "stages", List.of("minimal-implementation"),
                "skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "the fixture draft")),
                "acceptanceCriteria", List.of("The draft is prepared."),
                "workspace", session.workspace().toString(),
                "writeTargets", List.of("draft.txt"), "changePlan", List.of("draft.txt")));
        String runId = started.path("runId").asString();
        assertFalse(runId.isBlank(), started.toString());
        Path draft = session.workspace().resolve("draft.txt");
        JsonNode admitted = post(session, "PreToolUse", body -> {
            if (host.agy()) {
                body.putObject("toolCall").put("name", "write_to_file").putObject("args")
                        .put("TargetFile", draft.toString()).put("CodeContent", "draft");
            } else if (host.label().equals("codex")) {
                body.put("tool_name", "apply_patch");
                body.putObject("tool_input").put("command", "*** Begin Patch\n*** Add File: " + draft.toString().replace('\\', '/') + "\n+draft\n*** End Patch");
            } else {
                body.put("tool_name", "Write");
                body.putObject("tool_input").put("file_path", draft.toString()).put("content", "draft");
            }
        });
        assertFalse(admitted.path("decision").asString().equals("deny"), admitted.toString());
        assertFalse(admitted.path("hookSpecificOutput").path("permissionDecision").asString().equals("deny"), admitted.toString());
        // The admitted write really occurs in this case's isolated workspace.
        Files.writeString(draft, "draft");
        assertEquals("draft", Files.readString(draft));
        prompt(session, STOP_INPUT);
        call(session, "resume_workflow", Map.of("runId", runId));
        paused(session);
        return new Pending(session, runId);
    }
}
