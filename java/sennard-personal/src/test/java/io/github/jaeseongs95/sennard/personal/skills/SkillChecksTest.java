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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;

import io.github.jaeseongs95.sennard.personal.LocalFileFacts;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import io.github.jaeseongs95.sennard.server.skills.SkillSchemas;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The skills' judgments through {@link SkillCheckService}, the MCP tool's own entry, with the real file boundary. The cases are the
 * skills' fixtures and rules, kept in {@code tests/<skill>/fixtures} and read as plain JSON: one normal path and the failures each rule exists for.
 */
class SkillChecksTest {
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;
    private static SkillSchemas schemas;

    @BeforeAll
    static void wire() {
        context = SkillCheckContext.open();
        service = context.getBean(SkillCheckService.class);
        schemas = context.getBean(SkillSchemas.class);
    }

    @AfterAll
    static void close() {
        context.close();
        // The class's statics outlive it in the test JVM: let go of the context's beans (the schema registry among them).
        context = null;
        service = null;
        schemas = null;
    }

    private static JsonNode fixture(String relative) throws IOException {
        return TestJson.MAPPER.readTree(Files.readString(REPO.resolve("tests").resolve(relative), StandardCharsets.UTF_8));
    }

    private static ObjectNode copy(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    private static Outcome run(String skill, String command, Object... pairs) {
        return service.run(skill, command, TestJson.object(pairs));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    private static void refused(Outcome outcome, String code) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("ERROR", outcome.body().path("status").stringValue(), () -> outcome.body().toString());
        assertEquals(code, outcome.body().path("code").stringValue(), () -> outcome.body().toString());
    }

    @Test
    void anUnknownSkillOrCommandIsRefused() {
        refused(run("no-such-skill", "x"), "INVALID_INPUT");
        refused(run("task-contract", "no-such-command"), "INVALID_INPUT");
        refused(run("iteration-frame-auditor", "digest-request"), "INVALID_INPUT");
        rejectedContract(run("task-contract", "validate"));
    }

    /** task-contract and the CLIs it replaced answer a refused input with {@code ok:false} and exit 1, not the tool's error body. */
    private static void rejectedContract(Outcome outcome) {
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertFalse(value(outcome).get("ok").booleanValue(), () -> outcome.body().toString());
    }

    private static JsonNode contractOutput(Outcome outcome) {
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        return value(outcome).get("output");
    }

    @Test
    void everySkillScriptGroupHasABean() {
        List<String> expected = List.of("acceptance-evidence-validator", "blocker-diagnostician", "code-review", "coordinate-subagents", "cs-engineering", "evaluation-validity-auditor", "instruction-scope-resolver",
                "iteration-frame-auditor", "korean-prose-editor", "mutation-risk-preflight", "orchestrator", "prompt-engineering", "recovery-strategy-selector", "software-security-auditor", "solid-design", "task-contract", "test-engineering");
        assertEquals(expected, service.checks().keySet().stream().sorted().toList());
    }

    @Test
    void everyPackagedSchemaCompiles() {
        // These two carry a relative $id and belong to the classification evaluation, which no check here loads; the validator cannot register them.
        Set<String> unregistered = Set.of("contracts/skill-classification-evaluation-configuration.v1.schema.json", "contracts/skill-classification-provider-profiles.v1.schema.json");
        int compiled = 0;
        for (String path : schemas.assets().matching("**/*.schema.json").keySet()) {
            if (unregistered.contains(path) || !schemas.exists(path)) {
                continue; // A schema without an $id has no address to resolve it by, and no check refers to one.
            }
            assertTrue(schemas.valid(path, TestJson.MAPPER.nullNode()) || !schemas.violations(path, TestJson.MAPPER.nullNode()).isEmpty(), path);
            compiled++;
        }
        assertTrue(compiled > 30, "packaged schemas: " + compiled);
    }

    @Test
    void theFileBoundaryRefusesWhatARequestCannotName(@TempDir Path root) throws IOException {
        FileFacts files = new LocalFileFacts();
        Files.writeString(root.resolve("a.json"), "{}");
        Files.writeString(root.resolve("big.json"), "12345678901");
        assertEquals("{}", new String(files.readUnderRoot(root.toString(), "a.json", 100), StandardCharsets.UTF_8));
        for (String relative : List.of("../a.json", "/a.json", "a\\b", "C:/x", "x/./a", "nul", "con.txt")) {
            var refusal = org.junit.jupiter.api.Assertions.assertThrows(io.github.jaeseongs95.sennard.service.files.FileFactsException.class, () -> files.readUnderRoot(root.toString(), relative, 100), relative);
            assertEquals("INVALID_INPUT", refusal.code(), relative);
        }
        refused(run("task-contract", "validate", "input", TestJson.object("file", "\\\\server\\share\\x.json")), "INVALID_INPUT");
        refused(run("task-contract", "validate", "input", TestJson.object("file", "relative.json")), "INVALID_INPUT");
        var missing = org.junit.jupiter.api.Assertions.assertThrows(io.github.jaeseongs95.sennard.service.files.FileFactsException.class, () -> files.readUnderRoot(root.toString(), "missing.json", 100));
        assertEquals("NOT_FOUND", missing.code());
        assertEquals("practices.io.fileNotFound", missing.messageKey());
        try {
            files.read(root.resolve("big.json").toString(), 10);
            assertTrue(false, "over the limit must be refused");
        } catch (io.github.jaeseongs95.sennard.service.files.FileFactsException refused) {
            assertEquals("INVALID_INPUT", refused.code());
        }
    }

    @Test
    void aFileDocumentArgumentReadsThroughTheBoundary(@TempDir Path root) throws IOException {
        Path file = root.resolve("simple-read.json");
        Files.writeString(file, fixture("task-contract/fixtures/normal/simple-read.json").toString());
        assertEquals("PASS", contractOutput(run("task-contract", "validate", "input", TestJson.object("file", file.toString()))).get("verdict").stringValue());
        Files.writeString(root.resolve("bad.json"), "{");
        rejectedContract(run("task-contract", "validate", "input", TestJson.object("file", root.resolve("bad.json").toString())));
    }

    @Test
    void taskContractFixtures() throws IOException {
        assertEquals("PASS", contractOutput(run("task-contract", "validate", "input", fixture("task-contract/fixtures/normal/simple-read.json"))).get("verdict").stringValue());
        JsonNode high = contractOutput(run("task-contract", "validate", "input", fixture("task-contract/fixtures/boundary/high-risk.json")));
        assertEquals("high", high.get("taskEnvelope").get("riskLevel").stringValue());
        assertEquals(List.of("deploy to production"), TestJson.strings(high.get("taskEnvelope").get("authorization").get("approvalRequired")));
        rejectedContract(run("task-contract", "validate", "input", fixture("task-contract/fixtures/failure/conflicting.json")));
    }

    @Test
    void taskContractRefusesACycleAndAMissingDependency() throws IOException {
        ObjectNode input = copy(fixture("task-contract/fixtures/normal/simple-read.json"));
        ObjectNode envelope = (ObjectNode) input.get("report").get("taskEnvelope");
        envelope.set("workUnits", TestJson.array(List.of(
                TestJson.object("id", "a", "objective", "a", "dependencies", TestJson.array(List.of("b")), "writeTargets", TestJson.array(List.of("src/a.ts"))),
                TestJson.object("id", "b", "objective", "b", "dependencies", TestJson.array(List.of("a")), "writeTargets", TestJson.array(List.of("src/b.ts"))))));
        rejectedContract(run("task-contract", "validate", "input", input));
        envelope.set("workUnits", TestJson.array(List.of(TestJson.object("id", "a", "objective", "a", "dependencies", TestJson.array(List.of("missing")), "writeTargets", TestJson.array(List.of("src/a.ts"))))));
        rejectedContract(run("task-contract", "validate", "input", input));
    }

    private static ObjectNode acceptanceFixture(String name) throws IOException {
        ObjectNode input = copy(fixture("acceptance-evidence-validator/fixtures/" + name + ".json"));
        List<ObjectNode> bindings = new ArrayList<>();
        List<String> statements = TestJson.strings(input.get("taskEnvelope").get("acceptanceCriteria"));
        for (int i = 0; i < statements.size(); i++) {
            bindings.add(TestJson.object("criterionId", String.format("AC-%03d", i + 1), "statement", statements.get(i)));
        }
        input.set("criterionBindings", TestJson.array(bindings));
        return input;
    }

    @Test
    void acceptanceEvidenceFixtures() throws IOException {
        JsonNode passing = value(run("acceptance-evidence-validator", "analyze", "input", acceptanceFixture("passing")));
        assertEquals("PASS", passing.get("verdict").stringValue());
        assertEquals("BLOCKED", value(run("acceptance-evidence-validator", "analyze", "input", acceptanceFixture("stale"))).get("verdict").stringValue());
        JsonNode notApplicable = value(run("acceptance-evidence-validator", "analyze", "input", acceptanceFixture("not-applicable")));
        assertEquals("PASS", notApplicable.get("verdict").stringValue());
        assertEquals("not-applicable", notApplicable.get("criteria").get(1).get("status").stringValue());
        assertEquals("FAIL", value(run("acceptance-evidence-validator", "analyze", "input", acceptanceFixture("failing"))).get("verdict").stringValue());
    }

    @Test
    void acceptanceReportBoundToItsRequestValidates() throws IOException {
        ObjectNode input = acceptanceFixture("passing");
        JsonNode report = value(run("acceptance-evidence-validator", "analyze", "input", input));
        ObjectNode artifact = TestJson.object("artifactId", "acceptance-request:" + TestJson.text(input.get("taskEnvelope"), "taskId"), "schemaId", "https://sennard-skills.local/acceptance-evidence-validator/contracts/acceptance-evidence-input.v1.schema.json",
                "locator", "artifact://acceptance-request/" + TestJson.text(input.get("taskEnvelope"), "taskId"), "digest", TestJson.hashJson(input));
        Outcome good = run("acceptance-evidence-validator", "validate-report", "report", report, "request", input, "requestArtifact", artifact);
        assertEquals(0, good.exitCode(), () -> good.body().toString());
        ObjectNode changed = copy(report);
        changed.put("verdict", "BLOCKED");
        assertEquals(1, run("acceptance-evidence-validator", "validate-report", "report", changed, "request", input, "requestArtifact", artifact).exitCode());
    }

    @Test
    void blockerDiagnosisFixtures() throws IOException {
        assertEquals("NEXT_TEST", value(run("blocker-diagnostician", "analyze", "input", fixture("blocker-diagnostician/fixtures/same-failure.json"))).get("verdict").stringValue());
        JsonNode confirmed = value(run("blocker-diagnostician", "analyze", "input", fixture("blocker-diagnostician/fixtures/confirmed.json")));
        assertEquals("CAUSE_CONFIRMED", confirmed.get("verdict").stringValue());
        assertEquals("NEEDS_APPROVAL", value(run("blocker-diagnostician", "analyze", "input", fixture("blocker-diagnostician/fixtures/approval-needed.json"))).get("verdict").stringValue());
        assertEquals("NEEDS_INPUT", value(run("blocker-diagnostician", "analyze", "input", fixture("blocker-diagnostician/fixtures/duplicate-check.json"))).get("verdict").stringValue());
        JsonNode clusters = value(run("blocker-diagnostician", "cluster-failures", "input", fixture("blocker-diagnostician/fixtures/similar-message.json")));
        assertTrue(clusters.get("failureClusters").size() > 1, "different failures with similar messages stay in separate clusters");
    }

    @Test
    void aDiagnosisValidatesOnlyAgainstTheRequestAndDigestItWasMadeFrom() throws IOException {
        JsonNode input = fixture("blocker-diagnostician/fixtures/confirmed.json");
        String digest = value(run("blocker-diagnostician", "digest-request", "input", input)).get("requestArtifactDigest").stringValue();
        JsonNode report = value(run("blocker-diagnostician", "analyze", "input", input));
        Outcome good = run("blocker-diagnostician", "validate-report", "request", input, "requestArtifactDigest", digest, "report", report);
        assertEquals(0, good.exitCode(), () -> good.body().toString());
        Outcome wrong = run("blocker-diagnostician", "validate-report", "request", input, "requestArtifactDigest", "sha256:" + "0".repeat(64), "report", report);
        assertEquals(1, wrong.exitCode(), () -> wrong.body().toString());
    }

    private static void digestFrames(ObjectNode side) {
        side.put("contractDigest", TestJson.hashJson(side.get("contract")));
        side.put("frameDigest", TestJson.hashJson(side.get("frame")));
        side.put("controlDigest", TestJson.hashJson(side.get("frame").get("controlArtifacts")));
        side.put("targetDigest", TestJson.hashJson(side.get("frame").get("targetArtifacts")));
    }

    private static ObjectNode frame(String workspace, JsonNode control, JsonNode target) {
        return TestJson.object("schemaVersion", "1.0.0", "workspace", TestJson.object("workspaceId", "workspace-1", "locator", workspace),
                "controlArtifacts", TestJson.array(List.of(artifact("validator", "validator", "artifact://control/validator", control.get("verifier")), artifact("aggregation", "aggregation", "artifact://control/aggregation", control.get("aggregation")), artifact("pass-condition", "pass-condition", "artifact://control/pass-condition", control.get("passCondition")), artifact("evaluation-inputs", "evaluation-input", "artifact://control/evaluation-inputs", control.get("evaluationInputs")))),
                "targetArtifacts", TestJson.array(List.of(artifact("candidate", "candidate", "artifact://target/candidate", target.get("candidate")), artifact("files", "target", "artifact://target/files", target.get("files")))),
                "operationalSettings", TestJson.object("maxAttemptsPerEpoch", 3, "maxEpochs", 2, "leaseTtlSeconds", 900));
    }

    private static ObjectNode artifact(String id, String role, String locator, JsonNode content) {
        return TestJson.object("artifactId", id, "role", role, "locator", locator, "digest", TestJson.hashJson(content));
    }

    /** {@code normal-target-only} with its hand-written digests and reviewer-separation evidence filled in. */
    private static ObjectNode[] iterationCase() throws IOException {
        JsonNode base = fixture("iteration-frame-auditor/fixtures/normal-target-only.json");
        ObjectNode request = copy(base.get("request"));
        ObjectNode root = (ObjectNode) request.get("root");
        ObjectNode proposal = (ObjectNode) request.get("proposal");
        root.put("revision", 7);
        for (ObjectNode side : List.of(root, proposal)) {
            ObjectNode frame = frame(TestJson.text(root.get("contract"), "workspace"), side.get("controlFrame"), side.get("targetFrame"));
            side.set("frame", frame);
            ((ObjectNode) side.get("contract")).set("targetLocations", TestJson.array(TestJson.items(frame.get("targetArtifacts")).stream().map(t -> t.get("locator")).toList()));
            side.remove(List.of("controlFrame", "targetFrame"));
        }
        digestFrames(root);
        digestFrames(proposal);
        ((ObjectNode) request.get("reviewer")).put("freshContextEvidenceRef", "evidence-reviewer");
        ((ArrayNode) request.get("evidenceInventory")).add(TestJson.object("evidenceRef", "evidence-reviewer", "artifactDigest", "sha256:" + "5".repeat(64), "locator", "artifact://reviewer/fresh-context-assignment", "kind", "reviewer-separation", "verified", true));
        ObjectNode template = (ObjectNode) base.get("review");
        String digest = TestJson.hashJson(request);
        List<String> comparisonRefs = new ArrayList<>(TestJson.strings(template.get("evidenceRefs")));
        comparisonRefs.add("evidence-reviewer");
        ObjectNode comparison = TestJson.object("schemaVersion", "1.0.0", "rootId", template.get("rootId"), "reviewedEpoch", template.get("reviewedEpoch"), "requestArtifactDigest", digest, "reviewer", template.get("reviewer"), "comparisons", template.get("comparisons"), "evidenceRefs", TestJson.array(comparisonRefs), "rationale", template.get("rationale"), "limitations", template.get("limitations"));
        return new ObjectNode[] {request, comparison, template};
    }

    @Test
    void iterationFrameDigestRefusesAChangedFrameAndAcceptsTheHydratedOne() throws IOException {
        ObjectNode[] parts = iterationCase();
        ObjectNode request = parts[0];
        assertEquals(TestJson.hashJson(request), value(run("iteration-frame-auditor", "digest-request", "input", request)).get("requestArtifactDigest").stringValue());
        ObjectNode tampered = copy(request);
        ((ObjectNode) tampered.get("proposal").get("frame")).put("tamper", true);
        refused(run("iteration-frame-auditor", "digest-request", "input", tampered), "INVALID_INPUT");
    }

    @Test
    void aTargetOnlyCorrectionReviewValidatesAndAnotherRequestsDigestDoesNot() throws IOException {
        ObjectNode[] parts = iterationCase();
        ObjectNode request = parts[0];
        ObjectNode comparison = parts[1];
        ObjectNode template = parts[2];
        List<String> refs = new ArrayList<>(TestJson.strings(template.get("evidenceRefs")));
        refs.add("evidence-reviewer");
        ObjectNode review = TestJson.object("schemaVersion", "1.0.0", "reviewId", "review-1", "rootId", template.get("rootId"), "rootRevision", request.get("root").get("revision"), "epoch", request.get("root").get("epoch"),
                "reviewerActorId", template.get("reviewer").get("actorId"), "freshContext", TestJson.object("confirmed", true, "evidenceRef", "evidence-reviewer"), "classification", template.get("classification"),
                "comparability", TestJson.object("comparable", "comparable".equals(TestJson.text(template, "comparability")), "rationale", template.get("rationale").get(0).get("statement")),
                "route", template.get("route"), "proposedFrame", request.get("proposal").get("frame"), "evidenceRefs", TestJson.array(refs), "userApprovalRefs", TestJson.array(List.of()), "reviewedAt", "2026-09-13T00:00:00.000Z");
        Outcome good = run("iteration-frame-auditor", "validate-review", "review", review, "comparison", comparison, "request", request, "requestArtifactDigest", TestJson.hashJson(request));
        assertEquals(0, good.exitCode(), () -> good.body().toString());
        Outcome wrong = run("iteration-frame-auditor", "validate-review", "review", review, "comparison", comparison, "request", request, "requestArtifactDigest", "sha256:" + "0".repeat(64));
        assertEquals(1, wrong.exitCode(), () -> wrong.body().toString());
    }

    @Test
    void mutationRiskFixturesAreJudgedAsTheirCasesExpect() throws IOException {
        // fixture directory -> the verdict the case expects
        Map<String, String> cases = Map.of("local-delete", "READY", "deploy", "NEEDS_APPROVAL", "migration", "NEEDS_REDESIGN", "permission", "BLOCKED",
                "billing", "NEEDS_APPROVAL", "broad-target", "BLOCKED");
        cases.forEach((fixture, expectedVerdict) -> {
            try {
                JsonNode intent = fixture("mutation-risk-preflight/fixtures/" + fixture + "/intent.json");
                Outcome outcome = run("mutation-risk-preflight", "evaluate", "input", intent, "now", "2026-09-09T00:00:00.000Z");
                assertEquals(expectedVerdict, value(outcome).get("verdict").stringValue(), fixture);
                assertTrue(schemas.valid("skills/mutation-risk-preflight/contracts/mutation-preflight-report.v1.schema.json", value(outcome)), fixture);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(fixture, e);
            }
        });
    }

    @Test
    void aReadyReportIsReverifiableAndAChangedIntentIsNot() throws IOException {
        JsonNode intent = fixture("mutation-risk-preflight/fixtures/local-delete/intent.json");
        JsonNode report = value(run("mutation-risk-preflight", "evaluate", "input", intent, "now", "2026-09-09T00:00:00.000Z"));
        assertEquals("READY", report.get("verdict").stringValue());
        Outcome verified = run("mutation-risk-preflight", "verify-receipt", "input", TestJson.object("report", report, "intent", intent, "verificationTime", "2026-09-09T00:01:00.000Z"));
        assertEquals(0, verified.exitCode(), () -> verified.body().toString());
        ObjectNode changed = copy(intent);
        ((ObjectNode) changed.get("targets").get(0)).put("expectedFingerprint", "sha256:" + "9".repeat(64));
        assertEquals(2, run("mutation-risk-preflight", "verify-receipt", "input", TestJson.object("report", report, "intent", changed, "verificationTime", "2026-09-09T00:01:00.000Z")).exitCode());
    }

    @Test
    void aMutationRiskReportIsInEnglishWhateverTheRequestLanguage() throws IOException {
        Pattern hangul = Pattern.compile("[가-힣]");
        // READY (every passing sentence), NEEDS_APPROVAL, NEEDS_REDESIGN and BLOCKED (the failing sentences and the invalidation list)
        for (String fixture : List.of("local-delete", "deploy", "migration", "permission", "broad-target")) {
            JsonNode intent = fixture("mutation-risk-preflight/fixtures/" + fixture + "/intent.json");
            JsonNode english = evaluateIn(intent, Locale.ENGLISH);
            JsonNode korean = evaluateIn(intent, Locale.KOREAN);
            // The blast-radius description is the user's own words, carried into the report unchanged; it is not a sentence the server makes.
            // Check that it is carried unchanged, then check the rest of the report (every sentence the server makes) has no Hangul.
            ObjectNode serverMade = copy(korean);
            ObjectNode blast = (ObjectNode) serverMade.get("blastRadius");
            JsonNode stated = intent.path("expectedBlastRadius").path("description");
            if (stated.isString() && stated.equals(blast.get("description"))) {
                blast.remove("description");
            }
            assertFalse(hangul.matcher(serverMade.toString()).find(), fixture + ": " + serverMade);
            assertEquals(english, korean, fixture + ": the report must not depend on the request language");
        }
    }

    private static JsonNode evaluateIn(JsonNode intent, Locale requestLanguage) {
        LocaleContextHolder.setLocale(requestLanguage);
        try {
            return value(run("mutation-risk-preflight", "evaluate", "input", intent, "now", "2026-09-09T00:00:00.000Z"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void anIntentThatBreaksItsContractIsABlockedReportNotAnError() {
        Outcome outcome = run("mutation-risk-preflight", "evaluate", "input", TestJson.object("operationId", "x"));
        assertEquals(2, outcome.exitCode());
        assertEquals("BLOCKED", value(outcome).get("verdict").stringValue());
    }

    @Test
    void reportBindingChecksFollowTheRequestsExactBytes(@TempDir Path root) throws IOException {
        JsonNode request = fixture("prompt-engineering/fixtures/request.json");
        Path requestFile = root.resolve("request.json");
        Files.writeString(requestFile, request.toPrettyString() + "\n");
        byte[] bytes = Files.readAllBytes(requestFile);
        ObjectNode report = copy(fixture("prompt-engineering/fixtures/report.json"));
        report.put("requestDigest", TestJson.hashBytes(bytes));
        for (JsonNode candidate : report.get("candidates")) {
            ((ObjectNode) candidate).put("digest", TestJson.hashText(TestJson.text(candidate, "text")));
        }
        JsonNode bound = value(run("prompt-engineering", "check-report", "request", TestJson.object("file", requestFile.toString()), "report", report));
        assertTrue(bound.get("valid").booleanValue(), bound::toString);
        ObjectNode wrongDigest = copy(report);
        wrongDigest.put("requestDigest", "sha256:" + "0".repeat(64));
        assertEquals(1, run("prompt-engineering", "check-report", "request", TestJson.object("file", requestFile.toString()), "report", wrongDigest).exitCode());
    }

    private static byte[] uncheckedRead(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode uncheckedJson(Path file) {
        return TestJson.MAPPER.readTree(new String(uncheckedRead(file), StandardCharsets.UTF_8));
    }

    // change-scope-guardian reads the run's change record (a database); its cases are ChangeScopeServerTest's, on the running server.

    private record EvalFixture(Path root, ObjectNode request, Map<String, String> filenames) {
    }

    private static ObjectNode evalArtifact(String id, String role, String locator, String digest, String mediaType, String availableAt) {
        return TestJson.object("artifactId", id, "role", role, "locator", locator, "digest", digest, "mediaType", mediaType, "availableAt", availableAt, "visibleToRoles", TestJson.array(List.of("auditor")),
                "provenance", TestJson.object("source", "trusted-system", "observedAt", availableAt, "evidenceRefs", TestJson.array(List.of("artifact:receipt-" + id.substring("artifact:".length())))));
    }

    private static final String AVAILABLE = "2025-12-31T20:00:00Z";

    private static String writeArtifact(Path root, String name, String content) throws IOException {
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
        return TestJson.hashText(content);
    }

    /** Pre-execution frozen inputs: two cases, two runs, one semantic and one exact criterion. */
    private static ObjectNode evalPre(Path root) throws IOException {
        String semantic = "test:semantic-quality";
        String exact = "test:exact-output";
        String manifest = writeArtifact(root, "fixture-manifest.jsonl", "{\"schemaVersion\":\"1.0.0\",\"caseId\":\"test:case-0001\",\"split\":\"validation\"}\n{\"schemaVersion\":\"1.0.0\",\"caseId\":\"test:case-0002\",\"split\":\"holdout\"}\n");
        String rubric = writeArtifact(root, "rubric.json", "{\"schemaVersion\":\"1.0.0\",\"criteria\":[\"" + semantic + "\",\"" + exact + "\"]}");
        String oracle = writeArtifact(root, "oracle.json", "{\"schemaVersion\":\"1.0.0\",\"kinds\":[\"independent-review\",\"lexical-match\"]}");
        String aggregation = writeArtifact(root, "aggregation.json", "{\"schemaVersion\":\"1.0.0\",\"denominator\":\"all-expected-records\",\"metricIds\":[\"test:semantic-pass-rate\",\"test:exact-pass-rate\"]}");
        String timing = writeArtifact(root, "timing.json", "{\"schemaVersion\":\"1.0.0\",\"receipt\":\"frozen-before-execution\"}");
        String evidence = writeArtifact(root, "evidence.json", "{\"schemaVersion\":\"1.0.0\",\"source\":\"independent-review\"}");
        return TestJson.object("schemaVersion", "1.0.0", "auditId", "55555555-5555-4555-8555-555555555555", "auditStage", "pre-execution", "auditedAt", "2026-01-01T00:00:00Z", "target", TestJson.object("evaluationId", "run:evaluation-target", "identifier", "artifact:evaluation-target", "revision", "commit:abcdef12", "digest", TestJson.hashText("candidate-v1")),
                "frozenAt", "2025-12-31T22:00:00Z", "executionStartedAt", "2026-01-02T00:00:00Z", "actors", TestJson.object("authorIds", TestJson.array(List.of("11111111-1111-4111-8111-111111111111")), "executorIds", TestJson.array(List.of("22222222-2222-4222-8222-222222222222")), "judgeIds", TestJson.array(List.of("33333333-3333-4333-8333-333333333333")), "auditorId", "44444444-4444-4444-8444-444444444444"),
                "artifacts", TestJson.array(List.of(evalArtifact("artifact:fixture-manifest", "fixture-manifest", "fixture-manifest.jsonl", manifest, "jsonl", AVAILABLE), evalArtifact("artifact:frozen-rubric", "rubric", "rubric.json", rubric, "json", AVAILABLE), evalArtifact("artifact:frozen-oracle", "oracle", "oracle.json", oracle, "json", AVAILABLE), evalArtifact("artifact:aggregation-rule", "aggregation-rule", "aggregation.json", aggregation, "json", AVAILABLE), evalArtifact("artifact:timing-evidence", "timing-evidence", "timing.json", timing, "json", AVAILABLE), evalArtifact("artifact:independent-evidence", "evidence", "evidence.json", evidence, "json", AVAILABLE))),
                "expected", TestJson.object("caseIds", TestJson.array(List.of("test:case-0001", "test:case-0002")), "runIds", TestJson.array(List.of("run:evaluation-0001", "run:evaluation-0002")), "criterionIds", TestJson.array(List.of(semantic, exact))),
                "criteria", TestJson.array(List.of(TestJson.object("criterionId", semantic, "kind", "semantic", "judgmentMethods", TestJson.array(List.of("independent-review", "self-report")), "oracleArtifactId", "artifact:frozen-oracle", "evidenceRefs", TestJson.array(List.of("artifact:independent-evidence"))), TestJson.object("criterionId", exact, "kind", "deterministic", "judgmentMethods", TestJson.array(List.of("lexical-match")), "oracleArtifactId", "artifact:frozen-oracle", "evidenceRefs", TestJson.array(List.of("artifact:frozen-oracle"))))),
                "metrics", TestJson.array(List.of(TestJson.object("metricId", "test:semantic-pass-rate", "criterionId", semantic, "operation", "rate", "denominator", "all-expected-records", "numeratorOutcomes", TestJson.array(List.of("PASS")), "comparator", ">=", "threshold", 0.25, "claimedValue", null), TestJson.object("metricId", "test:exact-pass-rate", "criterionId", exact, "operation", "rate", "denominator", "all-expected-records", "numeratorOutcomes", TestJson.array(List.of("PASS")), "comparator", ">=", "threshold", 0.5, "claimedValue", null))),
                "runs", TestJson.array(List.of()), "knownLimitations", TestJson.array(List.of()));
    }

    private static JsonNode check(JsonNode report, String id) {
        return TestJson.items(report.get("checks")).stream().filter(c -> id.equals(TestJson.text(c, "checkId"))).findFirst().orElseThrow();
    }

    @Test
    void evaluationPreStageBlocksOnUnobservableRoleSeparationAndBindsDigests(@TempDir Path root) throws IOException {
        ObjectNode request = evalPre(root);
        assertEquals(TestJson.hashJson(request), value(run("evaluation-validity-auditor", "digest-request", "input", request)).get("digest").stringValue());
        JsonNode report = value(run("evaluation-validity-auditor", "analyze", "input", request, "artifact-root", root.toString()));
        assertEquals("BLOCKED", report.get("verdict").stringValue(), report::toString);
        assertEquals("PROVENANCE_MISSING", TestJson.text(check(report, "ROLE_INDEPENDENCE"), "code"));
        assertEquals("PASS", TestJson.text(check(report, "ARTIFACT_INTEGRITY"), "status"));
        assertFalse(report.get("qualifiesAsQualityOrReleaseEvidence").booleanValue());
        Outcome validated = run("evaluation-validity-auditor", "validate-report", "request", request, "request-artifact", TestJson.object("artifactId", "evaluation-validity-request", "locator", "request.json", "digest", TestJson.hashJson(request)), "report", report, "artifact-root", root.toString());
        assertEquals(0, validated.exitCode(), () -> validated.body().toString());
    }

    @Test
    void evaluationFailsOnADigestMismatchABadJsonlLineAndAMissingOracle(@TempDir Path root) throws IOException {
        ObjectNode request = evalPre(root);
        Files.writeString(root.resolve("oracle.json"), "{\"changed\":true}");
        JsonNode changed = value(run("evaluation-validity-auditor", "analyze", "input", request, "artifact-root", root.toString()));
        assertEquals("DIGEST_MISMATCH", TestJson.text(check(changed, "ARTIFACT_INTEGRITY"), "code"));
        assertEquals("FAIL", TestJson.text(check(changed, "JUDGMENT_METHOD_VALID"), "status"));
        evalPre(root);
        Files.delete(root.resolve("timing.json"));
        JsonNode missing = value(run("evaluation-validity-auditor", "analyze", "input", request, "artifact-root", root.toString()));
        assertEquals("ARTIFACT_MISSING", TestJson.text(check(missing, "ARTIFACT_INTEGRITY"), "code"));
    }

    @Test
    void evaluationRefusesAnAbsoluteOrTraversingLocator(@TempDir Path root) throws IOException {
        ObjectNode request = evalPre(root);
        ((ObjectNode) request.get("artifacts").get(0)).put("locator", "../escape.jsonl");
        refused(run("evaluation-validity-auditor", "analyze", "input", request, "artifact-root", root.toString()), "INVALID_INPUT");
        ObjectNode absolute = evalPre(root);
        ((ObjectNode) absolute.get("artifacts").get(0)).put("locator", root.resolve("fixture-manifest.jsonl").toString());
        refused(run("evaluation-validity-auditor", "analyze", "input", absolute, "artifact-root", root.toString()), "INVALID_INPUT");
    }

    private static ObjectNode decision(String version, String directive, String route) {
        return TestJson.object("schemaVersion", version, "sourceOriginKind", "user-turn", "sourceReceiptId", null, "authorityEffect", "none", "userDirective", directive, "netBenefitCriteria", TestJson.object("independentlyCompletable", true, "parallelBottleneckReduced", true, "limitedContextSufficient", true, "singleWriterOwnership", true, "netBenefitAfterOverhead", true), "auditSeparationRequired", false, "route", route);
    }

    @Test
    void aDecisionWithARouteOutsideTheEnumIsJudgedNotThrown() {
        ObjectNode sideways = decision("1.1.0", "unspecified", "delegate");
        sideways.put("route", "sideways");
        Outcome outcome = run("orchestrator", "validate-collaboration-decision", "input", sideways);
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertFalse(value(outcome).get("valid").booleanValue());
        assertFalse(value(outcome).get("errors").isEmpty());
    }

    @Test
    void orchestratorRoutesFollowTheCriteria() {
        Outcome delegate = run("orchestrator", "validate-collaboration-decision", "input", decision("1.1.0", "unspecified", "delegate"));
        assertEquals(0, delegate.exitCode(), () -> delegate.body().toString());
        assertEquals("structural-only", value(delegate).get("scope").stringValue());
        Outcome forbidden = run("orchestrator", "validate-collaboration-decision", "input", decision("1.1.0", "forbid", "delegate"));
        assertEquals(1, forbidden.exitCode());
        assertFalse(value(forbidden).get("valid").booleanValue());
        Outcome historical = run("orchestrator", "validate-collaboration-decision", "input", decision("1.0.0", "unspecified", "delegate"));
        assertEquals(0, historical.exitCode(), () -> historical.body().toString());
    }

    @Test
    void orchestratorQueriesTheRegistry() {
        String known = value(run("orchestrator", "query-registry", "all", true)).get("providers").get(0).get("capabilities").get(0).stringValue();
        JsonNode query = value(run("orchestrator", "query-registry", "capability", TestJson.array(List.of(known, "no-such-capability"))));
        assertEquals(List.of("no-such-capability"), TestJson.strings(query.get("missingCapabilities")));
        assertTrue(query.get("providers").size() >= 1);
        assertTrue(value(run("orchestrator", "query-registry", "all", true)).get("providers").size() > 10);
        refused(run("orchestrator", "query-registry", "all", true, "capability", TestJson.array(List.of("x"))), "INVALID_INPUT");
        refused(run("orchestrator", "query-registry"), "INVALID_INPUT");
    }

    private static ObjectNode routingOptions() {
        return TestJson.object("provider", "test-provider", "role", "general-implementation", "highRisk", false, "supported", TestJson.array(List.of(TestJson.object("model", "model-a", "modelClass", null, "efforts", TestJson.array(List.of("medium", "high"))))));
    }

    @Test
    void routingKeepsAnyHostSupportedCombinationAndBlocksOnlyWhenNoneExists() {
        ObjectNode options = routingOptions();
        options.set("user", TestJson.object("model", "model-a", "effort", "high"));
        JsonNode explicit = value(run("coordinate-subagents", "resolve", "input", options));
        assertEquals("explicit", explicit.get("delivery").stringValue());
        assertEquals("model-a", explicit.get("selection").get("model").stringValue());
        ObjectNode unsupported = routingOptions();
        unsupported.set("supported", TestJson.array(List.of()));
        Outcome blocked = run("coordinate-subagents", "resolve", "input", unsupported);
        assertEquals(1, blocked.exitCode());
        assertEquals("blocked", value(blocked).get("delivery").stringValue());
        ObjectNode inherited = routingOptions();
        inherited.set("supported", TestJson.array(List.of()));
        inherited.put("inheritanceSupported", true);
        assertEquals("inherited", value(run("coordinate-subagents", "resolve", "input", inherited)).get("delivery").stringValue());
        ObjectNode audit = routingOptions();
        audit.put("role", "independent-audit");
        refused(run("coordinate-subagents", "resolve", "input", audit), "INVALID_INPUT");
        refused(run("coordinate-subagents", "resolve", "input", routingOptions(), "presets", TestJson.array(List.of())), "INVALID_INPUT");
    }

    private static ObjectNode listed(String model, String modelClass, String... efforts) {
        return TestJson.object("model", model, "modelClass", modelClass, "efforts", TestJson.array(List.of(efforts)));
    }

    private static ObjectNode judged(String modelClass, String depth, ObjectNode... supported) {
        ObjectNode options = routingOptions();
        options.set("supported", TestJson.array(List.of(supported)));
        options.set("judgment", TestJson.object("modelClass", modelClass, "effortDepth", depth));
        return options;
    }

    @Test
    void aJudgedClassAndDepthResolveThroughTheHostsOwnListing() {
        ObjectNode light = listed("model-a", "lightweight", "low", "medium", "high");
        ObjectNode general = listed("model-b", "general", "low", "medium", "high", "xhigh");
        ObjectNode deep = listed("model-c", "deep", "low", "medium", "high", "xhigh");
        JsonNode exact = value(run("coordinate-subagents", "resolve", "input", judged("general", "standard", light, general, deep)));
        assertEquals("model-b", exact.get("selection").get("model").stringValue());
        assertEquals("medium", exact.get("selection").get("effort").stringValue());
        assertEquals("general", exact.get("requested").get("modelClass").stringValue());
        assertEquals("standard", exact.get("requested").get("effortDepth").stringValue());
        assertTrue(exact.get("fallbackReason").isNull());
        JsonNode deepest = value(run("coordinate-subagents", "resolve", "input", judged("deep", "deepest", light, general, deep)));
        assertEquals("model-c", deepest.get("selection").get("model").stringValue());
        assertEquals("xhigh", deepest.get("selection").get("effort").stringValue());
        JsonNode shallow = value(run("coordinate-subagents", "resolve", "input", judged("lightweight", "shallow", light, general, deep)));
        assertEquals("low", shallow.get("selection").get("effort").stringValue());
    }

    @Test
    void aModelFirstSeenTodayCompetesByItsClassAndTheNearestClassFillsAMissingOne() {
        ObjectNode light = listed("model-a", "lightweight", "low", "medium", "high");
        ObjectNode deep = listed("model-c", "deep", "low", "medium", "high", "xhigh");
        ObjectNode fresh = listed("model-new", "deep", "medium", "high");
        JsonNode first = value(run("coordinate-subagents", "resolve", "input", judged("deep", "deep", fresh, deep)));
        assertEquals("model-new", first.get("selection").get("model").stringValue());
        assertEquals("high", first.get("selection").get("effort").stringValue(), "the depth maps to the position in the host's own listing");
        JsonNode nearest = value(run("coordinate-subagents", "resolve", "input", judged("general", "standard", light, deep)));
        assertEquals("model-c", nearest.get("selection").get("model").stringValue(), "on a tie the higher class");
        assertFalse(nearest.get("fallbackReason").isNull(), "a class other than the judged one is reported");
    }

    private static ObjectNode withoutJudgment(ObjectNode options) {
        options.remove("judgment");
        return options;
    }

    /** Case 2: a host that lists models and also says it supports inheritance is addressed explicitly, not inherited. */
    @Test
    void aHostThatListsModelsIsNamedExplicitlyEvenWhenItAlsoSupportsInheritance() {
        ObjectNode options = judged("general", "standard", listed("model-b", "general", "low", "medium", "high"));
        options.put("inheritanceSupported", true);
        JsonNode plan = value(run("coordinate-subagents", "resolve", "input", options));
        assertEquals("explicit", plan.get("delivery").stringValue());
        assertEquals("model-b", plan.get("selection").get("model").stringValue());
        assertEquals("medium", plan.get("selection").get("effort").stringValue());
        assertTrue(plan.get("fallbackReason").isNull());
    }

    /** Case 2b: a host that lists models and a call without a judgment would end in inheritance, so the input is refused instead. */
    @Test
    void aHostThatListsModelsNeedsAJudgmentRatherThanFallingBackToInheritance() {
        ObjectNode options = withoutJudgment(judged("general", "standard", listed("model-b", "general", "low", "medium", "high")));
        options.put("inheritanceSupported", true);
        refused(run("coordinate-subagents", "resolve", "input", options), "INVALID_INPUT");
        ObjectNode userOnlyModel = copy(options);
        userOnlyModel.set("user", TestJson.object("model", "model-b"));
        refused(run("coordinate-subagents", "resolve", "input", userOnlyModel), "INVALID_INPUT");
        ObjectNode userBoth = copy(options);
        userBoth.set("user", TestJson.object("model", "model-b", "effort", "low"));
        assertEquals("explicit", value(run("coordinate-subagents", "resolve", "input", userBoth)).get("delivery").stringValue());
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0",
                "assignment", TestJson.object("role", "general-implementation", "difficulty", "routine", "riskLevel", "low", "verification", "direct", "signals", TestJson.array(List.of("brief fixes target, method and criteria"))),
                "host", TestJson.object("id", "host-1", "provider", "test-provider", "capabilityReference", "fixture", "inheritanceSupported", true, "supported", TestJson.array(List.of(listed("model-b", "general", "low")))));
        refused(run("coordinate-subagents", "advise", "input", request), "INVALID_SUBAGENT_MODEL_REQUEST");
    }

    /** Case 3: a host or context that forbids overrides inherits, with the reason, and the delegation is never blocked. */
    @Test
    void aHostThatForbidsOverridesInheritsWithTheReasonAndIsNeverBlocked() {
        ObjectNode options = judged("general", "standard", listed("model-b", "general", "low", "medium", "high"));
        options.put("inheritOnly", true);
        Outcome outcome = run("coordinate-subagents", "resolve", "input", options);
        assertEquals(0, outcome.exitCode());
        JsonNode plan = value(outcome);
        assertEquals("inherited", plan.get("delivery").stringValue());
        assertTrue(plan.get("selection").isNull());
        assertFalse(plan.get("fallbackReason").isNull(), "an inherited call carries its reason");
        ObjectNode noJudgment = withoutJudgment(copy(options));
        assertEquals("inherited", value(run("coordinate-subagents", "resolve", "input", noJudgment)).get("delivery").stringValue(), "a host that forbids overrides needs no judgment");
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0",
                "assignment", TestJson.object("role", "general-implementation", "difficulty", "routine", "riskLevel", "low", "verification", "direct", "signals", TestJson.array(List.of("brief fixes target, method and criteria"))),
                "host", TestJson.object("id", "host-1", "provider", "test-provider", "capabilityReference", "fixture", "inheritOnly", true, "supported", TestJson.array(List.of(listed("model-b", "general", "low")))));
        Outcome advised = run("coordinate-subagents", "advise", "input", request);
        assertEquals(0, advised.exitCode(), () -> advised.body().toString());
        assertEquals("INHERIT_UNVERIFIED", value(advised).get("status").stringValue());
        assertFalse(value(advised).get("selectionPlan").get("fallbackReason").isNull());
    }

    /** Case 3b: a host that lists nothing it takes per call inherits and says why. */
    @Test
    void aHostThatListsNoModelInheritsWithTheReason() {
        ObjectNode options = judged("general", "standard");
        options.put("inheritanceSupported", true);
        JsonNode plan = value(run("coordinate-subagents", "resolve", "input", options));
        assertEquals("inherited", plan.get("delivery").stringValue());
        assertFalse(plan.get("fallbackReason").isNull());
    }

    /** Case 4: a host whose model takes no effort is passed the model alone, and an effort the model does not list is never passed. */
    @Test
    void aModelThatTakesNoEffortOrNotTheWantedOneIsPassedAlone() {
        JsonNode none = value(run("coordinate-subagents", "resolve", "input", judged("general", "deep", listed("model-b", "general"))));
        assertEquals("explicit", none.get("delivery").stringValue());
        assertEquals("model-b", none.get("selection").get("model").stringValue());
        assertFalse(none.get("selection").has("effort"), "no effort argument is passed");
        assertFalse(none.get("fallbackReason").isNull());
        ObjectNode unlistedLabel = judged("general", "deep", listed("model-b", "general", "low", "medium"));
        unlistedLabel.set("user", TestJson.object("effort", "max"));
        JsonNode alone = value(run("coordinate-subagents", "resolve", "input", unlistedLabel));
        assertEquals("explicit", alone.get("delivery").stringValue());
        assertEquals("model-b", alone.get("selection").get("model").stringValue());
        assertFalse(alone.get("selection").has("effort"), "an effort the model does not list is not passed");
        ObjectNode userModel = judged("general", "deep", listed("model-b", "general"), listed("model-c", "deep", "low", "high"));
        userModel.set("user", TestJson.object("model", "model-b", "effort", "high"));
        JsonNode named = value(run("coordinate-subagents", "resolve", "input", userModel));
        assertEquals("model-b", named.get("selection").get("model").stringValue());
        assertFalse(named.get("selection").has("effort"));
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0",
                "assignment", TestJson.object("role", "general-implementation", "difficulty", "routine", "riskLevel", "low", "verification", "direct", "signals", TestJson.array(List.of("brief fixes target, method and criteria"))),
                "host", TestJson.object("id", "host-1", "provider", "test-provider", "capabilityReference", "fixture", "supported", TestJson.array(List.of(listed("model-b", "general")))),
                "judgment", TestJson.object("modelClass", "general", "effortDepth", "standard"));
        Outcome advised = run("coordinate-subagents", "advise", "input", request);
        assertEquals(0, advised.exitCode(), () -> advised.body().toString());
        assertEquals("READY", value(advised).get("status").stringValue());
        assertFalse(value(advised).get("selectionPlan").get("selection").has("effort"));
    }

    /** Case 4b: the record accepts the model alone, refuses an effort argument the plan did not select, and refuses overrides on an inherited call. */
    @Test
    void theRecordMatchesAModelOnlyCallAndAnInheritedCall() {
        ObjectNode presets = TestJson.object("providers", TestJson.object("test-provider", TestJson.object("application", TestJson.object("model_field", "engine", "effort_field", "reasoning"))));
        ObjectNode options = judged("general", "deep", listed("model-b", "general"));
        ObjectNode record = TestJson.object("assignmentId", "fixture-unit", "selectionReason", "The host takes no effort for this model", "options", options, "contextMode", "limited",
                "spawnArguments", TestJson.object("engine", "model-b"), "observation", null);
        JsonNode recorded = value(run("coordinate-subagents", "record", "input", record, "presets", presets));
        assertEquals("unverified", recorded.get("actual").get("status").stringValue());
        assertFalse(recorded.get("fallbackReason").isNull());
        ObjectNode forced = copy(record);
        forced.set("spawnArguments", TestJson.object("engine", "model-b", "reasoning", "high"));
        refused(run("coordinate-subagents", "record", "input", forced, "presets", presets), "INVALID_INPUT");
        ObjectNode inheritOptions = copy(options);
        inheritOptions.put("inheritOnly", true);
        ObjectNode inherited = copy(record);
        inherited.set("options", inheritOptions);
        inherited.set("spawnArguments", TestJson.object());
        JsonNode inheritedRecord = value(run("coordinate-subagents", "record", "input", inherited, "presets", presets));
        assertFalse(inheritedRecord.get("fallbackReason").isNull(), "an inherited call is recorded with its reason");
        inherited.set("spawnArguments", TestJson.object("engine", "model-b"));
        refused(run("coordinate-subagents", "record", "input", inherited, "presets", presets), "INVALID_INPUT");
    }

    @Test
    void theHostOrderBreaksTiesBetweenUnclassifiedModelsAndAnExplicitUserChoiceStillWins() {
        ObjectNode unknown = listed("model-x", null, "low", "medium");
        ObjectNode secondUnknown = listed("model-y", null, "low", "medium", "high");
        JsonNode fallback = value(run("coordinate-subagents", "resolve", "input", judged("general", "standard", unknown, secondUnknown)));
        assertEquals("explicit", fallback.get("delivery").stringValue());
        assertEquals("model-x", fallback.get("selection").get("model").stringValue());
        assertEquals("low", fallback.get("selection").get("effort").stringValue(), "two host labels keep the existing depth mapping");
        assertFalse(fallback.get("fallbackReason").isNull(), "the selected model has no established class");

        ObjectNode labelled = judged("general", "standard", unknown, secondUnknown, listed("model-b", "general", "low"));
        labelled.set("user", TestJson.object("effort", "high"));
        JsonNode compatible = value(run("coordinate-subagents", "resolve", "input", labelled));
        assertEquals("model-y", compatible.get("selection").get("model").stringValue());
        assertEquals("high", compatible.get("selection").get("effort").stringValue());

        ObjectNode named = judged("general", "standard", unknown, listed("model-b", "general", "low", "medium", "high", "xhigh"));
        named.set("user", TestJson.object("model", "model-x", "effort", "low"));
        JsonNode user = value(run("coordinate-subagents", "resolve", "input", named));
        assertEquals("model-x", user.get("selection").get("model").stringValue());
        ObjectNode effortOnly = judged("general", "shallow", unknown, listed("model-b", "general", "low", "medium", "high", "xhigh"));
        effortOnly.set("user", TestJson.object("effort", "xhigh"));
        JsonNode kept = value(run("coordinate-subagents", "resolve", "input", effortOnly));
        assertEquals("model-b", kept.get("selection").get("model").stringValue(), "a user effort alone keeps the judged class");
        assertEquals("xhigh", kept.get("selection").get("effort").stringValue());
        ObjectNode invalid = judged("general", "standard", unknown);
        invalid.set("judgment", TestJson.object("modelClass", "general", "effortDepth", "xhigh"));
        refused(run("coordinate-subagents", "resolve", "input", invalid), "INVALID_INPUT");
    }

    @Test
    void theAdvisorPassesTheJudgmentThroughAndItsAdviceStaysValid() {
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0",
                "assignment", TestJson.object("role", "general-implementation", "difficulty", "substantial", "riskLevel", "low", "verification", "direct", "signals", TestJson.array(List.of("brief fixes target, method and criteria"))),
                "host", TestJson.object("id", "host-1", "provider", "test-provider", "capabilityReference", "fixture", "supported", TestJson.array(List.of(listed("model-b", "general", "low", "medium", "high")))),
                "judgment", TestJson.object("modelClass", "general", "effortDepth", "standard"));
        Outcome outcome = run("coordinate-subagents", "advise", "input", request);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode advice = value(outcome);
        assertEquals("READY", advice.get("status").stringValue());
        assertEquals("model-b", advice.get("selectionPlan").get("selection").get("model").stringValue());
        assertEquals("medium", advice.get("selectionPlan").get("selection").get("effort").stringValue());
    }

    @Test
    void routingRecordsWhatActuallyRan() {
        ObjectNode presets = TestJson.object("providers", TestJson.object("test-provider", TestJson.object("application", TestJson.object("model_field", "engine", "effort_field", "reasoning"))));
        ObjectNode options = routingOptions();
        options.set("user", TestJson.object("model", "model-a", "effort", "high"));
        ObjectNode record = TestJson.object("assignmentId", "fixture-unit", "selectionReason", "Observed role capability", "options", options, "contextMode", "limited", "spawnArguments", TestJson.object("engine", "model-a", "reasoning", "high"), "observation", null);
        JsonNode unconfirmed = value(run("coordinate-subagents", "record", "input", record, "presets", presets));
        assertEquals("unverified", unconfirmed.get("actual").get("status").stringValue());
        record.set("observation", TestJson.object("source", "spawn-result", "reference", "run-1", "model", "model-a", "effort", "high"));
        assertEquals("applied", value(run("coordinate-subagents", "record", "input", record, "presets", presets)).get("actual").get("status").stringValue());
        ObjectNode mismatch = copy(record);
        mismatch.set("spawnArguments", TestJson.object("engine", "model-b", "reasoning", "high"));
        refused(run("coordinate-subagents", "record", "input", mismatch, "presets", presets), "INVALID_INPUT");
    }

    @Test
    void theAdvisorRefusesAnInvalidRequestAndNeverClassifiesByName() {
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0");
        refused(run("coordinate-subagents", "advise", "input", request), "INVALID_SUBAGENT_MODEL_REQUEST");
    }

    @Test
    void anEffortLabelNoCodeKnowsIsAcceptedInTheHostsOwnOrder() {
        ObjectNode odd = listed("model-b", "general", "calm", "brisk", "thorough");
        assertEquals("thorough", value(run("coordinate-subagents", "resolve", "input", judged("general", "deepest", odd))).get("selection").get("effort").stringValue());
        assertEquals("calm", value(run("coordinate-subagents", "resolve", "input", judged("general", "shallow", odd))).get("selection").get("effort").stringValue());
        assertEquals("brisk", value(run("coordinate-subagents", "resolve", "input", judged("general", "standard", odd))).get("selection").get("effort").stringValue());
        ObjectNode named = judged("general", "standard", odd);
        named.set("user", TestJson.object("effort", "thorough"));
        assertEquals("thorough", value(run("coordinate-subagents", "resolve", "input", named)).get("selection").get("effort").stringValue());
        ObjectNode blank = judged("general", "standard", listed("model-b", "general", " "));
        refused(run("coordinate-subagents", "resolve", "input", blank), "INVALID_INPUT");
        ObjectNode twice = judged("general", "standard", listed("model-b", "general", "calm", "calm"));
        refused(run("coordinate-subagents", "resolve", "input", twice), "INVALID_INPUT");
        ObjectNode role = judged("general", "standard", odd);
        role.put("role", "complex-reasoning");
        refused(run("coordinate-subagents", "resolve", "input", role), "INVALID_INPUT");
    }

    private static ObjectNode instructionRequest(Path root, String target) {
        return TestJson.object("schemaVersion", "1.0.0", "workspaceRoot", root.toString(), "instructionRoots", TestJson.array(List.of(TestJson.object("path", root.toString(), "precedence", 10, "authorized", true))), "targets", TestJson.array(List.of(TestJson.object("path", target, "mayNotExist", false))), "externalPolicyRefs", TestJson.array(List.of()));
    }

    private static List<String> chain(JsonNode output) {
        return TestJson.items(output.get("targets").get(0).get("instructionChain")).stream().map(i -> Path.of(TestJson.text(i, "path")).getFileName().toString()).toList();
    }

    @Test
    void instructionChainsOrderBroaderToNarrowerAndAnOverrideReplacesItsSibling(@TempDir Path temp) throws IOException {
        Path root = temp.toRealPath();
        Files.createDirectories(root.resolve("src/nested"));
        Files.writeString(root.resolve("AGENTS.md"), "# root\n");
        Files.writeString(root.resolve("src/AGENTS.md"), "# src\n");
        Files.writeString(root.resolve("src/AGENTS.override.md"), "# override\n");
        JsonNode output = value(run("instruction-scope-resolver", "resolve", "input", instructionRequest(root, "src/nested")));
        assertEquals(List.of("AGENTS.md", "AGENTS.override.md"), chain(output));
        assertEquals("ANALYSIS_REQUIRED", output.get("verdict").stringValue());
        assertEquals(1, output.get("targets").get(0).get("instructionChain").get(0).get("precedence").asInt());
        Files.writeString(root.resolve("src/AGENTS.override.md"), " \n ");
        assertEquals(List.of("AGENTS.md", "AGENTS.md"), chain(value(run("instruction-scope-resolver", "resolve", "input", instructionRequest(root, "src/nested")))), "a whitespace-only override is ignored");
    }

    @Test
    void instructionScopeRefusesATargetOutsideTheWorkspace(@TempDir Path temp) throws IOException {
        Path root = temp.toRealPath();
        Files.writeString(root.resolve("AGENTS.md"), "# root\n");
        refused(run("instruction-scope-resolver", "resolve", "input", instructionRequest(root, "../outside")), "INVALID_INPUT");
        refused(run("instruction-scope-resolver", "resolve", "input", instructionRequest(root, "missing/dir")), "INVALID_INPUT");
    }

    @Test
    void anAdapterSuppliedFileListChangesWhichNamesCount(@TempDir Path temp) throws IOException {
        Path root = temp.toRealPath();
        Files.writeString(root.resolve("AGENTS.md"), "# agents\n");
        Files.writeString(root.resolve("HOST.md"), "# host\n");
        JsonNode output = value(run("instruction-scope-resolver", "resolve", "input", instructionRequest(root, "."), "instructionFiles", TestJson.array(List.of(TestJson.object("name", "HOST.md", "kind", "agents"), TestJson.object("name", "AGENTS.md", "kind", "agents")))));
        assertEquals(List.of("HOST.md"), chain(output));
    }

    @Test
    void recoveryStrategyRefusesARequestThatIsNotBoundToItsSources() throws IOException {
        refused(run("recovery-strategy-selector", "digest-request", "input", TestJson.object("schemaVersion", "1.0.0")), "INVALID_INPUT");
        Outcome handoff = run("recovery-strategy-selector", "validate-handoff", "input", TestJson.object("schemaVersion", "1.0.0"));
        assertEquals(1, handoff.exitCode(), () -> handoff.body().toString());
        assertFalse(value(handoff).get("valid").booleanValue());
        assertFalse(value(handoff).get("errors").isEmpty());
    }

    private static ObjectNode json(String text) {
        return (ObjectNode) TestJson.MAPPER.readTree(text);
    }

    private static ObjectNode recoveryEnvelope() {
        return json("""
                {"schemaVersion":"1.0.0","taskId":"failed-task","objective":"Repair the local cache writer.","scope":{"included":["cache writer"],"excluded":["deployment"]},
                 "acceptanceCriteria":["[AC-001] The cache write completes and its regression test passes."],"riskLevel":"medium",
                 "workUnits":[{"id":"repair","objective":"Repair cache writes.","dependencies":[],"writeTargets":["src/cache.ts"]}],"requiredCapabilities":["local-fix"],"constraints":["Keep production untouched."],
                 "authorization":{"allowedActions":["local-edit","rollback"],"prohibitedActions":["deploy"],"approvalRequired":["schema-migration"]},
                 "decision":{"complexity":"complex","hasConflicts":false},"orchestration":{"requested":true,"mcpAvailable":true}}""");
    }

    /** A confirmed diagnosis, its source task and failed run, all bound by digest: the only shape a recovery may start from. */
    private static ObjectNode recoveryRequest() {
        ObjectNode envelope = recoveryEnvelope();
        ObjectNode receipt = json("""
                {"schemaVersion":"1.0.0","runId":"run-failed","revision":4,"state":"FAILED",
                 "plan":{"schemaVersion":"1.0.0","taskId":"failed-task","executionMode":"orchestrated","state":"FAILED","selectedSkills":[],"stages":[],"currentStageId":null,"nextStageId":null,"errors":[]},
                 "stageResults":[],"blockers":["cache-write-failed"],"unresolved":["cache-write-failed"],"error":null,"unverifiedClaims":[]}""");
        ((ObjectNode) receipt.get("plan")).put("taskDigest", TestJson.hashJson(envelope)).put("integrityToken", "A".repeat(43));
        String causeEvidence = TestJson.hashJson(TestJson.object("mode", "readonly"));
        ObjectNode diagnosisRequest = json("""
                {"schemaVersion":"1.0.0","objective":"Repair the local cache writer.","expectedBehavior":"The cache writer stores its entry.",
                 "episodes":[{"attemptId":"attempt-1","operation":"write cache","stableFailureTuple":{"code":"EACCES"},"environmentDigest":"env:local","changeSummary":"","evidenceRefs":["cause-evidence","source-task-envelope","source-workflow-receipt"]}],
                 "lastKnownGood":null,"constraints":["Keep production untouched."],"authorization":{"allowedChecks":[],"approvalRequired":[],"prohibitedChecks":[]},"attemptedChecks":[],
                 "candidateHypotheses":[{"id":"cause-cache-permission","causalLayer":"permission","statement":"The cache path lacks write permission.","supportingEvidence":["cause-evidence","source-task-envelope","source-workflow-receipt"],"contradictingEvidence":[],"state":"confirmed",
                   "causeAnalysis":{"symptom":"Cache writes fail with EACCES.","mechanism":"The writer reaches a path without write permission.","rootCondition":"The cache path lacks write permission.",
                     "discriminatingEvidence":{"statement":"The file check identifies the denied path.","evidenceRefs":["cause-evidence"]},"eliminationObservation":{"statement":"The permission result rules out a serialization failure.","evidenceRefs":["cause-evidence"]}}}],
                 "candidateTests":[],"accessBlockers":[]}""");
        diagnosisRequest.set("evidenceBindings", TestJson.array(List.of(recoveryBinding("cause-evidence", causeEvidence), recoveryBinding("source-task-envelope", TestJson.hashJson(envelope)), recoveryBinding("source-workflow-receipt", TestJson.hashJson(receipt)))));
        JsonNode report = value(run("blocker-diagnostician", "analyze", "input", diagnosisRequest));
        ObjectNode request = json("""
                {"schemaVersion":"1.0.0","selectionId":"selection-001","constraints":["Use a fresh workflow run."],
                 "authorizationEvidence":[{"action":"local-edit","effect":"allow","authority":"user","sourceLocator":"request:/authorization"},{"action":"rollback","effect":"allow","authority":"user","sourceLocator":"request:/authorization"},
                   {"action":"schema-migration","effect":"require-approval","authority":"user","sourceLocator":"request:/authorization"},{"action":"deploy","effect":"prohibit","authority":"user","sourceLocator":"request:/authorization"}],
                 "priorStrategyFingerprints":[]}""");
        request.set("diagnosis", TestJson.object("locator", "diagnosis:run-2", "digest", TestJson.hashJson(report), "report", report, "requestLocator", "diagnosis-request:run-2", "requestDigest", TestJson.hashJson(diagnosisRequest), "request", diagnosisRequest));
        request.set("sourceTask", TestJson.object("locator", "task:failed-task", "digest", TestJson.hashJson(envelope), "envelope", envelope));
        request.set("sourceWorkflow", TestJson.object("runId", "run-failed", "revision", 4, "state", "FAILED", "receiptLocator", "workflow:run-failed", "receiptDigest", TestJson.hashJson(receipt), "receipt", receipt));
        request.set("evidenceIndex", TestJson.array(List.of(TestJson.object("evidenceRef", "cause-evidence", "locator", "tool:file-stat", "digest", causeEvidence, "verified", true))));
        return request;
    }

    private static ObjectNode recoveryBinding(String ref, String digest) {
        return TestJson.object("evidenceRef", ref, "artifactDigest", digest, "hypothesisIds", TestJson.array(List.of("cause-cache-permission")), "relation", "supports");
    }

    private static ObjectNode recoveryStrategy(String id) {
        ObjectNode item = json("""
                {"strategyId":"<id>","summary":"Apply <id>.","mechanism":"mechanism-<id>","addressesCauseId":"cause-cache-permission","rootConditionChange":"Change the denied cache-path condition with <id>.",
                 "actions":[{"name":"local-edit","target":"src/cache.ts"}],"writeTargets":["<id>.ts"],"preconditions":[{"statement":"The cause evidence is current.","status":"satisfied","evidenceRefs":["cause-evidence"]}],
                 "verificationPlan":[{"check":"test-<id>","expectedResult":"passes","evidenceRequired":"test output"}],"stopConditions":["Stop after one failed verification."],"failureImpact":"low","reversibility":"full","causeFit":"direct","verificationStrength":"direct","changeBreadth":1,
                 "scopeExpansion":false,"objectivePreserved":true,"acceptanceCriteriaPreserved":true,"mutatesPriorRun":false,"repeatsPriorAttempt":false,"requiredAuthorization":[],"evidenceRefs":["cause-evidence"],"objectiveGate":{"verdict":"PASS","reasons":[]}}""".replace("<id>", id));
        item.put("rootConditionDigest", TestJson.hashJson(TestJson.object("causeId", "cause-cache-permission", "rootCondition", "The cache path lacks write permission.")));
        return fingerprinted(item);
    }

    private static ObjectNode fingerprinted(ObjectNode strategy) {
        ArrayNode preconditions = TestJson.MAPPER.createArrayNode();
        strategy.get("preconditions").forEach(item -> preconditions.add(item.get("statement")));
        strategy.put("strategyFingerprint", TestJson.hashJson(TestJson.object("mechanism", strategy.get("mechanism"), "addressesCauseId", strategy.get("addressesCauseId"), "rootConditionDigest", strategy.get("rootConditionDigest"), "rootConditionChange", strategy.get("rootConditionChange"),
                "actions", strategy.get("actions"), "writeTargets", strategy.get("writeTargets"), "preconditions", preconditions, "verificationPlan", strategy.get("verificationPlan"), "stopConditions", strategy.get("stopConditions"))));
        return strategy;
    }

    private static ObjectNode rejectedStrategy() {
        ObjectNode unsafe = recoveryStrategy("unsafe").put("mutatesPriorRun", true);
        unsafe.set("objectiveGate", TestJson.object("verdict", "FAIL", "reasons", TestJson.array(List.of("prior-run-mutation"))));
        return fingerprinted(unsafe);
    }

    private static ObjectNode migrationStrategy() {
        ObjectNode approval = recoveryStrategy("migration");
        approval.set("actions", TestJson.array(List.of(TestJson.object("name", "schema-migration", "target", "cache schema"))));
        approval.set("writeTargets", TestJson.array(List.of("schema.sql")));
        approval.set("requiredAuthorization", TestJson.array(List.of("schema-migration")));
        approval.set("objectiveGate", TestJson.object("verdict", "REQUIRES_APPROVAL", "reasons", TestJson.array(List.of())));
        return fingerprinted(approval);
    }

    private static ObjectNode recoveryHandoff(ObjectNode request, List<ObjectNode> strategies, String selected, String verdict) {
        ObjectNode chosen = strategies.stream().filter(s -> TestJson.text(s, "strategyId").equals(selected)).findFirst().orElse(null);
        List<String> survivors = strategies.stream().filter(s -> !"FAIL".equals(TestJson.text(s.get("objectiveGate"), "verdict"))).map(s -> TestJson.text(s, "strategyId")).toList();
        ObjectNode review = survivors.size() > 1
                ? TestJson.object("status", selected != null ? "completed" : "unresolved", "reviews", TestJson.array(survivors.stream().map(id -> TestJson.object("strategyId", id, "challenge", "Could a narrower change work?", "response", "This option directly addresses the confirmed cause.", "evidenceRefs", TestJson.array(List.of("cause-evidence")))).toList()), "unresolvedStrategyIds", TestJson.array(selected != null ? List.of() : survivors))
                : TestJson.object("status", "not-required", "reviews", TestJson.array(List.of()), "unresolvedStrategyIds", TestJson.array(List.of()));
        JsonNode envelope = request.get("sourceTask").get("envelope");
        JsonNode report = request.get("diagnosis").get("report");
        JsonNode seed = TestJson.MAPPER.nullNode();
        if (chosen != null) {
            List<String> constraints = new ArrayList<>(TestJson.strings(envelope.get("constraints")));
            constraints.addAll(TestJson.strings(request.get("constraints")));
            seed = TestJson.object("objective", envelope.get("objective"), "recoveryObjective", chosen.get("summary"), "addressesCauseId", chosen.get("addressesCauseId"), "rootConditionDigest", chosen.get("rootConditionDigest"), "scope", envelope.get("scope"), "acceptanceCriteria", envelope.get("acceptanceCriteria"),
                    "constraints", TestJson.array(constraints.stream().sorted().toList()), "workUnits", TestJson.array(List.of(TestJson.object("id", "new-recovery", "objective", chosen.get("summary"), "dependencies", TestJson.array(List.of()), "writeTargets", chosen.get("writeTargets")))),
                    "requiredCapabilities", TestJson.array(List.of("local-fix")), "requestedActions", TestJson.array(TestJson.items(chosen.get("actions")).stream().map(a -> a.get("name")).toList()), "verificationPlan", chosen.get("verificationPlan"));
        }
        return TestJson.object("schemaVersion", "1.0.0", "handoffId", "handoff-001", "selectionRequestDigest", TestJson.hashJson(request), "sourceTask", TestJson.object("taskId", envelope.get("taskId"), "envelopeDigest", request.get("sourceTask").get("digest")),
                "sourceWorkflow", TestJson.object("runId", request.get("sourceWorkflow").get("runId"), "revision", request.get("sourceWorkflow").get("revision"), "receiptDigest", request.get("sourceWorkflow").get("receiptDigest")),
                "diagnosis", TestJson.object("reportDigest", request.get("diagnosis").get("digest"), "requestArtifactDigest", report.get("requestArtifactDigest"), "confirmedCauseId", report.get("confirmedCause").get("causeId"), "rootConditionDigest", TestJson.hashJson(TestJson.object("causeId", report.get("confirmedCause").get("causeId"), "rootCondition", report.get("confirmedCause").get("rootCondition")))),
                "strategies", TestJson.array(strategies), "survivingStrategyIds", TestJson.array(survivors), "crossReview", review, "selectedStrategyId", selected, "nextTaskSeed", seed,
                "approvalRequired", chosen == null ? TestJson.array(List.of()) : chosen.get("requiredAuthorization"), "limitations", TestJson.array(List.of("The selector does not execute the strategy.")), "verdict", verdict);
    }

    private static Outcome validateHandoff(ObjectNode request, ObjectNode handoff, String frozenDigest) {
        return run("recovery-strategy-selector", "validate-handoff", "input", TestJson.object("request", request, "handoff", handoff, "requestArtifactDigest", frozenDigest));
    }

    private static void handoffValid(Outcome outcome) {
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        assertTrue(value(outcome).get("valid").booleanValue(), () -> outcome.body().toString());
        assertTrue(value(outcome).get("errors").isEmpty(), () -> outcome.body().toString());
    }

    private static void handoffInvalid(Outcome outcome) {
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertFalse(value(outcome).get("valid").booleanValue(), () -> outcome.body().toString());
    }

    @Test
    void aHandoffSelectingTheOnlySurvivorValidatesAgainstItsFrozenRequest() {
        ObjectNode request = recoveryRequest();
        String frozen = value(run("recovery-strategy-selector", "digest-request", "input", request)).get("requestArtifactDigest").stringValue();
        assertEquals(TestJson.hashJson(request), frozen);
        ObjectNode handoff = recoveryHandoff(request, List.of(recoveryStrategy("safe"), rejectedStrategy()), "safe", "SELECTED");
        handoffValid(validateHandoff(request, handoff, frozen));

        handoffInvalid(validateHandoff(request, handoff, "sha256:" + "0".repeat(64)));

        ObjectNode changedMechanism = copy(handoff);
        ((ObjectNode) changedMechanism.get("strategies").get(0)).put("mechanism", "another mechanism");
        handoffInvalid(validateHandoff(request, changedMechanism, frozen));

        ObjectNode gateLie = recoveryStrategy("unsafe").put("mutatesPriorRun", true);
        handoffInvalid(validateHandoff(request, recoveryHandoff(request, List.of(recoveryStrategy("safe"), fingerprinted(gateLie)), "safe", "SELECTED"), frozen));

        ObjectNode otherTask = copy(handoff);
        ((ObjectNode) otherTask.get("sourceTask")).put("taskId", "another-task");
        handoffInvalid(validateHandoff(request, otherTask, frozen));
    }

    @Test
    void twoSurvivorsAreCrossReviewedAndAnExactTieIsLeftToTheUser() {
        ObjectNode request = recoveryRequest();
        ObjectNode contained = recoveryStrategy("contained").put("causeFit", "containment");
        ObjectNode decided = recoveryHandoff(request, List.of(recoveryStrategy("direct"), fingerprinted(contained)), "direct", "SELECTED");
        handoffValid(validateHandoff(request, decided, TestJson.hashJson(request)));
        assertEquals("completed", decided.get("crossReview").get("status").stringValue());

        List<ObjectNode> tied = List.of(recoveryStrategy("a"), recoveryStrategy("b"));
        handoffValid(validateHandoff(request, recoveryHandoff(request, tied, null, "NEEDS_INPUT"), TestJson.hashJson(request)));
        handoffInvalid(validateHandoff(request, recoveryHandoff(request, tied, "a", "SELECTED"), TestJson.hashJson(request)));
    }

    private static ObjectNode[] recoveryTask(ObjectNode handoff, List<String> allowed, List<String> approval) {
        JsonNode seed = handoff.get("nextTaskSeed");
        String locator = "recovery-handoff:" + TestJson.hashJson(handoff);
        List<ObjectNode> evidence = new ArrayList<>();
        allowed.forEach(action -> evidence.add(TestJson.object("action", action, "effect", "allow", "authority", "user", "sourceLocator", "user-approval:" + action)));
        evidence.add(TestJson.object("action", "deploy", "effect", "prohibit", "authority", "user", "sourceLocator", "source-task:deploy"));
        approval.forEach(action -> evidence.add(TestJson.object("action", action, "effect", "require-approval", "authority", "user", "sourceLocator", "source-task:" + action)));
        ObjectNode taskRequest = TestJson.object("schemaVersion", "1.0.0", "taskId", "recovery-task-001", "request", "Create a new recovery task from the validated handoff.", "instructionResolutionRefs", TestJson.array(List.of("instructions:root")), "pathSemantics", "windows",
                "authorizationEvidence", TestJson.array(evidence), "suppliedFacts", TestJson.array(List.of(TestJson.object("statement", "Use the validated recovery handoff.", "locator", locator))), "userDecisions", TestJson.array(List.of()));
        ObjectNode envelope = TestJson.object("schemaVersion", "1.0.0", "taskId", "recovery-task-001", "objective", seed.get("objective"), "scope", seed.get("scope"), "acceptanceCriteria", seed.get("acceptanceCriteria"), "riskLevel", "medium", "workUnits", seed.get("workUnits"), "requiredCapabilities", seed.get("requiredCapabilities"), "constraints", seed.get("constraints"),
                "authorization", TestJson.object("allowedActions", TestJson.array(allowed), "prohibitedActions", TestJson.array(List.of("deploy")), "approvalRequired", TestJson.array(approval)), "decision", TestJson.object("complexity", "complex", "hasConflicts", false), "orchestration", TestJson.object("requested", true, "mcpAvailable", true));
        List<ObjectNode> provenance = new ArrayList<>();
        List.of("/objective", "/scope", "/acceptanceCriteria", "/workUnits").forEach(field -> provenance.add(TestJson.object("field", field, "valueSummary", "Bound from recovery handoff.", "sourceLocator", locator, "basis", "explicit")));
        provenance.add(TestJson.object("field", "/riskLevel", "valueSummary", "Preserve medium risk.", "sourceLocator", "source-task:risk", "basis", "explicit"));
        provenance.add(TestJson.object("field", "/authorization", "valueSummary", "Bind current authority evidence.", "sourceLocator", "task-contract-request:authorization", "basis", "explicit"));
        List<ObjectNode> authorization = evidence.stream().map(item -> TestJson.object("action", item.get("action"), "envelopeField", switch (TestJson.text(item, "effect")) {
            case "allow" -> "/authorization/allowedActions";
            case "prohibit" -> "/authorization/prohibitedActions";
            default -> "/authorization/approvalRequired";
        }, "effect", item.get("effect"), "authority", item.get("authority"), "sourceLocator", item.get("sourceLocator"))).toList();
        ObjectNode plan = TestJson.object("schemaVersion", "1.0.0", "criteria", TestJson.array(List.of(TestJson.object("criterionId", "AC-001", "statement", seed.get("acceptanceCriteria").get(0), "verificationMethod", "Run the cache regression test.", "expectedEvidenceKinds", TestJson.array(List.of("test")), "passCondition", "The regression test passes."))));
        ObjectNode taskReport = TestJson.object("schemaVersion", "1.0.0", "taskEnvelope", envelope, "acceptanceEvidencePlan", plan, "provenance", TestJson.array(provenance), "authorizationProvenance", TestJson.array(authorization), "assumptions", TestJson.array(List.of()), "ambiguities", TestJson.array(List.of()), "contradictions", TestJson.array(List.of()), "verdict", "PASS");
        return new ObjectNode[] {taskRequest, taskReport};
    }

    private static Outcome validateBinding(ObjectNode request, ObjectNode handoff, ObjectNode[] task) {
        return run("recovery-strategy-selector", "validate-task-binding", "input", TestJson.object("request", request, "handoff", handoff, "taskContractRequest", task[0], "taskContractReport", task[1]));
    }

    @Test
    void aNewTaskBindsToTheHandoffAndKeepsTheOriginalLimits() {
        ObjectNode request = recoveryRequest();
        ObjectNode handoff = recoveryHandoff(request, List.of(recoveryStrategy("safe"), rejectedStrategy()), "safe", "SELECTED");
        List<String> allowed = List.of("local-edit", "rollback");
        ObjectNode[] task = recoveryTask(handoff, allowed, List.of("schema-migration"));
        Outcome bound = validateBinding(request, handoff, task);
        assertEquals(0, bound.exitCode(), () -> bound.body().toString());
        assertTrue(value(bound).get("valid").booleanValue(), () -> bound.body().toString());

        ObjectNode droppedProhibition = copy(task[1]);
        ((ObjectNode) droppedProhibition.get("taskEnvelope").get("authorization")).set("prohibitedActions", TestJson.array(List.of()));
        handoffInvalid(validateBinding(request, handoff, new ObjectNode[] {task[0], droppedProhibition}));

        ObjectNode otherId = copy(task[1]);
        ((ObjectNode) otherId.get("taskEnvelope")).set("workUnits", TestJson.array(List.of(TestJson.object("id", "new-recovery", "objective", "Something else.", "dependencies", TestJson.array(List.of()), "writeTargets", TestJson.array(List.of("src/other.ts"))))));
        handoffInvalid(validateBinding(request, handoff, new ObjectNode[] {task[0], otherId}));
    }

    @Test
    void anApprovalRequiredStrategyBindsOnlyToATaskThatHoldsTheApproval() {
        ObjectNode request = recoveryRequest();
        ObjectNode handoff = recoveryHandoff(request, List.of(migrationStrategy(), rejectedStrategy()), "migration", "NEEDS_APPROVAL");
        handoffValid(validateHandoff(request, handoff, TestJson.hashJson(request)));
        assertEquals(List.of("schema-migration"), TestJson.strings(handoff.get("approvalRequired")));
        handoffInvalid(validateBinding(request, handoff, recoveryTask(handoff, List.of("local-edit", "rollback"), List.of("schema-migration"))));
        Outcome approved = validateBinding(request, handoff, recoveryTask(handoff, List.of("local-edit", "rollback", "schema-migration"), List.of()));
        assertEquals(0, approved.exitCode(), () -> approved.body().toString());
        assertTrue(value(approved).get("valid").booleanValue(), () -> approved.body().toString());
    }

    @Test
    void theRemovedSupportGuardCommandIsNotAvailable() {
        refused(run("coordinate-subagents", "support-guard", "input", TestJson.object("advice", TestJson.object())), "INVALID_INPUT");
    }
}
