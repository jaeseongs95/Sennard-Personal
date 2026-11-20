// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which preset a machine starts with, from the one memory reading the installer takes. The reading is rounded up to the smallest standard size
 * (4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384 ... GB) and the preset is the largest memory class (4, 8, 16, 32, 64, 128) not above that size.
 *
 * <pre>
 * reading GB   standard GB   preset         why
 * 3.5          4             G1_224M        up to 4 GB
 * 4            4             G1_224M        exactly a standard size stays in it
 * 6            8             G1_512M
 * 8            8             G1_512M
 * 11.9         12            G1_512M        12 is between the 8 and 16 classes: the class below
 * 12           12            G1_512M
 * 16           16            G1_1G
 * 24           24            G1_1G
 * 29.7         32            G1_2G          what a 32 GB machine reports (the author's PC reports 29.67)
 * 32           32            G1_2G
 * 48           48            G1_2G
 * 64           64            G1_4G          ZGC_4G is the choice at this class, never the default
 * 96           96            G1_4G
 * 128          128           ZGC_6_PERCENT
 * 200          256           ZGC_6_PERCENT
 * edges: one byte over a standard size is the next one; 1 byte is 4 GB; 0 and below are refused; a size beyond the listed ones keeps alternating
 * </pre>
 */
class JvmPresetsTest {
    private static final long GIGABYTE = 1L << 30;

    @ParameterizedTest
    @CsvSource({
        "3.5,4,G1_224M", "4,4,G1_224M", "6,8,G1_512M", "8,8,G1_512M", "11.9,12,G1_512M", "12,12,G1_512M", "16,16,G1_1G", "24,24,G1_1G",
        "29.7,32,G1_2G", "32,32,G1_2G", "48,48,G1_2G", "64,64,G1_4G", "96,96,G1_4G", "128,128,ZGC_6_PERCENT", "200,256,ZGC_6_PERCENT"
    })
    void aReadingBecomesTheStandardSizeAndTheDefaultPresetOfItsClass(double readingGigabytes, long standard, JvmPreset expected) {
        MemoryEstimate estimate = JvmPresets.estimate((long) (readingGigabytes * GIGABYTE));
        assertEquals(standard, estimate.estimatedGigabytes());
        assertEquals(expected, estimate.preset());
        assertEquals((long) (readingGigabytes * GIGABYTE), estimate.observedBytes());
    }

    @Test
    void oneByteOverAStandardSizeIsTheNextSize() {
        assertEquals(4, JvmPresets.estimate(1).estimatedGigabytes());
        assertEquals(4, JvmPresets.estimate(4 * GIGABYTE).estimatedGigabytes());
        assertEquals(8, JvmPresets.estimate(4 * GIGABYTE + 1).estimatedGigabytes());
        assertEquals(JvmPreset.G1_512M, JvmPresets.estimate(4 * GIGABYTE + 1).preset());
        assertEquals(JvmPreset.G1_4G, JvmPresets.estimate(64 * GIGABYTE).preset());
        assertEquals(96, JvmPresets.estimate(64 * GIGABYTE + 1).estimatedGigabytes());
        assertEquals(JvmPreset.G1_4G, JvmPresets.estimate(64 * GIGABYTE + 1).preset());
        assertEquals(JvmPreset.ZGC_6_PERCENT, JvmPresets.estimate(96 * GIGABYTE + 1).preset());
    }

    @Test
    void theStandardSizesKeepAlternatingBeyondTheListedOnes() {
        assertEquals(192, JvmPresets.estimate(129 * GIGABYTE).estimatedGigabytes());
        assertEquals(256, JvmPresets.estimate(193 * GIGABYTE).estimatedGigabytes());
        assertEquals(384, JvmPresets.estimate(300 * GIGABYTE).estimatedGigabytes());
        assertEquals(512, JvmPresets.estimate(500 * GIGABYTE).estimatedGigabytes());
        assertEquals(1024, JvmPresets.estimate(1000 * GIGABYTE).estimatedGigabytes());
        assertEquals(JvmPreset.ZGC_6_PERCENT, JvmPresets.estimate(Long.MAX_VALUE).preset());
    }

    @Test
    void aReadingThatIsNotPositiveIsRefusedNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> JvmPresets.estimate(0));
        assertThrows(IllegalArgumentException.class, () -> JvmPresets.estimate(-1));
    }

    @Test
    void theStandardSizeNeverFallsBelowTheReadingAndNeverSkipsOne() {
        long previous = 0;
        for (long gigabytes = 1; gigabytes <= 2048; gigabytes++) {
            long standard = JvmPresets.estimate(gigabytes * GIGABYTE).estimatedGigabytes();
            assertTrue(standard >= gigabytes, gigabytes + " GB became " + standard);
            assertTrue(standard >= previous, "the size never goes down as the reading goes up: " + gigabytes);
            assertTrue(standard < gigabytes * 3 / 2 + 4, gigabytes + " GB became " + standard + ", which skips a standard size");
            previous = standard;
        }
    }

    @Test
    void theDefaultNeverIsTheChoiceOnlyPreset() {
        Set<JvmPreset> reached = new HashSet<>();
        for (long gigabytes = 1; gigabytes <= 1024; gigabytes++) {
            reached.add(JvmPresets.estimate(gigabytes * GIGABYTE).preset());
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(JvmPreset.ZGC_4G)), reached);
        assertEquals(0, JvmPreset.ZGC_4G.tierGigabytes());
    }

    @Test
    void eachPresetNamesOneCollectorAndOneHeapBound() {
        assertEquals(List.of("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-Xmx224m"), JvmPreset.G1_224M.options());
        assertEquals(List.of("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-Xmx512m"), JvmPreset.G1_512M.options());
        assertEquals(List.of("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-Xmx1g"), JvmPreset.G1_1G.options());
        assertEquals(List.of("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-Xmx2g"), JvmPreset.G1_2G.options());
        assertEquals(List.of("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-Xmx4g"), JvmPreset.G1_4G.options());
        assertEquals(List.of("-XX:+UseZGC", "-Xmx4g"), JvmPreset.ZGC_4G.options());
        assertEquals(List.of("-XX:+UseZGC", "-XX:MaxRAMPercentage=6"), JvmPreset.ZGC_6_PERCENT.options());
        for (JvmPreset preset : JvmPreset.values()) {
            assertEquals(1, preset.options().stream().filter(option -> option.startsWith("-XX:+Use") && option.endsWith("GC")).count(), preset.name());
            assertFalse(preset.options().stream().anyMatch(option -> option.contains(" ")), "no option holds a space: " + preset);
        }
    }

    @Test
    void aCollectorsCacheHasItsOwnFile() {
        assertEquals("app-g1.aot", Collector.G1.cacheFileName());
        assertEquals("app-zgc.aot", Collector.ZGC.cacheFileName());
    }
}
