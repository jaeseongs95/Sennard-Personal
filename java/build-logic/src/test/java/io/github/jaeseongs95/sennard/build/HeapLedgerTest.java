// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ledger of the heaps, with real files and real file locks, and for what only another process can show, real processes ({@link Requester}). Cases:
 *
 * <pre>
 * Case                                         Input                                                        Expected
 * order of arrival (D-0185)                    holder 512; large 1792 waits; small 512 arrives later        small waits behind large, though 1024 are free; large first, then small
 * dead head                                    another process waits at the head and is killed              the request behind it is admitted
 * timeout, interrupt, throwing logger          a waiter ends in each of the three ways                      no ticket is left; the next request is admitted
 * admitted build                               build a admitted, build b waits, a asks for its Test         a's Test is granted past b's ticket; b follows when a ends
 * short deadline behind a waiting head         head waits; another request with a short deadline            it fails at its deadline, the head keeps waiting
 * class loaders and rescans (D-0188)           a reservation of one loader, 200 reserve/close of another    another process is not admitted until the reservation is closed
 * dead holder, live holder                     files nobody holds; a live other process                     not counted and deleted; counted
 * </pre>
 */
class HeapLedgerTest {

    @TempDir
    Path folder;

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<Process> children = new CopyOnWriteArrayList<>();

    private HeapLedger ledger(long limit, Duration wait) {
        return new HeapLedger(folder, limit, wait, Duration.ofMillis(50), messages::add);
    }

    /**
     * A request for heap in its turn: a build whose maximum claim is its daemon, the way the ledger takes one (the ledger's log and failures name it "<owner> daemon").
     */
    private static HeapLedger.BuildReservation request(HeapLedger ledger, String owner, long mib) throws InterruptedException {
        return ledger.registerBuild(owner, mib, mib);
    }

    /**
     * The threads of the requests that wait in a case. They are the test's own and not the common pool of the JVM: that pool is sized by the processors the JVM sees (a test JVM
     * started with -Psennard.test.cpus=<n> sees n, and CompletableFuture falls back to a thread per task or to a pool of n-1), and a request that waits for a minute must not take
     * one of its places from the other requests of the case.
     */
    private final ExecutorService requests = Executors.newCachedThreadPool();

    @AfterEach
    void endTheChildrenAndTheRequests() throws InterruptedException {
        children.forEach(Process::destroyForcibly);
        // A request that still waits (the case failed before it ended) is interrupted and gives its ticket back; the folder is deleted after this.
        requests.shutdownNow();
        assertTrue(requests.awaitTermination(30, TimeUnit.SECONDS), "a request of the case did not end");
    }

    @FunctionalInterface
    private interface Ask<T> {
        T get() throws InterruptedException;
    }

