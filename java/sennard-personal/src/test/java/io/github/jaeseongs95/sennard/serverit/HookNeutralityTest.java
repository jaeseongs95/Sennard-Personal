// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * ARCH-ADAPT-1: no name of a host in common code. Read from the compiled classes, so a later change that adds a
 * host name to the entry point fails here. The adapters' own package is the one place these names belong.
 */
class HookNeutralityTest {
    private static final List<String> HOST_NAMES = List.of("claude", "codex", "agy", "antigravity", "gemini", "anthropic", "openai");
    private static final String ADAPTERS = "/hook/host/";

    private static List<Path> compiledHookClasses() throws IOException {
        String property = System.getProperty("sennard.product.classes");
        assertNotNull(property, "the build passes the compiled product classes (sennard-personal/build.gradle.kts)");
        List<Path> classes = new ArrayList<>();
        for (String entry : property.split(File.pathSeparator)) {
            for (String common : List.of("hook", "mcp", "session")) {
                Path directory = Path.of(entry).resolve("io/github/jaeseongs95/sennard/server/" + common);
                if (Files.isDirectory(directory)) {
                    try (Stream<Path> files = Files.walk(directory)) {
                        files.filter(path -> path.toString().endsWith(".class")).forEach(classes::add);
                    }
                }
            }
        }
        return classes;
    }

    private static boolean inAdapters(Path classFile) {
        return classFile.toString().replace('\\', '/').contains(ADAPTERS);
    }

    @Test
    void thereIsCompiledCommonCodeToCheck() throws IOException {
        List<Path> classes = compiledHookClasses();
        assertTrue(classes.stream().anyMatch(path -> path.getFileName().toString().equals("HookController.class")), classes.toString());
        assertTrue(classes.stream().anyMatch(HookNeutralityTest::inAdapters), "the check must be able to tell the two apart");
    }

    @Test
    void commonHookCodeNamesNoHost() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path classFile : compiledHookClasses()) {
            if (inAdapters(classFile)) {
                continue;
            }
            ClassModel model = ClassFile.of().parse(classFile);
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof Utf8Entry utf8) {
                    String value = utf8.stringValue().toLowerCase(Locale.ROOT);
                    HOST_NAMES.stream().filter(value::contains).forEach(name -> found.add(classFile.getFileName() + ": " + utf8.stringValue()));
                }
            }
        }
        assertTrue(found.isEmpty(), "host names in common hook code: " + found);
    }
}
