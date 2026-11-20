// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.skills.SkillProcessRunner;

/**
 * The child of a test-engineering run: the caller's own command, started from its argv with no shell, its output read from its pipes by {@link ProcessOutput} (one virtual thread
 * per stream; nothing of it is written to disk). Whoever can connect to the server's loopback port can ask for a run: the guard filter checks the headers
 * and the body size of a request and does not ask who connected (it is not a peer check), so a run has the authority of the server's user: on the personal edition that is this
 * PC's user, and the loopback-only listening address is what keeps others out. What the runner itself bounds is what a run can take from the computer:
 * <ul>
 * <li>how many run at once ({@code sennard.run.max-concurrent}); one asked for beyond that is refused at once;</li>
 * <li>how much of each of stdout and stderr is kept: the first {@value ProcessOutput#LIMIT} bytes, the rest is dropped as it is read. A command that is still running when
 * the runner looks and finds more than that was written is killed with what it started, not left to the time limit; one that has ended is only marked as truncated;</li>
 * <li>the environment: nothing of the server's except {@link RunEnvironment#INHERITED_NAMES}, then the caller's variables;</li>
 * <li>what is left behind: every running command is tracked, so the server's stop ({@link SmartLifecycle}) kills them with what they started, and a command that ends on its own
 * has the processes it started that are still alive killed.</li>
 * </ul>
 *
 * <p>A process the command started and detached from it can still hold the pipes of the output open after the command ended. When the command has ended the runner kills the
 * processes it knows, then {@link ProcessOutput#finish()} takes what is in the pipes, waits at most a short grace for a process that keeps writing, and closes its end of them
 * and ends its readers: a process that still writes gets a broken pipe, so the run does not wait for it, nothing it writes is kept past the limit or reaches the disk, and no
 * reader thread or pipe end of the run is left when the run answers. The output is finished once for every run, whichever way the run ends.
 *
 * <p>A process is found through {@link ProcessHandle#descendants()}, a snapshot of the processes whose parent it is now. The runner takes that snapshot as soon as the child
 * is started and then every {@value #SAMPLE_MILLIS} ms while it runs, of the child and of every process already found that is still alive, and keeps the handles: a process
 * the child started and left running is found after the child ended (on most systems a process that lost its parent is not found through it), and so is what a process that
 * lost its parent started after that. A process that lost its parent before a snapshot saw it (the shell of {@code sh -c 'sleep 300 &'} ends at once and the process it started
 * has no parent of the child's by the first snapshot; on Windows the middle process of a doubled {@code start /b} ends first) is not found and is not killed: it goes on
 * running after the run, cut off from the output only. The runner has no handle on such a process short of a process group or a job object, which Java does not give a command
 * it starts through {@link ProcessBuilder}. The output is looked at every {@code sennard.run.output-check-interval} (25 ms by default) for the limit, so a command that writes faster
 * than it is read goes on for at most that long. A command that ends before the first look is not killed for its output: it ended by itself, and its exit code is kept.
 */
