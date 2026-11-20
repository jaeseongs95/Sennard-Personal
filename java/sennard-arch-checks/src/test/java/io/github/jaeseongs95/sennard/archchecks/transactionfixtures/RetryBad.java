// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A service whose transaction is run again when it lost: through each of the two annotations of the product that are annotated with the retry, and through the retry annotation itself. */
@FixtureService
final class RetryBad {
    @FixtureTransactional
    @FixtureRetryOnConflict
    int throughTheProductAnnotation() {
        return 1;
    }

    @FixtureTransactional
    @FixtureRetryable
    int throughTheRetryAnnotation() {
        return 2;
    }

    @FixtureTransactional
    @FixtureRetryOnLostRace
    int throughTheLostRaceAnnotation() {
        return 3;
    }
}
