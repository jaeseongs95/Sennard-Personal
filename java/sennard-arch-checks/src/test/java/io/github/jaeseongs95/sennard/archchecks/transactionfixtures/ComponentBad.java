// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A bean that holds a transaction for a service (not a service itself): the class is the transaction, and it takes a lock made by hand of a store through the store's own type. */
@FixtureTransactional
final class ComponentBad {
    private final FixtureStore store = new FixtureStore();

    int link() {
        store.lockModule();
        return store.rows();
    }
}
