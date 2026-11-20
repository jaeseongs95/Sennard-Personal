// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.jvm;

import java.util.List;
import java.util.Locale;

/**
 * The garbage collectors a server preset can name. A closed set: the JVM options that select it and the collector's own AOT cache file are fixed here,
 * so a preset cannot carry a collector this build has not trained a cache for.
 */
public enum Collector {
    /** The periodic collection hands memory an idle server no longer needs back to the OS (the server is resident, and mostly idle). */
    G1("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000"),
    ZGC("-XX:+UseZGC");

    private final List<String> options;

    Collector(String... options) {
        this.options = List.of(options);
    }

    /** The JVM options this collector needs, its selection first. */
    public List<String> options() {
        return options;
    }

    /**
     * The AOT cache's file name: one file per collector, because a cache is made by a JVM run with the collector it will be used with.
     * A JVM started with another collector finds no file of its own and starts without a cache.
     */
    public String cacheFileName() {
        return "app-" + name().toLowerCase(Locale.ROOT) + ".aot";
    }
}
