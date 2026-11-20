// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDataSource;

import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft;

/** What the settings store's tests share: a context runner over a folder, the clock the product has (UTC) fixed at one instant, and sample values. */
final class SettingsFixtures {
    static final Instant NOW = Instant.parse("2026-11-13T01:02:03.456Z");

    private SettingsFixtures() {
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /** A DataSource that stands for the business database in a wiring test: a second bean of the type, which opens no connection. */
    static final class BusinessStandIn extends AbstractDataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("The stand-in business DataSource opens no connection.");
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }
    }

    /** The beans of the business side that the settings beans must not disturb: its DataSource, named and qualified as the product's is. */
    @Configuration(proxyBeanMethods = false)
    static class BusinessSide {
        @Bean(name = "store")
        @org.springframework.beans.factory.annotation.Qualifier("store")
        DataSource store() {
            return new BusinessStandIn();
        }
    }

    /** The port of the storage of secrets, which an edition's configuration gives the product (the settings module has no edition). */
    @Configuration(proxyBeanMethods = false)
    static class StorageOfSecrets {
        @Bean
        io.github.jaeseongs95.sennard.service.secret.PrivateStorage privateStorage() {
            return new TestPrivateStorage();
        }
    }

    static ApplicationContextRunner runner(Path directory) {
        return new ApplicationContextRunner().withUserConfiguration(FixedClock.class, StorageOfSecrets.class, SettingsStoreConfiguration.class)
                .withPropertyValues("sennard.settings.directory=" + directory.toString().replace('\\', '/'));
    }

    static PasskeyRegistration passkey(String id) {
        return new PasskeyRegistration(id, "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-" + id, PasskeyAlgorithm.ES256, "노트북 " + id);
    }

    static PreLinkAdminDraft record(String target, String requestId) {
        return new PreLinkAdminDraft(target, "{\"v\":1,\"target\":\"" + target + "\"}", "cred-1", "YXV0aA", "e30", "c2ln", "hash-" + target, requestId);
    }
}
