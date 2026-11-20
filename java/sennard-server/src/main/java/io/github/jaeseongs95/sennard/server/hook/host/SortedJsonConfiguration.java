// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper that writes a tree with its properties sorted, for the keys a host's call is matched by (the same arguments must give the same text whatever
 * order the host wrote them in). It is the application's mapper with that one setting added, made here once and injected by its name; it is not a default candidate,
 * so every other injection point still gets the application's own mapper.
 */
@Configuration(proxyBeanMethods = false)
class SortedJsonConfiguration {
    static final String SORTED_JSON_MAPPER = "sortedJsonMapper";

    // @Bean: Jackson's JsonMapper (a library type), a second one under its own name next to the application's.
    @Bean(name = SORTED_JSON_MAPPER, defaultCandidate = false)
    JsonMapper sortedJsonMapper(JsonMapper applicationMapper) {
        return applicationMapper.rebuild().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
    }
}
