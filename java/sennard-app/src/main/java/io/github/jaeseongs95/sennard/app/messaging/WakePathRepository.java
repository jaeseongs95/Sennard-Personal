// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The claim boundaries of the session generations (the {@code Repository} marker: only what is declared here is offered; see {@link MessageRepository}). */
public interface WakePathRepository extends Repository<WakePath, WakePath.Key> {
    /** The INSERT of a generation's row (the version is null), made by the claim that finds none ({@link MessagingService}). */
    WakePath save(WakePath path);

    /**
     * A claim boundary at {@code now}: never earlier than the one recorded before, in one statement (no read of the row first). 0 when the generation has no row yet: the
     * same claim then inserts it.
     */
    @Modifying
    @Query("UPDATE sennard.wake_paths SET claim_boundary_at = CASE WHEN claim_boundary_at IS NULL OR claim_boundary_at < :now THEN :now ELSE claim_boundary_at END, "
            + "updated_at = :now, version = version + 1 WHERE session_ref = :sessionRef AND instance_id = :instanceId AND birth_generation = :birthGeneration")
    int moveBoundary(@Param("sessionRef") String sessionRef, @Param("instanceId") String instanceId, @Param("birthGeneration") String birthGeneration, @Param("now") Instant now);
}
