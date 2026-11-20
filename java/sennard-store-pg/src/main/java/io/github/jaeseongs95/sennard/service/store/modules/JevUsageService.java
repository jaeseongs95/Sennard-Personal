// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases of the JEV usage counters: each method is one transaction, and {@link JevUsageStore} (the repository) only joins it. A call is counted by {@link #add}: one UPDATE
 * that counts up in SQL, and, when that changed no row (the first call of a day and a model), the INSERT of the day's row with this call counted, in the same transaction. Two first
 * calls of a day and a model at the same moment meet the primary key; the second one ends in the translated unique violation, which is a limit that is accepted: nothing catches it
 * to go on and nothing runs the call again.
 */
@Service
public class JevUsageService {
    private final JevUsageStore usage;

    public JevUsageService(JevUsageStore usage) {
        this.usage = usage;
    }

    /** Counts one call into the day's row of the model, and writes the row when it is not there yet. */
    @Transactional
    public void add(Instant at, String modelId, long inputTokens, long outputTokens, double estimatedUsd) {
        if (usage.add(at, modelId, inputTokens, outputTokens, estimatedUsd) == 0) {
            usage.insertFirst(at, modelId, inputTokens, outputTokens, estimatedUsd);
        }
    }

    /** Every call recorded so far, zero when none. */
    @Transactional(readOnly = true)
    public JevUsageStore.Totals totals() {
        return usage.totals();
    }
}
