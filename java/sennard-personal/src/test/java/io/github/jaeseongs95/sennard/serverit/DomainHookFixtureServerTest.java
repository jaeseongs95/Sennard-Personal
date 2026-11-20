// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The continuity and messaging answers to the hooks of the three hosts, on the assembled server with real PostgreSQL. Bodies are the probe's
 * recordings with this test's session in them; where the probe recorded no such event or tool, named fields of a recording are changed.
 */
@Tag("business-db")
class DomainHookFixtureServerTest {
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static RunningServer server;
    private static Path repository;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.hooks.wake-hold-ms=120000");
        repository = Path.of("build", "domain-hook-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(repository);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static RawHttp.Response hook(String os, String host, String event, String module, String session, Consumer<ObjectNode> edit) throws IOException {
        return DomainServerSupport.hook(server.port, os, host, event, module, session, edit);
    }

    private static RawHttp.Response hook(String os, String host, String event, String session) throws IOException {
        return hook(os, host, event, null, session, body -> {
        });
    }

    private static JsonNode ok(RawHttp.Response response) {
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.MAPPER.readTree(response.body());
    }

    private static String newSession(String host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        ok(hook("windows", host, "SessionStart", session));
        return session;
    }

    private static String stored(String host) {
        return switch (host) {
            case "claude" -> "claude-code";
            case "agy" -> "antigravity";
            default -> host;
        };
    }

    private static String contextOf(String host, JsonNode output) {
        return switch (host) {
            case "agy" -> output.path("injectSteps").path(0).has("ephemeralMessage") ? output.path("injectSteps").path(0).path("ephemeralMessage").asString()
                    : output.path("injectSteps").path(0).path("systemMessage").path("systemMessage").asString();
            default -> output.path("hookSpecificOutput").path("additionalContext").asString();
        };
    }

    // --------------------------------------------------------------------------------------------------------------- turns

