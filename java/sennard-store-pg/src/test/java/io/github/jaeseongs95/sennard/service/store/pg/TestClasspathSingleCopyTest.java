// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.migration.MigrationScript;

/**
 * The module's own classes and migration scripts are on the test JVM's classpath once. The task {@code businessDbTest} is given the classpath of the test source set, and
 * sennard-store-pg applies java-test-fixtures, whose plugin replaces that source set's runtimeClasspath (main output folders left out, the main jar in through the fixtures'
 * dependency on the project); a classpath read before the replacement held both, and {@code classpath*:db/expand/**} found every script twice ("Two migration scripts have the
 * number 18"). The class is tagged {@code business-db} so that it runs in {@code businessDbTest}, the task that had the fault; it needs no database.
 *
 * <pre>
 * Case                             Input                                             Expected
 * a class of the main code         resource MigrationRunner.class                    one location on the classpath
 * a migration script               resource db/expand/V1__module_schema.sql               one location on the classpath
 * the scripts as the runner reads  MigrationScript.load("db/expand")                 no failure (no number is found twice)
 * </pre>
 */
@Tag("business-db")
class TestClasspathSingleCopyTest {
    private static List<URL> locations(String resource) throws IOException {
        return Collections.list(TestClasspathSingleCopyTest.class.getClassLoader().getResources(resource));
    }

    @Test
    void aClassOfTheMainCodeIsOnTheClasspathOnce() throws IOException {
        List<URL> found = locations("io/github/jaeseongs95/sennard/service/store/pg/MigrationRunner.class");
        assertEquals(1, found.size(), "the main classes are on the classpath as a folder and as a jar: " + found);
    }

    @Test
    void aMigrationScriptIsOnTheClasspathOnce() throws IOException {
        List<URL> found = locations("db/expand/V1__module_schema.sql");
        assertEquals(1, found.size(), "the main resources are on the classpath as a folder and as a jar: " + found);
    }

    @Test
    void theRunnerReadsTheExpandScriptsWithoutFindingANumberTwice() {
        assertDoesNotThrow(() -> MigrationScript.load("db/expand"));
    }
}
