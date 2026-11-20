// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;
import io.github.jaeseongs95.sennard.server.skills.SkillSchemas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * korean-prose-editor through {@link SkillCheckService}, the MCP tool's own entry: the protected-span manifest, the source units, the preservation check and the finalizer.
 * Each rule has a normal case and the failure it exists for. A broken work product is a fallback to the source with a receipt that says why; only a request that is itself wrong is
 * refused (exit 2, the catalog key of the rule it breaks).
 */
class KoreanProseCheckTest {
    private static final String SKILL = "korean-prose-editor";
    private static final String CONTRACTS = "skills/korean-prose-editor/contracts/";
    private static final String KEY = "skillChecks.koreanProseEditor.";
    private static final String SPANS_KEY = "skillChecks.koreanProseEditorSpans.";
    private static final String UNITS_KEY = "skillChecks.koreanProseEditorUnits.";
    private static final String FINAL_KEY = "skillChecks.koreanProseEditorFinalizer.";
    private static final String[] ACTORS = {"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333"};
    private static final String RUBRIC = "a".repeat(64);
    private static final String ZEROS = "0".repeat(64);
    private static final String SOURCE = "여기 문장은 편집 대상입니다.";
    private static final String WITH_FENCE = "첫 문단입니다.\n\n```\ncode\n```\n\n둘째 문단입니다.";
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

    // ---- helpers ------------------------------------------------------------------------------------------------------------------------

    private static Outcome run(String command, ObjectNode input) {
        return service.run(SKILL, command, TestJson.object("input", input));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    /** A request the check will not judge: exit 2, the wire code and the catalog key of the rule it breaks. */
    private static void refused(Outcome outcome, String messageKey) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("ERROR", outcome.body().get("status").stringValue(), () -> outcome.body().toString());
        assertEquals("INVALID_INPUT", outcome.body().get("code").stringValue(), () -> outcome.body().toString());
        assertEquals(messageKey, outcome.body().get("messageKey").stringValue(), () -> outcome.body().toString());
    }

    private static String hex(String text) {
        return Digests.sha256Hex(text);
    }

    /** The digest of a document's canonical form: what the work products bind to one another with. */
    private static String docDigest(JsonNode document) {
        return Digests.sha256Hex(CanonicalJson.of(document));
    }

    private static JsonNode manifestOf(String source) {
        return value(run("extract-protected-spans", TestJson.object("source", source)));
    }

    private static JsonNode unitsOf(String source, JsonNode manifest) {
        return value(run("source-unit-manifest", TestJson.object("source", source, "manifest", manifest)));
    }

    private static List<String> texts(JsonNode manifest, String kind) {
        return TestJson.items(manifest.get("spans")).stream().filter(span -> kind.equals(TestJson.text(span, "kind"))).map(span -> TestJson.text(span, "text")).toList();
    }

    private static List<String> strings(JsonNode array) {
        return TestJson.strings(array);
    }

    private static JsonNode receiptOf(Outcome outcome) {
        JsonNode answer = value(outcome);
        return answer.has("receipt") ? answer.get("receipt") : answer;
    }

    private static List<String> warnings(Outcome outcome) {
        return strings(receiptOf(outcome).get("warnings"));
    }

    /** The receipt holds to its contract, whatever the case. */
    private static JsonNode finalized(Outcome outcome) {
        JsonNode receipt = receiptOf(outcome);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        assertTrue(schemas.valid(CONTRACTS + "receipt.schema.json", receipt), () -> schemas.violations(CONTRACTS + "receipt.schema.json", receipt).toString());
        return receipt;
    }

    private static void assertFallback(Outcome outcome, String source, String... expectedWarnings) {
        JsonNode receipt = finalized(outcome);
        assertTrue(receipt.get("decisions").get("fallback").booleanValue(), receipt::toString);
        assertEquals("fallback", receipt.get("decisions").get("status").stringValue());
        assertEquals("unverified", receipt.get("decisions").get("assurance").stringValue());
        assertEquals(hex(source), receipt.get("digest").get("result").stringValue(), "the text that comes back is the source");
        assertEquals(0, receipt.get("decisions").get("appliedEditDigests").size());
        List<String> found = strings(receipt.get("warnings"));
        assertTrue(found.contains("GLOBAL_FALLBACK_APPLIED"), found::toString);
        for (String warning : expectedWarnings) {
            assertTrue(found.contains(warning), () -> warning + " is not among " + found);
        }
        JsonNode answer = value(outcome);
        if (answer.has("output")) {
            assertEquals(source, answer.get("output").stringValue());
        }
    }

    // ---- the work products of a finalization ----------------------------------------------------------------------------------------------

    private record EditSpec(String id, int start, int end, String replacement) {
    }

    private static ObjectNode accept(String editId) {
        return TestJson.object("editId", editId, "decision", "accept", "reasonCode", "MEANING_PRESERVED", "sourceDefect", "REDUNDANCY", "invariantDelta", "NONE");
    }

    private static ObjectNode retain(String editId) {
        return TestJson.object("editId", editId, "decision", "retain", "reasonCode", "MEANING_CHANGED", "sourceDefect", "NONE", "invariantDelta", "UNCERTAIN");
    }

    private static ObjectNode assessment(String meaning, String preference) {
        return TestJson.object("meaningPreservation", meaning, "majorMeaningChange", false, "registerCompliance", "pass", "protectedStrings", "pass", "terminologyJudgment", "not-applicable",
                "pairPreference", preference);
    }

    private static String apply(String source, List<EditSpec> edits) {
        StringBuilder out = new StringBuilder(source);
        edits.stream().sorted(Comparator.comparingInt(EditSpec::start).reversed().thenComparing(Comparator.comparingInt(EditSpec::end).reversed()))
                .forEach(edit -> out.replace(edit.start(), edit.end(), edit.replacement()));
        return out.toString();
    }

