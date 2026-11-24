// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.attachmentcli.PreparedFile;
import io.github.jaeseongs95.sennard.attachmentcli.UploadClient;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.cli.SennardCli;
import io.github.jaeseongs95.sennard.personal.install.ReleaseJarFixture;
import tools.jackson.databind.JsonNode;

/**
 * The upload client ({@link UploadClient}, {@code sennard attachments send}) against the real server with real PostgreSQL (AGS3-D-0264): the model begins an upload with what
 * {@link PreparedFile} measured ({@code artifact_begin} as its own session), the client sends the file by multipart HTTP using its upload ID.
 * The model's one {@code artifact_finish}, bound by the attachment's ID, makes the
 * attachment after every file was sent. The session that began the upload owns the attachment. The server is started with the
 * default limits, so a file of several chunks is a file of the size the product has. Each test takes its own sessions (the author of an upload is the session that began it, and an
 * author may have only a few uploads open). Nothing here waits for a time: no test reads a clock; the one bound of the process cases only stops a wedged child process.
 *
 * <pre>
 * u1  several chunks  a file of three chunks: begin as s1, send, Sent(uploadId, attachmentId, seq 0, received) and the upload holds every byte but there is NO attachment yet (the client does not finish);
 *                     the model's one artifact_finish makes it: status COMPLETED with files[], artifact_read as s1 gives the bytes; s1 records a stage result whose evidence is the attachment and the run finalizes PASSED
 * u2  empty           a file of no bytes is begun, sent (one empty chunk) and kept; its sha256 is the sha256 of no bytes
 * u3  changed         a file changed after prepare (the same size with other bytes, and another size) is InvalidInputException VALIDATION_FAILED, nothing was sent (received 0, sequence 0);
 *                     the file given back as it was is then sent to the same upload
 * u4  other upload    the file of another declaration (the same size, other bytes) sent to an upload is InvalidInputException VALIDATION_FAILED, nothing was sent
 * u5  unknown id      an upload id the server does not know is UploadClient.Refused with the code ARTIFACT_NOT_FOUND
 * u6  finished        a send of an upload whose bytes are all there answers the same Sent, before and after the finish, and the attachment is as it was
 * u7  no server       a port nothing listens on (a port that was just given back) is UnavailableException SERVER_UNREACHABLE, naming the port
 * u8  continued       a send continues an upload the model began and sent the first chunk (or the first two) of itself: it sends from the sequence the server holds (with two taken in,
 *                     chunk 0 sent again would be a conflict) and the attachment has the right bytes
 * u9  author          the attachment a send finished is s1's: s2 recording a stage result with it is BINDING_INVALID ATTACHMENT_AUTHOR_MISMATCH and records nothing
 * u10 command         the real {@code sennard attachments send} in a child JVM (the public entry of the CLI), which finds the server by the address file in the state folder of an installed
 *                     version (a copy of the release's extracted folder): stdout is exactly one JSON line {uploadId, attachmentId, seq, received}, exit 0, and the attachment is not made (the upload holds every byte); the refusal of a tool is its code on stderr,
 *                     exit 1, nothing on stdout; no address file is SERVER_UNREACHABLE on stderr naming the file, exit 1
 * u11 bundle          two {@code sennard attachments send} (the two uploads of one artifact_begin) print seq 0 and seq 1 of one attachment; the one artifact_finish then makes it with both files, each with its
 *                     own sha256; artifact_read takes the file number
 * </pre>
 *
 * Not tested here, because it cannot be made honestly: a file that changes while it is sent (the second measure and the short read are judged by reading the code), and the
 * {@code Stopped} outcomes of a connection that breaks in the middle of a call (the server cannot be made to cut one call of a real client without a stand-in for the server).
 */
