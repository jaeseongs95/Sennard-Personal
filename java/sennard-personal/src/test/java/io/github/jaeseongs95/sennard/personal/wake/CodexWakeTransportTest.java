// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static org.junit.jupiter.api.Assertions.*;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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
    void optInDefaultsOffAndPresentEmptyOrZeroOverrideTrueFile() throws Exception {
        Files.writeString(temporary.resolve("session-messaging.json"), "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":true}}");
        var adapter = adapter(request -> { throw new AssertionError("connect must not launch"); });
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(Map.of(), TARGET));
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "0"), TARGET));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", ""), TARGET));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString()), TARGET));
    }

    @Test
    void boundedOrMalformedFileCannotEnableWake() throws Exception {
        var adapter = adapter(request -> { throw new AssertionError("no launch"); });
        Files.writeString(temporary.resolve("session-messaging.json"), "x".repeat(4097));
        assertInstanceOf(WakeTransport.Availability.Disabled.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString()), TARGET));
        Files.writeString(temporary.resolve("session-messaging.json"), "{");
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter.connect(Map.of("PLUGIN_DATA", temporary.toString()), TARGET));
    }

    @Test
    void nativePathAndExactQueueArgumentsAreOnlyAHandoff() throws Exception {
        Path executable = candidate();
        AtomicReference<ToolProcess.Request> observed = new AtomicReference<>();
        var adapter = adapter(request -> { observed.set(request); return new ToolProcess.Finished(0, new byte[0]); });
        var ready = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter.connect(enabled(), TARGET));
        assertInstanceOf(WakeTransport.Outcome.Submitted.class, ready.connection().wake("thread-a", "marker-a"));
        assertEquals(List.of(executable.toString(), "queue", "--thread", "thread-a", "--message", "marker-a"), observed.get().command());
        assertFalse(observed.get().inheritEnvironment());
        assertEquals(65536, observed.get().maxOutputBytes());
        assertFalse(observed.get().environment().containsKey("AGENT_GOVERNANCE_CODEX_QUEUE_WAKE"));
    }

    @Test
    void timeoutAndNonzeroStayUnknownAndMissingExecutableIsNotLaunched() throws Exception {
        candidate();
        var timed = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> new ToolProcess.TimedOut()).connect(enabled(), TARGET));
        assertInstanceOf(WakeTransport.Outcome.Unknown.class, timed.connection().wake("thread-a", "m"));
        var failed = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> new ToolProcess.Finished(1, new byte[0])).connect(enabled(), TARGET));
        assertInstanceOf(WakeTransport.Outcome.Unknown.class, failed.connection().wake("thread-a", "m"));
        Files.delete(temporary.resolve("codex.exe"));
        assertInstanceOf(WakeTransport.Availability.Missing.class, adapter(request -> { throw new AssertionError("not launched"); }).connect(enabled(), TARGET));
    }

    @Test
    void unreadableManagedSubtreeIsMissingWithoutAbortingBootstrap() throws Exception {
        Path managed = Files.createDirectory(temporary.resolve("managed-package"));
        Path denied = Files.createDirectory(managed.resolve("unreadable"));
        AclFileAttributeView acl = Files.getFileAttributeView(denied, AclFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> original = List.copyOf(acl.getAcl());
            List<AclEntry> restricted = new ArrayList<>(original);
            restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            try {
                // Restrict only this fixture directory; retaining WRITE_ACL permits exact restoration in finally.
                acl.setAcl(restricted);
                assertUnreadableManagedSubtree(managed, denied);
            } finally {
                acl.setAcl(original);
                assertEquals(original, acl.getAcl(), "fixture must restore the original ACL");
            }
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(denied, PosixFileAttributeView.class);
            assertNotNull(posix, "fixture requires an ACL or POSIX permission view; unsupported access is not a skip");
            Set<PosixFilePermission> original = Set.copyOf(posix.readAttributes().permissions());
            try {
                posix.setPermissions(Set.of());
                assertUnreadableManagedSubtree(managed, denied);
            } finally {
                posix.setPermissions(original);
                assertEquals(original, posix.readAttributes().permissions(), "fixture must restore the original permissions");
            }
        }
    }

    private void assertUnreadableManagedSubtree(Path managed, Path denied) throws Exception {
        UncheckedIOException failure;
        // Stream creation can open the readable root. Only terminal traversal reaches the inaccessible child.
        try (Stream<Path> entries = Files.find(managed, 8, (path, attributes) -> false)) {
            failure = assertThrows(UncheckedIOException.class, entries::toList,
                    "fixture must demonstrate a real lazy Files.find failure before checking the adapter");
        }
        AccessDeniedException inaccessible = assertInstanceOf(AccessDeniedException.class, failure.getCause());
        assertEquals(denied, Path.of(inaccessible.getFile()), "the denied path must belong to this fixture");
        var adapter = adapter(request -> { throw new AssertionError("unreadable bootstrap must never launch a process"); });
        assertEquals(new WakeTransport.Availability.Missing("BOOTSTRAP_UNREADABLE"), adapter.connect(Map.of(
                "CODEX_MANAGED_PACKAGE_ROOT", managed.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "1"), TARGET));
    }

    @Test
    void onlyExactBooleanConfigurationEnablesAndUnknownFieldsRemainCompatible() throws Exception {
        candidate();
        var adapter = adapter(request -> { throw new AssertionError("configuration never launches"); });
        Map<String, String> environment = Map.of("PLUGIN_DATA", temporary.toString(), "CODEX_MANAGED_PACKAGE_ROOT", temporary.toString());
        Path file = temporary.resolve("session-messaging.json");
        for (String flag : List.of("false", "null", "\"true\"", "1", "[]", "{}")) {
            Files.writeString(file, "{\"schemaVersion\":\"1.0.0\",\"codex\":{\"queueWake\":" + flag + "}}");
            assertFalse(adapter.connect(environment, TARGET) instanceof WakeTransport.Availability.Ready, flag);
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
        for (WakeProcess.Phase phase : WakeProcess.Phase.values()) {
            var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, adapter(request -> {
                throw new WakeProcess.Failure(phase, new java.io.IOException("synthetic-token"));
            }).connect(enabled(), TARGET)).connection();
            var result = connection.wake("thread-a", "marker");
            assertTrue(phase == WakeProcess.Phase.STARTED
                    ? result instanceof WakeTransport.Outcome.Unknown : result instanceof WakeTransport.Outcome.NotSent);
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
        Path path = Files.write(temporary.resolve("codex.exe"), new byte[] {'M', 'Z', 0, 0});
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
    private Map<String, String> enabled() {
        return Map.of("CODEX_MANAGED_PACKAGE_ROOT", temporary.toString(), "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE", "1");
    }
    private static CodexWakeTransport adapter(WakeProcess runner) {
        return new CodexWakeTransport(runner, JsonMapper.builder().build());
    }
}
