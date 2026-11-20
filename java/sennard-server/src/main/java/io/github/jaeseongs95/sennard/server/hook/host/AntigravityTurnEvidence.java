// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.TurnWork;
import tools.jackson.databind.JsonNode;

/**
 * What one finished Antigravity tool call shows about its turn. agy reads a skill's SKILL.md itself and nothing stops a turn that changed files from ending
 * without having run the changed thing again; the hook events show those three facts and nothing else. What they cannot show is never claimed: whether a
 * command reproduced the symptom or exited 0, a change made by a shell command, or whether the model understood a SKILL.md it opened.
 */
final class AntigravityTurnEvidence {
    private static final Logger LOG = LoggerFactory.getLogger(AntigravityTurnEvidence.class);

    /** The finished call's artifact folder, carried in its arguments by the adapter under a name no agy tool uses. */
    static final String ARTIFACT_DIRECTORY = "_artifactDirectoryPath";

    private static final Set<String> EDIT_TOOLS = Set.of("Write", "Edit", "MultiEdit");
    private static final Set<String> SHELL_TOOLS = Set.of("Bash", "PowerShell");
    private static final List<String> PATH_KEYS = List.of("TargetFile", "AbsolutePath", "FilePath", "file_path", "Path", "path");
    private static final Pattern COMMAND_SEPARATOR = Pattern.compile("&&|\\|\\||;|\\|");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ENVIRONMENT_PREFIX = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern QUOTES = Pattern.compile("^[\"'(]+|[\"')]+$");
    private static final Pattern WINDOWS_SUFFIX = Pattern.compile("\\.(?:exe|cmd|bat)$");
    /** Programs that look at or arrange things without running the changed thing again; git is whole because none of its subcommands runs the user's program. */
    private static final Set<String> NOT_A_RUN = Set.of("cd", "pushd", "popd", "pwd", "ls", "dir", "tree", "cat", "type", "head", "tail", "less", "more", "nl", "wc", "stat", "file", "grep",
            "egrep", "fgrep", "rg", "find", "which", "where", "echo", "printf", "true", "false", "sleep", "export", "set", "mkdir", "touch", "cp", "mv", "rm", "chmod", "git", "sed", "diff",
            "cmp", "sort", "uniq", "tee", "get-content", "select-string", "get-childitem", "get-location", "set-location", "write-output", "write-host", "test-path");

    private AntigravityTurnEvidence() {
    }

    /**
     * @param name the tool's name in the mapped form
     * @param failed the host's own report; a failed edit changed nothing, a failed command still ran, and a failing re-run is what the turn should show
     */
    static Optional<TurnWork> classify(String name, JsonNode input, boolean failed) {
        if (EDIT_TOOLS.contains(name)) {
            return failed || insideArtifactFolder(input) ? Optional.empty() : Optional.of(TurnWork.EDIT);
        }
        if (input.toString().contains("SKILL.md")) {
            return Optional.of(TurnWork.SKILL_READ);
        }
        if (SHELL_TOOLS.contains(name)) {
            String command = HookJson.present(input, "CommandLine").orElseGet(() -> HookJson.text(input, "command"));
            return runProgram(command).isEmpty() ? Optional.empty() : Optional.of(TurnWork.RUN);
        }
        return Optional.empty();
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

    private static boolean insideArtifactFolder(JsonNode input) {
        String folder = HookJson.text(input, ARTIFACT_DIRECTORY);
        String target = PATH_KEYS.stream().map(key -> HookJson.text(input, key)).filter(value -> !value.isEmpty()).findFirst().orElse("");
        if (folder.isEmpty() || target.isEmpty()) {
            return false;
        }
        try {
            Path directory = comparable(folder);
            Path file = comparable(target);
            return file.startsWith(directory) && !file.equals(directory);
        } catch (InvalidPathException notAPath) {
            LOG.debug("not a path, so not inside the artifact folder: {} {}", folder, target, notAPath);
            return false;
        }
    }

    /** Whether two such paths are the same place, or one is under the other, is the file system's rule ({@code Path.equals}, {@code Path.startsWith}), including letter case. */
    private static Path comparable(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
