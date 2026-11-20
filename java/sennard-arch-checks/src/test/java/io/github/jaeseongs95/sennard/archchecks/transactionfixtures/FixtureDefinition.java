// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for {@code DefaultTransactionDefinition}: a transaction definition whose propagation is set by a number. */
class FixtureDefinition {
    private int propagation;

    FixtureDefinition() {
    }

    FixtureDefinition(int propagation) {
        this.propagation = propagation;
    }

    void setPropagationBehavior(int propagation) {
        this.propagation = propagation;
    }

    int propagation() {
        return propagation;
    }
}
