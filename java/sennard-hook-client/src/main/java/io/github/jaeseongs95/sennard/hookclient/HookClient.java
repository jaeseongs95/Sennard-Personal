// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.RefusalAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;
import io.github.jaeseongs95.sennard.hookclient.HookOptionException.Problem;

/**
 * The Sennard client of a host hook that can only be a command: it reads the hook's JSON on stdin, posts it to the server's hook endpoint and gives the host the answer in the
 * host's form. It is Java only and has no dependency but the JDK's {@code java.base}. It can start the installed Sennard jar when a session start finds no
 * server (see {@link ServerStart}) or when the accepted session start can run a native wake worker. The server's port is not in the definition: the server takes the one the operating system gives it at each start
 * and announces it in a file of its state folder ({@link ServerAddress}), which this client reads for every call. The request goes through the JDK's {@link HttpURLConnection}, not {@code java.net.http.HttpClient} and not a Spring
 * {@code RestClient}: this is a process the host starts for every hook, so what it loads at start is its cost. {@code HttpClient} builds the default SSL context when it is
 * created, which HttpURLConnection does not; nothing here needs a Spring context. The cost of each is not measured here (the release's hook connection check measures the hook).
 *
 * <p>What it sends is not the host's whole input but the part of it the server reads ({@link InputReducer}): a host that hands over a model request with the conversation so far
 * (Gemini CLI's {@code BeforeModel}) would otherwise send megabytes for the one field read of it. A host this client has no fields for is sent as it came.
 *
 * <p>What the host sees: the server's answer on stdout and exit code 0; or, when the server does not answer, the {@link FailureAnswer} its definition chose. The idle wake ends
 * differently (see {@link #wake}). The server's answer for a refused call (a status above 299) counts as no answer, except for a gate (a tool-call hook whose refusal stops the
 * tool, {@link HookClientCall#denyingRefusal}), which a status of 400 or above makes the client deny in the host's decision form; a server that is not there or does not answer in time
 * is no refusal, and the gate passes it through the failure answer like any hook. A reason for no answer is written as its code
 * ({@link Reason}): this program has no message source, so the sentence for a code is the server's and the documentation's.
 *
 * <p>A call with {@code --start-server} (the session start of each host) that finds no live server (no file, a file of a process that is gone, or a refused connection) starts the installed server first, waits for it to be ready, and
 * sends the event once ({@link #postStartingTheServer}); a start that fails or takes too long is no answer, so the host gets the failure answer as before and its session is not
 * held up beyond the wait the definition asked for. Nothing the start does reaches stdout.
 */
public final class HookClient {
    private static final System.Logger LOG = System.getLogger(HookClient.class.getName());
    /** Printed for the user or the model when the server is down; ASCII and free of quotes and backslashes, so it passes through every host's JSON unchanged. */
    public static final String GUIDANCE = "Sennard server is not reachable. Check with: java -jar <the Sennard jar in the install folder> status. After it is running, restart this session; tools do not attach later.";
    /** {@code {"decision":"allow"}}: the allow Antigravity's PreToolUse accepts. */
    public static final String ALLOW_DECISION_JSON = "{\"decision\":\"allow\"}";
    /** Antigravity's {@code injectSteps} carrying the guidance. */
    public static final String GUIDANCE_STEPS_JSON = "{\"injectSteps\":[{\"systemMessage\":{\"systemMessage\":\"" + GUIDANCE + "\"}}]}";
    /** Gemini CLI's {@code systemMessage} carrying the guidance. */
    public static final String GUIDANCE_SYSTEM_MESSAGE_JSON = "{\"systemMessage\":\"" + GUIDANCE + "\"}";
    /** The reason of a gate's deny, in two halves around the refusal's code; ASCII and free of quotes and backslashes, so it passes through every host's JSON unchanged. */
    private static final String DENY_REASON_START = "[Sennard] The Sennard server refused to check this tool call (";
    private static final String DENY_REASON_END = "), so the tool was not run. Try the same call again. If it keeps being refused, check the Sennard admin page or the server log.";
    private static final String DENY_FAILURE_START = "[Sennard] The Sennard hook could not verify this tool call (";
    private static final String DENY_FAILURE_END = "), so the tool was not run. Check the Sennard admin page or the server log.";
    /** How a hook's wait ends for the host when a message woke it: the exit code that wakes the session. */
    public static final int WAKE_EXIT = 2;
    private static final int TOOL_BLOCKED = 2;
    private static final int HOOK_FAILED = 1;
    /** How many processes above this one are sent: a shell between the host and this process is the usual depth, so eight leaves room. */
    private static final int MAX_ANCESTORS = 8;
    /** The server is on this computer's loopback address: it accepts at once or it is not there. */
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 2_000;

