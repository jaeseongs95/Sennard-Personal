// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A release version {@code major.minor.patch}, compared numerically. */
public record StableVersion(BigInteger major, BigInteger minor, BigInteger patch) implements Comparable<StableVersion> {
    private static final Pattern STABLE = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Comparator<StableVersion> ORDER = Comparator.comparing(StableVersion::major).thenComparing(StableVersion::minor).thenComparing(StableVersion::patch);

    static StableVersion of(Matcher parts) {
        return new StableVersion(new BigInteger(parts.group(1)), new BigInteger(parts.group(2)), new BigInteger(parts.group(3)));
    }

    static boolean accepts(String text) {
        return STABLE.matcher(text).matches();
    }

    /** Parses a release version, returning empty when the text does not match the release format. */
    public static Optional<StableVersion> tryParse(String text) {
        Matcher parts = STABLE.matcher(text);
        return parts.matches() ? Optional.of(of(parts)) : Optional.empty();
    }

    public static StableVersion parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException("Not a release version: " + text));
    }

    @Override
    public int compareTo(StableVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
