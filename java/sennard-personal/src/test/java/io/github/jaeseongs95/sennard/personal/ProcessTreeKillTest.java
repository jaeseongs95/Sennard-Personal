// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.execution.StopDeadline;

/**
 * The rounds of {@link ProcessTree#kill()} with the listing of the processes beneath one process given by the test (the constructor takes it; the product's is
 * {@link ProcessHandle#descendants()}), so what each listing answers and when is the test's and no process is started.
 *
 * <pre>
 * k1  a late one   a process that is first found by the listing after the first kill is killed too, before the kill waits, and the kill answers true when nothing of the tree is alive
 * k2  the rounds   a tree that grows at every listing is killed round after round until a listing finds nothing alive, and the kill answers true
 * k3  the deadline a process that does not end when it is killed is waited for no longer than what is left of the deadline of the stop (the grace of the kill is ten seconds), and the
 *                  kill answers false
 * k4  no time      a process that was found alive is asked to end when no time is left to wait for it (a real process): the wait is tied to the time, the request is not
 * </pre>
 */
class ProcessTreeKillTest {
    /** A process the test controls: it is alive until it is killed; killing it ends it, and what was killed is written down in order. */
    private static final class Fake implements ProcessHandle {
        private final long pid;
        private final List<Long> killed;
        private final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
        /** A process that is asked to end and goes on (the kill is a request): it is alive until the test says it is not. */
        private final boolean stubborn;
        private volatile boolean alive = true;

        Fake(long pid, List<Long> killed) {
            this(pid, killed, false);
        }

        Fake(long pid, List<Long> killed, boolean stubborn) {
            this.pid = pid;
            this.killed = killed;
            this.stubborn = stubborn;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            throw new UnsupportedOperationException("the kill does not ask for the information of a process");
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return exit;
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        @Override
        public boolean destroy() {
            return destroyForcibly();
        }

        @Override
        public boolean destroyForcibly() {
            if (alive && !stubborn) {
                alive = false;
                killed.add(pid);
                exit.complete(this);
            }
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ProcessHandle handle && handle.pid() == pid;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(pid);
        }
    }

    @Test
    void aProcessFirstFoundByTheListingAfterTheFirstKillIsKilledTooAndTheKillAnswersWhenNothingIsAlive() {
        List<Long> killed = Collections.synchronizedList(new ArrayList<>());
        Fake root = new Fake(1, killed);
        Fake early = new Fake(2, killed);
        Fake late = new Fake(3, killed);
        AtomicInteger listings = new AtomicInteger();
        // the first listing of the root finds one process; the processes that were killed in that round had started another by the time the listing is taken again
        Function<ProcessHandle, Stream<ProcessHandle>> listing = of -> {
            if (of != root) {
                return Stream.empty();
            }
            return listings.incrementAndGet() == 1 ? Stream.of(early) : Stream.of(early, late);
        };

        boolean noneAlive = new ProcessTree(root, listing).kill();

        assertTrue(noneAlive, "nothing of the tree is alive when the kill answers");
        assertFalse(root.isAlive() || early.isAlive() || late.isAlive());
        assertEquals(List.of(3L), killed.subList(killed.size() - 1, killed.size()), "the late one was killed after the round that did not know it: " + killed);
        assertEquals(3, killed.size(), "each process was killed once: " + killed);
    }

    @Test
    void aTreeThatGrowsAtEveryListingIsKilledRoundAfterRoundUntilNothingIsAliveAndThenTheKillEnds() {
        List<Long> killed = Collections.synchronizedList(new ArrayList<>());
        Fake root = new Fake(1, killed);
        List<Fake> made = new ArrayList<>();
        for (long pid = 2; pid <= 4; pid++) {
            made.add(new Fake(pid, killed));
        }
        AtomicInteger listings = new AtomicInteger();
        // the k-th listing of the root finds the first k processes that have been started (three in all), alive or not
        Function<ProcessHandle, Stream<ProcessHandle>> listing = of -> {
            if (of != root) {
                return Stream.empty();
            }
            int count = Math.min(listings.incrementAndGet(), made.size());
            return made.subList(0, count).stream().map(each -> (ProcessHandle) each);
        };

        boolean noneAlive = new ProcessTree(root, listing).kill();

        assertTrue(noneAlive);
        assertFalse(root.isAlive());
        made.forEach(each -> assertFalse(each.isAlive(), "the process " + each.pid() + " is still alive"));
        assertEquals(4, killed.size(), "the root and the three, each once, in the order they were found: " + killed);
        assertEquals(List.of(2L, 3L, 4L), killed.subList(killed.size() - 3, killed.size()).stream().sorted().toList());
        assertTrue(listings.get() >= 4, "the tree was listed again after each round, until a listing found nothing alive: " + listings.get());
    }

    @Test
    void aProcessThatDoesNotEndIsWaitedForNoLongerThanWhatIsLeftOfTheDeadlineOfTheStop() throws Exception {
        List<Long> killed = Collections.synchronizedList(new ArrayList<>());
        Fake root = new Fake(1, killed, true);
        StopDeadline stop = new StopDeadline(Duration.ofMillis(100));
        stop.arm();

        // without the deadline the kill waits for its whole grace, ten seconds, for a process that stays alive: the limit of the test ends it earlier if the deadline is not kept
        boolean noneAlive = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> new ProcessTree(root, handle -> Stream.empty()).kill(stop::limit));

        assertFalse(noneAlive, "a process of the tree was still alive when the kill gave up");
        assertTrue(root.isAlive());
        assertEquals(Duration.ZERO, stop.remaining(), "it gave up when the deadline had passed, not before");
    }

    /** Writes a file when it is running and does not end by itself. */
    private static final String WAITER = """
            public class Waiter {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    Thread.sleep(600_000);
                }
            }
            """;

    @Test
    void aProcessFoundAliveIsAskedToEndEvenWhenNoTimeIsLeftToWaitForIt(@TempDir Path work) throws Exception {
        RunnerFixture.program(work, "Waiter", WAITER);
        Process process = new ProcessBuilder(RunnerFixture.JAVA, RunnerFixture.JAVA_HEAP, "-cp", ".", "Waiter").directory(work.toFile()).start();
        try {
            RunnerFixture.awaitFile(work.resolve("started.txt"));

            // the stop of the server has no time left: only the wait is tied to the time, a process that was found is asked to end all the same
            boolean noneAlive = new ProcessTree(process.toHandle(), ProcessHandle::descendants).kill(left -> Duration.ZERO);

            assertFalse(noneAlive, "no time was left to see that it ended: the kill does not claim it");
            // what is waited for is the end of the real process (the operating system's wait on it), which only the request for it can bring about; the limit ends the test if it never comes
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the process was asked to end and did");
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
