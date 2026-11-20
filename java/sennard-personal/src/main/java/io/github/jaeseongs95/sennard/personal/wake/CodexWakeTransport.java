// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
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
    private static final String OPT_IN = "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE";
    private final WakeProcess process;
    private final JsonMapper mapper;
    private final ObjectReader configuration;

    public CodexWakeTransport(WakeProcess process, JsonMapper mapper) {
        this.process = process;
        this.mapper = mapper;
        this.configuration = mapper.readerFor(QueueConfiguration.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public Availability connect(Map<String, String> environment, Target target) {
        try {
            String override = environment.get(OPT_IN);
            if (override != null && !override.equals("0") && !override.equals("1")) {
                return new Availability.Missing("INVALID_OPT_IN");
            }
            boolean enabled = override == null ? configured(environment.get("PLUGIN_DATA")) : override.equals("1");
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
            Map<String, String> child = nativeEnvironment(environment);
            return new Availability.Ready((session, marker) -> target.hostSessionId().equals(session)
                    ? send(executable.orElseThrow(), child, session, marker) : new Outcome.NotSent("SESSION_TARGET_MISMATCH"));
        } catch (IOException | UncheckedIOException | InvalidPathException | tools.jackson.core.JacksonException invalid) {
            LOG.warn("CODEX_WAKE_BOOTSTRAP_UNREADABLE", SafeFailure.capture(invalid));
            return new Availability.Missing("BOOTSTRAP_UNREADABLE");
        }
    }

    @Override
    public Optional<ManagedLauncher> managedLauncher(Map<String, String> environment, Target target) {
        if (target.workspace() == null) {
            return Optional.empty();
        }
        try {
            Path workspace = Path.of(target.workspace());
            if (!workspace.isAbsolute() || !Files.isDirectory(workspace)) {
                return Optional.empty();
            }
            Map<String, String> child = nativeEnvironment(environment);
            return executable(environment).map(binary -> launch ->
                    CodexManagedConversation.open(binary, workspace, child, mapper, Duration.ofSeconds(10), launch.model(), launch.effort(), null));
        } catch (IOException | UncheckedIOException | InvalidPathException invalid) {
            LOG.warn("CODEX_MANAGED_BOOTSTRAP_UNREADABLE", SafeFailure.capture(invalid));
            return Optional.empty();
        }
    }

    private static Map<String, String> nativeEnvironment(Map<String, String> environment) {
        return environment.entrySet().stream()
                .filter(entry -> List.of("PATH", "Path", "CODEX_MANAGED_PACKAGE_ROOT", "CODEX_HOME", "SystemRoot", "HOME", "USERPROFILE", "TEMP", "TMP", "LANG").contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean configured(String folder) throws IOException {
        if (folder == null) {
            return false;
        }
        Path root = Path.of(folder);
        if (!root.isAbsolute()) {
            return false;
        }
        Path file = root.resolve("session-messaging.json");
        if (!Files.exists(file)) {
            return false;
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(4097);
        }
        if (bytes.length > 4096) {
            return false;
        }
        QueueConfiguration config = configuration.readValue(bytes);
        return config != null && config.enabled();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueueConfiguration(@HostInput.Text String schemaVersion, CodexQueue codex) {
        boolean enabled() {
            return "1.0.0".equals(schemaVersion) && codex != null && Boolean.TRUE.equals(codex.queueWake());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodexQueue(@JsonDeserialize(using = QueueFlag.class) Boolean queueWake) {
    }

    /** Configuration permits the JSON boolean itself, with no text or numeric coercion. */
    public static final class QueueFlag extends StdDeserializer<Boolean> {
        public QueueFlag() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_TRUE) || parser.hasToken(JsonToken.VALUE_FALSE)) {
                return parser.hasToken(JsonToken.VALUE_TRUE);
            }
            parser.skipChildren();
            return null;
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
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path directory = Path.of(entry);
            if (!directory.isAbsolute()) {
                continue;
            }
            for (String name : List.of(CodexHost.ID.label(), CodexHost.ID.label() + ".exe")) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate) && nativeFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean nativeName(Path path) {
        String name = path.getFileName().toString();
        return (name.equals(CodexHost.ID.label() + ".exe") || name.equals(CodexHost.ID.label())) && Files.isExecutable(path) && nativeFile(path);
    }

    /** Executable permission alone also accepts scripts. Recognize native PE, ELF and Mach-O headers without launching a probe. */
    private static boolean nativeFile(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            if (header.length < 4) return false;
            int magic = java.nio.ByteBuffer.wrap(header).getInt();
            return header[0] == 'M' && header[1] == 'Z' || magic == 0x7f454c46 || magic == 0xfeedface || magic == 0xcefaedfe
                    || magic == 0xfeedfacf || magic == 0xcffaedfe || magic == 0xcafebabe || magic == 0xbebafeca;
        } catch (IOException unreadable) {
            LOG.warn("CODEX_NATIVE_EXECUTABLE_UNREADABLE", SafeFailure.capture(unreadable));
            return false;
        }
    }

    private Outcome send(Path executable, Map<String, String> environment, String session, String marker) {
        ToolProcess.Request request = ToolProcess.Request.of(List.of(executable.toString(), "queue", "--thread", session, "--message", marker),
                ToolProcess.Stderr.MERGE, Duration.ofSeconds(10)).limitedTo(65536).withoutInheritedEnvironment().withEnvironment(environment);
        try {
            return switch (process.run(request)) {
                case ToolProcess.Finished finished when finished.exitCode() == 0 -> new Outcome.Submitted();
                // Nonzero exits may follow a partial submission. Do not guess from free-text diagnostics that a send did not happen.
                case ToolProcess.Finished ignored -> new Outcome.Unknown("CLI_NONZERO");
                case ToolProcess.TimedOut ignored -> new Outcome.Unknown("TIMED_OUT");
                case ToolProcess.Overflowed ignored -> new Outcome.Unknown("OUTPUT_LIMIT");
            };
        } catch (WakeProcess.Failure failed) {
            LOG.warn("CODEX_WAKE_SEND_FAILED", SafeFailure.capture(failed));
            return failed.phase() == WakeProcess.Phase.STARTED ? new Outcome.Unknown("IO_AFTER_START") : new Outcome.NotSent("START_FAILED");
        } catch (IOException beforeStart) {
            // ToolProcess can fail before entering its launch scope while allocating its temporary output file.
            LOG.warn("CODEX_WAKE_SEND_PREPARATION_FAILED", SafeFailure.capture(beforeStart));
            return new Outcome.NotSent("START_PREPARATION_FAILED");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new Outcome.Unknown("CANCELLED");
        }
    }
}
