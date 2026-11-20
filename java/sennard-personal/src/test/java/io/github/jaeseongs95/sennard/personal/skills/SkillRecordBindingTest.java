// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a document the check binds to a record does when it is not the shape the record holds: an unknown member, a missing member and a value outside an enum are
 * refused (the contract's schema is the gate) or judged as a failed check, and the caller always gets an answer, never an exception from the server.
 */
class SkillRecordBindingTest {
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;

    @BeforeAll
    static void wire() {
        context = SkillCheckContext.open();
        service = context.getBean(SkillCheckService.class);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private static ObjectNode fixture(String relative) throws IOException {
        return (ObjectNode) TestJson.MAPPER.readTree(Files.readString(REPO.resolve("tests").resolve(relative), StandardCharsets.UTF_8));
    }

    private static Outcome run(String skill, String command, Object... pairs) {
        return service.run(skill, command, TestJson.object(pairs));
    }

    /** A refused input: exit 2, the error body with the wire code and the catalog key. */
    private static void refused(Outcome outcome, String messageKeyPrefix) {
        assertEquals(2, outcome.exitCode(), outcome.body()::toString);
        assertEquals("ERROR", outcome.body().get("status").stringValue());
        assertEquals("INVALID_INPUT", outcome.body().get("code").stringValue());
        assertTrue(outcome.body().get("messageKey").stringValue().startsWith(messageKeyPrefix), outcome.body()::toString);
    }

    /** task-contract answers a refused document as {@code ok:false} with exit 1. */
    private static void contractRejected(Outcome outcome) {
        assertEquals(1, outcome.exitCode(), outcome.body()::toString);
        assertFalse(outcome.body().get("value").get("ok").booleanValue(), outcome.body()::toString);
        assertEquals("INVALID_INPUT", outcome.body().get("value").get("error").get("code").stringValue());
    }

    @Test
    void anUnknownMemberOfAContractDocumentIsRejected() throws IOException {
        ObjectNode input = fixture("task-contract/fixtures/normal/simple-read.json");
        ((ObjectNode) input.get("report").get("taskEnvelope")).put("unexpectedMember", true);
        contractRejected(run("task-contract", "validate", "input", input));
    }

    @Test
    void aMissingMemberOfAContractDocumentIsRejected() throws IOException {
        ObjectNode input = fixture("task-contract/fixtures/normal/simple-read.json");
        ((ObjectNode) input.get("report").get("taskEnvelope")).remove("objective");
        contractRejected(run("task-contract", "validate", "input", input));
    }

    @Test
    void aValueOutsideAnEnumIsRejected() throws IOException {
        ObjectNode input = fixture("task-contract/fixtures/normal/simple-read.json");
        ((ObjectNode) input.get("report").get("taskEnvelope")).put("riskLevel", "extreme");
        contractRejected(run("task-contract", "validate", "input", input));
    }

    @Test
    void theSamePassingDocumentStillPasses() throws IOException {
        Outcome outcome = run("task-contract", "validate", "input", fixture("task-contract/fixtures/normal/simple-read.json"));
        assertEquals(0, outcome.exitCode(), outcome.body()::toString);
        assertEquals("PASS", outcome.body().get("value").get("output").get("verdict").stringValue());
    }

    @Test
    void anUnknownMemberOrAWrongEnumInAnAnalysisRequestIsARefusedInput() throws IOException {
        ObjectNode unknown = fixture("blocker-diagnostician/fixtures/same-failure.json");
        unknown.put("unexpectedMember", 1);
        Outcome unknownOutcome = run("blocker-diagnostician", "analyze", "input", unknown);
        assertNotEquals(0, unknownOutcome.exitCode(), unknownOutcome.body()::toString);
        assertEquals("ERROR", unknownOutcome.body().get("status").stringValue(), unknownOutcome.body()::toString);

        ObjectNode missing = fixture("blocker-diagnostician/fixtures/same-failure.json");
        missing.remove(missing.propertyNames().iterator().next());
        Outcome missingOutcome = run("blocker-diagnostician", "analyze", "input", missing);
        assertEquals(2, missingOutcome.exitCode(), missingOutcome.body()::toString);
    }

    @Test
    void anArgumentsObjectThatIsNotAnObjectIsRefused() {
        JsonNode array = TestJson.MAPPER.createArrayNode().add(1);
        refused(service.run("task-contract", "validate", array), "skillCheck.malformedArgument");
    }

    /** A report that holds a refusal's sentence, not the bare catalog key of it (a caller cannot read "skillCheck.argumentRequired"). */
    @Test
    void aCheckThatAnswersARefusedInputInItsReportSaysItInWordsNotInTheCatalogKey() {
        Outcome outcome = service.run("task-contract", "validate", TestJson.object());
        assertEquals(1, outcome.exitCode(), outcome.body()::toString);
        JsonNode error = outcome.body().get("value").get("error");
        assertEquals("INVALID_INPUT", error.get("code").stringValue());
        assertEquals("Argument input is required.", error.get("message").stringValue(), error::toString);
    }

    @Test
    void aDocumentArgumentThatIsNotJsonIsRefused(@org.junit.jupiter.api.io.TempDir Path root) throws IOException {
        Files.write(root.resolve("latin1.json"), new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xE9, '"', '}'});
        Outcome outcome = run("task-contract", "validate", "input", TestJson.object("file", root.resolve("latin1.json").toString()));
        assertEquals(1, outcome.exitCode(), outcome.body()::toString);
        assertFalse(outcome.body().get("value").get("ok").booleanValue());
    }
}
