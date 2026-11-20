// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.nio.file.Path;
import java.util.List;

/**
 * Where a host has its copy of this installation's skills. A host with no skill tool opens a skill by reading its {@code SKILL.md}, and it reads it from the copy of the plugin it
 * made for itself, not from the installation's own skills folder. Whether such a read is a skill being used is decided in one place ({@code SkillFileReads}); this is the one fact
 * it needs from the host side.
 */
public interface HostInstalledSkills {
    /**
     * The folders that hold one folder per skill ({@code <folder>/<skill>/SKILL.md}) in the host's installed copies of the Sennard plugin; empty when the host has none installed or
     * where it keeps them cannot be told (a read is then not counted, and no other place stands in for it).
     */
    List<Path> skillFolders(HostId host);
}
