// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder.Database;

/**
 * AGS3-D-0254 (audit 1): the one judgment of a password in a JDBC URL, against the form each product the {@link SqlBuilder.Database} list names documents for it (PostgreSQL pgjdbc
 * "Connection Parameters", MySQL Connector/J and MariaDB Connector/J "Connection URL", Microsoft JDBC "Building the connection URL", Oracle JDBC "Database URLs and Database
 * Specifiers", Db2 "IBM Data Server Driver for JDBC URL syntax", H2 "Database URL Overview"). Every product has a URL that carries one and a URL that does not.
 */
class JdbcUrlPasswordTest {
    private static final Map<Database, List<String>> CARRYING = new EnumMap<>(Database.class);
    private static final Map<Database, List<String>> CLEAN = new EnumMap<>(Database.class);

    static {
        CARRYING.put(Database.POSTGRESQL, List.of(
                "jdbc:postgresql://h:5432/db?user=u&password=secret", "jdbc:postgresql://h/db?PASSWORD=secret", "jdbc:postgresql://h/db?user=u&passfile=/home/u/.pgpass",
                "jdbc:postgresql://u:secret@h/db", "jdbc:postgresql://h/db?user=u&pwd=secret", "jdbc:postgresql://h/db?user=u&passwd=secret"));
        CLEAN.put(Database.POSTGRESQL, List.of(
                "jdbc:postgresql://127.0.0.1:5432/sennard", "jdbc:postgresql://[::1]:5432/sennard?x=a@b", "jdbc:postgresql://h/sennard?user=u&sslmode=verify-full&sslrootcert=/etc/sennard/root.crt",
                "jdbc:postgresql://h/passwords", "jdbc:postgresql://h/sennard?ApplicationName=password-check", "jdbc:postgresql://h/sennard?passwordless=true"));
        CARRYING.put(Database.MYSQL, List.of(
                "jdbc:mysql://h:3306/db?user=u&password=secret", "jdbc:mysql://u:secret@h:3306/db", "jdbc:mysql://h/db?user=u&Password=secret"));
        CLEAN.put(Database.MYSQL, List.of("jdbc:mysql://h:3306/sennard?sslMode=VERIFY_IDENTITY", "jdbc:mysql://h/sennard?user=u"));
        CARRYING.put(Database.MARIADB, List.of(
                "jdbc:mariadb://h:3306/db?user=u&password=secret", "jdbc:mariadb://u:secret@h:3306/db", "jdbc:mariadb://h/db?user=u&pwd=secret"));
        CLEAN.put(Database.MARIADB, List.of("jdbc:mariadb://h:3306/sennard?sslMode=verify-full", "jdbc:mariadb://h/sennard?user=u"));
        CARRYING.put(Database.SQLSERVER, List.of(
                "jdbc:sqlserver://h:1433;databaseName=db;user=u;password=secret;encrypt=true", "jdbc:sqlserver://h:1433;databaseName=db;user=u;Password = secret",
                "jdbc:sqlserver://h:1433;password=secret"));
        CLEAN.put(Database.SQLSERVER, List.of("jdbc:sqlserver://h:1433;databaseName=db;encrypt=true", "jdbc:sqlserver://h:1433;databaseName=db;user=u;trustServerCertificate=false"));
        CARRYING.put(Database.ORACLE, List.of(
                "jdbc:oracle:thin:scott/tiger@//h:1521/svc", "jdbc:oracle:thin:scott/tiger@h:1521:sid", "jdbc:oracle:oci:scott/tiger@db",
                "jdbc:oracle:thin:@//h:1521/svc?password=secret",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))?oracle.jdbc.password=secret"));
        CLEAN.put(Database.ORACLE, List.of(
                "jdbc:oracle:thin:@//h:1521/svc", "jdbc:oracle:thin:@h:1521:sid", "jdbc:oracle:thin:/@//h:1521/svc",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))"));
        CARRYING.put(Database.DB2, List.of(
                "jdbc:db2://h:50000/db:password=secret;", "jdbc:db2://h:50000/db:user=u;password=secret;", "jdbc:db2://h:50000/db:user=u;pwd=secret;",
                "jdbc:db2://h:50000/db:user=u;Password = secret;"));
        CLEAN.put(Database.DB2, List.of("jdbc:db2://h:50000/db", "jdbc:db2://h:50000/db:user=u;sslConnection=true;"));
        CARRYING.put(Database.H2, List.of("jdbc:h2:tcp://h/db;USER=u;PASSWORD=secret", "jdbc:h2:mem:x;PASSWORD=secret", "jdbc:h2:file:./x;user=u;password=secret"));
        CLEAN.put(Database.H2, List.of("jdbc:h2:mem:x", "jdbc:h2:tcp://h/db;USER=u"));
    }

    @Test
    void everyProductSqlBuilderNamesIsCoveredByAFormThatCarriesAndAFormThatDoesNot() {
        for (Database product : Database.values()) {
            assertTrue(CARRYING.containsKey(product) && CLEAN.containsKey(product), "a URL case is missing for " + product);
        }
    }

    @Test
    void theUrlOfEachProductThatCarriesAPasswordIsJudgedSo() {
        CARRYING.forEach((product, urls) -> urls.forEach(url -> {
            assertEquals(product, SqlBuilder.ofJdbcUrl(url), "the case belongs to the product it is listed under: " + url);
            assertTrue(JdbcUrlPassword.carriedBy(url), product + " carries a password: " + url);
        }));
    }

    @Test
    void theUrlOfEachProductWithNoPasswordIsNotJudgedToCarryOne() {
        CLEAN.forEach((product, urls) -> urls.forEach(url -> {
            assertEquals(product, SqlBuilder.ofJdbcUrl(url), "the case belongs to the product it is listed under: " + url);
            assertFalse(JdbcUrlPassword.carriedBy(url), product + " carries no password: " + url);
        }));
    }

    @Test
    void noUrlIsNoPassword() {
        assertFalse(JdbcUrlPassword.carriedBy(null));
    }
}
