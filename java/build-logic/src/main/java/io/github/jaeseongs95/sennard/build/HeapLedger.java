// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The heap ledger: every build that uses it reserves the heaps it will hold, and the live reservations together stay within one limit. It is used only where a computer
 * switches it on (SennardBuildLogicPlugin).
 *
 * <p>A reservation is a data file and a lock file of its own in the ledger folder; the build holds the lock ({@link FileChannel#lock}) of the lock file until it ends. Only
 * a locked file is counted: when a process dies the OS releases its lock, so the files it left are not counted and are deleted by the next process that finds them. The
 * check of the sum and the registration are one step under a global lock file ({@code ledger.lock}), so two builds that start together cannot both pass.
 *
 * <p>Admission is first come, first served: a build that registers first takes a numbered ticket (also a data file and a lock file) and is
 * admitted only when its ticket is the oldest live one, so a large request is not overtaken for ever by small ones that arrive later. The Test allocations of a build
 * that is already admitted do not queue (the build would wait for itself); they pass the same safety check of the claims ({@link #safe}), which is what keeps the
 * admitted builds able to finish. A ticket of a process that died is skipped.
 *
 * <p>Java's file locks belong to the JVM, not to a channel, and on Linux (POSIX record locks) closing any channel of a file in the JVM releases the JVM's lock on it.
 * Two rules follow, and the class keeps both: no channel is opened on a file whose lock this JVM holds (a file this process made is recognised by its name, which carries the
 * process, and is never probed), and the global lock file is opened, locked and closed by one thread at a time under one monitor that every class loader of the JVM shares
 * (Gradle may load this class through more than one class loader). The monitor is never held while waiting for another process: the global lock is tried, not waited for.
 */
public final class HeapLedger {
    private static final String RESERVATION_SUFFIX = ".reservation";
    private static final String TICKET_SUFFIX = ".waiting";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String GLOBAL_LOCK = "ledger.lock";
    private static final long GLOBAL_LOCK_POLL_MILLIS = 10L;

    private final Path folder;
    private final long limitMiB;
    private final Duration maxWait;
    private final Duration poll;
    private final Consumer<String> log;
    /** Names this process in the files it makes: its id and start time, so that a reused id is another process. */
    private final String processTag;

    public HeapLedger(Path folder, long limitMiB, Duration maxWait, Duration poll, Consumer<String> log) {
        this.folder = folder;
        this.limitMiB = limitMiB;
        this.maxWait = maxWait;
        this.poll = poll;
        this.log = log;
        ProcessHandle current = ProcessHandle.current();
        Instant started = current.info().startInstant().orElseThrow(() -> new IllegalStateException("The start time of this process is not known, so its reservations cannot be named"));
        this.processTag = current.pid() + "-" + started.getEpochSecond() + "-" + started.getNano();
    }

    /** One step done while this process holds the global lock. */
    @FunctionalInterface
    private interface Step<T> {
        T run() throws IOException;
    }

    /**
     * Runs {@code step} while this process holds the global lock. The monitor is the same object for every class loader of the JVM (an interned name of the canonical folder),
     * and it is held for the whole of open, try-lock, step and close of the lock file, so no other thread of the JVM has a channel on that file meanwhile: closing this
     * one releases only this thread's lock. When another process holds the lock, the channel is closed and the try is repeated after a pause, outside the monitor.
     */
    private <T> T underGlobalLock(Step<T> step) throws IOException, InterruptedException {
        Files.createDirectories(folder);
        Object monitor = (HeapLedger.class.getName() + ":" + folder.toRealPath()).intern();
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            synchronized (monitor) {
                try (FileChannel channel = FileChannel.open(folder.resolve(GLOBAL_LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return step.run();
                    }
                }
            }
            Thread.sleep(GLOBAL_LOCK_POLL_MILLIS);
        }
    }

    /** As {@link #underGlobalLock}, for a release that must happen whatever the caller's interrupt state is; the interrupt is kept for the caller. */
    private <T> T underGlobalLockUninterrupted(Step<T> step, String what) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return underGlobalLock(step);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(what + " could not be done in the heap ledger " + folder, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The files of one reservation or ticket: its data, and the lock file whose lock is held for as long as the holder lives. */
    private static final class Held {
        private final Path data;
        private final Path lockFile;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean released;

        private Held(Path data, Path lockFile, FileChannel channel, FileLock lock) {
            this.data = data;
            this.lockFile = lockFile;
            this.channel = channel;
            this.lock = lock;
        }

        /** Releases the lock and deletes both files; called under the global lock, and harmless a second time. */
        private void releaseLocked() throws IOException {
            if (released) {
                return;
            }
            released = true;
            if (channel.isOpen()) {
                lock.release();
                channel.close();
            }
            Files.deleteIfExists(data);
            Files.deleteIfExists(lockFile);
        }
    }

    /** One live reservation found in the ledger. */
    private record Entry(String owner, long mib, String group, long maximum) {
        @Override
        public String toString() {
            return owner + " " + mib + " MiB";
        }
    }

    /** The reservation of a build's daemon or of one of its Test allocations; closing it releases the lock and deletes its files. */
    public final class Reservation implements AutoCloseable {
        private final Held held;
        private final long mib;

        private Reservation(Held held, long mib) {
            this.held = held;
            this.mib = mib;
        }

        public long mib() {
            return mib;
        }

        @Override
        public void close() {
            underGlobalLockUninterrupted(() -> {
                held.releaseLocked();
                return null;
            }, "The release of the heap reservation " + held.data);
        }
    }

    /** A build's daemon and its subsequent Test (or JavaExec) allocations. */
    public final class BuildReservation implements AutoCloseable {
        private final String owner;
        private final String group;
        private final long maximum;
        private final long daemonMiB;
        private final Reservation daemon;

        private BuildReservation(String owner, String group, long maximum, long daemonMiB, Reservation daemon) {
            this.owner = owner;
            this.group = group;
            this.maximum = maximum;
            this.daemonMiB = daemonMiB;
            this.daemon = daemon;
        }

        /** An allocation of the admitted build: it does not queue behind other builds' admissions, and it must pass the safety check of the claims. */
        public Reservation reserveTest(long mib) throws InterruptedException {
            if (mib <= 0 || mib > maximum - daemonMiB) {
                throw new IllegalStateException("Test reservation " + mib + " MiB exceeds the build's maximum claim " + maximum + " MiB");
            }
            return reserve(owner + " test", mib, group, maximum, false);
        }

        @Override
        public void close() {
            daemon.close();
        }
    }

    /**
     * Registers a build and its maximum claim; the build is admitted in its turn: it waits while the live reservations plus its daemon are over the limit or an older request
     * waits, and fails with the list of the holders after the maximum wait.
     */
    public BuildReservation registerBuild(String owner, long daemonMiB, long maximumMiB) throws InterruptedException {
        if (daemonMiB <= 0 || maximumMiB < daemonMiB || maximumMiB > limitMiB) {
            throw new IllegalStateException("Build claim " + maximumMiB + " MiB with daemon " + daemonMiB + " MiB cannot fit in " + limitMiB + " MiB");
        }
        String group = UUID.randomUUID().toString();
        return new BuildReservation(owner, group, maximumMiB, daemonMiB, reserve(owner + " daemon", daemonMiB, group, maximumMiB, true));
    }

    private Reservation reserve(String owner, long mib, String group, long maximum, boolean queued) throws InterruptedException {
        if (mib <= 0 || mib > limitMiB) {
            throw new IllegalStateException("The reservation of " + owner + " is " + mib + " MiB, over the limit of " + limitMiB + " MiB on its own");
        }
        long deadline = System.nanoTime() + maxWait.toNanos();
        long loggedAt = Long.MIN_VALUE;
        Held ticket = queued ? takeTicket(owner) : null;
        try {
            while (true) {
                List<Entry> live = new ArrayList<>();
                Reservation reservation = tryReserve(owner, mib, group, maximum, ticket, live);
                if (reservation != null) {
                    return reservation;
                }
                long now = System.nanoTime();
                if (now - deadline >= 0) {
                    throw new IllegalStateException(owner + " waited " + maxWait.toMinutes() + " minutes for " + mib + " MiB of heap and the builds of this ledger hold " + sum(live)
                            + " MiB of " + limitMiB + " MiB: " + live);
                }
                if (loggedAt == Long.MIN_VALUE || now - loggedAt >= Duration.ofMinutes(1).toNanos()) {
                    log.accept(owner + " waits for " + mib + " MiB of heap: the builds of this ledger hold " + sum(live) + " MiB of " + limitMiB + " MiB: " + live);
                    loggedAt = now;
                }
                Thread.sleep(poll.toMillis());
            }
        } finally {
            if (ticket != null && !ticket.released) {
                underGlobalLockUninterrupted(() -> {
                    ticket.releaseLocked();
                    return null;
                }, "The release of the heap ticket " + ticket.data);
            }
        }
    }

    /** Takes the next ticket: one more than the newest that exists, which is all the order needs, since tickets are compared only with the ones that live beside them. */
    private Held takeTicket(String owner) throws InterruptedException {
        try {
            return underGlobalLock(() -> {
                long newest = 0L;
                try (DirectoryStream<Path> tickets = Files.newDirectoryStream(folder, "*" + TICKET_SUFFIX)) {
                    for (Path ticket : tickets) {
                        newest = Math.max(newest, sequenceOf(ticket));
                    }
                }
                String name = String.format(Locale.ROOT, "%020d-%s-%s%s", Math.addExact(newest, 1L), processTag, UUID.randomUUID(), TICKET_SUFFIX);
                return create(folder.resolve(name), owner);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("The heap ledger " + folder + " could not be used", e);
        }
    }

    private Reservation tryReserve(String owner, long mib, String group, long maximum, Held ticket, List<Entry> liveOut) throws InterruptedException {
        try {
            return underGlobalLock(() -> {
                liveOut.addAll(liveReservations());
                if (ticket != null && !oldestLiveTicket(ticket.data)) {
                    return null;
                }
                List<Entry> candidate = new ArrayList<>(liveOut);
                candidate.add(new Entry(owner, mib, group, maximum));
                if (!safe(candidate)) {
                    return null;
                }
                Held made = create(folder.resolve(processTag + "-" + UUID.randomUUID() + RESERVATION_SUFFIX), mib + "\t" + owner + "\t" + group + "\t" + maximum);
                try {
                    if (ticket != null) {
                        ticket.releaseLocked();
                    }
                    return new Reservation(made, mib);
                } catch (IOException | RuntimeException failure) {
                    try {
                        made.releaseLocked();
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("The heap ledger " + folder + " could not be used", e);
        }
    }

    /**
     * Makes the data file and then the lock file with its lock held. Called under the global lock: no other process reads the folder meanwhile, so a half-made pair is
     * seen only after a crash, and then as a dead one (no lock file, or a lock nobody holds).
     */
    private static Held create(Path data, String content) throws IOException {
        Path lockFile = data.resolveSibling(data.getFileName() + LOCK_SUFFIX);
        Files.writeString(data, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("A new lock file could not be locked: " + lockFile);
            }
            return new Held(data, lockFile, channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                Files.deleteIfExists(lockFile);
                Files.deleteIfExists(data);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Whether the file was made by this process (any class loader of it): its name starts with this process's tag, after the sequence number of a ticket. */
    private boolean madeByThisProcess(Path file, boolean ticket) {
        String name = file.getFileName().toString();
        if (ticket) {
            name = name.substring(name.indexOf('-') + 1);
        }
        return name.startsWith(processTag + "-");
    }

    /**
     * Whether another process holds the lock of {@code lockFile}. A file of this process is never asked: opening it here and closing it again would release this JVM's lock
     * on it on Linux. A lock file that is gone belongs to a holder that is gone.
     */
    private static boolean heldByAnotherProcess(Path lockFile) throws IOException {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock probe = channel.tryLock();
            if (probe == null) {
                return true;
            }
            probe.release();
            return false;
        } catch (NoSuchFileException gone) {
            return false;
        }
    }

    /** Whether the data file belongs to a live holder; one that does not is deleted with its lock file. Called under the global lock. */
    private boolean aliveOrDelete(Path data, boolean ticket) throws IOException {
        if (madeByThisProcess(data, ticket)) {
            return true;
        }
        Path lockFile = data.resolveSibling(data.getFileName() + LOCK_SUFFIX);
        if (heldByAnotherProcess(lockFile)) {
            return true;
        }
        Files.deleteIfExists(data);
        Files.deleteIfExists(lockFile);
        return false;
    }

    /** The reservations whose holder lives. Called under the global lock. */
    private List<Entry> liveReservations() throws IOException {
        List<Entry> live = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + RESERVATION_SUFFIX)) {
            for (Path file : files) {
                if (aliveOrDelete(file, false)) {
                    live.add(read(file));
                }
            }
        }
        return live;
    }

    private static Entry read(Path file) throws IOException {
        String[] fields = Files.readString(file, StandardCharsets.UTF_8).split("\t", 4);
        if (fields.length != 4) {
            throw new IOException("Invalid heap reservation " + file);
        }
        return new Entry(fields[1], Long.parseLong(fields[0]), fields[2], Long.parseLong(fields[3]));
    }

    private static long sequenceOf(Path ticket) throws IOException {
        String name = ticket.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.indexOf('-')));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IOException("Invalid heap ticket name " + name, e);
        }
    }

    /** Whether {@code mine} is the oldest ticket whose holder lives (the tickets of dead holders are deleted on the way). Called under the global lock. */
    private boolean oldestLiveTicket(Path mine) throws IOException {
        Path oldest = null;
        try (DirectoryStream<Path> tickets = Files.newDirectoryStream(folder, "*" + TICKET_SUFFIX)) {
            for (Path ticket : tickets) {
                if (aliveOrDelete(ticket, true) && (oldest == null || ticket.getFileName().toString().compareTo(oldest.getFileName().toString()) < 0)) {
                    oldest = ticket;
                }
            }
        }
        return mine.equals(oldest);
    }

    /**
     * Whether every build in {@code live} can still finish if each asked for the rest of its maximum claim (the banker's safety check): some build has all it can still ask
     * for available, finishes and gives its heap back, and so on until none is left. A request is granted only into a safe state.
     */
    private boolean safe(List<Entry> live) {
        long available = limitMiB - sum(live);
        if (available < 0) {
            return false;
        }
        Map<String, Long> allocations = new HashMap<>();
        Map<String, Long> maxima = new HashMap<>();
        for (Entry entry : live) {
            allocations.merge(entry.group(), entry.mib(), Long::sum);
            maxima.merge(entry.group(), entry.maximum(), Math::max);
        }
        for (Map.Entry<String, Long> allocation : allocations.entrySet()) {
            if (allocation.getValue() > maxima.get(allocation.getKey())) {
                return false;
            }
        }
        while (!allocations.isEmpty()) {
            String finished = null;
            for (String group : allocations.keySet()) {
                if (maxima.get(group) - allocations.get(group) <= available) {
                    finished = group;
                    break;
                }
            }
            if (finished == null) {
                return false;
            }
            available += allocations.remove(finished);
        }
        return true;
    }

    private static long sum(List<Entry> live) {
        return live.stream().mapToLong(Entry::mib).sum();
    }
}
