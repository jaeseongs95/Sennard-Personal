// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        try {
            var directory = ManagedWakeWorkspace.existingDirectory(target.workspace());
            if (directory.isEmpty()) {
                return Optional.empty();
            }
            Path workspace = directory.orElseThrow();
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
        String name = ClaudeHost.ID.label();
        var onPath = NativeLauncher.onPath(environment, name, ClaudeWakeTransport::eligible);
        if (onPath.isPresent()) {
            return onPath;
        }
        for (String variable : List.of("HOME", "USERPROFILE")) {
            String home = environment.get(variable);
            if (home != null) {
                var candidate = NativeLauncher.inDirectory(Path.of(home, ".local", "bin"), name, ClaudeWakeTransport::eligible);
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean eligible(Path candidate) {
        return NativeLauncher.eligible(candidate, NativeLauncher.ScriptPolicy.POSIX_SHEBANG, unreadable ->
                LOG.log(System.Logger.Level.DEBUG, "Claude launcher unreadable ({0})", unreadable.getClass().getSimpleName()));
    }
}
