// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport;
import tools.jackson.databind.json.JsonMapper;

/** Injected process boundary: checks policy and argv, and never claims that a fixture file is a working native host. */
class CodexWakeTransportTest {
    @TempDir Path temporary;
    private static final WakeTransport.Target TARGET = new WakeTransport.Target("thread-a", "/work");

    @Test
    void queueWakeDefaultsOnWithoutEnvironmentSettingOrFile() throws Exception {
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(Map.of("CODEX_MANAGED_PACKAGE_ROOT", temporary.toString()), TARGET));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of(), TARGET));
        assertInstanceOf(WakeTransport.Availability.Ready.class,
                adapter.connect(Map.of("CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString()), TARGET));
    }

    @Test
    void danglingConfigurationLinkDoesNotEnableWake() throws Exception {
        candidate();
        Path file = temporary.resolve("session-messaging.json");
        try {
            if (File.separatorChar == '\\') {
                // A junction is a real NTFS link and does not require the symbolic-link privilege.
                Path target = Files.createDirectory(temporary.resolve("settings-target"));
                var junction = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                        file.getFileName().toString(), target.getFileName().toString())
                        .directory(temporary.toFile()).redirectErrorStream(true).start();
                try {
                    assertTrue(junction.waitFor(10, TimeUnit.SECONDS), "junction creation timed out");
                    assertEquals(0, junction.exitValue(), () -> "mklink failed with exit " + junction.exitValue());
                } finally {
                    if (junction.isAlive()) {
                        junction.destroyForcibly();
                    }
                }
                Files.delete(target);
            } else {
                Files.createSymbolicLink(file, temporary.resolve("missing-settings.json"));
            }
            assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS), "the link itself must exist");
            assertTrue(Files.notExists(file), "the link target must be missing");
            var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
            assertEquals(new WakeTransport.Availability.Missing("BOOTSTRAP_UNREADABLE"), adapter.connect(Map.of(
                    "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString()), TARGET));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void unreadableConfigurationPathDoesNotEnableWake() throws Exception {
        candidate();
        Files.createDirectory(temporary.resolve("session-messaging.json"));
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        assertEquals(new WakeTransport.Availability.Missing("BOOTSTRAP_UNREADABLE"), adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString()), TARGET));
    }

    @Test
    void explicitZeroOverridesTrueFile() throws Exception {
        candidate();
        Files.writeString(temporary.resolve("session-messaging.json"), "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":true}}");
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString(), "SENNARD_CODEX_QUEUE_WAKE", "0"), TARGET));
    }

    @Test
    void presentEmptyOrInvalidOverrideDoesNotFallBackToTrueFile() throws Exception {
        candidate();
        Files.writeString(temporary.resolve("session-messaging.json"), "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":true}}");
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        for (String override : List.of("", "true", " 0")) {
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of(
                    "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString(), "SENNARD_CODEX_QUEUE_WAKE", override), TARGET), override);
        }
    }

    @Test
    void theWorkerIgnoresTheLegacyVariable() throws Exception {
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "0"), TARGET));
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "0",
                "SENNARD_CODEX_QUEUE_WAKE", "0"), TARGET));
    }

    @Test
    void boundedOrMalformedFileCannotEnableWake() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("no launch"); });
        Files.writeString(temporary.resolve("session-messaging.json"), "x".repeat(4097));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString()), TARGET));
        Files.writeString(temporary.resolve("session-messaging.json"), "{");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString()), TARGET));
    }

    @Test
    void nestedNativePathAndExactQueueArgumentsAreOnlyAHandoff() throws Exception {
        Path platformPackage = Files.createDirectory(temporary.resolve("platform-package"));
        Path executable = candidate(Files.createDirectory(platformPackage.resolve("bin")));
        AtomicReference<ToolProcess.Request> observed = new AtomicReference<>();
        var adapter = adapter(request -> { observed.set(request); return new ToolProcess.Finished(0, new byte[0]); });
        Map<String, String> expectedEnvironment = Map.of("CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(),
                "CODEX_HOME", "fixture-home", "Path", "fixture-path");
        var exposed = new java.util.HashMap<>(expectedEnvironment);
        exposed.put("SENNARD_CODEX_QUEUE_WAKE", "1");
        exposed.put("UNRELATED_SETTING", "fixture-value");
        var ready = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(exposed, TARGET));
        exposed.put("CODEX_HOME", "later-home");
        assertInstanceOf(WakeTransport.Outcome.Submitted.class, ready.connection().wake("thread-a", "marker-a"));
        assertEquals(List.of(executable.toString(), "queue", "--thread", "thread-a", "--message", "marker-a"), observed.get().command());
        assertEquals(ToolProcess.Stderr.MERGE, observed.get().stderr());
        assertEquals(java.time.Duration.ofSeconds(10), observed.get().timeout());
        assertFalse(observed.get().inheritEnvironment());
        assertEquals(65536, observed.get().maxOutputBytes());
        assertEquals(expectedEnvironment, observed.get().environment());
    }

    @Test
    void processResultsPreserveUncertainReasonsAndMissingExecutableIsNotLaunched() throws Exception {
        candidate();
        Map<ToolProcess.Outcome, String> failures = Map.of(new ToolProcess.TimedOut(), "TIMED_OUT",
                new ToolProcess.Overflowed(), "OUTPUT_LIMIT", new ToolProcess.Finished(1, new byte[0]), "CLI_NONZERO");
        for (var entry : failures.entrySet()) {
            var ready = assertInstanceOf(WakeTransport.Availability.Ready.class,
                    adapter(request -> entry.getKey()).connect(enabled(), TARGET));
            assertEquals(new WakeTransport.Outcome.Unknown(entry.getValue()), ready.connection().wake("thread-a", "m"));
        }
        Files.delete(temporary.resolve("codex.exe"));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter(request -> { throw new AssertionError("not launched"); }).connect(enabled(), TARGET));
    }

    @Test
    void pathCandidatesRequireTheLocalNativeFormatForQueueAndManagedLaunch() throws Exception {
        Path executable = candidate();
        var adapter = adapter(request -> { throw new AssertionError("connect never launches"); });
        Map<String, String> environment = Map.of("PATH", temporary.toString(), "SENNARD_CODEX_QUEUE_WAKE", "1");
        var managedTarget = new WakeTransport.Target(TARGET.hostSessionId(), temporary.toString());
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(environment, TARGET));
        assertTrue(adapter.managedLauncher(environment, managedTarget).isPresent());
        Files.write(executable, NativeLauncherFixture.foreignHeader());
        assertAll(
                () -> assertEquals(new WakeTransport.Availability.Missing("NATIVE_EXECUTABLE_MISSING"), adapter.connect(environment, TARGET)),
                () -> assertTrue(adapter.managedLauncher(environment, managedTarget).isEmpty()));
        for (String header : List.of("4d5a0000", "23212f62", "00000000")) {
            Files.write(executable, java.util.HexFormat.of().parseHex(header));
            assertEquals(new WakeTransport.Availability.Missing("NATIVE_EXECUTABLE_MISSING"), adapter.connect(environment, TARGET), header);
        }
    }

    @Test
    void managedRootCountsOnlyLocalCandidatesAndNeverFallsBackToPath() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("managed"));
        Path localDir = Files.createDirectory(root.resolve("local"));
        Path foreignDir = Files.createDirectory(root.resolve("foreign"));
        Path local = candidate(localDir);
        NativeLauncherFixture.executable(foreignDir.resolve(local.getFileName()), NativeLauncherFixture.foreignHeader());
        Path deeperThanLimit = Files.createDirectories(root.resolve("1/2/3/4/5/6/7/8"));
        candidate(deeperThanLimit);
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("discovery never launches"); });
        var environment = Map.of("PATH", temporary.toString(), "CODEX_MANAGED_PACKAGE_ROOT", root.toString());
        var managedTarget = new WakeTransport.Target(TARGET.hostSessionId(), temporary.toString());
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(environment, TARGET));
        assertTrue(adapter.managedLauncher(environment, managedTarget).isPresent());

        Path second = candidate(root);
        assertEquals(new WakeTransport.Availability.Missing("NATIVE_EXECUTABLE_MISSING"), adapter.connect(environment, TARGET));
        assertTrue(adapter.managedLauncher(environment, managedTarget).isEmpty());

        Files.delete(second);
        Files.delete(local);
        assertEquals(new WakeTransport.Availability.Missing("NATIVE_EXECUTABLE_MISSING"), adapter.connect(environment, TARGET));
        assertTrue(adapter.managedLauncher(environment, managedTarget).isEmpty());
    }

    @Test
    void invalidManagedRootIsMissingWithoutLaunching() {
        String invalid = temporary.toString() + "\0";
        var adapter = adapter(request -> { throw new AssertionError("invalid bootstrap must never launch a process"); });
        assertEquals(new WakeTransport.Availability.Missing("BOOTSTRAP_UNREADABLE"), adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", invalid, "SENNARD_CODEX_QUEUE_WAKE", "1"), TARGET));
    }

    @Test
    void onlyExactBooleanConfigurationEnablesAndUnknownFieldsRemainCompatible() throws Exception {
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("configuration never launches"); });
        Map<String, String> environment = Map.of("PLUGIN_DATA", temporary.toString(), "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString());
        Path file = temporary.resolve("session-messaging.json");
        Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":false}}");
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(environment, TARGET));
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "PLUGIN_DATA", temporary.toString(), "SENNARD_CODEX_QUEUE_WAKE", "1"), TARGET));
        for (String flag : List.of("null", "\"true\"", "1", "[]", "{}")) {
            Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":" + flag + "}}");
            assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(environment, TARGET), flag);
        }
        for (String schema : List.of("\"2.0.0\"", "1", "null", "true", "[]", "{}")) {
            Files.writeString(file, "{\"schemaVersion\":" + schema + ",\"codex\":{\"queueWake\":true}}");
            assertFalse(adapter.connect(environment, TARGET) instanceof WakeTransport.Availability.Ready, schema);
        }
        for (String section : List.of("null", "[]", "\"true\"", "1")) {
            Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":" + section + "}");
            assertFalse(adapter.connect(environment, TARGET) instanceof WakeTransport.Availability.Ready, section);
        }
        Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"extra\":1,\"codex\":{\"queueWake\":true,\"extra\":[]}}");
        assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(environment, TARGET));
        Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":false,\"queueWake\":true}}");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(environment, TARGET));
        Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":true}} {}");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(environment, TARGET));
    }

    @Test
    void sessionTargetCannotBeReplacedByWakeCaller() throws Exception {
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("wrong target never launches"); });
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(enabled(), TARGET)).connection();
        assertEquals(new WakeTransport.Outcome.NotSent("SESSION_TARGET_MISMATCH"), connection.wake("other-thread", "marker"));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(enabled(), new WakeTransport.Target("", "/work")));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(enabled(), null));
    }

    @Test
    void publicProcessFailurePhasePreservesSendCertainty() throws Exception {
        candidate();
        Map<WakeProcess.Phase, WakeTransport.Outcome> expected = Map.of(
                WakeProcess.Phase.STARTED, new WakeTransport.Outcome.Unknown("IO_AFTER_START"),
                WakeProcess.Phase.NOT_STARTED, new WakeTransport.Outcome.NotSent("START_FAILED"));
        for (WakeProcess.Phase phase : WakeProcess.Phase.values()) {
            var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> {
                throw new WakeProcess.Failure(phase, new java.io.IOException("synthetic-token"));
            }).connect(enabled(), TARGET)).connection();
            assertEquals(expected.get(phase), connection.wake("thread-a", "marker"));
        }
        var preparation = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> {
            throw new java.io.IOException("synthetic-token");
        }).connect(enabled(), TARGET)).connection();
        assertEquals(new WakeTransport.Outcome.NotSent("START_PREPARATION_FAILED"), preparation.wake("thread-a", "marker"));
    }

    @Test
    void anInterruptedSendStillCallsTheCodexProcessBoundaryAndRestoresInterrupt() throws Exception {
        candidate();
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> {
            assertTrue(Thread.interrupted());
            throw new InterruptedException("synthetic-token");
        }).connect(enabled(), TARGET)).connection();
        try {
            Thread.currentThread().interrupt();
            assertEquals(new WakeTransport.Outcome.Unknown("CANCELLED"), connection.wake("thread-a", "marker"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void localRunnerTranslatesRealLaunchFailureThroughPublicPort() {
        var request = ToolProcess.Request.of(List.of(temporary.resolve("does-not-exist").toString()),
                ToolProcess.Stderr.DISCARD, java.time.Duration.ofSeconds(2)).withoutInheritedEnvironment();
        var failure = assertThrows(WakeProcess.Failure.class,
                () -> new LocalWakeProcess().run(request));
        assertEquals(WakeProcess.Phase.NOT_STARTED, failure.phase());
        var cause = assertInstanceOf(ToolProcess.RunFailure.class, failure.getCause());
        assertFalse(cause.started());
        assertInstanceOf(java.io.IOException.class, cause.getCause());
    }

    private Path candidate() throws Exception {
        return candidate(temporary);
    }

    private Path candidate(Path folder) throws Exception {
        return NativeLauncherFixture.executable(folder.resolve("codex.exe"), NativeLauncherFixture.localHeader());
    }
    private Map<String, String> enabled() {
        return Map.of("CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "SENNARD_CODEX_QUEUE_WAKE", "1");
    }

    private static CodexWakeTransport adapter(WakeProcess runner) {
        return new CodexWakeTransport(runner, JsonMapper.builder().build());
    }
}
