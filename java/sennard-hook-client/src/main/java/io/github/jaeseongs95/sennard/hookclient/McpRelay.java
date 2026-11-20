// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;

/**
 * The stdio MCP server of a host that cannot name an address for an HTTP one: the relay between the host's stdio and the Sennard server's MCP endpoint ({@code /mcp}). Every host
 * of Sennard registers the MCP server as this program because the server's port is not fixed: the server binds the port the operating system gives it at each start and announces
 * it in a file ({@link ServerAddress}), and no host's configuration file can name a port that is not known when it is written. A host's configuration names this program and the file,
 * and the relay finds the server for every request, so a server that was started again at another port is found as well. It has nothing but {@code java.base} (no Spring, no Jackson,
 * no SLF4J: {@code HookClientProcessTest} holds it to that), like the hook client, and it is the one of the two that stays for the whole session.
 *
 * <p>What it does is the stdio transport's framing ({@code modelcontextprotocol.io/specification/2025-06-18/basic/transports}): a JSON-RPC message per line of stdin, posted to the
 * endpoint with {@code Accept: application/json, text/event-stream}; the answer is one JSON message, or an event stream whose {@code data} are messages, and each is a line of stdout.
 * A message without an {@code id} is a notification: the server answers 202 and the relay writes nothing. It reads no more of a message than the top object's {@code id} and
 * {@code method} ({@link InputReducer#rawMembers}), and sends the message to the server as it came. The negotiated {@code protocolVersion} of an {@code initialize} answer is sent as
 * {@code MCP-Protocol-Version} on every request after it, which the specification asks of an HTTP client and the server's guard checks.
 *
 * <p>The server is started only by an {@code initialize} that finds none ({@code --start-server}; once for a session), the point where a host begins to use the server, as a
 * hook's session start does. Any other request with no live server is answered as a JSON-RPC error with the request's own id, whose message is the code of the reason
 * ({@link HookNoAnswer}): the relay does not start a server for every request, and does not wait for one. The requests are sent at the same time (a tool that runs for long does not hold
 * the ones behind it), and the relay ends when stdin does, after the ones in progress are answered.
 */
public final class McpRelay {
    /** The server's MCP endpoint, a path of its own address. */
    static final String ENDPOINT = "/mcp";
    /** The header that says which host the request is of: the server's {@code McpConfiguration} reads it as the host's label. */
    static final String HOST_LABEL_HEADER = "Sennard-Host-Label";
    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    /** JSON-RPC's code for a message that is not JSON (the id is null: it cannot be known). */
    static final int PARSE_ERROR = -32700;
    /** A code from JSON-RPC's range for the implementation's own errors: the server could not be reached, started or understood. */
    static final int SERVER_ERROR = -32000;
    /** The server is on this computer's loopback address: it accepts at once or it is not there. */
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 2_000;
    /**
     * How long the relay waits for the server's answer to one request. It is the bound for a server that accepts and never answers, not the time a request is expected to take: a
     * tool may run for long, and the host applies its own limit to a tool call, which is the shorter. No measured time stands behind it.
     */
    static final Duration REQUEST_LIMIT = Duration.ofMinutes(10);
    private static final Set<String> ENVELOPE = Set.of("id", "method");
    private static final String INITIALIZE = "\"initialize\"";

    private final Map<String, String> environment;
    private final McpRelayArguments.Parsed arguments;
    private final HookClient.ServerStart serverStart;
    private volatile String protocolVersion; // null until an initialize answer names it

    /**
     * @param environment the host's environment as the relay received it
     * @param arguments what the relay's command line says
     * @param serverStart what an {@code initialize} does when no live server is announced and the relay was asked to start one
     */
    public McpRelay(Map<String, String> environment, McpRelayArguments.Parsed arguments, HookClient.ServerStart serverStart) {
        this.environment = environment;
        this.arguments = arguments;
        this.serverStart = serverStart;
    }

