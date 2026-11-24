// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.execution.LogRetentionPolicy.Ended;

/**
 * Which log files are chosen to be deleted, from the sizes and the times the caller gives (no file, no clock: the moment is a value here).
 *
 * <pre>
 * p1  age          what ended the retention ago or longer ago is chosen, what ended later is not; the edge belongs to the deletion
 * p2  capacity     while the total is over the capacity the ones that ended longest ago are chosen, and no more than needed; a total equal to the capacity is not over it
 * p3  running      the running ones are never chosen and count in the total, so they make more of the ended ones go
 * p4  both         the ones past their age go first whatever their size, then the capacity decides among those that are left
 * p5  the order    longest ended first; the same time is ordered by id
 * p6  nothing      no ended executions: nothing is chosen, however much the running ones take
 * p7  the values   a retention that is not longer than zero and a capacity under one byte are refused
 * </pre>
 */
class LogRetentionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private static final long GIB = 1024L * 1024 * 1024;
    private final LogRetentionPolicy policy = new LogRetentionPolicy(SEVEN_DAYS, 100);

    private static Ended ended(String id, Duration ago, long bytes) {
        return new Ended(id, NOW.minus(ago), bytes);
    }

    private static List<String> ids(List<Ended> chosen) {
        return chosen.stream().map(Ended::id).toList();
    }

    @Test
    void whatEndedTheRetentionAgoOrLongerAgoIsChosenAndTheEdgeBelongsToTheDeletion() {
        List<Ended> all = List.of(ended("old", SEVEN_DAYS.plusMillis(1), 1), ended("edge", SEVEN_DAYS, 1), ended("fresh", SEVEN_DAYS.minusMillis(1), 1));

        assertEquals(List.of("old", "edge"), ids(policy.select(all, 0, NOW)));
    }

    @Test
    void overTheCapacityTheOnesThatEndedLongestAgoAreChosenAndNoMoreThanNeeded() {
        List<Ended> all = List.of(ended("c", Duration.ofHours(1), 40), ended("a", Duration.ofHours(3), 40), ended("b", Duration.ofHours(2), 40));

        // 120 bytes with 100 allowed: one is enough, and it is the oldest
        assertEquals(List.of("a"), ids(policy.select(all, 0, NOW)));
        // exactly the capacity is not over it
        assertEquals(List.of(), ids(new LogRetentionPolicy(SEVEN_DAYS, 120).select(all, 0, NOW)));
        // 119 allowed: still one
        assertEquals(List.of("a"), ids(new LogRetentionPolicy(SEVEN_DAYS, 119).select(all, 0, NOW)));
        // 79 allowed: two are needed
        assertEquals(List.of("a", "b"), ids(new LogRetentionPolicy(SEVEN_DAYS, 79).select(all, 0, NOW)));
    }

    @Test
    void theRunningOnesAreNeverChosenAndCountInTheTotal() {
        List<Ended> all = List.of(ended("a", Duration.ofHours(3), 30), ended("b", Duration.ofHours(2), 30));

        assertEquals(List.of(), ids(policy.select(all, 40, NOW)), "100 in all");
        assertEquals(List.of("a"), ids(policy.select(all, 41, NOW)), "101 in all: the running ones are not chosen, so an ended one goes");
        assertEquals(List.of("a", "b"), ids(policy.select(all, 500, NOW)), "all the ended ones go and the running ones stay over the capacity");
    }

    @Test
    void thoseThatAreTooOldGoFirstWhateverTheirSizeAndThenTheCapacityDecidesAmongTheRest() {
        List<Ended> all = List.of(ended("tiny-old", Duration.ofDays(8), 1), ended("big-new", Duration.ofHours(1), 90), ended("mid", Duration.ofHours(5), 30));

        // after the old one goes 120 is left, over 100: the one of the rest that ended longest ago
        assertEquals(List.of("tiny-old", "mid"), ids(policy.select(all, 0, NOW)));
    }

    @Test
    void theOrderIsLongestEndedFirstAndTheSameTimeIsOrderedById() {
        Instant same = NOW.minus(Duration.ofDays(9));
        List<Ended> all = List.of(new Ended("b", same, 1), ended("older", Duration.ofDays(10), 1), new Ended("a", same, 1));

        assertEquals(List.of("older", "a", "b"), ids(policy.select(all, 0, NOW)));
    }

    @Test
    void withNoEndedExecutionsNothingIsChosenHoweverMuchTheRunningOnesTake() {
        assertEquals(List.of(), policy.select(List.of(), 10 * GIB, NOW));
    }

    @Test
    void aRetentionNotLongerThanZeroAndACapacityUnderOneByteAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LogRetentionPolicy(Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogRetentionPolicy(Duration.ofDays(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> new LogRetentionPolicy(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogRetentionPolicy(SEVEN_DAYS, 0));
        assertEquals(new LogRetentionPolicy(Duration.ofMillis(1), 1), new LogRetentionPolicy(Duration.ofMillis(1), 1));
    }
}
