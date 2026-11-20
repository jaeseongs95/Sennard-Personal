// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.server.messaging.WakeWaiters;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The operationKey of the write tools that are not the workflow's (AGS3-D-0267), on the assembled server (real PostgreSQL, a real MCP client). Cases planned before the code:
 * <ol>
 * <li>every tool of the table requires the key in its published schema, with the common pattern;</li>
 * <li>a call without a key, or with a key of another shape, is refused as INVALID_INPUT and leaves no receipt;</li>
 * <li>the same key with the same content is answered with the first answer, and the work is not done twice (one receipt, and what a repeat would change is unchanged);</li>
 * <li>the same key with other content is REQUEST_CONFLICT with the reason OPERATION_CONFLICT, and the receipt of the first stays;</li>
 * <li>a {@code start_workflow} that comes again with its key (the run has since left its planning phase) is answered with the first answer in full, the change journal ({@code phase},
 * {@code planRevision}, {@code unreadable}) and the notice after it as the first call showed them;</li>
 * <li>a {@code record_stage_result} that comes again with its key (a file outside the plan has changed since) is answered with the first answer and its notes, and the journal records no
 * scan for it;</li>
 * <li>a {@code recall_session_message} that comes again with its key (the session of the target has lost its host session id since) shows the target as the first answer did.</li>
 * </ol>
 * The tools of the table are those whose use case is one service method; {@code record_turn_recap} (it needs a prompt the hooks archived) is in {@code PromptToolsServerTest}.
 */
