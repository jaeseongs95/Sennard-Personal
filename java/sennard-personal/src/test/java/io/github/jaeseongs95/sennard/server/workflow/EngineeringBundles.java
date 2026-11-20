// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.personal.skills.TestJson;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The closed evidence of one engineering stage: the frozen task, the prepared input, the evidence, the stage's result and the manifest that names them by raw digest, all judged by
 * the skills' real checks (snapshot, check-proof, check-review). The skills' commands that make the documents (snapshot, check-proof, check-review) read a folder, so the files are
 * made under a root first; what the stage result then names is what a caller would upload (AGS3-D-0264): each file of the root is an {@link Upload} (the file name is its path under the
 * bundle's folder, {@code /}-separated, and its bytes), the manifest and the report are artifacts of the result, and every other file is an evidence item, each with the digest of its bytes.
 * The server does not run a test command, so the red and the green receipts a proof names are written the way a caller's own run would write them: documents with the digests of what
 * they record.
 */
final class EngineeringBundles {
    static final String TEST_STAGE = "test-sensitivity-review";
    static final String CODE_STAGE = "change-code-review";
    /** The file names (paths under the bundle's folder) of the manifest and of the report an engineering bundle's result names. */
    static final String BUNDLE_FILE = "stage-bundle.json";
    static final String REPORT_FILE = "result.json";
    private static final String PAGE = "sample/page.txt";
    private static final String CHECK = "sample/Check.txt";
    private static final String REQ = "R-PREFIX";
    private static final String WITNESS = "ERR_ASSERTION";
    private static final List<String> ARGV = List.of("tool", "check.txt");

    /** What a stage's check judged: the stage as planned, its result as the provider reports it, the digest of the signed task, and the files of attachments the result names, by attachment id and file number. */
    record Bundle(WorkflowPlan.Stage stage, StageResult result, String taskDigest, Map<AttachmentFileRef, Upload> uploads) {
    }

    /** An attachment as the caller uploaded it: the file name (its path under the bundle's folder) and the bytes. */
    record Upload(String fileName, byte[] bytes) {
    }

    /** Runs one command of a skill's check and gives its value, the way {@code run_skill_check} answers it. */
    @FunctionalInterface
    interface Skills {
        JsonNode value(String skill, String command, Object... pairs);
    }

    private EngineeringBundles() {
    }

    /** The attachment id of the upload of this file name: the same name is the same id, so a case names an attachment without a table of ids. */
    static String attachmentOf(String fileName) {
        return UUID.nameUUIDFromBytes(fileName.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Every file under the root as an upload, one attachment of one file (file 0) each, by attachment id and file number: the file name is the path under the root, read by {@code /}. */
    static Map<AttachmentFileRef, Upload> uploadsOf(Path root) throws IOException {
        Map<AttachmentFileRef, Upload> uploads = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = root.relativize(file).toString().replace('\\', '/');
                uploads.put(fileOf(fileName), new Upload(fileName, Files.readAllBytes(file)));
            }
        }
        return uploads;
    }

    /** The evidence items of a result: one for each upload except the attachments the result names as its artifacts, each with the digest of its bytes. */
    static List<Evidence> evidenceBesides(Map<AttachmentFileRef, Upload> uploads, AttachmentFileRef... artifacts) {
        List<AttachmentFileRef> named = List.of(artifacts);
        return uploads.entrySet().stream().filter(entry -> !named.contains(entry.getKey()))
                .map(entry -> new Evidence(entry.getValue().fileName(), EvidenceKind.FILE, entry.getKey().attachmentId(), entry.getKey().seq(), TestJson.hashBytes(entry.getValue().bytes()), true, null))
                .toList();
    }

    /** The first file of the attachment of this file name ({@link #attachmentOf}). */
    static AttachmentFileRef fileOf(String fileName) {
        return new AttachmentFileRef(attachmentOf(fileName), 0);
    }

    /**
     * The same bundle with every one of its files a file of ONE attachment ({@code attachmentId}): the files are numbered 0, 1, 2 ... in the order of their names (the order of
     * {@link #uploadsOf}), as one {@code artifact_begin} with all of them would number them. The result's artifacts and evidence point at (attachment id, file number); the bytes, the
     * file names and every digest are the same, so the check that judged the bundle one attachment per file judges it again here.
     */
    static Bundle inOneAttachment(Bundle given, String attachmentId) {
        Map<AttachmentFileRef, AttachmentFileRef> moved = new LinkedHashMap<>();
        Map<AttachmentFileRef, Upload> uploads = new LinkedHashMap<>();
        for (Map.Entry<AttachmentFileRef, Upload> entry : given.uploads().entrySet()) {
            AttachmentFileRef now = new AttachmentFileRef(attachmentId, moved.size());
            moved.put(entry.getKey(), now);
            uploads.put(now, entry.getValue());
        }
        ProviderResult output = given.result().output();
        List<ProviderArtifact> artifacts = output.artifacts().stream().map(artifact -> {
            AttachmentFileRef now = moved.get(new AttachmentFileRef(artifact.attachmentId(), artifact.seq()));
            return new ProviderArtifact(artifact.artifactId(), artifact.schemaId(), now.attachmentId(), now.seq(), artifact.digest(), artifact.targetDigest(), artifact.verified());
        }).toList();
        List<Evidence> evidence = given.result().evidence().stream().map(item -> {
            AttachmentFileRef now = moved.get(new AttachmentFileRef(item.attachmentId(), item.seq()));
            return new Evidence(item.artifactId(), item.kind(), now.attachmentId(), now.seq(), item.digest(), item.verified(), item.note());
        }).toList();
        StageResult result = given.result().withOutput(new ProviderResult(output.schemaVersion(), output.kind(), output.output(), artifacts, output.error())).withEvidence(evidence);
        return new Bundle(given.stage(), result, given.taskDigest(), uploads);
    }

    static Bundle of(Skills skills, Path root, String capability) throws IOException {
        return TEST_STAGE.equals(capability) ? test(skills, root) : review(skills, root);
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

    private static ObjectNode snapshot(Skills skills, Path root, List<String> files) {
        return copy(skills.value("test-engineering", "snapshot", "root", root.toString(), "files", TestJson.array(files)));
    }

    private static Bundle seal(Path root, String capability, ObjectNode task, String preparedKey, ObjectNode prepared, String evidenceKey, ObjectNode evidence, JsonNode result, JsonNode head)
            throws IOException {
        ObjectNode manifest = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-stage-bundle", "capability", capability, "task", write(root, "task.json", task), preparedKey,
                write(root, preparedKey + ".json", prepared), evidenceKey, write(root, evidenceKey + ".json", evidence), "result", write(root, "result.json", result));
        ObjectNode bundleRef = write(root, BUNDLE_FILE, manifest);
        ObjectNode resultRef = TestJson.object("digest", TestJson.hashBytes(Files.readAllBytes(root.resolve(REPORT_FILE))));
        String target = head.get("digest").stringValue();
        String reportId = TEST_STAGE.equals(capability) ? "engineering-test-result" : "engineering-review-result";
        AttachmentFileRef bundleAttachment = fileOf(BUNDLE_FILE);
        AttachmentFileRef reportAttachment = fileOf(REPORT_FILE);
        // The provider reports the stage's result as the skill's own check made it, and names the bundle and the result file by what they are: attachments, whose file names are their
        // paths under the bundle's folder. Every other file of the bundle is evidence.
        ProviderResult provider = new ProviderResult("1.0.0", OutputKind.OUTPUT, result, List.of(
                new ProviderArtifact("engineering-stage-bundle", "artifact.v1", bundleAttachment.attachmentId(), bundleAttachment.seq(), bundleRef.get("digest").stringValue(), target, true),
                new ProviderArtifact(reportId, "artifact.v1", reportAttachment.attachmentId(), reportAttachment.seq(), resultRef.get("digest").stringValue(), target, true)), null);
        Map<AttachmentFileRef, Upload> uploads = uploadsOf(root);
        StageResult recorded = new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED, provider, evidenceBesides(uploads, bundleAttachment, reportAttachment), List.of(), List.of(), null, null,
                null, null, null);
        return new Bundle(PlanSamples.stage("stage-01", capability, "unused-by-this-check", List.of(), PlanSamples.openGate(), null), recorded, TestJson.hashJson(task), uploads);
    }

    /** A plan with one case, a candidate whose production file changed between the red and the green run, and the two receipts. */
    private static Bundle test(Skills skills, Path root) throws IOException {
        Files.createDirectories(root.resolve("sample"));
        Files.writeString(root.resolve(PAGE), "limit + 1\n");
        Files.writeString(root.resolve(CHECK), "check\n");
        List<String> files = List.of(PAGE, CHECK);
        ObjectNode base = snapshot(skills, root, files);
        Files.writeString(root.resolve(PAGE), "limit\n");
        ObjectNode head = snapshot(skills, root, files);
        ObjectNode red = receipt(base, "FAIL", 1, "", "boom " + WITNESS + "\n", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z");
        ObjectNode green = receipt(head, "PASS", 0, "ok\n", "", "2026-01-01T00:00:02.000Z", "2026-01-01T00:00:03.000Z");
        ObjectNode task = taskDocument(TEST_STAGE, false, head);
        ObjectNode theCase = TestJson.object("id", "PAGE-1", "requirementIds", TestJson.array(List.of(REQ)), "scenario", "Three items with limit two.", "entryPoint", "page(items, limit)", "level", "unit",
                "expectedBehavior", "Returns the prefix.", "oracle", "Literal expected prefix derived from the frozen requirement.", "testFiles", TestJson.array(List.of(CHECK)), "required", true,
                "sensitivity", "red-green", "sensitivityReason", "An off-by-one mutation must be detected by the same test.", "argv", TestJson.array(ARGV));
        ObjectNode plan = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-plan", "taskId", "demo-page", "contractDigest", TestJson.hashJson(task), "requirements",
                TestJson.array(List.of(REQ)), "scopeFiles", TestJson.array(files), "runner", "java", "environment", "Local offline JDK, no external dependencies.", "cases", TestJson.array(List.of(theCase)),
                "exclusions", TestJson.array(List.of("Negative limits are outside this example.")));
        ObjectNode entry = TestJson.object("caseId", "PAGE-1", "status", "DEMONSTRATED", "red", write(root, "evidence/red.json", red), "green", write(root, "evidence/green.json", green), "mutationPaths",
                TestJson.array(List.of(PAGE)), "assertionWitness", WITNESS, "reason", "The red run shows the extra item; the same test passes after removing it.");
        ObjectNode proof = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-test-proof", "planDigest", TestJson.hashJson(plan), "candidate", copy(head), "proofs", TestJson.array(List.of(entry)));
        JsonNode result = skills.value("test-engineering", "check-proof", "root", root.toString(), "plan", plan, "proof", proof);
        return seal(root, TEST_STAGE, task, "plan", plan, "proof", proof, result, head);
    }

    /** A request over changed files (one deleted, as the head says), a report that reviews all of them, and the files on disk. */
    private static Bundle review(Skills skills, Path root) throws IOException {
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
        ObjectNode task = taskDocument(CODE_STAGE, true, head);
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-request", "taskId", "demo-page", "contractDigest", TestJson.hashJson(task), "base", copy(base), "head",
                copy(head), "changedFiles", TestJson.array(List.of(a, b, old)), "excludedFiles", TestJson.array(List.of()), "requirements", TestJson.array(List.of(REQ)));
        ObjectNode report = TestJson.object("schemaVersion", "1.0.0", "kind", "engineering-review-report", "requestDigest", TestJson.hashJson(request), "headDigest", head.get("digest").stringValue(),
                "reviewer", "reviewer-1", "independence", "separate-reviewer", "coverage", TestJson.array(List.of(coverage(a), coverage(b), coverage(old))), "findings", TestJson.array(List.of()),
                "openQuestions", TestJson.array(List.of()), "handoffs", TestJson.array(List.of()));
        JsonNode result = skills.value("code-review", "check-review", "root", root.toString(), "request", request, "report", report);
        return seal(root, CODE_STAGE, task, "request", request, "report", report, result, head);
    }

    private static ObjectNode coverage(String path) {
        return TestJson.object("path", path, "status", "REVIEWED", "reason", "Read the whole file against the declared requirement.");
    }
}
