// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Decision;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Event;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Phase;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Refusal;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Relay;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Source;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.State;
import io.github.jaeseongs95.sennard.service.identity.GenerationTable.Verdict;

/**
 * GEN-TABLE-1: the late-event table of GenerationTable, transcribed here row by row in the document's order (the first row
 * that matches decides), against every combination of phase, generation, relay, hook time, end time, start time, origin and caller.
 * A combination no row matches fails the test, so the table and the code cannot both be silent about a case.
 */
class GenerationTableTest {
    private static final long G = 5;
    private static final long START = 1_000;
    private static final long END_AT = 1_000;

    private static final Decision STALE = new Decision(Verdict.NO_EFFECT, Refusal.GENERATION_STALE, false, false);
    private static final Decision FENCED = new Decision(Verdict.NO_EFFECT, Refusal.RELAY_FENCED, false, false);
    private static final Decision OWNED = new Decision(Verdict.NO_EFFECT, Refusal.OWNED_BY_CLIENT, false, false);
    private static final Decision REQUIRED = new Decision(Verdict.NO_EFFECT, Refusal.GENERATION_REQUIRED, false, false);
    private static final Decision EXPIRED = new Decision(Verdict.NO_EFFECT, Refusal.GENERATION_EXPIRED, false, false);
    private static final Decision ENDED = new Decision(Verdict.NO_EFFECT, Refusal.GENERATION_ENDED, false, false);
    private static final Decision NONE = new Decision(Verdict.NO_EFFECT, null, false, false);
    private static final Decision END = new Decision(Verdict.END, null, false, false);
    private static final Decision END_FILL = new Decision(Verdict.END, null, true, false);
    private static final Decision ACCEPT = new Decision(Verdict.ACCEPT, null, false, false);
    private static final Decision ACCEPT_TOUCH = new Decision(Verdict.ACCEPT, null, false, true);
    private static final Decision ACCEPT_FILL = new Decision(Verdict.ACCEPT, null, true, true);
    private static final Decision RECOVER = new Decision(Verdict.RECOVER, null, false, false);

    private record Row(String name, List<Phase> phases, BiPredicate<State, Event> when, BiFunction<State, Event, Decision> then) {
    }

    private static Row row(String name, Phase phase, BiPredicate<State, Event> when, Decision then) {
        return new Row(name, List.of(phase), when, (state, event) -> then);
    }

    private static boolean client(Event e) {
        return e.source() == Source.CLIENT;
    }

    private static boolean hook(Event e) {
        return e.source() == Source.HOOK;
    }

    private static boolean toolOrCli(Event e) {
        return e.source() == Source.TOOL || e.source() == Source.CLI;
    }

    private static boolean same(State s, Event e) {
        return e.generation() != null && e.generation() == s.generation();
    }

    private static boolean differs(State s, Event e) {
        return e.generation() != null && e.generation() != s.generation();
    }

    private static boolean absent(Event e) {
        return e.generation() == null;
    }

    /** 5.5, in order. */
    private static final List<Row> TABLE = List.of(
            row("S1 owner's end", Phase.S1, (s, e) -> client(e) && same(s, e) && e.relay() == Relay.MATCH, END),
            row("S1 client end, g != G", Phase.S1, (s, e) -> client(e) && differs(s, e), STALE),
            row("S1 client end, relay differs", Phase.S1, (s, e) -> client(e) && same(s, e) && e.relay() == Relay.MISMATCH, FENCED),
            row("S1 hook end", Phase.S1, (s, e) -> hook(e) && e.end(), OWNED),
            row("S1 g = G", Phase.S1, GenerationTableTest::same, ACCEPT),
            row("S1 g != G", Phase.S1, GenerationTableTest::differs, STALE),
            row("S1 no g, hook", Phase.S1, (s, e) -> absent(e) && hook(e), REQUIRED),
            row("S1 tool or CLI", Phase.S1, (s, e) -> toolOrCli(e), ACCEPT),
            row("S3 tool or CLI", Phase.S3, (s, e) -> toolOrCli(e), ACCEPT_TOUCH),
            row("S2 end", Phase.S2, (s, e) -> e.end(), EXPIRED),
            row("S2 g != G", Phase.S2, GenerationTableTest::differs, STALE),
            row("S2 no g or g = G", Phase.S2, (s, e) -> absent(e) || same(s, e), RECOVER),
            row("S3 client end", Phase.S3, (s, e) -> client(e), FENCED),
            row("S3 H, no S, end", Phase.S3, (s, e) -> e.hookStartedAtMs() != null && s.clientStartedAtMs() == null && e.end(), END_FILL),
            row("S3 H, no S", Phase.S3, (s, e) -> e.hookStartedAtMs() != null && s.clientStartedAtMs() == null, ACCEPT_FILL),
            new Row("S3 S <= H", List.of(Phase.S3), (s, e) -> s.clientStartedAtMs() <= e.hookStartedAtMs(), (s, e) -> e.end() ? END : ACCEPT_TOUCH),
            row("S3 S > H, recovery, not end", Phase.S3, (s, e) -> s.clientStartedAtMs() > e.hookStartedAtMs() && s.recoveryOrigin() && !e.end(), ACCEPT_TOUCH),
            row("S3 S > H", Phase.S3, (s, e) -> s.clientStartedAtMs() > e.hookStartedAtMs(), STALE),
            row("S0 end", Phase.S0, (s, e) -> e.end(), NONE),
            row("S4 end", Phase.S4, (s, e) -> e.end(), NONE),
            row("S0 other", Phase.S0, (s, e) -> true, RECOVER),
            row("S4 other", Phase.S4, (s, e) -> true, RECOVER),
            row("S5 end", Phase.S5, (s, e) -> e.end(), NONE),
            row("S5 g != G", Phase.S5, GenerationTableTest::differs, STALE),
            row("S5 H <= E", Phase.S5, (s, e) -> s.clientEndedAtMs() != null && e.hookStartedAtMs() != null && e.hookStartedAtMs() <= s.clientEndedAtMs(), STALE),
            row("S5 g = G, E or H unknown", Phase.S5, (s, e) -> same(s, e) && (s.clientEndedAtMs() == null || e.hookStartedAtMs() == null), ENDED),
            row("S5 no g, hook, no E", Phase.S5, (s, e) -> absent(e) && hook(e) && s.clientEndedAtMs() == null, ENDED),
            row("S5 H > E", Phase.S5, (s, e) -> (absent(e) || same(s, e)) && s.clientEndedAtMs() != null && e.hookStartedAtMs() != null
                    && e.hookStartedAtMs() > s.clientEndedAtMs(), RECOVER),
            row("S5 no g, no H", Phase.S5, (s, e) -> absent(e) && e.hookStartedAtMs() == null, RECOVER));

