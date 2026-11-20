// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cost of one request at the four entry points the 6.0.0 redesign changes: a hook's SessionStart and UserPromptSubmit,
 * and the MCP tools plan_workflow and record_stage_result. The assembled application (the PC's shared PostgreSQL, real skill tree, real connection-owner
 * lookup) runs in this JVM, and the requests go to it as a host sends them: one new connection each, the recorded hook bodies and tool
 * {@code _meta}, one after another. Each entry point is warmed up, then measured twice: a pass with no recorder (latency, CPU and allocation
 * come from it) and a pass under a JDK Flight Recorder recording (the hot methods come from it; its latencies show what the recorder costs).
 * <p>
 * It is a measurement, not a regression gate: it fails only when a request is refused or the files cannot be written. It carries the
 * {@link #TAG} tag, which the default {@code test} task excludes; {@code :sennard-personal:perfBaseline} runs it. The case table is in the
 * report (baseline.md, section 1), written before the first run.
 */
@Tag(PerfBaselineMeasurement.TAG)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfBaselineMeasurement {
    static final String TAG = "perf-baseline";
    private static final int WARMUP = Integer.getInteger("sennard.perf.warmup", 200);
    private static final int MEASURED = Integer.getInteger("sennard.perf.count", 200);
    private static final String HOST = "codex";
    private static final String OS = "windows";
    private static final com.sun.management.OperatingSystemMXBean OS_BEAN = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static ToolServerSupport.StubRemote remote;
    private static RunningServer server;
    private static Path out;
    private static Path workspaces;
    private static int callNumber;
    private static final Map<String, String> reports = new LinkedHashMap<>();
    private static String environmentBefore;

    /** One request to send; {@link #settle} is the work after it (outside the measurement) that leaves the server as it was. */
    private interface Step {
        RawHttp.Response send() throws IOException;

        default void settle(RawHttp.Response response) throws Exception {
        }
    }

    /** Makes request number {@code index}: everything the request needs is made here, outside the measurement. */
    private interface Scenario {
        Step prepare(int index) throws Exception;
    }

    /** One measured request: the CPU is the JVM's (client thread subtracted), {@code collections} the collections that ran during it. The database is a service of the PC outside the product, so its CPU is not part of the measure. */
    private record Sample(long latencyNs, long cpuNs, long allocBytes, long collections) {
    }

    private record Pass(Sample[] samples, long wallNs, long collections, long collectionMillis, double systemLoad, double processLoad, long[] windowStarts,
            long[] windowEnds) {
    }

    @BeforeAll
    static void start() throws Exception {
        out = Path.of(System.getProperty("sennard.perf.out", "build/perf")).toAbsolutePath();
        Files.createDirectories(out);
        Path repository = Path.of(System.getProperty("sennard.repo.root")).toAbsolutePath();
        workspaces = Path.of("build", "perf-ws", UUID.randomUUID().toString()).toAbsolutePath(); // the edit gate does not count the OS temporary directory
        Files.createDirectories(workspaces);
        remote = new ToolServerSupport.StubRemote(); // the tag API and the JEV endpoint stay on this computer
        List<String> arguments = List.of("--sennard.skills-root=" + repository, "--sennard.updates.tags-api=" + remote.url("/tags"),
                "--sennard.typesafe.base-url=" + remote.url("/jev"), "--sennard.typesafe.allow-loopback-http=true", "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        server = SessionServerSupport.started(List.of(), arguments.toArray(String[]::new));
        environmentBefore = environment("before the measurement");
    }

    @AfterAll
    static void finish() throws IOException {
        String environmentAfter = environment("after the measurement");
        try {
            server.close();
            remote.close();
        } finally {
            StringBuilder all = new StringBuilder("# Measured values\n\nwarm-up ").append(WARMUP).append(", measured ").append(MEASURED).append(" per pass.\n\n");
            reports.values().forEach(report -> all.append(report).append('\n'));
            Files.writeString(out.resolve("summary.md"), all.toString(), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("environment.txt"), environmentBefore + "\n" + environmentAfter,StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------------------ the four entry points

    @Test
    @Order(1)
    void hookSessionStart() throws Exception {
        measure("E1-hook-SessionStart", "hook SessionStart", index -> {
            byte[] body = SessionServerSupport.hook(OS, HOST, "SessionStart", SessionServerSupport.uniqueSession(), "none");
            return () -> SessionServerSupport.post(server.port, "/hooks/v1/" + HOST + "/SessionStart", body);
        }, PerfBaselineMeasurement::hookAnswered);
    }

    @Test
    @Order(2)
    void hookUserPromptSubmit() throws Exception {
        String session = startedSession();
        byte[] body = SessionServerSupport.hook(OS, HOST, "UserPromptSubmit", session, "none");
        measure("E2-hook-UserPromptSubmit", "hook UserPromptSubmit", index -> () -> SessionServerSupport.post(server.port, "/hooks/v1/" + HOST + "/UserPromptSubmit", body),
                PerfBaselineMeasurement::hookAnswered);
    }

    @Test
    @Order(3)
    void planWorkflow() throws Exception {
        String session = startedSession();
        String rootId = data(session, "start_workflow", startInput(newWorkspace())).path("rootId").asString();
        JsonNode task = data(session, "get_convergence_status", args("rootId", rootId)).path("root").path("taskEnvelope");
        Map<String, Object> input = ToolServerSupport.MAPPER.convertValue(task, MAP);
        measure("E3-mcp-plan_workflow", "MCP plan_workflow", index -> {
            byte[] body = toolRequest("plan_workflow", input, session);
            return () -> mcp(body);
        }, PerfBaselineMeasurement::toolSucceeded);
    }

    @Test
    @Order(4)
    void recordStageResult() throws Exception {
        String session = startedSession();
        measure("E4-mcp-record_stage_result", "MCP record_stage_result", index -> {
            String runId = data(session, "start_workflow", startInput(newWorkspace())).path("runId").asString();
            JsonNode receipt = data(session, "get_workflow_status", args("runId", runId));
            String stage = receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
            // The evidence is an attachment of this session, uploaded here, outside the measurement: the measured request is the record that names it.
            ToolServerSupport.Uploaded evidence = ToolServerSupport.uploadEvidence(server.port, PerfBaselineMeasurement::call, session);
            byte[] body = toolRequest("record_stage_result", passedStage(runId, receipt.path("revision").asLong(), stage, evidence), session);
            return new Step() {
                @Override
                public RawHttp.Response send() throws IOException {
                    return mcp(body);
                }

                @Override
                public void settle(RawHttp.Response response) {
                    long revision = envelope(response).path("data").path("revision").asLong();
                    data(session, "finalize_workflow", args("runId", runId, "expectedRevision", revision)); // releases the run's convergence root
                }
            };
        }, PerfBaselineMeasurement::toolSucceeded);
    }

    // ------------------------------------------------------------------------------------------------------------------ the loop

    private static void measure(String id, String title, Scenario scenario, Consumer<RawHttp.Response> check) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            Step step = scenario.prepare(i);
            step.settle(send(step, check));
        }
        Pass plain = pass(scenario, check, false);
        Path recording = out.resolve(id + ".jfr");
        Pass recorded;
        try (Recording flight = new Recording(Configuration.getConfiguration("profile"))) {
            flight.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            flight.setToDisk(true);
            flight.start();
            recorded = pass(scenario, check, true);
            flight.stop();
            flight.dump(recording);
        }
        PerfStatistics.Hotspots hot = PerfStatistics.hotspots(recording, Thread.currentThread().getName(), recorded.windowStarts(), recorded.windowEnds(), 20);
        writeSamples(id, plain, recorded);
        Files.writeString(out.resolve(id + "-hot.txt"), hotText(hot), StandardCharsets.UTF_8);
        reports.put(id, report(id, title, plain, recorded, hot));
        assertEquals(MEASURED, plain.samples().length);
        assertEquals(MEASURED, recorded.samples().length);
    }

    private static RawHttp.Response send(Step step, Consumer<RawHttp.Response> check) throws IOException {
        RawHttp.Response response = step.send();
        check.accept(response);
        return response;
    }

    private static Pass pass(Scenario scenario, Consumer<RawHttp.Response> check, boolean windows) throws Exception {
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        long collections0 = collectionCount();
        long millis0 = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        OS_BEAN.getCpuLoad(); // the first read starts the interval
        OS_BEAN.getProcessCpuLoad();
        Sample[] samples = new Sample[MEASURED];
        long[] starts = new long[MEASURED];
        long[] ends = new long[MEASURED];
        long wall0 = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            Step step = scenario.prepare(i);
            long requestCollections0 = collectionCount();
            long processCpu0 = OS_BEAN.getProcessCpuTime();
            long ownCpu0 = THREADS.getCurrentThreadCpuTime();
            long allocated0 = THREADS.getTotalThreadAllocatedBytes();
            long ownAllocated0 = THREADS.getCurrentThreadAllocatedBytes();
            Instant began = Instant.now();
            long t0 = System.nanoTime();
            RawHttp.Response response = step.send();
            long t1 = System.nanoTime();
            Instant ended = Instant.now();
            long processCpu1 = OS_BEAN.getProcessCpuTime();
            long ownCpu1 = THREADS.getCurrentThreadCpuTime();
            long allocated1 = THREADS.getTotalThreadAllocatedBytes();
            long ownAllocated1 = THREADS.getCurrentThreadAllocatedBytes();
            long requestCollections1 = collectionCount();
            check.accept(response);
            step.settle(response);
            samples[i] = new Sample(t1 - t0, (processCpu1 - processCpu0) - (ownCpu1 - ownCpu0), (allocated1 - allocated0) - (ownAllocated1 - ownAllocated0),
                    requestCollections1 - requestCollections0);
            if (windows) {
                starts[i] = began.getEpochSecond() * 1_000_000_000L + began.getNano();
                ends[i] = ended.getEpochSecond() * 1_000_000_000L + ended.getNano();
            }
        }
        long wall = System.nanoTime() - wall0;
        double systemLoad = OS_BEAN.getCpuLoad();
        double processLoad = OS_BEAN.getProcessCpuLoad();
        return new Pass(samples, wall, collectionCount() - collections0, collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum() - millis0, systemLoad, processLoad,
                starts, ends);
    }

    private static long collectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    // ------------------------------------------------------------------------------------------------------------ the requests

    private static void hookAnswered(RawHttp.Response response) {
        assertEquals(200, response.status(), response.text());
    }

    private static void toolSucceeded(RawHttp.Response response) {
        assertEquals(200, response.status(), response.text());
        assertTrue(envelope(response).path("ok").asBoolean(false), response.text());
    }

    private static String startedSession() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        hookAnswered(SessionServerSupport.post(server.port, "/hooks/v1/" + HOST + "/SessionStart", SessionServerSupport.hook(OS, HOST, "SessionStart", session, "none")));
        return session;
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** The recorded Codex {@code _meta} with this session; every call is its own host call, numbered. */
    private static Map<String, Object> meta(String session) {
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta(OS, HOST, session, "none"));
        meta.put("callId", "exec-perf-" + session.substring(0, 8) + "-" + (++callNumber));
        return meta;
    }

    private static byte[] toolRequest(String tool, Map<String, Object> arguments, String session) {
        ObjectNode request = ToolServerSupport.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode params = request.putObject("params").put("name", tool);
        params.set("arguments", ToolServerSupport.MAPPER.valueToTree(OperationKeys.withKey(tool, arguments)));
        params.set("_meta", ToolServerSupport.MAPPER.valueToTree(meta(session)));
        return ToolServerSupport.MAPPER.writeValueAsBytes(request);
    }

    private static RawHttp.Response mcp(byte[] body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        headers.put("MCP-Protocol-Version", "2025-11-25");
        headers.put("Sennard-Host-Label", HOST);
        return RawHttp.exchange(server.port, "POST", "/mcp", headers, body);
    }

    private static JsonNode envelope(RawHttp.Response response) {
        String text = ToolServerSupport.MAPPER.readTree(response.body()).path("result").path("content").path(0).path("text").asString();
        return ToolServerSupport.MAPPER.readTree(text);
    }

    /** A preparing call, outside the measurement, which must have succeeded. */
    private static JsonNode data(String session, String tool, Map<String, Object> arguments) {
        try {
            RawHttp.Response response = mcp(toolRequest(tool, arguments, session));
            toolSucceeded(response);
            return envelope(response).path("data");
        } catch (IOException unreachable) {
            throw new java.io.UncheckedIOException(unreachable);
        }
    }

    private static Path newWorkspace() throws IOException {
        return Files.createDirectories(workspaces.resolve("w" + (++callNumber)));
    }

    /** ToolGoldenTest.startInput: the same start_workflow input the golden cases use. */
    private static Map<String, Object> startInput(Path workspace) {
        return args("goal", "Change [.] in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills",
                List.of(args("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(),
                "writeTargets", List.of("."));
    }

    /** ToolGoldenTest.passedStage: the same record_stage_result input the golden case uses; its evidence names an attachment of the recording session. */
    private static Map<String, Object> passedStage(String runId, long revision, String stageId, ToolServerSupport.Uploaded evidence) {
        return args("schemaVersion", "1.0.0", "runId", runId, "expectedRevision", revision, "stageId", stageId, "state", "PASSED",
                "output", args("schemaVersion", "1.0.0", "kind", "output", "output", args("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(ToolServerSupport.observedEvidence(evidence)), "findings", List.of(),
                "blockers", List.of(), "error", null);
    }

    /** The envelope of a preparing call, outside the measurement (the call the upload of an attachment is made with). */
    private static JsonNode call(String tool, Map<String, Object> arguments, String session) {
        try {
            return envelope(mcp(toolRequest(tool, arguments, session)));
        } catch (IOException unreachable) {
            throw new java.io.UncheckedIOException(unreachable);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the files

    private static void writeSamples(String id, Pass plain, Pass recorded) throws IOException {
        StringBuilder csv = new StringBuilder("pass,index,latency_ns,cpu_ns,alloc_bytes,collections_during\n");
        for (Pass pass : List.of(plain, recorded)) {
            String name = pass == plain ? "A" : "B";
            for (int i = 0; i < pass.samples().length; i++) {
                Sample sample = pass.samples()[i];
                csv.append(name).append(',').append(i).append(',').append(sample.latencyNs()).append(',').append(sample.cpuNs()).append(',').append(sample.allocBytes()).append(',')
                        .append(sample.collections()).append('\n');
            }
        }
        Files.writeString(out.resolve(id + ".csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private static String hotText(PerfStatistics.Hotspots hot) {
        StringBuilder text = new StringBuilder();
        text.append("samples in request windows (server side): ").append(hot.samples()).append("; client thread samples left out: ").append(hot.clientSamples())
                .append("; samples outside every request window: ").append(hot.outsideWindows()).append("\n\n");
        text.append("top of the stack (method at the top of each sampled stack)\n");
        table(text, hot.topFrame(), hot.samples());
        text.append("\ntopmost frame inside ").append(PerfStatistics.SENNARD_PACKAGE).append("*\n");
        table(text, hot.topSennardFrame(), hot.samples());
        return text.toString();
    }

    private static void table(StringBuilder text, List<PerfStatistics.Hot> rows, int total) {
        int rank = 1;
        for (PerfStatistics.Hot row : rows) {
            text.append(String.format(Locale.ROOT, "%2d  %5d  %5.1f%%  %s%n", rank++, row.samples(), total == 0 ? 0.0 : 100.0 * row.samples() / total, row.method()));
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String kb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1024.0);
    }

    private static String report(String id, String title, Pass plain, Pass recorded, PerfStatistics.Hotspots hot) {
        StringBuilder text = new StringBuilder("## ").append(id).append(' ').append(title).append("\n\n");
        text.append("| metric | pass A (no recorder) | pass B (JFR on) |\n|---|---|---|\n");
        row(text, "latency p50 / p95 / max (ms)", p -> ms(PerfStatistics.percentile(latency(p), 50)) + " / " + ms(PerfStatistics.percentile(latency(p), 95)) + " / " + ms(PerfStatistics.max(latency(p))),
                plain, recorded);
        row(text, "latency mean (ms)", p -> String.format(Locale.ROOT, "%.3f", PerfStatistics.mean(latency(p)) / 1_000_000.0), plain, recorded);
        row(text, "JVM CPU per request p50 / p95 / max (ms), client thread subtracted", p -> ms(PerfStatistics.percentile(cpu(p), 50)) + " / " + ms(PerfStatistics.percentile(cpu(p), 95)) + " / "
                + ms(PerfStatistics.max(cpu(p))), plain, recorded);
        row(text, "JVM CPU per request mean (ms)", p -> String.format(Locale.ROOT, "%.3f", PerfStatistics.mean(cpu(p)) / 1_000_000.0), plain, recorded);
        row(text, "CPU clock: requests with 0 delta / smallest step between different values (ms)", p -> java.util.Arrays.stream(cpu(p)).filter(v -> v == 0).count() + " / "
                + ms(PerfStatistics.smallestStep(cpu(p))), plain, recorded);
        row(text, "JVM CPU per request of the requests no collection ran during: count / p50 / p95 (ms)", p -> {
            long[] quiet = java.util.Arrays.stream(p.samples()).filter(sample -> sample.collections() == 0).mapToLong(Sample::cpuNs).toArray();
            return quiet.length == 0 ? "0 / - / -" : quiet.length + " / " + ms(PerfStatistics.percentile(quiet, 50)) + " / " + ms(PerfStatistics.percentile(quiet, 95));
        }, plain, recorded);
        row(text, "allocated per request p50 / p95 / max (KiB), client thread subtracted", p -> kb(PerfStatistics.percentile(alloc(p), 50)) + " / " + kb(PerfStatistics.percentile(alloc(p), 95))
                + " / " + kb(PerfStatistics.max(alloc(p))), plain, recorded);
        row(text, "allocated per request mean (KiB)", p -> String.format(Locale.ROOT, "%.1f", PerfStatistics.mean(alloc(p)) / 1024.0), plain, recorded);
        row(text, "garbage collections in the pass / their time (ms)", p -> p.collections() + " / " + p.collectionMillis(), plain, recorded);
        row(text, "pass wall time (s) / sum of request latencies (s)", p -> String.format(Locale.ROOT, "%.2f / %.2f", p.wallNs() / 1e9, java.util.Arrays.stream(latency(p)).sum() / 1e9), plain, recorded);
        row(text, "system CPU load / this JVM's CPU load over the pass", p -> String.format(Locale.ROOT, "%.3f / %.3f", p.systemLoad(), p.processLoad()), plain, recorded);
        text.append("\nJFR: one recording (profile settings, jdk.ExecutionSample period 1 ms asked), ").append(hot.samples()).append(" server-side execution samples in the ")
                .append(MEASURED).append(" request windows (").append(hot.clientSamples()).append(" client-thread samples and ").append(hot.outsideWindows()).append(" samples outside the windows left out).\n");
        return text.toString();
    }

    private interface Cell {
        String of(Pass pass);
    }

    private static void row(StringBuilder text, String label, Cell cell, Pass plain, Pass recorded) {
        text.append("| ").append(label).append(" | ").append(cell.of(plain)).append(" | ").append(cell.of(recorded)).append(" |\n");
    }

    private static long[] latency(Pass pass) {
        return java.util.Arrays.stream(pass.samples()).mapToLong(Sample::latencyNs).toArray();
    }

    private static long[] cpu(Pass pass) {
        return java.util.Arrays.stream(pass.samples()).mapToLong(Sample::cpuNs).toArray();
    }

    private static long[] alloc(Pass pass) {
        return java.util.Arrays.stream(pass.samples()).mapToLong(Sample::allocBytes).toArray();
    }

    private static String environment(String when) {
        var runtime = ManagementFactory.getRuntimeMXBean();
        long self = ProcessHandle.current().pid();
        List<String> others = ProcessHandle.allProcesses().filter(handle -> handle.pid() != self && handle.info().command().map(c -> c.toLowerCase(Locale.ROOT).contains("java")).orElse(false))
                .map(handle -> Long.toString(handle.pid())).collect(Collectors.toList());
        List<String> children = new ArrayList<>();
        ProcessHandle.current().descendants().forEach(handle -> children.add(handle.pid() + ":" + handle.info().command().map(c -> Path.of(c).getFileName().toString()).orElse("?")));
        return "environment " + when + "\n"
                + "time: " + Instant.now() + "\n"
                + "jvm: " + runtime.getVmName() + " " + runtime.getVmVersion() + " (spec " + runtime.getSpecVersion() + ")\n"
                + "jvm input arguments: " + runtime.getInputArguments().stream().filter(argument -> !argument.startsWith("-Dsennard.test.classpath=")).toList() + " (the long class path left out)\n"
                + "max heap (MiB): " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "\n"
                + "os: " + OS_BEAN.getName() + " " + OS_BEAN.getVersion() + " " + OS_BEAN.getArch() + ", available processors " + OS_BEAN.getAvailableProcessors() + "\n"
                + "processor identifier: " + System.getenv("PROCESSOR_IDENTIFIER") + "\n"
                + "system CPU load now: " + OS_BEAN.getCpuLoad() + "\n"
                + "physical memory free / total (MiB): " + OS_BEAN.getFreeMemorySize() / (1024 * 1024) + " / " + OS_BEAN.getTotalMemorySize() / (1024 * 1024) + "\n"
                + "other java processes (pids, not touched): " + others.size() + " " + others + "\n"
                + "this JVM's child processes: " + children + "\n"
                + "garbage collectors: " + ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList() + "\n"
                + "warm-up " + WARMUP + ", measured " + MEASURED + ", host " + HOST + ", os fixtures " + OS + "\n";
    }
}
