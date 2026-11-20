// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/** Rule p (TimeRules): the product has no machine clock, no machine zone and no JVM-zone time type; the fixtures prove the rule goes red. */
class TimeRulesTest {
    private static final String FIXTURES = "io.github.jaeseongs95.sennard.archchecks.timefixtures";

    @Test
    void theProductReadsNoMachineClockAndNoMachineZone() {
        List<Path> directories = Stream.of(System.getProperty("sennard.arch.classes").split(",")).map(Path::of).filter(Files::isDirectory).toList();
        assertTrue(directories.size() >= 8, "the compiled classes of the product's modules are read: " + directories);
        JavaClasses classes = new ClassFileImporter().importPaths(directories);
        assertTrue(classes.contain("io.github.jaeseongs95.sennard.app.AppConfiguration"), "the clock bean's class is among those read");
        List<String> found = TimeRules.violations(classes);
        assertEquals(List.of(), found, () -> found.stream().collect(Collectors.joining("\n    ", "\n    ", "")));
    }

    @Test
    void theRuleFindsEachWayTheFixtureBreaksItAndNothingInTheOnesThatKeepToIt() {
        JavaClasses bad = new ClassFileImporter().importPackages(FIXTURES).that(com.tngtech.archunit.base.DescribedPredicate.describe("TimeBad",
                type -> type.getSimpleName().equals("TimeBad")));
        List<String> found = TimeRules.violations(bad);
        for (String part : List.of("java.time.Instant.now()", "java.time.LocalDateTime.now()", "System.currentTimeMillis()", "java.time.ZoneId.systemDefault()",
                "java.util.TimeZone.getDefault()", "Clock.systemUTC() outside the clock makers", "uses java.util.Date", "uses java.util.Calendar", "uses java.sql.Timestamp")) {
            assertTrue(found.stream().anyMatch(line -> line.contains(part)), part + " is found in " + found);
        }
        JavaClasses good = new ClassFileImporter().importPackages(FIXTURES).that(com.tngtech.archunit.base.DescribedPredicate.describe("TimeGood",
                type -> type.getSimpleName().equals("TimeGood")));
        assertEquals(1, good.stream().count());
        assertEquals(List.of(), TimeRules.violations(good));
    }
}
