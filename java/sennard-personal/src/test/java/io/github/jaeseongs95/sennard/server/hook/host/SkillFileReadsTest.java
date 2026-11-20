// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostInstalledSkills;

/**
 * Which paths are a skill's {@code SKILL.md} for a host: one of this installation's skills folder, or one of the host's own installed copy of the plugin; no other copy. Case-sensitivity is
 * the file system's rule ({@code Path.equals}), so the cases that differ only in letter case take their expectation from the same Path API and every other case is the same on every
 * operating system.
 */
class SkillFileReadsTest {
    private static final HostId HOST = CodexHost.ID;

    @TempDir
    Path home;

    private SkillFileReads reads(Path skills) {
        return new SkillFileReads(skills.toString());
    }

    /** The hosts' copies as a host reports them: the given folders for the host, nothing for any other host. */
    private SkillFileReads reads(Path skills, HostId host, Path... copies) {
        return new SkillFileReads(skills.toString(), asked -> asked.equals(host) ? List.of(copies) : List.of());
    }

    @Test
    void theSkillFileOfAFolderOfTheSkillsDirectoryIsThatSkill() {
        Path skills = home.resolve("skills");
        SkillFileReads reads = reads(skills);
        assertEquals(List.of("minimal-implementation"), reads.named(HOST, skills.resolve("minimal-implementation").resolve("SKILL.md").toString()));
        assertEquals(List.of("b"), reads.named(HOST, skills.resolve("a").resolve("..").resolve("b").resolve("SKILL.md").toString()), "dots are resolved before the folder is compared");
        assertEquals(List.of("a1"), reads.named(HOST, "  " + skills.resolve("a1").resolve("SKILL.md") + "  "), "blanks around the value are not part of the path");
    }

    @Test
    void anythingElseIsNoSkillRead() {
        Path skills = home.resolve("skills");
        SkillFileReads reads = reads(skills);
        assertEquals(List.of(), reads.named(HOST, home.resolve("other").resolve("a").resolve("SKILL.md").toString()), "another copy is not this installation's");
        assertEquals(List.of(), reads.named(HOST, skills.resolve("a").resolve("b").resolve("SKILL.md").toString()), "a folder deeper than one");
        assertEquals(List.of(), reads.named(HOST, skills.resolve("SKILL.md").toString()), "the skills folder's own file");
        assertEquals(List.of(), reads.named(HOST, skills.resolve("a").resolve("README.md").toString()), "another file of a skill");
        assertEquals(List.of(), reads.named(HOST, skills.resolve("Bad_Name").resolve("SKILL.md").toString()), "a folder that is no skill name");
        assertEquals(List.of(), reads.named(HOST, skills.resolve("a").resolve("..").resolve("..").resolve("x").resolve("SKILL.md").toString()), "climbing out of the skills folder");
        assertEquals(List.of(), reads.named(HOST, "a/SKILL.md"), "a relative path names no place");
        assertEquals(List.of(), reads.named(HOST, "a\0b/SKILL.md"), "text that is no path");
        assertEquals(List.of(), reads.named(HOST, null));
        assertEquals(List.of(), reads.named(HOST, ""));
    }

    @Test
    void noSkillsDirectoryMeansNoSkillRead() {
        assertEquals(List.of(), new SkillFileReads("").named(HOST, home.resolve("a").resolve("SKILL.md").toString()));
        assertEquals(List.of(), new SkillFileReads("   ").readByCommand(HOST, "cat " + home.resolve("skills").resolve("a").resolve("SKILL.md")));
    }

    /** {@code Path.equals}: "In some cases the paths are compared without regard to case, and others are case sensitive." */
    @Test
    void letterCaseIsTheFileSystemsRule() {
        Path skills = home.resolve("skills");
        SkillFileReads reads = reads(skills);
        Path upperRoot = Path.of(skills.toString().toUpperCase(Locale.ROOT));
        assertEquals(upperRoot.equals(skills) ? List.of("a") : List.of(), reads.named(HOST, upperRoot.resolve("a").resolve("SKILL.md").toString()));
        assertEquals(Path.of("skill.md").equals(Path.of("SKILL.md")) ? List.of("a") : List.of(), reads.named(HOST, skills.resolve("a").resolve("skill.md").toString()));
        assertEquals(skills.resolve("A").equals(skills.resolve("a")) ? List.of("a") : List.of(), reads.named(HOST, skills.resolve("A").resolve("SKILL.md").toString()));
    }

    @Test
    void aReadCommandThatNamesTheSkillFileIsThatSkillRead() {
        Path skills = home.resolve("skills");
        SkillFileReads reads = reads(skills);
        Path first = skills.resolve("first").resolve("SKILL.md");
        Path second = skills.resolve("second").resolve("SKILL.md");
        assertEquals(List.of("first"), reads.readByCommand(HOST, "cat " + first));
        assertEquals(List.of("first"), reads.readByCommand(HOST, "Get-Content '" + first + "'"));
        assertEquals(List.of("first"), reads.readByCommand(HOST, "sed -n 1,20p \"" + first + "\" | head"));
        assertEquals(List.of("first"), reads.readByCommand(HOST, "echo $(cat " + first + ")"));
        assertEquals(List.of("first", "second"), reads.readByCommand(HOST, "cat " + first + "; type '" + second + "' && cat " + first));
    }

    @Test
    void aQuotedPathWithBlanksIsOnePath() {
        Path skills = home.resolve("my skills");
        assertEquals(List.of("a"), reads(skills).readByCommand(HOST, "cat \"" + skills.resolve("a").resolve("SKILL.md") + "\""));
    }

