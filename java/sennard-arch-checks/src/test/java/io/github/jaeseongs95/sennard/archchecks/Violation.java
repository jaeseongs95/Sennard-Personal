// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

/**
 * One place where a rule is broken: the rule, the file (relative to the repository's java folder; for the rules of {@link RepositoryRules}, relative to the repository root),
 * the line and the text of that line.
 */
record Violation(String rule, String file, int line, String text) {
    String place() {
        return file + ":" + line;
    }

    @Override
    public String toString() {
        return place() + "  " + text;
    }
}
