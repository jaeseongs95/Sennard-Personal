// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

class CodexWakeUpgradeTest {
    private static final String LEGACY = "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE";
    private static final String CURRENT = "SENNARD_CODEX_QUEUE_WAKE";
    private static final String DISABLED = """
            {
              "schemaVersion": "1.0.0",
              "codex": {"queueWake": false, "extra": 1},
              "other": {"queueWake": false}
            }
            """;
    private static final String PROMOTED = DISABLED.replace("\"queueWake\": false, \"extra\"", "\"queueWake\": true, \"extra\"");
    private static final String ENABLED = "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":true}}\n";

    @TempDir Path home;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no", "y", " ", "unexpected"})
    void theUpgradeRequiresAnExplicitYesEvenWithoutTheOldVariable(String answer) {
        assertTrue(CodexWakeSetup.isTransition("1.3.0", "1.4.0"));
        var cancelled = assertThrows(UnavailableException.class, () -> CodexWakeSetup.prepare(environment(Map.of()), true, true, prompt -> {
            assertTrue(prompt.contains("defaults to on"));
            assertTrue(prompt.contains("turn on"));
            assertTrue(prompt.contains("꺼져"));
            return CodexWakeSetup.accepts(answer);
        }));
        assertEquals(UnavailableCode.HOST_FILES_FAILED, cancelled.code());
        assertFalse(Files.exists(pluginData()));
    }

    @Test
    void yesCanKeepTheNewDefaultWhenThereWasNoOptOut() {
        CodexWakeSetup.prepare(environment(Map.of()), true, true, prompt -> CodexWakeSetup.accepts(" YES ")).apply();
        assertFalse(Files.exists(pluginData()));
    }

    @Test
    void aFreshInstallWithoutAnOldOptOutDoesNotAsk() {
        CodexWakeSetup.prepare(environment(Map.of()), false, true, prompt -> fail("unexpected prompt")).apply();
        assertFalse(Files.exists(pluginData()));
    }

    @Test
    void anAlreadyEnabledPluginDoesNotAskInTheConsole() throws Exception {
        Path file = writeSetting(pluginData(), ENABLED);
        CodexWakeSetup.prepare(environment(Map.of()), true, true, prompt -> fail("unexpected prompt")).apply();
        assertEquals(ENABLED, Files.readString(file));
    }

    @Test
    void aLegacyOnOverrideKeepsWakeEnabledWithoutAskingDespiteAnOffFile() throws Exception {
        Path file = writeSetting(pluginData(), DISABLED);
        CodexWakeSetup.prepare(environment(Map.of(LEGACY, "1")), true, true, prompt -> fail("unexpected prompt")).apply();
        assertEquals(PROMOTED, Files.readString(file));
    }

    @Test
    void aFreshInstallIgnoresTheLegacyVariable() {
        CodexWakeSetup.prepare(environment(Map.of(LEGACY, "0")), false, true, prompt -> fail("unexpected prompt")).apply();
        assertFalse(Files.exists(pluginData()));
    }

    @Test
    void aNewOnOverridePromptsForALegacyOptOutWithoutWritingAFile() {
        var environment = environment(Map.of(LEGACY, "0", CURRENT, "1"));
        assertThrows(UnavailableException.class, () -> CodexWakeSetup.prepare(environment, true, true, prompt -> false));
        CodexWakeSetup.prepare(environment, true, true, prompt -> true).apply();
        assertFalse(Files.exists(pluginData()));
    }

    @Test
    void aNewOffOverrideDoesNotAskOrChangeTheFile() throws Exception {
        Path file = writeSetting(pluginData(), DISABLED);
        CodexWakeSetup.prepare(environment(Map.of(CURRENT, "0")), true, true, prompt -> fail("unexpected prompt")).apply();
        assertEquals(DISABLED, Files.readString(file));
    }

    @Test
    void theSetupWindowAlsoAsksForThePreviouslyOffDefault() {
        assertThrows(UnavailableException.class, () -> CodexWakeSetup.prepare(environment(Map.of()), true, false, prompt -> false));
        assertFalse(Files.exists(pluginData()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "", "disabled"})
    void aPresentLegacyValueOtherThanOneTakesPrecedenceOverAnOnFile(String legacy) throws Exception {
        Path file = writeSetting(pluginData(), ENABLED);
        assertThrows(UnavailableException.class, () -> CodexWakeSetup.prepare(environment(Map.of(LEGACY, legacy)), true, false, prompt -> false));
        CodexWakeSetup.prepare(environment(Map.of(LEGACY, legacy)), true, false, prompt -> true).apply();
        assertEquals(ENABLED, Files.readString(file));
    }

    @Test
    void multipleMarketplacesShareOneConfirmationAndKeepOtherSettings() throws Exception {
        Path off = marketplaceSetting("company", DISABLED);
        Path alreadyOn = marketplaceSetting("community", ENABLED);
        Path other = writeSetting(home.resolve("codex/plugins/data/other-company"), DISABLED);
        var environment = environment(Map.of());
        assertThrows(UnavailableException.class, () -> CodexWakeSetup.prepare(environment, true, false, prompt -> false));
        var prompts = new java.util.ArrayList<String>();
        var approved = CodexWakeSetup.prepare(environment, true, false, prompts::add);
        assertEquals(1, prompts.size());
        assertEquals(DISABLED, Files.readString(off));

        approved.apply();

        assertEquals(PROMOTED, Files.readString(off));
        assertEquals(ENABLED, Files.readString(alreadyOn));
        assertEquals(DISABLED, Files.readString(other));
    }

    @Test
    void setupRechecksAllCopiesBeforeWritingAnyApprovedChange() throws Exception {
        Path first = marketplaceSetting("a-company", DISABLED);
        Path second = marketplaceSetting("z-community", DISABLED);
        var approved = CodexWakeSetup.prepare(environment(Map.of()), true, true, prompt -> true);
        String changed = DISABLED.replace("\"extra\": 1", "\"extra\": 2");
        Files.writeString(second, changed);

        assertThrows(UnavailableException.class, approved::apply);
        assertEquals(DISABLED, Files.readString(first));
        assertEquals(changed, Files.readString(second));
    }

    private Path marketplaceSetting(String marketplace, String settings) throws Exception {
        Files.createDirectories(home.resolve("codex/plugins/cache").resolve(marketplace).resolve("sennard/1.3.0"));
        return writeSetting(home.resolve("codex/plugins/data").resolve("sennard-" + marketplace), settings);
    }

    private static Path writeSetting(Path data, String settings) throws Exception {
        return Files.writeString(Files.createDirectories(data).resolve("session-messaging.json"), settings);
    }

    private InstallEnvironment environment(Map<String, String> values) {
        Map<String, String> env = new HashMap<>(values);
        env.put("CODEX_HOME", home.resolve("codex").toString());
        return new InstallEnvironment(env, home, () -> fail("no Java child"),
                (argv, variables, timeout) -> fail("no command"), StandardCharsets.UTF_8);
    }

    private Path pluginData() {
        return home.resolve("codex/plugins/data/sennard-sennard");
    }
}
