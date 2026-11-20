// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

/**
 * The size each path may have that puts the shared intake (the block of {@code skills/orchestrator/references/intake.md}) or what points at it in front of a session, and how much of
 * the block any path but the server instructions may quote. The numbers are written here and nowhere else; the tests that measure the paths read them from here.
 *
 * <p>The block travels by exactly one path, the server's initialize instructions, which every host gives its model. Every other path points at the block (its file) or lists the skill
 * targets, and none of them repeats it: a copy on a second path is paid for again in the same session, and it is the tokens the user's budget decision is about.
 */
public enum IntakeDeliveryBudget {
    /**
     * The server's initialize instructions: the block in the catalog's frame plus the session-message guide. The user's decision (12,000 UTF-8 bytes), the same number the intake file's
     * own block is held to by {@code RepositoryContractTest.theIntakeBlockStaysWithinItsByteBudget}. Merge repeated sentences instead of raising it.
     */
    SERVER_INSTRUCTIONS(12_000),
    /**
     * What a hook answers at a session start: the host's invocation line, the pointer to the installed intake file and the list of installed skill targets, one line per skill (about
     * 2 KB for 23 skills), with the room of a few short lines of the shared context layers. The block alone is about 10 KB, so a cap under half of it catches a copy of the block,
     * and the list still has room to grow by about a dozen skills.
     */
    SESSION_START_HOOK(5_000),
    /**
     * What a hook answers at a turn start: the checkpoint cue, and for a host that restores at every turn start (agy) the same pointer and target list as a session start. The same
     * reasoning and the same number as {@link #SESSION_START_HOOK}.
     */
    TURN_START_HOOK(5_000),
    /**
     * The applicability of one skill in the {@code get_skill_inventory} and {@code classify_skills} results (both are made by the same {@code SkillInventoryReader}): when the skill
     * applies, as the lines its classification cites. The largest of the other skills is about 3.5 KB, so a skill that carries a body of its own, such as the intake block (about
     * 10 KB), goes over it.
     */
    SKILL_APPLICABILITY(4_000);

    /**
     * The most of the block, in UTF-8 bytes, that a path other than the server instructions may repeat word for word (runs of 24 characters or more): a tenth of the block. A skill's
     * applicability or exclusion may quote the sentence that says when the skill applies, as the orchestrator's does; it may not carry the body.
     */
    public static final int QUOTED_BLOCK_BYTES = 1_000;

    private final int bytes;

    IntakeDeliveryBudget(int bytes) {
        this.bytes = bytes;
    }

    /** The most the path may be, in UTF-8 bytes. */
    public int bytes() {
        return bytes;
    }
}
