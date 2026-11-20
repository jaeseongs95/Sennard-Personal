// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import tools.jackson.databind.json.JsonMapper;

/** Gemini CLI outside a Spring context. The host's names are written here and only here; its adapters read them from {@link #ID}. */
public final class GeminiHost implements HostDefinition {
    public static final HostId ID = new HostId("gemini");

    @Override
    public HostId host() {
        return ID;
    }

    @Override
    public Class<?> inputType() {
        return GeminiHookInput.class;
    }

    @Override
    public HostHookAdapter hookAdapter(JsonMapper mapper) {
        return new GeminiHookAdapter(mapper);
    }

    /**
     * The Gemini CLI extension folder (geminicli.com/docs/extensions/reference: gemini-extension.json and hooks/hooks.json in the extension's root). It is its own
     * folder: the repository root's hooks/hooks.json is Codex's definition, whose event names Gemini CLI does not have.
     */
    @Override
    public String packageDirectory() {
        return "gemini-extension";
    }
}
