// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.springframework.transaction.annotation.Isolation;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * The one business-database pool of a test JVM (design principle 3.9: a shared resource is one, and everyone borrows from it). A test account may be
 * limited in the connections it holds at once (the pool is sized for {@link #ACCOUNT_CONNECTION_LIMIT}); a pool per test class, a second pool inside a class and a connection opened
 * outside any pool each took a share of it, and together they went over the limit ("too many connections for role"). Now every test of the JVM borrows from this
 * pool: {@link BusinessDb#connect()}, {@link BusinessDb#clearData()}, {@link BusinessDb#reset()}, the Spring contexts of the tests and the stores.
 *
 * <p>The pool is a value of the JUnit root store ({@link ExtensionContext#getRoot()}), made on first use by {@code computeIfAbsent} with the product's own factory
 * ({@link DataSourceFactory#create}); it is {@code AutoCloseable}, so the engine closes it when the root context ends, after the last test of the JVM. The extension is
 * found by the engine by itself ({@code META-INF/services}, {@code junit.jupiter.extensions.autodetection.enabled}, which the Gradle build sets for the modules on the
 * business database); it only remembers the root context at the first class and makes nothing, so a run that never touches the database (the tests tagged
 * {@code business-db} left out) never connects. The only static state is that root context; the pool belongs to the store.
 *
 * <p>Its size is {@link #POOL_SIZE}, fixed, and checked against the account's limit the moment the pool is made: a size that leaves no room fails the run at once, not
 * when the limit is hit by chance.
 */
public final class BusinessDbExtension implements BeforeAllCallback {
    /**
     * The smallest connection limit (CONNECTION LIMIT of the role, which the database's administrator sets and this build never changes) the pool is sized for: the
     * pool and the reserved connections stay below it. An account with a smaller limit fails the run at once ({@link #verify} reads the real value from the server);
     * an account with a larger limit, or none, only leaves the pool room it does not use.
     */
    public static final int ACCOUNT_CONNECTION_LIMIT = 10;
    /**
     * The connections a run needs besides the pool: the connection a migration or the start-up encoding check opens by itself (one at a time, the product's own code
     * opens it) and the pool of the one product lifecycle test that makes its own (two connections, StoreClusterTest). The pool and these must fit the limit.
     */
    public static final int RESERVED_CONNECTIONS = 3;
    /**
     * Six backends: the cases that race eight to sixteen callers wait for a connection (the pool's connection timeout, the product's one second; a transaction of a
     * test is short) instead of holding one each.
     */
    public static final int POOL_SIZE = ACCOUNT_CONNECTION_LIMIT - RESERVED_CONNECTIONS - 1;
    private static final Namespace NAMESPACE = Namespace.create(BusinessDbExtension.class);
    private static final String POOL = "pool";
    private static final String DRIVERS = "drivers";
    private static volatile ExtensionContext root;

    /** The engine instantiates the extension; it holds nothing of its own. */
    public BusinessDbExtension() {
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        root = context.getRoot();
    }

    /** The pool of this JVM, made on the first call. A call before any test class has started is a failure: the engine has not yet told this extension where the pool lives. */
    public static HikariDataSource pool() {
        ExtensionContext holder = root;
        if (holder == null) {
            throw new IllegalStateException("The business pool is asked for outside a test run: the extension BusinessDbExtension has not seen a test class "
                    + "(META-INF/services/org.junit.jupiter.api.extension.Extension and junit.jupiter.extensions.autodetection.enabled=true).");
        }
        return holder.getStore(NAMESPACE).computeIfAbsent(POOL, key -> make(), HikariDataSource.class);
    }

    /**
     * The product's way to a connection, over the one {@link JdbcDrivers} of this JVM: the user's driver jar is read by one class loader, kept in the root store and closed
     * with it, after the pool that was made from it.
     */
    public static DataSourceFactory dataSources() {
        return dataSources(Isolation.READ_COMMITTED);
    }

    /** The same, for an edition whose setting is {@code isolation}. */
    public static DataSourceFactory dataSources(Isolation isolation) {
        ExtensionContext holder = root;
        if (holder == null) {
            throw new IllegalStateException("The business data sources are asked for outside a test run: the extension BusinessDbExtension has not seen a test class.");
        }
        return new DataSourceFactory(holder.getStore(NAMESPACE).computeIfAbsent(DRIVERS, key -> new JdbcDrivers(), JdbcDrivers.class), isolation, new TestPrivateStorage());
    }

    private static HikariDataSource make() {
        HikariDataSource made = dataSources().create(BusinessDb.config(POOL_SIZE));
        try {
            verify(made);
        } catch (SQLException | RuntimeException failure) {
            made.close();
            throw new IllegalStateException("The business pool of the tests does not fit the account's connection limit: " + failure.getMessage(), failure);
        }
        return made;
    }

    /**
     * The pool and the reserved connections must stay under the limit the server states for the account and for the database; a limit of none ({@code -1}) is no
     * limit. A limit that is below what the pool needs is a reason to give the account more connections, not to run on.
     */
    private static void verify(HikariDataSource made) throws SQLException {
        long limit = connectionLimit(made);
        if (limit > 0 && POOL_SIZE + RESERVED_CONNECTIONS >= limit) {
            throw new IllegalStateException("a pool of " + POOL_SIZE + " and " + RESERVED_CONNECTIONS + " reserved connections do not stay below the limit of " + limit);
        }
    }

    /**
     * The smallest limit the server states for the current account and database, or -1 when none has one: PostgreSQL's {@code rolconnlimit} and {@code datconnlimit}.
     */
    public static long connectionLimit(HikariDataSource source) throws SQLException {
        String sql = "SELECT (SELECT rolconnlimit FROM pg_roles WHERE rolname = current_user), (SELECT datconnlimit FROM pg_database WHERE datname = current_database())";
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            long smallest = -1;
            for (int column = 1; column <= 2; column++) {
                long limit = rows.getLong(column);
                if (limit > 0 && (smallest < 0 || limit < smallest)) {
                    smallest = limit;
                }
            }
            return smallest;
        }
    }
}
