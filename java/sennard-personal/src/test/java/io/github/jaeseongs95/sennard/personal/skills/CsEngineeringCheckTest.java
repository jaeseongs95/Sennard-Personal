// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * cs-engineering through {@link SkillCheckService}, the MCP tool's own entry, with the real file boundary and the repository's frozen example
 * ({@code skills/cs-engineering/assets/examples/claim-queue}). One normal path for each command, and for each rule a document that breaks only that rule.
 *
 * <p>The example as committed passes {@code check-bundle} and the stage check as it is ({@link #theCommittedExampleBundleHoldsAsItIsFrozen}). A case that changes a file
 * or a document of the chain runs on a copy whose digests are frozen again ({@link #refreeze}), so that it breaks only the rule it names.
 */
class CsEngineeringCheckTest {
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final Path EXAMPLE = REPO.resolve("skills/cs-engineering/assets/examples/claim-queue");
    private static final String KEY = "skillTools.csEngineering.";
    private static final String ZERO = "sha256:" + "0".repeat(64);
    private static final String INVALID = "INVALID_INPUT";
    private static final String INTEGRITY = "INTEGRITY_FAILED";

    private static AnnotationConfigApplicationContext context;
    private static SkillCheckService service;
    private static JsonNode request;
    private static JsonNode constraints;
    private static JsonNode review;
    private static JsonNode candidate;
    /** A rule card of a draft module, as the {@code rules} command answers it. */
    private static JsonNode draftRule;

    @BeforeAll
    static void wire() throws IOException {
        context = SkillCheckContext.open();
        service = context.getBean(SkillCheckService.class);
        request = read(EXAMPLE.resolve("request.json"));
        constraints = read(EXAMPLE.resolve("constraints.json"));
        review = read(EXAMPLE.resolve("review.json"));
        candidate = read(EXAMPLE.resolve("candidate.json"));
        draftRule = value(run("rules", "domain", TestJson.array(List.of("language-semantics")), "allow-draft", true), 0).get("rules").get(0);
    }

    @AfterAll
    static void close() {
        context.close();
        // The class's statics outlive it in the test JVM: let go of the context's beans (the schema registry among them) and the documents.
        context = null;
        service = null;
        request = null;
        constraints = null;
        review = null;
        candidate = null;
        draftRule = null;
    }

    // ------------------------------------------------------------------------------------------------------------------------------ helpers

    private static Outcome run(String command, Object... pairs) {
        return service.run("cs-engineering", command, TestJson.object(pairs));
    }

    private static JsonNode value(Outcome outcome, int exitCode) {
        assertEquals(exitCode, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode value = outcome.body().get("value");
        assertNotNull(value, () -> "a refused call: " + outcome.body());
        return value;
    }

    private static JsonNode read(Path file) throws IOException {
        return TestJson.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static ObjectNode object(Path dir, String name) throws IOException {
        return (ObjectNode) read(dir.resolve(name));
    }

    private static void write(Path dir, String name, JsonNode document) throws IOException {
        Files.writeString(dir.resolve(name), TestJson.MAPPER.writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
    }

    private static void edit(Path dir, String name, Consumer<ObjectNode> change) throws IOException {
        ObjectNode document = object(dir, name);
        change.accept(document);
        write(dir, name, document);
    }

    private static String rawDigest(Path dir, String relative) throws IOException {
        return TestJson.hashBytes(Files.readAllBytes(dir.resolve(relative)));
    }

    private static ObjectNode copyOf(JsonNode document) {
        return (ObjectNode) document.deepCopy();
    }

    private static ObjectNode at(JsonNode document, String member, int index) {
        return (ObjectNode) document.get(member).get(index);
    }

    private static ArrayNode list(JsonNode document, String member) {
        return (ArrayNode) document.get(member);
    }

    private static ArrayNode texts(String... values) {
        return TestJson.array(List.of(values));
    }

    private static List<String> strings(JsonNode array) {
        return TestJson.strings(array);
    }

    /** A refused call: the exit code 2, the code, and the catalog key of the cs-engineering sentence (and the label it names when the sentence has one). */
    private static void assertRefused(String what, Outcome outcome, String code, String key, String label) {
        assertEquals(2, outcome.exitCode(), () -> what + ": " + outcome.body());
        JsonNode body = outcome.body();
        assertEquals("ERROR", body.path("status").stringValue(), () -> what + ": " + body);
        assertEquals(code, body.path("code").stringValue(), () -> what + ": " + body);
        assertEquals(KEY + key, body.path("messageKey").stringValue(), () -> what + ": " + body);
        assertNotEquals(KEY + key, body.path("message").stringValue(), () -> what + ": the sentence is made from the catalog, not the key: " + body);
        if (label != null) {
            assertEquals(label, body.path("params").path("label").stringValue(), () -> what + ": " + body);
        }
    }

    /** One document that breaks one rule, and what the check must answer. */
    private record Refusal(String name, Consumer<ObjectNode> change, String code, String key, String label) {
        static Refusal invalid(String name, String key, String label, Consumer<ObjectNode> change) {
            return new Refusal(name, change, INVALID, key, label);
        }

        static Refusal integrity(String name, String key, String label, Consumer<ObjectNode> change) {
            return new Refusal(name, change, INTEGRITY, key, label);
        }
    }

    private static void judge(List<Refusal> refusals, JsonNode original, Function<ObjectNode, Outcome> ask) {
        for (Refusal refusal : refusals) {
            ObjectNode changed = copyOf(original);
            refusal.change().accept(changed);
            assertRefused(refusal.name(), ask.apply(changed), refusal.code(), refusal.key(), refusal.label());
        }
    }

    private static ObjectNode assumption(String id) {
        return TestJson.object("id", id, "statement", "An assumption the report rests on.", "verificationPlan", "Observe it.");
    }

    private static ObjectNode draftSelection() {
        return TestJson.object("ruleId", draftRule.get("id").stringValue(), "ruleDigest", TestJson.hashJson(draftRule), "applicabilityReason", "Applies to the example.");
    }

    // --------------------------------------------------------------------------------------------------------------------------- the frozen copy

    private static void copyTree(Path from, Path to) throws IOException {
        CsFrozenExample.copyTree(from, to);
    }

    /** A copy of the example, frozen again. */
    private static Path frozen(Path where) throws IOException {
        return CsFrozenExample.frozen(where);
    }

    /** Writes every digest a directory's documents hold again; the rules are in {@link CsFrozenExample#refreeze}. */
    private static void refreeze(Path dir) throws IOException {
        CsFrozenExample.refreeze(dir);
    }

    private static Outcome checkBundle(Path dir) {
        return run("check-bundle", "root", dir.toString(), "binding", "binding.json", "task", "task.json", "policy", "policy.json", "review", "review.json", "candidate", "candidate.json");
    }

    private static Outcome handoff(Path dir) {
        return run("handoff", "root", dir.toString(), "binding", "binding.json", "task", "task.json", "policy", "policy.json");
    }

    private static Outcome checkStageBundle(Path dir, String bundleDigest, String taskDigest) {
        return run("check-stage-bundle", "root", dir.toString(), "input", "stage-bundle.json", "bundle-digest", bundleDigest, "task-digest", taskDigest);
    }

    private static Outcome checkStageBundle(Path dir) throws IOException {
        return checkStageBundle(dir, rawDigest(dir, "stage-bundle.json"), TestJson.hashJson(object(dir, "task.json")));
    }

    /** The review a failing verification makes: OB-CLAIM failed and F-CLAIM says so. */
    private static void failTheReview(ObjectNode document) {
        at(document, "verificationResults", 0).put("result", "FAIL");
        at(document, "findings", 0).put("status", "VIOLATED");
        document.set("blockingFindingIds", texts("F-CLAIM"));
        document.put("verdict", "FAIL");
    }

    /** A review no required obligation of which ran: F-CLAIM is left unverified. */
    private static void blockTheReview(ObjectNode document) {
        ObjectNode result = at(document, "verificationResults", 0);
        result.put("executionStatus", "NOT_RUN");
        result.put("result", "UNKNOWN");
        result.set("evidenceRefs", texts());
        ObjectNode finding = at(document, "findings", 0);
        finding.put("status", "UNVERIFIED");
        finding.set("evidenceRefs", texts());
        document.set("unverifiedRequiredObligationIds", texts("OB-CLAIM"));
        document.put("verdict", "BLOCKED");
    }

    // ----------------------------------------------------------------------------------------------------------------------- pack and catalog
    // old: csEngineeringPackIsIntactAndItsBundleExamplePasses (the self-check half)

    @Test
    void theSelfCheckAnswersForThePackedKnowledgeAndItsDigestIsThatOfTheLock() throws IOException {
        JsonNode self = value(run("self-check"), 0);
        assertEquals("PASS", self.get("status").stringValue());
        assertEquals("0.1.0", self.get("version").stringValue());
        assertEquals("networknt-2020-12", self.get("schemaEngine").stringValue());
        assertEquals(TestJson.hashJson(read(REPO.resolve("skills/cs-engineering/assets/knowledge-lock.json"))), self.get("packDigest").stringValue());
        int rules = self.get("rules").intValue();
        assertTrue(rules > 0);
        assertTrue(self.get("modules").intValue() > 0);
        JsonNode everything = value(run("catalog", "allow-draft", true), 0);
        assertEquals(rules, everything.get("rules").size());
        JsonNode validated = value(run("catalog"), 0);
        assertEquals(self.get("validatedRules").intValue(), validated.get("rules").size());
        assertTrue(validated.get("rules").size() < rules, "the pack holds draft rules");
        for (JsonNode entry : validated.get("rules")) {
            assertEquals("validated", entry.get("status").stringValue());
        }
        assertEquals(self.get("packDigest").stringValue(), validated.get("packDigest").stringValue());
        assertFalse(validated.get("notice").stringValue().isEmpty());
    }

    @Test
    void rulesAreSelectedByDomainAndByRuleAndDraftsNeedAnExplicitRequest() {
        JsonNode concurrency = value(run("rules", "domain", texts("concurrency")), 0).get("rules");
        assertTrue(concurrency.size() > 0);
        for (JsonNode rule : concurrency) {
            assertEquals("concurrency", rule.get("domain").stringValue());
        }
        JsonNode one = value(run("rules", "rule", texts("CONC-CLAIM-001")), 0).get("rules");
        assertEquals(1, one.size());
        // The answered card is the frozen one: the digest the example's constraint report holds for this rule is the digest of the card as it is answered.
        assertEquals("CONC-CLAIM-001", constraints.get("selectedRules").get(0).get("ruleId").stringValue());
        assertEquals(constraints.get("selectedRules").get(0).get("ruleDigest").stringValue(), TestJson.hashJson(one.get(0)));
        assertEquals(0, value(run("rules", "domain", texts("language-semantics")), 0).get("rules").size());
        assertEquals("draft", draftRule.get("status").stringValue());
        assertRefused("unknown domain", run("rules", "domain", texts("no-such-domain")), INVALID, "unknownDomain", null);
        assertRefused("unknown rule", run("rules", "rule", texts("NO-SUCH-RULE")), INVALID, "unknownRule", null);
    }

    @Test
    void helpListsEveryCommandSorted() {
        JsonNode help = value(run("help"), 0);
        assertEquals(List.of("catalog", "check-bundle", "check-stage-bundle", "handoff", "help", "provider-result", "rules", "self-check", "validate-constraints", "validate-request", "validate-review"),
                strings(help.get("commands")));
        assertFalse(help.get("notes").stringValue().isEmpty());
    }

    // ------------------------------------------------------------------------------------------------------------------------ the documents

    @Test
    void aDocumentIsTheValueItselfAFileOrAPathUnderTheRoot() {
        assertEquals("VALID", value(run("validate-request", "input", request), 0).get("status").stringValue());
        assertEquals("VALID", value(run("validate-request", "input", TestJson.object("file", EXAMPLE.resolve("request.json").toString())), 0).get("status").stringValue());
        assertEquals("VALID", value(run("validate-request", "root", EXAMPLE.toString(), "input", "request.json"), 0).get("status").stringValue());
        assertEquals(2, run("validate-request", "input", "request.json").exitCode(), "a path under a root needs the root");
        assertEquals(INVALID, run("validate-request", "root", EXAMPLE.toString(), "input", "../request.json").body().path("code").stringValue());
        assertEquals("NOT_FOUND", run("validate-request", "root", EXAMPLE.toString(), "input", "no-such-file.json").body().path("code").stringValue());
    }

    // ------------------------------------------------------------------------------------------------------------------------ validate-request

    private static Outcome validateRequest(ObjectNode document) {
        return run("validate-request", "input", document);
    }

    @Test
    void theExampleRequestIsValid() {
        JsonNode valid = value(run("validate-request", "input", request), 0);
        assertEquals("VALID", valid.get("status").stringValue());
        assertEquals("networknt-2020-12", valid.get("schemaEngine").stringValue());
        assertFalse(valid.get("scope").stringValue().isEmpty());
    }

    @Test
    void eachRuleOfARequestRefusesADocumentThatBreaksIt() {
        List<Refusal> refusals = List.of(
                Refusal.invalid("a member the contract requires is missing", "schemaValidationFailed", null, document -> document.remove("requestId")),
                Refusal.invalid("a repeated source id", "duplicate", "sourceReference", document -> at(document, "sourceRefs", 1).put("id", "SRC-REQUEST")),
                Refusal.invalid("a repeated source path", "duplicate", "sourcePath", document -> at(document, "sourceRefs", 1).put("path", "source/request.txt")),
                Refusal.invalid("a repeated fact id", "duplicate", "operationalFact", document -> at(document, "operationalFacts", 1).put("id", "FACT-CLAIM")),
                Refusal.invalid("a parent directory in a path", "unsafePathComponent", null, document -> at(document, "sourceRefs", 0).put("path", "../escape.txt")),
                Refusal.invalid("an absolute path", "forbiddenPathForm", null, document -> at(document, "sourceRefs", 0).put("path", "/etc/passwd")),
                Refusal.invalid("a drive path", "forbiddenPathForm", null, document -> at(document, "sourceRefs", 0).put("path", "C:/escape.txt")),
                Refusal.invalid("a backslash", "forbiddenPathForm", null, document -> at(document, "sourceRefs", 0).put("path", "a\\b")),
                Refusal.invalid("a control character", "forbiddenPathForm", null, document -> at(document, "sourceRefs", 0).put("path", "a\u0001b")),
                Refusal.invalid("two line terminators in a path", "forbiddenPathForm", null, document -> at(document, "sourceRefs", 0).put("path", "a\n\nb")),
                Refusal.invalid("a reserved file name", "unsafePathComponent", null, document -> at(document, "sourceRefs", 0).put("path", "nul.txt")),
                Refusal.invalid("a component that ends with a dot", "unsafePathComponent", null, document -> at(document, "sourceRefs", 0).put("path", "a./b")),
                Refusal.invalid("an empty component", "unsafePathComponent", null, document -> at(document, "sourceRefs", 0).put("path", "a//b")),
                Refusal.invalid("a fact whose evidence is no source", "unknownReference", "factEvidence", document -> at(document, "operationalFacts", 0).set("evidenceRefs", texts("SRC-NOPE"))),
                Refusal.integrity("a snapshot digest that is not the digest of the sources", "digestMismatch", "sourceSnapshot", document -> document.put("sourceSnapshotDigest", ZERO)));
        judge(refusals, request, CsEngineeringCheckTest::validateRequest);
    }

    @Test
    void aRefusedSchemaCarriesTheViolationsAsDetails() {
        ObjectNode broken = copyOf(request);
        broken.remove("requestId");
        Outcome outcome = validateRequest(broken);
        assertEquals("cs-request", outcome.body().path("params").path("name").stringValue());
        assertTrue(outcome.body().path("details").path("errors").size() > 0, () -> outcome.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------------- validate-constraints
    // old: csEngineeringRefusesATamperedKnowledgeDigestAndADraftRule

    private static Outcome validateConstraints(ObjectNode document) {
        return run("validate-constraints", "input", document, "request", request);
    }

    @Test
    void theExampleConstraintReportIsValidAndNamesItsRequiredObligations() {
        JsonNode valid = value(run("validate-constraints", "input", constraints, "request", request), 0);
        assertEquals("VALID", valid.get("status").stringValue());
        assertEquals("READY", valid.get("verdict").stringValue());
        assertEquals(List.of("OB-CLAIM", "OB-FENCE"), strings(valid.get("requiredObligationIds")));
    }

    @Test
    void aVerdictThatIsNotReadyAnswersWithExitCodeOneAndStaysAValidReport() {
        for (String verdict : List.of("BLOCKED", "NEEDS_INPUT", "NEEDS_REDESIGN")) {
            ObjectNode changed = copyOf(constraints);
            changed.put("verdict", verdict);
            JsonNode valid = value(validateConstraints(changed), 1);
            assertEquals("VALID", valid.get("status").stringValue(), verdict);
            assertEquals(verdict, valid.get("verdict").stringValue());
        }
    }

    @Test
    void aDraftRuleIsAcceptedOnlyWithAStatedBasisForUsingDrafts() {
        ObjectNode withDraft = copyOf(constraints);
        list(withDraft, "selectedRules").add(draftSelection());
        assertRefused("a draft rule without a basis", validateConstraints(withDraft), INVALID, "draftNeedsBasis", null);
        ((ObjectNode) withDraft.get("draftUse")).put("allowed", true);
        assertEquals("VALID", value(validateConstraints(withDraft), 0).get("status").stringValue());
    }

    @Test
    void eachRuleOfAConstraintReportRefusesADocumentThatBreaksIt() {
        List<Refusal> refusals = List.of(
                Refusal.invalid("a member the contract requires is missing", "schemaValidationFailed", null, document -> document.remove("verdict")),
                Refusal.integrity("a knowledge pack digest that is not the pack's", "digestMismatch", "knowledgePack", document -> document.put("knowledgePackDigest", ZERO)),
                Refusal.integrity("a knowledge version that is not the pack's", "knowledgeVersionMismatch", null, document -> document.put("knowledgePackVersion", "9.9.9")),
                Refusal.integrity("a selected rule digest that is not the rule's", "digestMismatch", "selectedRule", document -> at(document, "selectedRules", 0).put("ruleDigest", ZERO)),
                Refusal.invalid("a selected rule the pack does not have", "selectedRuleMissing", null, document -> at(document, "selectedRules", 0).put("ruleId", "NO-SUCH-RULE")),
                Refusal.invalid("a rule selected twice", "duplicate", "selectedRule", document -> at(document, "selectedRules", 1).put("ruleId", "CONC-CLAIM-001")),
                Refusal.invalid("a fact id repeated", "duplicate", "fact", document -> at(document, "facts", 1).put("id", "FACT-CLAIM")),
                Refusal.invalid("a fact and an assumption with one id", "factAssumptionCollide", null, document -> list(document, "assumptions").add(assumption("FACT-CLAIM"))),
                Refusal.invalid("an invariant id repeated", "duplicate", "invariant", document -> at(document, "invariants", 1).put("id", "INV-CLAIM")),
                Refusal.invalid("an obligation id repeated", "duplicate", "obligation", document -> at(document, "verificationObligations", 1).put("id", "OB-CLAIM")),
                Refusal.invalid("a design choice repeated", "duplicate", "designChoice", document -> list(document, "designChoices").add(copyOf(at(document, "designChoices", 0)))),
                Refusal.invalid("an invariant whose basis is unknown", "unknownReference", "invariantBasis", document -> at(document, "invariants", 0).set("basisRefs", texts("NOPE"))),
                Refusal.invalid("an invariant whose rule was not selected", "unknownReference", "selectedInvariantRule", document -> at(document, "invariants", 0).set("ruleIds", texts("DB-BOUNDARY-001"))),
                Refusal.invalid("a required invariant resting on an assumption alone", "requiredInvariantNeedsFact", null, document -> {
                    list(document, "assumptions").add(assumption("ASM-1"));
                    at(document, "invariants", 0).set("basisRefs", texts("ASM-1"));
                }),
                Refusal.invalid("a design choice whose basis is unknown", "unknownReference", "choiceBasis", document -> at(document, "designChoices", 0).set("basisRefs", texts("NOPE"))),
                Refusal.invalid("an obligation of an unknown invariant", "unknownReference", "obligationInvariant", document -> at(document, "verificationObligations", 0).set("invariantIds", texts("INV-NOPE"))),
                Refusal.invalid("an advisory obligation of a required invariant", "obligationSeverityMismatch", null, document -> at(document, "verificationObligations", 0).put("requirementLevel", "advisory")),
                Refusal.invalid("a required invariant no required obligation verifies", "requiredInvariantNoObligation", null,
                        document -> at(document, "verificationObligations", 1).set("invariantIds", texts("INV-CLAIM"))),
                Refusal.invalid("a READY report of an applicable request with nothing selected", "applicableReadyIncomplete", null, document -> {
                    document.set("selectedRules", TestJson.array(List.of()));
                    document.set("invariants", TestJson.array(List.of()));
                    document.set("verificationObligations", TestJson.array(List.of()));
                }),
                Refusal.invalid("a report that is not applicable yet holds constraints", "notApplicableHasConstraints", null, document -> document.put("applicability", "not-applicable")),
                Refusal.integrity("a source snapshot digest that is not the request's", "digestMismatch", "sourceSnapshot", document -> document.put("sourceSnapshotDigest", ZERO)),
                Refusal.invalid("a fact whose evidence is not one of the request's sources", "unknownReference", "factEvidence", document -> at(document, "facts", 0).set("evidenceRefs", texts("SRC-NOPE"))));
        judge(refusals, constraints, CsEngineeringCheckTest::validateConstraints);
    }

    @Test
    void aTamperedKnowledgeDigestIsAnIntegrityFailureNamingTheKnowledgePack() {
        ObjectNode tampered = copyOf(constraints);
        tampered.put("knowledgePackDigest", ZERO);
        Outcome outcome = run("validate-constraints", "input", tampered, "request", request);
        assertRefused("tampered knowledge digest", outcome, INTEGRITY, "digestMismatch", "knowledgePack");
        assertEquals("Knowledge pack mismatch.", outcome.body().path("message").stringValue());
    }

    @Test
    void aReportBoundToAnotherRequestIsRefused() {
        ObjectNode other = copyOf(request);
        other.put("objective", "A different objective for the same sources.");
        assertRefused("another request", run("validate-constraints", "input", constraints, "request", other), INTEGRITY, "digestMismatch", "request");
    }

    // ---------------------------------------------------------------------------------------------------------------------- validate-review

    private static Outcome validateReview(ObjectNode document) {
        return run("validate-review", "input", document, "constraint", constraints, "candidate", candidate);
    }

    @Test
    void theExampleReviewIsValidAndItsVerdictIsRecomputedFromTheObligations() {
        JsonNode valid = value(run("validate-review", "input", review, "constraint", constraints, "candidate", candidate), 0);
        assertEquals("VALID", valid.get("status").stringValue());
        assertEquals("PASS", valid.get("verdict").stringValue());
        assertEquals(0, valid.get("blockingFindingIds").size());
        assertEquals(0, valid.get("unverifiedRequiredObligationIds").size());
        assertEquals(2, valid.get("requiredCount").intValue());
        assertEquals("networknt-2020-12", valid.get("schemaEngine").stringValue());
    }

    @Test
    void aFailingVerificationIsAFailVerdictAndABlockedOneIsBlocked() {
        ObjectNode failing = copyOf(review);
        failTheReview(failing);
        JsonNode failed = value(validateReview(failing), 1);
        assertEquals("FAIL", failed.get("verdict").stringValue());
        assertEquals(List.of("F-CLAIM"), strings(failed.get("blockingFindingIds")));

        ObjectNode blocked = copyOf(review);
        blockTheReview(blocked);
        JsonNode notRun = value(validateReview(blocked), 1);
        assertEquals("BLOCKED", notRun.get("verdict").stringValue());
        assertEquals(List.of("OB-CLAIM"), strings(notRun.get("unverifiedRequiredObligationIds")));
    }

    @Test
    void eachRuleOfAReviewReportRefusesADocumentThatBreaksIt() {
        List<Refusal> refusals = List.of(
                Refusal.invalid("a member the contract requires is missing", "schemaValidationFailed", null, document -> document.remove("verdict")),
                Refusal.integrity("a constraint report digest that is not the report's", "digestMismatch", "reviewConstraint", document -> document.put("constraintReportDigest", ZERO)),
                Refusal.integrity("a candidate digest that is not the candidate's", "digestMismatch", "reviewCandidate", document -> document.put("candidateDigest", ZERO)),
                Refusal.invalid("a repeated evidence id", "duplicate", "evidence", document -> list(document, "evidence").add(copyOf(at(document, "evidence", 0)))),
                Refusal.invalid("a repeated finding id", "duplicate", "finding", document -> at(document, "findings", 1).put("id", "F-CLAIM")),
                Refusal.invalid("an invariant reviewed twice", "duplicate", "reviewedInvariant", document -> at(document, "findings", 1).put("invariantId", "INV-CLAIM")),
                Refusal.invalid("an obligation with two results", "duplicate", "verificationResult", document -> at(document, "verificationResults", 1).put("obligationId", "OB-CLAIM")),
                Refusal.invalid("an invariant not reviewed", "invariantReviewCoverage", null, document -> list(document, "findings").remove(1)),
                Refusal.invalid("an obligation without a result", "obligationResultCoverage", null, document -> list(document, "verificationResults").remove(1)),
                Refusal.invalid("evidence with an unsafe path", "unsafePathComponent", null, document -> at(document, "evidence", 0).put("path", "../outside.json")),
                Refusal.integrity("evidence about another candidate", "digestMismatch", "evidenceCandidate", document -> at(document, "evidence", 0).put("targetDigest", ZERO)),
                Refusal.integrity("a result about another candidate", "digestMismatch", "resultCandidate", document -> at(document, "verificationResults", 0).put("targetDigest", ZERO)),
                Refusal.invalid("a verification method other than the obligation's", "verificationMethodChanged", null, document -> at(document, "verificationResults", 0).put("method", "static-review")),
                Refusal.invalid("a result whose evidence is unknown", "unknownReference", "resultEvidence", document -> at(document, "verificationResults", 0).set("evidenceRefs", texts("E-NOPE"))),
                Refusal.invalid("an executed result without an observed outcome", "executedNeedsEvidence", null, document -> at(document, "verificationResults", 0).put("result", "UNKNOWN")),
                Refusal.invalid("an environment other than the plan's", "environmentMismatch", null, document -> at(document, "verificationResults", 0).put("environmentRef", "other-environment")),
                Refusal.invalid("evidence of another kind than the result's method", "evidenceKindMismatch", null, document -> at(document, "evidence", 0).put("kind", "static-review")),
                Refusal.invalid("a result that was not run yet claims a pass", "nonExecutedClaims", null, document -> at(document, "verificationResults", 0).put("executionStatus", "NOT_RUN")),
                Refusal.invalid("a finding whose evidence is unknown", "unknownReference", "findingEvidence", document -> at(document, "findings", 0).set("evidenceRefs", texts("E-NOPE"))),
                Refusal.invalid("a finding whose evidence is not its verification's", "findingEvidenceUnconnected", null, document -> {
                    ObjectNode other = copyOf(at(document, "evidence", 0));
                    other.put("id", "E-OTHER");
                    list(document, "evidence").add(other);
                    at(document, "findings", 0).set("evidenceRefs", texts("E-OTHER"));
                }),
                Refusal.invalid("a conclusive finding without evidence", "conclusiveNeedsEvidence", null, document -> at(document, "findings", 0).set("evidenceRefs", texts())),
                Refusal.invalid("a satisfied finding over a failed verification", "satisfiedIncomplete", null, document -> at(document, "verificationResults", 0).put("result", "FAIL")),
                Refusal.invalid("a violated finding over a passing verification", "violatedNoFail", null, document -> at(document, "findings", 0).put("status", "VIOLATED")),
                Refusal.invalid("a failed required verification hidden as unverified", "failedVerificationHidden", null, document -> {
                    at(document, "verificationResults", 0).put("result", "FAIL");
                    ObjectNode finding = at(document, "findings", 0);
                    finding.put("status", "UNVERIFIED");
                    finding.set("evidenceRefs", texts());
                }),
                Refusal.invalid("a verdict that is not the recomputed one", "reviewVerdictMismatch", null, document -> document.put("verdict", "FAIL")),
                Refusal.invalid("a blocking list that is not the findings'", "blockingListInaccurate", null, document -> document.set("blockingFindingIds", texts("F-CLAIM"))),
                Refusal.invalid("an unverified list that is not the results'", "unverifiedListInaccurate", null, document -> document.set("unverifiedRequiredObligationIds", texts("OB-CLAIM"))));
        judge(refusals, review, CsEngineeringCheckTest::validateReview);
    }

    @Test
    void aVerdictThatDiffersFromTheRecomputedOneNamesTheExpectedOne() {
        ObjectNode changed = copyOf(review);
        changed.put("verdict", "FAIL");
        Outcome outcome = validateReview(changed);
        assertEquals("PASS", outcome.body().path("params").path("expected").stringValue());
        assertEquals("Review verdict must be PASS.", outcome.body().path("message").stringValue());
    }

    @Test
    void aReviewNeedsAReadyConstraintReport() {
        ObjectNode notReady = copyOf(constraints);
        notReady.put("verdict", "BLOCKED");
        assertRefused("a constraint report that is not ready", run("validate-review", "input", review, "constraint", notReady, "candidate", candidate), INVALID, "reviewNeedsReady", null);
    }

    @Test
    void eachRuleOfACandidateRefusesADocumentThatBreaksIt() {
        String digest = "sha256:" + "1".repeat(64);
        List<Refusal> refusals = List.of(
                Refusal.invalid("a member the contract requires is missing", "schemaValidationFailed", null, document -> document.remove("files")),
                Refusal.invalid("files out of order", "candidatePathsUnsorted", null,
                        document -> document.set("files", TestJson.array(List.of(TestJson.object("path", "b.txt", "digest", digest), TestJson.object("path", "a.txt", "digest", digest))))),
                Refusal.invalid("a file listed twice", "duplicate", "candidateFile",
                        document -> document.set("files", TestJson.array(List.of(TestJson.object("path", "a.txt", "digest", digest), TestJson.object("path", "a.txt", "digest", digest))))),
                Refusal.invalid("a path outside the root", "unsafePathComponent", null,
                        document -> document.set("files", TestJson.array(List.of(TestJson.object("path", "../a.txt", "digest", digest))))));
        judge(refusals, candidate, document -> run("validate-review", "input", review, "constraint", constraints, "candidate", document));
    }

    // ------------------------------------------------------------------------------------------------------------------------- provider-result

    @Test
    void aProviderResultCarriesTheValidatedReportAndAnErrorOnlyWhenTheVerdictIsNotGood() {
        JsonNode derived = value(run("provider-result", "mode", "derive", "input", constraints, "request", request), 0);
        assertEquals("1.0.0", derived.get("schemaVersion").stringValue());
        assertEquals("output", derived.get("kind").stringValue());
        assertEquals(TestJson.hashJson(constraints), TestJson.hashJson(derived.get("output")), "the report is handed back as it came");
        assertEquals(0, derived.get("artifacts").size());
        assertTrue(derived.has("error") && derived.get("error").isNull(), () -> derived.toString());

        JsonNode reviewed = value(run("provider-result", "mode", "review", "input", review, "constraint", constraints, "candidate", candidate), 0);
        assertEquals(TestJson.hashJson(review), TestJson.hashJson(reviewed.get("output")));
        assertTrue(reviewed.get("error").isNull());

        ObjectNode blocked = copyOf(constraints);
        blocked.put("verdict", "BLOCKED");
        JsonNode missing = value(run("provider-result", "mode", "derive", "input", blocked, "request", request), 1);
        assertEquals("MISSING_EVIDENCE", missing.get("error").get("code").stringValue());
        assertFalse(missing.get("error").get("message").stringValue().isEmpty());
        assertTrue(missing.get("error").has("details") && missing.get("error").get("details").isNull());

        ObjectNode redesign = copyOf(constraints);
        redesign.put("verdict", "NEEDS_REDESIGN");
        JsonNode violated = value(run("provider-result", "mode", "derive", "input", redesign, "request", request), 1);
        assertEquals("GATE_FAILED", violated.get("error").get("code").stringValue());
        assertEquals("CS conditions are violated.", violated.get("error").get("message").stringValue());

        ObjectNode failing = copyOf(review);
        failTheReview(failing);
        JsonNode failedReview = value(run("provider-result", "mode", "review", "input", failing, "constraint", constraints, "candidate", candidate), 1);
        assertEquals("GATE_FAILED", failedReview.get("error").get("code").stringValue());
    }

    @Test
    void aProviderResultNeedsTheDeriveOrReviewMode() {
        assertRefused("another mode", run("provider-result", "mode", "other", "input", constraints, "request", request), INVALID, "invalidMode", null);
        assertRefused("no mode", run("provider-result", "input", constraints, "request", request), INVALID, "invalidMode", null);
    }

    @Test
    void aProviderReportThatBreaksARuleIsRefusedAsTheValidatorsAre() {
        ObjectNode tampered = copyOf(constraints);
        tampered.put("knowledgePackDigest", ZERO);
        assertRefused("a derived report with another knowledge pack", run("provider-result", "mode", "derive", "input", tampered, "request", request), INTEGRITY, "digestMismatch", "knowledgePack");
        ObjectNode changed = copyOf(review);
        changed.put("verdict", "FAIL");
        assertRefused("a review with another verdict", run("provider-result", "mode", "review", "input", changed, "constraint", constraints, "candidate", candidate), INVALID, "reviewVerdictMismatch", null);
    }

    // ----------------------------------------------------------------------------------------------------------------------------- handoff
    // old: csEngineeringHandoffAndStageBundleVerifyTheFrozenChainAndRefuseOneWrongDigest (the handoff half)

    @Test
    void theExampleHandoffVerifiesTheFrozenChainAndNamesWhatItHands() throws IOException {
        JsonNode answer = value(handoff(EXAMPLE), 0);
        assertEquals("cs-handoff", answer.get("kind").stringValue());
        assertEquals("1.0.0", answer.get("schemaVersion").stringValue());
        JsonNode binding = read(EXAMPLE.resolve("binding.json"));
        assertEquals(binding.get("taskDigest").stringValue(), answer.get("taskDigest").stringValue());
        assertEquals(TestJson.hashJson(binding), answer.get("bindingDigest").stringValue());
        assertEquals(binding.get("constraintReportDigest").stringValue(), answer.get("constraintReportDigest").stringValue());
        assertEquals("constraints.json", answer.get("constraintReportRef").get("path").stringValue());
        assertEquals(binding.get("policyDigest").stringValue(), answer.get("policyDigest").stringValue());
        assertEquals(value(run("self-check"), 0).get("packDigest").stringValue(), answer.get("knowledgePackDigest").stringValue());
        assertEquals(List.of("OB-CLAIM", "OB-FENCE"), strings(answer.get("requiredObligationIds")));
        assertFalse(answer.get("assurance").stringValue().isEmpty());
    }

    @Test
    void oneWrongDigestInTheBindingIsRefusedByTheHandoffNamingWhatItBoundTo(@TempDir Path temp) throws IOException {
        Path copy = temp.resolve("example");
        copyTree(EXAMPLE, copy);
        Path binding = copy.resolve("binding.json");
        String bound = read(binding).get("knowledgePackDigest").stringValue();
        Files.writeString(binding, Files.readString(binding, StandardCharsets.UTF_8).replace(bound, ZERO), StandardCharsets.UTF_8);
        Outcome refused = handoff(copy);
        assertRefused("knowledge digest of the binding", refused, INTEGRITY, "digestMismatch", "boundKnowledge");
        assertEquals("Bound knowledge mismatch.", refused.body().path("message").stringValue());
    }

    // ----------------------------------------------------------------------------------------------------------------------------- bundles
    // old: csEngineeringPackIsIntactAndItsBundleExamplePasses (the bundle half), SkillBackedStageGatesTest.csCase

    @Test
    void aFrozenBundleThePackPassesIsCheckedFileByFile(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("bundle"));
        JsonNode answer = value(checkBundle(dir), 0);
        assertEquals("cs-bundle-check", answer.get("kind").stringValue());
        assertEquals("1.0.0", answer.get("schemaVersion").stringValue());
        assertEquals("PASS", answer.get("verdict").stringValue());
        assertTrue(answer.get("canProceedUnderSuppliedPolicy").booleanValue());
        assertEquals("enforce", answer.get("mode").stringValue());
        assertEquals("networknt-2020-12", answer.get("schemaEngine").stringValue());
        assertEquals(value(run("self-check"), 0).get("packDigest").stringValue(), answer.get("knowledgePackDigest").stringValue());
        assertEquals(TestJson.hashJson(object(dir, "candidate.json")), answer.get("candidateDigest").stringValue());
        assertEquals(TestJson.hashJson(object(dir, "constraints.json")), answer.get("constraintReportDigest").stringValue());
        // The binding's two files, the request's two sources, the candidate's one file and the review's one evidence file.
        assertEquals(6, answer.get("checkedRawFiles").intValue());
        assertEquals(2, answer.get("requiredObligationCount").intValue());
        assertFalse(answer.get("assurance").stringValue().isEmpty());
        assertFalse(answer.has("task"), "check-bundle does not hand back the task");
        assertFalse(answer.has("review"));
        assertFalse(answer.has("reviewRef"));
        assertEquals("cs-handoff", value(handoff(dir), 0).get("kind").stringValue());
    }

    @Test
    void theCommittedExampleBundleHoldsAsItIsFrozen() throws IOException {
        JsonNode answer = value(checkBundle(EXAMPLE), 0);
        assertEquals("PASS", answer.get("verdict").stringValue());
        assertEquals(6, answer.get("checkedRawFiles").intValue(), "two sources, the candidate's file, the evidence, the binding's two files");
        assertEquals("PASS", value(checkStageBundle(EXAMPLE), 0).get("verdict").stringValue());
    }

    @Test
    void aStageBundleThePackPassesHandsBackTheTaskTheReviewAndWhereItWasRead(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        JsonNode answer = value(checkStageBundle(dir), 0);
        assertEquals("cs-bundle-check", answer.get("kind").stringValue());
        assertEquals("PASS", answer.get("verdict").stringValue());
        assertEquals(TestJson.hashJson(object(dir, "task.json")), TestJson.hashJson(answer.get("task")), "the task is handed back as it came");
        assertEquals(TestJson.hashJson(object(dir, "review.json")), TestJson.hashJson(answer.get("review")), "the review is handed back as it came");
        assertEquals("review.json", answer.get("reviewRef").get("path").stringValue());
        assertEquals(rawDigest(dir, "review.json"), answer.get("reviewRef").get("digest").stringValue());
        assertEquals(TestJson.hashJson(object(dir, "candidate.json")), answer.get("candidateDigest").stringValue());
    }

    /** What a case does to a frozen copy before the check reads it. */
    @FunctionalInterface
    private interface Tamper {
        void apply(Path dir) throws IOException;
    }

    private record BundleRefusal(String name, Tamper tamper, String code, String key, String label, String path) {
    }

    /** A file whose bytes are not the ones their reference names; the refusal names the file. */
    private static BundleRefusal changed(String name, String file, Tamper tamper) {
        return new BundleRefusal(name, tamper, INTEGRITY, "artifactDigestMismatch", null, file);
    }

    private static BundleRefusal invalid(String name, String key, Tamper tamper) {
        return new BundleRefusal(name, tamper, INVALID, key, null, null);
    }

    private static BundleRefusal integrity(String name, String key, String label, Tamper tamper) {
        return new BundleRefusal(name, tamper, INTEGRITY, key, label, null);
    }

    @Test
    void eachRuleOfTheFrozenChainRefusesAFileOrDocumentThatBreaksIt(@TempDir Path temp) throws IOException {
        List<BundleRefusal> refusals = List.of(
                changed("a source changed after it was frozen", "source/request.txt", dir -> Files.writeString(dir.resolve("source/request.txt"), "changed")),
                changed("the candidate's file changed", "candidate/ClaimQueue.java", dir -> Files.writeString(dir.resolve("candidate/ClaimQueue.java"), "changed")),
                changed("the evidence file changed", "evidence/queue-tests.json", dir -> Files.writeString(dir.resolve("evidence/queue-tests.json"), "{}")),
                changed("the request file changed", "request.json", dir -> Files.writeString(dir.resolve("request.json"), Files.readString(dir.resolve("request.json")) + "\n")),
                changed("the constraint report file changed", "constraints.json", dir -> Files.writeString(dir.resolve("constraints.json"), Files.readString(dir.resolve("constraints.json")) + "\n")),
                invalid("a binding the contract refuses", "schemaValidationFailed", dir -> edit(dir, "binding.json", document -> document.remove("mode"))),
                integrity("a binding to another request", "digestMismatch", "boundRequest", dir -> edit(dir, "binding.json", document -> document.put("requestDigest", ZERO))),
                integrity("a binding to another constraint report", "digestMismatch", "boundReport", dir -> edit(dir, "binding.json", document -> document.put("constraintReportDigest", ZERO))),
                integrity("a binding to another task", "digestMismatch", "boundTask", dir -> edit(dir, "binding.json", document -> document.put("taskDigest", ZERO))),
                integrity("a binding to other knowledge", "digestMismatch", "boundKnowledge", dir -> edit(dir, "binding.json", document -> document.put("knowledgePackDigest", ZERO))),
                integrity("a binding to another policy", "digestMismatch", "boundPolicy", dir -> edit(dir, "binding.json", document -> document.put("policyDigest", ZERO))),
                integrity("a binding to another policy id", "policyKnowledgeChanged", null, dir -> edit(dir, "binding.json", document -> document.put("policyId", "OTHER-POLICY"))),
                integrity("a binding to another policy version", "policyKnowledgeChanged", null, dir -> edit(dir, "binding.json", document -> document.put("policyVersion", "9.9.9"))),
                integrity("a binding to another knowledge version", "policyKnowledgeChanged", null, dir -> edit(dir, "binding.json", document -> document.put("knowledgePackVersion", "9.9.9"))),
                integrity("a binding of another mode than the policy's", "policyKnowledgeChanged", null, dir -> edit(dir, "binding.json", document -> document.put("mode", "observe"))),
                integrity("a binding that names other required obligations", "requiredObligationsChanged", null,
                        dir -> edit(dir, "binding.json", document -> document.set("requiredObligationIds", texts("OB-CLAIM")))),
                invalid("a constraint report that is not ready", "onlyReadyFrozen", dir -> {
                    edit(dir, "constraints.json", document -> document.put("verdict", "BLOCKED"));
                    refreeze(dir);
                }),
                invalid("draft knowledge the policy does not authorize", "draftNotAuthorized", dir -> {
                    edit(dir, "constraints.json", document -> {
                        list(document, "selectedRules").add(draftSelection());
                        ((ObjectNode) document.get("draftUse")).put("allowed", true);
                    });
                    refreeze(dir);
                }),
                invalid("a candidate whose files are out of order", "candidatePathsUnsorted", dir -> {
                    // "A" sorts before the candidate's "C", so a file added after it is out of order.
                    Files.writeString(dir.resolve("candidate/A-first.txt"), "a\n");
                    edit(dir, "candidate.json", document -> list(document, "files").add(TestJson.object("path", "candidate/A-first.txt", "digest", ZERO)));
                    refreeze(dir);
                }),
                invalid("a review of another verdict than the recomputed one", "reviewVerdictMismatch", dir -> edit(dir, "review.json", document -> document.put("verdict", "FAIL"))));
        int number = 0;
        for (BundleRefusal refusal : refusals) {
            Path dir = frozen(temp.resolve("case-" + number++));
            refusal.tamper().apply(dir);
            Outcome outcome = checkBundle(dir);
            assertRefused(refusal.name(), outcome, refusal.code(), refusal.key(), refusal.label());
            if (refusal.path() != null) {
                assertEquals(refusal.path(), outcome.body().path("details").path("path").stringValue(), refusal.name());
            }
        }
    }

    @Test
    void aFileTheBundleNamesAndTheRootLacksIsNotFound(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("bundle"));
        Files.delete(dir.resolve("candidate/ClaimQueue.java"));
        Outcome outcome = checkBundle(dir);
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("NOT_FOUND", outcome.body().path("code").stringValue(), () -> outcome.body().toString());
        assertEquals("practices.io.fileNotFound", outcome.body().path("messageKey").stringValue(), () -> outcome.body().toString());
    }

    @Test
    void draftKnowledgeTheSuppliedPolicyAuthorizesIsAccepted(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("bundle"));
        edit(dir, "constraints.json", document -> {
            list(document, "selectedRules").add(draftSelection());
            ((ObjectNode) document.get("draftUse")).put("allowed", true);
        });
        edit(dir, "policy.json", document -> document.put("allowDraft", true));
        refreeze(dir);
        assertEquals("PASS", value(checkBundle(dir), 0).get("verdict").stringValue());
    }

    @Test
    void aFailingReviewStopsTheStageUnderEnforceAndOnlyAdvisesUnderObserve(@TempDir Path temp) throws IOException {
        Path enforce = frozen(temp.resolve("enforce"));
        edit(enforce, "review.json", CsEngineeringCheckTest::failTheReview);
        refreeze(enforce);
        JsonNode stopped = value(checkBundle(enforce), 1);
        assertEquals("FAIL", stopped.get("verdict").stringValue());
        assertFalse(stopped.get("canProceedUnderSuppliedPolicy").booleanValue());
        assertEquals("FAIL", value(checkStageBundle(enforce), 1).get("verdict").stringValue());

        Path observe = frozen(temp.resolve("observe"));
        edit(observe, "review.json", CsEngineeringCheckTest::failTheReview);
        edit(observe, "policy.json", document -> document.put("mode", "observe"));
        edit(observe, "binding.json", document -> document.put("mode", "observe"));
        refreeze(observe);
        JsonNode advised = value(checkBundle(observe), 1);
        assertEquals("FAIL", advised.get("verdict").stringValue());
        assertEquals("observe", advised.get("mode").stringValue());
        assertTrue(advised.get("canProceedUnderSuppliedPolicy").booleanValue());
    }

    @Test
    void aBlockedReviewIsBlocked(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("bundle"));
        edit(dir, "review.json", CsEngineeringCheckTest::blockTheReview);
        refreeze(dir);
        JsonNode blocked = value(checkBundle(dir), 1);
        assertEquals("BLOCKED", blocked.get("verdict").stringValue());
        assertFalse(blocked.get("canProceedUnderSuppliedPolicy").booleanValue());
    }

    // ------------------------------------------------------------------------------------------------------------------------ stage bundles
    // old: csEngineeringHandoffAndStageBundleVerifyTheFrozenChainAndRefuseOneWrongDigest (the stage half), SkillBackedStageGatesTest (the check's side of the CS cases)

    @Test
    void oneWrongDigestInTheChainIsRefusedByTheStageBundleNamingTheFile(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        Path binding = dir.resolve("binding.json");
        String bound = read(binding).get("knowledgePackDigest").stringValue();
        Files.writeString(binding, Files.readString(binding, StandardCharsets.UTF_8).replace(bound, ZERO), StandardCharsets.UTF_8);
        Outcome refused = checkStageBundle(dir, rawDigest(dir, "stage-bundle.json"), TestJson.hashJson(object(dir, "task.json")));
        assertRefused("a binding changed after the bundle sealed it", refused, INTEGRITY, "artifactDigestMismatch", null);
        assertEquals("binding.json", refused.body().path("details").path("path").stringValue());
    }

    @Test
    void aStageBundleWhoseDigestIsNotTheOneGivenIsRefusedBeforeItIsRead(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        Outcome refused = checkStageBundle(dir, ZERO, TestJson.hashJson(object(dir, "task.json")));
        assertRefused("another bundle digest", refused, INTEGRITY, "artifactDigestMismatch", null);
        assertEquals("stage-bundle.json", refused.body().path("details").path("path").stringValue());
    }

    @Test
    void aStageBundleJudgedForAnotherTaskIsNotAccepted(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        assertRefused("another task", checkStageBundle(dir, rawDigest(dir, "stage-bundle.json"), ZERO), INTEGRITY, "stageTaskDiffers", null);
    }

    @Test
    void aStageBundleNeedsTheDigestOfTheBundleAndOfTheSignedTask(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        String bundle = rawDigest(dir, "stage-bundle.json");
        String task = TestJson.hashJson(object(dir, "task.json"));
        assertRefused("no digests", run("check-stage-bundle", "root", dir.toString(), "input", "stage-bundle.json"), INVALID, "stageDigestsRequired", null);
        assertRefused("no task digest", run("check-stage-bundle", "root", dir.toString(), "input", "stage-bundle.json", "bundle-digest", bundle), INVALID, "stageDigestsRequired", null);
        assertRefused("a digest in the wrong form", checkStageBundle(dir, bundle, task.toUpperCase(Locale.ROOT)), INVALID, "stageDigestsRequired", null);
        assertRefused("a bundle the contract refuses", stageWithoutMember(dir), INVALID, "schemaValidationFailed", null);
    }

    private static Outcome stageWithoutMember(Path dir) throws IOException {
        edit(dir, "stage-bundle.json", document -> document.remove("candidate"));
        return checkStageBundle(dir);
    }

    @Test
    void aBlockedStageBundleStillAnswersWithTheTaskAndTheReviewItJudged(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("stage"));
        edit(dir, "review.json", CsEngineeringCheckTest::blockTheReview);
        refreeze(dir);
        JsonNode blocked = value(checkStageBundle(dir), 1);
        assertEquals("BLOCKED", blocked.get("verdict").stringValue());
        assertEquals("BLOCKED", blocked.get("review").get("verdict").stringValue());
        assertNotNull(blocked.get("task"));
        assertEquals("TASK-CS-QUEUE", blocked.get("task").get("taskId").stringValue());
    }

    @Test
    void theDefaultHandoffAcceptsTheReportedSourceSizeAndChecksItsOriginalDigest(@TempDir Path temp) throws IOException {
        Path dir = frozen(temp.resolve("handoff"));
        String sourceName = "source/request.txt";
        Path source = dir.resolve(sourceName);
        byte[] original = new byte[1_259_173];
        Files.write(source, original);
        refreeze(dir);
        assertEquals("cs-handoff", value(handoff(dir), 0).path("kind").stringValue());
        assertEquals("PASS", value(checkBundle(dir), 0).path("verdict").stringValue());
        original[0] = 1;
        Files.write(source, original);
        Outcome changed = handoff(dir);
        assertRefused("source bytes changed at the same size", changed, INTEGRITY, "artifactDigestMismatch", null);
        assertEquals(sourceName, changed.body().path("details").path("path").stringValue());
    }

    @Test
    void everyStageFileUsesTheConfiguredBoundaryAndTheNextInvocationSeesChanges(@TempDir Path temp) throws IOException {
        int cap = 1024 * 1024;
        AtomicInteger configuredBytes = new AtomicInteger(cap);
        AtomicInteger reads = new AtomicInteger();
        try (var settingsContext = SkillCheckContext.open(temp, Map.of(), () -> {
            reads.incrementAndGet();
            return new ResourceLimits(configuredBytes.get(), ResourceLimits.DEFAULT.maxOpenWorkflowsPerSession());
        })) {
            SkillCheckService checks = settingsContext.getBean(SkillCheckService.class);
            List<String> files = List.of("source/request.txt", "candidate/ClaimQueue.java", "evidence/queue-tests.json",
                    "request.json", "constraints.json", "stage-bundle.json", "binding.json", "task.json", "policy.json", "review.json", "candidate.json");
            int number = 0;
            for (String file : files) {
                Path dir = frozen(temp.resolve("stage-" + number++));
                configuredBytes.set(cap);
                resizeStageFile(dir, file, cap);
                assertEquals("PASS", value(runStage(checks, dir), 0).path("verdict").stringValue(), file);
                resizeStageFile(dir, file, cap + 1);
                assertByteLimit(file, runStage(checks, dir));
                configuredBytes.set(cap + 1);
                assertEquals("PASS", value(runStage(checks, dir), 0).path("verdict").stringValue(), file);
            }
            assertEquals(3 * files.size(), reads.get());
        }
    }

    @Test
    void relativeAndAbsoluteDocumentArgumentsUseTheConfiguredBoundary(@TempDir Path temp) throws IOException {
        final int size = 17 * 1024 * 1024;
        Path input = temp.resolve("request.json");
        Files.copy(EXAMPLE.resolve("request.json"), input);
        padJson(input, size);
        AtomicInteger configuredBytes = new AtomicInteger(size - 1);
        try (var settingsContext = SkillCheckContext.open(temp, Map.of(),
                () -> new ResourceLimits(configuredBytes.get(), ResourceLimits.DEFAULT.maxOpenWorkflowsPerSession()))) {
            SkillCheckService checks = settingsContext.getBean(SkillCheckService.class);
            for (Object argument : List.of("request.json", TestJson.object("file", input.toString()))) {
                var args = TestJson.object("root", temp.toString(), "input", argument);
                configuredBytes.set(size - 1);
                assertByteLimit("request document", checks.run("cs-engineering", "validate-request", args));
                configuredBytes.set(size);
                assertEquals("VALID", value(checks.run("cs-engineering", "validate-request", args), 0).path("status").stringValue());
            }
        }
    }

    private static void assertByteLimit(String file, Outcome outcome) {
        assertEquals(2, outcome.exitCode(), () -> file + ": " + outcome.body());
        assertEquals(INVALID, outcome.body().path("code").stringValue());
        assertEquals("practices.io.artifactExceedsTheByteLimit", outcome.body().path("messageKey").stringValue());
    }

    private static Outcome runStage(SkillCheckService checks, Path dir) throws IOException {
        return checks.run("cs-engineering", "check-stage-bundle", TestJson.object("root", dir.toString(), "input", "stage-bundle.json",
                "bundle-digest", rawDigest(dir, "stage-bundle.json"), "task-digest", TestJson.hashJson(object(dir, "task.json"))));
    }

    private static void padJson(Path file, int size) throws IOException {
        byte[] padding = " ".repeat(size - Math.toIntExact(Files.size(file))).getBytes(StandardCharsets.UTF_8);
        Files.write(file, padding, StandardOpenOption.APPEND);
    }

    private static void resizeStageFile(Path dir, String relative, int size) throws IOException {
        Path file = dir.resolve(relative);
        if (relative.contains("/")) {
            Files.write(file, new byte[size]);
            refreeze(dir);
            return;
        }
        padJson(file, size);
        if (relative.equals("stage-bundle.json")) return;
        String stageMember = relative.substring(0, relative.indexOf('.'));
        if (relative.equals("request.json") || relative.equals("constraints.json")) {
            String reference = relative.equals("request.json") ? "requestRef" : "constraintReportRef";
            String digest = rawDigest(dir, relative);
            edit(dir, "binding.json", binding -> ((ObjectNode) binding.get(reference)).put("digest", digest));
            stageMember = "binding";
        }
        String member = stageMember;
        String digest = rawDigest(dir, member + ".json");
        edit(dir, "stage-bundle.json", bundle -> ((ObjectNode) bundle.get(member)).put("digest", digest));
    }
}
