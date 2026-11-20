// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;

/**
 * Rule p: one clock, one conversion, no zone from the machine. Every time the product reads comes from the injected {@code Clock} (the one UTC bean of
 * AppConfiguration, or the one the CLI process makes at its entry point), every instant reaches a {@code TIMESTAMP} column through the converters of
 * {@code UtcTimestampColumns} (which use {@code Timestamps}), and
 * no code asks the JVM or the OS for a time or a zone. The compiled classes of the product are read with ArchUnit, so a call is found wherever it is
 * written (a lambda, a helper, a library wrapper in the product's own code).
 *
 * <p>Forbidden: a {@code now()} without a clock on any {@code java.time} class, {@code System.currentTimeMillis}, {@code ZoneId.systemDefault},
 * {@code TimeZone.getDefault}, {@code Clock.systemUTC}/{@code system}/{@code systemDefaultZone} outside the two clock makers, {@code java.util.Date},
 * {@code Calendar}, and the JDBC time types ({@code java.sql.Timestamp}, {@code Date}, {@code Time}), which carry the JVM's zone. A monotonic wait
 * ({@code System.nanoTime}) is not a date and stays allowed.
 */
final class TimeRules {
    private TimeRules() {
    }

    /** The two places a UTC clock is made: the server's one bean, and the entry point of the CLI process (no Spring context there). */
    static final Set<String> CLOCK_MAKERS = Set.of("io.github.jaeseongs95.sennard.app.AppConfiguration", "io.github.jaeseongs95.sennard.personal.cli.SennardCli");

    /**
     * The one place a {@code java.sql.Timestamp} is named: the reading converter of Spring Data JDBC. Spring Data JDBC reads a {@code TIMESTAMP} column with the driver's plain
     * {@code getObject}, which hands over a {@code Timestamp}, so the converter that turns it into the stored UTC fields (and an {@code Instant}) has to take that type; it is
     * the database boundary, and every other class keeps to {@code Instant}.
     */
    static final Set<String> TIMESTAMP_BOUNDARY = Set.of("io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns");

    private static final Set<String> BANNED_TYPES = Set.of("java.util.Date", "java.util.Calendar", "java.util.GregorianCalendar", "java.sql.Timestamp", "java.sql.Date", "java.sql.Time");
    private static final Set<String> CLOCK_FACTORIES = Set.of("systemUTC", "system", "systemDefaultZone");
    private static final Set<String> JDBC_TIME_ACCESS = Set.of("getTimestamp", "setTimestamp", "getDate", "setDate", "getTime", "setTime");

    /** The violations as {@code Class:line what}; empty when the classes keep to the rule. */
    static List<String> violations(JavaClasses classes) {
        List<String> found = new ArrayList<>();
        for (JavaClass origin : classes) {
            for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getName();
                String what = null;
                if (owner.startsWith("java.time.") && name.equals("now") && call.getTarget().getRawParameterTypes().isEmpty()) {
                    what = owner + ".now() reads the machine's clock";
                } else if (owner.equals("java.lang.System") && name.equals("currentTimeMillis")) {
                    what = "System.currentTimeMillis() reads the machine's clock";
                } else if (owner.equals("java.time.ZoneId") && name.equals("systemDefault") || owner.equals("java.util.TimeZone") && name.equals("getDefault")) {
                    what = owner + "." + name + "() asks for the machine's zone";
                } else if (owner.equals("java.time.Clock") && CLOCK_FACTORIES.contains(name) && !isClockMaker(origin)) {
                    what = "Clock." + name + "() outside the clock makers";
                } else if (owner.startsWith("java.sql.") && JDBC_TIME_ACCESS.contains(name)) {
                    what = owner + "." + name + "() reads or writes a time in the JVM's zone";
                }
                if (what != null) {
                    found.add(origin.getName() + ":" + call.getLineNumber() + " " + what);
                }
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                if (BANNED_TYPES.contains(dependency.getTargetClass().getFullName()) && !isTimestampBoundary(origin, dependency.getTargetClass().getFullName())) {
                    found.add(origin.getName() + ":" + dependency.getSourceCodeLocation().getLineNumber() + " uses " + dependency.getTargetClass().getFullName());
                }
            }
        }
        return found.stream().distinct().sorted().toList();
    }

    private static boolean isTimestampBoundary(JavaClass origin, String type) {
        return "java.sql.Timestamp".equals(type) && TIMESTAMP_BOUNDARY.stream().anyMatch(boundary -> origin.getName().equals(boundary) || origin.getName().startsWith(boundary + "$"));
    }

    private static boolean isClockMaker(JavaClass origin) {
        return CLOCK_MAKERS.stream().anyMatch(maker -> origin.getName().equals(maker) || origin.getName().startsWith(maker + "$"));
    }
}
