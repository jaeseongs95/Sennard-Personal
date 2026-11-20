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

/** Claude Code outside a Spring context. The host's names are written here and only here; its adapters read them from {@link #ID}. */
public final class ClaudeHost implements HostDefinition {
    @Configuration(proxyBeanMethods = false)
    static final class NativeWake implements ServerContextOnly {
        @Bean
        WakeTransport claudeWakeRelay(NativeWakeRelay relay) {
            return relay.transport(ID, true);
        }
    }

    /** Hooks and MCP requests are posted under {@code claude}; sessions are stored under {@code claude-code}. */
    public static final HostId ID = new HostId("claude", "claude-code");

    @Override
    public HostId host() {
        return ID;
    }

    @Override
    public Class<?> inputType() {
        return ClaudeHookInput.class;
    }

    @Override
    public HostHookAdapter hookAdapter(JsonMapper mapper) {
        return new ClaudeHookAdapter(mapper);
    }

    /** The plugin package of Claude Code is the repository's overlay folder, which the repository tools complete into the package (docs/contributing/hooks.md). */
    @Override
    public String packageDirectory() {
        return "claude-overlay";
    }
}
