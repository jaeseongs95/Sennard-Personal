// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** Stands for the class that writes a user's prompt ({@code PromptArchiveWriter}); it may use the insert repository. */
final class FixtureWriter {
    private final FixtureInserts inserts = new FixtureInserts();

    int store(String prompt) {
        return inserts.insert(prompt);
    }
}
