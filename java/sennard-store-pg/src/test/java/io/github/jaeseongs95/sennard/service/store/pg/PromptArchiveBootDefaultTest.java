// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Instant;

/**
 * Case g2 of {@link AbstractPromptArchiveTest}: the Boot default path. The context has a plain DataSource bean (the test JVM's one pool) and Boot's
 * {@code JdbcTransactionManager}, no row-level security. The same services and repositories run every case, which is the proof that the archive is a plain
 * Boot application layer.
 */
class PromptArchiveBootDefaultTest extends AbstractPromptArchiveTest {
    @Override
    RewrittenAreasContext open(Instant start) {
        return RewrittenAreasContext.bootDefault(start);
    }
}
