// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.sennard.service.settings.h2.SettingsStoreConfiguration;

/**
 * The personal edition's settings store: Sennard's own H2 database under {@code sennard.settings.directory} (set from the state folder in {@code application.yaml}), apart from the
 * business database. It is migrated when the server starts, so the passkey registry, the admin records made before the link and the link settings are there before any
 * business database is.
 */
@Configuration(proxyBeanMethods = false)
@Import(SettingsStoreConfiguration.class)
public class PersonalSettingsConfiguration {
}
