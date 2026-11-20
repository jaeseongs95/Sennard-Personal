// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.time.Instant;

/** The saved link settings and when they were saved. */
public record DatabaseLink(DatabaseLinkRequest request, Instant savedAt) {
    public DatabaseLink {
        SettingsChecks.required("request", request);
        SettingsChecks.required("savedAt", savedAt);
    }
}
