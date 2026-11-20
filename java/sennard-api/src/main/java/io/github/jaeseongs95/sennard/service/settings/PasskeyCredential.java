// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.time.Instant;

/**
 * A registered passkey as the registry holds it. {@code revokedAt} is null while the key is {@link PasskeyState#ACTIVE}.
 */
public record PasskeyCredential(PasskeyRegistration registration, PasskeyState state, Instant registeredAt, Instant revokedAt) {
    public PasskeyCredential {
        SettingsChecks.required("registration", registration);
        SettingsChecks.required("state", state);
        SettingsChecks.required("registeredAt", registeredAt);
        if ((state == PasskeyState.REVOKED) != (revokedAt != null)) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, "revokedAt is set exactly when the passkey is revoked.");
        }
    }
}
