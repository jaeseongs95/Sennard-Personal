// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the tool contract needs from the MCP layer. Its answer to a bad input is the envelope with {@code INVALID_INPUT} and the tool's reason code, so the
 * SDK's own check against the published schema, which answers in its own words before the tool runs, is off and each tool checks its input.
 */
@Configuration(proxyBeanMethods = false)
public class ToolInputConfiguration {
    // @Bean: a lambda of Spring AI's McpStatelessSyncServerCustomizer (a library type).
    @Bean
    McpStatelessSyncServerCustomizer statelessToolInputs() {
        return specification -> specification.validateToolInputs(false);
    }

    /**
     * The starter's mapper (which backs off for this name) with one difference: it drops null map entries, and a request's arguments are a map, so a required
     * argument sent as null ({@code expectedStateToken}, {@code role}) would arrive as missing. Not a default candidate, so the application's own mapper stays
     * the one every other injection point gets.
     */
    // @Bean: Jackson's JsonMapper (a library type), a second one under its own name that the starter looks for.
    @Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
    JsonMapper mcpServerJsonMapper(JsonMapper applicationMapper) {
        // Boot's mapper ignores unknown properties; the MCP SDK's default (and the nested objects of a tool's input) refuse them.
        return applicationMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)).build();
    }
}
