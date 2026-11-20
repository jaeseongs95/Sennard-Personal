// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.app.AppConfiguration;
import io.github.jaeseongs95.sennard.app.RandomConfiguration;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentKeys;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.SegmentCipher;
import io.github.jaeseongs95.sennard.app.attachment.StorageFolders;
import io.github.jaeseongs95.sennard.app.attachment.UploadCandidates;
import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.BusinessJdbcConfiguration;
import io.github.jaeseongs95.sennard.personal.PersonalPrivateStorage;
import io.github.jaeseongs95.sennard.personal.PersonalProperties;
import io.github.jaeseongs95.sennard.personal.PersonalStoreIsolation;
import io.github.jaeseongs95.sennard.server.context.AdminLogSource;
import io.github.jaeseongs95.sennard.server.context.AdminLogVerifier;
import io.github.jaeseongs95.sennard.server.context.AdminWebAuthn;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.server.context.SignedAttachmentSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.h2.SettingsStoreConfiguration;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.EncryptedRecords;
import io.github.jaeseongs95.sennard.service.store.pg.SennardDatabaseConfig;
import io.github.jaeseongs95.sennard.service.store.pg.DataSourceFactory;
import io.github.jaeseongs95.sennard.service.store.pg.JdbcDrivers;
import io.github.jaeseongs95.sennard.service.store.pg.RemoteConfigProvider;
import io.github.jaeseongs95.sennard.service.store.pg.StoreTransactionConfiguration;

/**
 * The context of {@code sennard attachments}: what the upkeep of the attachment folder ({@link AttachmentMaintenance}) needs and nothing else, with no web server, no hook, no MCP, no
 * schedule (the cleaner's rounds are not imported) and no store preparation. The beans come the way the server gets them:
 *
 * <ul>
 * <li>the application layer is {@link AppConfiguration} itself: the clock, the id generator, the message source, the error translation of the services, {@code SqlBuilder}, the
 * lazily made Spring Data JDBC repositories and the {@code sennard.attachments.*} properties record (its settings scan), with {@link BusinessJdbcConfiguration} for the dialect and
 * mapping the repositories need, asked from the connection at their first use, which is inside a service's transaction;</li>
 * <li>the attachment classes ({@link AttachmentService}, {@link UploadCandidates}, {@link AttachmentKeys}, {@link StorageFolders}, {@link SegmentCipher},
 * {@link AttachmentFileStore}, {@link AttachmentMaintenance}) are the server's own classes, imported one by one because the package is not scanned here;</li>
 * <li>the folders and key files are made and read through the edition's {@code PrivateStorage} ({@link PersonalPrivateStorage}), the bean the database pool's password file goes
 * through too (AGS3-D-0268);</li>
 * <li>the business database is reached as {@code sennard admin-reset} reaches it: the link the user signed on the admin page (the settings database of the state folder,
 * {@link SettingsStoreConfiguration}, which a running server holds: the command is run while the server is stopped), the user's driver, the pool of the
 * edition's isolation level and the store's own transaction manager ({@link StoreTransactionConfiguration}); {@code JdbcTemplateAutoConfiguration} makes the
 * {@code NamedParameterJdbcTemplate} over that one pool, as it does in the server.</li>
 * </ul>
 *
 * <p>This class and {@link AttachmentsEntrance} are in a package that the server's component scan does not cover ({@code ...sennard.server}, {@code ...sennard.personal}, {@code ...sennard.app}),
 * so the server's context has neither of them; only {@link AttachmentsContext} names this class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonalProperties.class)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({AppConfiguration.class, BusinessJdbcConfiguration.class, RandomConfiguration.class, PersonalStoreIsolation.class, PersonalPrivateStorage.class, StoreTransactionConfiguration.class,
        JdbcDrivers.class, DataSourceFactory.class, UtcTimestampColumns.class, UploadCandidates.class, AttachmentService.class, AttachmentKeys.class, StorageFolders.class, SegmentCipher.class,
        AttachmentFileStore.class, AttachmentMaintenance.class, AttachmentsEntrance.class, SettingsStoreConfiguration.class,
        SignedAttachmentSettings.class, AdminLogSource.class, AdminLogVerifier.class, AdminWebAuthn.class, ContextLayerStore.class, EncryptedRecords.class})
class AttachmentsConfiguration {
    /** The command's name in the sentence of a failure to start it. */
    static final String COMMAND = "sennard attachments";
    /** The pool of the command: one account's connections, as many as the server's pool of a linked database. */
    private static final int LINK_POOL_SIZE = 4;

    /** Where the runner leaves the result: registered in the context before it starts, taken by the caller of the start after it. */
    static final class Outcome {
        private AttachmentsResult result;

        void deliver(AttachmentsResult delivered) {
            this.result = delivered;
        }

        AttachmentsResult delivered() {
            if (result == null) {
                throw new IllegalStateException("The attachments context started without delivering a result.");
            }
            return result;
        }
    }

    /**
     * The pool of the installation's business database, the link the user signed on the admin page (the settings database of the state folder keeps it): the account's one
     * connection of the user's driver, at the edition's isolation level. Closed with the context.
     *
     * @throws UnavailableException COMMAND_NOT_STARTED when no business database is linked
     */
    @Bean
    @Qualifier("store")
    HikariDataSource store(DatabaseLinkSettings links, DataSourceFactory pools) {
        DatabaseLinkRequest link = links.find().orElseThrow(() -> new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED,
                SennardException.args("command", COMMAND, "cause", "No business database is linked on the admin page."))).request();
        SennardDatabaseConfig config = new RemoteConfigProvider(link.jdbcUrl(), link.accountName(), link.passwordRef(), link.driverPath(), LINK_POOL_SIZE, PreLinkAdminService.tlsPolicy(link.tls())).provide();
        return pools.create(config);
    }

    /**
     * Runs the request when the context has started: an entrance, so it opens no transaction and calls the upkeep, whose use cases are the service's. A runner and not a bean the caller
     * asks the context for, as the admin reset does it.
     */
    // @Bean: a lambda of Spring Boot's ApplicationRunner (a library type), made from the beans this context was given.
    @Bean
    ApplicationRunner attachmentsRunner(AttachmentsEntrance entrance, AttachmentsRequest request, Outcome outcome) {
        return arguments -> outcome.deliver(entrance.run(request));
    }
}
