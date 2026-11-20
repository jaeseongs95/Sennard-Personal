// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.jvm;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's garbage collector and heap, chosen as one closed value: there is no free text for a JVM option, so the admin page offers exactly
 * these and nothing it signs can put another option into the launcher.
 *
 * <p>{@code tierGigabytes} is the memory class whose default the preset is: the installer picks the preset of the largest class that is not above the
 * machine's estimated memory ({@link JvmPresets}). A preset with 0 is never a default; it is a choice (ZGC with the heap of the 64 GB class).
 */
public enum JvmPreset {
    G1_224M(Collector.G1, "-Xmx224m", 4),
    G1_512M(Collector.G1, "-Xmx512m", 8),
    G1_1G(Collector.G1, "-Xmx1g", 16),
    G1_2G(Collector.G1, "-Xmx2g", 32),
    G1_4G(Collector.G1, "-Xmx4g", 64),
    ZGC_4G(Collector.ZGC, "-Xmx4g", 0),
    /** The heap follows the machine: a few percent of what is installed, where a fixed size would leave a large machine's memory unused. */
    ZGC_6_PERCENT(Collector.ZGC, "-XX:MaxRAMPercentage=6", 128);

    private final Collector collector;
    private final String heapOption;
    private final int tierGigabytes;

    JvmPreset(Collector collector, String heapOption, int tierGigabytes) {
        this.collector = collector;
        this.heapOption = heapOption;
        this.tierGigabytes = tierGigabytes;
    }

    public Collector collector() {
        return collector;
    }

    /** The one option that bounds the heap. */
    public String heapOption() {
        return heapOption;
    }

    /** The memory class (GB) whose default this is, 0 for a preset that is only ever chosen. */
    public int tierGigabytes() {
        return tierGigabytes;
    }

    /** The collector's options, then the heap's: what a preset contributes to the server's command line. */
    public List<String> options() {
        List<String> options = new ArrayList<>(collector.options());
        options.add(heapOption);
        return List.copyOf(options);
    }
}
