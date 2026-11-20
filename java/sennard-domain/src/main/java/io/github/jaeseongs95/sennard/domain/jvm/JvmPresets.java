// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.jvm;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Which preset a machine starts with. The JVM reports less than the RAM that is installed (the OS and the hardware keep a part), so the reading is
 * rounded up to the standard size it most likely belongs to (4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512 ... GB: the sizes alternate between a
 * power of two and one and a half times it), and the preset is the one of the largest memory class that is not above that size. A size between two classes
 * (12 GB, 24 GB, 48 GB, 96 GB) therefore takes the class below it. A GB here is 2^30 bytes, as RAM is sold.
 */
public final class JvmPresets {
    private static final long GIGABYTE = 1L << 30;
    private static final long SMALLEST = 4;

    private JvmPresets() {
    }

    /** @throws IllegalArgumentException when the reading is not positive */
    public static MemoryEstimate estimate(long observedBytes) {
        if (observedBytes <= 0) {
            throw new IllegalArgumentException("The total memory must be positive: " + observedBytes);
        }
        long size = standardGigabytes(observedBytes);
        JvmPreset preset = Arrays.stream(JvmPreset.values()).filter(candidate -> candidate.tierGigabytes() > 0 && candidate.tierGigabytes() <= size)
                .max(Comparator.comparingInt(JvmPreset::tierGigabytes)).orElseThrow();
        return new MemoryEstimate(observedBytes, size, preset);
    }

    /** The smallest standard memory size, in GB, that is not below the reading. */
    static long standardGigabytes(long observedBytes) {
        long observed = Math.ceilDiv(observedBytes, GIGABYTE);
        if (observed <= SMALLEST) {
            return SMALLEST;
        }
        for (long power = 8;; power *= 2) {
            if (observed <= power) {
                return power;
            }
            long between = power / 2 * 3;
            if (observed <= between) {
                return between;
            }
        }
    }
}
