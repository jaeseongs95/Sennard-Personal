// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app;

import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of random bytes of every context that has one: the server's and the settings context of the CLI (which imports this class).
 * Every class that needs randomness takes this bean through its constructor; none makes a {@link SecureRandom} of its own. It is a class apart from
 * {@link AppConfiguration} so that a context that wants nothing else of the application layer can import it alone.
 */
@Configuration(proxyBeanMethods = false)
public class RandomConfiguration {
    // @Bean: a JDK class.
    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
