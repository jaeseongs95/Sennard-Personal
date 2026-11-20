// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stdout and stderr of a command, read from its pipes: one virtual thread per stream reads and keeps the first {@value #LIMIT} bytes. What is read after that is read and
 * dropped (a command that writes more is not blocked on a full pipe, and nothing past the limit is kept in memory or written to disk) and the stream is marked as truncated.
 * This is the one place of the product that reads the pipes of a process it started; the pipes are only read here, never by a caller that would wait on them without an end.
 *
 * <p>A pipe has an end of stream only when every process that has it open has closed it, and a process the command started and detached from it can hold it open for as long as
 * it lives, writing nothing. A read of such a pipe does not return, and nothing wakes it: the JDK does not interrupt a reader that is blocked in a read of a pipe when the stream
 * is closed from another thread (on Linux the read goes on holding the pipe). The reader would stay parked for as long as the detached process lives, with its end of the pipe
 * open, and on Linux it would also hold the monitor of the stream that the JDK's process reaper thread needs when the command ends, so that thread would stay blocked too (one
 * leaked pair of readers, one blocked reaper thread and two open pipe ends for every such run).
 *
 * <p>So a reader never makes a read that can wait: it reads only what {@link InputStream#available()} says is in the pipe now, and parks for {@value #POLL_MILLIS} ms when there
 * is nothing. {@link #finish()} then ends the readers by saying so, in two steps: it lets them take what is in the pipes at that point and end at the first moment there is
 * nothing more (the bytes of a command that ended were written before it ended, so they are in the pipe), and when a process that still writes keeps them from finding the pipe
 * empty it releases them after the grace it was given. A reader that ends closes its stream (a process that still writes to it gets a broken pipe from the operating system), and
 * {@link #finish()} returns after both readers ended: when it returns no thread of this object is left and both pipes are closed, whatever the processes the command left
 * running do. What such a process writes in the grace is read like the rest and counts toward the limit.
 */
final class ProcessOutput {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessOutput.class);
    /** What is kept of each of stdout and stderr, in bytes. */
    static final int LIMIT = 1024 * 1024;
    private static final int READ_SIZE = 8192;
    /** How long a reader parks when the pipe has nothing in it. Parking is a virtual thread waiting; it holds no resource, and {@link #finish()} wakes the reader. */
    static final long POLL_MILLIS = 5;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS);

    /** What was kept of one stream: its first {@value #LIMIT} bytes as text (malformed UTF-8 is replaced) and whether more was written. */
    record Kept(String text, boolean truncated) {
    }

    /** What {@link #finish()} has asked of a reader so far; each step includes the one before. */
    private enum Phase {
        /** The command runs: the reader takes what comes and waits when there is nothing. */
        READING,
        /** The command has ended: the reader takes what is in the pipe and ends when it finds nothing more. */
        DRAINING,
        /** The grace is over: the reader ends without taking more. */
        RELEASED
    }

    private final Pump stdout;
    private final Pump stderr;
    private final Duration drainGrace;
    private boolean finished;
    private int waits;

    /**
     * Starts reading the output of {@code process}. {@code drainGrace} is how long {@link #finish()} waits for a reader that finds more in the pipe, before and after it
     * releases the readers.
     */
    ProcessOutput(Process process, Duration drainGrace) {
        this(process.getInputStream(), process.getErrorStream(), drainGrace);
    }

    /** Starts reading both streams. */
    ProcessOutput(InputStream stdout, InputStream stderr, Duration drainGrace) {
        this.stdout = new Pump(stdout);
        this.stderr = new Pump(stderr);
        this.drainGrace = drainGrace;
        this.stdout.start();
        this.stderr.start();
    }

    /** Whether either stream has had more than {@value #LIMIT} bytes written to it so far. */
    boolean truncated() {
        return stdout.truncated() || stderr.truncated();
    }

    /**
     * Ends the readers and closes both streams, as the class description says, and returns when both readers ended (a reader that did not end within the grace after the release
     * is logged and not waited for any longer). The first call does this; a call after it returns at once, so a run that has several ways out can call it on every one of them
     * and wait only once.
     */
    synchronized ProcessOutput finish() {
        if (finished) {
            return this;
        }
        finished = true;
        stdout.advance(Phase.DRAINING);
        stderr.advance(Phase.DRAINING);
        awaitEnd();
        stdout.advance(Phase.RELEASED);
        stderr.advance(Phase.RELEASED);
        awaitEnd();
        if (!stdout.ended.isDone() || !stderr.ended.isDone()) {
            LOG.warn("A reader of the output of a command did not end within {} after it was released.", drainGrace);
        }
        return this;
    }

    /** How many times {@link #finish()} has waited for the readers so far; the tests read it to see that a run's output is finished once, whatever the number of ways out of the run. */
    synchronized int waits() {
        return waits;
    }

    private void awaitEnd() {
        waits++;
        CompletableFuture.allOf(stdout.ended, stderr.ended).completeOnTimeout(null, drainGrace.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    Kept stdout() {
        return stdout.kept();
    }

    Kept stderr() {
        return stderr.kept();
    }

    private static final class Pump implements Runnable {
        private final InputStream source;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        /** Completes when the reader has left the stream and closed it (at the request of {@link #finish()}, or on an error, or at the end of the stream). */
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final Thread reader;
        private volatile Phase phase = Phase.READING;
        private boolean truncated;

        Pump(InputStream source) {
            this.source = source;
            this.reader = Thread.ofVirtual().name("run-output").unstarted(this);
        }

        void start() {
            reader.start();
        }

        /** Moves the reader to {@code to} (never back) and wakes it. */
        void advance(Phase to) {
            if (to.compareTo(phase) > 0) {
                phase = to;
            }
            LockSupport.unpark(reader);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[READ_SIZE];
            try (InputStream in = source) {
                boolean more = true;
                while (more) {
                    // The phase is read before the pipe is looked at: when the reader was told that the command ended, what the command wrote is in the pipe by now.
                    Phase seen = phase;
                    int ready = in.available();
                    if (ready > 0) {
                        int read = in.read(buffer, 0, Math.min(ready, buffer.length));
                        more = read >= 0 && accept(buffer, read);
                    } else if (seen == Phase.READING) {
                        LockSupport.parkNanos(POLL_NANOS);
                    } else {
                        more = false;
                    }
                }
            } catch (IOException unreadable) {
                LOG.debug("The output of a command run stopped being read.", unreadable);
            } finally {
                ended.complete(null);
            }
        }

        /** Keeps what fits under the limit and drops the rest; false once the stream was released. */
        private synchronized boolean accept(byte[] data, int length) {
            if (phase == Phase.RELEASED) {
                return false;
            }
            int room = LIMIT - kept.size();
            kept.write(data, 0, Math.min(room, length));
            if (length > room) {
                truncated = true;
            }
            return true;
        }

        synchronized boolean truncated() {
            return truncated;
        }

        synchronized Kept kept() {
            return new Kept(kept.toString(StandardCharsets.UTF_8), truncated);
        }
    }
}
