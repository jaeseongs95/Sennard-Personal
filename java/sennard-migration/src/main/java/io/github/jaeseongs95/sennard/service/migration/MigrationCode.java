// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.migration;

/**
 * Why a {@link MigrationException} stopped a run. The meaning is the same in every product that applies migrations; an adapter turns it into the code of its own failure type
 * (an exhaustive {@code switch}, so a constant added here is a compile error there until it is mapped).
 */
public enum MigrationCode {
    /** A script that was applied is not the file it was: its digest differs from the history. Nothing was applied by the run. */
    CHANGED,
    /** The history has applied a number whose script is not in the jar. */
    MISSING,
    /** A script that is not applied has a number below the highest applied one. */
    OUT_OF_ORDER,
    /** The numbers of the scripts are not 1, 2, 3 ... one after the other: a number is left out (or used twice). */
    GAP,
    /** A script failed, another runner failed on the same number, or the runner could not read what it needs from the database. */
    FAILED
}
