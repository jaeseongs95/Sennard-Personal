// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostInstalledSkills;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;

/**
 * A skill opened by reading its {@code SKILL.md}: the way of the hosts with no skill tool. A skill of this installation counts, and so does a skill of the copy of the plugin the host
 * made for itself (Codex reads {@code <codex home>/plugins/cache/.../sennard/<version>/skills/}, not the installation's folder); the host only says where its copies are
 * ({@link HostInstalledSkills}), and this class decides whether a read is one. Any other copy is not a skill being used, and a host whose copies are not known counts none of its own.
 * Whether a path is a skill's file of such a folder is the file system's rule ({@code Path.equals}), letter case included, not this class's.
 */
@Component
final class SkillFileReads {
    private static final Logger LOG = LoggerFactory.getLogger(SkillFileReads.class);
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Path SKILL_FILE = Path.of("SKILL.md");
    /** A command that names the file without one of these ({@code ls}, {@code test -f}) only looked at it. */
    private static final Pattern READ_COMMAND = Pattern.compile("(?:^|[\\s;&|(\"'`])(?:cat|sed|head|tail|nl|less|more|bat|awk|perl|python3?|node|type|Get-Content|gc|cut|strings|od|xxd)(?=[\\s\"'`]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** The words of a command that can be a path: a quoted text, or a run that has no blank, quote, backtick or shell operator in it. */
    private static final Pattern WORD = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s\"'`;&|()<>]+)");

    /** The skills folder of this installation, absolute and normalized; null when none is configured. */
    private final Path skills;
    /** Where a host has its own copy of the plugin; absent in a context that has no installation to ask (no host copy is then counted). */
    private final HostInstalledSkills hostCopies;
    /** How the shell a command runs in spells a path that is not this file system's spelling ({@code /c/Users/x} is {@code C:\Users\x}). */
    private final ShellPaths shellPaths;

    /** The skills folder comes from the settings ({@code sennard.skills-dir}), by constructor injection; the host copies from the installation's host locations when it has them. */
    @Autowired
    SkillFileReads(SennardProperties properties, ObjectProvider<HostInstalledSkills> hostCopies, ShellPaths shellPaths) {
        this(properties.skillsDirectory(), hostCopies.getIfAvailable(() -> host -> List.of()), shellPaths);
    }

    SkillFileReads(String skillsDirectory) {
        this(skillsDirectory, host -> List.of());
    }

    SkillFileReads(String skillsDirectory, HostInstalledSkills hostCopies) {
        this(skillsDirectory, hostCopies, new MsysShellPaths());
    }

    SkillFileReads(String skillsDirectory, HostInstalledSkills hostCopies, ShellPaths shellPaths) {
        this.skills = skillsDirectory.isBlank() ? null : Path.of(skillsDirectory.strip()).toAbsolutePath().normalize();
        this.hostCopies = hostCopies;
        this.shellPaths = shellPaths;
    }

    /** The skill whose {@code SKILL.md} the given path is for the host, as a list of at most one; empty for any other path. */
    List<String> named(HostId host, String path) {
        return path == null ? List.of() : skillOf(host, path.strip()).map(List::of).orElse(List.of());
    }

    List<String> readByCommand(HostId host, String command) {
        if (command == null || !READ_COMMAND.matcher(command).find()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher word = WORD.matcher(command);
        while (word.find()) {
            String text = word.group(1) != null ? word.group(1) : word.group(2) != null ? word.group(2) : word.group(3);
            skillOf(host, text).ifPresent(found::add);
        }
        return new ArrayList<>(found);
    }

    /**
     * The skill's name when the text is the {@code SKILL.md} of a folder of this installation's skills folder, or of the skills folder of the host's own installed copy of the plugin. The
     * name is the folder's, in the lower case skill names are written in. Any other copy is not counted, and neither is a host whose copies are not known.
     */
    private Optional<String> skillOf(HostId host, String text) {
        if (!text.toLowerCase(Locale.ROOT).contains("skill.md")) {
            return Optional.empty();
        }
        // The text as the file system reads it, then as the shell's own spelling of a drive path: the same file gives the same answer in either spelling, and a spelling the file
        // system does not take as absolute falls through to the other.
        Optional<String> asWritten = skillOfPath(host, text);
        String spelled = shellPaths.windowsSpelling(text);
        return asWritten.isPresent() || spelled.equals(text) ? asWritten : skillOfPath(host, spelled);
    }

    private Optional<String> skillOfPath(HostId host, String text) {
        Path file;
        try {
            file = Path.of(text);
        } catch (InvalidPathException notAPath) {
            LOG.debug("not a path, so not a skill file: {}", text, notAPath);
            return Optional.empty();
        }
        if (!file.isAbsolute()) {
            return Optional.empty();
        }
        file = file.normalize();
        Path folder = file.getParent();
        if (folder == null || folder.getFileName() == null || !SKILL_FILE.equals(file.getFileName())) {
            return Optional.empty();
        }
        String name = folder.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!SKILL_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        if (skills != null && skills.resolve(name).equals(folder)) {
            return Optional.of(name);
        }
        // The host's copies are asked last: only a path that is shaped like a skill's file gets here.
        return hostCopies.skillFolders(host).stream().anyMatch(copy -> copy.resolve(name).equals(folder)) ? Optional.of(name) : Optional.empty();
    }
}
