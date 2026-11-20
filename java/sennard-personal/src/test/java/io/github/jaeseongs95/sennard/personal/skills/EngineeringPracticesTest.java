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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.server.skills.SkillCheckService;
import io.github.jaeseongs95.sennard.server.skills.SkillCheckService.Outcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frozen-evidence rules of test-engineering and code-review through {@link SkillCheckService}, the MCP tool's own entry, with the real file boundary. This server does
 * not run a test command, so the receipts a proof names are made here the way a caller's own run would write them: a JSON document with the digests of what it records.
 * The cases are the old rules' cases: a passing document for each command, and for each rule a document that breaks only that rule.
 */
class EngineeringPracticesTest {
    private static final String INVALID = "INVALID_INPUT";
    private static final String INTEGRITY = "INTEGRITY_FAILED";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String P = "practices.core.";
    private static final String IO = "practices.io.";
    private static final String STAGE = "practices.stageBundle.";
    private static final String SCHEMA = "practices.schema.validationFailed";
    /** The attachment a provider result below names its artifact by (a UUID, as the artifact tools make it). */
    private static final String ATTACHMENT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String PAGE = "sample/page.txt";
    private static final String CHECK = "sample/Check.txt";
    private static final String WITNESS = "ERR_ASSERTION";
    private static final String REQ = "R-PREFIX";
    private static final String TEST_STAGE = "test-sensitivity-review";
    private static final String CODE_STAGE = "change-code-review";
    private static final List<String> ARGV = List.of("tool", "check.txt");
    private static final int MIB = 1024 * 1024;

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
        // The class's statics outlive it in the test JVM: let go of the context's beans (the schema registry among them).
        context = null;
        service = null;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------- helpers

    private static Outcome run(String skill, String command, Object... pairs) {
        return service.run(skill, command, TestJson.object(pairs));
    }

    private static JsonNode value(Outcome outcome) {
        assertNotNull(outcome.body().get("value"), () -> "a refused call: " + outcome.body());
        return outcome.body().get("value");
    }

