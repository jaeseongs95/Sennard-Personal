// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.ServerHookAdapter;

/**
 * The idle wake of a host whose hook can hold a request open: the request stays here, on a virtual thread and with no database
 * connection, until a message for the session commits. A 2xx is what wakes the session, so it is answered only with a message; the body is
 * the envelope the hook prints. Every other ending is a {@link HookRejected} (a status above 299 and the reason, answered by the one error advice like any refused hook
 * call), which the hook turns into not waking.
 *
 * <p>ceiling: the hold is a day (sennard.hooks.wake-hold-ms), a little under what the host's hook waits. A session idle for longer, or one whose
 * wait a server restart cut, is woken again only after its next turn registers a new hook.
 */
@RestController
public class WakeController {
    /** A day less a minute; a host's hook waits a little longer, so this answer comes first. */
    public static final long DEFAULT_HOLD_MS = 86_340_000;

    private static final MediaType MESSAGE = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final Map<String, ServerHookAdapter> adapters;
    private final WakeWaiters waiters;
    private final WakeClaims claims;
    private final Duration hold;

    public WakeController(List<ServerHookAdapter> adapters, WakeWaiters waiters, WakeClaims claims, SennardProperties properties) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(adapter -> adapter.host().label(), Function.identity()));
        this.waiters = waiters;
        this.claims = claims;
        this.hold = properties.hooks().wakeHoldMs();
    }

    @PostMapping("/hooks/v1/{label}/{event:[A-Za-z]+}/wake")
    public ResponseEntity<String> wake(@PathVariable String label, @PathVariable String event, @RequestBody(required = false) byte[] body) throws InterruptedException {
        ServerHookAdapter adapter = adapters.get(label);
        if (adapter == null) {
            throw new HookRejected(HookRejection.HOOK_HOST_UNKNOWN);
        }
        // The two points a session is idle from: its start, and the end of a turn.
        HostEvent idleFrom = adapter.read(event, HookBody.posted(body));
        if (!(idleFrom instanceof HostEvent.SessionStarted || idleFrom instanceof HostEvent.TurnEnded)) {
            throw new HookRejected(HookRejection.HOOK_WAKE_EVENT_INVALID);
        }
        String sessionId = idleFrom.context().sessionId();
        String host = adapter.host().sessionHost();
        long deadline = System.nanoTime() + hold.toNanos();
        try (WakeWaiters.Waiter waiter = waiters.register(host, sessionId)) {
            while (true) {
                waiter.rearm();
                Optional<String> text = claims.claim(host, sessionId);
                if (text.isPresent()) {
                    return ResponseEntity.ok().contentType(MESSAGE).body(text.orElseThrow());
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new HookRejected(HookRejection.HOOK_WAKE_NO_MESSAGE);
                }
                switch (waiter.await(Duration.ofNanos(left))) {
                    case SIGNALLED -> {
                    }
                    case TIMED_OUT -> throw new HookRejected(HookRejection.HOOK_WAKE_NO_MESSAGE);
                    case REPLACED -> throw new HookRejected(HookRejection.HOOK_WAKE_REPLACED);
                    case RELEASED -> throw new HookRejected(HookRejection.HOOK_WAKE_SESSION_NOT_IDLE);
                    case CLOSING -> throw new HookRejected(HookRejection.HOOK_WAKE_SERVER_STOPPING);
                }
            }
        }
    }
}
