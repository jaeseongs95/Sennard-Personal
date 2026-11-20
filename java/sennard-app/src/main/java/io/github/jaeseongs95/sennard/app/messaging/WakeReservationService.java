// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation.State;

/** Each CAS commits before the caller may invoke a host. First-row unique conflicts are surfaced; nobody retries an uncertain attempt. */
@Service
public class WakeReservationService {
    private final WakeReservationRepository repository;
    private final Clock clock;

    public WakeReservationService(WakeReservationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Optional<WakeReservation> reserve(String ref, long generation, String serverId) {
        Instant now = clock.instant();
        if (repository.findById(ref).isEmpty()) {
            repository.save(new WakeReservation(ref, generation, UUID.randomUUID().toString(), serverId, State.READY, now, now, null));
        }
        String nonce = UUID.randomUUID().toString();
        if (repository.reserve(ref, generation, nonce, serverId, now) != 1) {
            return Optional.empty();
        }
        return Optional.of(new WakeReservation(ref, generation, nonce, serverId, State.RESERVED, now, now, null));
    }

    @Transactional
    public boolean start(WakeReservation reservation) {
        return repository.start(reservation.sessionRef(), reservation.generation(), reservation.nonce(), clock.instant()) == 1;
    }

    @Transactional
    public void finish(WakeReservation reservation, State state) {
        if (state != State.SUBMITTED && state != State.UNKNOWN && state != State.NOT_SENT) {
            throw new IllegalArgumentException("Not a dispatch outcome: " + state);
        }
        Instant now = clock.instant();
        repository.finish(reservation.sessionRef(), reservation.generation(), reservation.nonce(), state.name(), now, now.plusSeconds(5));
    }

    @Transactional
    public void cancelReserved(String ref, long generation) {
        repository.cancelReserved(ref, generation, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<WakeReservation> status(String ref) {
        return repository.findById(ref);
    }

    /** Joins a hook's source-receipt transaction: a failed claim rolls this consumption back too. */
    @Transactional
    public boolean consume(String ref, long generation, String nonce, String serverId) {
        return repository.consume(ref, generation, nonce, serverId, clock.instant()) == 1;
    }

    @Transactional
    public void recover(String ref, long generation, String serverId) {
        repository.recover(ref, generation, serverId, clock.instant());
    }

    @Transactional
    public void bodyObserved(String ref) {
        repository.bodyObserved(ref, clock.instant());
    }

    @Transactional
    public void nativeActivity(String ref, long generation) {
        Instant now = clock.instant();
        repository.nativeActivity(ref, generation, now, now.minusSeconds(70 * 60));
    }
}
