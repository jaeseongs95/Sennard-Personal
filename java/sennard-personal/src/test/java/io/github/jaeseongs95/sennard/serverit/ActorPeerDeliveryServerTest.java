// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static io.github.jaeseongs95.sennard.serverit.DomainServerSupport.arguments;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.app.messaging.MessagingService;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;
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

    @Test
    void observedAntigravityConversationsRetainTheirImmediateParents() throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String grandchild = SessionServerSupport.uniqueSession();
        var registry = server.bean(SessionRegistry.class);
        hook("agy", "SessionStart", root, body -> {});
        DomainServerSupport.observeAgyRoot(server, root);
        hook("agy", "SessionStart", child, body -> {});
        DomainServerSupport.observeAgy(server, child, root, root);
        DomainServerSupport.observeAgy(server, grandchild, root, child);

        var membership = registry.treeMembership("antigravity", grandchild).orElseThrow();
        assertEquals(2, membership.depth());
        assertEquals(registry.findRef("antigravity", child).orElseThrow(), membership.parentRef());
        assertEquals(root, membership.rootSessionId());
        var judgement = registry.notifyToolByRef(membership.rootRef()).orElseThrow().judgment();
        hook("agy", "Stop", child, body -> {});
        assertTrue(registry.isLiveGeneration(membership.rootRef(), judgement.generation()));
    }

    @ParameterizedTest
    @CsvSource({"codex,codex,PostToolUse", "claude,claude-code,PostToolUse", "agy,antigravity,PreInvocation"})
    void peerEndpointsAreMainSessions(String host, String storedHost, String boundary) throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String sibling = SessionServerSupport.uniqueSession();
        String other = SessionServerSupport.uniqueSession();
        hook(host, "SessionStart", root, body -> {});
        hook("codex", "SessionStart", other, body -> {});
        if (host.equals("agy")) {
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.observeAgy(server, child, root, root);
            DomainServerSupport.observeAgy(server, sibling, root, root);
        }
        Map<String, Object> explicitChild = arguments("targetHost", storedHost, "targetSessionId", root, "targetActorId", child, "body", "child target");
        Map<String, Object> toOther = arguments("targetHost", "codex", "targetSessionId", other, "body", "root-only message");
        String rootId = data(call(host, root, null, "prepare_session_message", toOther)).path("messageId").asString();
        var ids = arguments("messageIds", List.of(rootId));

        assertAll(
                () -> assertRefused(call("codex", other, null, "prepare_session_message", explicitChild)),
                () -> assertRefused(call(host, root, child, "prepare_session_message", toOther)),
                () -> assertRefused(call(host, root, child, "send_session_message", arguments("messageId", rootId))),
                () -> assertRefused(call(host, root, child, "get_session_message_status", arguments("messageId", rootId))),
                () -> assertRefused(call(host, root, child, "recall_session_message", arguments("messageId", rootId))),
                () -> assertRefused(call(host, root, sibling, "acknowledge_session_messages", ids)),
                () -> assertFalse(atBoundary(host, boundary, root, child).contains(BEGIN)));

        data(call(host, root, null, "send_session_message", arguments("messageId", rootId)));
        assertTrue(atBoundary("codex", "PostToolUse", other, null).contains("root-only message"));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id IN (?, ?, ?)", root, child, sibling));
    }

    @ParameterizedTest
    @CsvSource({"codex,codex,PostToolUse", "claude,claude-code,PostToolUse", "agy,antigravity,PreInvocation"})
    void previouslyAddressedChildMessagesCannotBeSentOrClaimed(String host, String storedHost, String boundary) throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String other = SessionServerSupport.uniqueSession();
        hook(host, "SessionStart", root, body -> {});
        hook("codex", "SessionStart", other, body -> {});
        if (host.equals("agy")) {
            DomainServerSupport.observeAgyRoot(server, root);
            DomainServerSupport.observeAgy(server, child, root, root);
        }
        var registry = server.bean(SessionRegistry.class);
        String rootRef = registry.findRef(storedHost, root).orElseThrow();
        String otherRef = registry.findRef("codex", other).orElseThrow();
        var childAddress = new PeerAddress(rootRef, child);
        var otherAddress = PeerAddress.session(otherRef);
        var queue = server.bean(MessagingService.class);
        String unsent = legacyDraft(queue, otherAddress, childAddress, "old draft for child");
        String toChild = legacyDraft(queue, otherAddress, childAddress, "old message for child");
        String fromChild = legacyDraft(queue, childAddress, otherAddress, "old message from child");
        legacySend(queue, otherAddress, "codex", toChild);
        legacySend(queue, childAddress, storedHost, fromChild);
        String legitimate = data(call("codex", other, null, "prepare_session_message",
                arguments("targetHost", storedHost, "targetSessionId", root, "body", "deliver only to root"))).path("messageId").asString();
        data(call("codex", other, null, "send_session_message", arguments("messageId", legitimate)));

        assertAll(
                () -> assertRefused(call("codex", other, null, "send_session_message", arguments("messageId", unsent))),
                () -> assertFalse(atBoundary(host, boundary, root, child).contains(BEGIN)),
                () -> {
                    String rootOutput = atBoundary(host, boundary, root, null);
                    assertTrue(rootOutput.contains("deliver only to root"), rootOutput);
                    assertFalse(rootOutput.contains("old message"));
                },
                () -> assertFalse(atBoundary("codex", "PostToolUse", other, null).contains(BEGIN)));
        var status = data(call("codex", other, null, "get_session_message_status", arguments("messageId", toChild))).path("status");
        assertEquals("QUEUED", status.path("state").asString());
        assertTrue(data(call("codex", other, null, "recall_session_message", arguments("messageId", toChild))).path("recalled").asBoolean());
    }

    @ParameterizedTest
    @CsvSource({"codex,codex,PostToolUse", "claude,claude-code,PostToolUse", "agy,antigravity,PreInvocation"})
    void managedConversationCannotBeAddressedAsPeerByPhysicalId(String host, String storedHost, String boundary) throws Exception {
        String root = SessionServerSupport.uniqueSession();
        String child = SessionServerSupport.uniqueSession();
        String other = SessionServerSupport.uniqueSession();
        hook(host, "SessionStart", root, body -> {});
        hook("codex", "SessionStart", other, body -> {});
        if (host.equals("agy")) {
            DomainServerSupport.observeAgyRoot(server, root);
        }
        var registry = server.bean(SessionRegistry.class);
        String rootRef = registry.findRef(storedHost, root).orElseThrow();
        long generation = ((Number) SessionServerSupport.row(server, "SELECT generation FROM sennard.active_generation WHERE session_ref = ?", rootRef)
                .get("generation")).longValue();
        assertTrue(registry.bindManagedChild(storedHost, root, rootRef, generation, child));
        String childRef = registry.findRef(storedHost, child).orElseThrow();
        hook(host, "SessionStart", child, body -> {});
        assertFalse(atBoundary(host, boundary, child, null).contains(BEGIN));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.active_generation WHERE session_ref = ?", childRef));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.user_prompt WHERE session_id = ?", child));
        RawHttp.Response wrongPhysicalThread = DomainServerSupport.hook(server.port, "windows", host, "PostToolUse", null, root,
                body -> body.put("agent_id", child));
        if (!host.equals("agy")) {
            assertEquals(409, wrongPhysicalThread.status(), DomainServerSupport.text(wrongPhysicalThread));
        }
        var request = arguments("targetHost", storedHost, "targetSessionId", child, "body", "wrong physical address");
        var result = call("codex", other, null, "prepare_session_message", request);
        assertRefused(result);

        if (host.equals("agy")) {
            registry.endByHook(storedHost, root, System.currentTimeMillis(), "test");
        } else {
            hook(host, "SessionEnd", root, body -> {});
        }
        assertFalse(registry.isLiveGeneration(rootRef, generation));
        RawHttp.Response stale = DomainServerSupport.hook(server.port, "windows", host, "PostToolUse", null, child, body -> {});
        assertEquals(409, stale.status(), DomainServerSupport.text(stale));
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM sennard.active_generation WHERE session_ref IN (?, ?)", rootRef, childRef));
    }

    private static String legacyDraft(MessagingService queue, PeerAddress sender, PeerAddress target, String body) {
        return queue.prepare(sender, target, body, null, OperationKeys.operation("prepare_session_message", sender.sessionRef())).messageId();
    }

    private static void legacySend(MessagingService queue, PeerAddress sender, String host, String id) {
        queue.send(sender, host, id, OperationKeys.operation("send_session_message", sender.sessionRef()), Sent.class, Function.identity());
    }

    private static void assertRefused(JsonNode answer) {
        assertFalse(answer.path("ok").asBoolean(), answer.toString());
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
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