@Tag("business-db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadClientServerTest {
    private static final String ONE = "1.0.0";
    private static final int CHUNK = AttachmentFileStore.MAX_CHUNK_BYTES;
    /** Three chunks: two whole ones and a tail. */
    private static final int THREE_CHUNKS = 2 * CHUNK + 1000;
    private static final String EMPTY_SHA256 = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** An extension no platform knows: the media type of such a file is the unknown type everywhere. */
    private static final String FILE_NAME = "evidence.sennard-test-unknown";

    private ToolServerSupport sennard;
    private Path workspaces;
    /** The {@code user.home} of the command's child JVM, and the installation and state folder inside it: the command reaches the server through them and nothing of this computer's. */
    private Path home;
    private Path installRoot;
    private Path addressFile;

    @BeforeAll
    void start() throws Exception {
        Path repository = repository();
        // The edit gate does not count the OS temporary directory, so the workspaces of the runs live in this checkout's build directory (as AttachmentEvidenceServerTest's).
        workspaces = Path.of("build", "upload-client-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        sennard = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        // The command finds the server the way a user's does: through the installation's state folder, whose address file names the server's port. The installed version is a copy of
        // the release's extracted folder (no install, which trains AOT caches), its settings put the state folder in the test's folder, and the server of this test announces itself in it.
        home = workspaces.resolve("home");
        installRoot = home.resolve("install");
        Path state = home.resolve("state");
        Files.createDirectories(state);
        ReleaseJarFixture.writeSettings(ReleaseJarFixture.installedCopy(installRoot, ReleaseJarFixture.version()), state, false);
        addressFile = state.resolve(ServerAddress.FILE_NAME);
        announceServer();
    }

    /** What the server of this test would write itself: its port, with the process that is alive and the time it started (this test's own process stands for the server's). */
    private void announceServer() throws IOException {
        ServerAddress.ofThisProcess(sennard.server.port).writeAtomically(addressFile);
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

    private static void assertRefused(JsonNode answer, String code, String reason) {
        assertFalse(answer.path("ok").asBoolean(true), answer.toString());
        assertEquals(code, answer.path("error").path("code").asString(), answer.toString());
        if (reason != null) {
            assertEquals(reason, answer.path("error").path("details").path("reason").asString(), answer.toString());
        }
    }

    private static String sha(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    /** Bytes of their own: the same seed gives the same bytes, another seed other ones. */
    private static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static Path file(Path folder, byte[] bytes) throws IOException {
        return Files.write(folder.resolve(FILE_NAME), bytes);
    }

    private UploadClient client() {
        return new UploadClient(sennard.server.port);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- the model's side

    /** What {@code artifact_begin} answered: the attachment the finish makes and the upload of each file, in the order of the files. */
    private record Begun(String attachmentId, List<String> uploadIds) {
        String uploadId() {
            return uploadIds.getFirst();
        }
    }

    /** What the model does with what {@code sennard attachments prepare} printed for each file: one {@code artifact_begin} as its own session with all the files. */
    private Begun beginAll(String session, PreparedFile... prepared) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (PreparedFile one : prepared) {
            files.add(args("fileName", one.fileName(), "mediaType", one.mediaType(), "size", one.size(), "sha256", one.sha256()));
        }
        JsonNode begun = data(call("artifact_begin", v1("operationKey", "begin-" + UUID.randomUUID(), "files", files, "purpose", "evidence"), session));
        String attachmentId = begun.path("attachmentId").asString();
        assertFalse(attachmentId.isBlank(), begun.toString());
        assertEquals(prepared.length, begun.path("files").size(), begun.toString());
        List<String> uploadIds = new ArrayList<>();
        for (int seq = 0; seq < prepared.length; seq++) {
            assertEquals(seq, begun.path("files").get(seq).path("seq").asInt(), begun.toString());
            uploadIds.add(begun.path("files").get(seq).path("uploadId").asString());
        }
        return new Begun(attachmentId, List.copyOf(uploadIds));
    }

    /** The begin of one file. */
    private Begun begin(String session, PreparedFile prepared) {
        return beginAll(session, prepared);
    }

    /** The one finish the model calls after every file was sent: the attachment is made only then. */
    private JsonNode finish(String session, String attachmentId) {
        return data(call("artifact_finish", v1("attachmentId", attachmentId), session));
    }

    /** The status of the attachment: {@code ARTIFACT_NOT_FOUND} until the finish made it. */
    private void assertNoAttachmentYet(String session, String attachmentId) {
        JsonNode answer = call("artifact_status", v1("attachmentId", attachmentId), session);
        assertRefused(answer, "ARTIFACT_NOT_FOUND", null);
    }

    private JsonNode uploadStatus(String session, String uploadId) {
        return data(call("artifact_status", v1("uploadId", uploadId), session));
    }

    private void assertNothingWasSent(String session, String uploadId) {
        JsonNode status = uploadStatus(session, uploadId);
        assertEquals("STAGING", status.path("state").asString(), status.toString());
        assertEquals(0, status.path("received").asLong(), "no byte was sent: " + status);
        assertEquals(0, status.path("sequence").asLong(), "no chunk was sent: " + status);
    }

    /** Every page of an attachment, one after the other, as a reader outside the model reads it. */
    private byte[] readAll(String session, String attachmentId, long size) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        long pages = size / CHUNK + 2;
        for (long page = 0; page < pages; page++) {
            JsonNode answer = data(call("artifact_read", v1("attachmentId", attachmentId, "offsetBytes", whole.size(), "maxBytes", CHUNK), session));
            byte[] part = Base64.getDecoder().decode(answer.path("data").asString());
            assertEquals(whole.size(), answer.path("offsetBytes").asLong(), answer.toString());
            assertEquals(size, answer.path("size").asLong(), answer.toString());
            whole.write(part, 0, part.length);
            if (answer.path("end").asBoolean(false)) {
                return whole.toByteArray();
            }
            assertTrue(part.length > 0, "a page that is not the last one has bytes: " + answer);
        }
        throw new AssertionError("The attachment " + attachmentId + " of " + size + " bytes did not end in " + pages + " pages");
    }

    /** Prepare, begin as the session, send, and the model's one finish: the file the client sent, in the attachment the finish made. */
    private UploadClient.Sent prepareBeginSendAndFinish(String session, Path file, PreparedFile prepared) {
        Begun begun = begin(session, prepared);
        UploadClient.Sent sent = assertInstanceOf(UploadClient.Sent.class, client().send(file, begun.uploadId()));
        finish(session, begun.attachmentId());
        return sent;
    }

    // ---------------------------------------------------------------------------------------------------------------------------- runs

    /** A started run of one stage: the session that started it, its root, and the stage it waits at. */
    private record Run(String session, String runId, String stageId, long revision) {
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
        return new Run(session, runId, stageOf(current), current.path("revision").asLong());
    }

    /** The passing result of the run's stage whose only evidence is this attachment. */
    private static Map<String, Object> passedWithEvidence(Run run, String attachmentId, String digest) {
        return passedWithEvidence(run, attachmentId, 0, digest);
    }

    /** The passing result whose only evidence is file {@code seq} of this attachment. */
    private static Map<String, Object> passedWithEvidence(Run run, String attachmentId, int seq, String digest) {
        Map<String, Object> item = args("artifactId", "uploaded", "kind", "file", "attachmentId", attachmentId, "seq", seq, "digest", digest, "verified", true, "note", "Sent by the upload client.");
        return v1("runId", run.runId(), "expectedRevision", run.revision(), "stageId", run.stageId(), "state", "PASSED", "output",
                v1("kind", "output", "output", args("status", "passed"), "artifacts", List.of(), "error", null), "evidence", List.of(item), "findings", List.of(), "blockers", List.of(), "error", null);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u1 several chunks

    /** A file of three chunks is sent whole, is the session's attachment, and a stage result of that session names it and finishes the run. */
    @Test
    void u1AFileOfSeveralChunksIsSentAndKeptAndTheSessionsRunPassesOnIt(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] bytes = bytes(THREE_CHUNKS, 1L);
        assertTrue(bytes.length > 2 * CHUNK, "the file has more than two chunks");
        Path file = file(temp, bytes);
        PreparedFile prepared = PreparedFile.of(file, null);
        assertEquals(bytes.length, prepared.size());
        assertEquals(sha(bytes), prepared.sha256());

        Begun begun = begin(s1, prepared);
        String uploadId = begun.uploadId();
        UploadClient.Outcome outcome = client().send(file, uploadId);

        UploadClient.Sent sent = assertInstanceOf(UploadClient.Sent.class, outcome);
        assertEquals(new UploadClient.Sent(uploadId, begun.attachmentId(), 0, bytes.length), sent, "the upload, the attachment it is a file of, its number, and the bytes the server holds");
        assertNotEquals(uploadId, sent.attachmentId(), "an upload is a file of an attachment: the two ids are not the same");
        // The client sent the bytes and did not finish: the upload holds every byte, and there is no attachment until the model's one artifact_finish.
        JsonNode held = uploadStatus(s1, uploadId);
        assertEquals("STAGING", held.path("state").asString(), held.toString());
        assertEquals(bytes.length, held.path("received").asLong(), held.toString());
        assertEquals(begun.attachmentId(), held.path("attachmentId").asString(), held.toString());
        assertEquals(0, held.path("seq").asInt(), held.toString());
        assertNoAttachmentYet(s1, sent.attachmentId());
        JsonNode finished = finish(s1, sent.attachmentId());
        assertEquals(sent.attachmentId(), finished.path("attachmentId").asString(), finished.toString());
        assertEquals(1, finished.path("files").size(), finished.toString());
        JsonNode status = data(call("artifact_status", v1("attachmentId", sent.attachmentId()), s1));
        assertEquals("attachment", status.path("kind").asString(), status.toString());
        assertEquals("COMPLETED", status.path("state").asString(), status.toString());
        assertEquals(bytes.length, status.path("files").get(0).path("size").asLong(), status.toString());
        assertEquals(prepared.sha256(), status.path("files").get(0).path("sha256").asString(), status.toString());
        assertArrayEquals(bytes, readAll(s1, sent.attachmentId(), bytes.length), "artifact_read gives the bytes of the file");

        Run run = startRun(s1);
        JsonNode recorded = data(call("record_stage_result", passedWithEvidence(run, sent.attachmentId(), prepared.sha256()), s1));
        JsonNode result = recorded.path("stageResults").get(0);
        assertEquals(sent.attachmentId(), result.path("evidence").get(0).path("attachmentId").asString(), result.toString());
        assertEquals("SHA256_MATCHED", result.path("artifactChecks").get(0).path("check").asString(), result.toString());
        JsonNode finalized = data(call("finalize_workflow", args("runId", run.runId(), "expectedRevision", recorded.path("revision").asLong()), s1));
        assertEquals("PASSED", finalized.path("state").asString(), finalized.toString());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u2 empty

    @Test
    void u2AnEmptyFileIsSentThroughOneEmptyChunkAndKept(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        Path file = file(temp, new byte[0]);
        PreparedFile prepared = PreparedFile.of(file, null);
        assertEquals(0, prepared.size());
        assertEquals(EMPTY_SHA256, prepared.sha256());

        UploadClient.Sent sent = prepareBeginSendAndFinish(s1, file, prepared);

        assertEquals(0, sent.received());
        JsonNode status = data(call("artifact_status", v1("attachmentId", sent.attachmentId()), s1));
        assertEquals("COMPLETED", status.path("state").asString(), status.toString());
        assertEquals(0, status.path("files").get(0).path("size").asLong(), status.toString());
        assertEquals(EMPTY_SHA256, status.path("files").get(0).path("sha256").asString(), status.toString());
        assertEquals(0, readAll(s1, sent.attachmentId(), 0).length);
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u3 changed

    /** The file was changed after prepare: the client refuses before it sends a byte, and the upload is still there for the file as it was. */
    @ParameterizedTest
    @ValueSource(strings = {"same-size", "other-size"})
    void u3AFileChangedAfterPrepareIsRefusedAndNothingIsSent(String how, @TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] declared = bytes(CHUNK + 500, 2L);
        Path file = file(temp, declared);
        PreparedFile prepared = PreparedFile.of(file, null);
        Begun begun = begin(s1, prepared);
        String uploadId = begun.uploadId();
        Files.write(file, how.equals("same-size") ? bytes(declared.length, 3L) : bytes(declared.length + 7, 2L));

        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> client().send(file, uploadId));

        assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        List<?> violations = assertInstanceOf(List.class, refused.details().get("violations"), refused.details().toString());
        assertTrue(violations.stream().allMatch(violation -> violation.toString().startsWith("<file>:")), violations.toString());
        assertNothingWasSent(s1, uploadId);

        Files.write(file, declared);
        UploadClient.Sent sent = assertInstanceOf(UploadClient.Sent.class, client().send(file, uploadId));
        assertEquals(uploadId, sent.uploadId());
        assertEquals(begun.attachmentId(), sent.attachmentId());
        finish(s1, sent.attachmentId());
        assertArrayEquals(declared, readAll(s1, sent.attachmentId(), declared.length), "the file as it was is sent to the same upload");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u4 other upload

    @Test
    void u4TheFileOfAnotherDeclarationIsRefusedAndNothingIsSent(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] declaredBytes = bytes(1500, 4L);
        Path declaredFile = Files.write(temp.resolve("declared.sennard-test-unknown"), declaredBytes);
        Path otherFile = Files.write(temp.resolve("other.sennard-test-unknown"), bytes(declaredBytes.length, 5L));
        Begun begun = begin(s1, PreparedFile.of(declaredFile, null));
        String uploadId = begun.uploadId();

        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> client().send(otherFile, uploadId));

        assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        assertNothingWasSent(s1, uploadId);
        UploadClient.Sent sent = assertInstanceOf(UploadClient.Sent.class, client().send(declaredFile, uploadId));
        finish(s1, sent.attachmentId());
        assertArrayEquals(declaredBytes, readAll(s1, sent.attachmentId(), declaredBytes.length));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u5 unknown id

    @Test
    void u5AnUploadIdTheServerDoesNotKnowIsRefusedByTheTool(@TempDir Path temp) throws Exception {
        Path file = file(temp, bytes(100, 6L));

        UploadClient.Refused refused = assertThrows(UploadClient.Refused.class, () -> client().send(file, UUID.randomUUID().toString()));

        assertEquals("ARTIFACT_NOT_FOUND", refused.code());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u6 finished

    @Test
    void u6ASendOfAFinishedUploadAnswersTheSameAttachmentAndChangesNothing(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] bytes = bytes(THREE_CHUNKS, 7L);
        Path file = file(temp, bytes);
        PreparedFile prepared = PreparedFile.of(file, null);
        Begun begun = begin(s1, prepared);
        String uploadId = begun.uploadId();
        UploadClient.Sent first = assertInstanceOf(UploadClient.Sent.class, client().send(file, uploadId));

        // Before the finish: the upload already holds every byte, so a send again sends nothing and answers the same.
        assertEquals(first, client().send(file, uploadId));
        assertEquals(bytes.length, uploadStatus(s1, uploadId).path("received").asLong());
        finish(s1, begun.attachmentId());
        UploadClient.Outcome again = client().send(file, uploadId);

        assertEquals(first, again);
        assertEquals(begun.attachmentId(), first.attachmentId());
        JsonNode status = data(call("artifact_status", v1("attachmentId", begun.attachmentId()), s1));
        assertEquals("COMPLETED", status.path("state").asString(), status.toString());
        assertEquals(prepared.sha256(), status.path("files").get(0).path("sha256").asString(), status.toString());
        assertArrayEquals(bytes, readAll(s1, begun.attachmentId(), bytes.length));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u7 no server

    /** A port that was just given back: nothing listens on it. */
    private static int portNothingListensOn() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void u7NoServerOnThePortIsServerUnreachableNamingThePort(@TempDir Path temp) throws Exception {
        Path file = file(temp, bytes(100, 8L));
        int port = portNothingListensOn();

        UnavailableException unreachable = assertThrows(UnavailableException.class, () -> new UploadClient(port).send(file, UUID.randomUUID().toString()));

        assertEquals(UnavailableCode.SERVER_UNREACHABLE, unreachable.code());
        assertEquals("http://127.0.0.1:" + port, unreachable.arguments().get("where"));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u8 continued

    /**
     * The model began the upload and sent the first chunk (or the first two) itself, as it could; the client reads the status and sends the rest. With two chunks taken in, a client
     * that began again from chunk 0 would be refused as ARTIFACT_CHUNK_CONFLICT (chunk 0 is neither the last chunk taken in nor the next one), so that the success is the proof that it
     * continued from the server's sequence.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void u8ASendContinuesFromTheChunksTheServerAlreadyHolds(int chunksAlreadyTaken, @TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] bytes = bytes(THREE_CHUNKS, 9L);
        Path file = file(temp, bytes);
        PreparedFile prepared = PreparedFile.of(file, null);
        Begun begun = begin(s1, prepared);
        String uploadId = begun.uploadId();
        for (int sequence = 0; sequence < chunksAlreadyTaken; sequence++) {
            byte[] part = Arrays.copyOfRange(bytes, sequence * CHUNK, (sequence + 1) * CHUNK);
            sennard.bean(io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads.class).chunk(uploadId, sequence, part, sha(part));
        }
        JsonNode midway = uploadStatus(s1, uploadId);
        assertEquals("STAGING", midway.path("state").asString(), midway.toString());
        assertEquals(chunksAlreadyTaken, midway.path("sequence").asInt(), midway.toString());
        assertEquals((long) chunksAlreadyTaken * CHUNK, midway.path("received").asLong(), midway.toString());

        UploadClient.Sent sent = assertInstanceOf(UploadClient.Sent.class, client().send(file, uploadId));

        assertEquals(new UploadClient.Sent(uploadId, begun.attachmentId(), 0, bytes.length), sent);
        finish(s1, sent.attachmentId());
        JsonNode status = data(call("artifact_status", v1("attachmentId", sent.attachmentId()), s1));
        assertEquals("COMPLETED", status.path("state").asString(), status.toString());
        assertEquals(prepared.sha256(), status.path("files").get(0).path("sha256").asString(), status.toString());
        assertArrayEquals(bytes, readAll(s1, sent.attachmentId(), bytes.length), "the attachment has the bytes of the whole file");
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u9 author

    /** The client has no session: the attachment's author is the session that began the upload, so another session cannot name it in its result. */
    @Test
    void u9TheFinishedAttachmentIsTheSessionThatBeganItsAndAnotherSessionsResultCannotNameIt(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        String s2 = sennard.session();
        byte[] bytes = bytes(2000, 10L);
        Path file = file(temp, bytes);
        PreparedFile prepared = PreparedFile.of(file, null);
        UploadClient.Sent sent = prepareBeginSendAndFinish(s1, file, prepared);
        Run other = startRun(s2);

        JsonNode refused = call("record_stage_result", passedWithEvidence(other, sent.attachmentId(), prepared.sha256()), s2);

        assertRefused(refused, "BINDING_INVALID", "ATTACHMENT_AUTHOR_MISMATCH");
        assertEquals(sent.attachmentId(), refused.path("error").path("details").path("attachmentId").asString(), refused.toString());
        JsonNode after = receipt(s2, other.runId());
        assertEquals(0, after.path("stageResults").size(), "nothing was recorded: " + after);

        Run own = startRun(s1);
        JsonNode recorded = data(call("record_stage_result", passedWithEvidence(own, sent.attachmentId(), prepared.sha256()), s1));
        assertEquals("SHA256_MATCHED", recorded.path("stageResults").get(0).path("artifactChecks").get(0).path("check").asString(), recorded.toString());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- u10 command

    private record Finished(int exit, String stdout, String stderr) {
    }

    /**
     * {@code sennard <arguments>} as a person runs it: the product's own {@code main} in a child JVM (it ends with {@code System.exit}, so it cannot run inside this one). The class path is
     * this JVM's, in an argument file (the product's class path is longer than a command line may be on Windows). The child gets the installation of this test as its home, install
     * root and state folder ({@code -Duser.home} and the environment of {@link ReleaseJarFixture#isolated}), so that it finds this test's server through the address file and cannot
     * reach this computer's profile or an installation of it. The bound on the wait only stops a wedged process from holding the suite; it decides nothing.
     */
    private Finished command(Path folder, String... arguments) throws Exception {
        Path argumentFile = folder.resolve("cli.args");
        Files.writeString(argumentFile, "-Xmx" + System.getProperty("sennard.test.childHeap") + System.lineSeparator() + "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\""
                + System.lineSeparator(), StandardCharsets.UTF_8);
        List<String> line = new ArrayList<>(ReleaseJarFixture.java(home, "@" + argumentFile, SennardCli.class.getName()));
        line.addAll(List.of(arguments));
        Path out = folder.resolve("cli.stdout");
        Path err = folder.resolve("cli.stderr");
        ProcessBuilder builder = new ProcessBuilder(line).directory(folder.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(ReleaseJarFixture.isolated(home, Map.of("SENNARD_INSTALL_ROOT", installRoot.toString(), "SENNARD_STATE_DIR", home.resolve("state").toString())));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the command did not end: " + arguments[1]);
            return new Finished(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void u10TheCommandSendsAFileAndPrintsTheAttachmentIdOnOneLine(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] bytes = bytes(THREE_CHUNKS, 11L);
        Path file = file(temp, bytes);
        PreparedFile prepared = PreparedFile.of(file, null);
        Begun begun = begin(s1, prepared);
        String uploadId = begun.uploadId();

        Finished sent = command(temp, "attachments", "send", "--upload-id", uploadId, file.toString());

        assertEquals(0, sent.exit(), sent.stderr());
        assertEquals(List.of("{\"uploadId\":\"" + uploadId + "\",\"attachmentId\":\"" + begun.attachmentId() + "\",\"seq\":0,\"received\":" + bytes.length + "}"), sent.stdout().lines().toList(),
                "stdout is exactly one JSON line: " + sent.stdout());
        // c7: the command does not finish: the upload holds every byte and the attachment is not there until the model's one artifact_finish.
        JsonNode held = uploadStatus(s1, uploadId);
        assertEquals("STAGING", held.path("state").asString(), held.toString());
        assertEquals(bytes.length, held.path("received").asLong(), held.toString());
        assertNoAttachmentYet(s1, begun.attachmentId());
        finish(s1, begun.attachmentId());
        assertArrayEquals(bytes, readAll(s1, begun.attachmentId(), bytes.length), "the command kept the bytes of the file");
    }

    /**
     * The command for each file of one attachment (two {@code send} calls with the two uploads of one {@code artifact_begin}), then the model's one finish: the second file's line says
     * {@code seq} 1 and the attachment of the first, no send finished anything, and after the finish both files are the attachment's, each with its own sha256.
     */
    @Test
    void u11TheCommandForEachFileOfABundleSendsThemToOneAttachmentAndTheModelsOneFinishMakesIt(@TempDir Path temp) throws Exception {
        String s1 = sennard.session();
        byte[] first = bytes(THREE_CHUNKS, 14L);
        byte[] second = bytes(CHUNK + 10, 15L);
        Path folder = Files.createDirectories(temp.resolve("first"));
        Path firstFile = file(folder, first);
        Path secondFile = file(Files.createDirectories(temp.resolve("second")), second);
        Begun begun = beginAll(s1, PreparedFile.of(firstFile, null), PreparedFile.of(secondFile, null));

        Finished sentFirst = command(temp, "attachments", "send", "--upload-id", begun.uploadIds().get(0), firstFile.toString());
        Finished sentSecond = command(temp, "attachments", "send", "--upload-id", begun.uploadIds().get(1), secondFile.toString());

        assertEquals(0, sentFirst.exit(), sentFirst.stderr());
        assertEquals(0, sentSecond.exit(), sentSecond.stderr());
        assertEquals(List.of("{\"uploadId\":\"" + begun.uploadIds().get(0) + "\",\"attachmentId\":\"" + begun.attachmentId() + "\",\"seq\":0,\"received\":" + first.length + "}"),
                sentFirst.stdout().lines().toList());
        assertEquals(List.of("{\"uploadId\":\"" + begun.uploadIds().get(1) + "\",\"attachmentId\":\"" + begun.attachmentId() + "\",\"seq\":1,\"received\":" + second.length + "}"),
                sentSecond.stdout().lines().toList());
        assertNoAttachmentYet(s1, begun.attachmentId());
        JsonNode finished = finish(s1, begun.attachmentId());
        assertEquals(2, finished.path("files").size(), finished.toString());
        assertEquals(sha(first), finished.path("files").get(0).path("sha256").asString(), finished.toString());
        assertEquals(sha(second), finished.path("files").get(1).path("sha256").asString(), finished.toString());
        // artifact_read takes the file number (0 when it is left out).
        assertArrayEquals(first, readAll(s1, begun.attachmentId(), first.length), "file 0 is what a read without a number gives");
        JsonNode page = data(call("artifact_read", v1("attachmentId", begun.attachmentId(), "seq", 1, "offsetBytes", 0, "maxBytes", CHUNK), s1));
        assertEquals(1, page.path("seq").asInt(), page.toString());
        assertEquals(second.length, page.path("size").asLong(), page.toString());
        assertArrayEquals(Arrays.copyOfRange(second, 0, CHUNK), Base64.getDecoder().decode(page.path("data").asString()));
    }

    @Test
    void u10TheCommandEndsWithTheCodeOfARefusalOnStderrAndNothingOnStdout(@TempDir Path temp) throws Exception {
        Path file = file(temp, bytes(100, 12L));

        Finished refused = command(temp, "attachments", "send", "--upload-id", UUID.randomUUID().toString(), file.toString());

        assertEquals(1, refused.exit(), refused.stderr());
        assertEquals("", refused.stdout());
        assertTrue(refused.stderr().lines().findFirst().orElse("").startsWith("ARTIFACT_NOT_FOUND: "), refused.stderr());
    }

    @Test
    void u10TheCommandWithNoServerAnnouncedEndsWithServerUnreachableOnStderr(@TempDir Path temp) throws Exception {
        Path file = file(temp, bytes(100, 13L));
        // No server announces itself: the address file is not there (the cases of this class run one after another, so it is written again at the end).
        Files.delete(addressFile);
        try {
            Finished unreachable = command(temp, "attachments", "send", "--upload-id", UUID.randomUUID().toString(), file.toString());

            assertEquals(1, unreachable.exit(), unreachable.stderr());
            assertEquals("", unreachable.stdout());
            assertEquals("SERVER_UNREACHABLE: No server of this installation answers: no live server is announced in " + addressFile, unreachable.stderr().lines().findFirst().orElse(""),
                    unreachable.stderr());
        } finally {
            announceServer();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"codex", "claude", "agy"})
    void automaticUploadUsesStandardMultipartBeyondOneMiBAndReplays(String host, @TempDir Path temp) throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, SessionServerSupport.post(sennard.server.port, "/hooks/v1/" + host + "/SessionStart", SessionServerSupport.hook("windows", host, "SessionStart", session, "none")).status());
        String canonicalHost = switch (host) { case "claude" -> "claude-code"; case "agy" -> "antigravity"; default -> host; };
        var registry = sennard.bean(io.github.jaeseongs95.sennard.service.identity.SessionRegistry.class);
        String ref = registry.findRef(canonicalHost, session).orElseThrow();
        byte[] source = bytes(2 * 1024 * 1024 + 31, 7351L);
        Path file = Files.write(temp.resolve("첨부 파일.bin"), source);
        {
            Finished sent = command(temp, "attachments", "upload", "--session-ref", ref, file.toString());
            assertEquals(0, sent.exit(), sent.stderr());
            assertEquals(1, sent.stdout().lines().count());
            JsonNode first = ToolServerSupport.MAPPER.readTree(sent.stdout());
            assertEquals(sha(source), first.path("files").path(0).path("sha256").asString());
            assertEquals(source.length, first.path("files").path(0).path("size").asLong());
            assertEquals("첨부 파일.bin", first.path("files").path(0).path("fileName").asString());
            var uploads = sennard.bean(io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads.class);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            while (actual.size() < source.length) {
                var read = uploads.read(first.path("attachmentId").asString(), 0, actual.size(), CHUNK);
                assertEquals(io.github.jaeseongs95.sennard.server.workflow.WorkflowCall.actorId(canonicalHost, session, null), read.view().attachment().createdBy());
                actual.writeBytes(read.page().data());
            }
            assertArrayEquals(source, actual.toByteArray());
            sennard.bean(io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance.class).reap();
            Finished replay = command(temp, "attachments", "upload", "--session-ref", ref, file.toString());
            assertEquals(0, replay.exit(), replay.stderr());
            assertEquals(first, ToolServerSupport.MAPPER.readTree(replay.stdout()));
        }
        assertInstanceOf(org.springframework.web.multipart.support.StandardServletMultipartResolver.class,
                sennard.bean(org.springframework.web.multipart.MultipartResolver.class));
        var config = sennard.bean(jakarta.servlet.MultipartConfigElement.class);
        assertEquals(0, config.getFileSizeThreshold());
        try (var left = Files.list(Path.of(config.getLocation()))) { assertEquals(0, left.count(), "Servlet temporary files are cleaned after the request"); }
    }

    @Test
    void multipartGuardAndHashFailuresPreserveProgressAndCleanTemporaryFiles() throws Exception {
        var uploads = sennard.bean(io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads.class);
        byte[] expected = "declared bytes".getBytes(StandardCharsets.UTF_8);
        var begun = uploads.begin("multipart-http-test", new io.github.jaeseongs95.sennard.app.attachment.AttachmentService.BeginRequest(
                "raw-" + UUID.randomUUID(), List.of(new io.github.jaeseongs95.sennard.app.attachment.AttachmentService.BeginFile("test", "text/plain", expected.length, sha(expected))), null));
        String id = begun.files().getFirst().uploadId();
        String boundary = "test-boundary";
        byte[] wrong = expected.clone();
        wrong[0] ^= 1;
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test\"\r\n\r\n"
                + new String(wrong, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("X-Sennard-Upload-Id", id);
        var mismatch = RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body);
        assertEquals(422, mismatch.status(), mismatch.text());
        assertEquals("ARTIFACT_HASH_MISMATCH", ToolServerSupport.MAPPER.readTree(mismatch.body()).path("code").asString(), mismatch.text());
        assertEquals(0, uploads.uploadStatus(id).received());
        assertEquals(0, uploads.uploadStatus(id).sequence());

        headers.put("X-Sennard-Upload-Id", UUID.randomUUID().toString());
        assertEquals(404, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        headers.remove("X-Sennard-Upload-Id");
        assertEquals(400, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        headers.put("X-Sennard-Upload-Id", id);
        headers.remove("Content-Length");
        assertEquals(411, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        headers.put("Content-Length", String.valueOf(expected.length + 65537L));
        assertEquals(413, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, null).status());
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Content-Type", "application/json");
        assertEquals(415, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Origin", "https://foreign.example");
        assertEquals(403, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        headers.remove("Origin");
        headers.put("Host", "foreign.example");
        assertEquals(421, RawHttp.exchange(sennard.server.port, "POST", "/attachments/upload", headers, body).status());
        assertEquals(0, uploads.uploadStatus(id).sequence());
        var config = sennard.bean(jakarta.servlet.MultipartConfigElement.class);
        try (var left = Files.list(Path.of(config.getLocation()))) { assertEquals(0, left.count()); }
    }

}
