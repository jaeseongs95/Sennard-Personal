// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.annotation.Isolation;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.execution.EnvironmentSpec;
import io.github.jaeseongs95.sennard.server.execution.ExecutionRequest;
import io.github.jaeseongs95.sennard.server.execution.StopDeadline;
import io.github.jaeseongs95.sennard.server.skills.SkillRunEnvironment;

/**
 * The real runner of the personal edition on a state folder of the test, and the small Java programs its tests run. The programs are compiled once into the folder they run in (the
 * JDK's own compiler, in this JVM) and started as {@code java -cp . Name}, so a program starts in a fraction of a second and nothing depends on the operating system or on any
 * program but the test JVM's own {@code java}. The order of what happens is fixed by files the programs write and wait for; the deadlines here only make a failure end the test.
 */
public final class RunnerFixture implements AutoCloseable {
    public static final String JAVA = ProcessHandle.current().info().command().orElseThrow();
    /** The heap of every JVM the tests start (the property is set on the Test tasks in java/build.gradle.kts). */
    public static final String JAVA_HEAP = "-Xmx" + System.getProperty("sennard.test.childHeap");
    private static final Duration DEADLINE = Duration.ofSeconds(120);

    final Path state;
    public final LocalProcessRunner runner;

    public RunnerFixture(Path state) {
        this(state, 4, 32);
    }

    /** The runner stops within {@code stopDeadline}, which the test may share with the tracker of the records it also stops. */
    public RunnerFixture(Path state, StopDeadline stopDeadline) {
        this(state, 4, 32, stopDeadline);
    }

    RunnerFixture(Path state, int maxConcurrent, int retainedFinished) {
        this(state, maxConcurrent, retainedFinished, new StopDeadline(Duration.ofSeconds(15)));
    }

    private RunnerFixture(Path state, int maxConcurrent, int retainedFinished, StopDeadline stopDeadline) {
        this.state = state;
        SennardProperties settings = new Binder(new MapConfigurationPropertySource(
                Map.of("sennard.run.max-concurrent", Integer.toString(maxConcurrent), "sennard.run.retained-finished", Integer.toString(retainedFinished)))).bindOrCreate("sennard",
                        SennardProperties.class);
        PersonalProperties personal = new PersonalProperties(state, Path.of("."), new PersonalProperties.Store(false, Isolation.READ_COMMITTED));
        this.runner = new LocalProcessRunner(settings, personal, new BatchFileArguments(), new ProgramLocator(), Clock.systemUTC(), stopDeadline);
        this.runner.start();
    }

    /** Compiles {@code source} (the class {@code className}) into {@code folder}. */
    public static void program(Path folder, String className, String source) throws IOException {
        Path file = folder.resolve(className + ".java");
        Files.writeString(file, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The test JVM has no Java compiler");
        }
        int result = compiler.run(null, null, null, "-d", folder.toString(), file.toString());
        if (result != 0) {
            throw new IllegalStateException("The program " + className + " did not compile");
        }
    }

    /** The environment the tests give a program: the variables a program of this system needs to start (the ones a skill's run gets), and nothing else of the server's. */
    public static EnvironmentSpec environment() {
        return new EnvironmentSpec(SkillRunEnvironment.INHERITED_NAMES, Map.of());
    }

    /** A request with the tests' environment and the largest log limit. */
    public static ExecutionRequest request(List<String> argv, Path folder, Duration timeout) {
        return new ExecutionRequest(argv, folder, environment(), timeout, ExecutionRequest.MAX_LOG_BYTES);
    }

    /** {@code java -cp . className args}, in {@code folder}, with the tests' environment. */
    public static ExecutionRequest java(Path folder, Duration timeout, String className, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of(JAVA, JAVA_HEAP, "-cp", ".", className));
        argv.addAll(List.of(args));
        return request(argv, folder, timeout);
    }

    public static void awaitFile(Path file) throws InterruptedException {
        awaitCondition(() -> Files.exists(file), "the file " + file);
    }

    /** The process id a file will hold: waits until the file has a whole number in it (a file can be seen before its text is written). */
    public static long awaitPid(Path file) throws InterruptedException, IOException {
        awaitCondition(() -> {
            try {
                return Files.exists(file) && Files.readString(file).matches("\\d+");
            } catch (IOException notYet) {
                return false;
            }
        }, "the process id in " + file);
        return Long.parseLong(Files.readString(file));
    }

    public static void awaitCondition(BooleanSupplier condition, String what) throws InterruptedException {
        Instant end = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(end)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    public static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public void close() {
        runner.stop();
    }
}
