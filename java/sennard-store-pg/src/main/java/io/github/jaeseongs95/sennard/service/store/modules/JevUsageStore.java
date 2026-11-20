// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


/**
 * JEV calls per day and model, summed over every service. A call is one UPDATE statement that counts up in SQL; when it finds no row, the day's row is written by an INSERT with
 * the call counted. No module lock; both join the transaction of {@link JevUsageService}.
 */
@Repository
public class JevUsageStore extends ModuleStore {
    public JevUsageStore(@Qualifier("storeJdbc") JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * Counts one call into the day's row.
     *
     * @return the rows changed: 1, or 0 when the day's row is not there yet. The update always changes the row it finds (calls goes up by one), so the number says whether the row
     *     was there on every driver.
     */
    public int add(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
        return update("UPDATE sennard.jev_usage_daily SET calls = calls + 1, input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, estimated_usd = estimated_usd + ? "
                + "WHERE day = ? AND model_id = ?", inputTokens, outputTokens, estimatedUsd, StoreValues.ts(at.truncatedTo(ChronoUnit.DAYS)), modelId);
    }

    /** The first write of a day's row for a model, with this call counted in it; a second one for the same key is a duplicate key (the primary key). */
    public void insertFirst(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
        update("INSERT INTO sennard.jev_usage_daily (day, model_id, calls, input_tokens, output_tokens, estimated_usd) VALUES (?, ?, 1, ?, ?, ?)",
                StoreValues.ts(at.truncatedTo(ChronoUnit.DAYS)), modelId, inputTokens, outputTokens, estimatedUsd);
    }

    /** Every call recorded so far, zero when none. */
    public Totals totals() {
        return jdbc.sql("SELECT coalesce(sum(calls), 0) AS calls, coalesce(sum(estimated_usd), 0) AS estimated_usd "
                + "FROM sennard.jev_usage_daily")
                .query(Totals.class).single();
    }

    public record Totals(long calls, double estimatedUsd) {
    }
}
