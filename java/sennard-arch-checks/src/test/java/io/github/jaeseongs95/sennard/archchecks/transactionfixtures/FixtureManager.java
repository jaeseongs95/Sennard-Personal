// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for {@code PlatformTransactionManager}: a transaction opened and ended by hand; {@code name} is no transaction call. */
final class FixtureManager {
    Object getTransaction() {
        return new Object();
    }

    void commit(Object status) {
    }

    void rollback(Object status) {
    }

    String name() {
        return "manager";
    }
}
