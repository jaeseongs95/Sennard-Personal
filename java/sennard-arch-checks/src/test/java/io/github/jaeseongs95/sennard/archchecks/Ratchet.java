// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The ceiling of each rule: the number of violations the code has now. A rule fails when the code has more (a new violation), and also when it has
 * fewer, so that a ceiling is lowered by a person in {@code ceilings.properties} as soon as the code improves and never leaves room for a new
 * violation to take the place of a fixed one. The rewrite brings every ceiling to zero; a rule at zero is a plain ban.
 */
final class Ratchet {
    private final Properties ceilings = new Properties();

    Ratchet() {
        try (InputStream in = Ratchet.class.getResourceAsStream("/ceilings.properties")) {
            if (in == null) {
                throw new IllegalStateException("ceilings.properties is missing from the test resources");
            }
            ceilings.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException cause) {
            throw new IllegalStateException("cannot read ceilings.properties", cause);
        }
    }

    int ceiling(String rule) {
        String value = ceilings.getProperty(rule);
        if (value == null) {
            throw new IllegalStateException("ceilings.properties has no ceiling for the rule " + rule);
        }
        return Integer.parseInt(value.strip());
    }

    /** Fails with the places when the violations differ from the ceiling. */
    void hold(String rule, List<Violation> found) {
        List<Violation> distinct = found.stream().distinct().sorted(Comparator.comparing(Violation::file).thenComparingInt(Violation::line).thenComparing(Violation::text)).toList();
        int ceiling = ceiling(rule);
        if (distinct.size() == ceiling) {
            return;
        }
        String places = distinct.stream().map(Violation::toString).collect(Collectors.joining("\n    ", "\n    ", ""));
        if (distinct.size() > ceiling) {
            fail(rule + ": " + distinct.size() + " violations, the ceiling is " + ceiling + ". A new violation was added; these are all of them:" + places);
        }
        fail(rule + ": " + distinct.size() + " violations, below the ceiling " + ceiling + ". Lower the ceiling in ceilings.properties to " + distinct.size() + ". These remain:" + places);
    }
}
