// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a hook definition asks of the Sennard hook client when a host can only run a command: {@code hook <label> <event> [options]}. The definition's
 * generator (an adapter, in the server) and the client's own option reader ({@link HookClientArguments}) both read this one description, so the words cannot
 * drift apart.
 *
 * @param label the host label of the endpoint
 * @param event the event name of the endpoint
 * @param module the endpoint's module, or null
 * @param onFailure what the client answers the host when the server cannot be reached or refuses
 * @param timeoutSeconds how long the client waits for the server's answer
 * @param bindHost whether the client sends the process ids above it, among them the host's (a session start does, so the server can watch the host end)
 * @param wake the call is the idle wake: it waits for a message and answers the host only with one
 * @param onlyIfEnvironment {@code NAME=VALUE}: the call does nothing unless the host's environment has that value, or null
 * @param startServerSeconds how long the client waits for the server to be ready after starting the installed one when no live server is announced
 *        ({@link ServerAddress}): 0 starts nothing. Only a session start asks for it, so that a call made for every model request or tool never starts a server
 * @param onRefusal what the client answers the host when the server answers with a status of 400 or above: a hook that is a gate (a tool-call hook whose refusal stops the tool)
 *        asks for {@link RefusalAnswer#DENY_DECISION}; every other hook leaves {@link RefusalAnswer#AS_FAILURE}, where a refusal is no answer like any other
 */
public record HookClientCall(String label, String event, String module, FailureAnswer onFailure, int timeoutSeconds, boolean bindHost, boolean wake,
        String onlyIfEnvironment, int startServerSeconds, RefusalAnswer onRefusal) {
    public static final String COMMAND = "hook";
    public static final String ON_REFUSAL = "--on-refusal";
    public static final String MODULE = "--module";
    public static final String TIMEOUT = "--timeout";
    public static final String ON_FAILURE = "--on-failure";
    public static final String BIND_HOST = "--bind-host";
    public static final String START_SERVER = "--start-server";
    public static final String WAKE = "--wake";
    public static final String ONLY_IF_ENVIRONMENT = "--only-if-env";
    /**
     * {@code --warm=<host label>=<file>}: for the AOT cache's training run only, not something a host's definition asks for (it is not a component of this record and
     * {@link #arguments} never writes it). Before the call, the client reads the file as that host's input and drops what it makes, so the one recorded run also loads
     * the classes of a second host's input; a JVM records one run, and the hosts that can only run a command each have their own fields.
     */
    public static final String WARM = "--warm";
    /**
     * {@code --server-file=<path>}: the file where the server announces its address ({@link ServerAddress}, {@code <state folder>/server.properties}). There is no port to write in
     * a definition because the server does not have one of its own choosing: it binds the port the operating system gives it at each start, and the client reads it from this file
     * for every call. It is the installation's, not the call's: it is not a component of this record, {@link #arguments} never writes it, and the installation's command line
     * carries it (the client program has no way to find the installation itself). Every client of the server takes it, the MCP relay ({@code McpRelayArguments}) too.
     */
    public static final String SERVER_FILE = "--server-file";
    /**
     * {@code --jar=<path>}: the installed Sennard jar ({@code <install folder>/versions/<version>/sennard-<version>.jar}), which a call with {@link #START_SERVER} runs
     * ({@code java -jar <jar> start --within=<seconds>}) when no live server is announced. The installation's, like {@link #SERVER_FILE}: not a component of this record, and
     * never written by {@link #arguments}. The client has no launcher program to run instead because the release is the one jar and the user's own Java.
     */
    public static final String JAR = "--jar";
    /** The command of the jar that the client runs to start the server ({@code java -jar <jar> start --within=<seconds>}): written here so that the jar's command and the client agree on its words. */
    public static final String START_COMMAND = "start";
    /** The option of {@link #START_COMMAND} that makes it wait for the server to be ready, as a host's session start does, and print nothing. */
    public static final String START_WITHIN = "--within";
    public static final int DEFAULT_TIMEOUT_SECONDS = 9;
    /**
     * How long a session start waits for a server it started to be ready, in seconds. It is the bound for a process that never gets there, not the time a start is
     * expected to take, and no measured time stands behind it. The server's start and its store preparation vary with the computer, so the bound is generous: long enough
     * that a slow start is not cut off every time (a server that is cut off is not killed, but a session that always gave up before it was ready would never use it), and
     * short enough that a session whose server cannot start is not held up for long. {@code SESSION_START_LIMIT_SECONDS} is twice this wait. The time a start really takes
     * is measured at the release's hook connection check (HookStartsServerProcessTest logs it), not written here.
     */
    public static final int START_SERVER_SECONDS = 30;
    /**
     * The limit a definition gives the host for a session start that may start the server, in seconds: the wait, the client's own start and the event's request fit
     * in it. It is the hosts' own documented default for a command hook where that is the smallest generous one (Gemini CLI: 60000 ms; Claude Code and Codex: 600 s;
     * Antigravity: 30 s, which the definition's explicit {@code timeout} replaces), and twice the wait.
     */
    public static final int SESSION_START_LIMIT_SECONDS = 60;

    /** What the client prints and exits with when the server does not answer; the host's own form of "Sennard is down". */
    public enum FailureAnswer {
        /** Nothing printed to the host but a reason code on stderr; exit 1 (a host treats it as a failed hook, as it did a failed curl). */
        FAIL("fail"),
        /** A tool denial with a reason on stderr and exit 2. */
        BLOCK_STDERR("block-stderr"),
        /** A nested {@code PreToolUse} permission denial with its reason on stdout and exit 0. */
        DENY_PRE_TOOL_USE("deny-pre-tool-use"),
        /** The guidance on stderr and exit 2, which Claude Code shows to the user at session start without blocking anything. */
        GUIDANCE_STDERR("guidance-stderr"),
        /** The guidance on stdout and exit 0, which Codex takes as the session's context. */
        GUIDANCE_STDOUT("guidance-stdout"),
        /** Antigravity's {@code injectSteps} with the guidance, exit 0. */
        GUIDANCE_STEPS("guidance-steps"),
        /** {@code {"decision":"allow"}}, exit 0. */
        ALLOW_DECISION("allow-decision"),
        /** {@code {"decision":"deny","reason":"..."}}, exit 0. */
        DENY_DECISION("deny-decision"),
        /** A JSON denial that replaces a finished tool's result while preserving the hook failure reason. */
        DENY_RESULT("deny-result"),
        /** Gemini CLI's {@code systemMessage} with the guidance, exit 0. */
        GUIDANCE_SYSTEM_MESSAGE("guidance-system-message");

        private final String option;

        FailureAnswer(String option) {
            this.option = option;
        }

        public String option() {
            return option;
        }

        public static Optional<FailureAnswer> ofOption(String text) {
            for (FailureAnswer answer : values()) {
                if (answer.option.equals(text)) {
                    return Optional.of(answer);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What the client prints and exits with when the server answers with a status of 400 or above. Not being there (no connection, no answer in time, no start) is never a
     * refusal and uses the configured {@link FailureAnswer}.
     */
    public enum RefusalAnswer {
        /** A refusal is no answer like any other: the {@link FailureAnswer} is what the host gets. */
        AS_FAILURE("as-failure"),
        /** {@code {"decision":"deny","reason":"..."}} and exit 0: the one deny that Antigravity's PreToolUse and Gemini CLI's BeforeTool both read. */
        DENY_DECISION("deny-decision");

        private final String option;

        RefusalAnswer(String option) {
            this.option = option;
        }

        public String option() {
            return option;
        }

        public static Optional<RefusalAnswer> ofOption(String text) {
            for (RefusalAnswer answer : values()) {
                if (answer.option.equals(text)) {
                    return Optional.of(answer);
                }
            }
            return Optional.empty();
        }
    }

    /** A hook the host waits for at the default time, which only the failure answer sets apart. */
    public static HookClientCall of(String label, String event, FailureAnswer onFailure) {
        return new HookClientCall(label, event, null, onFailure, DEFAULT_TIMEOUT_SECONDS, false, false, null, 0, RefusalAnswer.AS_FAILURE);
    }

    /** A call of the endpoint of a module of the event ({@code /hooks/v1/<label>/<event>/<module>}), such as the governance gate that only a Sennard tool's call goes through. */
    public HookClientCall inModule(String module) {
        return new HookClientCall(label, event, module, onFailure, timeoutSeconds, bindHost, wake, onlyIfEnvironment, startServerSeconds, onRefusal);
    }

    public HookClientCall bindingHost() {
        return new HookClientCall(label, event, module, onFailure, timeoutSeconds, true, wake, onlyIfEnvironment, startServerSeconds, onRefusal);
    }

    public HookClientCall withTimeout(int seconds) {
        return new HookClientCall(label, event, module, onFailure, seconds, bindHost, wake, onlyIfEnvironment, startServerSeconds, onRefusal);
    }

    /** A call that starts the installed server when nothing listens, and waits up to {@code seconds} for it to be ready, before it sends the event. */
    public HookClientCall startingServer(int seconds) {
        return new HookClientCall(label, event, module, onFailure, timeoutSeconds, bindHost, wake, onlyIfEnvironment, seconds, onRefusal);
    }

    /** A gate: a server that refuses the call (a status of 400 or above) makes the client deny the tool in the host's decision form, where a server that is not there still gets the failure answer. */
    public HookClientCall denyingRefusal() {
        return new HookClientCall(label, event, module, onFailure, timeoutSeconds, bindHost, wake, onlyIfEnvironment, startServerSeconds, RefusalAnswer.DENY_DECISION);
    }

    /** The idle wake of an event: it waits for a day, a minute above the server's hold, and only where the environment says the session is attended. */
    public HookClientCall asWake(int seconds, String environmentCondition) {
        return new HookClientCall(label, event, HookEndpoints.WAKE_MODULE, FailureAnswer.FAIL, seconds, false, true, environmentCondition, 0, RefusalAnswer.AS_FAILURE);
    }

    /** Every argument is free of spaces, so a host that splits a command string at spaces and one that takes an argument list read the same words. */
    public List<String> arguments() {
        List<String> arguments = new ArrayList<>(List.of(COMMAND, label, event));
        if (module != null) {
            arguments.add(MODULE + "=" + module);
        }
        if (timeoutSeconds != DEFAULT_TIMEOUT_SECONDS) {
            arguments.add(TIMEOUT + "=" + timeoutSeconds);
        }
        if (onFailure != FailureAnswer.FAIL) {
            arguments.add(ON_FAILURE + "=" + onFailure.option());
        }
        if (onRefusal != RefusalAnswer.AS_FAILURE) {
            arguments.add(ON_REFUSAL + "=" + onRefusal.option());
        }
        if (bindHost) {
            arguments.add(BIND_HOST);
        }
        if (startServerSeconds > 0) {
            arguments.add(START_SERVER + "=" + startServerSeconds);
        }
        if (wake) {
            arguments.add(WAKE);
        }
        if (onlyIfEnvironment != null) {
            arguments.add(ONLY_IF_ENVIRONMENT + "=" + onlyIfEnvironment);
        }
        return arguments;
    }
}
