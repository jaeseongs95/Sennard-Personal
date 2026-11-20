// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import tools.jackson.databind.JsonNode;

/**
 * The workflow statistics of the admin page (GET /api/workflow-stats), on a real server and PostgreSQL: the starts the agents made through the tools of the kinds are counted by the
 * database from the kind and the skills each start stored with its run in the same transaction (V22). Cases (written first):
 * <pre>
 * s1  the kinds        the answer lists every WorkflowKind of the enum in its order, whatever was started
 * s2  by kind          one start of each kind is counted under its own kind and in the total, and the run is in the newest list with its goal, state, host and start time
 * s3  skills by kind   the skills a start declared are counted under the kind it opened; a start of the kind whose skills are optional that declared none is counted as without skills
 * s4  the period       only the periods the page offers are served (the same as the skill statistics); anything else is a problem naming days
 * s5  the page         the page has the screen and its call
 * </pre>
 * The server is shared by the cases, so each reads the statistics before and after its starts and compares the difference.
 */
@Tag("business-db")
class AdminWorkflowStatsServerTest {
    private static ToolServerSupport sennard;
    private static Path workspaces;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        workspaces = Path.of("build", "workflow-stats-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        sennard = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static List<Map<String, String>> skills(String... ids) {
        return Arrays.stream(ids).map(id -> Map.of("skillId", id, "reason", "the start of this test")).toList();
    }