    private final Map<String, String> environment;
    private final ProcessHandle self;
    private final Path serverFile;
    private final ServerStart serverStart;
    private final Path installedJar;

    /**
     * Starts the installed server and returns when it is ready: what a session start asks for when no live server is announced. It fails with a {@link RuntimeException} when the
     * server is not installed, cannot be started or is not ready in time. The client program has no installer; it runs {@code java -jar <jar> start} ({@link JarServerStart}). Why
     * that is a process: the installer is Spring and the database's settings, which this program must not load, and the installed jar is the only thing that knows where the
     * installation is.
     */
    @FunctionalInterface
    public interface ServerStart {
        void startWithin(Duration within);
    }

    /**
     * @param environment the host's environment as the client received it
     * @param self the client's own process
     * @param serverFile where the server announces its address ({@link ServerAddress})
     * @param serverStart what a call with {@code --start-server} does when no live server is announced
     */
    public HookClient(Map<String, String> environment, ProcessHandle self, Path serverFile, ServerStart serverStart) {
        this(environment, self, serverFile, serverStart, null);
    }

    HookClient(Map<String, String> environment, ProcessHandle self, Path serverFile, ServerStart serverStart, Path installedJar) {
        this.environment = environment;
        this.self = self;
        this.serverFile = serverFile;
        this.serverStart = serverStart;
        this.installedJar = installedJar;
    }

    /** @return the exit code the host sees */
    public int run(HookClientCall call, InputStream stdin, OutputStream stdout, OutputStream stderr) throws IOException {
        if (call.onlyIfEnvironment() != null && !environmentMatches(call.onlyIfEnvironment())) {
            return 0;
        }
        byte[] body = stdin.readAllBytes();
        byte[] answer = null; // stays null when the server gave none
        HookNoAnswer noAnswer = null;
        try {
            answer = postStartingTheServer(call, reduced(call.label(), body));
        } catch (HookNoAnswer cause) {
            noAnswer = cause;
        } catch (IOException cause) {
            noAnswer = new HookNoAnswer(Reason.SERVER_NOT_REACHABLE, cause.getClass().getSimpleName(), cause);
        }
        if (call.wake()) {
            return wake(answer, stderr);
        }
        if (answer != null) {
            stdout.write(answer);
            return 0;
        }
        return noAnswered(call, stdout, stderr, noAnswer);
    }

    /**
     * What the host gets when the server gave no answer. A server that refused the call (a status of 400 or above) is told from one that is not there only for a call that asks to
     * deny a refusal (a gate, {@link HookClientCall#denyingRefusal}); no connection, no answer in time and no start are the failure answer's whatever the call asks. One complete
     * {@code switch} over the refusal answers.
     */
    private static int noAnswered(HookClientCall call, OutputStream stdout, OutputStream stderr, HookNoAnswer cause) throws IOException {
        RefusalAnswer refusal = cause.isRefusal() ? call.onRefusal() : RefusalAnswer.AS_FAILURE;
        return switch (refusal) {
            case AS_FAILURE -> fail(call.onFailure(), stdout, stderr, cause);
            case DENY_DECISION -> printed(stdout, deniedJson(DENY_REASON_START, cause, DENY_REASON_END));
        };
    }

    /** The client states the reason code when it has no decision from the server. */
    private static String deniedJson(String start, HookNoAnswer cause, String end) {
        return "{\"decision\":\"deny\",\"reason\":\"" + start + cause.getMessage() + end + "\"}";
    }

    private boolean environmentMatches(String condition) {
        int equals = condition.indexOf('=');
        return equals > 0 && condition.substring(equals + 1).equals(environment.get(condition.substring(0, equals)));
    }

