// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared intake, as the deployed skill wrote it: the block between its markers, in the package language, in the frame of the message catalog. The deployed
 * folder is what the hosts read; the copy of that file built into the server answers when the folder is absent, as for a server run from its jar alone.
 */
public final class SkillIntake {
    private static final String RELATIVE = "orchestrator/references/intake.md";
    private static final String PACKAGED = "sennard-skills/skills/" + RELATIVE;
    private static final Pattern BLOCK = Pattern.compile("<!-- skill-intake:start -->\\r?\\n(.*?)\\r?\\n<!-- skill-intake:end -->", Pattern.DOTALL);

    /** The part of a skill's {@code classification.json} that the server instructions list. */
    record Classification(List<String> targets) {
    }

    private SkillIntake() {
    }

    /** @param skillsDirectory the deployed skills folder; blank when none is configured */
    static String serverInstructions(Messages messages, String skillsDirectory) {
        return serverInstructions(messages, skillsDirectory, Messages.MODEL_LOCALE);
    }

    /** The instructions in one catalog language: what the server sends is the model's, and the size budget holds for each language a catalog has. */
    static String serverInstructions(Messages messages, String skillsDirectory, String languageTag) {
        return messages.render(MessageRef.of("instructions.intake.frame", "intake", block(skillsDirectory)), languageTag) + "\n"
                + messages.render(MessageRef.of("instructions.sessionMessage.guide"), languageTag);
    }

    /** The deployed intake file, for a hook to point at; only a file that holds the block is named. */
    public static Optional<Path> deployedIntake(String skillsDirectory) throws IOException {
        if (skillsDirectory.isBlank()) {
            return Optional.empty();
        }
        Path deployed = Path.of(skillsDirectory).resolve(RELATIVE);
        return Files.isRegularFile(deployed) && BLOCK.matcher(Files.readString(deployed)).find() ? Optional.of(deployed.toAbsolutePath()) : Optional.empty();
    }

    /** The installed skills' declared targets, one line each, derived from the installed files so it never lists a skill that is not installed; empty when none declares any. */
    public static String installedSkillTargets(String skillsDirectory, JsonMapper mapper) throws IOException {
        if (skillsDirectory.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> skills = Files.list(Path.of(skillsDirectory))) {
            for (Path skill : skills.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                Path file = skill.resolve("classification.json");
                if (Files.isRegularFile(file)) {
                    Classification classification = mapper.readValue(Files.readString(file), Classification.class);
                    List<String> targets = classification.targets() == null ? List.of() : classification.targets();
                    lines.add("- " + skill.getFileName() + ": " + String.join(", ", targets));
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String block(String skillsDirectory) {
        Path deployed = skillsDirectory.isBlank() ? null : Path.of(skillsDirectory).resolve(RELATIVE);
        try (InputStream in = deployed != null && Files.isRegularFile(deployed) ? Files.newInputStream(deployed) : new ClassPathResource(PACKAGED).getInputStream()) {
            Matcher matcher = BLOCK.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new IllegalStateException("The shared skill intake has no block between its markers: " + RELATIVE);
        } catch (IOException cause) {
            throw new IllegalStateException("The shared skill intake could not be read: " + RELATIVE, cause);
        }
    }
}
