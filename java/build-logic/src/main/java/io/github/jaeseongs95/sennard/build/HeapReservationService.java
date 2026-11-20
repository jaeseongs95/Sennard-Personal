// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.build;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFinishEvent;

/** Holds the build daemon's claim and each running Test's allocation until its task finishes. */
public abstract class HeapReservationService implements BuildService<HeapReservationService.Parameters>, OperationCompletionListener, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(HeapReservationService.class);

    public interface Parameters extends BuildServiceParameters {
        Property<String> getLedgerFolder();
        Property<Long> getLimitMiB();
        Property<Long> getMaxTestMiB();
        Property<Long> getMaxWaitMinutes();
        Property<String> getOwner();
    }

    private final HeapLedger.BuildReservation build;
    private final Map<String, HeapLedger.Reservation> tests = new HashMap<>();
    private boolean closed;

    @SuppressWarnings("this-escape")
    public HeapReservationService() throws InterruptedException {
        Parameters p = getParameters();
        long daemon = HeapBudget.currentJvmMiB();
        long maximum = Math.min(p.getLimitMiB().get(), Math.addExact(daemon, p.getMaxTestMiB().get()));
        HeapLedger ledger = new HeapLedger(Path.of(p.getLedgerFolder().get()), p.getLimitMiB().get(), Duration.ofMinutes(p.getMaxWaitMinutes().get()),
                Duration.ofSeconds(2), LOGGER::lifecycle);
        build = ledger.registerBuild(p.getOwner().get() + " pid " + ProcessHandle.current().pid(), daemon, maximum);
        LOGGER.lifecycle("Heap ledger: build daemon {} MiB, maximum claim {} MiB", daemon, maximum);
    }

    public void reserveTest(String path, long mib) throws InterruptedException {
        HeapLedger.Reservation reservation = build.reserveTest(mib);
        synchronized (this) {
            if (closed || tests.containsKey(path)) {
                reservation.close();
                throw new IllegalStateException("Cannot register heap reservation for " + path);
            }
            tests.put(path, reservation);
        }
        LOGGER.lifecycle("Heap ledger: {} reserves {} MiB", path, mib);
    }

    @Override
    public synchronized void onFinish(FinishEvent event) {
        if (event instanceof TaskFinishEvent task) {
            HeapLedger.Reservation reservation = tests.remove(task.getDescriptor().getTaskPath());
            if (reservation != null) {
                reservation.close();
                LOGGER.lifecycle("Heap ledger: {} released {} MiB", task.getDescriptor().getTaskPath(), reservation.mib());
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        tests.values().forEach(HeapLedger.Reservation::close);
        tests.clear();
        build.close();
    }
}
