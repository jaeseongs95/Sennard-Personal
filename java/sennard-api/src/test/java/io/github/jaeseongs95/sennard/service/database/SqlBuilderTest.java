// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.zaxxer.hikari.HikariDataSource;

/** Cases: the seven dialects render a bound limit; a complete statement keeps its parameter; an unknown database is refused; the default embedded database is H2. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlBuilderTest {
    private AnnotationConfigApplicationContext context;

    /** The DataSource and the SqlBuilder are beans of one context, so the builder is injected with the DataSource the way the application's is. */
    @BeforeAll
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:sqlLimitTest");
            pool.setUsername("sa");
            pool.setMaximumPoolSize(2);
            return pool;
        }, definition -> definition.setDestroyMethodName("close"));
        context.register(SqlBuilder.class);
        context.refresh();
    }

    @AfterAll
    void closeContext() {
        context.close();
    }

    /** One or three session pairs use distinct bound names; the optional text condition only appears when requested. */
    @Test
    void assemblesSessionConditionsAndOptionalSearch() {
        SqlBuilder builder = h2Builder();
        assertEquals("SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0)) ORDER BY received_at DESC FETCH FIRST :limit ROWS ONLY",
                builder.query("SELECT * FROM prompt WHERE ").orGroup("(host = :host%1$d AND session_id = :session%1$d)", 1)
                        .orderBy("received_at DESC").limit(":limit"));
        assertEquals("SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0) OR (host = :host1 AND session_id = :session1) OR (host = :host2 AND session_id = :session2)) AND text LIKE :pattern ORDER BY received_at DESC FETCH FIRST :limit ROWS ONLY",
                builder.query("SELECT * FROM prompt WHERE ").orGroup("(host = :host%1$d AND session_id = :session%1$d)", 3)
                        .and("text LIKE :pattern").orderBy("received_at DESC").limit(":limit"));
    }

    /** Closing-event alternatives are positional; an empty group cannot match any event. */
    @Test
    void assemblesClosingEventConditions() {
        SqlBuilder builder = h2Builder();
        assertEquals("SELECT (1 = 0)", builder.query("SELECT ").orGroup("event = ?", 0).build());
        assertEquals("SELECT (event = ? OR event = ?)", builder.query("SELECT ").orGroup("event = ?", 2).build());
    }

    @Test
    void addsTheDialectLimitToAStatement() {
        assertEquals("SELECT id FROM item ORDER BY id FETCH FIRST :count ROWS ONLY",
                h2Builder().applyLimit("SELECT id FROM item ORDER BY id", ":count"));
        assertEquals("SELECT id FROM item ORDER BY id LIMIT ?",
                "SELECT id FROM item ORDER BY id " + SqlBuilder.generateLimit(SqlBuilder.Database.MYSQL, "?"));
    }

    @Test
    void rendersEachDialect() {
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.ORACLE, SqlBuilder.Database.DB2}) {
            assertEquals("FETCH FIRST :count ROWS ONLY", SqlBuilder.generateLimit(db, ":count"));
        }
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.SQLSERVER, SqlBuilder.Database.MARIADB}) {
            assertEquals("OFFSET 0 ROWS FETCH NEXT :count ROWS ONLY", SqlBuilder.generateLimit(db, ":count"));
        }
        assertEquals("LIMIT :count", SqlBuilder.generateLimit(SqlBuilder.Database.MYSQL, ":count"));
    }

    @Test
    void declaresALongTextColumnForEachOfTheSevenDialects() {
        assertEquals("TEXT", SqlBuilder.generateLongText(SqlBuilder.Database.POSTGRESQL));
        assertEquals("CHARACTER LARGE OBJECT(2147483647)", SqlBuilder.generateLongText(SqlBuilder.Database.H2));
        assertEquals("CLOB", SqlBuilder.generateLongText(SqlBuilder.Database.ORACLE));
        assertEquals("CLOB(2147483647)", SqlBuilder.generateLongText(SqlBuilder.Database.DB2));
        assertEquals("NVARCHAR(MAX)", SqlBuilder.generateLongText(SqlBuilder.Database.SQLSERVER));
        assertEquals("LONGTEXT", SqlBuilder.generateLongText(SqlBuilder.Database.MARIADB));
        assertEquals("LONGTEXT", SqlBuilder.generateLongText(SqlBuilder.Database.MYSQL));
        assertEquals(SqlBuilder.Database.values().length, 7, "a new dialect needs its declaration (the switch is exhaustive and fails to compile first)");
    }

    @Test
    void probesTheEncodingWithTheCharacterCountOfEachDialect() {
        assertEquals("SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.POSTGRESQL));
        assertEquals("SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.H2));
        assertEquals("SELECT LENGTH(CAST(? AS VARCHAR2(40))), CAST(? AS VARCHAR2(40)) FROM DUAL", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.ORACLE));
        assertEquals("SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(40)) USING CODEUNITS32), CAST(? AS VARCHAR(40)) FROM SYSIBM.SYSDUMMY1",
                SqlBuilder.generateEncodingProbe(SqlBuilder.Database.DB2));
        assertEquals("SELECT LEN(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.SQLSERVER));
        assertEquals("SELECT CHAR_LENGTH(CAST(? AS CHAR(40))), CAST(? AS CHAR(40))", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.MARIADB));
        assertEquals("SELECT CHAR_LENGTH(CAST(? AS CHAR(40))), CAST(? AS CHAR(40))", SqlBuilder.generateEncodingProbe(SqlBuilder.Database.MYSQL));
        for (SqlBuilder.Database db : SqlBuilder.Database.values()) {
            assertEquals(2, SqlBuilder.generateEncodingProbe(db).chars().filter(c -> c == '?').count(), db + " takes the text twice: for the count and for the value");
        }
    }

    @Test
    void judgesTheCountOfCodePointsOnEveryDialectButH2() {
        for (SqlBuilder.Database db : SqlBuilder.Database.values()) {
            assertEquals(db != SqlBuilder.Database.H2, SqlBuilder.countsCodePoints(db), db.name());
        }
    }

    @Test
    void asksTheSchemaCharacterSetOnMariaDbAndMySqlOnly() {
        // Third column: the database's own verdict, with the case of both names folded first (LOWER), so the code compares no name.
        String query = "SELECT DEFAULT_CHARACTER_SET_NAME, @@character_set_server, "
                + "CASE WHEN LOWER(DEFAULT_CHARACTER_SET_NAME) = 'utf8mb4' AND LOWER(@@character_set_server) = 'utf8mb4' THEN 1 ELSE 0 END "
                + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()";
        assertEquals(java.util.Optional.of(query), SqlBuilder.generateSchemaCharsetProbe(SqlBuilder.Database.MARIADB));
        assertEquals(java.util.Optional.of(query), SqlBuilder.generateSchemaCharsetProbe(SqlBuilder.Database.MYSQL));
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.ORACLE, SqlBuilder.Database.DB2,
                SqlBuilder.Database.SQLSERVER}) {
            assertEquals(java.util.Optional.empty(), SqlBuilder.generateSchemaCharsetProbe(db), db.name());
        }
    }

    @Test
    void rejectsAnUnknownDatabase() {
        var failure = assertThrows(io.github.jaeseongs95.sennard.service.store.StoreException.class, () -> SqlBuilder.database(DatabaseDriver.UNKNOWN));
        assertEquals(io.github.jaeseongs95.sennard.service.store.StoreCode.UNSUPPORTED_DATABASE_PRODUCT, failure.code());
    }

    /** A matching product is accepted; a mismatch identifies both the configured and actual products. */
    @Test
    void checksTheConnectedProduct() {
        SqlBuilder.requireSame("jdbc:postgresql://localhost/db", "PostgreSQL");
        var mismatch = assertThrows(io.github.jaeseongs95.sennard.service.store.StoreException.class,
                () -> SqlBuilder.requireSame("jdbc:postgresql://localhost/db", "H2"));
        assertEquals(io.github.jaeseongs95.sennard.service.store.StoreCode.DATABASE_PRODUCT_MISMATCH, mismatch.code());
        org.junit.jupiter.api.Assertions.assertTrue(mismatch.getMessage().contains("POSTGRESQL"));
        org.junit.jupiter.api.Assertions.assertTrue(mismatch.getMessage().contains("H2"));
    }

    @Test
    void selectsTheDialectFromTheConfiguredUrl() {
        assertEquals(SqlBuilder.Database.POSTGRESQL, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:postgresql://localhost/db")));
        assertEquals(SqlBuilder.Database.ORACLE, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/db")));
        assertEquals(SqlBuilder.Database.DB2, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:db2://localhost/db")));
        assertEquals(SqlBuilder.Database.SQLSERVER, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:sqlserver://localhost")));
        assertEquals(SqlBuilder.Database.MARIADB, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:mariadb://localhost/db")));
        assertEquals(SqlBuilder.Database.MYSQL, SqlBuilder.database(DatabaseDriver.fromJdbcUrl("jdbc:mysql://localhost/db")));
    }

    @Test
    void recognizesTheEmbeddedH2Database() {
        assertEquals("FETCH FIRST ? ROWS ONLY", h2Builder().generateLimit("?"));
        h2Builder().requireSame("H2");
    }

    /** The builder the application has: the bean of the context, over the context's one DataSource (a pool of an in-memory H2), as the product wires it. */
    private SqlBuilder h2Builder() {
        return context.getBean(SqlBuilder.class);
    }
}