    @ParameterizedTest
    @CsvSource({"windows,claude,UserPromptSubmit", "linux,claude,UserPromptSubmit", "windows,codex,UserPromptSubmit", "linux,codex,UserPromptSubmit", "windows,agy,PreInvocation", "linux,agy,PreInvocation"})
    void aTurnStartGetsTheTurnCheck(String os, String host, String event) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        JsonNode output = ok(hook(os, host, event, session));
        String context = contextOf(host, output);
        assertTrue(context.contains("[Sennard turn check]"), output.toString());
        assertTrue(context.contains("checkpoint_context"), output.toString());
    }

    @ParameterizedTest
    @CsvSource({"windows,claude", "linux,claude", "windows,codex", "linux,codex", "windows,agy", "linux,agy"})
    void aTurnEndWithNoWorkflowHasNothingToContinue(String os, String host) throws IOException {
        JsonNode output = ok(hook(os, host, "Stop", SessionServerSupport.uniqueSession()));
        assertTrue(output.isEmpty() || !output.has("decision"), output.toString());
    }

    @ParameterizedTest
    @CsvSource({"PreCompact", "without-pre-compact"})
    void aSavedStateIsShownWhenCompactionStartsANewContext(String preCompact) throws IOException {
        String session = newSession("codex");
        Map<String, Object> meta = SessionServerSupport.meta("windows", "codex", session, "none");
        Map<String, Object> core = Map.of("objective", "survive compaction", "completionCriteria", List.of("c"), "constraints", List.of(), "decisions", List.of(), "progress", List.of(), "blockers", List.of(),
                "nextActions", List.of());
        try (McpSyncClient client = DomainServerSupport.client(server.port, "codex")) {
            String request = UUID.randomUUID().toString();
            JsonNode stale = DomainServerSupport.call(client, "checkpoint_context", DomainServerSupport.arguments("operationKey", request,"expectedStateToken", "a".repeat(48), "status", "ACTIVE", "core", core, "evidenceRefs", List.of()), meta);
            String actual = stale.path("error").path("details").path("actualStateToken").asString();
            assertTrue(DomainServerSupport.call(client, "checkpoint_context", DomainServerSupport.arguments("operationKey", request,"expectedStateToken", actual, "status", "ACTIVE", "core", core, "evidenceRefs", List.of()), meta).path("ok").booleanValue());
        }
        assertFalse(contextOf("codex", ok(hook("windows", "codex", "UserPromptSubmit", session))).contains("survive compaction"));
        assertFalse(contextOf("codex", ok(hook("windows", "codex", "PostToolUse", session))).contains("survive compaction"));
        if (preCompact.equals("PreCompact")) {
            JsonNode before = ok(DomainServerSupport.hookFrom("Stop", server.port, "windows", "codex", "PreCompact", null, session, body -> body.put("trigger", "auto")));
            assertFalse(contextOf("codex", before).contains("survive compaction"));
        }
        JsonNode compact = ok(hook("windows", "codex", "SessionStart", null, session, body -> body.put("source", "compact")));
        assertTrue(contextOf("codex", compact).contains("survive compaction"), compact.toString());
        JsonNode resumed = ok(hook("windows", "codex", "SessionStart", null, session, body -> body.put("source", "resume")));
        assertTrue(contextOf("codex", resumed).contains("survive compaction"), resumed.toString());
    }

    // ----------------------------------------------------------------------------------------------------------- edit gate

    private static ObjectNode object() {
        return DomainServerSupport.MAPPER.createObjectNode();
    }

    private record Edit(String host, String event, Consumer<ObjectNode> body) {
    }

    /** One edit of {@code file} in the form each host's tool has. */
    private static Edit edit(String host, String file) {
        return switch (host) {
            case "claude" -> new Edit(host, "PreToolUse", DomainServerSupport.tool("Write", object().put("file_path", file).put("content", "x")));
            case "codex" -> new Edit(host, "PreToolUse", DomainServerSupport.tool("apply_patch", object().put("command", "*** Begin Patch\n*** Update File: " + file + "\n*** End Patch")));
            default -> new Edit(host, "PreToolUse", body -> ((ObjectNode) body.path("toolCall")).put("name", "Write").set("args", object().put("TargetFile", file)));
        };
    }

    private static boolean denied(String host, JsonNode output) {
        return host.equals("agy") ? output.path("decision").asString().equals("deny") : output.path("hookSpecificOutput").path("permissionDecision").asString().equals("deny");
    }

    private static String reason(String host, JsonNode output) {
        return host.equals("agy") ? output.path("reason").asString() : output.path("hookSpecificOutput").path("permissionDecisionReason").asString();
    }

    private static JsonNode applyEdit(String os, String host, String session, String file) throws IOException {
        Edit edit = edit(host, file);
        return ok(hook(os, edit.host(), edit.event(), host.equals("agy") ? null : "continuity", session, edit.body()));
    }

    @ParameterizedTest
    @CsvSource({"windows,claude", "linux,claude", "windows,codex", "linux,codex", "windows,agy", "linux,agy"})
    void aProductFileWithoutAWorkflowIsRefusedAtTheFirstFileOnEveryOsAndHost(String os, String host) throws IOException {
        String session = newSession(host);
        String prefix = repository.resolve(host + "-" + UUID.randomUUID()).toString();
        JsonNode first = applyEdit(os, host, session, prefix + "-1.txt");
        assertTrue(denied(host, first), first.toString());
        assertTrue(reason(host, first).contains("[Sennard workflow gate]") && reason(host, first).contains("no workflow handles this request"), first.toString());
        assertTrue(reason(host, first).contains("-1.txt"), "the refusal names the file: " + first);
        assertTrue(reason(host, first).contains("start_workflow"), "the refusal shows how to start one: " + first);
        JsonNode second = applyEdit(os, host, session, prefix + "-2.txt");
        assertTrue(denied(host, second), "every file is refused, not only the first: " + second);
    }

    @ParameterizedTest
    @CsvSource({"claude", "codex", "agy"})
    void aFileInTheTemporaryDirectoryIsNotAProductFile(String host, @TempDir Path checkout) throws IOException {
        String session = newSession(host);
        // A checkout inside the temporary directory (JUnit's folder lies in it and is deleted after the test): the exemption has to be what lets these through, not the absence of version control.
        Files.createDirectory(checkout.resolve(".git"));
        for (int file = 0; file < 4; file += 1) {
            assertFalse(denied(host, applyEdit("windows", host, session, checkout.resolve("scratch-" + file + ".txt").toString())));
        }
    }

    @Test
    void aShellWriteIsAnEditOfTheFileItNamesAndIsRefusedWithNoWorkflowAtTheFirstFile() throws IOException {
        String session = newSession("claude");
        String prefix = repository.resolve("shell-" + UUID.randomUUID()).toString().replace('\\', '/');
        for (int file = 0; file < 2; file += 1) {
            String command = "echo x > " + prefix + "-" + file + ".txt";
            JsonNode refused = ok(hook("windows", "claude", "PreToolUse", "continuity", session, DomainServerSupport.tool("Bash", object().put("command", command))));
            assertTrue(denied("claude", refused), "file " + file + ": " + refused);
            assertTrue(reason("claude", refused).contains("-" + file + ".txt") && !reason("claude", refused).contains("path unknown"), "the redirect names the file: " + refused);
        }
    }

    /**
     * A shell write to a path spelled {@code spelled} is a write of the file that path reaches. With no workflow it is refused at the first file, naming it (and not as a write of an unknown
     * file); with a change workflow whose folder is the folder of the file, it is inside the workflow and runs; a file beside that folder is outside it and is refused.
     */
    private static void assertAShellWriteIsTheFileItReaches(String label, java.util.function.Function<Path, String> spell) throws Exception {
        String name = label + "-" + UUID.randomUUID();
        Path workspace = Files.createDirectories(repository.resolve(name));
        String bare = newSession("claude");
        JsonNode refused = shellWrite(bare, spell.apply(workspace.resolve("a.txt")));
        assertTrue(denied("claude", refused), "no workflow: " + refused);
        assertTrue(reason("claude", refused).contains("a.txt") && reason("claude", refused).contains("no workflow handles this request") && !reason("claude", refused).contains("path unknown"), refused.toString());
        String served = SessionServerSupport.uniqueSession();
        ChangeWorkflows.open(server, served, workspace, List.of("."));
        JsonNode inside = shellWrite(served, spell.apply(workspace.resolve("a.txt")));
        assertFalse(denied("claude", inside), "the file is in the folder of the workflow: " + inside);
        JsonNode beside = shellWrite(served, spell.apply(repository.resolve(name + "-beside.txt")));
        assertTrue(denied("claude", beside), "a file beside the workflow's folder is outside it: " + beside);
        assertTrue(reason("claude", beside).contains("change plan"), beside.toString());
    }

    private static JsonNode shellWrite(String session, String spelledPath) throws IOException {
        return ok(hook("windows", "claude", "PreToolUse", "continuity", session, DomainServerSupport.tool("Bash", object().put("command", "echo x > " + spelledPath))));
    }

    /** The path as Git Bash writes it: a Windows drive is {@code /d/...}. A path with no drive in it is already what the shell writes. */
    private static String msysSpelling(Path path) {
        String slashed = path.toString().replace('\\', '/');
        return slashed.matches("^[A-Za-z]:/.*") ? "/" + Character.toLowerCase(slashed.charAt(0)) + slashed.substring(2) : slashed;
    }

    @Test
    void aShellWriteToAnMsysDrivePathIsAWriteOfTheFileItNames() throws Exception {
        assertAShellWriteIsTheFileItReaches("msys", path -> msysSpelling(path));
    }

    @Test
    void aGitBashPathThatClimbsOutOfItsDriveAndBackInIsAWriteOfTheFileItReaches() throws Exception {
        // "/d/rest" written "/d/../d/rest": the shell lands on the same file; read as a path of the current drive it lands on a directory that is not there.
        assertAShellWriteIsTheFileItReaches("climb", path -> {
            String drive = msysSpelling(path);
            return drive.matches("^/[a-z]/.*") ? "/" + drive.charAt(1) + "/../" + drive.charAt(1) + drive.substring(2) : drive;
        });
    }

    @Test
    void aReadCommandIsNotAnEdit() throws IOException {
        String session = newSession("claude");
        for (int file = 0; file < 4; file += 1) {
            JsonNode output = ok(hook("windows", "claude", "PreToolUse", "continuity", session, DomainServerSupport.tool("Bash", object().put("command", "cat a" + file + ".txt"))));
            assertFalse(denied("claude", output), output.toString());
        }
    }

    // --------------------------------------------------------------------------------------------------------- child agents

    @Test
    void aSubagentStartGetsItsScopeCue() throws IOException {
        String session = newSession("claude");
        JsonNode output = ok(DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStart", null, session, body -> body.put("hook_event_name", "SubagentStart").put("agent_id", "child-1").put("agent_type", "general-purpose")));
        assertTrue(contextOf("claude", output).contains("[Sennard subagent start check]"), output.toString());
    }

    // ------------------------------------------------------------------------------------------------------------ messages

    private static void send(String body, String targetHost, String targetSession) {
        String sender = SessionServerSupport.uniqueSession();
        try {
            ok(hook("windows", "codex", "SessionStart", sender));
        } catch (IOException failed) {
            throw new AssertionError(failed);
        }
        SessionMessageService messages = server.bean(SessionMessageService.class);
        String senderRef = refOf("codex", sender);
        var prepared = messages.prepare(senderRef, stored(targetHost), targetSession, body, null, OperationKeys.operation("prepare_session_message", senderRef));
        messages.send(senderRef, "codex", prepared.messageId(), OperationKeys.operation("send_session_message", senderRef));
    }

    private static String refOf(String host, String session) {
        return (String) SessionServerSupport.row(server, "SELECT session_ref FROM sennard.session_ref WHERE host = ? AND local_session_id = ?", stored(host), session).get("session_ref");
    }

    @ParameterizedTest
    @CsvSource({"windows,claude,PostToolUse", "linux,claude,PostToolUse", "windows,codex,PostToolUse", "linux,codex,PostToolUse", "windows,agy,PostInvocation", "linux,agy,PostInvocation"})
    void aWaitingMessageIsGivenAtTheHostsBoundaryWithItsReceipt(String os, String host, String event) throws IOException {
        String session = newSession(host);
        String body = "hello-" + UUID.randomUUID();
        send(body, host, session);
        if (host.equals("agy")) {
            assertFalse(contextOf(host, ok(hook(os, host, event, session))).contains(BEGIN), "unconfirmed hook cannot claim a peer message");
            DomainServerSupport.observeAgyRoot(server, session);
        }
        JsonNode output = ok(hook(os, host, event, session));
        String context = contextOf(host, output);
        assertTrue(context.contains(BEGIN), output.toString());
        assertTrue(context.contains(body), output.toString());
        int start = context.indexOf('{', context.indexOf(BEGIN));
        JsonNode content = DomainServerSupport.MAPPER.readTree(context.substring(start, context.indexOf("\n", start)));
        assertEquals("plain-json", content.path("messageEncoding").asString());
        assertEquals(session, content.path("recipient").path("sessionId").asString());
        String receipt = content.path("receipt").path("sourceReceiptId").asString();
        assertTrue(receipt.startsWith("source-"), output.toString());
        assertEquals(1, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.input_source_receipts WHERE receipt_id = ?", receipt));
        // The session has no summary on the board, so every hook at the host's boundary also carries the summary question (RecapNudgeHook): the second answer is not empty,
        // but it must not carry the claimed message again.
        String second = contextOf(host, ok(hook(os, host, event, session)));
        assertFalse(second.contains(BEGIN) || second.contains(body), "a claimed message is not given twice while its claim lasts: " + second);
    }

    /**
     * Codex's model-request hooks have no place for text, so a domain that has context to give (here the summary question, due for a session with no summary) must not ask for one:
     * the host showed "Hook failed: SERVER_REFUSED 500" on every PostModelRequest. The answer is the empty object, and the question is still asked at the boundaries that take it.
     */
    @ParameterizedTest
    @CsvSource({"windows,PreModelRequest", "linux,PreModelRequest", "windows,PostModelRequest", "linux,PostModelRequest"})
    void aCodexModelRequestIsAnsweredEmptyWhileTheSummaryIsDue(String os, String event) throws IOException {
        String session = newSession("codex");

        JsonNode answer = ok(DomainServerSupport.hookFrom("UserPromptSubmit", server.port, os, "codex", event, null, session, body -> {
        }));

        assertTrue(answer.isEmpty(), answer.toString());
        String atTool = contextOf("codex", ok(hook(os, "codex", "PostToolUse", session)));
        assertTrue(atTool.contains("update_session_status"), "the summary question is still asked at a boundary that takes it: " + atTool);
    }

    @ParameterizedTest
    @CsvSource({"claude,additionalContext", "codex,reason", "agy,reason"})
    void aMessageAtATurnEndContinuesTheTurn(String host, String field) throws IOException {
        String session = newSession(host);
        if (host.equals("agy")) {
            DomainServerSupport.observeAgyRoot(server, session);
        }
        send("at-turn-end", host, session);
        JsonNode output = ok(hook("windows", host, "Stop", session));
        String text = field.equals("additionalContext") ? output.path("hookSpecificOutput").path("additionalContext").asString() : output.path("reason").asString();
        assertTrue(text.contains(BEGIN), output.toString());
        if (!host.equals("claude")) {
            assertEquals(host.equals("codex") ? "block" : "continue", output.path("decision").asString(), output.toString());
        }
    }

    @Test
    void aTurnAlreadyContinuedIsNotContinuedAgain() throws IOException {
        String session = newSession("codex");
        send("not-now", "codex", session);
        JsonNode output = ok(hook("windows", "codex", "Stop", null, session, body -> body.put("stop_hook_active", true)));
        assertFalse(output.toString().contains(BEGIN), output.toString());
        assertTrue(ok(hook("windows", "codex", "PostToolUse", session)).toString().contains(BEGIN), "it waits for the next boundary");
    }

    @Test
    void aCanceledStopOfAgyIsNeverContinued() throws IOException {
        String session = newSession("agy");
        send("canceled", "agy", session);
        JsonNode output = ok(hook("windows", "agy", "Stop", null, session, body -> body.put("terminationReason", "USER_CANCELED")));
        assertFalse(output.toString().contains(BEGIN), output.toString());
    }

    @Test
    void aChildAgentDoesNotTakeTheSessionsMessages() throws IOException {
        String session = newSession("claude");
        send("for-root", "claude", session);
        JsonNode output = ok(hook("windows", "claude", "PostToolUse", null, session, body -> body.put("agent_id", "child-2").put("agent_type", "general-purpose")));
        assertFalse(output.toString().contains(BEGIN), output.toString());
        assertTrue(ok(hook("windows", "claude", "PostToolUse", session)).toString().contains(BEGIN), "the root still gets it");
    }

    @Test
    void aFreshUserTurnTakesTheNextToolBoundaryOffOnce() throws IOException {
        String session = newSession("claude");
        ok(hook("windows", "claude", "UserPromptSubmit", session));
        send("after-input", "claude", session);
        assertFalse(ok(hook("windows", "claude", "PostToolUse", session)).toString().contains(BEGIN), "the input's own tool call is skipped once");
        assertTrue(ok(hook("windows", "claude", "PostToolUse", session)).toString().contains(BEGIN));
    }

    @Test
    void aNativeWaitIsToldToStayBounded() throws IOException {
        String session = newSession("codex");
        ObjectNode input = object();
        input.put("timeoutMs", 1000).putArray("targets").addObject().put("threadId", "thread-1");
        JsonNode output = ok(hook("windows", "codex", "PreToolUse", "peer-wait", session, DomainServerSupport.tool("mcp__codex_app__wait_threads", input)));
        assertTrue(output.path("hookSpecificOutput").path("additionalContext").asString().contains("bounded"), output.toString());
    }

    // --------------------------------------------------------------------------------------------------------------- wake

    /** A held wake blocks its caller for as long as the server holds it; the common pool has about as many threads as the machine has cores. */
    private static final Executor WAKE_CALLERS = Executors.newVirtualThreadPerTaskExecutor();

    private static CompletableFuture<RawHttp.Response> holdWake(String session) {
        return holdWake(session, "SessionStart");
    }

    private static CompletableFuture<RawHttp.Response> holdWake(String session, String event) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return hook("windows", "claude", event, "wake", session, body -> {
                });
            } catch (IOException failed) {
                throw new AssertionError(failed);
            }
        }, WAKE_CALLERS);
    }

    @Test
    void aHeldWakeIsAnsweredWithTheMessageWhenOneIsCommitted() throws Exception {
        String session = newSession("claude");
        CompletableFuture<RawHttp.Response> held = holdWake(session);
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the wake hook to be held");
        assertFalse(held.isDone());
        send("wake-up", "claude", session);
        RawHttp.Response response = held.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        String text = DomainServerSupport.text(response);
        assertTrue(text.contains(BEGIN) && text.contains("wake-up"), text);
    }

    @Test
    void aMessageAlreadyWaitingWakesWithoutHolding() throws Exception {
        String session = newSession("claude");
        send("was-waiting", "claude", session);
        RawHttp.Response response = holdWake(session).get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
    }

    @Test
    void aLaterWakeHookOfTheSameSessionReplacesTheHeldOne() throws Exception {
        String session = newSession("claude");
        CompletableFuture<RawHttp.Response> first = holdWake(session);
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the first wake hook to be held");
        CompletableFuture<RawHttp.Response> second = holdWake(session);
        RawHttp.Response replaced = first.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(409, replaced.status(), DomainServerSupport.text(replaced));
        send("to-second", "claude", session);
        assertEquals(200, second.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS).status());
    }

    /** A wake hook ends when it wakes, so the hook of the turn's end is what keeps an idle session wakeable after its first wake. */
    @Test
    void theWakeHookOfATurnsEndIsHeldAndAnsweredLikeTheOneOfTheStart() throws Exception {
        String session = newSession("claude");
        CompletableFuture<RawHttp.Response> held = holdWake(session, "Stop");
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the turn-end wake hook to be held");
        send("after-a-turn", "claude", session);
        RawHttp.Response response = held.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        assertTrue(DomainServerSupport.text(response).contains("after-a-turn"));
    }

    /** A working session takes its messages at the ordinary boundaries, so the hook held since it went idle ends without waking. */
    @Test
    void aPromptEndsTheHeldWakeWithoutWakingAndTheNextTurnEndHoldsAnother() throws Exception {
        String session = newSession("claude");
        CompletableFuture<RawHttp.Response> idle = holdWake(session);
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the wake hook to be held");
        ok(hook("windows", "claude", "UserPromptSubmit", session));
        RawHttp.Response released = idle.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(409, released.status(), DomainServerSupport.text(released));
        assertEquals("HOOK_WAKE_SESSION_NOT_IDLE", SessionServerSupport.json(released).path("reason").stringValue());
        assertFalse(waiters.isWaiting("claude-code", session));

        CompletableFuture<RawHttp.Response> afterTheTurn = holdWake(session, "Stop");
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the next wake hook to be held");
        send("to-the-idle-session", "claude", session);
        assertEquals(200, afterTheTurn.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS).status());
    }

    /** A hook of a session that left would otherwise stay held, and take a message nobody reads. */
    @Test
    void theEndOfTheSessionEndsItsHeldWake() throws Exception {
        String session = newSession("claude");
        CompletableFuture<RawHttp.Response> held = holdWake(session);
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "the wake hook to be held");
        hook("windows", "claude", "SessionEnd", session);
        RawHttp.Response released = held.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertEquals(409, released.status(), DomainServerSupport.text(released));
        assertFalse(waiters.isWaiting("claude-code", session));
    }

    @Test
    void aHeldWakeHoldsNoDatabaseConnection() throws Exception {
        List<CompletableFuture<RawHttp.Response>> held = new java.util.ArrayList<>();
        WakeWaiters waiters = server.bean(WakeWaiters.class);
        List<String> sessions = new java.util.ArrayList<>();
        // The embedded store's pool has 4 connections.
        for (int index = 0; index < 12; index += 1) {
            String session = newSession("claude");
            sessions.add(session);
            held.add(holdWake(session));
        }
        for (String session : sessions) {
            SessionServerSupport.await(() -> waiters.isWaiting("claude-code", session) ? Optional.of(true) : Optional.empty(), "every wake hook to be held");
        }
        // More wakes are held than the pool has connections, and an ordinary request still gets through.
        assertTrue(ok(hook("windows", "claude", "UserPromptSubmit", SessionServerSupport.uniqueSession())).path("hookSpecificOutput").has("additionalContext"));
        for (int index = 0; index < sessions.size(); index += 1) {
            send("release-" + index, "claude", sessions.get(index));
            assertEquals(200, held.get(index).get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS).status());
        }
    }
}
