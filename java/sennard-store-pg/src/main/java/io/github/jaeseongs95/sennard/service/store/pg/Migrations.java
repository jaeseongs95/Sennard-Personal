// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Clock;

/**
 * The migrations of the schema, run over a direct connection of the service's one account and not over the business pool. The scripts are
 * in the jar ({@code db/expand}, {@code db/contract}) and {@link MigrationRunner} applies them; expand and contract have their own folder and history table, so neither knows the other's
 * applied scripts. Both live in the {@code sennard} schema, which the runner creates.
 *
 * <p>6.0.0 ships expand scripts only. The contract phase exists so the first contract script of a later release applies after an expand ran.
 */
public final class Migrations {
    static final String SCHEMA = "sennard";

    private final Clock clock;
    private final DataSourceFactory dataSources;

    /**
     * @param clock the application's one Clock, which stamps the history rows
     * @param dataSources opens the direct connection of the one account with the user's driver
     */
    public Migrations(Clock clock, DataSourceFactory dataSources) {
        this.clock = clock;
        this.dataSources = dataSources;
    }

    /** Applies the pending expand scripts; the number applied (0 on a rerun). */
    public int expand(SennardDatabaseConfig config) {
        return new MigrationRunner(clock, dataSources).run(config, MigrationRunner.Phase.EXPAND);
    }

    /** Applies the pending contract scripts; the number applied. Run by an explicit command, never at start-up, once one exists. */
    public int contract(SennardDatabaseConfig config) {
        return new MigrationRunner(clock, dataSources).run(config, MigrationRunner.Phase.CONTRACT);
    }
}
