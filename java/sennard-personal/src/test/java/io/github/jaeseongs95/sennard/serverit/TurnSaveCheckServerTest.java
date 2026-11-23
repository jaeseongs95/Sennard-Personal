// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
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

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0156: the turn end checks that the turn left a saved record of what the user said, or said there was nothing to save. Every case runs through the real hook
 * adapters ({@code /hooks/v1/...} with the recorded bodies of Claude Code, Codex and Antigravity) and the real MCP tools of the host's own label and recorded {@code _meta}, on the
 * assembled server with real PostgreSQL. Each answer is read in the host's own form: Claude Code {@code additionalContext}, Codex {@code decision:block}, Antigravity
 * {@code decision:continue}. The recap question of the prompt archive and the question for the session summary are not this test's subject: the prompt of each turn has its recap and the session its summary, as they have once the agent has recorded them.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * a   no record           a user prompt, then the turn end with no save and no mark -> the turn is continued once, with the text that names what is missing and both ways out
 *                         (checkpoint_context or checkpoint_role_context, or mark_turn_unchanged)
 * b1  a save              a successful checkpoint_context after the prompt -> the turn end continues nothing
 * b2  a role save         a successful checkpoint_role_context (a session bound to a role by the signed admin page) after the prompt -> nothing
 * b3  a failed save       a checkpoint_context the server refuses (a stale state token) is no record: the turn end still continues the turn
 * c   a mark              mark_turn_unchanged after the prompt -> nothing
 * d   an earlier turn     a save in the turn before the previous prompt only -> this turn is continued
 * e1  the second end      the turn end the host reports as already continued (stop_hook_active) after the check asked once -> not continued again; the next prompt carries the warning
 *                         that the previous turn had no record
 * e2  already continued   a first turn end the host already reports as continued (another domain did it) -> not continued, and the next prompt carries the warning
 * e3  no turn end         a prompt, no turn end at all (a host or an interruption that sends none), the next prompt -> the warning is in its context
 * e4  no warning          a previous turn with a save or a mark -> no warning at the next prompt; the first prompt of a session -> none
 * e5  a turn that ended   after the turn was closed unconfirmed, a turn end with no new prompt (a woken turn) -> not continued
 * e6  a stop that cannot  a canceled Antigravity stop is not continued and does not close the turn: the next stop that can be continued still asks
 *     be continued
 * f1  a subagent          Claude Code and Codex turn ends that carry agent_id -> not continued, whatever the root saved
 * f2  a suppressed        a session whose restore the user suppressed (suppress_context_restore) -> not continued, and no warning at its next prompt
 * g1  another turn's mark a mark in the previous turn does not pass this turn
 * g2  another actor's     a subagent's mark and save (Claude Code) do not pass the main agent's turn
 * </pre>
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class TurnSaveCheckServerTest {
    private static final String CHECK = "[Sennard save check]";
    private static final Map<String, Object> CORE = Map.of("objective", "keep the user's correction", "completionCriteria", List.of("c"), "constraints", List.of(), "decisions", List.of(),
            "progress", List.of(), "blockers", List.of(), "nextActions", List.of());

    /** A host: the label of its hooks, the name its sessions are stored under, and the form of its prompt, its turn end and its answer. */
    record Host(String label, String sessionHost, String promptEvent) {
        @Override
        public String toString() {
            return label;
        }

        boolean agy() {
            return label.equals("agy");
        }

        Consumer<ObjectNode> prompt(String text) {
            return agy() ? body -> body.put("invocationNum", 0).put("lastUserInput", text) : body -> body.put("prompt", text);
        }

        /** A turn end the host reports as a first one, or, where the host reports it, as one its own hook already continued. */
        Consumer<ObjectNode> stop(boolean hostSaysContinued) {
            if (agy()) {
                return body -> {
                    body.remove("error");
                    body.remove("terminationReason");
                    body.put("fullyIdle", true);
                    body.put("executionNum", 1);
                };
            }
            return body -> body.put("stop_hook_active", hostSaysContinued);
        }

        /** The text this host's answer continues the turn with, or none. */
        Optional<String> continuation(JsonNode answer) {
            return switch (label) {
                case "claude" -> answer.path("hookSpecificOutput").path("hookEventName").asString().equals("Stop")
                        ? Optional.of(answer.path("hookSpecificOutput").path("additionalContext").asString()) : Optional.empty();
                case "codex" -> answer.path("decision").asString().equals("block") ? Optional.of(answer.path("reason").asString()) : Optional.empty();
                default -> answer.path("decision").asString().equals("continue") ? Optional.of(answer.path("reason").asString()) : Optional.empty();
            };
        }
    }

    private static final Host CLAUDE = new Host("claude", "claude-code", "UserPromptSubmit");
    private static final Host CODEX = new Host("codex", "codex", "UserPromptSubmit");
    private static final Host AGY = new Host("agy", "antigravity", "PreInvocation");

    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;
    private static McpSyncClient claudeClient;

    static Stream<Host> hosts() {
        return Stream.of(CLAUDE, CODEX, AGY);
    }

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
        owner = sennard.owner();
        claudeClient = DomainServerSupport.client(sennard.server.port, "claude");
    }

    @AfterAll
    static void stop() {
        claudeClient.close();
        sennard.close();
    }

    // ------------------------------------------------------------------------------------------------------------------ the host's side

    private static RawHttp.Response post(Host host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(sennard.server.port, "windows", host.label(), event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return response;
    }

    /** A session the server knows from its start, as a host starts one. */
    private static String started(Host host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        post(host, "SessionStart", session, body -> {
        });
        return session;
    }

    /** The prompt's own answer (the turn start); the prompt has its recap, so the recap question stays out of the turn end. */
    private static String prompt(Host host, String session, String text) throws Exception {
        if (host.agy()) {
            DomainServerSupport.observeAgyRoot(sennard.server, session);
        }
        String answer = DomainServerSupport.text(post(host, host.promptEvent(), session, host.prompt(text)));
        String id = (String) SessionServerSupport.row(sennard.server, "SELECT user_prompt_id FROM sennard.user_prompt p WHERE host = ? AND session_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM sennard.turn_recap r WHERE r.user_prompt_id = p.user_prompt_id) ORDER BY received_at DESC FETCH FIRST 1 ROWS ONLY", host.sessionHost(), session)
                .get("user_prompt_id");
        SessionServerSupport.update(sennard.server, "INSERT INTO sennard.turn_recap (user_prompt_id, recap_text, recorded_at, version) VALUES (?, ?, ?, 0)", id, "recorded",
                Timestamps.toTimestamp(Instant.now()));
        // the session's summary is on its board, as it is once the agent has written it: the turn end's question for it is not this test's subject
        DomainServerSupport.writeSessionSummary(sennard.server, host.label(), session, "a summary of the session " + session);
        Thread.sleep(5); // the newest prompt is the later millisecond
        return answer;
    }

    private static JsonNode stopAnswer(Host host, String session, boolean hostSaysContinued) throws IOException {
        return DomainServerSupport.MAPPER.readTree(post(host, "Stop", session, host.stop(hostSaysContinued)).body());
    }

    private static Optional<String> stop(Host host, String session) throws IOException {
        return host.continuation(stopAnswer(host, session, false));
    }

    private static Consumer<ObjectNode> child() {
        return body -> body.put("agent_id", "agent-" + UUID.randomUUID()).put("agent_type", "general-purpose");
    }

    /** A tool call by the host's own client; Claude Code's call is bound by the row its PreToolUse hook leaves, a subagent's by the agent_id in that hook. */
    private static JsonNode call(Host host, String tool, Map<String, Object> arguments, String session, boolean subagent) throws IOException {
        return switch (host.label()) {
            case "claude" -> {
                String toolUse = "toolu_" + UUID.randomUUID();
                String body = new String(SessionServerSupport.hook("windows", "claude", "PreToolUse", session, toolUse), StandardCharsets.UTF_8)
                        .replace("mcp__plugin_sennard-probe_probe__probe_echo", "mcp__plugin_sennard_sennard__" + tool);
                if (subagent) {
                    body = ((ObjectNode) ToolServerSupport.MAPPER.readTree(body)).put("agent_id", "agent-" + UUID.randomUUID()).put("agent_type", "general-purpose").toString();
                }
                RawHttp.Response admitted = SessionServerSupport.post(sennard.server.port, "/hooks/v1/claude/PreToolUse/governance", body.getBytes(StandardCharsets.UTF_8));
                assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
                yield DomainServerSupport.call(claudeClient, tool, arguments, SessionServerSupport.meta("windows", "claude", session, toolUse));
            }
            case "agy" -> ChangeWorkflows.callAs(sennard.server, host.label(), session, tool, arguments);
            default -> sennard.call(tool, arguments, session);
        };
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        map.put("schemaVersion", "1.0.0");
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static void assertOk(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
    }

    /** A save the server accepts: the first call learns the state token the server holds, the second is the save. */
    private static JsonNode save(Host host, String session, boolean subagent) throws IOException {
        String request = UUID.randomUUID().toString();
        JsonNode stale = call(host, "checkpoint_context", arguments("operationKey", request,"expectedStateToken", staleToken(), "status", "ACTIVE", "core", CORE, "evidenceRefs", List.of()), session, subagent);
        assertEquals("CONTINUITY_STATE_STALE", stale.path("error").path("details").path("reasonCode").asString(), stale.toString());
        String actual = stale.path("error").path("details").path("actualStateToken").asString();
        JsonNode saved = call(host, "checkpoint_context", arguments("operationKey", request,"expectedStateToken", actual, "status", "ACTIVE", "core", CORE, "evidenceRefs", List.of()), session, subagent);
        assertOk(saved);
        return saved;
    }

    private static String staleToken() {
        return "a".repeat(48);
    }

    private static JsonNode mark(Host host, String session, boolean subagent) throws IOException {
        JsonNode marked = call(host, "mark_turn_unchanged", arguments(), session, subagent);
        assertOk(marked);
        return marked;
    }

    private static void bindToNewRole(Host host, String session) throws Exception {
        String roleId = "role-" + UUID.randomUUID();
        sennard.sign(owner, ToolServerSupport.roleAdd(roleId, "turn-check-" + roleId.substring(5)));
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "BINDING_SET").put("host", host.sessionHost()).put("sessionId", session).put("roleId", roleId);
        sennard.sign(owner, change);
    }

    private static void assertAsks(Host host, Optional<String> continuation) {
        assertTrue(continuation.isPresent(), "the turn end continues the turn on " + host);
        String text = continuation.orElseThrow();
        assertTrue(text.contains(CHECK), text);
        assertTrue(text.contains("checkpoint_context") && text.contains("checkpoint_role_context") && text.contains("mark_turn_unchanged"), text);
    }

    private static void assertQuiet(Host host, Optional<String> continuation) {
        assertTrue(continuation.isEmpty(), "the turn end continues nothing on " + host + ": " + continuation.orElse(""));
    }

    /**
     * The key is the caller's own (AGS3-D-0267, scope): two sessions, each bound to a role of its own, that send one key and one body each save their own role. The second session's
     * save used to be answered with the first one's receipt and its own role stayed empty.
     */
    @Test
    void twoSessionsThatSendTheSameKeyAndBodyEachSaveTheirOwnRoleContext() throws Exception {
        String a = started(CODEX);
        String b = started(CODEX);
        bindToNewRole(CODEX, a);
        bindToNewRole(CODEX, b);
        String key = "role-save-" + UUID.randomUUID();
        String body = "role knowledge of two sessions " + UUID.randomUUID();
        Map<String, Object> save = arguments("operationKey", key, "expectedStateToken", null, "body", body);
        assertOk(call(CODEX, "checkpoint_role_context", save, a, false));
        assertOk(call(CODEX, "checkpoint_role_context", save, b, false));
        assertEquals(body, call(CODEX, "load_shared_context", arguments(), b, false).path("data").path("role").path("body").asString(), "the second session's role holds the body: its save ran");
        assertEquals(body, call(CODEX, "load_shared_context", arguments(), a, false).path("data").path("role").path("body").asString());
    }

    /**
     * A request that comes again with its key is answered with the first save, and the first save was noted for the turn it was made in. The repeat made in a later turn must not be
     * noted again: it is no save of that turn, and the warning that the turn left none must not be lost to it (AGS3-D-0267).
     */
    @Test
    void aRoleSaveThatComesAgainWithItsKeyIsNotASaveOfTheTurnItIsSentIn() throws Exception {
        String session = started(CODEX);
        bindToNewRole(CODEX, session);
        prompt(CODEX, session, "a decision of the first turn");
        Map<String, Object> save = arguments("operationKey", "role-save-" + UUID.randomUUID(), "expectedStateToken", null, "body", "role knowledge " + UUID.randomUUID());
        JsonNode first = call(CODEX, "checkpoint_role_context", save, session, false);
        assertOk(first);
        assertQuiet(CODEX, stop(CODEX, session));
        prompt(CODEX, session, "the second turn, with no save of its own");
        JsonNode again = call(CODEX, "checkpoint_role_context", save, session, false);
        assertOk(again);
        assertEquals(first.path("data"), again.path("data"), "the first save is the answer");
        assertAsks(CODEX, stop(CODEX, session));
    }

    // ------------------------------------------------------------------------------------------------------------------ a, b, c

    @ParameterizedTest
    @MethodSource("hosts")
    void aTurnWithNoSaveAndNoMarkIsContinuedOnceWithWhatIsMissing(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "from now on, do it my way");
        assertAsks(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSavedStateAfterThePromptPassesTheTurn(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "a correction");
        save(host, session, false);
        assertQuiet(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSavedRoleContextAfterThePromptPassesTheTurn(Host host) throws Exception {
        String session = started(host);
        bindToNewRole(host, session);
        prompt(host, session, "a decision later sessions need");
        JsonNode saved = call(host, "checkpoint_role_context", arguments("expectedStateToken", null, "body", "role knowledge " + UUID.randomUUID()), session, false);
        assertOk(saved);
        assertQuiet(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSaveTheServerRefusesIsNoRecord(Host host) throws Exception {
        String session = started(host);
        bindToNewRole(host, session);
        prompt(host, session, "a correction whose save fails");
        JsonNode refused = call(host, "checkpoint_context", arguments("operationKey", UUID.randomUUID().toString(), "expectedStateToken", staleToken(), "status", "ACTIVE", "core", CORE,
                "evidenceRefs", List.of()), session, false);
        assertEquals("CONTINUITY_STATE_STALE", refused.path("error").path("details").path("reasonCode").asString(), refused.toString());
        JsonNode roleRefused = call(host, "checkpoint_role_context", arguments("expectedStateToken", staleToken(), "body", "stale"), session, false);
        assertEquals("CONTEXT_LAYER_STATE_STALE", roleRefused.path("error").path("details").path("reasonCode").asString(), roleRefused.toString());
        assertAsks(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aMarkThatNothingChangedAfterThePromptPassesTheTurn(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "just a question");
        JsonNode marked = mark(host, session, false);
        assertTrue(marked.path("data").path("turnStartedAt").asString().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"), "the mark says which turn it is for: " + marked);
        assertQuiet(host, stop(host, session));
    }

    // ------------------------------------------------------------------------------------------------------------------ d, g

    @ParameterizedTest
    @MethodSource("hosts")
    void aSaveInAnEarlierTurnDoesNotPassThisOne(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "first");
        save(host, session, false);
        assertQuiet(host, stop(host, session));
        prompt(host, session, "second");
        assertAsks(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aMarkOfAnotherTurnDoesNotPassThisOne(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "first");
        mark(host, session, false);
        assertQuiet(host, stop(host, session));
        prompt(host, session, "second");
        assertAsks(host, stop(host, session));
    }

    @Test
    void aMarkAndASaveOfAnotherActorDoNotPassTheMainAgentsTurn() throws Exception {
        String session = started(CLAUDE);
        prompt(CLAUDE, session, "a turn with a subagent");
        mark(CLAUDE, session, true);
        save(CLAUDE, session, true);
        assertAsks(CLAUDE, stop(CLAUDE, session));
    }

    // ------------------------------------------------------------------------------------------------------------------ e

    @ParameterizedTest
    @MethodSource("hosts")
    void theSecondTurnEndIsNotContinuedAndTheNextPromptCarriesTheWarning(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "first");
        assertAsks(host, host.continuation(stopAnswer(host, session, false)));
        assertQuiet(host, host.continuation(stopAnswer(host, session, true)));
        String next = prompt(host, session, "second");
        assertTrue(next.contains(CHECK) && next.contains(previousInputWarning()) && next.contains("checkpoint_context"), "the warning is in the turn start of the next prompt: " + next);
    }

    @ParameterizedTest
    @MethodSource({"claudeAndCodex"})
    void aTurnEndTheHostAlreadyCallsContinuedIsNotContinuedAndIsWarnedAboutNext(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "first");
        assertQuiet(host, host.continuation(stopAnswer(host, session, true)));
        String next = prompt(host, session, "second");
        assertTrue(next.contains(CHECK) && next.contains(previousInputWarning()), next);
    }

    static Stream<Host> claudeAndCodex() {
        return Stream.of(CLAUDE, CODEX);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aTurnWithNoTurnEndIsWarnedAboutAtTheNextPrompt(Host host) throws Exception {
        String session = started(host);
        String first = prompt(host, session, "first");
        assertFalse(first.contains(previousInputWarning()), "the first prompt of a session has no earlier turn: " + first);
        String next = prompt(host, session, "second");
        assertTrue(next.contains(CHECK) && next.contains(previousInputWarning()), next);
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aPreviousTurnWithARecordIsNotWarnedAbout(Host host) throws Exception {
        String saved = started(host);
        prompt(host, saved, "first");
        save(host, saved, false);
        assertFalse(prompt(host, saved, "second").contains(previousInputWarning()));

        String marked = started(host);
        prompt(host, marked, "first");
        mark(host, marked, false);
        assertFalse(prompt(host, marked, "second").contains(previousInputWarning()));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aTurnEndAfterTheTurnWasClosedIsNoUserTurn(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "first");
        assertAsks(host, host.continuation(stopAnswer(host, session, false)));
        assertQuiet(host, host.continuation(stopAnswer(host, session, true)));
        assertQuiet(host, host.continuation(stopAnswer(host, session, false)));
    }

    @Test
    void aCanceledStopIsNotContinuedAndDoesNotCloseTheTurn() throws Exception {
        String session = started(AGY);
        prompt(AGY, session, "a prompt the user cancels");
        assertQuiet(AGY, AGY.continuation(DomainServerSupport.MAPPER.readTree(post(AGY, "Stop", session, body -> body.put("terminationReason", "USER_CANCELED")).body())));
        assertAsks(AGY, stop(AGY, session));
    }

    // ------------------------------------------------------------------------------------------------------------------ f

    @ParameterizedTest
    @MethodSource("claudeAndCodex")
    void aSubagentsTurnEndIsNotContinued(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "a turn whose subagent ends");
        assertQuiet(host, host.continuation(DomainServerSupport.MAPPER.readTree(post(host, "Stop", session, child().andThen(host.stop(false))).body())));
        assertAsks(host, stop(host, session));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    void aSuppressedSessionIsNotContinuedOrWarnedAbout(Host host) throws Exception {
        String session = started(host);
        prompt(host, session, "a session whose restore the user suppressed");
        JsonNode suppressed = call(host, "suppress_context_restore", arguments("expectedEpoch", 1), session, false);
        assertOk(suppressed);
        assertQuiet(host, stop(host, session));
        assertFalse(prompt(host, session, "next").contains(previousInputWarning()));
    }

    private static String previousInputWarning() {
        return DomainServerSupport.englishMessage(sennard.server, "hooks.turnCheck.previous", Map.of());
    }
}
