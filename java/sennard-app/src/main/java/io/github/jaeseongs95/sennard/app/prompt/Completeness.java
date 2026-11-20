// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

/**
 * Whether the archive kept the whole of a prompt or only its first part. The column {@code completeness} holds the constant's name and a CHECK lists the two: the schema has no boolean
 * column (a truth-value type is an optional feature of the standard) and no 0, 1, Y or N for a yes-or-no fact, and Spring Data JDBC maps an enum to its name, so this needs no converter.
 */
public enum Completeness {
    COMPLETE, TRUNCATED;

    public static Completeness of(boolean truncated) {
        return truncated ? TRUNCATED : COMPLETE;
    }

    public boolean isTruncated() {
        return this == TRUNCATED;
    }
}
