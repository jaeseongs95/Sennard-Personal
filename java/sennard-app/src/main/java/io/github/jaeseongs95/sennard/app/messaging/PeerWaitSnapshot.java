// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** One row of {@code sennard.peer_wait_snapshots}: what a peer wait policy saw for one query, who asked, and until when it counts. */
@Table(schema = "sennard", value = "peer_wait_snapshots")
public record PeerWaitSnapshot(@Id String queryKey, String ownerKey, String fingerprint, Instant expiresAt, @Version Long version) {
}
