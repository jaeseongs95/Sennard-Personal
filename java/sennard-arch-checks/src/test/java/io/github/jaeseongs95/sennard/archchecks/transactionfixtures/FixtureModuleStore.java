// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for a store of the product that has a lock made by hand ({@code lockModule}); the rule finds the name, defined or called, wherever it is. */
abstract class FixtureModuleStore {
    void lockModule() {
    }
}
