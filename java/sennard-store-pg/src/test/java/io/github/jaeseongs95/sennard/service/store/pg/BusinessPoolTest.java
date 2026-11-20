// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The connection budget of the test JVM on the database the run names (PostgreSQL): the account may hold {@link BusinessDbExtension#ACCOUNT_CONNECTION_LIMIT} connections, the tests'
 * one pool is smaller than that with room for what the product under test opens by itself, and nothing of the tests connects outside it ("too many connections for
 * role" came from a pool per class and a connection per fixture call).
 */
@Tag("business-db")
class BusinessPoolTest {
    @Test
    void theTestsOnePoolIsSmallerThanTheAccountsConnectionLimitWithRoomForTheProductsOwn() throws Exception {
        HikariDataSource pool = BusinessDb.pool();
        long limit = BusinessDbExtension.connectionLimit(pool);
        assertEquals(BusinessDbExtension.POOL_SIZE, pool.getMaximumPoolSize(), "the pool has the size the fixture states");
        if (limit > 0) { // a server that states no limit leaves the pool nothing to stay below
            assertTrue(pool.getMaximumPoolSize() < limit, "the pool of " + pool.getMaximumPoolSize() + " stays below the account's limit of " + limit);
            assertTrue(pool.getMaximumPoolSize() + BusinessDbExtension.RESERVED_CONNECTIONS < limit,
                    "the pool and the " + BusinessDbExtension.RESERVED_CONNECTIONS + " connections the product under test opens by itself stay below the limit of " + limit);
        }
        assertSame(pool, BusinessDbExtension.pool(), "one pool for the JVM, whoever asks");
        assertFalse(pool.isClosed(), "the root store of the engine closes it, not a test");
    }

    @Test
    void aFixtureConnectionIsTheSharedPoolsAndGoesBackToIt() throws Exception {
        HikariDataSource pool = BusinessDb.pool();
        int before = pool.getHikariPoolMXBean().getActiveConnections();
        try (Connection borrowed = BusinessDb.connect()) {
            assertEquals(before + 1, pool.getHikariPoolMXBean().getActiveConnections(), "connect() borrows from the pool: the pool counts it as in use");
            assertTrue(borrowed.isValid(5));
        }
        assertEquals(before, pool.getHikariPoolMXBean().getActiveConnections(), "closing it gives it back");
    }

    /** The server's own count of this account's backends in this database never goes above the pool's size: no connection of the tests is outside the pool. */
    @Test
    void theAccountHoldsNoMoreBackendsThanThePoolHasConnections() throws Exception {
        BusinessDb.reset();
        BusinessDb.clearData(); // fixtures that used to connect by themselves
        long limit = BusinessDb.pool().getMaximumPoolSize();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        long backends = backends();
        while (backends > limit && System.nanoTime() < deadline) {
            Thread.sleep(100); // the connections the drop replaced are closed a moment after
            backends = backends();
        }
        assertTrue(backends <= limit, backends + " backends of this account in this database, the pool's maximum is " + limit);
    }

    /** The server's list of the connections is PostgreSQL's {@code pg_stat_activity}. */
    private static long backends() throws SQLException {
        String sql = "SELECT count(*) FROM pg_stat_activity WHERE usename = current_user AND datname = current_database()";
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
