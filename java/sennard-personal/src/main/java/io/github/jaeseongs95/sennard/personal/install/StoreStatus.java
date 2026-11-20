// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;

/**
 * What the server's {@code /actuator/sennard} says about its store, read by the commands. The server writes more (when it started, when to ask again, the attempt, the
 * detail); a command needs the phase and, after a failure, the reason.
 *
 * @param phase how far the preparation is
 * @param reason why it failed, null unless it did
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreStatus(StorePhase phase, String reason) {
}
