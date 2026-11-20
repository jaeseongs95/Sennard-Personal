// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** A tool defined by the annotation, not through the entrance, that reaches the writer: the rule must see this way of defining a tool too. */
final class ToolBadAnnotated {
    private final FixtureWriter writer = new FixtureWriter();

    @FixtureTool
    int tool() {
        return writer.store("not allowed here");
    }
}