    private static Decision expected(State state, Event event) {
        for (Row row : TABLE) {
            if (row.phases().contains(state.phase()) && row.when().test(state, event)) {
                return row.then().apply(state, event);
            }
        }
        return fail("No row of 5.5 matches " + state + " " + event);
    }

    private static boolean consistent(Source source, boolean end, Long generation, Relay relay, Long hook) {
        return switch (source) {
            case CLIENT -> end && generation != null && relay != Relay.ABSENT && hook == null;
            case HOOK -> relay == Relay.ABSENT && hook != null;
            case TOOL, CLI -> !end && generation == null && relay == Relay.ABSENT && hook == null;
        };
    }

    @Test
    void everyCombinationMatchesTheFirstRowOfTheTable() {
        List<Long> startedAt = Arrays.asList(null, START);
        List<Long> endedAt = Arrays.asList(null, END_AT);
        List<Long> hookAt = Arrays.asList(null, START - 1, START, START + 1);
        int combinations = 0;
        for (Phase phase : Phase.values()) {
            long current = phase == Phase.S0 ? 0 : G;
            List<Long> generations = Arrays.asList(current, current - 1, current + 1, null);
            for (Long started : startedAt) {
                for (Long ended : endedAt) {
                    for (boolean recovery : new boolean[] {false, true}) {
                        State state = new State(phase, current, started, ended, recovery);
                        for (Source source : Source.values()) {
                            for (boolean end : new boolean[] {false, true}) {
                                for (Long generation : generations) {
                                    for (Relay relay : Relay.values()) {
                                        for (Long hook : hookAt) {
                                            if (!consistent(source, end, generation, relay, hook)) {
                                                continue;
                                            }
                                            Event event = new Event(source, end, generation, relay, hook);
                                            assertEquals(expected(state, event), GenerationTable.decide(state, event), state + " " + event);
                                            combinations += 1;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 5.5: H is the hook's own time, NULL only for a tool or CLI call, and the client form of session.end names a generation.
        // A hook: end or not x 4 g (the three values and none) x 3 H. The client: always an end x 3 g x 2 relays. A tool, a CLI: one each.
        int hookEvents = 2 * 4 * 3;
        int clientEvents = 3 * 2;
        assertEquals(6 * 2 * 2 * 2 * (hookEvents + clientEvents + 1 + 1), combinations);
    }

    @Test
    void anEventNoCallerCanSendIsRefusedBeforeTheTable() {
        List<Object[]> impossible = new ArrayList<>();
        impossible.add(new Object[] {Source.CLIENT, false, G, Relay.MATCH, null});
        impossible.add(new Object[] {Source.CLIENT, true, null, Relay.MATCH, null});
        impossible.add(new Object[] {Source.CLIENT, true, G, Relay.ABSENT, null});
        impossible.add(new Object[] {Source.CLIENT, true, G, Relay.MATCH, START});
        impossible.add(new Object[] {Source.HOOK, false, G, Relay.MATCH, START});
        impossible.add(new Object[] {Source.HOOK, false, G, Relay.ABSENT, null});
        impossible.add(new Object[] {Source.TOOL, true, null, Relay.ABSENT, null});
        impossible.add(new Object[] {Source.TOOL, false, G, Relay.ABSENT, null});
        impossible.add(new Object[] {Source.CLI, false, null, Relay.ABSENT, START});
        for (Object[] e : impossible) {
            assertThrows(IllegalArgumentException.class, () -> new Event((Source) e[0], (boolean) e[1], (Long) e[2], (Relay) e[3], (Long) e[4]), Arrays.toString(e));
        }
        assertTrue(TABLE.size() >= 29, "the transcription lost a row");
    }

    /** No margin is added to PC times (5.4): one millisecond before the start is late. */
    @Test
    void aHookOneMillisecondBeforeTheGenerationIsStale() {
        State state = new State(Phase.S3, G, START, null, false);
        assertEquals(STALE, GenerationTable.decide(state, new Event(Source.HOOK, true, null, Relay.ABSENT, START - 1)));
        assertEquals(END, GenerationTable.decide(state, new Event(Source.HOOK, true, null, Relay.ABSENT, START)));
    }
}
