// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.Set;

/**
 * The judgments of one skill, reached through {@code run_skill_check}. A bean for each skill; the tool knows only this interface. The skill's commands are an enum
 * ({@link SkillCommand}): the caller's command name is turned into the constant once, by {@link SkillCheckService}, and the check switches over the constants.
 */
public interface SkillCheck<C extends Enum<C> & SkillCommand> {
    /** The skill's directory name under {@code skills/}. */
    String skill();

    /** The enum of this skill's commands. */
    Class<C> commandType();

    /**
     * The commands whose time grows with what they read: those that walk a directory tree or start a child process over a repository or a caller's command. {@code run_skill_check}
     * answers them with a job reference (design B.3); a stage gate that reaches one still runs it, because its answer is the gate's verdict.
     */
    default Set<C> backgroundCommands() {
        return Set.of();
    }

    /** Runs one command. An input rejection is an {@code InvalidInputException} (or an {@code IntegrityException}); a failed check is a result with exit code 1. */
    SkillCheckResult run(C command, SkillArguments arguments);
}
