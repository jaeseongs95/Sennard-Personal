// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.jaeseongs95.sennard.app.AppConfiguration;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * A Spring context of the rewritten areas (the repositories and services of sennard-app) on the real database (PostgreSQL), for the tests of every area that has moved to
 * Spring Data JDBC (board now, messaging next). It is the assembly with nothing replaced: the product's classes and Boot's own auto-configuration, the way the
 * server's {@code @SpringBootApplication} gets them, and the only test seam is the {@link Clock} (a bean registered before the refresh, which wins over the
 * application's). Two connection modes, the two ways sennard-app is meant to run:
 *
 * <ul>
 * <li>{@link #appConnection}: this application's way. The DataSource is {@link PreparingDataSource} named {@code store} (the pool of the one account, handed
 * over once the context is up, as the personal server does), the transaction manager is {@link StoreTransactionManager}, Boot supplies the
 * {@code NamedParameterJdbcTemplate}, validation and Jackson.</li>
 * <li>{@link #bootDefault}: the Boot default path. A plain DataSource bean and nothing else of the store: Boot's {@code JdbcTransactionManager} and no advisory lock.</li>
 * </ul>
 *
 * The same services and repositories run in both. The pool is the test JVM's one ({@link BusinessDb#pool()}), which both modes borrow from: closing a context
 * does not close it (a pool is closed by whoever made it, and the JUnit root store made this one). A test that needs another connection to the database uses
 * {@link BusinessDb#connect()}.
 */
final class RewrittenAreasContext implements AutoCloseable {
    /** A clock a test sets and moves. */
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** What both modes take from Boot, and the application layer by package scan (the services, the repositories, the properties, the message source). */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AppConfiguration.class)
    @ImportAutoConfiguration({JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class, DataJdbcRepositoriesAutoConfiguration.class, ValidationAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    static class Common {
    }

    /** This application's connection: the store's transaction manager (the begin failures of the store are its own reasons). The DataSource {@code store} is registered by the factory method. */
    @Configuration(proxyBeanMethods = false)
    static class StoreConnection {
        @Bean
        StoreTransactionManager transactionManager(@Qualifier("store") DataSource store) {
            return new StoreTransactionManager(store);
        }
    }

    /** Boot's own transaction manager over the DataSource bean of the context (the Boot default path has no store of its own). */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceTransactionManagerAutoConfiguration.class})
    static class BootConnection {
    }

    private final AnnotationConfigApplicationContext context;
    private final TestClock clock;
    private final PreparingDataSource store;

    private RewrittenAreasContext(AnnotationConfigApplicationContext context, TestClock clock, PreparingDataSource store) {
        this.context = context;
        this.clock = clock;
        this.store = store;
    }

    /**
     * The application's connection, started the way the server starts: the context is refreshed first, with the DataSource not ready (a connection answers
     * STORE_PREPARING), and {@link #makeReady} then opens the pools. A context that needs a connection to start fails here.
     */
    static RewrittenAreasContext appConnectionNotReady(Instant start) {
        return appConnectionNotReady(start, dialect()); // this test database's dialect; the personal server gets it from its connection at the first use (BusinessJdbcConfiguration), which this context cannot, so the property states it
    }

    /** The value of {@code spring.data.jdbc.dialect} for the database of the test run, PostgreSQL (Boot's {@code DataJdbcDatabaseDialect.POSTGRESQL}; the setting binds without regard to case). */
    static String dialect() {
        return "postgresql";
    }

    /** The Spring Data JDBC dialect the database of the test run gets, whether the setting states it or Boot detects it from the connection. */
    static Class<? extends JdbcDialect> dialectType() {
        return JdbcPostgresDialect.class;
    }

    /** The same with {@code spring.data.jdbc.dialect} set: this context has Boot's Data JDBC configuration, not the personal server's lazy one (BusinessJdbcConfiguration), so the property states the dialect; null leaves it unset, and Boot detects the dialect from a connection. */
    static RewrittenAreasContext appConnectionNotReady(Instant start, String dialect) {
        return appConnectionNotReady(start, dialect, Map.of());
    }

    /** The same with settings of the application ({@code sennard.*}) a test sets, as an operator's {@code application.yaml} would (a lower limit of the messaging queue, for one). */
    static RewrittenAreasContext appConnection(Instant start, Map<String, Object> settings) {
        RewrittenAreasContext started = appConnectionNotReady(start, dialect(), settings);
        started.makeReady();
        return started;
    }

    private static RewrittenAreasContext appConnectionNotReady(Instant start, String dialect, Map<String, Object> settings) {
        TestClock clock = new TestClock(start);
        PreparingDataSource store = new PreparingDataSource(() -> Map.of("phase", "STARTING"));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (dialect != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dialect", Map.of("spring.data.jdbc.dialect", dialect)));
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("settings", settings));
        context.registerBean("store", DataSource.class, () -> store, notClosedWithTheContext()); // closing it would close the shared pool it was handed
        context.registerBean("clock", Clock.class, () -> clock);
        context.registerBean(io.github.jaeseongs95.sennard.service.secret.PrivateStorage.class, TestPrivateStorage::new); // the edition's port of the storage of secrets, which the attachment folders and keys go through (AGS3-D-0268)
        context.register(Common.class, StoreConnection.class);
        context.refresh();
        return new RewrittenAreasContext(context, clock, store);
    }

    /** The application's connection with the database ready. */
    static RewrittenAreasContext appConnection(Instant start) {
        RewrittenAreasContext started = appConnectionNotReady(start);
        started.makeReady();
        return started;
    }

    /**
     * The Boot default path: a plain DataSource bean (the shared pool) and Boot's {@code JdbcTransactionManager}, nothing of the store. No dialect setting: Boot detects
     * it from the connection. Boot would build a pool of its own from {@code spring.datasource.*}; the account's connection limit has room for one pool, so the bean is the
     * test JVM's.
     */
    static RewrittenAreasContext bootDefault(Instant start) {
        TestClock clock = new TestClock(start);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, BusinessDb::pool, notClosedWithTheContext());
        context.registerBean("clock", Clock.class, () -> clock);
        context.registerBean(io.github.jaeseongs95.sennard.service.secret.PrivateStorage.class, TestPrivateStorage::new); // the edition's port of the storage of secrets
        context.register(Common.class, BootConnection.class);
        context.refresh();
        return new RewrittenAreasContext(context, clock, null);
    }

    /** Hands the shared pool to the DataSource {@code store} (what the personal server does when its database is up). */
    void makeReady() {
        store.ready(BusinessDb.pool());
    }

    /** The pool is the JUnit root store's: a context that borrows it must not close it with its beans ({@code @Bean(destroyMethod = "")}). */
    private static BeanDefinitionCustomizer notClosedWithTheContext() {
        return definition -> definition.setDestroyMethodName("");
    }

    TestClock clock() {
        return clock;
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    PlatformTransactionManager transactionManager() {
        return context.getBean(PlatformTransactionManager.class);
    }

    /** The DataSource of the context ({@code store} or Boot's). */
    DataSource dataSource() {
        return context.getBean(DataSource.class);
    }

    @Override
    public void close() {
        context.close(); // the DataSource bean is not closed with it: the pool is the test JVM's
    }
}
