// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A service that cuts a use case with a second connection or a savepoint, by the annotation and by hand: four findings. */
@FixtureService
final class PropagationBad {
    @FixtureTransactional(propagation = FixturePropagation.REQUIRES_NEW)
    int secondConnection() {
        return 1;
    }

    @FixtureTransactional(propagation = FixturePropagation.NESTED)
    int savepoint() {
        return 2;
    }

    int byHand() {
        FixtureDefinition definition = new FixtureDefinition(6);
        definition.setPropagationBehavior(3);
        return definition.propagation();
    }
}
