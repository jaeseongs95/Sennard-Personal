// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Switches Spring's cache annotations on, the one place that does. The cache manager is Spring Boot's: its {@code CacheAutoConfiguration}
 * (spring-boot-cache, brought by spring-boot-starter-cache) acts once {@code @EnableCaching} is there and builds the Caffeine manager, because Caffeine is on the classpath.
 * The glossary's one entry ({@link GlossaryLoader#load}) is read once and valid until the glossary's migration runs again.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(proxyTargetClass = true)
public class GlossaryCacheConfiguration {
}
