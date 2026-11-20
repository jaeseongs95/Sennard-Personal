// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heap sizes in MiB: reading a size given to the build, and the maximum heap of the running JVM.
 *
 * <p>A size such as {@code 512m} or {@code 1g} is read with the JVM's suffix rules (k, m, g; no suffix is bytes): neither the JDK nor Gradle's public API has a parser for it.
 */
public final class HeapBudget {

    private static final Pattern SIZE = Pattern.compile("(\\d+)([kKmMgG]?)");
    private static final long MIB = 1024L * 1024L;

    private HeapBudget() {
    }

    /** The size in MiB, rounded up. */
    public static long parseMiB(String size) {
        Matcher match = SIZE.matcher(size.trim());
        if (!match.matches()) {
            throw new IllegalArgumentException("'" + size + "' is not a size such as 512m or 1g");
        }
        long unit = switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1024L;
            case "m" -> MIB;
            case "g" -> MIB * 1024L;
            default -> 1L;
        };
        return (Long.parseLong(match.group(1)) * unit + MIB - 1) / MIB;
    }

    /** The real maximum heap of this JVM (what -Xmx became), in MiB, rounded up. */
    public static long currentJvmMiB() {
        return (Runtime.getRuntime().maxMemory() + MIB - 1) / MIB;
    }
}
