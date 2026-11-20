// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

/** A command of a skill's check, an enum constant that knows the name a caller sends for it (for example {@code validate-report}). */
public interface SkillCommand {
    /** The command name on the wire. */
    String wire();
}
