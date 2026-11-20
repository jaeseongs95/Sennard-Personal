// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** A tool definition that reaches the dispatcher through a class in between: the only thing wrong is the way to the dispatcher, which no other part of the rule sees. */
final class ToolViaDispatcher {
    private final FixtureEntrance entrance = new FixtureEntrance();
    private final FixtureHop hop = new FixtureHop();

    String define() {
        return entrance.tool("pass_on") + hop.pass("an event");
    }
}
