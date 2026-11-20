// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * How Sennard came to know who a caller is. {@code HOST_OBSERVED} means the host supplied an identity the caller cannot forge; {@code UNAVAILABLE}
 * means Sennard has only what the caller said. Written in the stage result's and the review's observed caller by the constant's name.
 */
public enum Provenance {
    HOST_OBSERVED,
    UNAVAILABLE
}
