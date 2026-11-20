// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.SennardPersonalApplication;

/** Spring owns the test server's random port and context lifecycle; all state belongs to this test. */
@SpringBootTest(classes = SennardPersonalApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sennard.store.prepare=false", "sennard.scheduled-probes=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RandomPortServerTest {
    @TempDir
    static Path state;

    @DynamicPropertySource
    static void stateDirectory(DynamicPropertyRegistry properties) {
        properties.add("sennard.state-dir", () -> state.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @Test
    void springBootInjectsThePortActuallyBoundAndPublished() {
        assertEquals("0", environment.getRequiredProperty("server.port"));
        assertTrue(port > 0);
        ServerAddress published = ServerAddress.read(state.resolve(ServerAddress.FILE_NAME)).orElseThrow();
        assertEquals(port, published.port());
    }

    @Test
    void theHttpClientUsesTheSpringBootAssignedPort() throws Exception {
        assertEquals(200, RawHttp.exchange(port, "GET", "/actuator/health", Map.of(), null).status());
    }

    @Test
    void theServerUsesOnlyTheTestStateDirectory() {
        assertEquals(state.toAbsolutePath().normalize(), Path.of(environment.getRequiredProperty("sennard.state-dir")).toAbsolutePath().normalize());
    }
}
