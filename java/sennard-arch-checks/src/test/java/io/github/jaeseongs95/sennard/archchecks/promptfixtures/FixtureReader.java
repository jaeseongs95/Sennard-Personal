// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

/** Stands for the read side of the archive ({@code PromptArchiveReader}). */
final class FixtureReader {
    String read() {
        return "what the user said";
    }
}
