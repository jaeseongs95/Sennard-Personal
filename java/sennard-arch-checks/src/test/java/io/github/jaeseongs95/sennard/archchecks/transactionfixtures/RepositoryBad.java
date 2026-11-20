// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** A repository that opens its own transaction, takes a lock made by hand itself and opens one by hand: three findings of its own. */
final class RepositoryBad extends FixtureModuleStore {
    private final FixtureTemplate template = new FixtureTemplate();

    @FixtureTransactional
    int write() {
        lockModule();
        return template.execute(() -> 1);
    }
}