@Component
public class LocalProcessRunner implements SkillProcessRunner, SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(LocalProcessRunner.class);
    /**
     * How often the processes the child started are listed while it runs. The listing is a snapshot of the process table; what it finds is killed at the end of the run. A process it
     * misses cannot write to the output after the run (the pipe is closed), so a shorter interval would only kill more of what is left, not protect the output.
     */
    private static final long SAMPLE_MILLIS = 200;
    /** How long a killed process is given to be gone before this run answers (the kill is a request; the process ends asynchronously). */
    private static final Duration KILL_GRACE = Duration.ofSeconds(10);
    /** How long the end of the output streams is waited for after the command ended and what was found beneath it was killed, before the runner closes its end of them. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

    private final int maxConcurrent;
    /** How often the output is looked at for the limit ({@code sennard.run.output-check-interval}; the settings accept at least a millisecond). */
    private final long outputCheckNanos;
    private final RunEnvironment environments;
    private final BatchFileArguments batchFiles;
    private final ProgramLocator locator;
    private final Semaphore permits;
    private final Set<Child> running = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private volatile boolean stopped;

    public LocalProcessRunner(SennardProperties settings, RunEnvironment environments, BatchFileArguments batchFiles, ProgramLocator locator) {
        this.maxConcurrent = settings.run().maxConcurrent();
        this.outputCheckNanos = settings.run().outputCheckInterval().toNanos();
        this.environments = environments;
        this.batchFiles = batchFiles;
        this.locator = locator;
        this.permits = new Semaphore(maxConcurrent);
    }

    @Override
    public String platform() {
        OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
        return system.getName() + " " + system.getVersion() + " " + system.getArch();
    }

    @Override
    public String describeEnvironment(Set<String> callerVariableNames) {
        return environments.describe(callerVariableNames);
    }

    // ---- the server's life

    @Override
    public void start() {
        active = true;
    }

    /** Refuses new runs and kills every running command with what it started. */
    @Override
    public void stop() {
        stopped = true;
        active = false;
        List<Child> live = List.copyOf(running);
        if (!live.isEmpty()) {
            LOG.info("The server is stopping: killing {} running command(s) of test runs and what they started.", live.size());
        }
        live.forEach(Child::kill);
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    // ---- a run

    @Override
    public Completed run(Path workingDirectory, List<String> argv, Map<String, String> environment, Duration timeout) {
        if (stopped) {
            throw Refused.stopping();
        }
        batchFiles.requireSafe(argv);
        if (!permits.tryAcquire()) {
            throw Refused.tooManyRuns(maxConcurrent);
        }
        try {
            return runWithPermit(workingDirectory, argv, environment, timeout);
        } finally {
            permits.release();
        }
    }

    private Completed runWithPermit(Path workingDirectory, List<String> argv, Map<String, String> environment, Duration timeout) {
        List<String> command = new ArrayList<>(argv);
        command.set(0, locator.locate(argv.getFirst(), workingDirectory));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        Map<String, String> childEnvironment = environments.build(builder.environment(), environment);
        builder.environment().clear();
        builder.environment().putAll(childEnvironment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException notStarted) {
            LOG.info("The command of a test run could not be started: {}", argv.getFirst(), notStarted);
            return new Failed("", "");
        }
        Child child = new Child(process);
        ProcessOutput output = new ProcessOutput(process, DRAIN_GRACE);
        running.add(child);
        Ending ending;
        try {
            // What the child started in its first moments is listed now, not after the first interval.
            child.sample();
            closeInput(process);
            if (stopped) {
                // stop() looked at the running commands before this one was in the set
                child.kill();
                ending = Ending.FAILED;
            } else {
                ending = await(child, output, timeout);
            }
        } finally {
            running.remove(child);
            // The one place the output is finished, for every way out of the run, an unexpected exception included: the wait for the readers is paid once. An interrupt that
            // await() set again does not cut it short (the wait is not interruptible), so nothing is lost from the output when the run was interrupted.
            output.finish();
        }
        return complete(ending, child.process(), output);
    }

    /** How a run ended, before its output is finished and read. */
    private enum Ending {
        /** The child ended by itself (or at the limit, between two looks); the exit code is read from the process. */
        EXITED,
        /** The child was still running at the limit and was killed. */
        TIMED_OUT,
        /** The run did not run to an end: it was killed for its output or for the server's stop, or the wait for it was interrupted. */
        FAILED
    }

    private Completed complete(Ending ending, Process process, ProcessOutput output) {
        ProcessOutput.Kept out = output.stdout();
        ProcessOutput.Kept err = output.stderr();
        return switch (ending) {
            case TIMED_OUT -> new TimedOut(out.text(), err.text());
            case FAILED -> new Failed(out.text(), err.text());
            case EXITED -> stopped ? new Failed(out.text(), err.text()) : new Exited(process.exitValue(), out.text(), err.text(), out.truncated() || err.truncated());
        };
    }

    /** Waits for the child and watches its output; every way out has killed what it had to kill, and none has finished the output (the caller does). */
    private Ending await(Child child, ProcessOutput output, Duration timeout) {
        Process process = child.process();
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAMPLE_MILLIS);
        long nextLook = System.nanoTime() + outputCheckNanos;
        long stepMillis = Math.min(SAMPLE_MILLIS, TimeUnit.NANOSECONDS.toMillis(outputCheckNanos));
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    if (process.isAlive()) {
                        child.kill();
                        return Ending.TIMED_OUT;
                    }
                    break;
                }
                if (process.waitFor(Math.min(stepMillis, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))), TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (stopped) {
                    child.kill();
                    return Ending.FAILED;
                }
                if (System.nanoTime() - nextLook >= 0) {
                    nextLook = System.nanoTime() + outputCheckNanos;
                    if (output.truncated()) {
                        LOG.info("A command of a test run wrote more than {} bytes to stdout or stderr while it ran and was killed.", ProcessOutput.LIMIT);
                        child.kill();
                        return Ending.FAILED;
                    }
                }
                if (System.nanoTime() - nextSample >= 0) {
                    child.sample();
                    nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAMPLE_MILLIS);
                }
            }
        } catch (InterruptedException interrupted) {
            child.kill();
            Thread.currentThread().interrupt();
            return Ending.FAILED;
        }
        child.killLeftovers();
        return Ending.EXITED;
    }

    /** The child reads end of input at once: a program that waits for input would otherwise wait for the limit. */
    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException alreadyClosed) {
            LOG.debug("The input of the child was already closed.", alreadyClosed);
        }
    }

    /** One running command: the child and every process found beneath it. */
    static final class Child {
        private final Process process;
        /** The processes found beneath the child so far, by process id; a handle stays valid for the process it was taken for (a reused id is another process). */
        private final Map<Long, ProcessHandle> seen = new ConcurrentHashMap<>();

        Child(Process process) {
            this.process = process;
        }

        Process process() {
            return process;
        }

        /**
         * Lists the processes beneath the child now and keeps them. The listing starts at the child and at every process found before that is still alive: once the child has
         * ended, a process it started has lost its parent on most systems, and what that process starts later is found only through the process itself.
         */
        void sample() {
            List<ProcessHandle> roots = Stream.concat(Stream.of(process.toHandle()), seen.values().stream().filter(ProcessHandle::isAlive)).toList();
            roots.forEach(root -> root.descendants().forEach(handle -> seen.putIfAbsent(handle.pid(), handle)));
        }

        /** Whether the process with this id has been found beneath the child. */
        boolean found(long pid) {
            return seen.containsKey(pid);
        }

        /**
         * Kills the child and every process found beneath it, and waits (bounded) until they are gone. The processes are listed first: once the child is killed its own
         * children are no longer found through it. The child is killed before them so that it starts no new one meanwhile.
         */
        void kill() {
            sample();
            process.destroyForcibly();
            destroyAndAwait(Stream.concat(Stream.of(process.toHandle()), seen.values().stream()).toList());
        }

        /** After the child ended by itself: kills the processes found beneath it that are still alive. */
        void killLeftovers() {
            sample();
            destroyAndAwait(seen.values().stream().filter(ProcessHandle::isAlive).toList());
        }

        private static void destroyAndAwait(List<ProcessHandle> doomed) {
            doomed.forEach(ProcessHandle::destroyForcibly);
            CompletableFuture<?>[] exits = doomed.stream().map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(exits).completeOnTimeout(null, KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS).join();
            doomed.stream().filter(ProcessHandle::isAlive).forEach(alive -> LOG.warn("The process {} of a command run was still alive {} after it was killed.", alive.pid(), KILL_GRACE));
        }
    }
}
