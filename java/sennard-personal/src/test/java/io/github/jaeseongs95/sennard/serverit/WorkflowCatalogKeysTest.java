// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** A failure the workflow raises must render: every message key its sources name is in the English and the Korean catalog. */
class WorkflowCatalogKeysTest {
    private static final Pattern KEY = Pattern.compile("\\bmsg\\(\\s*\"([A-Za-z0-9_.]+)\"");

    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(directory.resolve("locales"))) {
            directory = directory.getParent();
        }
        return directory;
    }

    private static Set<String> keysNamedIn(Path sources) throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    private static Set<String> catalog(Path directory) throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().equals("translations.lock.json")).toList()) {
                mapper.readTree(file.toFile()).propertyNames().forEach(keys::add);
            }
        }
        return keys;
    }

    @Test
    void everyKeyTheWorkflowNamesIsInBothCatalogs() throws IOException {
        Path repository = repository();
        Path server = repository.resolve("java/sennard-server/src/main/java/io/github/jaeseongs95/sennard/server");
        Set<String> named = new TreeSet<>();
        for (String module : List.of("workflow", "approval")) {
            named.addAll(keysNamedIn(server.resolve(module)));
        }
        assertFalse(named.isEmpty());
        for (String locale : List.of("en", "ko")) {
            Set<String> missing = new TreeSet<>(named);
            missing.removeAll(catalog(repository.resolve("locales").resolve(locale)));
            assertEquals(Set.of(), missing, locale);
        }
    }
}