    private static ObjectNode match(int start, int end, String policy) {
        return TestJson.object("start", start, "end", end, "entryId", "entry-1", "policy", policy, "canonicalForm", "표준어", "priority", 10);
    }

    /** What the glossary lookup answers for this source, its digest made the way the finalizer remakes it. */
    private static ObjectNode matchSet(String source, String status, List<ObjectNode> matches) {
        ObjectNode glossary = TestJson.object("id", "korean-prose-core", "version", "1.3.0", "contentDigest", "b".repeat(64));
        String matchSetDigest = docDigest(TestJson.object("schemaVersion", "1.0.0", "sourceDigest", hex(source), "glossary", glossary, "matches", TestJson.array(matches)));
        return TestJson.object("schemaVersion", "1.0.0", "status", status, "sourceDigest", hex(source), "glossary", glossary, "matches", TestJson.array(matches), "matchSetDigest", matchSetDigest,
                "warnings", TestJson.array(List.of()));
    }

    private static ObjectNode mcpBinding(ObjectNode set) {
        JsonNode glossary = set.get("glossary");
        return TestJson.object("schemaVersion", "1.0.0", "mode", "mcp", "status", set.get("status"), "sourceDigest", set.get("sourceDigest"), "glossaryId", glossary.get("id"),
                "glossaryVersion", glossary.get("version"), "glossaryDigest", glossary.get("contentDigest"), "matchSetDigest", set.get("matchSetDigest"), "matchCount", set.get("matches").size(),
                "warnings", set.get("warnings"));
    }

    /** One finalization: the manifest and the units the skill itself makes, and the three work products bound to one another by digest; a case changes a field and builds. */
    private static final class Case {
        String source = SOURCE;
        String mode = "direct";
        /** The unit the selection asks to edit, and in which the edits lie. */
        int editUnit = 0;
        int issueStart = 0;
        int issueEnd = 2;
        String selectionStatus = "ready";
        List<String> extraProtected = List.of();
        List<EditSpec> edits = List.of(new EditSpec("edit-1", 0, 1, "저"));
        /** Null: the digest of the source with the edits applied. */
        String candidateDigest;
        /** Null: every edit accepted. */
        List<ObjectNode> decisions;
        String globalDecision = "continue";
        ObjectNode assessment = assessment("pass", "candidate");
        /** Null: no glossary (direct mode). */
        ObjectNode matchSet;

        ObjectNode build() {
            JsonNode manifest = manifestOf(source);
            JsonNode units = unitsOf(source, manifest);
            ObjectNode binding = matchSet == null ? TestJson.object("schemaVersion", "1.0.0", "mode", "none") : mcpBinding(matchSet);
            String sourceDigest = hex(source);
            List<JsonNode> unitList = TestJson.items(units.get("units"));
            List<ObjectNode> selectionDecisions = new ArrayList<>();
            for (int i = 0; i < unitList.size(); i++) {
                boolean edit = i == editUnit;
                List<ObjectNode> issues = edit ? List.of(TestJson.object("start", issueStart, "end", issueEnd, "reasonCode", "REDUNDANCY")) : List.of();
                selectionDecisions.add(TestJson.object("unitId", TestJson.text(unitList.get(i), "unitId"), "action", edit ? "edit" : "retain", "reasonCodes", TestJson.array(edit ? List.of("REDUNDANCY") : List.of()),
                        "riskFlags", TestJson.array(List.of()), "additionalProtectedStrings", TestJson.array(edit ? extraProtected : List.of()), "issueRanges", TestJson.array(issues)));
            }
            ObjectNode selection = TestJson.object("schemaVersion", "1.0.0", "actorId", ACTORS[0], "sourceDigest", sourceDigest, "glossaryBinding", binding, "status", selectionStatus, "decisions",
                    TestJson.array(selectionDecisions));
            String unitId = TestJson.text(unitList.get(editUnit), "unitId");
            List<ObjectNode> editNodes = new ArrayList<>();
            for (EditSpec edit : edits) {
                editNodes.add(TestJson.object("id", edit.id(), "unitId", unitId, "sourceDigest", sourceDigest, "start", edit.start(), "end", edit.end(), "replacement", edit.replacement(), "actorId", ACTORS[1]));
            }
            ObjectNode editing = TestJson.object("schemaVersion", "1.0.0", "actorId", ACTORS[1], "sourceDigest", sourceDigest, "glossaryBinding", binding, "selectionDigest", docDigest(selection), "edits",
                    TestJson.array(editNodes), "candidateDigest", candidateDigest != null ? candidateDigest : hex(apply(source, edits)));
            List<ObjectNode> verdicts = decisions != null ? decisions : edits.stream().map(edit -> accept(edit.id())).toList();
            ObjectNode verification = TestJson.object("schemaVersion", "1.0.0", "actorId", ACTORS[2], "sourceDigest", sourceDigest, "glossaryBinding", binding, "editingDigest", docDigest(editing),
                    "rubricDigest", RUBRIC, "globalDecision", globalDecision, "decisions", TestJson.array(verdicts), "assessment", assessment);
            ObjectNode plan = TestJson.object("schemaVersion", "1.0.0", "actorIds", TestJson.array(List.of(ACTORS)), "providers",
                    TestJson.object("selection", TestJson.object("kind", "agent", "actorId", ACTORS[0]), "editing", TestJson.object("kind", "agent", "actorId", ACTORS[1]), "verification",
                            TestJson.object("kind", "agent", "actorId", ACTORS[2]), "finalization",
                            TestJson.object("kind", "deterministic", "entrypoint", "run_skill_check:korean-prose-editor/finalize")));
            return TestJson.object("schemaVersion", "1.0.0", "mode", mode, "subagentsAvailable", true, "plan", plan, "source", source, "manifest", manifest, "sourceUnitManifest", units,
                    "glossaryMatchSet", matchSet == null ? TestJson.MAPPER.nullNode() : matchSet, "selection", selection, "editing", editing, "verification", verification, "rubricDigest", RUBRIC);
        }
    }

