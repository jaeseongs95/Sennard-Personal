// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;

import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * solid-design's report-to-request binding through {@link SkillCheckService}: a report is bound to its request's exact bytes, its scope, its principles and its references.
 * The fixtures are the skill's own example (the request and the report the example holds), kept as test resources. The wording is English, the language of an answer no
 * request's locale asked otherwise for.
 */
class SolidDesignCheckTest {
    private static final String SCOPE_ONLY = "schema-and-binding-only";
    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;

    @TempDir
    Path root;

    @BeforeAll
    static void wire() {
        context = SkillCheckContext.open();
        service = context.getBean(SkillCheckService.class);
    }

    @AfterAll
    static void close() {
        context.close();
        // The class's statics outlive it in the test JVM: let go of the context's beans (the schema registry among them).
        context = null;
        service = null;
    }

    private static ObjectNode fixture(String name) throws IOException {
        return (ObjectNode) TestJson.MAPPER.readTree(new ClassPathResource("skills/solid-design/" + name).getContentAsString(StandardCharsets.UTF_8));
    }

    /** The example's request written as the file whose exact bytes the report binds to. */
    private Path requestFile() throws IOException {
        Path file = root.resolve("request.json");
        Files.writeString(file, fixture("request.json").toPrettyString());
        return file;
    }

    /** The example's report, bound to the bytes of the request file. */
    private static ObjectNode boundReport(Path requestFile) throws IOException {
        ObjectNode report = fixture("report.json");
        report.put("requestDigest", TestJson.hashBytes(Files.readAllBytes(requestFile)));
        return report;
    }

