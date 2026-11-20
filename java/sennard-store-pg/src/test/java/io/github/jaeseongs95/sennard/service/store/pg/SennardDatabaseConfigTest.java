// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig.TlsPolicy;

/** The TLS and credential rules of the settings record: enforced where the settings are made, for the databases Sennard knows how to verify (PostgreSQL, MariaDB, MySQL). */
class SennardDatabaseConfigTest {
    /** A password file and a driver that do not exist: the record never reads them, it only carries the paths. */
    private static final Path FILE = Path.of("no-such-dir", "unused.credential");
    private static final Path DRIVER = Path.of("no-such-dir", "unused-driver.jar");

    private static SennardDatabaseConfig config(String url, TlsPolicy policy) {
        return new SennardDatabaseConfig(url, "sennard_owner", FILE, DRIVER, policy, 4);
    }

    @Test
    void onlyAnIpLiteralLoopbackAddressCountsAsLoopback() {
        for (String host : new String[] {"127.0.0.1", "127.255.255.254", "127.1.2.3", "[::1]", "127.1"}) { // 127.1 is a literal the JDK reads as 127.0.0.1
            assertTrue(SennardDatabaseConfig.isLoopbackHost(host), host);
        }
        for (String host : new String[] {"localhost", "LOCALHOST", "127.0.0.1.evil.example", "10.0.0.5", "128.0.0.1", "0.0.0.0", "[::2]",
                "999.0.0.1", "db.example.com", "[::ffff:10.0.0.1]", "10.1"}) {
            assertFalse(SennardDatabaseConfig.isLoopbackHost(host), host);
        }
    }

    @Test
    void plainTextIsAcceptedOnlyForALoopbackAddress() {
        config("jdbc:postgresql://127.0.0.1:5432/sennard", TlsPolicy.LOOPBACK_PLAINTEXT);
        config("jdbc:postgresql://[::1]:5432/sennard", TlsPolicy.LOOPBACK_PLAINTEXT);
        config("jdbc:mariadb://127.0.0.1:3306/sennard", TlsPolicy.LOOPBACK_PLAINTEXT);
        for (String url : new String[] {"jdbc:postgresql://localhost:5432/sennard", "jdbc:postgresql://10.1.2.3/sennard",
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=disable", "jdbc:mariadb://localhost:3306/sennard", "jdbc:mariadb://db.example.com:3306/sennard?sslMode=disable",
                "jdbc:mysql://10.1.2.3:3306/sennard"}) {
            assertEquals(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED,
                    assertThrows(StoreException.class, () -> config(url, TlsPolicy.LOOPBACK_PLAINTEXT), url).code(), url);
        }
    }

    @Test
    void anyOtherHostNeedsTheServerVerifiedByTheDriversOwnOption() {
        // PostgreSQL: sslmode=verify-full and a root certificate. MariaDB: sslMode=verify-full. MySQL: sslMode=VERIFY_IDENTITY.
        config("jdbc:postgresql://db.example.com:5432/sennard?sslmode=verify-full&sslrootcert=/etc/sennard/root.crt", TlsPolicy.VERIFY_FULL);
        config("jdbc:postgresql://localhost:5432/sennard?sslmode=verify-full&sslrootcert=C:/ca/root.crt&ApplicationName=sennard", TlsPolicy.VERIFY_FULL);
        config("jdbc:mariadb://db.example.com:3306/sennard?sslMode=verify-full", TlsPolicy.VERIFY_FULL);
        config("jdbc:mariadb://db.example.com:3306/sennard?sslmode=verify-full&serverSslCert=/etc/sennard/root.crt", TlsPolicy.VERIFY_FULL);
        config("jdbc:mysql://db.example.com:3306/sennard?sslMode=VERIFY_IDENTITY", TlsPolicy.VERIFY_FULL);
        for (String url : new String[] {
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=require",
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=verify-ca&sslrootcert=/etc/sennard/root.crt",
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=verify-full",
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=verify-full&sslrootcert=",
                "jdbc:postgresql://db.example.com:5432/sennard?sslmode=allow",
                "jdbc:postgresql://db.example.com:5432/sennard",
                "jdbc:mariadb://db.example.com:3306/sennard?sslMode=verify-ca",
                "jdbc:mariadb://db.example.com:3306/sennard?sslMode=trust",
                "jdbc:mariadb://db.example.com:3306/sennard",
                "jdbc:mysql://db.example.com:3306/sennard?sslMode=VERIFY_CA",
                "jdbc:mysql://db.example.com:3306/sennard?sslMode=REQUIRED"}) {
            assertEquals(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED,
                    assertThrows(StoreException.class, () -> config(url, TlsPolicy.VERIFY_FULL), url).code(), url);
        }
        // Asking for verify-full on a loopback address also needs the root certificate (PostgreSQL).
        assertEquals(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED, assertThrows(StoreException.class,
                () -> config("jdbc:postgresql://127.0.0.1:5432/sennard?sslmode=verify-full", TlsPolicy.VERIFY_FULL)).code());
    }

