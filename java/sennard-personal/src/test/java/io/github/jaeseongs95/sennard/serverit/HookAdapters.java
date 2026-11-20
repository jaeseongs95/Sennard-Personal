// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHookAdapter;
import tools.jackson.databind.json.JsonMapper;

/** The host adapters as a test makes them: the one place a test builds the mapper they bind with (the application gets its own from Spring Boot). */
public final class HookAdapters {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HookAdapters() {
    }

    public static ClaudeHookAdapter claude() {
        return new ClaudeHookAdapter(MAPPER);
    }

    public static CodexHookAdapter codex() {
        return new CodexHookAdapter(MAPPER);
    }

    public static AntigravityHookAdapter antigravity() {
        return new AntigravityHookAdapter(MAPPER);
    }

    public static GeminiHookAdapter gemini() {
        return new GeminiHookAdapter(MAPPER);
    }
}
