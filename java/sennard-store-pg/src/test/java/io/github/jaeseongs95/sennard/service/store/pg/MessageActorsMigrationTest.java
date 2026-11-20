// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.messaging.MessagingService;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimedMessage;
import io.github.jaeseongs95.sennard.domain.messaging.MessageState;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;
import io.github.jaeseongs95.sennard.service.migration.MigrationScript;

@BusinessDbTest
class MessageActorsMigrationTest {
    @Test
    void v25PreservesAndDeliversExistingDraftsAndQueuedMessages() throws Exception {
        BusinessDb.dropBusinessSchemas();
        var config = BusinessDb.config();
        var runner = new MigrationRunner(Clock.systemUTC(), BusinessDb.dataSources());
        List<MigrationScript> all = MigrationScript.load("db/expand");
        assertEquals(24, runner.run(config, MigrationRunner.Phase.EXPAND, all.stream().filter(script -> script.version() <= 24).toList()));
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO sennard.session_ref (session_ref, host, host_session_digest, pepper_version, created_at, last_seen_at, sender_drafts, sender_receipts, pending_count)
                    VALUES ('old-sender', 'codex', 'old-sender-digest', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 1, 0),
                           ('old-target', 'codex', 'old-target-digest', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 1)
                    """);
            statement.execute("""
                    INSERT INTO sennard.prepared_messages (message_id, sender_ref, target_ref, body, ttl_seconds, prepared_at, expires_at, state, sent_at, message_expires_at, record_bytes, version)
                    VALUES ('legacy-draft', 'old-sender', 'old-target', 'old draft', 3600, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:15:00',
                            'PREPARED', NULL, NULL, 128, 0),
                           ('legacy-queued', 'old-sender', 'old-target', NULL, 3600, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 02:00:00',
                            'SUBMITTED', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 01:00:00', 128, 1)
                    """);
            statement.execute("""
                    INSERT INTO sennard.messages (message_id, sender_ref, sender_host, target_ref, seq, body, body_bytes, created_at, expires_at, version)
                    VALUES ('legacy-queued', 'old-sender', 'codex', 'old-target', 7, 'old queue body', 14, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 01:00:00', 0)
                    """);
        }

        List<MigrationScript> throughV25 = all.stream().filter(script -> script.version() <= 25).toList();
        assertEquals(1, runner.run(config, MigrationRunner.Phase.EXPAND, throughV25));
        assertEquals(all.size() - throughV25.size(), runner.run(config, MigrationRunner.Phase.EXPAND, all));
        assertEquals(0, runner.run(config, MigrationRunner.Phase.EXPAND, all));
        try (RewrittenAreasContext context = RewrittenAreasContext.appConnection(Instant.parse("2026-01-01T00:01:00Z"))) {
            MessagingService service = context.bean(MessagingService.class);
            assertEquals(MessageState.PREPARED, service.status("old-sender", "legacy-draft").orElseThrow().state());
            assertEquals(MessageState.QUEUED, service.status("old-sender", "legacy-queued").orElseThrow().state());
            Sent prior = service.send("old-sender", "codex", "legacy-queued", Operations.fresh("send_session_message", "old-sender"), Sent.class, Function.identity()).value();
            assertTrue(prior.duplicate());
            assertEquals(Instant.parse("2026-01-01T01:00:00Z"), prior.expiresAt());
            service.send("old-sender", "codex", "legacy-draft", Operations.fresh("send_session_message", "old-sender"), Sent.class, Function.identity());
            List<ClaimedMessage> delivered = service.claim("old-target", null, null);
            assertEquals(List.of("legacy-queued", "legacy-draft"), delivered.stream().map(ClaimedMessage::messageId).toList());
            assertEquals(List.of("old queue body", "old draft"), delivered.stream().map(ClaimedMessage::body).toList());
            assertTrue(delivered.stream().allMatch(row -> row.senderActorId().isEmpty() && row.recipientActorId().isEmpty()));
        }
    }
}