    /**
     * Relays until stdin ends.
     *
     * @return the exit code: 0
     */
    public int run(InputStream stdin, OutputStream stdout, OutputStream stderr) throws IOException {
        Lines lines = new Lines(stdout, stderr);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        // Closing the executor waits for the requests in progress, so the answers of the last requests are written before the process ends.
        try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    String message = line;
                    requests.execute(() -> relay(message, lines));
                }
            }
        }
        return 0;
    }

    /** One message of the host: sent to the server, and its answers written. A request the relay cannot deliver is answered here, with its id. */
    private void relay(String message, Lines lines) {
        Map<String, String> envelope;
        try {
            envelope = InputReducer.rawMembers(message, ENVELOPE);
        } catch (HookNoAnswer notJson) {
            lines.answer(error("null", PARSE_ERROR, notJson));
            return;
        }
        String id = envelope.get("id");
        boolean request = id != null && !"null".equals(id) && envelope.containsKey("method");
        try {
            List<String> answers = post(message, request && INITIALIZE.equals(envelope.get("method")));
            if (request) {
                if (INITIALIZE.equals(envelope.get("method"))) {
                    for (String answer : answers) {
                        rememberProtocolVersion(answer);
                    }
                }
                answers.forEach(lines::answer);
            }
        } catch (HookNoAnswer noAnswer) {
            reportFailure(request, id, noAnswer, lines);
        } catch (IOException failed) {
            reportFailure(request, id, new HookNoAnswer(Reason.SERVER_NOT_REACHABLE, failed.getClass().getSimpleName(), failed), lines);
        }
    }

    /** A request that got no answer from the server is answered with the reason; a notification has nobody to answer to, so the reason goes to stderr, where a host shows its MCP server's log. */
    private static void reportFailure(boolean request, String id, HookNoAnswer noAnswer, Lines lines) {
        if (request) {
            lines.answer(error(id, SERVER_ERROR, noAnswer));
        } else {
            lines.log("Sennard relay: " + noAnswer.getMessage());
        }
    }

    /** The messages the server answered with, for a message the relay posts. */
    private List<String> post(String message, boolean mayStartServer) throws IOException {
        ServerAddress address = address(mayStartServer);
        HttpURLConnection connection = (HttpURLConnection) address.baseUrl().resolve(ENDPOINT).toURL().openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(Math.toIntExact(REQUEST_LIMIT.toMillis()));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty(HOST_LABEL_HEADER, arguments.host());
            if (protocolVersion != null) {
                connection.setRequestProperty(PROTOCOL_VERSION_HEADER, protocolVersion);
            }
            CallerTimeZone.from(environment).ifPresent(zone -> connection.setRequestProperty(HookHeaders.TIME_ZONE, zone));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_ACCEPTED || status == HttpURLConnection.HTTP_NO_CONTENT) {
                return List.of();
            }
            if (status / 100 != 2) {
                throw HookNoAnswer.refused(status);
            }
            String type = connection.getContentType() == null ? "" : connection.getContentType().toLowerCase(Locale.ROOT);
            String answer;
            try (InputStream in = connection.getInputStream()) {
                answer = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (type.startsWith("application/json")) {
                return answer.isBlank() ? List.of() : List.of(oneLine(answer.strip()));
            }
            if (type.startsWith("text/event-stream")) {
                return serverSentMessages(answer);
            }
            throw new HookNoAnswer(Reason.MCP_ANSWER_UNREADABLE, type.isEmpty() ? "no-content-type" : type.replaceAll("[^a-z0-9/.+-]", "?"));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The live server's address. When none is announced, an {@code initialize} that the relay may start a server for starts it and waits ({@code --start-server}); any other
     * message does not.
     */
    private ServerAddress address(boolean mayStartServer) throws HookNoAnswer {
        Optional<ServerAddress> live = ServerAddress.read(arguments.serverFile()).filter(ServerAddress::live);
        if (live.isPresent()) {
            return live.get();
        }
        if (!mayStartServer || arguments.startServerSeconds() <= 0) {
            throw new HookNoAnswer(Reason.SERVER_NOT_REACHABLE, "NoLiveServerAnnounced");
        }
        try {
            serverStart.startWithin(Duration.ofSeconds(arguments.startServerSeconds()));
        } catch (RuntimeException notStarted) {
            // Boundary (external process): the start of the installed server is the installer's and fails with any RuntimeException (see HookClient.ServerStart); the initialize is
            // answered with its message as the reason, and the cause is kept.
            throw new HookNoAnswer(Reason.SERVER_NOT_STARTED, notStarted.getMessage() == null ? notStarted.getClass().getSimpleName() : notStarted.getMessage(), notStarted);
        }
        return ServerAddress.read(arguments.serverFile()).filter(ServerAddress::live).orElseThrow(() -> new HookNoAnswer(Reason.SERVER_NOT_STARTED, "NoLiveServerAnnounced"));
    }

    /**
     * The {@code protocolVersion} the server answered an {@code initialize} with, to send on the requests after it.
     *
     * @throws HookNoAnswer {@link Reason#MCP_ANSWER_UNREADABLE} when an answer to an {@code initialize} is not a JSON object, or its {@code result} is not one: the server did not
     *         answer the way MCP does, and the host is told so, not given the text
     */
    private void rememberProtocolVersion(String answer) throws HookNoAnswer {
        try {
            String result = InputReducer.rawMembers(answer, Set.of("result")).get("result");
            String version = result == null || result.charAt(0) != '{' ? null : InputReducer.rawMembers(result, Set.of("protocolVersion")).get("protocolVersion");
            // A version is a date as a JSON string; anything else is not a version the server's guard would accept, so it is not sent.
            if (version != null && version.matches("\"[0-9-]+\"")) {
                protocolVersion = version.substring(1, version.length() - 1);
            }
        } catch (HookNoAnswer notAnObject) {
            throw new HookNoAnswer(Reason.MCP_ANSWER_UNREADABLE, "initialize-answer-is-not-json", notAnObject);
        }
    }

    /**
     * The messages of an event stream, in order: the {@code data} lines of each event joined, an event ending at a blank line (the stream's end ends the last). The other fields of an
     * event ({@code event}, {@code id}, {@code retry}) and comments are not messages.
     */
    static List<String> serverSentMessages(String stream) {
        List<String> messages = new ArrayList<>();
        List<String> data = new ArrayList<>();
        for (String line : stream.lines().toList()) {
            if (line.isEmpty()) {
                flush(data, messages);
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        flush(data, messages);
        return messages;
    }

    private static void flush(List<String> data, List<String> messages) {
        if (!data.isEmpty()) {
            messages.add(oneLine(String.join("\n", data)));
            data.clear();
        }
    }

    /** A message is one line of stdout. The line ends in JSON are white space (a raw line end cannot be inside a JSON string), so they become spaces without changing the message. */
    private static String oneLine(String json) {
        return json.replace('\r', ' ').replace('\n', ' ');
    }

    /** The error answer of a request that got no answer: its message is the reason's code ({@link HookNoAnswer#getMessage()}). */
    static String error(String id, int code, HookNoAnswer noAnswer) {
        return error(id, code, noAnswer.getMessage());
    }

    /** {@code {"jsonrpc":"2.0","id":<id>,"error":{"code":<code>,"message":"<message>"}}}; the id is the JSON text of the request's. */
    static String error(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":" + quoted(message) + "}}";
    }

    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                default -> {
                    if (c < ' ') {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    /** What the relay writes: the messages to stdout, one line each, and its own words to stderr. Requests are answered from several threads, so a line is written whole. */
    private static final class Lines {
        private final OutputStream stdout;
        private final OutputStream stderr;

        Lines(OutputStream stdout, OutputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        /** The stdio transport ends a message with a line feed, whatever the operating system ("\n": the transport's own framing, not the platform's line separator). */
        synchronized void answer(String message) {
            write(stdout, message + "\n");
        }

        synchronized void log(String text) {
            write(stderr, text + "\n");
        }

        private static void write(OutputStream stream, String text) {
            try {
                stream.write(text.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            } catch (IOException closed) {
                throw new UncheckedIOException("The host closed the relay's output", closed);
            }
        }
    }
}
