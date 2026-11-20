// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.session.HookSessions;

/** Once the store is open after a restart, watches the host processes of the sessions that were live and ends the ones whose host went away meanwhile. */
@Component
class SessionRestoreTask implements StoreReadyTask {
    private final HookSessions sessions;

    SessionRestoreTask(HookSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void whenStoreReady() {
        sessions.restoreAfterStart();
    }
}
