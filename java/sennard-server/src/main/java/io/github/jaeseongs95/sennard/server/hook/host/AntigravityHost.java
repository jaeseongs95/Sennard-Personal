// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.messaging.NativeWakeRelay;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.databind.json.JsonMapper;

/** Antigravity outside a Spring context. The host's names are written here and only here; its adapters read them from {@link #ID}. */
public final class AntigravityHost implements HostDefinition {
    @Configuration(proxyBeanMethods = false)
    static final class NativeWake implements ServerContextOnly {
        @Bean
        WakeTransport antigravityWakeRelay(NativeWakeRelay relay) {
            return relay.transport(ID);
        }
    }

    /** Hooks and MCP requests are posted under {@code agy}; sessions are stored under {@code antigravity}. */
    public static final HostId ID = new HostId("agy", "antigravity");
    /** The folder under the Gemini folder where Antigravity keeps what it works with (it is spelled as the host's stored name, but it is the host's own folder and not a name of it). */
    static final String APPLICATION_FOLDER = "antigravity";

    @Override
    public HostId host() {
        return ID;
    }

    @Override
    public Class<?> inputType() {
        return AntigravityHookInput.class;
    }

    @Override
    public HostHookAdapter hookAdapter(JsonMapper mapper) {
        return new AntigravityHookAdapter(mapper);
    }

    /** Antigravity reads the repository's own root as its plugin package. */
    @Override
    public String packageDirectory() {
        return "";
    }
}
