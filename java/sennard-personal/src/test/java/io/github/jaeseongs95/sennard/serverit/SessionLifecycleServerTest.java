// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.mcp.annotation.McpMeta;

import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.mcp.McpConfiguration;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Execution;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Provenance;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.github.jaeseongs95.sennard.server.session.SessionClosed;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The session side on the assembled server with real PostgreSQL: recorded hook bodies and recorded {@code _meta} from the three hosts, real
 * registration, leases, ends and a real process whose death is noticed. Host traffic is the probe's, with this test's identifiers put in.
 */
@Tag("business-db")
class SessionLifecycleServerTest {
    private static final String CLAUDE_TOOL = "mcp__plugin_sennard_sennard__session_probe";
    /** The hooks a test holds at a lock of the database: each waiting hook holds a connection of the server's pool (BusinessDb.POOL_SIZE, 4), so there are fewer of them than that. */
    private static final int HOOKS_AT_THE_KEY = 3;
    private static RunningServer server;
    /** One client per host label for the class, not one per call: a client made for each call leaves its connections open on the server. */
    private static DomainServerSupport.Clients clients;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.hooks.wake-hold-ms=0");
        clients = new DomainServerSupport.Clients(server.port, Duration.ofSeconds(60));
    }

    @AfterAll
    static void stop() {
        try {
            clients.close();
        } finally {
            server.close();
        }
    }

    private static String stored(String host) {
        return switch (host) {
            case "claude" -> "claude-code";
            case "agy" -> "antigravity";
            default -> host;
        };
    }

    private static Map<String, Object> rowOf(String host, String session) {
        return SessionServerSupport.row(server, "SELECT r.session_ref, r.last_generation, g.generation, g.process_key, g.relay_id, g.ended_at, g.end_reason, g.lease_until, g.superseded_at "
                + "FROM sennard.session_ref r JOIN sennard.session_generation g ON g.session_ref = r.session_ref "
                + "AND g.generation = r.last_generation WHERE r.host = ? AND r.local_session_id = ?", stored(host), session);
    }

    private static RawHttp.Response hook(String os, String host, String event, String module, String session, String toolUse) throws Exception {
        return SessionServerSupport.post(server.port, "/hooks/v1/" + host + "/" + event + (module == null ? "" : "/" + module),
                SessionServerSupport.hook(os, host, event, session, toolUse));
    }

    /** The class's one initialized client of this host label (closed in {@link #stop()}). */
    private static McpSyncClient client(String label) {
        return clients.of(label);
    }

    private static McpSessionResolver resolver() {
        return server.bean(McpSessionResolver.class);
    }

    private static McpTransportContext labelled(String label) {
        return McpTransportContext.create(Map.of(McpConfiguration.HOST_LABEL, label));
    }

    /** A Sennard tool's PreToolUse on Claude: the recorded body, with the tool named as Sennard names it. */
    private static byte[] claudeSennardPreToolUse(String os, String session, String toolUse) {
        String text = new String(SessionServerSupport.hook(os, "claude", "PreToolUse", session, toolUse), java.nio.charset.StandardCharsets.UTF_8);
        return text.replace("mcp__plugin_sennard-probe_probe__probe_echo", CLAUDE_TOOL).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A Sennard tool's PreToolUse on Antigravity: the recorded body, with the tool named as Sennard names it. */
    private static byte[] agySennardPreToolUse(String os, String session) {
        String text = new String(SessionServerSupport.hook(os, "agy", "PreToolUse", session, "unused"), java.nio.charset.StandardCharsets.UTF_8);
        return text.replace("\"ServerName\":\"probe\"", "\"ServerName\":\"sennard\"").replace("\"ToolName\":\"probe_echo\"", "\"ToolName\":\"session_probe\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------ registration

    @ParameterizedTest
    @CsvSource({"windows,claude", "windows,codex", "windows,agy", "linux,claude", "linux,codex", "linux,agy"})
    void aRecordedSessionStartIsAnsweredAndRegistersAnUnownedGeneration(String os, String host) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook(os, host, "SessionStart", null, session, "none").status());
        Map<String, Object> row = rowOf(host, session);
        assertEquals(1L, ((Number) row.get("generation")).longValue());
        assertNull(row.get("process_key"), "the server does not look for the host's process");
        assertNull(row.get("relay_id"));
    }

    @Test
    void aHookForASessionWhoseStartWasMissedRegistersItAtThatHook() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook("windows", "claude", "UserPromptSubmit", null, session, "none").status());
        assertNotNull(rowOf("claude", session).get("session_ref"), "registered at the hook");
    }

    /**
     * The first hooks of a session the server has never seen, three at once: the primary key lets one registration make the {@code session_ref} row; every other hook ends with the
     * translated unique-key failure ({@code STORE_UNIQUE_VIOLATION}, 409 HOOK_HANDLER_CONFLICT), the accepted limit of a first creation, and nothing runs again. Who arrives when is
     * not left to chance (on one CPU the hooks would never meet): the test holds the table of the generations, the second insert of the registration. The hook that inserts the session
     * row stops there with its row not committed, and the database shows the other two waiting for that row. Only then the lock goes: exactly one hook is answered 200, the other two
     * lost on the key. One row and one live generation are left, and the next hook of the session is answered 200.
     */
    @ParameterizedTest
    @CsvSource({"UserPromptSubmit", "SessionStart"})
    void threeFirstHooksOfANewSessionMakeOneSessionRefAndTheOthersLoseOnTheKey(String event) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        List<Callable<RawHttp.Response>> calls = new ArrayList<>();
        for (int i = 0; i < HOOKS_AT_THE_KEY; i += 1) {
            calls.add(() -> hook("windows", "claude", event, null, session, "none"));
        }
        List<RawHttp.Response> answers;
        HookRaceLocks.warm(server, HOOKS_AT_THE_KEY);
        try (HookRaceLocks generations = HookRaceLocks.lockingWritesOf("sennard.session_generation");
                HookRaceLocks.Calls<RawHttp.Response> hooks = HookRaceLocks.Calls.start(calls)) {
            generations.awaitBlocked(1, "no hook came to insert the session and wait for its generation", hooks);
            generations.awaitQueuedBehindBlocked(HOOKS_AT_THE_KEY - 1, "the other hooks do not wait for the session row of the first one: the statements did not meet", hooks);
            generations.release();
            answers = hooks.answers();
        }
        assertEquals(1, answers.stream().filter(answer -> answer.status() == 200).count(), "one hook made the session and was answered 200: " + answers.stream().map(RawHttp.Response::status).toList());
        for (RawHttp.Response response : answers) {
            if (response.status() != 200) {
                assertEquals(409, response.status(), "a hook that lost on the key is a conflict, not a server that cannot answer: " + response.text());
                assertTrue(response.text().contains("STORE_UNIQUE_VIOLATION"), "a hook that did not get 200 lost on the key: " + response.status() + " " + response.text());
            }
        }
        assertEquals(200, hook("windows", "claude", event, null, session, "none").status(), "the next hook of the session is answered");
        assertEquals(1, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.session_ref WHERE host = ? AND local_session_id = ?", stored("claude"), session));
        assertEquals(1, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.session_generation g JOIN sennard.session_ref r ON r.session_ref = g.session_ref "
                + "WHERE r.local_session_id = ? AND g.ended_at IS NULL AND g.superseded_at IS NULL", session));
    }

    @Test
    void aSessionKeepsAUsableGenerationWithNoOwner() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook("windows", "codex", "SessionStart", null, session, "none").status());
        Map<String, Object> row = rowOf("codex", session);
        assertNull(row.get("process_key"));
        assertNull(row.get("relay_id"));
        assertEquals(row.get("session_ref"), assertInstanceOf(SessionBinding.Bound.class,
                resolver().bind("t", Map.of(), new McpMeta(SessionServerSupport.meta("windows", "codex", session, "none")), labelled("codex"), null)).session().sessionRef());
    }

    @Test
    void theWakeHookIsNotAnsweredWithoutAMessage() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        RawHttp.Response response = hook("windows", "claude", "SessionStart", "wake", session, "none");
        assertEquals(408, response.status(), response.text());
        assertEquals("HOOK_WAKE_NO_MESSAGE", SessionServerSupport.json(response).path("reason").stringValue());
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.session_ref WHERE local_session_id = ?", session), "a wake request registers nothing");
    }

    /** A wake request the server cannot take is answered with its own status and reason, as on the hook path, not as a server error. */
    @Test
    void aWakeRequestThatCannotBeTakenIsRefusedWithItsReason() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertWakeRefused(404, "HOOK_HOST_UNKNOWN", "/hooks/v1/nobody/SessionStart/wake", SessionServerSupport.hook("windows", "claude", "SessionStart", session, "none"));
        assertWakeRefused(400, "HOOK_WAKE_EVENT_INVALID", "/hooks/v1/claude/UserPromptSubmit/wake", SessionServerSupport.hook("windows", "claude", "UserPromptSubmit", session, "none"));
        assertWakeRefused(400, "HOOK_BODY_NOT_A_JSON_OBJECT", "/hooks/v1/claude/Stop/wake", "bare".getBytes(StandardCharsets.UTF_8));
        assertWakeRefused(400, "HOOK_BODY_EMPTY", "/hooks/v1/claude/Stop/wake", new byte[0]);
    }

    private static void assertWakeRefused(int status, String reason, String path, byte[] body) throws Exception {
        RawHttp.Response response = SessionServerSupport.post(server.port, path, body);
        assertEquals(status, response.status(), path + ": " + response.text());
        assertEquals(reason, SessionServerSupport.json(response).path("reason").stringValue(), path);
    }

    // -------------------------------------------------------------------------------------------- the session of a call

    private static SelfSession callThroughMcp(String label, Map<String, Object> meta, Map<String, Object> arguments) {
        SessionBinding bound = bindingThroughMcp(label, meta, arguments);
        return assertInstanceOf(SessionBinding.Bound.class, bound, String.valueOf(bound)).session();
    }

    private static SessionBinding bindingThroughMcp(String label, Map<String, Object> meta, Map<String, Object> arguments) {
        SessionServerSupport.SessionProbe probe = server.bean(SessionServerSupport.SessionProbe.class);
        probe.last.set(null);
        McpSchema.CallToolResult result = client(label).callTool(McpSchema.CallToolRequest.builder("session_probe").arguments(arguments).meta(meta).build());
        assertNotEquals(Boolean.TRUE, result.isError(), result.toString());
        return probe.last.get();
    }

    @ParameterizedTest
    @CsvSource({"windows", "linux"})
    void classC_aCallIsBoundThroughTheRowItsHookLeft(String os) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String toolUse = "toolu_" + UUID.randomUUID();
        assertEquals(200, hook(os, "claude", "SessionStart", null, session, toolUse).status());
        RawHttp.Response admitted = SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", claudeSennardPreToolUse(os, session, toolUse));
        assertEquals(200, admitted.status(), admitted.text());

        SelfSession self = callThroughMcp("claude", SessionServerSupport.meta(os, "claude", session, toolUse), Map.of());

        Map<String, Object> row = rowOf("claude", session);
        assertEquals(row.get("session_ref"), self.sessionRef());
        assertEquals(1, self.generation());
        assertEquals("claude-code", self.host());
        assertEquals(session, self.hostSessionId());
        assertEquals(Provenance.HOST_OBSERVED, self.provenance());
        assertEquals(Role.ROOT, self.actor().role());
        assertEquals(toolUse, self.callId());
        assertNotNull(self.transcriptPath(), "the hook named the transcript");
        assertEquals(new Execution.Unobserved("HOOK_MODEL_NOT_REPORTED", Map.of(), "medium"), self.execution(),
                "this host's PreToolUse stdin reports the effort but omits the model");
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.call_admission WHERE call_id = ?", toolUse), "the claim consumed the row");
    }

    @ParameterizedTest
    @CsvSource({"windows", "linux"})
    void classA_aCallIsBoundFromItsOwnMetadataWithNoHookRow(String os) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook(os, "codex", "SessionStart", null, session, "none").status());
        assertEquals(200, hook(os, "codex", "PreToolUse", null, session, "none").status());
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.call_admission WHERE host_session_id = ?", session), "this host leaves no row");

        SelfSession self = callThroughMcp("codex", SessionServerSupport.meta(os, "codex", session, "none"), Map.of());

        assertEquals(rowOf("codex", session).get("session_ref"), self.sessionRef());
        assertEquals("codex", self.host());
        assertEquals(session, self.hostSessionId());
        assertEquals(Provenance.HOST_OBSERVED, self.provenance());
        assertEquals(Role.ROOT, self.actor().role());
        assertNotNull(self.callId());
        assertEquals(new Execution.Observed("gpt-6.1-sol", "low", "10000002-1111-4111-8111-000000000002", "codex:mcp-meta").model(),
                assertInstanceOf(Execution.Observed.class, self.execution()).model());
    }

    @ParameterizedTest
    @CsvSource({"windows", "linux"})
    void classB_aCallIsBoundToItsConversationAndItsHooksRowGivesTheModel(String os) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook(os, "agy", "SessionStart", null, session, "none").status());
        RawHttp.Response admitted = SessionServerSupport.post(server.port, "/hooks/v1/agy/PreToolUse", agySennardPreToolUse(os, session));
        assertEquals(200, admitted.status(), admitted.text());
        assertEquals(1, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.call_admission WHERE host_session_id = ?", session));

        SelfSession self = callThroughMcp("agy", SessionServerSupport.meta(os, "agy", session, "none"), Map.of("note", "A1"));

        assertEquals(rowOf("agy", session).get("session_ref"), self.sessionRef());
        assertEquals("antigravity", self.host());
        assertEquals(session, self.hostSessionId());
        Execution.Observed observed = assertInstanceOf(Execution.Observed.class, self.execution());
        assertEquals("gemini-4-argon", observed.model());
        assertEquals("medium", observed.effort());
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.call_admission WHERE host_session_id = ?", session), "the claim consumed the row");
    }

    @Test
    void classB_aCallWhoseArgumentsDifferFromItsHookIsUnbound() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/agy/PreToolUse", agySennardPreToolUse("windows", session)).status());
        SessionBinding binding = bindingThroughMcp("agy", SessionServerSupport.meta("windows", "agy", session, "none"), Map.of("note", "different"));
        assertEquals(new SessionBinding.Unbound("HOOK_OBSERVATION_UNMATCHED"), binding);
    }

    @Test
    void classC_aCallWithNoRowIsUnbound() {
        String toolUse = "toolu_" + UUID.randomUUID();
        SessionBinding binding = resolver().bind("session_probe", Map.of(), new McpMeta(SessionServerSupport.meta("windows", "claude", "ignored", toolUse)), labelled("claude"), null);
        assertEquals(new SessionBinding.Unbound("CALL_ADMISSION_MISSING"), binding);
    }

    @Test
    void classC_aRowLeftForAnotherToolIsAContradiction() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String toolUse = "toolu_" + UUID.randomUUID();
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", claudeSennardPreToolUse("windows", session, toolUse)).status());
        BindingException refused = assertThrows(BindingException.class,
                () -> resolver().bind("another_tool", Map.of(), new McpMeta(SessionServerSupport.meta("windows", "claude", session, toolUse)), labelled("claude"), null));
        assertEquals(BindingCode.BINDING_INVALID, refused.code());
        assertEquals("CALL_ADMISSION_TOOL_MISMATCH", refused.details().get("reasonCode"));
    }

    /**
     * Not held at a lock, because the result does not depend on who arrives when: a claim deletes the row of the call by its key ({@code CallAdmissions}), and only the claimer whose
     * {@code DELETE} changed a row has the call. A claimer that reads the row after the winner's commit finds none, and one whose {@code DELETE} waits for the winner's row lock finds the
     * row gone when it goes on (READ COMMITTED). Whether the claims overlap or follow one another exactly one gets the row, and none ends in an error (there is no insert, so no key to lose on).
     */
    @Test
    void classC_ofManyClaimsOfOneCallExactlyOneGetsTheRow() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String toolUse = "toolu_" + UUID.randomUUID();
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", claudeSennardPreToolUse("windows", session, toolUse)).status());
        McpMeta meta = new McpMeta(SessionServerSupport.meta("windows", "claude", session, toolUse));
        CyclicBarrier barrier = new CyclicBarrier(8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<SessionBinding>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i += 1) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return resolver().bind("session_probe", Map.of(), meta, labelled("claude"), null);
                }));
            }
            int bound = 0;
            for (Future<SessionBinding> future : futures) {
                if (future.get(60, TimeUnit.SECONDS) instanceof SessionBinding.Bound) {
                    bound += 1;
                }
            }
            assertEquals(1, bound, "the row is consumed once");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aContradictionInAHostsMetadataIsRefusedNotBound() {
        String session = SessionServerSupport.uniqueSession();
        Map<String, Object> meta = new java.util.HashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        meta.put("threadId", UUID.randomUUID().toString());
        BindingException refused = assertThrows(BindingException.class, () -> resolver().bind("session_probe", Map.of(), new McpMeta(meta), labelled("codex"), null));
        assertEquals(BindingCode.BINDING_INVALID, refused.code());
    }

    // ------------------------------------------------------------------------------- class D: a host with no adapter

    @Test
    void classD_openSessionMintsAnUnownedSessionAndLaterToolsNameIt() throws Exception {
        McpSchema.CallToolResult opened = client("a-host-no-one-has-written-an-adapter-for").callTool(McpSchema.CallToolRequest.builder("open_session").arguments(OperationKeys.withKey("open_session", Map.of())).build());
        assertNotEquals(Boolean.TRUE, opened.isError(), opened.toString());
        var body = SessionServerSupport.json(new RawHttp.Response(200, Map.of(), ((McpSchema.TextContent) opened.content().getFirst()).text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("unavailable", body.path("data").path("trust").stringValue());
        String sessionRef = body.path("data").path("sessionRef").stringValue();
        Map<String, Object> row = SessionServerSupport.row(server, "SELECT g.process_key, g.relay_id, g.generation, r.host FROM sennard.session_ref r JOIN sennard.session_generation g "
                + "ON g.session_ref = r.session_ref WHERE r.session_ref = ?", sessionRef);
        assertNull(row.get("process_key"), "no host process to watch");
        assertNull(row.get("relay_id"), "no owner");
        assertEquals("a-host-no-one-has-written-an-adapter-for", row.get("host"));

        SelfSession self = assertInstanceOf(SessionBinding.Bound.class,
                resolver().bind("any_tool", Map.of(), new McpMeta(Map.of()), labelled("a-host-no-one-has-written-an-adapter-for"), sessionRef)).session();
        assertEquals(sessionRef, self.sessionRef());
        assertEquals(Provenance.UNAVAILABLE, self.provenance(), "nothing observed this caller");
        assertEquals(Role.INDETERMINATE, self.actor().role());
        assertNull(self.hostSessionId());
    }

    @Test
    void classD_continuityAcceptsTheMintedReferenceAndPeerStatusRequiresObservation() throws Exception {
        McpSyncClient client = client("a-host-no-one-has-written-an-adapter-for");
        McpSchema.CallToolResult opened = client.callTool(McpSchema.CallToolRequest.builder("open_session").arguments(OperationKeys.withKey("open_session", Map.of())).build());
        String sessionRef = toolBody(opened).path("data").path("sessionRef").stringValue();
        for (Map.Entry<String, Map<String, Object>> call : List.of(Map.entry("inspect_context", Map.<String, Object>of("schemaVersion", "1.0.0")),
                Map.entry("load_context", Map.<String, Object>of("schemaVersion", "1.0.0")),
                Map.entry("get_session_message_status", Map.<String, Object>of("schemaVersion", "1.0.0", "messageId", "message-does-not-exist")))) {
            Map<String, Object> named = new java.util.HashMap<>(call.getValue());
            named.put("sessionRef", sessionRef);
            var answered = toolBody(client.callTool(McpSchema.CallToolRequest.builder(call.getKey()).arguments(named).build()));
            if (call.getKey().equals("get_session_message_status")) {
                assertEquals("BINDING_REQUIRED", answered.path("error").path("code").stringValue(), answered.toString());
            } else {
                assertTrue(answered.path("ok").booleanValue(), call.getKey() + " " + answered);
            }
            var unnamed = toolBody(client.callTool(McpSchema.CallToolRequest.builder(call.getKey()).arguments(call.getValue()).build()));
            assertEquals("BINDING_REQUIRED", unnamed.path("error").path("code").stringValue(), call.getKey() + " " + unnamed);
        }
    }

    private static tools.jackson.databind.JsonNode toolBody(McpSchema.CallToolResult result) {
        return SessionServerSupport.json(new RawHttp.Response(200, Map.of(), ((McpSchema.TextContent) result.content().getFirst()).text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void classD_withoutAReferenceThereIsNoSessionAndAToolThatNeedsOneIsRefused() {
        SessionBinding binding = resolver().bind("any_tool", Map.of(), new McpMeta(Map.of()), McpTransportContext.EMPTY, null);
        assertEquals(new SessionBinding.Unbound("HOST_ADAPTER_NOT_AVAILABLE"), binding);
        BindingException required = assertThrows(BindingException.class, binding::require);
        assertEquals(BindingCode.BINDING_REQUIRED, required.code());
        assertEquals("HOST_ADAPTER_NOT_AVAILABLE", required.details().get("reasonCode"));
    }

    @Test
    void classD_aMalformedOrUnknownReferenceIsNotASession() {
        assertEquals(new SessionBinding.Unbound("SESSION_REF_INVALID"), resolver().bind("t", Map.of(), new McpMeta(Map.of()), McpTransportContext.EMPTY, "not-a-reference"));
        assertEquals(new SessionBinding.Unbound("SESSION_NOT_FOUND"),
                resolver().bind("t", Map.of(), new McpMeta(Map.of()), McpTransportContext.EMPTY, "as1_00000000000000000000000000"));
    }

    @Test
    void aKnownHostsObservedSessionIsNeverReplacedByAReferenceTheModelTyped() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook("windows", "codex", "SessionStart", null, session, "none").status());
        SessionBinding opened = resolver().bind("t", Map.of(), new McpMeta(SessionServerSupport.meta("windows", "codex", session, "none")), labelled("codex"),
                "as1_00000000000000000000000000");
        assertEquals(rowOf("codex", session).get("session_ref"), assertInstanceOf(SessionBinding.Bound.class, opened).session().sessionRef());
    }

    @Test
    void aKnownHostAskingForASessionGetsTheOneItAlreadyHas() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook("windows", "codex", "SessionStart", null, session, "none").status());
        McpSchema.CallToolResult opened = client("codex").callTool(McpSchema.CallToolRequest.builder("open_session").arguments(OperationKeys.withKey("open_session", Map.of()))
                .meta(SessionServerSupport.meta("windows", "codex", session, "none")).build());
        assertNotEquals(Boolean.TRUE, opened.isError(), opened.toString());
        var body = SessionServerSupport.json(new RawHttp.Response(200, Map.of(), ((McpSchema.TextContent) opened.content().getFirst()).text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("observed", body.path("data").path("trust").stringValue());
        assertEquals(rowOf("codex", session).get("session_ref"), body.path("data").path("sessionRef").stringValue());
    }

    // ------------------------------------------------------------------------------------------------------------ end

    @Test
    void aSessionEndHookEndsTheGeneration() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook("windows", "claude", "SessionStart", null, session, "none").status());
        String ref = (String) rowOf("claude", session).get("session_ref");
        CompletableFuture<SessionClosed> closed = server.bean(SessionServerSupport.ClosedSessions.class).future(ref);
        assertEquals(200, hook("windows", "claude", "SessionEnd", null, session, "none").status());
        Map<String, Object> row = rowOf("claude", session);
        assertNotNull(row.get("ended_at"));
        assertEquals(1L, ((Number) row.get("generation")).longValue());
        assertEquals(ref, closed.get(60, TimeUnit.SECONDS).sessionRef());
        assertEquals(200, hook("windows", "claude", "SessionEnd", null, session, "none").status(), "a repeated end is an answer, not an error");
    }

    // --------------------------------------------------------------------------------------- the handler's seams

    @Test
    void theHookPathLeavesItsRowsInTheStore() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        String toolUse = "toolu_" + UUID.randomUUID();
        assertEquals(200, hook("windows", "claude", "SessionStart", null, session, "none").status());
        assertNull(rowOf("claude", session).get("ended_at"), "the start hook made the session's live generation");
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/claude/PreToolUse/governance", claudeSennardPreToolUse("windows", session, toolUse)).status());
        assertEquals(1L, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.call_admission WHERE host = ? AND call_id = ?", stored("claude"), toolUse),
                "the PreToolUse hook of a Sennard tool left the row its call claims");
        assertEquals(200, hook("windows", "claude", "SessionEnd", null, session, "none").status());
        assertNotNull(rowOf("claude", session).get("ended_at"), "the end hook ended the generation");
    }

    @Test
    void differentSessionsNeverShareAReference() throws Exception {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 3; i += 1) {
            String session = SessionServerSupport.uniqueSession();
            assertEquals(200, hook("linux", "claude", "SessionStart", null, session, "none").status());
            refs.add((String) rowOf("claude", session).get("session_ref"));
        }
        assertEquals(3, refs.size());
    }
}
