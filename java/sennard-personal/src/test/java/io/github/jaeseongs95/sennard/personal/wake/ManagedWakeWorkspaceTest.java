// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedWakeWorkspaceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temporary;

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("workspaceCases")
    void onlyAnAbsoluteExistingDirectoryAllowsManagedLauncherSelection(AdapterFixture fixture, WorkspaceCase input) throws IOException {
        Path binary = NativeLauncherFixture.executable(temporary.resolve(fixture.executable()), NativeLauncherFixture.localHeader());
        Map<String, String> environment = Map.of("PATH", temporary.toString());
        String candidate = switch (input) {
            case MISSING -> null;
            case RELATIVE -> ".";
            case MALFORMED -> temporary + "\0invalid";
            case FILE -> binary.toString();
            case DIRECTORY -> temporary.resolve(".").toString();
        };

        // The fixture file is only discovered. The returned launcher is never invoked here.
        assertEquals(input == WorkspaceCase.DIRECTORY,
                fixture.adapter().managedLauncher(environment, new Target("fixture-root", candidate)).isPresent());
    }

    private static Stream<Arguments> workspaceCases() {
        WakeProcess unexpectedSend = request -> { throw new AssertionError("launcher selection must not send a wake"); };
        return Stream.of(
                new AdapterFixture("agy.exe", new AntigravityWakeTransport(unexpectedSend)),
                new AdapterFixture("codex.exe", new CodexWakeTransport(unexpectedSend, JSON)),
                new AdapterFixture("claude.exe", new ClaudeWakeTransport(JSON)))
                .flatMap(adapter -> Stream.of(WorkspaceCase.values()).map(input -> Arguments.of(adapter, input)));
    }

    private record AdapterFixture(String executable, NativeWakeAdapter adapter) {
        @Override
        public String toString() {
            return executable;
        }
    }

    private enum WorkspaceCase {
        MISSING, RELATIVE, MALFORMED, FILE, DIRECTORY
    }
}
