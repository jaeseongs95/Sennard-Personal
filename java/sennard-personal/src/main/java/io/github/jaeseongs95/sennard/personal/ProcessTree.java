// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One process and every process found beneath it, and the one place that ends such a tree (the runner of executions and the finder of the processes a server before this one left
 * running both use it, so the process API is used here and not twice).
 *
 * <p>A process is found through {@link ProcessHandle#descendants()}, a snapshot of the processes whose parent it is now. {@link #sample()} takes it for the root and for every process
 * found before that is still alive, and keeps the handles (a handle stays valid for the process it was taken for; a reused id is another process): once a process has lost its parent
 * it is not found through that parent on most systems, but what it starts later is found through the process itself. A process that lost its parent before a snapshot saw it is not
 * found at all: Java gives no process group or job object to a program it starts.
 *
 * <p>{@link #kill()} does not stop at one round: what is found is killed, then the tree is listed again (a process that was alive when the round began may have started another one
 * before it ended), and this repeats until no process of the tree is alive, within {@link #KILL_GRACE}.
 */
final class ProcessTree {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTree.class);
    /** How long a kill is given to leave no process of the tree alive before it is reported as not done (the kill is a request; a process ends asynchronously). */
    static final Duration KILL_GRACE = Duration.ofSeconds(10);

    private final ProcessHandle root;
    /** The processes that a process has beneath it now: {@link ProcessHandle#descendants()} for the product; another listing in a test of the rounds of {@link #kill()}. */
    private final Function<ProcessHandle, Stream<ProcessHandle>> descendants;
    /** The processes found beneath the root so far, by process id. */
    private final Map<Long, ProcessHandle> seen = new ConcurrentHashMap<>();

    /** @param descendants lists the processes beneath one process, as {@link ProcessHandle#descendants()} does */
    ProcessTree(ProcessHandle root, Function<ProcessHandle, Stream<ProcessHandle>> descendants) {
        this.root = root;
        this.descendants = descendants;
    }

    private static ProcessTree ofHandle(ProcessHandle root) {
        return new ProcessTree(root, ProcessHandle::descendants);
    }

    /** The tree of a process this server started. */
    static ProcessTree of(Process process) {
        return ofHandle(process.toHandle());
    }

    /** The tree of the process with this id, when one is alive: it may be a process that nobody here started. */
    static Optional<ProcessTree> aliveWithId(long pid) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(ProcessTree::ofHandle);
    }

    long pid() {
        return root.pid();
    }

    /** When the root started, as the system reports it; empty when the system gives none. */
    Optional<Instant> startedAt() {
        return root.info().startInstant();
    }

    /** Lists the processes beneath the root now and keeps them. */
    void sample() {
        List<ProcessHandle> roots = Stream.concat(Stream.of(root), seen.values().stream().filter(ProcessHandle::isAlive)).toList();
        roots.forEach(each -> descendants.apply(each).forEach(handle -> seen.putIfAbsent(handle.pid(), handle)));
    }

    /** Whether the process with this id has been found beneath the root. */
    boolean found(long pid) {
        return seen.containsKey(pid);
    }

    /**
     * Asks the root and every process found beneath it to end, and does not wait. The tree is listed first: once the root is killed its own children are no longer found through it.
     * The root is asked first so that it starts no new process meanwhile.
     */
    void requestKill() {
        sample();
        destroy(alive());
    }

    /**
     * Kills the root and what is beneath it until no process of the tree is alive, and waits for them to be gone, for at most {@link #KILL_GRACE}.
     *
     * @return whether no process of the tree was alive at the end
     */
    boolean kill() {
        return kill(UnaryOperator.identity());
    }

    /**
     * As {@link #kill()}, and the time that is left of the grace is taken through {@code cap} each time it is looked at: the stop of the server gives the time that is left of its
     * deadline here, so that the wait of the kill does not go past it. The time given to {@code cap} is what the grace has left; what it answers is what may be waited. Only the wait
     * is tied to the time: every process that is found alive is asked to end, whatever time is left, and when none is left the kill answers false without having seen them end.
     */
    boolean kill(UnaryOperator<Duration> cap) {
        long started = System.nanoTime();
        long deadline = started + KILL_GRACE.toNanos();
        while (true) {
            sample();
            List<ProcessHandle> alive = alive();
            if (alive.isEmpty()) {
                return true;
            }
            destroy(alive);
            long left = cap.apply(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))).toNanos();
            if (left <= 0) {
                Duration waited = Duration.ofNanos(System.nanoTime() - started);
                alive.forEach(each -> LOG.warn("The process {} of a killed tree was asked to end and was still alive {} after the kill began; it is not waited for any longer.", each.pid(), waited));
                return false;
            }
            // the processes that were asked to end may have started another one before they ended: list again at once, before the wait, and end what is found, so that the wait is for
            // processes that were all asked to end (one that was only found would otherwise be waited for until the grace is over, alive)
            sample();
            List<ProcessHandle> waited = alive();
            destroy(waited);
            CompletableFuture<?>[] exits = waited.stream().map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(exits).completeOnTimeout(null, left, TimeUnit.NANOSECONDS).join();
        }
    }

    /** The root, then the processes found beneath it, that are alive now. */
    private List<ProcessHandle> alive() {
        return Stream.concat(Stream.of(root), seen.values().stream()).filter(ProcessHandle::isAlive).toList();
    }

    private static void destroy(List<ProcessHandle> doomed) {
        doomed.forEach(ProcessHandle::destroyForcibly);
    }
}
