// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared local upload client. {@code upload} measures a file, begins its attachment using the registered session reference,
 * streams one standard multipart request, and finishes it. MCP calls carry metadata only; file bytes do not enter model context.
 * {@code send} remains available for uploads begun separately. A repeated invocation continues committed progress;
 * an uncertain response is checked once with artifact_status and never silently treated as success.
 */
public final class UploadClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(UploadClient.class);
    private static final String ENDPOINT = "/mcp";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** What a send came to: the file is held whole by the upload, or why it stopped (the upload is not lost: the same command continues it). */
    public sealed interface Outcome {
    }

    /** The upload holds every byte of the file: its id, the id of the attachment it is a file of, the file's number in it, and the bytes the server holds. The attachment is finished with {@code artifact_finish}. */
    public record Sent(String uploadId, String attachmentId, int seq, long received) implements Outcome {
    }

    /** The send ended before every byte was sent, or the answer to a chunk is not known; {@code message} says what the server holds. */
    public record Stopped(String message) implements Outcome {
    }

    /** A tool refused the call: its code (the constant's name) and its sentence. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The state of an upload as the server reports it; an upload whose state is another word is a state this client does not know. */
    private enum UploadState {
        STAGING, COMPLETED
    }

    /** A call whose answer is not known: the connection broke, the time ran out, or what came is not the tool's envelope. */
    private static final class Unclear extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unclear(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private long requestId;

    /** @param port the server's port, from the installation's settings */
    public UploadClient(int port) {
        this.port = port;
    }

    /** One caller invocation measures, begins, sends and finishes a file; the bytes never enter the model. */
    public JsonNode upload(Path file, String sessionRef, String operationKey, String fileName) {
        PreparedFile measured = PreparedFile.of(file, fileName);
        String key = operationKey == null ? "upload:" + Digests.sha256Hex((file.toAbsolutePath().normalize() + "\0" + measured.sha256() + "\0" + measured.fileName()).getBytes(StandardCharsets.UTF_8)) : operationKey;
        JsonNode begun;
        try {
            begun = call("artifact_begin", Map.of("sessionRef", sessionRef, "operationKey", key, "files", java.util.List.of(measured)));
        } catch (Unclear unknown) { throw new Refused("UPLOAD_RESULT_UNKNOWN", unknown.getMessage() + "; repeat the same upload command to recover its result"); }
        String attachmentId = begun.path("attachmentId").asString();
        String uploadId = begun.path("files").path(0).path("uploadId").asString();
        switch (send(file, uploadId)) {
            case Sent ignored -> { }
            case Stopped stopped -> throw new Refused("UPLOAD_INCOMPLETE", stopped.message());
        }
        try { return call("artifact_finish", Map.of("attachmentId", attachmentId)); }
        catch (Unclear unknown) { throw new Refused("UPLOAD_RESULT_UNKNOWN", unknown.getMessage() + "; repeat the same upload command to recover its result"); }
    }

    @Override
    public void close() { http.close(); }

    /**
     * Sends the file to the upload, from the bytes the server already holds. It does not finish the attachment: that is one {@code artifact_finish} for all the files of the attachment,
     * which the model calls when every file has been sent.
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.InvalidInputException when the file is not what the upload declared (its size or sha256 differ: it changed after {@code prepare}) or
     *             changed while it was sent
     * @throws UnavailableException SERVER_UNREACHABLE when the server does not answer the first call
     * @throws Refused when a tool refuses a call
     */
    public Outcome send(Path file, String uploadId) {
        PreparedFile.Measure local = PreparedFile.measure(file);
        JsonNode status;
        try {
            status = call("artifact_status", Map.of("uploadId", uploadId));
        } catch (Unclear noAnswer) {
            throw new UnavailableException(UnavailableCode.SERVER_UNREACHABLE, SennardException.args("where", "http://127.0.0.1:" + port), noAnswer);
        }
        String attachmentId = status.path("attachmentId").asString();
        int seq = status.path("seq").asInt(0);
        if (UploadState.valueOf(status.path("state").asString()) == UploadState.COMPLETED) {
            return new Sent(uploadId, attachmentId, seq, status.path("received").asLong(0));
        }
        if (status.path("size").asLong(-1) != local.size() || !Objects.equals(status.path("sha256").asString(), local.sha256())) {
            throw ArtifactErrors.invalidArgument("<file>", "it is not the file the upload declared (size or sha256 differ): run prepare again and begin a new upload");
        }
        try {
            sendMultipart(file, uploadId);
            if (!local.equals(PreparedFile.measure(file))) {
                throw ArtifactErrors.invalidArgument("<file>", "the file changed while it was sent; the upload does not hold the file as it is now");
            }
            return new Sent(uploadId, attachmentId, seq, local.size());
        } catch (Unclear noAnswer) {
            LOG.debug("A call to the server was not answered.", noAnswer);
            return stopped(uploadId, local.size(), noAnswer.getMessage());
        }
    }

    /** The JDK streams the file publisher as one standard multipart request. */
    private void sendMultipart(Path file, String uploadId) {
        String boundary = "sennard-" + java.util.UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        try {
            var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix), HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofByteArray(suffix));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/attachments/upload"))
                    .timeout(CALL_TIMEOUT).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Sennard-Upload-Id", uploadId).POST(body).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode answer = mapper.readTree(response.body());
            if (response.statusCode() != 200) {
                if (answer.has("code")) throw new Refused(answer.path("code").asString(), answer.path("detail").asString());
                throw new Unclear("File upload was answered with HTTP " + response.statusCode(), null);
            }
            if (!answer.path("uploadId").asString().equals(uploadId)) throw new Unclear("File upload returned an unexpected response", null);
        } catch (IOException cause) {
            throw new Unclear("File upload was not answered (" + cause.getClass().getSimpleName() + ")", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unclear("File upload was interrupted", interrupted);
        } catch (JacksonException invalid) {
            throw new Unclear("File upload returned an invalid response", invalid);
        }
    }

    /**
     * The end of a send whose last answer is not known: the upload's state is read once, and that is how the command ends. An upload that holds every byte of the file is sent;
     * anything else tells what the server holds, and sending the file again continues from there.
     */
    private Outcome stopped(String uploadId, long size, String unclear) {
        try {
            JsonNode status = call("artifact_status", Map.of("uploadId", uploadId));
            // A file of no bytes is held once its one empty chunk was taken in (sequence 1), so the bytes alone do not say it.
            if (UploadState.valueOf(status.path("state").asString()) == UploadState.COMPLETED || status.path("sequence").asLong(0) > 0 && status.path("received").asLong(-1) == size) {
                return new Sent(uploadId, status.path("attachmentId").asString(), status.path("seq").asInt(0), status.path("received").asLong(0));
            }
            return new Stopped(unclear + "; the upload holds " + status.path("received").asLong() + " bytes (next chunk " + status.path("sequence").asLong() + "). Send the file again to continue it.");
        } catch (Unclear noAnswer) {
            LOG.debug("The state of the upload could not be read either.", noAnswer);
            return new Stopped(unclear + "; the upload's state could not be read either. Send the file again to continue it.");
        }
    }

    /** One {@code tools/call} of the server's stateless MCP endpoint; the data of the tool's envelope. */
    private JsonNode call(String tool, Map<String, Object> arguments) {
        Map<String, Object> toolArguments = new LinkedHashMap<>();
        toolArguments.put("schemaVersion", SCHEMA_VERSION);
        toolArguments.putAll(arguments);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", toolArguments);
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", ++requestId);
        rpc.put("method", "tools/call");
        rpc.put("params", params);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ENDPOINT)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException cause) {
            throw new Unclear(tool + " was not answered (" + cause.getClass().getSimpleName() + ")", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unclear(tool + " was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new Unclear(tool + " was answered with HTTP " + response.statusCode(), null);
        }
        return data(tool, response.body());
    }

    /** The data of the tool's envelope in the JSON-RPC answer (a plain JSON body, or the one event of a stream); a refusal is {@link Refused}, anything else that is not the envelope is not known. */
    private JsonNode data(String tool, String body) {
        try {
            JsonNode rpc = mapper.readTree(lastJson(body));
            if (rpc.has("error")) {
                throw new Refused("JSON_RPC_" + rpc.path("error").path("code").asString(), rpc.path("error").path("message").asString());
            }
            JsonNode envelope = mapper.readTree(rpc.path("result").path("content").path(0).path("text").asString());
            if (!envelope.path("ok").asBoolean(false)) {
                throw new Refused(envelope.path("error").path("code").asString(), envelope.path("error").path("message").asString());
            }
            return envelope.path("data");
        } catch (JacksonException notTheEnvelope) {
            throw new Unclear(tool + " was answered with something that is not the tool's answer", notTheEnvelope);
        }
    }

    /** The JSON of an answer: the body when it is JSON, else the last {@code data:} line of an event stream. */
    private static String lastJson(String body) {
        if (body.stripLeading().startsWith("{")) {
            return body;
        }
        String last = "";
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                last = line.substring("data:".length()).strip();
            }
        }
        return last;
    }
}
