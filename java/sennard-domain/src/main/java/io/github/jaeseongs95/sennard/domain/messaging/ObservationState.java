// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/**
 * What the last input observed on a session means for the next tool-boundary claim ({@code input_observations.state}, stored by the constant's name, a CHECK
 * lists them). A host that cannot take context at a prompt gives the model a prompt first; the message claim at the first tool boundary after it is
 * skipped once, so the model is not handed a message and a prompt at the same moment.
 */
public enum ObservationState {
    /** A native input was seen and the next deferred claim at a tool boundary is skipped (once). */
    DEFERRED,
    /** Nothing is pending: the skip was used, or a wake claim saw the input. */
    SETTLED
}
