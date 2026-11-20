// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Spring Data JDBC's configuration for the business database, made when the database is first used and not when the server starts.
 *
 * <p>The server listens first and prepares the business database behind the listener, so there is no connection while the context starts, and the database may be of any
 * product the settings name. Spring Data JDBC's {@code DialectResolver} (what {@link AbstractJdbcConfiguration#jdbcDialect} calls, and what Boot's own configuration calls)
 * asks the connection which product it is and how the server quotes identifiers (MySQL and MariaDB differ by {@code ANSI_QUOTES} and
 * {@code lower_case_table_names}); nothing here picks a dialect or copies a quoting rule. {@code @Lazy} makes every bean of this configuration (the dialect, the conversions,
 * the mapping context, the converter, the aggregate template) wait for the first repository that needs it; the repositories themselves are lazy in
 * {@code AppConfiguration} ({@code BootstrapMode.LAZY}). The first use is inside a service's transaction, which already has a connection, so a use before the store is ready
 * is refused at the transaction's begin with STORE_PREPARING, as before, and the next use after it retries.
 *
 * <p>This class takes the place of Boot's {@code SpringBootJdbcConfiguration}, which backs off when an {@code AbstractJdbcConfiguration} is a bean: its other
 * additions are the {@code spring.data.jdbc.dialect} setting (not used here; the connection decides) and an entity scan from the application's package, which is this package
 * and finds the same as Spring Data's own default.
 */
@Configuration(proxyBeanMethods = false)
@Lazy
public class BusinessJdbcConfiguration extends AbstractJdbcConfiguration {
}
