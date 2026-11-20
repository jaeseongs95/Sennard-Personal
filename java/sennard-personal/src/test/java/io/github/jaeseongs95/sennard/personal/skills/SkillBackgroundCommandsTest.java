// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The rule that a check which walks a tree (a directory listing, or the change journal's rescan of the write roots) or starts a process leaves the request's thread (design B.3) holds only if each such check declares its commands in
 * {@code backgroundCommands()}. The source is read because the sites are few and named: a new one fails here until its class is declared.
 */
class SkillBackgroundCommandsTest {
    private static final Pattern WALK_OR_PROCESS = Pattern.compile("\\b(?:facts|files)\\.list\\(|\\b(?:journal|scanner)\\.rescan\\(|\\bSkillProcessRunner runner\\b");

    @Test
    void everyCheckThatWalksOrStartsAProcessIsDeclared() throws IOException {
        Path sources = Path.of(System.getProperty("sennard.repo.root"), "java/sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/skills");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (WALK_OR_PROCESS.matcher(Files.readString(file)).find()) {
                    found.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(Set.of("ChangeScopeCheck.java", "EngineeringPractices.java"), found);
    }
}
