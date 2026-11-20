// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.settings.SettingsTransactions;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The settings store's wiring: its own DataSource (an H2 file under {@link SettingsProperties#directory()}), its transaction manager and its JdbcClient, apart from the
 * business database's (named {@code store}, {@code transactionManager}, {@code storeJdbc}). The repositories and the migrator are found by package.
 *
 * <p>The data source and the transaction manager are {@code autowireCandidate = false}: Boot's JDBC and transaction auto-configuration and every {@code @Transactional}
 * without a name look for the one DataSource and the one transaction manager of the application, and a second candidate would silently switch them off or make them
 * ambiguous. The settings code reaches them by name ({@code @Transactional("settingsTransactionManager")}) and through the JdbcClient below, whose qualifier is
 * {@code settingsJdbc}. This class keeps Spring's default {@code proxyBeanMethods = true} because its beans refer to one another by method call, which is how a bean that
 * no by-type injection can reach is passed on to the next one.
 */
@Configuration
@EnableConfigurationProperties(SettingsProperties.class)
@EnableTransactionManagement(proxyTargetClass = true)
@ComponentScan(basePackageClasses = SettingsStoreConfiguration.class)
public class SettingsStoreConfiguration {
    static final String TRANSACTION_MANAGER = SettingsTransactions.MANAGER;
    /** The database's file name: H2 adds {@code .mv.db}. */
    static final String DATABASE_NAME = "sennard-settings";
    /** What H2's URL puts between its settings. */
    private static final char SETTINGS_SEPARATOR = ';';

    private final SettingsProperties properties;
    private final SettingsMigrator migrator;
    private final PrivateStorage storages;

    SettingsStoreConfiguration(SettingsProperties properties, SettingsMigrator migrator, PrivateStorage storages) {
        this.properties = properties;
        this.migrator = migrator;
        this.storages = storages;
    }

    /**
     * The pool of the settings database (sized by {@link SettingsProperties.Pool}), migrated before it is handed out.
     */
    // @Bean: Hikari's class cannot carry our annotation, and the pool's settings are this bean's.
    @Bean(name = "settingsDataSource", autowireCandidate = false)
    @Qualifier("settings")
    DataSource settingsDataSource() {
        Path folder = properties.directory().toAbsolutePath();
        // H2 ends the database name at the first ';' of the URL and reads the rest as connection settings, and the URL has no way to write the character as part of a name. The
        // database is not moved to another folder to dodge that (there is no substitute folder: the data must be where the state folder says); a state folder whose path has one
        // is refused before anything is made, so the user picks a folder without it.
        if (folder.resolve(DATABASE_NAME).toString().indexOf(SETTINGS_SEPARATOR) >= 0) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The folder of the settings database has a ';' in its path, which H2 cannot take in a database name: " + folder);
        }
        // The folder is made through the port of the storage of secrets (private to its owner when it is made) and must be a folder at every start. The database file inside is H2's
        // own, made with the process's default mode, so it is protected by this folder and not by a check of its own.
        try {
            storages.createFolder(folder);
            storages.requireSafeFolder(folder);
        } catch (IOException failure) {
            throw new UncheckedIOException("The folder of the settings database cannot be made.", failure);
        }
        Path file = folder.resolve(DATABASE_NAME);
        // The driver is the one the URL names (jdbc:h2: is H2's), so no class name is written here.
        HikariDataSource pool = DataSourceBuilder.create().type(HikariDataSource.class).url("jdbc:h2:file:" + file).username("sa").password("").build();
        SettingsProperties.Pool sizing = properties.pool();
        pool.setPoolName(sizing.name());
        pool.setMaximumPoolSize(sizing.maximumSize());
        pool.setMinimumIdle(sizing.minimumIdle());
        pool.setConnectionTimeout(sizing.connectionTimeout().toMillis());
        try {
            migrator.migrate(pool);
        } catch (RuntimeException failed) {
            pool.close();
            throw failed;
        }
        return pool;
    }

    // @Bean: Spring's own class, over the settings DataSource that by-type injection does not reach.
    @Bean(name = TRANSACTION_MANAGER, autowireCandidate = false)
    JdbcTransactionManager settingsTransactionManager() {
        return new JdbcTransactionManager(settingsDataSource());
    }

    /**
     * Spring's own classes, over the settings DataSource, with Spring's error code translator for H2 (its bundled {@code sql-error-codes.xml}, which no file of ours replaces). The
     * default of Spring 7's {@code JdbcAccessor} is the JDBC subclass translator, and that one cannot tell H2's lock wait that ran out (error 50200, a
     * {@code JdbcSQLTimeoutException}) from a statement timeout: both are a {@code SQLTimeoutException}, so both become a {@code QueryTimeoutException}. The error code table of H2 lists 50200
     * as {@code cannotAcquireLockCodes}, so a caller that lost a race for a row this way gets a {@code CannotAcquireLockException} (a {@code ConcurrencyFailureException}), the same
     * family as H2's immediate refusal (40001); a statement that was cancelled (57014) is in no group of the table and is left to the subclass translator, as before. The settings database is
     * always H2, so the product name is not read from a connection.
     */
    @Bean(name = "settingsJdbcClient")
    @Qualifier("settingsJdbc")
    JdbcClient settingsJdbcClient() {
        JdbcTemplate template = new JdbcTemplate(settingsDataSource());
        template.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("H2"));
        return JdbcClient.create(template);
    }
}
