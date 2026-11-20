// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookBoundary;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.message.Messages;

/**
 * What the hook tests add to the real application: a stand-in for the domain, a fourth host that exists only here, and the recorded hook
 * inputs. Nothing in this file is reachable from a product class.
 */
final class HookTestSupport {
    static final String SESSION = "10000001-1111-4111-8111-000000000001";

    private HookTestSupport() {
    }

    /** One real recorded stdin body from the host measurements (identifiers replaced with fixed values). */
    static byte[] fixture(String os, String host, String event) {
        String resource = "/hook-fixtures/" + os + "/" + host + "/" + event + ".json";
        try (InputStream stream = HookTestSupport.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("No fixture at " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    static String fixtureText(String os, String host, String event) {
        return new String(fixture(os, host, event), StandardCharsets.UTF_8);
    }

    /** What a domain can fail with when it cannot answer, and the code the refusal of a gate says for it ({@code hooks.gateRefused}); the server answers these 409, 400, 503 and 500. */
    record FailingDomain(String situation, RuntimeException failure, String code, int status) {
    }

    static List<FailingDomain> failingDomains() {
        return List.of(
                new FailingDomain("409: the call lost the first creation of a row",
                        new HookRejected(HookRejection.HOOK_HANDLER_CONFLICT, "STORE_UNIQUE_VIOLATION", new IllegalStateException("lost")),
                        "HOOK_HANDLER_CONFLICT:STORE_UNIQUE_VIOLATION", 409),
                new FailingDomain("400: another 4xx", new InvalidInputException(InputCode.VALIDATION_FAILED),
                        "VALIDATION_FAILED", 400),
                new FailingDomain("503: a store that does not answer",
                        new HookRejected(HookRejection.HOOK_HANDLER_FAILED, "STORE_UNAVAILABLE", new IllegalStateException("down")),
                        "HOOK_HANDLER_FAILED:STORE_UNAVAILABLE", 503),
                new FailingDomain("500: a failure nobody expected", new IllegalStateException("a bug"), "HOOK_HANDLER_FAILED", 500));
    }

    /** The start of the sentence the server gives for a refused gate (locales {@code hooks.gateRefused}); the code follows, then {@code )}. */
    static final String GATE_REFUSED = "[Sennard] The Sennard server refused to check this tool call (reason ";

    /** A named stand-in for the domain services. The verdict is whatever a test set; every call is kept for the test to read. */
    static final class ScriptedHostEventHandler implements HostEventHandler {
        final AtomicReference<HookVerdict> next = new AtomicReference<>(new HookVerdict.Proceed());
        final Deque<HookCall> calls = new ConcurrentLinkedDeque<>();
        /** What the domain throws instead of answering, as a domain does when it cannot answer (a lost first creation, a store that does not answer, a bug); null: it answers. */
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        @Override
        public HookVerdict handle(HookCall call) {
            calls.add(call);
            RuntimeException failing = failure.get();
            if (failing != null) {
                throw failing;
            }
            return next.get();
        }
    }

    /**
     * A host that exists only in this test source. It answers in a shape no product adapter knows, so a passing round trip through the
     * real controller means the controller needed no change to serve it.
     */
    static final class FourthHostTestAdapter implements HostHookAdapter {
        static final String LABEL = "fourth-host-test";

        @Override
        public HostId host() {
            return new HostId(LABEL);
        }

        /** What this host's hook gets: one field. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Input(String run) {
        }

        /** What this host's hook prints. */
        public record Answer(@JsonProperty("fourth-host-answer") String answer) implements HookOutput {
        }

        @Override
        public Class<Input> inputType() {
            return Input.class;
        }

        @Override
        public HostEvent read(String event, HookBody body) {
            Input input = body.bind(HookAdapters.MAPPER, Input.class);
            HostContext context = new HostContext(host(), input.run() == null ? "" : input.run(), SessionActor.root(Basis.OBSERVED, "TEST_HOST"),
                    null, null, null, null, null);
            return new HostEvent.SessionStarted(context, HostEvent.StartKind.STARTUP);
        }

        @Override
        public HookOutput respond(String event, HostEvent read, HookVerdict verdict) {
            return new Answer(verdict.getClass().getSimpleName());
        }

        @Override
        public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
            throw new UnsupportedOperationException("The test host has no package.");
        }

        @Override
        public List<HostRegistrationFile.WholeFile> shippedFiles() {
            throw new UnsupportedOperationException("The test host has no package.");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerBeans {
        @Bean
        @org.springframework.context.annotation.Primary
        ScriptedHostEventHandler scriptedHostEventHandler() {
            return new ScriptedHostEventHandler();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        HookPipeline transportHookPipeline(List<HostHookAdapter> adapters, ObjectProvider<HostEventHandler> handler,
                Messages messages, List<HookBoundary> boundaries) {
            // These transport fixtures use a scripted domain. The managed identity path has a real-store test.
            return new HookPipeline(adapters, handler, messages, boundaries);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FourthHostBeans {
        @Bean
        FourthHostTestAdapter fourthHostTestAdapter() {
            return new FourthHostTestAdapter();
        }
    }
}
