// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.admincli;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.app.RandomConfiguration;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.personal.PersonalPrivateStorage;
import io.github.jaeseongs95.sennard.personal.PersonalStoreIsolation;
import io.github.jaeseongs95.sennard.server.context.AdminLogSource;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.h2.SettingsStoreConfiguration;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.EncryptedRecords;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.JdbcDrivers;
import io.github.jaeseongs95.sennard.service.store.pg.RemoteConfigProvider;
import io.github.jaeseongs95.sennard.service.store.pg.StoreTransactionConfiguration;

/**
 * The context of {@code sennard admin-reset}: what the one use case ({@link AdminReset}) needs of the store and nothing else, with no web server, no hook, no MCP, no schedule and no
 * store preparation. The business database is reached the way the server reaches it: the link the user signed on the admin page, the user's driver, the
 * pool of the edition's isolation level ({@link PersonalStoreIsolation}, {@link DataSourceFactory}) and the store's own transaction manager ({@link StoreTransactionConfiguration}).
 *
 * <p>This class and {@link AdminReset} are in a package that the server's component scan does not cover ({@code ...sennard.server}, {@code ...sennard.personal}, {@code ...sennard.app}), so the
 * server's context has neither of them; only {@link AdminResetContext} names this class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonalProperties.class)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({RandomConfiguration.class, PersonalStoreIsolation.class, PersonalPrivateStorage.class, StoreTransactionConfiguration.class, JdbcDrivers.class, DataSourceFactory.class,
        SqlBuilder.class, EncryptedRecords.class, ContextLayerStore.class, AdminLogSource.class, AdminReset.class, AdminResetBeforeLink.class, SettingsStoreConfiguration.class})
class AdminResetConfiguration {
    /** The command line option that confirms the reset ({@code --yes}); without it the run only reports what it would archive. */
    static final String CONFIRMED_OPTION = "yes";
    /** The pool of the command: one account's connections, as many as the server's pool of a linked database. */
    private static final int LINK_POOL_SIZE = 4;

    /** Where the runner leaves the result: registered in the context before it starts, taken by the caller of the start after it. */
    static final class Outcome {
        private AdminReset.Result result;

        void deliver(AdminReset.Result delivered) {
            this.result = delivered;
        }

        AdminReset.Result delivered() {
            if (result == null) {
                throw new IllegalStateException("The admin reset context started without delivering a result.");
            }
            return result;
        }
    }

    /**
     * The pool of the installation's business database, the link the user signed on the admin page (the one source, as for the server): the account's one connection of the user's
     * driver, at the edition's isolation level. Closed with the context.
     *
     * @throws UnavailableException ADMIN_RESET_DATABASE_UNREACHABLE when no business database is linked
     */
    @Bean
    @Qualifier("store")
    HikariDataSource store(DatabaseLinkSettings links, DataSourceFactory pools) {
        DatabaseLinkRequest link = links.find().orElseThrow(() -> new UnavailableException(UnavailableCode.ADMIN_RESET_DATABASE_UNREACHABLE,
                SennardException.args("cause", "No business database is linked on the admin page."))).request();
        SennardDatabaseConfig config = new RemoteConfigProvider(link.jdbcUrl(), link.accountName(), link.passwordRef(), link.driverPath(), LINK_POOL_SIZE, PreLinkAdminService.tlsPolicy(link.tls())).provide();
        return pools.create(config);
    }

    /**
     * Runs the use case when the context has started: an entrance, so it opens no transaction and calls one service method. A runner and not a bean the caller asks the
     * context for, as {@code InstalledSettings} does it. The one state of the section of the log before the link decides which: open (the business database was never ready), the
     * log is the settings database's alone and {@link AdminResetBeforeLink} resets it without the business database; closed, {@link AdminReset} resets the business database's log too.
     * The context starts lazily (see {@link AdminResetContext}), so the business database's pool, which an open section does not need and which may not even be set, is made only when
     * {@code businessReset} is asked for.
     */
    // @Bean: a lambda of Spring Boot's ApplicationRunner (a library type), made from the beans this context was given.
    @Bean
    ApplicationRunner adminResetRunner(PreLinkAdminRecords preLink, AdminResetBeforeLink beforeLink, ObjectProvider<AdminReset> businessReset, Outcome outcome) {
        return arguments -> {
            boolean confirmed = arguments.containsOption(CONFIRMED_OPTION);
            outcome.deliver(preLink.closed() ? businessReset.getObject().run(confirmed) : beforeLink.run(confirmed));
        };
    }
}
