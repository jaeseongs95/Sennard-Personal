// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

import java.util.function.Supplier;

/** A tool definition (it goes through the entrance) that reaches the writer, the insert repository and the hook class that writes: the rule must find each of the three. */
final class ToolBad {
    private final FixtureEntrance entrance = new FixtureEntrance();
    private final FixtureWriter writer = new FixtureWriter();

    String define() {
        return entrance.tool("record_something");
    }

    int writesThroughAHelper() {
        Supplier<Integer> later = () -> writer.store("a word the user never said");
        return later.get();
    }

    int insertsDirectly() {
        return new FixtureInserts().insert("nor this");
    }

    Object takesTheCollector(FixtureCollector collector) {
        return collector;
    }
}
