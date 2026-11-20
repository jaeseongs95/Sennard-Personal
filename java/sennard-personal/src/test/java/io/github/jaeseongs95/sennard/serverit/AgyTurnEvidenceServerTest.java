// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Antigravity turn-evidence rule (R1 to R7) through {@code /hooks/v1/agy/...} on the real server, from the recorded hook bodies; and the other
 * hosts through the same endpoints, to fix that they do not get it.
 */
@Tag("business-db")
class AgyTurnEvidenceServerTest {
    private static final String HEAD = "[Sennard turn check] Observed";
    private static final String RERUN = "No command run was observed after the last edit";
    private static final String SKILL = "No SKILL.md read was observed";
    private static final String NO_REPRO = "No command run was observed before the edit";
    private static final String ARTIFACTS = System.getProperty("java.io.tmpdir") + "/agy-brain";

    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static JsonNode post(String host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", host, event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.MAPPER.readTree(response.body());
    }

    private static String turn() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        startTurn(session);
        return session;
    }

    private static void startTurn(String session) throws IOException {
        DomainServerSupport.observeAgyRoot(server, session);
        post("agy", "PreInvocation", session, body -> body.put("invocationNum", 0));
        recapRecorded("antigravity", session);
        summaryRecorded("antigravity", session);
        DomainServerSupport.markTurnUnchanged(server, "agy", session);
    }

    /** The session's summary on its board row (the row the prompt's hook made): the turn-end question for the summary is not this test's subject either. */
    private static void summaryRecorded(String sessionHost, String session) {
        SessionServerSupport.update(server, "UPDATE sennard.board_sessions SET summary = ?, summary_at = ? WHERE host = ? AND session_id = ?",
                "recorded", Timestamps.toTimestamp(Instant.now()), sessionHost, session);
    }

    /**
     * The turn-end question for the recap and the summary (PromptArchiveHookServerTest) is not this test's subject: the prompt of the turn has its recap and the session its summary,
     * as they have once the agent has recorded them, so the stops below answer only for the turn evidence; the turn is marked with mark_turn_unchanged for the same reason (the save
     * check of AGS3-D-0156 has its own test).
     */
    private static void recapRecorded(String sessionHost, String session) {
        String id = (String) SessionServerSupport.row(server, "SELECT user_prompt_id FROM sennard.user_prompt p WHERE host = ? AND session_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM sennard.turn_recap r WHERE r.user_prompt_id = p.user_prompt_id) ORDER BY received_at DESC FETCH FIRST 1 ROWS ONLY",
                sessionHost, session).get("user_prompt_id");
        SessionServerSupport.update(server, "INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (?, ?, ?, 0)",
                id, "recorded", Timestamps.toTimestamp(Instant.now()));
    }

    private static void tool(String session, String name, Consumer<ObjectNode> args, boolean failed) throws IOException {
        post("agy", "PostToolUse", session, body -> {
            ObjectNode call = body.putObject("toolCall");
            call.put("name", name);
            args.accept(call.putObject("args"));
            body.put("artifactDirectoryPath", ARTIFACTS);
            body.put("error", failed ? "the tool failed" : "");
        });
    }

    private static void edit(String session) throws IOException {
        tool(session, "write_to_file", args -> args.put("TargetFile", System.getProperty("java.io.tmpdir") + "/work/a.txt"), false);
    }

    private static void run(String session, String command) throws IOException {
        tool(session, "run_command", args -> args.put("CommandLine", command), false);
    }

    private static void skillRead(String session) throws IOException {
        tool(session, "view_file", args -> args.put("AbsolutePath", "C:/sennard/skills/minimal-implementation/SKILL.md"), false);
    }

    private static JsonNode stop(String session) throws IOException {
        return post("agy", "Stop", session, body -> {
            body.remove("error");
            body.remove("terminationReason");
            body.put("fullyIdle", true);
            body.put("executionNum", 1);
        });
    }

    private static String continuation(JsonNode output) {
        assertEquals("continue", output.path("decision").asString(), output.toString());
        return output.path("reason").asString();
    }

    private static void assertNoContinuation(JsonNode output) {
        assertFalse(output.has("decision"), output.toString());
    }

    @Test
    void aTurnThatEditedAndShowedNothingElseContinuesOnceAndNamesWhatIsMissing() throws IOException {
        String session = turn();
        edit(session);
        String text = continuation(stop(session));
        assertTrue(text.contains(HEAD) && text.contains("1 file edit"), text);
        assertTrue(text.contains(RERUN) && text.contains(SKILL) && text.contains(NO_REPRO), text);
        assertNoContinuation(stop(session));
    }

    @Test
    void aTurnWithTheSkillReadAndARunAfterTheEditEndsQuietly() throws IOException {
        String session = turn();
        skillRead(session);
        run(session, "pytest");
        edit(session);
        run(session, "pytest");
        assertNoContinuation(stop(session));
    }

    @Test
    void aRunBeforeTheEditIsNotARerun() throws IOException {
        String session = turn();
        skillRead(session);
        run(session, "pytest");
        edit(session);
        String text = continuation(stop(session));
        assertTrue(text.contains(RERUN), text);
        assertFalse(text.contains(SKILL) || text.contains(NO_REPRO), text);
    }

    @Test
    void inspectingAfterTheEditIsNotARun() throws IOException {
        String session = turn();
        skillRead(session);
        edit(session);
        run(session, "cat a.txt");
        run(session, "echo done");
        run(session, "git diff");
        assertTrue(continuation(stop(session)).contains(RERUN));
    }

    @Test
    void aMissingSkillReadIsNamedAlone() throws IOException {
        String session = turn();
        edit(session);
        run(session, "pytest");
        String text = continuation(stop(session));
        assertTrue(text.contains(SKILL), text);
        assertFalse(text.contains(RERUN), text);
    }

    @Test
    void aTurnWithoutAnEditOrWithOnlyAnArtifactNoteOrAFailedEditIsUnchanged() throws IOException {
        String readOnly = turn();
        run(readOnly, "ls");
        assertNoContinuation(stop(readOnly));

        String note = turn();
        tool(note, "write_to_file", args -> args.put("TargetFile", ARTIFACTS + "/plan.md"), false);
        assertNoContinuation(stop(note));

        String failed = turn();
        tool(failed, "replace_file_content", args -> args.put("TargetFile", "C:/work/a.txt"), true);
        assertNoContinuation(stop(failed));
    }

    @Test
    void aFailedCommandStillCountsAsARun() throws IOException {
        String session = turn();
        skillRead(session);
        edit(session);
        tool(session, "run_command", args -> args.put("CommandLine", "pytest"), true);
        assertNoContinuation(stop(session));
    }

    @Test
    void aCanceledOrFailedStopIsNotContinuedAndDoesNotUseUpTheContinuation() throws IOException {
        String session = turn();
        edit(session);
        assertNoContinuation(post("agy", "Stop", session, body -> body.put("terminationReason", "USER_CANCELED")));
        assertNoContinuation(post("agy", "Stop", session, body -> body.put("error", "the model failed")));
        assertTrue(continuation(stop(session)).contains(RERUN));
    }

    @Test
    void workResetsAtEachTurnTheSkillReadStaysAndSessionsDoNotMix() throws IOException {
        String session = turn();
        skillRead(session);
        edit(session);
        continuation(stop(session));

        startTurn(session);
        edit(session);
        String second = continuation(stop(session));
        assertTrue(second.contains(RERUN), second);
        assertFalse(second.contains(SKILL), "the session's skill read stays: " + second);

        String other = turn();
        assertNoContinuation(stop(other));
    }

    @Test
    void theOtherHostsAreNotObservedOrContinuedForThis() throws IOException {
        long before = SessionServerSupport.count(server, "SELECT count(*) FROM sennard.continuity_observations WHERE event LIKE 'turn-work:%'");
        for (String host : new String[] {"claude", "codex"}) {
            String session = SessionServerSupport.uniqueSession();
            ObjectNode input = DomainServerSupport.MAPPER.createObjectNode().put("file_path", "C:/work/a.txt");
            post(host, "UserPromptSubmit", session, body -> { });
            recapRecorded(host.equals("claude") ? "claude-code" : host, session);
            summaryRecorded(host.equals("claude") ? "claude-code" : host, session);
            DomainServerSupport.markTurnUnchanged(server, host, session);
            post(host, "PostToolUse", session, DomainServerSupport.tool("Edit", input));
            assertNoContinuation(post(host, "Stop", session, body -> { }));
        }
        assertEquals(before, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.continuity_observations WHERE event LIKE 'turn-work:%'"));
    }
}
