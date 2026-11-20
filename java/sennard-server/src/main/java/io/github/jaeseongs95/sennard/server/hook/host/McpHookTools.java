// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejectionBody;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** Dispatches registered MCP hooks through the same pipeline as HTTP hooks. */
@Configuration(proxyBeanMethods = false)
public final class McpHookTools implements ServerContextOnly {
    private static final Logger LOG = LoggerFactory.getLogger(McpHookTools.class);

    @Bean
    List<SyncToolSpecification> mcpHookSpecifications(List<McpHookRegistration> registrations, HookPipeline pipeline, JsonMapper mapper) {
        return registrations.stream().map(registration -> SyncToolSpecification.builder().tool(registration.tool())
                .callHandler((context, request) -> answer(registration, pipeline, mapper, request)).build()).toList();
    }

    private static McpSchema.CallToolResult answer(McpHookRegistration registration, HookPipeline pipeline, JsonMapper mapper, McpSchema.CallToolRequest request) {
        try {
            HookBody body = HookBody.fields(request.arguments());
            McpHookRegistration.Route route = registration.route(mapper, body);
            String module = Objects.toString(route.module(), "");
            HookOutput answer = pipeline.answer(registration.host().label(), Objects.toString(route.event(), ""), module.isEmpty() ? null : module, body, List.of());
            return McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(answer)).build();
        } catch (HookRejected rejected) {
            LOG.debug("MCP_HOOK_REJECTED {} {}", registration.host().label(), rejected.reason(), rejected);
            return McpSchema.CallToolResult.builder().isError(true).addTextContent(mapper.writeValueAsString(HookRejectionBody.of(rejected))).build();
        }
    }
}
