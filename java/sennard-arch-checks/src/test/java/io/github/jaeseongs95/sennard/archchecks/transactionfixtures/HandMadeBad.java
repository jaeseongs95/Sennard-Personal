// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/**
 * A service that opens and ends a transaction by hand, with the transaction manager (three calls) and with a connection (four calls), and one that says on which failures it does
 * not roll back, by the class and by a method each (three annotations): ten findings in all.
 */
@FixtureService
final class HandMadeBad {
    private final FixtureManager manager = new FixtureManager();
    private final FixtureConnection connection = new FixtureConnection();

    void byManager() {
        Object status = manager.getTransaction();
        manager.commit(status);
        manager.rollback(status);
    }

    void byConnection() {
        connection.setAutoCommit(false);
        connection.setSavepoint();
        connection.commit();
        connection.rollback();
    }

    @FixtureTransactional(noRollbackFor = IllegalStateException.class)
    void keepsTheWriteOnAFailure() {
    }

    @FixtureTransactional(noRollbackForClassName = "java.lang.IllegalStateException")
    void keepsTheWriteOnAFailureByName() {
    }

    @FixtureTransactional(noRollbackFor = IllegalStateException.class)
    static final class WholeClass {
    }
}
