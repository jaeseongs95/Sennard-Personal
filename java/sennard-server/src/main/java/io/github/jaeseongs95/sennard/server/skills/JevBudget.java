// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DispatchState;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Usage;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.JevUsageStore;

/**
 * The signed JEV budget. A call (each try of a retried call too) is reserved at its ceiling before it is sent and released when it is over. A JEV response carries no billed
 * amount, so an answered call counts as an estimate from its tokens and is never reported as actual spending.
 */
@Component
public class JevBudget {
    private static final Logger LOG = LoggerFactory.getLogger(JevBudget.class);

    /** {@code estimatedUsd} is from the tokens answered calls reported at the provider's published price (an answer carries no billed amount); {@code reservedUsd} is what calls in flight hold. */
    public record UsageView(double reservedUsd, double estimatedUsd, long calls) {
    }

    private final JevUsageService usage;
    private final JevProviders providers;
    /** The calls reserved and not yet settled; every access is synchronized on the map. */
    private final Map<String, Double> reservations = new HashMap<>();

    private final Clock clock;

    public JevBudget(JevUsageService usage, JevProviders providers, Clock clock) {
        this.clock = clock;
        this.usage = usage;
        this.providers = providers;
    }

    /** ceiling: another process's call still in flight is not seen until it settles; at most one call each can overshoot. */
    public boolean reserve(Profile profile, String reservationId, Double limitUsd) {
        if (limitUsd == null || !Double.isFinite(limitUsd) || limitUsd < 0) {
            return false;
        }
        double shared;
        try {
            shared = usage.totals().estimatedUsd();
        } catch (DataAccessException | TransactionException unreadable) {
            LOG.warn("The JEV usage total could not be read, so the budget cannot be shown to cover the call", unreadable);
            return false;
        }
        Map<String, Double> held = reservations;
        synchronized (held) {
            double reserved = held.values().stream().mapToDouble(Double::doubleValue).sum();
            if (held.containsKey(reservationId) || held.size() >= 2048 || shared + reserved + profile.maximumCostUsd() > limitUsd) {
                return false;
            }
            held.put(reservationId, profile.maximumCostUsd());
            return true;
        }
    }

    /** Gives a reservation back; a reservation that is not held (already released) is left as it is. The caller releases in a {@code finally}, so no way a call ends can keep its ceiling. */
    public void release(String reservationId) {
        Map<String, Double> held = reservations;
        synchronized (held) {
            held.remove(reservationId);
        }
    }

    /**
     * Ends a reservation, whatever the call came to: the reservation covers only the time the call is in flight, so it is released when the call is over (not sent, failed, cut
     * off, answered wrongly or answered). What a call that was answered used is then recorded from the tokens its answer reported; a call that got no answer reported none, so
     * nothing is recorded for it, and the reservation does not stay to stand for it (a reservation that stays only blocks the budget until the server restarts). A failure to
     * record the usage of an answered call is not swallowed: it propagates (the reservation is already released).
     */
    public void settle(String reservationId, DispatchState dispatchState, Profile profile, Usage call) {
        release(reservationId);
        if (dispatchState == DispatchState.NOT_STARTED || call.inputTokens() == null) {
            return;
        }
        JevProvider provider = providers.of(profile).orElseThrow(() -> new IllegalStateException("No JEV provider for the profile " + profile.profileId()));
        // A failure to record is raised, not logged: a call missing from the total would make every later reserve see a larger budget than there is. The caller is outside any
        // transaction (SkillClassificationService.Tries.execute), and the write is the one transaction of JevUsageService.add, so nothing of the caller's is rolled back by it.
        usage.add(clock.instant(), profile.modelId(), call.inputTokens(), call.outputTokens() == null ? 0 : call.outputTokens(), provider.estimateUsd(call.inputTokens()));
    }

    /** What the signed budget is measured against: the recorded estimate of the answered calls (all Sennard servers of this PC), and the calls in flight on this server. Empty when the totals cannot be read. */
    public Optional<UsageView> view() {
        try {
            JevUsageStore.Totals totals = usage.totals();
            Map<String, Double> held = reservations;
            double reserved;
            synchronized (held) {
                reserved = held.values().stream().mapToDouble(Double::doubleValue).sum();
            }
            return Optional.of(new UsageView(reserved, totals.estimatedUsd(), totals.calls()));
        } catch (DataAccessException | TransactionException unreadable) {
            LOG.warn("The JEV usage total could not be read", unreadable);
            return Optional.empty();
        }
    }
}
