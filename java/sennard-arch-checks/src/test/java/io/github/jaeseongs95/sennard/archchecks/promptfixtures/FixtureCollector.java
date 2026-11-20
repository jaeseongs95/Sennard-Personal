// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** Stands for the hook class that writes ({@code PromptCollector}): a hook domain, and the one class outside the writer that may call it. It is not a tool definition. */
final class FixtureCollector implements FixtureDomain {
    private final FixtureWriter writer = new FixtureWriter();

    @Override
    public int onEvent(String event) {
        return onPrompt(event);
    }

    int onPrompt(String prompt) {
        return writer.store(prompt);
    }
}
