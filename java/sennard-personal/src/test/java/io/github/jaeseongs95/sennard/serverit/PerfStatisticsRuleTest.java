// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.LongStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Cases H1 and H2 of the baseline's case table: the percentile rule the report's numbers come from. It runs with the perfBaseline task only. */
@Tag(PerfBaselineMeasurement.TAG)
class PerfStatisticsRuleTest {
    @Test
    void nearestRankOfTwoHundredValues() {
        long[] values = LongStream.rangeClosed(1, 200).toArray();
        assertEquals(100, PerfStatistics.percentile(values, 50));
        assertEquals(190, PerfStatistics.percentile(values, 95));
        assertEquals(200, PerfStatistics.max(values));
    }

    @Test
    void unsortedInputIsNotChanged() {
        long[] values = {5, 1, 4, 2, 3};
        assertEquals(3, PerfStatistics.percentile(values, 50));
        assertEquals(5, values[0], "the caller's array stays as it was");
    }

    @Test
    void oneValueIsEveryPercentile() {
        long[] one = {42};
        assertEquals(42, PerfStatistics.percentile(one, 50));
        assertEquals(42, PerfStatistics.percentile(one, 95));
        assertEquals(42, PerfStatistics.percentile(one, 100));
    }

    @Test
    void noValuesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PerfStatistics.percentile(new long[0], 50));
    }

    @Test
    void windowsKeepOnlyTheInstantsInsideThem() {
        long[] starts = {10, 30};
        long[] ends = {20, 40};
        assertEquals(true, PerfStatistics.inside(starts, ends, 10));
        assertEquals(true, PerfStatistics.inside(starts, ends, 15));
        assertEquals(true, PerfStatistics.inside(starts, ends, 40));
        assertEquals(false, PerfStatistics.inside(starts, ends, 25));
        assertEquals(false, PerfStatistics.inside(starts, ends, 5));
        assertEquals(false, PerfStatistics.inside(starts, ends, 41));
    }

    @Test
    void clockStepIsTheSmallestGapBetweenDifferentPositiveValues() {
        assertEquals(15, PerfStatistics.smallestStep(new long[] {0, 15, 30, 60, 60, 0}));
        assertEquals(0, PerfStatistics.smallestStep(new long[] {0, 0, 7}));
    }
}
