// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** Neither a tool definition nor the hook class, and it calls the writer; and another that inserts without the writer: the rule must find both uses. */
final class StrayBad {
    private final FixtureWriter writer = new FixtureWriter();

    int writes() {
        return writer.store("from nowhere it should be written");
    }

    int inserts() {
        return new FixtureInserts().insert("around the writer");
    }
}
