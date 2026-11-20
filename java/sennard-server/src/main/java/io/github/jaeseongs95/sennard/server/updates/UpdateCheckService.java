// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.time.Clock;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.sennard.server.updates.PluginUpdateService.Status;

/**
 * The check of the update state: reads what the last check left, asks the repository when the window has passed or the check is forced, and stores what the repository answered.
 * The two stores are the transactions of {@link PluginUpdateService}; the network call is between them and holds no connection.
 */
@Service
public class UpdateCheckService {
    private final PluginUpdateService updates;
    private final ReleaseSource source;
    private final Clock clock;

    UpdateCheckService(PluginUpdateService updates, ReleaseSource source, Clock clock) {
        this.updates = updates;
        this.source = source;
        this.clock = clock;
    }

    /** Asks the repository when the window has passed or when forced; this is the network call, and the only one. */
    public Status check(boolean force) {
        long now = clock.millis();
        PluginUpdateService.Due due = updates.due(force, now);
        if (!due.ask()) {
            return due.status();
        }
        String etag = due.state().etag();
        return updates.record(due.state(), etag == null ? source.ask() : source.askIfChanged(etag), now);
    }
}
