// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;

class AntigravityWakeEnvironmentTest {
    @TempDir
    Path temporary;

    @Test
    void agyCredentialsStayLocal() throws Exception {
        Map<String, String> environment = Map.of("ANTIGRAVITY_PROJECT_ID", "project-a", "ANTIGRAVITY_CONVERSATION_ID", "conversation-a",
                "ANTIGRAVITY_LS_ADDRESS", "127.0.0.1:1234", "ANTIGRAVITY_CSRF_TOKEN", "synthetic-token",
                "ANTIGRAVITY_AGENTAPI_EXE", "C:/synthetic/agy.exe", "UNRELATED_SECRET", "private-fixture");
        var expected = new HashMap<>(environment);
        expected.remove("UNRELATED_SECRET");
        assertEquals(expected, WakeEnvironment.forHost("agy", environment).orElseThrow());

        try (HookServerStub stub = new HookServerStub()) {
            Path file = temporary.resolve(ServerAddress.FILE_NAME);
            stub.announceIn(file);
            HookClient client = new HookClient(environment, ProcessHandle.current(), file, within -> {
                throw new AssertionError("already announced");
            }, temporary.resolve("fixture-install.jar"));
            var result = WhatTheHostSees.run(client, HookClientCall.of("agy", "SessionStart", FailureAnswer.FAIL).bindingHost(),
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, result.exit());
            var request = stub.received.getFirst();
            assertTrue(NativeWakeProtocol.validKey(request.headers().getFirst(NativeWakeProtocol.KEY)));
            String transmitted = request.headers().toString() + request.bodyText();
            for (String value : environment.values()) {
                assertFalse(transmitted.contains(value));
            }
        }
    }

    @Test
    void emptyExplicitValuesRemainPresentForPrerequisiteValidation() throws Exception {
        var environment = Map.of("ANTIGRAVITY_CSRF_TOKEN", "", "ANTIGRAVITY_PROJECT_ID", "");
        assertEquals(environment, WakeEnvironment.forHost("agy", environment).orElseThrow());
    }
}
