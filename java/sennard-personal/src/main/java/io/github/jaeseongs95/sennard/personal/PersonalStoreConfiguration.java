// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.jaeseongs95.sennard.server.context.DatabaseLinkSaved;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.store.jobs.JobService;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import com.zaxxer.hikari.HikariDataSource;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.DatabaseConfigProvider;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStoreConfiguration;
import io.github.jaeseongs95.sennard.service.store.pg.PreparingDataSource;
import io.github.jaeseongs95.sennard.service.store.pg.RemoteConfigProvider;

/**
 * The personal edition's store: the business database the user linked on the admin page (the one source, kept in the settings database of the state folder), reached behind the
 * listener. The server starts whether or not a database is linked: without one the store stays NOT_CONFIGURED and answers STORE_PREPARING. The store is prepared once in a
 * process, so a link changed while the store is ready is used by the next start.
 */
@Configuration(proxyBeanMethods = false)
@Import({PreparedStoreConfiguration.class, PersonalStoreIsolation.class, PersonalPrivateStorage.class})
public class PersonalStoreConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalStoreConfiguration.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    /** The pool size of the link (the page asks for none). */
    private static final int LINK_POOL_SIZE = 4;

    @Bean
    PreparedStore preparedStore(DatabaseLinkSettings links, Clock clock, DataSourceFactory pools) {
        return new PreparedStore(phases -> provider(links), RETRY_DELAY, clock, pools);
    }

    /**
     * Where the business database is named: the link the user signed on the admin page, which the settings database keeps. Read at each attempt, so a link saved before the store
     * is ready is the one the next attempt uses.
     */
    private static DatabaseConfigProvider provider(DatabaseLinkSettings links) {
        DatabaseLinkRequest link = links.find().orElseThrow(() -> new IllegalStateException("No business database is linked on the admin page.")).request();
        return new RemoteConfigProvider(link.jdbcUrl(), link.accountName(), link.passwordRef(), link.driverPath(), LINK_POOL_SIZE, PreLinkAdminService.tlsPolicy(link.tls()));
    }

    /** Starts when the listener is up. {@code sennard.store.prepare=false} is for the tests that exercise the web layer alone. */
    @Bean
    @ConditionalOnProperty(name = "sennard.store.prepare", havingValue = "true", matchIfMissing = true)
    StorePreparationStarter storePreparationStarter(DatabaseLinkSettings links, PreparedStore preparedStore, PreparingDataSource store, JobService jobs,
            Map<String, StoreStartTask> startTasks, List<StoreReadyTask> tasks) {
        return new StorePreparationStarter(links, preparedStore, store, jobs, startTasks, tasks);
    }

    static void readyChecked(PreparingDataSource store, HikariDataSource pool, String jdbcUrl) {
        readyChecked(store, pool, jdbcUrl, () -> { });
    }

    /**
     * The pool is checked for its product, then {@code beforeReady} runs on it (on this thread only: {@link PreparingDataSource#whileStarting}), and only then is the store ready for a
     * request. A failure of the check or of {@code beforeReady} closes the pool and leaves the store not ready.
     */
    static void readyChecked(PreparingDataSource store, HikariDataSource pool, String jdbcUrl, Runnable beforeReady) {
        try (Connection connection = pool.getConnection()) {
            SqlBuilder.requireSame(jdbcUrl, connection.getMetaData().getDatabaseProductName());
        } catch (SQLException failure) {
            pool.close();
            throw new IllegalStateException("Could not check the business database product", failure);
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
        try {
            store.whileStarting(pool, beforeReady);
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
        store.ready(pool);
    }

    @Bean
    SennardEndpoint sennardEndpoint(PreparedStore preparedStore, AdminPageAddress adminPage) {
        return new SennardEndpoint(preparedStore, adminPage);
    }

    /** The preparation, off the thread that announced the application ready. */
    public static class StorePreparationStarter {
        private final DatabaseLinkSettings links;
        private final PreparedStore preparedStore;
        private final PreparingDataSource store;
        private final JobService jobs;
        /** By bean name: the name is what the log and the status say about a task that failed (a task can be a lambda). */
        private final Map<String, StoreStartTask> startTasks;
        private final List<StoreReadyTask> tasks;

        StorePreparationStarter(DatabaseLinkSettings links, PreparedStore preparedStore, PreparingDataSource store, JobService jobs,
                Map<String, StoreStartTask> startTasks, List<StoreReadyTask> tasks) {
            this.links = links;
            this.preparedStore = preparedStore;
            this.store = store;
            this.jobs = jobs;
            this.startTasks = startTasks;
            this.tasks = tasks;
        }

        @Async
        @EventListener(ApplicationReadyEvent.class)
        public void prepare() {
            startPreparing();
        }

        /**
         * A link was just saved on the admin page before the business database was ready (its transaction committed): the store prepares with it. An attempt that an earlier link
         * started is not interrupted (a preparation that is running does nothing here); after FAILED, or while the store waits for a database, this starts a new one. A store that is
         * READY is not prepared again: a link saved then is used by the next start.
         */
        @Async
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void linked(DatabaseLinkSaved saved) {
            startPreparing();
        }

        /** The URL of the database the page linked; empty when none is. */
        private Optional<String> databaseUrl() {
            return links.find().map(link -> link.request().jdbcUrl());
        }

        private void startPreparing() {
            databaseUrl().ifPresentOrElse(this::prepareStore, () -> LOG.info("No business database is set; the store waits for one."));
        }

        private void prepareStore(String url) {
            preparedStore.prepare(pool -> {
                // The store is ready for a request only after what it must have is done: the recovery of the last run's jobs and every StoreStartTask. A failure of one fails the attempt
                // (the pool is closed, the store stays not ready, a request keeps getting STORE_PREPARING) and shows in the log and in the status (phase, reason, detail) of the store.
                readyChecked(store, pool, url, this::beforeReady);
                for (StoreReadyTask task : tasks) {
                    try {
                        task.whenStoreReady();
                    } catch (RuntimeException failed) {
                        // What these tasks do is for later (the JVM preset is for the next start): the store is ready and stays so.
                        LOG.error("A task for the ready store failed: {}", task.getClass().getSimpleName(), failed);
                    }
                }
            });
        }

        private void beforeReady() {
            jobs.recover();
            startTasks.forEach((name, task) -> {
                try {
                    task.beforeStoreReady();
                } catch (RuntimeException failed) {
                    LOG.error("A task that the store needs before it is ready failed; the store stays not ready: {}", name, failed);
                    throw new IllegalStateException("A start task of the store failed: " + name, failed);
                }
            });
        }
    }

    /** {@code /actuator/sennard}, behind the guard filter like every path. */
    @Endpoint(id = "sennard")
    public static class SennardEndpoint {
        /** The key of the admin page's address in the answer: the page's port is the operating system's choice at each start, so this is where a command learns it. */
        public static final String ADMIN_PAGE = "adminPage";

        private final PreparedStore preparedStore;
        private final AdminPageAddress adminPage;

        SennardEndpoint(PreparedStore preparedStore, AdminPageAddress adminPage) {
            this.preparedStore = preparedStore;
            this.adminPage = adminPage;
        }

        /** The store's preparation state and where the admin page is listening. */
        @ReadOperation
        public Map<String, Object> status() {
            Map<String, Object> body = new LinkedHashMap<>(PreparedStoreConfiguration.statusBody(preparedStore.status()));
            body.put(ADMIN_PAGE, adminPage.origin());
            return body;
        }

        /** {@code sennard prepare --retry}: three more attempts after FAILED. */
        @WriteOperation
        public void retry() {
            preparedStore.retry();
        }
    }
}