    private <T> CompletableFuture<T> async(Ask<T> ask) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ask.get();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, requests);
    }

    /**
     * Waits until the ledger has refused {@code owner} its turn (it logs that the request waits) or the request has ended. The refusal is what the case observes: a request that
     * is granted at once, or fails, is reported as that and does not wait out the limit; the limit only ends a failure.
     */
    private void awaitRefusal(String owner, CompletableFuture<?> request) throws InterruptedException {
        waitUntil(() -> waited(owner) || request.isDone(), 10);
        if (!waited(owner)) {
            String ended;
            try {
                ended = "was granted " + request.getNow(null);
            } catch (CompletionException | CancellationException failure) {
                ended = "failed with " + failure;
            }
            fail("the request of " + owner + " ended without waiting: it " + ended + "; the log: " + messages);
        }
    }

    private boolean waited(String owner) {
        return messages.stream().anyMatch(message -> message.startsWith(owner + " daemon waits"));
    }

    @Test
    void twoReservationsWithinTheLimitBothRun() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(5));
        try (HeapLedger.BuildReservation a = request(ledger, "a", 1000); HeapLedger.BuildReservation b = request(ledger, "b", 1048)) {
            assertNotNull(a);
            assertNotNull(b);
            assertEquals(2, reservationFiles(), "both are held at once");
            assertTrue(messages.isEmpty(), "no waiting: " + messages);
        }
    }

    @Test
    void aReservationThatPassesTheLimitWaitsUntilTheHolderCloses() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        HeapLedger.BuildReservation first = request(ledger, "first", 1536);
        CompletableFuture<HeapLedger.BuildReservation> second = async(() -> request(ledger, "second", 1536));
        waitUntil(() -> !messages.isEmpty(), 10);
        assertFalse(second.isDone(), "the second waits while the first holds 1536 of 2048");
        assertTrue(messages.get(0).contains("first daemon 1536 MiB"), "the log names who holds how much: " + messages.get(0));
        first.close();
        try (HeapLedger.BuildReservation granted = second.get(10, TimeUnit.SECONDS)) {
            assertNotNull(granted);
            assertEquals(1, reservationFiles(), "the second holds its 1536 now");
        }
    }

    @Test
    void aLargeRequestIsNotOvertakenByLaterSmallOnes() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        HeapLedger.BuildReservation holder = request(ledger, "holder", 512);
        CompletableFuture<HeapLedger.BuildReservation> large = async(() -> request(ledger, "large", 1792));
        awaitRefusal("large", large);
        CompletableFuture<HeapLedger.BuildReservation> small = async(() -> request(ledger, "small", 512));
        // 1536 are free and the small request needs 512: that it is refused on its first try is the order of arrival, observed, with no time given to it to be wrongly granted.
        awaitRefusal("small", small);
        assertFalse(small.isDone(), "the small request arrived later and must not take the room the large one waits for, though 1024 MiB are free");
        assertFalse(large.isDone(), "the large request needs 1792 and 1536 are free");
        holder.close();
        HeapLedger.BuildReservation largeHeld = large.get(10, TimeUnit.SECONDS);
        assertFalse(small.isDone(), "the large one holds 1792 of 2048: 512 are not free for the small one");
        largeHeld.close();
        small.get(10, TimeUnit.SECONDS).close();
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    @Test
    void aDeadHeadOfTheQueueIsSkipped() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(60));
        HeapLedger.BuildReservation holder = request(ledger, "holder", 512);
        External head = new External(2048, 1792, "head");
        waitUntil(() -> head.saw("WAITING"), 60);
        CompletableFuture<HeapLedger.BuildReservation> small = async(() -> request(ledger, "small", 512));
        // 1536 are free and the small request needs 512: its refusal on the first try is the live head of the queue being older, observed.
        awaitRefusal("small", small);
        assertFalse(small.isDone(), "the process at the head is alive and older");
        head.kill();
        small.get(20, TimeUnit.SECONDS).close();
        holder.close();
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    @Test
    void aWaiterThatTimesOutOrIsInterruptedLeavesNoTicket() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        HeapLedger.BuildReservation holder = request(ledger, "holder", 1800);
        assertThrows(IllegalStateException.class, () -> request(ledger(2048, Duration.ofMillis(300)), "late", 600));
        assertEquals(0, ticketFiles(), "the waiter that timed out left its ticket");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HeapLedger.BuildReservation> interrupted = pool.submit(() -> request(ledger, "interrupted", 600));
            waitUntil(() -> waited("interrupted"), 10);
            assertEquals(1, ticketFiles(), "a waiter holds one ticket");
            assertTrue(interrupted.cancel(true));
            waitUntil(() -> ticketFiles() == 0, 10);
        } finally {
            pool.shutdownNow();
        }
        holder.close();
        try (HeapLedger.BuildReservation after = request(ledger, "after", 600)) {
            assertNotNull(after);
            assertEquals(1, reservationFiles(), "the request after the two that ended is admitted");
        }
        assertEquals(0, ticketFiles());
    }

    @Test
    void aWaitLoggerThatThrowsStillRemovesTheTicket() throws Exception {
        HeapLedger.BuildReservation holder = request(ledger(2048, Duration.ofSeconds(30)), "holder", 1800);
        HeapLedger failing = new HeapLedger(folder, 2048, Duration.ofSeconds(30), Duration.ofMillis(50), message -> {
            throw new IllegalStateException("logger failed");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> request(failing, "late", 600));
        assertEquals("logger failed", failure.getMessage());
        assertEquals(0, ticketFiles(), "the ticket of the request whose logger failed is still there");
        holder.close();
        try (HeapLedger.BuildReservation after = request(ledger(2048, Duration.ofSeconds(5)), "after", 600)) {
            assertNotNull(after);
            assertEquals(1, reservationFiles(), "the request after the failed one is admitted");
        }
    }

    @Test
    void anAdmittedBuildsTestPassesAWaitingAdmissionAndTheWaitingBuildFollows() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        CompletableFuture<HeapLedger.BuildReservation> second;
        try (HeapLedger.BuildReservation first = ledger.registerBuild("a", 512, 1792)) {
            second = async(() -> ledger.registerBuild("b", 512, 1792));
            waitUntil(() -> waited("b"), 10);
            try (HeapLedger.Reservation test = first.reserveTest(1280)) {
                assertEquals(1280, test.mib(), "the admitted build's Test does not queue behind the waiting admission");
                assertFalse(second.isDone());
            }
            assertFalse(second.isDone(), "the first build still holds its daemon");
        }
        try (HeapLedger.BuildReservation admitted = second.get(10, TimeUnit.SECONDS); HeapLedger.Reservation test = admitted.reserveTest(1280)) {
            assertEquals(1280, test.mib());
        }
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    @Test
    void aRequestWithAShortDeadlineBehindAWaitingHeadFailsAtItsDeadlineAndTheHeadKeepsWaiting() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        HeapLedger.BuildReservation holder = request(ledger, "holder", 1536);
        CompletableFuture<HeapLedger.BuildReservation> head = async(() -> request(ledger, "head", 1024));
        waitUntil(() -> waited("head"), 10);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> request(ledger(2048, Duration.ofMillis(500)), "independent", 1));
        assertTrue(failure.getMessage().contains("independent daemon waited"), failure.getMessage());
        assertFalse(head.isDone());
        holder.close();
        head.get(10, TimeUnit.SECONDS).close();
        assertEquals(0, ticketFiles());
    }

    @Test
    void aReservationOfAHolderThatIsGoneIsNotCountedAndItsFilesAreDeleted() throws Exception {
        Path withLockFile = folder.resolve("gone-1.reservation");
        Path lockFile = folder.resolve("gone-1.reservation.lock");
        Path withoutLockFile = folder.resolve("gone-2.reservation");
        Files.writeString(withLockFile, "2000\tgone pid 1\tgroup-1\t2000");
        Files.writeString(lockFile, "");
        Files.writeString(withoutLockFile, "2000\tgone pid 2\tgroup-2\t2000");
        try (HeapLedger.BuildReservation reservation = request(ledger(2048, Duration.ofSeconds(5)), "live", 2000)) {
            assertNotNull(reservation);
            assertEquals(1, reservationFiles(), "the live request is admitted although the gone holders' files name 4000 MiB");
            assertFalse(Files.exists(withLockFile) || Files.exists(lockFile) || Files.exists(withoutLockFile), "the leftovers of the gone holders are deleted");
        }
    }

    @Test
    void aReservationOfAnotherLiveProcessIsCountedUntilItEnds() throws Exception {
        External holder = new External(2048, 512, "old-build");
        waitUntil(() -> holder.saw("ADMITTED"), 60);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> request(ledger(2048, Duration.ofMillis(300)), "new", 1600));
        assertTrue(failure.getMessage().contains("old-build daemon 512 MiB"), failure.getMessage());
        holder.release();
        assertTrue(holder.process.waitFor(30, TimeUnit.SECONDS), "the holder ends when its input ends");
        try (HeapLedger.BuildReservation build = ledger(2048, Duration.ofSeconds(5)).registerBuild("new", 512, 1792);
                HeapLedger.Reservation test = build.reserveTest(1280)) {
            assertEquals(1280, test.mib());
        }
        assertEquals(0, reservationFiles());
    }

    @Test
    void closingDeletesTheReservationFiles() throws Exception {
        HeapLedger.BuildReservation reservation = request(ledger(2048, Duration.ofSeconds(5)), "a", 100);
        assertEquals(1, reservationFiles());
        reservation.close();
        assertEquals(0, reservationFiles());
        assertEquals(0, lockFiles());
    }

    @Test
    void afterTheMaximumWaitItFailsWithTheListOfHolders() throws Exception {
        try (HeapLedger.BuildReservation holder = request(ledger(2048, Duration.ofSeconds(5)), "holder pid 7", 1800)) {
            assertNotNull(holder);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> request(ledger(2048, Duration.ofMillis(300)), "late", 600));
            assertTrue(failure.getMessage().contains("holder pid 7 daemon 1800 MiB") && failure.getMessage().contains("600 MiB"), failure.getMessage());
        }
    }

    @Test
    void aReservationOverTheLimitOnItsOwnFailsAtOnce() {
        assertThrows(IllegalStateException.class, () -> request(ledger(2048, Duration.ofSeconds(5)), "big", 4096));
    }

    @Test
    void aTestThatCannotFitWithItsDaemonFailsWithoutWaiting() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(30));
        try (var build = ledger.registerBuild("build", 512, 2048)) {
            // The way, not the time: the request is refused by the check against the build's own maximum claim, before the wait loop that logs "waits for" and
            // ends with "waited ... minutes" is entered, although the wait allowed is 30 seconds.
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> build.reserveTest(1600));
            assertTrue(refused.getMessage().contains("exceeds the build's maximum claim"), refused.getMessage());
            assertFalse(refused.getMessage().contains("waited"), refused.getMessage());
            assertTrue(messages.stream().noneMatch(message -> message.contains("waits for")), messages.toString());
        }
        assertEquals(0, reservationFiles());
    }

    @Test
    void threeBuildsCanAllFinishWithoutHoldAndWaitDeadlock() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(15));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch admitted = new CountDownLatch(3);
        CountDownLatch requestTests = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var futures = java.util.stream.IntStream.range(0, 3).mapToObj(index -> pool.submit(() -> {
                started.countDown();
                start.await();
                try (HeapLedger.BuildReservation build = ledger.registerBuild("build-" + index, 512, 1792)) {
                    admitted.countDown();
                    requestTests.await();
                    try (HeapLedger.Reservation test = build.reserveTest(1280)) {
                        assertEquals(1280, test.mib());
                    }
                }
                return null;
            })).toList();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            start.countDown();
            try {
                boolean allAdmitted = admitted.await(2, TimeUnit.SECONDS);
                requestTests.countDown();
                assertFalse(allAdmitted, "all three daemons were admitted into a hold-and-wait deadlock");
                for (Future<?> future : futures) {
                    future.get(20, TimeUnit.SECONDS);
                }
            } finally {
                requestTests.countDown();
                futures.forEach(future -> future.cancel(true));
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    @Test
    void multipleTestsOfOneBuildCannotExceedItsClaim() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(10));
        try (var build = ledger.registerBuild("build", 512, 1792);
                var first = build.reserveTest(1280);
                var pool = Executors.newSingleThreadExecutor()) {
            assertEquals(1280, first.mib());
            var second = pool.submit(() -> build.reserveTest(1280));
            waitUntil(() -> !messages.isEmpty(), 10);
            assertFalse(second.isDone());
            assertTrue(second.cancel(true));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(0, reservationFiles());
        try (var build = ledger.registerBuild("later", 512, 1792);
                var test = build.reserveTest(1280)) {
            assertEquals(1280, test.mib());
        }
    }

    @Test
    void exceptionsReleaseBothTestAndDaemonReservations() throws Exception {
        HeapLedger ledger = ledger(2048, Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> {
            try (var build = ledger.registerBuild("fails", 512, 1792);
                    var test = build.reserveTest(1280)) {
                assertEquals(1280, test.mib());
                throw new IllegalArgumentException("intentional failure");
            }
        });
        assertEquals(0, reservationFiles());
        try (var build = ledger.registerBuild("after-failure", 512, 1792);
                var test = build.reserveTest(1280)) {
            assertEquals(1280, test.mib());
        }
    }

    @Test
    void separateClassLoadersCoordinateTheGlobalFileLock() throws Exception {
        var classes = HeapLedger.class.getProtectionDomain().getCodeSource().getLocation();
        try (var first = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
                var second = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
                var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (var loader : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    Object instance = newLedger(loader, 2048L, Duration.ofSeconds(10));
                    Method register = instance.getClass().getMethod("registerBuild", String.class, long.class, long.class);
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        try (var held = (AutoCloseable) register.invoke(instance, "classloader", 1L, 1L)) {
                            assertNotNull(held);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    /**
     * D-0188: one class loader of the JVM holds a reservation while another reads the same folder again and again (the Gradle build script and the build-logic build load the ledger
     * twice). On Linux, closing any channel to a file the JVM holds a lock on releases that lock, so a ledger that opens the other loader's files to read them lets another
     * process find them unlocked and admit itself over the limit. On Windows the same case passes before the fix as well: the OS keeps the lock.
     */
    @Test
    void aReservationOfOneClassLoaderStaysHeldWhileAnotherReadsTheFolderAgainAndAgain() throws Exception {
        var classes = HeapLedger.class.getProtectionDomain().getCodeSource().getLocation();
        try (var first = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
                var second = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Object holderLedger = newLedger(first, 2048L, Duration.ofSeconds(30));
            Object scannerLedger = newLedger(second, 2048L, Duration.ofSeconds(30));
            AutoCloseable held = (AutoCloseable) holderLedger.getClass().getMethod("registerBuild", String.class, long.class, long.class).invoke(holderLedger, "held", 1536L, 1536L);
            AutoCloseable scanner = (AutoCloseable) scannerLedger.getClass().getMethod("registerBuild", String.class, long.class, long.class).invoke(scannerLedger, "scanner", 100L, 300L);
            External other = new External(2048, 1024, "other");
            waitUntil(() -> other.saw("WAITING"), 60);
            Method reserveTest = scanner.getClass().getMethod("reserveTest", long.class);
            for (int i = 0; i < 200; i++) {
                try (var scan = (AutoCloseable) reserveTest.invoke(scanner, 1L)) {
                    assertNotNull(scan);
                }
            }
            Thread.sleep(500);
            assertFalse(other.saw("ADMITTED"), "another process was admitted while the reservation of the first class loader was still held: its lock was lost");
            held.close();
            waitUntil(() -> other.saw("ADMITTED"), 60);
            other.release();
            assertTrue(other.process.waitFor(30, TimeUnit.SECONDS), "the other process ends when its input ends");
            assertEquals(0, other.process.exitValue(), other.lines.toString());
            scanner.close();
        }
        assertEquals(0, reservationFiles());
    }

    @Test
    void threeSeparateProcessesCanFinishUsingTheSameLedger() throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<Process> processes = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                processes.add(new ProcessBuilder(javaExecutable.toString(), "-Xmx16m", "-cp", classpath(),
                        HeapLedgerTest.ProcessBuild.class.getName(), folder.toString(), Integer.toString(i)).redirectErrorStream(true).start());
            }
            for (Process process : processes) {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child process did not finish");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
            }
        } finally {
            for (Process process : processes) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        }
        assertEquals(0, reservationFiles());
        assertEquals(0, ticketFiles());
    }

    /** A build in a process of its own: its daemon and one Test, with the others at the same time. */
    public static final class ProcessBuild {
        public static void main(String[] args) throws Exception {
            Path folder = Path.of(args[0]);
            Files.writeString(folder.resolve("ready-" + args[1]), "ready");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                try (var files = Files.list(folder)) {
                    if (files.filter(path -> path.getFileName().toString().startsWith("ready-")).count() == 3) {
                        break;
                    }
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Other processes did not start");
                }
                Thread.sleep(20);
            }
            HeapLedger ledger = new HeapLedger(folder, 2048, Duration.ofSeconds(30), Duration.ofMillis(50), System.out::println);
            try (var build = ledger.registerBuild("process-" + args[1], 512, 1792);
                    var test = build.reserveTest(1280)) {
                if (test.mib() != 1280) {
                    throw new IllegalStateException("Incorrect allocation");
                }
                Thread.sleep(200);
            }
        }
    }

    /** A request in a process of its own: says WAITING when it has to wait, ADMITTED when it holds its reservation, and lets go when its input ends. */
    public static final class Requester {
        public static void main(String[] args) throws Exception {
            HeapLedger ledger = new HeapLedger(Path.of(args[0]), Long.parseLong(args[1]), Duration.ofSeconds(120), Duration.ofMillis(20), message -> {
                System.out.println("WAITING");
                System.out.flush();
            });
            long mib = Long.parseLong(args[2]);
            HeapLedger.BuildReservation held = ledger.registerBuild(args[3], mib, mib);
            try {
                System.out.println("ADMITTED " + mib);
                System.out.flush();
                System.in.read();
            } finally {
                held.close();
            }
        }
    }

    /** The other process of a case, with what it printed so far. */
    private final class External {
        final Process process;
        final List<String> lines = new CopyOnWriteArrayList<>();

        External(long limit, long mib, String name) throws IOException {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx16m", "-cp", classpath(), Requester.class.getName(),
                    folder.toString(), Long.toString(limit), Long.toString(mib), name).redirectErrorStream(true).start();
            children.add(process);
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line.startsWith("ADMITTED") ? "ADMITTED" : line);
                    }
                } catch (IOException e) {
                    lines.add("READ-FAILED " + e);
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        boolean saw(String line) {
            return lines.contains(line);
        }

        /** Ends its input, so that it lets go and ends. */
        void release() throws IOException {
            process.getOutputStream().close();
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the process did not end");
        }
    }

    @Test
    void sizesAreParsedWithTheJvmSuffixRulesAndRoundedUp() {
        assertEquals(512, HeapBudget.parseMiB("512m"));
        assertEquals(1024, HeapBudget.parseMiB("1g"));
        assertEquals(1, HeapBudget.parseMiB("1024k"));
        assertEquals(1, HeapBudget.parseMiB("1"), "no suffix is bytes, rounded up to one MiB");
        assertThrows(IllegalArgumentException.class, () -> HeapBudget.parseMiB("lots"));
    }

    /** A ledger of {@code loader}, made by reflection: the class the loader defines is not the class of this test. */
    private Object newLedger(ClassLoader loader, long limit, Duration wait) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass(HeapLedger.class.getName());
        var constructor = type.getConstructor(Path.class, long.class, Duration.class, Duration.class, java.util.function.Consumer.class);
        return constructor.newInstance(folder, limit, wait, Duration.ofMillis(5), (java.util.function.Consumer<String>) message -> { });
    }

    private static String classpath() {
        try {
            return Path.of(HeapLedgerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + File.pathSeparator + Path.of(HeapLedger.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String suffix) {
        try (var files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long reservationFiles() {
        return count(".reservation");
    }

    private long ticketFiles() {
        return count(".waiting");
    }

    private long lockFiles() {
        return count(".lock") - 1; // ledger.lock is the global lock file, which stays
    }

    private static void waitUntil(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "the condition was not met in time");
    }
}
