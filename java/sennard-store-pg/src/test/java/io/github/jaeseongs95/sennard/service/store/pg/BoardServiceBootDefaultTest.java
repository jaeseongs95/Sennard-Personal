// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Case g of {@link AbstractBoardServiceTest}: the Boot default path. The context has a plain pool as its DataSource bean (the test JVM's one, which Boot would otherwise
 * build from {@code spring.datasource.url/username/password}) and Boot's {@code JdbcTransactionManager}. No {@link StoreTransactionManager}, no row-level security (the table has no
 * policy), no advisory lock; the same {@code BoardService} and repository run every case of the base class, which is the proof that sennard-app is a plain Boot
 * application layer that works on any Spring Boot application with a DataSource. The dialect is Boot's own
 * detection (no {@code spring.data.jdbc.dialect}), which asks the database at start: here it is there to be asked.
 */
class BoardServiceBootDefaultTest extends AbstractBoardServiceTest {
    @Override
    RewrittenAreasContext open(Instant start) {
        return RewrittenAreasContext.bootDefault(start);
    }

    @Test
    void actualDatabaseDeterminesDialectWithoutConfiguredUrl() throws Exception {
        String product;
        try (Connection connection = BusinessDb.connect()) {
            product = connection.getMetaData().getDatabaseProductName(); // what the database says it is
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> context().bean(io.github.jaeseongs95.sennard.service.database.SqlBuilder.class).requireSame(product));
    }

    @Test
    void theContextHoldsBootsOwnBeansOnly() throws Exception {
        RewrittenAreasContext context = context();
        assertInstanceOf(JdbcTransactionManager.class, context.transactionManager());
        assertFalse(context.transactionManager() instanceof StoreTransactionManager);
        DataSource dataSource = context.dataSource();
        assertFalse(dataSource instanceof PreparingDataSource);
        assertInstanceOf(HikariDataSource.class, dataSource, "a plain pool, not the store's DataSource");
        assertSame(BusinessDb.pool(), dataSource, "the test JVM's one pool: the context borrows it and builds none of its own");
        assertInstanceOf(RewrittenAreasContext.dialectType(), context.bean(org.springframework.data.jdbc.core.dialect.JdbcDialect.class));
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection.getMetaData());
            assertEquals(BusinessDb.config().ownerUser(), connection.getMetaData().getUserName(), "the one account of the business database, as the pool's settings say");
        }
    }
}
