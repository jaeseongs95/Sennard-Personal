// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

import io.github.jaeseongs95.sennard.service.store.jobs.JobStore;
import io.github.jaeseongs95.sennard.service.store.modules.ModuleStore;

/**
 * The store's Spring wiring: what a transaction needs ({@link StoreTransactionConfiguration}: the transaction manager, the {@code storeJdbc} JdbcClient whose failures become Sennard reasons and
 * the module locks) and the module repositories. An assembly supplies the DataSource named {@code store} (the one account's pool) and imports this.
 */
@Configuration(proxyBeanMethods = false)
@Import(StoreTransactionConfiguration.class)
@EnableAsync(proxyTargetClass = true)
@ComponentScan(basePackageClasses = {ModuleStore.class, JobStore.class})
public class StoreConfiguration {
}
