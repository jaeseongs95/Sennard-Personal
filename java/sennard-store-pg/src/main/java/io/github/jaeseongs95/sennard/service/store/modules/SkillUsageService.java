// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases of the skill usage record: each method is one transaction, and {@link SkillUsageStore} (the repository) only joins it. A call that has an id is written once: the
 * id's row is the first thing the write inserts, so two records of one call at the same moment meet its primary key, and the second ends in the translated unique violation before it
 * wrote anything. That is a limit that is accepted: nothing catches it to go on and nothing runs the record again.
 */
@Service
public class SkillUsageService {
    private final SkillUsageStore usage;

    public SkillUsageService(SkillUsageStore usage) {
        this.usage = usage;
    }

    /** One call, deduplicated by the host's call id; the rows older than the retention are dropped on the same write. */
    @Transactional
    public void record(SkillUsageStore.Call call) {
        usage.record(call);
    }

    /** The skills this session was seen calling, at any time. */
    @Transactional(readOnly = true)
    public List<String> calledInSession(String sessionId) {
        return usage.calledInSession(sessionId);
    }

    /** Calls of the last {@code days} days. */
    @Transactional(readOnly = true)
    public SkillUsageStore.Summary summary(int days, Instant now) {
        return usage.summary(days, now);
    }
}
