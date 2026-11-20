// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.SennardLocaleResolver;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;

/**
 * The admin page's routes. The page is a second connector of the same application, on loopback, on a port the operating system gives at each start
 * ({@link AdminPageAddress}); a passkey is bound to the host name, not to the port, so the page can move between starts.
 */
@Configuration(proxyBeanMethods = false)
public class AdminConfiguration {
    /** After the guard's stages ({@code Ordered.HIGHEST_PRECEDENCE} to {@code + 2}, {@code GuardConfiguration}): the guard answers first. */
    private static final int ROUTE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * The page's own route. Tomcat matches the URL pattern {@code /} against the request path exactly (the root path), unlike the servlet mapping {@code /}, which is the
     * default servlet and takes every path; {@code AdminPageServerTest} holds this.
     */
    // @Bean: Spring Boot's FilterRegistrationBean (a library type) names the filter's URL pattern, order and name; the filter takes the page's address as a bean.
    @Bean
    FilterRegistrationBean<AdminPageFilter> adminPageFilter(AdminPageAddress address, FilterRefusals refusals, CspNonce nonces, AdminTexts texts, SennardLocaleResolver locales)
            throws IOException {
        String moveTemplate;
        try (InputStream in = new ClassPathResource("admin/move-to-localhost.html").getInputStream()) {
            moveTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        FilterRegistrationBean<AdminPageFilter> registration = new FilterRegistrationBean<>(new AdminPageFilter(address, refusals, nonces, moveTemplate, texts, locales));
        registration.setName("adminPageFilter");
        registration.setOrder(ROUTE_ORDER);
        registration.addUrlPatterns("/");
        return registration;
    }

    // @Bean: Spring Boot's FilterRegistrationBean (a library type) names the filter's URL pattern, order and name.
    @Bean
    FilterRegistrationBean<AdminApiFilter> adminApiFilter(AdminPageAddress address, FilterRefusals refusals) {
        FilterRegistrationBean<AdminApiFilter> registration = new FilterRegistrationBean<>(new AdminApiFilter(address, refusals));
        registration.setName("adminApiFilter");
        registration.setOrder(ROUTE_ORDER);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /** After the routes' filters (they mark what they took), before everything the application serves. */
    // @Bean: Spring Boot's FilterRegistrationBean (a library type) names the filter's URL pattern, order and name.
    @Bean
    FilterRegistrationBean<AdminPortGate> adminPortGate(AdminPageAddress address, FilterRefusals refusals) {
        FilterRegistrationBean<AdminPortGate> registration = new FilterRegistrationBean<>(new AdminPortGate(address, refusals));
        registration.setName("adminPortGate");
        registration.setOrder(ROUTE_ORDER + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