    @Test
    void aCommandThatOnlyLooksAtTheFileOrNamesAnotherCopyIsNoRead() {
        Path skills = home.resolve("skills");
        SkillFileReads reads = reads(skills);
        Path file = skills.resolve("first").resolve("SKILL.md");
        assertEquals(List.of(), reads.readByCommand(HOST, "ls " + file), "ls only looked at it");
        assertEquals(List.of(), reads.readByCommand(HOST, "test -f " + file));
        assertEquals(List.of(), reads.readByCommand(HOST, "cat " + home.resolve("other").resolve("first").resolve("SKILL.md")));
        assertEquals(List.of(), reads.readByCommand(HOST, "cat *.md"), "a glob is no path");
        assertEquals(List.of(), reads.readByCommand(HOST, null));
    }

    /**
     * The shell on Windows spells the drive path {@code D:\ws\skills\a\SKILL.md} as {@code /d/ws/skills/a/SKILL.md}: the same file is the same skill in either spelling, for the
     * installation's folder and for the host's copy alike. The case needs a drive, which only the file systems that have drives give.
     */
    @Test
    void aDrivePathInTheShellsSpellingIsTheSameFileAsInTheFileSystemsSpelling() {
        Path skills = home.resolve("skills");
        Path copy = home.resolve("codex").resolve("skills");
        String drivePath = skills.toString();
        assumeTrue(drivePath.length() > 2 && Character.isLetter(drivePath.charAt(0)) && drivePath.charAt(1) == ':', "this file system has no drive letters");
        SkillFileReads reads = reads(skills, HOST, copy);
        for (Path folder : List.of(skills, copy)) {
            Path file = folder.resolve("orchestrator").resolve("SKILL.md");
            String shellSpelling = "/" + Character.toLowerCase(file.toString().charAt(0)) + file.toString().substring(2).replace('\\', '/');
            assertEquals(List.of("orchestrator"), reads.named(HOST, file.toString()), "the file system's spelling");
            assertEquals(List.of("orchestrator"), reads.named(HOST, shellSpelling), "the shell's spelling");
            assertEquals(List.of("orchestrator"), reads.readByCommand(HOST, "cat " + shellSpelling), "a command in the shell's spelling");
            assertEquals(List.of("orchestrator"), reads.readByCommand(HOST, "cat '" + file + "'"), "a command in the file system's spelling");
        }
        assertEquals(List.of(), reads.named(HOST, "/" + Character.toLowerCase(drivePath.charAt(0)) + "/elsewhere/orchestrator/SKILL.md"), "another folder is no skill in either spelling");
    }

    /** The host's own installed copy of the plugin is where the host reads a skill from: a read there is that skill used, by a path or by a command, the same as the installation's folder. */
    @Test
    void theHostsInstalledCopyIsCountedForThatHost() {
        Path copy = home.resolve("codex").resolve("plugins").resolve("cache").resolve("market").resolve("sennard").resolve("6.0.0").resolve("skills");
        SkillFileReads reads = reads(home.resolve("skills"), HOST, copy);
        assertEquals(List.of("orchestrator"), reads.named(HOST, copy.resolve("orchestrator").resolve("SKILL.md").toString()));
        assertEquals(List.of("orchestrator"), reads.named(HOST, copy.resolve("x").resolve("..").resolve("orchestrator").resolve("SKILL.md").toString()), "dots are resolved first");
        assertEquals(List.of("orchestrator"), reads.readByCommand(HOST, "cat " + copy.resolve("orchestrator").resolve("SKILL.md")));
        assertEquals(List.of("first"), reads.named(HOST, home.resolve("skills").resolve("first").resolve("SKILL.md").toString()), "the installation's own folder still counts");
    }

    /** Only the host's own copies count for it: another host's copy, a copy of another version of no host, and a deeper or foreign folder are not a skill being used. */
    @Test
    void anotherCopyIsNoSkillReadEvenWhenTheHostHasItsOwn() {
        Path copy = home.resolve("codex").resolve("skills");
        Path anotherHostsCopy = home.resolve("gemini").resolve("skills");
        SkillFileReads reads = reads(home.resolve("skills"), HOST, copy);
        assertEquals(List.of(), reads.named(HOST, anotherHostsCopy.resolve("orchestrator").resolve("SKILL.md").toString()), "a copy that is not reported for the host");
        assertEquals(List.of(), reads.named(HOST, home.resolve("elsewhere").resolve("orchestrator").resolve("SKILL.md").toString()));
        assertEquals(List.of(), reads.named(HOST, copy.resolve("a").resolve("b").resolve("SKILL.md").toString()), "a folder deeper than one");
        assertEquals(List.of(), reads.named(HOST, copy.resolve("orchestrator").resolve("README.md").toString()));
        assertEquals(List.of(), reads.readByCommand(HOST, "ls " + copy.resolve("orchestrator").resolve("SKILL.md")), "ls only looked at it");
    }

    /** A host's copy counts for that host only: the same file read under another host is not counted, and a host whose copies are not known counts none. */
    @Test
    void aHostsCopyIsNotAnotherHostsAndAnUnknownLocationCountsNothing() {
        Path copy = home.resolve("codex").resolve("skills");
        HostId other = AntigravityHost.ID;
        SkillFileReads reads = reads(home.resolve("skills"), HOST, copy);
        String file = copy.resolve("orchestrator").resolve("SKILL.md").toString();
        assertEquals(List.of("orchestrator"), reads.named(HOST, file));
        assertEquals(List.of(), reads.named(other, file), "the copy is Codex's, not agy's");
        assertEquals(List.of(), reads(home.resolve("skills")).named(HOST, file), "no known location: nothing stands in for it");
        HostInstalledSkills none = asked -> List.of();
        assertEquals(List.of(), new SkillFileReads("", none).readByCommand(other, "cat " + file));
    }
}