    /** Only the one value that verifies the server is accepted for a remote PostgreSQL; every other value of the option, known or not, is refused. */
    @Test
    void onlyVerifyFullIsTheSslModeARemotePostgreSqlAccepts() {
        String remote = "jdbc:postgresql://db.example.com:5432/sennard?sslrootcert=/ca.pem&sslmode=";
        for (String mode : new String[] {"disable", "allow", "prefer", "require", "verify-ca", "VERIFY-FULL", "verify_full", ""}) {
            assertEquals(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED, assertThrows(StoreException.class, () -> config(remote + mode, TlsPolicy.VERIFY_FULL), mode).code(), mode);
        }
        assertEquals(TlsPolicy.VERIFY_FULL, config(remote + "verify-full", TlsPolicy.VERIFY_FULL).tlsPolicy());
    }

    /** A database whose TLS option Sennard does not know cannot be shown to be verified, so it is accepted on a loopback address only. */
    @Test
    void aProductWhoseVerificationOptionIsUnknownIsAcceptedOnLoopbackOnly() {
        config("jdbc:sqlserver://127.0.0.1:1433/sennard", TlsPolicy.LOOPBACK_PLAINTEXT);
        assertEquals(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED,
                assertThrows(StoreException.class, () -> config("jdbc:sqlserver://db.example.com:1433/sennard?encrypt=true", TlsPolicy.VERIFY_FULL)).code());
    }

    @Test
    void aUrlNeverCarriesAPasswordAndMustBeASingleHostUrl() {
        for (String url : new String[] {"jdbc:postgresql://127.0.0.1:5432/sennard?password=secret", "jdbc:postgresql://127.0.0.1:5432/sennard?user=x&passfile=/p",
                "jdbc:mariadb://127.0.0.1:3306/sennard?user=x&Password=secret", "jdbc:mariadb://sennard_owner:secret@127.0.0.1:3306/sennard", "jdbc:db2://127.0.0.1:50000/sennard:password=secret;",
                "jdbc:oracle:thin:scott/tiger@//127.0.0.1:1521/svc", "jdbc:sqlserver://127.0.0.1:1433;databaseName=sennard;user=u;password=secret",
                "jdbc:postgresql://127.0.0.1:5432,127.0.0.2:5432/sennard", "jdbc:postgresql:///sennard", "postgres://127.0.0.1/sennard",
                "jdbc:postgresql://127.0.0.1:5432", "jdbc:postgresql://127.0.0.1:5432/", "jdbc:postgresql://127.0.0.1:5432/sennard/other"}) {
            assertEquals(StoreCode.INVALID_DATABASE_CONFIG,
                    assertThrows(StoreException.class, () -> config(url, TlsPolicy.LOOPBACK_PLAINTEXT), url).code(), url);
        }
    }

    /** The one database account (the owner) is required with its password file, and the user's driver with them, so no config can lack either. */
    @Test
    void theOneAccountItsPasswordFileAndTheDriverAreRequired() {
        String url = "jdbc:postgresql://127.0.0.1:5432/sennard";
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(url, "sennard_owner", null, DRIVER, TlsPolicy.LOOPBACK_PLAINTEXT, 4)).code());
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(url, null, FILE, DRIVER, TlsPolicy.LOOPBACK_PLAINTEXT, 4)).code());
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(url, "sennard_owner", FILE, DRIVER, null, 4)).code());
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(url, "sennard_owner", FILE, null, TlsPolicy.LOOPBACK_PLAINTEXT, 4)).code());
    }

    @Test
    void theJdbcUrlIsRequired() {
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(null, "sennard_owner", FILE, DRIVER, TlsPolicy.LOOPBACK_PLAINTEXT, 4)).code());
    }

    @Test
    void thePoolHoldsAtLeastOneConnection() {
        String url = "jdbc:postgresql://127.0.0.1:5432/sennard";
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class,
                () -> new SennardDatabaseConfig(url, "sennard_owner", FILE, DRIVER, TlsPolicy.LOOPBACK_PLAINTEXT, 0)).code());
        assertEquals(1, new SennardDatabaseConfig(url, "sennard_owner", FILE, DRIVER, TlsPolicy.LOOPBACK_PLAINTEXT, 1).poolSize());
    }
}
