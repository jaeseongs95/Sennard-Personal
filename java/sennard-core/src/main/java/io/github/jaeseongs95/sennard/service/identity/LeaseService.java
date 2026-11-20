// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.session.LeaseOutcome;

/**
 * {@code sessions.renew}: renews the leases of owned generations, one {@code UPDATE} per item, in session order so two batches never take the rows in opposite orders and deadlock
 * (I13). The update holds the generation's row for the rest of the transaction, which is the lock. The lease is judged and extended on the service's clock only. An item that was not
 * renewed is reported with its state.
 */
@Service
public class LeaseService {
    /** One item of a renewal request. */
    public record Item(String sessionRef, long generation, UUID relayId) {
    }

    /** @param state what the renewal did; leaseUntil and pending only for {@link LeaseOutcome#RENEWED} */
    public record ItemResult(String sessionRef, long generation, LeaseOutcome state, Instant leaseUntil, Integer pending) {
    }

    public record Renewal(Instant serverNow, List<ItemResult> items) {
    }

    /** A renewed generation: what {@link #RENEWED} reads back, the lease as a TIMESTAMP value. */
    private record Renewed(LocalDateTime leaseUntil, int pending) {
    }

    /** What the table holds about the generation of an item that was not renewed. */
    private record Held(String relayId, LocalDateTime endedAt, LocalDateTime supersededAt) {
        LeaseOutcome outcome(UUID asked) {
            if (endedAt != null) {
                return LeaseOutcome.ENDED; // The generation ended: the client registers again.
            }
            if (supersededAt != null) {
                return LeaseOutcome.SUPERSEDED;
            }
            if (relayId == null || asked == null || !relayId.equals(asked.toString())) {
                return LeaseOutcome.FENCED;
            }
            return LeaseOutcome.EXPIRED;
        }
    }

    /** Renews the live, owned generation of this relay whose lease has not run out. */
    private static final String RENEW = "UPDATE sennard.session_generation SET lease_until = CASE WHEN lease_until > ? THEN lease_until ELSE ? END, heartbeat_at = ? "
            + "WHERE session_ref = ? AND generation = ? AND relay_id = ? AND ended_at IS NULL AND superseded_at IS NULL AND lease_until > ?";
    private static final String RENEWED = "SELECT g.lease_until, r.pending_count AS pending FROM sennard.session_generation g "
            + "JOIN sennard.session_ref r ON r.session_ref = g.session_ref WHERE g.session_ref = ? AND g.generation = ?";
    private static final String HELD = "SELECT relay_id, ended_at, superseded_at FROM sennard.session_generation WHERE session_ref = ? AND generation = ?";

    private final JdbcClient jdbc;
    private final LeasePolicy policy;
    private final Clock clock;

    public LeaseService(@Qualifier("storeJdbc") JdbcClient jdbc, LeasePolicy policy, Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public Renewal renew(List<Item> items) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime lease = Timestamps.toTimestamp(now.plusMillis(policy.leaseMs()));
        LocalDateTime at = Timestamps.toTimestamp(now);
        // One item per generation and relay, in the order of the session references: the rows are locked in that order.
        Map<String, Item> ordered = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparing(Item::sessionRef).thenComparingLong(Item::generation))
                .forEach(item -> ordered.putIfAbsent(item.sessionRef() + "#" + item.generation() + "#" + item.relayId(), item));
        List<ItemResult> results = new ArrayList<>();
        for (Item item : ordered.values()) {
            results.add(renewed(item, lease, at));
        }
        return new Renewal(now, results);
    }

    private ItemResult renewed(Item item, LocalDateTime lease, LocalDateTime at) {
        int changed = item.relayId() == null ? 0
                : jdbc.sql(RENEW).params(lease, lease, at, item.sessionRef(), item.generation(), item.relayId().toString(), at).update();
        if (changed == 1) {
            Renewed row = jdbc.sql(RENEWED).params(item.sessionRef(), item.generation()).query(Renewed.class).single();
            return new ItemResult(item.sessionRef(), item.generation(), LeaseOutcome.RENEWED, Timestamps.toInstant(row.leaseUntil()), row.pending());
        }
        LeaseOutcome outcome = jdbc.sql(HELD).params(item.sessionRef(), item.generation()).query(Held.class).optional()
                .map(held -> held.outcome(item.relayId())).orElse(LeaseOutcome.ENDED); // no such generation: the client registers again
        return new ItemResult(item.sessionRef(), item.generation(), outcome, null, null);
    }
}
