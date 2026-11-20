// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** What the messages between two sessions say about a peer wait: whether there is any, and a digest of their delivery state (it changes when one is claimed or acknowledged). Metadata only; a relation is neither work completion nor permission. */
public record PeerWait(boolean related, String fingerprint) {
}
