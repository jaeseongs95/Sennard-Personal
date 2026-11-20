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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Rule r (PromptArchiveRules): the user's words are written along the hook path only. The product passes with the rule's zero; the fixtures prove the rule goes red.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * r1  the product        the compiled classes of every module -> no violation; the rule recognises the product (the tool classes it checks include the board's, the messaging's and the prompt archive's tools, the
 *                        writer and the hook class that writes exist, and the writer uses the insert repository and the hook class uses the writer: a list gone stale would fail here)
 * r2  a tool that writes ToolBad (goes through the entrance) uses the writer, the insert repository and the hook class that writes -> three "MCP tool class" findings
 * r3  the annotation     ToolBadAnnotated (a method with the tool annotation, no entrance) uses the writer -> an "MCP tool class" finding
 * r4  outside the path   StrayBad (neither a tool nor the hook class) uses the writer and the insert repository -> one finding each; ToolBad's uses of both are found too
 * r5  what is allowed    ToolGood (entrance and reader only), FixtureCollector (the hook class using the writer), FixtureWriter (using the insert repository), FixtureReader, FixtureDispatcher
 *                        (the one class that calls the hook domains), FixtureHop (only reaches the dispatcher) -> no finding of their own
 * r6  by a service       ToolViaCollector names none of the write path but calls FixtureRecapService, which uses the hook class that writes -> a finding on the service that names the tool class
 *                        that reaches it (a rule that sees only a tool's own uses passes it)
 * r7  by the domains     ToolViaDomains calls FixtureDomainsService, which holds the hook domains and calls their onEvent -> a finding on the service that names the tool class
 * r8  by a hop           ToolViaDispatcher reaches the dispatcher through FixtureHop, the only thing wrong with it -> a finding on the dispatcher that names the tool class, none on the hop
 * r9  outside the hook   a class that is not a dispatcher and uses the hook class that writes (FixtureRecapService), and one that calls the domains' onEvent (FixtureDomainsService) -> one finding
 *     processing         each, whether or not a tool reaches it
 * </pre>
 */
class PromptArchiveRulesTest {
    private static final String FIXTURES = "io.github.jaeseongs95.sennard.archchecks.promptfixtures";
    private static final String PREFIX = FIXTURES + ".";
    private static final PromptArchiveRules.Names FIXTURE_NAMES = new PromptArchiveRules.Names(PREFIX + "FixtureWriter", PREFIX + "FixtureInserts", Set.of(PREFIX + "FixtureCollector"),
            PREFIX + "FixtureDomain", "onEvent", Set.of(PREFIX + "FixtureDispatcher"), PREFIX, PREFIX + "FixtureEntrance", PREFIX + "FixtureTool");

    private static JavaClasses product() {
        List<Path> directories = Stream.of(System.getProperty("sennard.arch.classes").split(",")).map(Path::of).filter(Files::isDirectory).toList();
        assertTrue(directories.size() >= 8, "the compiled classes of the product's modules are read: " + directories);
        return new ClassFileImporter().importPaths(directories);
    }

    private static List<String> about(List<String> violations, String className) {
        return violations.stream().filter(line -> line.startsWith(PREFIX + className + ":") || line.startsWith(PREFIX + className + "$")).toList();
    }

    private static String lines(List<String> found) {
        return found.stream().collect(Collectors.joining("\n    ", "\n    ", ""));
    }

    @Test
    void theProductWritesTheUsersWordsOnlyAlongTheHookPath() {
        JavaClasses classes = product();
        PromptArchiveRules.Names names = PromptArchiveRules.Names.PRODUCT;
        assertTrue(classes.contain(names.writer()) && classes.contain(names.insert()), "the writer and the insert repository are among the classes read");
        List<String> tools = PromptArchiveRules.toolClasses(classes, names);
        assertTrue(tools.contains("io.github.jaeseongs95.sennard.server.board.SessionBoardTools") && tools.contains("io.github.jaeseongs95.sennard.server.messaging.SessionMessageTools"),
                "the rule recognises the tool definitions of the product: " + tools);
        assertTrue(tools.contains("io.github.jaeseongs95.sennard.server.prompt.PromptTools"), "and the tools of the prompt archive themselves, which the rule is about: " + tools);
        for (String writer : names.writers()) {
            assertTrue(classes.contain(writer), writer + " exists");
            assertTrue(classes.get(writer).getDirectDependenciesFromSelf().stream().anyMatch(dependency -> dependency.getTargetClass().getName().equals(names.writer())),
                    writer + " does not use the writer: remove it from the list");
        }
        assertTrue(classes.get(names.writer()).getDirectDependenciesFromSelf().stream().anyMatch(dependency -> dependency.getTargetClass().getName().equals(names.insert())),
                "the writer does not use the insert repository");
        Set<String> reached = PromptArchiveRules.reachedBy(classes, "io.github.jaeseongs95.sennard.server.prompt.PromptTools");
        assertTrue(reached.contains("io.github.jaeseongs95.sennard.app.prompt.TurnRecapService") && reached.contains("io.github.jaeseongs95.sennard.app.prompt.TurnRecapRepository"),
                "the rule follows the prompt tools through their services to the repository of the recap, two classes away: " + reached.size() + " classes reached");
        assertFalse(reached.contains(names.writer()) || reached.contains(names.insert()), "and the prompt tools do not reach the writer");
        assertTrue(classes.contain(names.hookDomain()), "the hook domain interface exists");
        for (String writer : names.writers()) {
            assertTrue(classes.get(writer).isAssignableTo(names.hookDomain()), writer + " is a hook domain: the dispatcher hands it every hook event");
        }
        for (String dispatcher : names.dispatchers()) {
            assertTrue(classes.contain(dispatcher), dispatcher + " exists");
            assertFalse(PromptArchiveRules.domainCalls(classes.get(dispatcher), names).isEmpty(), dispatcher + " does not call the hook domains: remove it from the list");
        }
        List<String> found = PromptArchiveRules.violations(classes, names);
        assertEquals(List.of(), found, () -> lines(found));
    }

    @Test
    void aToolThatReachesTheWriterIsFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> bad = about(found, "ToolBad");
        for (String part : List.of("depends on " + PREFIX + "FixtureWriter, which writes the user's words", "depends on " + PREFIX + "FixtureInserts, which writes the user's words",
                "depends on " + PREFIX + "FixtureCollector, which writes the user's words")) {
            assertTrue(bad.stream().anyMatch(line -> line.contains("an MCP tool class") && line.contains(part)), part + " is found in " + lines(bad));
        }
        List<String> annotated = about(found, "ToolBadAnnotated");
        assertTrue(annotated.stream().anyMatch(line -> line.contains("an MCP tool class depends on " + PREFIX + "FixtureWriter")), "the annotation defines a tool too: " + lines(annotated));
    }

    @Test
    void aClassOutsideTheHookPathThatWritesOrInsertsIsFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> stray = about(found, "StrayBad");
        assertTrue(stray.stream().anyMatch(line -> line.contains("uses " + PREFIX + "FixtureWriter outside the hook classes")), lines(stray));
        assertTrue(stray.stream().anyMatch(line -> line.contains("uses " + PREFIX + "FixtureInserts but only " + PREFIX + "FixtureWriter may insert a prompt")), lines(stray));
        List<String> bad = about(found, "ToolBad");
        assertTrue(bad.stream().anyMatch(line -> line.contains("uses " + PREFIX + "FixtureWriter outside the hook classes")), lines(bad));
        assertTrue(bad.stream().anyMatch(line -> line.contains("uses " + PREFIX + "FixtureInserts but only")), lines(bad));
    }

    @Test
    void aToolThatReachesTheHookClassThroughAServiceIsFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> service = about(found, "FixtureRecapService");
        assertTrue(service.stream().anyMatch(line -> line.contains("depends on " + PREFIX + "FixtureCollector, which writes the user's words")
                && line.contains("the MCP tool class " + PREFIX + "ToolViaCollector reaches " + PREFIX + "FixtureRecapService")), lines(service));
        assertEquals(List.of(), about(found, "ToolViaCollector"), "the tool itself names nothing of the write path: the way to it is the service's");
    }

    @Test
    void aToolThatReachesTheHookDomainsThroughAServiceIsFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> service = about(found, "FixtureDomainsService");
        assertTrue(service.stream().anyMatch(line -> line.contains("hands events to the hook domains") && line.contains("the MCP tool class " + PREFIX + "ToolViaDomains reaches " + PREFIX + "FixtureDomainsService")),
                lines(service));
    }

    @Test
    void aToolThatReachesTheDispatcherThroughAHopIsFoundOnTheDispatcher() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> dispatcher = about(found, "FixtureDispatcher");
        assertTrue(dispatcher.stream().anyMatch(line -> line.contains("the MCP tool class " + PREFIX + "ToolViaDispatcher reaches " + PREFIX + "FixtureDispatcher")), lines(dispatcher));
        assertEquals(List.of(), about(found, "FixtureHop"), "the hop is not at fault: it only reaches the dispatcher");
        assertEquals(List.of(), about(found, "ToolViaDispatcher"));
    }

    @Test
    void aClassOutsideTheDispatchersThatUsesTheHookClassOrCallsTheDomainsIsFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        List<String> service = about(found, "FixtureRecapService");
        assertTrue(service.stream().anyMatch(line -> line.contains("uses " + PREFIX + "FixtureCollector outside the dispatchers")), lines(service));
        List<String> domains = about(found, "FixtureDomainsService");
        assertTrue(domains.stream().anyMatch(line -> line.contains("calls a hook domain's onEvent but only the dispatchers")), lines(domains));
    }

    @Test
    void whatKeepsToTheRuleIsNotFound() {
        List<String> found = PromptArchiveRules.violations(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES);
        for (String good : List.of("ToolGood", "FixtureReader", "FixtureEntrance", "FixtureDomain")) {
            assertEquals(List.of(), about(found, good), good + " keeps to the rule and no tool reaches the way to the writer through it");
        }
        // The hook classes, the writer, the insert repository and the dispatcher do what they may; a tool that reaches them is the finding of the tool, not of theirs.
        for (String good : List.of("FixtureCollector", "FixtureWriter", "FixtureInserts", "FixtureDispatcher")) {
            assertEquals(List.of(), about(found, good).stream().filter(line -> !line.contains("the MCP tool class")).toList(), good + " keeps to the rule");
        }
        assertFalse(PromptArchiveRules.definesTools(new ClassFileImporter().importPackages(FIXTURES).get(PREFIX + "FixtureCollector"), FIXTURE_NAMES), "the hook class is not a tool definition");
        assertEquals(List.of(PREFIX + "ToolBad", PREFIX + "ToolBadAnnotated", PREFIX + "ToolGood", PREFIX + "ToolViaCollector", PREFIX + "ToolViaDispatcher", PREFIX + "ToolViaDomains"),
                PromptArchiveRules.toolClasses(new ClassFileImporter().importPackages(FIXTURES), FIXTURE_NAMES));
    }
}
