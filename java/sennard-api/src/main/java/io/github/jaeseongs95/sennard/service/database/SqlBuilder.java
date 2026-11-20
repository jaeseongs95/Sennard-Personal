// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/** Builds SQL for the actual business database, resolving its product on first use. */
@Component
public class SqlBuilder {
    /** The business database products Sennard builds SQL for. */
    public enum Database { H2, POSTGRESQL, ORACLE, DB2, SQLSERVER, MARIADB, MYSQL }

    private final DataSource dataSource;
    private volatile Database resolved;

    public SqlBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Database database() {
        Database result = resolved;
        if (result != null) {
            return result;
        }
        // Acquire before the lock: another thread may hold a transaction's only pool connection.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            synchronized (this) {
                result = resolved;
                if (result == null) {
                    result = database(DatabaseDriver.fromProductName(connection.getMetaData().getDatabaseProductName()));
                    resolved = result;
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new StoreException(StoreCode.STORE_UNREACHABLE, "Could not inspect business database product", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    static Database database(DatabaseDriver driver) {
        return switch (driver) {
            case H2 -> Database.H2;
            case POSTGRESQL -> Database.POSTGRESQL;
            case ORACLE -> Database.ORACLE;
            case DB2 -> Database.DB2;
            case SQLSERVER -> Database.SQLSERVER;
            case MARIADB -> Database.MARIADB;
            case MYSQL -> Database.MYSQL;
            default -> throw new StoreException(StoreCode.UNSUPPORTED_DATABASE_PRODUCT, "Unsupported business database: " + driver);
        };
    }

    /** The parameter is a JDBC placeholder, not the value. */
    public String generateLimit(String parameter) {
        return generateLimit(database(), parameter);
    }

    static String generateLimit(Database database, String parameter) {
        // PostgreSQL: https://www.postgresql.org/docs/current/sql-select.html
        // H2: https://h2database.com/html/commands.html#select
        // Oracle: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/SELECT.html
        // Db2: https://www.ibm.com/docs/en/db2/12.1.x?topic=design-guidelines-restricting-select-statements
        // SQL Server: https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql
        // MariaDB 10.6+: https://mariadb.com/docs/server/reference/sql-statements/data-manipulation/selecting-data/select-offset-fetch
        // MySQL: https://dev.mysql.com/doc/refman/8.4/en/select.html
        return switch (database) {
            case H2, POSTGRESQL, ORACLE, DB2 -> "FETCH FIRST " + parameter + " ROWS ONLY";
            case SQLSERVER, MARIADB -> "OFFSET 0 ROWS FETCH NEXT " + parameter + " ROWS ONLY";
            case MYSQL -> "LIMIT " + parameter;
        };
    }

    /**
     * The declaration of a column that holds a text of no fixed size (the migration scripts' {@code ${long_text}}), spelled as the product takes it. It is a fixed table, not a
     * reading of the driver: a driver's {@code getTypeInfo()} CREATE_PARAMS is free text (MariaDB's driver reports the character set and collation options of LONGTEXT, not a length).
     * Where an omitted length would make the limit small, the length is written.
     */
    public static String generateLongText(Database database) {
        // PostgreSQL text, any length up to about 1 GB: https://www.postgresql.org/docs/current/datatype-character.html
        // H2 CHARACTER LARGE OBJECT: the datatypes page does not state the limit of an omitted length (https://h2database.com/html/datatypes.html#character_large_object_type),
        //     so the largest length is written (2147483647 is accepted by H2 2.4.240: measured by the SSH MCP, 400,000 Korean characters round trip).
        // Oracle CLOB takes no length, (4 GB - 1) * block size: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Data-Types.html
        // Db2 CLOB is up to 2,147,483,647 bytes (https://www.ibm.com/docs/en/db2/12.1.x?topic=list-character-strings), and the CLOB entry of the CREATE TABLE data types says
        //     "The default is 1,048,576 (1M) code units." when the length is omitted (https://www.ibm.com/docs/en/db2/12.1.x?topic=statements-create-table)
        // SQL Server NVARCHAR(MAX), up to 2^31-1 bytes, UTF-16 whatever the collation (VARCHAR(MAX) holds the full Unicode range only with a UTF-8 collation of 2019 or later; another collation keeps its code page's characters):
        //     https://learn.microsoft.com/en-us/sql/t-sql/data-types/nchar-and-nvarchar-transact-sql
        // MariaDB LONGTEXT, 4,294,967,295 bytes, takes no length: https://mariadb.com/docs/server/reference/data-types/string-data-types/longtext
        // MySQL LONGTEXT, 4,294,967,295 bytes, takes no length: https://dev.mysql.com/doc/refman/8.4/en/string-type-syntax.html
        return switch (database) {
            case POSTGRESQL -> "TEXT";
            case H2 -> "CHARACTER LARGE OBJECT(2147483647)";
            case ORACLE -> "CLOB";
            case DB2 -> "CLOB(2147483647)";
            case SQLSERVER -> "NVARCHAR(MAX)";
            case MARIADB, MYSQL -> "LONGTEXT";
        };
    }

    /**
     * A query with two {@code ?} (the same text twice) for the start-up check that the database takes UTF-8 text in a variable-length character type (the migration scripts'
     * {@code VARCHAR}; {@code VARCHAR2} on Oracle). The text is cast to that type, and the query returns two columns: the number of characters the database counts in the cast
     * value, and the cast value itself. The count catches a database that counts bytes (PostgreSQL's SQL_ASCII); the value catches a type that loses the text (a code page
     * that has no such character turns it into {@code ?}, and the count may still be right). Both must equal the text ({@link #countsCodePoints}).
     *
     * <p>The text of the check holds a character outside the Basic Multilingual Plane (an emoji), which no legacy code page has: a Korean code page (PostgreSQL EUC_KR, Oracle
     * KO16MSWIN949, SQL Server Korean_Wansung, Db2 CP949) holds Korean syllables and would pass a check of Korean alone. The length of the cast is 40: it is counted in bytes on
     * Oracle ({@code VARCHAR2} has BYTE semantics by default), SQL Server (a UTF-8 {@code VARCHAR}) and Db2, where the text is 10 bytes.
     *
     * <p>It is a fixed table by dialect, not a JDBC scalar function escape ({@code {fn CHAR_LENGTH(...)}}): the escape's translation is each driver's own and a driver's
     * documentation of it could not be confirmed for all seven products, and the products differ in more than the function name (a FROM clause, the type name). Where a cast
     * to {@code CHAR(n)} would pad the value (PostgreSQL's value comes back padded), a variable-length type is used.
     *
     * <p>The result is the database's, not the schema's character set, except on MariaDB and MySQL where the cast takes the connection's character set
     * ({@link #generateSchemaCharsetProbe}).
     */
    public static String generateEncodingProbe(Database database) {
        // PostgreSQL char_length: https://www.postgresql.org/docs/current/functions-string.html
        // H2 CHAR_LENGTH: https://h2database.com/html/functions.html#char_length
        // MySQL CHAR_LENGTH (code points; CAST has CHAR, not VARCHAR, and CAST ... AS CHAR(n) is not padded): https://dev.mysql.com/doc/refman/8.4/en/string-functions.html
        // MariaDB CHAR_LENGTH: https://mariadb.com/docs/server/reference/sql-functions/string-functions/char_length
        // Oracle LENGTH (characters), VARCHAR2, FROM DUAL as before 23ai: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/LENGTH.html
        // Db2 CHARACTER_LENGTH(... USING CODEUNITS32), FROM SYSIBM.SYSDUMMY1: https://www.ibm.com/docs/en/db2/12.1.x?topic=functions-character-length
        // SQL Server has no CHAR_LENGTH: LEN counts a supplementary character as one when the collation is supplementary-character aware, and UTF-8 collations (_UTF8, available only
        //     for such Windows collations) are: https://learn.microsoft.com/en-us/sql/relational-databases/collations/collation-and-unicode-support (Supplementary characters)
        //     The cast is to VARCHAR, not NVARCHAR: NVARCHAR is UTF-16 whatever the collation and would always pass, while the schema's VARCHAR holds the text whole only with a UTF-8
        //     collation (SQL Server 2019 or later); a Korean (code page 949) collation holds the Korean syllables but not the emoji, which comes back as ?
        //     (https://learn.microsoft.com/en-us/sql/t-sql/data-types/char-and-varchar-transact-sql); LEN drops trailing spaces (https://learn.microsoft.com/en-us/sql/t-sql/functions/len-transact-sql)
        return switch (database) {
            case POSTGRESQL, H2 -> "SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))";
            case MARIADB, MYSQL -> "SELECT CHAR_LENGTH(CAST(? AS CHAR(40))), CAST(? AS CHAR(40))";
            case ORACLE -> "SELECT LENGTH(CAST(? AS VARCHAR2(40))), CAST(? AS VARCHAR2(40)) FROM DUAL";
            case DB2 -> "SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(40)) USING CODEUNITS32), CAST(? AS VARCHAR(40)) FROM SYSIBM.SYSDUMMY1";
            case SQLSERVER -> "SELECT LEN(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))";
        };
    }

    /**
     * Whether the count of the probe is the number of code points of the text. It is not judged on H2: its documentation says only "the number of characters in a character string"
     * (https://h2database.com/html/functions.html#char_length), without saying how a supplementary character counts, and H2 holds Java strings, so no encoding of the database can
     * lose the text: the value comparison is the whole judgment there. On the others the count is characters, and a supplementary character is one: PostgreSQL char_length, MySQL
     * CHAR_LENGTH (code points), MariaDB CHAR_LENGTH, Oracle LENGTH (the characters of the database character set), Db2 CODEUNITS32 (UTF-32 code units), SQL Server LEN with a
     * UTF-8 collation (see {@link #generateEncodingProbe}); one that does not count so has a database that is not UTF-8, which the value comparison refuses as well.
     */
    public static boolean countsCodePoints(Database database) {
        return switch (database) {
            case H2 -> false;
            case POSTGRESQL, ORACLE, DB2, SQLSERVER, MARIADB, MYSQL -> true;
        };
    }

    /**
     * For MariaDB and MySQL, a query of three columns, the default character set of the current schema, the server's ({@code character_set_server}) and the database's own verdict
     * (1 when both are {@code utf8mb4}, else 0; the first two are for the message only); empty for the others. The verdict is {@code LOWER(name) = 'utf8mb4'} for each name inside a
     * {@code CASE}: a character set name is a string, and whether {@code =} on strings ignores case depends on the collation of the operands ("Case Sensitivity in String Searches",
     * https://dev.mysql.com/doc/refman/8.4/en/case-sensitivity.html; a {@code _bin} collation compares case-sensitively and the collation of a system variable's value differs from the
     * column's, "Collation Coercibility in Expressions", https://dev.mysql.com/doc/refman/8.4/en/charset-collation-coercibility.html), so the names are folded first. {@code LOWER} changes every
     * non-binary string whatever its collation (https://dev.mysql.com/doc/refman/8.4/en/string-functions.html#function_lower; MariaDB:
     * https://mariadb.com/docs/server/reference/sql-functions/string-functions/lower), and a literal has the lowest coercibility, so it takes the collation of the column or variable with no
     * "illegal mix of collations". A name that is NULL makes the condition unknown, and {@code CASE} answers 0 for it. The cast of
     * the probe is in the connection's character set ({@code CAST(... AS CHAR)} with no {@code CHARACTER SET}, MySQL 8.4 "Cast Functions and Operators"), so a schema in latin1
     * with a driver connected as utf8mb4 would pass it, while the tables are made in the schema's own set. A schema Sennard creates ({@code CREATE SCHEMA}) takes the server's set
     * when none is given (https://dev.mysql.com/doc/refman/8.4/en/charset-database.html), so both are asked. Only {@code utf8mb4} holds the text: {@code utf8mb3} (and {@code utf8}, its
     * alias) has three bytes per character and "supports BMP characters only" (https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-utf8mb3.html; MariaDB 10.6 and later:
     * https://mariadb.com/docs/server/reference/data-types/string-data-types/character-sets/supported-character-sets-and-collations).
     * SCHEMATA: https://dev.mysql.com/doc/refman/8.4/en/information-schema-schemata-table.html and
     * https://mariadb.com/docs/server/reference/system-tables/information-schema/information-schema-tables/information-schema-schemata-table
     * The other products take the character type of the schema from the database and the cast of the probe is in that type, so the probe is the whole check.
     */
    public static Optional<String> generateSchemaCharsetProbe(Database database) {
        return switch (database) {
            case MARIADB, MYSQL -> Optional.of("SELECT DEFAULT_CHARACTER_SET_NAME, @@character_set_server, "
                    + "CASE WHEN LOWER(DEFAULT_CHARACTER_SET_NAME) = 'utf8mb4' AND LOWER(@@character_set_server) = 'utf8mb4' THEN 1 ELSE 0 END "
                    + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()");
            case H2, POSTGRESQL, ORACLE, DB2, SQLSERVER -> Optional.empty();
        };
    }

    /** Appends the dialect's row limit to an otherwise complete query. */
    public String applyLimit(String statement, String parameter) {
        return statement + " " + generateLimit(parameter);
    }

    /** Starts a short-lived statement from fixed SQL. */
    public Statement query(String sql) {
        return new Statement(sql);
    }

    public final class Statement {
        private final StringBuilder sql;

        private Statement(String base) {
            sql = new StringBuilder(base);
        }

        /** The fixed pattern may use %1$d for the zero-based parameter suffix. */
        public Statement orGroup(String pattern, int count) {
            sql.append('(');
            if (count == 0) {
                sql.append("1 = 0");
            }
            for (int index = 0; index < count; index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                sql.append(pattern.formatted(index));
            }
            sql.append(')');
            return this;
        }

        public Statement and(String condition) {
            sql.append(" AND ").append(condition);
            return this;
        }

        public Statement orderBy(String columns) {
            sql.append(" ORDER BY ").append(columns);
            return this;
        }

        public Statement append(String fixedSql) {
            sql.append(fixedSql);
            return this;
        }

        public String build() {
            database();
            return sql.toString();
        }

        public String limit(String parameter) {
            return applyLimit(build(), parameter);
        }
    }

    /** Refuses a product different from the actual DataSource product. */
    public void requireSame(String productName) {
        requireSame(database(), database(DatabaseDriver.fromProductName(productName)));
    }

    /** The product a JDBC URL names (no connection is made); StoreCode.UNSUPPORTED_DATABASE_PRODUCT for one Sennard has no SQL for. */
    public static Database ofJdbcUrl(String jdbcUrl) {
        return database(DatabaseDriver.fromJdbcUrl(jdbcUrl));
    }

    /** The product a connection reports ({@code DatabaseMetaData.getDatabaseProductName()}); StoreCode.UNSUPPORTED_DATABASE_PRODUCT for one Sennard has no SQL for. */
    public static Database ofProductName(String productName) {
        return database(DatabaseDriver.fromProductName(productName));
    }

    /** Checks the configured URL against the pool product before the pool is made ready. */
    public static void requireSame(String jdbcUrl, String productName) {
        requireSame(ofJdbcUrl(jdbcUrl), database(DatabaseDriver.fromProductName(productName)));
    }

    private static void requireSame(Database configured, Database actual) {
        if (configured != actual) {
            throw new StoreException(StoreCode.DATABASE_PRODUCT_MISMATCH,
                    "Configured business database " + configured + " does not match connected product " + actual);
        }
    }
}
