// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.messaging.ClaimReceipt;
import io.github.jaeseongs95.sennard.domain.messaging.RecallRefusal;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.trust.TrustService;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;

/**
 * Taking a message when several things want it, on real PostgreSQL through the real hook path: exactly one claimer holds a claim, a recall and
 * a claim cannot both win, and a lapsed claim is given again under a new receipt. A barrier lines the racers up; nothing waits on a clock.
 */
@Tag("business-db")
class SessionMessageRaceServerTest {
    private static final Logger LOG = LoggerFactory.getLogger(SessionMessageRaceServerTest.class);
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static final int RACERS = 8;
    private static final int ROUNDS = 12;
    private static RunningServer server;
    private static ExecutorService pool;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
        pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void stop() {
        pool.close();
        server.close();
    }

    private static String newSession(String host) throws IOException {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(server.port, "windows", host, "SessionStart", null, session, body -> {
        }).status());
        return session;
    }

    private static String refOf(String host, String session) {
        return (String) SessionServerSupport.row(server, "SELECT session_ref FROM sennard.session_ref WHERE host = ? AND local_session_id = ?", host, session).get("session_ref");
    }

    private static String sendMessage(String senderRef, String target, String body) {
        SessionMessageService messages = server.bean(SessionMessageService.class);
        var prepared = messages.prepare(senderRef, "claude-code", target, body, null, OperationKeys.operation("prepare_session_message", senderRef));
        messages.send(senderRef, "codex", prepared.messageId(), OperationKeys.operation("send_session_message", senderRef));
        return prepared.messageId();
    }

    private static String claimThroughHook(String session) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", "claude", "PostToolUse", null, session, body -> {
        });
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static <T> List<T> race(List<Callable<T>> racers) throws Exception {
        CyclicBarrier line = new CyclicBarrier(racers.size());
        List<Future<T>> running = new ArrayList<>();
        for (Callable<T> racer : racers) {
            running.add(pool.submit(() -> {
                line.await();
                return racer.call();
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : running) {
            results.add(future.get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void oneMessageGoesToOneClaimerHoweverManyAsk() throws Exception {
        String target = newSession("claude");
        String senderRef = refOf("codex", newSession("codex"));
        for (int round = 0; round < ROUNDS; round += 1) {
            String body = "race-" + UUID.randomUUID();
            String messageId = sendMessage(senderRef, target, body);
            List<Callable<String>> racers = new ArrayList<>();
            for (int racer = 0; racer < RACERS; racer += 1) {
                racers.add(() -> claimThroughHook(target)); // every answer is a 2xx (claimThroughHook asserts it): a claimer that lost the message takes nothing and is not an error
            }
            long winners = race(racers).stream().filter(text -> text.contains(body)).count();
            assertEquals(1, winners, "round " + round);
            assertEquals(1, receiptsOf(messageId), "one source receipt for the message, round " + round);
        }
    }

    private static long receiptsOf(String messageId) {
        return SessionServerSupport.count(server, "SELECT COUNT(*) FROM sennard.input_source_receipts WHERE event_id = ?", messageId);
    }

    private static long signingKeyRows() {
        return SessionServerSupport.count(server, "SELECT COUNT(*) FROM sennard.trust_metadata WHERE meta_key = ?", "trust-signing-key");
    }

    private static String signingKey() {
        return (String) SessionServerSupport.row(server, "SELECT value FROM sennard.trust_metadata WHERE meta_key = ?", "trust-signing-key").get("value");
    }

    /**
     * The signing key is made once, by the start of the server, and the claims only read it: eight sessions claim at once, each its own message, and afterwards there is still
     * one key row with the value it had, and every receipt verifies against it.
     */
    @Test
    void theSigningKeyIsMadeAtStartAndEightSessionsClaimingAtOnceLeaveItAndVerifyEveryReceipt() throws Exception {
        assertEquals(1, signingKeyRows(), "the start of the server made the key");
        String key = signingKey();
        String senderRef = refOf("codex", newSession("codex"));
        Map<String, String> bodies = new java.util.LinkedHashMap<>();
        List<String> targets = new ArrayList<>();
        Map<String, String> messageOf = new java.util.LinkedHashMap<>();
        for (int session = 0; session < RACERS; session += 1) {
            String target = newSession("claude");
            String body = "trust-" + UUID.randomUUID();
            messageOf.put(target, sendMessage(senderRef, target, body));
            bodies.put(target, body);
            targets.add(target);
        }
        List<Callable<String>> racers = new ArrayList<>();
        for (String target : targets) {
            racers.add(() -> claimThroughHook(target));
        }
        List<String> answers = race(racers);
        for (int index = 0; index < targets.size(); index += 1) {
            assertTrue(answers.get(index).contains(bodies.get(targets.get(index))), "every session got its message");
        }
        assertEquals(1, signingKeyRows(), "the claims made no key");
        assertEquals(key, signingKey(), "and changed none");
        for (String messageId : messageOf.values()) {
            assertEquals(1, receiptsOf(messageId));
            String json = (String) SessionServerSupport.row(server, "SELECT receipt_json FROM sennard.input_source_receipts WHERE event_id = ?", messageId).get("receipt_json");
            JsonNode receipt = DomainServerSupport.MAPPER.readTree(json);
            String expected = CanonicalJson.integrityToken(receipt, java.util.Base64.getUrlDecoder().decode(key));
            assertEquals(expected, receipt.path("integrityToken").asString(), "the receipt of " + messageId + " is signed with the key");
        }
    }

    /** The start use case reads first: called again (the server has started, so the key is there), it finds the key, inserts nothing and fails with nothing. */
    @Test
    void theStartUseCaseCalledAgainFindsTheKeyAndLeavesItAsItIs() {
        String key = signingKey();
        server.bean(TrustService.class).createSigningKeyIfAbsent();
        assertEquals(1, signingKeyRows());
        assertEquals(key, signingKey());
    }

    @Test
    void aRecallAndAClaimCannotBothWin() throws Exception {
        String target = newSession("claude");
        String senderSession = newSession("codex");
        String senderRef = refOf("codex", senderSession);
        Map<String, Object> meta = SessionServerSupport.meta("windows", "codex", senderSession, "none");
        int claimedRounds = 0;
        int recalledRounds = 0;
        try (McpSyncClient client = DomainServerSupport.client(server.port, "codex")) {
            for (int round = 0; round < ROUNDS * 2; round += 1) {
                String body = "recall-" + UUID.randomUUID();
                String messageId = sendMessage(senderRef, target, body);
                // Both sides arrive over HTTP, so neither is favoured by a shorter path.
                List<Callable<Object>> racers = List.of(() -> claimThroughHook(target),
                        () -> DomainServerSupport.call(client, "recall_session_message", DomainServerSupport.arguments("messageId", messageId), meta).path("data"));
                List<Object> results = race(racers);
                boolean claimed = ((String) results.get(0)).contains(body);
                boolean recalled = ((JsonNode) results.get(1)).path("recalled").booleanValue();
                assertTrue(claimed ^ recalled, "exactly one wins in round " + round + ": claimed=" + claimed + " recalled=" + recalled + " " + results.get(1));
                if (claimed) {
                    assertEquals("DELIVERED", ((JsonNode) results.get(1)).path("reason").asString(), results.get(1).toString());
                    claimedRounds += 1;
                } else {
                    recalledRounds += 1;
                }
            }
        }
        LOG.info("recall race: claimed {}, recalled {}", claimedRounds, recalledRounds);
    }

    /** The order the race may not happen to produce: which side wins each round is up to the scheduler. */
    @Test
    void aMessageAlreadyClaimedCannotBeRecalled() throws Exception {
        String target = newSession("claude");
        String senderRef = refOf("codex", newSession("codex"));
        String messageId = sendMessage(senderRef, target, "taken");
        assertTrue(claimThroughHook(target).contains(BEGIN));
        SessionMessageService messages = server.bean(SessionMessageService.class);
        SessionMessageService.RecallView recall = messages.recall(senderRef, messageId, OperationKeys.operation("recall_session_message", senderRef));
        assertFalse(recall.recalled(), recall.toString());
        assertEquals(RecallRefusal.DELIVERED, recall.reason(), recall.toString());
    }

    @Test
    void aLapsedClaimIsGivenAgainUnderANewReceiptAndTheOldOneNoLongerAcknowledges() throws Exception {
        String target = newSession("claude");
        String senderRef = refOf("codex", newSession("codex"));
        String targetRef = refOf("claude-code", target);
        String messageId = sendMessage(senderRef, target, "again");
        JsonNode first = content(claimThroughHook(target));
        assertEquals(1, first.path("receipt").path("deliveryAttempt").asInt());
        String oldReceipt = first.path("receipt").path("claimReceipt").asString();

        // The lease runs out by the row, not by the clock of this test.
        SessionServerSupport.update(server, "UPDATE sennard.messages SET claim_until = TIMESTAMP '2000-01-01 00:00:00' WHERE message_id = ?", messageId);
        JsonNode second = content(claimThroughHook(target));
        assertEquals(2, second.path("receipt").path("deliveryAttempt").asInt());
        String newReceipt = second.path("receipt").path("claimReceipt").asString();
        assertFalse(newReceipt.equals(oldReceipt));
        assertEquals(first.path("receipt").path("sourceReceiptId").asString(), second.path("receipt").path("sourceReceiptId").asString(), "the same message keeps its source receipt");
        assertEquals(first.path("receipt").path("firstDeliveredAt").asString(), second.path("receipt").path("firstDeliveredAt").asString());

        SessionMessageService messages = server.bean(SessionMessageService.class);
        assertEquals(0, messages.acknowledge(targetRef, List.of(messageId), List.of(new ClaimReceipt(messageId, oldReceipt)), OperationKeys.operation("acknowledge_session_messages", targetRef)).acknowledged());
        assertEquals(1, messages.acknowledge(targetRef, List.of(messageId), List.of(new ClaimReceipt(messageId, newReceipt)), OperationKeys.operation("acknowledge_session_messages", targetRef)).acknowledged());
        assertFalse(claimThroughHook(target).contains(BEGIN), "an acknowledged message is not given again");
    }

    @Test
    void acknowledgementsOfOneMessageCountOnce() throws Exception {
        String target = newSession("claude");
        String senderRef = refOf("codex", newSession("codex"));
        String targetRef = refOf("claude-code", target);
        String messageId = sendMessage(senderRef, target, "ack");
        String receipt = content(claimThroughHook(target)).path("receipt").path("claimReceipt").asString();
        SessionMessageService messages = server.bean(SessionMessageService.class);
        List<Callable<Integer>> racers = new ArrayList<>();
        for (int racer = 0; racer < RACERS; racer += 1) {
            racers.add(() -> messages.acknowledge(targetRef, List.of(messageId), List.of(new ClaimReceipt(messageId, receipt)), OperationKeys.operation("acknowledge_session_messages", targetRef)).acknowledged());
        }
        assertEquals(1, race(racers).stream().mapToInt(Integer::intValue).sum());
    }

    private static JsonNode content(String response) {
        String context = DomainServerSupport.MAPPER.readTree(response).path("hookSpecificOutput").path("additionalContext").asString();
        assertTrue(context.contains(BEGIN), response);
        int start = context.indexOf('{', context.indexOf(BEGIN));
        return DomainServerSupport.MAPPER.readTree(context.substring(start, context.indexOf("\n", start)));
    }
}
