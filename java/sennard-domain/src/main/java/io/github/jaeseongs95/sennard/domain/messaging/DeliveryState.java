// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/**
 * What the sender can know about the delivery of a message it submitted, once the queue row is gone and only the receipt is left. The receipt
 * does not record a claim, so the only honest answer is {@code UNKNOWN}; a recalled message has none.
 */
public enum DeliveryState {
    UNKNOWN
}
