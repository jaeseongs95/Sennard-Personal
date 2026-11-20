// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.messaging.MessagingProperties;
import io.github.jaeseongs95.sennard.app.messaging.MessagingService;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.messaging.Prepared;
import io.github.jaeseongs95.sennard.domain.messaging.Sent;

/**
 * The messaging cases of {@link AbstractMessagingServiceTest} with this application's connection: {@link PreparingDataSource} named {@code store}, and the
 * {@link StoreTransactionManager} (the begin failures of the store are its own reasons). Besides them:
 *
 * <pre>
 * m1  start before ready  the context with the messaging beans is refreshed while the DataSource answers STORE_PREPARING (no repository asks the database at start); a call
 *                         then is an UnavailableException; after makeReady it works
 * </pre>
 */
class MessagingServiceAppConnectionTest extends AbstractMessagingServiceTest {
    @Override
    RewrittenAreasContext open(Instant start) {
        return RewrittenAreasContext.appConnection(start);
    }

    @Test
    void theContextStartsBeforeTheDatabaseIsReadyAndServesAfterwards() throws Exception {
        try (RewrittenAreasContext early = RewrittenAreasContext.appConnectionNotReady(Instant.parse("2026-01-01T00:00:00Z"))) {
            MessagingService messaging = early.bean(MessagingService.class);
            UnavailableException unavailable = assertThrows(UnavailableException.class, () -> messaging.pendingCount("ref-none"));
            assertEquals("STORE_PREPARING", unavailable.arguments().get("reason"), "the caller is told the store is preparing: " + unavailable.arguments());
            early.makeReady();
            assertEquals(0L, messaging.pendingCount("ref-none"));
        }
    }

    /**
     * The count branch of the queue limit: {@code sennard.messaging.message-limit} lowered to 2 as an operator's setting, so the queue is full by the number of
     * unacknowledged messages while their bytes and the receipts are far below their limits (with the defaults the receipts, as many as the messages, are full first).
     */
    @Test
    void theNumberOfUnacknowledgedMessagesIsTheQueueLimitWhenTheSettingLowersIt() throws Exception {
        String sender = session("claude");
        String target = session("codex");
        try (RewrittenAreasContext lowered = RewrittenAreasContext.appConnection(Instant.parse("2026-01-01T00:00:00Z"), Map.of("sennard.messaging.message-limit", 2))) {
            MessagingService messaging = lowered.bean(MessagingService.class);
            assertEquals(2, lowered.bean(MessagingProperties.class).messageLimit(), "the setting reached the properties record");
            for (int count = 0; count < 2; count++) {
                messaging.send(sender, "claude", messaging.prepare(sender, target, "a", null, Operations.fresh("prepare_session_message", sender)).messageId(), Operations.fresh("send_session_message", sender),
                        Sent.class, Function.identity());
            }
            Prepared third = messaging.prepare(sender, target, "a", null, Operations.fresh("prepare_session_message", sender));
            UnavailableException full = assertThrows(UnavailableException.class, () -> messaging.send(sender, "claude", third.messageId(), Operations.fresh("send_session_message", sender), Sent.class, Function.identity()));
            assertEquals(UnavailableCode.MESSAGE_SPOOL_FULL, full.code());
            assertEquals(2L, messaging.pendingCount(target), "the third message was not queued");
        }
    }

    /** A session, as the identity area keeps it (a fixture only: the identity area owns the table). */
    private static String session(String host) throws SQLException {
        String ref = "ref-" + UUID.randomUUID();
        try (Connection owner = BusinessDb.connect(); Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO sennard.session_ref (session_ref, host, host_session_digest, pepper_version, created_at, last_seen_at) VALUES ('" + ref + "', '" + host
                    + "', '" + Digests.sha256Hex(ref) + "', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        return ref;
    }
}
