// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import tools.jackson.databind.json.JsonMapper;

class ClaudeStopSettingsTest {
    private static final String KEY = ClaudeStopSettings.KEY;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path home;

    @Test
    void addsMissingValueWithoutReformattingTheSettings() throws Exception {
        String original = "\uFEFF{\r\n  \"env\": { \"OTHER\": \"테스트🙂\" },\r\n  \"other\": [1, {\"key\":true}]\r\n}\r\n";
        Path settings = Files.writeString(home.resolve("settings.json"), original);
        ClaudeStopSettings.prepare(settings, prompt -> fail(prompt)).orElseThrow().apply();
        assertEquals(original.replace("\"OTHER\"", "\"" + KEY + "\": \"0\", \"OTHER\""), Files.readString(settings));
        assertTrue(ClaudeStopSettings.prepare(settings, prompt -> fail(prompt)).isEmpty());
    }

    @Test
    void reportsFailedSettingsWrite() throws Exception {
        Path profile = home.resolve("profile");
        Path settings = profile.resolve("settings.json");
        var change = ClaudeStopSettings.prepare(settings, prompt -> fail(prompt)).orElseThrow();

        // A file occupies the parent path, so settings cannot be written.
        Files.writeString(profile, "obstruction");
        assertThrows(UnavailableException.class, change::apply);
        assertEquals("obstruction", Files.readString(profile));
        assertFalse(Files.exists(settings));
    }

    @Test
    void replacesTheExistingValueAfterApproval() throws Exception {
        String original = "{\"env\":{\"BEFORE\":true,\"" + KEY + "\" : 7, \"AFTER\":[1,2]}}";
        Path settings = Files.writeString(home.resolve("settings.json"), original);
        ClaudeStopSettings.prepare(settings, prompt -> {
            assertTrue(prompt.contains(KEY));
            assertTrue(prompt.contains(" = 7"));
            return ClaudeStopConfirmation.Answer.YES;
        }).orElseThrow().apply();
        assertEquals(original.replace(": 7", ": \"0\""), Files.readString(settings));
    }

    @Test
    void explicitJsonNullNeedsApprovalLikeAnyExistingValue() throws Exception {
        String original = "{\"env\":{\"" + KEY + "\":null},\"other\":null}";
        Path settings = Files.writeString(home.resolve("settings.json"), original);
        assertThrows(UnavailableException.class, () -> ClaudeStopSettings.prepare(settings, prompt -> {
            assertTrue(prompt.contains(" = null"));
            return ClaudeStopConfirmation.Answer.NO;
        }));
        assertEquals(original, Files.readString(settings));

        ClaudeStopSettings.prepare(settings, prompt -> ClaudeStopConfirmation.Answer.YES).orElseThrow().apply();
        assertEquals("{\"env\":{\"" + KEY + "\":\"0\"},\"other\":null}", Files.readString(settings));
    }

    @Test
    void stopsInstallationBeforeWritingWhenApprovalIsMissing() throws Exception {
        Path config = Files.createDirectory(home.resolve("claude-profile"));
        Path plugins = Files.createDirectory(config.resolve("plugins"));
        Path cached = Files.createDirectories(plugins.resolve("cache/market/sennard/1.3.0"));
        Path body = Files.createDirectory(cached.resolve(".claude-plugin"));
        Files.writeString(body.resolve("plugin.json"), "{\"version\":\"1.3.0\"}");
        Path manifest = plugins.resolve("installed_plugins.json");
        String packageIndex = JSON.writeValueAsString(Map.of("plugins", Map.of("sennard@market", Map.of("installPath", cached.toString(), "version", "1.3.0"))));
        Files.writeString(manifest, packageIndex);
        String original = "{\"env\":{\"" + KEY + "\":\"8\"}}";
        Path settings = Files.writeString(config.resolve("settings.json"), original);
        Path root = home.resolve("install");
        InstallEnvironment environment = new InstallEnvironment(Map.of("CLAUDE_CONFIG_DIR", config.toString(), "SENNARD_INSTALL_ROOT", root.toString()), home,
                () -> fail("Java must not be located"), (argv, variables, timeout) -> fail("No command should run"), StandardCharsets.UTF_8);

        for (ClaudeStopConfirmation.Answer answer : new ClaudeStopConfirmation.Answer[] {
                ClaudeStopConfirmation.Answer.NO, ClaudeStopConfirmation.Answer.UNAVAILABLE }) {
            AtomicBoolean confirmed = new AtomicBoolean();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (PrintStream console = new PrintStream(output, false, StandardCharsets.UTF_8)) {
                Installer installer = new Installer(environment, RunningProgram.elsewhere(), "1.3.0", console, prompt -> {
                    assertTrue(confirmed.compareAndSet(false, true));
                    assertTrue(prompt.contains(KEY));
                    assertTrue(prompt.contains(" = \"8\""));
                    return answer;
                });
                assertThrows(UnavailableException.class, () -> installer.install(null));
            }
            assertTrue(confirmed.get(), "installer reached the confirmation after validating the plugin");
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertFalse(Files.exists(root));
            assertEquals(original, Files.readString(settings));
            assertEquals(packageIndex, Files.readString(manifest));
            try (var entries = Files.list(cached)) {
                assertEquals(java.util.List.of(body), entries.toList());
            }
        }
    }

    @Test
    void selectsDialogOrTerminalAndHandlesMissingInput() {
        assertEquals(ClaudeStopConfirmation.Answer.YES, ClaudeStopConfirmation.ask("prompt", false, prompt -> JOptionPane.YES_OPTION, prompt -> fail(prompt)));
        assertEquals(ClaudeStopConfirmation.Answer.NO, ClaudeStopConfirmation.ask("prompt", false, prompt -> JOptionPane.CLOSED_OPTION, prompt -> fail(prompt)));
        assertEquals(ClaudeStopConfirmation.Answer.YES, ClaudeStopConfirmation.ask("prompt", true, prompt -> fail(prompt), prompt -> " y "));
        assertEquals(ClaudeStopConfirmation.Answer.NO, ClaudeStopConfirmation.ask("prompt", true, prompt -> fail(prompt), prompt -> "n"));
        assertEquals(ClaudeStopConfirmation.Answer.UNAVAILABLE, ClaudeStopConfirmation.ask("prompt", true, prompt -> fail(prompt), prompt -> null));
        assertEquals(ClaudeStopConfirmation.Answer.YES, ClaudeStopConfirmation.ask("prompt", false, prompt -> {
            throw new java.awt.HeadlessException();
        }, prompt -> "y"));
        assertEquals(ClaudeStopConfirmation.Answer.UNAVAILABLE, ClaudeStopConfirmation.ask("prompt", false, prompt -> {
            throw new java.awt.AWTError("display unavailable");
        }, prompt -> null));
    }

    @Test
    void addsAnEnvObjectWhenSettingsAreAbsent() throws Exception {
        Path settings = home.resolve("settings.json");
        ClaudeStopSettings.prepare(settings, prompt -> fail(prompt)).orElseThrow().apply();
        assertEquals("0", JSON.readTree(Files.readString(settings)).path("env").path(KEY).asString());
    }
}
