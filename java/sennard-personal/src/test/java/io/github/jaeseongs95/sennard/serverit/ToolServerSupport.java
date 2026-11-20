// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole application with real PostgreSQL, the real skill tree and the real signed log, driven as its users drive it: an MCP client with
 * a host's recorded {@code _meta}, recorded hook bodies, and HTTP to the admin port. Replaced, each named: the host CLIs (a test must not run
 * them), the workflow side of a root approval (area C's, not merged yet), the two remote services (a local stand-in, so no test leaves this
 * computer), and the browser's passkey (a software authenticator making the same assertion).
 */
final class ToolServerSupport implements AutoCloseable {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The tag API and the JEV endpoint. {@code jevBody} is what the provider answers for a classification. */
    static final class StubRemote implements AutoCloseable {
        final HttpServer http;
        private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger tagCalls = new AtomicInteger();
        final AtomicInteger jevCalls = new AtomicInteger();
        volatile int tagStatus = 200;
        volatile String tags = "[{\"ref\":\"refs/tags/v99.1.0\",\"object\":{\"sha\":\"" + "a".repeat(40) + "\",\"type\":\"commit\",\"url\":\"http://unused\"}}]";
        volatile java.util.function.Function<JsonNode, String> jevAnswer = request -> "{}";
        /** When set, answers each classification call by its number (1 for the first since the server started) and body, with a status and headers; it replaces {@link #jevAnswer}. */
        volatile java.util.function.BiFunction<Integer, JsonNode, JevReply> jevReply;
        /** When each classification call arrived ({@link System#nanoTime}), and the model each asked for, in order. */
        final List<Long> jevTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> jevModels = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** What the stand-in provider answers: a status, headers and a body; {@link #CUT} cuts the connection without any answer. */
        record JevReply(int status, Map<String, String> headers, String body) {
            static final JevReply CUT = new JevReply(-1, Map.of(), "");

            static JevReply of(int status, String body) {
                return new JevReply(status, Map.of(), body);
            }
        }

        StubRemote() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Without an executor the server runs every handler on its one dispatcher thread, so a request that waits holds all the others. Two classifications that run at the same time
            // (JevProviderServerTest.theTriesStopWhereTheBudgetStops) must each be answered on their own.
            http.setExecutor(executor);
            http.createContext("/tags", exchange -> {
                tagCalls.incrementAndGet();
                respond(exchange, tagStatus, tags);
            });
            http.createContext("/jev/systemone", exchange -> {
                int call = jevCalls.incrementAndGet();
                jevTimes.add(System.nanoTime());
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                jevModels.add(request.path("model").asString());
                java.util.function.BiFunction<Integer, JsonNode, JevReply> replies = jevReply;
                if (replies == null) {
                    respond(exchange, 200, jevAnswer.apply(request));
                    return;
                }
                JevReply reply = replies.apply(call, request);
                if (reply.status() < 0) {
                    exchange.close();
                    return;
                }
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                respond(exchange, reply.status(), reply.body());
            });
            http.createContext("/jev/models", exchange -> respond(exchange, 200, "{\"models\":[]}"));
            http.start();
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        String url(String path) {
            return "http://127.0.0.1:" + http.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            http.stop(0);
            executor.close();
        }
    }

    /** A WebAuthn credential in software: the assertion a browser passkey returns, with the fields a test bends. */
    static final class Passkey {
        final String credentialId = UUID.randomUUID().toString().replace("-", "");
        final KeyPair pair;

        Passkey() throws GeneralSecurityException {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        }

        private static String b64(byte[] value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }

        /** The public key as the page sends it with the commit of the registration (the signed change names only its fingerprint). */
        String publicKey() {
            return b64(pair.getPublic().getEncoded());
        }

        /** The fingerprint the signed registration carries in place of the key: {@code sha256:} and the hex of the SHA-256 of the SPKI bytes. */
        String fingerprint() throws GeneralSecurityException {
            return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded()));
        }

        ObjectNode addChange() throws GeneralSecurityException {
            return MAPPER.createObjectNode().put("op", "KEY_ADD").put("credentialId", credentialId).put("fingerprint", fingerprint()).put("alg", -7).put("label", "test key");
        }

