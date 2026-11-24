// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Classifies a shell command for turn evidence after the hook adapter has read it. */
final class TurnCommandClassification {
    private static final Pattern COMMAND_SEPARATOR = Pattern.compile("&&|\\|\\||;|\\|");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ENVIRONMENT_PREFIX = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern QUOTES = Pattern.compile("^[\"'(]+|[\"')]+$");
    private static final Pattern WINDOWS_SUFFIX = Pattern.compile("\\.(?:exe|cmd|bat)$");
    /** Programs that look at or arrange things without running the changed thing again; git is whole because none of its subcommands runs the user's program. */
    private static final Set<String> NOT_A_RUN = Set.of("cd", "pushd", "popd", "pwd", "ls", "dir", "tree", "cat", "type", "head", "tail", "less", "more", "nl", "wc", "stat", "file", "grep",
            "egrep", "fgrep", "rg", "find", "which", "where", "echo", "printf", "true", "false", "sleep", "export", "set", "mkdir", "touch", "cp", "mv", "rm", "chmod", "git", "sed", "diff",
            "cmp", "sort", "uniq", "tee", "get-content", "select-string", "get-childitem", "get-location", "set-location", "write-output", "write-host", "test-path");

    private TurnCommandClassification() {
    }

    /** The program of the first part of a command that runs something other than inspection, or "" when there is none; a heredoc body is not read. */
    static String runProgram(String command) {
        int heredoc = command.indexOf("<<");
        for (String segment : COMMAND_SEPARATOR.split(heredoc < 0 ? command : command.substring(0, heredoc))) {
            String program = programOf(segment);
            if (!program.isEmpty() && !NOT_A_RUN.contains(program)) {
                return program;
            }
        }
        return "";
    }

    private static String programOf(String segment) {
        String[] words = WHITESPACE.split(segment.strip());
        int index = 0;
        while (index < words.length && ENVIRONMENT_PREFIX.matcher(words[index]).matches()) {
            index++;
        }
        if (index >= words.length) {
            return "";
        }
        // The program's name is read as the host's shell wrote it, with either separator (a Windows shell's {@code C:\tools\git.exe} and a POSIX shell's {@code /usr/bin/git}), on whichever
        // operating system this server runs: Path follows only the file system's own syntax (on Linux a backslash is a letter), so the text is read by both.
        String token = QUOTES.matcher(words[index]).replaceAll("").replace('/', '\\');
        String name = token.substring(token.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
        return WINDOWS_SUFFIX.matcher(name).replaceFirst("");
    }
}
