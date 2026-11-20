// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Instant;

/** The prompt archive's cases of {@link AbstractPromptArchiveTest} with this application's connection: {@link PreparingDataSource} named {@code store} and the {@link StoreTransactionManager}. */
class PromptArchiveAppConnectionTest extends AbstractPromptArchiveTest {
    @Override
    RewrittenAreasContext open(Instant start) {
        return RewrittenAreasContext.appConnection(start);
    }
}
