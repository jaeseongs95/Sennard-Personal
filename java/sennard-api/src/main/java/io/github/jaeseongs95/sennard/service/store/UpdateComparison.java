// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * How the installed plugin compares with the latest stable release ({@code plugin_update_state.comparison}, holding the constant's name; the
 * plugin update status contract spells the same names). {@code UNKNOWN} means no release has been read yet.
 */
public enum UpdateComparison {
    UNKNOWN,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    AHEAD_OF_STABLE;

    /** The comparison a version order gives: negative when the installed version is older than the latest. */
    public static UpdateComparison ofOrder(int installedVersusLatest) {
        return installedVersusLatest < 0 ? UPDATE_AVAILABLE : installedVersusLatest > 0 ? AHEAD_OF_STABLE : UP_TO_DATE;
    }
}
