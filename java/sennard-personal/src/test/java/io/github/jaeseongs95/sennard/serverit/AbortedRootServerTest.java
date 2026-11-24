// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * An abort ends the attempt and nothing else: the root is not closed (a root is given up with {@code release_convergence_root}), and only a session that may take the workflow can abort
 * it. A root with no run in progress does not keep another session from the files it covers, while the session that holds it stays bound to it.
 */
@Tag("business-db")
class AbortedRootServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.test.workflow-gate=on");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String session() throws IOException {
        String id = SessionServerSupport.uniqueSession();
        DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, id, body -> {
        });
        return id;
    }

    private static Path workspace() throws IOException {
        return Files.createDirectories(Path.of("build", "aborted-root", UUID.randomUUID().toString()).toAbsolutePath());
    }

    private static JsonNode call(String session, String tool, Map<String, Object> arguments) throws IOException {
        return ChangeWorkflows.callAs(server, "codex", session, tool, arguments);
    }

    private static JsonNode data(JsonNode envelope) {
        assertTrue(envelope.path("ok").asBoolean(), envelope.toString());
        return envelope.path("data");
    }

    private static Map<String, Object> startArguments(Path workspace, String goal) {
        return Map.of("goal", goal, "workspace", workspace.toString(), "stages", List.of("minimal-implementation"),
                "skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The change works."),
                "writeTargets", List.of("alpha"), "changePlan", List.of("alpha/a.txt"));
    }

    private static JsonNode startChange(String session, Path workspace, String goal) throws IOException {
        return call(session, "start_workflow", startArguments(workspace, goal));
    }

    private static long revision(String session, String runId) throws IOException {
        return data(call(session, "get_workflow_status", Map.of("runId", runId, "detail", "compact"))).path("revision").asLong();
    }

    private static JsonNode abortCall(String session, String runId) throws IOException {
        return call(session, "abort_workflow", Map.of("runId", runId, "expectedRevision", revision(session, runId)));
    }

    private static JsonNode root(String session, String rootId) throws IOException {
        return data(call(session, "get_convergence_status", Map.of("rootId", rootId))).path("root");
    }

    private static String state(String session, String runId) throws IOException {
        return data(call(session, "get_workflow_status", Map.of("runId", runId, "detail", "compact"))).path("state").asString();
    }

    /** The attempt ends as failed: the run's implementation stage is recorded as failed, which leaves the root open and the run over. */
    private static void failAttempt(String session, String runId) throws IOException {
        JsonNode status = data(call(session, "get_workflow_status", Map.of("runId", runId)));
        Map<String, Object> error = Map.of("code", "GATE_FAILED", "message", "The implementation check failed.", "details", Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1.0.0");
        result.put("runId", runId);
        result.put("expectedRevision", status.path("revision").asLong());
        result.put("stageId", status.path("plan").path("currentStageId").asString());
        result.put("state", "FAILED");
        result.put("output", Map.of("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "failed"), "artifacts", List.of(), "error", error));
        result.put("evidence", List.of());
        result.put("findings", List.of());
        result.put("blockers", List.of());
        result.put("error", error);
        assertEquals("FAILED", data(call(session, "record_stage_result", result)).path("state").asString());
    }

    @Test
    void anAbortEndsOnlyTheAttemptAndTheRootStaysOpenForItsOwner() throws IOException {
        Path workspace = workspace();
        String owner = session();
        JsonNode started = data(startChange(owner, workspace, "Change alpha"));
        String rootId = started.path("rootId").asString();
        String runId = started.path("runId").asString();

        JsonNode aborted = data(abortCall(owner, runId));
        assertEquals("BLOCKED", aborted.path("state").asString(), aborted.toString());
        JsonNode open = root(owner, rootId);
        assertEquals("OPEN", open.path("state").asString(), "an abort does not close the root: " + open);
        assertTrue(open.path("release").isNull() || open.path("release").isMissingNode(), open.toString());
        assertFalse(call(owner, "resume_workflow", Map.of("runId", runId)).path("ok").asBoolean(), "an aborted run cannot resume");
        JsonNode next = data(startChange(owner, workspace(), "Change beta"));
        assertEquals(2, next.path("sessionCapacity").path("openCount").asInt(), "the root of the aborted attempt is still an open workflow of its session: " + next);
    }

    @Test
    void anAbortByASessionThatDoesNotHoldTheWorkflowIsRefusedAndNothingChanges() throws IOException {
        String owner = session();
        JsonNode started = data(startChange(owner, workspace(), "Change alpha"));
        String runId = started.path("runId").asString();
        String rootId = started.path("rootId").asString();
        String stranger = session();

        JsonNode refused = call(stranger, "abort_workflow", Map.of("runId", runId, "expectedRevision", revision(owner, runId)));
        assertFalse(refused.path("ok").asBoolean(), refused.toString());
        assertEquals("INVALID_INPUT", refused.path("error").path("code").asString(), refused.toString());
        assertEquals("INSTANCE_NOT_OWNED", refused.path("error").path("details").path("reason").asString(), refused.toString());
        assertEquals("RUNNING", state(owner, runId), "the run is as it was");
        assertEquals("OPEN", root(owner, rootId).path("state").asString());

        JsonNode byOwner = data(abortCall(owner, runId));
        assertEquals("BLOCKED", byOwner.path("state").asString(), "the session that holds the workflow can still abort it");
    }

    @Test
    void aRootWithNoRunInProgressDoesNotBlockAnotherSessionsStartAndItsOwnerStaysBoundToIt() throws IOException {
        Path workspace = workspace();
        String owner = session();
        JsonNode started = data(startChange(owner, workspace, "Change alpha"));
        String rootId = started.path("rootId").asString();
        failAttempt(owner, started.path("runId").asString());
        assertEquals("OPEN", root(owner, rootId).path("state").asString());

        // Another session opens a workflow on the same files: the root nobody is running does not refuse it.
        String other = session();
        JsonNode taken = startChange(other, workspace, "Change alpha as well");
        assertTrue(taken.path("ok").asBoolean(), "a root with no run in progress does not block another session: " + taken);
        assertNotEquals(rootId, taken.path("data").path("rootId").asString());
        assertEquals("OPEN", root(owner, rootId).path("state").asString(), "the owner's root is still active and its own");

        // The files are now held by the other session's running workflow, so the owner's start on them is refused as any other session's would be.
        JsonNode again = startChange(owner, workspace, "Change alpha again");
        assertFalse(again.path("ok").asBoolean(), again.toString());
        assertEquals("ROOT_CONFLICT", again.path("error").path("code").asString(), again.toString());
    }

    @Test
    void theSessionThatHoldsARootWithNoRunIsRefusedItsOwnRestartAndToldHowToReleaseIt() throws IOException {
        Path workspace = workspace();
        String owner = session();
        JsonNode started = data(startChange(owner, workspace, "Change alpha"));
        String rootId = started.path("rootId").asString();
        data(abortCall(owner, started.path("runId").asString()));

        JsonNode refused = startChange(owner, workspace, "Change alpha again");
        assertFalse(refused.path("ok").asBoolean(), refused.toString());
        assertEquals("ROOT_CONFLICT", refused.path("error").path("code").asString(), refused.toString());
        JsonNode details = refused.path("error").path("details");
        assertEquals(rootId, details.path("rootId").asString(), refused.toString());
        assertTrue(details.path("ownedBySession").asBoolean(), refused.toString());
        assertTrue(refused.path("error").path("message").asString().contains("release_convergence_root"), "the refusal names the tool that gives the root up: " + refused);
        boolean namesRelease = false;
        for (JsonNode action : details.path("nextActions")) {
            namesRelease |= "release_convergence_root".equals(action.path("tool").asString());
        }
        assertTrue(namesRelease, details.toString());

        // The way it names works: release the root, and the same files start again.
        data(call(owner, "release_convergence_root", Map.of("rootId", rootId, "expectedRevision", details.path("rootRevision").asLong(), "reason", "The work starts again.")));
        assertEquals("ABANDONED", root(owner, rootId).path("state").asString());
        assertTrue(startChange(owner, workspace, "Change alpha again").path("ok").asBoolean());
    }

    @Test
    void aRootWithARunningOrPausedRunStillBlocksEveryOtherSession() throws IOException {
        Path workspace = workspace();
        String owner = session();
        JsonNode started = data(startChange(owner, workspace, "Change alpha"));
        String runId = started.path("runId").asString();
        String other = session();

        JsonNode whileRunning = startChange(other, workspace, "Change alpha as well");
        assertEquals("ROOT_CONFLICT", whileRunning.path("error").path("code").asString(), whileRunning.toString());

        data(call(owner, "pause_workflow", Map.of("runId", runId, "expectedRevision", revision(owner, runId), "reason", "Waiting for input", "resumeCondition", "The input arrived")));
        assertEquals("PAUSED", state(owner, runId));
        JsonNode whilePaused = startChange(other, workspace, "Change alpha as well");
        assertEquals("ROOT_CONFLICT", whilePaused.path("error").path("code").asString(), "a paused run is still the owner's work in progress: " + whilePaused);
    }
}
