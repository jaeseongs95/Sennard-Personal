// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.time.Instant;

/** A draft the server issued an ID for: it has no effect on the queue until it is sent, and it lapses at {@code expiresAt}. */
public record Prepared(String messageId, Instant preparedAt, Instant expiresAt) {
}
