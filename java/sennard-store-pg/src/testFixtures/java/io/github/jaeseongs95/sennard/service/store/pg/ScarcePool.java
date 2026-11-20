// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;

/**
 * A case that is about a pool with few connections (a pool of one that is exhausted, a pool of one that reuses its connection) runs on the one shared pool
 * ({@link BusinessDbExtension#pool()}) made scarce: the connections above the {@code free} it leaves are borrowed and kept, so the caller sees a pool of
 * {@code free} connections, with the product's own one second wait for a connection (the shared pool is the product's factory's, and so is its wait). Closing it
 * gives the connections back. No pool of its own is made, so the account's connection limit is not touched.
 */
public final class ScarcePool implements AutoCloseable {
    private final HikariDataSource pool;
    private final List<Connection> held = new ArrayList<>();

    private ScarcePool(HikariDataSource pool) {
        this.pool = pool;
    }

    /** The shared pool with {@code free} connections left to borrow. */
    public static ScarcePool leaving(int free) throws SQLException {
        HikariDataSource shared = BusinessDbExtension.pool();
        ScarcePool scarce = new ScarcePool(shared);
        try {
            for (int taken = shared.getMaximumPoolSize() - free; taken > 0; taken--) {
                scarce.held.add(shared.getConnection());
            }
        } catch (SQLException | RuntimeException failure) {
            scarce.close();
            throw failure;
        }
        return scarce;
    }

    /** The scarce pool, which is the shared one: borrow from it as from any pool. */
    public HikariDataSource dataSource() {
        return pool;
    }

    @Override
    public void close() throws SQLException {
        SQLException failed = null;
        for (Connection connection : held) {
            try {
                connection.close();
            } catch (SQLException failure) {
                if (failed == null) {
                    failed = failure;
                } else {
                    failed.addSuppressed(failure);
                }
            }
        }
        held.clear();
        if (failed != null) {
            throw failed;
        }
    }
}
