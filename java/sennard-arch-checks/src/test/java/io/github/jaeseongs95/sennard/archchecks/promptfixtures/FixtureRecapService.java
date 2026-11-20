// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/**
 * A service a tool calls (like {@code PromptRecapService}) that takes the hook class that writes and hands it an event of its own making: the tool does not touch the collector, the
 * service does. Only the rule that follows what a tool reaches finds the way from the tool to the writer.
 */
final class FixtureRecapService {
    private final FixtureCollector collector = new FixtureCollector();

    int record(String made) {
        return collector.onPrompt(made);
    }
}
