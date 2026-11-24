// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            return TurnCommandClassification.runProgram(command).isEmpty() ? Optional.empty() : Optional.of(TurnWork.RUN);
        }
        return Optional.empty();
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
