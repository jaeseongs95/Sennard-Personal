// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * What a service method's {@code @Transactional} needs over the DataSource named {@code store}: the transaction manager and the {@code storeJdbc} JdbcClient whose failures become Sennard
 * reasons. {@link StoreConfiguration} imports it; a context that has one service over one repository and nothing else of the store (the offline admin reset
 * command) imports it alone, so that the connection and the transaction are the store's own wherever the store is used.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
public class StoreTransactionConfiguration {
    /**
     * The settings of the edition ({@code spring.transaction.default-timeout}, {@code spring.transaction.rollback-on-commit-failure}) reach this manager the way Boot gives them to its own:
     * through {@link TransactionManagerCustomizers}. A context that has none (no Boot) leaves Spring's defaults.
     */
    @Bean
    StoreTransactionManager transactionManager(@Qualifier("store") DataSource store, ObjectProvider<TransactionManagerCustomizers> customizers) {
        StoreTransactionManager manager = new StoreTransactionManager(store);
        customizers.ifAvailable(available -> available.customize(manager));
        return manager;
    }

    @Bean
    @Qualifier("storeJdbc")
    JdbcClient storeJdbcClient(@Qualifier("store") DataSource store) {
        JdbcTemplate template = new JdbcTemplate(store);
        template.setExceptionTranslator(StoreErrors.translator(store));
        return JdbcClient.create(template);
    }
}
