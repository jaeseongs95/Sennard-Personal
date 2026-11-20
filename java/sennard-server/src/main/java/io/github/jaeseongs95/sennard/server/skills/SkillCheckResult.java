// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

/** What a command answered: its value (a record or a map Jackson writes) and an exit code, 0 when it holds and 1 when a judged check did not. */
public record SkillCheckResult(Object value, int exitCode) {
    public static SkillCheckResult ok(Object value) {
        return new SkillCheckResult(value, 0);
    }

    public static SkillCheckResult of(Object value, boolean holds) {
        return new SkillCheckResult(value, holds ? 0 : 1);
    }
}
