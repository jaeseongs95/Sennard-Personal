// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The use case of the workflow statistics: one read-only transaction around {@link WorkflowStatsStore} (the repository only joins it). */
@Service
public class WorkflowStatsService {
    private final WorkflowStatsStore stats;

    public WorkflowStatsService(WorkflowStatsStore stats) {
        this.stats = stats;
    }

    /** The counts by kind and by skill of the runs created in the last {@code days} days, and the {@code recentLimit} newest of them. */
    @Transactional(readOnly = true)
    public WorkflowStatsStore.Stats stats(int days, Instant now, int recentLimit) {
        return stats.stats(days, now, recentLimit);
    }
}
