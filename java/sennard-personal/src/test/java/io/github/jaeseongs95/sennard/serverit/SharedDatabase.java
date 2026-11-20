// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.sennard.personal.PersonalPrivateStorage;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLink;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import io.github.jaeseongs95.sennard.service.settings.h2.SettingsStoreConfiguration;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;

/**
 * The business database of the server tests: the PostgreSQL database the test run names ({@link BusinessDb}), which replaced the database the server used to start for itself.
 * The server is given it the way the user gives it: as the link the admin page saves in the settings database of the state folder ({@link #linkInto}); the password stays in the
 * file the link names.
 *
 * <p>Every test class starts from nothing: the first server a class starts (or a class that needs the database without a server: {@link #resetForTheClass()}) drops the
 * {@code sennard} schema and migrates it anew. A class that restarts its server over the same state does not drop them again, so what the first server wrote is
 * still there. The classes of a test JVM run one after the other (the build allows one fork here and one task at a time on the database), so no class sees another's rows.
 * The class that is running is told by this JUnit extension, which the engine finds by itself ({@code META-INF/services}, {@code junit-platform.properties}).
 */
public final class SharedDatabase implements BeforeAllCallback {
    private static volatile boolean droppedForThisClass;

    /** The engine instantiates the extension; it holds no state of its own. */
    public SharedDatabase() {
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        droppedForThisClass = false;
    }

    /** The link the page would save for the test run's business database: the user's driver, the URL, the account, the password file and the protection the database asks for. */
    public static DatabaseLinkRequest link() {
        SennardDatabaseConfig config = BusinessDb.config();
        return new DatabaseLinkRequest(config.driverPath().toAbsolutePath(), config.jdbcUrl(), config.ownerUser(), config.ownerPasswordRef().toAbsolutePath(),
                DatabaseTls.valueOf(config.tlsPolicy().name()));
    }

    /** What the settings database alone needs: its folder, a clock and the edition's storage of secrets (the store's own wiring, no business side). */
    @Configuration(proxyBeanMethods = false)
    @Import({PersonalPrivateStorage.class, SettingsStoreConfiguration.class})
    static class SettingsOnly {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /** The settings database in this folder, opened as the server opens it, for the one use of {@code work}; the file is free again when this returns (H2 holds it while it is open). */
    private static <T> T inSettings(Path settingsDirectory, Function<DatabaseLinkSettings, T> work) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SettingsOnly.class).web(WebApplicationType.NONE).logStartupInfo(false)
                .run("--spring.main.keep-alive=false", "--logging.level.root=OFF", "--sennard.settings.directory=" + settingsDirectory.toAbsolutePath())) {
            return work.apply(context.getBean(DatabaseLinkSettings.class));
        }
    }

    /**
     * Saves the test run's business database as the link of the settings database in this folder (the folder is made when it is not there), before the server of the test is started on
     * the state folder that holds it: what the page does when a user links the database, without the passkey. The server reads it at its start and prepares its store with it.
     */
    public static void linkInto(Path settingsDirectory) {
        inSettings(settingsDirectory, links -> links.save(link()));
    }

    /** The link the settings database in this folder holds; empty when it holds none. */
    public static Optional<DatabaseLinkRequest> linkedIn(Path settingsDirectory) {
        return inSettings(settingsDirectory, links -> links.find().map(DatabaseLink::request));
    }

    /** Drops and migrates the business schemas anew, once for the running test class; a second call of the same class does nothing. */
    public static synchronized void resetForTheClass() {
        if (droppedForThisClass) {
            return;
        }
        try {
            BusinessDb.reset();
        } catch (SQLException unreachable) {
            throw new IllegalStateException("The shared business database cannot be reset: " + unreachable.getMessage(), unreachable);
        }
        droppedForThisClass = true;
    }

    /**
     * Drops and migrates the business schemas anew for one test, every time it is called (it is not remembered). For a test class whose every test starts a server of its own: the
     * public keys of the passkeys are in the settings database the server makes anew (AGS3-D-0254), while the business log holds the fingerprints only, so a log that outlives its
     * server names keys the next server's registry does not have, and the log fails its integrity check (ADMIN_RECORD_ALTERED). The class's first server then finds the schemas made already.
     */
    public static synchronized void resetForTheTest() {
        try {
            BusinessDb.reset();
        } catch (SQLException unreachable) {
            throw new IllegalStateException("The shared business database cannot be reset: " + unreachable.getMessage(), unreachable);
        }
        droppedForThisClass = true;
    }
}
