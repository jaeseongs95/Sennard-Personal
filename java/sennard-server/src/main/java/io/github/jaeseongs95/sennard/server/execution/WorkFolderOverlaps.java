// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

/**
 * The register of the observations that are going on: which executions are being watched for changes in which working folder right now. It holds nobody back: an execution whose
 * observation begins while another's is going on in the same folder, or in a folder above or below it, starts at once, and the two are written down as overlapping each other. What
 * either of them then reports as changed may be the other's work as well, and the report says so by naming the other execution ({@link WorkFolderChanges#overlappingExecutions()}).
 *
 * <p>Folders are told apart by their real path (a link, a different spelling of one folder, are the same folder), and two are related when they are the same or one is below the other.
 * The overlap is symmetric and complete: it holds for the whole of both observations, both for the one that was going on when the other began and for the one that began while the
 * other was going on. A registration lasts from {@link #register} to {@link Registration#release()}, which any thread may call, once; the register keeps nothing of an observation that
 * has ended, so it is empty when no observation is going on. The server is one process on one computer, so the register is in its memory.
 */
@Component
public class WorkFolderOverlaps {
    /** One observation that is going on; it is the register's to change ({@link #overlapping} is guarded by the register). */
    public final class Registration implements AutoCloseable {
        private final String executionId;
        private final Path folder;
        private final Set<String> overlapping = new TreeSet<>();
        private boolean released;

        private Registration(String executionId, Path folder) {
            this.executionId = executionId;
            this.folder = folder;
        }

        /** The execution that is observed. */
        public String executionId() {
            return executionId;
        }

        /** The real path of the folder that is observed. */
        public Path folder() {
            return folder;
        }

        /**
         * Ends the registration and answers the ids of the other executions whose observation overlapped this one, sorted. Calling it again answers the same and changes nothing.
         */
        public Set<String> release() {
            synchronized (WorkFolderOverlaps.this) {
                if (!released) {
                    released = true;
                    active.remove(this);
                }
                return Set.copyOf(overlapping);
            }
        }

        /** Ends the registration without asking who overlapped it; does nothing when it has ended. */
        @Override
        public void close() {
            release();
        }
    }

    /** The observations that are going on, in the order they began; guarded by this. */
    private final Set<Registration> active = new LinkedHashSet<>();

    /**
     * Registers the observation of {@code executionId} in {@code folder} and writes it down as overlapping each registered observation of the same folder or of one above or below it.
     *
     * @param executionId the execution that will run in the folder
     * @param folder an existing folder
     * @throws IOException when the folder has no real path (it does not exist, or cannot be read); nothing is registered
     * @throws IllegalArgumentException when the id is blank, or an observation of that execution is going on
     */
    public Registration register(String executionId, Path folder) throws IOException {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("An observation is of an execution: its id is not blank");
        }
        Path real = folder.toRealPath();
        synchronized (this) {
            for (Registration other : active) {
                if (other.executionId.equals(executionId)) {
                    throw new IllegalArgumentException("An observation of the execution " + executionId + " is going on");
                }
            }
            Registration mine = new Registration(executionId, real);
            for (Registration other : active) {
                if (related(real, other.folder)) {
                    mine.overlapping.add(other.executionId);
                    other.overlapping.add(executionId);
                }
            }
            active.add(mine);
            return mine;
        }
    }

    /** Whether one folder is the other or is below it, by real path. */
    private static boolean related(Path one, Path other) {
        return one.startsWith(other) || other.startsWith(one);
    }

    /** How many observations are going on now; the tests read it to see that nothing is left behind. */
    synchronized int registered() {
        return active.size();
    }
}
