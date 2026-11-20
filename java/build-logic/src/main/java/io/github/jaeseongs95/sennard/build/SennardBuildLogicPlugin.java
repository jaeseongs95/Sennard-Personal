// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.io.File;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;
import org.gradle.build.event.BuildEventsListenerRegistry;

/**
 * Applied by the root project of the build. It puts the classes of this included build (the heap ledger) on the classpath of the root script, it gives the root project the
 * lifecycle tasks {@code check} and {@code build}, and it wires the opt-in heap ledger into the build.
 *
 * <p>The wiring is Java code of this plugin and not a script plugin ({@code apply(from = "...kts")}) because a script plugin is compiled with a class loader of its own, isolated from the
 * classes that the {@code plugins {}} block of the root script puts on the classpath, so it cannot name {@link HeapBudget} or {@link HeapReservationService}.
 *
 * <p>The heap ledger ({@link HeapLedger}) is off. A computer on which several builds run at once and must stay within one memory limit switches it on in its own
 * {@code ~/.gradle/gradle.properties} or on the command line, and states the limit: {@code sennard.heap.ledger=true}, {@code sennard.build.heapLimitMiB=<MiB>} (no default and no upper bound).
 * With it on, a build reserves its daemon once and every executing {@link Test}, {@link JavaExec} or {@link Exec} task reserves its heap (a {@code Test} also its child JVMs; an
 * {@code Exec} task gets {@code -Xmx} of the same size in {@code JAVA_TOOL_OPTIONS}); a task waits while the reservations of all builds that use the same ledger folder
 * ({@code sennard.heap.ledgerDir}, the Gradle user home's {@code sennard-heap-ledger} when not given) are over the limit, up to {@code sennard.heap.maxWaitMinutes} (60). A reservation counts the heap
 * that is stated: {@code -Psennard.test.heap} and {@code -Psennard.test.childHeap}, and {@code -Psennard.exec.heap} for a {@code JavaExec} task that states none and for every {@code Exec} task
 * (it is the build's {@link HeapSizes#getGradleForkHeap() gradleForkHeap} when not given).
 *
 * <p>The three sizes that the ledger counts are stated by the applying script in the extension {@value #EXTENSION_NAME} (the root script, java/build.gradle.kts, and the build that
 * HeapLedgerBuildTest of sennard-repo-tools runs through Gradle TestKit): they are read when a task is configured and when it runs, so they must be stated in the body of that script,
 * which Gradle evaluates before the other projects.
 *
 * <p>The tasks of the build that reserve heap (Test, JavaExec, Exec) run one at a time, and Gradle's scheduler is what holds them back (D-0270). The ledger lets a build's own reservations
 * wait for each other only inside the task's first action, which is outside the scheduler: a task that waits there holds one of the {@code --max-workers} places, and the task that holds
 * the reservation can be left without the place it needs to start its test JVM (the deadlock seen with {@code -Psennard.build.heapLimitMiB=2048} and {@code --max-workers=2}). The build's
 * maximum claim is its daemon and its largest task, so one task at a time is what the claim allows; the ledger's wait is left to the other processes. A build service with
 * {@code maxParallelUsages = 1} that every such task declares with {@code usesService} is the standard way: "Gradle will allow no more than the specified number of tasks to use the service
 * concurrently" (https://docs.gradle.org/current/userguide/build_services.html; it takes effect only for a task that declares the service with {@code usesService}). Gradle 9.9.0 (read in
 * the distribution's gradle-core classes, not run): {@code LocalTaskNode.getResourcesToLock} returns the task's shared-resource locks, and the plan executor selects a node only when its
 * locks are acquired, so a task whose slot is taken is not started and holds no worker place. The slot is a service of its own ({@link HeapSlot}): {@link HeapReservationService} is used by
 * every task of the build (the first action of each creates it), and a limit on it would run all tasks one at a time. A build's service lock belongs to that build (the registry of shared
 * resources is of the Build scope: {@code SharedResourceLeaseRegistry}), so this included build cannot take part in this slot: its tests run in the root build, in sennard-repo-tools (see the
 * source sets of that module), not in a Test task of this build.
 *
 * <p>It does not make {@code check} depend on this build's {@code test} (it did, D-0230): a Test task of an included build is outside the root build's limit on the tasks that reserve
 * heap (D-0270). A shared build service limits the tasks that use it per build ({@code SharedResourceLeaseRegistry} is of the Build scope in Gradle 9.9), so that Test, which waits in its
 * first action for the reservation of a Test of the root build, could hold a worker place that the other one needs. The tests of this build run in the root build instead: sennard-repo-tools
 * compiles them with the classes they test (its {@code build.gradle.kts}), and its {@code test} is part of {@code check}.
 */
public abstract class SennardBuildLogicPlugin implements Plugin<Project> {
    /** The name of the extension in which the applying script states the sizes the ledger counts. */
    public static final String EXTENSION_NAME = "sennardHeapLedger";

    /** The JVMs the packaged product starts under a test: the hook command's 256m and the default preset's 512m, 768 MiB at once (HookStartsServerProcessTest). */
    private static final long PRODUCT_CHILDREN_MIB = 256L + 512L;

