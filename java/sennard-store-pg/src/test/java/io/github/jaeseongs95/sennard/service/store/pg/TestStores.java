// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.service.identity.LeasePolicy;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.identity.CallAdmissions;
import io.github.jaeseongs95.sennard.service.identity.LeaseService;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.jobs.JobHandler;
import io.github.jaeseongs95.sennard.service.store.modules.ModuleStore;

/**
 * The tests' way into the store. It is the real wiring ({@link StoreConfiguration} over the real pool, the real transaction manager and
 * the real repositories), and only adds what a store test needs and production code gets from its callers: opening a transaction around
 * several repository calls with {@code write}, {@code plain}, {@code snapshot} and the lambda form {@code transaction(options, tx -> ...)}.
 * {@code TxOptions} and {@code QueryResult} are test-only records.
 */
public final class TestStores {
    private static final Map<DataSource, TestStores> ALL = new ConcurrentHashMap<>();

    /** One context per pool, as a server has one. */
    public static TestStores of(DataSource app) {
        return ALL.computeIfAbsent(app, TestStores::new);
    }

    private final AnnotationConfigApplicationContext context;
    private final StoreTransactionManager manager;
    private final JdbcTemplate template;

    private TestStores(DataSource app) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("store", DataSource.class, () -> app);
        context.registerBean(SqlBuilder.class, () -> new SqlBuilder(app));
        context.registerBean(RecordingJobs.class, RecordingJobs::new);
        context.registerBean(SecretSealer.class, TestSealer::new); // the edition's sealer (the store's encrypted columns need one)
        context.registerBean("clock", java.time.Clock.class, java.time.Clock::systemUTC); // the application's one Clock bean (AppConfiguration in the product)
        context.register(io.github.jaeseongs95.sennard.app.RandomConfiguration.class); // the application's one SecureRandom bean, which the stores take
        context.register(JacksonAutoConfiguration.class); // the JsonMapper bean the continuity and workflow stores take, as Boot makes it
        context.register(StoreConfiguration.class);
        context.refresh();
        manager = context.getBean(StoreTransactionManager.class);
        template = new JdbcTemplate(app);
        template.setExceptionTranslator(StoreErrors.translator(app));
    }

    /** The repository of this type, the bean of the context. */
    public <S extends ModuleStore> S repository(Class<S> type) {
        return context.getBean(type);
    }

    /** The body in one transaction, as a service method's {@code @Transactional} is; {@code store} names the repository the body works with (a store has no lock to take). */
    public <T> T write(ModuleStore store, Supplier<T> body) {
        return plain(body);
    }

    /** One transaction. */
    public <T> T plain(Supplier<T> body) {
        return new TransactionTemplate(manager).execute(status -> body.get());
    }

    /** REPEATABLE READ READ ONLY. */
    public <T> T snapshot(Supplier<T> body) {
        return snapshotTemplate().execute(status -> body.get());
    }

    private TransactionTemplate snapshotTemplate() {
        TransactionTemplate snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return snapshot;
    }

    // ------------------------------------------------------------------------------------------------ the identity services

    /**
     * The session services of this context, as beans of the application: every call in a transaction as the {@code @Transactional} of the service says (the same advice the
     * product's context has, here in a child context over this one).
     */
    public SessionRegistry registry(SessionKeys keys, LeasePolicy policy) {
        AnnotationConfigApplicationContext services = services(SessionRegistry.class);
        services.registerBean(SessionKeys.class, () -> keys);
        services.registerBean(LeasePolicy.class, () -> policy);
        services.refresh();
        return services.getBean(SessionRegistry.class);
    }

    public LeaseService leases(LeasePolicy policy) {
        AnnotationConfigApplicationContext services = services(LeaseService.class);
        services.registerBean(LeasePolicy.class, () -> policy);
        services.refresh();
        return services.getBean(LeaseService.class);
    }

    public CallAdmissions callAdmissions() {
        AnnotationConfigApplicationContext services = services(CallAdmissions.class);
        services.refresh();
        return services.getBean(CallAdmissions.class);
    }

    private AnnotationConfigApplicationContext services(Class<?> service) {
        AnnotationConfigApplicationContext services = new AnnotationConfigApplicationContext();
        services.setParent(context);
        services.register(ServiceAdvice.class, service);
        return services;
    }

    /** What a context that holds services needs: the transaction advice, which the store's own configuration has for its beans. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class ServiceAdvice {
    }

    // ------------------------------------------------------------------------------------------- the statement-level test form

    public <T> T transaction(TxOptions options, Function<Tx, T> body) {
        TransactionTemplate transactions = options.readOnlySnapshot() ? snapshotTemplate() : new TransactionTemplate(manager);
        if (options.timeoutSeconds() != null) {
            transactions.setTimeout(options.timeoutSeconds());
        }
        return transactions.execute(status -> {
            return body.apply(new Tx());
        });
    }

    public QueryResult query(String sql, Object... params) {
        return transaction(TxOptions.write(), tx -> tx.query(sql, params));
    }

    /** What the statement-level tests run: any statement, its rows by label or its update count. */
    public final class Tx {
        public QueryResult query(String sql, Object... params) {
            return template.execute((ConnectionCallback<QueryResult>) connection -> run(connection, sql, params));
        }

        /** A savepoint: the body's writes are undone when it throws, the outer transaction goes on. */
        public <T> T nested(Function<Tx, T> body) {
            return new TransactionTemplate(manager, nestedDefinition()).execute(status -> body.apply(this));
        }
    }

    private static TransactionDefinition nestedDefinition() {
        org.springframework.transaction.support.DefaultTransactionDefinition definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return definition;
    }

    private static QueryResult run(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i] instanceof java.util.UUID id ? id.toString() : params[i]); // a UUID column is CHAR(36) with the text form
            }
            if (!statement.execute()) {
                return new QueryResult(List.of(), statement.getUpdateCount());
            }
            try (ResultSet rows = statement.getResultSet()) {
                ResultSetMetaData meta = rows.getMetaData();
                List<Map<String, Object>> result = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        row.put(meta.getColumnLabel(column), rows.getObject(column));
                    }
                    result.add(row);
                }
                return new QueryResult(result, result.size());
            }
        }
    }

    /** The job kind {@code test}: its input says how it ends, and every run is counted by input. */
    public static final class RecordingJobs implements JobHandler {
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> runs = new ConcurrentHashMap<>();

        @Override
        public String kind() {
            return "test";
        }

        @Override
        public String run(String inputJson) {
            runs.computeIfAbsent(inputJson, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            return switch (inputJson) {
                case "boom" -> throw new IllegalStateException("the job failed");
                case "store-busy" -> throw new StoreException(StoreCode.STORE_BUSY, "the job's store was busy");
                default -> "{\"done\":true}";
            };
        }

        public int runs(String inputJson) {
            java.util.concurrent.atomic.AtomicInteger count = runs.get(inputJson);
            return count == null ? 0 : count.get();
        }
    }

    public RecordingJobs jobs() {
        return context.getBean(RecordingJobs.class);
    }

    public AnnotationConfigApplicationContext context() {
        return context;
    }

    public StoreTransactionManager manager() {
        return manager;
    }
}
