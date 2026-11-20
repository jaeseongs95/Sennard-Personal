// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The business database is the user's choice of JDBC URL and driver, and Sennard refuses what it cannot serve before it connects. The cases, on the settings path the product has
 * ({@link RemoteConfigProvider}, which the link on the admin page becomes, and the {@link PreparedStore} that runs it):
 *
 * <pre>
 * c1  H2                         every URL form of H2 is refused with BUSINESS_DATABASE_NOT_H2 (H2 holds Sennard's first-time settings only), by the provider and by the store's status
 * c2  another product            a URL of a database Sennard has no SQL for is refused as such, and a URL that is not a JDBC URL is a configuration failure
 * c3  no driver                  the settings without a driver path are refused (Sennard ships none)
 * c4  a verified product         PostgreSQL, MariaDB and MySQL URLs are accepted (their driver is the user's)
 * </pre>
 */
class BusinessDatabaseChoiceTest {
    private static final Path PASSWORD = Path.of("no-such-dir", "unused.credential");
    private static final Path DRIVER = Path.of("no-such-dir", "unused-driver.jar");

    private static RemoteConfigProvider provider(String url) {
        return new RemoteConfigProvider(url, "sennard_owner", PASSWORD, DRIVER, 2);
    }

    @Test
    void c1H2IsRefusedInEveryUrlFormByTheProviderAndByTheStoresStatus() {
        for (String url : List.of("jdbc:h2:mem:business", "jdbc:h2:file:/data/sennard", "jdbc:h2:tcp://localhost/~/sennard", "jdbc:H2:mem:upper")) {
            assertEquals(StoreCode.BUSINESS_DATABASE_NOT_H2, assertThrows(StoreException.class, provider(url)::provide, url).code(), url);
            try (JdbcDrivers drivers = new JdbcDrivers()) {
                PreparedStore store = new PreparedStore(phases -> provider(url), Duration.ofMillis(1), Clock.systemUTC(), new DataSourceFactory(drivers, new TestPrivateStorage()));
                store.prepare(pool -> {
                    throw new AssertionError("no pool is made for H2");
                });
                StorePreparation.Status status = store.status();
                assertEquals(StorePhase.FAILED, status.phase(), url);
                assertEquals(StoreCode.BUSINESS_DATABASE_NOT_H2.name(), status.reason(), "the reason the status gives a user is the code of the refusal: " + url);
                assertNotNull(status.detail());
            }
        }
    }

    @Test
    void c2AnotherProductAndANonJdbcUrlAreRefusedWithTheirOwnReasons() {
        assertEquals(StoreCode.UNSUPPORTED_DATABASE_PRODUCT, assertThrows(StoreException.class, provider("jdbc:fake://127.0.0.1/sennard")::provide).code());
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class, provider("postgresql://127.0.0.1/sennard")::provide).code());
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class, provider(null)::provide).code());
    }

    @Test
    void c3TheSettingsWithoutADriverPathAreRefused() {
        RemoteConfigProvider noDriver = new RemoteConfigProvider("jdbc:mariadb://127.0.0.1:3306/sennard", "sennard_owner", PASSWORD, null, 2);
        assertEquals(StoreCode.INVALID_DATABASE_CONFIG, assertThrows(StoreException.class, noDriver::provide).code());
    }

    @Test
    void c4PostgreSqlMariaDbAndMySqlUrlsAreAccepted() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/sennard", "jdbc:mariadb://127.0.0.1:3306/sennard", "jdbc:mysql://[::1]:3306/sennard")) {
            SennardDatabaseConfig config = provider(url).provide();
            assertEquals(url, config.jdbcUrl());
            assertEquals(DRIVER, config.driverPath());
        }
    }
}
