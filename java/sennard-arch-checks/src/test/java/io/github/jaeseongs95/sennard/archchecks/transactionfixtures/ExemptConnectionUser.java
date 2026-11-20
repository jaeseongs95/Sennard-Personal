// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A class named in the connection exemptions (the way the migration applier is): its connection calls are not findings, and a nested class of it belongs to it. */
final class ExemptConnectionUser {
    private final FixtureConnection connection = new FixtureConnection();

    void apply() {
        connection.setAutoCommit(false);
        connection.commit();
        new Inner().undo();
    }

    final class Inner {
        void undo() {
            connection.rollback();
        }
    }
}
