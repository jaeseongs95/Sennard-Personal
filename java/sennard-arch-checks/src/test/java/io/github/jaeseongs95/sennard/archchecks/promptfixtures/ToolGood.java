// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** A tool definition that only reads: it goes through the entrance and uses the reader, never the writer, the insert repository or the hook class. */
final class ToolGood {
    private final FixtureEntrance entrance = new FixtureEntrance();
    private final FixtureReader reader = new FixtureReader();

    String define() {
        return entrance.tool("recall_something") + reader.read();
    }
}
