// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/** Where the repository is asked for its tags: off the request path (design B.3). The tool and the turn's notice read what this left behind. */
@Component
public class UpdateCheckSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(UpdateCheckSchedule.class);

    private final UpdateCheckService updates;
    private final boolean scheduled;

    public UpdateCheckSchedule(UpdateCheckService updates, SennardProperties properties) {
        this.updates = updates;
        this.scheduled = properties.scheduledProbes();
    }

    /** Asks only once the last answer's window has passed; a store still being prepared is simply asked again on the next round. */
    public void whenDue() {
        if (scheduled) {
            check(false);
        }
    }

    /** A caller's request to ask now. */
    @Async
    public void now() {
        check(true);
    }

    private void check(boolean force) {
        try {
            updates.check(force);
        } catch (RuntimeException failure) {
            // Boundary (scheduled and asynchronous task top): the check never throws into the scheduler; the failure is logged with its cause.
            LOG.warn("UPDATE_CHECK_NOT_RUN", failure);
        }
    }
}
