// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.jaeseongs95.sennard.personal.skills.TestJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The closed evidence of one engineering review stage as the files a caller makes on its own computer, for the end-to-end tests of the bundle review stages (AGS3-D-0264): the frozen
 * task, the prepared input, the evidence, the stage's result and the manifest that names them by raw digest, all judged by the skills' real checks (snapshot, check-proof,
 * check-review, reached through {@code run_skill_check}). The server runs no test command, so the red and the green receipts a proof names are written the way a caller's own run would
 * write them: documents with the digests of what they record.
 *
 * <p>A test uploads each file of {@link Bundle#files()} as an attachment whose file name is its path under the bundle's folder, which is how the server finds the bundle again.
 */
final class EngineeringBundleFiles {
    static final String TEST_STAGE = "test-sensitivity-review";
    static final String CODE_STAGE = "change-code-review";
    static final String BUNDLE_ARTIFACT = "engineering-stage-bundle";
    private static final String PAGE = "sample/page.txt";
    private static final String CHECK = "sample/Check.txt";
    private static final String WITNESS = "ERR_ASSERTION";
    private static final List<String> ARGV = List.of("tool", "check.txt");

    /** Runs one command of a skill's check and gives its value, the way {@code run_skill_check} answers it. */
    @FunctionalInterface
    interface Skills {
        JsonNode value(String skill, String command, Object... pairs);
    }

    /**
     * What a stage's bundle is: the files by their path under the bundle's folder (the manifest and the report among them), where the manifest and the report are, the artifact id of the
     * report, the target the stage's result is bound to and the result as the skill's own check made it.
     */
    record Bundle(String capability, String reportArtifactId, String manifestPath, String reportPath, String target, JsonNode result, Map<String, byte[]> files) {
    }

    /** The files of the candidate on disk and the two snapshots of them, before the task exists: the task carries the target, and the bundle carries the task. */
    static final class Pending {
        private final Skills skills;
        private final Path root;
        private final String capability;
        private final ObjectNode base;
        private final ObjectNode head;
        /** The two receipts of the test bundle; null for the review bundle. */
        private final ObjectNode red;
        private final ObjectNode green;

        private Pending(Skills skills, Path root, String capability, ObjectNode base, ObjectNode head, ObjectNode red, ObjectNode green) {
            this.skills = skills;
            this.root = root;
            this.capability = capability;
            this.base = base;
            this.head = head;
            this.red = red;
            this.green = green;
        }

        /** The digest of the head snapshot: the target the stage's report artifact is bound to, which the task's artifact contract names. */
        String target() {
            return head.get("digest").stringValue();
        }

        /** The artifact that carries the stage's result. */
        String reportArtifactId() {
            return TEST_STAGE.equals(capability) ? "engineering-test-result" : "engineering-review-result";
        }

        /** The rest of the bundle, over the frozen task the workflow's plan was made from (its digest is the plan's task digest). */
        Bundle seal(ObjectNode task) throws IOException {
            return TEST_STAGE.equals(capability) ? sealTest(task) : sealReview(task, true);
        }

        /** The review bundle of a report that leaves one changed file unreviewed: the real check judges it INCOMPLETE, a verdict that does not pass the stage. Only the code review has it. */
        Bundle sealIncompleteReview(ObjectNode task) throws IOException {
            if (!CODE_STAGE.equals(capability)) {
                throw new IllegalStateException("Only the review bundle has an incomplete form: " + capability);
            }
            return sealReview(task, false);
        }

        /** A plan with one case, a candidate whose production file changed between the red and the green run, and the two receipts. */
        private Bundle sealTest(ObjectNode task) throws IOException {
            List<String> requirements = TestJson.strings(task.get("acceptanceCriteria"));
            List<String> files = List.of(PAGE, CHECK);
            ObjectNode theCase = TestJson.object("id", "PAGE-1", "requirementIds", TestJson.array(requirements), "scenario", "Three items with limit two.", "entryPoint", "page(items, limit)", "level",
                    "unit", "expectedBehavior", "Returns the prefix.", "oracle", "Literal expected prefix derived from the frozen requirement.", "testFiles", TestJson.array(List.of(CHECK)), "required", true,
                    "sensitivity", "red-green", "sensitivityReason", "An off-by-one mutation must be detected by the same test.", "argv", TestJson.array(ARGV));
            ObjectNode plan = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-plan", "taskId", task.get("taskId").stringValue(), "contractDigest", TestJson.hashJson(task),
                    "requirements", TestJson.array(requirements), "scopeFiles", TestJson.array(files), "runner", "java", "environment", "Local offline JDK, no external dependencies.", "cases",
                    TestJson.array(List.of(theCase)), "exclusions", TestJson.array(List.of("Negative limits are outside this example.")));
            ObjectNode entry = TestJson.object("caseId", "PAGE-1", "status", "DEMONSTRATED", "red", write(root, "evidence/red.json", red), "green", write(root, "evidence/green.json", green),
                    "mutationPaths", TestJson.array(List.of(PAGE)), "assertionWitness", WITNESS, "reason", "The red run shows the extra item; the same test passes after removing it.");
            ObjectNode proof = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-proof", "planDigest", TestJson.hashJson(plan), "candidate", copy(head), "proofs",
                    TestJson.array(List.of(entry)));
            JsonNode result = skills.value("test-engineering", "check-proof", "root", root.toString(), "plan", plan, "proof", proof);
            assertEquals("CONSISTENT", result.path("verdict").asString(), "the real check judges the proof consistent: " + result);
            return seal(task, "plan", plan, "proof", proof, result);
        }

        /** A request over changed files (one deleted, as the head says), a report that reviews all of them, and the files on disk. */
        private Bundle sealReview(ObjectNode task, boolean complete) throws IOException {
            String a = "src/a.txt";
            String b = "src/b.txt";
            String old = "src/old.txt";
            List<String> requirements = TestJson.strings(task.get("acceptanceCriteria"));
            // The base snapshot was taken of the three files; the old file is not on disk any more, as the head says.
            ObjectNode request = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-request", "taskId", task.get("taskId").stringValue(), "contractDigest",
                    TestJson.hashJson(task), "base", copy(base), "head", copy(head), "changedFiles", TestJson.array(List.of(a, b, old)), "excludedFiles", TestJson.array(List.of()), "requirements",
                    TestJson.array(requirements));
            ObjectNode report = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-report", "requestDigest", TestJson.hashJson(request), "headDigest",
                    head.get("digest").stringValue(), "reviewer", "reviewer-1", "independence", "separate-reviewer", "coverage", TestJson.array(List.of(coverage(a), complete ? coverage(b) : notReviewed(b), coverage(old))),
                    "findings", TestJson.array(List.of()), "openQuestions", TestJson.array(List.of()), "handoffs", TestJson.array(List.of()));
            JsonNode result = skills.value("code-review", "check-review", "root", root.toString(), "request", request, "report", report);
            assertEquals(complete ? "NO_BLOCKING_FINDINGS" : "INCOMPLETE", result.path("verdict").asString(), "the real check judges the review: " + result);
            return seal(task, "request", request, "report", report, result);
        }

        private Bundle seal(ObjectNode task, String preparedKey, ObjectNode prepared, String evidenceKey, ObjectNode evidence, JsonNode result) throws IOException {
            ObjectNode manifest = TestJson.object("schemaVersion", "1.0.0", "kind", BUNDLE_ARTIFACT, "capability", capability, "task", write(root, "task.json", task), preparedKey,
                    write(root, preparedKey + ".json", prepared), evidenceKey, write(root, evidenceKey + ".json", evidence), "result", write(root, "result.json", result));
            write(root, "stage-bundle.json", manifest);
            return new Bundle(capability, reportArtifactId(), "stage-bundle.json", "result.json", target(), result, filesUnder(root));
        }
    }

    private EngineeringBundleFiles() {
    }

    /** Writes the candidate's files under the root and takes the snapshots the bundle's documents bind. */
    static Pending begin(Skills skills, Path root, String capability) throws IOException {
        return TEST_STAGE.equals(capability) ? beginTest(skills, root) : beginReview(skills, root);
    }

    private static Pending beginTest(Skills skills, Path root) throws IOException {
        Files.createDirectories(root.resolve("sample"));
        Files.writeString(root.resolve(PAGE), "limit + 1\n");
        Files.writeString(root.resolve(CHECK), "check\n");
        List<String> files = List.of(PAGE, CHECK);
        ObjectNode base = snapshot(skills, root, files);
        Files.writeString(root.resolve(PAGE), "limit\n");
        ObjectNode head = snapshot(skills, root, files);
        ObjectNode red = receipt(base, "FAIL", 1, "", "boom " + WITNESS + "\n", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z");
        ObjectNode green = receipt(head, "PASS", 0, "ok\n", "", "2026-01-01T00:00:02.000Z", "2026-01-01T00:00:03.000Z");
        return new Pending(skills, root, TEST_STAGE, base, head, red, green);
    }

    private static Pending beginReview(Skills skills, Path root) throws IOException {
        String a = "src/a.txt";
        String b = "src/b.txt";
        String old = "src/old.txt";
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(a), "a1\nb\n");
        Files.writeString(root.resolve(b), "x1\n");
        Files.writeString(root.resolve(old), "old\n");
        ObjectNode base = snapshot(skills, root, List.of(a, b, old));
        Files.writeString(root.resolve(a), "a2\nb\nc\n");
        Files.writeString(root.resolve(b), "x2\n");
        ObjectNode head = snapshot(skills, root, List.of(a, b));
        Files.delete(root.resolve(old));
        return new Pending(skills, root, CODE_STAGE, base, head, null, null);
    }

    private static ObjectNode copy(JsonNode node) {
        return (ObjectNode) node.deepCopy();
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

    /** Every file under the root by its path under it, read with {@code /}: the path a file's attachment is named by. */
    private static Map<String, byte[]> filesUnder(Path root) throws IOException {
        List<Path> found;
        try (Stream<Path> walk = Files.walk(root)) {
            found = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Path file : found) {
            files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }

    private static ObjectNode snapshot(Skills skills, Path root, List<String> files) {
        return copy(skills.value("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(files)));
    }

    private static ObjectNode receipt(JsonNode snapshot, String status, int exit, String stdout, String stderr, String started, String finished) {
        ObjectNode receipt = TestJson.MAPPER.createObjectNode();
        receipt.put("schemaVersion", "2.0.0");
        receipt.put("kind", "engineering-run-receipt");
        receipt.set("argv", TestJson.array(ARGV));
        receipt.set("snapshot", copy(snapshot));
        receipt.put("afterDigest", snapshot.get("digest").stringValue());
        receipt.put("startedAt", started);
        receipt.put("finishedAt", finished);
        receipt.put("status", status);
        receipt.put("exitCode", exit);
        receipt.put("stdout", stdout);
        receipt.put("stderr", stderr);
        receipt.put("stdoutDigest", TestJson.hashText(stdout));
        receipt.put("stderrDigest", TestJson.hashText(stderr));
        receipt.put("timeoutMs", 30000);
        receipt.put("executable", ARGV.getFirst());
        receipt.put("recorderRuntime", "25.0.5+8");
        receipt.put("platform", "test-platform");
        receipt.put("environmentNote", "stage bundle case");
        return seal(receipt);
    }

    private static ObjectNode notReviewed(String path) {
        return TestJson.object("path", path, "status", "NOT_REVIEWED", "reason", "Not read: the reviewer ran out of time.");
    }

    private static ObjectNode coverage(String path) {
        return TestJson.object("path", path, "status", "REVIEWED", "reason", "Read the whole file against the declared requirement.");
    }
}
