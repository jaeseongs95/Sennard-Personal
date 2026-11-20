// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;

import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.Migrations;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;

/**
 * Tries a business database a link names, the way the store reaches it when the server starts: the user's driver and account, the start-up checks of the pool (the encoding), the product
 * the URL names, and the migrations of its tables. The admin page runs it before it keeps a link while the business database is ready (the store is prepared once in a process, so a
 * link kept then is used by the next start): a link that this refuses is not kept, so a restart is not spent on a database that cannot start the store. Everything it does to the
 * database is the store's own start-up work (the migrations are idempotent), so the next start finds it done.
 */
@Component
public class LinkedDatabaseProbe {
    private final DataSourceFactory pools;
    private final Clock clock;

    public LinkedDatabaseProbe(DataSourceFactory pools, Clock clock) {
        this.pools = pools;
        this.clock = clock;
    }

    /**
     * Connects with the link, checks the product and applies the pending expand scripts.
     *
     * @throws StoreException when the database cannot be reached or does not pass the store's checks (the code says which; the application's error translation states it)
     */
    public void prepare(DatabaseLinkRequest link) {
        SennardDatabaseConfig config = new SennardDatabaseConfig(link.jdbcUrl(), link.accountName(), link.passwordRef(), link.driverPath(), PreLinkAdminService.tlsPolicy(link.tls()), 1);
        try (HikariDataSource pool = pools.create(config); Connection connection = pool.getConnection()) {
            SqlBuilder.requireSame(config.jdbcUrl(), connection.getMetaData().getDatabaseProductName());
        } catch (SQLException failure) {
            throw new StoreException(StoreCode.STORE_UNREACHABLE, "The database the link names could not be reached.", failure);
        }
        new Migrations(clock, pools).expand(config);
    }
}
