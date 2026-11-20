// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.TransactionDefinition;

/**
 * The time every transaction of the store has is the edition's setting ({@code spring.transaction.default-timeout}), which Boot's {@code TransactionManagerCustomizers} sets on the
 * store's transaction manager; the manager itself holds no number. No connection is made: the manager is built over a data source that is never asked for one.
 */
class StoreTransactionTimeoutTest {
    /** A data source that fails when a connection is asked for: the manager is only built here. */
    private static final class NoConnections extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("no connection in this test");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("no connection in this test");
        }
    }

    private static StoreTransactionManager managerOf(Map<String, Object> settings) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("edition", settings));
            context.registerBean("store", DataSource.class, NoConnections::new);
            context.register(TransactionManagerCustomizationAutoConfiguration.class, StoreTransactionConfiguration.class);
            context.refresh();
            return context.getBean(StoreTransactionManager.class);
        }
    }

    @Test
    void theEditionsSettingIsTheTimeOfTheStoresTransactions() {
        assertEquals(10, managerOf(Map.of("spring.transaction.default-timeout", "10s")).getDefaultTimeout());
        assertEquals(3, managerOf(Map.of("spring.transaction.default-timeout", "3s")).getDefaultTimeout());
    }

    @Test
    void theManagerHasNoTimeOfItsOwnWhenTheEditionSetsNone() {
        assertEquals(TransactionDefinition.TIMEOUT_DEFAULT, managerOf(Map.of()).getDefaultTimeout());
        assertEquals(TransactionDefinition.TIMEOUT_DEFAULT, new StoreTransactionManager(new NoConnections()).getDefaultTimeout());
    }
}
