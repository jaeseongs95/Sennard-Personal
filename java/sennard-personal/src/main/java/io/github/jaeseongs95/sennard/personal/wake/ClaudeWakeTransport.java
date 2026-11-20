// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.hookclient.WakeEnvironment;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHost;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;
import tools.jackson.databind.json.JsonMapper;

/** The attended Claude session handles its own input; this worker manages separate native conversations. */
public final class ClaudeWakeTransport implements NativeWakeAdapter {
    public static final class Provider implements NativeWakeProvider {
        @Override
        public HostDefinition definition() {
            return new ClaudeHost();
        }

        @Override
        public NativeWakeAdapter adapter(WakeProcess process, JsonMapper mapper) {
            return new ClaudeWakeTransport(mapper);
        }
    }

    private static final System.Logger LOG = System.getLogger(ClaudeWakeTransport.class.getName());
    private static final Duration HANDOFF_DEADLINE = Duration.ofSeconds(10);
    private final JsonMapper mapper;

    ClaudeWakeTransport(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Availability connect(Map<String, String> environment, Target target) {
        return new Availability.Disabled();
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
            var selected = WakeEnvironment.forHost(ClaudeHost.ID.label(), environment);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            var workerEnvironment = new HashMap<>(selected.orElseThrow());
            workerEnvironment.put("DISABLE_AUTOUPDATER", "1");
            Map<String, String> child = Map.copyOf(workerEnvironment);
            return executable(child).map(binary -> launch -> ClaudeManagedConversation.open(binary, launch, mapper, HANDOFF_DEADLINE,
                    command -> ToolProcess.startStdio(command, workspace, child)));
        } catch (IOException | InvalidPathException invalid) {
            LOG.log(System.Logger.Level.DEBUG, "Claude worker preparation failed ({0})", invalid.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<Path> executable(Map<String, String> environment) {
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                var candidate = executableIn(Path.of(entry));
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }
        for (String variable : List.of("HOME", "USERPROFILE")) {
            String home = environment.get(variable);
            if (home != null) {
                var candidate = executableIn(Path.of(home, ".local", "bin"));
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableIn(Path directory) {
        if (directory.isAbsolute()) {
            String name = ClaudeHost.ID.label();
            for (String file : List.of(name, name + ".exe")) {
                Path candidate = directory.resolve(file);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
