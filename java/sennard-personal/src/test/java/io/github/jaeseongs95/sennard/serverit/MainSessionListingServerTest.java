// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpMeta;

import io.github.jaeseongs95.sennard.server.mcp.McpConfiguration;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.modelcontextprotocol.common.McpTransportContext;
import tools.jackson.databind.JsonNode;

/**
 * The session list and the admin page's session binding show main sessions only. Real server, real PostgreSQL, recorded hook
 * bodies and recorded {@code _meta}; the hooks come from inside the server's own process, so each session is the unowned kind the
 * hooks and tool calls keep alive. Each case reads the list the way a user does (list_session_status, the admin page's state).
 */
@Tag("business-db")
class MainSessionListingServerTest {
    private static final String CLAUDE_TOOL = "mcp__plugin_sennard_sennard__session_probe";
    private static ToolServerSupport sennard;
    private static String self;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
        self = sennard.session();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    // ----------------------------------------------------------------------------------------------------------------- helpers

    /** A recorded Claude hook body of this session with extra top-level fields (the host's marker of a subagent or of {@code --agent}). */
    private static byte[] claude(String event, String session, String toolUse, String extraFields) {
        String text = new String(SessionServerSupport.hook("windows", "claude", event, session, toolUse), StandardCharsets.UTF_8);
        return (extraFields.isEmpty() ? text : text.replaceFirst("^\\{", "{" + extraFields + ",")).getBytes(StandardCharsets.UTF_8);
    }

    private static int post(String event, String module, byte[] body) throws Exception {
        return SessionServerSupport.post(sennard.server.port, "/hooks/v1/claude/" + event + (module == null ? "" : "/" + module), body).status();
    }

    private static Set<String> listed() {
        JsonNode data = sennard.call("list_session_status", Map.of("schemaVersion", "1.0.0"), self).path("data");
        Set<String> ids = new HashSet<>();
        data.path("sessions").forEach(item -> ids.add(item.path("sessionId").asString()));
        return ids;
    }

    private static JsonNode adminRow(String session) throws Exception {
        for (JsonNode item : sennard.state().path("sessions")) {
            if (session.equals(item.path("sessionId").asString())) {
                return item;
            }
        }
        return null;
    }

    private static long refs(String session) {
        return SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.session_ref WHERE local_session_id = ?", session);
    }

    private static String kindOf(String session) {
        return String.valueOf(SessionServerSupport.row(sennard.server, "SELECT kind FROM sennard.session_ref WHERE local_session_id = ?", session).get("kind"));
    }

    private static McpSessionResolver resolver() {
        return sennard.bean(McpSessionResolver.class);
    }

    private static McpTransportContext labelled(String label) {
        return McpTransportContext.create(Map.of(McpConfiguration.HOST_LABEL, label));
    }

    // ------------------------------------------------------------------------------------------------------------------ cases

