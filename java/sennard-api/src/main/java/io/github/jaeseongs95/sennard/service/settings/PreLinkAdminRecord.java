// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.time.Instant;

/** A {@link PreLinkAdminDraft} as stored: its sequence number (the order of the log) and when it was recorded. */
public record PreLinkAdminRecord(long seq, PreLinkAdminDraft draft, Instant createdAt) {
    public PreLinkAdminRecord {
        SettingsChecks.required("draft", draft);
        SettingsChecks.required("createdAt", createdAt);
    }
}
