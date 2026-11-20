// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

import java.time.Instant;

/** The receipt of a sent message. {@code duplicate} is true when the same ID had been sent before and this is its first receipt again. */
public record Sent(String messageId, Instant createdAt, Instant expiresAt, boolean duplicate) {
}
