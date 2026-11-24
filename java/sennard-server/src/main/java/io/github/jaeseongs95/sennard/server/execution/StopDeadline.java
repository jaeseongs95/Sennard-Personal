// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * The one deadline of the stop of the server for its executions ({@code sennard.run.stop-deadline}). The stop is made of several waits in several components (the runner that ends the
 * programs and their output, the tracker that closes the records of the ones that ended); each of them waiting for a time of its own would add up, and the server's own stop command gives
 * up after a fixed time. So they share this: the first of them that begins to stop {@link #arm() arms} it, which fixes the moment the stop must be over, and every wait is for what is
 * {@link #remaining() left} of it (nothing, once it has passed). The start of the server {@link #reset() clears} it for the next stop.
 *
 * <p>It is a plain object that the components of the stop are given: the port that starts the programs ({@link ProcessRunner}) says nothing of it, and an implementation of the port
 * that needs no deadline of its own does not take one. The time is the monotonic one of the machine ({@link System#nanoTime()}), not a date.
 */
@Component
public class StopDeadline {
    private static final Duration LONGEST = Duration.ofDays(36_500);

    private final Duration total;
    /** The moment ({@link System#nanoTime()}) the stop must be over; null while no stop has begun. */
    private final AtomicReference<Long> moment = new AtomicReference<>();

    @Autowired
    public StopDeadline(SennardProperties settings) {
        this(settings.run().stopDeadline());
    }

    /** @param total how long a stop may take from the time it is armed */
    public StopDeadline(Duration total) {
        if (total.isNegative()) {
            throw new IllegalArgumentException("The deadline of a stop is not negative: " + total);
        }
        this.total = total;
    }

    /** Fixes the moment the stop must be over, unless another has: the first call after {@link #reset()} fixes it and the ones after it change nothing. */
    public void arm() {
        moment.compareAndSet(null, System.nanoTime() + saturated(total));
    }

    /** Forgets the moment: the next {@link #arm()} fixes a new one. */
    public void reset() {
        moment.set(null);
    }

    /** What is left until the stop must be over; the whole deadline while it has not been armed, and zero once it has passed. */
    public Duration remaining() {
        Long fixed = moment.get();
        if (fixed == null) {
            return total;
        }
        long left = fixed - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    /**
     * The time a wait that usually takes {@code usual} may take now: {@code usual} when no stop is going on (the waits of the executions that end on their own are not changed by a
     * deadline of the stop), and not more than what is left of the deadline when one is.
     */
    public Duration limit(Duration usual) {
        if (moment.get() == null) {
            return usual;
        }
        Duration left = remaining();
        return left.compareTo(usual) < 0 ? left : usual;
    }

    /** The nanoseconds of the duration; a deadline longer than a hundred years is a hundred years (a count of nanoseconds that does not fit in a long would otherwise be an error). */
    private static long saturated(Duration duration) {
        return duration.compareTo(LONGEST) > 0 ? LONGEST.toNanos() : duration.toNanos();
    }
}
