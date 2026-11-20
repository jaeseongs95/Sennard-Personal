// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The HTTP clients of {@link TypeSafeProvider}: one bean each for the classification call and for the model list, made from Spring's {@code RestClient.Builder} with
 * the settings the provider needs (its address from {@link TypeSafeProperties}, the same one the provider tells the admin page, no redirect followed, its own read time limit).
 * The provider never builds a client.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TypeSafeProperties.class)
class TypeSafeClientConfiguration {
    static final String CLASSIFICATION_CLIENT = "typeSafeClassificationClient";
    static final String MODELS_CLIENT = "typeSafeModelsClient";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(5);

    @Bean(CLASSIFICATION_CLIENT)
    RestClient typeSafeClassificationClient(RestClient.Builder builder, TypeSafeProperties properties) {
        return client(builder, properties, Duration.ofMillis(JevProvider.CALL_TIMEOUT_MS));
    }

    @Bean(MODELS_CLIENT)
    RestClient typeSafeModelsClient(RestClient.Builder builder, TypeSafeProperties properties) {
        return client(builder, properties, MODELS_TIMEOUT);
    }

    private static RestClient client(RestClient.Builder builder, TypeSafeProperties properties, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults().withRedirects(HttpRedirects.DONT_FOLLOW).withTimeouts(CONNECT_TIMEOUT, readTimeout);
        return builder.baseUrl(properties.baseUrl()).requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings)).build();
    }
}
