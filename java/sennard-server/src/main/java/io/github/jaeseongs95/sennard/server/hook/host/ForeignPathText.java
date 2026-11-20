// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path text written for another platform than the one the server runs on, read as the text it is: the one place of it besides {@link MsysShellPaths}. A locator an agent declares for
 * a target (URI, drive path, UNC path, POSIX path), a write scope or a baseline path as git writes it, and the portable relative path of a check contract are spelled for any
 * platform and must come out the same on every host. {@link java.nio.file.Path} follows only the file system the program runs on (on Linux a {@code \} is a letter and a drive
 * is a file name, on Windows {@code /d/ws} is a path from the current drive's root and some characters are refused), so it cannot read them; text that names a place of this
 * host's own file system is read with {@code Path} everywhere else in the product.
 */
public final class ForeignPathText {
    private static final Pattern URI = Pattern.compile("^([a-z][a-z0-9+.-]*://)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_START = Pattern.compile("^[a-z]:", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_ALONE = Pattern.compile("^[a-z]:$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[a-z]:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPPER_DRIVE_PREFIX = Pattern.compile("^[A-Z]:.*");
    private static final Pattern REPEATED_SLASH = Pattern.compile("/+");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/$");
    private static final Pattern SLASH = Pattern.compile("/");

    private ForeignPathText() {
    }

    /**
     * Lexical only: a symlink or real-path identity needs file system evidence, not this. The locators are read by the syntax they show (URI, drive path, UNC path, POSIX path)
     * and the answer is the same on every host.
     */
    public static String canonicalLocator(String value) {
        String trimmed = value.trim().replace('\\', '/');
        Matcher uri = URI.matcher(trimmed);
        if (uri.matches() && !DRIVE_START.matcher(trimmed).find()) {
            String rest = TRAILING_SLASH.matcher(REPEATED_SLASH.matcher(uri.group(2)).replaceAll("/")).replaceAll("");
            return uri.group(1).toLowerCase(Locale.ROOT) + rest;
        }
        String text = trimmed.startsWith("//") ? "//" + REPEATED_SLASH.matcher(trimmed.substring(2)).replaceAll("/") : REPEATED_SLASH.matcher(trimmed).replaceAll("/");
        String normalized;
        if (DRIVE_ALONE.matcher(text).matches()) {
            normalized = text;
        } else {
            boolean drive = DRIVE_PREFIX.matcher(text).matches();
            normalized = drive || text.startsWith("//") ? normalizeWin32(text, drive) : normalizePosix(text);
        }
        String driveLower = UPPER_DRIVE_PREFIX.matcher(normalized).matches() ? Character.toLowerCase(normalized.charAt(0)) + normalized.substring(1) : normalized;
        String stripped = driveLower.endsWith("/") ? driveLower.substring(0, driveLower.length() - 1) : driveLower;
        return stripped.isEmpty() ? "/" : stripped;
    }

    private static String normalizePosix(String text) {
        boolean absolute = text.startsWith("/");
        boolean trailing = text.endsWith("/");
        String joined = String.join("/", collapse(List.of(SLASH.split(text)), absolute));
        String path = joined.isEmpty() && !absolute ? "." : joined;
        if (!path.isEmpty() && trailing && !"/".equals(path)) {
            path += "/";
        }
        return (absolute ? "/" : "") + path;
    }

    private static String normalizeWin32(String text, boolean drive) {
        boolean unc = text.startsWith("//");
        String driveText = drive ? text.substring(0, 2) : "";
        String rest = unc ? text.substring(2) : text.substring(driveText.length());
        boolean absolute = unc || rest.startsWith("/");
        boolean trailing = rest.endsWith("/");
        List<String> tail = collapse(List.of(SLASH.split(rest)), absolute);
        String path = String.join("/", tail);
        String out = (unc ? "//" : driveText) + (absolute && !unc ? "/" : "") + (path.isEmpty() && !absolute ? "." : path);
        return trailing && !out.endsWith("/") ? out + "/" : out;
    }

    private static List<String> collapse(List<String> parts, boolean rooted) {
        ArrayList<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.get(stack.size() - 1))) {
                    stack.remove(stack.size() - 1);
                } else if (!rooted) {
                    stack.add("..");
                }
            } else {
                stack.add(part);
            }
        }
        return stack;
    }

    /** The text with every backslash read as a separator: a scope is a pattern over repository paths, which git writes with {@code /}, on every platform. */
    public static String slashed(String text) {
        return text.replace('\\', '/');
    }

    /** Whether the portable text holds a backslash (a separator of another platform, which a portable path must not carry). */
    public static boolean hasBackslash(String text) {
        return text.indexOf('\\') >= 0;
    }

    /** Whether the text starts the way a Windows drive path does ({@code C:...}); a repository-relative POSIX path must not. */
    public static boolean looksLikeDrivePath(String text) {
        return text.length() >= 2 && text.charAt(0) < 128 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ':';
    }

    /** The components of a portable path, read by {@code /}; an empty component (a doubled or trailing slash) is kept, where {@link java.nio.file.Path} would drop it. */
    public static List<String> components(String portable) {
        return List.of(portable.split("/", -1));
    }

    /** Whether two portable paths (no trailing slash) are the same or one lies under the other, on whole components. */
    public static boolean overlaps(String first, String second) {
        return first.equals(second) || first.startsWith(second + "/") || second.startsWith(first + "/");
    }
}
