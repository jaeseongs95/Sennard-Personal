// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.session;

/**
 * What a lease renewal did for one session generation. {@code RENEWED} extended the lease; the others say why it could not, and what the client
 * does next: {@code ENDED} (no such generation, or it ended) and {@code SUPERSEDED} mean it registers again, {@code FENCED} means another relay
 * holds the generation, {@code EXPIRED} means the lease ran out before the renewal and the generation is still the client's to take again.
 */
public enum LeaseOutcome {
    RENEWED,
    ENDED,
    SUPERSEDED,
    FENCED,
    EXPIRED
}
