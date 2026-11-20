// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.sennard.domain.messaging.ObservationState;

/**
 * One row of {@code sennard.input_observations}: what the last input seen on a session means for the next tool-boundary claim
 * ({@link ObservationState}, stored by the constant's name, a CHECK lists them). It replaces a 0/1 flag that was only ever read as one of two states.
 * The key is the session's address ({@code session_ref}).
 */
@Table(schema = "sennard", value = "input_observations")
public record InputObservation(@Id String sessionRef, ObservationState state, Instant observedAt, @Version Long version) {

}
