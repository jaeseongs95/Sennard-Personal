// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder.Database;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The only way the service gets its business connections: the user's driver ({@link JdbcDrivers}) behind Spring's {@link SimpleDriverDataSource}, which a pool of the one
 * account lends from. The pool values are the design's starting values; the load tests fix them.
 *
 * <p>Before the pool is handed out the service checks the database's encoding on a connection of its own, taken from the same driver but not from the pool: the pool's 1 second
 * connection timeout is a hook's budget, and a first TLS handshake on a busy machine can take longer than that once without anything being wrong. The wait of that connection is
 * the driver's own (JDBC has no login timeout of a data source that is not the driver manager's).
 */
@Component
public class DataSourceFactory {
    private final JdbcDrivers drivers;
    private final Isolation isolation;
    private final PrivateStorage storages;

    /**
     * @param isolation the isolation level of the transactions of this edition: the edition's setting, in Spring's {@link Isolation}. {@link Isolation#DEFAULT} leaves the level to
     *     the database
     * @param storages the edition's port of the storage of secrets, through which the password file is read
     */
    @Autowired
    public DataSourceFactory(JdbcDrivers drivers, @Qualifier("storeIsolation") Isolation isolation, PrivateStorage storages) {
        this.drivers = drivers;
        this.isolation = isolation;
        this.storages = storages;
    }

    /** For the callers that only open connections of their own ({@link #direct}) and no pool: no isolation is applied. */
    public DataSourceFactory(JdbcDrivers drivers, PrivateStorage storages) {
        this(drivers, Isolation.DEFAULT, storages);
    }

    /** The checked pool of the one account; a failed start-up check (the driver, the encoding, the login) is a {@code StoreException}. */
    public HikariDataSource create(SennardDatabaseConfig config) {
        SimpleDriverDataSource direct = direct(config);
        try (Connection check = direct.getConnection()) {
            check.setAutoCommit(false);
            verifyEncoding(check);
        } catch (SQLException failure) {
            throw StoreErrors.map(failure, false);
        } catch (DataAccessException failure) {
            throw StoreErrors.map(failure, false);
        }
        return pool("sennard-store", direct, config.poolSize(), isolation);
    }

    /**
     * Connections of the one account that no pool owns (the migrations and the account's own commands): the user's driver, the URL, the user and the password read from its file.
     *
     * @throws StoreException DB_DRIVER_NOT_FOUND or DB_DRIVER_REJECTS_URL when the driver at the user's path cannot serve the URL, DB_CREDENTIAL_UNREADABLE for the password file
     */
    public SimpleDriverDataSource direct(SennardDatabaseConfig config) {
        Driver driver = drivers.find(config.driverPath(), config.jdbcUrl());
        SimpleDriverDataSource source = new SimpleDriverDataSource(driver, config.jdbcUrl());
        source.setUsername(config.ownerUser());
        // The driver's API takes the password as a String: the one place the secret becomes text, from the file the port of the storage of secrets reads (ceiling: a String cannot be cleared).
        SecretValue password = Credentials.read(config.ownerPasswordRef(), storages);
        char[] characters = password.reveal();
        try {
            source.setPassword(new String(characters));
        } finally {
            SecretValue.clear(characters);
        }
        source.setConnectionProperties(driverProperties(config));
        return source;
    }

    /** Driver options of one product that no JDBC setting stands for. */
    private static Properties driverProperties(SennardDatabaseConfig config) {
        Properties properties = new Properties();
        if (SqlBuilder.ofJdbcUrl(config.jdbcUrl()) == Database.POSTGRESQL) {
            // pgjdbc: the server's DETAIL line quotes the row ("Failing row contains ..."); the exception text goes to the log file.
            properties.setProperty("logServerErrorDetail", "false");
        }
        return properties;
    }

