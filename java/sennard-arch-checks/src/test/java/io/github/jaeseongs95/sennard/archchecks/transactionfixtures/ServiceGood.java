// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/**
 * What the rules leave alone: a service whose methods are transactions, read a store, and use a lambda and a nested class that belong to it; a retry on a method that
 * is no transaction (the retry of a call that failed in communication); and a read with no transaction at all.
 */
@FixtureService
final class ServiceGood {
    private final FixtureStore store = new FixtureStore();

    @FixtureTransactional
    int useCase() {
        Runnable read = store::rows;
        read.run();
        return new Inner().rows();
    }

    @FixtureTransactional(propagation = FixturePropagation.REQUIRED)
    int named() {
        return store.rows();
    }

    @FixtureRetryOnConflict
    int callAgain() {
        return store.rows();
    }

    /** Near misses of the hand-made rules: a call on a manager or a connection that is no transaction call. */
    @FixtureTransactional
    String nearMisses() {
        new FixtureConnection().close();
        return new FixtureManager().name();
    }

    int read() {
        return store.rows();
    }

    final class Inner {
        int rows() {
            return store.rows();
        }
    }
}