    /**
     * The three sizes that the heap ledger counts, as JVM size strings such as {@code 512m}: the heap Gradle gives a test JVM when none is stated ({@code gradleForkHeap}, 512m:
     * https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html, maxHeapSize), the heap of a test JVM ({@code testHeap}) and the heap of the JVMs that tests start
     * ({@code childHeap}; a test JVM's children are counted at the larger of two sums: twice this heap, and 768 MiB for the product's own JVMs).
     */
    public interface HeapSizes {
        Property<String> getGradleForkHeap();

        Property<String> getTestHeap();

        Property<String> getChildHeap();
    }

    /** The slot that the tasks reserving heap share, so that Gradle's scheduler runs them one at a time ({@code maxParallelUsages = 1}). It holds nothing. */
    public abstract static class HeapSlot implements BuildService<BuildServiceParameters.None> {
    }

    /** The registry that subscribes {@link HeapReservationService} to the completion of tasks (it releases a task's reservation); a plugin takes it by injection. */
    @Inject
    public abstract BuildEventsListenerRegistry getEventsListenerRegistry();

    @Override
    public void apply(Project project) {
        // `base` gives the root project the lifecycle tasks `check` and `build`, which a project without a language plugin lacks.
        project.getPluginManager().apply("base");
        HeapSizes sizes = project.getExtensions().create(EXTENSION_NAME, HeapSizes.class);
        boolean ledgerOn = project.getProviders().gradleProperty("sennard.heap.ledger").map(SennardBuildLogicPlugin::toBooleanStrict).getOrElse(false);
        if (ledgerOn) {
            wireHeapLedger(project, sizes);
        }
    }

    private void wireHeapLedger(Project project, HeapSizes sizes) {
        ProviderFactory providers = project.getProviders();
        Gradle gradle = project.getGradle();
        Provider<Long> limit = providers.gradleProperty("sennard.build.heapLimitMiB").map(Long::parseLong);
        if (!limit.isPresent()) {
            throw new GradleException("The heap ledger is on (sennard.heap.ledger=true) and its limit is not stated: give -Psennard.build.heapLimitMiB=<MiB>");
        }
        long heapBudgetMiB = limit.get();
        Provider<String> execHeap = providers.gradleProperty("sennard.exec.heap").orElse(sizes.getGradleForkHeap());
        Property<Long> maxTaskClaim = project.getObjects().property(Long.class);
        Provider<HeapReservationService> heapReservation = gradle.getSharedServices().registerIfAbsent("heapReservation", HeapReservationService.class, spec -> {
            HeapReservationService.Parameters parameters = spec.getParameters();
            parameters.getLedgerFolder().set(providers.gradleProperty("sennard.heap.ledgerDir").orElse(new File(gradle.getGradleUserHomeDir(), "sennard-heap-ledger").getPath()));
            parameters.getLimitMiB().set(heapBudgetMiB);
            parameters.getMaxTestMiB().set(maxTaskClaim);
            parameters.getMaxWaitMinutes().set(providers.gradleProperty("sennard.heap.maxWaitMinutes").map(Long::parseLong).getOrElse(60L));
            parameters.getOwner().set(project.getRootDir().getParentFile().getName());
        });
        getEventsListenerRegistry().onTaskCompletion(heapReservation);
        Provider<HeapSlot> heapSlot = gradle.getSharedServices().registerIfAbsent("heapSlot", HeapSlot.class, spec -> spec.getMaxParallelUsages().set(1));

        gradle.projectsEvaluated(evaluated -> maxTaskClaim.set(largestClaimMiB(project, sizes, execHeap.get())));

        project.allprojects(each -> {
            // usesService only declares the service; Gradle creates it when something asks for it, so the first action of every task asks (the later asks return the same instance).
            // The actions are classes that hold only providers: an action cannot hold the project or the plugin, which the configuration cache cannot store.
            each.getTasks().configureEach(task -> {
                task.usesService(heapReservation);
                task.doFirst("reserveHeap", new CreateReservationService(heapReservation));
            });
            each.getTasks().withType(Test.class).configureEach(test -> {
                test.usesService(heapSlot);
                test.doFirst("reserveTestHeap", new ReserveTestHeap(heapReservation, sizes.getTestHeap(), sizes.getChildHeap()));
            });
            // A JavaExec task starts a JVM too (the tools of sennard-repo-tools): it gets a stated heap when it has none, and reserves it before it starts.
            each.getTasks().withType(JavaExec.class).configureEach(exec -> {
                exec.usesService(heapSlot);
                if (exec.getMaxHeapSize() == null) {
                    exec.setMaxHeapSize(execHeap.get());
                }
                exec.doFirst("reserveExecHeap", new ReserveJavaExecHeap(heapReservation, execHeap));
            });
            // An Exec task starts a JVM too (sennard-personal: extractBootJar runs java, jlinkRuntime and appImage run the JDK tools jlink and jpackage, commandAotRecord and commandAotCache run the
            // app-image's launcher): it reserves -Psennard.exec.heap (512m) before it starts, and the JVM gets -Xmx of the same size. The option is added to JAVA_TOOL_OPTIONS, which every one of
            // them reads (https://docs.oracle.com/en/java/javase/25/troubleshoot/environment-variables-and-system-properties.html), because there is no -Xmx argument that all of them take (the
            // image's launcher is given none by the build); it is added when the task runs, after the options the task itself put there (the AOT steps).
            each.getTasks().withType(Exec.class).configureEach(exec -> {
                exec.usesService(heapSlot);
                exec.doFirst("reserveExecHeap", new ReserveExecHeap(heapReservation, execHeap));
            });
        });
    }

