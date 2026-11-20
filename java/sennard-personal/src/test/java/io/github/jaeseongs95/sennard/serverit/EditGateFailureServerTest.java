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
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowCall;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCode;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import io.github.jaeseongs95.sennard.service.store.RootState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises recorded start failures and unreadable edit-gate state. Uses a dedicated server because one test inserts an unreadable root.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class EditGateFailureServerTest {
    private static final String UNCHECKED = "[Sennard] The workflow state could not be checked";
    private static final String BROKEN_ROOT = "root-that-cannot-be-read";

    private static RunningServer server;
    private static Path repository;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
        repository = Path.of("build", "edit-gate-failure-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(repository);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String newSession() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "claude", "UserPromptSubmit", null, session, body -> { }).status());
        return session;
    }

    private static boolean editDenied(String session, String file) throws IOException {
        return editDecision(session, file).path("permissionDecision").asString().equals("deny");
    }

    private static JsonNode editDecision(String session, String file) throws IOException {
        ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("file_path", repository.resolve(file).toString()).put("content", "x");
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", "claude", "PreToolUse", null, session, DomainServerSupport.tool("Write", input));
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.MAPPER.readTree(response.body()).path("hookSpecificOutput");
    }

    private static String stop(String session) throws IOException {
        return DomainServerSupport.text(DomainServerSupport.hook(server.port, "windows", "claude", "Stop", null, session, body -> { }));
    }

    private static long events(String pattern) {
        return SessionServerSupport.count(server, "SELECT count(*) FROM sennard.continuity_observations WHERE event LIKE ?", pattern);
    }

    private static void assertTheFirstFileIsRefused() throws IOException {
        String control = newSession();
        String prefix = "control-" + UUID.randomUUID();
        assertTrue(editDenied(control, prefix + "-1.txt"), "a session with no workflow has no file it may change");
        assertTrue(editDenied(control, prefix + "-2.txt"), "and it holds for every file, not only the first");
    }

    @Test
    void aFailedWorkflowStartRefusesEveryEditAndRetainsTheDiagnosis() throws IOException {
        assertTheFirstFileIsRefused();
        String session = newSession();
        long before = events("phase-start-failure-pass");
        long filesBefore = events("phase-file");
        server.bean(WorkflowService.class).recordStartFailure(new WorkflowCall("claude-code", session, null, null, null, null, "en"), WorkflowCode.MCP_UNAVAILABLE);
        boolean firstDenied = editDenied(session, "first.txt");
        boolean secondDenied = editDenied(session, "second.txt");
        String answer = stop(session);
        assertAll(
                () -> assertTrue(firstDenied && secondDenied, "a failed start grants no edit permission"),
                () -> assertEquals(before, events("phase-start-failure-pass"), "no failed-start allowance was recorded"),
                () -> assertEquals(filesBefore, events("phase-file"), "a refused edit is not an observed write"),
                () -> assertTrue(answer.contains("MCP_UNAVAILABLE"), answer),
                () -> assertFalse(answer.contains(UNCHECKED), answer));
    }

    @Test
    void aRootRowThatCannotBeReadRefusesEveryEditAndRecordsOneDiagnosis() throws IOException {
        long before = events("phase-gate-unavailable:%");
        long filesBefore = events("phase-file");
        SessionServerSupport.update(server, "INSERT INTO sennard.convergence_roots (root_id, revision, state, workspace_id, workspace_locator, root_json, created_at, updated_at) "
                + "VALUES (?, 0, ?, 'w', 'w', 'not json', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')", BROKEN_ROOT, RootState.OPEN.name());
        try {
            String session = newSession();
            for (int file = 0; file < 5; file += 1) {
                JsonNode decision = editDecision(session, "unreadable-" + session + "-" + file + ".txt");
                assertEquals("deny", decision.path("permissionDecision").asString(), decision.toString());
                assertTrue(decision.path("permissionDecisionReason").asString().contains("UNEXPECTED_FAILURE"), decision.toString());
            }
            assertEquals(before + 1, events("phase-gate-unavailable:%"), "failure is noted once in the window");
            assertEquals(filesBefore, events("phase-file"), "none of the refused writes is recorded as a changed file");
            String answer = stop(session);
            assertTrue(answer.contains(UNCHECKED + " in this window. Cause codes"), answer);
            assertTrue(answer.contains("phase-gate-unavailable:UNEXPECTED_FAILURE"), answer);
        } finally {
            SessionServerSupport.update(server, "DELETE FROM sennard.convergence_roots WHERE root_id = ?", BROKEN_ROOT);
        }
        assertTheFirstFileIsRefused();
    }
}
