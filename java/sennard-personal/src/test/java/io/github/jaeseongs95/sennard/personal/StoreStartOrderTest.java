// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.PreparingDataSource;

/**
 * The store is ready for a request only after what it must have is done (the keys and secrets a request only reads): while the start work runs, its own thread has the
 * connection and any other thread, a request, is answered with STORE_PREPARING; when the work fails the store stays so, with the pool closed.
 */
class StoreStartOrderTest {
    private static final String URL = "jdbc:h2:mem:store-start-order;DB_CLOSE_DELAY=-1";

    private static HikariDataSource pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(URL);
        pool.setMaximumPoolSize(2);
        return pool;
    }

    private static StoreException requestMeetsTheStore(PreparingDataSource store) throws Exception {
        try (ExecutorService request = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Connection> asked = request.submit((Callable<Connection>) store::getConnection);
            ExecutionException refused = assertThrows(ExecutionException.class, asked::get);
            return (StoreException) refused.getCause();
        }
    }

    @Test
    void aRequestIsRefusedUntilTheStartWorkIsDoneAndThenServed() throws Exception {
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "MIGRATING"))) {
            AtomicReference<StoreException> requestDuringTheWork = new AtomicReference<>();
            AtomicReference<Boolean> workGotItsConnection = new AtomicReference<>(false);

            PersonalStoreConfiguration.readyChecked(store, pool(), URL, () -> {
                try (Connection connection = store.getConnection()) {
                    workGotItsConnection.set(connection.isValid(1));
                    requestDuringTheWork.set(requestMeetsTheStore(store));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

            assertTrue(workGotItsConnection.get(), "the start work uses the store on its own thread");
            assertEquals(StoreCode.STORE_PREPARING, requestDuringTheWork.get().code(), "a request during the start work gets the answer of a store that is not ready");
            try (ExecutorService request = Executors.newVirtualThreadPerTaskExecutor(); Connection connection = request.submit((Callable<Connection>) store::getConnection).get()) {
                assertTrue(connection.isValid(1), "after the work, a request is served");
            }
        }
    }

    @Test
    void aFailedStartWorkKeepsTheStoreNotReadyAndClosesThePool() throws Exception {
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "MIGRATING"))) {
            HikariDataSource pool = pool();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PersonalStoreConfiguration.readyChecked(store, pool, URL, () -> {
                throw new IllegalStateException("the key could not be made");
            }));
            assertEquals("the key could not be made", failure.getMessage());
            assertTrue(pool.isClosed());
            assertEquals(StoreCode.STORE_PREPARING, requestMeetsTheStore(store).code());
        }
    }
}