    private static Outcome submit(ObjectNode request) {
        return run("finalize", request);
    }

    private static ObjectNode member(JsonNode parent, String name) {
        return (ObjectNode) parent.get(name);
    }

    // ---- extract-protected-spans ----------------------------------------------------------------------------------------------------------

    @Test
    void spansProtectCodeUrlsAndNumbers() {
        String source = "가격은 1,000원입니다. `code` 와 https://example.test/a?b=1 를 보세요.\n";
        JsonNode manifest = manifestOf(source);
        assertTrue(schemas.valid(CONTRACTS + "protected-manifest.schema.json", manifest), () -> schemas.violations(CONTRACTS + "protected-manifest.schema.json", manifest).toString());
        List<String> kinds = TestJson.items(manifest.get("spans")).stream().map(span -> TestJson.text(span, "kind")).toList();
        assertTrue(kinds.containsAll(List.of("number-or-date", "inline-code", "url")), kinds::toString);
        assertEquals(source.length(), manifest.get("sourceLength").intValue());
        assertEquals(hex(source), manifest.get("sourceDigest").stringValue());
        JsonNode span = manifest.get("spans").get(0);
        assertEquals(TestJson.text(span, "text"), source.substring(span.get("start").intValue(), span.get("end").intValue()));
        assertEquals(hex(TestJson.text(span, "text")), TestJson.text(span, "digest"));
        assertEquals("span-0001", TestJson.text(span, "id"));
        JsonNode kept = value(run("check-result", TestJson.object("source", source, "candidate", source, "manifest", manifest)));
        assertTrue(kept.get("decisions").get("protectedSpansPreserved").booleanValue());
        JsonNode lost = value(run("check-result", TestJson.object("source", source, "candidate", "`code` 없음", "manifest", manifest)));
        assertFalse(lost.get("decisions").get("protectedSpansPreserved").booleanValue());
        assertTrue(strings(lost.get("warnings")).contains("PROTECTED_SPAN_MISSING_OR_REORDERED"));
    }

    @Test
    void spansOfEveryKindTheRulesKnow() {
        assertEquals(List.of("help@example.test"), texts(manifestOf("문의는 help@example.test 로 부탁합니다."), "email"));
        assertEquals(List.of("/etc/sennard/config.yml"), texts(manifestOf("설정은 /etc/sennard/config.yml 에 있다."), "path"));
        assertEquals(List.of("“안녕하세요”"), texts(manifestOf("그는 “안녕하세요”라고 말했다."), "quotation"));
        assertEquals(List.of("$ ./gradlew test"), texts(manifestOf("다음을 실행한다.\n$ ./gradlew test\n끝."), "command"));
        assertEquals(List.of("12:30"), texts(manifestOf("회의는 12:30 에 시작한다."), "number-or-date"));
        assertEquals(List.of("2026-11-11"), texts(manifestOf("기준일은 2026-11-11 이다."), "number-or-date"));
        assertEquals(List.of("12.5%"), texts(manifestOf("증가율은 12.5% 이다."), "number-or-date"));
        assertEquals(List.of("```\ncode\n```"), texts(manifestOf(WITH_FENCE), "fenced-code"));
    }

    @Test
    void aMarkdownTargetKeepsItsBalancedParenthesesAndTheUrlInsideIsOneSpan() {
        JsonNode manifest = manifestOf("[링크](https://example.test/a_(b)) 를 참조한다.");
        assertEquals(List.of("https://example.test/a_(b)"), texts(manifest, "markdown-target"));
        assertEquals(List.of(), texts(manifest, "url"), "the target's own span takes the url with it");
    }

    @Test
    void protectedStringsBecomeUserDefinedSpansAndTheUnionIsKeptWhereTheyCrossAnAutomaticOne() {
        JsonNode plain = value(run("extract-protected-spans", TestJson.object("source", "프로젝트 Sennard 는 에이전트를 교정한다.", "protectedStrings", TestJson.array(List.of("Sennard")))));
        assertEquals(List.of("Sennard"), texts(plain, "user-defined"));
        String source = "주소는 https://example.test/a 입니다.";
        JsonNode crossing = value(run("extract-protected-spans", TestJson.object("source", source, "protectedStrings", TestJson.array(List.of("test/a 입")))));
        assertEquals(List.of("https://example.test/a 입"), TestJson.items(crossing.get("spans")).stream().map(span -> TestJson.text(span, "text")).toList());
    }

    @Test
    void spansRefuseAMissingSourceAndProtectedStringsThatAreNotInIt() {
        refused(run("extract-protected-spans", TestJson.object("protectedStrings", TestJson.array(List.of("a")))), SPANS_KEY + "sourceNotString");
        refused(run("extract-protected-spans", TestJson.object("source", "가나다", "protectedStrings", TestJson.array(List.of("라")))), SPANS_KEY + "protectedStringsInvalid");
        refused(run("extract-protected-spans", TestJson.object("source", "가나다", "protectedStrings", TestJson.array(List.of("")))), SPANS_KEY + "protectedStringsInvalid");
        refused(service.run(SKILL, "extract-protected-spans", TestJson.object("input", TestJson.array(List.of("가")))), KEY + "mustBeObject");
    }

    // ---- source-unit-manifest -------------------------------------------------------------------------------------------------------------

