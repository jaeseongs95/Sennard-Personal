// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.JdbcDrivers;
import io.github.jaeseongs95.sennard.service.store.pg.DatabaseConfigProvider;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;

/**
 * The assembled application reaches the store behind its listener: the server answers while the preparation blocks, and
 * {@code /actuator/sennard} tells the phase and, after three failed attempts, the reason. Without a database setting the server starts all the same and waits for one. The
 * database itself is the fixture's here: the real preparation has its own tests in sennard-store-pg and the server tests of this module reach the shared PostgreSQL.
 */
class StoreAssemblyTest {
    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    static class BlockedDatabase {
        @Bean
        @Primary
        PreparedStore blockedStore(java.time.Clock clock) {
            return new PreparedStore(phases -> new DatabaseConfigProvider() {
                @Override
                public SennardDatabaseConfig provide() {
                    phases.entered(StorePhase.CONNECTING);
                    STARTED.countDown();
                    ATTEMPTS.incrementAndGet();
                    try {
                        RELEASE.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new StoreException(StoreCode.STORE_UNREACHABLE, "the fixture's database does not answer");
                }
            }, Duration.ofMillis(1), clock, new DataSourceFactory(new JdbcDrivers(), org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
                    new io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage()));
        }
    }

    private static String status(RunningServer server) throws Exception {
        RawHttp.Response response = RawHttp.exchange(server.port, "GET", "/actuator/sennard", Map.of(), null);
        assertEquals(200, response.status(), response.text());
        return response.text();
    }

    @Test
    @Tag("business-db")
    void theServerAnswersWhileTheStoreIsReachedAndReportsTheFailure(@TempDir Path state) throws Exception {
        // A database is linked (the store prepares only then); the fixture's provider stands in for reaching it.
        SharedDatabase.linkInto(state.resolve("settings"));
        try (RunningServer server = RunningServer.startAt(state, List.of(BlockedDatabase.class, ServerFixtures.Beans.class), "--sennard.store.prepare=true")) {
            STARTED.await();
            assertTrue(status(server).contains("\"phase\":\"CONNECTING\""), "the listener is up while the store is being reached");
            assertEquals(200, RawHttp.exchange(server.port, "GET", "/actuator/health", Map.of(), null).status());

            RELEASE.countDown();
            String settled = status(server);
            while (!settled.contains("\"phase\":\"FAILED\"")) {
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
                settled = status(server);
            }
            assertTrue(settled.contains("\"reason\":\"STORE_UNREACHABLE\""), settled);
            assertEquals(3, ATTEMPTS.get(), "three attempts before FAILED");

            assertEquals(204, RawHttp.exchange(server.port, "POST", "/actuator/sennard", Map.of("Content-Length", "0"), new byte[0]).status());
            while (ATTEMPTS.get() < 6 || !status(server).contains("\"phase\":\"FAILED\"")) {
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        }
    }

    /** No link of a business database (and a {@code sennard.store.database.*} key is no source): the server is up, says NOT_CONFIGURED, and a call that needs the store is answered STORE_PREPARING. */
    @Test
    void theServerStartsWithoutADatabaseSettingAndSaysSo() throws Exception {
        // The key names a database that nothing listens on: a source of the business database it is not, so the store waits all the same.
        try (RunningServer server = RunningServer.start(List.of(ServerFixtures.Beans.class), "--sennard.store.prepare=true", "--sennard.store.database.url=jdbc:postgresql://127.0.0.1:1/none")) {
            assertEquals(200, RawHttp.exchange(server.port, "GET", "/actuator/health", Map.of(), null).status());
            assertTrue(status(server).contains("\"phase\":\"NOT_CONFIGURED\""), status(server));
            assertEquals(StorePhase.NOT_CONFIGURED, server.bean(PreparedStore.class).status().phase());
            StoreException preparing = org.junit.jupiter.api.Assertions.assertThrows(StoreException.class,
                    () -> server.context.getBean("store", javax.sql.DataSource.class).getConnection());
            assertEquals(StoreCode.STORE_PREPARING, preparing.code());
        }
    }
}
