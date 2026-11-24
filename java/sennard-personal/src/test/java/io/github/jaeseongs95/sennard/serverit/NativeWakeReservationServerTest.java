// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation;
import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation.State;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationRepository;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimReceipt;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeWorker;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters.MessageQueued;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHost;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.messaging.IdleWakeService;
import io.github.jaeseongs95.sennard.server.messaging.WakeServerIncarnation;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import io.github.jaeseongs95.sennard.server.session.HookSessions;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real store CAS/transactions; no native process or mock database. */
@Tag("business-db")
class NativeWakeReservationServerTest {
    private static RunningServer server;
    @BeforeAll static void start() throws Exception { server = SessionServerSupport.started(SessionServerSupport.sources()); }
    @AfterAll static void stop() { server.close(); }

    private record Target(String ref, long generation, String session) { }
    private Target target() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, session, body -> { }).status());
        var row = SessionServerSupport.row(server, "SELECT session_ref, last_generation FROM sennard.session_ref WHERE host = ? AND local_session_id = ?", "codex", session);
        return new Target((String) row.get("session_ref"), ((Number) row.get("last_generation")).longValue(), session);
    }
    private WakeReservationService service() { return server.bean(WakeReservationService.class); }

    @Test
    void aTitleReportCanUpdateOnlyTheCurrentWorkerSession() throws Exception {
        String key = UUID.randomUUID().toString();
        var board = server.bean(BoardService.class);
        String session;
        try (var owned = ownedTarget(key); var other = ownedTarget()) {
            session = owned.target().session();
            var mapper = JsonMapper.builder().build();
            long observed = java.time.Instant.now().toEpochMilli();
            byte[] first = mapper.writeValueAsBytes(Map.of("title", "Draft α", "observedAtMillis", observed));
            assertEquals(410, reportTitle(UUID.randomUUID().toString(), first).status());
            assertNull(board.readEntry("codex", session).orElseThrow().userTitle());
            assertEquals(204, reportTitle(key, first).status());
            assertEquals("Draft α", board.readEntry("codex", session).orElseThrow().userTitle());
            assertNull(board.readEntry("codex", other.target().session()).orElseThrow().userTitle());
            byte[] renamed = mapper.writeValueAsBytes(Map.of("title", "<b>Review β</b>", "observedAtMillis", observed + 1));
            assertEquals(204, reportTitle(key, renamed).status());
            assertEquals(204, reportTitle(key, first).status());
            assertEquals("<b>Review β</b>", board.readEntry("codex", session).orElseThrow().userTitle());
            assertEquals(400, reportTitle(key, mapper.writeValueAsBytes(Map.of("title", "", "observedAtMillis", observed + 2))).status());
        }
        byte[] late = JsonMapper.builder().build().writeValueAsBytes(Map.of("title", "Expired", "observedAtMillis", java.time.Instant.now().toEpochMilli()));
        assertEquals(410, reportTitle(key, late).status());
        assertEquals("<b>Review β</b>", board.readEntry("codex", session).orElseThrow().userTitle());
    }

    private static RawHttp.Response reportTitle(String key, byte[] body) throws Exception {
        return RawHttp.exchange(server.port, "POST", "/hooks/v1/native-wake/session-title",
                Map.of(NativeWakeProtocol.KEY, key, "Content-Type", "application/json", "Content-Length", Integer.toString(body.length)), body);
    }

    @Test
    void codexManagedChildrenStartWithoutReceivingPeerWake() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var owned = ownedTarget(key); var executor = Executors.newVirtualThreadPerTaskExecutor();
                var client = DomainServerSupport.client(server.port, "codex")) {
            Target root = owned.target();
            var wake = server.bean(IdleWakeService.class);
            String child = SessionServerSupport.uniqueSession();
            String sibling = SessionServerSupport.uniqueSession();
            Map<String, Object> launchArguments = Map.of("schemaVersion", "1.0.0", "operationKey", OperationKeys.fresh(), "model", "model", "effort", "effort");
            try (var named = DomainServerSupport.client(server.port, null)) {
                var namedArguments = new java.util.HashMap<>(launchArguments);
                namedArguments.put("sessionRef", root.ref());
                assertFalse(DomainServerSupport.callAsSent(named, "launch_managed_child", namedArguments, Map.of()).path("ok").asBoolean());
            }
            var firstPoll = executor.submit(() -> managedPoll(key));
            SessionServerSupport.await(() -> wake.outlook("codex", root.session())
                    .filter(advisory -> "RECIPIENT_ACTIVE".equals(String.valueOf(advisory.reason()))), "the worker's registration");
            var first = executor.submit(() -> callLaunch(client, root.session(), launchArguments));
            var firstOffer = firstPoll.get(12, TimeUnit.SECONDS);
            assertEquals(200, firstOffer.status(), firstOffer.text());
            assertEquals("UNKNOWN", callLaunch(client, root.session(), launchArguments).path("data").path("result").asString());
            completeManagedLaunch(key, firstOffer, first, child);
            assertEquals(child, callLaunch(client, root.session(), launchArguments).path("data").path("childId").asString());
            var changed = new java.util.HashMap<>(launchArguments);
            changed.put("effort", "other");
            assertEquals("REQUEST_CONFLICT", callLaunch(client, root.session(), changed).path("error").path("code").asString());
            assertFalse(callLaunch(client, child, launchArguments).path("ok").asBoolean());
            var siblingArguments = new java.util.HashMap<>(launchArguments);
            siblingArguments.put("operationKey", OperationKeys.fresh());
            var siblingLaunch = executor.submit(() -> callLaunch(client, root.session(), siblingArguments));
            completeManagedLaunch(key, executor.submit(() -> managedPoll(key)),
                    siblingLaunch, sibling);

            assertManagedConversationDoesNotReceivePeer("codex", root.session(), child);
            var registry = server.bean(SessionRegistry.class);
            String childRef = registry.findRef("codex", child).orElseThrow();
            assertTrue(service().status(childRef).isEmpty());
            assertTrue(service().status(root.ref()).isEmpty());
            assertTrue(service().status(registry.findRef("codex", sibling).orElseThrow()).isEmpty());
            assertTrue(service().reserve(childRef, root.generation() + 1, UUID.randomUUID().toString()).isEmpty());
            assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id IN (?, ?, ?)", root.session(), child, sibling));
        }
    }

    private static JsonNode callLaunch(McpSyncClient client, String session, Map<String, Object> arguments) {
        return DomainServerSupport.callAsSent(client, "launch_managed_child", arguments, SessionServerSupport.meta("windows", "codex", session, "none"));
    }

    @Test
    void claudeStartupBelongsToTheRootBeforeLaunchCompletion() throws Exception {
        String key = UUID.randomUUID().toString();
        String host = ClaudeHost.ID.sessionHost();
        try (var owned = ownedTarget(ClaudeHost.ID, key); var executor = Executors.newVirtualThreadPerTaskExecutor();
                var client = DomainServerSupport.client(server.port, "claude")) {
            Target root = owned.target();
            var wake = server.bean(IdleWakeService.class);
            var polling = executor.submit(() -> managedPoll(key));
            SessionServerSupport.await(() -> wake.outlook(host, root.session())
                    .filter(advisory -> "RECIPIENT_ACTIVE".equals(String.valueOf(advisory.reason()))), "the Claude worker's registration");

            var launchArguments = DomainServerSupport.arguments("operationKey", OperationKeys.fresh(), "model", "model", "effort", "effort");
            String toolUse = "toolu_" + UUID.randomUUID();
            var admitted = DomainServerSupport.hook(server.port, "windows", "claude", "PreToolUse", "governance", root.session(), body -> {
                body.put("tool_use_id", toolUse);
                body.put("tool_name", "mcp__plugin_sennard_sennard__launch_managed_child");
                body.set("tool_input", DomainServerSupport.MAPPER.valueToTree(launchArguments));
            });
            assertEquals(200, admitted.status(), admitted.text());
            var launching = executor.submit(() -> DomainServerSupport.callAsSent(client, "launch_managed_child", launchArguments,
                    SessionServerSupport.meta("windows", "claude", root.session(), toolUse)));

            var offered = polling.get(12, TimeUnit.SECONDS);
            assertEquals(200, offered.status(), () -> launching.isDone() ? launching.resultNow().toString() : offered.text());
            String child = DomainServerSupport.MAPPER.readTree(offered.body()).path("id").asString();
            assertNotEquals(root.session(), child);

            var startup = DomainServerSupport.hook(server.port, "windows", "claude", "SessionStart", null, child,
                    body -> body.put("source", "startup"));
            assertEquals(200, startup.status(), startup.text());
            var registry = server.bean(SessionRegistry.class);
            assertEquals(Optional.of(new SessionRegistry.ManagedRoot(root.ref(), root.generation())), registry.managedRootOf(host, child));
            assertTrue(server.bean(HookSessions.class).ownership(host, child).isEmpty());
            String childRef = registry.findRef(host, child).orElseThrow();
            assertFalse(registry.isLiveGeneration(childRef, root.generation()));

            var mismatched = RawHttp.exchange(server.port, "POST", NativeWakeProtocol.LAUNCH_COMPLETE + "/CREATED",
                    Map.of(NativeWakeProtocol.KEY, key, NativeWakeProtocol.LAUNCH_ID, child,
                            NativeWakeProtocol.CHILD_ID, SessionServerSupport.uniqueSession()), null);
            assertEquals(409, mismatched.status());
            completeManagedLaunch(key, offered, launching, child);
        }
    }

    private RawHttp.Response managedPoll(String key) throws Exception {
        return RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL,
                Map.of(NativeWakeProtocol.KEY, key, NativeWakeProtocol.CAPABILITIES, NativeWakeProtocol.Capability.BOTH.name()), null);
    }

    private void completeManagedLaunch(String key, Future<RawHttp.Response> polled, Future<JsonNode> reply, String child) throws Exception {
        completeManagedLaunch(key, polled.get(12, TimeUnit.SECONDS), reply, child);
    }

    private void completeManagedLaunch(String key, RawHttp.Response launch, Future<JsonNode> reply, String child) throws Exception {
        assertEquals(200, launch.status(), launch.text());
        var offered = DomainServerSupport.MAPPER.readTree(launch.body());
        assertEquals("model", offered.path("model").asString());
        assertEquals("effort", offered.path("effort").asString());
        String launchId = offered.path("id").asString();
        var completed = RawHttp.exchange(server.port, "POST", NativeWakeProtocol.LAUNCH_COMPLETE + "/CREATED",
                Map.of(NativeWakeProtocol.KEY, key, NativeWakeProtocol.LAUNCH_ID, launchId, NativeWakeProtocol.CHILD_ID, child), null);
        assertEquals(204, completed.status(), completed.text());
        var result = reply.get(5, TimeUnit.SECONDS);
        assertTrue(result.path("ok").asBoolean(), result.toString());
        assertEquals("CREATED", result.path("data").path("result").asString());
        assertEquals(child, result.path("data").path("childId").asString());
    }

    @Test
    void aNewManagedChildKeepsItsFirstRootAndGenerationInTheIdentityStore() throws Exception {
        try (var first = ownedTarget(); var other = ownedTarget()) {
            Target root = first.target();
            Target unrelated = other.target();
            var registry = server.bean(SessionRegistry.class);
            String child = SessionServerSupport.uniqueSession();
            assertFalse(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation() + 1, child));
            assertTrue(registry.managedRootOf("codex", child).isEmpty());
            assertFalse(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation(), unrelated.session()));
            assertFalse(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation(), root.session()));

            assertTrue(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation(), child));
            var binding = new SessionRegistry.ManagedRoot(root.ref(), root.generation());
            assertEquals(binding, registry.managedRootOf("codex", child).orElseThrow());
            assertTrue(registry.isChild("codex", child));
            assertFalse(registry.bindManagedChild("codex", unrelated.session(), unrelated.ref(), unrelated.generation(), child));
            assertFalse(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation() + 1, child));
            assertFalse(registry.bindManagedChild("codex", root.session(), root.ref(), root.generation(), child));
            assertEquals(binding, registry.managedRootOf("codex", child).orElseThrow());
        }
    }

    private record OwnedTarget(Target target, Process process) implements AutoCloseable {
        @Override public void close() {
            process.destroyForcibly();
            // join waits for reaping without clearing an interrupt or abandoning the owned child.
            process.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            assertFalse(Thread.currentThread().isInterrupted(), "interrupted while reaping the ownership fixture process");
        }
    }

    private OwnedTarget ownedTarget() throws Exception {
        return ownedTarget(null);
    }

    private static Process ownerProcess() throws Exception {
        // The existing inert child fixture supplies a real observable owner; the test JVM cannot watch its own exit.
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path classes = Path.of(HostOwnedSessionServerTest.Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ProcessBuilder(java.toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", classes.toString(),
                HostOwnedSessionServerTest.Sleeper.class.getName()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private OwnedTarget ownedTarget(String wakeKey) throws Exception {
        return ownedTarget(CodexHost.ID, wakeKey);
    }

    private OwnedTarget ownedTarget(HostId host, String wakeKey) throws Exception {
        Process process = ownerProcess();
        try {
            String session = SessionServerSupport.uniqueSession();
            byte[] body = SessionServerSupport.hook("windows", host.label(), "SessionStart", session, "none");
            var headers = new java.util.HashMap<>(Map.of("Content-Type", "application/json", "Content-Length", Integer.toString(body.length),
                    HookHeaders.HOST_PIDS, Long.toString(process.pid())));
            if (wakeKey != null) {
                headers.put(NativeWakeProtocol.KEY, wakeKey);
            }
            var start = RawHttp.exchange(server.port, "POST", "/hooks/v1/" + host.label() + "/SessionStart", headers, body);
            assertEquals(200, start.status(), start.text());
            if (wakeKey != null) {
                assertEquals(wakeKey, start.headers().get(NativeWakeProtocol.KEY.toLowerCase(Locale.ROOT)));
            }
            HookSessions.Ownership owner = SessionServerSupport.await(() -> server.bean(HookSessions.class).ownership(host.sessionHost(), session),
                    "the fixture's real process to own the target session");
            assertTrue(server.bean(SessionRegistry.class).isLiveGeneration(owner.sessionRef(), owner.generation()));
            return new OwnedTarget(new Target(owner.sessionRef(), owner.generation(), session), process);
        } catch (Exception | Error failure) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                failure.addSuppressed(new AssertionError("the failed ownership fixture process was not reaped"));
            }
            throw failure;
        }
    }

    private RawHttp.Response agyWakeHook(Process owner, String event, String session, String key) throws Exception {
        byte[] body = SessionServerSupport.hook("windows", "agy", event, session, "none");
        var response = RawHttp.exchange(server.port, "POST", "/hooks/v1/agy/" + event,
                Map.of("Content-Type", "application/json", "Content-Length", Integer.toString(body.length),
                        HookHeaders.HOST_PIDS, Long.toString(owner.pid()), NativeWakeProtocol.KEY, key), body);
        assertEquals(200, response.status());
        return response;
    }

    private static String acceptedKey(RawHttp.Response response) {
        return response.headers().get(NativeWakeProtocol.KEY.toLowerCase(Locale.ROOT));
    }

    private static void reap(Process owner) {
        owner.destroyForcibly();
        owner.onExit().orTimeout(5, TimeUnit.SECONDS).join();
    }

    // An unclassified conversation and a linked child must never open a worker, even with a live process and a valid key.
    @Test
    void antigravityUnconfirmedAndChildHooksCannotRegisterWake() throws Exception {
        Process owner = ownerProcess();
        try {
            String child = SessionServerSupport.uniqueSession();
            String root = SessionServerSupport.uniqueSession();
            String key = UUID.randomUUID().toString();
            assertNull(acceptedKey(agyWakeHook(owner, "SessionStart", child, key)));
            assertEquals(410, RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL, Map.of(NativeWakeProtocol.KEY, key), null).status());

            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.observeAgy(server, child, root, root);
            assertNull(acceptedKey(agyWakeHook(owner, "SessionStart", child, key)));
            assertNull(acceptedKey(agyWakeHook(owner, "Stop", child, key)));
        } finally {
            reap(owner);
        }
    }

    // A proven root opens its relay at an idle Stop without another SessionStart. A fresh key cannot replace a connected worker.
    @Test
    void antigravityObservedRootRegistersOnceAndReceivesItsPeerMessage() throws Exception {
        Process owner = ownerProcess();
        try {
            String root = SessionServerSupport.uniqueSession();
            String key = UUID.randomUUID().toString();
            agyWakeHook(owner, "SessionStart", root, UUID.randomUUID().toString());
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.markTurnUnchanged(server, "agy", root);
            DomainServerSupport.writeSessionSummary(server, "agy", root, "ready for peer messages");
            assertEquals(key, acceptedKey(agyWakeHook(owner, "Stop", root, key)));
            assertNull(acceptedKey(agyWakeHook(owner, "Stop", root, UUID.randomUUID().toString())));

            SessionServerSupport.await(() -> server.bean(HookSessions.class).ownership("antigravity", root), "Antigravity root process ownership");
            Target sender = target();
            SessionMessageService messages = server.bean(SessionMessageService.class);
            var prepared = messages.prepare(sender.ref(), "antigravity", root, "peer root " + UUID.randomUUID(), null,
                    OperationKeys.operation("prepare_session_message", sender.ref()));
            messages.send(sender.ref(), "codex", prepared.messageId(), OperationKeys.operation("send_session_message", sender.ref()));

            var offer = RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL, Map.of(NativeWakeProtocol.KEY, key), null);
            assertEquals(200, offer.status());
            assertTrue(NativeWakeProtocol.validMarker(offer.text()));
            var input = DomainServerSupport.hook(server.port, "windows", "agy", "PreInvocation", null, root,
                    body -> body.put("invocationNum", 0).put("lastUserInput", offer.text()));
            assertEquals(200, input.status());
            assertTrue(input.text().contains(prepared.messageId()));
            var replay = DomainServerSupport.hook(server.port, "windows", "agy", "PreInvocation", null, root,
                    body -> body.put("invocationNum", 0).put("lastUserInput", offer.text()));
            assertFalse(replay.text().contains(prepared.messageId()));
            assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE host = ? AND session_id = ?", "antigravity", root));
        } finally {
            reap(owner);
        }
    }

    @Test
    void antigravityManagedChildrenStartWithoutReceivingPeerWake() throws Exception {
        Process owner = ownerProcess();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var client = DomainServerSupport.client(server.port, "agy")) {
            String root = SessionServerSupport.uniqueSession();
            String child = SessionServerSupport.uniqueSession();
            String sibling = SessionServerSupport.uniqueSession();
            String key = UUID.randomUUID().toString();
            agyWakeHook(owner, "SessionStart", root, UUID.randomUUID().toString());
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.markTurnUnchanged(server, "agy", root);
            DomainServerSupport.writeSessionSummary(server, "agy", root, "ready for peer messages");
            assertEquals(key, acceptedKey(agyWakeHook(owner, "Stop", root, key)));
            var wake = server.bean(IdleWakeService.class);
            var firstPoll = executor.submit(() -> managedPoll(key));
            SessionServerSupport.await(() -> wake.outlook("antigravity", root)
                    .filter(advisory -> "AVAILABLE".equals(String.valueOf(advisory.state()))), "the Antigravity worker's registration");

            launchAgyChild(client, executor, firstPoll, key, root, child);
            launchAgyChild(client, executor, executor.submit(() -> managedPoll(key)), key, root, sibling);
            assertNull(acceptedKey(agyWakeHook(owner, "SessionStart", child, UUID.randomUUID().toString())));

            assertManagedConversationDoesNotReceivePeer("antigravity", root, child);
            assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id IN (?, ?, ?)", root, child, sibling));
        } finally {
            reap(owner);
        }
    }

    private void launchAgyChild(McpSyncClient client, ExecutorService executor, Future<RawHttp.Response> poll,
            String key, String root, String child) throws Exception {
        String tool = "launch_managed_child";
        var arguments = DomainServerSupport.arguments("operationKey", OperationKeys.fresh(), "model", "model", "effort", "effort");
        var admitted = DomainServerSupport.hook(server.port, "windows", "agy", "PreToolUse", null, root, body -> {
            var nativeTool = body.putObject("toolCall").put("name", "call_mcp_tool").putObject("args");
            nativeTool.put("ServerName", "sennard").put("ToolName", tool).set("Arguments", DomainServerSupport.MAPPER.valueToTree(arguments));
        });
        assertEquals(200, admitted.status(), admitted.text());
        assertEquals("allow", DomainServerSupport.MAPPER.readTree(admitted.body()).path("decision").stringValue());
        var launch = executor.submit(() -> DomainServerSupport.callAsSent(client, tool, arguments, SessionServerSupport.meta("windows", "agy", root, "none")));
        completeManagedLaunch(key, poll, launch, child);
    }

    private void assertManagedConversationDoesNotReceivePeer(String host, String root, String child) throws Exception {
        Target sender = target();
        var messages = server.bean(SessionMessageService.class);
        var refused = assertThrows(BindingException.class, () -> messages.prepare(PeerAddress.session(sender.ref()), host, root, child,
                "legacy child address", null, OperationKeys.operation("prepare_session_message", sender.ref())));
        assertEquals(BindingCode.PEER_MAIN_SESSION_REQUIRED, refused.code());
        server.bean(IdleWakeService.class).on(new MessageQueued(host, root, child));
        String childRef = server.bean(SessionRegistry.class).findRef(host, child).orElseThrow();
        assertTrue(service().status(childRef).isEmpty());
    }

    @Test
    void antigravityRefusesToMoveAnObservedRootUnderAnotherRoot() throws Exception {
        Process owner = ownerProcess();
        try {
            String conversation = SessionServerSupport.uniqueSession();
            String parent = SessionServerSupport.uniqueSession();
            String key = UUID.randomUUID().toString();
            agyWakeHook(owner, "SessionStart", conversation, UUID.randomUUID().toString());
            DomainServerSupport.observeAgyRoot(server, conversation);
            DomainServerSupport.markTurnUnchanged(server, "agy", conversation);
            DomainServerSupport.writeSessionSummary(server, "agy", conversation, "ready for peer messages");
            assertEquals(key, acceptedKey(agyWakeHook(owner, "Stop", conversation, key)));
            IdleWakeService wake = server.bean(IdleWakeService.class);
            assertTrue(wake.outlook("antigravity", conversation).isPresent());

            DomainServerSupport.observeAgyRoot(server, parent);
            var response = DomainServerSupport.agyObservation(server, conversation, parent, parent);

            assertEquals("BINDING_REQUIRED", response.path("error").path("code").asString());
            assertTrue(wake.outlook("antigravity", conversation).isPresent());
        } finally {
            reap(owner);
        }
    }

    private String send(Target recipient) throws Exception {
        return sendTo("codex", recipient.session(), null).messageId();
    }

    private SessionMessageService.SendResult sendTo(String host, String session, String actor) throws Exception {
        Target sender = target();
        SessionMessageService messages = server.bean(SessionMessageService.class);
        var prepared = messages.prepare(PeerAddress.session(sender.ref()), host, session, actor, "wake-lock-" + UUID.randomUUID(), null,
                OperationKeys.operation("prepare_session_message", sender.ref()));
        return messages.send(sender.ref(), "codex", prepared.messageId(), OperationKeys.operation("send_session_message", sender.ref()));
    }

    @Test
    void workerReportsDisabledThroughTheSendOutlook() throws Exception {
        String disabledKey = UUID.randomUUID().toString();
        try (var disabled = ownedTarget(disabledKey)) {
            String session = disabled.target().session();
            assertWakeOutlook(sendTo("codex", session, null), "no-live-relay", "relay-lease-missing");
            runWorker("codex", "SessionStart", session, disabledKey, Map.of("SENNARD_CODEX_QUEUE_WAKE", "0"));
            assertWakeOutlook(sendTo("codex", session, null), "unsupported", "no-idle-wake");
        }
    }

    @Test
    void workerReportsFileConfiguredDisablementThroughTheSendOutlook(@TempDir Path pluginData) throws Exception {
        Files.writeString(pluginData.resolve("session-messaging.json"),
                "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":false}}");
        String disabledKey = UUID.randomUUID().toString();
        try (var disabled = ownedTarget(disabledKey)) {
            String session = disabled.target().session();
            runWorker("codex", "SessionStart", session, disabledKey, Map.of("PLUGIN_DATA", pluginData.toString()));
            assertWakeOutlook(sendTo("codex", session, null), "unsupported", "no-idle-wake");
        }
    }

    @Test
    void codexWorkerReportsMissingPrerequisitesThroughTheSendOutlook() throws Exception {
        String missingKey = UUID.randomUUID().toString();
        try (var missing = ownedTarget(missingKey)) {
            String session = missing.target().session();
            runWorker("codex", "SessionStart", session, missingKey, Map.of("SENNARD_CODEX_QUEUE_WAKE", "malformed"));
            assertWakeOutlook(sendTo("codex", session, null), "degraded", "wake-prerequisite-missing");
        }
    }

    @Test
    void antigravityWorkerWithoutItsLocalPrerequisitesReportsDegraded() throws Exception {
        Process owner = ownerProcess();
        try {
            String session = SessionServerSupport.uniqueSession();
            String key = UUID.randomUUID().toString();
            agyWakeHook(owner, "SessionStart", session, UUID.randomUUID().toString());
            DomainServerSupport.observeAgyRoot(server, session);
            DomainServerSupport.markTurnUnchanged(server, "agy", session);
            DomainServerSupport.writeSessionSummary(server, "agy", session, "ready for peer messages");
            assertEquals(key, acceptedKey(agyWakeHook(owner, "Stop", session, key)));
            runWorker("agy", "Stop", session, key, Map.of());
            assertWakeOutlook(sendTo("antigravity", session, null), "degraded", "wake-prerequisite-missing");
        } finally {
            reap(owner);
        }
    }

    @Test
    void targetWithoutABindingReportsPresenceUnknown() throws Exception {
        try (var target = ownedTarget()) {
            assertWakeOutlook(sendTo("codex", target.target().session(), null), "no-live-relay", "presence-unknown");
        }
    }

    private static void assertWakeOutlook(SessionMessageService.SendResult sent, String state, String reason) {
        JsonNode outlook = JsonMapper.builder().build().valueToTree(sent.autoWake());
        assertEquals(state, outlook.path("state").asString());
        assertEquals(reason, outlook.path("reason").asString());
        assertEquals("none", outlook.path("authorityEffect").asString());
    }

    private static void runWorker(String host, String event, String session, String key, Map<String, String> environment) throws Exception {
        ProcessHandle process = ProcessHandle.current();
        var bytes = new ByteArrayOutputStream();
        try (var input = new DataOutputStream(bytes)) {
            input.writeUTF(key);
            input.writeInt(server.port);
            input.writeLong(process.pid());
            input.writeUTF(process.info().startInstant().orElseThrow().toString());
            input.write(SessionServerSupport.hook("windows", host, event, session, "none"));
        }
        assertEquals(0, NativeWakeWorker.run(new String[]{NativeWakeProtocol.COMMAND, host, event},
                new ByteArrayInputStream(bytes.toByteArray()), environment, Clock.systemUTC()));
    }

    @Test
    void relayHandsOffOnlyOneMarkerAndRequiresTheNativeInputToObserveIt() throws Exception {
        String key = UUID.randomUUID().toString();
        try (OwnedTarget owned = ownedTarget(key)) {
            Target target = owned.target();
            var request = Map.of(NativeWakeProtocol.KEY, key);
            assertEquals(410, RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL,
                    Map.of(NativeWakeProtocol.KEY, UUID.randomUUID().toString()), null).status());
            DomainServerSupport.markTurnUnchanged(server, "codex", target.session());
            assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "Stop", null, target.session(), body -> { }).status());
            String messageId = send(target);

            var offer = RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL, request, null);
            assertEquals(200, offer.status());
            assertTrue(NativeWakeProtocol.validMarker(offer.text()));
            assertFalse(offer.text().contains(messageId));
            assertEquals(409, RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL, request, null).status());
            String complete = NativeWakeProtocol.COMPLETE + "/SUBMITTED";
            assertEquals(409, RawHttp.exchange(server.port, "POST", complete,
                    Map.of(NativeWakeProtocol.KEY, key, NativeWakeProtocol.MARKER, IdleWakeService.marker(UUID.randomUUID().toString())), null).status());
            assertEquals(204, RawHttp.exchange(server.port, "POST", complete,
                    Map.of(NativeWakeProtocol.KEY, key, NativeWakeProtocol.MARKER, offer.text()), null).status());
            SessionServerSupport.await(() -> service().status(target.ref()).filter(row -> row.state() == State.SUBMITTED), "worker handoff to commit");
            var input = DomainServerSupport.hook(server.port, "windows", "codex", "UserPromptSubmit", null, target.session(),
                    body -> body.put("prompt", offer.text()));
            assertEquals(200, input.status());
            assertTrue(input.text().contains(messageId));
            assertEquals(State.OBSERVED, service().status(target.ref()).orElseThrow().state());
            var replay = DomainServerSupport.hook(server.port, "windows", "codex", "UserPromptSubmit", null, target.session(),
                    body -> body.put("prompt", offer.text()));
            assertFalse(replay.text().contains(messageId));
            assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionEnd", null, target.session(), body -> { }).status());
            assertEquals(410, RawHttp.exchange(server.port, "POST", NativeWakeProtocol.POLL, request, null).status());
        }
    }

    @Test void replayedAcknowledgementDoesNotObserveALaterUnknownAttempt() throws Exception {
        Target target = target();
        String messageId = send(target);
        SessionMessageService messages = server.bean(SessionMessageService.class);
        Delivered delivered = messages.claim("codex", target.session(), target.ref(), Boundary.WAKE).getFirst();
        assertEquals(messageId, delivered.messageId());
        String owner = UUID.randomUUID().toString();
        WakeReservation first = service().reserve(target.ref(), target.generation(), owner).orElseThrow();
        assertTrue(service().start(first));
        service().finish(first, State.UNKNOWN);
        var operation = OperationKeys.operation("acknowledge_session_messages", target.ref());
        var receipts = List.of(new ClaimReceipt(messageId, delivered.claimReceipt()));
        assertEquals(1, messages.acknowledge(target.ref(), List.of(messageId), receipts, operation).acknowledged());
        assertEquals(State.OBSERVED, service().status(target.ref()).orElseThrow().state(), "a fresh ACK observes the current attempt");

        WakeReservation second = service().reserve(target.ref(), target.generation(), owner).orElseThrow();
        assertTrue(service().start(second));
        service().finish(second, State.UNKNOWN);
        WakeReservation beforeReplay = service().status(target.ref()).orElseThrow();
        assertEquals(1, messages.acknowledge(target.ref(), List.of(messageId), receipts, operation).acknowledged(), "the same operation keeps its original answer");
        WakeReservation afterReplay = service().status(target.ref()).orElseThrow();
        assertEquals(State.UNKNOWN, afterReplay.state(), "an old ACK replay must not observe the later UNKNOWN attempt");
        assertEquals(beforeReplay, afterReplay, "a replay must not mutate the later reservation");
        assertTrue(service().reserve(target.ref(), target.generation(), owner).isEmpty(), "UNKNOWN must still block redispatch");
    }

    @Test void ordinaryClaimAndNonceClaimDoNotInvertDatabaseLocks() throws Exception {
        try (OwnedTarget owned = ownedTarget()) {
        Target target = owned.target();
        String messageId = send(target);
        IdleWakeService wake = server.bean(IdleWakeService.class);
        HostContext context = new HostContext(new HostId("codex", "codex"), target.session(),
                HostContext.SessionActor.root(HostContext.Basis.OBSERVED, "test"), null, null, null, null, null);
        wake.bootstrap(context, UUID.randomUUID().toString());
        // Use the real service incarnation without starting a native process; ownership and nonce checks remain real.
        String incarnation = server.bean(WakeServerIncarnation.class).value();
        WakeReservation reservation = service().reserve(target.ref(), target.generation(), incarnation).orElseThrow();
        assertTrue(service().start(reservation));
        TransactionTemplate transaction = server.bean(TransactionTemplate.class);
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        SessionMessageService messages = server.bean(SessionMessageService.class);
        // The lock-order race starts after a real input; first-row creation conflicts have separate store tests.
        messages.observeNativeInput(target.ref());
        assertEquals(1, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.input_observations WHERE session_ref = ? AND state = 'DEFERRED'", target.ref()));
        CompletableFuture<Integer> wakePid = new CompletableFuture<>();
        CompletableFuture<Integer> ordinaryPid = new CompletableFuture<>();
        CountDownLatch proceed = new CountDownLatch(1);
        MethodInterceptor pauseAfterConsume = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("consume") && target.ref().equals(invocation.getArguments()[0]) && Boolean.TRUE.equals(result)) {
                wakePid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                try {
                    assertTrue(proceed.await(20, TimeUnit.SECONDS), "the orchestrator must release the native claim");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return result;
        };
        Advised reservations = (Advised) service();
        reservations.addAdvice(pauseAfterConsume);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Delivered>> nativeClaim = pool.submit(() -> {
                try {
                    List<Delivered> result = transaction.execute(status -> {
                        jdbc.sql("SET LOCAL statement_timeout = '15s'").update();
                        return messages.claimWakeInput(context, reservation.nonce());
                    });
                    wakePid.completeExceptionally(new AssertionError("native claim returned before a successful consume: " + result));
                    return result;
                } catch (Exception | Error failure) {
                    wakePid.completeExceptionally(failure);
                    throw failure;
                }
            });
            try {
                int nativePid = wakePid.get(20, TimeUnit.SECONDS);
                Future<List<Delivered>> ordinaryClaim = pool.submit(() -> transaction.execute(status -> {
                    jdbc.sql("SET LOCAL statement_timeout = '15s'").update();
                    ordinaryPid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                    return messages.claim("codex", target.session(), target.ref(), Boundary.WAKE);
                }));
                int waitingPid = ordinaryPid.get(20, TimeUnit.SECONDS);
                SessionServerSupport.await(() -> SessionServerSupport.count(server,
                        "SELECT CASE WHEN ? = ANY(pg_blocking_pids(?)) THEN 1 ELSE 0 END", nativePid, waitingPid) == 1
                                ? Optional.of(true) : Optional.empty(), "the ordinary claim to wait for the native reservation transaction");
                proceed.countDown();
                List<Delivered> nativeResult = nativeClaim.get(20, TimeUnit.SECONDS);
                List<Delivered> ordinaryResult = ordinaryClaim.get(20, TimeUnit.SECONDS);
                assertEquals(1, nativeResult.size() + ordinaryResult.size(), "the concurrent claims deliver exactly one body");
                Delivered result = nativeResult.isEmpty() ? ordinaryResult.getFirst() : nativeResult.getFirst();
                assertEquals(messageId, result.messageId());
                assertNotNull(result.sourceReceiptId(), "the winner creates its real source receipt");
                assertEquals(State.OBSERVED, service().status(target.ref()).orElseThrow().state());
                assertFalse(service().consume(target.ref(), target.generation(), reservation.nonce(), reservation.serverId()), "the nonce is consumed once");
            } finally {
                proceed.countDown();
            }
        } finally {
            reservations.removeAdvice(pauseAfterConsume);
        }
        }
    }

    @Test void simultaneousRequestsHaveOneWinner() throws Exception {
        Target target = target();
        String owner = UUID.randomUUID().toString();
        // Make the first row in one committed call; the race below is the actual conditional reservation of an existing target.
        WakeReservation first = service().reserve(target.ref(), target.generation(), owner).orElseThrow();
        service().cancelReserved(target.ref(), target.generation());
        CyclicBarrier barrier = new CyclicBarrier(8);
        List<Future<Optional<WakeReservation>>> futures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> { barrier.await(); return service().reserve(target.ref(), target.generation(), owner); }));
            }
            int winners = 0;
            for (var future : futures) { if (future.get(20, TimeUnit.SECONDS).isPresent()) winners++; }
            assertEquals(1, winners);
        }
        assertNotEquals(first.nonce(), service().status(target.ref()).orElseThrow().nonce());
    }

    @Test void uncertainAttemptSurvivesRecreationAndRejectsForeignServerNonce() throws Exception {
        Target target = target();
        String firstServer = UUID.randomUUID().toString();
        String newServer = UUID.randomUUID().toString();
        WakeReservation row = service().reserve(target.ref(), target.generation(), firstServer).orElseThrow();
        assertTrue(service().start(row));
        service().finish(row, State.UNKNOWN);
        service().recover(target.ref(), target.generation(), newServer);
        assertTrue(service().reserve(target.ref(), target.generation(), newServer).isEmpty());
        assertFalse(service().consume(target.ref(), target.generation(), row.nonce(), newServer));
        assertFalse(service().consume(target.ref(), target.generation() + 1, row.nonce(), firstServer));
        assertFalse(service().consume(target.ref(), target.generation(), UUID.randomUUID().toString(), firstServer));
        assertEquals(State.UNKNOWN, service().status(target.ref()).orElseThrow().state());
        service().bodyObserved(target.ref());
        assertTrue(service().reserve(target.ref(), target.generation(), newServer).isPresent());
    }

    @Test void onlyLiveMatchingNonceConsumesOnceAndTransactionRollbackRestoresIt() throws Exception {
        Target target = target();
        String owner = UUID.randomUUID().toString();
        WakeReservation row = service().reserve(target.ref(), target.generation(), owner).orElseThrow();
        assertTrue(service().start(row));
        TransactionTemplate transaction = server.bean(TransactionTemplate.class);
        transaction.executeWithoutResult(status -> {
            assertTrue(service().consume(target.ref(), target.generation(), row.nonce(), owner));
            status.setRollbackOnly();
        });
        assertTrue(service().consume(target.ref(), target.generation(), row.nonce(), owner));
        assertFalse(service().consume(target.ref(), target.generation(), row.nonce(), owner));
        service().finish(row, State.UNKNOWN);
        assertEquals(State.OBSERVED, service().status(target.ref()).orElseThrow().state());
    }

    @Test void recoveryChangesOnlyTheOwnedSessionAndGeneration() throws Exception {
        Target own = target();
        Target other = target();
        String firstServer = UUID.randomUUID().toString();
        String secondServer = UUID.randomUUID().toString();
        WakeReservation ownRow = service().reserve(own.ref(), own.generation(), firstServer).orElseThrow();
        WakeReservation otherRow = service().reserve(other.ref(), other.generation(), secondServer).orElseThrow();
        assertTrue(service().start(ownRow));
        assertTrue(service().start(otherRow));
        service().recover(own.ref(), own.generation() + 1, secondServer);
        assertEquals(State.STARTED, service().status(own.ref()).orElseThrow().state(), "wrong generation must not recover the old one");
        service().recover(own.ref(), own.generation(), secondServer);
        assertEquals(State.UNKNOWN, service().status(own.ref()).orElseThrow().state());
        assertEquals(State.STARTED, service().status(other.ref()).orElseThrow().state(), "another session remains owned by its server");
        assertEquals(otherRow.nonce(), service().status(other.ref()).orElseThrow().nonce());
        assertEquals(secondServer, service().status(other.ref()).orElseThrow().serverId());
    }

    @Test void endedGenerationCannotStartAndUncertaintyNeedsFreshActivityAfterGrace() throws Exception {
        Target ended = target();
        String owner = UUID.randomUUID().toString();
        WakeReservation reserved = service().reserve(ended.ref(), ended.generation(), owner).orElseThrow();
        SessionServerSupport.update(server, "UPDATE sennard.session_generation SET ended_at = CURRENT_TIMESTAMP WHERE session_ref = ? AND generation = ?", ended.ref(), ended.generation());
        assertFalse(service().start(reserved));
        assertFalse(server.bean(SessionRegistry.class).isLiveGeneration(ended.ref(), ended.generation()));

        Target active = target();
        WakeReservation uncertain = service().reserve(active.ref(), active.generation(), owner).orElseThrow();
        assertTrue(service().start(uncertain));
        service().finish(uncertain, State.UNKNOWN);
        service().nativeActivity(active.ref(), active.generation());
        assertTrue(service().reserve(active.ref(), active.generation(), owner).isEmpty());
        Clock afterGrace = Clock.fixed(service().status(active.ref()).orElseThrow().updatedAt().plusSeconds(71 * 60), ZoneOffset.UTC);
        WakeReservationService later = new WakeReservationService(server.bean(WakeReservationRepository.class), afterGrace);
        later.nativeActivity(active.ref(), active.generation());
        assertEquals(State.OBSERVED, service().status(active.ref()).orElseThrow().state());
    }
}
