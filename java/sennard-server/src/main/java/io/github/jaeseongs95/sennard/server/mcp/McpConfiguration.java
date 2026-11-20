// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.Map;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the MCP starter does not do on its own: carry the host's label to the tools, and refuse undeclared arguments. Tool
 * discovery and the JSON-RPC side stay the starter's.
 */
@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
    /** The key a tool reads from {@link McpTransportContext} for the value of {@value #HOST_LABEL_HEADER}, absent when the host sends none. */
    public static final String HOST_LABEL = "sennard.hostLabel";
    /** The unmodified tool request, available with the label at the stateless MCP handler. */
    public static final String TOOL_REQUEST = "sennard.toolRequest";
    static final String HOST_LABEL_HEADER = "Sennard-Host-Label";

    /**
     * The starter's stateless transport, built the same way as its auto-configuration (which backs off for this bean) except that the
     * request's host label reaches the tools. The stateless protocol is the only one the server runs ({@code spring.ai.mcp.server.protocol}).
     */
    // @Bean: the MCP SDK's own transport class (a library type), whose builder takes the request's host label.
    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties serverProperties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(serverProperties.getMcpEndpoint())
                .contextExtractor(request -> request.headers().firstHeader(HOST_LABEL_HEADER) instanceof String label
                        ? McpTransportContext.create(Map.of(HOST_LABEL, label))
                        : McpTransportContext.EMPTY)
                .build();
    }

    /** The server's instructions are the installed intake and the delivery guide; the starter hands them to every {@code initialize} answer. */
    // @Bean: a static BeanPostProcessor (an anonymous class), made before the beans it post-processes; a component cannot be that.
    @Bean
    static BeanPostProcessor serverInstructions(ObjectProvider<Messages> messages, ObjectProvider<SennardProperties> sennardProperties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof McpServerProperties properties && properties.getInstructions() == null) {
                    properties.setInstructions(SkillIntake.serverInstructions(messages.getObject(), sennardProperties.getObject().skillsDirectory()));
                }
                return bean;
            }
        };
    }

    // @Bean: a static BeanPostProcessor, so that the container makes it before the tool lists it post-processes (a component of the configuration's own beans would be made with them).
    @Bean
    static UndeclaredArgumentGuard undeclaredArgumentGuard(ObjectProvider<SennardToolResults> results) {
        return new UndeclaredArgumentGuard(results);
    }
}
