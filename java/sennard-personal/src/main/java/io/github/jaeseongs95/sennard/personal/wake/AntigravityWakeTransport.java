// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;
import tools.jackson.databind.json.JsonMapper;

/** Uses agentapi for the host's interactive root and ACP for conversations started by the worker. */
public final class AntigravityWakeTransport implements NativeWakeAdapter {
    public static final class Provider implements NativeWakeProvider {
        @Override
        public HostDefinition definition() {
            return new AntigravityHost();
        }

        @Override
        public NativeWakeAdapter adapter(WakeProcess process, JsonMapper mapper) {
            return new AntigravityWakeTransport(process, mapper);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(AntigravityWakeTransport.class);
    private static final Set<String> CHILD_FIELDS = Set.of("ANTIGRAVITY_LS_ADDRESS", "ANTIGRAVITY_CSRF_TOKEN", "ANTIGRAVITY_PROJECT_ID",
            "ANTIGRAVITY_CONVERSATION_ID", "PATH", "Path", "HOME", "USERPROFILE", "SystemRoot", "TEMP", "TMP", "LANG");
    private static final Set<String> ACP_FIELDS = Set.of("PATH", "Path", "HOME", "USERPROFILE", "SystemRoot", "TEMP", "TMP", "LANG",
            "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME");
    private static final Pattern ENDPOINT = Pattern.compile("(localhost|127(?:\\.[0-9]{1,3}){3}|\\[::1\\]):([0-9]{1,5})");
    private final WakeProcess process;
    private final JsonMapper mapper;

    public AntigravityWakeTransport(WakeProcess process) {
        this(process, JsonMapper.builder().build());
    }

    private AntigravityWakeTransport(WakeProcess process, JsonMapper mapper) {
        this.process = process;
        this.mapper = mapper;
    }

    @Override
    public Availability connect(Map<String, String> exposed, Target target) {
        if (!value(exposed.get("ANTIGRAVITY_PROJECT_ID"))) {
            return new Availability.Missing("AGY_PROJECT_ID_MISSING");
        }
        if (!loopback(exposed.get("ANTIGRAVITY_LS_ADDRESS"))) {
            return new Availability.Missing("AGY_LS_ADDRESS_INVALID");
        }
        if (!value(exposed.get("ANTIGRAVITY_CSRF_TOKEN"))) {
            return new Availability.Missing("AGY_CSRF_TOKEN_MISSING");
        }
        String conversation = exposed.get("ANTIGRAVITY_CONVERSATION_ID");
        if (conversation != null && !value(conversation)) {
            return new Availability.Missing("AGY_CONVERSATION_ID_INVALID");
        }
        if (target == null || !value(target.hostSessionId()) || target.hostSessionId().startsWith("-")) {
            return new Availability.Missing("AGY_SESSION_TARGET_INVALID");
        }
        if (conversation != null && !conversation.equals(target.hostSessionId())) {
            return new Availability.Missing("AGY_CONVERSATION_MISMATCH");
        }
        try {
            Optional<Path> executable = executable(exposed);
            if (executable.isEmpty()) {
                return new Availability.Missing("AGY_NATIVE_EXECUTABLE_MISSING");
            }
            Map<String, String> child = exposed.entrySet().stream().filter(entry -> CHILD_FIELDS.contains(entry.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            // This immutable environment/project belongs to the SessionStarted target, not a later caller or server environment.
            return new Availability.Ready((session, marker) -> target.hostSessionId().equals(session)
                    ? send(executable.orElseThrow(), child, session, marker) : new Outcome.NotSent("AGY_CONVERSATION_MISMATCH"));
        } catch (InvalidPathException invalid) {
            LOG.warn("AGY_NATIVE_EXECUTABLE_INVALID", SafeFailure.capture(invalid));
            return new Availability.Missing("AGY_NATIVE_EXECUTABLE_INVALID");
        }
    }

    @Override
    public Optional<ManagedLauncher> managedLauncher(Map<String, String> exposed, Target target) {
        if (target == null || !NativeWakeProtocol.validConversation(target.hostSessionId()) || target.workspace() == null
                || !target.hostSessionId().equals(exposed.getOrDefault("ANTIGRAVITY_CONVERSATION_ID", target.hostSessionId()))) {
            return Optional.empty();
        }
        try {
            Path workspace = Path.of(target.workspace());
            if (!workspace.isAbsolute() || !Files.isDirectory(workspace)) {
                return Optional.empty();
            }
            Map<String, String> child = exposed.entrySet().stream().filter(entry -> ACP_FIELDS.contains(entry.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            return executable(exposed).map(binary -> launch -> AntigravityManagedConversation.open(binary, workspace, child, mapper, launch));
        } catch (InvalidPathException invalid) {
            LOG.warn("AGY_MANAGED_EXECUTABLE_INVALID", SafeFailure.capture(invalid));
            return Optional.empty();
        }
    }

    private Outcome send(Path executable, Map<String, String> environment, String session, String marker) {
        if (Thread.currentThread().isInterrupted()) {
            return new Outcome.NotSent("AGY_SEND_CANCELLED_BEFORE_START");
        }
        try {
            return switch (process.run(request(executable, environment, List.of("send-message", session, marker), 10))) {
                case ToolProcess.Finished finished when finished.exitCode() == 0 -> new Outcome.Submitted();
                case ToolProcess.Finished ignored -> new Outcome.Unknown("AGY_SEND_NONZERO");
                case ToolProcess.TimedOut ignored -> new Outcome.Unknown("AGY_SEND_TIMED_OUT");
                case ToolProcess.Overflowed ignored -> new Outcome.Unknown("AGY_SEND_OUTPUT_LIMIT");
            };
        } catch (WakeProcess.Failure failed) {
            LOG.warn("AGY_SEND_FAILED", SafeFailure.capture(failed));
            return failed.phase() == WakeProcess.Phase.STARTED ? new Outcome.Unknown("AGY_SEND_IO_AFTER_START") : new Outcome.NotSent("AGY_SEND_START_FAILED");
        } catch (IOException beforeStart) {
            LOG.warn("AGY_SEND_PREPARATION_FAILED", SafeFailure.capture(beforeStart));
            return new Outcome.NotSent("AGY_SEND_PREPARATION_FAILED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome.Unknown("AGY_SEND_CANCELLED");
        }
    }

    private static ToolProcess.Request request(Path executable, Map<String, String> environment, List<String> arguments, int seconds) {
        var command = new java.util.ArrayList<>(List.of(executable.toString(), "agentapi"));
        command.addAll(arguments);
        // Native send output is bounded and never exported to server logs.
        return ToolProcess.Request.of(command, ToolProcess.Stderr.DISCARD, Duration.ofSeconds(seconds)).limitedTo(65536)
                .withoutInheritedEnvironment().withEnvironment(environment);
    }

    private static Optional<Path> executable(Map<String, String> exposed) {
        String configured = exposed.get("ANTIGRAVITY_AGENTAPI_EXE");
        if (configured != null) {
            Path candidate = Path.of(configured);
            return candidate.isAbsolute() && nativeFile(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        for (String entry : exposed.getOrDefault("PATH", exposed.getOrDefault("Path", "")).split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path directory = Path.of(entry);
            if (!directory.isAbsolute()) continue;
            for (String name : List.of(AntigravityHost.ID.label(), AntigravityHost.ID.label() + ".exe")) {
                Path candidate = directory.resolve(name);
                if (nativeFile(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean nativeFile(Path path) {
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1")) return false;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            if (header.length != 4) return false;
            int magic = java.nio.ByteBuffer.wrap(header).getInt();
            return header[0] == 'M' && header[1] == 'Z' || magic == 0x7f454c46 || magic == 0xfeedface || magic == 0xcefaedfe
                    || magic == 0xfeedfacf || magic == 0xcffaedfe || magic == 0xcafebabe || magic == 0xbebafeca;
        } catch (IOException unreadable) {
            LOG.warn("AGY_NATIVE_EXECUTABLE_UNREADABLE", SafeFailure.capture(unreadable));
            return false;
        }
    }

    private static boolean loopback(String address) {
        if (address == null) return false;
        var match = ENDPOINT.matcher(address);
        if (!match.matches()) return false;
        if (match.group(1).startsWith("127.")) {
            for (String octet : match.group(1).split("\\.")) {
                if (octet.length() > 1 && octet.startsWith("0") || Integer.parseInt(octet) > 255) return false;
            }
        }
        int port = Integer.parseInt(match.group(2));
        return port > 0 && port <= 65535;
    }

    private static boolean value(String value) {
        return value != null && !value.isBlank() && value.chars().noneMatch(Character::isISOControl);
    }
}
