// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.identity.CallAdmissions;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Source;
import io.github.jaeseongs95.sennard.service.identity.LeaseService;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.JudgeMode;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.Judgment;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.RegisterRequest;
import io.github.jaeseongs95.sennard.service.session.LeaseOutcome;
import io.github.jaeseongs95.sennard.service.session.SessionKind;

/**
 * What the hooks' session side asks of the session registry: register, renew, end. It is plain data in and out, so the hook classes do not hold the registry's own
 * request and result types.
 */
@Component
public class SessionLifecycle {
    private final SessionRegistry registry;
    private final LeaseService leases;
    private final CallAdmissions admissions;
    private final ApplicationEventPublisher events;

    public SessionLifecycle(SessionRegistry registry, LeaseService leases, CallAdmissions admissions, ApplicationEventPublisher events) {
        this.registry = registry;
        this.leases = leases;
        this.admissions = admissions;
        this.events = events;
    }

    /**
     * Registers a session whose host process the server is watching.
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.ConflictException {@code CURRENT_GENERATION_PROTECTED} when another live process holds the session
     */
    public Registered register(Registration registration) {
        SessionRegistry.Registration registered = registry.register(registration.host(), new RegisterRequest(registration.hostSessionId(),
                registration.clientStartedAtMs(), registration.processKey(), registration.clientInstanceId(), null, null, registration.transport(), registration.deliveryJson()));
        return new Registered(registered.sessionRef(), registered.generation(), registered.relayId());
    }

    /** A hook of a session no process owns: keeps it usable with no owner, creating or recovering it if needed. */
    public void noteHook(HookSeen hook) {
        registry.judge(hook.host(), hook.hostSessionId(), Source.HOOK, null, hook.receivedAtMs(), JudgeMode.NOTIFY);
    }

    /** Extends the leases the server holds; every item gets its own answer. */
    public List<LeaseResult> renew(List<LeaseItem> items) {
        List<LeaseService.Item> request = items.stream().map(item -> new LeaseService.Item(item.sessionRef(), item.generation(), item.relayId())).toList();
        return leases.renew(request).items().stream().map(result -> new LeaseResult(result.sessionRef(), result.generation(), result.state())).toList();
    }

    /** The owner's end of a generation; a stale or fenced owner changes nothing. */
    public Ended end(OwnedEnd end) {
        return closed(registry.endByClient(end.sessionRef(), end.generation(), end.relayId(), end.reason(), end.endedAtMs()), end.reason());
    }

    /** A hook's end, for a session the server does not own. */
    public Ended end(HookEnd end) {
        return closed(registry.endByHook(end.host(), end.hostSessionId(), end.receivedAtMs(), end.reason()), end.reason());
    }

    private Ended closed(Judgment judged, String reason) {
        if (judged.accepted()) {
            events.publishEvent(new SessionClosed(judged.sessionRef(), judged.generation(), reason));
        }
        return new Ended(judged.accepted(), judged.reason());
    }

    /** Leaves the row a tool call will claim, on hosts whose tool call does not say by itself who called. */
    public void admit(CallAdmission admission) {
        admissions.leave(admission.host(), new CallAdmissions.Admission(admission.callId(), admission.tool(), admission.hostSessionId(), admission.agentId(),
                admission.model(), admission.effort(), admission.turnId(), admission.transcriptPath()));
    }

    /**
     * Says what kind of session a host session is (see {@link SessionKind}). A kind other than {@code MAIN} creates the session's row when it
     * has none, so a tool call that registers the session later finds it already marked; {@code MAIN} only corrects an existing row.
     */
    public void markKind(Marking marking) {
        registry.markKind(marking.host(), marking.hostSessionId(), marking.kind());
    }

    /** The owned generations still live, for a server that restarted. */
    public List<OwnedSession> activeOwned() {
        return registry.activeOwned().stream()
                .map(owned -> new OwnedSession(owned.sessionRef(), owned.generation(), owned.host(), owned.hostSessionId(), owned.processKey(), owned.relayId())).toList();
    }

    public record Marking(String host, String hostSessionId, SessionKind kind) {
    }

    /**
     * @param clientStartedAtMs when the hook saw the session start, on the PC's clock
     * @param processKey which host process this is
     * @param clientInstanceId this run of the server: a new one fences the previous run
     * @param deliveryJson how this session can be reached
     */
    public record Registration(String host, String hostSessionId, long clientStartedAtMs, String processKey, UUID clientInstanceId, String transport, String deliveryJson) {
    }

    /** @param relayId proof of ownership of this generation, presented on renewal and end */
    public record Registered(String sessionRef, long generation, UUID relayId) {
    }

    public record HookSeen(String host, String hostSessionId, long receivedAtMs) {
    }

    public record LeaseItem(String sessionRef, long generation, UUID relayId) {
    }

    /** @param state {@code RENEWED}, {@code EXPIRED}, or another outcome meaning the generation is no longer this server's */
    public record LeaseResult(String sessionRef, long generation, LeaseOutcome state) {
    }

    public record OwnedEnd(String sessionRef, long generation, UUID relayId, String reason, long endedAtMs) {
    }

    public record HookEnd(String host, String hostSessionId, String reason, long receivedAtMs) {
    }

    /** @param reason why it was not applied, null when it was */
    public record Ended(boolean applied, String reason) {
    }

    /** One observed call. Nullable fields are the ones the host did not report. */
    public record CallAdmission(String host, String callId, String tool, String hostSessionId, String agentId, String model, String effort, String turnId, String transcriptPath) {
    }

    public record OwnedSession(String sessionRef, long generation, String host, String hostSessionId, String processKey, UUID relayId) {
    }
}
