// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.server.skills.JevProvider.CatalogStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * D-0227: a model is offered by the TypeSafe provider only when its list names it (the bundled list, then the list the provider last gave); a well-formed name that is in no list is
 * not offered, so a signed setting that names it is {@code MODEL_UNSUPPORTED}. No call leaves this computer: a local HTTP server stands in for the provider's list.
 */
class TypeSafeProviderModelsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static HttpServer remote;

    @BeforeAll
    static void start() throws IOException {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/models", exchange -> {
            byte[] bytes = "{\"models\":[{\"name\":\"jev-1.15.0\"},{\"name\":\"not a model\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        remote.start();
    }

    @AfterAll
    static void stop() {
        remote.stop(0);
    }

    @Test
    void aWellFormedModelThatNoListNamesIsNotOfferedAndOneTheProviderListsIsOfferedFromThen() {
        String address = "http://127.0.0.1:" + remote.getAddress().getPort();
        RestClient client = RestClient.builder().baseUrl(address).build();
        TypeSafeProvider provider = new TypeSafeProvider(MAPPER, new TypeSafeProperties(address, true), client, client);

        for (String bundled : TypeSafeProvider.BUNDLED_MODELS) {
            assertTrue(provider.profileFor(bundled).isPresent(), bundled + " is in the bundled list");
        }
        assertTrue(provider.profileFor("jev-9.9.9").isEmpty(), "a model name of this provider that no list names");
        assertTrue(provider.profileFor("jev-1.15.0").isEmpty(), "not offered before the provider listed it");
        assertTrue(provider.profileFor("not-a-model").isEmpty());
        assertTrue(provider.profileFor(null).isEmpty());

        assertEquals(CatalogStatus.CURRENT, provider.catalog(io.github.jaeseongs95.sennard.domain.secret.SecretValue.of("a-key"), 1L).status());
        assertTrue(provider.profileFor("jev-1.15.0").isPresent(), "offered once the provider's list names it");
        assertTrue(provider.profileFor("jev-9.9.9").isEmpty());
        assertEquals("jev-1.15.0", provider.profileFor("jev-1.15.0").orElseThrow().modelId());
    }
}
