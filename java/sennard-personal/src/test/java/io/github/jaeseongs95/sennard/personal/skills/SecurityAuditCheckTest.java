// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * software-security-auditor through {@link SkillCheckService}, the MCP tool's own entry, with the real file boundary: the target snapshot, the report's bindings to the
 * request and to the files, and each rule of the report's structure. Whether a finding is real is the auditor's, so no case here judges one.
 */
class SecurityAuditCheckTest {
    private static final String SKILL = "software-security-auditor";
    private static final String SOURCE = "export const value = 'synthetic';\n";
    private static final String ZERO = "sha256:" + "0".repeat(64);
    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;

    @TempDir
    Path root;
    private Path target;
    private Path evidence;

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

    @BeforeEach
    void files() throws IOException {
        target = Files.createDirectories(root.resolve("target"));
        evidence = Files.createDirectories(root.resolve("evidence"));
        Files.writeString(target.resolve("target.txt"), SOURCE);
        Files.writeString(evidence.resolve("target.txt"), SOURCE);
    }

    // ------------------------------------------------------------------------------------------------------------------------- documents the cases are made of

    private static ObjectNode pinned(String path, String content) {
        return TestJson.object("path", path, "digest", TestJson.hashText(content));
    }

    private static ObjectNode request(boolean localReproduction, List<String> checkIds) {
        ObjectNode targetSpec = TestJson.object("identifier", "fixture", "files", TestJson.array(List.of(pinned("target.txt", SOURCE))));
        targetSpec.putNull("baseRef");
        List<ObjectNode> checks = checkIds.stream().map(id -> TestJson.object("id", id, "description", "check " + id)).toList();
        return TestJson.object("schemaVersion", "1.0.0", "target", targetSpec, "mode", "repository", "profiles", TestJson.array(List.of("web-api", "cli-mcp")), "excluded", TestJson.array(List.of()),
                "environment", "isolated synthetic fixture", "authorization", TestJson.object("localReproduction", localReproduction, "constraints", TestJson.array(List.of("static only"))),
                "checks", TestJson.array(checks), "providedEvidence", TestJson.array(List.of()));
    }

    private static ObjectNode request() {
        return request(false, List.of("C1"));
    }

    private static ObjectNode checkResult(String id, String status, List<String> refs, String method) {
        return TestJson.object("id", id, "status", status, "evidenceRefs", TestJson.array(refs), "observation", "inspected fixture", "method", method);
    }

    private static ObjectNode evidenceItem(String id, String path, String content, String targetDigest) {
        return TestJson.object("id", id, "path", path, "digest", TestJson.hashText(content), "targetDigest", targetDigest, "kind", "source", "description", "synthetic source");
    }

    private static ObjectNode finding(String id, String status, String proof, List<String> checkIds, String path) {
        return TestJson.object("id", id, "status", status, "severity", "low", "checkIds", TestJson.array(checkIds), "locations", TestJson.array(List.of(TestJson.object("path", path, "line", 1))),
                "preconditions", "a caller reaches the function", "attackPath", "input reaches the effect", "impact", "synthetic", "defenseReview", "none found", "evidenceRefs", TestJson.array(List.of("E1")),
                "proof", proof, "remediation", "validate the input", "retest", "repeat the check");
    }

    /** A complete report of the request: every check checked on E1, no finding, the evidence E1 the target file, every digest the way the request makes it. */
    private static ObjectNode report(ObjectNode request) {
        String targetDigest = TestJson.hashJson(request.get("target"));
        List<ObjectNode> checks = TestJson.items(request.get("checks")).stream().map(check -> checkResult(TestJson.text(check, "id"), "checked", List.of("E1"), "static-analysis")).toList();
        return TestJson.object("schemaVersion", "1.0.0", "request", request.deepCopy(), "requestDigest", TestJson.hashJson(request), "targetDigest", targetDigest, "status", "complete", "threatModel",
                TestJson.object("assets", TestJson.array(List.of("synthetic data")), "actors", TestJson.array(List.of("user")), "entrypoints", TestJson.array(List.of("function")),
                        "trustBoundaries", TestJson.array(List.of("input to effect"))),
                "checks", TestJson.array(checks), "findings", TestJson.array(List.of()), "evidence", TestJson.array(List.of(evidenceItem("E1", "target.txt", SOURCE, targetDigest))), "limitations",
                TestJson.array(List.of()));
    }

    private static ObjectNode report() {
        return report(request());
    }

    private static ObjectNode at(JsonNode array, int index) {
        return (ObjectNode) array.get(index);
    }

    // ------------------------------------------------------------------------------------------------------------------------- the calls

