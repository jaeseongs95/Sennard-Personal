// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

import java.util.List;

/**
 * A service a tool calls that takes the bundle of hook domains ({@code List<HookDomain>}) and hands them an event of its own making: whichever domain writes a prompt would write it.
 * No class here names the writer or the hook class that writes.
 */
final class FixtureDomainsService {
    private final List<FixtureDomain> domains;

    FixtureDomainsService(List<FixtureDomain> domains) {
        this.domains = domains;
    }

    int announce(String made) {
        int handled = 0;
        for (FixtureDomain domain : domains) {
            handled += domain.onEvent(made);
        }
        return handled;
    }
}
