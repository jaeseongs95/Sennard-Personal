// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app;

import java.time.Clock;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.util.IdGenerator;
import org.springframework.util.JdkIdGenerator;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.app.support.ErrorTranslationAdvisor;
import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;

/**
 * The beans of the application layer. The repositories of the rewritten areas are found by package; the DataSource, the {@code NamedParameterJdbcTemplate}
 * and the transaction manager they use are whatever the application provides under Boot's names, so {@code spring.datasource.*} works with nothing written here.
 *
 * <p>The classes of this layer are components (the services, the message source, the error translation); a context that imports this class without scanning the
 * package gets the message source and the error translation through the {@code @Import} below. What is made by {@code @Bean} here is a JDK or Spring type, which
 * takes no annotation of ours.
 */
@Configuration(proxyBeanMethods = false)
@Import({CatalogMessageSource.class, ErrorTranslationAdvisor.class, SqlBuilder.class, LazyJdbcRepositories.class})
@ConfigurationPropertiesScan(basePackages = "io.github.jaeseongs95.sennard.app")
public class AppConfiguration {
    /** What every class asks for a sentence. Its default locale is English, the catalogs' source language, never the JVM's; a caller with a request passes the request's locale. */
    // @Bean: Spring's own class (it takes no annotation of ours), and the default locale is this bean's setting.
    @Bean
    MessageSourceAccessor messageSourceAccessor(MessageSource messageSource) {
        return new MessageSourceAccessor(messageSource, Locale.ENGLISH);
    }

    /**
     * What Spring Data JDBC converts between an {@code Instant} and a {@code TIMESTAMP} column ({@link UtcTimestampColumns}). Boot's configuration already makes a
     * {@code JdbcCustomConversions} (named {@code jdbcCustomConversions}) but has no place to add a converter, and replacing that configuration class would lose
     * {@code spring.data.jdbc.dialect} and its entity scan; so this one is made the way Spring Data JDBC makes it ({@link JdbcCustomConversions#of}: the dialect's own
     * conversions plus ours) and is {@code @Primary}, which is the one the mapping context and the converter take.
     *
     * <p>{@code @Lazy}, with the repositories' {@code BootstrapMode.LAZY} ({@link LazyJdbcRepositories}): a dialect that is asked from the connection (the personal edition's
     * {@code BusinessJdbcConfiguration}) is not there while the context starts, so what needs it is made at the first use of a repository, not at the start.
     */
    // @Bean: Spring Data's own class, made from the dialect the context has.
    @Bean
    @Lazy
    @Primary
    JdbcCustomConversions utcTimestampConversions(JdbcDialect dialect) {
        return JdbcCustomConversions.of(dialect, UtcTimestampColumns.converters());
    }

    /** The one clock of the application layer (a test sets a fixed one). */
    // @Bean: a JDK class.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** New identifiers (a message ID): Spring's own abstraction over {@code UUID.randomUUID()}, so a test can fix them. */
    // @Bean: Spring's own interface and class.
    @Bean
    IdGenerator idGenerator() {
        return new JdkIdGenerator();
    }
}