    private static ObjectNode copy(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    /** A refused input: exit 2, the wire code, the catalog key, and a sentence the catalog really has (a missing key would be answered as the key itself). */
    private static void refused(Outcome outcome, String code, String key) {
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("ERROR", outcome.body().get("status").stringValue(), () -> outcome.body().toString());
        assertEquals(code, outcome.body().get("code").stringValue(), () -> outcome.body().toString());
        assertEquals(key, outcome.body().get("messageKey").stringValue(), () -> outcome.body().toString());
        assertNotEquals(key, outcome.body().get("message").stringValue(), () -> "the catalog has no sentence for " + key);
    }

    private static ObjectNode seal(ObjectNode document) {
        document.remove("digest");
        document.put("digest", TestJson.hashJson(document));
        return document;
    }

    /** A file under the root and the reference to it that a document carries: its path and the digest of its raw bytes. */
    private static ObjectNode write(Path root, String relative, JsonNode document) throws IOException {
        byte[] bytes = (TestJson.MAPPER.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return TestJson.object("path", relative, "digest", TestJson.hashBytes(bytes));
    }

    private static ObjectNode snapshot(Path root, List<String> files) {
        return copy(value(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(files))));
    }

    private static ObjectNode snapshotWithout(JsonNode snapshot, String path) {
        ObjectNode copy = copy(snapshot);
        List<JsonNode> kept = TestJson.items(copy.get("files")).stream().filter(file -> !path.equals(file.get("path").stringValue())).toList();
        copy.set("files", TestJson.array(kept));
        return seal(copy);
    }

    private static ObjectNode receipt(JsonNode snapshot, String status, Integer exit, String stdout, String stderr, String started, String finished) {
        ObjectNode receipt = TestJson.MAPPER.createObjectNode();
        receipt.put("schemaVersion", "2.0.0");
        receipt.put("kind", "engineering-run-receipt");
        receipt.set("argv", TestJson.array(ARGV));
        receipt.set("snapshot", copy(snapshot));
        receipt.put("afterDigest", snapshot.get("digest").stringValue());
        receipt.put("startedAt", started);
        receipt.put("finishedAt", finished);
        receipt.put("status", status);
        if (exit == null) {
            receipt.putNull("exitCode");
        } else {
            receipt.put("exitCode", exit.intValue());
        }
        receipt.put("stdout", stdout);
        receipt.put("stderr", stderr);
        receipt.put("stdoutDigest", TestJson.hashText(stdout));
        receipt.put("stderrDigest", TestJson.hashText(stderr));
        receipt.put("timeoutMs", 30000);
        receipt.put("executable", ARGV.getFirst());
        receipt.put("recorderRuntime", "25.0.5+8");
        receipt.put("platform", "test-platform");
        receipt.put("environmentNote", "judgment case");
        return seal(receipt);
    }

    @FunctionalInterface
    private interface Mutation<T> {
        void apply(T target) throws IOException;
    }

    /** One document that breaks one rule: what it is, the code and the catalog key the refusal must carry, and how the passing document is broken. */
    private record Case<T>(String label, String code, String key, Mutation<T> mutate) {
    }

    // ------------------------------------------------------------------------------------------------------------------- the files of a test proof

    /** A plan with one case, a candidate whose production file changed between the red and the green run, and the two receipts, all on disk under the root. */
    private static final class Evidence {
        final Path root;
        ObjectNode base;
        ObjectNode head;
        ObjectNode plan;
        ObjectNode proof;
        ObjectNode red;
        ObjectNode green;

        private Evidence(Path root) {
            this.root = root;
        }

        static Evidence create(Path root) throws IOException {
            Files.createDirectories(root.resolve("sample"));
            Files.writeString(root.resolve(PAGE), "limit + 1\n");
            Files.writeString(root.resolve(CHECK), "check\n");
            Evidence evidence = new Evidence(root);
            List<String> files = List.of(PAGE, CHECK);
            evidence.base = snapshot(root, files);
            Files.writeString(root.resolve(PAGE), "limit\n");
            evidence.head = snapshot(root, files);
            evidence.red = receipt(evidence.base, "FAIL", 1, "", "boom " + WITNESS + "\n", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z");
            evidence.green = receipt(evidence.head, "PASS", 0, "ok\n", "", "2026-01-01T00:00:02.000Z", "2026-01-01T00:00:03.000Z");
            ObjectNode theCase = TestJson.object("id", "PAGE-1", "requirementIds", TestJson.array(List.of(REQ)), "scenario", "Three items with limit two.", "entryPoint", "page(items, limit)", "level", "unit",
                    "expectedBehavior", "Returns the prefix.", "oracle", "Literal expected prefix derived from the frozen requirement.", "testFiles", TestJson.array(List.of(CHECK)), "required", true,
                    "sensitivity", "red-green", "sensitivityReason", "An off-by-one mutation must be detected by the same test.", "argv", TestJson.array(ARGV));
            evidence.plan = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-plan", "taskId", "demo-page", "contractDigest", TestJson.hashText("task"), "requirements",
                    TestJson.array(List.of(REQ)), "scopeFiles", TestJson.array(files), "runner", "java", "environment", "Local offline JDK, no external dependencies.", "cases", TestJson.array(List.of(theCase)),
                    "exclusions", TestJson.array(List.of("Negative limits are outside this example.")));
            ObjectNode entry = TestJson.object("caseId", "PAGE-1", "status", "DEMONSTRATED", "red", emptyRef(), "green", emptyRef(), "mutationPaths", TestJson.array(List.of(PAGE)), "assertionWitness", WITNESS,
                    "reason", "The red run shows the extra item; the same test passes after removing it.");
            evidence.proof = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-proof", "planDigest", TestJson.hashText(""), "candidate", copy(evidence.head), "proofs", TestJson.array(List.of(entry)));
            evidence.sync();
            return evidence;
        }

        /** The receipts as files, their references in the proof, and the proof bound to the plan as it is now. */
        void sync() throws IOException {
            ObjectNode entry = entry();
            entry.set("red", write(root, "evidence/red.json", red));
            entry.set("green", write(root, "evidence/green.json", green));
            proof.put("planDigest", TestJson.hashJson(plan));
        }

        ObjectNode entry() {
            return (ObjectNode) proof.get("proofs").get(0);
        }

        ObjectNode candidate() {
            return (ObjectNode) proof.get("candidate");
        }

        ObjectNode theCase() {
            return (ObjectNode) plan.get("cases").get(0);
        }

        Outcome checkProof() {
            return run("test-engineering", "check-proof", "root", root.toString(), "plan", plan, "proof", proof);
        }
    }

    private static ObjectNode emptyRef() {
        return TestJson.object("path", "", "digest", TestJson.hashText(""));
    }

    private static Mutation<Evidence> red(Consumer<ObjectNode> change, boolean reseal) {
        return evidence -> {
            change.accept(evidence.red);
            if (reseal) {
                seal(evidence.red);
            }
            evidence.sync();
        };
    }

    private static Mutation<Evidence> green(Consumer<ObjectNode> change, boolean reseal) {
        return evidence -> {
            change.accept(evidence.green);
            if (reseal) {
                seal(evidence.green);
            }
            evidence.sync();
        };
    }

    private static void notRun(Evidence evidence, String status, String reason) {
        ObjectNode entry = evidence.entry();
        entry.put("status", status);
        entry.set("red", emptyRef());
        entry.set("green", emptyRef());
        entry.set("mutationPaths", TestJson.array(List.of()));
        entry.put("assertionWitness", "");
        entry.put("reason", reason);
    }

    // ------------------------------------------------------------------------------------------------------------ the files of a code review request

    /** A request over three changed files (one deleted, as the head says), a report that reviews all of them, and the files on disk. */
    private static final class ReviewEvidence {
        static final String A = "src/a.txt";
        static final String B = "src/b.txt";
        static final String OLD = "src/old.txt";

        final Path root;
        ObjectNode base;
        ObjectNode head;
        ObjectNode request;
        ObjectNode report;

        private ReviewEvidence(Path root) {
            this.root = root;
        }

        static ReviewEvidence create(Path root, boolean oldDeleted) throws IOException {
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve(A), "a1\nb\n");
            Files.writeString(root.resolve(B), "x1\n");
            Files.writeString(root.resolve(OLD), "old\n");
            ReviewEvidence evidence = new ReviewEvidence(root);
            evidence.base = snapshot(root, List.of(A, B, OLD));
            Files.writeString(root.resolve(A), "a2\nb\nc\n");
            Files.writeString(root.resolve(B), "x2\n");
            evidence.head = snapshot(root, List.of(A, B));
            if (oldDeleted) {
                Files.delete(root.resolve(OLD));
            }
            evidence.request = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-request", "taskId", "demo-page", "contractDigest", TestJson.hashText("task"), "base", copy(evidence.base),
                    "head", copy(evidence.head), "changedFiles", TestJson.array(List.of(A, B, OLD)), "excludedFiles", TestJson.array(List.of()), "requirements", TestJson.array(List.of(REQ)));
            evidence.report = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-report", "requestDigest", TestJson.hashText(""), "headDigest", TestJson.hashText(""), "reviewer", "reviewer-1",
                    "independence", "separate-reviewer", "coverage", TestJson.array(List.of(coverage(A, "REVIEWED"), coverage(B, "REVIEWED"), coverage(OLD, "REVIEWED"))), "findings", TestJson.array(List.of()),
                    "openQuestions", TestJson.array(List.of()), "handoffs", TestJson.array(List.of()));
            evidence.sync();
            return evidence;
        }

        static ObjectNode coverage(String path, String status) {
            return TestJson.object("path", path, "status", status, "reason", "Read the whole file against the declared requirement.");
        }

        /** The report bound to the request and head as they are now. */
        void sync() {
            report.put("requestDigest", TestJson.hashJson(request));
            report.put("headDigest", head.get("digest").stringValue());
        }

        ArrayNode findings() {
            return (ArrayNode) report.get("findings");
        }

        ArrayNode coverageList() {
            return (ArrayNode) report.get("coverage");
        }

        Outcome checkReview() {
            return run("code-review", "check-review", "root", root.toString(), "request", request, "report", report);
        }
    }

    private static ObjectNode finding(String id, String path, String side, int start, int end, String category, String confidence, boolean blocking) {
        return TestJson.object("id", id, "path", path, "side", side, "lineStart", start, "lineEnd", end, "category", category, "severity", "medium", "confidence", confidence, "blocking", blocking, "title", "A title",
                "contract", "The contract the code must keep.", "trace", "How the code breaks it.", "impact", "What a caller sees.", "correction", "What to change.");
    }

    // ------------------------------------------------------------------------------------------------------------------------- the two skills and the commands

    @Test
    void bothSkillsAnswerTheSameCommandsAndRunLeavesTheRequestThread() {
        assertTrue(service.checks().containsKey("test-engineering"));
        assertTrue(service.checks().containsKey("code-review"));
        for (String skill : List.of("test-engineering", "code-review")) {
            for (String command : List.of("self-check", "catalog", "snapshot", "check-plan", "check-proof", "check-review", "provider-result", "check-stage-bundle")) {
                Outcome outcome = run(skill, command);
                assertNotEquals("skillCheck.unknownCommand", outcome.exitCode() == 2 ? outcome.body().get("messageKey").stringValue() : "", skill + " " + command);
                assertFalse(service.runsInBackground(skill, command), skill + " " + command);
            }
            // run starts a caller's program, so it leaves the request's thread.
            assertTrue(service.runsInBackground(skill, "run"), skill);
        }
    }

    @Test
    void selfCheckAndCatalogAnswerFromThePackagedCatalog() {
        JsonNode self = value(run("test-engineering", "self-check"));
        assertEquals("CATALOG_CONSISTENT", self.get("status").stringValue());
        assertTrue(self.get("rules").intValue() > 0);
        assertEquals("networknt-2020-12", self.get("schemaEngine").stringValue());
        assertEquals("NOT_RUN", self.get("hostBehaviorEvaluation").stringValue());
        List<String> modules = TestJson.strings(self.get("modules"));
        assertTrue(modules.containsAll(List.of("debugging", "verification")), modules::toString);

        JsonNode whole = value(run("code-review", "catalog"));
        assertEquals("1.0.0", whole.get("schemaVersion").stringValue());
        assertEquals(self.get("rules").intValue(), whole.get("rules").size());
        assertEquals(Set.of("id", "module", "owner", "title", "appliesWhen", "action", "evidence", "avoid", "exceptions", "contentStatus", "behaviorEvaluation"), Set.copyOf(whole.get("rules").get(0).propertyNames()));

        JsonNode verification = value(run("code-review", "catalog", "module", "verification"));
        assertTrue(verification.get("rules").size() > 0 && verification.get("rules").size() < whole.get("rules").size());
        TestJson.items(verification.get("rules")).forEach(rule -> assertEquals("verification", rule.get("module").stringValue()));
        refused(run("code-review", "catalog", "module", "no-such-module"), INVALID, "practices.cli.unknownModule");
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------- snapshots

    @Test
    void aSnapshotBindsTheDeclaredFilesByDigestSizeAndLineCountInPathOrder(@TempDir Path root) throws IOException {
        byte[][] contents = {"one\ntwo\n".getBytes(StandardCharsets.UTF_8), "one\ntwo".getBytes(StandardCharsets.UTF_8), "\n".getBytes(StandardCharsets.UTF_8), new byte[0],
                "a\u0000b\n".getBytes(StandardCharsets.UTF_8), {(byte) 0xE9, '\n'}, "한글\nxx\n".getBytes(StandardCharsets.UTF_8), "\uFEFFa\n".getBytes(StandardCharsets.UTF_8)};
        int[] lines = {2, 2, 1, 0, 0, 0, 2, 1};
        List<String> names = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            names.add("f" + i + ".txt");
            Files.write(root.resolve("f" + i + ".txt"), contents[i]);
        }
        List<String> declared = new ArrayList<>(names);
        Collections.reverse(declared);
        JsonNode snapshot = value(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(declared)));
        List<JsonNode> files = TestJson.items(snapshot.get("files"));
        assertEquals(names, files.stream().map(file -> file.get("path").stringValue()).toList());
        List<Object> expected = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            JsonNode file = files.get(i);
            assertEquals(TestJson.hashBytes(contents[i]), file.get("digest").stringValue(), names.get(i));
            assertEquals(contents[i].length, file.get("size").intValue(), names.get(i));
            assertEquals(lines[i], file.get("lineCount").intValue(), names.get(i));
            expected.add(TestJson.object("path", names.get(i), "digest", TestJson.hashBytes(contents[i]), "size", contents[i].length, "lineCount", lines[i]));
        }
        assertEquals("1.0.0", snapshot.get("schemaVersion").stringValue());
        assertEquals("engineering-snapshot", snapshot.get("kind").stringValue());
        // The digest is the one of the document without it, in canonical form, taken here without any of the check's own types.
        ObjectNode body = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-snapshot", "files", TestJson.array(expected));
        assertEquals(TestJson.hashJson(body), snapshot.get("digest").stringValue());
    }

    @Test
    void aSnapshotRefusesWhatARequestCannotNameAndWhatItCannotHold(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.txt"), "a\n");
        for (String relative : List.of("../a.json", "/a.json", "a\\b", "C:/x", "x/./a", "nul", "con.txt", "a.", "a ", "a\u0001b")) {
            Outcome outcome = run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of(relative)));
            assertEquals(2, outcome.exitCode(), relative);
            assertEquals(INVALID, outcome.body().get("code").stringValue(), relative);
        }
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("missing.json"))), NOT_FOUND, IO + "fileNotFound");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of())), INVALID, P + "emptyPathSet");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of(".git/config"))), INVALID, P + "gitInternalsAreOutOfSnapshot");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("a.txt", "A.TXT"))), INVALID, P + "duplicatePaths");
        refused(run("test-engineering", "snapshot", "root", root.toString()), INVALID, "skillCheck.argumentRequired");
        refused(run("test-engineering", "snapshot", "files", TestJson.array(List.of("a.txt"))), INVALID, "skillCheck.argumentRequired");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", "a.txt"), INVALID, "skillCheck.argumentMustBeArray");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= 1000; i++) {
            tooMany.add(String.format("many/f%04d.txt", i));
        }
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(tooMany)), INVALID, P + "snapshotIsLimitedTo1000Declared");

        Files.write(root.resolve("big.bin"), new byte[8 * MIB + 1]);
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("big.bin"))), INVALID, IO + "artifactExceedsTheByteLimit");

        List<String> nine = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            Files.write(root.resolve("part" + i + ".bin"), new byte[8 * MIB]);
            nine.add("part" + i + ".bin");
        }
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(nine)), INVALID, P + "snapshotExceeds64Mib");
        assertEquals(0, run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(nine.subList(0, 8))).exitCode());
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------- the plan

    @Test
    void aPlanThatFreezesRequirementsAndCasesIsReady(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        Outcome outcome = run("test-engineering", "check-plan", "root", root.toString(), "plan", evidence.plan);
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode result = value(outcome);
        assertEquals("engineering-plan-result", result.get("kind").stringValue());
        assertEquals("READY", result.get("verdict").stringValue());
        assertEquals(TestJson.hashJson(evidence.plan), result.get("planDigest").stringValue());
        assertEquals(List.of("PAGE-1"), TestJson.strings(result.get("caseIds")));
    }

    @Test
    void aPlanOrAProofNamedByAFileIsReadFromTheRootOrFromAnAbsolutePath(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        write(root, "plan.json", evidence.plan);
        write(root, "proof.json", evidence.proof);
        assertEquals("READY", value(run("test-engineering", "check-plan", "root", root.toString(), "plan", "plan.json")).get("verdict").stringValue());
        assertEquals("READY", value(run("test-engineering", "check-plan", "plan", TestJson.object("file", root.resolve("plan.json").toString()))).get("verdict").stringValue());
        Outcome proof = run("code-review", "check-proof", "root", root.toString(), "plan", "plan.json", "proof", "proof.json");
        assertEquals(0, proof.exitCode(), () -> proof.body().toString());
        refused(run("test-engineering", "check-plan", "plan", "plan.json"), INVALID, "skillCheck.argumentRequired");
        refused(run("test-engineering", "check-plan", "root", root.toString()), INVALID, "skillCheck.argumentRequired");
        refused(run("test-engineering", "check-plan", "root", root.toString(), "plan", "missing.json"), NOT_FOUND, IO + "fileNotFound");
    }

    /** A path that names nothing is NOT_FOUND with its own sentence; a path that names something that is not a regular file below directories keeps the format refusal. */
    @Test
    void aMissingFileIsNotFoundAndADirectoryInItsPlaceIsAFormatViolation(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.txt"), "a\n");
        Files.createDirectory(root.resolve("dir"));
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("missing.txt"))), NOT_FOUND, IO + "fileNotFound");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("no-such-dir/missing.txt"))), NOT_FOUND, IO + "fileNotFound");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("dir"))), INVALID, IO + "onlyRegularFilesBeneathDirectoriesAre");
        refused(run("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(List.of("a.txt/below-a-file.txt"))), INVALID, IO + "onlyRegularFilesBeneathDirectoriesAre");
    }

    @Test
    void aPlanIsRefusedForEachRuleItBreaks(@TempDir Path root) throws IOException {
        List<Case<Evidence>> cases = new ArrayList<>();
        cases.add(new Case<>("a case without its oracle (the contract)", INVALID, SCHEMA, e -> e.theCase().remove("oracle")));
        cases.add(new Case<>("a repeated requirement", INVALID, P + "duplicateIdentifier", e -> e.plan.set("requirements", TestJson.array(List.of(REQ, REQ)))));
        cases.add(new Case<>("a blank requirement", INVALID, P + "duplicateIdentifier", e -> e.plan.set("requirements", TestJson.array(List.of(REQ, "  ")))));
        cases.add(new Case<>("a git internal in the scope", INVALID, P + "gitInternalsAreOutOfSnapshot", e -> ((ArrayNode) e.plan.get("scopeFiles")).add(".git/hooks/x")));
        cases.add(new Case<>("a scope path that differs only in case", INVALID, P + "duplicatePaths", e -> ((ArrayNode) e.plan.get("scopeFiles")).add("SAMPLE/PAGE.TXT")));
        cases.add(new Case<>("a scope path that leaves the root", INVALID, IO + "unsafePathComponent", e -> ((ArrayNode) e.plan.get("scopeFiles")).add("../x")));
        cases.add(new Case<>("a backslash in the scope", INVALID, IO + "absoluteDriveBackslashControlAndStream", e -> ((ArrayNode) e.plan.get("scopeFiles")).add("a\\b")));
        cases.add(new Case<>("two cases with one id", INVALID, IO + "duplicateEntry", e -> ((ArrayNode) e.plan.get("cases")).add(copy(e.theCase()))));
        cases.add(new Case<>("a case that names a requirement twice", INVALID, P + "duplicateIdentifier", e -> e.theCase().set("requirementIds", TestJson.array(List.of(REQ, REQ)))));
        cases.add(new Case<>("a case that names a requirement the plan does not have", INVALID, P + "unknownRequirementInCase", e -> e.theCase().set("requirementIds", TestJson.array(List.of(REQ, "R-OTHER")))));
        cases.add(new Case<>("a test file outside the scope", INVALID, P + "caseTestFileOutsideScope", e -> e.theCase().set("testFiles", TestJson.array(List.of("sample/Other.txt")))));
        cases.add(new Case<>("a NUL in the frozen argv", INVALID, P + "nulInFrozenCaseArgv", e -> e.theCase().set("argv", TestJson.array(List.of("tool", "a\u0000b")))));
        cases.add(new Case<>("a requirement no case covers", INVALID, P + "aRequirementHasNoTestCase", e -> {
            e.plan.set("requirements", TestJson.array(List.of(REQ, "R-OTHER")));
        }));
        cases.add(new Case<>("a blank oracle", INVALID, P + "caseIdentityOracleAndSensitivityRationale", e -> e.theCase().put("oracle", "   ")));
        cases.add(new Case<>("a blank sensitivity reason", INVALID, P + "caseIdentityOracleAndSensitivityRationale", e -> e.theCase().put("sensitivityReason", "   ")));
        cases.add(new Case<>("a blank case id", INVALID, P + "caseIdentityOracleAndSensitivityRationale", e -> e.theCase().put("id", "  ")));
        for (int i = 0; i < cases.size(); i++) {
            Case<Evidence> broken = cases.get(i);
            Evidence evidence = Evidence.create(root.resolve("plan" + i));
            broken.mutate().apply(evidence);
            Outcome outcome = run("test-engineering", "check-plan", "root", evidence.root.toString(), "plan", evidence.plan);
            assertEquals(broken.key(), outcome.body().path("messageKey").stringValue(), broken.label() + ": " + outcome.body());
            refused(outcome, broken.code(), broken.key());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------- the proof (check-proof)

    @Test
    void aProofOfTheFrozenPlanWithAFailingRedAndAPassingGreenIsConsistent(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        Outcome outcome = evidence.checkProof();
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode result = value(outcome);
        assertEquals("engineering-test-result", result.get("kind").stringValue());
        assertEquals("CONSISTENT", result.get("verdict").stringValue());
        assertEquals(evidence.head.get("digest").stringValue(), result.get("targetDigest").stringValue());
        assertEquals(TestJson.hashJson(evidence.proof), result.get("reportDigest").stringValue());
        assertEquals(0, result.get("issues").size());
        assertEquals(3, result.get("limitations").size());
        assertTrue(result.get("limitations").get(0).stringValue().contains("unsigned local consistency check"));
    }

    @Test
    void aSnapshotChangedAfterCaptureIsRefusedByTheProofAndByTheReview(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root.resolve("proof"));
        Files.writeString(evidence.root.resolve(PAGE), "changed after capture\n");
        refused(evidence.checkProof(), INTEGRITY, P + "currentFilesDifferFromTheDeclared");
        ReviewEvidence review = ReviewEvidence.create(root.resolve("review"), true);
        Files.writeString(review.root.resolve(ReviewEvidence.A), "changed after capture\n");
        refused(review.checkReview(), INTEGRITY, P + "currentFilesDifferFromTheDeclared");
    }

    @Test
    void aProofIsRefusedForEachRuleItBreaks(@TempDir Path root) throws IOException {
        List<Case<Evidence>> cases = new ArrayList<>();
        // the proof and its plan
        cases.add(new Case<>("a proof of another plan", INTEGRITY, P + "proofBelongsToADifferentFrozen", e -> e.proof.put("planDigest", TestJson.hashText("another plan"))));
        cases.add(new Case<>("a proof without its candidate (the contract)", INVALID, SCHEMA, e -> e.proof.remove("candidate")));
        cases.add(new Case<>("a candidate whose scope differs from the plan's", INVALID, P + "candidateFileSetDiffersFromThe", e -> {
            ((ArrayNode) e.plan.get("scopeFiles")).add("sample/Other.txt");
            e.sync();
        }));
        cases.add(new Case<>("a proof of another case", INVALID, P + "proofCasesDoNotExactlyMatch", e -> {
            ObjectNode other = copy(e.entry());
            other.put("caseId", "OTHER");
            e.proof.set("proofs", TestJson.array(List.of(other)));
        }));
        cases.add(new Case<>("two proofs of one case", INVALID, IO + "duplicateEntry", e -> e.proof.set("proofs", TestJson.array(List.of(copy(e.entry()), copy(e.entry()))))));
        // the candidate snapshot
        cases.add(new Case<>("a candidate digest that is not its files'", INTEGRITY, P + "snapshotDigestMismatch", e -> e.candidate().put("digest", TestJson.hashText("x"))));
        cases.add(new Case<>("a candidate whose paths are not sorted", INVALID, P + "snapshotPathsMustBeSorted", e -> {
            List<JsonNode> files = new ArrayList<>(TestJson.items(e.candidate().get("files")));
            Collections.reverse(files);
            e.candidate().set("files", TestJson.array(files));
        }));
        cases.add(new Case<>("a candidate file with a negative size", INVALID, P + "invalidSnapshotSizesOrLines", e -> ((ObjectNode) e.candidate().get("files").get(0)).put("size", -1)));
        cases.add(new Case<>("a candidate file with more lines than bytes", INVALID, P + "invalidSnapshotSizesOrLines", e -> ((ObjectNode) e.candidate().get("files").get(0)).put("lineCount", 999)));
        cases.add(new Case<>("a candidate with a git internal", INVALID, P + "gitInternalsAreOutOfSnapshot",
                e -> ((ArrayNode) e.candidate().get("files")).add(TestJson.object("path", ".git/config", "digest", TestJson.hashText("x"), "size", 1, "lineCount", 1))));
        cases.add(new Case<>("a candidate with a path that differs only in case", INVALID, P + "duplicatePaths",
                e -> ((ArrayNode) e.candidate().get("files")).add(TestJson.object("path", "SAMPLE/PAGE.TXT", "digest", TestJson.hashText("x"), "size", 1, "lineCount", 1))));
        cases.add(new Case<>("a candidate of 1001 files", INVALID, P + "tooManySnapshotFiles", e -> {
            List<JsonNode> files = new ArrayList<>();
            for (int i = 0; i <= 1000; i++) {
                files.add(TestJson.object("path", String.format("big/f%04d.txt", i), "digest", TestJson.hashText("x"), "size", 1, "lineCount", 1));
            }
            e.candidate().set("files", TestJson.array(files));
        }));
        cases.add(new Case<>("a candidate of more than 64 MiB", INVALID, P + "snapshotExceedsTotalBound", e -> {
            List<JsonNode> files = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                files.add(TestJson.object("path", "big/p" + i + ".bin", "digest", TestJson.hashText("x"), "size", 8 * MIB, "lineCount", 0));
            }
            e.candidate().set("files", TestJson.array(files));
        }));
        // a case that was not run, or is exempt
        cases.add(new Case<>("a not-run case that still carries its receipts", INVALID, P + "aNonExecutedProofCannotCarry", e -> e.entry().put("status", "NOT_RUN")));
        cases.add(new Case<>("a not-run case with a blank reason", INVALID, P + "aNonExecutedProofCannotCarry", e -> notRun(e, "NOT_RUN", "   ")));
        cases.add(new Case<>("a not-run case that claims a mutation", INVALID, P + "aNonExecutedProofCannotCarry", e -> {
            notRun(e, "NOT_RUN", "later");
            e.entry().set("mutationPaths", TestJson.array(List.of(PAGE)));
        }));
        cases.add(new Case<>("a not-run case that claims a witness", INVALID, P + "aNonExecutedProofCannotCarry", e -> {
            notRun(e, "NOT_RUN", "later");
            e.entry().put("assertionWitness", WITNESS);
        }));
        cases.add(new Case<>("an exemption the plan did not freeze", INVALID, P + "exemptionWasNotInTheFrozen", e -> notRun(e, "EXEMPT", "not applicable")));
        // the planned method and the two runs
        cases.add(new Case<>("a demonstration where the plan says manual review", INVALID, P + "demonstrationConflictsWithThePlannedMethod", e -> {
            e.theCase().put("sensitivity", "manual-review");
            e.sync();
        }));
        cases.add(new Case<>("one receipt used for red and green", INVALID, P + "distinctRedGreenReceiptsAndA", e -> e.entry().set("green", e.entry().get("red").deepCopy())));
        cases.add(new Case<>("a witness shorter than four characters", INVALID, P + "distinctRedGreenReceiptsAndA", e -> e.entry().put("assertionWitness", "abc")));
        cases.add(new Case<>("a red run that passed", INVALID, P + "proofRequiresOrdinaryFailingRedAnd", red(r -> {
            r.put("status", "PASS");
            r.put("exitCode", 0);
        }, true)));
        cases.add(new Case<>("a green run that failed", INVALID, P + "proofRequiresOrdinaryFailingRedAnd", green(r -> {
            r.put("status", "FAIL");
            r.put("exitCode", 1);
        }, true)));
        cases.add(new Case<>("a red run that timed out (no exit code: the digest still holds with a null member)", INVALID, P + "proofRequiresOrdinaryFailingRedAnd", red(r -> {
            r.put("status", "TIMED_OUT");
            r.putNull("exitCode");
        }, true)));
        cases.add(new Case<>("a red run that could not start", INVALID, P + "proofRequiresOrdinaryFailingRedAnd", red(r -> {
            r.put("status", "SPAWN_ERROR");
            r.putNull("exitCode");
        }, true)));
        cases.add(new Case<>("a green run of another candidate", INTEGRITY, P + "greenEvidenceIsForADifferent", e -> {
            e.green = receipt(e.base, "PASS", 0, "ok\n", "", "2026-01-01T00:00:02.000Z", "2026-01-01T00:00:03.000Z");
            e.sync();
        }));
        cases.add(new Case<>("a red run of the candidate itself (no production change)", INVALID, P + "noProductionMutationIsRepresented", e -> {
            e.red = receipt(e.head, "FAIL", 1, "", "boom " + WITNESS + "\n", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z");
            e.sync();
        }));
        cases.add(new Case<>("red and green with other commands", INVALID, P + "redAndGreenCommandsDiffer", red(r -> r.set("argv", TestJson.array(List.of("tool", "other.txt"))), true)));
        cases.add(new Case<>("red and green with a command other than the frozen one", INVALID, P + "redAndGreenCommandsMustMatch", e -> {
            e.red.set("argv", TestJson.array(List.of("tool", "other.txt")));
            e.green.set("argv", TestJson.array(List.of("tool", "other.txt")));
            seal(e.red);
            seal(e.green);
            e.sync();
        }));
        cases.add(new Case<>("another platform for the green run", INVALID, P + "declaredExecutionEnvironmentsDiffer", green(r -> r.put("platform", "other-platform"), true)));
        cases.add(new Case<>("another environment note for the green run", INVALID, P + "declaredExecutionEnvironmentsDiffer", green(r -> r.put("environmentNote", "another"), true)));
        cases.add(new Case<>("another runtime version for the green run", INVALID, P + "declaredExecutionEnvironmentsDiffer", green(r -> r.put("recorderRuntime", "26+35"), true)));
        cases.add(new Case<>("a green run that began before the red run ended", INVALID, P + "greenMustFollowTheRecordedRed", green(r -> {
            r.put("startedAt", "2026-01-01T00:00:00.500Z");
            r.put("finishedAt", "2026-01-01T00:00:01.500Z");
        }, true)));
        cases.add(new Case<>("a red run over other files", INVALID, P + "redAndGreenFileSetsDiffer", e -> {
            e.red = receipt(snapshotWithout(e.base, CHECK), "FAIL", 1, "", "boom " + WITNESS + "\n", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z");
            e.sync();
        }));
        cases.add(new Case<>("a mutation that names a test file", INVALID, P + "mutationMustExactlyDescribeProductionChanges", e -> e.entry().set("mutationPaths", TestJson.array(List.of(CHECK)))));
        cases.add(new Case<>("a mutation that names a file that did not change", INVALID, P + "mutationMustExactlyDescribeProductionChanges", e -> e.entry().set("mutationPaths", TestJson.array(List.of(PAGE, CHECK)))));
        cases.add(new Case<>("a mutation that names nothing", INVALID, P + "mutationMustExactlyDescribeProductionChanges", e -> e.entry().set("mutationPaths", TestJson.array(List.of()))));
        cases.add(new Case<>("a witness the red log does not hold", INVALID, P + "theClaimedAssertionWitnessIsAbsent", e -> e.entry().put("assertionWitness", "NOT-IN-THE-LOG")));
        // the receipts themselves
        cases.add(new Case<>("a red log that is not the one its digest names", INTEGRITY, P + "receiptLogDigestMismatch", red(r -> r.put("stderr", "tampered"), false)));
        cases.add(new Case<>("a receipt edited after it was sealed", INTEGRITY, P + "receiptDigestMismatch", red(r -> r.put("environmentNote", "edited"), false)));
        cases.add(new Case<>("a receipt snapshot that is not its files'", INTEGRITY, P + "snapshotDigestMismatch", red(r -> ((ObjectNode) r.get("snapshot")).put("digest", TestJson.hashText("x")), true)));
        cases.add(new Case<>("a receipt without its platform (the contract)", INVALID, SCHEMA, red(r -> r.remove("platform"), true)));
        cases.add(new Case<>("a receipt with a NUL in its argv", INVALID, P + "nulInArgv", red(r -> r.set("argv", TestJson.array(List.of("tool", "a\u0000b"))), true)));
        cases.add(new Case<>("a receipt whose executable is not argv[0]", INVALID, P + "receiptExecutableDiffersFromArgv0", red(r -> r.put("executable", "other"), true)));
        cases.add(new Case<>("a receipt with a month 13", INVALID, P + "invalidReceiptTimestamps", red(r -> r.put("startedAt", "2026-13-01T00:00:00.000Z"), true)));
        cases.add(new Case<>("a receipt time without milliseconds", INVALID, P + "invalidReceiptTimestamps", red(r -> r.put("finishedAt", "2026-01-01T00:00:01Z"), true)));
        cases.add(new Case<>("a receipt that finished before it started", INVALID, P + "invalidReceiptTimestamps", red(r -> r.put("finishedAt", "2025-12-31T23:59:59.000Z"), true)));
        cases.add(new Case<>("a receipt timeout of zero", INVALID, P + "invalidTimeoutBound", red(r -> r.put("timeoutMs", 0), true)));
        cases.add(new Case<>("a receipt timeout above 300000", INVALID, P + "invalidTimeoutBound", red(r -> r.put("timeoutMs", 300001), true)));
        cases.add(new Case<>("an exit code beyond a long", INVALID, P + "unsafeExitCode", red(r -> r.put("exitCode", new BigInteger("9223372036854775808")), true)));
        cases.add(new Case<>("a passing receipt with a non-zero exit", INVALID, P + "falsePassingReceipt", green(r -> r.put("exitCode", 1), true)));
        cases.add(new Case<>("a receipt that still has the 5.x member signal (a 2.0.0 receipt has none)", INVALID, SCHEMA, green(r -> r.put("signal", "SIGKILL"), true)));
        cases.add(new Case<>("a receipt of the 5.x contract (1.0.0, nodeVersion and signal)", INVALID, P + "runReceiptVersion1IsNotAccepted", red(r -> {
            r.put("schemaVersion", "1.0.0");
            r.remove("recorderRuntime");
            r.put("nodeVersion", "java-25");
            r.putNull("signal");
        }, true)));
        cases.add(new Case<>("a receipt that names the 5.x member nodeVersion instead of recorderRuntime", INVALID, SCHEMA, red(r -> {
            r.remove("recorderRuntime");
            r.put("nodeVersion", "java-25");
        }, true)));
        cases.add(new Case<>("a passing receipt whose inputs changed", INVALID, P + "falsePassingReceipt", green(r -> r.put("afterDigest", TestJson.hashText("other")), true)));
        cases.add(new Case<>("a failing receipt with exit zero", INVALID, P + "nonAssertingProcessFailureCannotMasquerade", red(r -> r.put("exitCode", 0), true)));
        cases.add(new Case<>("an input-changed receipt whose inputs did not change", INVALID, P + "inputChangedRequiresDifferentInputs", red(r -> r.put("status", "INPUT_CHANGED"), true)));
        // the files the proof names
        cases.add(new Case<>("a receipt changed after the proof named it", INTEGRITY, IO + "artifactDigestMismatch", e -> Files.writeString(e.root.resolve("evidence/red.json"), "{}")));
        cases.add(new Case<>("a receipt path that leaves the root", INVALID, IO + "unsafePathComponent", e -> e.entry().set("red", TestJson.object("path", "../red.json", "digest", TestJson.hashText("x")))));
        cases.add(new Case<>("a demonstrated case without a receipt path", INVALID, IO + "aBoundedRelativePathIsRequired", e -> e.entry().set("red", emptyRef())));
        for (int i = 0; i < cases.size(); i++) {
            Case<Evidence> broken = cases.get(i);
            Evidence evidence = Evidence.create(root.resolve("proof" + i));
            broken.mutate().apply(evidence);
            Outcome outcome = evidence.checkProof();
            assertEquals(broken.key(), outcome.body().path("messageKey").stringValue(), broken.label() + ": " + outcome.body());
            refused(outcome, broken.code(), broken.key());
        }
    }

    @Test
    void aRequiredCaseThatWasNotRunMakesTheProofIncompleteAndAnOptionalOneOnlyNotesIt(@TempDir Path root) throws IOException {
        Evidence required = Evidence.create(root.resolve("required"));
        notRun(required, "NOT_RUN", "the runner is not installed here");
        Outcome incomplete = required.checkProof();
        assertEquals(1, incomplete.exitCode(), () -> incomplete.body().toString());
        assertEquals("INCOMPLETE", value(incomplete).get("verdict").stringValue());
        assertEquals(List.of("REQUIRED PAGE-1: NOT_RUN \u2014 the runner is not installed here"), TestJson.strings(value(incomplete).get("issues")));

        Evidence optional = Evidence.create(root.resolve("optional"));
        optional.theCase().put("required", false);
        optional.sync();
        notRun(optional, "NOT_RUN", "the runner is not installed here");
        Outcome noted = optional.checkProof();
        assertEquals(0, noted.exitCode(), () -> noted.body().toString());
        assertEquals("CONSISTENT", value(noted).get("verdict").stringValue());
        assertEquals(List.of("OPTIONAL PAGE-1: NOT_RUN \u2014 the runner is not installed here"), TestJson.strings(value(noted).get("issues")));
    }

    @Test
    void anExemptionTheFrozenPlanAllowedIsConsistent(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        evidence.theCase().put("sensitivity", "not-applicable");
        evidence.sync();
        notRun(evidence, "EXEMPT", "documentation only");
        Outcome outcome = evidence.checkProof();
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("CONSISTENT", value(outcome).get("verdict").stringValue());
    }

    @Test
    void aMutationProofWithAPlannedMutationSensitivityIsConsistentToo(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        evidence.theCase().put("sensitivity", "mutation");
        evidence.sync();
        assertEquals(0, evidence.checkProof().exitCode());
    }

    // -------------------------------------------------------------------------------------------------------------------------------- the review (check-review)

    @Test
    void aReviewThatAnswersEveryChangedFileWithNothingBlockingHasNoBlockingFindings(@TempDir Path root) throws IOException {
        ReviewEvidence review = ReviewEvidence.create(root, true);
        review.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 3, "defect", "supported", false));
        review.findings().add(finding("F-2", ReviewEvidence.OLD, "base", 1, 1, "recommendation", "hypothesis", false));
        Outcome outcome = review.checkReview();
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        JsonNode result = value(outcome);
        assertEquals("engineering-review-result", result.get("kind").stringValue());
        assertEquals("NO_BLOCKING_FINDINGS", result.get("verdict").stringValue());
        assertEquals(review.head.get("digest").stringValue(), result.get("targetDigest").stringValue());
        assertEquals(TestJson.hashJson(review.report), result.get("reportDigest").stringValue());
        assertEquals(0, result.get("issues").size());
        assertEquals(4, result.get("limitations").size());
        assertTrue(result.get("limitations").get(3).stringValue().contains("not release approval"));
    }

    @Test
    void aBlockingDefectRequestsChangesAndAnUnreviewedFileMakesTheReviewIncomplete(@TempDir Path root) throws IOException {
        ReviewEvidence blocking = ReviewEvidence.create(root.resolve("blocking"), true);
        blocking.findings().add(finding("F-1", ReviewEvidence.B, "head", 1, 1, "defect", "confirmed", true));
        Outcome requested = blocking.checkReview();
        assertEquals(1, requested.exitCode(), () -> requested.body().toString());
        assertEquals("CHANGES_REQUESTED", value(requested).get("verdict").stringValue());

        ReviewEvidence unreviewed = ReviewEvidence.create(root.resolve("unreviewed"), true);
        unreviewed.coverageList().set(1, ReviewEvidence.coverage(ReviewEvidence.B, "NOT_REVIEWED"));
        Outcome incomplete = unreviewed.checkReview();
        assertEquals(1, incomplete.exitCode(), () -> incomplete.body().toString());
        assertEquals("INCOMPLETE", value(incomplete).get("verdict").stringValue());
        assertEquals(List.of("UNREVIEWED src/b.txt: Read the whole file against the declared requirement."), TestJson.strings(value(incomplete).get("issues")));

        // A blocking defect outranks an unreviewed file.
        ReviewEvidence both = ReviewEvidence.create(root.resolve("both"), true);
        both.coverageList().set(1, ReviewEvidence.coverage(ReviewEvidence.B, "NOT_REVIEWED"));
        both.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "confirmed", true));
        assertEquals("CHANGES_REQUESTED", value(both.checkReview()).get("verdict").stringValue());
    }

    @Test
    void anExcludedFileNotReviewedIsNotedButDoesNotMakeTheReviewIncomplete(@TempDir Path root) throws IOException {
        ReviewEvidence review = ReviewEvidence.create(root, true);
        review.request.set("excludedFiles", TestJson.array(List.of(ReviewEvidence.OLD)));
        review.coverageList().set(2, ReviewEvidence.coverage(ReviewEvidence.OLD, "NOT_REVIEWED"));
        review.sync();
        Outcome outcome = review.checkReview();
        assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("NO_BLOCKING_FINDINGS", value(outcome).get("verdict").stringValue());
        assertEquals(List.of("EXCLUDED src/old.txt: Read the whole file against the declared requirement."), TestJson.strings(value(outcome).get("issues")));
    }

    @Test
    void aSpecialistHandoffForAChangedFileIsAccepted(@TempDir Path root) throws IOException {
        ReviewEvidence review = ReviewEvidence.create(root, true);
        ((ArrayNode) review.report.get("handoffs")).add(TestJson.object("owner", "security-reviewer", "path", ReviewEvidence.A, "reason", "Input handling needs a security reading."));
        assertEquals(0, review.checkReview().exitCode());
    }

    @Test
    void aReviewIsRefusedForEachRuleItBreaks(@TempDir Path root) throws IOException {
        List<Case<ReviewEvidence>> cases = new ArrayList<>();
        // the request
        cases.add(new Case<>("a request without its requirements (the contract)", INVALID, SCHEMA, e -> e.request.remove("requirements")));
        cases.add(new Case<>("a changed-file list that omits a changed file", INVALID, P + "changedFileInventoryOmitsOrInvents", e -> {
            e.request.set("changedFiles", TestJson.array(List.of(ReviewEvidence.A, ReviewEvidence.B)));
            e.sync();
        }));
        cases.add(new Case<>("a changed-file list that invents a changed file", INVALID, P + "changedFileInventoryOmitsOrInvents", e -> {
            ((ArrayNode) e.request.get("changedFiles")).add("src/c.txt");
            e.sync();
        }));
        cases.add(new Case<>("an excluded path that did not change", INVALID, P + "excludedPathIsNotInThe", e -> {
            e.request.set("excludedFiles", TestJson.array(List.of("src/c.txt")));
            e.sync();
        }));
        cases.add(new Case<>("a request that excludes everything", INVALID, P + "theRequestExcludesItsEntireReview", e -> {
            e.request.set("excludedFiles", TestJson.array(List.of(ReviewEvidence.A, ReviewEvidence.B, ReviewEvidence.OLD)));
            e.sync();
        }));
        cases.add(new Case<>("a repeated requirement", INVALID, P + "duplicateIdentifier", e -> {
            e.request.set("requirements", TestJson.array(List.of(REQ, REQ)));
            e.sync();
        }));
        cases.add(new Case<>("a base digest that is not its files'", INTEGRITY, P + "snapshotDigestMismatch", e -> {
            ((ObjectNode) e.request.get("base")).put("digest", TestJson.hashText("x"));
            e.sync();
        }));
        cases.add(new Case<>("a changed file that git internals hide", INVALID, P + "gitInternalsAreOutOfSnapshot", e -> {
            ((ArrayNode) e.request.get("changedFiles")).add(".git/config");
            e.sync();
        }));
        // the report's binding to the request and the files
        cases.add(new Case<>("a report of another request", INTEGRITY, P + "reviewDoesNotBindThisRequest", e -> e.report.put("requestDigest", TestJson.hashText("another request"))));
        cases.add(new Case<>("a report of another head", INTEGRITY, P + "reviewDoesNotBindThisRequest", e -> e.report.put("headDigest", TestJson.hashText("another head"))));
        cases.add(new Case<>("a head file changed after capture", INTEGRITY, P + "currentFilesDifferFromTheDeclared", e -> Files.writeString(e.root.resolve(ReviewEvidence.B), "edited\n")));
        cases.add(new Case<>("a deleted file that is still there", INTEGRITY, P + "aSupposedlyDeletedFileIsStill", e -> Files.writeString(e.root.resolve(ReviewEvidence.OLD), "back\n")));
        // the coverage
        cases.add(new Case<>("a changed file without coverage", INVALID, P + "everyChangedFileMustBeReviewed", e -> e.coverageList().remove(2)));
        cases.add(new Case<>("coverage of a file that did not change", INVALID, P + "everyChangedFileMustBeReviewed", e -> e.coverageList().add(ReviewEvidence.coverage("src/c.txt", "REVIEWED"))));
        cases.add(new Case<>("two coverage entries for one file", INVALID, IO + "duplicateEntry", e -> e.coverageList().add(ReviewEvidence.coverage(ReviewEvidence.A, "REVIEWED"))));
        cases.add(new Case<>("a coverage entry with a blank reason", INVALID, P + "coverageRequiresAReasonScopeNote", e -> ((ObjectNode) e.coverageList().get(0)).put("reason", "  ")));
        cases.add(new Case<>("an excluded file counted as reviewed", INVALID, P + "anExcludedFileCannotBeCounted", e -> {
            e.request.set("excludedFiles", TestJson.array(List.of(ReviewEvidence.OLD)));
            e.sync();
        }));
        // the findings
        cases.add(new Case<>("two findings with one id", INVALID, IO + "duplicateEntry", e -> {
            e.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "supported", false));
            e.findings().add(finding("F-1", ReviewEvidence.B, "head", 1, 1, "defect", "supported", false));
        }));
        cases.add(new Case<>("a finding on a file the report did not cover", INVALID, P + "findingPointsAtAnUnreviewedOr", e -> e.findings().add(finding("F-1", "src/c.txt", "head", 1, 1, "defect", "supported", false))));
        cases.add(new Case<>("a finding on a file that was not reviewed", INVALID, P + "findingPointsAtAnUnreviewedOr", e -> {
            e.coverageList().set(1, ReviewEvidence.coverage(ReviewEvidence.B, "NOT_REVIEWED"));
            e.findings().add(finding("F-1", ReviewEvidence.B, "head", 1, 1, "defect", "supported", false));
        }));
        cases.add(new Case<>("a finding on a path that leaves the root", INVALID, IO + "unsafePathComponent", e -> e.findings().add(finding("F-1", "../a.txt", "head", 1, 1, "defect", "supported", false))));
        cases.add(new Case<>("a finding past the last line of the head file", INVALID, P + "findingLineRangeIsOutsideThe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 4, "defect", "supported", false))));
        cases.add(new Case<>("a finding on the base side past the base's last line", INVALID, P + "findingLineRangeIsOutsideThe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "base", 1, 3, "defect", "supported", false))));
        cases.add(new Case<>("a finding that starts at line zero", INVALID, P + "findingLineRangeIsOutsideThe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "head", 0, 1, "defect", "supported", false))));
        cases.add(new Case<>("a finding whose range ends before it starts", INVALID, P + "findingLineRangeIsOutsideThe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "head", 3, 2, "defect", "supported", false))));
        cases.add(new Case<>("a finding on a file the head does not have (head side)", INVALID, P + "findingLineRangeIsOutsideThe", e -> e.findings().add(finding("F-1", ReviewEvidence.OLD, "head", 1, 1, "defect", "supported", false))));
        cases.add(new Case<>("a finding with a blank trace", INVALID, P + "findingHasMissingReasoningOrIdentity", e -> {
            ObjectNode blank = finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "supported", false);
            blank.put("trace", "   ");
            e.findings().add(blank);
        }));
        cases.add(new Case<>("a blocking recommendation", INVALID, P + "recommendationsAndUnsupportedHypothesesCannotBe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "recommendation", "supported", true))));
        cases.add(new Case<>("a blocking hypothesis", INVALID, P + "recommendationsAndUnsupportedHypothesesCannotBe", e -> e.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "hypothesis", true))));
        // the handoffs
        cases.add(new Case<>("a handoff for a file that did not change", INVALID, P + "invalidSpecialistHandoff", e -> ((ArrayNode) e.report.get("handoffs")).add(TestJson.object("owner", "o", "path", "src/c.txt", "reason", "r"))));
        cases.add(new Case<>("a handoff without an owner", INVALID, P + "invalidSpecialistHandoff", e -> ((ArrayNode) e.report.get("handoffs")).add(TestJson.object("owner", "  ", "path", ReviewEvidence.A, "reason", "r"))));
        cases.add(new Case<>("a handoff without a reason", INVALID, P + "invalidSpecialistHandoff", e -> ((ArrayNode) e.report.get("handoffs")).add(TestJson.object("owner", "o", "path", ReviewEvidence.A, "reason", "  "))));
        for (int i = 0; i < cases.size(); i++) {
            Case<ReviewEvidence> broken = cases.get(i);
            ReviewEvidence review = ReviewEvidence.create(root.resolve("review" + i), true);
            broken.mutate().apply(review);
            Outcome outcome = review.checkReview();
            assertEquals(broken.key(), outcome.body().path("messageKey").stringValue(), broken.label() + ": " + outcome.body());
            refused(outcome, broken.code(), broken.key());
        }
    }

    @Test
    void aDeletedFileThatIsReallyGoneIsAccepted(@TempDir Path root) throws IOException {
        ReviewEvidence gone = ReviewEvidence.create(root.resolve("gone"), true);
        assertEquals(0, gone.checkReview().exitCode());
        ReviewEvidence present = ReviewEvidence.create(root.resolve("present"), false);
        refused(present.checkReview(), INTEGRITY, P + "aSupposedlyDeletedFileIsStill");
    }

    // ------------------------------------------------------------------------------------------------------------------------------ provider results

    @Test
    void aProviderResultIsWrappedWithItsArtifactAndNoErrorWhenItPasses(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root.resolve("t"));
        ObjectNode plan = copy(value(run("test-engineering", "check-plan", "root", evidence.root.toString(), "plan", evidence.plan)));
        ObjectNode test = copy(value(evidence.checkProof()));
        ReviewEvidence review = ReviewEvidence.create(root.resolve("r"), true);
        ObjectNode reviewed = copy(value(review.checkReview()));
        record Wrapped(String capability, String schema, ObjectNode result, String target, Path dir) {
        }
        List<Wrapped> wrapped = List.of(new Wrapped("test-plan-validation", "engineering-plan-result", plan, plan.get("planDigest").stringValue(), evidence.root),
                new Wrapped(TEST_STAGE, "engineering-test-result", test, test.get("targetDigest").stringValue(), evidence.root),
                new Wrapped(CODE_STAGE, "engineering-review-result", reviewed, reviewed.get("targetDigest").stringValue(), review.root));
        for (Wrapped item : wrapped) {
            ObjectNode ref = write(item.dir(), item.schema() + ".json", item.result());
            Outcome outcome = run("test-engineering", "provider-result", "root", item.dir().toString(), "capability", item.capability(), "result", item.schema() + ".json", "attachmentId", ATTACHMENT);
            assertEquals(0, outcome.exitCode(), () -> outcome.body().toString());
            JsonNode output = value(outcome);
            assertEquals("1.0.0", output.get("schemaVersion").stringValue());
            assertEquals("output", output.get("kind").stringValue());
            assertEquals(TestJson.hashJson(item.result()), TestJson.hashJson(output.get("output")), item.capability());
            assertEquals(1, output.get("artifacts").size());
            JsonNode artifact = output.get("artifacts").get(0);
            assertEquals(item.schema(), artifact.get("artifactId").stringValue());
            assertEquals("https://skill-suite.local/contracts/" + item.schema() + ".v1.schema.json", artifact.get("schemaId").stringValue());
            assertEquals(ATTACHMENT, artifact.get("attachmentId").stringValue());
            assertFalse(artifact.has("locator"), "the artifact names its bytes by the attachment, never by a path");
            assertEquals(ref.get("digest").stringValue(), artifact.get("digest").stringValue());
            assertEquals(item.target(), artifact.get("targetDigest").stringValue());
            assertFalse(artifact.get("verified").booleanValue());
            assertTrue(output.has("error") && output.get("error").isNull(), output::toString);
        }
    }

    @Test
    void aProviderResultThatIsNotAPassCarriesAnErrorBesideItsOutput(@TempDir Path root) throws IOException {
        Evidence incomplete = Evidence.create(root.resolve("t"));
        notRun(incomplete, "NOT_RUN", "later");
        ObjectNode test = copy(value(incomplete.checkProof()));
        write(incomplete.root, "test-result.json", test);
        Outcome missing = run("test-engineering", "provider-result", "root", incomplete.root.toString(), "capability", TEST_STAGE, "result", "test-result.json", "attachmentId", ATTACHMENT);
        assertEquals(0, missing.exitCode(), () -> missing.body().toString());
        JsonNode error = value(missing).get("error");
        assertEquals("MISSING_EVIDENCE", error.get("code").stringValue());
        assertTrue(error.get("message").stringValue().contains("incomplete"), error::toString);
        assertEquals(TestJson.strings(test.get("issues")), TestJson.strings(error.get("details").get("issues")));
        assertEquals(1, error.get("details").get("issues").size());

        ReviewEvidence blocking = ReviewEvidence.create(root.resolve("r"), true);
        blocking.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "confirmed", true));
        ObjectNode review = copy(value(blocking.checkReview()));
        write(blocking.root, "review-result.json", review);
        Outcome failed = run("code-review", "provider-result", "root", blocking.root.toString(), "capability", CODE_STAGE, "result", "review-result.json", "attachmentId", ATTACHMENT);
        assertEquals(0, failed.exitCode(), () -> failed.body().toString());
        assertEquals("GATE_FAILED", value(failed).get("error").get("code").stringValue());
        assertTrue(value(failed).get("error").get("message").stringValue().contains("Blocking"), () -> value(failed).toString());
        assertEquals(0, value(failed).get("error").get("details").get("issues").size());
    }

    @Test
    void aProviderResultIsRefusedWhenItsCapabilityOrDocumentIsWrong(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        ObjectNode plan = copy(value(run("test-engineering", "check-plan", "root", root.toString(), "plan", evidence.plan)));
        write(root, "plan-result.json", plan);
        refused(run("test-engineering", "provider-result", "root", root.toString(), "capability", "no-such-capability", "result", "plan-result.json", "attachmentId", ATTACHMENT), INVALID, P + "unsupportedCapability");
        // a plan result is not a test result
        refused(run("test-engineering", "provider-result", "root", root.toString(), "capability", TEST_STAGE, "result", "plan-result.json", "attachmentId", ATTACHMENT), INVALID, SCHEMA);
        plan.put("verdict", "CLAIMED");
        write(root, "claimed.json", plan);
        refused(run("test-engineering", "provider-result", "root", root.toString(), "capability", "test-plan-validation", "result", "claimed.json", "attachmentId", ATTACHMENT), INVALID, SCHEMA);
        Outcome missing = run("test-engineering", "provider-result", "root", root.toString(), "capability", "test-plan-validation", "result", "absent.json", "attachmentId", ATTACHMENT);
        assertEquals(2, missing.exitCode(), () -> missing.body().toString());
        assertEquals(NOT_FOUND, missing.body().get("code").stringValue());
    }

    /**
     * The artifact a provider result names is an attachment of the server, by its id, and never a path of the caller's computer: an id that is not a UUID (a path, a URL, the file's own
     * name, a UUID in capitals, a UUID with its dashes left out, a sentence) is refused whole, before the document is read, with the input error of a failed validation.
     */
    @ParameterizedTest
    @ValueSource(strings = {"plan-result.json", "../plan-result.json", "a\\b", "C:\\work\\plan-result.json", "/work/plan-result.json", "file:///work/plan-result.json", "not-a-uuid", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
            "aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa ", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaaa"})
    void aProviderResultWhoseAttachmentIdIsNotAUuidIsRefusedAsAFailedValidation(String attachmentId, @TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        write(root, "plan-result.json", copy(value(run("test-engineering", "check-plan", "root", root.toString(), "plan", evidence.plan))));
        Outcome outcome = run("test-engineering", "provider-result", "root", root.toString(), "capability", "test-plan-validation", "result", "plan-result.json", "attachmentId", attachmentId);
        refused(outcome, INVALID, "error.VALIDATION_FAILED");
        assertTrue(outcome.body().get("details").get("violations").get(0).stringValue().startsWith("attachmentId"), () -> outcome.body().toString());
    }

    @Test
    void aProviderResultThatIsNotGivenAnAttachmentIdIsRefused(@TempDir Path root) throws IOException {
        Evidence evidence = Evidence.create(root);
        write(root, "plan-result.json", copy(value(run("test-engineering", "check-plan", "root", root.toString(), "plan", evidence.plan))));
        Outcome outcome = run("test-engineering", "provider-result", "root", root.toString(), "capability", "test-plan-validation", "result", "plan-result.json");
        assertEquals(2, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals(INVALID, outcome.body().get("code").stringValue(), () -> outcome.body().toString());
        // The old argument is not read any more: a caller that still sends a locator and no attachment id is refused, not served.
        Outcome old = run("test-engineering", "provider-result", "root", root.toString(), "capability", "test-plan-validation", "result", "plan-result.json", "locator", "plan-result.json");
        assertEquals(2, old.exitCode(), () -> old.body().toString());
    }

    // ------------------------------------------------------------------------------------------------------------------------- the stage bundle

    private static ObjectNode taskDocument(String capability, boolean withContracts, JsonNode head) {
        ObjectNode task = TestJson.object("schemaVersion", "1.0.0", "taskId", "demo-page", "objective", "Verify the prefix.", "scope",
                TestJson.object("included", TestJson.array(List.of(PAGE)), "excluded", TestJson.array(List.of())), "acceptanceCriteria", TestJson.array(List.of(REQ)), "riskLevel", "low", "workUnits",
                TestJson.array(List.of()), "requiredCapabilities", TestJson.array(List.of(capability)), "constraints", TestJson.array(List.of()), "authorization",
                TestJson.object("allowedActions", TestJson.array(List.of("local-test")), "prohibitedActions", TestJson.array(List.of("network")), "approvalRequired", TestJson.array(List.of())), "decision",
                TestJson.object("complexity", "simple", "hasConflicts", false), "orchestration", TestJson.object("requested", true, "mcpAvailable", true));
        if (withContracts) {
            ObjectNode produced = TestJson.object("artifactId", "engineering-review-result", "schemaId", "https://skill-suite.local/contracts/engineering-review-result.v1.schema.json", "targetDigest",
                    head.get("digest").stringValue());
            task.set("artifactContracts", TestJson.array(List.of(TestJson.object("capability", capability, "produced", TestJson.array(List.of(produced))))));
        }
        return task;
    }

    /** The closed evidence of one stage on disk: the task, the prepared input, the evidence, the stage's result and the manifest that names them by raw digest. */
    private static final class Stage {
        final Path root;
        final String capability;
        final String skill;
        final String preparedKey;
        final String evidenceKey;
        final String target;
        final ObjectNode task;
        final ObjectNode prepared;
        final ObjectNode evidence;
        ObjectNode result;
        String taskDigest;
        Consumer<ObjectNode> manifestEdit = manifest -> {
        };
        Evidence proof;
        ReviewEvidence review;

        private Stage(Path root, String capability, String skill, String preparedKey, String evidenceKey, ObjectNode task, ObjectNode prepared, ObjectNode evidence, ObjectNode result, String target) {
            this.root = root;
            this.capability = capability;
            this.skill = skill;
            this.preparedKey = preparedKey;
            this.evidenceKey = evidenceKey;
            this.task = task;
            this.prepared = prepared;
            this.evidence = evidence;
            this.result = result;
            this.taskDigest = TestJson.hashJson(task);
            this.target = target;
        }

        static Stage test(Path root) throws IOException {
            Evidence proof = Evidence.create(root);
            ObjectNode task = taskDocument(TEST_STAGE, false, proof.head);
            proof.plan.put("contractDigest", TestJson.hashJson(task));
            proof.sync();
            Stage stage = new Stage(root, TEST_STAGE, "test-engineering", "plan", "proof", task, proof.plan, proof.proof, copy(value(proof.checkProof())), proof.head.get("digest").stringValue());
            stage.proof = proof;
            return stage;
        }

        static Stage review(Path root) throws IOException {
            ReviewEvidence review = ReviewEvidence.create(root, true);
            ObjectNode task = taskDocument(CODE_STAGE, true, review.head);
            review.request.put("taskId", "demo-page");
            review.request.put("contractDigest", TestJson.hashJson(task));
            review.sync();
            Stage stage = new Stage(root, CODE_STAGE, "code-review", "request", "report", task, review.request, review.report, copy(value(review.checkReview())), review.head.get("digest").stringValue());
            stage.review = review;
            return stage;
        }

        static Stage of(String kind, Path root) throws IOException {
            return "test".equals(kind) ? test(root) : review(root);
        }

        /** The task as it is now, bound again by its digest. */
        void refreshTask() {
            taskDigest = TestJson.hashJson(task);
        }

        /** The files as they are now; the digest of the manifest's raw bytes. */
        String seal() throws IOException {
            ObjectNode manifest = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-stage-bundle", "capability", capability, "task", write(root, "task.json", task), preparedKey,
                    write(root, preparedKey + ".json", prepared), evidenceKey, write(root, evidenceKey + ".json", evidence), "result", write(root, "result.json", result));
            manifestEdit.accept(manifest);
            return write(root, "stage-bundle.json", manifest).get("digest").stringValue();
        }

        Outcome check(String bundleDigest, String task, String capabilityName) {
            return run(skill, "check-stage-bundle", "root", root.toString(), "input", "stage-bundle.json", "bundle-digest", bundleDigest, "task-digest", task, "capability", capabilityName);
        }

        Outcome check(String bundleDigest) {
            return check(bundleDigest, taskDigest, capability);
        }
    }

    @Test
    void aBundleOfTheFrozenTaskItsPreparedInputItsEvidenceAndItsResultPassesTheStage(@TempDir Path root) throws IOException {
        for (String kind : List.of("test", "review")) {
            Stage stage = Stage.of(kind, root.resolve(kind));
            String digest = stage.seal();
            Outcome outcome = stage.check(digest);
            assertEquals(0, outcome.exitCode(), () -> kind + " " + outcome.body());
            JsonNode value = value(outcome);
            assertEquals("engineering-stage-check", value.get("kind").stringValue());
            assertEquals(stage.capability, value.get("capability").stringValue());
            // The task answered is the task read, member for member (the optional artifactContracts included when it is there).
            assertEquals(stage.taskDigest, TestJson.hashJson(value.get("task")), kind);
            assertEquals(TestJson.hashJson(stage.result), TestJson.hashJson(value.get("result")), kind);
            assertEquals("result.json", value.get("resultRef").get("path").stringValue());
            assertEquals(TestJson.hashBytes(Files.readAllBytes(stage.root.resolve("result.json"))), value.get("resultRef").get("digest").stringValue());
            assertEquals(stage.target, value.get("targetDigest").stringValue());
            assertEquals(TestJson.strings(stage.result.get("limitations")), TestJson.strings(value.get("limitations")));
            assertEquals("review".equals(kind), value.get("task").has("artifactContracts"), kind);
        }
    }

    @Test
    void aStageWhoseResultIsNotAPassStillAnswersWithExitCodeOne(@TempDir Path root) throws IOException {
        Stage incomplete = Stage.test(root.resolve("test"));
        notRun(incomplete.proof, "NOT_RUN", "later");
        incomplete.result = copy(value(incomplete.proof.checkProof()));
        String digest = incomplete.seal();
        Outcome outcome = incomplete.check(digest);
        assertEquals(1, outcome.exitCode(), () -> outcome.body().toString());
        assertEquals("INCOMPLETE", value(outcome).get("result").get("verdict").stringValue());

        Stage blocking = Stage.review(root.resolve("review"));
        blocking.review.findings().add(finding("F-1", ReviewEvidence.A, "head", 1, 1, "defect", "confirmed", true));
        blocking.result = copy(value(blocking.review.checkReview()));
        Outcome requested = blocking.check(blocking.seal());
        assertEquals(1, requested.exitCode(), () -> requested.body().toString());
        assertEquals("CHANGES_REQUESTED", value(requested).get("result").get("verdict").stringValue());
    }

    @Test
    void aBundleIsRefusedForEachRuleItBreaks(@TempDir Path root) throws IOException {
        int n = 0;
        for (String kind : List.of("test", "review")) {
            // the bundle's own bytes and the capability the caller names
            Stage bytes = Stage.of(kind, root.resolve(kind + n++));
            bytes.seal();
            refused(bytes.check(TestJson.hashText("other bytes")), INTEGRITY, STAGE + "stageBundleBytesChanged");
            Stage unsupported = Stage.of(kind, root.resolve(kind + n++));
            String digest = unsupported.seal();
            refused(unsupported.check(digest, unsupported.taskDigest, "test-plan-validation"), INVALID, STAGE + "unsupportedEngineeringStageCapability");
            refused(unsupported.check(digest, unsupported.taskDigest, "no-such-capability"), INVALID, STAGE + "unsupportedEngineeringStageCapability");

            // the manifest
            List<Case<Stage>> manifest = new ArrayList<>();
            manifest.add(new Case<>("another kind", INVALID, STAGE + "invalidOrMismatchedStageBundle", s -> s.manifestEdit = m -> m.put("kind", "other")));
            manifest.add(new Case<>("another version", INVALID, STAGE + "invalidOrMismatchedStageBundle", s -> s.manifestEdit = m -> m.put("schemaVersion", "2.0.0")));
            manifest.add(new Case<>("another capability than the caller's", INVALID, STAGE + "invalidOrMismatchedStageBundle",
                    s -> s.manifestEdit = m -> m.put("capability", "test-sensitivity-review".equals(s.capability) ? CODE_STAGE : TEST_STAGE)));
            manifest.add(new Case<>("an unknown capability", INVALID, STAGE + "invalidOrMismatchedStageBundle", s -> s.manifestEdit = m -> m.put("capability", "no-such-capability")));
            manifest.add(new Case<>("an extra member", INVALID, STAGE + "invalidOrMismatchedStageBundle", s -> s.manifestEdit = m -> m.put("extra", 1)));
            manifest.add(new Case<>("the other kind's prepared input beside its own", INVALID, STAGE + "invalidOrMismatchedStageBundle",
                    s -> s.manifestEdit = m -> m.set("test".equals(s.capability.substring(0, 4)) ? "request" : "plan", m.get(s.preparedKey).deepCopy())));
            manifest.add(new Case<>("a reference with a member of its own", INVALID, STAGE + "invalidOrMismatchedStageBundle", s -> s.manifestEdit = m -> ((ObjectNode) m.get("task")).put("note", "x")));
            manifest.add(new Case<>("a null reference", INVALID, STAGE + "expectedAClosedRawFileReference", s -> s.manifestEdit = m -> m.putNull("result")));
            manifest.add(new Case<>("a missing reference", INVALID, STAGE + "expectedAClosedRawFileReference", s -> s.manifestEdit = m -> m.remove("task")));
            for (Case<Stage> broken : manifest) {
                Stage stage = Stage.of(kind, root.resolve(kind + n++));
                broken.mutate().apply(stage);
                Outcome outcome = stage.check(stage.seal());
                assertEquals(broken.key(), outcome.body().path("messageKey").stringValue(), kind + " " + broken.label() + ": " + outcome.body());
                refused(outcome, broken.code(), broken.key());
            }

            // a raw file that changed after the bundle was sealed
            for (String file : List.of("task.json", "result.json", "stage-bundle.json")) {
                Stage stage = Stage.of(kind, root.resolve(kind + n++));
                String sealed = stage.seal();
                Files.writeString(stage.root.resolve(file), "{\"changed\":true}\n");
                Outcome outcome = stage.check(sealed);
                assertEquals(2, outcome.exitCode(), () -> kind + " " + file + ": " + outcome.body());
                assertEquals(INTEGRITY, outcome.body().get("code").stringValue(), () -> kind + " " + file);
            }
            Stage prepared = Stage.of(kind, root.resolve(kind + n++));
            String sealedPrepared = prepared.seal();
            Files.writeString(prepared.root.resolve(prepared.preparedKey + ".json"), "{\"changed\":true}\n");
            ObjectNode changed = copy(prepared.check(sealedPrepared).body());
            assertEquals(IO + "artifactDigestMismatch", changed.get("messageKey").stringValue());
            assertEquals(prepared.preparedKey + ".json", changed.get("details").get("path").stringValue());

            // the signed task
            Stage signed = Stage.of(kind, root.resolve(kind + n++));
            String signedDigest = signed.seal();
            refused(signed.check(signedDigest, TestJson.hashText("another task"), signed.capability), INTEGRITY, STAGE + "bundleDoesNotBindTheSigned");

            List<Case<Stage>> bound = new ArrayList<>();
            bound.add(new Case<>("a task that does not select the capability", INVALID, STAGE + "taskDidNotSelectThisEngineering", s -> {
                s.task.set("requiredCapabilities", TestJson.array(List.of("some-other-capability")));
                s.refreshTask();
            }));
            bound.add(new Case<>("a task without its objective (the contract)", INVALID, SCHEMA, s -> {
                s.task.remove("objective");
                s.refreshTask();
            }));
            bound.add(new Case<>("a prepared input of another task", INTEGRITY, STAGE + "preparedInputDoesNotBindThis", s -> s.prepared.put("taskId", "another-task")));
            bound.add(new Case<>("a prepared input of another contract digest", INTEGRITY, STAGE + "preparedInputDoesNotBindThis", s -> s.prepared.put("contractDigest", TestJson.hashText("another"))));
            bound.add(new Case<>("prepared requirements the task did not freeze", INVALID, STAGE + "preparedRequirementsDifferFromTheFrozen", s -> {
                s.task.set("acceptanceCriteria", TestJson.array(List.of(REQ, "R-OTHER")));
                s.refreshTask();
                s.prepared.put("contractDigest", s.taskDigest);
            }));
            bound.add(new Case<>("a result that is not what the check computes", INTEGRITY, STAGE + "stageResultDiffersFromCurrentRaw", s -> s.result.put("verdict", "INCOMPLETE")));
            bound.add(new Case<>("a result with a member the contract does not have", INTEGRITY, STAGE + "stageResultDiffersFromCurrentRaw", s -> s.result.put("extra", 1)));
            for (Case<Stage> broken : bound) {
                Stage stage = Stage.of(kind, root.resolve(kind + n++));
                broken.mutate().apply(stage);
                Outcome outcome = stage.check(stage.seal());
                assertEquals(broken.key(), outcome.body().path("messageKey").stringValue(), kind + " " + broken.label() + ": " + outcome.body());
                refused(outcome, broken.code(), broken.key());
            }
        }
    }

    @Test
    void aTestStageNeedsARequiredMechanicalCaseForEveryFrozenRequirement(@TempDir Path root) throws IOException {
        Stage uncovered = Stage.test(root.resolve("uncovered"));
        uncovered.task.set("acceptanceCriteria", TestJson.array(List.of(REQ, "R-OTHER")));
        uncovered.refreshTask();
        uncovered.prepared.set("requirements", TestJson.array(List.of(REQ, "R-OTHER")));
        uncovered.prepared.put("contractDigest", uncovered.taskDigest);
        ObjectNode optional = copy(uncovered.proof.theCase());
        optional.put("id", "PAGE-2");
        optional.put("required", false);
        optional.set("requirementIds", TestJson.array(List.of("R-OTHER")));
        optional.put("sensitivity", "manual-review");
        ((ArrayNode) uncovered.prepared.get("cases")).add(optional);
        refused(uncovered.check(uncovered.seal()), INVALID, STAGE + "aFrozenRequirementHasNoRequired");

        Stage downgraded = Stage.test(root.resolve("downgraded"));
        ((ObjectNode) downgraded.prepared.get("cases").get(0)).put("sensitivity", "manual-review");
        refused(downgraded.check(downgraded.seal()), INVALID, STAGE + "requiredSensitivityEvidenceCannotBeDowngraded");

        Stage exempt = Stage.test(root.resolve("exempt"));
        ((ObjectNode) exempt.prepared.get("cases").get(0)).put("sensitivity", "not-applicable");
        refused(exempt.check(exempt.seal()), INVALID, STAGE + "requiredSensitivityEvidenceCannotBeDowngraded");
    }
}
