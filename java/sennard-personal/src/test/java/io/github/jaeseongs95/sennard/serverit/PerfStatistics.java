// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * The arithmetic and the JFR reading of the baseline measurement ({@link PerfBaselineMeasurement}), kept apart so the percentile rule has a
 * case of its own. The JFR file is read with the JDK's own {@code jdk.jfr.consumer} API (Java 25 guide, section 1 row 11 and line 629).
 */
final class PerfStatistics {
    /** The package whose topmost frame names the Sennard-owned place a sample is in. */
    static final String SENNARD_PACKAGE = "io.github.jaeseongs95.sennard.";

    private PerfStatistics() {
    }

    /** Nearest-rank percentile of the values: the value at the one-based rank ceil(p/100 * n) of the sorted values. */
    static long percentile(long[] values, double percent) {
        if (values.length == 0) {
            throw new IllegalArgumentException("No values");
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(1, Math.min(sorted.length, rank)) - 1];
    }

    static long max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    static double mean(long[] values) {
        return Arrays.stream(values).average().orElseThrow();
    }

    /** The smallest gap between two different positive values: how fine a clock that produced them can be told to be (0 when fewer than two differ). */
    static long smallestStep(long[] values) {
        long[] positive = Arrays.stream(values).filter(value -> value > 0).distinct().sorted().toArray();
        long step = 0;
        for (int i = 1; i < positive.length; i++) {
            long gap = positive[i] - positive[i - 1];
            step = step == 0 ? gap : Math.min(step, gap);
        }
        return step;
    }

    record Hot(String method, int samples) {
    }

    /** What the execution samples of one recording say: the count read, and the methods at the top of the sampled stacks. */
    record Hotspots(int samples, int clientSamples, int outsideWindows, List<Hot> topFrame, List<Hot> topSennardFrame) {
    }

    /** Whether the instant (epoch nanoseconds) is inside one of the sorted, non-overlapping windows [starts[i], ends[i]]. */
    static boolean inside(long[] starts, long[] ends, long instant) {
        int at = Arrays.binarySearch(starts, instant);
        int window = at >= 0 ? at : -at - 2;
        return window >= 0 && instant <= ends[window];
    }

    /**
     * The {@code jdk.ExecutionSample} events of the recording that fall inside a request's window, without those of the client thread (the
     * test's own, which sends the requests), counted by the method at the top of the stack and by the topmost frame inside {@link #SENNARD_PACKAGE}.
     * The windows keep the setup calls between two measured requests out of the counts.
     */
    static Hotspots hotspots(Path recording, String clientThread, long[] windowStarts, long[] windowEnds, int limit) throws IOException {
        Map<String, Integer> top = new HashMap<>();
        Map<String, Integer> sennard = new HashMap<>();
        int server = 0;
        int client = 0;
        int outside = 0;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    continue;
                }
                java.time.Instant at = event.getStartTime();
                if (!inside(windowStarts, windowEnds, at.getEpochSecond() * 1_000_000_000L + at.getNano())) {
                    outside++;
                    continue;
                }
                RecordedThread thread = event.getThread("sampledThread");
                if (thread != null && clientThread.equals(thread.getJavaName())) {
                    client++;
                    continue;
                }
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null || stack.getFrames().isEmpty()) {
                    continue;
                }
                server++;
                List<RecordedFrame> frames = stack.getFrames();
                top.merge(name(frames.get(0)), 1, Integer::sum);
                String owned = frames.stream().map(PerfStatistics::name).filter(name -> name.startsWith(SENNARD_PACKAGE)).findFirst().orElse("(no Sennard frame in the stack)");
                sennard.merge(owned, 1, Integer::sum);
            }
        }
        return new Hotspots(server, client, outside, ranked(top, limit), ranked(sennard, limit));
    }

    private static String name(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static List<Hot> ranked(Map<String, Integer> counts, int limit) {
        List<Hot> all = new ArrayList<>();
        counts.forEach((method, samples) -> all.add(new Hot(method, samples)));
        all.sort(Comparator.comparingInt(Hot::samples).reversed().thenComparing(Hot::method));
        return all.subList(0, Math.min(limit, all.size()));
    }
}
