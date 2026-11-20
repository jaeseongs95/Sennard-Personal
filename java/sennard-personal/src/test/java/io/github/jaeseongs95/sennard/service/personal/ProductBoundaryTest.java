// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * ARCH-DB-1 (DBMS 6): store code has no way to a connection that did not go through the store's JdbcClient and its transaction manager. Read from the compiled
 * classes, so a later change that adds such a path fails here and not in review. (LOCAL-DB-1, "the personal product has no path to an external database", went with
 * the embedded database: the business database is the one the user names.)
 */
class ProductBoundaryTest {
    private static List<Path> classFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    /** The repositories and the session services reach the database through the store's JdbcClient and nothing else. */
    @Test
    void theRepositoriesCannotReachAConnectionOutsideTheStoreJdbcClient() throws Exception {
        List<String> scannedClasses = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String directory : System.getProperty("sennard.encoding.classes").split(File.pathSeparator)) {
            for (Path file : classFiles(Path.of(directory))) {
                String path = file.toString().replace(File.separatorChar, '/');
                if (!path.contains("/service/store/modules/") && !path.contains("/service/identity/")) {
                    continue;
                }
                scannedClasses.add(file.getFileName().toString());
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                for (PoolEntry entry : model.constantPool()) {
                    if (entry instanceof Utf8Entry text
                            && Stream.of("java/sql/Connection", "javax/sql/DataSource", "org/springframework/jdbc/core/JdbcTemplate").anyMatch(text.stringValue()::contains)) {
                        offenders.add(file.getFileName() + " names " + text.stringValue());
                    }
                }
            }
        }
        for (String repository : List.of("ContextLayerStore.class", "ContinuityStore.class", "JevUsageStore.class",
                "SkillUsageStore.class", "TrustStore.class", "WorkflowStore.class", "SessionRegistry.class", "LeaseService.class")) {
            assertTrue(scannedClasses.contains(repository), "the scan read " + repository);
        }
        assertEquals(List.of(), offenders);
    }
}
