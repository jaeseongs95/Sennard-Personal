// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the Spring JDBC the stores already use (JdbcClient) does with an enum, on real PostgreSQL. The answer decides how the stores write and
 * read a closed value set: Spring Data JDBC documents "Enums get mapped to their names" (reference, Mapping, Supported Types); the plain JdbcClient
 * path has its own rules, and each one is a case here. The probe table is a TEMPORARY table of one connection to the shared database (PostgreSQL): it lives in
 * the session's own space (PostgreSQL's pg_temp schema), touches nothing of the {@code sennard} schema, and goes away when the connection closes.
 */
@BusinessDbTest
class EnumMappingTest {
    enum Phase { QUEUED, RUNNING, NEEDS_INPUT }

    record Row(int id, Phase phase) {
    }

    private static Connection connection;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() throws SQLException {
        connection = BusinessDb.connect(); // one connection of the shared pool for the class: a TEMP table lives as long as the connection that made it
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE enum_probe (id int PRIMARY KEY, phase VARCHAR(20) NOT NULL CHECK (phase IN ('QUEUED', 'RUNNING', 'NEEDS_INPUT')))");
        }
        jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /** The probe table is the connection's own and the connection goes back to the shared pool, so the table is dropped before it does. */
    @AfterAll
    static void stop() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                // The temporary table of a connection is named by PostgreSQL's own pg_temp schema (which never touches a base table of the same name).
                statement.execute("DROP TABLE IF EXISTS pg_temp.enum_probe");
            } finally {
                connection.close();
            }
        }
    }

    /**
     * E1 (measured): JdbcClient does not bind a bare enum. The PostgreSQL driver cannot infer an SQL type for it, which is why Spring Data JDBC's
     * own converter turns an enum into its name before it binds. The stores therefore bind an enum as {@code SqlParameterValue(VARCHAR)} (E2),
     * through {@link StoreValues#bind}. The refusal is the driver's, so its kind is the driver's: the PostgreSQL driver refuses the parameter itself (a grammar failure for Spring). Nothing is stored.
     */
    @Test
    void aBareEnumParameterIsNotBound() {
        assertThrows(BadSqlGrammarException.class, () -> jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (?, ?)").params(9, Phase.NEEDS_INPUT).update());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM enum_probe WHERE id = 9").query(Integer.class).single(), "the bare enum was not stored");
    }

    /** E2: an enum bound with the Spring parameter type VARCHAR is stored as its name, by position and by name, and filters by name. */
    @Test
    void anEnumBoundAsVarcharWritesAndFiltersByName() {
        jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (?, ?)").params(StoreValues.bind(1, Phase.NEEDS_INPUT)).update();
        assertEquals("NEEDS_INPUT", jdbc.sql("SELECT phase FROM enum_probe WHERE id = 1").query(String.class).single());
        jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (:id, :phase)").param("id", 2).param("phase", new SqlParameterValue(Types.VARCHAR, Phase.RUNNING)).update();
        List<Integer> running = jdbc.sql("SELECT id FROM enum_probe WHERE phase = ?").params(StoreValues.bind(Phase.RUNNING)).query(Integer.class).list();
        assertTrue(running.contains(2));
        assertFalse(running.contains(1));
    }

    /** E6: Jackson writes an enum as its name and refuses a text that names no constant (the contract documents rely on both). */
    @Test
    void jacksonWritesTheNameAndRefusesAnUnknownOne() {
        JsonMapper mapper = JsonMapper.builder().build();
        assertEquals("\"NEEDS_INPUT\"", mapper.writeValueAsString(Phase.NEEDS_INPUT));
        assertEquals(Phase.RUNNING, mapper.readValue("\"RUNNING\"", Phase.class));
        assertThrows(JacksonException.class, () -> mapper.readValue("\"needs-input\"", Phase.class));
        assertThrows(JacksonException.class, () -> mapper.treeToValue(mapper.readTree("\"bogus\""), Phase.class));
    }

    /** E3: a record with an enum component is read from the name column (DataClassRowMapper's conversion service). */
    @Test
    void recordRowReadsTheEnum() {
        jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (3, 'QUEUED')").update();
        Row row = jdbc.sql("SELECT id, phase FROM enum_probe WHERE id = 3").query(Row.class).single();
        assertEquals(new Row(3, Phase.QUEUED), row);
    }

    /** E4: a single enum column is read as the enum type. */
    @Test
    void singleColumnReadsTheEnum() {
        jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (4, 'RUNNING')").update();
        assertEquals(Phase.RUNNING, jdbc.sql("SELECT phase FROM enum_probe WHERE id = 4").query(Phase.class).single());
    }

    /** E5: the CHECK constraint refuses a value that is not an enum name, whatever the writer. */
    @Test
    void checkRefusesAValueThatIsNotAName() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("INSERT INTO enum_probe (id, phase) VALUES (5, 'needs-input')").update());
    }
}
