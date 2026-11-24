// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;
import tools.jackson.databind.json.JsonMapper;

/** Only the native Codex CLI can enqueue input on the host's live thread; Java has no equivalent host API. No shell, npm wrapper, resume flag or user-specific path. */
public final class CodexWakeTransport implements NativeWakeAdapter {
    public static final class Provider implements NativeWakeProvider {
        @Override
        public HostDefinition definition() {
            return new CodexHost();
        }

        @Override
        public NativeWakeAdapter adapter(WakeProcess process, JsonMapper mapper) {
            return new CodexWakeTransport(process, mapper);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CodexWakeTransport.class);
    private static final NativeWakeSend.Diagnostics SEND_DIAGNOSTICS = new NativeWakeSend.Diagnostics(
            "CLI_NONZERO", "TIMED_OUT", "OUTPUT_LIMIT", "IO_AFTER_START", "START_FAILED",
            "START_PREPARATION_FAILED", "CANCELLED", "CODEX_WAKE_SEND_FAILED", "CODEX_WAKE_SEND_PREPARATION_FAILED");
    private static final Set<String> NATIVE_FIELDS = Set.of("PATH", "Path", "CODEX_MANAGED_PACKAGE_ROOT", "CODEX_HOME",
            "SystemRoot", "HOME", "USERPROFILE", "TEMP", "TMP", "LANG");
    private final WakeProcess process;
    private final JsonMapper mapper;
    private final CodexQueueWakeSettings configuration;

    public CodexWakeTransport(WakeProcess process, JsonMapper mapper) {
        this.process = process;
        this.mapper = mapper;
        this.configuration = new CodexQueueWakeSettings(mapper);
    }

    @Override
    public Availability connect(Map<String, String> environment, Target target) {
        try {
            String override = environment.get(CodexQueueWakeSettings.OVERRIDE);
            if (override != null && !override.equals("0") && !override.equals("1")) {
                return new Availability.Missing("INVALID_OPT_IN");
            }
            boolean enabled = override == null ? configuration.enabled(environment) : override.equals("1");
            if (!enabled) {
                return new Availability.Disabled();
            }
            if (target == null || target.hostSessionId() == null || target.hostSessionId().isBlank()
                    || target.hostSessionId().startsWith("-") || target.hostSessionId().chars().anyMatch(Character::isISOControl)) {
                return new Availability.Missing("SESSION_TARGET_INVALID");
            }
            Optional<Path> executable = executable(environment);
            if (executable.isEmpty()) {
                return new Availability.Missing("NATIVE_EXECUTABLE_MISSING");
            }
            // Capture only this target's exposed environment, never System.getenv of the server or another target.
            Map<String, String> child = NativeWakeSend.selectEnvironment(environment, NATIVE_FIELDS);
            return NativeWakeSend.boundTo(target.hostSessionId(), "SESSION_TARGET_MISMATCH",
                    (session, marker) -> send(executable.orElseThrow(), child, session, marker));
        } catch (IOException | UncheckedIOException | InvalidPathException | tools.jackson.core.JacksonException invalid) {
            LOG.warn("CODEX_WAKE_BOOTSTRAP_UNREADABLE", SafeFailure.capture(invalid));
            return new Availability.Missing("BOOTSTRAP_UNREADABLE");
        }
    }

    @Override
    public Optional<ManagedLauncher> managedLauncher(Map<String, String> environment, Target target) {
        try {
            var directory = ManagedWakeWorkspace.existingDirectory(target.workspace());
            if (directory.isEmpty()) {
                return Optional.empty();
            }
            Path workspace = directory.orElseThrow();
            Map<String, String> child = NativeWakeSend.selectEnvironment(environment, NATIVE_FIELDS);
            return executable(environment).map(binary -> launch ->
                    CodexManagedConversation.open(binary, workspace, child, mapper, Duration.ofSeconds(10), launch.model(), launch.effort(), null));
        } catch (IOException | UncheckedIOException | InvalidPathException invalid) {
            LOG.warn("CODEX_MANAGED_BOOTSTRAP_UNREADABLE", SafeFailure.capture(invalid));
            return Optional.empty();
        }
    }

