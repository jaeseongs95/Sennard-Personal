// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveWriter;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.hook.HookActorObserved;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.session.HookDomain;

/**
 * Keeps what the user said: at a prompt of a main session, the hook writes the text to the archive word for word ({@link PromptArchiveWriter}), the one place the archive is
 * written from. It runs right after the observations of the session ({@code @Order(5)}: the session is known) and before the domains that answer the prompt, so the words are
 * kept whatever those answer. The MCP tools can read the archive and record a recap but have no way to write a prompt; the machine check holds them to it.
 *
 * <p>This is not an advisory observation: a prompt that could not be kept is a hook that failed. The failure is logged once here and thrown again as it is: the HTTP boundary
 * answers an {@code SennardException} with the status of its category ({@code SennardExceptionHandler}: 503 when the store is unavailable, 422 for a value the store refuses, 400 for
 * input that fails validation), always above 299, so the host's own failure behaviour applies and the server never answers as if the words were kept. (Wrapping it in a
 * {@code HookRejected} would change nothing: the advice that handles the exception's cause answers it too.) A child agent's prompts are not the user's and are not kept.
 */
@Component
@Order(7)
public class PromptCollector implements HookDomain {
    private static final Logger LOG = LoggerFactory.getLogger(PromptCollector.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();
    private static final int PENDING_INPUT_BYTES = 64 * 1024 * 1024;
    private static final Duration PENDING_LIFETIME = Duration.ofMinutes(30);

    private final PromptArchiveWriter writer;
    private final RootSessions roots;
    private final Clock clock;
    private final Map<HostId, HostCallerAdapter> adapters;
    // Unknown conversations can be children. Keep their input in bounded volatile memory; only confirmed roots reach the archive.
    private final Cache<PromptConversation, ArrayDeque<ReceivedPrompt>> pending = Caffeine.newBuilder().maximumWeight(PENDING_INPUT_BYTES)
            .<PromptConversation, ArrayDeque<ReceivedPrompt>>weigher((key, inputs) ->
                    (int) Math.min(Integer.MAX_VALUE, inputs.stream().mapToLong(ReceivedPrompt::weight).sum()))
            .expireAfterWrite(PENDING_LIFETIME).build();

    public PromptCollector(PromptArchiveWriter writer, RootSessions roots, Clock clock, List<HostCallerAdapter> adapters) {
        this.writer = writer;
        this.roots = roots;
        this.clock = clock;
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(HostCallerAdapter::host, Function.identity()));
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        HostContext context = call.event().context();
        if (call.module() != null || context.actor().role() != Role.ROOT) {
            return PROCEED;
        }
        if (call.event() instanceof HostEvent.UserPromptSubmitted submitted) {
            HostCallerAdapter adapter = adapters.get(context.hostLabel());
            ReceivedPrompt input = input(context, submitted.prompt());
            if (adapter != null && adapter.currentHookActor(context.sessionId()) != null) {
                reconcile(new PromptConversation(context.hostLabel(), context.sessionId()), input);
            } else {
                roots.of(context).ifPresent(session -> keep(session.host(), session.sessionId(), input));
            }
        } else if (call.event() instanceof HostEvent.TurnStarted started && started.provisionalInput() != null) {
            reconcile(new PromptConversation(context.hostLabel(), context.sessionId()), input(context, started.provisionalInput()));
        }
        return PROCEED;
    }

    @EventListener
    public void on(HookActorObserved observed) {
        reconcile(new PromptConversation(observed.host(), observed.conversation()), null);
    }

    private ReceivedPrompt input(HostContext context, String text) {
        return new ReceivedPrompt(text, context.transcriptPath(), clock.instant());
    }

    private void reconcile(PromptConversation conversation, ReceivedPrompt input) {
        HostCallerAdapter adapter = adapters.get(conversation.host());
        if (adapter == null) {
            return;
        }
        pending.asMap().compute(conversation, (key, queued) -> {
            SessionActor actor = adapter.currentHookActor(key.id());
            if (actor == null || (!actor.rootEstablished() && actor.basis() != Basis.PROVISIONAL)) {
                return null;
            }
            if (queued != null) {
                Instant oldest = clock.instant().minus(PENDING_LIFETIME);
                queued.removeIf(item -> item.receivedAt().isBefore(oldest));
            }
            if (actor.rootEstablished()) {
                while (queued != null && !queued.isEmpty()) {
                    keep(key.host().sessionHost(), key.id(), queued.getFirst());
                    queued.removeFirst();
                }
                if (input != null) {
                    keep(key.host().sessionHost(), key.id(), input);
                }
                return null;
            }
            if (input == null) {
                return queued;
            }
            ArrayDeque<ReceivedPrompt> inputs = queued == null ? new ArrayDeque<>() : new ArrayDeque<>(queued);
            inputs.addLast(input);
            return inputs;
        });
    }

    private void keep(String host, String session, ReceivedPrompt input) {
        try {
            writer.storeReceivedAt(host, session, input.text(), input.transcript(), input.receivedAt());
        } catch (SennardException failure) {
            LOG.warn("A user prompt was not archived: host={} reason={}", host, failure.code().name(), failure);
            throw failure;
        }
    }
}

record PromptConversation(HostId host, String id) {
}

record ReceivedPrompt(String text, String transcript, Instant receivedAt) {
    long weight() {
        return 128L + 2L * (text.length() + (transcript == null ? 0L : transcript.length()));
    }
}
