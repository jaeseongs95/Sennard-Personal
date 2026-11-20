// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
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

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The user's prompts kept word for word, and the turn-end question for the recap, through {@code /hooks/v1/...} on the assembled server (real PostgreSQL, the real hook adapters
 * of four hosts). Each fact is read back from its table; nothing here calls the archive to record. The server runs with {@code --sennard.prompt-archive.max-bytes=2KB}, which is the
 * setting's real path into the writer.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * h1  every host keeps    a main session's prompt on Claude Code (UserPromptSubmit), Codex (UserPromptSubmit), Antigravity (PreInvocation, invocation 0) and Gemini CLI (BeforeAgent) ->
 *                         one row under the host's session name (claude-code, codex, antigravity, gemini) and the host's session id: the text exactly (Korean, an emoji, a new line),
 *                         original_bytes, the SHA-256 digest, truncated N, the hook answers 200
 * h2  a child is not      a Claude Code subagent's prompt (agent_id) -> no row
 * h2a Antigravity actor   before proof, after mismatched root metadata, and after a matching child call -> zero rows under the child id
 *                         after a matching root call -> one exact row, no row for the missing lastUserInput field
 * h3  the empty prompt    "" -> a row with the empty text
 * h4  over the limit      3000 bytes with the 2 KB setting -> truncated Y, 2048 bytes kept, the size and digest of the whole
 * h5  the transcript      Gemini's hook names transcript_path: the row holds it
 * h6  a failed write      a prompt Postgres cannot hold (a NUL character) -> the hook answers 422 INTEGRITY_FAILED (reasonCode VALUE_NOT_ALLOWED, application/problem+json, above 299 so the
 *                         host's failure behaviour applies) and the failure is logged once with its cause; no row, and the next prompt is kept
 * (The turn-end save check of AGS3-D-0156 is TurnSaveCheckServerTest's subject: a turn of the n cases is marked with mark_turn_unchanged, so that its turn end answers only for the recap.)
 * n1  the question, once  after a prompt, the turn end continues the turn with the recap instruction on every host; a second turn end of the same prompt does not; one reminder row
 * n2  the recap is there  a recap row for the newest prompt and a summary written with update_session_status -> the turn end asks for nothing, no reminder row
 * s1  no summary         a main session whose board row has no summary: its request's start asks for the summary (update_session_status), its first turn end asks for the recap and the
 *                         summary in one continuation, on every host; a second turn end of the same prompt asks for the summary again, not the recap; one reminder row
 * s2  recap, no summary   a recap row but no summary -> the summary alone is asked for at every turn end until it is there, on every host
 * s4  after a tool        no summary -> asked after every finished tool (Claude Code, Codex: PostToolUse) until it is there
 * s5  a stale summary     a summary older than the last prompt -> asked to be rewritten at the request's start, at every turn end and after a finished tool, until it is newer than the request
 * s3  a summary is there  a summary on the board row -> only the recap is asked for, never the summary; the child agent's and the subagent's turn ends ask for neither (n4)
 * n3  no prompt           a turn end of a session with no prompt -> nothing asked
 * n4  not a child's       Claude Code's child turn end (agent_id) and its subagent end -> nothing asked, no row
 * n5  the host's flag     a turn end the host reports as already continued (stop_hook_active) is still asked about, once: the question is the prompt's, not the host's flag
 * n6  a new prompt        the next prompt of the session is asked about again, once
 * n7  a stop that cannot  an Antigravity stop that is canceled (terminationReason USER_CANCELED, which the adapter reads as a stop the host cannot continue) is not turned into a
 *     be continued        continuation and spends nothing of the prompt's one question: no reminder row; the next stop of the same prompt that can be continued is asked about, once
 * </pre>
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class PromptArchiveHookServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String QUESTION = "[Sennard turn recap]";
    private static final String SUMMARY_QUESTION = "[Sennard session summary]";
    private static final String STALE_QUESTION = "is older than your last request";
    private static final String NO_NUL_PREFIX = "before ";

    /** A host: the label of its hooks, the name its sessions are stored under, and the events of a prompt and of a turn end. */
    record Host(String label, String sessionHost, String promptEvent, String stopEvent) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Host CLAUDE = new Host("claude", "claude-code", "UserPromptSubmit", "Stop");
    private static final Host CODEX = new Host("codex", "codex", "UserPromptSubmit", "Stop");
    private static final Host AGY = new Host("agy", "antigravity", "PreInvocation", "Stop");
    private static final Host GEMINI = new Host("gemini", "gemini", "BeforeAgent", "AfterAgent");

    private static RunningServer server;

    static Stream<Host> hosts() {
        return Stream.of(CLAUDE, CODEX, AGY, GEMINI);
    }

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.prompt-archive.max-bytes=2KB");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    // ------------------------------------------------------------------------------------------------------------------ posting

    private static RawHttp.Response post(Host host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        if (host == GEMINI) {
            // Gemini CLI's hook input, as GeminiHookServerTest writes it: the launcher posts the hook's stdin JSON.
            ObjectNode body = MAPPER.createObjectNode().put("session_id", session).put("transcript_path", "/tmp/chat-" + session + ".json").put("cwd", "/work")
                    .put("hook_event_name", event).put("timestamp", "2026-11-11T00:00:00.000Z");
            edit.accept(body);
            return SessionServerSupport.post(server.port, "/hooks/v1/gemini/" + event, MAPPER.writeValueAsBytes(body));
        }
        return DomainServerSupport.hook(server.port, "windows", host.label(), event, null, session, edit);
    }

    private static Consumer<ObjectNode> promptText(Host host, String text) {
        return switch (host.label()) {
            case "agy" -> body -> body.put("invocationNum", 0).put("lastUserInput", text);
            default -> body -> body.put("prompt", text);
        };
    }

    private static String answer(RawHttp.Response response) {
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static String prompt(Host host, String session, String text) throws IOException {
        if (host == AGY) {
            DomainServerSupport.observeAgyRoot(server, session);
        }
        return postedPrompt(host, session, text);
    }

    private static String postedPrompt(Host host, String session, String text) throws IOException {
        return answer(post(host, host.promptEvent(), session, promptText(host, text)));
    }

    private static String turnEnd(Host host, String session) throws IOException {
        return answer(post(host, host.stopEvent(), session, body -> {
        }));
    }

    private static long count(String sql, Object... params) {
        return SessionServerSupport.count(server, sql, params);
    }

    private static long prompts(Host host, String session) {
        return count("SELECT count(*) FROM sennard.user_prompt WHERE host = ? AND session_id = ?", host.sessionHost(), session);
    }

    private static long reminders(Host host, String session) {
        return count("SELECT count(*) FROM sennard.recap_nudge n JOIN sennard.user_prompt p ON p.user_prompt_id = n.user_prompt_id "
                + "WHERE p.host = ? AND p.session_id = ?", host.sessionHost(), session);
    }

    private static Map<String, Object> row(Host host, String session) {
        return SessionServerSupport.row(server, "SELECT prompt_text, original_bytes, prompt_digest, completeness, transcript_path FROM sennard.user_prompt WHERE host = ? AND session_id = ?",
                host.sessionHost(), session);
    }

    private static Consumer<ObjectNode> childOf(Consumer<ObjectNode> edit) {
        return edit.andThen(body -> body.put("agent_id", "agent-1").put("agent_type", "general-purpose"));
    }

    // ------------------------------------------------------------------------------------------------------------------ h

    @ParameterizedTest
    @MethodSource("hosts")
    void aMainSessionsPromptIsKeptWordForWordOnEveryHost(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        String text = "원문 ‘그대로’ <" + session + "> 😀\n둘째 줄\t끝";
        prompt(host, session, text);

        assertEquals(1, prompts(host, session));
        Map<String, Object> stored = row(host, session);
        assertEquals(text, stored.get("prompt_text"));
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, ((Number) stored.get("original_bytes")).longValue());
        assertEquals(Digests.sha256Hex(text), stored.get("prompt_digest"));
        assertEquals("COMPLETE", stored.get("completeness"));
    }

    @Test
    void aChildAgentsPromptIsNotKept() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        RawHttp.Response response = post(CLAUDE, CLAUDE.promptEvent(), session, childOf(promptText(CLAUDE, "the child's own prompt")));
        assertEquals(200, response.status());
        assertEquals(0, prompts(CLAUDE, session));
        assertEquals(0, count("SELECT count(*) FROM sennard.user_prompt WHERE prompt_text = ?", "the child's own prompt"));
    }

    @Test
    void antigravityCannotArchiveBeforeItsHookConversationIsObserved() throws IOException {
        String child = SessionServerSupport.uniqueSession();
        String root = SessionServerSupport.uniqueSession();
        postedPrompt(AGY, child, "input before the actor is known");
        assertEquals(0, prompts(AGY, child));

        DomainServerSupport.observeAgy(server, child, root, root);
        assertEquals(0, prompts(AGY, child));
        postedPrompt(AGY, child, "input of the now-linked child");
        assertEquals(0, prompts(AGY, child));

        DomainServerSupport.observeAgyRoot(server, root);
        answer(post(AGY, AGY.promptEvent(), root, body -> {
            body.put("invocationNum", 0);
            body.remove("lastUserInput");
        }));
        String original = "원문 😀\n둘째 줄";
        postedPrompt(AGY, root, original);
        assertEquals(1, prompts(AGY, root));
        assertEquals(original, row(AGY, root).get("prompt_text"));
    }

    @Test
    void antigravityArchivesTheFirstInputWhenItsRootIsConfirmed() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        String original = "첫 요청 😀\n  둘째 줄  ";
        postedPrompt(AGY, session, original);
        assertEquals(0, prompts(AGY, session));

        DomainServerSupport.observeAgyRoot(server, session);
        assertEquals(1, prompts(AGY, session));
        assertEquals(original, row(AGY, session).get("prompt_text"));
        DomainServerSupport.observeAgyRoot(server, session);
        assertEquals(1, prompts(AGY, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void anEmptyPromptIsAPromptToo(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "");
        assertEquals(1, prompts(host, session));
        assertEquals("", row(host, session).get("prompt_text"));
        assertEquals(0L, ((Number) row(host, session).get("original_bytes")).longValue());
    }

    @Test
    void aPromptOverTheConfiguredSizeIsCutAndKnownToBeCut() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        String whole = "가".repeat(1000); // 3000 bytes
        prompt(CLAUDE, session, whole);
        Map<String, Object> stored = row(CLAUDE, session);
        assertEquals("TRUNCATED", stored.get("completeness"));
        assertEquals(3000L, ((Number) stored.get("original_bytes")).longValue());
        assertEquals(Digests.sha256Hex(whole), stored.get("prompt_digest"));
        assertEquals(whole.substring(0, 682), stored.get("prompt_text"), "682 characters are 2046 bytes: the 683rd would pass 2048");
    }

    @Test
    void theTranscriptPathTheHostNamesIsKept() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(GEMINI, session, "where is the transcript");
        assertEquals("/tmp/chat-" + session + ".json", row(GEMINI, session).get("transcript_path"));
        String claude = SessionServerSupport.uniqueSession();
        prompt(CLAUDE, claude, "claude names it too");
        assertTrue(String.valueOf(row(CLAUDE, claude).get("transcript_path")).endsWith(claude + ".jsonl"), String.valueOf(row(CLAUDE, claude).get("transcript_path")));
    }

    @Test
    void aPromptThatCannotBeStoredIsAFailedHookNotAnAnswerAndTheNextPromptIsKept() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        RawHttp.Response failed = post(CLAUDE, CLAUDE.promptEvent(), session, promptText(CLAUDE, NO_NUL_PREFIX + "\u0000 after"));
        assertEquals(422, failed.status(), DomainServerSupport.text(failed));
        assertEquals("VALUE_NOT_ALLOWED", SessionServerSupport.json(failed).path("reasonCode").asString(), "the answer says why: the store refused the value (a NUL is not text the database keeps)");
        assertEquals(0, prompts(CLAUDE, session), "nothing was kept, and the server did not answer as if it were");

        prompt(CLAUDE, session, "an ordinary prompt");
        assertEquals(1, prompts(CLAUDE, session));
    }

    // ------------------------------------------------------------------------------------------------------------------ n

    @ParameterizedTest
    @MethodSource("hosts")
    void aTurnEndAsksOnceForTheRecapOfItsPrompt(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "do the thing " + session);
        if (host == GEMINI) {
            // ceiling: a call that holds only the session reference (Gemini CLI) is keyed by that reference in the continuity module, not by the host session id its hooks use, so its mark cannot
            // reach the turn the hooks recorded; the turn end asks the save check's question first, and its second one is the recap's.
            assertTrue(turnEnd(host, session).contains("[Sennard save check]"));
        } else {
            DomainServerSupport.markTurnUnchanged(server, host.label(), session);
        }

        String first = turnEnd(host, session);
        assertTrue(first.contains(QUESTION) && first.contains("record_turn_recap"), first);
        assertEquals(1, reminders(host, session));

        String second = turnEnd(host, session);
        assertFalse(second.contains(QUESTION), second);
        assertEquals(1, reminders(host, session), "once per prompt");
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aTurnEndOfASessionWithNoPromptAsksNothing(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        String answer = turnEnd(host, session);
        assertFalse(answer.contains(QUESTION), answer);
        assertEquals(0, reminders(host, session));
    }

    /** What the agent does after the recap's question: the recap of the session's newest prompt, as a row (the tool's own path is PromptToolsServerTest's subject). */
    private static void recapRecorded(Host host, String session) {
        String id = (String) SessionServerSupport.row(server, "SELECT user_prompt_id FROM sennard.user_prompt WHERE host = ? AND session_id = ?", host.sessionHost(), session).get("user_prompt_id");
        SessionServerSupport.update(server, "INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (?, ?, ?, 0)",
                id, "did it", Timestamps.toTimestamp(Instant.now()));
    }

    /** The summary on the session's board row (the row the hooks of its prompt made), for the hosts whose tool call the test does not make. */
    private static void summaryRecorded(Host host, String session) {
        summaryRecordedAt(host, session, Instant.now());
    }

    /** The same, written at the given time: a summary written long before the last prompt is the board's stale one, with no wait for the clock. */
    private static void summaryRecordedAt(Host host, String session, Instant at) {
        assertEquals(1, count("SELECT count(*) FROM sennard.board_sessions WHERE host = ? AND session_id = ?", host.sessionHost(), session), "the prompt's hook made the board row");
        SessionServerSupport.update(server, "UPDATE sennard.board_sessions SET summary = ?, summary_at = ? WHERE host = ? AND session_id = ?",
                "working on it", Timestamps.toTimestamp(at), host.sessionHost(), session);
    }

    /** The turn ends that come before the recap's and the summary's question on a host that cannot say its turn was marked (see the n1 case). */
    private static void passSaveCheck(Host host, String session) throws IOException {
        if (host == GEMINI) {
            assertTrue(turnEnd(host, session).contains("[Sennard save check]"));
        } else {
            DomainServerSupport.markTurnUnchanged(server, host.label(), session);
        }
    }

    @Test
    void aRecapAndASummaryThatAreThereStopTheQuestion() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(CLAUDE, session, "a prompt with a recap");
        DomainServerSupport.markTurnUnchanged(server, CLAUDE.label(), session);
        recapRecorded(CLAUDE, session);
        DomainServerSupport.writeSessionSummary(server, CLAUDE.label(), session, "written with the tool");

        String answer = turnEnd(CLAUDE, session);
        assertFalse(answer.contains(QUESTION) || answer.contains(SUMMARY_QUESTION), answer);
        assertEquals(0, reminders(CLAUDE, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSessionWithoutASummaryIsAskedForItWithTheRecapOnceAndWithoutTheRecapAtEveryTurnEndOnEveryHost(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        String start = prompt(host, session, "a prompt of a session with no summary " + session);
        assertTrue(start.contains(SUMMARY_QUESTION) && start.contains("update_session_status"), "asked at the request's start, before any work: " + start);
        assertFalse(start.contains(QUESTION), "the recap is a turn end's question: " + start);
        passSaveCheck(host, session);

        String first = turnEnd(host, session);
        assertTrue(first.contains(QUESTION) && first.contains("record_turn_recap") && first.contains(SUMMARY_QUESTION) && first.contains("update_session_status"), first);
        assertEquals(1, reminders(host, session));

        String second = turnEnd(host, session);
        assertFalse(second.contains(QUESTION), "the recap once per prompt: " + second);
        assertTrue(second.contains(SUMMARY_QUESTION), "the summary every time it is missing, also at a second turn end of the same prompt: " + second);
        assertEquals(1, reminders(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSessionWithTheRecapButNoSummaryIsAskedForTheSummaryAtEveryTurnEndUntilItIsThere(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "a prompt with a recap and no summary " + session);
        passSaveCheck(host, session);
        recapRecorded(host, session);

        String first = turnEnd(host, session);
        assertTrue(first.contains(SUMMARY_QUESTION) && first.contains("update_session_status"), first);
        assertFalse(first.contains(QUESTION), "the recap is there: " + first);
        assertTrue(turnEnd(host, session).contains(SUMMARY_QUESTION), "not once per prompt");

        summaryRecorded(host, session);
        assertFalse(turnEnd(host, session).contains(SUMMARY_QUESTION), "the summary is there: the question stops");
    }

    /** The hosts whose finished tool carries context to the next model call (Antigravity's does not: its context comes at the invocation boundaries). */
    static Stream<Host> toolHosts() {
        return Stream.of(CLAUDE, CODEX);
    }

    @ParameterizedTest
    @MethodSource("toolHosts")
    void aMissingSummaryIsAskedForAfterEveryFinishedToolUntilItIsThere(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "a long first turn " + session);

        String first = answer(post(host, "PostToolUse", session, body -> {
        }));
        assertTrue(first.contains(SUMMARY_QUESTION) && first.contains("update_session_status"), "asked as soon as the first tool finishes: " + first);
        assertTrue(answer(post(host, "PostToolUse", session, body -> {
        })).contains(SUMMARY_QUESTION), "and after the next one");

        summaryRecorded(host, session);
        assertFalse(answer(post(host, "PostToolUse", session, body -> {
        })).contains(SUMMARY_QUESTION), "the summary is there: the question stops");
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSummaryOlderThanTheLastPromptIsAskedToBeRewrittenAtTheStartAndEveryTurnEndUntilItIsNewer(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "the first prompt " + session);
        summaryRecordedAt(host, session, Instant.parse("2020-01-01T00:00:00Z"));
        passSaveCheck(host, session);
        recapRecorded(host, session);

        String start = prompt(host, session, "the second prompt, the summary is older than it " + session);
        assertTrue(start.contains(STALE_QUESTION) && start.contains("update_session_status"), "asked at the request's start: " + start);
        assertFalse(start.contains("has no summary"), "it has one, and it is old: " + start);
        passSaveCheck(host, session);

        String first = turnEnd(host, session);
        assertTrue(first.contains(STALE_QUESTION) && first.contains(QUESTION), "the turn end asks for the summary and, once, the recap of the new prompt: " + first);
        String second = turnEnd(host, session);
        assertTrue(second.contains(STALE_QUESTION), "the summary again at the next turn end: " + second);

        summaryRecorded(host, session);
        String after = turnEnd(host, session);
        assertFalse(after.contains(STALE_QUESTION) || after.contains(SUMMARY_QUESTION), "newer than the request: the question stops: " + after);
    }

    @ParameterizedTest
    @MethodSource("toolHosts")
    void aSummaryOlderThanTheLastPromptIsAskedToBeRewrittenAfterAFinishedTool(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "the first prompt " + session);
        summaryRecordedAt(host, session, Instant.parse("2020-01-01T00:00:00Z"));
        prompt(host, session, "the second prompt " + session);

        assertTrue(answer(post(host, "PostToolUse", session, body -> {
        })).contains(STALE_QUESTION), "asked after the first tool of the new request");

        summaryRecorded(host, session);
        assertFalse(answer(post(host, "PostToolUse", session, body -> {
        })).contains(STALE_QUESTION), "rewritten: the question stops");
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSessionWithASummaryIsAskedForTheRecapOnlyAndTheSummaryIsNotRenewed(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(host, session, "a prompt of a session with a summary " + session);
        summaryRecorded(host, session);
        passSaveCheck(host, session);

        String first = turnEnd(host, session);
        assertTrue(first.contains(QUESTION), first);
        assertFalse(first.contains(SUMMARY_QUESTION) || first.contains("update_session_status"), "a summary that is there is not asked for again: " + first);

        recapRecorded(host, session);
        String after = turnEnd(host, session);
        assertFalse(after.contains(QUESTION) || after.contains(SUMMARY_QUESTION), "the recap is there, the summary is there: " + after);
    }

    @Test
    void aChildTurnEndAndASubagentEndAskNothing() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(CLAUDE, session, "a prompt whose child ends");
        RawHttp.Response childStop = post(CLAUDE, "Stop", session, childOf(body -> {
        }));
        assertFalse(DomainServerSupport.text(childStop).contains(QUESTION) || DomainServerSupport.text(childStop).contains(SUMMARY_QUESTION), DomainServerSupport.text(childStop));
        RawHttp.Response subagentStop = DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStop", null, session, childOf(body -> {
        }));
        assertFalse(DomainServerSupport.text(subagentStop).contains(QUESTION) || DomainServerSupport.text(subagentStop).contains(SUMMARY_QUESTION), DomainServerSupport.text(subagentStop));
        assertEquals(0, reminders(CLAUDE, session), "only the main agent's turn end is asked (the session has no summary, and the child is not asked for it either)");
    }

    @Test
    void aTurnEndTheHostCallsAlreadyContinuedIsStillAskedAboutOnce() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(CLAUDE, session, "a prompt whose turn another domain continued");
        String first = answer(post(CLAUDE, "Stop", session, body -> body.put("stop_hook_active", true)));
        assertTrue(first.contains(QUESTION), first);
        String again = answer(post(CLAUDE, "Stop", session, body -> body.put("stop_hook_active", true)));
        assertFalse(again.contains(QUESTION), again);
        assertEquals(1, reminders(CLAUDE, session));
    }

    @Test
    void aStopTheHostCannotContinueIsNotContinuedForTheRecap() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        prompt(AGY, session, "a prompt the user cancels");
        DomainServerSupport.markTurnUnchanged(server, AGY.label(), session);
        String canceled = answer(post(AGY, "Stop", session, body -> body.put("terminationReason", "USER_CANCELED")));
        assertFalse(canceled.contains("decision") || canceled.contains(QUESTION), "a canceled turn is not continued: " + canceled);
        assertEquals(0, reminders(AGY, session), "the prompt's one question was not spent on a stop that could not carry it");

        String next = answer(post(AGY, "Stop", session, body -> {
        }));
        assertTrue(next.contains("continue") && next.contains(QUESTION) && next.contains("record_turn_recap"), "the next stop that can be continued asks: " + next);
        assertEquals(1, reminders(AGY, session), "asked once, at the stop that could carry it");
        String again = answer(post(AGY, "Stop", session, body -> {
        }));
        assertFalse(again.contains(QUESTION), again);
        assertEquals(1, reminders(AGY, session));
    }

    @Test
    void aNewPromptIsAskedAboutAgainOnce() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        prompt(CLAUDE, session, "first prompt");
        DomainServerSupport.markTurnUnchanged(server, CLAUDE.label(), session);
        assertTrue(turnEnd(CLAUDE, session).contains(QUESTION));
        Thread.sleep(20); // the newest prompt is the later millisecond
        prompt(CLAUDE, session, "second prompt");
        DomainServerSupport.markTurnUnchanged(server, CLAUDE.label(), session);
        assertTrue(turnEnd(CLAUDE, session).contains(QUESTION));
        assertFalse(turnEnd(CLAUDE, session).contains(QUESTION));
        assertEquals(2, reminders(CLAUDE, session), "one reminder for each prompt");
    }
}