    /** Case 1: a hook that carries agent_id is a subagent's; it registers nothing and the session is not listed. */
    @Test
    void aSubagentsHooksRegisterNoSession() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "\"agent_id\":\"child-1\",\"agent_type\":\"Explore\"")));
        assertEquals(200, post("UserPromptSubmit", null, claude("UserPromptSubmit", session, "none", "\"agent_id\":\"child-1\",\"agent_type\":\"Explore\"")));
        assertEquals(200, post("PostToolUse", null, claude("PostToolUse", session, "toolu_c1", "\"agent_id\":\"child-1\",\"agent_type\":\"Explore\"")));
        assertFalse(listed().contains(session), "a subagent is not listed");
        assertEquals(0, refs(session), "nothing was registered");
        assertEquals(null, adminRow(session), "nor shown on the page");
    }

    /** Case 2: agent_type without agent_id is a session started with --agent; it is not a main session. */
    @Test
    void aSessionStartedWithAgentIsNotListed() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String marker = "\"agent_type\":\"claude\"";
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", marker)));
        assertEquals(200, post("UserPromptSubmit", null, claude("UserPromptSubmit", session, "none", marker)));
        assertFalse(listed().contains(session), "a --agent session is not listed");
        assertEquals(null, adminRow(session), "nor shown on the page");
        assertEquals("PRESET_AGENT", kindOf(session), "the store says why");
    }

    /** Case 3 (regression guard): a hook with neither marker is the main session's; it registers and is listed. */
    @Test
    void aMainSessionIsRegisteredAndListed() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "")));
        assertTrue(listed().contains(session));
        JsonNode row = adminRow(session);
        assertNotNull(row, "the page shows it");
        assertEquals("online", row.path("state").asString());
        assertEquals("MAIN", kindOf(session));
    }

    /**
     * startedAt is when the session first started, not when its current generation was made: after the lease lapsed and the next SessionStart made a new generation, the list still
     * answers the first start, while lastSeenAt (the lease's last renewal) has moved on.
     */
    @Test
    void startedAtStaysTheFirstStartAfterTheLeaseExpiredAndTheSessionWasRecovered() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "")));
        JsonNode first = listedRow(session);
        String startedAt = first.path("startedAt").asString();
        assertFalse(startedAt.isEmpty(), first.toString());
        assertEquals(1L, SessionServerSupport.count(sennard.server, "SELECT last_generation FROM sennard.session_ref WHERE local_session_id = ?", session));
        Thread.sleep(50);
        // The lease lapses: the generation's lease_until is in the past, as after a server that was down past it.
        SessionServerSupport.update(sennard.server, "UPDATE sennard.session_generation SET lease_until = ? WHERE session_ref = (SELECT session_ref FROM sennard.session_ref WHERE local_session_id = ?)",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1), session);
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "")));
        assertEquals(2L, SessionServerSupport.count(sennard.server, "SELECT last_generation FROM sennard.session_ref WHERE local_session_id = ?", session), "the recovery made a new generation");
        JsonNode recovered = listedRow(session);
        assertEquals("online", recovered.path("presence").asString(), recovered.toString());
        assertEquals(startedAt, recovered.path("startedAt").asString(), "the session did not start again: " + recovered);
        assertTrue(java.time.Instant.parse(recovered.path("lastSeenAt").asString()).isAfter(java.time.Instant.parse(startedAt)), "the lease was renewed after the first start: " + recovered);
    }

    private static JsonNode listedRow(String session) {
        JsonNode data = sennard.call("list_session_status", Map.of("schemaVersion", "1.0.0"), self).path("data");
        for (JsonNode item : data.path("sessions")) {
            if (session.equals(item.path("sessionId").asString())) {
                return item;
            }
        }
        throw new AssertionError("the session is not listed: " + session);
    }

    /** Case 3b: a missed SessionStart is made up by the next hook of a main session, as before. */
    @Test
    void aMainSessionWhoseStartWasMissedIsRegisteredAtItsNextHook() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("UserPromptSubmit", null, claude("UserPromptSubmit", session, "none", "")));
        assertTrue(listed().contains(session));
    }

    /** Case 4: an --agent session that calls a Sennard tool is registered by that call, and is still not listed. */
    @Test
    void anAgentSessionThatCallsAnSennardToolIsStillNotListed() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String toolUse = "toolu_agent_" + session;
        String marker = "\"agent_type\":\"claude\"";
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, toolUse, marker)));
        byte[] pre = new String(claude("PreToolUse", session, toolUse, marker), StandardCharsets.UTF_8).replace("mcp__plugin_sennard-probe_probe__probe_echo", CLAUDE_TOOL)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(200, post("PreToolUse", "governance", pre));
        SessionBinding binding = resolver().bind("session_probe", Map.of(), new McpMeta(SessionServerSupport.meta("windows", "claude", session, toolUse)), labelled("claude"), null);
        assertInstanceOf(SessionBinding.Bound.class, binding, "the call itself is admitted and bound as before");
        assertEquals(1, refs(session), "the call registered the session");
        assertFalse(listed().contains(session), "and it is still not listed");
        assertEquals(null, adminRow(session));
    }

    /** Case 4b (admission is unchanged): the Sennard tool of an --agent session leaves its row, as a main session's does. */
    @Test
    void theAdmissionOfAnSennardToolDoesNotDependOnTheAgentMarker() throws Exception {
        for (String marker : new String[] { "", "\"agent_type\":\"claude\"" }) {
            String session = SessionServerSupport.uniqueSession();
            String toolUse = "toolu_adm_" + session;
            byte[] pre = new String(claude("PreToolUse", session, toolUse, marker), StandardCharsets.UTF_8).replace("mcp__plugin_sennard-probe_probe__probe_echo", CLAUDE_TOOL)
                    .getBytes(StandardCharsets.UTF_8);
            assertEquals(200, post("PreToolUse", "governance", pre), marker);
            assertEquals(1, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.call_admission WHERE call_id = ?", toolUse), "a row is left: " + marker);
        }
        // A marker that is present but unusable is still refused, not read as a main session.
        String session = SessionServerSupport.uniqueSession();
        byte[] pre = new String(claude("PreToolUse", session, "toolu_bad_" + session, "\"agent_id\":\"  \""), StandardCharsets.UTF_8)
                .replace("mcp__plugin_sennard-probe_probe__probe_echo", CLAUDE_TOOL).getBytes(StandardCharsets.UTF_8);
        post("PreToolUse", "governance", pre);
        assertEquals(0, SessionServerSupport.count(sennard.server, "SELECT count(*) FROM sennard.call_admission WHERE host_session_id = ?", session), "no row for an unusable marker");
        assertEquals(0, refs(session), "and no session");
    }

    /** Case 5: a conversation an Antigravity hook registered as a root, and a tool call then says is a child's, leaves the list. */
    @Test
    void aConversationObservedAsAChildByAToolCallLeavesTheList() throws Exception {
        String child = SessionServerSupport.uniqueSession();
        String root = SessionServerSupport.uniqueSession();
        assertEquals(200, SessionServerSupport.post(sennard.server.port, "/hooks/v1/agy/SessionStart", SessionServerSupport.hook("windows", "agy", "SessionStart", child, "none")).status());
        assertTrue(listed().contains(child), "the hook cannot tell: it is listed until a call says otherwise");

        DomainServerSupport.observeAgy(sennard.server, child, root, root);

        assertFalse(listed().contains(child), "observed as a child: not listed");
        assertEquals(null, adminRow(child), "nor on the page");
        assertEquals("CHILD", kindOf(child));

        // The child's later hooks do not bring it back.
        assertEquals(200, SessionServerSupport.post(sennard.server.port, "/hooks/v1/agy/PostToolUse", SessionServerSupport.hook("windows", "agy", "PostToolUse", child, "none")).status());
        assertFalse(listed().contains(child), "a later hook of the child does not list it again");
    }

    /** Case 6: the main session's end hook still ends it. */
    @Test
    void aMainSessionsEndHookStillEndsIt() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "")));
        assertTrue(listed().contains(session));
        assertEquals(200, post("SessionEnd", null, claude("SessionEnd", session, "none", "")));
        assertFalse(listed().contains(session), "ended");
        assertNotNull(SessionServerSupport.row(sennard.server, "SELECT g.ended_at FROM sennard.session_ref r JOIN sennard.session_generation g ON g.session_ref = r.session_ref "
                + "AND g.generation = r.last_generation WHERE r.local_session_id = ? AND g.ended_at IS NOT NULL", session));
    }

    /** Case 6b: a session's end hook with the --agent marker registers nothing. */
    @Test
    void anEndHookOfAnUnregisteredSessionRegistersNothing() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionEnd", null, claude("SessionEnd", session, "none", "\"agent_type\":\"claude\"")));
        assertEquals(0, refs(session), "no session is registered by an end");
        assertFalse(listed().contains(session));
    }

    /** Case 7: a session that was started with --agent and later resumed as a main session is listed again. */
    @Test
    void aSessionResumedWithoutAgentIsListedAgain() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "\"agent_type\":\"claude\"")));
        assertFalse(listed().contains(session));
        assertEquals(200, post("SessionStart", null, claude("SessionStart", session, "none", "")));
        assertTrue(listed().contains(session), "a main hook of the same session lists it");
        assertEquals("MAIN", kindOf(session));
    }

    /** Case 8: on a host whose hooks cannot mark a subagent, a session that said nothing about itself is offered to the user as undecided. */
    @Test
    void aSessionOfAHostThatMarksNoSubagentsAndSaidNothingIsUndecided() throws Exception {
        String undecided = SessionServerSupport.uniqueSession();
        assertEquals(200, SessionServerSupport.post(sennard.server.port, "/hooks/v1/agy/SessionStart", SessionServerSupport.hook("windows", "agy", "SessionStart", undecided, "none")).status());
        JsonNode row = adminRow(undecided);
        assertNotNull(row);
        assertTrue(row.path("kindUnknown").asBoolean(false), row.toString());

        String main = SessionServerSupport.uniqueSession();
        assertEquals(200, post("SessionStart", null, claude("SessionStart", main, "none", "")));
        assertFalse(adminRow(main).path("kindUnknown").asBoolean(false), "Claude marks its subagents: a session with no marker is a main one");
    }

    /**
     * Case 9: the page does not claim the session does nothing when it only has no summary. The page's sentences are the catalog's, in the request's language (English by
     * default, Korean on Accept-Language: ko), so each language is asked for and its own "no summary" sentence is looked for.
     */
    @Test
    void thePageSaysThereIsNoSummaryInsteadOfNothingBeingDone() throws Exception {
        String korean = pageIn("ko");
        assertFalse(korean.contains("지금 하는 일 없음"), "no claim about what the session does");
        assertTrue(korean.contains("요약 없음"));
        assertTrue(pageIn(null).contains("No summary"), "English by default");
    }

    /** The admin page on localhost, asked for in a language (null: no Accept-Language, so English). */
    private static String pageIn(String acceptLanguage) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "localhost:" + sennard.adminPort);
        if (acceptLanguage != null) {
            headers.put("Accept-Language", acceptLanguage);
        }
        return RawHttp.exchange(sennard.adminPort, "GET", "/", headers, null).text();
    }
}
