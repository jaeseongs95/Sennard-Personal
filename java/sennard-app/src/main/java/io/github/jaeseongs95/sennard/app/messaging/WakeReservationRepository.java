// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WakeReservationRepository extends Repository<WakeReservation, String> {
    String LIVE = "EXISTS (SELECT 1 FROM sennard.active_generation a JOIN sennard.session_generation g ON g.session_ref = a.session_ref AND g.generation = a.generation "
            + "LEFT JOIN sennard.managed_child_identity c ON c.child_ref = :ref "
            + "WHERE a.session_ref = COALESCE(c.root_ref, :ref) AND a.generation = :generation "
            + "AND COALESCE(c.root_generation, :generation) = :generation AND g.ended_at IS NULL AND g.superseded_at IS NULL AND g.lease_until > :now)";

    Optional<WakeReservation> findById(String ref);
    WakeReservation save(WakeReservation reservation);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET generation = :generation, nonce = :nonce, server_id = :serverId, state = 'RESERVED', updated_at = :now, retry_after = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND (generation <> :generation OR (state IN ('READY','OBSERVED','NOT_SENT','CANCELLED') AND retry_after <= :now)) AND " + LIVE)
    int reserve(@Param("ref") String ref, @Param("generation") long generation, @Param("nonce") String nonce, @Param("serverId") String serverId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = 'STARTED', updated_at = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND generation = :generation AND nonce = :nonce AND state = 'RESERVED' AND " + LIVE)
    int start(@Param("ref") String ref, @Param("generation") long generation, @Param("nonce") String nonce, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = :state, updated_at = :now, retry_after = :retryAfter, version = version + 1 "
            + "WHERE session_ref = :ref AND generation = :generation AND nonce = :nonce AND state = 'STARTED'")
    int finish(@Param("ref") String ref, @Param("generation") long generation, @Param("nonce") String nonce, @Param("state") String state,
            @Param("now") Instant now, @Param("retryAfter") Instant retryAfter);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = 'OBSERVED', updated_at = :now, retry_after = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND generation = :generation AND nonce = :nonce AND server_id = :serverId AND state IN ('STARTED','SUBMITTED','UNKNOWN') AND " + LIVE)
    int consume(@Param("ref") String ref, @Param("generation") long generation, @Param("nonce") String nonce, @Param("serverId") String serverId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = 'CANCELLED', updated_at = :now, version = version + 1 WHERE session_ref = :ref AND generation = :generation AND state = 'RESERVED'")
    int cancelReserved(@Param("ref") String ref, @Param("generation") long generation, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = CASE WHEN state = 'RESERVED' THEN 'CANCELLED' ELSE 'UNKNOWN' END, updated_at = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND generation = :generation AND server_id <> :serverId AND state IN ('RESERVED','STARTED') AND " + LIVE)
    int recover(@Param("ref") String ref, @Param("generation") long generation, @Param("serverId") String serverId, @Param("now") Instant now);

    /** A real body claim/ACK rearms the doorbell; syntactic marker interception still rejects every retired nonce. */
    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = 'OBSERVED', updated_at = :now, retry_after = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND state IN ('STARTED','SUBMITTED','UNKNOWN')")
    int bodyObserved(@Param("ref") String ref, @Param("now") Instant now);

    /** Fresh activity after the old attempt's lifetime and grace; elapsed time alone never rearms it. */
    @Modifying
    @Query("UPDATE sennard.native_wake_reservations SET state = 'OBSERVED', updated_at = :now, retry_after = :now, version = version + 1 "
            + "WHERE session_ref = :ref AND generation = :generation AND state IN ('STARTED','SUBMITTED','UNKNOWN') AND updated_at < :olderThan")
    int nativeActivity(@Param("ref") String ref, @Param("generation") long generation, @Param("now") Instant now, @Param("olderThan") Instant olderThan);
}
