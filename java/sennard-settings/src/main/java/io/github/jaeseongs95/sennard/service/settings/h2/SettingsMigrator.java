// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.migration.HistoryMigrator;
import io.github.jaeseongs95.sennard.service.migration.MigrationCode;
import io.github.jaeseongs95.sennard.service.migration.MigrationException;
import io.github.jaeseongs95.sennard.service.migration.MigrationScript;
import io.github.jaeseongs95.sennard.service.migration.MigrationTarget;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * Applies the scripts of {@code db/settings} to the settings database, in number order, once each. It is the settings store's side of the one migration runner of the product
 * ({@link HistoryMigrator} of the module sennard-migration, which the business database's migrations use as well): the history format (a row per attempt), the claim of a number, the
 * numbers one after the other and the refusals of a changed, a missing and an out-of-order script are that runner's, and this class turns what it stops with into a
 * {@link SettingsException}. What is its own is the place of the history: the table {@code sennard.history} of this database (the settings database is a file of its own, so its history never mixes into the business
 * database's history).
 *
 * <p>The database has one user, this process (H2's file lock keeps a second one out), so there is no second runner to wait for. H2 commits a DDL statement as it runs it, so a script
 * that fails half way leaves what it did; its attempt stays in the history as FAILED and the next start makes the next attempt, which fails with the database's own reason unless
 * the script was fixed. The history table has no CHECK of the two outcomes: the runner is its only writer and checks the outcome against its enum when a row is read.
 */
@Component
public class SettingsMigrator {
    static final String LOCATION = "db/settings";
    private static final MigrationTarget HISTORY = new MigrationTarget("sennard", "history", LOCATION);

    private final HistoryMigrator migrator;

    SettingsMigrator(Clock clock) {
        this.migrator = new HistoryMigrator(clock);
    }

    /** Applies the pending scripts of the jar; the number applied by this call (0 on a rerun). */
    public int migrate(DataSource dataSource) {
        try {
            return migrate(dataSource, MigrationScript.load(LOCATION));
        } catch (MigrationException failure) {
            throw settingsFailure(failure);
        }
    }

    int migrate(DataSource dataSource, List<MigrationScript> scripts) {
        try (Connection connection = dataSource.getConnection()) {
            return migrator.run(connection, HISTORY, scripts);
        } catch (SQLException failure) {
            throw new SettingsException(SettingsCode.MIGRATION_FAILED, "The settings database could not be migrated.", failure);
        } catch (MigrationException failure) {
            throw settingsFailure(failure);
        }
    }

    private static SettingsException settingsFailure(MigrationException failure) {
        return new SettingsException(settingsCode(failure.code()), failure.getMessage(), failure.getCause());
    }

    private static SettingsCode settingsCode(MigrationCode code) {
        return switch (code) {
            case CHANGED -> SettingsCode.MIGRATION_CHANGED;
            case MISSING -> SettingsCode.MIGRATION_MISSING;
            case OUT_OF_ORDER -> SettingsCode.MIGRATION_OUT_OF_ORDER;
            case GAP -> SettingsCode.MIGRATION_GAP;
            case FAILED -> SettingsCode.MIGRATION_FAILED;
        };
    }
}
