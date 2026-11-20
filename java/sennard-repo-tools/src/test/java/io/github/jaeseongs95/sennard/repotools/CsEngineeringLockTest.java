// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * The digests of the cs-engineering knowledge pack and of its frozen example follow the files they name, and what the pack calls validated is backed by tests that exist:
 * a card of a validated module names the Gradle test classes and methods that run its reference cases, and the evidence of the example is the record of a run of the
 * database cases against this candidate and this test file.
 */
class CsEngineeringLockTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final String SKILL = "skills/cs-engineering/";
    private static final String EXAMPLE = SKILL + "assets/examples/claim-queue/";
    private static final Pattern TEST = Pattern.compile("([A-Z][A-Za-z0-9]+)#([A-Za-z0-9]+)");
    private static final String QUEUE_TEST = "java/sennard-store-pg/src/test/java/io/github/jaeseongs95/sennard/service/store/pg/CsQueueReferenceTest.java";

    @Test
    void everyDigestOfThePackAndTheExampleIsTheDigestOfTheFileItNames() {
        assertEquals(List.of(), new CsEngineeringLock(REPO).sync(false), "run the updateCsEngineeringLock task");
    }

    /** The source file of a test class of the Java modules, by simple class name. */
    private static String sourceOf(String className) {
        List<String> found = REPO.trackedFiles().stream().filter(file -> file.startsWith("java/") && file.contains("/src/test/java/") && file.endsWith("/" + className + ".java")).toList();
        assertEquals(1, found.size(), className + " is one test class of the Java modules: " + found);
        return found.get(0);
    }

    @Test
    void everyValidatedCardNamesTestsThatExistAndCarryItsId() {
        List<String> problems = new ArrayList<>();
        int validated = 0;
        for (JsonNode module : REPO.json(SKILL + "assets/rule-catalog.json").path("modules")) {
            if (!"validated".equals(module.path("status").stringValue())) {
                continue;
            }
            for (JsonNode card : REPO.json(SKILL + module.path("ruleFile").stringValue())) {
                validated++;
                String id = card.path("id").stringValue();
                Matcher tests = TEST.matcher(card.path("validationScope").stringValue(""));
                boolean any = false;
                while (tests.find()) {
                    any = true;
                    String source = REPO.text(sourceOf(tests.group(1)));
                    if (!Pattern.compile("@Test\\s+@DisplayName\\(\"" + Pattern.quote(id) + ":[^\\n]*\\n\\s+void " + tests.group(2) + "\\(").matcher(source).find()) {
                        problems.add(id + ": " + tests.group() + " is not a @Test whose display name starts with the card's ID");
                    }
                }
                if (!any) {
                    problems.add(id + ": validationScope names no Class#method");
                }
            }
        }
        assertEquals(20, validated, "the core pool");
        assertEquals(List.of(), problems);
    }

    @Test
    void theEvidenceOfTheExampleRecordsARunOfThisCandidateAndThisTestFile() {
        JsonNode evidence = REPO.json(EXAMPLE + "evidence/queue-tests.json");
        assertEquals("PASS", evidence.path("status").stringValue());
        assertEquals(0, evidence.path("failures").intValue());
        assertEquals(0, evidence.path("errors").intValue());
        assertEquals(0, evidence.path("skipped").intValue());
        assertTrue(evidence.path("candidateUnchangedDuringRun").booleanValue());
        assertEquals(DirectoryChecksum.sha256Of(REPO.bytes(EXAMPLE + "candidate/ClaimQueue.java")), evidence.path("candidateRawDigest").stringValue(), "the candidate changed since the run");
        assertEquals(DirectoryChecksum.sha256Of(REPO.bytes(QUEUE_TEST)), evidence.path("testScriptDigest").stringValue(), "the test file changed since the run");
        assertTrue(evidence.path("database").stringValue("").startsWith("PostgreSQL "), "the database product and version that ran");
        assertFalse(evidence.path("jdk").stringValue("").isEmpty());
        String source = REPO.text(QUEUE_TEST);
        assertEquals(evidence.path("testsRun").intValue(), evidence.path("caseIds").size());
        for (JsonNode caseId : evidence.path("caseIds")) {
            String[] parts = caseId.stringValue().split(" ");
            assertTrue(source.contains("\"" + parts[0] + ":") && source.contains("void " + parts[1] + "("), caseId.stringValue() + " is a case of the test class");
        }
        assertEquals(source.split("@Test\\s", -1).length - 1, evidence.path("caseIds").size(), "every case of the test class ran");
    }
}