@Tag("business-db")
class OperationKeyToolsServerTest {
    private static ToolServerSupport sennard;
    /** The workspaces of the workflow runs: the edit gate does not count the OS temporary directory, so they live in this checkout's build directory (as AttachmentEvidenceServerTest's). */
    private static Path workspaces;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("skills/registry.json"))) {
            repository = repository.getParent();
        }
        if (repository == null) {
            throw new IllegalStateException("skills/registry.json not found above " + Path.of("").toAbsolutePath());
        }
        workspaces = Files.createDirectories(Path.of("build", "operation-key-it", UUID.randomUUID().toString()).toAbsolutePath());
        // start_workflow needs the content root and the skills folder.
        sennard = ToolServerSupport.startWithSourcesAndSettings(List.of(WakeCounter.class), "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    static Stream<String> tools() {
        return Stream.of("update_session_status", "prepare_session_message", "send_session_message", "acknowledge_session_messages", "recall_session_message", "suppress_context_restore",
                "mark_turn_unchanged", "execute_state_cleanup", "bind_session_role", "checkpoint_global_context", "checkpoint_role_context");
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", "1.0.0");
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> keyed(Map<String, Object> arguments, String key) {
        Map<String, Object> keyed = new LinkedHashMap<>(arguments);
        keyed.put("operationKey", key);
        return keyed;
    }

    private static long receipts(String tool, String key) {
        JdbcClient jdbc = sennard.server.context.getBean("storeJdbcClient", JdbcClient.class);
        return sennard.inTransaction(() -> jdbc.sql("SELECT COUNT(*) FROM sennard.operation_receipt WHERE tool = ? AND operation_key = ?").params(tool, key).query(Long.class).single());
    }

    private static long receipts(String tool) {
        JdbcClient jdbc = sennard.server.context.getBean("storeJdbcClient", JdbcClient.class);
        return sennard.inTransaction(() -> jdbc.sql("SELECT COUNT(*) FROM sennard.operation_receipt WHERE tool = ?").params(tool).query(Long.class).single());
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(false), answer.toString());
        return answer.path("data");
    }

    private static void assertKeyConflict(JsonNode refused) {
        assertFalse(refused.path("ok").asBoolean(true), refused.toString());
        assertEquals("REQUEST_CONFLICT", refused.path("error").path("code").asString(), refused.toString());
        assertEquals("services.operation.keyConflict", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals("OPERATION_CONFLICT", refused.path("error").path("details").path("reason").asString(), "every write tool shows the conflict of its key with the reason: " + refused);
        assertEquals("operationKey", refused.path("error").path("details").path("field").asString(), refused.toString());
    }

    @ParameterizedTest
    @MethodSource("tools")
    void aToolPublishesItsOperationKeyAsRequiredWithTheCommonPattern(String tool) {
        JsonNode schema = ToolServerSupport.MAPPER.valueToTree(sennard.listTools().stream().filter(item -> item.name().equals(tool)).findFirst().orElseThrow().inputSchema());
        List<String> required = new java.util.ArrayList<>();
        schema.path("required").forEach(item -> required.add(item.asString()));
        assertTrue(required.contains("operationKey"), tool + " " + schema);
    }

    @ParameterizedTest
    @MethodSource("tools")
    void aCallWithoutAValidOperationKeyIsRefusedAndLeavesNoReceipt(String tool) throws java.io.IOException {
        String session = sennard.session();
        long before = receipts(tool);
        Map<String, Object> content = arguments("summary", "no key", "messageId", "message-without-key-1", "messageIds", List.of("message-without-key-1"), "body", "b", "targetHost", "codex",
                "targetSessionId", session, "expectedEpoch", 1, "planToken", "p".repeat(90), "role", null, "expectedStateToken", null);
        content.keySet().retainAll(contentKeysOf(tool));
        JsonNode missing = sennard.callAsSent(tool, content, session);
        assertEquals("INVALID_INPUT", missing.path("error").path("code").asString(), missing.toString());
        for (Object malformed : java.util.Arrays.asList("", "short-key", "a".repeat(129), "has space in the operation key", "ünïcode-operation-key-1")) {
            Map<String, Object> bad = new LinkedHashMap<>(content);
            bad.put("operationKey", malformed);
            JsonNode refused = sennard.callAsSent(tool, bad, session);
            assertEquals("INVALID_INPUT", refused.path("error").path("code").asString(), malformed + " " + refused);
        }
        assertEquals(before, receipts(tool), "a refusal leaves no receipt");
    }

    /** The members of each tool's contract that the probe above fills in. */
    private static java.util.Set<String> contentKeysOf(String tool) {
        return switch (tool) {
            case "update_session_status" -> java.util.Set.of("schemaVersion", "summary");
            case "prepare_session_message" -> java.util.Set.of("schemaVersion", "targetHost", "targetSessionId", "body");
            case "send_session_message", "recall_session_message" -> java.util.Set.of("schemaVersion", "messageId");
            case "suppress_context_restore" -> java.util.Set.of("schemaVersion", "expectedEpoch");
            case "mark_turn_unchanged" -> java.util.Set.of("schemaVersion");
            case "execute_state_cleanup" -> java.util.Set.of("schemaVersion", "planToken");
            case "bind_session_role" -> java.util.Set.of("schemaVersion", "role");
            case "checkpoint_global_context", "checkpoint_role_context" -> java.util.Set.of("schemaVersion", "expectedStateToken", "body");
            default -> java.util.Set.of("schemaVersion", "messageIds");
        };
    }

    /** Counts what the sends ring, to tell a send that rang its wake from a request answered with the first send's receipt (AGS3-D-0267). */
    @Configuration(proxyBeanMethods = false)
    static class WakeCounter {
        private static final Map<String, AtomicInteger> RUNG = new ConcurrentHashMap<>();

        static int rungFor(String hostSessionId) {
            return RUNG.getOrDefault(hostSessionId, new AtomicInteger()).get();
        }

        // @Bean: the listener of the test, a bean of the server's own context.
        @Bean
        WakeListener wakeListener() {
            return new WakeListener();
        }

        static class WakeListener {
            @EventListener
            void on(WakeWaiters.MessageQueued queued) {
                RUNG.computeIfAbsent(queued.hostSessionId(), id -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    @Test
    void aSendThatComesAgainWithItsKeyRingsTheWakeOnlyOnce() throws java.io.IOException {
        String sender = sennard.session();
        String target = sennard.session();
        String messageId = data(sennard.call("prepare_session_message", arguments("targetHost", "codex", "targetSessionId", target, "body", "wake once"), sender)).path("messageId").asString();
        Map<String, Object> send = keyed(arguments("messageId", messageId), OperationKeys.fresh());
        data(sennard.call("send_session_message", send, sender));
        assertEquals(1, WakeCounter.rungFor(target), "the send rang the wake");
        data(sennard.call("send_session_message", send, sender));
        assertEquals(1, WakeCounter.rungFor(target), "the request answered with the first send rang nothing");
    }

    private static long receiptsOfScope(String tool, String key) {
        JdbcClient jdbc = sennard.server.context.getBean("storeJdbcClient", JdbcClient.class);
        return sennard.inTransaction(() -> jdbc.sql("SELECT COUNT(DISTINCT scope) FROM sennard.operation_receipt WHERE tool = ? AND operation_key = ?").params(tool, key).query(Long.class).single());
    }

    private static String summaryOf(JsonNode listing, String session) {
        for (JsonNode listed : listing.path("sessions")) {
            if (session.equals(listed.path("sessionId").asString())) {
                return listed.path("summary").asString();
            }
        }
        throw new AssertionError("session not listed: " + session + " " + listing);
    }

    /**
     * The key is the caller's own (AGS3-D-0267, scope): two sessions that send the same tool, key and content each get their own effect and their own receipt, where one answer of the first
     * used to be given to both and the second session's effect never happened.
     */
    @Test
    void twoSessionsThatSendTheSameKeyAndContentEachHaveTheirOwnEffectAndReceipt() throws java.io.IOException {
        String a = sennard.session();
        String b = sennard.session();

        String statusKey = OperationKeys.fresh();
        Map<String, Object> status = keyed(arguments("summary", "the same line of two sessions"), statusKey);
        data(sennard.call("update_session_status", status, a));
        data(sennard.call("update_session_status", status, b));
        assertEquals(2, receipts("update_session_status", statusKey));
        assertEquals(2, receiptsOfScope("update_session_status", statusKey), "one receipt for each session");
        JsonNode listing = data(sennard.call("list_session_status", arguments(), a));
        assertEquals("the same line of two sessions", summaryOf(listing, a));
        assertEquals("the same line of two sessions", summaryOf(listing, b), "the board row of the second session was written, not answered from the first session's receipt");

        String prepareKey = OperationKeys.fresh();
        String target = sennard.session();
        Map<String, Object> prepare = keyed(arguments("targetHost", "codex", "targetSessionId", target, "body", "the same draft"), prepareKey);
        String draftOfA = data(sennard.call("prepare_session_message", prepare, a)).path("messageId").asString();
        String draftOfB = data(sennard.call("prepare_session_message", prepare, b)).path("messageId").asString();
        assertNotEquals(draftOfA, draftOfB, "each session has its own draft");
        assertEquals(draftOfA, data(sennard.call("prepare_session_message", prepare, a)).path("messageId").asString(), "and each is answered with its own first answer");
        assertEquals(draftOfB, data(sennard.call("prepare_session_message", prepare, b)).path("messageId").asString());
        assertEquals(2, receiptsOfScope("prepare_session_message", prepareKey));

        // A send of the key another session used for its own message: another content, and still not a conflict, because the key is the sender's own. The message of B is B's
        // (a message of A's that B named would be refused as not B's, not answered with A's receipt).
        String sendKey = OperationKeys.fresh();
        JsonNode sentByA = data(sennard.call("send_session_message", keyed(arguments("messageId", draftOfA), sendKey), a));
        JsonNode sentByB = data(sennard.call("send_session_message", keyed(arguments("messageId", draftOfB), sendKey), b));
        assertEquals(draftOfA, sentByA.path("messageId").asString());
        assertEquals(draftOfB, sentByB.path("messageId").asString(), "B's own message, not the messageId of A's receipt");
        assertEquals(2, receiptsOfScope("send_session_message", sendKey));

        String markKey = OperationKeys.fresh();
        data(sennard.call("mark_turn_unchanged", keyed(arguments(), markKey), a));
        data(sennard.call("mark_turn_unchanged", keyed(arguments(), markKey), b));
        assertEquals(2, receiptsOfScope("mark_turn_unchanged", markKey));

        String bindKey = OperationKeys.fresh();
        Map<String, Object> unbind = keyed(arguments("role", null), bindKey);
        String proposalOfA = data(sennard.call("bind_session_role", unbind, a)).path("proposalId").asString();
        String proposalOfB = data(sennard.call("bind_session_role", unbind, b)).path("proposalId").asString();
        assertNotEquals(proposalOfA, proposalOfB, "each session has its own pending proposal");
        assertEquals(2, receiptsOfScope("bind_session_role", bindKey));
    }

    @Test
    void aStatusThatComesAgainWithItsKeyIsAnsweredWithTheFirstOneAndAnotherContentIsAConflict() throws java.io.IOException {
        String session = sennard.session();
        String key = OperationKeys.fresh();
        JsonNode first = data(sennard.call("update_session_status", keyed(arguments("summary", "first line", "role", "the first role"), key), session));
        JsonNode again = data(sennard.call("update_session_status", keyed(arguments("summary", "first line", "role", "the first role"), key), session));
        assertEquals(first, again, "the answer is the first one, with the time of the first write");
        assertEquals(1, receipts("update_session_status", key));
        assertKeyConflict(sennard.call("update_session_status", keyed(arguments("summary", "another line"), key), session));
        assertEquals(1, receipts("update_session_status", key));
    }

    @Test
    void aPreparationThatComesAgainWithItsKeyMakesNoSecondDraft() throws java.io.IOException {
        String sender = sennard.session();
        String target = sennard.session();
        String key = OperationKeys.fresh();
        Map<String, Object> prepare = keyed(arguments("targetHost", "codex", "targetSessionId", target, "body", "hello"), key);
        JsonNode first = data(sennard.call("prepare_session_message", prepare, sender));
        JsonNode again = data(sennard.call("prepare_session_message", prepare, sender));
        assertEquals(first.path("messageId").asString(), again.path("messageId").asString(), "the key, not a new draft, answered");
        assertEquals(1, receipts("prepare_session_message", key));
        JsonNode otherKey = data(sennard.call("prepare_session_message", keyed(prepare, OperationKeys.fresh()), sender));
        assertNotEquals(first.path("messageId").asString(), otherKey.path("messageId").asString(), "the same content under a new key is a new draft");
        assertKeyConflict(sennard.call("prepare_session_message", keyed(arguments("targetHost", "codex", "targetSessionId", target, "body", "another body"), key), sender));
    }

    @Test
    void aSendAndARecallThatComeAgainWithTheirKeysAreAnsweredWithTheFirstAnswer() throws java.io.IOException {
        String sender = sennard.session();
        String target = sennard.session();
        String messageId = data(sennard.call("prepare_session_message", arguments("targetHost", "codex", "targetSessionId", target, "body", "send and recall"), sender)).path("messageId").asString();

        String sendKey = OperationKeys.fresh();
        Map<String, Object> send = keyed(arguments("messageId", messageId), sendKey);
        JsonNode sent = data(sennard.call("send_session_message", send, sender));
        JsonNode sentAgain = data(sennard.call("send_session_message", send, sender));
        assertEquals(sent, sentAgain, "the first answer again, and not the duplicate answer of a send under a new key");
        assertFalse(sentAgain.has("duplicate"), sentAgain.toString());
        assertEquals(1, receipts("send_session_message", sendKey));
        JsonNode underNewKey = data(sennard.call("send_session_message", keyed(send, OperationKeys.fresh()), sender));
        assertTrue(underNewKey.path("duplicate").asBoolean(false), "a new key meets the message ID that was sent: " + underNewKey);
        String other = data(sennard.call("prepare_session_message", arguments("targetHost", "codex", "targetSessionId", target, "body", "another"), sender)).path("messageId").asString();
        assertKeyConflict(sennard.call("send_session_message", keyed(arguments("messageId", other), sendKey), sender));

        String recallKey = OperationKeys.fresh();
        Map<String, Object> recall = keyed(arguments("messageId", messageId), recallKey);
        JsonNode recalled = data(sennard.call("recall_session_message", recall, sender));
        JsonNode recalledAgain = data(sennard.call("recall_session_message", recall, sender));
        assertEquals(recalled, recalledAgain);
        assertEquals(1, receipts("recall_session_message", recallKey));
        assertKeyConflict(sennard.call("recall_session_message", keyed(arguments("messageId", other), recallKey), sender));
    }

    @Test
    void aSuppressionAndATurnMarkThatComeAgainWithTheirKeysAreAnsweredWithTheFirstAnswer() throws java.io.IOException {
        String session = sennard.session();
        String suppressKey = OperationKeys.fresh();
        Map<String, Object> suppress = keyed(arguments("expectedEpoch", 1), suppressKey);
        JsonNode suppressed = data(sennard.call("suppress_context_restore", suppress, session));
        assertEquals(suppressed, data(sennard.call("suppress_context_restore", suppress, session)));
        assertEquals(1, receipts("suppress_context_restore", suppressKey));
        assertKeyConflict(sennard.call("suppress_context_restore", keyed(arguments("expectedEpoch", 2), suppressKey), session));

        // mark_turn_unchanged has no content besides its key, so a key can only be sent again with the same content: the first mark is answered, with the time of the first.
        String markKey = OperationKeys.fresh();
        Map<String, Object> mark = keyed(arguments(), markKey);
        JsonNode marked = data(sennard.call("mark_turn_unchanged", mark, session));
        assertEquals(marked, data(sennard.call("mark_turn_unchanged", mark, session)));
        assertEquals(1, receipts("mark_turn_unchanged", markKey));
    }

    @Test
    void aRoleBindingAndAGlobalChangeThatComeAgainWithTheirKeysMakeNoSecondProposal() throws java.io.IOException {
        String session = sennard.session();
        String bindKey = OperationKeys.fresh();
        Map<String, Object> unbind = keyed(arguments("role", null), bindKey);
        JsonNode first = data(sennard.call("bind_session_role", unbind, session));
        assertEquals(first, data(sennard.call("bind_session_role", unbind, session)), "the same proposal, not a second pending one");
        assertEquals(1, receipts("bind_session_role", bindKey));
        JsonNode underNewKey = data(sennard.call("bind_session_role", keyed(unbind, OperationKeys.fresh()), session));
        assertNotEquals(first.path("proposalId").asString(), underNewKey.path("proposalId").asString());
        assertKeyConflict(sennard.call("bind_session_role", keyed(arguments("role", "another-role-name"), bindKey), session));

        String globalKey = OperationKeys.fresh();
        Map<String, Object> change = keyed(arguments("expectedStateToken", null, "body", "a keyed global body"), globalKey);
        JsonNode proposed = data(sennard.call("checkpoint_global_context", change, session));
        assertEquals(proposed, data(sennard.call("checkpoint_global_context", change, session)));
        assertEquals(1, receipts("checkpoint_global_context", globalKey));
        assertKeyConflict(sennard.call("checkpoint_global_context", keyed(arguments("expectedStateToken", null, "body", "another global body"), globalKey), session));
    }

    @Test
    void aCleanupExecutionThatComesAgainWithItsKeyIsAnsweredWithTheFirstReceipt() throws java.io.IOException {
        String session = sennard.session();
        String token = data(sennard.call("prepare_state_cleanup", arguments(), session)).path("planToken").asString();
        String key = OperationKeys.fresh();
        Map<String, Object> execute = keyed(arguments("planToken", token), key);
        JsonNode done = data(sennard.call("execute_state_cleanup", execute, session));
        assertEquals(done, data(sennard.call("execute_state_cleanup", execute, session)), "the first receipt again, where the used token under a new key is refused");
        assertEquals(1, receipts("execute_state_cleanup", key));
        JsonNode newKey = sennard.call("execute_state_cleanup", keyed(execute, OperationKeys.fresh()), session);
        assertFalse(newKey.path("ok").asBoolean(true), newKey.toString());
        String otherToken = data(sennard.call("prepare_state_cleanup", arguments(), session)).path("planToken").asString();
        assertKeyConflict(sennard.call("execute_state_cleanup", keyed(arguments("planToken", otherToken), key), session));
    }

    /** open_session answers in the envelope like every tool (the session is its data); a caller that names no session by its host gets the session its first call minted when it sends its key again. */
    @Test
    void anOpenSessionThatComesAgainWithItsKeyIsAnsweredWithTheSessionTheFirstCallMinted() throws java.io.IOException {
        String key = OperationKeys.fresh();
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("operationKey", key);
        JsonNode first = sennard.callUnobserved("open_session", open);
        assertEquals("unavailable", first.path("data").path("trust").asString(), first.toString());
        JsonNode again = sennard.callUnobserved("open_session", open);
        assertEquals(first.path("data").path("sessionRef").asString(), again.path("data").path("sessionRef").asString(), "the key, not a new mint, answered");
        assertEquals(1, receipts("open_session", key));
        JsonNode underNewKey = sennard.callUnobserved("open_session", keyed(Map.of(), OperationKeys.fresh()));
        assertNotEquals(first.path("data").path("sessionRef").asString(), underNewKey.path("data").path("sessionRef").asString(), "a new key is a new session");
        assertEquals(Boolean.TRUE, sennard.callResultAsSent("open_session", Map.of(), sennard.session()).isError(), "a call without a key is refused");
        assertEquals(Boolean.TRUE, sennard.callResultAsSent("open_session", Map.of("operationKey", "short"), sennard.session()).isError(), "a key of another shape is refused");
    }

    /** The arguments of a workflow tool: its contract takes no schemaVersion member beside what it declares. */
    private static Map<String, Object> plain(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    /** A call that is a host call of its own, with its raw result (the notes after the envelope are texts of their own). */
    private static McpSchema.CallToolResult callAsNewHostCall(String tool, Map<String, Object> arguments, String session) {
        Map<String, Object> meta = new LinkedHashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        meta.put("callId", "exec-" + UUID.randomUUID());
        return sennard.callRaw(tool, arguments, meta);
    }

    /** Every text a result carries: the envelope's JSON first, then the notes. */
    private static List<String> texts(McpSchema.CallToolResult result) {
        return result.content().stream().map(item -> ((McpSchema.TextContent) item).text()).toList();
    }

    private static JsonNode envelopeOf(McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), texts(result).toString());
        return data(ToolServerSupport.MAPPER.readTree(texts(result).getFirst()));
    }

    /** The answers are the same: the result as JSON (the whole of it, with what is null or empty), and the notes after it as the texts they were. */
    private static void assertSameAnswer(McpSchema.CallToolResult first, McpSchema.CallToolResult again) {
        List<String> shown = texts(first);
        List<String> repeated = texts(again);
        assertTrue(shown.size() > 1, "the first call showed notes after its result: " + shown);
        assertEquals(ToolServerSupport.MAPPER.readTree(shown.getFirst()), ToolServerSupport.MAPPER.readTree(repeated.getFirst()));
        assertEquals(shown.subList(1, shown.size()), repeated.subList(1, repeated.size()), "the notes after the result");
    }

    private static Map<String, Object> startArguments(Path workspace, List<String> changePlan) throws java.io.IOException {
        Files.createDirectories(workspace.resolve("src"));
        Map<String, Object> arguments = plain("goal", "Keep the first answer of " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills",
                List.of(plain("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The first answer is kept."), "workspace", workspace.toString(),
                "writeTargets", List.of("src"));
        if (!changePlan.isEmpty()) {
            arguments.put("changePlan", changePlan);
        }
        return arguments;
    }

    /**
     * Case 5. The first start has no change plan, so the run is in its planning phase with the plan's first revision; the plan is then submitted and the run is executing. The start that
     * comes again is answered with the first start, which was in its planning phase, and not with the run as it is now.
     */
    @Test
    void aStartThatComesAgainWithItsKeyIsAnsweredWithTheFirstStartInFull() throws java.io.IOException {
        String session = sennard.session();
        Path workspace = Files.createDirectories(workspaces.resolve("start-" + UUID.randomUUID()));
        String key = OperationKeys.fresh();
        Map<String, Object> start = keyed(startArguments(workspace, List.of()), key);
        McpSchema.CallToolResult first = callAsNewHostCall("start_workflow", start, session);
        JsonNode started = envelopeOf(first);
        String runId = started.path("runId").asString();
        assertEquals("PLANNING", started.path("changeJournal").path("phase").asString(), started.toString());
        assertEquals(1, started.path("changeJournal").path("planRevision").asLong(), started.toString());
        assertTrue(started.path("changeJournal").path("unreadable").isEmpty(), started.toString());

        JsonNode submitted = data(sennard.callAsNewHostCall("submit_change_plan", plain("runId", runId, "expectedRevision", started.path("revision").asLong(), "files", List.of("src/a.ts")), session));
        assertEquals("EXECUTING", submitted.path("phase").asString(), submitted.toString());
        assertEquals(java.util.Optional.of(WorkflowPhase.EXECUTING), sennard.bean(ChangeJournalService.class).phase(runId), "the run has left its planning phase");

        McpSchema.CallToolResult again = callAsNewHostCall("start_workflow", start, session);
        assertSameAnswer(first, again);
        assertEquals("PLANNING", envelopeOf(again).path("changeJournal").path("phase").asString(), "the first start's phase, not the run's now");
        assertEquals(1, receipts("start_workflow", key));
    }

    /**
     * Case 6. The first call records the stage result, and the journal records a scan for its notes. A file outside the plan then changes. The call that comes again is answered with the
     * first answer and its notes (which did not see that file), and the journal records no scan for it: its version, which every recorded scan advances, stays where the first call left it.
     */
    @Test
    void aStageResultThatComesAgainWithItsKeyIsAnsweredWithTheFirstAnswerAndRecordsNoScan() throws java.io.IOException {
        String session = sennard.session();
        Path workspace = Files.createDirectories(workspaces.resolve("stage-" + UUID.randomUUID()));
        JsonNode started = envelopeOf(callAsNewHostCall("start_workflow", keyed(startArguments(workspace, List.of("src/a.ts")), OperationKeys.fresh()), session));
        String runId = started.path("runId").asString();
        assertEquals("EXECUTING", started.path("changeJournal").path("phase").asString(), started.toString());
        JsonNode status = data(sennard.callAsNewHostCall("get_workflow_status", plain("runId", runId), session));
        String stageId = status.path("plan").path("currentStageId").asString(status.path("plan").path("nextStageId").asString());
        Map<String, Object> result = plain("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", status.path("revision").asLong(), "stageId", stageId, "state", "PASSED",
                "output", plain("schemaVersion", "1.0.0", "kind", "output", "output", Map.of("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(ToolServerSupport.observedEvidence(sennard.uploadEvidence(session))), "findings", List.of(), "blockers", List.of(), "error", null);
        String key = OperationKeys.fresh();
        ChangeJournalService journal = sennard.bean(ChangeJournalService.class);
        long before = journal.rescanBasis(runId).version();

        McpSchema.CallToolResult first = callAsNewHostCall("record_stage_result", keyed(result, key), session);
        envelopeOf(first);
        long afterFirst = journal.rescanBasis(runId).version();
        assertTrue(afterFirst > before, "the first call recorded a scan for its notes");

        Files.writeString(workspace.resolve("src/unplanned.txt"), "changed after the first answer");
        McpSchema.CallToolResult again = callAsNewHostCall("record_stage_result", keyed(result, key), session);
        assertSameAnswer(first, again);
        assertEquals(afterFirst, journal.rescanBasis(runId).version(), "the call that came again recorded no scan");
        assertEquals(1, receipts("record_stage_result", key));
    }

    /**
     * Case 7. The recall is refused, because the target has taken the message, and the answer shows the target with its session id. The target's session then loses its host session id
     * (a session whose row was cleaned up); the recall that comes again shows the target as the first answer did.
     */
    @Test
    void aRecallThatComesAgainWithItsKeyShowsTheTargetAsTheFirstAnswerDid() throws java.io.IOException {
        String sender = sennard.session();
        String target = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(sennard.server.port, "windows", "claude", "SessionStart", null, target, body -> {
        }).status());
        String messageId = data(sennard.call("prepare_session_message", arguments("targetHost", "claude-code", "targetSessionId", target, "body", "taken before the recall"), sender)).path("messageId").asString();
        data(sennard.call("send_session_message", keyed(arguments("messageId", messageId), OperationKeys.fresh()), sender));
        assertEquals(200, DomainServerSupport.hook(sennard.server.port, "windows", "claude", "PostToolUse", null, target, body -> {
        }).status(), "the target takes the message");

        Map<String, Object> recall = keyed(arguments("messageId", messageId), OperationKeys.fresh());
        JsonNode first = data(sennard.call("recall_session_message", recall, sender));
        assertFalse(first.path("recalled").asBoolean(true), first.toString());
        assertEquals(target, first.path("status").path("target").path("sessionId").asString(), first.toString());

        SessionServerSupport.update(sennard.server, "UPDATE sennard.session_ref SET local_session_id = NULL WHERE host = ? AND local_session_id = ?", "claude-code", target);
        JsonNode again = data(sennard.call("recall_session_message", recall, sender));
        assertEquals(first, again, "the first answer, with the target as it was");
    }

    @Test
    void anAcknowledgementThatComesAgainWithItsKeyIsAnsweredWithTheFirstAnswer() throws java.io.IOException {
        String session = sennard.session();
        String key = OperationKeys.fresh();
        Map<String, Object> acknowledge = keyed(arguments("messageIds", List.of("message-not-held-0001")), key);
        JsonNode first = data(sennard.call("acknowledge_session_messages", acknowledge, session));
        assertEquals(0, first.path("acknowledged").asInt(), first.toString());
        assertEquals(first, data(sennard.call("acknowledge_session_messages", acknowledge, session)));
        assertEquals(1, receipts("acknowledge_session_messages", key));
        assertKeyConflict(sennard.call("acknowledge_session_messages", keyed(arguments("messageIds", List.of("message-not-held-0002")), key), session));
    }
}