    /** The largest claim of one task of the build, in MiB: the build's maximum claim is its daemon and this. */
    private static long largestClaimMiB(Project project, HeapSizes sizes, String execHeap) {
        String testHeap = sizes.getTestHeap().get();
        String childHeap = sizes.getChildHeap().get();
        long execHeapMiB = HeapBudget.parseMiB(execHeap);
        long largest = 0L;
        for (Project each : project.getAllprojects()) {
            for (Test test : each.getTasks().withType(Test.class)) {
                largest = Math.max(largest, testClaimMiB(test, testHeap, childHeap));
            }
            for (JavaExec exec : each.getTasks().withType(JavaExec.class)) {
                largest = Math.max(largest, HeapBudget.parseMiB(exec.getMaxHeapSize() != null ? exec.getMaxHeapSize() : execHeap));
            }
            for (Exec ignored : each.getTasks().withType(Exec.class)) {
                largest = Math.max(largest, execHeapMiB);
            }
        }
        return largest;
    }

    /** What a Test task reserves: every fork's heap and the heap of the JVMs its tests start. */
    private static long testClaimMiB(Test test, String testHeap, String childHeap) {
        long heap = HeapBudget.parseMiB(test.getMaxHeapSize() != null ? test.getMaxHeapSize() : testHeap);
        long children = Math.max(Math.multiplyExact(2L, HeapBudget.parseMiB(childHeap)), PRODUCT_CHILDREN_MIB);
        return Math.multiplyExact((long) test.getMaxParallelForks(), Math.addExact(heap, children));
    }

    private static void reserve(Provider<HeapReservationService> reservation, String path, long mib) {
        try {
            reservation.get().reserveTest(path, mib);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while " + path + " waited for its heap reservation", e);
        }
    }

    /** Kotlin's {@code String.toBooleanStrict()}: only {@code true} and {@code false}, in lower case. */
    private static boolean toBooleanStrict(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("sennard.heap.ledger must be true or false, not '" + value + "'");
        };
    }

    // The task actions are classes with final fields (providers only), not records or lambdas: the configuration cache stores an action by its fields.

    private static final class CreateReservationService implements Action<Task> {
        private final Provider<HeapReservationService> reservation;

        CreateReservationService(Provider<HeapReservationService> reservation) {
            this.reservation = reservation;
        }

        @Override
        public void execute(Task task) {
            reservation.get();
        }
    }

    private static final class ReserveTestHeap implements Action<Task> {
        private final Provider<HeapReservationService> reservation;
        private final Provider<String> testHeap;
        private final Provider<String> childHeap;

        ReserveTestHeap(Provider<HeapReservationService> reservation, Provider<String> testHeap, Provider<String> childHeap) {
            this.reservation = reservation;
            this.testHeap = testHeap;
            this.childHeap = childHeap;
        }

        @Override
        public void execute(Task task) {
            Test test = (Test) task;
            reserve(reservation, test.getPath(), testClaimMiB(test, testHeap.get(), childHeap.get()));
        }
    }

    private static final class ReserveJavaExecHeap implements Action<Task> {
        private final Provider<HeapReservationService> reservation;
        private final Provider<String> execHeap;

        ReserveJavaExecHeap(Provider<HeapReservationService> reservation, Provider<String> execHeap) {
            this.reservation = reservation;
            this.execHeap = execHeap;
        }

        @Override
        public void execute(Task task) {
            JavaExec exec = (JavaExec) task;
            reserve(reservation, exec.getPath(), HeapBudget.parseMiB(exec.getMaxHeapSize() != null ? exec.getMaxHeapSize() : execHeap.get()));
        }
    }

    private static final class ReserveExecHeap implements Action<Task> {
        private final Provider<HeapReservationService> reservation;
        private final Provider<String> execHeap;

        ReserveExecHeap(Provider<HeapReservationService> reservation, Provider<String> execHeap) {
            this.reservation = reservation;
            this.execHeap = execHeap;
        }

        @Override
        public void execute(Task task) {
            Exec exec = (Exec) task;
            String heap = execHeap.get();
            reserve(reservation, exec.getPath(), HeapBudget.parseMiB(heap));
            Object stated = exec.getEnvironment().get("JAVA_TOOL_OPTIONS");
            String options = Stream.of(stated == null ? "" : stated.toString(), "-Xmx" + heap).filter(option -> !option.isBlank()).collect(Collectors.joining(" "));
            exec.environment("JAVA_TOOL_OPTIONS", options);
        }
    }
}
