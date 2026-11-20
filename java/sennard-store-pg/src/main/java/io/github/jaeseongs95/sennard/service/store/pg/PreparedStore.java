// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.DisposableBean;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The server's business database as one bean: how it is reached, migrated and ended. The database is not the server's to start or stop: it is a
 * service the user runs, and the provider only says how to reach it. The business DataSource bean depends on this one, so the context closes the pool first and
 * then lets the provider end what it started, by nothing more than that dependency.
 */
public final class PreparedStore implements DisposableBean {
    private final AtomicReference<DatabaseConfigProvider> provider = new AtomicReference<>();
    private final StorePreparation preparation;
    private final Migrations migrations;
    private final DataSourceFactory pools;
    private volatile Consumer<HikariDataSource> ready = pool -> {
        throw new IllegalStateException("No one takes the pool: call prepare.");
    };

    /**
     * @param providers  makes the provider of one attempt
     * @param retryDelay the pause between two attempts
     * @param clock      the application's clock, which stamps the migrations' history
     * @param pools      makes the checked pool once the migrations are through (the bean of the application, not a static call)
     */
    public PreparedStore(Function<PhaseListener, DatabaseConfigProvider> providers, Duration retryDelay, Clock clock, DataSourceFactory pools) {
        this.migrations = new Migrations(clock, pools);
        this.pools = pools;
        preparation = new StorePreparation(phases -> attempt(providers.apply(phases), phases), retryDelay, clock);
    }

    /** One attempt: the settings, the migrations, then a pool that passed the start-up checks. A failed check is a failed attempt and ends in FAILED, not a live server. */
    private DatabaseConfigProvider attempt(DatabaseConfigProvider attempt, PhaseListener phases) {
        provider.set(attempt);
        return new DatabaseConfigProvider() {
            @Override
            public SennardDatabaseConfig provide() {
                SennardDatabaseConfig config = attempt.provide();
                phases.entered(StorePhase.MIGRATING);
                migrations.expand(config);
                ready.accept(pools.create(config));
                phases.entered(StorePhase.READY);
                return config;
            }

            @Override
            public void stop() {
                attempt.stop();
            }
        };
    }

    /** Prepares the store on this thread and gives the checked pool to {@code ready}; the status tells how it went. */
    public void prepare(Consumer<HikariDataSource> ready) {
        this.ready = ready;
        preparation.run();
    }

    /** Three more attempts after FAILED, on their own thread. */
    public void retry() {
        preparation.retry();
    }

    public StorePreparation.Status status() {
        return preparation.status();
    }

    @Override
    public void destroy() {
        DatabaseConfigProvider last = provider.get();
        if (last != null) {
            last.stop();
        }
    }
}