    @Override
    public Optional<SessionTitleReader> sessionTitles(Map<String, String> environment, Target target) throws IOException {
        if (!NativeWakeProtocol.validConversation(target.hostSessionId())) {
            return Optional.empty();
        }
        var directory = ManagedWakeWorkspace.existingDirectory(target.workspace());
        if (directory.isEmpty()) {
            return Optional.empty();
        }
        Path workspace = directory.orElseThrow();
        Map<String, String> child = NativeWakeSend.selectEnvironment(environment, NATIVE_FIELDS);
        Path index = sessionNameIndex(child);
        return executable(environment).map(binary -> new SessionTitleReader() {
            private boolean read;
            private IndexStamp previous;

            @Override
            public Optional<String> read() throws IOException, InterruptedException {
                IndexStamp current = IndexStamp.capture(index);
                if (read && Objects.equals(previous, current)) {
                    return Optional.empty();
                }
                var process = ToolProcess.startStdio(List.of(binary.toString(), "app-server", "--listen", "stdio://"), workspace, child);
                Optional<String> title = CodexAppServerConnection.readThreadTitle(process, mapper, Duration.ofSeconds(3), target.hostSessionId());
                previous = current;
                read = true;
                return title;
            }
        });
    }

    /** The upstream index is used only as a change signal. The app-server supplies the title. */
    private static Path sessionNameIndex(Map<String, String> environment) {
        String explicit = environment.get("CODEX_HOME");
        if (explicit != null && !explicit.isBlank()) {
            Path directory = Path.of(explicit);
            return directory.isAbsolute() ? directory.resolve("session_index.jsonl") : null;
        }
        String home = environment.getOrDefault("HOME", environment.get("USERPROFILE"));
        if (home == null || home.isBlank()) {
            return null;
        }
        Path directory = Path.of(home);
        return directory.isAbsolute() ? directory.resolve(".codex").resolve("session_index.jsonl") : null;
    }

    private record IndexStamp(long size, FileTime modifiedAt, Object fileKey) {
        private static IndexStamp capture(Path index) throws IOException {
            if (index == null) {
                return null;
            }
            try {
                var attributes = Files.readAttributes(index, BasicFileAttributes.class);
                return new IndexStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
            } catch (NoSuchFileException absent) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("CODEX_SESSION_INDEX_ABSENT", SafeFailure.capture(absent));
                }
                return null;
            }
        }
    }

    private static Optional<Path> executable(Map<String, String> environment) throws IOException {
        String managed = environment.get("CODEX_MANAGED_PACKAGE_ROOT");
        if (managed != null) {
            Path root = Path.of(managed);
            if (!root.isAbsolute() || !Files.isDirectory(root)) {
                return Optional.empty();
            }
            // Codex's managed package layout supplies a platform package below this root. Search depth and candidate count are bounded; ambiguous installs are refused.
            try (Stream<Path> found = Files.find(root, 8, (path, attributes) -> attributes.isRegularFile() && nativeName(path))) {
                List<Path> candidates = found.limit(2).toList();
                return candidates.size() == 1 ? Optional.of(candidates.getFirst().toAbsolutePath()) : Optional.empty();
            }
        }
        return NativeLauncher.onPath(environment, CodexHost.ID.label(), CodexWakeTransport::nativeFile);
    }

    private static boolean nativeName(Path path) {
        String name = path.getFileName().toString();
        return (name.equals(CodexHost.ID.label() + ".exe") || name.equals(CodexHost.ID.label())) && nativeFile(path);
    }

    private static boolean nativeFile(Path path) {
        return NativeLauncher.eligible(path, NativeLauncher.ScriptPolicy.NATIVE_ONLY,
                unreadable -> LOG.warn("CODEX_NATIVE_EXECUTABLE_UNREADABLE", SafeFailure.capture(unreadable)));
    }

    private Outcome send(Path executable, Map<String, String> environment, String session, String marker) {
        ToolProcess.Request request = NativeWakeSend.request(
                List.of(executable.toString(), "queue", "--thread", session, "--message", marker),
                ToolProcess.Stderr.MERGE, environment);
        return NativeWakeSend.run(process, request, LOG, SEND_DIAGNOSTICS);
    }
}
