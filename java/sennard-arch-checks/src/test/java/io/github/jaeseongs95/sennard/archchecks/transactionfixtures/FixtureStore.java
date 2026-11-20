// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A store of the fixtures: a module store, called through its own type as the product's services call theirs. */
final class FixtureStore extends FixtureModuleStore {
    int rows() {
        return 0;
    }
}
