// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static io.github.jaeseongs95.sennard.serverit.DomainServerSupport.arguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Tag("business-db")
class ActorPeerDeliveryServerTest {
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({"codex,codex,PostToolUse", "claude,claude-code,PostToolUse", "agy,antigravity,PreInvocation"})
    void observedChildrenHaveSeparatePeerMailboxes(String host, String storedHost, String boundary) throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String sibling = SessionServerSupport.uniqueSession();
        String sender = SessionServerSupport.uniqueSession();
        hook(host, "SessionStart", root, body -> {});
        hook("codex", "SessionStart", sender, body -> {});

        String inbound = "inbound-" + UUID.randomUUID();
        String id = data(call("codex", sender, null, "prepare_session_message",
                arguments("targetHost", storedHost, "targetSessionId", root, "targetActorId", child, "body", inbound))).path("messageId").asString();
        JsonNode sent = data(call("codex", sender, null, "send_session_message", arguments("messageId", id)));
        assertEquals("no-live-relay", sent.path("autoWake").path("state").asString());
        if (host.equals("agy")) {
            assertFalse(atBoundary(host, boundary, root, child).contains(BEGIN), "a provisional conversation cannot claim");
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.observeAgy(server, child, root, root);
            DomainServerSupport.observeAgy(server, sibling, root, root);
            String unrelatedRoot = SessionServerSupport.uniqueSession();
            hook(host, "SessionStart", unrelatedRoot, body -> {});
            DomainServerSupport.observeAgyRoot(server, unrelatedRoot);
            JsonNode misbound = call(host, unrelatedRoot, child, "prepare_session_message",
                    arguments("targetHost", "codex", "targetSessionId", sender, "body", "cross-root draft"));
            assertFalse(misbound.path("ok").asBoolean(), "the same child cannot act from another root");
            assertFalse(atBoundary(host, "PostToolUse", root, child).contains(BEGIN), "no delivery slot");
        }
        if (host.equals("codex")) {
            assertFalse(atBoundary(host, "PostModelRequest", root, child).contains(BEGIN), "no delivery slot");
        }
        assertFalse(atBoundary(host, boundary, root, null).contains(BEGIN));
        assertFalse(atBoundary(host, boundary, root, sibling).contains(BEGIN));

