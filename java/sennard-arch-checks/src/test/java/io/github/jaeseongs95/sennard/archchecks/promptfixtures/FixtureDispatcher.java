// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

import java.util.List;

/** Stands for the hook dispatcher ({@code SessionEventHandler}): it holds the hook domains and hands each hook event to them. It is the one class that may call a domain's {@code onEvent}. */
final class FixtureDispatcher {
    private final List<FixtureDomain> domains;

    FixtureDispatcher(List<FixtureDomain> domains) {
        this.domains = domains;
    }

    int dispatch(String event) {
        int handled = 0;
        for (FixtureDomain domain : domains) {
            handled += domain.onEvent(event);
        }
        return handled;
    }
}