    private static HikariDataSource pool(String name, SimpleDriverDataSource source, int size, Isolation isolation) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(name);
        hikari.setDataSource(source);
        hikari.setMaximumPoolSize(size);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(1_000);
        hikari.setValidationTimeout(250);
        hikari.setIdleTimeout(30_000);
        hikari.setMaxLifetime(1_800_000);
        hikari.setKeepaliveTime(120_000);
        hikari.setLeakDetectionThreshold(20_000);
        // The isolation of the edition, on every DBMS: the default of the databases differs (PostgreSQL: read committed, MariaDB and MySQL: repeatable read), and what the stores write
        // is decided by the row locks and the constraints of the isolation the edition names. HikariCP takes the name of the constant of java.sql.Connection ("the constant name
        // from the Connection class such as TRANSACTION_READ_COMMITTED", its documentation of transactionIsolation), which is the name of Spring's Isolation constant
        // behind "TRANSACTION_". Applied once to each connection the pool opens, not on every borrow.
        if (isolation != Isolation.DEFAULT) {
            hikari.setTransactionIsolation("TRANSACTION_" + isolation.name());
        }
        // A database that is not up yet must not stop the service from starting: it answers STORE_UNREACHABLE instead.
        hikari.setInitializationFailTimeout(-1);
        // Auto-commit stays on (the pool's and Spring's default): a transaction turns it off for its own length (JdbcTransactionManager) and a
        // statement outside one commits by itself. A pool that lends connections with auto-commit off would lose such a statement silently.
        return new HikariDataSource(hikari);
    }

    /**
     * Two Korean syllables and an emoji: three characters (code points), 10 bytes in UTF-8, four UTF-16 units. A Korean code page (EUC_KR, KO16MSWIN949, Korean_Wansung, CP949) holds
     * the syllables and not the emoji, which no legacy code page has; so the text tells UTF-8 from a Korean database.
     */
    private static final String ENCODING_PROBE = "한글😀";

    /**
     * Text is UTF-8 from end to end; a database in another encoding cannot hold it, and is refused before anything else. The check is a query, not a setting of one DBMS (the dialect's
     * spelling of it, {@link SqlBuilder#generateEncodingProbe}): the database is asked how many characters the probe has and what it gives back of it cast to a variable-length
     * character type. A database that cannot take the text (the statement fails), one that does not read it as UTF-8 (PostgreSQL's SQL_ASCII counts its bytes) and one whose type loses
     * a character (the value comes back changed) are refused. MariaDB and MySQL cast in the connection's character set, so their schema's and the server's default character sets are asked as
     * well ({@link SqlBuilder#generateSchemaCharsetProbe}).
     */
    static void verifyEncoding(Connection connection) throws SQLException {
        SqlBuilder.Database product = SqlBuilder.ofProductName(connection.getMetaData().getDatabaseProductName());
        try {
            // The query is the dialect's (SQL Server has no CHAR_LENGTH): it returns the count and the value of the text cast to the schema's kind of VARCHAR.
            EncodingProbe answer = checkClient(connection).sql(SqlBuilder.generateEncodingProbe(product)).param(ENCODING_PROBE).param(ENCODING_PROBE)
                    .query((row, index) -> new EncodingProbe(row.getInt(1), row.getString(2))).single();
            judgeEncoding(answer, SqlBuilder.countsCodePoints(product));
            Optional<String> charsetQuery = SqlBuilder.generateSchemaCharsetProbe(product);
            if (charsetQuery.isPresent()) {
                List<CharacterSets> sets = checkClient(connection).sql(charsetQuery.get())
                        .query((row, index) -> new CharacterSets(row.getString(1), row.getString(2), row.getInt(3) == 1)).list();
                if (sets.isEmpty()) {
                    throw new StoreException(StoreCode.DB_ENCODING_MISMATCH, "The database has no current schema whose character set could be read.");
                }
                judgeCharacterSets(sets.get(0));
            }
        } catch (StoreException refused) {
            // Before DataAccessException, which StoreException extends: the reason a judgment threw must not be wrapped again.
            rollbackBeside(connection, refused);
            throw refused;
        } catch (DataAccessException failure) {
            rollbackBeside(connection, failure);
            if (isNotAboutEncoding(failure)) {
                throw StoreErrors.map(failure, false);
            }
            throw new StoreException(StoreCode.DB_ENCODING_MISMATCH, "The database cannot take UTF-8 text.", failure);
        }
        connection.rollback();
    }

    /**
     * Spring's classes of a failure that says nothing about the text: a transient one (a statement timeout or a cancel, a lock, a connection that broke and may work again), a
     * resource failure (the connection is gone, the database is full) and a recoverable one. {@code QueryTimeoutException} is among the transient ones. The other failures of the probe (the
     * statement that the database refuses, a value it cannot store) are the database refusing the text. Not decided by SQLSTATE class 22: MySQL refuses a character it cannot hold with
     * HY000 (1366).
     */
    private static boolean isNotAboutEncoding(DataAccessException failure) {
        return failure instanceof TransientDataAccessException || failure instanceof DataAccessResourceFailureException || failure instanceof RecoverableDataAccessException;
    }

    /** Ends the check's transaction after a failure; a rollback that fails too (the connection is gone) is added to the failure, which stays the reason. */
    private static void rollbackBeside(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailed) {
            failure.addSuppressed(rollbackFailed);
        }
    }

    /** What the database answered to the probe: the characters it counts in the text and the text it gives back. */
    record EncodingProbe(int characters, String value) {
    }

    /**
     * Both must be the text's own: a count of bytes is a database that does not read UTF-8, and another value (such as {@code ?} for the emoji) is a type whose code page has no such
     * text. The count is not judged where {@link SqlBuilder#countsCodePoints} is false.
     */
    static void judgeEncoding(EncodingProbe answer, boolean countsCodePoints) {
        int expected = ENCODING_PROBE.codePointCount(0, ENCODING_PROBE.length());
        if (countsCodePoints && answer.characters() != expected) {
            throw new StoreException(StoreCode.DB_ENCODING_MISMATCH, "The database does not read UTF-8 text: " + expected + " characters count as " + answer.characters() + ".");
        }
        if (!ENCODING_PROBE.equals(answer.value())) {
            throw new StoreException(StoreCode.DB_ENCODING_MISMATCH, "The database's character type does not keep UTF-8 text: it gives back '" + answer.value() + "'.");
        }
    }

    /**
     * What MariaDB and MySQL answered to {@link SqlBuilder#generateSchemaCharsetProbe}: the schema's and the server's default character set names, and the database's own verdict
     * on whether both are utf8mb4 (the names are for the message; the name is not compared here, so what spelling or case the database gives it does not matter).
     */
    record CharacterSets(String schemaSet, String serverSet, boolean utf8mb4) {
    }

    /** MariaDB and MySQL: refused unless the database says the schema's and the server's default character sets are utf8mb4, the one that holds a 4-byte character ({@code utf8mb3} and its alias {@code utf8} do not). */
    static void judgeCharacterSets(CharacterSets answer) {
        if (!answer.utf8mb4()) {
            throw new StoreException(StoreCode.DB_ENCODING_MISMATCH, "The database's default character set is " + answer.schemaSet() + " and the server's is " + answer.serverSet()
                    + "; both must be utf8mb4, which holds every character.");
        }
    }

    /** A client over the one connection the start-up check opened (it stays open, auto-commit off): Spring runs the statement and reads the row. */
    static JdbcClient checkClient(Connection connection) {
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }
}
