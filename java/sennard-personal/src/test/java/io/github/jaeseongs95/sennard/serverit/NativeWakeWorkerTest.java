// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.personal.wake.NativeWakeAdapter;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeProvider;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeWorker;
import io.github.jaeseongs95.sennard.personal.wake.WakeProcess;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import tools.jackson.databind.json.JsonMapper;

public final class NativeWakeWorkerTest {
    private static final HostId HOST = new HostId(HookTestSupport.FourthHostTestAdapter.LABEL);
    private static String targetSession;
    private static Map<String, String> localEnvironment;

    @BeforeEach
    void resetObservations() {
        targetSession = null;
        localEnvironment = null;
    }

    @Test
    void registeredProviderReceivesTheSessionReadByItsHostAdapter() throws IOException {
        Map<String, String> environment = Map.of("TEST_FOURTH_HOST_WAKE", "enabled");
        assertEquals(0, NativeWakeWorker.run(new String[]{"native-wake-worker", HOST.label(), "SessionStart"}, framedHook(), environment));
        assertEquals("fixture-worker-session", targetSession);
        assertEquals(environment, localEnvironment);
    }

    @Test
    void unregisteredHostDoesNotRunAProvider() throws IOException {
        assertEquals(2, NativeWakeWorker.run(new String[]{"native-wake-worker", "no-test-provider", "SessionStart"}, framedHook(), Map.of()));
        assertNull(targetSession);
    }

    private static ByteArrayInputStream framedHook() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var frame = new DataOutputStream(bytes)) {
            frame.writeUTF("10000001-1111-4111-8111-000000000001");
            frame.writeInt(41234);
            frame.writeLong(ProcessHandle.current().pid());
            frame.writeUTF(Instant.now().toString());
            frame.write("{\"run\":\"fixture-worker-session\"}".getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    public static final class FourthHostProvider implements NativeWakeProvider {
        @Override
        public HostDefinition definition() {
            return new FourthHostDefinition();
        }

        @Override
        public NativeWakeAdapter adapter(WakeProcess process, JsonMapper mapper) {
            return (environment, target) -> {
                targetSession = target.hostSessionId();
                localEnvironment = environment;
                return new Availability.Disabled();
            };
        }
    }

    private static final class FourthHostDefinition implements HostDefinition {
        @Override
        public HostId host() {
            return HOST;
        }

        @Override
        public Class<?> inputType() {
            return HookTestSupport.FourthHostTestAdapter.Input.class;
        }

        @Override
        public HostHookAdapter hookAdapter(JsonMapper mapper) {
            return new HookTestSupport.FourthHostTestAdapter();
        }

        @Override
        public String packageDirectory() {
            return "";
        }
    }
}
