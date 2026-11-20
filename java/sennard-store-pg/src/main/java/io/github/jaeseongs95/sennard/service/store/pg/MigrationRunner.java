// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import io.github.jaeseongs95.sennard.service.migration.HistoryMigrator;
import io.github.jaeseongs95.sennard.service.migration.MigrationCode;
import io.github.jaeseongs95.sennard.service.migration.MigrationException;
import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.migration.MigrationTarget;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.StoreException.Effect;

/**
 * The business database's side of the migrations: it opens the connection of the service's one account and hands it to {@link HistoryMigrator}, the one runner of the product (the
 * module sennard-migration, which the settings store uses as well), then turns what that runner stops with into a {@link StoreException}. The history table
 * {@code sennard.<phase>_history}, its rows (one per attempt), the claim of a number and the rules that judge the history are described there; what this class adds is the two
 * phases, each with its folder in the jar and its table, and the check of the database's encoding before any script runs.
 */
final class MigrationRunner {
    /** The two kinds of migration: expand scripts run at every start, contract scripts by an explicit command. Each has its folder in the jar and its history table. */
    enum Phase {
        EXPAND("db/expand", "expand_history"), CONTRACT("db/contract", "contract_history");

        private final String location;
        private final String tableName;

        Phase(String location, String tableName) {
            this.location = location;
            this.tableName = tableName;
        }

        String location() {
            return location;
        }

        /** Where the phase keeps its history; the CHECK of the two outcomes is on every database the business schema runs on. */
        MigrationTarget target() {
            return new MigrationTarget(Migrations.SCHEMA, tableName, location);
        }
    }

    private final HistoryMigrator migrator;
    private final DataSourceFactory dataSources;

    MigrationRunner(Clock clock, DataSourceFactory dataSources) {
        this.migrator = new HistoryMigrator(clock);
        this.dataSources = dataSources;
    }

    /** Applies the pending scripts of the phase from the jar; the number applied by this call. */
    int run(SennardDatabaseConfig config, Phase phase) {
        try {
            return run(config, phase, MigrationScript.load(phase.location()));
        } catch (MigrationException failure) {
            throw storeFailure(failure);
        }
    }

    /** Applies the pending ones of these scripts (the jar's, or the ones a test gives); the number applied by this call. */
    int run(SennardDatabaseConfig config, Phase phase, List<MigrationScript> scripts) {
        // The runner has its own connection of the service's one account, not the business pool: the user's driver, no driver manager.
        try (Connection connection = dataSources.direct(config).getConnection()) {
            connection.setAutoCommit(false);
            // Text is UTF-8 from end to end, and a script holds Korean text (the glossary): a database in another encoding is refused here, before a script fails half way with an encoding error.
            DataSourceFactory.verifyEncoding(connection);
            return migrator.run(connection, phase.target(), scripts);
        } catch (SQLException failure) {
            throw StoreErrors.map(failure, false);
        } catch (MigrationException failure) {
            throw storeFailure(failure);
        }
    }

    private static StoreException storeFailure(MigrationException failure) {
        return new StoreException(storeCode(failure.code()), null, Effect.NONE, failure.getMessage(), failure.getCause());
    }

    private static StoreCode storeCode(MigrationCode code) {
        return switch (code) {
            case CHANGED -> StoreCode.MIGRATION_CHANGED;
            case MISSING -> StoreCode.MIGRATION_MISSING;
            case OUT_OF_ORDER -> StoreCode.MIGRATION_OUT_OF_ORDER;
            case GAP -> StoreCode.MIGRATION_GAP;
            case FAILED -> StoreCode.MIGRATION_FAILED;
        };
    }
}
