// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** A class in the middle of a chain: it knows nothing of the user's words and only reaches the dispatcher, which a tool must not reach even through a class like this. */
final class FixtureHop {
    private final FixtureDispatcher dispatcher = new FixtureDispatcher(java.util.List.of());

    int pass(String event) {
        return dispatcher.dispatch(event);
    }
}
