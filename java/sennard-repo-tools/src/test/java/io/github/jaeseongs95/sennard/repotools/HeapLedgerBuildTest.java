// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heap ledger in a real Gradle build (Gradle TestKit, https://docs.gradle.org/current/userguide/test_kit.html). The build is a small one of its own, three modules with one JUnit test
 * each, that applies the build logic of the repository (java/build-logic, copied, so that this build and the build that runs this test do not share a folder): its plugin
 * ({@code SennardBuildLogicPlugin}) wires the ledger, and the build states the three sizes the ledger counts in the plugin's extension, as java/build.gradle.kts does. Every case is
 * judged by what the build does: its outcome, the outcome of its tasks and the output of the ledger and of the processes the tasks start.
 *
 * <p>The first case is the settings with which the build once stopped (D-0270): the ledger on, -Psennard.build.heapLimitMiB=2048 and --max-workers=2, and the Test tasks finish although
 * their reservations together are over the limit; the judgement is that the build finishes, with every task successful and every Test task having reserved, and it runs with the
 * configuration cache, as the build of the repository does (java/gradle.properties). No wait of a length is part of it. A build that does not finish ends in two ways: the ledger's own
 * wait, which ends a task that waits for the heap for longer than {@code sennard.heap.maxWaitMinutes} (one minute here) with a failure, and the deadline of the test, which ends a build that is
 * stuck elsewhere.
 *
 * <pre>
 * Case                         Input                                                       Expected
 * three Test tasks, 2 workers  limit 2048 MiB, daemon 512 MiB, a Test task reserves 1024    the build ends successfully; :a:test, :b:test and :c:test are SUCCESS and each reserved 1024 MiB
 * ledger off                   no sennard.heap.ledger, and sennard.heap.ledger=false with a limit  the build succeeds and the ledger says nothing and writes nothing
 * ledger on without a limit    sennard.heap.ledger=true, no sennard.build.heapLimitMiB             the build fails and names the property that is missing (no default limit)
 * limit above 2048             limit 4096 MiB, a Test task of 2 GiB (2816 MiB claimed)     the build's maximum claim is over 2048 MiB (no upper bound on the limit)
 * JavaExec and Exec tasks      ledger on, a JavaExec task and an Exec task                 each reserves 512 MiB; the JavaExec JVM gets -Xmx512m, the Exec JVM reads -Xmx512m from JAVA_TOOL_OPTIONS
 * </pre>
 */
class HeapLedgerBuildTest {
    private static final Path JAVA = Path.of(System.getProperty("sennard.repo.root")).resolve("java");
    private static final List<String> MODULES = List.of("a", "b", "c");
    private static final Pattern MAXIMUM_CLAIM = Pattern.compile("Heap ledger: build daemon \\d+ MiB, maximum claim (\\d+) MiB");

    /** The deadline of a build: a build that is not finished by then is stuck. The first build takes the time its three small test runs and the first resolution of JUnit take. */
    private static final Duration DEADLINE = Duration.ofMinutes(15);

    @TempDir
    Path project;

    @TempDir
    Path ledgerFolder;

    @Test
    void threeTestTasksOverTheLimitTogetherFinishWithTwoWorkers() throws IOException {
        writeBuild("256m", false);

        BuildResult result = build(false, "test", "--max-workers=2", "--configuration-cache", "-Psennard.heap.ledger=true", "-Psennard.build.heapLimitMiB=2048");

        for (String module : MODULES) {
            String task = ":" + module + ":test";
            assertEquals(TaskOutcome.SUCCESS, result.task(task).getOutcome(), task);
            assertTrue(result.getOutput().contains("Heap ledger: " + task + " reserves 1024 MiB"), task + " reserved no heap, so the ledger was not on: " + result.getOutput());
        }
    }

    @Test
    void theLedgerIsOffUnlessItsPropertyIsTrue() throws IOException {
        writeBuild("256m", false);

        BuildResult withoutProperty = build(false, "help");
        BuildResult stated = build(false, "help", "-Psennard.heap.ledger=false", "-Psennard.build.heapLimitMiB=2048");

        assertEquals(TaskOutcome.SUCCESS, withoutProperty.task(":help").getOutcome());
        assertFalse(withoutProperty.getOutput().contains("Heap ledger:"), "the ledger is on although sennard.heap.ledger is not stated: " + withoutProperty.getOutput());
        assertEquals(TaskOutcome.SUCCESS, stated.task(":help").getOutcome());
        assertFalse(stated.getOutput().contains("Heap ledger:"), "the ledger is on although sennard.heap.ledger=false: " + stated.getOutput());
        assertEquals(List.of(), listFiles(ledgerFolder), "the ledger wrote into its folder although it is off");
    }

    @Test
    void theLedgerOnRequiresItsLimitAndHasNoDefault() throws IOException {
        writeBuild("256m", false);

        BuildResult result = build(true, "help", "-Psennard.heap.ledger=true");

        assertTrue(result.getOutput().contains("sennard.build.heapLimitMiB"), "the failure does not name the property that states the limit: " + result.getOutput());
        assertFalse(result.getOutput().contains("Heap ledger:"), "the ledger ran without a limit: " + result.getOutput());
    }

    @Test
    void theLimitHasNoUpperBound() throws IOException {
        // A Test task with a 2 GiB heap claims 2048 + 768 MiB (its child JVMs); the build's maximum claim is its daemon and that, held to the limit and to nothing else. No JVM of a test starts: help.
        writeBuild("2g", false);

        BuildResult result = build(false, "help", "-Psennard.heap.ledger=true", "-Psennard.build.heapLimitMiB=4096");

        Matcher claim = MAXIMUM_CLAIM.matcher(result.getOutput());
        assertTrue(claim.find(), "the ledger did not report the build's claim: " + result.getOutput());
        long maximumClaim = Long.parseLong(claim.group(1));
        assertTrue(maximumClaim > 2048L && maximumClaim <= 4096L, "the maximum claim " + maximumClaim + " MiB is not held to the limit of 4096 MiB alone");
    }

