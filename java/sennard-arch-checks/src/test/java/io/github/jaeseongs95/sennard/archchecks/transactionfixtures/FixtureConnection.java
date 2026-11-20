// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for {@code java.sql.Connection}: a transaction made with the connection by hand; {@code close} is no transaction call. */
final class FixtureConnection {
    void setAutoCommit(boolean autoCommit) {
    }

    void commit() {
    }

    void rollback() {
    }

    Object setSavepoint() {
        return new Object();
    }

    void close() {
    }
}