        ObjectNode assertion(String intent, String origin) throws GeneralSecurityException {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String challenge = b64(sha.digest(intent.getBytes(StandardCharsets.UTF_8)));
            byte[] client = MAPPER.writeValueAsBytes(Map.of("type", "webauthn.get", "challenge", challenge, "origin", origin, "crossOrigin", false));
            byte[] rp = MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8));
            byte[] data = ByteBuffer.allocate(37).put(rp).put((byte) 0x05).putInt(1).array();
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(data);
            signer.update(MessageDigest.getInstance("SHA-256").digest(client));
            return MAPPER.createObjectNode().put("credentialId", credentialId).put("authenticatorData", b64(data)).put("clientDataJSON", b64(client)).put("signature", b64(signer.sign()));
        }
    }

    final RunningServer server;
    final int adminPort;
    final String adminOrigin;
    final StubRemote remote;
    private final McpSyncClient client;

    private ToolServerSupport(RunningServer server, int adminPort, StubRemote remote) {
        this.server = server;
        this.adminPort = adminPort;
        this.adminOrigin = "http://localhost:" + adminPort;
        this.remote = remote;
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port).endpoint("/mcp");
        transport.requestBuilder(HttpRequest.newBuilder().header("Sennard-Host-Label", "codex"));
        this.client = McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(60)).build();
        this.client.initialize();
    }

    /** The repository this build runs in: the skill tree a test reads is the one shipped. */
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

    static ToolServerSupport start() throws Exception {
        return start(new String[0]);
    }

    /** The server with the repository's skills folder as its deployed skills, for what a hook reads from there. */
    static ToolServerSupport startWithDeployedSkills() throws Exception {
        return start("--sennard.skills-dir=" + repository().resolve("skills"));
    }

    /** The server with these settings added, for a test that needs another folder (the golden tests' content root). */
    static ToolServerSupport startWith(String... extra) throws Exception {
        return start(extra);
    }

    /** The server with the test's own configuration classes added (tools a test declares to reach a path no public tool reaches). */
    static ToolServerSupport startWithSources(List<Class<?>> extraSources) throws Exception {
        return start(extraSources, new String[0]);
    }

    /** The server with the test's own configuration classes and these settings added (a test that starts workflows needs the content root and the skills folder too). */
    static ToolServerSupport startWithSourcesAndSettings(List<Class<?>> extraSources, String... extra) throws Exception {
        return start(extraSources, extra);
    }

    private static ToolServerSupport start(String... extra) throws Exception {
        return start(List.of(), extra);
    }

    private static ToolServerSupport start(List<Class<?>> extraSources, String... extra) throws Exception {
        StubRemote remote = new StubRemote();
        List<Class<?>> sources = new ArrayList<>(SessionServerSupport.sources());
        sources.addAll(extraSources);
        List<String> arguments = new ArrayList<>(List.of("--sennard.skills-root=" + repository(),
                "--sennard.updates.tags-api=" + remote.url("/tags"), "--sennard.typesafe.base-url=" + remote.url("/jev"), "--sennard.typesafe.allow-loopback-http=true"));
        arguments.addAll(List.of(extra));
        RunningServer server = SessionServerSupport.started(sources, arguments.toArray(String[]::new));
        return new ToolServerSupport(server, server.adminPort(), remote);
    }

    /**
     * A server whose own settings name no business database (unless {@code extra} does): the store waits as NOT_CONFIGURED, and the admin page is the settings database's until a
     * database is linked. The test makes the database clean itself ({@link SharedDatabase#resetForTheClass()}) before it links it.
     */
    static ToolServerSupport startBeforeTheLink(String... extra) throws Exception {
        StubRemote remote = new StubRemote();
        List<Class<?>> sources = new ArrayList<>(SessionServerSupport.sources());
        List<String> arguments = new ArrayList<>(List.of("--sennard.store.prepare=true", "--sennard.skills-root=" + repository(),
                "--sennard.updates.tags-api=" + remote.url("/tags"), "--sennard.typesafe.base-url=" + remote.url("/jev"), "--sennard.typesafe.allow-loopback-http=true"));
        arguments.addAll(List.of(extra));
        RunningServer server = RunningServer.start(sources, arguments.toArray(String[]::new));
        return new ToolServerSupport(server, server.adminPort(), remote);
    }

    @Override
    public void close() {
        client.close();
        server.close();
        remote.close();
    }

    <T> T bean(Class<T> type) {
        return server.bean(type);
    }

    /** Work in one transaction, as a service method would have it. */
    <T> T inTransaction(Supplier<T> work) {
        return bean(TransactionTemplate.class).execute(status -> work.get());
    }

    // --------------------------------------------------------------------------------------------------------- as a host

    /** A session the server knows, started the way a host starts one. */
    String session() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, SessionServerSupport.post(server.port, "/hooks/v1/codex/SessionStart", SessionServerSupport.hook("windows", "codex", "SessionStart", session, "none")).status());
        return session;
    }

    /** A tool call by the session the recorded metadata names. */
    McpSchema.CallToolResult callRaw(String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(OperationKeys.withKey(tool, arguments)).meta(meta).build());
    }

    /** A call that adds no operationKey of its own, as a caller that forgot the key sends it (the other calls of this class add one to a write tool). */
    JsonNode callAsSent(String tool, Map<String, Object> arguments, String session) {
        return envelope(client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).meta(SessionServerSupport.meta("windows", "codex", session, "none")).build()));
    }

    /** The result of a call that adds no operationKey, as the protocol gives it (a tool that answers with something other than the envelope is read by the test itself). */
    McpSchema.CallToolResult callResultAsSent(String tool, Map<String, Object> arguments, String session) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).meta(SessionServerSupport.meta("windows", "codex", session, "none")).build());
    }

    private static JsonNode envelope(McpSchema.CallToolResult result) {
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        if (!text.startsWith("{")) {
            throw new AssertionError("Not the envelope: " + text);
        }
        return MAPPER.readTree(text);
    }

    /** The tool's envelope, for a call by this session. */
    JsonNode call(String tool, Map<String, Object> arguments, String session) {
        return envelope(callRaw(tool, arguments, SessionServerSupport.meta("windows", "codex", session, "none")));
    }

    /**
     * A tool call that is a host call of its own. The fixture's meta carries one fixed call ID, and the server consumes an execution observation (session, actor and call ID) once, so a session
     * that starts a workflow more than once reports a new call ID each time, as a host does for every tool call.
     */
    JsonNode callAsNewHostCall(String tool, Map<String, Object> arguments, String session) {
        Map<String, Object> meta = new LinkedHashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        meta.put("callId", "exec-" + UUID.randomUUID());
        return envelope(callRaw(tool, arguments, meta));
    }

    /** What tools/list answers a real MCP client. */
    List<McpSchema.Tool> listTools() {
        return client.listTools().tools();
    }

    /** A call whose host says nothing about its session. */
    JsonNode callUnobserved(String tool, Map<String, Object> arguments) {
        return envelope(callRaw(tool, arguments, Map.of()));
    }

    // ----------------------------------------------------------------------------------------------------------- attachments

    /**
     * One file of an attachment that an upload made: the id of the attachment, the number of the file in it (from 0, the order of the files of {@code artifact_begin}) and the sha256 the
     * server kept for the file, as {@code sha256:<hex>} (the form an evidence item's digest has). An attachment of one file is file 0.
     */
    record Uploaded(String attachmentId, int seq, String sha256) {
        /** The only file of an attachment of one file. */
        Uploaded(String attachmentId, String sha256) {
            this(attachmentId, 0, sha256);
        }
    }

    /** A file of a bundle to upload: its bytes, media type and file name. */
    record UploadFile(byte[] bytes, String mediaType, String fileName) {
    }

    /** How a test calls a tool as a given session: this support's own client, or the client a test makes for itself. The answer is the tool's envelope. */
    @FunctionalInterface
    interface ToolCaller {
        JsonNode call(String tool, Map<String, Object> arguments, String session);
    }

    private static JsonNode uploadStep(ToolCaller caller, String tool, String session, Map<String, Object> arguments) {
        Map<String, Object> versioned = new LinkedHashMap<>();
        versioned.put("schemaVersion", "1.0.0");
        versioned.putAll(arguments);
        JsonNode answer = caller.call(tool, versioned, session);
        assertTrue(answer.path("ok").asBoolean(false), tool + " while uploading: " + answer);
        return answer.path("data");
    }

    /**
     * The bytes as an attachment of this session, made with the real {@code artifact_begin} and {@code artifact_finish} tools and multipart upload, as a client outside the model
     * does for a file: a begin with an operationKey of its own, the chunks, the finish. The author of the attachment is the session, so the same session can name it in the result it
     * records.
     */
    static Uploaded upload(int port, ToolCaller caller, String session, byte[] bytes, String mediaType, String fileName) {
        return uploadBundle(port, caller, session, List.of(new UploadFile(bytes, mediaType, fileName))).getFirst();
    }

    /** The {@code files} argument of {@code artifact_begin} for these files, numbered in the order of the list. */
    static List<Map<String, Object>> beginFiles(List<UploadFile> files) {
        List<Map<String, Object>> declared = new ArrayList<>();
        for (UploadFile file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fileName", file.fileName());
            entry.put("mediaType", file.mediaType());
            entry.put("size", file.bytes().length);
            entry.put("sha256", "sha256:" + Digests.sha256Hex(file.bytes()));
            declared.add(entry);
        }
        return declared;
    }

    static RawHttp.Response postMultipart(int port, String uploadId, byte[] bytes) throws IOException {
        String boundary = "sennard-test-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = ByteBuffer.allocate(prefix.length + bytes.length + suffix.length).put(prefix).put(bytes).put(suffix).array();
        return RawHttp.exchange(port, "POST", "/attachments/upload", Map.of(
                "Content-Type", "multipart/form-data; boundary=" + boundary,
                "Content-Length", Integer.toString(body.length),
                "X-Sennard-Upload-Id", uploadId), body);
    }

    static JsonNode sendMultipart(int port, String uploadId, byte[] bytes) {
        try {
            var reply = postMultipart(port, uploadId, bytes);
            assertEquals(200, reply.status(), reply.text());
            return MAPPER.readTree(reply.body());
        } catch (IOException transportFailure) {
            throw new UncheckedIOException(transportFailure);
        }
    }

    /**
     * The files as one attachment of this session, made through the real {@code artifact_begin}, one multipart upload per file,
     * (the chunks of each file to its own uploadId) and {@code artifact_finish} (one finish, by the attachmentId) tools. The files are numbered in the order of the list. The result is one
     * {@link Uploaded} per file, in that order, with the sha256 the server kept for the file.
     */
    static List<Uploaded> uploadBundle(int port, ToolCaller caller, String session, List<UploadFile> files) {
        assertTrue(!files.isEmpty() && files.stream().allMatch(file -> file.bytes().length > 0), "an attachment has files and every file has bytes");
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("operationKey", "upload-" + UUID.randomUUID());
        begin.put("files", beginFiles(files));
        begin.put("purpose", "evidence");
        JsonNode begun = uploadStep(caller, "artifact_begin", session, begin);
        String attachmentId = begun.path("attachmentId").asString();
        assertEquals(files.size(), begun.path("files").size(), "one upload for each file: " + begun);
        for (int seq = 0; seq < files.size(); seq++) {
            assertEquals(seq, begun.path("files").get(seq).path("seq").asInt(), begun.toString());
            sendMultipart(port, begun.path("files").get(seq).path("uploadId").asString(), files.get(seq).bytes());
        }
        JsonNode finished = uploadStep(caller, "artifact_finish", session, Map.of("attachmentId", attachmentId));
        assertEquals(attachmentId, finished.path("attachmentId").asString(), finished.toString());
        assertEquals(files.size(), finished.path("files").size(), finished.toString());
        List<Uploaded> kept = new ArrayList<>();
        for (int seq = 0; seq < files.size(); seq++) {
            JsonNode file = finished.path("files").get(seq);
            assertEquals(seq, file.path("seq").asInt(), finished.toString());
            assertEquals("sha256:" + Digests.sha256Hex(files.get(seq).bytes()), file.path("sha256").asString(), "the server kept the bytes that were sent: " + finished);
            kept.add(new Uploaded(attachmentId, seq, file.path("sha256").asString()));
        }
        return List.copyOf(kept);
    }

    /** Text of its own (never the same as another upload's), as an attachment of this session. */
    static Uploaded uploadEvidence(int port, ToolCaller caller, String session) {
        return upload(port, caller, session, ("stage evidence " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8), "text/plain", "evidence.txt");
    }

    /** A JSON document as an attachment of this session (the file a stage result's {@code outputFile} names). */
    static Uploaded uploadJson(int port, ToolCaller caller, String session, String json) {
        return upload(port, caller, session, json.getBytes(StandardCharsets.UTF_8), "application/json", "output.json");
    }

    /** The bytes as an attachment of this session, through this support's own client; every call is a host call of its own. */
    Uploaded upload(String session, byte[] bytes, String mediaType, String fileName) {
        return upload(server.port, this::callAsNewHostCall, session, bytes, mediaType, fileName);
    }

    /** The files as one attachment of this session, through this support's own client; one {@link Uploaded} for each file. */
    List<Uploaded> uploadBundle(String session, List<UploadFile> files) {
        return uploadBundle(server.port, this::callAsNewHostCall, session, files);
    }

    /** Text of its own as an attachment of this session, through this support's own client. */
    Uploaded uploadEvidence(String session) {
        return uploadEvidence(server.port, this::callAsNewHostCall, session);
    }

    /** A JSON document as an attachment of this session, through this support's own client. */
    Uploaded uploadJson(String session, String json) {
        return uploadJson(server.port, this::callAsNewHostCall, session, json);
    }

    /** The evidence item the tests record: it names the attachment and its digest (a stage result's evidence names a file in no other way). */
    static Map<String, Object> observedEvidence(Uploaded uploaded) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("artifactId", "observed");
        item.put("kind", "test");
        item.put("attachmentId", uploaded.attachmentId());
        if (uploaded.seq() > 0) {
            // The first file is the default: a result that leaves the number out points at file 0.
            item.put("seq", uploaded.seq());
        }
        item.put("digest", uploaded.sha256());
        item.put("verified", true);
        item.put("note", "Uploaded with the artifact tools.");
        return item;
    }

    // --------------------------------------------------------------------------------------------------- as the admin page

    private Map<String, String> pageHeaders(byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "localhost:" + adminPort);
        if (body != null) {
            headers.put("Origin", adminOrigin);
            headers.put("Content-Type", "application/json");
            headers.put("Content-Length", Integer.toString(body.length));
        }
        return headers;
    }

    /**
     * The sentence patterns the admin page is sent for a language ({@code null}: none asked for, so English): the JSON of the page's {@code texts} element, by the whole key
     * ({@code admin.page.…}). The browser makes every sentence of the page from these.
     */
    JsonNode pageTexts(String language) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(Map.of("Host", "localhost:" + adminPort));
        if (language != null) {
            headers.put("Accept-Language", language);
        }
        String page = RawHttp.exchange(adminPort, "GET", "/", headers, null).text();
        java.util.regex.Matcher texts = java.util.regex.Pattern.compile("id=\"texts\"[^>]*>(.*?)</script>", java.util.regex.Pattern.DOTALL).matcher(page);
        assertTrue(texts.find(), "the page carries its sentences");
        return MAPPER.readTree(texts.group(1));
    }

    RawHttp.Response get(String path) throws IOException {
        return RawHttp.exchange(adminPort, "GET", path, pageHeaders(null), null);
    }

    /** As the page's own script would send it, with the given header changes (a null value removes one). */
    RawHttp.Response post(String path, byte[] body, Map<String, String> changes) throws IOException {
        Map<String, String> headers = pageHeaders(body);
        changes.forEach((name, value) -> {
            if (value == null) {
                headers.remove(name);
            } else {
                headers.put(name, value);
            }
        });
        return RawHttp.exchange(adminPort, "POST", path, headers, body);
    }

    RawHttp.Response post(String path, JsonNode body) throws IOException {
        return post(path, MAPPER.writeValueAsBytes(body), Map.of());
    }

    JsonNode state() throws IOException {
        return SessionServerSupport.json(get("/api/state"));
    }

    private static ObjectNode intentRequest(JsonNode change, boolean bootstrap) {
        ObjectNode request = MAPPER.createObjectNode();
        request.set("change", change);
        request.put("bootstrap", bootstrap);
        return request;
    }

    JsonNode prepare(JsonNode change, boolean bootstrap) throws IOException {
        RawHttp.Response prepared = post("/api/intent", intentRequest(change, bootstrap));
        assertEquals(200, prepared.status(), prepared.text());
        return SessionServerSupport.json(prepared);
    }

    /** Sends a commit for this intent with this assertion. */
    RawHttp.Response commit(String intent, JsonNode assertion, String requestId, String proposalId, String secret) throws IOException {
        return commit(intent, assertion, requestId, proposalId, secret, null);
    }

    /** The same, with the public key of a passkey the signed change registers (null for any other change). */
    RawHttp.Response commit(String intent, JsonNode assertion, String requestId, String proposalId, String secret, String publicKey) throws IOException {
        ObjectNode body = MAPPER.createObjectNode().put("intent", intent).put("requestId", requestId).put("proposalId", proposalId);
        body.set("assertion", assertion);
        if (secret != null) {
            body.put("secret", secret);
        }
        if (publicKey != null) {
            body.put("publicKey", publicKey);
        }
        return post("/api/commit", body);
    }

    /** What the page does for one change: prepare, sign with the passkey, commit. */
    JsonNode sign(Passkey key, JsonNode change, boolean bootstrap, String proposalId) throws Exception {
        JsonNode prepared = prepare(change, bootstrap);
        String intent = prepared.path("intent").asString();
        // The one key registers itself: its registration travels with the public key the signed change names by its fingerprint, as the page sends it.
        String publicKey = "KEY_ADD".equals(change.path("op").asString()) && key.credentialId.equals(change.path("credentialId").asString()) ? key.publicKey() : null;
        RawHttp.Response committed = commit(intent, key.assertion(intent, adminOrigin), UUID.randomUUID().toString(), proposalId, null, publicKey);
        assertEquals(200, committed.status(), committed.text());
        return SessionServerSupport.json(committed);
    }

    /**
     * What a restart over the same installation gives the second server: the passkeys the first one registered. The public keys are in the settings database of the state folder
     * (AGS3-D-0254) and the business log holds their fingerprints only, so a server that starts on a state folder of its own while the log is the installation's has a log that
     * names keys its registry does not hold, and the log fails its integrity check (ADMIN_RECORD_ALTERED) by design. A test that starts a second server beside the class's first
     * one cannot stop the first (its other tests use it) and cannot open its settings database a second time (H2 holds the file), so the registry of this server is given the keys
     * of the earlier one as they are, revoked ones as revoked.
     */
    void adoptPasskeysOf(ToolServerSupport earlier) {
        PasskeyRegistry from = earlier.bean(PasskeyRegistry.class);
        PasskeyRegistry into = bean(PasskeyRegistry.class);
        for (PasskeyCredential held : from.list()) {
            into.register(held.registration());
            if (held.state() == PasskeyState.REVOKED) {
                into.revoke(held.registration().credentialId());
            }
        }
    }

    static ObjectNode revokeChange(Passkey key) {
        return MAPPER.createObjectNode().put("op", "KEY_REVOKE").put("credentialId", key.credentialId);
    }

    JsonNode sign(Passkey key, JsonNode change) throws Exception {
        return sign(key, change, false, null);
    }

    /** Registers the first passkey when the page has none. */
    Passkey owner() throws Exception {
        Passkey key = new Passkey();
        if (!state().path("keys").isEmpty()) {
            throw new IllegalStateException("A test that registers the first passkey needs a server of its own.");
        }
        sign(key, key.addChange(), true, null);
        return key;
    }

    /** Approves an agent's pending request on the page with the key: the requested change, signed, naming the request. */
    void approve(Passkey key, String proposalId) throws Exception {
        for (JsonNode proposal : state().path("proposals")) {
            if (proposal.path("id").asString().equals(proposalId)) {
                sign(key, proposal.path("change"), false, proposalId);
                return;
            }
        }
        throw new AssertionError("No pending proposal " + proposalId);
    }

    static ObjectNode roleAdd(String roleId, String name) {
        return MAPPER.createObjectNode().put("op", "ROLE_ADD").put("roleId", roleId).put("name", name);
    }
}
