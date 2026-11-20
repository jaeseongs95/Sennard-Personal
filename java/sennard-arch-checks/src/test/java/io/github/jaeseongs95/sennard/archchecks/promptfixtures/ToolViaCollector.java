// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** A tool definition that names none of the writer, the insert repository and the hook class that writes, and calls a service that uses the hook class: found only by following what it reaches. */
final class ToolViaCollector {
    private final FixtureEntrance entrance = new FixtureEntrance();
    private final FixtureRecapService service = new FixtureRecapService();

    String define() {
        return entrance.tool("record_recap") + service.record("a word the user never said");
    }
}