    private Outcome validate(JsonNode request, JsonNode report) {
        return service.run(SKILL, "validate", TestJson.object("input", request, "report", report, "target-root", target.toString(), "evidence-root", evidence.toString()));
    }

    private Outcome snapshot(JsonNode request) {
        return service.run(SKILL, "snapshot", TestJson.object("input", request, "target-root", target.toString()));
    }

    private static Outcome semantics(JsonNode report) {
        return service.run(SKILL, "provider-semantics", TestJson.object("report", report));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    private static List<String> errors(Outcome outcome) {
        return TestJson.strings(value(outcome).get("errors"));
    }

    private static void assertJudged(Outcome outcome, String... expected) {
        assertEquals(List.of(expected), errors(outcome), outcome.body()::toString);
        assertEquals(expected.length == 0, value(outcome).get("valid").booleanValue());
        assertEquals(expected.length == 0 ? 0 : 1, outcome.exitCode(), outcome.body()::toString);
    }

    private static void assertRefused(Outcome outcome, String messageKey) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("ERROR", outcome.body().get("status").stringValue());
        assertEquals("INVALID_INPUT", outcome.body().get("code").stringValue(), () -> outcome.body().toString());
        assertEquals(messageKey, outcome.body().get("messageKey").stringValue(), () -> outcome.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------------------- the old case, and what it binds

    @Test
    void aReportBoundToTheAuditedTargetIsValidAndASnapshotAnswersItsDigests() {
        ObjectNode request = request();
        assertJudged(validate(request, report(request)));
        JsonNode snapshot = value(snapshot(request));
        assertTrue(snapshot.get("requestDigest").isString());
        assertEquals(TestJson.hashJson(request), snapshot.get("requestDigest").stringValue());
        assertEquals(TestJson.hashJson(request.get("target")), snapshot.get("targetDigest").stringValue());
    }

    @Test
    void aSnapshotPinsTheTargetFilesAndKeepsEveryOtherMemberOfTheRequest() {
        ObjectNode unpinned = request();
        at(unpinned.get("target").get("files"), 0).put("digest", ZERO);
        Outcome outcome = snapshot(unpinned);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        // The request the pinned snapshot answers is the request the digests were pinned in: its member for member, baseRef null included.
        assertEquals(request(), value(outcome).get("request"));
        assertEquals(TestJson.hashJson(request()), value(outcome).get("requestDigest").stringValue());
        assertEquals(TestJson.hashJson(request().get("target")), value(outcome).get("targetDigest").stringValue());
    }

    @Test
    void evidenceMadeForAnotherTargetIsStale() {
        ObjectNode request = request();
        ObjectNode stale = report(request);
        at(stale.get("evidence"), 0).put("targetDigest", ZERO);
        assertJudged(validate(request, stale), "STALE_EVIDENCE");
    }

    @Test
    void aTargetChangedAfterTheSnapshotIsStale() throws IOException {
        ObjectNode request = request();
        ObjectNode report = report(request);
        Files.writeString(target.resolve("target.txt"), "changed\n");
        assertJudged(validate(request, report), "STALE_TARGET");
    }

    @Test
    void providedEvidenceAndReportEvidenceThatDriftAreStale() throws IOException {
        Files.writeString(evidence.resolve("provided.txt"), "provided");
        ObjectNode request = request();
        ((ArrayNode)request.get("providedEvidence")).add(pinned("provided.txt", "provided"));
        ObjectNode report = report(request);
        assertJudged(validate(request, report));
        Files.writeString(evidence.resolve("provided.txt"), "drifted");
        assertJudged(validate(request, report), "STALE_PROVIDED_EVIDENCE");
        Files.writeString(evidence.resolve("target.txt"), "drifted");
        assertJudged(validate(request, report), "STALE_PROVIDED_EVIDENCE", "STALE_EVIDENCE");
    }

    @Test
    void aFileTheBoundaryRefusesIsAFindingNamingItsSubjectAndTheLayersCode() throws IOException {
        ObjectNode request = request();
        ObjectNode report = report(request);
        Files.delete(target.resolve("target.txt"));
        assertJudged(validate(request, report), "TARGET: NOT_FOUND");
        Files.writeString(target.resolve("target.txt"), SOURCE);
        at(report.get("evidence"), 0).put("path", "missing.txt");
        assertJudged(validate(request, report), "EVIDENCE: NOT_FOUND");
    }

    @Test
    void aRequestOtherThanTheOneTheReportBindsToIsNotTheAuditedOne() {
        ObjectNode request = request();
        ObjectNode report = report(request);
        ObjectNode other = request.deepCopy();
        other.put("environment", "another environment");
        assertJudged(validate(other, report), "REQUEST_BINDING_MISMATCH");
    }

    @Test
    void aRequestOrAReportThatBreaksItsSchemaIsNotJudgedAgainstTheFiles() {
        ObjectNode request = request();
        ObjectNode noMode = request.deepCopy();
        noMode.remove("mode");
        assertJudged(validate(noMode, report(request)), "INVALID_REQUEST_SCHEMA");
        ObjectNode noStatus = report(request);
        noStatus.remove("status");
        assertJudged(validate(request, noStatus), "INVALID_REPORT_SCHEMA");
        assertJudged(semantics(noStatus), "INVALID_REPORT_SCHEMA");
        ObjectNode unknownMember = report(request);
        unknownMember.put("unexpected", true);
        assertJudged(validate(request, unknownMember), "INVALID_REPORT_SCHEMA");
    }

    // ------------------------------------------------------------------------------------------------------------------------- the report's structure, rule by rule

    @Test
    void aStructurallyCompleteReportHasNoFinding() {
        assertJudged(semantics(report()));
    }

    @Test
    void theRequestAndTargetDigestsOfAReportMustBeTheOnesItsRequestMakes() {
        ObjectNode wrongRequestDigest = report();
        wrongRequestDigest.put("requestDigest", ZERO);
        assertJudged(semantics(wrongRequestDigest), "REQUEST_DIGEST_MISMATCH");
        ObjectNode wrongTargetDigest = report();
        wrongTargetDigest.put("targetDigest", ZERO);
        assertJudged(semantics(wrongTargetDigest), "TARGET_DIGEST_MISMATCH", "STALE_EVIDENCE");
    }

    @Test
    void aRepeatedIdentifierIsNamedByWhatItRepeats() {
        ObjectNode twoPaths = request();
        ((ArrayNode)twoPaths.get("target").get("files")).add(pinned("target.txt", SOURCE));
        assertJudged(semantics(report(twoPaths)), "DUPLICATE_TARGET_PATH");

        ObjectNode twoProvided = request();
        ((ArrayNode)twoProvided.get("providedEvidence")).add(pinned("a.txt", "a")).add(pinned("a.txt", "b"));
        assertJudged(semantics(report(twoProvided)), "DUPLICATE_PROVIDED_EVIDENCE");

        assertJudged(semantics(report(request(false, List.of("C1", "C1")))), "DUPLICATE_REQUEST_CHECK", "DUPLICATE_CHECK");

        ObjectNode twoChecks = report();
        ((ArrayNode)twoChecks.get("checks")).add(checkResult("C1", "checked", List.of("E1"), "static-analysis"));
        assertJudged(semantics(twoChecks), "DUPLICATE_CHECK");

        ObjectNode twoEvidence = report();
        ((ArrayNode)twoEvidence.get("evidence")).add(at(twoEvidence.get("evidence"), 0).deepCopy());
        assertJudged(semantics(twoEvidence), "DUPLICATE_EVIDENCE");

        ObjectNode twoFindings = report();
        ((ArrayNode)twoFindings.get("findings")).add(finding("F1", "suspected", "static-analysis", List.of("C1"), "target.txt"))
                .add(finding("F1", "suspected", "static-analysis", List.of("C1"), "target.txt"));
        assertJudged(semantics(twoFindings), "DUPLICATE_FINDING");
    }

    @Test
    void theChecksOfAReportAreExactlyTheChecksOfItsRequest() {
        ObjectNode other = report();
        at(other.get("checks"), 0).put("id", "C2");
        assertJudged(semantics(other), "CHECK_INVENTORY_MISMATCH");
        ObjectNode missing = report();
        ((ArrayNode)missing.get("request").get("checks")).add(TestJson.object("id", "C2", "description", "second"));
        // The embedded request changed, so its digest no longer matches the report's: both are findings.
        assertJudged(semantics(missing), "REQUEST_DIGEST_MISMATCH", "CHECK_INVENTORY_MISMATCH");
    }

    @Test
    void pathsAreSafeOnlyWhenRelativeAndPlain() {
        for (String unsafe : List.of("../a", "a/../b", "a/..", "/a", "a\\b", "C:/x", "a:b", "a//b", "a/", "./a", "a/.", ".", "..", "a\tb", "a\nb")) {
            ObjectNode report = report();
            at(report.get("evidence"), 0).put("path", unsafe);
            assertJudged(semantics(report), "UNSAFE_PATH");
        }
        for (String safe : List.of("a", "a/b.txt", "..a", "a..", ".hidden", "...", "a/.b/c", "dir name/file name.txt", "한글/파일.txt")) {
            ObjectNode report = report();
            at(report.get("evidence"), 0).put("path", safe);
            assertJudged(semantics(report));
        }
        ObjectNode escapingTarget = request();
        at(escapingTarget.get("target").get("files"), 0).put("path", "../escape.txt");
        assertJudged(semantics(report(escapingTarget)), "UNSAFE_PATH");
    }

    @Test
    void anUnsafeTargetPathIsRefusedByTheSnapshotBeforeAnyFileIsRead() {
        for (String unsafe : List.of("../escape.txt", "a\\b", "/abs.txt", "a//b", "C:/x")) {
            ObjectNode request = request();
            at(request.get("target").get("files"), 0).put("path", unsafe);
            assertRefused(snapshot(request), "skillScripts.securityAuditor.invalidOption");
        }
    }

    @Test
    void evidenceMustNameKnownEvidenceAndCheckedChecksNeedSome() {
        ObjectNode unknownRef = report();
        at(unknownRef.get("checks"), 0).set("evidenceRefs", TestJson.array(List.of("E9")));
        assertJudged(semantics(unknownRef), "UNKNOWN_EVIDENCE");

        ObjectNode noEvidence = report();
        at(noEvidence.get("checks"), 0).set("evidenceRefs", TestJson.array(List.of()));
        assertJudged(semantics(noEvidence), "CHECK_WITHOUT_EVIDENCE");

        ObjectNode notExecuted = report();
        at(notExecuted.get("checks"), 0).put("method", "not-executed");
        assertJudged(semantics(notExecuted), "CHECK_WITHOUT_EVIDENCE");

        ObjectNode findingRef = report();
        ObjectNode finding = finding("F1", "suspected", "static-analysis", List.of("C1"), "target.txt");
        finding.set("evidenceRefs", TestJson.array(List.of("E9")));
        ((ArrayNode)findingRef.get("findings")).add(finding);
        assertJudged(semantics(findingRef), "UNKNOWN_EVIDENCE");
    }

    @Test
    void aCheckThatWasNotCheckedHasNoMethodButNotExecuted() {
        ObjectNode notApplicable = report();
        at(notApplicable.get("checks"), 0).put("status", "not-applicable");
        assertJudged(semantics(notApplicable), "UNEXECUTED_CHECK_METHOD");
        at(notApplicable.get("checks"), 0).put("method", "not-executed");
        assertJudged(semantics(notApplicable));
    }

    @Test
    void aFindingStandsOnLocationsOfTheTargetAndOnCheckedChecks() {
        ObjectNode report = report();
        ((ArrayNode)report.get("findings")).add(finding("F1", "suspected", "static-analysis", List.of("C1"), "target.txt"));
        assertJudged(semantics(report));

        ObjectNode elsewhere = report();
        ((ArrayNode)elsewhere.get("findings")).add(finding("F1", "suspected", "static-analysis", List.of("C1"), "other.txt"));
        assertJudged(semantics(elsewhere), "UNKNOWN_FINDING_LOCATION");

        ObjectNode unknownCheck = report();
        ((ArrayNode)unknownCheck.get("findings")).add(finding("F1", "suspected", "static-analysis", List.of("C9"), "target.txt"));
        assertJudged(semantics(unknownCheck), "UNVERIFIED_FINDING_CHECK");

        ObjectNode unchecked = report();
        at(unchecked.get("checks"), 0).put("status", "not-checked").put("method", "not-executed").set("evidenceRefs", TestJson.array(List.of()));
        unchecked.put("status", "blocked");
        unchecked.set("limitations", TestJson.array(List.of("not run")));
        ((ArrayNode)unchecked.get("findings")).add(finding("F1", "suspected", "static-analysis", List.of("C1"), "target.txt"));
        assertJudged(semantics(unchecked), "UNVERIFIED_FINDING_CHECK");
    }

    @Test
    void aConfirmedFindingNeedsAProof() {
        ObjectNode unverified = report();
        ((ArrayNode)unverified.get("findings")).add(finding("F1", "confirmed", "unverified", List.of("C1"), "target.txt"));
        assertJudged(semantics(unverified), "UNVERIFIED_CONFIRMED_FINDING");

        ObjectNode proven = report();
        ((ArrayNode)proven.get("findings")).add(finding("F1", "confirmed", "static-analysis", List.of("C1"), "target.txt"));
        assertJudged(semantics(proven));
    }

    @Test
    void aLocalReproductionNeedsTheRequestsAuthorizationAndACheckThatRanIt() {
        // Not authorised: the finding's proof has no support, and the check that claims the method is itself unauthorised.
        ObjectNode unauthorised = report();
        ((ArrayNode)unauthorised.get("findings")).add(finding("F1", "suspected", "local-reproduction", List.of("C1"), "target.txt"));
        assertJudged(semantics(unauthorised), "UNSUPPORTED_REPRODUCTION");

        ObjectNode unauthorisedCheck = report();
        at(unauthorisedCheck.get("checks"), 0).put("method", "local-reproduction");
        assertJudged(semantics(unauthorisedCheck), "UNAUTHORIZED_REPRODUCTION");

        // Authorised, but no check of the finding ran a reproduction.
        ObjectNode authorisedRequest = request(true, List.of("C1"));
        ObjectNode noRun = report(authorisedRequest);
        ((ArrayNode)noRun.get("findings")).add(finding("F1", "suspected", "local-reproduction", List.of("C1"), "target.txt"));
        assertJudged(semantics(noRun), "UNSUPPORTED_REPRODUCTION");

        // Authorised and run: nothing is broken.
        ObjectNode ran = report(authorisedRequest);
        at(ran.get("checks"), 0).put("method", "local-reproduction");
        ((ArrayNode)ran.get("findings")).add(finding("F1", "suspected", "local-reproduction", List.of("C1"), "target.txt"));
        assertJudged(semantics(ran));
    }

    @Test
    void theStatusFollowsTheCoverageAndAnUncoveredReportStatesItsLimits() {
        ObjectNode partialClaim = report();
        partialClaim.put("status", "partial");
        assertJudged(semantics(partialClaim), "STATUS_COVERAGE_MISMATCH");

        // One check done and one not: partial, with limits.
        ObjectNode twoChecks = report(request(false, List.of("C1", "C2")));
        at(twoChecks.get("checks"), 1).put("status", "not-checked").put("method", "not-executed").set("evidenceRefs", TestJson.array(List.of()));
        twoChecks.put("status", "partial");
        assertJudged(semantics(twoChecks), "MISSING_LIMITATIONS");
        twoChecks.set("limitations", TestJson.array(List.of("C2 was not run")));
        assertJudged(semantics(twoChecks));
        twoChecks.put("status", "blocked");
        assertJudged(semantics(twoChecks), "STATUS_COVERAGE_MISMATCH");

        // Nothing checked: blocked.
        ObjectNode none = report();
        at(none.get("checks"), 0).put("status", "not-checked").put("method", "not-executed").set("evidenceRefs", TestJson.array(List.of()));
        none.put("status", "blocked");
        none.set("limitations", TestJson.array(List.of("nothing was run")));
        assertJudged(semantics(none));
        none.put("status", "complete");
        assertJudged(semantics(none), "STATUS_COVERAGE_MISMATCH");
    }

    // ------------------------------------------------------------------------------------------------------------------------- refused inputs

    @Test
    void validateNeedsTheReportAndTheEvidenceRoot() {
        Outcome noReport = service.run(SKILL, "validate", TestJson.object("input", request(), "target-root", target.toString(), "evidence-root", evidence.toString()));
        assertRefused(noReport, "skillScripts.securityAuditor.missingReportOrRoot");
        Outcome noRoot = service.run(SKILL, "validate", TestJson.object("input", request(), "report", report(), "target-root", target.toString()));
        assertRefused(noRoot, "skillScripts.securityAuditor.missingReportOrRoot");
        assertRefused(service.run(SKILL, "snapshot", TestJson.object("input", request())), "skillCheck.argumentRequired");
        assertRefused(service.run(SKILL, "no-such-command", TestJson.object()), "skillCheck.unknownCommand");
    }

    @Test
    void aSnapshotRefusesARequestThatBreaksTheSchemaWithItsViolations() {
        ObjectNode noChecks = request();
        noChecks.remove("checks");
        Outcome outcome = snapshot(noChecks);
        assertRefused(outcome, "skillScripts.securityAuditor.invalidOption");
        assertFalse(outcome.body().get("details").get("errors").isEmpty(), () -> outcome.body().toString());
    }

    @Test
    void aSnapshotRefusesRepeatedPathsAndFilesTheBoundaryRefuses() {
        ObjectNode twice = request();
        ((ArrayNode)twice.get("target").get("files")).add(pinned("target.txt", SOURCE));
        assertRefused(snapshot(twice), "skillScripts.securityAuditor.invalidOption");

        ObjectNode missing = request();
        at(missing.get("target").get("files"), 0).put("path", "gone.txt");
        Outcome outcome = snapshot(missing);
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("NOT_FOUND", outcome.body().get("code").stringValue());
    }
}