    @Test
    void unitsSeparateFencesFromParagraphs() {
        JsonNode manifest = manifestOf(WITH_FENCE);
        JsonNode units = unitsOf(WITH_FENCE, manifest);
        assertTrue(schemas.valid(CONTRACTS + "source-unit-manifest.v1.schema.json", units), () -> schemas.violations(CONTRACTS + "source-unit-manifest.v1.schema.json", units).toString());
        assertEquals(List.of("prose", "fenced-code", "prose"), TestJson.items(units.get("units")).stream().map(unit -> TestJson.text(unit, "kind")).toList());
        assertEquals(List.of("unit-0001", "unit-0002", "unit-0003"), TestJson.items(units.get("units")).stream().map(unit -> TestJson.text(unit, "unitId")).toList());
        JsonNode fenced = units.get("units").get(1);
        assertEquals("```\ncode\n```", WITH_FENCE.substring(fenced.get("start").intValue(), fenced.get("end").intValue()).strip());
        assertEquals(hex(WITH_FENCE.substring(fenced.get("start").intValue(), fenced.get("end").intValue())), TestJson.text(fenced, "digest"));
    }

    @Test
    void unitsListTheProtectedSpansThatLieInThem() {
        String source = "첫 `a` 문단입니다.\n\n둘째 문단입니다.";
        JsonNode units = unitsOf(source, manifestOf(source));
        assertEquals(List.of("span-0001"), strings(units.get("units").get(0).get("protectedSpanIds")));
        assertEquals(List.of(), strings(units.get("units").get(1).get("protectedSpanIds")));
    }

    @Test
    void aFenceIsClosedOnlyByAFenceAsLongAsItsOpeningOne() {
        String longer = "앞.\n\n````\nx\n```\ny\n````\n\n뒤.";
        JsonNode units = unitsOf(longer, manifestOf(longer));
        assertEquals(List.of("prose", "fenced-code", "prose"), TestJson.items(units.get("units")).stream().map(unit -> TestJson.text(unit, "kind")).toList());
        JsonNode fenced = units.get("units").get(1);
        assertTrue(longer.substring(fenced.get("start").intValue(), fenced.get("end").intValue()).contains("y"), "the shorter fence inside did not close it");
        String unclosed = "앞.\n\n```\ncode without a closing fence";
        JsonNode open = unitsOf(unclosed, manifestOf(unclosed));
        assertEquals(List.of("prose", "fenced-code"), TestJson.items(open.get("units")).stream().map(unit -> TestJson.text(unit, "kind")).toList());
        assertEquals(unclosed.length(), open.get("units").get(1).get("end").intValue(), "an unclosed fence runs to the end of the source");
        String tilde = "앞.\n\n~~~\ncode\n~~~\n\n뒤.";
        assertEquals(List.of("prose", "fenced-code", "prose"), TestJson.items(unitsOf(tilde, manifestOf(tilde)).get("units")).stream().map(unit -> TestJson.text(unit, "kind")).toList());
    }

    @Test
    void unitsSplitParagraphsOnBlankLinesOfEitherLineEnding() {
        String source = "첫 문단.\r\n\r\n둘째 문단.";
        JsonNode units = unitsOf(source, manifestOf(source));
        assertEquals(2, units.get("units").size());
        assertEquals(0, units.get("units").get(0).get("start").intValue());
        assertEquals("둘째 문단.", source.substring(units.get("units").get(1).get("start").intValue(), units.get("units").get(1).get("end").intValue()));
    }

    @Test
    void unitsRefuseAManifestThatIsNotTheSources() {
        JsonNode other = manifestOf("다른 원문입니다.");
        refused(run("source-unit-manifest", TestJson.object("source", SOURCE, "manifest", other)), UNITS_KEY + "manifestSourceMismatch");
        refused(run("source-unit-manifest", TestJson.object("source", SOURCE)), UNITS_KEY + "manifestSourceMismatch");
        refused(run("source-unit-manifest", TestJson.object("manifest", other)), UNITS_KEY + "sourceNotString");
    }

    // ---- check-result ---------------------------------------------------------------------------------------------------------------------

    @Test
    void aCandidateKeepsEveryProtectedSpanInOrderAndAsOftenAsTheSourceHasIt() {
        String source = "가 `a` 나 `b` 다";
        JsonNode manifest = manifestOf(source);
        assertTrue(preserved(source, "가 `a` 라 `b` 마", manifest));
        JsonNode reordered = check(source, "가 `b` 나 `a` 다", manifest);
        assertFalse(reordered.get("decisions").get("protectedSpansPreserved").booleanValue());
        assertEquals(List.of("PROTECTED_SPAN_MISSING_OR_REORDERED"), strings(reordered.get("warnings")));
        JsonNode repeated = check(source, "가 `a` `a` 나 `b` 다", manifest);
        assertFalse(repeated.get("decisions").get("protectedSpansPreserved").booleanValue());
        assertEquals(List.of("PROTECTED_SPAN_MISSING_OR_REORDERED"), strings(repeated.get("warnings")));
        assertEquals(source.length(), repeated.get("length").get("source").intValue());
        assertEquals("가 `a` `a` 나 `b` 다".length(), repeated.get("length").get("candidate").intValue());
    }

    private static JsonNode check(String source, String candidate, JsonNode manifest) {
        return value(run("check-result", TestJson.object("source", source, "candidate", candidate, "manifest", manifest)));
    }

    private static boolean preserved(String source, String candidate, JsonNode manifest) {
        return check(source, candidate, manifest).get("decisions").get("protectedSpansPreserved").booleanValue();
    }

