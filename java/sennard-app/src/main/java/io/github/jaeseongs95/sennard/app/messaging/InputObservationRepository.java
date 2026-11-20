// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The input observations of the sessions (the {@code Repository} marker: only what is declared here is offered; see {@link MessageRepository}). A session's row is made once,
 * by the first input seen on it ({@link MessagingService#observeNativeInput} or {@link MessagingService#claimWakeInput}, an INSERT after an UPDATE that changed no row) and is
 * never deleted: the state says whether a deferral is pending, and every change of it is one conditional statement below, judged by the rows it changed.
 */
public interface InputObservationRepository extends Repository<InputObservation, String> {
    /** The INSERT of the session's row (the version is null), made by the use case that finds none ({@link MessagingService}). */
    InputObservation save(InputObservation observation);

    /**
     * The deferral is used or forgotten: DEFERRED becomes SETTLED and the time stays. 1 for the one writer that took the deferral, 0 when there is nothing deferred (or another
     * writer took it).
     */
    @Modifying
    @Query("UPDATE sennard.input_observations SET state = 'SETTLED', version = version + 1 WHERE session_ref = :sessionRef AND state = 'DEFERRED'")
    int settle(@Param("sessionRef") String sessionRef);

    /** A native input was seen: DEFERRED at {@code at}. 0 when the session has no row yet: the same use case then inserts it. */
    @Modifying
    @Query("UPDATE sennard.input_observations SET state = 'DEFERRED', observed_at = :at, version = version + 1 WHERE session_ref = :sessionRef")
    int defer(@Param("sessionRef") String sessionRef, @Param("at") Instant at);

    /** An input was seen that defers nothing: only the time moves (an observation that defers keeps deferring). 0 when the session has no row yet: the same use case then inserts it. */
    @Modifying
    @Query("UPDATE sennard.input_observations SET observed_at = :at, version = version + 1 WHERE session_ref = :sessionRef")
    int seenAt(@Param("sessionRef") String sessionRef, @Param("at") Instant at);
}
