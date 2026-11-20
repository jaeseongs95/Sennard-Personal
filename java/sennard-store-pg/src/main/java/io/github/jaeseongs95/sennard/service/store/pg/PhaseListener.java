// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

/** Told when the preparation enters a phase; runs on the preparing thread, so it must not block. */
@FunctionalInterface
public interface PhaseListener {
    PhaseListener NONE = phase -> {
    };

    void entered(StorePhase phase);
}