    @Test
    void aManifestThatIsNotTheSourcesOrIsNoManifestIsJudgedAndSaysWhy() {
        JsonNode none = value(run("check-result", TestJson.object("source", "가 `a` 나", "candidate", "가 `a` 나")));
        assertFalse(none.get("decisions").get("protectedSpansPreserved").booleanValue());
        assertEquals(List.of("MANIFEST_SCHEMA_VERSION", "MANIFEST_SPANS_INVALID", "SOURCE_MANIFEST_MISMATCH"), strings(none.get("warnings")));
        assertEquals(hex("null"), none.get("digest").get("manifest").stringValue());
        JsonNode another = manifestOf("가 `a` 나");
        JsonNode mismatch = check("가 `b` 나", "가 `b` 나", another);
        assertFalse(mismatch.get("decisions").get("protectedSpansPreserved").booleanValue());
        assertTrue(strings(mismatch.get("warnings")).containsAll(List.of("SOURCE_MANIFEST_MISMATCH", "MANIFEST_SPAN_INVALID")), () -> strings(mismatch.get("warnings")).toString());
    }

    @Test
    void theManifestDigestIsThatOfTheDocumentAsItWasSent() {
        String source = "가 `a` 나";
        ObjectNode manifest = (ObjectNode) manifestOf(source).deepCopy();
        String plain = TestJson.text(check(source, source, manifest).get("digest"), "manifest");
        assertEquals(docDigest(manifest), plain);
        manifest.put("note", "an extra member");
        JsonNode annotated = check(source, source, manifest);
        assertTrue(annotated.get("decisions").get("protectedSpansPreserved").booleanValue(), "a member the check does not read changes no judgment");
        assertEquals(docDigest(manifest), TestJson.text(annotated.get("digest"), "manifest"));
        assertNotEquals(plain, TestJson.text(annotated.get("digest"), "manifest"));
    }

    // ---- finalize: what is applied ----------------------------------------------------------------------------------------------------------

    @Test
    void finalizerAppliesOnlyAnAcceptedEditAndNeverVerifiesDirectMode() {
        Outcome outcome = submit(new Case().build());
        JsonNode receipt = finalized(outcome);
        JsonNode result = value(outcome);
        assertEquals("저" + SOURCE.substring(1), result.get("output").stringValue(), result::toString);
        assertEquals("unverified", result.get("verificationStatus").stringValue());
        JsonNode decisions = receipt.get("decisions");
        assertEquals("finalized", decisions.get("status").stringValue());
        assertEquals("unverified", decisions.get("assurance").stringValue());
        assertFalse(decisions.get("fallback").booleanValue());
        assertEquals(List.of(hex("edit-1")), strings(decisions.get("appliedEditDigests")));
        assertEquals(List.of(), strings(decisions.get("retainedEditDigests")));
        assertEquals(List.of(), strings(receipt.get("warnings")));
        assertEquals(hex(SOURCE), receipt.get("digest").get("source").stringValue());
        assertEquals(hex("저" + SOURCE.substring(1)), receipt.get("digest").get("result").stringValue());
        assertEquals(List.of(ACTORS), strings(receipt.get("actorIds")));
        assertEquals("none", receipt.get("glossary").get("mode").stringValue());
        assertEquals("direct", receipt.get("glossary").get("status").stringValue());
    }

    @Test
    void anAcceptedEditAndARetainedOneAreSeparatedAndTheSafeOneStillApplies() {
        Case two = new Case();
        two.issueEnd = 6;
        two.edits = List.of(new EditSpec("edit-1", 0, 1, "저"), new EditSpec("edit-2", 3, 4, "분"));
        two.decisions = List.of(accept("edit-1"), retain("edit-2"));
        Outcome outcome = submit(two.build());
        JsonNode receipt = finalized(outcome);
        assertEquals("저기 문장은 편집 대상입니다.", value(outcome).get("output").stringValue());
        assertEquals(List.of(hex("edit-1")), strings(receipt.get("decisions").get("appliedEditDigests")));
        assertEquals(List.of(hex("edit-2")), strings(receipt.get("decisions").get("retainedEditDigests")));
        assertFalse(receipt.get("decisions").get("fallback").booleanValue());
    }

    @Test
    void aSourceWithAFencedBlockTakesAnEditOnlyInTheUnitTheSelectionChose() {
        int at = WITH_FENCE.indexOf("둘째");
        Case prose = new Case();
        prose.source = WITH_FENCE;
        prose.editUnit = 2;
        prose.issueStart = at;
        prose.issueEnd = at + 2;
        prose.edits = List.of(new EditSpec("edit-1", at, at + 1, "두"));
        Outcome outcome = submit(prose.build());
        finalized(outcome);
        assertEquals(WITH_FENCE.substring(0, at) + "두" + WITH_FENCE.substring(at + 1), value(outcome).get("output").stringValue());
    }

    @Test
    void anEditInTheFencedBlockBreaksTheSelectionAndTheSourceComesBack() {
        int at = WITH_FENCE.indexOf("code");
        Case fenced = new Case();
        fenced.source = WITH_FENCE;
        fenced.editUnit = 1;
        fenced.issueStart = at;
        fenced.issueEnd = at + 4;
        fenced.edits = List.of(new EditSpec("edit-1", at, at + 1, "k"));
        assertFallback(submit(fenced.build()), WITH_FENCE, "SELECTION_CONTRACT_INVALID", "EDIT_OUT_OF_SCOPE_RETAINED");
    }

    @Test
    void aVerifierThatRetainsOrSaysNothingKeepsTheSourceWithoutFallingBack() {
        Case retained = new Case();
        retained.decisions = List.of(retain("edit-1"));
        Outcome kept = submit(retained.build());
        JsonNode receipt = finalized(kept);
        assertEquals(SOURCE, value(kept).get("output").stringValue());
        assertEquals("finalized", receipt.get("decisions").get("status").stringValue());
        assertEquals(List.of(), strings(receipt.get("decisions").get("appliedEditDigests")));
        assertEquals(List.of(hex("edit-1")), strings(receipt.get("decisions").get("retainedEditDigests")));
        assertEquals(List.of(), strings(receipt.get("warnings")));
        Case silent = new Case();
        silent.decisions = List.of();
        Outcome missing = submit(silent.build());
        assertEquals(SOURCE, value(missing).get("output").stringValue());
        assertEquals(List.of("VERIFICATION_DECISION_MISSING"), warnings(missing));
        assertFalse(finalized(missing).get("decisions").get("fallback").booleanValue());
    }

