// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;

/**
 * A hook takes no lock of the trust area, whether a message waits for it or not: the message goes to the claimer whose conditional UPDATE changed its row, and the receipt is an
 * INSERT under its UNIQUE key. There is no module lock row to queue on any more (the table is not in the schema, which the first case reads). Nothing here waits on a clock; what is
 * judged is the answers of the hooks and the schema of the real PostgreSQL database.
 */
@Tag("business-db")
class SessionMessageLockServerTest {
    private static final String BEGIN = "[sennard peer message BEGIN]";
    private static final String CLAUDE_STORED = "claude-code";
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
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


    private static void sendMessage(String senderRef, String target, String body) {
        SessionMessageService messages = server.bean(SessionMessageService.class);
        var prepared = messages.prepare(senderRef, CLAUDE_STORED, target, body, null, OperationKeys.operation("prepare_session_message", senderRef));
        messages.send(senderRef, "codex", prepared.messageId(), OperationKeys.operation("send_session_message", senderRef));
    }

    private static String postToolUse(String session) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", "claude", "PostToolUse", null, session, body -> {
        });
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    @Test
    void thereIsNoModuleLockRowForAHookToQueueOn() {
        assertEquals(0, SessionServerSupport.count(server, "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?", "sennard", "module_lock"));
    }

    @Test
    void aPostToolUseWithNoMessageToClaimGivesNothing() throws IOException {
        String target = newSession("claude");
        for (int hook = 0; hook < 3; hook += 1) {
            assertFalse(postToolUse(target).contains(BEGIN), "no message was sent");
        }
    }

    @Test
    void aPostToolUseThatClaimsAMessageGivesItOnce() throws IOException {
        String target = newSession("claude");
        String senderRef = refOf("codex", newSession("codex"));
        String body = "lock-" + UUID.randomUUID();
        sendMessage(senderRef, target, body);
        assertTrue(postToolUse(target).contains(body), "the waiting message is given by the hook");
        assertFalse(postToolUse(target).contains(body), "a claimed message is not given again while its claim lasts");
    }

    /** What a claim does besides taking is not lost with the lock: the first tool boundary after a native input is skipped, whether or not a message waits at it. */
    @Test
    void aBoundaryWithNothingToClaimStillSettlesTheDeferralAfterANativeInput() throws IOException {
        String target = newSession("claude");
        String targetRef = refOf(CLAUDE_STORED, target);
        String senderRef = refOf("codex", newSession("codex"));
        SessionMessageService messages = server.bean(SessionMessageService.class);
        messages.observeNativeInput(targetRef);
        assertTrue(messages.claim(CLAUDE_STORED, target, targetRef, Boundary.TOOL).isEmpty(), "nothing waits at the skipped boundary");
        String body = "deferred-" + UUID.randomUUID();
        sendMessage(senderRef, target, body);
        List<Delivered> delivered = messages.claim(CLAUDE_STORED, target, targetRef, Boundary.TOOL);
        assertEquals(1, delivered.size(), "the next boundary is not skipped again: the deferral was settled by the one before it");
        assertEquals(body, delivered.getFirst().body());
    }
}
