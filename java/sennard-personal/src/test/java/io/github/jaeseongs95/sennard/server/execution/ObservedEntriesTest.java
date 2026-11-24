// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * The entries that all the observations of the server hold together, on the real counter and real threads (no mock, no pause of a chosen length): an observation takes one entry for
 * each entry it looks at, never more than the total are taken, and what a lease took is given back once, exactly, whichever way and whenever the lease is ended.
 *
 * <pre>
 * e1  borrow   a lease takes one entry at a time from the total, and none when none is left
 * e2  give     closing a lease gives back exactly what it took; a second close gives nothing; the leases of other observations are not touched
 * e3  closed   a lease that is closed takes no more, and an entry that was being taken when it closed is given back (it is in no count)
 * e4  at once  many leases on many threads that take and close together never hold more than the total, and when all have ended every entry is free
 * e5  the total is at least one
 * </pre>
 */
class ObservedEntriesTest {
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    @Test
    void aLeaseTakesOneEntryAtATimeAndNoneWhenNoneIsLeft() {
        ObservedEntries entries = new ObservedEntries(3);
        ObservedEntries.Lease lease = entries.lease();

        assertEquals(3, entries.free());
        assertTrue(lease.take());
        assertTrue(lease.take());
        assertEquals(1, entries.free());
        assertEquals(2, lease.held());
        assertTrue(lease.take());
        assertFalse(lease.take(), "the total is three");
        assertEquals(0, entries.free());
        assertEquals(3, lease.held(), "a refused entry is not held");
        assertEquals(3, entries.total());
    }

    @Test
    void closingALeaseGivesBackExactlyWhatItTookOnceAndTouchesNoOtherLease() {
        ObservedEntries entries = new ObservedEntries(10);
        ObservedEntries.Lease one = entries.lease();
        ObservedEntries.Lease other = entries.lease();
        for (int count = 0; count < 4; count++) {
            assertTrue(one.take());
        }
        for (int count = 0; count < 3; count++) {
            assertTrue(other.take());
        }
        assertEquals(3, entries.free());

        one.close();
        assertEquals(7, entries.free(), "the four of the first are back, the three of the other are not");
        one.close();
        assertEquals(7, entries.free(), "a second close gives nothing back");
        assertEquals(3, other.held());
        other.close();
        assertEquals(10, entries.free());
    }

    @Test
    void aLeaseThatIsClosedTakesNoMoreAndLeaksNothing() {
        ObservedEntries entries = new ObservedEntries(5);
        ObservedEntries.Lease lease = entries.lease();
        assertTrue(lease.take());
        lease.close();

        assertFalse(lease.take(), "a closed lease takes nothing");
        assertEquals(0, lease.held());
        assertEquals(5, entries.free(), "and the entry that was asked for after the close is in no count: nothing is lost");

        ObservedEntries.Lease idle = entries.lease();
        idle.close();
        assertEquals(5, entries.free(), "a lease that took nothing gives nothing back");
    }

    @Test
    void manyLeasesOnManyThreadsNeverHoldMoreThanTheTotalAndEveryEntryIsFreeWhenAllHaveEnded() throws Exception {
        long total = 1_000;
        int threads = 16;
        ObservedEntries entries = new ObservedEntries(total);
        AtomicLong inUse = new AtomicLong();
        AtomicLong mostInUse = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> started = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int number = 0; number < threads; number++) {
            started.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int round = 0; round < 200; round++) {
                        ObservedEntries.Lease lease = entries.lease();
                        long mine = 0;
                        // each round asks for more than the thread's share, so the total is met and refused entries happen
                        for (int ask = 0; ask < 150; ask++) {
                            if (lease.take()) {
                                mine++;
                                long now = inUse.incrementAndGet();
                                mostInUse.accumulateAndGet(now, Math::max);
                            }
                            long free = entries.free();
                            if (free < 0 || free > total) {
                                throw new AssertionError("the entries that are free are " + free + " of " + total + ": more were taken than there are, or more given back than were taken");
                            }
                        }
                        if (lease.held() != mine) {
                            throw new AssertionError("the lease holds " + lease.held() + " and took " + mine);
                        }
                        inUse.addAndGet(-mine);
                        lease.close();
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }
        go.countDown();
        assertTimeoutPreemptively(DEADLINE, () -> {
            for (Thread thread : started) {
                thread.join();
            }
        });

        assertEquals(List.of(), failures);
        assertTrue(mostInUse.get() <= total, "never more than the total was held at once: " + mostInUse.get());
        assertEquals(total, entries.free(), "every lease ended: every entry is free, none was lost and none was given back twice");
    }

    @Test
    void aLeaseAndItsCloseOnAnotherThreadLeakNothing() throws Exception {
        ObservedEntries entries = new ObservedEntries(1_000_000);
        for (int round = 0; round < 200; round++) {
            ObservedEntries.Lease lease = entries.lease();
            CountDownLatch go = new CountDownLatch(1);
            Thread taking = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int ask = 0; ask < 2_000; ask++) {
                    lease.take();
                }
            });
            Thread closing = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                lease.close();
            });
            go.countDown();
            assertTimeoutPreemptively(DEADLINE, () -> {
                taking.join();
                closing.join();
            });
            lease.close();
            assertEquals(1_000_000, entries.free(), "round " + round + ": the lease was closed while it was taking, and every entry is back");
        }
    }

    @Test
    void theTotalIsAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new ObservedEntries(0));
        assertThrows(IllegalArgumentException.class, () -> new ObservedEntries(-5));
    }
}