    @Test
    void anEditThatIsNotMinimalOutsideTheIssuesOrOnProtectedTextIsRetained() {
        Case notMinimal = new Case();
        notMinimal.edits = List.of(new EditSpec("edit-1", 0, 2, "여거"));
        Outcome first = submit(notMinimal.build());
        assertEquals(List.of("NON_MINIMAL_EDIT_RETAINED"), warnings(first));
        assertEquals(SOURCE, value(first).get("output").stringValue());
        Case outside = new Case();
        outside.edits = List.of(new EditSpec("edit-1", 3, 4, "분"));
        Outcome second = submit(outside.build());
        assertEquals(List.of("EDIT_OUT_OF_SCOPE_RETAINED"), warnings(second));
        assertEquals(SOURCE, value(second).get("output").stringValue());
        String priced = "가격은 1,000원입니다. 여기 문장은 편집 대상입니다.";
        int number = priced.indexOf("1,000원");
        Case protectedText = new Case();
        protectedText.source = priced;
        protectedText.issueStart = number;
        protectedText.issueEnd = number + 6;
        protectedText.edits = List.of(new EditSpec("edit-1", number, number + 1, "2"));
        Outcome third = submit(protectedText.build());
        assertEquals(List.of("PROTECTED_EDIT_RETAINED"), warnings(third));
        assertEquals(priced, value(third).get("output").stringValue());
    }

    @Test
    void aStringTheSelectionAsksToProtectProtectsItAndTheReceiptBindsTheWiderManifest() {
        Case asked = new Case();
        asked.issueEnd = 16;
        asked.extraProtected = List.of("편집 대상");
        asked.edits = List.of(new EditSpec("edit-1", 7, 8, "변"));
        ObjectNode request = asked.build();
        Outcome outcome = submit(request);
        JsonNode receipt = finalized(outcome);
        assertEquals(List.of("PROTECTED_EDIT_RETAINED"), warnings(outcome));
        assertEquals(SOURCE, value(outcome).get("output").stringValue());
        assertNotEquals(docDigest(request.get("manifest")), receipt.get("digest").get("manifest").stringValue(), "the manifest the result was held to carries the selection's strings");
    }

    @Test
    void anEditThatCutsASurrogatePairBreaksItsContract() {
        Case emoji = new Case();
        emoji.source = "😀 문장은 편집 대상입니다.";
        emoji.edits = List.of(new EditSpec("edit-1", 1, 2, "x"));
        assertFallback(submit(emoji.build()), emoji.source, "EDIT_CONTRACT_INVALID");
    }

    // ---- finalize: the glossary -------------------------------------------------------------------------------------------------------------

    @Test
    void mcpModeAnswersTheReceiptOnlyAndVerifiesWhatItFinalized() {
        Case mcp = new Case();
        mcp.mode = "mcp";
        mcp.matchSet = matchSet(SOURCE, "no-match", List.of());
        Outcome outcome = submit(mcp.build());
        JsonNode receipt = finalized(outcome);
        assertFalse(value(outcome).has("output"), "the text travels by another channel");
        assertEquals("verified", receipt.get("decisions").get("assurance").stringValue());
        assertEquals("finalized", receipt.get("decisions").get("status").stringValue());
        assertEquals("mcp", receipt.get("decisions").get("mode").stringValue());
        assertEquals("no-match", receipt.get("glossary").get("status").stringValue());
        assertEquals("korean-prose-core", receipt.get("glossary").get("id").stringValue());
        assertEquals(mcp.matchSet.get("matchSetDigest").stringValue(), receipt.get("glossary").get("matchSetDigest").stringValue());
    }

    @Test
    void everyProtectMatchMustLieInsideAProtectedSpan() {
        String priced = "가격은 1,000원입니다. 여기 문장은 편집 대상입니다.";
        int number = priced.indexOf("1,000원");
        int edit = priced.indexOf("여기");
        Case covered = new Case();
        covered.source = priced;
        covered.mode = "mcp";
        covered.issueStart = edit;
        covered.issueEnd = edit + 2;
        covered.edits = List.of(new EditSpec("edit-1", edit, edit + 1, "저"));
        covered.matchSet = matchSet(priced, "matched", List.of(match(number, number + 6, "protect")));
        JsonNode receipt = finalized(submit(covered.build()));
        assertEquals("verified", receipt.get("decisions").get("assurance").stringValue());
        assertEquals("matched", receipt.get("glossary").get("status").stringValue());
        assertEquals(1, receipt.get("glossary").get("matchCount").intValue());
        Case uncovered = new Case();
        uncovered.source = priced;
        uncovered.mode = "mcp";
        uncovered.issueStart = edit;
        uncovered.issueEnd = edit + 2;
        uncovered.edits = List.of(new EditSpec("edit-1", edit, edit + 1, "저"));
        uncovered.matchSet = matchSet(priced, "matched", List.of(match(edit, edit + 2, "protect")));
        assertFallback(submit(uncovered.build()), priced, "GLOSSARY_PROTECT_MISSING", "GLOSSARY_MATCH_SET_INVALID");
    }

