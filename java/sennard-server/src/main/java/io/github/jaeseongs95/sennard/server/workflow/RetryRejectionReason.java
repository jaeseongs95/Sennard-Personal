// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

/** Why a convergence root refused another attempt, recorded in its {@code retryRejections}: the same failure fingerprint came back, or no evidence moved past the old one. The root contract spells the wire text. */
enum RetryRejectionReason {
    STALE_FINGERPRINT("stale-fingerprint"),
    STALE_EVIDENCE("stale-evidence");

    private final String wire;

    RetryRejectionReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    String wire() {
        return wire;
    }
}