    private static Outcome check(Path requestFile, JsonNode report) {
        return service.run("solid-design", "check-report", TestJson.object("request", TestJson.object("file", requestFile.toString()), "report", report));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    private static List<String> errors(Outcome outcome) {
        return TestJson.strings(value(outcome).get("errors"));
    }

    /** The report is judged and breaks exactly these rules, in this order. */
    private static void assertBreaks(Outcome outcome, String... expected) {
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertFalse(value(outcome).get("valid").booleanValue());
        assertEquals(List.of(expected), errors(outcome));
        assertEquals(SCOPE_ONLY, value(outcome).get("validationScope").stringValue());
    }

    private static ObjectNode finding(JsonNode report, int index) {
        return (ObjectNode) report.get("findings").get(index);
    }

    // ------------------------------------------------------------------------------------------------------------------------- the old cases

    @Test
    void aReportBoundToItsRequestsExactBytesPasses() throws IOException {
        Path requestFile = requestFile();
        Outcome bound = check(requestFile, boundReport(requestFile));
        assertEquals(0, bound.exitCode(), () -> bound.body().toString());
        assertTrue(value(bound).get("valid").booleanValue());
        assertEquals(List.of(), errors(bound));
        assertEquals(SCOPE_ONLY, value(bound).get("validationScope").stringValue());
    }

    @Test
    void aSnapshotRefThatIsNotTheRequestsFails() throws IOException {
        Path requestFile = requestFile();
        ObjectNode report = boundReport(requestFile);
        report.put("snapshotRef", "other");
        assertBreaks(check(requestFile, report), "snapshotRef mismatch");
    }

    @Test
    void anInlineRequestIsBoundByItsCanonicalForm() throws IOException {
        ObjectNode request = fixture("request.json");
        ObjectNode report = fixture("report.json");
        report.put("requestDigest", TestJson.hashJson(request));
        Outcome outcome = service.run("solid-design", "check-report", TestJson.object("request", request, "report", report));
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------------------- the binding to the request

    @Test
    void aRequestIdOrADigestThatIsNotTheRequestsFails() throws IOException {
        Path requestFile = requestFile();
        ObjectNode otherId = boundReport(requestFile);
        otherId.put("requestId", "other");
        assertBreaks(check(requestFile, otherId), "requestId mismatch");
        ObjectNode otherDigest = boundReport(requestFile);
        otherDigest.put("requestDigest", "sha256:" + "0".repeat(64));
        assertBreaks(check(requestFile, otherDigest), "requestDigest mismatch");
        // The digest is over the file's bytes: the same document written otherwise is another request.
        Files.writeString(requestFile, fixture("request.json").toPrettyString() + "\n");
        assertBreaks(check(requestFile, boundReport(Files.writeString(root.resolve("other-bytes.json"), fixture("request.json").toPrettyString()))), "requestDigest mismatch");
    }

    @Test
    void aRequestThatIsNotJsonIsAFindingAboutItNotARefusedCall() throws IOException {
        Path requestFile = root.resolve("request.json");
        Files.writeString(requestFile, "not json");
        Outcome outcome = check(requestFile, fixture("report.json"));
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals(List.of("request is not valid JSON"), errors(outcome));
        assertFalse(value(outcome).get("valid").booleanValue());
        assertFalse(value(outcome).has("validationScope"), () -> outcome.body().toString());
    }

    @Test
    void aDocumentThatBreaksItsSchemaIsNotJudgedAgainstTheOther() throws IOException {
        Path requestFile = requestFile();
        ObjectNode noSummary = boundReport(requestFile);
        noSummary.remove("summary");
        Outcome reportBroken = check(requestFile, noSummary);
        assertEquals(1, reportBroken.exitCode(), () -> reportBroken.body().toString());
        assertEquals(1, errors(reportBroken).size());
        assertTrue(errors(reportBroken).get(0).startsWith("report: "), () -> reportBroken.body().toString());
        assertFalse(value(reportBroken).has("validationScope"));

        ObjectNode noTargets = fixture("request.json");
        noTargets.remove("targets");
        Path brokenRequest = root.resolve("broken-request.json");
        Files.writeString(brokenRequest, noTargets.toPrettyString());
        Outcome requestBroken = check(brokenRequest, fixture("report.json"));
        assertEquals(1, requestBroken.exitCode(), () -> requestBroken.body().toString());
        assertTrue(errors(requestBroken).get(0).startsWith("request: "), () -> requestBroken.body().toString());
        assertFalse(value(requestBroken).has("validationScope"));

        ObjectNode unknownMember = boundReport(requestFile);
        unknownMember.put("unexpected", true);
        assertTrue(errors(check(requestFile, unknownMember)).get(0).startsWith("report: "));
    }

    // ------------------------------------------------------------------------------------------------------------------------- the scope

    private static void scope(ObjectNode report, List<String> reviewed, List<String> notReviewed) {
        report.set("scope", TestJson.object("reviewed", TestJson.array(reviewed), "notReviewed", TestJson.array(notReviewed)));
    }

    @Test
    void theScopeCoversEveryTargetOnceAndOnlyTheTargets() throws IOException {
        Path requestFile = requestFile();

        ObjectNode duplicate = boundReport(requestFile);
        scope(duplicate, List.of("core.ts", "host-adapter.ts"), List.of("core.ts"));
        assertBreaks(check(requestFile, duplicate), "scope contains duplicate or overlapping targets");

        ObjectNode undeclared = boundReport(requestFile);
        scope(undeclared, List.of("core.ts", "host-adapter.ts", "extra.ts"), List.of());
        assertBreaks(check(requestFile, undeclared), "scope contains an undeclared target");

        ObjectNode omits = boundReport(requestFile);
        scope(omits, List.of("host-adapter.ts"), List.of());
        assertBreaks(check(requestFile, omits), "scope omits a requested target");

        // A target set aside as not reviewed is still covered.
        ObjectNode setAside = boundReport(requestFile);
        scope(setAside, List.of("host-adapter.ts"), List.of("core.ts"));
        Outcome outcome = check(requestFile, setAside);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------------------- the findings

    @Test
    void aFindingStandsOnReviewedTargetsConsideredPrinciplesAndBoundReferences() throws IOException {
        Path requestFile = requestFile();

        ObjectNode notReviewed = boundReport(requestFile);
        scope(notReviewed, List.of("host-adapter.ts"), List.of("core.ts"));
        finding(notReviewed, 0).put("target", "core.ts");
        assertBreaks(check(requestFile, notReviewed), "S-1: finding target was not reviewed");

        ObjectNode principle = boundReport(requestFile);
        principle.set("principlesConsidered", TestJson.array(List.of("DIP")));
        assertBreaks(check(requestFile, principle), "S-1: principle was not considered");

        ObjectNode contract = boundReport(requestFile);
        finding(contract, 0).set("contractRefs", TestJson.array(List.of("fixture:other-port")));
        assertBreaks(check(requestFile, contract), "S-1: contract reference is not bound to the request");

        ObjectNode prior = boundReport(requestFile);
        finding(prior, 0).set("relatedFindingRefs", TestJson.array(List.of("prior-1")));
        assertBreaks(check(requestFile, prior), "S-1: prior finding reference is not bound to the request");
    }

    @Test
    void everyBrokenBindingOfOneFindingIsNamed() throws IOException {
        Path requestFile = requestFile();
        ObjectNode report = boundReport(requestFile);
        scope(report, List.of("host-adapter.ts"), List.of("core.ts"));
        finding(report, 0).put("target", "core.ts");
        report.set("principlesConsidered", TestJson.array(List.of("DIP")));
        finding(report, 0).set("contractRefs", TestJson.array(List.of("fixture:other-port")));
        finding(report, 0).set("relatedFindingRefs", TestJson.array(List.of("prior-1")));
        assertBreaks(check(requestFile, report), "S-1: finding target was not reviewed", "S-1: principle was not considered", "S-1: contract reference is not bound to the request",
                "S-1: prior finding reference is not bound to the request");
    }

    @Test
    void aRepeatedFindingIdIsNamed() throws IOException {
        Path requestFile = requestFile();
        ObjectNode report = boundReport(requestFile);
        ((ArrayNode) report.get("findings")).add(finding(report, 0).deepCopy());
        assertBreaks(check(requestFile, report), "duplicate finding id");
    }

    @Test
    void aPriorFindingTheRequestNamesMayBeReferenced() throws IOException {
        ObjectNode request = fixture("request.json");
        request.set("priorFindingRefs", TestJson.array(List.of("prior-1")));
        Path requestFile = root.resolve("request-with-prior.json");
        Files.writeString(requestFile, request.toPrettyString());
        ObjectNode report = boundReport(requestFile);
        finding(report, 0).set("relatedFindingRefs", TestJson.array(List.of("prior-1")));
        Outcome outcome = check(requestFile, report);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
    }
}
