// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;

/**
 * The JVM options of each program Sennard starts from its installed version, the only place they are written. The server's come from one preset
 * ({@link JvmPreset}: the collector and the heap) and from options every preset shares; nothing here is computed from the machine when the server starts.
 * The hook client and the MCP relay (the programs a host starts for every command hook and for each session) are their own small set and do not follow the preset.
 *
 * <p>The server runs with the installation's folder as its working folder ({@link ServerStarter}), so its {@code -Xlog} files are written as relative names: a path inside a
 * {@code -Xlog} option would put the drive letter's colon among the colons that separate the option's parts, and no quoting of it is needed for a name without a folder.
 */
public final class JvmOptions {
    /** The server's time is UTC, whatever zone the machine is in (a request's zone is the client's, never the server's). */
    private static final String UTC = "-Duser.timezone=UTC";
    private static final String COMPACT_OBJECT_HEADERS = "-XX:+UseCompactObjectHeaders";
    /** What the training run records and the cache is created from (the version's folder holds it until the cache exists). */
    static final String AOT_CONFIGURATION_FILE = "app.aotconf";
    /** The hook client's own cache and the configuration its training records (the version's folder holds the configuration until the cache exists). */
    static final String HOOK_AOT_CACHE_FILE = "sennard-hook.aot";
    static final String HOOK_AOT_CONFIGURATION_FILE = "sennard-hook.aotconf";
    /** Every JVM log off: one word, so that a host's shell has nothing to quote. The JVM's own warnings about a cache go to standard output, which is the hook's answer. */
    static final String HOOK_LOG_OFF = "-Xlog:disable";
    static final String GC_LOG_FILE = "jvm-gc.log";
    static final String AOT_LOG_FILE = "jvm-aot.log";

    private JvmOptions() {
    }

    /**
     * The words of one JVM start: the program, its options, the class path (one jar, whose manifest names the {@code lib} jars beside it) and the main class. Nothing is quoted: no
     * shell reads them.
     */
    public static List<String> command(Path java, List<String> options, Path classPath, String mainClass) {
        List<String> words = new ArrayList<>();
        words.add(java.toString());
        words.addAll(options);
        words.add("-cp");
        words.add(classPath.toString());
        words.add(mainClass);
        return List.copyOf(words);
    }

    /**
     * What the server starts with. Time in UTC; the preset's collector and heap; the collector's GC log, rotated by the JVM; and the AOT cache of this collector in
     * {@code auto} mode, which is how a missing or stale cache ends: the JVM runs without it and writes why to its own log file (a server started by a command has no console to say it on).
     */
    public static List<String> server(JvmPreset preset, InstalledVersion version) {
        List<String> options = base(preset);
        options.add("-Xlog:gc*:file=" + GC_LOG_FILE + ":time,uptime,level,tags:filecount=5,filesize=10m");
        options.add("-XX:AOTMode=auto");
        options.add("-XX:AOTCache=" + version.aotCache(preset.collector()));
        options.add("-Xlog:aot=info,cds=info:file=" + AOT_LOG_FILE + ":time,level,tags:filecount=2,filesize=1m");
        return List.copyOf(options);
    }

    /** The training run: the server's JVM with the same collector, recording what it loads. */
    public static List<String> training(JvmPreset preset, InstalledVersion version) {
        List<String> options = base(preset);
        options.add("-XX:AOTMode=record");
        options.add("-XX:AOTConfiguration=" + version.aotConfiguration());
        options.add("-Dspring.context.exit=onRefresh");
        return List.copyOf(options);
    }

    /** The second step: the cache of the preset's collector, made from what the training recorded. */
    public static List<String> cacheCreation(JvmPreset preset, InstalledVersion version) {
        List<String> options = base(preset);
        options.add("-XX:AOTMode=create");
        options.add("-XX:AOTConfiguration=" + version.aotConfiguration());
        options.add("-XX:AOTCache=" + version.aotCache(preset.collector()));
        return List.copyOf(options);
    }

    /**
     * The JVM options of the hook client program, which a host starts for every command hook (so its start-up is the hook's delay), and of the MCP relay, which shares its class
     * path: a small serial collector and heap, no time spent optimizing code that runs once, and the AOT cache of the client's own classes in {@code auto} mode (the client is a few
     * classes of the JDK's {@code java.base} and its own jar, so the cache is small and the JVM starts at about the speed of an empty one).
     *
     * <p>The JVM writes a cache it cannot use (missing, stale, damaged) to <b>standard output</b> as {@code [warning][aot]} and {@code [error][aot]} lines and then runs
     * without it (measured on JDK 25.0.5; the manual says only that the JVM continues). Standard output is a hook's answer to its host (and the relay's protocol stream), so every
     * JVM log is switched off ({@value #HOOK_LOG_OFF}): the client never prints for a cache. This is not a log file; a hook must not write one. The log option is one word without
     * {@code *} or {@code ,} (the form that switches only the AOT tags off has both), so that no shell has anything to quote in it; {@code =} and {@code :} in the other options are
     * only characters in a word that is not the first.
     */
    public static List<String> hookClient(InstalledVersion version) {
        List<String> options = clientBase();
        options.add("-XX:AOTMode=auto");
        options.add("-XX:AOTCache=" + version.folder().resolve(HOOK_AOT_CACHE_FILE));
        return List.copyOf(options);
    }

    /** The hook client's training run: its own options and recording what a hook loads. */
    public static List<String> hookClientTraining(InstalledVersion version) {
        List<String> options = clientBase();
        options.add("-XX:AOTMode=record");
        options.add("-XX:AOTConfiguration=" + version.folder().resolve(HOOK_AOT_CONFIGURATION_FILE));
        return List.copyOf(options);
    }

    /** The hook client's second step: the cache made from what the training recorded. */
    public static List<String> hookClientCacheCreation(InstalledVersion version) {
        List<String> options = clientBase();
        options.add("-XX:AOTMode=create");
        options.add("-XX:AOTConfiguration=" + version.folder().resolve(HOOK_AOT_CONFIGURATION_FILE));
        options.add("-XX:AOTCache=" + version.folder().resolve(HOOK_AOT_CACHE_FILE));
        return List.copyOf(options);
    }

    /**
     * {@code -XX:-UsePerfData}: the JVM does not create its performance-counter file at start (a memory-mapped file in the temporary folder that only an attaching tool
     * reads). A client that lives for one hook has no tool attached and does not need the file made and deleted on every start.
     */
    private static List<String> clientBase() {
        return new ArrayList<>(List.of("-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", HOOK_LOG_OFF));
    }

    private static List<String> base(JvmPreset preset) {
        List<String> options = new ArrayList<>();
        options.add(UTC);
        options.addAll(preset.options());
        options.add(COMPACT_OBJECT_HEADERS);
        return options;
    }
}