    /**
     * For the AOT cache's training run only ({@link HookClientCall#WARM}): reads the file as the input of the host's hooks, as a hook of that host does, and drops what it
     * makes, so that one recorded run also loads the classes of that host's input. An input that cannot be read is refused as a hook's is. A label no host has fields for is
     * refused with its reason: a hook sends such a label's input as it is, but warming a host that is not there would train nothing and still end as a success, a training run
     * that quietly did not load what it was asked for.
     *
     * @throws HookOptionException when the label has no fields
     */
    public void warm(String label, Path input) throws IOException {
        if (fieldsOf(label).isEmpty()) {
            throw new HookOptionException(Problem.UNKNOWN_VALUE, HookClientCall.WARM, label);
        }
        reduced(label, Files.readAllBytes(input));
    }

    /**
     * {@link #warm(String, Path)} for the value of {@code --warm}, {@code <label>=<file>}.
     *
     * @throws HookOptionException when the value is not of that form, or the label has no fields
     */
    public void warm(String option) throws IOException {
        int equals = option.indexOf('=');
        if (equals <= 0) {
            throw new HookOptionException(Problem.OTHER, HookClientCall.WARM, option);
        }
        warm(option.substring(0, equals), Path.of(option.substring(equals + 1)));
    }

    /** The host's input as the server reads it: refused for the reasons the server refuses it, which is no answer, like its 400. A label without fields is sent as it came. */
    private static byte[] reduced(String label, byte[] body) throws IOException {
        Optional<InputFields> fields = fieldsOf(label);
        return fields.isEmpty() ? body : InputReducer.reduce(fields.get(), body);
    }

    /** The fields of the host's input; a file of the client's own jar that cannot be read is its own reason, not a server that did not answer. */
    private static Optional<InputFields> fieldsOf(String label) throws HookNoAnswer {
        try {
            return InputFields.of(label);
        } catch (IOException unreadable) {
            throw new HookNoAnswer(Reason.HOOK_FIELDS_UNREADABLE, label, unreadable);
        }
    }

    /**
     * The server's answer; when no live server is announced (or its port refuses the connection) and the call asks for it, the installed server is started first and the event is
     * sent once it is ready. Only that starts it: a server that accepts and answers slowly or with a refusal is running, and a timeout is not "not there". A start that fails
     * is no answer, with its failure as the cause, so the host gets the call's failure answer as it did before this start existed.
     */
    private byte[] postStartingTheServer(HookClientCall call, byte[] body) throws IOException {
        try {
            return post(call, body);
        } catch (ConnectException refused) {
            if (call.startServerSeconds() <= 0) {
                throw refused;
            }
            try {
                serverStart.startWithin(Duration.ofSeconds(call.startServerSeconds()));
            } catch (RuntimeException notStarted) {
                // Boundary (external process): the start of the installed server is the installer's, which fails with any RuntimeException (see ServerStart); the hook's answer is
                // the failure answer, with the cause kept.
                throw new HookNoAnswer(Reason.SERVER_NOT_STARTED, notStarted.getMessage() == null ? notStarted.getClass().getSimpleName() : notStarted.getMessage(), notStarted);
            }
            return post(call, body);
        }
    }

