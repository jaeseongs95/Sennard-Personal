// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

/**
 * The late-event table of a generation as a pure function: the first row that matches decides. Reading the rows and
 * applying the result are {@link SessionRegistry}'s job, by conditional statements; this class holds the verdicts only, so every combination
 * can be tested without a database. PC times are compared with PC times, generations with generations, and no margin is added (5.4).
 */
public final class GenerationTable {
    /** The current generation's state (5.5). */
    public enum Phase { S0, S1, S2, S3, S4, S5 }

    public enum Source { HOOK, CLIENT, TOOL, CLI }

    /** Whether the relayId of a client's session.end matches the current generation's; HOOK, TOOL and CLI carry none. */
    public enum Relay { ABSENT, MATCH, MISMATCH }

    /** @param generation the current generation number; 0 for S0 */
    public record State(Phase phase, long generation, Long clientStartedAtMs, Long clientEndedAtMs, boolean recoveryOrigin) {
    }

    /** One event. A hook always carries its PC start time; a tool call or a CLI call has neither generation nor time and is never an end. */
    public record Event(Source source, boolean end, Long generation, Relay relay, Long hookStartedAtMs) {
        public Event {
            boolean client = source == Source.CLIENT;
            if (client != (relay != Relay.ABSENT) || client && (!end || generation == null || hookStartedAtMs != null)
                    || source == Source.HOOK && hookStartedAtMs == null
                    || (source == Source.TOOL || source == Source.CLI) && (end || generation != null || hookStartedAtMs != null)) {
                throw new IllegalArgumentException("Inconsistent event: " + source);
            }
        }
    }

    public enum Verdict { ACCEPT, END, RECOVER, NO_EFFECT }

    /** Why a late event is refused (5.5); the constant's name is the reason the wire shows. */
    public enum Refusal { GENERATION_STALE, RELAY_FENCED, OWNED_BY_CLIENT, GENERATION_REQUIRED, GENERATION_EXPIRED, GENERATION_ENDED }

    /**
     * @param reason        the reason of a refusal, null otherwise or for the plain no-effect rows
     * @param fillStartedAt S := H (a recovered generation first reported by a hook)
     * @param touchUnowned  extend the lease of this unowned generation
     */
    public record Decision(Verdict verdict, Refusal reason, boolean fillStartedAt, boolean touchUnowned) {
    }

    private static final Decision STALE = refuse(Refusal.GENERATION_STALE);
    private static final Decision FENCED = refuse(Refusal.RELAY_FENCED);
    private static final Decision OWNED = refuse(Refusal.OWNED_BY_CLIENT);
    private static final Decision REQUIRED = refuse(Refusal.GENERATION_REQUIRED);
    private static final Decision EXPIRED = refuse(Refusal.GENERATION_EXPIRED);
    private static final Decision ENDED = refuse(Refusal.GENERATION_ENDED);
    private static final Decision NO_EFFECT = new Decision(Verdict.NO_EFFECT, null, false, false);
    private static final Decision ACCEPT = new Decision(Verdict.ACCEPT, null, false, false);
    private static final Decision ACCEPT_TOUCH = new Decision(Verdict.ACCEPT, null, false, true);
    private static final Decision RECOVER = new Decision(Verdict.RECOVER, null, false, false);
    private static final Decision END = new Decision(Verdict.END, null, false, false);

    private GenerationTable() {
    }

    private static Decision refuse(Refusal reason) {
        return new Decision(Verdict.NO_EFFECT, reason, false, false);
    }

    public static Decision decide(State state, Event event) {
        long current = state.generation();
        Long sent = event.generation();
        Long hookStart = event.hookStartedAtMs();
        Long start = state.clientStartedAtMs();
        Long end = state.clientEndedAtMs();
        boolean differs = sent != null && sent != current;
        boolean hook = event.source() == Source.HOOK;
        boolean toolOrCli = event.source() == Source.TOOL || event.source() == Source.CLI;
        switch (state.phase()) {
            case S1 -> {
                if (event.source() == Source.CLIENT) {
                    return differs ? STALE : event.relay() == Relay.MATCH ? END : FENCED;
                }
                if (hook && event.end()) {
                    return OWNED;
                }
                if (sent != null) {
                    return differs ? STALE : ACCEPT;
                }
                return hook ? REQUIRED : ACCEPT; // A tool call or a CLI call: not judged by generation.
            }
            case S2 -> {
                if (event.end()) {
                    return EXPIRED;
                }
                return differs ? STALE : RECOVER;
            }
            case S3 -> {
                if (toolOrCli) {
                    return ACCEPT_TOUCH;
                }
                if (event.source() == Source.CLIENT) {
                    return FENCED;
                }
                if (start == null) {
                    return event.end() ? new Decision(Verdict.END, null, true, false) : new Decision(Verdict.ACCEPT, null, true, true);
                }
                if (start <= hookStart) {
                    return event.end() ? END : ACCEPT_TOUCH;
                }
                return state.recoveryOrigin() && !event.end() ? ACCEPT_TOUCH : STALE;
            }
            case S0, S4 -> {
                return event.end() ? NO_EFFECT : RECOVER;
            }
            case S5 -> {
                if (event.end()) {
                    return NO_EFFECT;
                }
                if (differs) {
                    return STALE;
                }
                if (end != null && hookStart != null && hookStart <= end) {
                    return STALE;
                }
                if (sent != null && (end == null || hookStart == null)) {
                    return ENDED;
                }
                if (sent == null && hook && end == null) {
                    return ENDED;
                }
                return RECOVER; // g absent or equal: a hook started after the end, or a tool call or a CLI call.
            }
            default -> throw new IllegalStateException("Unknown phase");
        }
    }
}
