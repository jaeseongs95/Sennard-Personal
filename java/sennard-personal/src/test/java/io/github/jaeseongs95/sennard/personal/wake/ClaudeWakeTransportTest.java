// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeWakeTransportTest {
    private final ClaudeWakeTransport adapter = new ClaudeWakeTransport(JsonMapper.builder().build());

    @TempDir
    Path temporary;

    @Test
    void rejectsExecutablePlainTextAndForeignHeaders() throws Exception {
        Path candidate = temporary.resolve("claude.exe");
        var environment = Map.of("PATH", temporary.toString());
        var target = target();
        NativeLauncherFixture.executable(candidate, "plain text".getBytes(StandardCharsets.US_ASCII));
        assertTrue(adapter.managedLauncher(environment, target).isEmpty(), "plain text");
        NativeLauncherFixture.executable(candidate, NativeLauncherFixture.foreignHeader());
        assertTrue(adapter.managedLauncher(environment, target).isEmpty(), "foreign header");
        NativeLauncherFixture.executable(candidate, NativeLauncherFixture.localHeader());
        assertTrue(adapter.managedLauncher(environment, target).isPresent(), "local header");
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(environment, target));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesCustomWrapperAndSymlinkAndFallsBackToUserBin() throws Exception {
        Path path = Files.createDirectory(temporary.resolve("path"));
        Path bin = Files.createDirectories(temporary.resolve(".local/bin"));
        Path wrapper = NativeLauncherFixture.executable(bin.resolve("custom"),
                "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII));
        Files.createSymbolicLink(bin.resolve("claude"), wrapper.getFileName());
        var environment = Map.of("PATH", path.toString(), "HOME", temporary.toString());
        assertTrue(adapter.managedLauncher(environment, target()).isPresent());

        NativeLauncherFixture.executable(path.resolve("claude"), NativeLauncherFixture.localHeader());
        assertTrue(adapter.managedLauncher(Map.of("PATH", "." + File.pathSeparator + path, "HOME", "\0"), target()).isPresent());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsRejectsShebangWrappers() throws Exception {
        NativeLauncherFixture.executable(temporary.resolve("claude.exe"),
                "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(adapter.managedLauncher(Map.of("PATH", temporary.toString()), target()).isEmpty());
    }

    private WakeTransport.Target target() {
        return new WakeTransport.Target("claude-root", temporary.toString());
    }
}
