// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The isolation level of the transactions is the edition's setting, in Spring's {@link Isolation}, and the connections of the pool the factory makes are opened with it: the level a
 * transaction of the store runs at, read from the database through the connection the store's own transaction manager gives it, is the one the factory was given. The databases
 * have a default of their own (PostgreSQL: read committed), so the two cases below cannot both pass by the database's default.
 */
@BusinessDbTest
class StoreIsolationTest {
    @Test
    void theEditionsSettingIsTheLevelOfTheStoresTransactions() throws Exception {
        for (Isolation setting : new Isolation[] {Isolation.READ_COMMITTED, Isolation.REPEATABLE_READ}) {
            try (HikariDataSource pool = BusinessDb.dataSources(setting).create(BusinessDb.config(1))) {
                TransactionTemplate transaction = new TransactionTemplate(new StoreTransactionManager(pool));
                Integer level = transaction.execute(status -> levelOf(pool));
                assertEquals(setting.value(), level, "a transaction of the store runs at the level the edition names: " + setting);
            }
        }
    }

    private static int levelOf(HikariDataSource pool) {
        Connection connection = DataSourceUtils.getConnection(pool);
        try {
            return connection.getTransactionIsolation();
        } catch (SQLException failure) {
            throw StoreErrors.map(failure, false);
        } finally {
            DataSourceUtils.releaseConnection(connection, pool);
        }
    }
}
