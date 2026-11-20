// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

import java.util.List;

/** A tool definition that calls a service holding the bundle of hook domains: found only by following what it reaches. */
final class ToolViaDomains {
    private final FixtureEntrance entrance = new FixtureEntrance();
    private final FixtureDomainsService service = new FixtureDomainsService(List.of());

    String define() {
        return entrance.tool("announce") + service.announce("an event the host never sent");
    }
}
