// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Isolation;

/**
 * The isolation level of the store's transactions: this edition's setting ({@code sennard.store.isolation}), which the pool's connections are opened with
 * ({@code DataSourceFactory}). A class of its own so that the server's store wiring ({@link PersonalStoreConfiguration}) and the offline commands that open the store
 * (the admin reset) take the level from the same bean, the same setting and the same path.
 */
@Configuration(proxyBeanMethods = false)
public class PersonalStoreIsolation {
    @Bean
    @Qualifier("storeIsolation")
    Isolation storeIsolation(PersonalProperties properties) {
        return properties.store().isolation();
    }
}
