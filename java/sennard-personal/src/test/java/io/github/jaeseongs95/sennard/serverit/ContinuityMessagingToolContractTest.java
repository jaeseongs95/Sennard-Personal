// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The twelve tools through a real MCP client against the assembled server with real PostgreSQL: names, published inputs, and the error codes
 * and catalog keys of the contract. A call is made as a host with the recorded Codex {@code _meta}, with this test's session in it.
 */
@Tag("business-db")
class ContinuityMessagingToolContractTest {
    private static final List<String> TOOLS = List.of("checkpoint_context", "mark_turn_unchanged", "inspect_context", "load_context", "suppress_context_restore", "purge_direct_context", "prepare_state_cleanup",
            "execute_state_cleanup", "prepare_session_message", "send_session_message", "acknowledge_session_messages", "get_session_message_status", "recall_session_message");
    private static final String TOKEN = "a".repeat(48);
    private static final String MESSAGE_ID = "message-does-not-exist";
    private static RunningServer server;
    private static McpSyncClient client;
    private static String sender;
    private static String target;
    private static Map<String, Object> meta;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
        client = DomainServerSupport.client(server.port, "codex");
        sender = SessionServerSupport.uniqueSession();
        target = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "claude", "SessionStart", null, target, body -> {
        }).status());
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, sender, body -> {
        }).status());
        meta = SessionServerSupport.meta("windows", "codex", sender, "none");
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    private static JsonNode call(String tool, Map<String, Object> arguments) {
        return DomainServerSupport.call(client, tool, arguments, meta);
    }

    /** The call as it is sent: no operationKey is added to it ({@link #call} adds one to a write tool). */
    private static JsonNode callAsSent(String tool, Map<String, Object> arguments) {
        return DomainServerSupport.callAsSent(client, tool, arguments, meta);
    }

    private static void assertRefused(JsonNode result, String code, String key) {
        assertFalse(result.path("ok").booleanValue(), result.toString());
        assertEquals(code, result.path("error").path("code").asString(), result.toString());
        assertEquals(key, result.path("error").path("messageKey").asString(), result.toString());
        assertFalse(result.path("error").path("message").asString().isBlank(), result.toString());
    }

    private static JsonNode contract(String tool) throws IOException {
        try (InputStream in = ContinuityMessagingToolContractTest.class.getResourceAsStream("/contracts/" + tool.replace('_', '-') + "-request.v1.schema.json")) {
            assertNotNull(in, "the contract of " + tool);
            return DomainServerSupport.MAPPER.readTree(in);
        }
    }

    @Test
    void everyToolIsPublishedWithItsContract() throws IOException {
        Map<String, McpSchema.Tool> listed = client.listTools().tools().stream().collect(Collectors.toMap(McpSchema.Tool::name, tool -> tool));
        assertTrue(listed.keySet().containsAll(TOOLS), listed.keySet().toString());
        for (String name : TOOLS) {
            McpSchema.Tool tool = listed.get(name);
            JsonNode contract = contract(name);
            Set<String> required = new HashSet<>();
            contract.path("required").forEach(field -> required.add(field.asString()));
            JsonNode published = DomainServerSupport.MAPPER.valueToTree(tool.inputSchema());
            Set<String> publishedRequired = new HashSet<>();
            published.path("required").forEach(field -> publishedRequired.add(field.asString()));
            assertEquals(required, publishedRequired, name);
            Set<String> properties = new HashSet<>();
            contract.path("properties").propertyNames().forEach(properties::add);
            // The session tools add the optional reference every session tool takes; the cleanup tools act on the whole state, not on a session.
            if (!name.endsWith("_state_cleanup")) {
                properties.add("sessionRef");
            }
            Set<String> publishedProperties = new HashSet<>();
            published.path("properties").propertyNames().forEach(publishedProperties::add);
            assertEquals(properties, publishedProperties, name);
            assertFalse(published.path("additionalProperties").asBoolean(true), name);
            assertFalse(tool.description().startsWith("tools."), "the catalog's text is published: " + tool.description());
        }
    }

    static List<String> tools() {
        return TOOLS;
    }

    @ParameterizedTest
    @MethodSource("tools")
    void aRequestOutsideTheContractIsRefusedWithTheContractError(String tool) {
        JsonNode result = call(tool, Map.of());
        assertFalse(result.path("ok").booleanValue(), result.toString());
        assertEquals("INVALID_INPUT", result.path("error").path("code").asString(), result.toString());
        if (tool.equals("send_session_message")) {
            assertEquals("server.sessionMessage.sendInvalid", result.path("error").path("messageKey").asString(), result.toString());
            return;
        }
        assertEquals("workflow.schema.contractMismatch", result.path("error").path("messageKey").asString(), result.toString());
        String camel = java.util.regex.Pattern.compile("_([a-z])").matcher(tool).replaceAll(match -> match.group(1).toUpperCase(java.util.Locale.ROOT));
        assertEquals(camel + "Request", result.path("error").path("params").path("name").asString(), result.toString());
    }

    @Test
    void anUndeclaredArgumentIsRefusedBeforeTheContract() {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("inspect_context").arguments(Map.of("schemaVersion", "1.0.0", "bogus", 1)).meta(meta).build());
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(((McpSchema.TextContent) result.content().getFirst()).text().contains("bogus"), result.toString());
    }

    private static Map<String, Object> core(String objective) {
        return Map.of("objective", objective, "completionCriteria", List.of("c"), "constraints", List.of(), "decisions", List.of(), "progress", List.of(), "blockers", List.of(), "nextActions", List.of());
    }

    @Test
    void aWriteToolWithoutAnOperationKeyIsRefusedAndTheServerMakesNone() {
        for (String tool : List.of("checkpoint_context", "purge_direct_context")) {
            Map<String, Object> arguments = "checkpoint_context".equals(tool)
                    ? DomainServerSupport.arguments("expectedStateToken", TOKEN, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of())
                    : DomainServerSupport.arguments("expectedStateToken", TOKEN);
            assertRefused(callAsSent(tool, arguments), "INVALID_INPUT", "workflow.schema.contractMismatch");
            Map<String, Object> oldName = new java.util.LinkedHashMap<>(arguments);
            oldName.put("requestId", SessionServerSupport.uniqueSession());
            // The old name is no alias of the key: the entrance refuses an argument the tool's schema does not declare before the tool reads its arguments, in the envelope it has.
            JsonNode refused = callAsSent(tool, oldName);
            assertFalse(refused.path("ok").booleanValue(), refused.toString());
            assertEquals("INVALID_INPUT", refused.path("error").path("code").asString(), refused.toString());
            assertEquals("UNDECLARED_ARGUMENT", refused.path("error").path("details").path("reasonCode").asString(), refused.toString());
            assertTrue(refused.path("error").path("message").asString().contains("requestId"), refused.toString());
        }
    }

    @Test
    void anOperationKeyOutsideThePatternIsRefused() {
        for (String key : List.of("short", "a".repeat(129), "has space in the key 123", "ünïcode-operation-key-1")) {
            assertRefused(call("purge_direct_context", DomainServerSupport.arguments("operationKey", key, "expectedStateToken", TOKEN)), "INVALID_INPUT", "workflow.schema.contractMismatch");
            assertRefused(call("checkpoint_context", DomainServerSupport.arguments("operationKey", key, "expectedStateToken", TOKEN, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of())),
                    "INVALID_INPUT", "workflow.schema.contractMismatch");
        }
    }

    @Test
    void aSavedStateIsReadBackAndAStaleTokenIsRefused() {
        String operationKey = SessionServerSupport.uniqueSession();
        JsonNode stale = call("checkpoint_context", DomainServerSupport.arguments("operationKey", operationKey,"expectedStateToken", TOKEN, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of()));
        assertRefused(stale, "STALE_REVISION", "continuity.checkpoint.stateChanged");
        String actual = stale.path("error").path("details").path("actualStateToken").asString();

        JsonNode saved = call("checkpoint_context", DomainServerSupport.arguments("operationKey", operationKey,"expectedStateToken", actual, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of()));
        assertTrue(saved.path("ok").booleanValue(), saved.toString());
        String token = saved.path("data").path("stateToken").asString();
        assertFalse(token.isBlank(), saved.toString());

        JsonNode replay = call("checkpoint_context", DomainServerSupport.arguments("operationKey", operationKey,"expectedStateToken", actual, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of()));
        assertEquals(token, replay.path("data").path("stateToken").asString(), "the same request answers the same state");

        JsonNode conflict = call("checkpoint_context", DomainServerSupport.arguments("operationKey", operationKey,"expectedStateToken", token, "status", "PAUSED", "core", core("second"), "evidenceRefs", List.of()));
        assertRefused(conflict, "REQUEST_CONFLICT", "continuity.checkpoint.requestConflict");

        JsonNode inspected = call("inspect_context", DomainServerSupport.arguments());
        assertTrue(inspected.path("ok").booleanValue(), inspected.toString());
        assertEquals("DEFER", inspected.path("data").path("decision").asString(), inspected.toString());
        String candidate = inspected.path("data").path("restoreToken").asString();
        JsonNode loaded = call("load_context", DomainServerSupport.arguments("candidateToken", candidate));
        assertTrue(loaded.path("ok").booleanValue(), loaded.toString());
        assertEquals("first", loaded.path("data").path("memory").path("core").path("objective").asString(), loaded.toString());

        assertRefused(call("load_context", DomainServerSupport.arguments("candidateToken", "x".repeat(40))), "BINDING_INVALID", "continuity.token.malformed");
        assertRefused(call("suppress_context_restore", DomainServerSupport.arguments("expectedEpoch", 987654)), "STALE_REVISION", "continuity.epoch.changed");
        assertRefused(call("purge_direct_context", DomainServerSupport.arguments("operationKey", "purge-operation-0001","expectedStateToken", TOKEN)), "STALE_REVISION", "continuity.purge.stateChanged");
    }

    /**
     * A refusal rolls its transaction back, so it must not show a value that transaction made. A session registered by a start the continuity rules do nothing for has no task: the
     * stale {@code checkpoint_context} and {@code purge_direct_context} of such a session are refused with no {@code actualStateToken} and make no task (a token of a task made
     * inside the refusal would be gone with it, and the next call would show another). {@code load_context} makes the task and commits it: the token it answers is the one
     * the database holds, and the save that names it is accepted.
     */
    @Test
    void aSessionWithNoTaskIsRefusedWithoutATokenAndTheTokenLoadContextGivesIsTheOneTheDatabaseHolds() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, session, body -> body.put("source", "other")).status());
        Map<String, Object> own = SessionServerSupport.meta("windows", "codex", session, "none");
        String task = server.bean(ContinuityStore.class).opaqueDigest("session", session);
        String tasks = "SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?";
        assertEquals(0, SessionServerSupport.count(server, tasks, task), "the session has no task");

        JsonNode saved = DomainServerSupport.call(client, "checkpoint_context", DomainServerSupport.arguments("expectedStateToken", TOKEN, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of()), own);
        assertRefused(saved, "STALE_REVISION", "continuity.checkpoint.stateChanged");
        assertTrue(saved.path("error").path("details").path("actualStateToken").isMissingNode(), "no token that was never committed is shown: " + saved);
        assertEquals(TOKEN, saved.path("error").path("details").path("expectedStateToken").asString(), saved.toString());
        JsonNode purged = DomainServerSupport.call(client, "purge_direct_context", DomainServerSupport.arguments("expectedStateToken", TOKEN), own);
        assertRefused(purged, "STALE_REVISION", "continuity.purge.stateChanged");
        assertTrue(purged.path("error").path("details").path("actualStateToken").isMissingNode(), "no token that was never committed is shown: " + purged);
        assertEquals(0, SessionServerSupport.count(server, tasks, task), "a refusal made no task");

        JsonNode loaded = DomainServerSupport.call(client, "load_context", DomainServerSupport.arguments(), own);
        assertTrue(loaded.path("ok").booleanValue(), loaded.toString());
        String token = loaded.path("data").path("stateToken").asString();
        assertEquals(1, SessionServerSupport.count(server, tasks, task), "load_context made the task");
        assertEquals(token, SessionServerSupport.row(server, "SELECT state_token FROM sennard.continuity_current_memory WHERE task_correlation = ?", task).get("state_token"),
                "the token that was answered is the one the database holds");
        JsonNode accepted = DomainServerSupport.call(client, "checkpoint_context", DomainServerSupport.arguments("expectedStateToken", token, "status", "ACTIVE", "core", core("first"), "evidenceRefs", List.of()), own);
        assertTrue(accepted.path("ok").booleanValue(), accepted.toString());
    }

    @Test
    void aCleanupPlanRunsOnceAndAForgedPlanIsRefused() {
        JsonNode plan = call("prepare_state_cleanup", DomainServerSupport.arguments());
        assertTrue(plan.path("ok").booleanValue(), plan.toString());
        String token = plan.path("data").path("planToken").asString();
        JsonNode done = call("execute_state_cleanup", DomainServerSupport.arguments("planToken", token));
        assertTrue(done.path("ok").booleanValue(), done.toString());
        JsonNode again = call("execute_state_cleanup", DomainServerSupport.arguments("planToken", token));
        assertRefused(again, "STALE_REVISION", "services.stateCleanup.tokenUsed");
        assertRefused(call("execute_state_cleanup", DomainServerSupport.arguments("planToken", "x".repeat(90))), "INVALID_INPUT", "services.stateCleanup.tokenMalformed");
    }

    private static String prepared(String body) {
        JsonNode prepared = call("prepare_session_message", DomainServerSupport.arguments("targetHost", "claude-code", "targetSessionId", target, "body", body));
        assertTrue(prepared.path("ok").booleanValue(), prepared.toString());
        return prepared.path("data").path("messageId").asString();
    }

    @Test
    void aMessageIsPreparedSentAndRecalledAndItsStatusFollows() {
        String messageId = prepared("hello");
        assertEquals("PREPARED", status(messageId).path("data").path("status").path("state").asString());

        JsonNode sent = call("send_session_message", DomainServerSupport.arguments("messageId", messageId));
        assertTrue(sent.path("ok").booleanValue(), sent.toString());
        assertEquals(messageId, sent.path("data").path("messageId").asString());
        assertEquals("none", sent.path("data").path("autoWake").path("authorityEffect").asString(), sent.toString());
        assertFalse(sent.path("data").has("duplicate"), sent.toString());

        JsonNode duplicate = call("send_session_message", DomainServerSupport.arguments("messageId", messageId));
        assertTrue(duplicate.path("data").path("duplicate").booleanValue(), duplicate.toString());
        assertEquals(sent.path("data").path("createdAt").asString(), duplicate.path("data").path("createdAt").asString());

        JsonNode queued = status(messageId);
        assertEquals("QUEUED", queued.path("data").path("status").path("state").asString(), queued.toString());
        assertEquals(target, queued.path("data").path("status").path("target").path("sessionId").asString(), queued.toString());
        assertEquals("claude-code", queued.path("data").path("status").path("target").path("host").asString(), queued.toString());

        JsonNode recalled = call("recall_session_message", DomainServerSupport.arguments("messageId", messageId));
        assertTrue(recalled.path("data").path("recalled").booleanValue(), recalled.toString());
        assertEquals("RECALLED", recalled.path("data").path("status").path("state").asString(), recalled.toString());
        JsonNode second = call("recall_session_message", DomainServerSupport.arguments("messageId", messageId));
        assertTrue(second.path("data").path("duplicate").booleanValue(), second.toString());
    }

    private static JsonNode status(String messageId) {
        return call("get_session_message_status", DomainServerSupport.arguments("messageId", messageId));
    }

    @Test
    void anUnknownMessageIsAnUnknownDeliveryNotAnError() {
        JsonNode result = status(MESSAGE_ID);
        assertTrue(result.path("ok").booleanValue(), result.toString());
        assertTrue(result.path("data").path("status").isNull(), result.toString());
        assertEquals("services.sessionMessage.status.deliveryUnknown", result.path("data").path("guidanceKey").asString(), result.toString());
        JsonNode recall = call("recall_session_message", DomainServerSupport.arguments("messageId", MESSAGE_ID));
        assertFalse(recall.path("data").path("recalled").booleanValue(), recall.toString());
        assertEquals("UNKNOWN", recall.path("data").path("reason").asString(), recall.toString());
    }

    @Test
    void aSendThatCannotBeConfirmedSaysHowToRetry() {
        JsonNode result = call("send_session_message", DomainServerSupport.arguments("messageId", MESSAGE_ID));
        assertRefused(result, "MCP_UNAVAILABLE", "services.sessionMessage.send.retryAfterRejection");
        assertTrue(result.path("error").path("message").asString().contains("Issued message ID is unavailable"), result.toString());
        assertEquals("SEND_REJECTED", result.path("error").path("details").path("reasonCode").asString(), result.toString());
        assertTrue(result.path("error").path("params").path("reason").asString().startsWith("Issued message ID is unavailable"), "the reason's sentence is a parameter: " + result);
    }

    @Test
    void aBodyOfOnlyWhitespaceIsNotAMessage() {
        JsonNode result = call("prepare_session_message", DomainServerSupport.arguments("targetHost", "claude-code", "targetSessionId", target, "body", "   "));
        assertRefused(result, "INVALID_INPUT", "services.sessionMessage.message.bodySize");
        assertEquals("MESSAGE_BODY_SIZE", result.path("error").path("details").path("reasonCode").asString(), result.toString());
        assertEquals(4096, result.path("error").path("params").path("maxBytes").asInt(), result.toString());
    }

    @Test
    void aSessionThisServerHasNeverSeenCannotBeAddressed() {
        JsonNode result = call("prepare_session_message", DomainServerSupport.arguments("targetHost", "claude-code", "targetSessionId", SessionServerSupport.uniqueSession(), "body", "x"));
        assertRefused(result, "NOT_FOUND", "error.SESSION_NOT_FOUND");
        assertEquals("SESSION_NOT_FOUND", result.path("error").path("details").path("reasonCode").asString(), result.toString());
    }

    @Test
    void anAcknowledgementNeedsAMessageTheCallerHolds() {
        JsonNode result = call("acknowledge_session_messages", DomainServerSupport.arguments("messageIds", new ArrayList<>(List.of(MESSAGE_ID))));
        assertTrue(result.path("ok").booleanValue(), result.toString());
        assertEquals(0, result.path("data").path("acknowledged").asInt(), result.toString());
    }
}
