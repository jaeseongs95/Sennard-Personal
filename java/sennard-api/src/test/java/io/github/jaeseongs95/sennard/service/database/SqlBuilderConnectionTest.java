// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

class SqlBuilderConnectionTest {
    @Test
    void concurrentCallsResolveActualH2MetadataOnce() throws Exception {
        try (HikariDataSource pool = pool(8); var executor = Executors.newFixedThreadPool(8)) {
            CountingDataSource source = new CountingDataSource(pool, false);
            SqlBuilder builder = new SqlBuilder(source);
            CountDownLatch start = new CountDownLatch(1);
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return builder.generateLimit("?");
                }));
            }
            start.countDown();
            for (var result : results) {
                assertEquals("FETCH FIRST ? ROWS ONLY", result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, source.metadataCalls.get());
        }
    }

    @Test
    void transactionHoldingOnlyConnectionCanResolveWhileAnotherThreadWaits() throws Exception {
        try (HikariDataSource pool = pool(1); var executor = Executors.newSingleThreadExecutor()) {
            CountingDataSource source = new CountingDataSource(pool, false);
            SqlBuilder builder = new SqlBuilder(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var waitingRef = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            tx.executeWithoutResult(status -> {
                var waiting = executor.submit(() -> builder.generateLimit("?"));
                waitingRef.set(waiting);
                try {
                    assertTrue(source.secondAcquisition.await(5, TimeUnit.SECONDS));
                    assertEquals("FETCH FIRST ? ROWS ONLY", builder.generateLimit("?"));
                    assertFalse(waiting.isDone());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            assertEquals("FETCH FIRST ? ROWS ONLY", waitingRef.get().get(10, TimeUnit.SECONDS));
            assertEquals(1, source.metadataCalls.get());
        }
    }

    @Test
    void unsupportedProductDoesNotCacheAResult() {
        try (HikariDataSource pool = pool(1)) {
            CountingDataSource source = new CountingDataSource(pool, true);
            SqlBuilder builder = new SqlBuilder(source);
            for (int i = 0; i < 2; i++) {
                StoreException failure = assertThrows(StoreException.class, () -> builder.generateLimit("?"));
                assertEquals(StoreCode.UNSUPPORTED_DATABASE_PRODUCT, failure.code());
            }
            assertEquals(2, source.metadataCalls.get());
        }
    }

    private static HikariDataSource pool(int size) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:sqlBuilderConnections");
        pool.setUsername("sa");
        pool.setMaximumPoolSize(size);
        return pool;
    }

    /** Delegates to real H2 and observes metadata; the unsupported case replaces only the product name. */
    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final boolean unknown;
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger metadataCalls = new AtomicInteger();
        private final CountDownLatch secondAcquisition = new CountDownLatch(1);

        CountingDataSource(DataSource delegate, boolean unknown) {
            this.delegate = delegate;
            this.unknown = unknown;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (acquisitions.incrementAndGet() == 2) {
                secondAcquisition.countDown();
            }
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("getMetaData")) {
                        metadataCalls.incrementAndGet();
                        if (unknown) {
                            DatabaseMetaData metadata = (DatabaseMetaData) result;
                            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
                                    (p, m, a) -> {
                                        if (m.getName().equals("getDatabaseProductName")) return "Unsupported product";
                                        try { return m.invoke(metadata, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                                    });
                        }
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
    }
}
