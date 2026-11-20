// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The peer wait snapshots (the {@code Repository} marker: only what is declared here is offered; see {@link MessageRepository}). */
public interface PeerWaitSnapshotRepository extends Repository<PeerWaitSnapshot, String> {
    Optional<PeerWaitSnapshot> findById(String queryKey);

    /** The INSERT of a query's first snapshot (the version is null). */
    PeerWaitSnapshot save(PeerWaitSnapshot snapshot);

    /** The query's snapshot is replaced in one statement; 0 when the query has none yet. */
    @Modifying
    @Query("UPDATE sennard.peer_wait_snapshots SET owner_key = :ownerKey, fingerprint = :fingerprint, expires_at = :expiresAt, version = version + 1 WHERE query_key = :queryKey")
    int replace(@Param("queryKey") String queryKey, @Param("ownerKey") String ownerKey, @Param("fingerprint") String fingerprint, @Param("expiresAt") Instant expiresAt);

    /** Every snapshot, the one that lapses last first. */
    List<PeerWaitSnapshot> findAllByOrderByExpiresAtDesc();

    /** Snapshots that lapsed at or before {@code at}; returns how many went. */
    long deleteByExpiresAtLessThanEqual(Instant at);

    long deleteByQueryKey(String queryKey);

    /** The snapshots one owner made; returns how many went. */
    long deleteByOwnerKey(String ownerKey);
}
