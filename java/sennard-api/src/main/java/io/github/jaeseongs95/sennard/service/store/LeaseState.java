// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/** The state of an attempt lease ({@code convergence_leases.state}): issued once, then consumed by its attempt or expired when a newer one is issued. */
public enum LeaseState {
    ISSUED,
    CONSUMED,
    EXPIRED
}
