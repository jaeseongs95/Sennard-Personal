// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/** The personal edition's configuration registers its own storage of secrets as the one {@link PrivateStorage} bean: what the state folder, the key files and the password file go through. */
class PersonalPrivateStorageTest {
    @Test
    void theConfigurationOfThePersonalEditionGivesTheLocalStorageAsTheOnlyPort() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(PersonalPrivateStorage.class)) {
            assertEquals(1, context.getBeansOfType(PrivateStorage.class).size());
            assertInstanceOf(LocalPrivateStorage.class, context.getBean(PrivateStorage.class));
        }
    }
}
