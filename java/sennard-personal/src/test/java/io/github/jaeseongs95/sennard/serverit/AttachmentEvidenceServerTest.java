// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.serverit.ToolServerSupport.Uploaded;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The attachments (AGS3-D-0264) through multipart and the tools of a real MCP client and what a stage result does with them, on the assembled server with real PostgreSQL: the four
 * {@code artifact_*} tools, and what {@code record_stage_result}, {@code finalize_workflow} and a continuing {@code start_workflow} do when the result names an attachment. The server
 * never reads a path of the caller's computer: every file a result names (evidence, the artifacts of the output, the output file, the evidence of a prior failure) is an attachment
 * that the same session made with the {@code artifact_*} tools, and the server judges it by the sha256 it holds. Each test takes its own sessions, so the author of an upload is
 * always the session that made it. The server is started with {@code sennard.attachments.max-file-bytes=1KB}, so that the size limit is reached with a small file and a file of any
 * test below stays under it. Nothing here waits for a time: no test reads a clock.
 *
 * <pre>
 * The design test numbers are those of section 9 of the three products' common attachment design (AGS3-D-0264).
 * t1  normal         begin, chunks, finish, status, read: the plain hash is the same
 * t2   chunks         an out-of-order chunk is refused, the last chunk sent again is answered as before, another content under its number is refused, a wrong chunk hash is refused
 * t3   by id alone    multipart transfer, finish and upload status work without a session. The session that began the attachment owns its evidence.
 *                     An unknown ID returns ARTIFACT_NOT_FOUND. Begin, read and completed attachment status require a session.
 * t10  limits         a file over the size limit and the sixth open upload of an author are refused by begin, whole
 * t12  evidence       a record that points at an attachment: none, the other session's, the same session's (SHA256_MATCHED), another digest (a passing stage is refused, another stage
 *                     records NOT_CHECKED), no digest, two items at one attachment
 * t12  artifacts      the same for the artifacts of the provider's output: the server's checks are by attachment
 * t12  PC files       a path or a {@code locator} where an attachmentId belongs is refused by the contract; a file of the caller's computer changed or deleted after its upload
 *                     changes nothing, and the run finalizes on the attachment
 * t12  output file    an outputFile that names an attachment is read and judged like inline output: the same bytes with a byte order mark, another digest, not JSON, not an object,
 *                     a missing attachment and another session's attachment (the 16 MiB limit of a document is not tested: its file would be that large)
 * t12  prior failure  the evidence of a prior failure is attachments of the calling session, and what is new is judged by the sha256 of the attachment
 * t12  audit          a mandatory audit recorded on attachments is finished on them; one whose audited attachment is lost or damaged (its stored file removed or emptied) is refused at
 *                     the finish with AUDIT_TARGET_UNAVAILABLE; the same when the audited file is file 1 of a bundle (the refusal names the file number)
 *
 * An attachment is a bundle of files ("attachments can be uploaded many files at once"): artifact_begin takes {@code files}, answers the attachmentId and an uploadId for each file,
 * artifact_finish takes the attachmentId and makes the attachment only when ALL files verify, and every pointer of a stage result (evidence, artifact, output file) names a file by
 * (attachmentId, seq). A seq of 0 is the default and is not written in what the server records; seq 1 and more are.
 * c1  bundle          a bundle of 2 files uploaded by the tools; evidence item A at (attachment, seq 0) with file 0's digest, item B at (attachment, seq 1) with file 1's digest: both
 *                     artifactChecks are SHA256_MATCHED and carry seq 0 / 1 (the run keeps them); an artifact at file 1 and an evidence item at file 0 likewise
 * c2  digest by file  on a passing stage the digest of file 1 declared for file 0 (and file 0's for file 1) is BINDING_INVALID ARTIFACT_DIGEST_MISMATCH with details.seq, nothing recorded;
 *                     on a stage that does not pass it is NOT_CHECKED (the other file's check is SHA256_MATCHED)
 * c3  no such file    seq 5 of a 2-file attachment (evidence), seq 2 (artifact) and seq 7 (output file) are BINDING_INVALID ATTACHMENT_NOT_FOUND with details.seq, a negative seq is INVALID_INPUT,
 *                     artifact_read of seq 5 is ARTIFACT_NOT_FOUND; a file of another session's bundle is ATTACHMENT_AUTHOR_MISMATCH whatever its number
 * c4  output file     the output file is file 1 of a bundle with that file's digest: read, judged, and the receipt keeps seq 1; file 0 of the attachment with file 1's digest is INTEGRITY_FAILED
 *                     and with its own digest (not JSON) INVALID_INPUT
 * c6  finish whole    a finish with a bad second file (short, or other bytes) is refused (ARTIFACT_INCOMPLETE / ARTIFACT_HASH_MISMATCH), makes no attachment (artifact_status by attachmentId is
 *                     not found, no result can point at it, the uploads are still STAGING), and a later finish of a completed second file makes it, with both files, once
 * c8  prior failure   the evidence of a prior failure may be a bundle: the same files again are NEW_EVIDENCE_REQUIRED, the same files in another order, or one more file, are new
 * (c5 is StageBundleChecksTest, c7 the CLI send in UploadClientServerTest; begin/status/read answers of a bundle are the t1 and "operation key" cases below)
 * </pre>
 */
@Tag("business-db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachmentEvidenceServerTest {
    private static final String ONE = "1.0.0";
    /** {@code sennard.attachments.max-file-bytes} of the server below: 1KB is 1024 bytes. */
    private static final int LIMIT = 1024;
    private static final int PAGE = 256;
    private static final String TEXT = "text/plain";
    private static final String JSON = "application/json";
    private static final String PASSED_OUTPUT = "{\"status\":\"passed\"}";

    private ToolServerSupport sennard;
    private Path workspaces;

    @BeforeAll
    void start() throws Exception {
        Path repository = repository();
        // The edit gate does not count the OS temporary directory, so the workspaces of the runs live in this checkout's build directory (as WorkflowToolsServerTest's).
        workspaces = Path.of("build", "attachment-evidence-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        sennard = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"), "--sennard.attachments.max-file-bytes=1KB");
    }

    @AfterAll
    void stop() {
        sennard.close();
    }

    private static Path repository() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("skills/registry.json"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException("skills/registry.json not found above " + Path.of("").toAbsolutePath());
        }
        return at;
    }

    // ---------------------------------------------------------------------------------------------------------------------------- calls

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> v1(Object... pairs) {
        Map<String, Object> map = args("schemaVersion", ONE);
        map.putAll(args(pairs));
        return map;
    }

    /** Every call is a host call of its own: the server consumes the execution observation of a call once. */
    private JsonNode call(String tool, Map<String, Object> arguments, String session) {
        return sennard.callAsNewHostCall(tool, arguments, session);
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(false), answer.toString());
        return answer.path("data");
    }

    private static String sha(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    private static String bare(String digest) {
        return digest.substring("sha256:".length());
    }

    /** A refused call: the code of the envelope and, when given, the reason the workflow names in the details. */
    private static void assertRefused(JsonNode answer, String code, String reason) {
        assertFalse(answer.path("ok").asBoolean(true), answer.toString());
        assertEquals(code, answer.path("error").path("code").asString(), answer.toString());
        if (reason != null) {
            assertEquals(reason, answer.path("error").path("details").path("reason").asString(), answer.toString());
        }
    }

    /** A refusal of a result that names an attachment: the code, the reason, the sentence and the pointer (where in the result, which item, which attachment) the details carry. */
    private static void assertAttachmentRefused(JsonNode answer, String reason, String messageKey, String source, String attachmentId) {
        assertRefused(answer, "BINDING_INVALID", reason);
        assertEquals(messageKey, answer.path("error").path("messageKey").asString(), answer.toString());
        JsonNode details = answer.path("error").path("details");
        assertEquals(source, details.path("source").asString(), answer.toString());
        assertEquals(attachmentId, details.path("attachmentId").asString(), answer.toString());
    }

    /** An attachment tool's refusal: the code is the constant's name, and the details say whether sending the same request again later can succeed. */
    private static void assertArtifactRefused(JsonNode answer, String code, boolean retryable) {
        assertRefused(answer, code, null);
        JsonNode retry = answer.path("error").path("details").path("retryable");
        assertTrue(retry.isBoolean(), "details.retryable is carried: " + answer);
        assertEquals(retryable, retry.asBoolean(), answer.toString());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- uploads

    /** The {@code files} of an {@code artifact_begin}: one entry for each of these files, as the model declares them. */
    private static List<Map<String, Object>> declared(String mediaType, byte[]... files) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < files.length; index++) {
            entries.add(args("fileName", files.length == 1 ? "evidence.txt" : "file-" + index + ".txt", "mediaType", mediaType, "size", files[index].length, "sha256", sha(files[index])));
        }
        return entries;
    }

    /** The begin of an attachment of these files (numbered in this order), as the model's session makes it. */
    private JsonNode beginAll(String session, String operationKey, String mediaType, byte[]... files) {
        return call("artifact_begin", v1("operationKey", operationKey, "files", declared(mediaType, files), "purpose", "evidence"), session);
    }

    /** The begin of an attachment of one file. */
    private JsonNode begin(String session, String operationKey, String fileName, String mediaType, byte[] bytes) {
        Map<String, Object> file = args("fileName", fileName, "mediaType", mediaType, "size", bytes.length, "sha256", sha(bytes));
        return call("artifact_begin", v1("operationKey", operationKey, "files", List.of(file), "purpose", "evidence"), session);
    }

    private static String uploadIdOf(JsonNode begun, int seq) {
        return begun.path("files").get(seq).path("uploadId").asString();
    }

    /** The upload of the only file of a begin. */
    private static String uploadIdOf(JsonNode begun) {
        return uploadIdOf(begun, 0);
    }

    private static String freshKey() {
        return "op-" + UUID.randomUUID();
    }

    private AttachmentUploads uploads() {
        return sennard.bean(AttachmentUploads.class);
    }

    private AttachmentService.Progress chunk(String uploadId, int sequence, byte[] part) {
        return uploads().chunk(uploadId, sequence, part, sha(part));
    }

    /** Seed staging rows and encrypted segments for recovery and finish tests. */
    private void stageChunks(String uploadId, byte[] bytes, int chunkBytes) {
        int sequence = 0;
        for (int at = 0; at < bytes.length; at += chunkBytes) {
            chunk(uploadId, sequence++, Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + chunkBytes)));
        }
    }

    private JsonNode finish(String session, String attachmentId) {
        return call("artifact_finish", v1("attachmentId", attachmentId), session);
    }

    private Uploaded upload(String session, byte[] bytes, String mediaType) {
        return sennard.upload(session, bytes, mediaType, "evidence.txt");
    }

    /** One attachment of several files, with one reference per file in declaration order. */
    private List<Uploaded> uploadBundle(String session, byte[]... files) {
        JsonNode begun = data(beginAll(session, freshKey(), TEXT, files));
        assertEquals(files.length, begun.path("files").size(), begun.toString());
        for (int seq = 0; seq < files.length; seq++) {
            ToolServerSupport.sendMultipart(sennard.server.port, uploadIdOf(begun, seq), files[seq]);
        }
        JsonNode finished = data(finish(session, begun.path("attachmentId").asString()));
        List<Uploaded> kept = new ArrayList<>();
        for (int seq = 0; seq < files.length; seq++) {
            assertEquals(seq, finished.path("files").get(seq).path("seq").asInt(), finished.toString());
            kept.add(new Uploaded(finished.path("attachmentId").asString(), seq, finished.path("files").get(seq).path("sha256").asString()));
        }
        return kept;
    }

    /** Every page of an attachment's first file, one after the other, as a reader outside the model reads it. */
    private byte[] readAll(String session, String attachmentId, long size) {
        return readAll(session, attachmentId, 0, size);
    }

    /** Every page of file {@code seq} of an attachment. */
    private byte[] readAll(String session, String attachmentId, int seq, long size) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        long pages = size / PAGE + 2;
        for (long page = 0; page < pages; page++) {
            JsonNode answer = data(call("artifact_read", v1("attachmentId", attachmentId, "seq", seq, "offsetBytes", whole.size(), "maxBytes", PAGE), session));
            assertEquals(seq, answer.path("seq").asInt(), answer.toString());
            byte[] part = Base64.getDecoder().decode(answer.path("data").asString());
            assertEquals(whole.size(), answer.path("offsetBytes").asLong(), answer.toString());
            assertEquals(part.length, answer.path("bytes").asInt(), answer.toString());
            assertEquals(size, answer.path("size").asLong(), answer.toString());
            whole.write(part, 0, part.length);
            if (answer.path("end").asBoolean(false)) {
                return whole.toByteArray();
            }
            assertTrue(part.length > 0, "a page that is not the last one has bytes: " + answer);
        }
        throw new AssertionError("The attachment " + attachmentId + " of " + size + " bytes did not end in " + pages + " pages");
    }

    private static byte[] lines(int count) {
        return "evidence line\n".repeat(count).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- runs

    /** A started run of one stage: the session that started it, its root, and the arguments it was started with (a continuing start sends them again). */
    private record Run(String session, String runId, String stageId, long revision, String rootId, Map<String, Object> startArguments) {
    }

    private JsonNode receipt(String session, String runId) {
        return data(call("get_workflow_status", args("runId", runId), session));
    }

    private static String stageOf(JsonNode receipt) {
        return receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
    }

    /** A run of one stage that this session has just started, on a workspace of its own. */
    private Run startRun(String session) throws IOException {
        Path workspace = Files.createDirectories(workspaces.resolve("w-" + UUID.randomUUID()));
        Map<String, Object> startArguments = args("goal", "Keep the evidence of " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills",
                List.of(args("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The evidence is kept."), "workspace", workspace.toString(), "writeTargets", List.of("."));
        JsonNode started = data(call("start_workflow", startArguments, session));
        String runId = started.path("runId").asString();
        JsonNode current = receipt(session, runId);
        return new Run(session, runId, stageOf(current), current.path("revision").asLong(), started.path("rootId").asString(), startArguments);
    }

    /** The result of the run's stage with this state, this provider output, this evidence and this error (the provider's and the stage's are the same). */
    private static Map<String, Object> resultOf(Run run, String state, Map<String, Object> output, List<Map<String, Object>> evidence, Map<String, Object> error) {
        return v1("runId", run.runId(), "expectedRevision", run.revision(), "stageId", run.stageId(), "state", state, "output", output, "evidence", evidence, "findings", List.of(), "blockers",
                List.of(), "error", error);
    }

    /** The provider's output: its status (the implementation stage's mapping reads it), the artifacts it names, and its error. */
    private static Map<String, Object> providerOutput(String status, List<Map<String, Object>> artifacts, Map<String, Object> error) {
        return v1("kind", "output", "output", args("status", status), "artifacts", artifacts, "error", error);
    }

    /** The passing result of the run's stage, with these evidence items. */
    private static Map<String, Object> passed(Run run, List<Map<String, Object>> evidence) {
        return resultOf(run, "PASSED", providerOutput("passed", List.of(), null), evidence, null);
    }

    /** The passing result of the run's stage, with these evidence items and these artifacts of the provider's output. */
    private static Map<String, Object> passedWithArtifacts(Run run, List<Map<String, Object>> evidence, List<Map<String, Object>> artifacts) {
        return resultOf(run, "PASSED", providerOutput("passed", artifacts, null), evidence, null);
    }

    /** The failing result of the run's stage (the implementation stage's provider says failed, with GATE_FAILED), with these evidence items and artifacts. */
    private static Map<String, Object> failed(Run run, List<Map<String, Object>> evidence, List<Map<String, Object>> artifacts) {
        Map<String, Object> error = args("code", "GATE_FAILED", "message", "The test run exited 1.", "details", null);
        return resultOf(run, "FAILED", providerOutput("failed", artifacts, error), evidence, error);
    }

    /** The passing result whose provider output is in the attachment {@code outputFile} names; {@code output.output} is null then. */
    private static Map<String, Object> passedByFile(Run run, List<Map<String, Object>> evidence, Map<String, Object> outputFile) {
        Map<String, Object> result = resultOf(run, "PASSED", v1("kind", "output", "output", null, "artifacts", List.of(), "error", null), evidence, null);
        result.put("outputFile", outputFile);
        return result;
    }

    /** An evidence item that points at an attachment; {@code digest} is left out when null. */
    private static Map<String, Object> pointing(String attachmentId, String digest) {
        Map<String, Object> item = args("artifactId", "uploaded", "kind", "file", "attachmentId", attachmentId);
        if (digest != null) {
            item.put("digest", digest);
        }
        item.put("verified", true);
        item.put("note", "Uploaded with the artifact tools.");
        return item;
    }

    private static Map<String, Object> pointing(Uploaded uploaded) {
        return uploaded.seq() == 0 ? pointing(uploaded.attachmentId(), uploaded.sha256()) : pointing(uploaded.attachmentId(), uploaded.seq(), uploaded.sha256());
    }

    /** An evidence item that points at file {@code seq} of an attachment, the number written out (also for file 0). */
    private static Map<String, Object> pointing(String attachmentId, int seq, String digest) {
        Map<String, Object> item = args("artifactId", "uploaded-" + seq, "kind", "file", "attachmentId", attachmentId, "seq", seq);
        item.put("digest", digest);
        item.put("verified", true);
        item.put("note", "File " + seq + " of an attachment uploaded with the artifact tools.");
        return item;
    }

    /** An artifact of the provider's output that points at an attachment; its digest is spelled as the caller likes (bare or as {@code sha256:<hex>}). */
    private static Map<String, Object> artifact(String attachmentId, String digest) {
        return args("artifactId", "report", "schemaId", "artifact.v1", "attachmentId", attachmentId, "digest", digest, "targetDigest", "target", "verified", true);
    }

    private static Map<String, Object> outputFile(Uploaded uploaded) {
        Map<String, Object> file = args("attachmentId", uploaded.attachmentId());
        if (uploaded.seq() > 0) {
            file.put("seq", uploaded.seq());
        }
        file.put("digest", uploaded.sha256());
        return file;
    }

    private long attachmentRows() {
        return SessionServerSupport.count(sennard.server, "SELECT COUNT(*) FROM sennard.attachment");
    }

    /** A refused record leaves the run as it was and makes no attachment (the rows are made in the transaction of the record, which did not commit). */
    private void assertNothingRecorded(Run run, long rowsBefore) {
        JsonNode after = receipt(run.session(), run.runId());
        assertEquals(run.revision(), after.path("revision").asLong(), "the revision of the run is as it was: " + after);
        assertEquals(0, after.path("stageResults").size(), "no stage result is recorded: " + after);
        assertEquals(rowsBefore, attachmentRows(), "the refused record made no attachment");
    }

    private static JsonNode firstResult(JsonNode recorded) {
        return recorded.path("stageResults").get(0);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 evidence

    /** Design test 12: an evidence item that points at no attachment is refused and nothing is recorded. */
    @Test
    void t12aRecordPointingAtNoAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Run run = startRun(session);
        long rows = attachmentRows();
        String missing = UUID.randomUUID().toString();

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(missing, sha(lines(3))))), session);

        assertAttachmentRefused(refused, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "EVIDENCE", missing);
        assertNothingRecorded(run, rows);
    }

    /** Design test 12: an attachment that another session made is not the author's, and the record fails. */
    @Test
    void t12bRecordPointingAtAnotherSessionsAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String author = sennard.session();
        byte[] bytes = lines(10);
        Uploaded uploaded = upload(author, bytes, TEXT);
        String other = sennard.session();
        Run run = startRun(other);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(uploaded))), other);

        assertAttachmentRefused(refused, "ATTACHMENT_AUTHOR_MISMATCH", "workflow.service.evidenceAttachmentOtherAuthor", "EVIDENCE", uploaded.attachmentId());
        assertNothingRecorded(run, rows);
        assertEquals(bytes.length, data(call("artifact_status", v1("attachmentId", uploaded.attachmentId()), author)).path("files").get(0).path("size").asLong(), "the author's attachment is untouched");
    }

    /** Design test 12: the same session's attachment passes, and the recorded evidence carries its attachmentId and the check the server made of it, by attachment. */
    @Test
    void t12cRecordPointingAtTheSameSessionsAttachmentIsRecordedWithItsAttachmentIdAndChecked() throws Exception {
        String session = sennard.session();
        byte[] bytes = lines(10);
        Uploaded uploaded = upload(session, bytes, TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode recorded = data(call("record_stage_result", passed(run, List.of(pointing(uploaded))), session));

        JsonNode result = firstResult(recorded);
        JsonNode item = result.path("evidence").get(0);
        assertEquals(uploaded.attachmentId(), item.path("attachmentId").asString(), recorded.toString());
        assertEquals(uploaded.sha256(), item.path("digest").asString(), recorded.toString());
        assertFalse(item.has("locator"), "an evidence item names no path: " + item);
        JsonNode check = result.path("artifactChecks").get(0);
        assertEquals("EVIDENCE", check.path("source").asString(), result.toString());
        assertEquals("uploaded", check.path("artifactId").asString(), result.toString());
        assertEquals(uploaded.attachmentId(), check.path("attachmentId").asString(), result.toString());
        assertEquals("SHA256_MATCHED", check.path("check").asString(), result.toString());
        JsonNode stored = firstResult(receipt(session, run.runId())).path("evidence").get(0);
        assertEquals(uploaded.attachmentId(), stored.path("attachmentId").asString(), "the run keeps it: " + stored);
        assertEquals(rows, attachmentRows(), "the item points at the attachment that was there; the server made no other");
    }

    /** Design test 12: a digest that is not the attachment's sha256 on a passing stage is refused as ARTIFACT_DIGEST_MISMATCH and nothing is recorded. */
    @Test
    void t12dRecordWithADigestThatIsNotTheAttachmentsOnAPassingStageIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded uploaded = upload(session, lines(10), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(uploaded.attachmentId(), sha(lines(11))))), session);

        assertRefused(refused, "BINDING_INVALID", "ARTIFACT_DIGEST_MISMATCH");
        assertEquals("workflow.service.recordedDigestMatchAttachment", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals(uploaded.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** A digest that is not the attachment's sha256 on a stage that does not pass is recorded, and the server's check of the item is NOT_CHECKED, not SHA256_MATCHED. */
    @Test
    void aDigestThatIsNotTheAttachmentsOnAStageThatDoesNotPassIsRecordedAsNotChecked() throws Exception {
        String session = sennard.session();
        Uploaded uploaded = upload(session, lines(10), TEXT);
        Run run = startRun(session);

        JsonNode recorded = data(call("record_stage_result", failed(run, List.of(pointing(uploaded.attachmentId(), sha(lines(11)))), List.of()), session));

        JsonNode result = firstResult(recorded);
        assertEquals("FAILED", result.path("state").asString(), result.toString());
        assertEquals(uploaded.attachmentId(), result.path("evidence").get(0).path("attachmentId").asString(), result.toString());
        assertEquals(1, result.path("artifactChecks").size(), result.toString());
        assertEquals("NOT_CHECKED", result.path("artifactChecks").get(0).path("check").asString(), result.toString());
        assertEquals(uploaded.attachmentId(), result.path("artifactChecks").get(0).path("attachmentId").asString(), result.toString());
    }

    /**
     * Design test 12: an item with no digest. The stage result's contract says so first ({@code digest} is required of an evidence item, as is the attachmentId), so the answer is
     * the contract's INVALID_INPUT.
     */
    @Test
    void t12eRecordPointingAtAnAttachmentWithNoDigestIsRefusedByTheContractAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded uploaded = upload(session, lines(10), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(uploaded.attachmentId(), null))), session);

        assertRefused(refused, "INVALID_INPUT", null);
        assertNothingRecorded(run, rows);
    }

    /** Two evidence items may point at the same attachment: both are recorded, each with its own check, and the server makes no attachment. */
    @Test
    void twoEvidenceItemsAtTheSameAttachmentAreRecordedNormally() throws Exception {
        String session = sennard.session();
        Uploaded uploaded = upload(session, lines(10), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();
        Map<String, Object> second = pointing(uploaded);
        second.put("artifactId", "uploaded-again");

        JsonNode recorded = data(call("record_stage_result", passed(run, List.of(pointing(uploaded), second)), session));

        JsonNode result = firstResult(recorded);
        assertEquals(2, result.path("evidence").size(), result.toString());
        assertEquals(2, result.path("artifactChecks").size(), result.toString());
        for (int index = 0; index < 2; index++) {
            assertEquals(uploaded.attachmentId(), result.path("evidence").get(index).path("attachmentId").asString(), result.toString());
            assertEquals(uploaded.attachmentId(), result.path("artifactChecks").get(index).path("attachmentId").asString(), result.toString());
            assertEquals("SHA256_MATCHED", result.path("artifactChecks").get(index).path("check").asString(), result.toString());
        }
        assertEquals("uploaded", result.path("artifactChecks").get(0).path("artifactId").asString(), result.toString());
        assertEquals("uploaded-again", result.path("artifactChecks").get(1).path("artifactId").asString(), result.toString());
        assertEquals(rows, attachmentRows(), "both items point at the attachment that was there");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 artifacts

    /** An artifact of the provider's output that points at the session's attachment is checked by attachment: its digest, bare or prefixed, is the attachment's sha256. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anArtifactOfTheOutputThatPointsAtTheSessionsAttachmentIsRecordedAndMatched(boolean prefixed) throws Exception {
        String session = sennard.session();
        Uploaded artifactBytes = upload(session, lines(7), TEXT);
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);
        String declared = prefixed ? artifactBytes.sha256() : bare(artifactBytes.sha256());

        JsonNode recorded = data(call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifact(artifactBytes.attachmentId(), declared))), session));

        JsonNode result = firstResult(recorded);
        assertEquals(artifactBytes.attachmentId(), result.path("output").path("artifacts").get(0).path("attachmentId").asString(), result.toString());
        assertFalse(result.path("output").path("artifacts").get(0).has("locator"), "an artifact names no path: " + result);
        JsonNode checks = result.path("artifactChecks");
        assertEquals(2, checks.size(), result.toString());
        assertEquals("ARTIFACT", checks.get(0).path("source").asString(), result.toString());
        assertEquals("report", checks.get(0).path("artifactId").asString(), result.toString());
        assertEquals(artifactBytes.attachmentId(), checks.get(0).path("attachmentId").asString(), result.toString());
        assertEquals("SHA256_MATCHED", checks.get(0).path("check").asString(), result.toString());
        assertEquals("EVIDENCE", checks.get(1).path("source").asString(), result.toString());
        assertEquals("SHA256_MATCHED", checks.get(1).path("check").asString(), result.toString());
    }

    @Test
    void anArtifactWhoseDigestIsNotItsAttachmentsOnAPassingStageIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded artifactBytes = upload(session, lines(7), TEXT);
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifact(artifactBytes.attachmentId(), bare(sha(lines(9)))))), session);

        assertRefused(refused, "BINDING_INVALID", "ARTIFACT_DIGEST_MISMATCH");
        assertEquals(artifactBytes.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        assertEquals("report", refused.path("error").path("details").path("artifactId").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    @Test
    void anArtifactWhoseDigestIsNotItsAttachmentsOnAStageThatDoesNotPassIsRecordedAsNotChecked() throws Exception {
        String session = sennard.session();
        Uploaded artifactBytes = upload(session, lines(7), TEXT);
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);

        JsonNode recorded = data(call("record_stage_result", failed(run, List.of(pointing(evidence)), List.of(artifact(artifactBytes.attachmentId(), sha(lines(9))))), session));

        JsonNode checks = firstResult(recorded).path("artifactChecks");
        assertEquals(2, checks.size(), recorded.toString());
        assertEquals("ARTIFACT", checks.get(0).path("source").asString(), recorded.toString());
        assertEquals("NOT_CHECKED", checks.get(0).path("check").asString(), recorded.toString());
        assertEquals("EVIDENCE", checks.get(1).path("source").asString(), recorded.toString());
        assertEquals("SHA256_MATCHED", checks.get(1).path("check").asString(), recorded.toString());
    }

    @Test
    void anArtifactThatPointsAtNoAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();
        String missing = UUID.randomUUID().toString();

        JsonNode refused = call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifact(missing, bare(sha(lines(7)))))), session);

        assertAttachmentRefused(refused, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "ARTIFACT", missing);
        assertEquals("report", refused.path("error").path("details").path("artifactId").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    @Test
    void anArtifactThatPointsAtAnotherSessionsAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String author = sennard.session();
        Uploaded foreign = upload(author, lines(7), TEXT);
        String other = sennard.session();
        Uploaded evidence = upload(other, lines(8), TEXT);
        Run run = startRun(other);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifact(foreign.attachmentId(), bare(foreign.sha256())))), other);

        assertAttachmentRefused(refused, "ATTACHMENT_AUTHOR_MISMATCH", "workflow.service.evidenceAttachmentOtherAuthor", "ARTIFACT", foreign.attachmentId());
        assertNothingRecorded(run, rows);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 files of the caller's computer

    /**
     * What the contract says of a path: no field of a stage result names a file of the caller's computer. A Windows path, a POSIX path, a share and a relative name where an
     * attachmentId belongs (in an evidence item, in an artifact of the output, in the outputFile) are refused, and nothing is recorded.
     */
    @ParameterizedTest
    @ValueSource(strings = {"C:\\Users\\someone\\evidence.txt", "C:/Users/someone/evidence.txt", "/home/someone/evidence.txt", "\\\\server\\share\\evidence.txt", "./evidence.txt", "evidence.txt"})
    void aPathWhereAnAttachmentIdBelongsIsRefusedByTheContractAndRecordsNothing(String path) throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();

        assertRefused(call("record_stage_result", passed(run, List.of(pointing(path, evidence.sha256()))), session), "INVALID_INPUT", null);
        assertRefused(call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifact(path, bare(evidence.sha256())))), session), "INVALID_INPUT", null);
        assertRefused(call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), args("attachmentId", path, "digest", evidence.sha256())), session), "INVALID_INPUT", null);

        assertNothingRecorded(run, rows);
    }

    /** The {@code locator} the contract had is gone: a result that still carries it, next to the attachmentId, is refused in each place, and nothing is recorded. */
    @Test
    void aLocatorPropertyNextToTheAttachmentIdIsRefusedByTheContractAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.uploadJson(session, PASSED_OUTPUT);
        Run run = startRun(session);
        long rows = attachmentRows();
        Map<String, Object> evidenceWithLocator = pointing(evidence);
        evidenceWithLocator.put("locator", "evidence.txt");
        Map<String, Object> artifactWithLocator = artifact(evidence.attachmentId(), evidence.sha256());
        artifactWithLocator.put("locator", "evidence.txt");
        Map<String, Object> fileWithLocator = outputFile(document);
        fileWithLocator.put("locator", "output.json");

        assertRefused(call("record_stage_result", passed(run, List.of(evidenceWithLocator)), session), "INVALID_INPUT", null);
        assertRefused(call("record_stage_result", passedWithArtifacts(run, List.of(pointing(evidence)), List.of(artifactWithLocator)), session), "INVALID_INPUT", null);
        assertRefused(call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), fileWithLocator), session), "INVALID_INPUT", null);
        // A file of the caller's computer named by its locator alone, in the place the old contract had it, is not an item of the contract either.
        Map<String, Object> locatorOnly = args("artifactId", "local", "kind", "file", "locator", "C:\\Users\\someone\\evidence.txt", "verified", true, "digest", evidence.sha256(), "note", "A file of this computer.");
        assertRefused(call("record_stage_result", passed(run, List.of(locatorOnly)), session), "INVALID_INPUT", null);

        assertNothingRecorded(run, rows);
    }

    /**
     * The server never reads the caller's computer, so the file the caller made the attachment from is not part of the result: it can be changed or deleted after the upload, and
     * the record and the finish still pass on the attachment, which keeps the bytes that were sent.
     */
    @ParameterizedTest
    @ValueSource(strings = {"changed", "deleted"})
    void aFileOfTheCallersComputerChangedOrDeletedAfterItsUploadChangesNothingAndTheRunFinalizes(String what, @TempDir Path temp) throws Exception {
        byte[] bytes = lines(10);
        Path file = Files.write(temp.resolve("evidence.txt"), bytes);
        String session = sennard.session();
        Uploaded uploaded = upload(session, Files.readAllBytes(file), TEXT);
        Run run = startRun(session);
        if (what.equals("changed")) {
            Files.write(file, lines(11));
        } else {
            Files.delete(file);
        }

        JsonNode recorded = data(call("record_stage_result", passed(run, List.of(pointing(uploaded))), session));
        JsonNode finalized = data(call("finalize_workflow", args("runId", run.runId(), "expectedRevision", recorded.path("revision").asLong()), session));

        assertEquals("PASSED", finalized.path("state").asString(), finalized.toString());
        JsonNode result = firstResult(finalized);
        assertEquals(uploaded.attachmentId(), result.path("evidence").get(0).path("attachmentId").asString(), result.toString());
        assertEquals("SHA256_MATCHED", result.path("artifactChecks").get(0).path("check").asString(), result.toString());
        assertArrayEquals(bytes, readAll(session, uploaded.attachmentId(), bytes.length), "the attachment still has the bytes that were uploaded");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 output file

    /** An outputFile that names the session's attachment is read and judged like inline output: its status maps the stage, and the receipt keeps the reference. */
    @Test
    void anOutputFileThatNamesTheSessionsAttachmentIsReadAndJudgedLikeInlineOutput() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.uploadJson(session, PASSED_OUTPUT);
        Run run = startRun(session);

        JsonNode recorded = data(call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(document)), session));

        JsonNode result = firstResult(recorded);
        assertEquals("PASSED", result.path("state").asString(), result.toString());
        assertEquals(document.attachmentId(), result.path("outputFile").path("attachmentId").asString(), result.toString());
        assertEquals(document.sha256(), result.path("outputFile").path("digest").asString(), result.toString());
        JsonNode kept = result.path("output").path("output");
        assertTrue(kept.isNull() || kept.isMissingNode(), "the receipt keeps the reference, not the document: " + result);
    }

    /** What the document says is what is judged: the file says failed, so a result recorded as passed does not match the provider's mapping, as for inline output. */
    @Test
    void anOutputFileWhoseDocumentSaysFailedDoesNotMatchAPassedStageLikeInlineOutput() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.uploadJson(session, "{\"status\":\"failed\"}");
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(document)), session);

        assertRefused(refused, "INVALID_TRANSITION", null);
        assertNothingRecorded(run, rows);
    }

    /** A byte order mark, which a Windows tool may write, is the encoding mark it is; the digest is of the bytes with it. */
    @Test
    void anOutputFileWithAByteOrderMarkIsRead() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        byte[] marked = ("\uFEFF" + PASSED_OUTPUT).getBytes(StandardCharsets.UTF_8);
        assertEquals(0xEF, marked[0] & 0xFF, "the document starts with the three bytes of the byte order mark");
        Uploaded document = sennard.upload(session, marked, JSON, "output.json");
        Run run = startRun(session);

        JsonNode recorded = data(call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(document)), session));

        assertEquals("PASSED", firstResult(recorded).path("state").asString(), recorded.toString());
    }

    /** The digest the result declares for the output file is not the sha256 of the attachment's bytes: INTEGRITY_FAILED, and nothing is recorded. */
    @Test
    void anOutputFileWhoseBytesAreNotItsDigestIsRefusedAsAnIntegrityFailureAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.uploadJson(session, PASSED_OUTPUT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), args("attachmentId", document.attachmentId(), "digest", sha(lines(9)))), session);

        assertRefused(refused, "INTEGRITY_FAILED", null);
        assertEquals("workflow.outputFile.outputFileContentMatchDigest", refused.path("error").path("messageKey").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** An attachment that is not JSON is refused, with the sentence of the output file's own check. */
    @Test
    void anOutputFileThatIsNotJsonIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.upload(session, "{\"status\": passed".getBytes(StandardCharsets.UTF_8), JSON, "output.json");
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(document)), session);

        assertRefused(refused, "INVALID_INPUT", null);
        assertEquals("workflow.outputFile.outputFileValidJSON", refused.path("error").path("messageKey").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** JSON that is not an object (the provider output is an object) is refused, with the sentence of the output file's own check. */
    @ParameterizedTest
    @ValueSource(strings = {"[1,2]", "7", "\"passed\"", "null", "true"})
    void anOutputFileThatIsJsonButNotAnObjectIsRefusedAndRecordsNothing(String json) throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Uploaded document = sennard.uploadJson(session, json);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(document)), session);

        assertRefused(refused, "INVALID_INPUT", null);
        assertEquals("workflow.outputFile.outputFileContainJSONObject", refused.path("error").path("messageKey").asString(), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** An outputFile that names no attachment is refused as the evidence is (the attachment is looked at in the recording's transaction), and nothing is recorded. */
    @Test
    void anOutputFileThatNamesNoAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String session = sennard.session();
        Uploaded evidence = upload(session, lines(8), TEXT);
        Run run = startRun(session);
        long rows = attachmentRows();
        String missing = UUID.randomUUID().toString();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), args("attachmentId", missing, "digest", sha(PASSED_OUTPUT.getBytes(StandardCharsets.UTF_8)))), session);

        assertAttachmentRefused(refused, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "OUTPUT_FILE", missing);
        assertNothingRecorded(run, rows);
    }

    /** An outputFile that names another session's attachment is refused: the output of a result is the author's own. */
    @Test
    void anOutputFileThatNamesAnotherSessionsAttachmentIsRefusedAndRecordsNothing() throws Exception {
        String author = sennard.session();
        Uploaded foreign = sennard.uploadJson(author, PASSED_OUTPUT);
        String other = sennard.session();
        Uploaded evidence = upload(other, lines(8), TEXT);
        Run run = startRun(other);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passedByFile(run, List.of(pointing(evidence)), outputFile(foreign)), other);

        assertAttachmentRefused(refused, "ATTACHMENT_AUTHOR_MISMATCH", "workflow.service.evidenceAttachmentOtherAuthor", "OUTPUT_FILE", foreign.attachmentId());
        assertNothingRecorded(run, rows);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 prior failure

    private void abort(String session, String runId) {
        long revision = receipt(session, runId).path("revision").asLong();
        data(call("abort_workflow", args("runId", runId, "expectedRevision", revision), session));
    }

    /** The start that continues the root of this run after its failure, with this evidence for the failure (the skills are the ones a retry declares). */
    private static Map<String, Object> continuing(Run run, List<String> evidenceRefs) {
        Map<String, Object> next = new LinkedHashMap<>(run.startArguments());
        next.put("rootId", run.rootId());
        next.put("priorFailure", args("hypothesis", "h", "changeSummary", "c", "discriminator", "d", "evidenceRefs", evidenceRefs));
        next.put("skills", List.of(args("skillId", "blocker-diagnostician", "reason", "the first attempt failed")));
        return next;
    }

    /** The evidence of a prior failure is an attachment that exists: a reference to none is MISSING_EVIDENCE, and the root is left as it was. */
    @Test
    void aPriorFailureWhoseEvidenceIsNoAttachmentIsRefusedAndTheRootStaysOpenForTheRightEvidence() throws Exception {
        String session = sennard.session();
        Run first = startRun(session);
        abort(session, first.runId());
        String missing = UUID.randomUUID().toString();

        JsonNode refused = call("start_workflow", continuing(first, List.of(missing)), session);

        assertRefused(refused, "MISSING_EVIDENCE", "ATTACHMENT_NOT_FOUND");
        assertEquals("workflow.service.priorFailureEvidenceAttachment", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals(missing, refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        Uploaded evidence = upload(session, lines(5), TEXT);
        JsonNode resumed = data(call("start_workflow", continuing(first, List.of(evidence.attachmentId())), session));
        assertEquals(first.rootId(), resumed.path("rootId").asString(), "the refusal left the root open to the continuation");
    }

    /** The evidence of a prior failure is the calling session's attachment: another session's is MISSING_EVIDENCE with the author's reason. */
    @Test
    void aPriorFailureWhoseEvidenceIsAnotherSessionsAttachmentIsRefused() throws Exception {
        String session = sennard.session();
        Run first = startRun(session);
        abort(session, first.runId());
        Uploaded foreign = upload(sennard.session(), lines(5), TEXT);

        JsonNode refused = call("start_workflow", continuing(first, List.of(foreign.attachmentId())), session);

        assertRefused(refused, "MISSING_EVIDENCE", "ATTACHMENT_AUTHOR_MISMATCH");
        assertEquals("workflow.service.priorFailureEvidenceAttachment", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals(foreign.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
    }

    /**
     * What is new is the sha256 the server holds for the attachment, never a new id: the same bytes uploaded again are not new evidence (NEW_EVIDENCE_REQUIRED), and bytes that
     * differ are.
     */
    @Test
    void theSameBytesUploadedAgainAreNotNewEvidenceOfAPriorFailureAndOtherBytesAre() throws Exception {
        String session = sennard.session();
        Run first = startRun(session);
        abort(session, first.runId());
        byte[] bytes = lines(5);
        JsonNode second = data(call("start_workflow", continuing(first, List.of(upload(session, bytes, TEXT).attachmentId())), session));
        abort(session, second.path("runId").asString());

        JsonNode again = call("start_workflow", continuing(first, List.of(upload(session, bytes, TEXT).attachmentId())), session);

        assertRefused(again, "NEW_EVIDENCE_REQUIRED", null);
        JsonNode third = data(call("start_workflow", continuing(first, List.of(upload(session, lines(6), TEXT).attachmentId())), session));
        assertEquals(first.rootId(), third.path("rootId").asString());
    }

    /**
     * c8: the evidence of a prior failure may be an attachment of several files; what is new is then the sha256 of all its files taken together: the same files uploaded again are not new
     * evidence, the same files in another order are, and so is the attachment with one more file.
     */
    @Test
    void theSameFilesOfABundleUploadedAgainAreNotNewEvidenceOfAPriorFailureAndOtherFilesOrAnotherOrderAre() throws Exception {
        String session = sennard.session();
        Run first = startRun(session);
        abort(session, first.runId());
        byte[] a = lines(5);
        byte[] b = lines(6);
        JsonNode second = data(call("start_workflow", continuing(first, List.of(uploadBundle(session, a, b).get(0).attachmentId())), session));
        abort(session, second.path("runId").asString());

        assertRefused(call("start_workflow", continuing(first, List.of(uploadBundle(session, a, b).get(0).attachmentId())), session), "NEW_EVIDENCE_REQUIRED", null);
        JsonNode swapped = data(call("start_workflow", continuing(first, List.of(uploadBundle(session, b, a).get(0).attachmentId())), session));
        assertEquals(first.rootId(), swapped.path("rootId").asString());

        // A root gives three attempts (ConvergenceLimits.DEFAULT) and the swapped start is the third, so the extra file is tried on a root of its own, in the same two steps.
        Run other = startRun(session);
        abort(session, other.runId());
        JsonNode otherSecond = data(call("start_workflow", continuing(other, List.of(uploadBundle(session, a, b).get(0).attachmentId())), session));
        abort(session, otherSecond.path("runId").asString());
        JsonNode more = data(call("start_workflow", continuing(other, List.of(uploadBundle(session, a, b, lines(7)).get(0).attachmentId())), session));
        assertEquals(other.rootId(), more.path("rootId").asString());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- t12 mandatory audit

    private static final String AUDIT_TARGET = "sha256:" + "a".repeat(64);
    private static final String ARTIFACT_SCHEMA = "artifact.v1";
    /** The artifacts the mandatory audit stage must produce (the independent-audit provider's own, the verdict among them). */
    private static final List<String> AUDIT_ARTIFACTS = List.of("audit-target", "audit-findings", "re-audit-status", "gate-verdict");

    /** A run of the implementation stage and the mandatory audit of a high-risk task, both recorded as passed: the run, the revision to finish on, and the attachment of the verdict. */
    private record Audited(String runId, long revision, Uploaded gateVerdict) {
    }

    /**
     * A high-risk task (its plan carries the mandatory audit, whose four artifacts the task declares with their schema and target) on a root of its own that the session opens,
     * claims and starts, the implementation stage recorded, then the audit stage: a passing audit of the planned target, by an auditor that is none of the implementers, whose four
     * artifacts and evidence are attachments of the session. A start_workflow cannot give a stage its artifact targets, so the run is made with the plan, root, lease and guarded
     * start tools.
     */
    private Audited recordedMandatoryAudit(String session) throws IOException {
        return recordedMandatoryAudit(session, false);
    }

    /** As above; with {@code verdictIsFileOne} the verdict artifact is file 1 of an attachment of two files (the audit is then recorded on a file that is not the first). */
    private Audited recordedMandatoryAudit(String session, boolean verdictIsFileOne) throws IOException {
        Run first = startRun(session);
        JsonNode current = data(call("get_convergence_status", args("rootId", first.rootId()), session)).path("root");
        data(call("release_convergence_root", args("rootId", first.rootId(), "expectedRevision", current.path("revision").asLong(), "reason", "The test opens its own root."), session));
        List<Map<String, Object>> produced = AUDIT_ARTIFACTS.stream().map(id -> args("artifactId", id, "schemaId", ARTIFACT_SCHEMA, "targetDigest", AUDIT_TARGET)).toList();
        Map<String, Object> task = new LinkedHashMap<>(ToolServerSupport.MAPPER.convertValue(current.path("taskEnvelope"), new TypeReference<Map<String, Object>>() { }));
        task.put("taskId", "task-" + UUID.randomUUID());
        task.put("riskLevel", "high");
        task.put("requiredCapabilities", List.of("minimal-implementation"));
        task.put("artifactContracts", List.of(args("capability", "independent-audit", "produced", produced)));
        Map<String, Object> frame = ToolServerSupport.MAPPER.convertValue(current.path("frame"), new TypeReference<Map<String, Object>>() { });
        JsonNode opened = data(call("open_convergence_root", v1("parentRootId", null, "taskEnvelope", task, "frame", frame, "userApprovalRefs", List.of()), session));
        JsonNode planned = data(call("plan_workflow", task, session));
        JsonNode lease = data(call("claim_workflow_attempt", v1("rootId", opened.path("rootId").asString(), "expectedRevision", opened.path("revision").asLong(), "taskEnvelope", task, "frame", frame,
                "plan", ToolServerSupport.MAPPER.convertValue(planned, new TypeReference<Map<String, Object>>() { }), "outputTargets", List.of("."), "priorFailure", null), session));
        JsonNode started = data(call("start_guarded_workflow", v1("leaseId", lease.path("leaseId").asString(), "expectedRootRevision", lease.path("rootRevision").asLong(),
                "skills", List.of(args("skillId", "minimal-implementation", "reason", "test"), args("skillId", "independent-audit-gate", "reason", "test"))), session));
        String runId = started.path("runId").asString();
        String rootId = opened.path("rootId").asString();

        JsonNode beforeImplementation = receipt(session, runId);
        Run implementation = new Run(session, runId, stageOf(beforeImplementation), beforeImplementation.path("revision").asLong(), rootId, first.startArguments());
        data(call("record_stage_result", passed(implementation, List.of(pointing(upload(session, lines(3), TEXT)))), session));

        JsonNode beforeAudit = receipt(session, runId);
        Run audit = new Run(session, runId, stageOf(beforeAudit), beforeAudit.path("revision").asLong(), rootId, first.startArguments());
        Map<String, Uploaded> kept = new LinkedHashMap<>();
        List<Map<String, Object>> artifacts = new ArrayList<>();
        int size = 4;
        for (String id : AUDIT_ARTIFACTS) {
            Uploaded uploaded = verdictIsFileOne && id.equals("gate-verdict") ? uploadBundle(session, lines(3), lines(++size)).get(1) : upload(session, lines(++size), TEXT);
            kept.put(id, uploaded);
            Map<String, Object> artifact = args("artifactId", id, "schemaId", ARTIFACT_SCHEMA, "attachmentId", uploaded.attachmentId());
            if (uploaded.seq() > 0) {
                artifact.put("seq", uploaded.seq());
            }
            artifact.putAll(args("digest", uploaded.sha256(), "targetDigest", AUDIT_TARGET, "verified", true));
            artifacts.add(artifact);
        }
        Map<String, Object> verdict = args("gateVerdict", "PASS", "auditorId", "auditor-1", "implementationActorIds", List.of("worker-1"), "auditTarget", AUDIT_TARGET, "blockingFindings", List.of(),
                "freshContext", true, "delegationAllowed", false, "stale", false, "phase", "post-execution", "postExecutionVerified", true);
        JsonNode recorded = data(call("record_stage_result", resultOf(audit, "PASSED", v1("kind", "output", "output", verdict, "artifacts", artifacts, "error", null),
                List.of(pointing(upload(session, lines(4), TEXT))), null), session));
        return new Audited(runId, recorded.path("revision").asLong(), kept.get("gate-verdict"));
    }

    /** A mandatory audit recorded on attachments that are all there is finished on them: the audit's checks are by attachment, five of them (four artifacts and the evidence). */
    @Test
    void aMandatoryAuditRecordedOnAttachmentsThatAreIntactFinalizes() throws Exception {
        String session = sennard.session();
        Audited audited = recordedMandatoryAudit(session);

        JsonNode finalized = data(call("finalize_workflow", args("runId", audited.runId(), "expectedRevision", audited.revision()), session));

        assertEquals("PASSED", finalized.path("state").asString(), finalized.toString());
        JsonNode checks = finalized.path("stageResults").get(1).path("artifactChecks");
        assertEquals(5, checks.size(), checks.toString());
        checks.forEach(check -> assertEquals("SHA256_MATCHED", check.path("check").asString(), checks.toString()));
    }

    /**
     * The finish judges again the attachments a mandatory audit was recorded on: one that was lost or damaged since (its stored file removed, or emptied) is GATE_FAILED with the
     * reason AUDIT_TARGET_UNAVAILABLE, and the run is as it was. An attachment is never rewritten, so what the finish can find is a loss, not a swapped file.
     */
    @ParameterizedTest
    @CsvSource({"removed,false", "emptied,false", "removed,true", "emptied,true"})
    void aMandatoryAuditWhoseAuditedAttachmentIsNoLongerIntactIsRefusedAtTheFinish(String damage, boolean verdictIsFileOne) throws Exception {
        String session = sennard.session();
        Audited audited = recordedMandatoryAudit(session, verdictIsFileOne);
        String attachmentId = audited.gateVerdict().attachmentId();
        int seq = audited.gateVerdict().seq();
        assertEquals(verdictIsFileOne ? 1 : 0, seq, "the audit is recorded on the file the case says");
        JdbcClient jdbc = sennard.server.context.getBean("storeJdbcClient", JdbcClient.class);
        // The stored copy of that file (the first copy, rev 0): the table of the copies holds where the encrypted bytes of each file are.
        String key = sennard.inTransaction(() -> jdbc.sql("SELECT storage_key FROM sennard.attachment_storage WHERE attachment_id = ? AND seq = ? AND rev = 0").param(attachmentId).param(seq)
                .query(String.class).single());
        Path stored = sennard.server.stateDirectory().resolve("attachments").resolve(key.substring(0, 2)).resolve(key);
        assertTrue(Files.isRegularFile(stored), "the attachment's file is in the store folder of the server: " + stored);
        // The product closes the stored file read-only; a third party who damages it has to lift that first (the product's own protection stays as it is).
        var posix = Files.getFileAttributeView(stored, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } else {
            var dos = Files.getFileAttributeView(stored, java.nio.file.attribute.DosFileAttributeView.class);
            if (dos != null) {
                dos.setReadOnly(false);
            }
        }
        if (damage.equals("removed")) {
            Files.delete(stored);
        } else {
            Files.write(stored, new byte[0]);
        }

        JsonNode refused = call("finalize_workflow", args("runId", audited.runId(), "expectedRevision", audited.revision()), session);

        assertRefused(refused, "GATE_FAILED", "AUDIT_TARGET_UNAVAILABLE");
        assertEquals("workflow.service.auditedAttachmentNotIntact", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals("gate-verdict", refused.path("error").path("details").path("artifactId").asString(), refused.toString());
        assertEquals(attachmentId, refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        assertEquals(seq, refused.path("error").path("details").path("seq").asInt(-1), "the refusal names the file: " + refused);
        assertEquals(audited.revision(), receipt(session, audited.runId()).path("revision").asLong(), "the refused finish changed nothing");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- bundles: several files in one attachment

    private static JsonNode checkOf(JsonNode result, int index) {
        return result.path("artifactChecks").get(index);
    }

    private static List<Map<String, Object>> twoItems(Uploaded first, Uploaded second) {
        Map<String, Object> a = pointing(first.attachmentId(), first.seq(), first.sha256());
        a.put("artifactId", "item-a");
        Map<String, Object> b = pointing(second.attachmentId(), second.seq(), second.sha256());
        b.put("artifactId", "item-b");
        return List.of(a, b);
    }

    /** c1: a bundle of two files made by the tools; two evidence items point at file 0 and file 1 each with its own sha256, and both are SHA256_MATCHED with the file numbers the checks carry. */
    @Test
    void c1TwoEvidenceItemsAtTwoFilesOfOneAttachmentAreEachMatchedAgainstTheirOwnFile() throws Exception {
        String session = sennard.session();
        List<Uploaded> bundle = uploadBundle(session, lines(7), lines(9));
        assertEquals(bundle.get(0).attachmentId(), bundle.get(1).attachmentId(), "one attachment");
        assertNotEquals(bundle.get(0).sha256(), bundle.get(1).sha256(), "two files of their own bytes");
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode recorded = data(call("record_stage_result", passed(run, twoItems(bundle.get(0), bundle.get(1))), session));

        JsonNode result = firstResult(recorded);
        assertEquals(2, result.path("evidence").size(), result.toString());
        assertEquals(2, result.path("artifactChecks").size(), result.toString());
        for (int index = 0; index < 2; index++) {
            JsonNode check = checkOf(result, index);
            assertEquals("EVIDENCE", check.path("source").asString(), result.toString());
            assertEquals("item-" + (index == 0 ? "a" : "b"), check.path("artifactId").asString(), result.toString());
            assertEquals(bundle.get(index).attachmentId(), check.path("attachmentId").asString(), result.toString());
            assertEquals("SHA256_MATCHED", check.path("check").asString(), "file " + index + ": " + result);
            assertEquals(index, check.path("seq").asInt(0), "the check says which file it was made on: " + check);
            assertEquals(bundle.get(index).sha256(), result.path("evidence").get(index).path("digest").asString(), result.toString());
            assertEquals(index, result.path("evidence").get(index).path("seq").asInt(0), result.toString());
        }
        // The first file is the default number and is not written; file 1 is.
        assertFalse(checkOf(result, 0).has("seq") || result.path("evidence").get(0).has("seq") && result.path("evidence").get(0).path("seq").asInt() != 0, result.toString());
        assertTrue(checkOf(result, 1).has("seq") && result.path("evidence").get(1).has("seq"), result.toString());
        JsonNode stored = firstResult(receipt(session, run.runId()));
        assertEquals(1, stored.path("evidence").get(1).path("seq").asInt(), "the run keeps the number of the file: " + stored);
        assertEquals(1, checkOf(stored, 1).path("seq").asInt(), stored.toString());
        assertEquals(rows, attachmentRows(), "both items point at files of the attachment that was there; the server made no other");
        // Each file has its own bytes through artifact_read (the number defaults to 0).
        assertArrayEquals(lines(7), readAll(session, bundle.get(0).attachmentId(), 0, lines(7).length));
        assertArrayEquals(lines(9), readAll(session, bundle.get(0).attachmentId(), 1, lines(9).length));
        JsonNode status = data(call("artifact_status", v1("attachmentId", bundle.get(0).attachmentId()), session));
        assertEquals(2, status.path("files").size(), status.toString());
        assertEquals(bundle.get(1).sha256(), status.path("files").get(1).path("sha256").asString(), status.toString());
    }

    /** c1, artifacts: the artifacts of the provider's output point at files of one attachment as well. */
    @Test
    void c1AnArtifactAtFileOneAndAnEvidenceItemAtFileZeroOfOneAttachmentAreBothMatched() throws Exception {
        String session = sennard.session();
        List<Uploaded> bundle = uploadBundle(session, lines(7), lines(9));
        Run run = startRun(session);
        Map<String, Object> artifact = artifact(bundle.get(1).attachmentId(), bundle.get(1).sha256());
        artifact.put("seq", 1);

        JsonNode recorded = data(call("record_stage_result", passedWithArtifacts(run, List.of(pointing(bundle.get(0))), List.of(artifact)), session));

        JsonNode result = firstResult(recorded);
        assertEquals(1, result.path("output").path("artifacts").get(0).path("seq").asInt(), result.toString());
        assertEquals("ARTIFACT", checkOf(result, 0).path("source").asString(), result.toString());
        assertEquals(1, checkOf(result, 0).path("seq").asInt(), result.toString());
        assertEquals("SHA256_MATCHED", checkOf(result, 0).path("check").asString(), result.toString());
        assertEquals("EVIDENCE", checkOf(result, 1).path("source").asString(), result.toString());
        assertEquals("SHA256_MATCHED", checkOf(result, 1).path("check").asString(), result.toString());
    }

    /** c2, passing stage: the sha256 of file 1 declared for file 0 is a digest the file contradicts: BINDING_INVALID ARTIFACT_DIGEST_MISMATCH with the number of the file, and nothing is recorded. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void c2TheSha256OfAnotherFileOfTheAttachmentOnAPassingStageIsRefusedWithTheFileNumber(int declaredFor) throws Exception {
        String session = sennard.session();
        List<Uploaded> bundle = uploadBundle(session, lines(7), lines(9));
        Run run = startRun(session);
        long rows = attachmentRows();
        Uploaded other = bundle.get(1 - declaredFor);

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(bundle.get(declaredFor).attachmentId(), declaredFor, other.sha256()))), session);

        assertRefused(refused, "BINDING_INVALID", "ARTIFACT_DIGEST_MISMATCH");
        assertEquals("workflow.service.recordedDigestMatchAttachment", refused.path("error").path("messageKey").asString(), refused.toString());
        assertEquals(bundle.get(0).attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        assertTrue(refused.path("error").path("details").has("seq"), "the refusal names the file: " + refused);
        assertEquals(declaredFor, refused.path("error").path("details").path("seq").asInt(-1), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** c2, a stage that does not pass: the same declaration is recorded, and the server's check of that file is NOT_CHECKED (the other file's SHA256_MATCHED is its own). */
    @Test
    void c2TheSha256OfAnotherFileOfTheAttachmentOnAStageThatDoesNotPassIsRecordedAsNotChecked() throws Exception {
        String session = sennard.session();
        List<Uploaded> bundle = uploadBundle(session, lines(7), lines(9));
        Run run = startRun(session);
        Map<String, Object> wrong = pointing(bundle.get(0).attachmentId(), 0, bundle.get(1).sha256());
        wrong.put("artifactId", "wrong");
        Map<String, Object> right = pointing(bundle.get(1).attachmentId(), 1, bundle.get(1).sha256());
        right.put("artifactId", "right");

        JsonNode recorded = data(call("record_stage_result", failed(run, List.of(wrong, right), List.of()), session));

        JsonNode result = firstResult(recorded);
        assertEquals("FAILED", result.path("state").asString(), result.toString());
        assertEquals("NOT_CHECKED", checkOf(result, 0).path("check").asString(), result.toString());
        assertEquals("wrong", checkOf(result, 0).path("artifactId").asString(), result.toString());
        assertEquals(0, checkOf(result, 0).path("seq").asInt(0), result.toString());
        assertEquals("SHA256_MATCHED", checkOf(result, 1).path("check").asString(), result.toString());
        assertEquals(1, checkOf(result, 1).path("seq").asInt(), result.toString());
    }

    /**
     * c3: a file number the attachment does not have is the same refusal as an attachment that is not there (BINDING_INVALID ATTACHMENT_NOT_FOUND), in each place a result points at a file,
     * with the number in the details; a negative number is the contract's INVALID_INPUT. Nothing is recorded.
     */
    @Test
    void c3AFileNumberTheAttachmentDoesNotHaveIsRefusedLikeAMissingAttachment() throws Exception {
        String session = sennard.session();
        List<Uploaded> bundle = uploadBundle(session, lines(7), lines(9));
        String attachmentId = bundle.get(0).attachmentId();
        Uploaded document = sennard.uploadJson(session, PASSED_OUTPUT);
        Run run = startRun(session);
        long rows = attachmentRows();

        JsonNode evidence = call("record_stage_result", passed(run, List.of(pointing(attachmentId, 5, bundle.get(1).sha256()))), session);
        assertAttachmentRefused(evidence, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "EVIDENCE", attachmentId);
        assertEquals(5, evidence.path("error").path("details").path("seq").asInt(-1), evidence.toString());

        Map<String, Object> artifact = artifact(attachmentId, bundle.get(1).sha256());
        artifact.put("seq", 2);
        JsonNode artifactRefused = call("record_stage_result", passedWithArtifacts(run, List.of(pointing(bundle.get(0))), List.of(artifact)), session);
        assertAttachmentRefused(artifactRefused, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "ARTIFACT", attachmentId);
        assertEquals(2, artifactRefused.path("error").path("details").path("seq").asInt(-1), artifactRefused.toString());

        JsonNode outputRefused = call("record_stage_result", passedByFile(run, List.of(pointing(bundle.get(0))), args("attachmentId", attachmentId, "seq", 7, "digest", document.sha256())), session);
        assertAttachmentRefused(outputRefused, "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "OUTPUT_FILE", attachmentId);
        assertEquals(7, outputRefused.path("error").path("details").path("seq").asInt(-1), outputRefused.toString());

        Map<String, Object> negative = pointing(attachmentId, 0, bundle.get(0).sha256());
        negative.put("seq", -1);
        assertRefused(call("record_stage_result", passed(run, List.of(negative)), session), "INVALID_INPUT", null);
        assertNothingRecorded(run, rows);
        // artifact_read says the same of a file the attachment does not have.
        assertArtifactRefused(call("artifact_read", v1("attachmentId", attachmentId, "seq", 5), session), "ARTIFACT_NOT_FOUND", false);
        assertEquals(lines(9).length, data(call("artifact_read", v1("attachmentId", attachmentId, "seq", 1, "maxBytes", 1), session)).path("size").asInt(), "the file that is there is read");
    }

    /** c3: the pointer of a bundle's file is judged by its attachment's author like any pointer: another session's attachment is refused whatever the file number. */
    @Test
    void c3AFileOfAnotherSessionsBundleIsRefusedWhateverItsNumber() throws Exception {
        String author = sennard.session();
        List<Uploaded> foreign = uploadBundle(author, lines(7), lines(9));
        String other = sennard.session();
        Run run = startRun(other);
        long rows = attachmentRows();

        JsonNode refused = call("record_stage_result", passed(run, List.of(pointing(foreign.get(1)))), other);

        assertAttachmentRefused(refused, "ATTACHMENT_AUTHOR_MISMATCH", "workflow.service.evidenceAttachmentOtherAuthor", "EVIDENCE", foreign.get(1).attachmentId());
        assertEquals(1, refused.path("error").path("details").path("seq").asInt(-1), refused.toString());
        assertNothingRecorded(run, rows);
    }

    /** c4: the output file is file 1 of a bundle, with that file's digest: it is read and judged like inline output; the receipt keeps the number; file 0 of the same attachment is not that file. */
    @Test
    void c4AnOutputFileThatIsFileOneOfABundleIsReadAndJudgedByThatFile() throws Exception {
        String session = sennard.session();
        byte[] text = lines(8);
        byte[] json = PASSED_OUTPUT.getBytes(StandardCharsets.UTF_8);
        List<Uploaded> bundle = sennard.uploadBundle(session, List.of(new ToolServerSupport.UploadFile(text, TEXT, "evidence.txt"), new ToolServerSupport.UploadFile(json, JSON, "output.json")));
        Run run = startRun(session);
        long rows = attachmentRows();
        Map<String, Object> file = args("attachmentId", bundle.get(1).attachmentId(), "seq", 1, "digest", bundle.get(1).sha256());

        JsonNode recorded = data(call("record_stage_result", passedByFile(run, List.of(pointing(bundle.get(0))), file), session));

        JsonNode result = firstResult(recorded);
        assertEquals("PASSED", result.path("state").asString(), result.toString());
        assertEquals(bundle.get(1).attachmentId(), result.path("outputFile").path("attachmentId").asString(), result.toString());
        assertEquals(1, result.path("outputFile").path("seq").asInt(), result.toString());
        assertEquals(bundle.get(1).sha256(), result.path("outputFile").path("digest").asString(), result.toString());
        assertEquals(rows, attachmentRows(), "the output is a file of the attachment that was there");

        // The same attachment's file 0 is another file: with file 1's digest it is not that file's bytes (INTEGRITY_FAILED), with its own digest it is not JSON (INVALID_INPUT).
        Run second = startRun(session);
        long before = attachmentRows();
        JsonNode wrongDigest = call("record_stage_result", passedByFile(second, List.of(pointing(bundle.get(0))), args("attachmentId", bundle.get(1).attachmentId(), "seq", 0, "digest", bundle.get(1).sha256())),
                session);
        assertRefused(wrongDigest, "INTEGRITY_FAILED", null);
        assertEquals("workflow.outputFile.outputFileContentMatchDigest", wrongDigest.path("error").path("messageKey").asString(), wrongDigest.toString());
        JsonNode notJson = call("record_stage_result", passedByFile(second, List.of(pointing(bundle.get(0))), args("attachmentId", bundle.get(0).attachmentId(), "seq", 0, "digest", bundle.get(0).sha256())),
                session);
        assertRefused(notJson, "INVALID_INPUT", null);
        assertEquals("workflow.outputFile.outputFileValidJSON", notJson.path("error").path("messageKey").asString(), notJson.toString());
        assertNothingRecorded(second, before);
    }

    /**
     * c6: a finish with a bad second file makes no attachment: the uploads go on, the status of the attachment is not found and no result can point at it; the files that are in order are not
     * a half attachment. A second file that is only short is completed and the same finish then makes the attachment with both files; one whose bytes are not the declared ones is refused
     * again by every finish.
     */
    @ParameterizedTest
    @ValueSource(strings = {"short", "other-bytes"})
    void c6AFinishWithABadSecondFileMakesNoAttachmentAndALaterFinishOfAFixedOneDoes(String bad) throws Exception {
        String session = sennard.session();
        byte[] first = lines(4);
        byte[] second = lines(6);
        JsonNode begun = data(beginAll(session, freshKey(), TEXT, first, second));
        String attachmentId = begun.path("attachmentId").asString();
        ToolServerSupport.sendMultipart(sennard.server.port, uploadIdOf(begun, 0), first);
        byte[] sent = bad.equals("short") ? Arrays.copyOf(second, 40) : "x".repeat(second.length).getBytes(StandardCharsets.UTF_8);
        stageChunks(uploadIdOf(begun, 1), sent, 70);
        long rows = attachmentRows();

        JsonNode refused = finish(session, attachmentId);

        assertArtifactRefused(refused, bad.equals("short") ? "ARTIFACT_INCOMPLETE" : "ARTIFACT_HASH_MISMATCH", false);
        assertEquals(rows, attachmentRows(), "the refused finish made no attachment");
        assertArtifactRefused(call("artifact_status", v1("attachmentId", attachmentId), session), "ARTIFACT_NOT_FOUND", false);
        assertEquals("STAGING", data(call("artifact_status", v1("uploadId", uploadIdOf(begun, 0)), session)).path("state").asString(), "the good first file is still an upload: nothing was completed");
        assertEquals("STAGING", data(call("artifact_status", v1("uploadId", uploadIdOf(begun, 1)), session)).path("state").asString());
        Run run = startRun(session);
        long before = attachmentRows();
        assertAttachmentRefused(call("record_stage_result", passed(run, List.of(pointing(attachmentId, 0, sha(first)))), session), "ATTACHMENT_NOT_FOUND", "workflow.service.evidenceAttachmentNotFound", "EVIDENCE",
                attachmentId);
        assertNothingRecorded(run, before);
        assertArtifactRefused(finish(session, attachmentId), bad.equals("short") ? "ARTIFACT_INCOMPLETE" : "ARTIFACT_HASH_MISMATCH", false);

        if (bad.equals("short")) {
            // The missing chunk of file 1 is sent (its next number is 1: the 40 bytes were one chunk), and the finish that was refused is sent again.
            chunk(uploadIdOf(begun, 1), 1, Arrays.copyOfRange(second, 40, second.length));
            JsonNode finished = data(finish(session, attachmentId));
            assertEquals(2, finished.path("files").size(), finished.toString());
            assertEquals(sha(first), finished.path("files").get(0).path("sha256").asString(), finished.toString());
            assertEquals(sha(second), finished.path("files").get(1).path("sha256").asString(), finished.toString());
            assertEquals(rows + 1, attachmentRows(), "one attachment, made by the one finish that passed");
            assertEquals("COMPLETED", data(call("artifact_status", v1("attachmentId", attachmentId), session)).path("state").asString());
            assertArrayEquals(first, readAll(session, attachmentId, 0, first.length));
            assertArrayEquals(second, readAll(session, attachmentId, 1, second.length));
            assertEquals(finished, data(finish(session, attachmentId)), "finishing again answers the same attachment");
            Run own = startRun(session);
            JsonNode recorded = data(call("record_stage_result", passed(own, List.of(pointing(attachmentId, 1, sha(second)))), session));
            assertEquals("SHA256_MATCHED", checkOf(firstResult(recorded), 0).path("check").asString(), recorded.toString());
        } else {
            assertEquals(rows, attachmentRows(), "a file whose bytes are not the declared ones never becomes an attachment");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- tools

    /** Design test 1: begin, several chunks, finish, status, read: the plain hash is the same, and the answers carry what the design says. */
    @Test
    void t1BeginChunksFinishStatusReadGiveTheSamePlainHash() throws Exception {
        String session = sennard.session();
        byte[] bytes = lines(64);
        int part = 300;
        JsonNode begun = data(begin(session, freshKey(), "report.txt", TEXT, bytes));
        String uploadId = uploadIdOf(begun);
        String attachmentId = begun.path("attachmentId").asString();
        assertFalse(uploadId.isBlank() || attachmentId.isBlank(), begun.toString());
        assertEquals(1, begun.path("files").size(), begun.toString());
        assertEquals(0, begun.path("files").get(0).path("seq").asInt(), begun.toString());
        assertNotEquals(uploadId, attachmentId, "an upload is a file of an attachment: the two ids are not the same");
        assertEquals(262144, begun.path("maxChunkBytes").asInt(), begun.toString());
        assertTrue(begun.path("expiresAt").isString(), begun.toString());

        for (int sequence = 0, at = 0; at < bytes.length; sequence++, at += part) {
            byte[] piece = Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + part));
            var taken = chunk(uploadId, sequence, piece);
            assertEquals(sequence, taken.sequence());
            assertEquals(Math.min(bytes.length, at + part), taken.received());
            if (sequence == 0) {
                JsonNode midway = data(call("artifact_status", v1("uploadId", uploadId), session));
                assertEquals("upload", midway.path("kind").asString(), midway.toString());
                assertEquals("STAGING", midway.path("state").asString(), midway.toString());
                assertEquals(uploadId, midway.path("uploadId").asString(), midway.toString());
                assertEquals(attachmentId, midway.path("attachmentId").asString(), "an upload says which attachment it is a file of: " + midway);
                assertEquals(0, midway.path("seq").asInt(), midway.toString());
                assertEquals(part, midway.path("received").asInt(), midway.toString());
                assertEquals(1, midway.path("sequence").asInt(), midway.toString());
                assertEquals(sha(bytes), midway.path("sha256").asString(), midway.toString());
                assertFalse(midway.has("path") || midway.has("storageKey"), "status answers no path and no storage key: " + midway);
            }
        }

        // The attachment is made by the finish and not before: the status of the attachment is not found while its files are being sent.
        assertArtifactRefused(call("artifact_status", v1("attachmentId", attachmentId), session), "ARTIFACT_NOT_FOUND", false);
        JsonNode finished = data(finish(session, attachmentId));
        assertEquals(attachmentId, finished.path("attachmentId").asString(), finished.toString());
        assertEquals(1, finished.path("files").size(), finished.toString());
        JsonNode keptFile = finished.path("files").get(0);
        assertEquals(0, keptFile.path("seq").asInt(), finished.toString());
        assertEquals("report.txt", keptFile.path("fileName").asString(), finished.toString());
        assertEquals(TEXT, keptFile.path("mediaType").asString(), finished.toString());
        assertEquals(bytes.length, keptFile.path("size").asLong(), finished.toString());
        assertEquals(sha(bytes), keptFile.path("sha256").asString(), finished.toString());
        assertEquals(finished, data(finish(session, attachmentId)), "finishing again answers the same attachment");

        JsonNode status = data(call("artifact_status", v1("attachmentId", attachmentId), session));
        assertEquals("attachment", status.path("kind").asString(), status.toString());
        assertEquals("COMPLETED", status.path("state").asString(), status.toString());
        assertEquals(attachmentId, status.path("attachmentId").asString(), status.toString());
        assertEquals(1, status.path("files").size(), status.toString());
        assertEquals(bytes.length, status.path("files").get(0).path("size").asLong(), status.toString());
        assertEquals(sha(bytes), status.path("files").get(0).path("sha256").asString(), status.toString());

        byte[] read = readAll(session, attachmentId, bytes.length);
        assertArrayEquals(bytes, read);
        assertEquals(sha(bytes), sha(read), "the plain hash of what was read is the declared one");
        JsonNode firstPage = data(call("artifact_read", v1("attachmentId", attachmentId, "maxBytes", PAGE), session));
        assertEquals(new String(Arrays.copyOf(bytes, PAGE), StandardCharsets.UTF_8), firstPage.path("text").asString(), "a text file's whole UTF-8 page is also given as text");
        assertFalse(firstPage.path("end").asBoolean(true), firstPage.toString());
    }

    /** Design test 2: the order of the chunks, the last chunk sent again, and a wrong chunk hash. */
    @Test
    void t2AChunkOutOfOrderOrOfOtherContentIsRefusedTheLastOneSentAgainIsAnsweredAsBeforeAndAWrongHashIsRefused() throws Exception {
        String session = sennard.session();
        byte[] bytes = lines(64);
        String uploadId = uploadIdOf(data(begin(session, freshKey(), "report.txt", TEXT, bytes)));
        byte[] first = Arrays.copyOfRange(bytes, 0, 300);

        assertChunkRefused("ARTIFACT_CHUNK_CONFLICT", () -> chunk(uploadId, 3, first));

        assertChunkRefused("CHUNK_HASH_MISMATCH", () -> uploads().chunk(uploadId, 0, first, sha(lines(1))));
        assertEquals(0, data(call("artifact_status", v1("uploadId", uploadId), session)).path("received").asInt(), "a chunk whose hash is wrong stores nothing");

        var taken = chunk(uploadId, 0, first);
        var again = chunk(uploadId, 0, first);
        assertEquals(taken, again, "the last chunk sent again with the same content is answered as before");
        assertEquals(300, again.received());

        assertChunkRefused("ARTIFACT_CHUNK_CONFLICT", () -> chunk(uploadId, 0, Arrays.copyOfRange(bytes, 300, 600)));
        assertChunkRefused("ARTIFACT_CHUNK_CONFLICT", () -> chunk(uploadId, 2, Arrays.copyOfRange(bytes, 300, 600)));
        assertEquals(300, data(call("artifact_status", v1("uploadId", uploadId), session)).path("received").asInt(), "the refused chunks stored nothing");
    }

    private static void assertChunkRefused(String code, Runnable chunk) {
        var refused = assertThrows(SennardException.class, chunk::run);
        assertEquals(code, refused.code().wireCode());
        assertEquals(false, refused.details().get("retryable"));
    }

    /** The purposes this server does not keep are refused as ARTIFACT_UNSUPPORTED_PURPOSE, and waiting does not help. */
    @ParameterizedTest
    @ValueSource(strings = {"upload", "staged"})
    void aPurposeThatTheServerDoesNotKeepIsRefused(String purpose) throws Exception {
        String session = sennard.session();
        byte[] bytes = lines(2);

        JsonNode refused = call("artifact_begin", v1("operationKey", freshKey(), "files", declared(TEXT, bytes), "purpose", purpose), session);

        assertArtifactRefused(refused, "ARTIFACT_UNSUPPORTED_PURPOSE", false);
    }

    /** An operationKey that is not 16 to 128 characters of letters, digits and {@code _ . : -} is the contract's INVALID_INPUT. */
    @Test
    void anOperationKeyOutsideThePatternIsInvalidInput() throws Exception {
        String session = sennard.session();
        byte[] bytes = lines(2);
        List<String> keys = List.of("too-short", "a".repeat(15), "has a space in it, 16+ long", "a".repeat(129), "slash/is/not/allowed/in/it");
        for (String key : keys) {
            JsonNode refused = call("artifact_begin", v1("operationKey", key, "files", declared(TEXT, bytes)), session);
            assertRefused(refused, "INVALID_INPUT", null);
        }
        data(call("artifact_begin", v1("operationKey", "a".repeat(16), "files", declared(TEXT, bytes)), session));
        data(call("artifact_begin", v1("operationKey", "A_b.c:d-".repeat(16), "files", declared(TEXT, bytes)), session));
    }

    /** The same operationKey with the same content is the same upload; with other content it is ARTIFACT_OPERATION_CONFLICT. */
    @Test
    void theSameOperationKeyWithTheSameContentIsTheSameUploadAndWithOtherContentIsAConflict() throws Exception {
        String session = sennard.session();
        String key = freshKey();
        byte[] bytes = lines(4);

        String uploadId = uploadIdOf(data(begin(session, key, "a.txt", TEXT, bytes)));

        assertEquals(uploadId, uploadIdOf(data(begin(session, key, "a.txt", TEXT, bytes))), "the same begin again answers the same upload");
        assertArtifactRefused(begin(session, key, "a.txt", TEXT, lines(5)), "ARTIFACT_OPERATION_CONFLICT", false);
        assertArtifactRefused(begin(session, key, "other-name.txt", TEXT, bytes), "ARTIFACT_OPERATION_CONFLICT", false);
        assertEquals(uploadId, uploadIdOf(data(begin(session, key, "a.txt", TEXT, bytes))), "a refused begin changed nothing");
    }

    /** The same holds for the begin of a bundle: the same files in the same order answer the same attachment and uploads; another file, another order or another count is a conflict. */
    @Test
    void theSameOperationKeyWithTheSameFilesIsTheSameAttachmentAndWithOtherFilesIsAConflict() throws Exception {
        String session = sennard.session();
        String key = freshKey();
        byte[] first = lines(4);
        byte[] second = lines(6);

        JsonNode begun = data(beginAll(session, key, TEXT, first, second));

        assertEquals(2, begun.path("files").size(), begun.toString());
        assertNotEquals(uploadIdOf(begun, 0), uploadIdOf(begun, 1), "each file has an upload of its own");
        assertEquals(begun, data(beginAll(session, key, TEXT, first, second)), "the same begin again answers the same attachment and uploads");
        assertArtifactRefused(beginAll(session, key, TEXT, first, lines(7)), "ARTIFACT_OPERATION_CONFLICT", false);
        assertArtifactRefused(beginAll(session, key, TEXT, second, first), "ARTIFACT_OPERATION_CONFLICT", false);
        assertArtifactRefused(beginAll(session, key, TEXT, first), "ARTIFACT_OPERATION_CONFLICT", false);
        assertArtifactRefused(beginAll(session, key, TEXT, first, second, lines(8)), "ARTIFACT_OPERATION_CONFLICT", false);
        assertEquals(begun, data(beginAll(session, key, TEXT, first, second)), "the refused begins changed nothing");
    }

    /** A bundle has at least one file and at most 64: an empty list is the contract's INVALID_INPUT, 65 files is ARTIFACT_LIMIT file-count and makes nothing. */
    @Test
    void aBundleOfNoFilesIsInvalidInputAndOneOfMoreThanSixtyFourFilesIsOverTheLimit() throws Exception {
        String session = sennard.session();
        assertRefused(call("artifact_begin", v1("operationKey", freshKey(), "files", List.of(), "purpose", "evidence"), session), "INVALID_INPUT", null);
        byte[][] tooMany = new byte[65][];
        Arrays.fill(tooMany, lines(1));
        long uploadRows = SessionServerSupport.count(sennard.server, "SELECT COUNT(*) FROM sennard.upload_staging");

        JsonNode refused = beginAll(session, freshKey(), TEXT, tooMany);

        // The contract's own maxItems may refuse it before the use case does: either way nothing is made.
        assertFalse(refused.path("ok").asBoolean(true), refused.toString());
        assertEquals(uploadRows, SessionServerSupport.count(sennard.server, "SELECT COUNT(*) FROM sennard.upload_staging"), "the refused begin made no upload");
    }

    /** Design test 10: a file over the size limit is refused by begin whole, and an author's open uploads are limited. */
    @Test
    void t10BeginRefusesAFileOverTheSizeLimitAndTheFifthOpenUploadOfAnAuthor() throws Exception {
        String session = sennard.session();
        byte[] over = "z".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8);

        JsonNode tooBig = begin(session, freshKey(), "big.bin", "application/octet-stream", over);

        assertArtifactRefused(tooBig, "ARTIFACT_LIMIT", false);
        assertEquals("file-size", tooBig.path("error").path("details").path("limit").asString(), tooBig.toString());
        data(begin(session, freshKey(), "exact.bin", "application/octet-stream", "z".repeat(LIMIT).getBytes(StandardCharsets.UTF_8)));

        // The upload of exactly the limit is the first open one; the settings' default is four open uploads per author.
        for (int count = 1; count < 4; count++) {
            data(begin(session, freshKey(), "open-" + count + ".txt", TEXT, lines(count)));
        }
        JsonNode fifth = begin(session, freshKey(), "open-5.txt", TEXT, lines(5));
        assertArtifactRefused(fifth, "ARTIFACT_LIMIT", true);
        assertEquals("open-uploads", fifth.path("error").path("details").path("limit").asString(), fifth.toString());
    }

    /** A call that carries no session at all: no host metadata and no sessionRef, as the upload client outside the model sends its calls. */
    private JsonNode callWithoutSession(String tool, Map<String, Object> arguments) {
        return sennard.callUnobserved(tool, arguments);
    }

    /**
     * Upload and finish use the IDs from begin. A second session can finish by ID, and evidence belongs to the session that began the attachment.
     */
    @Test
    void anotherSessionCanFinishByIdAndTheBeginningSessionOwnsTheEvidence() throws Exception {
        String author = sennard.session();
        String other = sennard.session();
        byte[] bytes = lines(4);
        JsonNode begun = data(begin(author, freshKey(), "a.txt", TEXT, bytes));
        String uploadId = uploadIdOf(begun);

        JsonNode taken = ToolServerSupport.sendMultipart(sennard.server.port, uploadId, bytes);
        assertEquals(bytes.length, taken.path("received").asInt(), taken.toString());
        assertEquals(bytes.length, data(call("artifact_status", v1("uploadId", uploadId), author)).path("received").asInt());
        JsonNode finished = data(finish(other, begun.path("attachmentId").asString()));
        assertEquals(begun.path("attachmentId").asString(), finished.path("attachmentId").asString(), finished.toString());
        assertEquals(sha(bytes), finished.path("files").get(0).path("sha256").asString(), finished.toString());
        Uploaded uploaded = new Uploaded(finished.path("attachmentId").asString(), finished.path("files").get(0).path("sha256").asString());
        assertArrayEquals(bytes, readAll(author, uploaded.attachmentId(), bytes.length));

        Run otherRun = startRun(other);
        long rows = attachmentRows();
        JsonNode refused = call("record_stage_result", passed(otherRun, List.of(pointing(uploaded))), other);
        assertAttachmentRefused(refused, "ATTACHMENT_AUTHOR_MISMATCH", "workflow.service.evidenceAttachmentOtherAuthor", "EVIDENCE", uploaded.attachmentId());
        assertNothingRecorded(otherRun, rows);

        Run authorRun = startRun(author);
        JsonNode recorded = data(call("record_stage_result", passed(authorRun, List.of(pointing(uploaded))), author));
        assertEquals("SHA256_MATCHED", firstResult(recorded).path("artifactChecks").get(0).path("check").asString(), recorded.toString());
    }

    /** Multipart, finish and upload status use the IDs issued at begin. */
    @Test
    void multipartFinishAndUploadStatusNeedNoSession() throws Exception {
        String author = sennard.session();
        byte[] bytes = lines(4);
        JsonNode begun = data(begin(author, freshKey(), "a.txt", TEXT, bytes));
        String uploadId = uploadIdOf(begun);
        String attachmentId = begun.path("attachmentId").asString();

        JsonNode midway = data(callWithoutSession("artifact_status", v1("uploadId", uploadId)));
        assertEquals("upload", midway.path("kind").asString(), midway.toString());
        assertEquals(0, midway.path("received").asInt(), midway.toString());
        JsonNode taken = ToolServerSupport.sendMultipart(sennard.server.port, uploadId, bytes);
        assertEquals(bytes.length, taken.path("received").asInt(), taken.toString());
        assertEquals(bytes.length, data(callWithoutSession("artifact_status", v1("uploadId", uploadId))).path("received").asInt());
        JsonNode finished = data(callWithoutSession("artifact_finish", v1("attachmentId", attachmentId)));
        assertEquals(attachmentId, finished.path("attachmentId").asString(), finished.toString());
        assertEquals(sha(bytes), finished.path("files").get(0).path("sha256").asString(), finished.toString());
        assertEquals(attachmentId, data(callWithoutSession("artifact_finish", v1("attachmentId", attachmentId))).path("attachmentId").asString(), "a finish sent again, still without a session, answers the same attachment");

        Uploaded uploaded = new Uploaded(finished.path("attachmentId").asString(), finished.path("files").get(0).path("sha256").asString());
        assertArrayEquals(bytes, readAll(author, uploaded.attachmentId(), bytes.length));
        Run run = startRun(author);
        JsonNode recorded = data(call("record_stage_result", passed(run, List.of(pointing(uploaded))), author));
        assertEquals("SHA256_MATCHED", firstResult(recorded).path("artifactChecks").get(0).path("check").asString(), "the attachment is the begin's session's: " + recorded);
    }

    /** What needs a session still needs one: the status of an attachment, a begin and a read are refused without it (BINDING_REQUIRED), and nothing is made by the refused begin. */
    @Test
    void theStatusOfAnAttachmentABeginAndAReadStillNeedASession() throws Exception {
        String author = sennard.session();
        Uploaded uploaded = upload(author, lines(4), TEXT);
        long rows = attachmentRows();
        long uploadRows = SessionServerSupport.count(sennard.server, "SELECT COUNT(*) FROM sennard.upload_staging");
        byte[] bytes = lines(2);

        assertRefused(callWithoutSession("artifact_status", v1("attachmentId", uploaded.attachmentId())), "BINDING_REQUIRED", null);
        assertRefused(callWithoutSession("artifact_begin", v1("operationKey", freshKey(), "files", declared(TEXT, bytes), "purpose", "evidence")),
                "BINDING_REQUIRED", null);
        assertRefused(callWithoutSession("artifact_read", v1("attachmentId", uploaded.attachmentId())), "BINDING_REQUIRED", null);
        assertEquals(uploadRows, SessionServerSupport.count(sennard.server, "SELECT COUNT(*) FROM sennard.upload_staging"), "the refused begin made no upload");
        assertEquals(rows, attachmentRows());
        assertEquals("COMPLETED", data(call("artifact_status", v1("attachmentId", uploaded.attachmentId()), author)).path("state").asString(), "with a session the same status answers");
    }

    /** An unknown ID returns ARTIFACT_NOT_FOUND for multipart, finish and upload status. */
    @Test
    void anUnknownUploadIdIsNotFoundWithOrWithoutASession() throws Exception {
        String session = sennard.session();
        String unknown = UUID.randomUUID().toString();
        byte[] bytes = lines(2);
        var missing = ToolServerSupport.postMultipart(sennard.server.port, unknown, bytes);
        assertEquals(404, missing.status(), missing.text());
        assertEquals("ARTIFACT_NOT_FOUND", ToolServerSupport.MAPPER.readTree(missing.body()).path("code").asString());
        assertArtifactRefused(call("artifact_finish", v1("attachmentId", unknown), session), "ARTIFACT_NOT_FOUND", false);
        assertArtifactRefused(callWithoutSession("artifact_finish", v1("attachmentId", unknown)), "ARTIFACT_NOT_FOUND", false);
        assertArtifactRefused(callWithoutSession("artifact_status", v1("uploadId", unknown)), "ARTIFACT_NOT_FOUND", false);
        assertArtifactRefused(call("artifact_status", v1("uploadId", unknown), session), "ARTIFACT_NOT_FOUND", false);
    }

    /** The status answers exactly one of the two ids. */
    @Test
    void statusOfBothIdsOrOfNoneIsInvalidInput() throws Exception {
        String session = sennard.session();
        String id = UUID.randomUUID().toString();

        assertRefused(call("artifact_status", v1("uploadId", id, "attachmentId", id), session), "INVALID_INPUT", null);
        assertRefused(call("artifact_status", v1(), session), "INVALID_INPUT", null);
        assertArtifactRefused(call("artifact_status", v1("attachmentId", id), session), "ARTIFACT_NOT_FOUND", false);
        assertArtifactRefused(call("artifact_read", v1("attachmentId", id), session), "ARTIFACT_NOT_FOUND", false);
    }

    /** The attachment tools are published with their localized descriptions. */
    @Test
    void theAttachmentToolsArePublishedWithTheirCatalogText() {
        Map<String, String> descriptions = new TreeMap<>();
        sennard.listTools().stream().filter(tool -> tool.name().startsWith("artifact_")).forEach(tool -> descriptions.put(tool.name(), tool.description()));

        assertEquals(List.of("artifact_begin", "artifact_finish", "artifact_read", "artifact_status"), List.copyOf(descriptions.keySet()));
        descriptions.forEach((name, text) -> assertFalse(text == null || text.isBlank() || text.startsWith("tools."), name + ": " + text));
    }
}
