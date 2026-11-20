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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code read_prompt_transcript} on the assembled server (real PostgreSQL, a real MCP client under the host's label, the real hooks of the prompt archive). The user's words get in
 * through the Claude Code hook, whose {@code transcript_path} names a file this test writes (synthetic JSON Lines: a real transcript is never read); the tool is called by the
 * session that heard them, as a Claude Code call is made (the PreToolUse hook leaves the row the call is bound by). The server runs with {@code --sennard.prompt-archive.max-bytes=2KB}.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * s1  the turn, end to end   a Claude hook stores "please do the thing" with the transcript path; recall_user_prompts gives its promptId; read_prompt_transcript(promptId) -> status LOCATED,
 *                            host claude-code, the entries of the turn in the order written with the fields of their kind (AGENT_MESSAGE text, TOOL_CALL callId and toolName, TOOL_RESULT callId
 *                            and text, AGENT_MESSAGE), more false (the user's next message follows), no reason; the entries before the prompt and after the next message are not in it
 * s2  the anchor             the first call writes one transcript_anchor row (entry_key = the uuid of the user's line, no text of the transcript anywhere in it); the text of that line is then
 *                            changed in the file and the second call still answers LOCATED from the kept position (a search by text would find nothing); the row is not written again
 * s3  the limit              limit 2 of a longer turn -> 2 entries and more true; limit 1 and 100 pass; the default is 20
 * s4  not yet written        the hook stored the prompt but the file has no line of it -> NOT_YET_WRITTEN, no entries, no anchor row; once the line is written the same call -> LOCATED and one anchor
 * s5  unreadable             a transcript path that does not exist -> UNREADABLE, reason is the catalog sentence "The transcript file does not exist.", no anchor row; a file of no JSON -> UNREADABLE with the
 *                            sentence for a file that is no transcript; a hook that named no transcript -> UNREADABLE with the sentence that the host named none (each compared whole)
 * s6  another session        session B calls with the promptId of session A -> INVALID_INPUT TRANSCRIPT_OTHER_SESSION (details.reason OTHER_SESSION), the sentence says the message was said in
 *                            another session; nothing is read or kept (no anchor row); A's own call still works
 * s7  no such prompt         an unknown promptId -> INVALID_INPUT PROMPT_NOT_FOUND
 * s8  another host           a Codex session's prompt (its hook names a transcript file that exists) read by that session -> status UNSUPPORTED, host codex, the catalog sentence that names the host (compared whole); no anchor row
 * s9  a cut prompt           3000 bytes said with the 2 KB setting (the archive keeps the first 682 characters): the transcript holds the whole text -> LOCATED by the beginning
 * s10 the texts are cut      an agent text of 2500 characters -> 2000 characters and truncated true; a tool result likewise
 * s11 input                  no promptId, a blank promptId, 37 characters, limit 0, limit 101, an undeclared argument that names a file ("path") -> INVALID_INPUT, nothing read
 * s12 same words twice       "again" said twice in one session, a transcript with both lines: each promptId reads the turn of its own line (the time of the receipt tells them apart)
 * s13 keys and pages         every entry has a key (the uuid of its line; a line of several entries adds /<place>: a1, a1/1, a1/2); a turn of six entries on four lines is read with limit 2 and after =
 *                            the last key of the previous page, once inside a line: the pages together are the turn read at once; more false only on the page that reaches the user's next message
 * s14 the end                after = the last entry of an ended turn -> LOCATED, no entries, more false
 * s15 not in the turn        after = a key of an earlier turn, of a user line, of the next turn, of a place a line does not have, or no key at all -> INVALID_INPUT TRANSCRIPT_AFTER_NOT_IN_TURN
 *                            (details.after is the key, the sentence says it is not an entry of this message's turn); a key of the turn still works afterwards
 * s16 the boundary           session B calls with A's promptId and an after -> INVALID_INPUT TRANSCRIPT_OTHER_SESSION, nothing kept; A reads on from its key
 * s17 another turn           a key of the first turn with the second turn's promptId (and the reverse) -> TRANSCRIPT_AFTER_NOT_IN_TURN; the right pairs read on
 * s18 the contract           after of "", of 129 characters and of a number -> INVALID_INPUT from the contract, nothing read
 * </pre>
 * The unit cases of the reader (a broken line, a subagent's lines, an injected line, a tool result with blocks, the boundaries of the window) are ClaudeTranscriptTest's.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class PromptTranscriptServerTest {
    private static final String SENNARD_TOOL = "mcp__plugin_sennard_sennard__";

    private static ToolServerSupport sennard;
    private static McpSyncClient claude;

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.startWith("--sennard.prompt-archive.max-bytes=2KB");
        claude = DomainServerSupport.client(sennard.server.port, "claude");
    }

    @AfterAll
    static void stop() {
        claude.close();
        sennard.close();
    }

    // ------------------------------------------------------------------------------------------------------------------ the synthetic transcript

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> user(String session, String uuid, Object content) {
        return map("type", "user", "uuid", uuid, "sessionId", session, "timestamp", Instant.now().toString(), "isSidechain", false, "message", map("role", "user", "content", content));
    }

    private static Map<String, Object> assistant(String session, String uuid, Object... blocks) {
        return map("type", "assistant", "uuid", uuid, "sessionId", session, "timestamp", Instant.now().toString(), "isSidechain", false, "message",
                map("role", "assistant", "model", "a-model", "content", List.of(blocks)));
    }

    private static Map<String, Object> toolResult(String session, String uuid, String callId, String text) {
        return user(session, uuid, List.of(map("type", "tool_result", "tool_use_id", callId, "content", text)));
    }

    private static Map<String, Object> text(String text) {
        return map("type", "text", "text", text);
    }

    private static Map<String, Object> toolUse(String id, String name) {
        return map("type", "tool_use", "id", id, "name", name, "input", Map.of());
    }

    private static String line(Object entry) {
        return ToolServerSupport.MAPPER.writeValueAsString(entry);
    }

    private static Path write(String name, Object... lines) throws IOException {
        List<String> text = new ArrayList<>();
        for (Object entry : lines) {
            text.add(entry instanceof String raw ? raw : line(entry));
        }
        Path file = dir.resolve(name + "-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(file, String.join("\n", text) + "\n");
        return file;
    }

    // ------------------------------------------------------------------------------------------------------------------ the server

    private static String claudeSession() {
        return SessionServerSupport.uniqueSession();
    }

    /** The Claude Code hook of a prompt, with the transcript the host names (null: the hook names none). */
    private static void said(String session, String prompt, Path transcript) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(sennard.server.port, "windows", "claude", "UserPromptSubmit", null, session, body -> {
            body.put("prompt", prompt);
            if (transcript == null) {
                body.remove("transcript_path");
            } else {
                body.put("transcript_path", transcript.toString());
            }
        });
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Map<String, Object> arguments(Object... pairs) {
        return DomainServerSupport.arguments(pairs);
    }

    /** A call by a Claude Code session: the PreToolUse hook leaves the row that binds the call, as the host does before it. */
    private static JsonNode asClaude(String session, String tool, Map<String, Object> arguments) throws IOException {
        String toolUse = "toolu_" + UUID.randomUUID();
        String body = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8).replace("mcp__plugin_sennard-probe_probe__probe_echo", SENNARD_TOOL + tool);
        RawHttp.Response admitted = SessionServerSupport.post(sennard.server.port, "/hooks/v1/claude/PreToolUse/governance", body.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
        return DomainServerSupport.call(claude, tool, arguments, SessionServerSupport.meta("windows", "claude", session, toolUse));
    }

    private static String promptIdOfNewest(String session) throws IOException {
        JsonNode recalled = data(asClaude(session, "recall_user_prompts", arguments()));
        return recalled.path("prompts").get(0).path("promptId").asString();
    }

    private static JsonNode transcript(String session, String promptId, Object... more) throws IOException {
        Map<String, Object> arguments = arguments("promptId", promptId);
        for (int index = 0; index + 1 < more.length; index += 2) {
            arguments.put((String) more[index], more[index + 1]);
        }
        return asClaude(session, "read_prompt_transcript", arguments);
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
    }

    private static void assertRefused(JsonNode answer, String code, String reason) {
        assertFalse(answer.path("ok").asBoolean(), answer.toString());
        assertEquals(code, answer.path("error").path("code").asString(), answer.toString());
        if (reason != null) {
            assertEquals(reason, answer.path("error").path("details").path("reasonCode").asString(), answer.toString());
        }
    }

    private static long anchors(String promptId) {
        return SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.transcript_anchor WHERE user_prompt_id = ?", promptId);
    }

    private static List<String> kinds(JsonNode data) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode entry : data.path("entries")) {
            kinds.add(entry.path("kind").asString());
        }
        return kinds;
    }

    // ------------------------------------------------------------------------------------------------------------------ the cases

    @Test
    void aTurnIsReadBackFromTheHostsTranscriptForTheSessionThatHeardIt() throws Exception {
        String session = claudeSession();
        Path file = write("turn", user(session, "u0", "an earlier message"), assistant(session, "a0", text("an earlier answer")),
                user(session, "u1", "please do the thing"),
                assistant(session, "a1", text("I will read the file"), toolUse("toolu_r1", "Read")),
                toolResult(session, "r1", "toolu_r1", "the contents of the file"),
                assistant(session, "a2", text("done: nothing to change")),
                user(session, "u2", "the next message"), assistant(session, "a3", text("the next answer")));
        said(session, "please do the thing", file);
        String promptId = promptIdOfNewest(session);

        JsonNode answer = data(transcript(session, promptId));
        assertEquals(promptId, answer.path("promptId").asString());
        assertEquals("claude-code", answer.path("host").asString());
        assertEquals("LOCATED", answer.path("status").asString());
        assertFalse(answer.path("more").asBoolean(), "the user's next message follows: the turn ended");
        assertFalse(answer.has("reason"), answer.toString());
        assertEquals(List.of("AGENT_MESSAGE", "TOOL_CALL", "TOOL_RESULT", "AGENT_MESSAGE"), kinds(answer));
        JsonNode entries = answer.path("entries");
        assertEquals("I will read the file", entries.get(0).path("text").asString());
        assertFalse(entries.get(0).path("truncated").asBoolean());
        assertEquals("toolu_r1", entries.get(1).path("callId").asString());
        assertEquals("Read", entries.get(1).path("toolName").asString());
        assertEquals("toolu_r1", entries.get(2).path("callId").asString());
        assertEquals("the contents of the file", entries.get(2).path("text").asString());
        assertEquals("done: nothing to change", entries.get(3).path("text").asString());
        assertFalse(answer.toString().contains("an earlier answer") || answer.toString().contains("the next answer"), "only this turn is returned");
    }

    @Test
    void theFirstCallKeepsOnlyThePositionAndTheSecondReadsFromIt() throws Exception {
        String session = claudeSession();
        Path file = write("anchor", user(session, "uuid-of-the-user-line", "a prompt to anchor"), assistant(session, "a1", text("the answer text of the transcript")));
        said(session, "a prompt to anchor", file);
        String promptId = promptIdOfNewest(session);
        assertEquals(0, anchors(promptId));

        assertEquals("LOCATED", data(transcript(session, promptId)).path("status").asString());
        assertEquals(1, anchors(promptId));
        Map<String, Object> row = SessionServerSupport.row(sennard.server, "SELECT entry_key, located_at FROM sennard.transcript_anchor WHERE user_prompt_id = ?", promptId);
        assertEquals("uuid-of-the-user-line", row.get("entry_key"));
        assertEquals(3, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'transcript_anchor'"),
                "prompt, entry key and time: nothing of the transcript's text has a column");
        assertEquals(0, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.transcript_anchor WHERE entry_key LIKE '%answer text%'"));

        Files.writeString(file, Files.readString(file).replace("a prompt to anchor", "the text is no longer the same"));
        JsonNode second = data(transcript(session, promptId));
        assertEquals("LOCATED", second.path("status").asString(), "found from the kept position, not by the text");
        assertEquals("the answer text of the transcript", second.path("entries").get(0).path("text").asString());
        assertEquals(1, anchors(promptId), "the position is not written again");
        assertEquals(row.get("located_at"), SessionServerSupport.row(sennard.server, "SELECT entry_key, located_at FROM sennard.transcript_anchor WHERE user_prompt_id = ?", promptId).get("located_at"));
    }

    @Test
    void theLimitStopsTheReadAndMoreSaysTheTurnGoesOn() throws Exception {
        String session = claudeSession();
        Path file = write("limit", user(session, "u1", "go on"), assistant(session, "a1", text("one"), text("two"), text("three")), assistant(session, "a2", text("four")));
        said(session, "go on", file);
        String promptId = promptIdOfNewest(session);

        JsonNode two = data(transcript(session, promptId, "limit", 2));
        assertEquals(2, two.path("entries").size());
        assertTrue(two.path("more").asBoolean());
        JsonNode one = data(transcript(session, promptId, "limit", 1));
        assertEquals(1, one.path("entries").size());
        JsonNode all = data(transcript(session, promptId, "limit", 100));
        assertEquals(4, all.path("entries").size());
        assertTrue(all.path("more").asBoolean(), "the file ends before the user's next message: the turn may still be written");
        assertEquals(4, data(transcript(session, promptId)).path("entries").size(), "the default holds this turn");
    }

    @Test
    void anEntryThatIsNotWrittenYetIsToldSoAndFoundOnceItIs() throws Exception {
        String session = claudeSession();
        Path file = write("late", user(session, "other", "something else entirely"));
        said(session, "the prompt that is late", file);
        String promptId = promptIdOfNewest(session);

        JsonNode early = data(transcript(session, promptId));
        assertEquals("NOT_YET_WRITTEN", early.path("status").asString());
        assertEquals(0, early.path("entries").size());
        assertFalse(early.path("more").asBoolean());
        assertEquals(0, anchors(promptId), "nothing was found, so nothing is kept");

        Files.writeString(file, Files.readString(file) + line(user(session, "late-uuid", "the prompt that is late")) + "\n" + line(assistant(session, "a1", text("answered"))) + "\n");
        JsonNode later = data(transcript(session, promptId));
        assertEquals("LOCATED", later.path("status").asString());
        assertEquals(List.of("AGENT_MESSAGE"), kinds(later));
        assertEquals(1, anchors(promptId));
    }

    @Test
    void aTranscriptThatCannotBeReadIsUnreadableWithItsReason() throws Exception {
        String session = claudeSession();
        said(session, "no such file", dir.resolve("does-not-exist.jsonl"));
        String missing = promptIdOfNewest(session);
        JsonNode gone = data(transcript(session, missing));
        assertEquals("UNREADABLE", gone.path("status").asString());
        assertEquals("The transcript file does not exist.", gone.path("reason").asString());
        assertEquals(0, gone.path("entries").size());
        assertEquals(0, anchors(missing));

        Path garbage = write("garbage", "not json", "{\"half\":");
        said(session, "a file that is no transcript", garbage);
        JsonNode notJson = data(transcript(session, promptIdOfNewest(session)));
        assertEquals("UNREADABLE", notJson.path("status").asString());
        assertEquals("The file is not in the transcript format of the host.", notJson.path("reason").asString(), notJson.toString());

        said(session, "the host named no transcript", null);
        JsonNode none = data(transcript(session, promptIdOfNewest(session)));
        assertEquals("UNREADABLE", none.path("status").asString());
        assertEquals("The host named no transcript for this prompt.", none.path("reason").asString(), none.toString());
    }

    @Test
    void anotherSessionsPromptIsRefusedAndNothingIsReadOrKept() throws Exception {
        String owner = claudeSession();
        String other = claudeSession();
        Path file = write("private", user(owner, "u1", "said in the first session"), assistant(owner, "a1", text("the first session's work")));
        said(owner, "said in the first session", file);
        said(other, "said in the second session", write("other", user(other, "x1", "said in the second session")));
        String promptId = promptIdOfNewest(owner);

        JsonNode refused = transcript(other, promptId);
        assertRefused(refused, "INVALID_INPUT", "TRANSCRIPT_OTHER_SESSION");
        assertEquals("OTHER_SESSION", refused.path("error").path("details").path("reason").asString(), refused.toString());
        assertTrue(refused.path("error").path("message").asString().contains("another session"), refused.toString());
        assertEquals(0, anchors(promptId), "a refused call keeps nothing");
        assertEquals("LOCATED", data(transcript(owner, promptId)).path("status").asString(), "the session that heard it still reads it");
    }

    @Test
    void aPromptThatDoesNotExistIsRefused() throws Exception {
        String session = claudeSession();
        said(session, "a prompt", write("exists", user(session, "u1", "a prompt")));
        assertRefused(transcript(session, UUID.randomUUID().toString()), "INVALID_INPUT", "PROMPT_NOT_FOUND");
    }

    @Test
    void aCodexPromptIsUnsupportedAndItsFileIsNotRead() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        Path file = write("codex", user(session, "u1", "said to codex"), assistant(session, "a1", text("codex's answer")));
        RawHttp.Response heard = DomainServerSupport.hook(sennard.server.port, "windows", "codex", "UserPromptSubmit", null, session,
                body -> body.put("prompt", "said to codex").put("transcript_path", file.toString()));
        assertEquals(200, heard.status(), DomainServerSupport.text(heard));
        JsonNode recalled = data(sennard.call("recall_user_prompts", arguments(), session));
        String promptId = recalled.path("prompts").get(0).path("promptId").asString();

        JsonNode answer = data(sennard.call("read_prompt_transcript", arguments("promptId", promptId), session));
        assertEquals("UNSUPPORTED", answer.path("status").asString());
        assertEquals("codex", answer.path("host").asString());
        assertEquals(0, answer.path("entries").size());
        assertEquals("This server does not read the transcripts of the host codex yet.", answer.path("reason").asString(), answer.toString());
        assertEquals(0, anchors(promptId));
    }

    @Test
    void aPromptKeptCutIsFoundByItsBeginning() throws Exception {
        String session = claudeSession();
        String whole = "가".repeat(1000);
        Path file = write("cut", user(session, "u1", whole), assistant(session, "a1", text("answered the long one")));
        said(session, whole, file);
        String promptId = promptIdOfNewest(session);
        JsonNode recalled = data(asClaude(session, "recall_user_prompts", arguments())).path("prompts").get(0);
        assertTrue(recalled.path("truncated").asBoolean(), "the archive kept only the first part");
        assertEquals(682, recalled.path("prompt").asString().length());

        JsonNode answer = data(transcript(session, promptId));
        assertEquals("LOCATED", answer.path("status").asString());
        assertEquals("answered the long one", answer.path("entries").get(0).path("text").asString());
    }

    @Test
    void everyTextOfAnEntryIsCutAtTwoThousandCharacters() throws Exception {
        String session = claudeSession();
        Path file = write("long", user(session, "u1", "write a lot"), assistant(session, "a1", text("x".repeat(2500))), toolResult(session, "r1", "toolu_l", "y".repeat(2001)));
        said(session, "write a lot", file);
        JsonNode answer = data(transcript(session, promptIdOfNewest(session)));
        JsonNode message = answer.path("entries").get(0);
        assertEquals("x".repeat(2000), message.path("text").asString());
        assertTrue(message.path("truncated").asBoolean());
        JsonNode result = answer.path("entries").get(1);
        assertEquals("y".repeat(2000), result.path("text").asString());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void inputOutsideTheContractIsRefused() throws Exception {
        String session = claudeSession();
        said(session, "a prompt", write("input", user(session, "u1", "a prompt")));
        String promptId = promptIdOfNewest(session);
        Map<String, Object> noPrompt = arguments();
        Map<String, Object> blank = arguments("promptId", " ");
        Map<String, Object> tooLong = arguments("promptId", "p".repeat(37));
        Map<String, Object> zero = arguments("promptId", promptId, "limit", 0);
        Map<String, Object> over = arguments("promptId", promptId, "limit", 101);
        Map<String, Object> aFile = new HashMap<>(arguments("promptId", promptId));
        aFile.put("path", "/etc/hosts");
        for (Map<String, Object> bad : List.of(noPrompt, blank, tooLong, zero, over, aFile)) {
            assertRefused(asClaude(session, "read_prompt_transcript", bad), "INVALID_INPUT", null);
        }
        assertEquals(0, anchors(promptId));
    }

    private static List<String> keys(JsonNode data) {
        List<String> keys = new ArrayList<>();
        for (JsonNode entry : data.path("entries")) {
            keys.add(entry.path("key").asString());
        }
        return keys;
    }

    /** A turn of six entries on four lines (a line of three texts, a tool call, its result, a closing text) and the user's next message after it. */
    private static Path turnOfSix(String session) throws IOException {
        return write("pages", user(session, "u0", "an earlier message"), assistant(session, "a0", text("an earlier answer")), user(session, "u1", "page through this"),
                assistant(session, "a1", text("one"), text("two"), text("three")), assistant(session, "a2", toolUse("toolu_p", "Read")), toolResult(session, "r1", "toolu_p", "the text"),
                assistant(session, "a3", text("six")), user(session, "u2", "the next message"), assistant(session, "a4", text("the next answer")));
    }

    @Test
    void everyEntryCarriesItsKeyAndTheTurnIsReadInPagesFromTheLastKeyOfThePrevious() throws Exception {
        String session = claudeSession();
        said(session, "page through this", turnOfSix(session));
        String promptId = promptIdOfNewest(session);

        JsonNode all = data(transcript(session, promptId));
        assertEquals(List.of("a1", "a1/1", "a1/2", "a2", "r1", "a3"), keys(all), "the key of an entry is its line's uuid, and a line of several entries adds /<place>");
        assertFalse(all.path("more").asBoolean());

        JsonNode first = data(transcript(session, promptId, "limit", 2));
        assertEquals(List.of("a1", "a1/1"), keys(first));
        assertTrue(first.path("more").asBoolean());
        JsonNode second = data(transcript(session, promptId, "limit", 2, "after", "a1/1"));
        assertEquals("LOCATED", second.path("status").asString());
        assertEquals(List.of("a1/2", "a2"), keys(second), "it goes on inside the line the first page stopped in");
        assertEquals("three", second.path("entries").get(0).path("text").asString());
        assertTrue(second.path("more").asBoolean());
        JsonNode third = data(transcript(session, promptId, "limit", 2, "after", "a2"));
        assertEquals(List.of("r1", "a3"), keys(third));
        assertFalse(third.path("more").asBoolean(), "the user's next message follows: the turn ended");
        assertEquals("the text", third.path("entries").get(0).path("text").asString());
        assertEquals(1, anchors(promptId), "continuing keeps no position of its own: the one anchor of the prompt");
    }

    @Test
    void continuingAfterTheLastEntryOfAnEndedTurnAnswersNothingMore() throws Exception {
        String session = claudeSession();
        said(session, "page through this", turnOfSix(session));
        JsonNode end = data(transcript(session, promptIdOfNewest(session), "after", "a3"));
        assertEquals("LOCATED", end.path("status").asString());
        assertEquals(0, end.path("entries").size());
        assertFalse(end.path("more").asBoolean());
    }

    @Test
    void aKeyThatIsNotInTheTurnOfThePromptIsRefused() throws Exception {
        String session = claudeSession();
        said(session, "page through this", turnOfSix(session));
        String promptId = promptIdOfNewest(session);
        for (String key : new String[] {"a0", "u0", "u1", "u2", "a4", "a1/3", "no-such-entry"}) {
            JsonNode refused = transcript(session, promptId, "after", key);
            assertRefused(refused, "INVALID_INPUT", "TRANSCRIPT_AFTER_NOT_IN_TURN");
            assertEquals(key, refused.path("error").path("details").path("after").asString(), refused.toString());
            assertTrue(refused.path("error").path("message").asString().contains("not an entry of this message's turn"), refused.toString());
        }
        assertEquals("LOCATED", data(transcript(session, promptId, "after", "a2")).path("status").asString(), "a key of the turn still works after the refusals");
    }

    @Test
    void continuingKeepsTheBoundaryOfTheSessionThatHeardTheMessage() throws Exception {
        String owner = claudeSession();
        String other = claudeSession();
        said(owner, "page through this", turnOfSix(owner));
        said(other, "said in the second session", write("other-pages", user(other, "x1", "said in the second session"), assistant(other, "x2", text("the second session's work"))));
        String promptId = promptIdOfNewest(owner);

        assertRefused(transcript(other, promptId, "after", "a1"), "INVALID_INPUT", "TRANSCRIPT_OTHER_SESSION");
        assertEquals(0, anchors(promptId), "a refused call keeps nothing");
        assertEquals(List.of("a1/1", "a1/2", "a2", "r1", "a3"), keys(data(transcript(owner, promptId, "after", "a1"))));
    }

    @Test
    void aKeyOfAnotherTurnOfTheSameSessionIsNotInThisPromptsTurn() throws Exception {
        String session = claudeSession();
        Path file = write("two-turns", user(session, "t1", "first question"), assistant(session, "t1a", text("first answer"), text("more of it")), user(session, "t2", "second question"),
                assistant(session, "t2a", text("second answer")));
        said(session, "first question", file);
        String first = promptIdOfNewest(session);
        said(session, "second question", file);
        String second = promptIdOfNewest(session);

        assertRefused(transcript(session, second, "after", "t1a"), "INVALID_INPUT", "TRANSCRIPT_AFTER_NOT_IN_TURN");
        assertRefused(transcript(session, first, "after", "t2a"), "INVALID_INPUT", "TRANSCRIPT_AFTER_NOT_IN_TURN");
        assertEquals(List.of("t1a/1"), keys(data(transcript(session, first, "after", "t1a"))));
        assertEquals(List.of(), keys(data(transcript(session, second, "after", "t2a"))));
    }

    @Test
    void anAfterOutsideTheContractIsRefused() throws Exception {
        String session = claudeSession();
        said(session, "a prompt", write("after-input", user(session, "u1", "a prompt")));
        String promptId = promptIdOfNewest(session);
        for (Object bad : new Object[] {"", "k".repeat(129), 7}) {
            assertRefused(asClaude(session, "read_prompt_transcript", arguments("promptId", promptId, "after", bad)), "INVALID_INPUT", null);
        }
        assertEquals(0, anchors(promptId));
    }

    @Test
    void theSameWordsSaidTwiceEachReadTheTurnOfTheirOwnLine() throws Exception {
        String session = claudeSession();
        Path file = write("twice", user(session, "first", "again"), assistant(session, "a1", text("first answer")));
        said(session, "again", file);
        String first = promptIdOfNewest(session);
        assertEquals(List.of("AGENT_MESSAGE"), kinds(data(transcript(session, first))));

        // The user says the same words again: this transcript holds both lines, the first written a minute before the second receipt (outside its window), the second now.
        Path both = write("twice-both", map("type", "user", "uuid", "first", "sessionId", session, "timestamp", Instant.now().minusSeconds(60).toString(), "isSidechain", false,
                "message", map("role", "user", "content", "again")), assistant(session, "a1", text("first answer")), user(session, "second", "again"), assistant(session, "a2", text("second answer")));
        said(session, "again", both);
        String second = promptIdOfNewest(session);
        assertFalse(first.equals(second));
        JsonNode answer = data(transcript(session, second));
        assertEquals("LOCATED", answer.path("status").asString());
        assertEquals("second answer", answer.path("entries").get(0).path("text").asString());
        assertEquals("first", SessionServerSupport.row(sennard.server, "SELECT entry_key FROM sennard.transcript_anchor WHERE user_prompt_id = ?", first).get("entry_key"));
        assertEquals("second", SessionServerSupport.row(sennard.server, "SELECT entry_key FROM sennard.transcript_anchor WHERE user_prompt_id = ?", second).get("entry_key"));
    }
}
