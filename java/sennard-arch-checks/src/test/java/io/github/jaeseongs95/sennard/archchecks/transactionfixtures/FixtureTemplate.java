// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for {@code TransactionTemplate}: a transaction opened by hand. */
final class FixtureTemplate {
    <T> T execute(java.util.function.Supplier<T> work) {
        return work.get();
    }
}
