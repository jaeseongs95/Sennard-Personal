// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * The state of an agent's proposed change to the signed context ({@code admin_proposals.state}): {@code PENDING}, then the user's signature
 * {@code APPLIED} it, or a newer proposal, a reset or the user {@code REJECTED} it. The column holds the constant's name.
 */
public enum ProposalState {
    PENDING,
    REJECTED,
    APPLIED
}