    /** One start of a kind by a session of its own, in a workspace of its own; the run's ID. */
    private static String started(WorkflowKind kind, String goal, List<Map<String, String>> skills) throws Exception {
        Path workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString()));
        Map<String, Object> input = arguments("goal", goal, "workspace", workspace.toString());
        if (!skills.isEmpty()) {
            input.put("skills", skills);
        }
        switch (kind) {
            case INQUIRY -> {
            }
            case OPERATION -> input.put("target", "branch of the test repository");
            case CHANGE -> {
                input.put("stages", List.of("minimal-implementation"));
                input.put("acceptanceCriteria", List.of("The change works."));
                input.put("writeTargets", List.of("alpha"));
            }
        }
        JsonNode result = sennard.callAsNewHostCall(kind.toolName(), input, sennard.session());
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.path("data").path("runId").asString();
    }

    private static JsonNode stats(int days) throws Exception {
        RawHttp.Response response = sennard.get("/api/workflow-stats?days=" + days);
        assertEquals(200, response.status(), response.text());
        return SessionServerSupport.json(response);
    }

    private static JsonNode kind(JsonNode stats, WorkflowKind kind) {
        for (JsonNode entry : stats.path("kinds")) {
            if (kind.name().equals(entry.path("kind").asString())) {
                return entry;
            }
        }
        throw new AssertionError(kind + " is not in " + stats);
    }

    private static int declared(JsonNode stats, WorkflowKind kind, String skill) {
        for (JsonNode entry : stats.path("skills")) {
            if (kind.name().equals(entry.path("kind").asString()) && skill.equals(entry.path("skillId").asString())) {
                return entry.path("workflows").asInt();
            }
        }
        return 0;
    }

    private static JsonNode entryOf(JsonNode newest, String run) {
        for (JsonNode entry : newest) {
            if (run.equals(entry.path("runId").asString())) {
                return entry;
            }
        }
        throw new AssertionError(run + " is not among the newest: " + newest);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.asString());
        }
        return out;
    }

    @Test
    void s1_theAnswerListsEveryKindOfTheEnumInItsOrder() throws Exception {
        JsonNode stats = stats(30);
        List<String> listed = new ArrayList<>();
        for (JsonNode entry : stats.path("kinds")) {
            listed.add(entry.path("kind").asString());
        }
        assertEquals(Arrays.stream(WorkflowKind.values()).map(Enum::name).toList(), listed);
        for (WorkflowKind kind : WorkflowKind.values()) {
            assertEquals(kind.skillsRequired(), kind(stats, kind).path("skillsRequired").asBoolean(), kind.name());
        }
        assertEquals(30, stats.path("days").asInt());
    }

    @Test
    void s2_s3_aStartOfEachKindIsCountedUnderItsKindWithItsSkills() throws Exception {
        JsonNode before = stats(30);
        String inquiry = started(WorkflowKind.INQUIRY, "Answer where the stats are " + UUID.randomUUID(), List.of());
        String operation = started(WorkflowKind.OPERATION, "Run the tests of the stats " + UUID.randomUUID(), skills("minimal-implementation"));
        String change = started(WorkflowKind.CHANGE, "Change the stats " + UUID.randomUUID(), skills("minimal-implementation", "test-engineering"));
        JsonNode after = stats(30);
        assertEquals(before.path("total").asInt() + 3, after.path("total").asInt(), after.toString());
        for (WorkflowKind kind : WorkflowKind.values()) {
            assertEquals(kind(before, kind).path("started").asInt() + 1, kind(after, kind).path("started").asInt(), "one start of " + kind);
        }
        assertEquals(kind(before, WorkflowKind.INQUIRY).path("withoutSkills").asInt() + 1, kind(after, WorkflowKind.INQUIRY).path("withoutSkills").asInt(), "the answer declared no skills");
        assertEquals(declared(before, WorkflowKind.OPERATION, "minimal-implementation") + 1, declared(after, WorkflowKind.OPERATION, "minimal-implementation"));
        assertEquals(declared(before, WorkflowKind.CHANGE, "minimal-implementation") + 1, declared(after, WorkflowKind.CHANGE, "minimal-implementation"));
        assertEquals(declared(before, WorkflowKind.CHANGE, "test-engineering") + 1, declared(after, WorkflowKind.CHANGE, "test-engineering"));
        assertEquals(declared(before, WorkflowKind.INQUIRY, "minimal-implementation"), declared(after, WorkflowKind.INQUIRY, "minimal-implementation"), "a skill of one kind is not counted under another");
        assertFalse(after.has("unrecorded"), "every run has its kind stored with it: the answer has no count of runs without one");
        JsonNode newest = after.path("recent");
        assertTrue(newest.size() <= after.path("recentLimit").asInt(), newest.toString());
        for (String run : List.of(inquiry, operation, change)) {
            JsonNode listed = entryOf(newest, run);
            assertFalse(listed.path("goal").asString().isBlank(), listed.toString());
            assertFalse(listed.path("state").asString().isBlank(), listed.toString());
            assertEquals("codex", listed.path("host").asString(), listed.toString());
            assertFalse(listed.path("startedAt").asString().isBlank(), listed.toString());
        }
        assertEquals("INQUIRY", entryOf(newest, inquiry).path("kind").asString());
        assertEquals("OPERATION", entryOf(newest, operation).path("kind").asString());
        assertEquals("CHANGE", entryOf(newest, change).path("kind").asString());
        assertEquals(List.of("minimal-implementation", "test-engineering"), texts(entryOf(newest, change).path("skills")));
        assertEquals(0, entryOf(newest, inquiry).path("skills").size());
    }

    @Test
    void s4_onlyThePeriodsThePageOffersAreServed() throws Exception {
        for (int days : List.of(1, 7, 30, 90)) {
            assertEquals(days, stats(days).path("days").asInt());
        }
        for (String days : List.of("", "0", "2", "365", "x")) {
            RawHttp.Response refused = sennard.get("/api/workflow-stats?days=" + days);
            assertEquals(400, refused.status(), days);
            assertEquals("days", SessionServerSupport.json(refused).path("details").path("field").asString(), days);
        }
    }

    @Test
    void s5_thePageHasTheScreenAndItsCall() throws Exception {
        String page = sennard.get("/").text();
        assertTrue(page.contains("viewWorkflows") && page.contains("/api/workflow-stats"), "the screen and its call are in the page");
    }
}