        JsonNode inboundEnvelope = message(atBoundary(host, boundary, root, child));
        assertEquals(inbound, inboundEnvelope.path("message").asString());
        assertEquals(root, inboundEnvelope.path("recipient").path("sessionId").asString());
        assertEquals(child, inboundEnvelope.path("recipient").path("actorId").asString());
        assertEquals(child, status("codex", sender, null, id).path("target").path("actorId").asString());
        JsonNode receipt = inboundEnvelope.path("receipt");
        Map<String, Object> ack = arguments("messageIds", List.of(id), "receipts",
                List.of(Map.of("messageId", id, "claimReceipt", receipt.path("claimReceipt").asString())));
        assertEquals(0, data(call(host, root, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());
        assertEquals(0, data(call(host, root, sibling, "acknowledge_session_messages", ack)).path("acknowledged").asInt());
        assertEquals(1, data(call(host, root, child, "acknowledge_session_messages", ack)).path("acknowledged").asInt());

        String outbound = "outbound-" + UUID.randomUUID();
        String replyId = data(call(host, root, child, "prepare_session_message",
                arguments("targetHost", "codex", "targetSessionId", sender, "body", outbound))).path("messageId").asString();
        assertTrue(status(host, root, null, replyId).isNull());
        assertTrue(status(host, root, sibling, replyId).isNull());
        assertFalse(data(call(host, root, null, "recall_session_message", arguments("messageId", replyId))).path("recalled").asBoolean());
        assertFalse(call(host, root, sibling, "send_session_message", arguments("messageId", replyId)).path("ok").asBoolean());
        data(call(host, root, child, "send_session_message", arguments("messageId", replyId)));
        JsonNode reply = message(atBoundary("codex", "PostToolUse", sender, null));
        assertEquals(outbound, reply.path("message").asString());
        assertEquals(root, reply.path("sender").path("sessionId").asString());
        assertEquals(child, reply.path("sender").path("actorId").asString());

        assertSource(receipt.path("sourceReceiptId").asString(), "recipientActorId", child);
        assertSource(reply.path("receipt").path("sourceReceiptId").asString(), "senderActorId", child);
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id IN (?, ?, ?, ?)", root, child, sibling, sender));
    }

    private static JsonNode status(String host, String root, String actor, String id) throws IOException {
        return data(call(host, root, actor, "get_session_message_status", arguments("messageId", id))).path("status");
    }

    @ParameterizedTest
    @CsvSource({"codex,codex", "claude,claude-code"})
    void managedConversationUsesOnlyItsBoundPeerActor(String host, String storedHost) throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String sibling = SessionServerSupport.uniqueSession();
        hook(host, "SessionStart", root, body -> {});
        var registry = server.bean(SessionRegistry.class);
        String rootRef = registry.findRef(storedHost, root).orElseThrow();
        long generation = ((Number) SessionServerSupport.row(server, "SELECT generation FROM sennard.active_generation WHERE session_ref = ?", rootRef)
                .get("generation")).longValue();
        assertTrue(registry.bindManagedChild(storedHost, root, rootRef, generation, child));
        assertTrue(registry.bindManagedChild(storedHost, root, rootRef, generation, sibling));
        String childRef = registry.findRef(storedHost, child).orElseThrow();

        hook(host, "SessionStart", child, body -> {});
        hook(host, "UserPromptSubmit", child, body -> body.put("prompt", "managed turn input"));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.active_generation WHERE session_ref = ?", childRef));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id = ?", child));

        String id = data(call(host, root, null, "prepare_session_message",
                arguments("targetHost", storedHost, "targetSessionId", root, "targetActorId", child, "body", "work for this child"))).path("messageId").asString();
        data(call(host, root, null, "send_session_message", arguments("messageId", id)));
        assertFalse(atBoundary(host, "PostToolUse", root, null).contains(BEGIN));
        assertFalse(atBoundary(host, "PostToolUse", sibling, null).contains(BEGIN));
        RawHttp.Response wrongPhysicalThread = DomainServerSupport.hook(server.port, "windows", host, "PostToolUse", null, root,
                body -> body.put("agent_id", child));
        assertEquals(409, wrongPhysicalThread.status(), DomainServerSupport.text(wrongPhysicalThread));
        JsonNode delivered = message(atBoundary(host, "PostToolUse", child, null));
        assertEquals(root, delivered.path("recipient").path("sessionId").asString());
        assertEquals(child, delivered.path("recipient").path("actorId").asString());
        assertEquals("work for this child", delivered.path("message").asString());
        assertSource(delivered.path("receipt").path("sourceReceiptId").asString(), "recipientActorId", child);
        Map<String, Object> ack = arguments("messageIds", List.of(id), "receipts",
                List.of(Map.of("messageId", id, "claimReceipt", delivered.path("receipt").path("claimReceipt").asString())));
        assertEquals(0, data(call(host, root, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());
        assertEquals(0, data(call(host, sibling, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());
        assertFalse(call(host, root, child, "acknowledge_session_messages", ack).path("ok").asBoolean(), "managed identity requires the physical thread");
        assertEquals(1, data(call(host, child, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());

        String replyId = data(call(host, child, null, "prepare_session_message",
                arguments("targetHost", storedHost, "targetSessionId", root, "body", "reply from this child"))).path("messageId").asString();
        data(call(host, child, null, "send_session_message", arguments("messageId", replyId)));
        JsonNode reply = message(atBoundary(host, "PostToolUse", root, null));
        assertEquals("reply from this child", reply.path("message").asString());
        assertEquals(root, reply.path("sender").path("sessionId").asString());
        assertEquals(child, reply.path("sender").path("actorId").asString());
        assertSource(reply.path("receipt").path("sourceReceiptId").asString(), "senderActorId", child);
        Map<String, Object> replyAck = arguments("messageIds", List.of(replyId), "receipts",
                List.of(Map.of("messageId", replyId, "claimReceipt", reply.path("receipt").path("claimReceipt").asString())));
        assertEquals(0, data(call(host, child, null, "acknowledge_session_messages", replyAck)).path("acknowledged").asInt());
        assertEquals(1, data(call(host, root, null, "acknowledge_session_messages", replyAck)).path("acknowledged").asInt());

        String statusTool = "get_session_message_status";
        String staleCall = "toolu_" + UUID.randomUUID();
        Map<String, Object> statusInput = arguments("messageId", replyId);
        if ("claude".equals(host)) {
            String toolUse = "toolu_" + UUID.randomUUID();
            admitClaude(child, null, statusTool, toolUse);
            try (var client = DomainServerSupport.client(server.port, host)) {
                var meta = SessionServerSupport.meta("windows", host, child, toolUse);
                data(DomainServerSupport.callAsSent(client, statusTool, statusInput, meta));
                assertFalse(DomainServerSupport.callAsSent(client, statusTool, statusInput, meta).path("ok").asBoolean());
            }
            admitClaude(child, null, statusTool, staleCall);
        }

        hook(host, "SessionEnd", root, body -> {});
        if ("claude".equals(host)) {
            try (var client = DomainServerSupport.client(server.port, host)) {
                var meta = SessionServerSupport.meta("windows", host, child, staleCall);
                assertFalse(DomainServerSupport.callAsSent(client, statusTool, statusInput, meta).path("ok").asBoolean());
            }
        }
        assertFalse(registry.isLiveGeneration(rootRef, generation));
        assertFalse(call(host, child, null, "acknowledge_session_messages", ack).path("ok").asBoolean());
        RawHttp.Response stale = DomainServerSupport.hook(server.port, "windows", host, "PostToolUse", null, child, body -> {});
        assertEquals(409, stale.status(), DomainServerSupport.text(stale));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.active_generation WHERE session_ref IN (?, ?)", rootRef, childRef));
    }

    @Test
    void antigravityManagedChildUsesPhysicalConversationEvenBeforeItsFirstMcpCall() throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String sibling = SessionServerSupport.uniqueSession();
        hook("agy", "SessionStart", root, body -> {});
        DomainServerSupport.observeAgyRoot(server, root);
        var registry = server.bean(SessionRegistry.class);
        String rootRef = registry.findRef("antigravity", root).orElseThrow();
        long generation = ((Number) SessionServerSupport.row(server, "SELECT generation FROM sennard.active_generation WHERE session_ref = ?", rootRef)
                .get("generation")).longValue();
        assertTrue(registry.bindManagedChild("antigravity", root, rootRef, generation, child));
        assertTrue(registry.bindManagedChild("antigravity", root, rootRef, generation, sibling));

        hook("agy", "SessionStart", child, body -> {});
        String id = data(call("agy", root, null, "prepare_session_message",
                arguments("targetHost", "antigravity", "targetSessionId", root, "targetActorId", child, "body", "work for this child"))).path("messageId").asString();
        data(call("agy", root, null, "send_session_message", arguments("messageId", id)));
        assertFalse(atBoundary("agy", "PreInvocation", root, null).contains(BEGIN));
        assertFalse(atBoundary("agy", "PreInvocation", sibling, null).contains(BEGIN));
        JsonNode delivered = message(atBoundary("agy", "PreInvocation", child, null));
        assertEquals(child, delivered.path("recipient").path("actorId").asString());
        Map<String, Object> ack = arguments("messageIds", List.of(id), "receipts",
                List.of(Map.of("messageId", id, "claimReceipt", delivered.path("receipt").path("claimReceipt").asString())));
        assertEquals(0, data(call("agy", root, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());
        assertFalse(call("agy", root, child, "acknowledge_session_messages", ack).path("ok").asBoolean());
        assertEquals(1, data(call("agy", child, null, "acknowledge_session_messages", ack)).path("acknowledged").asInt());

        hook("agy", "PreInvocation", child, body -> body.put("invocationNum", 0).put("lastUserInput", "automated child input"));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id IN (?, ?)", child, sibling));
    }

    private static void assertSource(String id, String actorField, String actor) {
        var row = SessionServerSupport.row(server, "SELECT receipt_json FROM sennard.input_source_receipts WHERE receipt_id = ?", id);
        JsonNode receipt = DomainServerSupport.MAPPER.readTree((String) row.get("receipt_json"));
        assertEquals("peer", receipt.path("originKind").asString());
        assertEquals("none", receipt.path("authorityEffect").asString());
        assertEquals(actor, receipt.path(actorField).asString());
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
    }

    private static JsonNode message(String text) {
        assertTrue(text.contains(BEGIN), text);
        int start = text.indexOf('{', text.indexOf(BEGIN));
        return DomainServerSupport.MAPPER.readTree(text.substring(start, text.indexOf('\n', start)));
    }

    private static String atBoundary(String host, String event, String root, String actor) throws IOException {
        String session = "agy".equals(host) && actor != null ? actor : root;
        JsonNode result = hook(host, event, session, body -> {
            if (actor != null && !"agy".equals(host)) {
                body.put("agent_id", actor);
            }
            if ("agy".equals(host) && "PreInvocation".equals(event)) {
                body.put("invocationNum", 0);
                if (actor == null) {
                    body.remove("lastUserInput");
                } else {
                    body.put("lastUserInput", "agent input at child invocation");
                }
            }
        });
        if ("agy".equals(host)) {
            StringBuilder context = new StringBuilder();
            result.path("injectSteps").forEach(step -> context.append(step.path("systemMessage").path("systemMessage").asString())
                    .append(step.path("ephemeralMessage").asString()));
            return context.toString();
        }
        return result.path("hookSpecificOutput").path("additionalContext").asString();
    }

    private static JsonNode hook(String host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        String fixture = "PostModelRequest".equals(event) ? "PostToolUse" : event;
        RawHttp.Response result = DomainServerSupport.hookFrom(fixture, server.port, "windows", host, event, null, session, edit);
        assertEquals(200, result.status(), DomainServerSupport.text(result));
        return DomainServerSupport.MAPPER.readTree(result.body());
    }

    private static void admitClaude(String session, String actor, String tool, String toolUse) throws IOException {
        RawHttp.Response admitted = DomainServerSupport.hook(server.port, "windows", "claude", "PreToolUse", "governance", session, body -> {
            body.put("tool_use_id", toolUse).put("tool_name", "mcp__plugin_sennard_sennard__" + tool);
            if (actor != null) {
                body.put("agent_id", actor).put("agent_type", "general-purpose");
            }
        });
        assertEquals(200, admitted.status(), DomainServerSupport.text(admitted));
    }

    private static JsonNode call(String host, String root, String actor, String tool, Map<String, Object> args) throws IOException {
        Map<String, Object> keyed = OperationKeys.withKey(tool, args);
        String toolUse = "toolu_" + UUID.randomUUID();
        String session = "agy".equals(host) && actor != null ? actor : root;
        Map<String, Object> meta = new LinkedHashMap<>(SessionServerSupport.meta("windows", host, session, "claude".equals(host) ? toolUse : "none"));
        switch (host) {
            case "codex" -> {
                if (actor != null) {
                    Map<String, Object> turn = new LinkedHashMap<>();
                    ((Map<?, ?>) meta.get("x-codex-turn-metadata")).forEach((key, value) -> turn.put((String) key, value));
                    turn.put("thread_id", actor);
                    turn.put("parent_thread_id", root);
                    meta.put("threadId", actor);
                    meta.put("x-codex-turn-metadata", turn);
                }
            }
            case "claude" -> admitClaude(root, actor, tool, toolUse);
            case "agy" -> {
                ObjectNode hookArguments = DomainServerSupport.MAPPER.valueToTree(keyed);
                hookArguments.put("toolSummary", "Call Sennard " + tool);
                JsonNode admitted = hook(host, "PreToolUse", session, body -> body.putObject("toolCall")
                        .put("name", "mcp_sennard_" + tool).put("id", UUID.randomUUID().toString()).set("args", hookArguments));
                assertEquals("allow", admitted.path("decision").asString(), admitted.toString());
                meta.put("antigravity.google/conversation_id", session);
                meta.put("antigravity.google/root_conversation_id", root);
                meta.remove("antigravity.google/parent_conversation_id");
                if (actor != null) {
                    meta.put("antigravity.google/parent_conversation_id", root);
                }
            }
            default -> throw new IllegalArgumentException(host);
        }
        try (McpSyncClient client = DomainServerSupport.client(server.port, host)) {
            return DomainServerSupport.callAsSent(client, tool, keyed, meta);
        }
    }
}
