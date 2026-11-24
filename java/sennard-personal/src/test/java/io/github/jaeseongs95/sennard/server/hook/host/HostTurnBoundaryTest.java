// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookBoundary;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookStopNotice;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFactsRegistry;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

class HostTurnBoundaryTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String STOP_REASON = "Previous input reached its workflow limit";

    static Stream<Arguments> stops() {
        return Stream.of(
                Arguments.of(new ClaudeHookAdapter(MAPPER), """
                        {"session_id":"root","stop_hook_active":true}
                        """, true, true),
                Arguments.of(new CodexHookAdapter(MAPPER), """
                        {"session_id":"root","stop_hook_active":true}
                        """, true, true),
                Arguments.of(new AntigravityHookAdapter(MAPPER), """
                        {"conversationId":"root","fullyIdle":true}
                        """, false, true),
                Arguments.of(new AntigravityHookAdapter(MAPPER), """
                        {"conversationId":"root","terminationReason":"USER_CANCELED"}
                        """, false, false));
    }

    @ParameterizedTest
    @MethodSource("stops")
    void terminalStopAndAlreadyContinuedAreIndependent(HostHookAdapter adapter, String body, boolean continued, boolean continuable) {
        HostEvent.TurnEnded ended = assertInstanceOf(HostEvent.TurnEnded.class, adapter.read("Stop", input(body)));
        assertEquals(continuable, ended.continuable());
        assertEquals(continued, ended.alreadyContinued());
    }

    @Test
    void promptAndFirstInvocationExposeTheSameInputBoundary() {
        String text = "<task-notification>finished</task-notification>";
        HookBody prompt = input("""
                {"session_id":"root","prompt":"%s"}
                """.formatted(text));
        HookBody firstInvocation = input("""
                {"conversationId":"root","invocationNum":0,"lastUserInput":"%s"}
                """.formatted(text));
        var antigravity = new AntigravityHookAdapter(MAPPER);
        for (HostEvent started : List.of(new ClaudeHookAdapter(MAPPER).read("UserPromptSubmit", prompt),
                new CodexHookAdapter(MAPPER).read("UserPromptSubmit", prompt), antigravity.read("PreInvocation", firstInvocation))) {
            assertTrue(started.startsTurn());
            assertEquals(text, started.turnInputText());
        }
        HostEvent later = antigravity.read("PreInvocation", input("""
                {"conversationId":"root","invocationNum":1,"lastUserInput":"%s"}
                """.formatted(text)));
        assertFalse(later.startsTurn());
        assertNull(later.turnInputText());
    }

    @Test
    void theSameHostFactsServeTheHookAndStoredSessionNames() {
        HostWorkFacts facts = () -> new HostId("wire-label", "stored-host");
        var registry = new HostWorkFactsRegistry(List.of(facts));
        assertSame(facts, registry.forHost(facts.host()));
        assertSame(facts, registry.forSessionHost("stored-host"));
        assertSame(HostWorkFacts.NONE, registry.forHost(new HostId("unknown")));
        assertSame(HostWorkFacts.NONE, registry.forSessionHost("wire-label"));
    }

    @Test
    void anInterceptedInvocationDoesNotTakeThePreviousStopNotice() {
        AtomicReference<String> pending = new AtomicReference<>();
        HookStopNotice notices = new HookStopNotice() {
            @Override
            public void rememberStop(HostId host, String session, String reason) {
                pending.set(reason);
            }

            @Override
            public String takeStopNotice(HostId host, String session) {
                return pending.getAndSet(null);
            }
        };
        StaticMessageSource catalog = new StaticMessageSource();
        catalog.addMessage("hooks.workflowGate.deferredStop", Locale.ENGLISH, STOP_REASON);
        Messages messages = new Messages(new MessageSourceAccessor(catalog, Locale.ENGLISH));
        var adapter = new AntigravityHookAdapter(MAPPER, new AntigravityCallerAdapter(MAPPER));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("handler", (HostEventHandler) call -> call.event() instanceof HostEvent.TurnEnded
                ? new HookVerdict.TerminateTurn(STOP_REASON) : new HookVerdict.Proceed());
        HookBoundary wake = event -> event instanceof HostEvent.TurnStarted started && "intercepted".equals(started.provisionalInput())
                ? Optional.of(new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock("Wake body", false))))
                : Optional.empty();
        HookPipeline pipeline = new HookPipeline(List.of(adapter), beans.getBeanProvider(HostEventHandler.class), messages, List.of(wake), null, notices);

        pipeline.answer("agy", "Stop", null, input("""
                {"conversationId":"root","terminationReason":"USER_CANCELED"}
                """), List.of());
        String intercepted = MAPPER.writeValueAsString(pipeline.answer("agy", "PreInvocation", null, input("""
                {"conversationId":"root","invocationNum":0,"lastUserInput":"intercepted"}
                """), List.of()));
        assertTrue(intercepted.contains("Wake body"), intercepted);
        assertFalse(intercepted.contains(STOP_REASON), intercepted);
        assertEquals(STOP_REASON, pending.get(), "the next real input still needs the notice");
        HookBody realInput = input("""
                {"conversationId":"root","invocationNum":0,"lastUserInput":"actual input"}
                """);
        String first = MAPPER.writeValueAsString(pipeline.answer("agy", "PreInvocation", null, realInput, List.of()));
        String repeated = MAPPER.writeValueAsString(pipeline.answer("agy", "PreInvocation", null, realInput, List.of()));
        assertTrue(first.contains(STOP_REASON), first);
        assertFalse(repeated.contains(STOP_REASON), repeated);
    }

    private static HookBody input(String json) {
        return HookBody.posted(json.getBytes(UTF_8));
    }
}