    @Test
    void aMatchSetWhoseDigestIsNotItsOwnIsUnavailableAndBindsNothing() {
        Case forged = new Case();
        forged.mode = "mcp";
        ObjectNode set = matchSet(SOURCE, "no-match", List.of());
        set.put("matchSetDigest", ZEROS);
        forged.matchSet = set;
        Outcome outcome = submit(forged.build());
        assertFallback(outcome, SOURCE, "GLOSSARY_MATCH_SET_INVALID", "GLOSSARY_BINDING_MISMATCH");
        assertEquals("unavailable", receiptOf(outcome).get("glossary").get("status").stringValue());
    }

    @Test
    void theModeAndTheGlossaryMustAgree() {
        ObjectNode asksForMcp = new Case().build();
        asksForMcp.put("mode", "mcp");
        Outcome unavailable = submit(asksForMcp);
        assertFallback(unavailable, SOURCE, "GLOSSARY_MATCH_SET_INVALID", "GLOSSARY_BINDING_MISMATCH");
        assertEquals("unavailable", receiptOf(unavailable).get("glossary").get("status").stringValue());
        ObjectNode directWithGlossary = new Case().build();
        directWithGlossary.set("glossaryMatchSet", matchSet(SOURCE, "no-match", List.of()));
        assertFallback(submit(directWithGlossary), SOURCE, "GLOSSARY_MATCH_SET_INVALID");
    }

    // ---- finalize: a work product that breaks falls back --------------------------------------------------------------------------------------

    @Test
    void finalizerFallsBackToTheSourceWhenAWorkProductBreaks() {
        ObjectNode request = new Case().build();
        member(request, "editing").put("selectionDigest", ZEROS);
        Outcome outcome = submit(request);
        assertFallback(outcome, SOURCE, "SELECTION_DIGEST_MISMATCH");
        assertEquals(List.of(hex("edit-1")), strings(receiptOf(outcome).get("decisions").get("retainedEditDigests")));
        assertEquals(SOURCE, value(outcome).get("output").stringValue());
    }

    @Test
    void theManifestAndTheUnitManifestAreHeldToTheSource() {
        ObjectNode manifest = new Case().build();
        member(manifest, "manifest").put("sourceDigest", ZEROS);
        assertFallback(submit(manifest), SOURCE, "SOURCE_MANIFEST_MISMATCH");
        ObjectNode units = new Case().build();
        ((ObjectNode) units.get("sourceUnitManifest").get("units").get(0)).put("digest", ZEROS);
        assertFallback(submit(units), SOURCE, "SOURCE_UNIT_MANIFEST_MISMATCH");
    }

    @Test
    void aSelectionThatIsNotReadyOrBreaksItsContractFallsBack() {
        Case needsInput = new Case();
        needsInput.selectionStatus = "needs-input";
        assertFallback(submit(needsInput.build()), SOURCE, "SELECTION_NOT_READY");
        ObjectNode broken = new Case().build();
        member(broken, "selection").remove("status");
        assertFallback(submit(broken), SOURCE, "SELECTION_CONTRACT_INVALID");
        ObjectNode wrongActor = new Case().build();
        member(wrongActor, "selection").put("actorId", ACTORS[1]);
        assertFallback(submit(wrongActor), SOURCE, "SELECTION_CONTRACT_INVALID");
    }

    @Test
    void anEditBelongsToTheEditingWorkProductAndItsRootAndItsItemsAreJudgedApart() {
        // One edit is broken (it has no replacement): the work product's own members hold, so only the edit is faulted, and the sound edit is still listed as retained.
        ObjectNode brokenItem = new Case().build();
        ((ArrayNode) member(brokenItem, "editing").get("edits")).add(TestJson.object("id", "edit-2", "unitId", "unit-0001", "sourceDigest", hex(SOURCE), "start", 3, "end", 4, "actorId", ACTORS[1]));
        Outcome item = submit(brokenItem);
        assertFallback(item, SOURCE, "EDIT_CONTRACT_INVALID");
        assertFalse(warnings(item).contains("SELECTION_DIGEST_MISMATCH"));
        assertEquals(List.of(hex("edit-1")), strings(receiptOf(item).get("decisions").get("retainedEditDigests")));
        // The work product's own member breaks: the editing actor is not the plan's.
        ObjectNode wrongActor = new Case().build();
        member(wrongActor, "editing").put("actorId", ACTORS[0]);
        assertFallback(submit(wrongActor), SOURCE, "EDIT_CONTRACT_INVALID");
    }

    @Test
    void editsThatRepeatAnIdOverlapOrDisagreeWithTheCandidateFallBack() {
        Case repeated = new Case();
        repeated.issueEnd = 6;
        repeated.edits = List.of(new EditSpec("edit-1", 0, 1, "저"), new EditSpec("edit-1", 3, 4, "분"));
        assertFallback(submit(repeated.build()), SOURCE, "EDIT_CONTRACT_INVALID");
        Case overlapping = new Case();
        overlapping.edits = List.of(new EditSpec("edit-1", 0, 1, "저"), new EditSpec("edit-2", 0, 2, "저거"));
        assertFallback(submit(overlapping.build()), SOURCE, "EDIT_RANGES_OVERLAP");
        Case digest = new Case();
        digest.candidateDigest = ZEROS;
        assertFallback(submit(digest.build()), SOURCE, "EDITING_DIGEST_MISMATCH");
    }

