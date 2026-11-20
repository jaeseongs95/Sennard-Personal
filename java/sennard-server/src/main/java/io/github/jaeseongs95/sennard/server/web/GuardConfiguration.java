// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;

/**
 * Puts the guard first on every path: the stages in the order a request meets them (origin and host, then the MCP protocol version, then the body), all ahead of
 * the admin page's filters ({@code Ordered.HIGHEST_PRECEDENCE + 10} and after).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SennardProperties.class)
public class GuardConfiguration {
    // @Bean: Spring Boot's FilterRegistrationBean (a library type) names the filter's URL pattern, order and name.
    @Bean
    FilterRegistrationBean<GuardFilter> guardFilterRegistration(AdminPageAddress address, FilterRefusals refusals) {
        FilterRegistrationBean<GuardFilter> registration = new FilterRegistrationBean<>(new GuardFilter(address, refusals));
        registration.setName("guardFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Only the MCP endpoint reads the header, so the filter is registered for the endpoint's own URL pattern. */
    // @Bean: Spring Boot's FilterRegistrationBean (a library type); its URL pattern is a setting of the MCP starter.
    @Bean
    FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilterRegistration(McpServerStreamableHttpProperties mcp, McpStatelessServerTransport transport, FilterRefusals refusals) {
        // The SDK's own list, so what the header check accepts cannot drift from what initialize negotiates.
        List<String> versions = transport.protocolVersions();
        FilterRegistrationBean<McpProtocolVersionFilter> registration = new FilterRegistrationBean<>(new McpProtocolVersionFilter(Set.copyOf(versions), refusals));
        registration.setName("mcpProtocolVersionFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns(mcp.getMcpEndpoint());
        return registration;
    }

    // @Bean: Spring Boot's FilterRegistrationBean (a library type) names the filter's URL pattern, order and name.
    @Bean
    FilterRegistrationBean<BodyGuardFilter> bodyGuardFilterRegistration(SennardProperties properties, AdminPageAddress address, FilterRefusals refusals) {
        FilterRegistrationBean<BodyGuardFilter> registration = new FilterRegistrationBean<>(new BodyGuardFilter(properties.guard().maxBody().toBytes(),
                properties.guard().adminMaxBody().toBytes(), address, refusals));
        registration.setName("bodyGuardFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
