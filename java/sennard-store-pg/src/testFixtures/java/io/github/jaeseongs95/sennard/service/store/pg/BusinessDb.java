// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import java.time.Clock;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder.Database;

/**
 * The database the tests of the business schema run on (PostgreSQL, the one database of the business tests: development principle 7.3): the one the person running them names, with the account that owns the
 * {@code sennard} schema. It is the one account the product connects as, too. The connection has no default: the JDBC URL, the user, the file whose first line is the password
 * and the JDBC driver (a jar or a folder of jars; the build passes the folder of the drivers it copies, see {@code businessDbDrivers} in java/build.gradle.kts) come from the
 * system properties {@code sennard.test.businessDb.url}, {@code .user}, {@code .passwordFile} and {@code .driverPath} (the Gradle task {@code businessDbTest} passes them from its own
 * properties of the same names and fails before it starts when one is missing) or from the environment variables {@code SENNARD_TEST_BUSINESS_DB_URL}, {@code _USER},
 * {@code _PASSWORD_FILE} and {@code _DRIVER_PATH}. Only the product's own code ({@link DataSourceFactory}) reads the password file; it is in no log, message or command line.
 *
 * <p>The tests run on PostgreSQL only; {@link #product()} is the product the URL names, which a check of the product-and-URL match asks.
 *
 * <p>A test that cannot have this database fails: a setting that is not given, a missing password file or an unreachable server is an error, never a skipped test.
 * The tests that need it carry the JUnit tag {@code business-db} and run in {@code businessDbTest}, not in {@code test} (java/build.gradle.kts). Every test class starts
 * from nothing: {@link #reset()} drops the schema {@code sennard} (and the {@code sennard_*} schemas a test made beside it, and only those) and migrates it again, so the classes do not depend on each other or
 * on what an earlier run left behind. Nothing else of the database is touched. The build runs one test JVM, one class at a time, and one task at a time on this
 * database (a Gradle shared build service with one user, java/build.gradle.kts).
 *
 * <p>The test JVM has one pool on the database, {@link BusinessDbExtension}'s, and every connection of the tests is borrowed from it: the account's connection limit
 * is shared by every test of the run, so no class makes a pool or a connection of its own.
 */
public final class BusinessDb {
    public static final String URL_PROPERTY = "sennard.test.businessDb.url";
    public static final String USER_PROPERTY = "sennard.test.businessDb.user";
    public static final String PASSWORD_FILE_PROPERTY = "sennard.test.businessDb.passwordFile";
    public static final String DRIVER_PATH_PROPERTY = "sennard.test.businessDb.driverPath";
    /**
     * The pool a product under test opens by itself (the server of a test of sennard-personal gets it as {@code pool-size}): four connections, the product's embedded pool.
     * The tests' own connections are not this: they borrow from {@link #pool()}, whose size is {@link BusinessDbExtension#POOL_SIZE}.
     */
    public static final int POOL_SIZE = 4;

    /** The business schema {@code sennard}, and the schemas a test of the runner makes beside it ({@code sennard_} and a name), which the reset drops with it. */
    private static final Pattern BUSINESS_SCHEMA = Pattern.compile("sennard(_[a-z0-9_]+)?");
    /** The tables {@code sennard} keeps through {@link #clearData()}: the migration histories, the module versions and the glossary the migrations load. */
    private static final String KEPT_TABLES = "'expand_history', 'contract_history', 'module_schema', 'glossary_metadata', 'glossary_entries', 'glossary_forms'";

    private BusinessDb() {
    }

    /** The settings a product under test connects with: the one account, loopback plain text, the pool of {@link #POOL_SIZE}. */
    public static SennardDatabaseConfig config() {
        return config(POOL_SIZE);
    }

    public static SennardDatabaseConfig config(int poolSize) {
        return new RemoteConfigProvider(setting(URL_PROPERTY, "SENNARD_TEST_BUSINESS_DB_URL"), setting(USER_PROPERTY, "SENNARD_TEST_BUSINESS_DB_USER"),
                Path.of(setting(PASSWORD_FILE_PROPERTY, "SENNARD_TEST_BUSINESS_DB_PASSWORD_FILE")), Path.of(setting(DRIVER_PATH_PROPERTY, "SENNARD_TEST_BUSINESS_DB_DRIVER_PATH")), poolSize)
                .provide();
    }

