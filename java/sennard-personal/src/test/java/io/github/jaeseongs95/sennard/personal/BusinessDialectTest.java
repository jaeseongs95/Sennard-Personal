// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcH2Dialect;
import org.springframework.data.jdbc.core.dialect.JdbcMariaDbDialect;
import org.springframework.data.jdbc.core.dialect.JdbcMySqlDialect;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.PreparingDataSource;

/**
 * D-0191: the personal edition's Spring Data JDBC dialect is the one of the business database that is connected, asked from the connection the first time a repository
 * needs it ({@code DialectResolver}, the way Boot asks it), and the context starts without any connection. Nothing here reads the JDBC URL or knows a quoting rule: a
 * stub connection says which product it is and how the server quotes, and the dialect follows (MySQL by {@code ANSI_QUOTES}, MariaDB apart from MySQL).
 */
class BusinessDialectTest {
    /** The pool of an H2 database in memory: a real connection whose product is H2. */
    private static HikariDataSource h2Pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:business-dialect;DB_CLOSE_DELAY=-1");
        pool.setMaximumPoolSize(2);
        return pool;
    }

    /** Counts the connections that were handed out (a refused request is not one). */
    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource target;
        private final AtomicInteger opened = new AtomicInteger();

        CountingDataSource(DataSource target) {
            this.target = target;
        }

        int opened() {
            return opened.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = target.getConnection();
            opened.incrementAndGet();
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection connection = target.getConnection(username, password);
            opened.incrementAndGet();
            return connection;
        }
    }

    /** An interface that answers by method name, and fails on any other call: the test names every question the code under test may ask. */
    private static <T> T stub(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (answers.containsKey(method.getName())) {
                return answers.get(method.getName());
            }
            return switch (method.getName()) {
                case "close" -> null;
                case "toString" -> type.getSimpleName() + " stub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }));
    }

    /** A data source whose connection reports the product and how the server quotes identifiers (what {@code DatabaseMetaData} says). */
    private static DataSource serverReporting(String product, String identifierQuote) {
        DatabaseMetaData metaData = stub(DatabaseMetaData.class, Map.of("getDatabaseProductName", product, "getIdentifierQuoteString", identifierQuote,
                "supportsMixedCaseIdentifiers", false, "storesUpperCaseIdentifiers", false, "storesLowerCaseIdentifiers", false));
        Connection connection = stub(Connection.class, Map.of("getMetaData", metaData));
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return connection;
            }
        };
    }

    /** The context of the personal server's JDBC configuration over the data source; the business database's {@code NamedParameterJdbcOperations} is what Boot's JDBC template configuration gives. */
    private static AnnotationConfigApplicationContext contextOver(DataSource source) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, () -> source);
        context.registerBean("namedParameterJdbcTemplate", NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(source));
        context.register(BusinessJdbcConfiguration.class);
        context.refresh();
        return context;
    }

    private static JdbcDialect dialectOf(String product, String identifierQuote) {
        try (AnnotationConfigApplicationContext context = contextOver(serverReporting(product, identifierQuote))) {
            return context.getBean(JdbcDialect.class);
        }
    }

    private static StoreException storeFailureIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StoreException store) {
                return store;
            }
        }
        throw new AssertionError("No StoreException in the chain of " + failure, failure);
    }

    /** The server starts with the store not ready (no connection can be had): nothing of Spring Data JDBC is made, and a use before the store is ready is refused as STORE_PREPARING. */
    @Test
    void theContextStartsWithoutAConnectionAndTheDialectIsAskedFromTheConnectionAtFirstUse() {
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "STARTING"))) {
            CountingDataSource counted = new CountingDataSource(store);
            try (AnnotationConfigApplicationContext context = contextOver(counted)) {
                assertEquals(0, counted.opened(), "starting the context needs no connection");
                for (String name : List.of("jdbcDialect", "jdbcCustomConversions", "jdbcMappingContext", "jdbcConverter", "dataAccessStrategyBean", "jdbcAggregateTemplate")) {
                    assertFalse(context.getBeanFactory().containsSingleton(name), name + " is made at its first use, not at the start");
                }
                BeansException refused = assertThrows(BeansException.class, () -> context.getBean(JdbcDialect.class));
                assertEquals(StoreCode.STORE_PREPARING, storeFailureIn(refused).code());

                store.ready(h2Pool());
                JdbcDialect dialect = context.getBean(JdbcDialect.class);
                assertInstanceOf(JdbcH2Dialect.class, dialect, "the connected product's dialect");
                assertEquals(1, counted.opened());
                assertSame(dialect, context.getBean(JdbcDialect.class));
                assertEquals(1, counted.opened(), "the dialect is decided once, then kept");
            }
        }
    }

    /** Many first uses at once: one dialect (the shared resource is one bean), asked from the connection once. */
    @Test
    void concurrentFirstUsesDecideTheDialectOnce() throws Exception {
        int users = 16;
        try (PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "STARTING"))) {
            store.ready(h2Pool());
            CountingDataSource counted = new CountingDataSource(store);
            try (AnnotationConfigApplicationContext context = contextOver(counted); ExecutorService threads = Executors.newFixedThreadPool(users)) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<JdbcDialect>> asked = new ArrayList<>();
                for (int user = 0; user < users; user++) {
                    asked.add(threads.submit(() -> {
                        start.await();
                        return context.getBean(JdbcDialect.class);
                    }));
                }
                start.countDown();
                Set<JdbcDialect> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Future<JdbcDialect> one : asked) {
                    distinct.add(one.get(30, TimeUnit.SECONDS));
                }
                assertEquals(1, distinct.size(), "every first use got the same dialect");
                assertEquals(1, counted.opened(), "the connection was asked once");
            }
        }
    }

    /** MySQL's identifier quoting is the server's: backticks, or double quotes under {@code ANSI_QUOTES}; the dialect takes what the connection reports. */
    @Test
    void aMySqlConnectionGivesMySqlsDialectQuotingAsTheServerReports() {
        JdbcDialect backticks = dialectOf("MySQL", "`");
        assertInstanceOf(JdbcMySqlDialect.class, backticks);
        assertEquals("`sennard`", backticks.getIdentifierProcessing().quote("sennard"));

        JdbcDialect ansiQuotes = dialectOf("MySQL", "\"");
        assertInstanceOf(JdbcMySqlDialect.class, ansiQuotes);
        assertEquals("\"sennard\"", ansiQuotes.getIdentifierProcessing().quote("sennard"));
    }

    /** MariaDB is not MySQL: its connection gets its own dialect, quoting as it reports. */
    @Test
    void aMariaDbConnectionGivesMariaDbsDialectNotMySqls() {
        JdbcDialect backticks = dialectOf("MariaDB", "`");
        assertInstanceOf(JdbcMariaDbDialect.class, backticks);
        assertFalse(backticks instanceof JdbcMySqlDialect);
        assertEquals("`sennard`", backticks.getIdentifierProcessing().quote("sennard"));

        JdbcDialect ansiQuotes = dialectOf("MariaDB", "\"");
        assertInstanceOf(JdbcMariaDbDialect.class, ansiQuotes);
        assertEquals("\"sennard\"", ansiQuotes.getIdentifierProcessing().quote("sennard"));
    }
}
