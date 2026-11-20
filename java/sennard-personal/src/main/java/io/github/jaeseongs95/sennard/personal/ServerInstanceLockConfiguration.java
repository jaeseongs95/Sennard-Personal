// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Makes the server's {@link ServerInstanceLock}. The method is {@code static} because the bean is a {@code BeanFactoryPostProcessor}: the container makes those before the other beans, and
 * a non-static method would make this configuration class early as well, which Spring warns about. The closing of the lock at the end of the context is the container's (the bean is
 * {@link AutoCloseable}).
 */
@Configuration(proxyBeanMethods = false)
class ServerInstanceLockConfiguration {
    @Bean
    static ServerInstanceLock serverInstanceLock(Environment environment) throws IOException {
        return new ServerInstanceLock(environment);
    }
}
