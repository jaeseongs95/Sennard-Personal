// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.server.execution.StreamOutput;

/**
 * The stdout and stderr of a command, read from its pipes: one virtual thread per stream reads every byte of it. Each byte goes to the stream's {@link LogFile} (up to the file's
 * limit) and the memory keeps the first {@value StreamOutput#HEAD_BYTES} bytes and the last {@value StreamOutput#TAIL_BYTES} (what is between is only in the file). A command that
 * writes more is never stopped for it and never blocked on a full pipe: the reader goes on reading, and what the file cannot take is dropped from the file and marked.
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
 * empty it releases them after the grace it was given. A reader that ends closes its stream (a process that still writes to it gets a broken pipe from the operating system) and its
 * log file, and {@link #finish()} returns after both readers ended: when it returns no thread of this object is left, both pipes and both files are closed, whatever the processes the
 * command left running do. What such a process writes in the grace is read like the rest.
 */
final class ProcessOutput {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessOutput.class);
    private static final int READ_SIZE = 8192;
    /** How long a reader parks when the pipe has nothing in it. Parking is a virtual thread waiting; it holds no resource, and {@link #finish()} wakes the reader. */
    static final long POLL_MILLIS = 5;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS);

    /**
     * What was kept in memory of one stream.
     *
     * @param head the first bytes (up to {@value StreamOutput#HEAD_BYTES})
     * @param tail the last bytes that did not fit in {@code head} (up to {@value StreamOutput#TAIL_BYTES}); empty when everything fit in {@code head} and {@code tail} together
     * @param total how many bytes were read from the pipe
     * @param logTruncated whether the stream's log file did not take all of them
     */
    record Kept(byte[] head, byte[] tail, long total, boolean logTruncated) {
        /** How many bytes are in neither {@code head} nor {@code tail}. */
        long omitted() {
            return total - head.length - tail.length;
        }
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
     * releases the readers. The files are the readers' from now on: they close them.
     */
    ProcessOutput(Process process, Duration drainGrace, LogFile stdoutLog, LogFile stderrLog) {
        this(process.getInputStream(), process.getErrorStream(), drainGrace, stdoutLog, stderrLog);
    }

    /** Starts reading both streams. */
    ProcessOutput(InputStream stdout, InputStream stderr, Duration drainGrace, LogFile stdoutLog, LogFile stderrLog) {
        this.stdout = new Pump(stdout, stdoutLog);
        this.stderr = new Pump(stderr, stderrLog);
        this.drainGrace = drainGrace;
        this.stdout.start();
        this.stderr.start();
    }

    /**
     * Ends the readers and closes both streams and files, as the class description says, and returns when both readers ended (a reader that did not end within the grace after the
     * release is logged and not waited for any longer). The first call does this; a call after it returns at once, so a run that has several ways out can call it on every one of
     * them and wait only once.
     */
    synchronized ProcessOutput finish() {
        return finish(UnaryOperator.identity());
    }

    /**
     * As {@link #finish()}, and each wait is for the grace taken through {@code cap}: the stop of the server gives what is left of its deadline here, so that the waits do not go
     * past it. A reader that was not given the time to end is released all the same, and is not waited for.
     */
    synchronized ProcessOutput finish(UnaryOperator<Duration> cap) {
        if (finished) {
            return this;
        }
        finished = true;
        stdout.advance(Phase.DRAINING);
        stderr.advance(Phase.DRAINING);
        awaitEnd(cap.apply(drainGrace));
        stdout.advance(Phase.RELEASED);
        stderr.advance(Phase.RELEASED);
        Duration waitedAfterRelease = cap.apply(drainGrace);
        awaitEnd(waitedAfterRelease);
        if (!stdout.ended.isDone() || !stderr.ended.isDone()) {
            // the time that was waited, which the stop of the server may have cut short of the grace
            LOG.warn("A reader of the output of a command did not end within {} after it was released.", waitedAfterRelease);
        }
        return this;
    }

    /** How many times {@link #finish()} has waited for the readers so far; the tests read it to see that a run's output is finished once, whatever the number of ways out of the run. */
    synchronized int waits() {
        return waits;
    }

    private void awaitEnd(Duration grace) {
        waits++;
        if (grace.isZero()) {
            return;
        }
        CompletableFuture.allOf(stdout.ended, stderr.ended).completeOnTimeout(null, grace.toNanos(), TimeUnit.NANOSECONDS).join();
    }

    /** What has been read of the standard output so far. */
    Kept stdout() {
        return stdout.kept();
    }

    /** What has been read of the standard error so far. */
    Kept stderr() {
        return stderr.kept();
    }

    private static final class Pump implements Runnable {
        private final InputStream source;
        private final LogFile log;
        /** Completes when the reader has left the stream and closed it (at the request of {@link #finish()}, or on an error, or at the end of the stream). */
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final Thread reader;
        private volatile Phase phase = Phase.READING;
        private final byte[] head = new byte[StreamOutput.HEAD_BYTES];
        private int headLength;
        /** The last bytes after the head, a ring of {@value StreamOutput#TAIL_BYTES}; made when the first byte past the head is read. */
        private byte[] ring;
        private int ringLength;
        /** Where the next byte goes in the ring. */
        private int ringPosition;
        private long total;

        Pump(InputStream source, LogFile log) {
            this.source = source;
            this.log = log;
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
            try (InputStream in = source; log) {
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

        /** Keeps the bytes in memory and in the file; false once the stream was released. */
        private synchronized boolean accept(byte[] data, int length) {
            if (phase == Phase.RELEASED) {
                return false;
            }
            total += length;
            int intoHead = Math.min(head.length - headLength, length);
            System.arraycopy(data, 0, head, headLength, intoHead);
            headLength += intoHead;
            if (length > intoHead) {
                keepTail(data, intoHead, length - intoHead);
            }
            log.write(data, length);
            return true;
        }

        /** Puts {@code length} bytes of {@code data} from {@code offset} at the end of the ring, over the oldest bytes when it is full. */
        private void keepTail(byte[] data, int offset, int length) {
            if (ring == null) {
                ring = new byte[StreamOutput.TAIL_BYTES];
            }
            int capacity = ring.length;
            if (length >= capacity) {
                System.arraycopy(data, offset + length - capacity, ring, 0, capacity);
                ringPosition = 0;
                ringLength = capacity;
                return;
            }
            int first = Math.min(length, capacity - ringPosition);
            System.arraycopy(data, offset, ring, ringPosition, first);
            System.arraycopy(data, offset + first, ring, 0, length - first);
            ringPosition = (ringPosition + length) % capacity;
            ringLength = Math.min(capacity, ringLength + length);
        }

        synchronized Kept kept() {
            byte[] tail;
            if (ringLength == 0) {
                tail = new byte[0];
            } else if (ringLength < ring.length) {
                tail = Arrays.copyOf(ring, ringLength);
            } else {
                tail = new byte[ring.length];
                int older = ring.length - ringPosition;
                System.arraycopy(ring, ringPosition, tail, 0, older);
                System.arraycopy(ring, 0, tail, older, ringPosition);
            }
            return new Kept(Arrays.copyOf(head, headLength), tail, total, log.truncated());
        }
    }
}