    /** The database product the URL of the tests names: the one thing a test asks when its expectation depends on the DBMS. */
    public static Database product() {
        return SqlBuilder.ofJdbcUrl(config().jdbcUrl());
    }

    /** The one {@link DataSourceFactory} of this test JVM ({@link BusinessDbExtension#dataSources()}): the user's driver is read from its jar once. */
    public static DataSourceFactory dataSources() {
        return BusinessDbExtension.dataSources();
    }

    /** The {@link DataSourceFactory} of an edition whose isolation setting is {@code isolation}. */
    public static DataSourceFactory dataSources(org.springframework.transaction.annotation.Isolation isolation) {
        return BusinessDbExtension.dataSources(isolation);
    }

    /** The migrations over {@link #dataSources()}, stamped by the system clock. */
    public static Migrations migrations() {
        return new Migrations(Clock.systemUTC(), dataSources());
    }

    /** A setting of the test database: the system property, else the environment variable; neither is an error, so that a run without a database never guesses one. */
    private static String setting(String property, String variable) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(variable);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The business database of the tests is not named: " + property + " (or the environment variable " + variable + ") is not set. "
                    + "Run the tests that need it with ./gradlew businessDbTest -Psennard.test.businessDb.url=<jdbc url> -Psennard.test.businessDb.user=<account> "
                    + "-Psennard.test.businessDb.passwordFile=<file> (the driver path is given by the build).");
        }
        return value;
    }

    /**
     * The one pool of this test JVM ({@link BusinessDbExtension#pool()}): the fixtures, the checks, the Spring contexts and the stores of the tests all borrow from it,
     * so the connections of the run can never be more than its size.
     */
    public static HikariDataSource pool() {
        return BusinessDbExtension.pool();
    }

    /** A connection borrowed from the shared pool, for the fixtures and the checks of the tests; the caller closes it, which gives it back. */
    public static Connection connect() throws SQLException {
        return pool().getConnection();
    }

    /** The schema {@code sennard} of the database and the {@code sennard_*} schemas a test made beside it, and nothing else. */
    public static List<String> businessSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'sennard' OR schema_name LIKE 'sennard!_%' ESCAPE '!' ORDER BY schema_name")) {
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
        }
        return schemas;
    }

    /** Drops the schema {@code sennard} (and the {@code sennard_*} schemas a test made) with what is in it. The one place a test removes anything from the shared database. */
    public static void dropBusinessSchemas() throws SQLException {
        List<String> schemas = businessSchemas();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String schema : schemas) {
                if (!BUSINESS_SCHEMA.matcher(schema).matches()) {
                    throw new IllegalStateException("a schema that is not a business schema was about to be dropped: " + schema);
                }
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
        // A pooled connection keeps what it learned of the dropped tables and types (the driver's prepared statements); the schemas are made anew, so the pool's connections are
        // replaced as they come back, and the next test starts on connections that know nothing of the old ones.
        pool().getHikariPoolMXBean().softEvictConnections();
    }

    /**
     * The start of every test: the rows of every business table removed, so that a test sees only what it wrote itself (the module counts, the messaging capacity
     * and the keys are the service's as a whole, and the tests of a class run on one database). The migration histories, the module versions and the glossary the migrations
     * load ({@link #KEPT_TABLES}) are kept. It truncates only tables of the schema {@code sennard}.
     */
    public static void clearData() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT table_schema, table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' "
                        + "AND table_schema = 'sennard' AND table_name NOT IN (" + KEPT_TABLES + ") ORDER BY table_schema, table_name")) {
            while (rows.next()) {
                if (!BUSINESS_SCHEMA.matcher(rows.getString(1)).matches()) {
                    throw new IllegalStateException("a schema that is not a business schema was about to be truncated: " + rows.getString(1));
                }
                tables.add(rows.getString(1) + "." + rows.getString(2));
            }
            if (tables.isEmpty()) {
                return;
            }
            statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }

    /** The start of every test class: the business schemas dropped and migrated again (the number of migration scripts applied). */
    public static int reset() throws SQLException {
        dropBusinessSchemas();
        return migrations().expand(config());
    }
}
