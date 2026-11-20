// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/** Whether a registered passkey may still sign. A revoked key stays in the registry: its earlier signatures are checked against it. */
public enum PasskeyState {
    ACTIVE, REVOKED
}
