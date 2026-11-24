// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * How many entries of working folders the whole server holds in memory at once, in all the observations that are going on ({@code sennard.run.observation-max-total-entries}). The look
 * before an execution is kept until the program has ended (and a development server does not end), so the observations of many executions in large folders could together use more
 * memory than the server has; the bound of one look ({@code sennard.run.observation-max-entries}) does not prevent that. This is the bound of all of them.
 *
 * <p>An observation takes a {@link Lease}. The look before takes an entry from it for every entry it keeps, and the look after takes one only for what it keeps of its own: a change (a
 * path that is new, a file that is modified, a path it could not read). What it finds as it was is compared with the look before as it is met and is only marked there, so an observation
 * leases as many entries as its folder has, and one more for each change; what the look before leased for an entry that the look after met stays leased until the lease ends, so the lease
 * is an upper bound of the entries kept: at least as many as are kept. A look that finds none left stops where it is and says so ({@code TOTAL_ENTRY_LIMIT}): the execution is not refused and
 * nothing waits for another observation to let go. The lease gives back what it took when the observation ends, whichever way it ends; the result of the observation, which is handed to
 * the caller as it ends, is not held by the lease any more (it is bounded by the entries of the two looks, see {@link WorkFolderObservation#finish()}). The count is one
 * {@link AtomicLong}: no lock is held, and a look takes an entry with one compare-and-set.
 */
@Component
public class ObservedEntries {
    private final long total;
    private final AtomicLong free;

    @Autowired
    public ObservedEntries(SennardProperties settings) {
        this(settings.run().observationMaxTotalEntries());
    }

    /** @param total how many entries all the observations may hold together */
    public ObservedEntries(long total) {
        if (total < 1) {
            throw new IllegalArgumentException("The entries the observations may hold are at least one: " + total);
        }
        this.total = total;
        this.free = new AtomicLong(total);
    }

    /** How many entries all the observations may hold together. */
    public long total() {
        return total;
    }

    /** How many entries are not held by any observation now. */
    public long free() {
        return free.get();
    }

    /** A lease for one observation; it holds nothing until a walk takes an entry. */
    public Lease lease() {
        return new Lease();
    }

    /**
     * The entries one observation holds. {@link #take()} is called by the walking thread, {@link #close()} by whoever ends the observation, after the walks; they may meet, and what was
     * taken is given back exactly once.
     */
    public final class Lease implements AutoCloseable {
        private static final long CLOSED = -1;
        private final AtomicLong held = new AtomicLong();

        private Lease() {
        }

        /** Takes one entry from the server's; false when none is left, or when the lease has been closed. */
        public boolean take() {
            long now;
            do {
                now = free.get();
                if (now <= 0) {
                    return false;
                }
            } while (!free.compareAndSet(now, now - 1));
            // the entry is the lease's from here: counted in what it holds, or given back at once when the lease has been closed meanwhile (close() has already counted what it held)
            long mine;
            do {
                mine = held.get();
                if (mine == CLOSED) {
                    free.incrementAndGet();
                    return false;
                }
            } while (!held.compareAndSet(mine, mine + 1));
            return true;
        }

        /** How many entries the lease holds now; zero once it is closed. */
        public long held() {
            return Math.max(0, held.get());
        }

        /** Gives back what the lease holds, and takes no more. A second call does nothing. */
        @Override
        public void close() {
            long mine = held.getAndSet(CLOSED);
            if (mine > 0) {
                free.addAndGet(mine);
            }
        }
    }
}