    @Test
    void theVerificationIsBoundToTheEditingTheRubricAndTheActors() {
        ObjectNode digest = new Case().build();
        member(digest, "verification").put("editingDigest", ZEROS);
        assertFallback(submit(digest), SOURCE, "VERIFICATION_DIGEST_MISMATCH");
        ObjectNode rubric = new Case().build();
        rubric.put("rubricDigest", "b".repeat(64));
        assertFallback(submit(rubric), SOURCE, "VERIFICATION_RUBRIC_DIGEST_MISMATCH");
        ObjectNode noRubric = new Case().build();
        noRubric.remove("rubricDigest");
        assertFallback(submit(noRubric), SOURCE, "VERIFICATION_RUBRIC_DIGEST_MISMATCH");
        ObjectNode shape = new Case().build();
        member(shape, "verification").remove("assessment");
        assertFallback(submit(shape), SOURCE, "VERIFICATION_CONTRACT_INVALID");
        ObjectNode wrongActor = new Case().build();
        member(wrongActor, "verification").put("actorId", ACTORS[0]);
        assertFallback(submit(wrongActor), SOURCE, "VERIFICATION_CONTRACT_INVALID");
    }

    @Test
    void theVerifiersGlobalFallbackAndAnUnsafeAssessmentSendTheSourceBack() {
        Case global = new Case();
        global.globalDecision = "fallback";
        assertFallback(submit(global.build()), SOURCE, "VERIFIER_GLOBAL_FALLBACK");
        Case unsafe = new Case();
        unsafe.assessment = assessment("pass", "original");
        assertFallback(submit(unsafe.build()), SOURCE, "VERIFICATION_CONTRACT_INVALID");
        Case major = new Case();
        major.assessment = assessment("uncertain", "candidate");
        assertFallback(submit(major.build()), SOURCE, "VERIFICATION_CONTRACT_INVALID");
    }

    @Test
    void aDecisionThatIsBrokenOrAboutNoEditIsFaultedAloneAndTheVerificationRootStaysSound() {
        Case ghost = new Case();
        ghost.decisions = List.of(accept("edit-1"), accept("ghost"));
        Outcome unknown = submit(ghost.build());
        assertFallback(unknown, SOURCE, "VERIFICATION_DECISION_INVALID");
        assertFalse(warnings(unknown).contains("VERIFICATION_CONTRACT_INVALID"));
        Case brokenDecision = new Case();
        brokenDecision.decisions = List.of(TestJson.object("editId", "edit-1", "decision", "accept", "reasonCode", "OTHER", "sourceDefect", "REDUNDANCY", "invariantDelta", "NONE"));
        Outcome broken = submit(brokenDecision.build());
        assertFallback(broken, SOURCE, "VERIFICATION_DECISION_INVALID");
        assertFalse(warnings(broken).contains("VERIFICATION_CONTRACT_INVALID"));
        assertFalse(warnings(broken).contains("VERIFICATION_DIGEST_MISMATCH"));
        Case twice = new Case();
        twice.decisions = List.of(accept("edit-1"), accept("edit-1"));
        assertFallback(submit(twice.build()), SOURCE, "VERIFICATION_DECISION_INVALID");
    }

    // ---- finalize: a request that is itself wrong is refused ----------------------------------------------------------------------------------

    @Test
    void finalizerRefusesAMalformedPlanOutright() {
        ObjectNode request = new Case().build();
        member(member(member(request, "plan"), "providers"), "finalization").put("entrypoint", "elsewhere");
        refused(submit(request), KEY + "finalizerProvider");
    }

    @Test
    void finalizerRefusesEveryRuleOfThePlanAndOfTheRequest() {
        ObjectNode noSubagents = new Case().build();
        noSubagents.put("subagentsAvailable", false);
        refused(submit(noSubagents), KEY + "threeActorsRequired");
        ObjectNode version = new Case().build();
        member(version, "plan").put("schemaVersion", "2.0.0");
        refused(submit(version), KEY + "planSchemaVersionUnsupported");
        ObjectNode two = new Case().build();
        member(two, "plan").set("actorIds", TestJson.array(List.of(ACTORS[0], ACTORS[1])));
        refused(submit(two), KEY + "actorIdsCount");
        ObjectNode format = new Case().build();
        member(format, "plan").set("actorIds", TestJson.array(List.of("not-a-uuid", ACTORS[1], ACTORS[2])));
        refused(submit(format), KEY + "actorIdsFormat");
        ObjectNode unique = new Case().build();
        member(unique, "plan").set("actorIds", TestJson.array(List.of(ACTORS[0], ACTORS[0], ACTORS[2])));
        refused(submit(unique), KEY + "actorIdsUnique");
        ObjectNode mismatch = new Case().build();
        member(member(member(mismatch, "plan"), "providers"), "selection").put("actorId", ACTORS[1]);
        refused(submit(mismatch), KEY + "providerActorMismatch");
        ObjectNode actor = new Case().build();
        member(member(member(actor, "plan"), "providers"), "finalization").put("actorId", ACTORS[0]);
        refused(submit(actor), KEY + "finalizerActor");
        ObjectNode noProviders = new Case().build();
        member(noProviders, "plan").remove("providers");
        refused(submit(noProviders), KEY + "mustBeObject");
        ObjectNode noPlan = new Case().build();
        noPlan.remove("plan");
        refused(submit(noPlan), KEY + "mustBeObject");
        ObjectNode requestVersion = new Case().build();
        requestVersion.put("schemaVersion", "2.0.0");
        refused(submit(requestVersion), FINAL_KEY + "requestSchemaVersionUnsupported");
        ObjectNode mode = new Case().build();
        mode.put("mode", "elsewhere");
        refused(submit(mode), FINAL_KEY + "modeInvalid");
        ObjectNode noSource = new Case().build();
        noSource.remove("source");
        refused(submit(noSource), FINAL_KEY + "sourceNotString");
        for (String document : List.of("manifest", "sourceUnitManifest", "selection", "editing", "verification")) {
            ObjectNode notAnObject = new Case().build();
            notAnObject.put(document, "not an object");
            refused(submit(notAnObject), KEY + "mustBeObject");
            ObjectNode missing = new Case().build();
            missing.remove(document);
            refused(submit(missing), KEY + "mustBeObject");
        }
    }
}