    /**
     * The body of the server's answer, when its status is 2xx; anything else is an {@link IOException}: no server, no answer in time, a refusal. The request is a
     * direct connection to the loopback address ({@link Proxy#NO_PROXY}: no proxy setting of the host's environment applies to it) with no redirect followed.
     */
    private byte[] post(HookClientCall call, byte[] body) throws IOException {
        // The port is read for every call: a server that was started again has another one. A file that is not there, or is of a process that has ended (or whose id another process
        // has now), is no server, and is the same "connection refused" as a port nobody listens on, so a session start that asked for it starts the server.
        ServerAddress address = ServerAddress.read(serverFile).filter(ServerAddress::live)
                .orElseThrow(() -> new ConnectException("no live server is announced in " + serverFile.getFileName()));
        URI uri = URI.create(HookEndpoints.url(address.baseUrl().toString(), call.label(), call.event(), call.module()));
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        Optional<NativeWakeLaunch> wakeLaunch = wakeLaunch(call, body.length);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(Math.toIntExact(call.timeoutSeconds() * 1000L));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            CallerTimeZone.from(environment).ifPresent(zone -> connection.setRequestProperty(HookHeaders.TIME_ZONE, zone));
            if (call.bindHost()) {
                List<Long> above = hostCandidates();
                if (!above.isEmpty()) {
                    connection.setRequestProperty(HookHeaders.HOST_PIDS, above.stream().map(String::valueOf).collect(Collectors.joining(",")));
                }
            }
            wakeLaunch.ifPresent(launch -> connection.setRequestProperty(NativeWakeProtocol.KEY, launch.key()));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw HookNoAnswer.refused(status);
            }
            byte[] answer;
            try (InputStream input = connection.getInputStream()) {
                answer = input.readAllBytes();
            }
            wakeLaunch.filter(launch -> launch.key().equals(connection.getHeaderField(NativeWakeProtocol.KEY)))
                    .ifPresent(launch -> startWake(launch, call, address, body));
            return answer;
        } finally {
            connection.disconnect();
        }
    }

    private static void startWake(NativeWakeLaunch launch, HookClientCall call, ServerAddress address, byte[] body) {
        try {
            launch.start(call, address, body);
        } catch (IOException | SecurityException unavailable) {
            // Preserve the successful hook and keep local paths and environment values out of diagnostics.
            LOG.log(System.Logger.Level.DEBUG, "Native wake worker could not start ({0})", unavailable.getClass().getSimpleName());
        }
    }

    private Optional<NativeWakeLaunch> wakeLaunch(HookClientCall call, int inputSize) {
        if (installedJar == null || !call.bindHost() || call.wake() || inputSize > NativeWakeProtocol.INPUT_LIMIT) {
            return Optional.empty();
        }
        try {
            return WakeEnvironment.forHost(call.label(), environment).map(values -> new NativeWakeLaunch(installedJar, values));
        } catch (IOException unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "Native wake environment could not be read ({0})", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * The idle wake: a message makes the server answer, and the client ends with exit code 2 and the message on stderr, which is what wakes the session.
     * Any other end (no server, a refusal, a timeout, no message) is exit code 0 with nothing printed and wakes nothing.
     */
    private static int wake(byte[] answer, OutputStream stderr) throws IOException {
        if (answer == null) {
            return 0;
        }
        stderr.write(answer);
        return WAKE_EXIT;
    }

    /** One complete {@code switch} over the failure answers: a new answer does not compile until it is handled here. */
    private static int fail(FailureAnswer answer, OutputStream stdout, OutputStream stderr, HookNoAnswer cause) throws IOException {
        return switch (answer) {
            case FAIL -> {
                stderr.write(("Sennard hook failed: " + cause.getMessage()).getBytes(StandardCharsets.UTF_8));
                yield HOOK_FAILED;
            }
            case BLOCK_STDERR -> {
                stderr.write((DENY_FAILURE_START + cause.getMessage() + DENY_FAILURE_END).getBytes(StandardCharsets.UTF_8));
                yield TOOL_BLOCKED;
            }
            case DENY_PRE_TOOL_USE -> printed(stdout, "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                    + "\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"" + DENY_FAILURE_START + cause.getMessage() + DENY_FAILURE_END + "\"}}");
            case GUIDANCE_STDERR -> {
                stderr.write(GUIDANCE.getBytes(StandardCharsets.UTF_8));
                yield WAKE_EXIT;
            }
            case GUIDANCE_STDOUT -> printed(stdout, GUIDANCE);
            case GUIDANCE_STEPS -> printed(stdout, GUIDANCE_STEPS_JSON);
            case ALLOW_DECISION -> printed(stdout, ALLOW_DECISION_JSON);
            case DENY_DECISION -> printed(stdout, deniedJson(DENY_FAILURE_START, cause, DENY_FAILURE_END));
            case GUIDANCE_SYSTEM_MESSAGE -> printed(stdout, GUIDANCE_SYSTEM_MESSAGE_JSON);
        };
    }

    private static int printed(OutputStream stdout, String text) throws IOException {
        stdout.write(text.getBytes(StandardCharsets.UTF_8));
        return 0;
    }

    /**
     * The processes above this one, nearest first, through the JDK's {@link ProcessHandle#parent()} alone. The host is among them, and which one it is
     * cannot be read here: between the host and the client there may be a shell the host starts for its hook, and the JDK gives no command line of another process on every OS
     * ({@code ProcessHandle.Info.commandLine()} is empty on Windows, measured). A process of that kind ends when the hook does and the host does not, so the server picks the host
     * by which of these is still running once the hook has returned (the decision is {@code HookSessions}').
     */
    List<Long> hostCandidates() {
        return Stream.iterate(self.parent(), Optional::isPresent, ancestor -> ancestor.flatMap(ProcessHandle::parent)).limit(MAX_ANCESTORS).flatMap(Optional::stream).map(ProcessHandle::pid).toList();
    }
}