    @Test
    void javaExecAndExecTasksReserveToo() throws IOException {
        writeBuild("256m", true);

        BuildResult result = build(false, ":a:hello", ":a:javaVersion", "--max-workers=2", "-Psennard.heap.ledger=true", "-Psennard.build.heapLimitMiB=2048");

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:hello").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":a:javaVersion").getOutcome());
        String output = result.getOutput();
        assertTrue(output.contains("Heap ledger: :a:hello reserves 512 MiB"), ":a:hello (JavaExec) did not reserve its heap: " + output);
        assertTrue(output.contains("Heap ledger: :a:javaVersion reserves 512 MiB"), ":a:javaVersion (Exec) did not reserve its heap: " + output);
        assertTrue(output.lines().anyMatch(line -> line.startsWith("hello jvm arguments") && line.contains("-Xmx512m")), "the JavaExec JVM was given no -Xmx512m: " + output);
        assertTrue(output.contains("Picked up JAVA_TOOL_OPTIONS:") && output.contains("-Xmx512m"), "the Exec JVM did not read -Xmx512m from JAVA_TOOL_OPTIONS: " + output);
    }

    /**
     * The fixture build: the build logic of the repository, three modules with one JUnit test each, and the sizes the ledger counts. A test JVM has the given heap; its child JVMs are
     * counted at the larger of two sums (2 x 256 and 256 + 512 MiB), 768 MiB, so with 256m a Test task reserves 1024 MiB, and two of them with the daemon (512 + 2048) are over 2048 MiB.
     * With {@code jvmTasks} module a has a JavaExec task (hello) and an Exec task (javaVersion), which start a JVM each.
     */
    private void writeBuild(String testHeap, boolean jvmTasks) throws IOException {
        copyBuildLogic();
        write("settings.gradle.kts", """
                pluginManagement {
                    includeBuild("build-logic")
                }
                rootProject.name = "heap-ledger-fixture"
                include("a", "b", "c")
                """);
        // The daemon's heap is stated, so that the ledger counts a known 512 MiB for it; the modules run side by side, as in the build of the repository.
        write("gradle.properties", """
                org.gradle.jvmargs=-Xmx512m
                org.gradle.parallel=true
                """);
        // What java/build.gradle.kts hands the plugin: the three heaps the ledger counts.
        String root = """
                plugins {
                    id("io.github.jaeseongs95.sennard.build-logic")
                }
                sennardHeapLedger.gradleForkHeap = "512m"
                sennardHeapLedger.testHeap = "%s"
                sennardHeapLedger.childHeap = "256m"
                subprojects {
                    apply(plugin = "java")
                    repositories {
                        mavenCentral()
                    }
                    dependencies {
                        // The JUnit line of java/build-logic/build.gradle.kts.
                        "testImplementation"(platform("org.junit:junit-bom:6.0.3"))
                        "testImplementation"("org.junit.jupiter:junit-jupiter")
                        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
                    }
                    tasks.withType<Test> {
                        useJUnitPlatform()
                        maxHeapSize = "%s"
                    }
                }
                """.formatted(testHeap, testHeap);
        String jvmTaskScript = """
                project(":a") {
                    val main = extensions.getByType<SourceSetContainer>()["main"]
                    tasks.register<JavaExec>("hello") {
                        classpath = main.runtimeClasspath
                        mainClass = "Hello"
                    }
                    tasks.register<Exec>("javaVersion") {
                        commandLine(File(System.getProperty("java.home"), "bin/java").path, "-version")
                    }
                }
                """;
        write("build.gradle.kts", jvmTasks ? root + jvmTaskScript : root);
        for (String module : MODULES) {
            write(module + "/src/test/java/HeldTest.java", """
                    import org.junit.jupiter.api.Test;

                    class HeldTest {
                        @Test
                        void runs() {
                        }
                    }
                    """);
        }
        write("a/src/main/java/Hello.java", """
                public class Hello {
                    public static void main(String[] arguments) {
                        System.out.println("hello jvm arguments " + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                    }
                }
                """);
    }

    /** Runs the fixture build; it must succeed, or, with {@code fails}, it must fail. The ledger's folder and a wait of one minute at most are stated for every run. */
    private BuildResult build(boolean fails, String... arguments) {
        List<String> all = Stream.concat(Stream.of(arguments), Stream.of("--console=plain", "--no-watch-fs", "-Psennard.heap.maxWaitMinutes=1", "-Psennard.heap.ledgerDir=" + ledgerFolder)).toList();
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile()).withArguments(all);
        ThrowingSupplier<BuildResult> run = fails ? runner::buildAndFail : runner::build;
        return assertTimeoutPreemptively(DEADLINE, run, "the build " + all + " did not finish");
    }

    private static List<String> listFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    /** The build logic of the repository without its tests, in a folder of this build: a copy, as this build and the build that runs this test must not share one. */
    private void copyBuildLogic() throws IOException {
        Path from = JAVA.resolve("build-logic");
        Path to = project.resolve("build-logic");
        Files.createDirectories(to);
        Files.copy(from.resolve("build.gradle.kts"), to.resolve("build.gradle.kts"));
        Files.copy(from.resolve("settings.gradle.kts"), to.resolve("settings.gradle.kts"));
        Path sources = from.resolve("src/main");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve("src/main").resolve(sources.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }

    private void write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
