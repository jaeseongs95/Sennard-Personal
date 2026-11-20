// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.time.Instant;

/** The snapshot a peer wait policy keeps per query: who asked ({@code owner}), the fingerprint it saw, and until when the snapshot counts. */
public record PeerWaitRecord(String owner, String fingerprint, Instant expiresAt) {
}
