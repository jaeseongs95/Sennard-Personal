// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code record_turn_recap} and {@code recall_user_prompts} on the assembled server (real PostgreSQL, a real MCP client with a host's recorded {@code _meta}, the real hooks of the
 * prompt archive). The user's words get in through the hooks only, as they do in use; the tools are called by the sessions that heard them. The server runs with
 * {@code --sennard.prompt-archive.max-bytes=2KB}.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * k1  record                  after a prompt, record_turn_recap("did the thing") -> ok: promptId of that prompt, the recap, recordedAt (UTC text), replaced false, a sharedContextHint that names
 *                             checkpoint_context and checkpoint_role_context; one turn_recap row; again with another text -> replaced true and the second text is kept
 * k2  trimmed, the limit      "  padded  " is kept trimmed; 100 Hangul pass; 101 Hangul (weight 505) -> INVALID_INPUT RECAP_INVALID, reason TOO_LONG; "   " -> RECAP_INVALID BLANK; 251
 *                             letters -> INVALID_INPUT from the contract (maxLength 250); nothing is written by a refusal
 * k5  a subagent              a Codex subagent's record_turn_recap (its thread names the session's as parent) -> INVALID_INPUT RECAP_NOT_ROOT, the sentence names the main agent, nothing
 *                             recorded or replaced; the main agent's call of the same prompt succeeds; a subagent's recall_user_prompts still reads. (An actor that cannot be told is the root.)
 *                             The same on Claude Code: a call whose PreToolUse row carries agent_id (the host marks its subagent's calls that way) -> RECAP_NOT_ROOT and no recap row; the main
 *                             agent's call (no agent_id) is recorded and the subagent's second call does not replace it
 * k3  no prompt              a session the hooks saw but that has no prompt -> INVALID_INPUT PROMPT_NOT_ARCHIVED
 * k4  the newest prompt       two prompts, a recap -> it belongs to the second; recall shows it there and not on the first
 * r1  recall, the session     recall_user_prompts with no arguments -> this session's prompts, newest first, each promptId, host, sessionId, receivedAt, prompt, truncated false,
 *                             originalBytes, recap when recorded and no recap key when not; another session's prompts are not in it
 * r2  query and limit         query "ALPHA" finds "alpha beta" (only the case of ASCII A-Z is ignored); a query that matches nothing -> an empty list; limit 1 -> the newest one
 * r3  a cut prompt            3000 bytes with the 2 KB setting -> truncated true, originalBytes 3000, the kept part is the first 682 characters
 * r4  the role, unbound       scope ROLE from a session with no signed binding -> INVALID_INPUT ROLE_NOT_BOUND
 * r5  the role, bound         sessions A and B bound to one role, C to another, D to none: ROLE from A -> the prompts of A and B, not C's, not D's; ROLE from B the same;
 *                             SESSION from A -> only A's
 * r6  bad input               scope "ALL", limit 0, limit 101, an undeclared argument, query of 201 characters -> INVALID_INPUT, no data
 * g1  Gemini, by reference    a Gemini CLI prompt (BeforeAgent hook), then record_turn_recap and recall_user_prompts by a call that names only the session reference (the host
 *                             sends no session in its call): the recap is on that prompt and the recall finds it under the key the hook stored (gemini, the host's session id)
 * g2  a session with no      a session opened by open_session has had no prompt: record -> INVALID_INPUT PROMPT_NOT_ARCHIVED; recall -> an empty list (a session with no prompt yet is not
 *     prompt                 an error). A reference the server does not know -> a refusal (BINDING_REQUIRED), never an empty answer. (PROMPT_SESSION_UNHOOKED, for a registered
 *                            session that carries no host session id, is not reachable, because every session is host observed.)
 * </pre>
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class PromptToolsServerTest {
    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.startWith("--sennard.prompt-archive.max-bytes=2KB");
        owner = sennard.owner();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    // ------------------------------------------------------------------------------------------------------------------ helpers

    private static String codexSession() {
        return SessionServerSupport.uniqueSession();
    }

    private static void said(String session, String text) throws IOException {
        Consumer<ObjectNode> edit = body -> body.put("prompt", text);
        RawHttp.Response response = DomainServerSupport.hook(sennard.server.port, "windows", "codex", "UserPromptSubmit", null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        sleepAMillisecond();
    }

    /** The newest prompt is the later millisecond. */
    private static void sleepAMillisecond() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        map.put("schemaVersion", "1.0.0");
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static JsonNode record(String session, String recap) {
        return sennard.call("record_turn_recap", arguments("recap", recap), session);
    }

    private static JsonNode recall(String session, Object... pairs) {
        return sennard.call("recall_user_prompts", arguments(pairs), session);
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

    private static List<String> texts(JsonNode recalled) {
        List<String> texts = new java.util.ArrayList<>();
        for (JsonNode prompt : recalled.path("prompts")) {
            texts.add(prompt.path("prompt").asString());
        }
        return texts;
    }

    private static long count(String sql, Object... params) {
        return SessionServerSupport.count(sennard.server, sql, params);
    }

    private static String role(String name) throws Exception {
        String roleId = "role-" + UUID.randomUUID();
        sennard.sign(owner, ToolServerSupport.roleAdd(roleId, name + "-" + roleId.substring(5)));
        return roleId;
    }

    private static void bind(String session, String roleId) throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "BINDING_SET").put("host", "codex").put("sessionId", session);
        if (roleId == null) {
            change.putNull("roleId");
        } else {
            change.put("roleId", roleId);
        }
        sennard.sign(owner, change);
    }

    // ------------------------------------------------------------------------------------------------------------------ record_turn_recap

    @Test
    void aRecapIsRecordedForThePromptAndRecordingAgainReplacesIt() throws Exception {
        String session = codexSession();
        said(session, "please do the thing");

        JsonNode first = data(record(session, "did the thing"));
        assertEquals("did the thing", first.path("recap").asString());
        assertFalse(first.path("replaced").asBoolean());
        assertTrue(first.path("recordedAt").asString().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"), first.toString());
        String hint = first.path("sharedContextHint").asString();
        assertTrue(hint.contains("checkpoint_context") && hint.contains("checkpoint_role_context"), hint);
        String promptId = first.path("promptId").asString();
        assertEquals(1, count("SELECT count(*) FROM sennard.turn_recap WHERE user_prompt_id = ?", promptId));
        assertEquals(promptId, data(recall(session)).path("prompts").get(0).path("promptId").asString());

        JsonNode second = data(record(session, "did it another way"));
        assertTrue(second.path("replaced").asBoolean(), second.toString());
        assertEquals(promptId, second.path("promptId").asString());
        assertEquals("did it another way", data(recall(session)).path("prompts").get(0).path("recap").asString());
        assertEquals(1, count("SELECT count(*) FROM sennard.turn_recap WHERE user_prompt_id = ?", promptId));
    }

    @Test
    void aRecapIsTrimmedAndHeldToItsWeightedLimit() throws Exception {
        String session = codexSession();
        said(session, "a prompt");
        assertEquals("padded", data(record(session, "  padded  ")).path("recap").asString());
        assertEquals("가".repeat(100), data(record(session, "가".repeat(100))).path("recap").asString());

        JsonNode tooLong = record(session, "가".repeat(101));
        assertRefused(tooLong, "INVALID_INPUT", "RECAP_INVALID");
        assertEquals("TOO_LONG", tooLong.path("error").path("details").path("reason").asString(), tooLong.toString());
        assertEquals(505, tooLong.path("error").path("details").path("weight").asInt(), tooLong.toString());
        JsonNode blank = record(session, "   ");
        assertRefused(blank, "INVALID_INPUT", "RECAP_INVALID");
        assertEquals("BLANK", blank.path("error").path("details").path("reason").asString(), blank.toString());
        assertRefused(record(session, "a".repeat(251)), "INVALID_INPUT", null);
        assertEquals("가".repeat(100), data(recall(session)).path("prompts").get(0).path("recap").asString(), "a refusal changed nothing");
    }

    /** AGS3-D-0267: the recap that comes again with its operationKey is answered with the first one, and another content under the key is a conflict. */
    @Test
    void aRecapThatComesAgainWithItsKeyIsAnsweredWithTheFirstOneAndAnotherContentIsAConflict() throws Exception {
        String session = codexSession();
        said(session, "a prompt for a keyed recap");
        String key = "recap-key-" + UUID.randomUUID();
        Map<String, Object> keyed = arguments("recap", "the keyed recap", "operationKey", key);
        JsonNode first = data(sennard.call("record_turn_recap", keyed, session));
        JsonNode again = data(sennard.call("record_turn_recap", keyed, session));
        assertEquals(first, again, "the first answer again: replaced stays false, as a second recording under a new key would say true");
        assertFalse(again.path("replaced").asBoolean(true), again.toString());
        assertEquals(1, count("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'record_turn_recap' AND operation_key = ?", key));
        JsonNode other = sennard.call("record_turn_recap", arguments("recap", "another recap", "operationKey", key), session);
        assertFalse(other.path("ok").asBoolean(true), other.toString());
        assertEquals("REQUEST_CONFLICT", other.path("error").path("code").asString(), other.toString());
        assertEquals("the keyed recap", data(recall(session)).path("prompts").get(0).path("recap").asString(), "a conflict changed nothing");
        JsonNode missing = sennard.callAsSent("record_turn_recap", arguments("recap", "no key"), session);
        assertEquals("INVALID_INPUT", missing.path("error").path("code").asString(), missing.toString());
    }

    @Test
    void aSessionWithNoPromptHasNothingToAttachARecapTo() throws Exception {
        String session = codexSession();
        assertEquals(200, SessionServerSupport.post(sennard.server.port, "/hooks/v1/codex/SessionStart", SessionServerSupport.hook("windows", "codex", "SessionStart", session, "none")).status());
        assertRefused(record(session, "a recap"), "INVALID_INPUT", "PROMPT_NOT_ARCHIVED");
        assertEquals(0, count("SELECT count(*) FROM sennard.turn_recap r JOIN sennard.user_prompt p ON p.user_prompt_id = r.user_prompt_id "
                + "WHERE p.session_id = ?", session));
    }

    /** A call by a Codex subagent: its thread is not the session's and names the session's thread as its parent (CodexCallerAdapter). */
    private static JsonNode asChild(String tool, Map<String, Object> arguments, String session) {
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        String thread = UUID.randomUUID().toString();
        meta.put("threadId", thread);
        Map<String, Object> turn = new HashMap<>();
        ((Map<?, ?>) meta.get("x-codex-turn-metadata")).forEach((key, value) -> turn.put((String) key, value));
        turn.put("thread_id", thread);
        turn.put("parent_thread_id", session);
        meta.put("x-codex-turn-metadata", turn);
        McpSchema.CallToolResult result = sennard.callRaw(tool, arguments, meta);
        return ToolServerSupport.MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void aSubagentsRecapIsRefusedAndTheMainAgentsIsKept() throws Exception {
        String session = codexSession();
        said(session, "a prompt whose turn has a subagent");

        JsonNode refused = asChild("record_turn_recap", arguments("recap", "the child's own report"), session);
        assertRefused(refused, "INVALID_INPUT", "RECAP_NOT_ROOT");
        assertTrue(refused.path("error").path("message").asString().contains("main agent"), "the sentence says who records it: " + refused);
        assertEquals(0, count("SELECT count(*) FROM sennard.turn_recap r JOIN sennard.user_prompt p ON p.user_prompt_id = r.user_prompt_id "
                + "WHERE p.session_id = ?", session), "the child's report was not recorded");

        assertEquals("the main agent's summary", data(record(session, "the main agent's summary")).path("recap").asString());
        assertEquals("the main agent's summary", data(recall(session)).path("prompts").get(0).path("recap").asString());
        assertTrue(asChild("recall_user_prompts", arguments(), session).path("ok").asBoolean(), "a subagent may read what the user said");
        assertRefused(asChild("record_turn_recap", arguments("recap", "replacing it"), session), "INVALID_INPUT", "RECAP_NOT_ROOT");
        assertEquals("the main agent's summary", data(recall(session)).path("prompts").get(0).path("recap").asString(), "the child did not replace it");
    }

    /**
     * A call by a Claude Code session or by one of its subagents: the PreToolUse hook leaves the row that binds the call, and a subagent's hook carries {@code agent_id}, which is
     * what marks the actor as a child (ClaudeCallerAdapter reads the row, not the call).
     */
    private static JsonNode asClaude(McpSyncClient claude, String tool, Map<String, Object> arguments, String session, boolean subagent) throws IOException {
        String toolUse = "toolu_" + UUID.randomUUID();
        String body = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8)
                .replace("mcp__plugin_sennard-probe_probe__probe_echo", "mcp__plugin_sennard_sennard__" + tool);
        if (subagent) {
            body = ((ObjectNode) ToolServerSupport.MAPPER.readTree(body)).put("agent_id", "agent-" + UUID.randomUUID()).put("agent_type", "general-purpose").toString();
        }
        RawHttp.Response admitted = SessionServerSupport.post(sennard.server.port, "/hooks/v1/claude/PreToolUse/governance", body.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
        return DomainServerSupport.call(claude, tool, arguments, SessionServerSupport.meta("windows", "claude", session, toolUse));
    }

    @Test
    void aClaudeCodeSubagentsRecapIsRefusedAndTheMainAgentsIsKept() throws Exception {
        String session = codexSession();
        RawHttp.Response said = DomainServerSupport.hook(sennard.server.port, "windows", "claude", "UserPromptSubmit", null, session, body -> body.put("prompt", "a prompt whose turn has a Claude Code subagent"));
        assertEquals(200, said.status(), DomainServerSupport.text(said));
        sleepAMillisecond();
        try (McpSyncClient claude = DomainServerSupport.client(sennard.server.port, "claude")) {
            JsonNode refused = asClaude(claude, "record_turn_recap", arguments("recap", "the subagent's own report"), session, true);
            assertRefused(refused, "INVALID_INPUT", "RECAP_NOT_ROOT");
            assertEquals(0, count("SELECT count(*) FROM sennard.turn_recap r JOIN sennard.user_prompt p ON p.user_prompt_id = r.user_prompt_id "
                    + "WHERE p.session_id = ?", session), "the subagent's report was not recorded");

            assertEquals("the main agent's summary", data(asClaude(claude, "record_turn_recap", arguments("recap", "the main agent's summary"), session, false)).path("recap").asString());
            assertRefused(asClaude(claude, "record_turn_recap", arguments("recap", "replacing it"), session, true), "INVALID_INPUT", "RECAP_NOT_ROOT");
            assertEquals("the main agent's summary", data(asClaude(claude, "recall_user_prompts", arguments(), session, false)).path("prompts").get(0).path("recap").asString(),
                    "the subagent did not replace it");
        }
    }

    @Test
    void theRecapBelongsToTheNewestPrompt() throws Exception {
        String session = codexSession();
        said(session, "first prompt");
        said(session, "second prompt");
        record(session, "about the second");
        JsonNode prompts = data(recall(session)).path("prompts");
        assertEquals("second prompt", prompts.get(0).path("prompt").asString());
        assertEquals("about the second", prompts.get(0).path("recap").asString());
        assertEquals("first prompt", prompts.get(1).path("prompt").asString());
        assertFalse(prompts.get(1).has("recap"), "the first prompt has no recap key: " + prompts.get(1));
    }

    // ------------------------------------------------------------------------------------------------------------------ recall_user_prompts

    @Test
    void theSessionsOwnPromptsComeBackNewestFirstWithTheirFacts() throws Exception {
        String session = codexSession();
        String other = codexSession();
        said(session, "alpha beta");
        said(session, "gamma 한글 😀");
        said(other, "someone else's alpha");

        JsonNode answer = data(recall(session));
        assertEquals("SESSION", answer.path("scope").asString());
        assertEquals(List.of("gamma 한글 😀", "alpha beta"), texts(answer));
        JsonNode newest = answer.path("prompts").get(0);
        assertEquals("codex", newest.path("host").asString());
        assertEquals(session, newest.path("sessionId").asString());
        assertTrue(newest.path("receivedAt").asString().endsWith("Z"), newest.toString());
        assertFalse(newest.path("truncated").asBoolean());
        assertEquals("gamma 한글 😀".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, newest.path("originalBytes").asInt());
        assertFalse(newest.has("recap"));
        assertTrue(newest.path("promptId").asString().length() == 36, newest.toString());
    }

    @Test
    void aQueryFindsAPartWithoutRegardToCaseAndTheLimitKeepsTheNewest() throws Exception {
        String session = codexSession();
        said(session, "alpha beta");
        said(session, "gamma");
        said(session, "delta alpha");
        assertEquals(List.of("delta alpha", "alpha beta"), texts(data(recall(session, "query", "ALPHA"))));
        assertEquals(List.of(), texts(data(recall(session, "query", "no such text"))));
        assertEquals(List.of("delta alpha"), texts(data(recall(session, "limit", 1))));
        assertEquals(List.of("delta alpha", "alpha beta"), texts(data(recall(session, "query", "alpha", "limit", 5, "scope", "SESSION"))));
    }

    @Test
    void aRecallHonorsTheRequestedTimeInterval() throws Exception {
        String session = codexSession();
        said(session, "in range");
        String received = data(recall(session)).path("prompts").get(0).path("receivedAt").asString();
        String after = io.github.jaeseongs95.sennard.domain.Timestamps.format(java.time.Instant.parse(received).plusMillis(1));
        assertEquals(List.of("in range"), texts(data(recall(session, "from", received, "to", after))));
        assertEquals(List.of(), texts(data(recall(session, "to", received))));
        assertRefused(recall(session, "from", after, "to", received), "INVALID_INPUT", null);
        assertRefused(recall(session, "from", "not-a-date"), "INVALID_INPUT", null);
    }

    @Test
    void aPromptOverTheSizeComesBackCutAndMarkedCut() throws Exception {
        String session = codexSession();
        said(session, "가".repeat(1000));
        JsonNode hit = data(recall(session)).path("prompts").get(0);
        assertTrue(hit.path("truncated").asBoolean(), hit.toString());
        assertEquals(3000, hit.path("originalBytes").asInt());
        assertEquals("가".repeat(682), hit.path("prompt").asString());
    }

    @Test
    void aRoleRecallNeedsASignedBindingAndReadsOnlyTheSessionsOfThatRole() throws Exception {
        String a = codexSession();
        String b = codexSession();
        String c = codexSession();
        String d = codexSession();
        said(a, "from A");
        said(b, "from B");
        said(c, "from C");
        said(d, "from D");

        assertRefused(recall(a, "scope", "ROLE"), "INVALID_INPUT", "ROLE_NOT_BOUND");

        String shared = role("shared");
        String another = role("another");
        bind(a, shared);
        bind(b, shared);
        bind(c, another);

        assertEquals(List.of("from B", "from A"), texts(data(recall(a, "scope", "ROLE"))));
        assertEquals(List.of("from B", "from A"), texts(data(recall(b, "scope", "ROLE"))));
        assertEquals(List.of("from C"), texts(data(recall(c, "scope", "ROLE"))));
        assertRefused(recall(d, "scope", "ROLE"), "INVALID_INPUT", "ROLE_NOT_BOUND");
        assertEquals(List.of("from A"), texts(data(recall(a))), "the session scope reads the session only");
        assertEquals("ROLE", data(recall(a, "scope", "ROLE")).path("scope").asString());
        assertEquals(List.of("from B"), texts(data(recall(a, "scope", "ROLE", "query", "b"))));

        bind(b, null);
        assertEquals(List.of("from A"), texts(data(recall(a, "scope", "ROLE"))), "a session whose binding was withdrawn is no longer of the role");
    }

    @Test
    void inputOutsideTheContractIsRefused() throws Exception {
        String session = codexSession();
        said(session, "a prompt");
        for (Map<String, Object> bad : List.of(arguments("scope", "ALL"), arguments("limit", 0), arguments("limit", 101), arguments("query", "q".repeat(201)), arguments("bogus", 1),
                arguments("query", ""))) {
            assertRefused(sennard.call("recall_user_prompts", bad, session), "INVALID_INPUT", null);
        }
        assertRefused(sennard.call("record_turn_recap", arguments("recap", "x", "bogus", 1), session), "INVALID_INPUT", null);
        assertRefused(sennard.call("record_turn_recap", arguments(), session), "INVALID_INPUT", null);
    }

    /**
     * A range that ends before it starts (or where it starts: from is inclusive and to is exclusive) used to be answered "does not match its contract" with no details, and a time
     * that has the shape but does not exist the same. Each now has a sentence of its own, from the catalog, that says what was sent.
     */
    @Test
    void aTimeRangeThatIsEmptyOrBackwardsIsRefusedWithASentenceThatSaysWhatWasSent() throws Exception {
        String session = codexSession();
        said(session, "a prompt");
        String later = "2026-11-16T10:00:00.000Z";
        String earlier = "2026-11-16T09:00:00.000Z";
        for (JsonNode refused : List.of(sennard.call("recall_user_prompts", arguments("from", later, "to", earlier), session), sennard.call("recall_user_prompts", arguments("from", later, "to", later), session))) {
            assertRefused(refused, "INVALID_INPUT", "PROMPT_RANGE_INVALID");
            assertEquals("error.PROMPT_RANGE_INVALID", refused.path("error").path("messageKey").asString(), refused.toString());
            assertTrue(refused.path("error").path("message").asString().contains("from=" + later), refused.toString());
        }
        JsonNode oneEnd = sennard.call("recall_user_prompts", arguments("to", "0001-01-01T00:00:00.000Z"), session);
        assertRefused(oneEnd, "INVALID_INPUT", "PROMPT_RANGE_INVALID");
        assertTrue(oneEnd.path("error").path("message").asString().contains("from=-"), "an end that was not sent is shown as -: " + oneEnd);
        JsonNode impossible = sennard.call("recall_user_prompts", arguments("from", "2026-13-45T00:00:00.000Z"), session);
        assertRefused(impossible, "INVALID_INPUT", "PROMPT_TIME_UNREADABLE");
        assertEquals("error.PROMPT_TIME_UNREADABLE", impossible.path("error").path("messageKey").asString(), impossible.toString());
        assertTrue(data(sennard.call("recall_user_prompts", arguments("from", earlier, "to", later), session)).path("prompts").isArray(), "a range that is in order still reads");
    }

    // ------------------------------------------------------------------------------------------------------------------ the session key of a call that names the session by reference

    @Test
    void aGeminiPromptIsFoundAndRecappedByACallThatHoldsOnlyTheSessionReference() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode().put("session_id", session).put("transcript_path", "/tmp/gemini-" + session + ".json").put("cwd", "/work")
                .put("hook_event_name", "BeforeAgent").put("timestamp", "2026-11-11T00:00:00.000Z").put("prompt", "gemini heard this");
        RawHttp.Response heard = SessionServerSupport.post(sennard.server.port, "/hooks/v1/gemini/BeforeAgent", ToolServerSupport.MAPPER.writeValueAsBytes(body));
        assertEquals(200, heard.status(), DomainServerSupport.text(heard));
        assertEquals(1, count("SELECT count(*) FROM sennard.user_prompt WHERE host = 'gemini' AND session_id = ?", session), "the hook stored it under the host's session id");

        String reference = (String) SessionServerSupport.row(sennard.server, "SELECT session_ref FROM sennard.session_ref WHERE host = 'gemini' AND local_session_id = ?", session).get("session_ref");
        assertTrue(reference != null && !reference.equals(session), "the call holds the server's reference, which is not the host's id: " + reference);

        JsonNode recorded = data(sennard.callUnobserved("record_turn_recap", arguments("recap", "gemini recap", "sessionRef", reference)));
        assertEquals("gemini recap", recorded.path("recap").asString());
        JsonNode recalled = data(sennard.callUnobserved("recall_user_prompts", arguments("sessionRef", reference)));
        assertEquals(List.of("gemini heard this"), texts(recalled));
        assertEquals("gemini", recalled.path("prompts").get(0).path("host").asString());
        assertEquals(session, recalled.path("prompts").get(0).path("sessionId").asString());
        assertEquals("gemini recap", recalled.path("prompts").get(0).path("recap").asString());
    }

    @Test
    void aSessionOpenedByTheServerHasNoPromptAndTheRecapSaysSo() throws Exception {
        JsonNode opened = sennard.callUnobserved("open_session", Map.of());
        String reference = opened.path("sessionRef").asString(opened.path("data").path("sessionRef").asString());
        assertTrue(!reference.isEmpty(), opened.toString());
        assertRefused(sennard.callUnobserved("record_turn_recap", arguments("recap", "a recap", "sessionRef", reference)), "INVALID_INPUT", "PROMPT_NOT_ARCHIVED");
        JsonNode recalled = data(sennard.callUnobserved("recall_user_prompts", arguments("sessionRef", reference)));
        assertEquals(0, recalled.path("prompts").size(), "a session that has had no prompt has an empty list, as it should");
    }

    @Test
    void aSessionReferenceTheServerDoesNotKnowIsRefusedNotAnsweredWithNothing() throws Exception {
        JsonNode unknown = sennard.callUnobserved("record_turn_recap", arguments("recap", "a recap", "sessionRef", "ses_" + "0".repeat(32)));
        assertFalse(unknown.path("ok").asBoolean(), unknown.toString());
        JsonNode recalled = sennard.callUnobserved("recall_user_prompts", arguments("sessionRef", "ses_" + "0".repeat(32)));
        assertFalse(recalled.path("ok").asBoolean(), recalled.toString());
    }
}
