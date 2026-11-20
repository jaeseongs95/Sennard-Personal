// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparingDataSource;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;

/** Real database cases (the product the test URL names, PostgreSQL): matching product becomes ready; mismatch keeps the store unavailable and closes the rejected pool. */
@Tag("business-db")
class StoreProductCheckTest {
    /**
     * The class starts from nothing like every business-database class: the schemas are dropped and migrated anew, so the migration history an earlier build or class left in the
     * shared database (a script that has changed since) cannot fail the preparation with MIGRATION_CHANGED before the product check is reached.
     */
    @BeforeAll
    static void startFromNothing() throws SQLException {
        BusinessDb.reset();
    }

    @Test
    void matchingProductBecomesReady() throws Exception {
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "STARTING"))) {
            HikariDataSource pool = BusinessDb.dataSources().create(BusinessDb.config());
            PersonalStoreConfiguration.readyChecked(store, pool, BusinessDb.config().jdbcUrl());
            try (Connection connection = store.getConnection()) {
                assertEquals(BusinessDb.product(), SqlBuilder.ofProductName(connection.getMetaData().getDatabaseProductName()));
            }
        }
    }

    @Test
    void mismatchDoesNotMakeTheStoreReady() {
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "STARTING"))) {
            HikariDataSource pool = BusinessDb.dataSources().create(BusinessDb.config());
            StoreException failure = assertThrows(StoreException.class,
                    () -> PersonalStoreConfiguration.readyChecked(store, pool, "jdbc:h2:mem:mismatch"));
            assertTrue(failure.getMessage().contains("H2"));
            assertTrue(failure.getMessage().contains(BusinessDb.product().name()));
            assertTrue(pool.isClosed());
            assertThrows(StoreException.class, store::getConnection);
        }
    }

    @Test
    void mismatchFailsThePreparationAfterRetries() {
        PreparedStore prepared = new PreparedStore(phases -> BusinessDb::config, Duration.ofMillis(1), Clock.systemUTC(), BusinessDb.dataSources());
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", prepared.status().phase().name()))) {
            prepared.prepare(pool -> PersonalStoreConfiguration.readyChecked(store, pool, "jdbc:h2:mem:mismatch"));
            assertEquals(StorePhase.FAILED, prepared.status().phase());
            assertEquals(3, prepared.status().attempt());
            assertTrue(prepared.status().detail().contains("H2"));
            assertTrue(prepared.status().detail().contains(BusinessDb.product().name()));
            assertThrows(StoreException.class, store::getConnection);
        } finally {
            prepared.destroy();
        }
    }
}
