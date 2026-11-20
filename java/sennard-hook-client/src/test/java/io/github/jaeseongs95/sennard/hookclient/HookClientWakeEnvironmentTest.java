// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.WhatTheHostSees.Result;

/** Checks the loopback request emitted by the hook client. */
class HookClientWakeEnvironmentTest {
    private static final String HEADER = "X-Sennard-Wake-Environment";
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "CODEX_MANAGED_PACKAGE_ROOT", "/host/codex-root",
            "CLAUDE_CODE_OAUTH_TOKEN", "claude-auth-fixture-must-not-travel",
            "PATH", "/host/bin",
            "UNRELATED_PRIVATE_VALUE", "private-fixture-must-not-travel");

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"codex", "claude"})
    void bindingStartSendsOnlyCapability(String host) throws IOException {
        try (HookServerStub stub = new HookServerStub()) {
            Result result = run(stub, HookClientCall.of(host, "SessionStart", FailureAnswer.FAIL).bindingHost());

            assertEquals(0, result.exit());
            assertEquals("", result.err());
            assertEquals(1, stub.received.size());
            HookServerStub.Received request = stub.received.getFirst();
            assertEquals("/hooks/v1/" + host + "/SessionStart", request.path());
            String key = request.headers().getFirst(NativeWakeProtocol.KEY);
            assertNotNull(key);
            assertTrue(NativeWakeProtocol.validKey(key));
            assertNoPrivateValue(request, result);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"codex", "claude"})
    void nonBindingCallDoesNotOfferWake(String host) throws IOException {
        assertNoAttachment(HookClientCall.of(host, "UserPromptSubmit", FailureAnswer.FAIL));
    }

    private void assertNoAttachment(HookClientCall call) throws IOException {
        try (HookServerStub stub = new HookServerStub()) {
            Result result = run(stub, call);
            assertEquals(0, result.exit());
            assertEquals("", result.err());
            assertEquals(1, stub.received.size());
            HookServerStub.Received request = stub.received.getFirst();
            assertNull(request.headers().getFirst(NativeWakeProtocol.KEY));
            assertNoPrivateValue(request, result);
        }
    }

    private Result run(HookServerStub stub, HookClientCall call) throws IOException {
        Path serverFile = temporary.resolve(ServerAddress.FILE_NAME);
        stub.announceIn(serverFile);
        HookClient client = new HookClient(ENVIRONMENT, ProcessHandle.current(), serverFile, within -> {
            throw new AssertionError("the announced loopback server must not require a startup");
        }, temporary.resolve("fixture-install.jar"));
        return WhatTheHostSees.run(client, call, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNoPrivateValue(HookServerStub.Received request, Result result) {
        String visible = request.headers().toString() + request.bodyText() + result.out() + result.err();
        assertNull(request.headers().getFirst(HEADER));
        assertFalse(visible.contains("CODEX_MANAGED_PACKAGE_ROOT"));
        assertFalse(visible.contains("/host/codex-root"));
        assertFalse(visible.contains("CLAUDE_CODE_OAUTH_TOKEN"));
        assertFalse(visible.contains("claude-auth-fixture-must-not-travel"));
        assertFalse(visible.contains("UNRELATED_PRIVATE_VALUE"));
        assertFalse(visible.contains("private-fixture-must-not-travel"));
    }
}
