// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** Stands for the hook domain interface ({@code HookDomain}): the hook classes implement it, and the dispatcher hands every hook event to each of them. */
interface FixtureDomain {
    int onEvent(String event);
}
