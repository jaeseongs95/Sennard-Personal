// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.ShellPaths;

/**
 * Paths as the POSIX shell on Windows (Git Bash, MSYS2) writes them. It writes a drive as {@code /d/ws/f}; Windows itself takes that for a path from the
 * root of the current drive. A path that names no drive ({@code /tmp/x}, {@code /usr/bin/x}) is relative to the root of the shell's own installation,
 * which this server cannot place, so it is only ever compared as text against what the user has declared.
 *
 * <p>This is text handling on purpose and the only place of it: a shell's path is written in the shell's syntax, which {@link java.nio.file.Path} does not read
 * (it follows the file system the program runs on, so on Linux a {@code \} is a letter and on Windows {@code /d/ws} is a path from the current drive's root).
 * Found by {@link java.util.ServiceLoader} where there is no Spring context (the validation of the admin log's exempt paths in the reset command).
 */
@Component
public final class MsysShellPaths implements ShellPaths {
    private static final Pattern DRIVE = Pattern.compile("^/([A-Za-z])(?:/(.*))?$", Pattern.DOTALL);
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

    @Override
    public String windowsSpelling(String value) {
        Matcher drive = DRIVE.matcher(value);
        if (!drive.matches()) {
            return value;
        }
        String rest = drive.group(2) == null ? "" : drive.group(2).replace('/', '\\');
        return drive.group(1).toUpperCase(Locale.ROOT) + ":\\" + rest;
    }

    @Override
    public boolean rootRelative(String value) {
        return value.startsWith("/") && !value.startsWith("//") && !DRIVE.matcher(value).matches();
    }

    @Override
    public String normalizeRooted(String value) {
        List<String> kept = new ArrayList<>();
        for (String segment : value.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!kept.isEmpty()) {
                    kept.removeLast();
                }
                continue;
            }
            kept.add(segment);
        }
        return "/" + String.join("/", kept);
    }

    @Override
    public boolean within(String place, String root) {
        String[] given = place.split("/");
        String[] base = root.split("/");
        if (base.length > given.length) {
            return false;
        }
        for (int index = 0; index < base.length; index++) {
            if (!"*".equals(base[index]) && !base[index].equals(given[index])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String childOf(String directory, String name) {
        return normalizeRooted(directory + "/" + name);
    }

    @Override
    public String baseName(String value) {
        String clean = TRAILING_SLASHES.matcher(value.replace('\\', '/')).replaceAll("");
        return clean.substring(clean.lastIndexOf('/') + 1);
    }

    @Override
    public String dirName(String value) {
        String clean = TRAILING_SLASHES.matcher(value.replace('\\', '/')).replaceAll("");
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? "." : slash == 0 ? "/" : clean.substring(0, slash);
    }

    @Override
    public boolean namesDirectory(String value) {
        return value.endsWith("/");
    }

    @Override
    public boolean holdsNoFile(String place) {
        return "/dev".equals(place) || place.startsWith("/dev/");
    }

    @Override
    public Optional<String> placeOf(String value, String shellDirectory, boolean fileSystemAbsolute) {
        if (value.startsWith("/") && !value.startsWith("//")) {
            return Optional.of(normalizeRooted(value));
        }
        if (shellDirectory != null && shellDirectory.startsWith("/") && !shellDirectory.startsWith("//") && !fileSystemAbsolute) {
            return Optional.of(childOf(shellDirectory, value));
        }
        return Optional.empty();
    }
}
