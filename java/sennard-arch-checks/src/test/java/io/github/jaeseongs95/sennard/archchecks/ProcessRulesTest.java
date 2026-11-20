// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/** Rule q (ProcessRules): the product starts or reaches a process only in the places on the list; the fixtures prove the rule goes red. */
class ProcessRulesTest {
    private static final String FIXTURES = "io.github.jaeseongs95.sennard.archchecks.processfixtures";

    @Test
    void theProductUsesTheProcessApiOnlyWhereTheListSaysAndTheListMatchesRealUses() {
        List<Path> directories = Stream.of(System.getProperty("sennard.arch.classes").split(",")).map(Path::of).filter(Files::isDirectory).toList();
        assertTrue(directories.size() >= 8, "the compiled classes of the product's modules are read: " + directories);
        JavaClasses classes = new ClassFileImporter().importPaths(directories);
        assertTrue(classes.contain("io.github.jaeseongs95.sennard.personal.LocalProcessRunner") && classes.contain("io.github.jaeseongs95.sennard.protocol.ToolProcess"), "the listed classes are among those read");
        List<String> found = ProcessRules.violations(classes);
        assertEquals(List.of(), found, () -> found.stream().collect(Collectors.joining("\n    ", "\n    ", "")));
        // A place on the list that no class uses any more is a list gone stale: every entry must be a package or class of the product that uses the API.
        List<String> using = classes.stream().filter(type -> type.getDirectDependenciesFromSelf().stream().anyMatch(dependency -> dependency.getTargetClass().getFullName().startsWith("java.lang.Process")))
                .map(type -> type.getName()).toList();
        for (String packageName : ProcessRules.ALLOWED_PACKAGES) {
            assertTrue(using.stream().anyMatch(name -> name.startsWith(packageName)), "no class of " + packageName + " uses the process API; remove it from the list");
        }
        for (String type : ProcessRules.ALLOWED_TYPES) {
            assertTrue(using.stream().anyMatch(name -> name.equals(type) || name.startsWith(type + "$")), type + " does not use the process API; remove it from the list");
        }
    }

    @Test
    void aPlaceIsAllowedOnlyByTheExactPackageOrClassOnTheList() {
        assertTrue(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.LocalProcessRunner"));
        assertTrue(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.LocalProcessRunner$Child"));
        assertTrue(ProcessRules.allowed("io.github.jaeseongs95.sennard.hookclient.HookClient"));
        assertTrue(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.install.Installer"));
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.cli.SennardCli"), "the commands of the CLI no longer use the process API, and the installer is what does");
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.service.store.embedded.PgServer"), "the embedded database's server is gone");
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.LocalProcessRunnerHelper"), "a longer name is another class");
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.RunEnvironment"));
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.server.skills.EngineeringPractices"));
        assertFalse(ProcessRules.allowed("io.github.jaeseongs95.sennard.personal.installer.Other"), "install is a package, not a prefix of other names");
    }

    @Test
    void theRuleFindsEachWayTheFixtureBreaksItAndNothingInTheOneThatKeepsToIt() {
        JavaClasses bad = new ClassFileImporter().importPackages(FIXTURES).that(DescribedPredicate.describe("ProcessBad", type -> type.getSimpleName().equals("ProcessBad")));
        List<String> found = ProcessRules.violations(bad);
        for (String part : List.of("uses java.lang.ProcessBuilder outside", "uses java.lang.ProcessHandle outside", "calls Runtime.exec outside", "uses java.lang.ProcessBuilder$Redirect outside")) {
            assertTrue(found.stream().anyMatch(line -> line.contains(part)), part + " is found in " + found);
        }
        JavaClasses good = new ClassFileImporter().importPackages(FIXTURES).that(DescribedPredicate.describe("ProcessGood", type -> type.getSimpleName().equals("ProcessGood")));
        assertEquals(1, good.stream().count());
        assertEquals(List.of(), ProcessRules.violations(good));
    }
}
