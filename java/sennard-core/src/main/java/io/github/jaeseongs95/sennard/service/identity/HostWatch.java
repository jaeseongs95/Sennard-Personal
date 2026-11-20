// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Whether one host process still lives, through the JDK's {@link ProcessHandle} alone: no child process, no PowerShell and
 * no read of the operating system's own files, so the same code runs on every OS the JVM does (JVMS 1.2: the JVM is "responsible for its hardware- and
 * operating system-independence").
 *
 * <p>The JDK says of a process id that "the time between when a process terminates and the process id is reused for a new process is unpredictable"
 * and advises: "When using ProcessHandles avoid assumptions about the liveness or identity of the underlying process". So this class never asks a
 * process id again. It takes one handle when it binds, and that handle's {@link ProcessHandle#onExit()} is the signal that the host ended: the future is
 * "completed regardless of the exit status" of the process that handle was made for. What is checked at the one moment of binding is the host's start time
 * ({@link ProcessHandle.Info#startInstant()}) against the time the hook reported, which refuses a process id that a later process already took.
 *
 * <p>ceiling: the JDK keeps one small reaper thread per watched process until that process ends. Threads are not processes;
 * batch waiting is the path if thousands of sessions per PC ever exist. The behaviour for a host that is a zombie
 * (ended but not yet reaped by its parent) is the JDK's and is measured on a real Linux host.
 */
public final class HostWatch {
    public static final String PROCESS_ENDED = "HOST_PROCESS_ENDED";
    public static final String IDENTITY_CHANGED = "HOST_IDENTITY_CHANGED";
    /** The platform reports a start time to a precision of up to a second, so a host that started at the same moment as the hook is not refused for it. */
    private static final long START_TOLERANCE_MS = 2_000;

    /** Binding failed with a fixed reason. */
    public static final class BindFailed extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        BindFailed(String reason) {
            super(reason);
            this.reason = reason;
        }

        BindFailed(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private final String startToken;
    private final CompletableFuture<ProcessHandle> exit;

    private HostWatch(String startToken, CompletableFuture<ProcessHandle> exit) {
        this.startToken = startToken;
        this.exit = exit;
    }

    /**
     * Binds the process with this PID, which must have been born by {@code aliveAtMs}: a later birth took the PID after
     * the host ended. {@code onExit} only wakes the caller; the caller reads {@link #endReason()}.
     */
    public static HostWatch bind(long pid, long aliveAtMs, Runnable onExit) throws BindFailed {
        Optional<ProcessHandle> found;
        try {
            found = ProcessHandle.of(pid);
        } catch (SecurityException cause) {
            throw new BindFailed("HOST_WATCH_DENIED", cause);
        }
        ProcessHandle handle = found.orElseThrow(() -> new BindFailed("HOST_PROCESS_GONE"));
        Optional<Instant> born = handle.info().startInstant();
        if (born.isEmpty()) {
            // The JDK gives no start time of a process it may not inspect; a process that left between the lookup and here gives none either.
            throw new BindFailed(handle.isAlive() ? "HOST_WATCH_DENIED" : "HOST_PROCESS_GONE");
        }
        if (born.get().toEpochMilli() > aliveAtMs + START_TOLERANCE_MS) {
            throw new BindFailed("HOST_BORN_AFTER_BOUND");
        }
        CompletableFuture<ProcessHandle> exit = handle.onExit();
        exit.thenRun(onExit);
        return new HostWatch(born.get().toString(), exit);
    }

    /** The token the service stores as this birth's host start: the process's start time, ISO-8601 in UTC. */
    public String startToken() {
        return startToken;
    }

    /** The reason the host is gone, or empty while it lives: the bound handle's exit future is the only source. */
    public Optional<String> endReason() {
        return exit.isDone() && !exit.isCancelled() ? Optional.of(PROCESS_ENDED) : Optional.empty();
    }

    public void close() {
        exit.cancel(false);
    }
}
