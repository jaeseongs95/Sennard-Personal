// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.server.hook.HookBoundary;
import io.github.jaeseongs95.sennard.server.hook.HookController;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.message.Messages;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tests the public hook registration and the client capability handshake. */
class CodexWakeRegistrationTest {
    @Test
    void sessionStartBindsHostAndOtherCommandHooksDoNot() {
        List<HostRegistrationFile> files = registration();
        JsonNode hooks = file(files, Path.of("hooks", "hooks.json")).path("hooks");
        String start = handler(hooks, "SessionStart").path("command").asString();
        assertTrue(start.startsWith("sennard-client hook codex SessionStart"));
        assertTrue(List.of(start.split(" ")).contains("--bind-host"));
        assertTrue(start.contains("--start-server="));
        assertEquals("command", handler(hooks, "SessionStart").path("type").asString());
        assertFalse(handler(hooks, "UserPromptSubmit").path("command").asString().contains("--bind-host"));
        assertFalse(handler(hooks, "SessionEnd").path("command").asString().contains("--bind-host"));
    }

    @Test
    void registrationKeepsRelayAndDoesNotEmbedRuntimeEnvironment() {
        List<HostRegistrationFile> files = registration();
        JsonNode relay = file(files, Path.of(".mcp.json")).path("mcpServers").path("sennard");
        assertEquals("relay", relay.path("command").asString());
        assertTrue(relay.path("args").isArray());
        assertFalse(relay.has("url"));
        for (HostRegistrationFile file : files) {
            String content = file.content().toString();
            assertFalse(content.contains("CODEX_MANAGED_PACKAGE_ROOT"));
            assertFalse(content.contains(NativeWakeProtocol.KEY));
        }
    }

    @Test
    void capabilityIsAcknowledgedOnlyForAcceptedStartsWithHostCandidates() {
        String key = java.util.UUID.randomUUID().toString();
        assertEquals(key, bootstrap(key, "SessionStart", List.of(42L), true));
        assertNull(bootstrap(key, "SessionStart", List.of(), true));
        assertNull(bootstrap(key, "UserPromptSubmit", List.of(42L), true));
        assertNull(bootstrap(key, "SessionStart", List.of(42L), false));
        assertNull(bootstrap("old-environment-header", "SessionStart", List.of(42L), true));
    }

    private static String bootstrap(String key, String eventName, List<Long> candidates, boolean willing) {
        AtomicReference<String> observed = new AtomicReference<>();
        HookBoundary boundary = new HookBoundary() {
            @Override public boolean bootstrap(HostEvent.SessionStarted event, String registration) {
                assertEquals("session-a", event.context().sessionId());
                observed.set(registration);
                return willing && registration != null;
            }

            @Override public Optional<HookVerdict> before(HostEvent event) {
                return Optional.empty();
            }
        };
        var beans = new StaticListableBeanFactory();
        beans.addBean("handler", (HostEventHandler) call -> new HookVerdict.Proceed());
        JsonMapper mapper = JsonMapper.builder().build();
        var pipeline = new HookPipeline(List.of(new CodexHookAdapter(mapper)), beans.getBeanProvider(HostEventHandler.class),
                new Messages(new MessageSourceAccessor(new StaticMessageSource())), List.of(boundary));
        var answer = new HookController(pipeline).receive("codex", eventName, null, candidates, key,
                "{\"session_id\":\"session-a\",\"cwd\":\"/work\",\"prompt\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, answer.getStatusCode().value());
        String acknowledged = answer.getHeaders().getFirst(NativeWakeProtocol.KEY);
        if (acknowledged != null) {
            assertEquals(observed.get(), acknowledged);
        }
        return acknowledged;
    }

    private static List<HostRegistrationFile> registration() {
        return new CodexHookAdapter(JsonMapper.builder().build()).registrationFiles(new HookInstallation(List.of("sennard-client"), List.of("relay")));
    }

    private static JsonNode file(List<HostRegistrationFile> files, Path path) {
        return files.stream().filter(file -> file.relativePath().equals(path)).findFirst().orElseThrow().content();
    }

    private static JsonNode handler(JsonNode hooks, String event) {
        return hooks.path(event).get(0).path("hooks").get(0);
    }
}
