// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final NativeWakeSend.Diagnostics SEND_DIAGNOSTICS = new NativeWakeSend.Diagnostics(
            "AGY_SEND_NONZERO", "AGY_SEND_TIMED_OUT", "AGY_SEND_OUTPUT_LIMIT", "AGY_SEND_IO_AFTER_START",
            "AGY_SEND_START_FAILED", "AGY_SEND_PREPARATION_FAILED", "AGY_SEND_CANCELLED",
            "AGY_SEND_FAILED", "AGY_SEND_PREPARATION_FAILED");
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
            Map<String, String> child = NativeWakeSend.selectEnvironment(exposed, CHILD_FIELDS);
            // This immutable environment/project belongs to the SessionStarted target, not a later caller or server environment.
            return NativeWakeSend.boundTo(target.hostSessionId(), "AGY_CONVERSATION_MISMATCH",
                    (session, marker) -> send(executable.orElseThrow(), child, session, marker));
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
            var directory = ManagedWakeWorkspace.existingDirectory(target.workspace());
            if (directory.isEmpty()) {
                return Optional.empty();
            }
            Path workspace = directory.orElseThrow();
            Map<String, String> child = NativeWakeSend.selectEnvironment(exposed, ACP_FIELDS);
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
        ToolProcess.Request request = NativeWakeSend.request(
                List.of(executable.toString(), "agentapi", "send-message", session, marker),
                ToolProcess.Stderr.DISCARD, environment);
        return NativeWakeSend.run(process, request, LOG, SEND_DIAGNOSTICS);
    }

    private static Optional<Path> executable(Map<String, String> exposed) {
        String configured = exposed.get("ANTIGRAVITY_AGENTAPI_EXE");
        if (configured != null) {
            Path candidate = Path.of(configured);
            return nativeFile(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        return NativeLauncher.onPath(exposed, AntigravityHost.ID.label(), AntigravityWakeTransport::nativeFile);
    }

    private static boolean nativeFile(Path path) {
        return NativeLauncher.eligible(path, NativeLauncher.ScriptPolicy.NATIVE_ONLY,
                unreadable -> LOG.warn("AGY_NATIVE_EXECUTABLE_UNREADABLE", SafeFailure.capture(unreadable)));
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
