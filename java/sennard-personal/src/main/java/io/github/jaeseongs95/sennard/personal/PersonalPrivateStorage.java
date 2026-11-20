// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The storage of secrets of the personal edition ({@link LocalPrivateStorage}): the one {@link PrivateStorage} bean, which the state folder, the key and pepper files, the settings
 * database folder and the database password file go through. A class of its own so that the server's wiring and the offline commands that open the store (the admin reset) take it from
 * the same place, as they take the isolation level from {@link PersonalStoreIsolation}.
 */
@Configuration(proxyBeanMethods = false)
public class PersonalPrivateStorage {
    @Bean
    PrivateStorage privateStorage() {
        return new LocalPrivateStorage();
    }
}
