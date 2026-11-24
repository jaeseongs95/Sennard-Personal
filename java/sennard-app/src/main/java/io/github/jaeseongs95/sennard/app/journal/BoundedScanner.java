// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.journal.JournalScanner.FailureReason;

/**
 * One walk of a folder (resolved to its real path, no link followed, no file read: the metadata only) that stops where its bounds say. It is the look an execution's working folder
 * gets before and after the program ran: the folder is the caller's and its size is not known, so the walk costs at most the bounds, in time and in memory.
 *
 * <p>The walk keeps nothing of what it sees: it hands every entry to a {@link LookSink} the moment it comes to it, and the sink decides what is worth keeping. The look before keeps all
 * of it, and the look after compares what it sees with that look as it goes, and keeps the differences only ({@code WorkFolderObservation}). Every path it hands over is a text relative to
 * the folder, with names joined by {@code /} (the folder itself is {@code .}), so the folder's own path is not repeated for every entry, and a file is its size and the time it was changed
 * to the millisecond. The change journal's walk ({@link JournalScanner}) is another one, with other needs (it hashes, and keeps what failed and why), and is not changed by this.
 */
@Component
public class BoundedScanner {
    /** Why a bounded walk stopped before it had seen everything. */
    public enum StopReason {
        /** The walk looked at as many entries as one walk may. */
        ENTRY_LIMIT,
        /** The walk took as long as one walk may. */
        TIME_LIMIT,
        /** The sink took no more: the entries that all the walks of the process may keep together were used up ({@link LookSink}). */
        TOTAL_ENTRY_LIMIT
    }

    /**
     * Where a walk hands what it sees. Each method of an entry answers whether the sink took it: false when the sink may keep no more (the entries that the walks of the whole process may
     * hold together are used up), and the walk stops there with {@link StopReason#TOTAL_ENTRY_LIMIT}; the entry it was refused is not counted as looked at. The methods are called by the
     * walking thread, in the order the walk comes to the entries (which is the order of the file system's listing, not a fixed one).
     */
    public interface LookSink {
        /** A regular file. */
        boolean file(String path, FileStamp stamp);

        /** A path that could not be taken as it is: a link or special file that was skipped ({@code LINK_OR_SPECIAL_SKIPPED}), or something that could not be read (any other reason). */
        boolean failure(String path, FailureReason reason);

        /** A folder the walk goes into. The walk may stop inside of it, and then it is the last word the sink hears of it: it exists. */
        boolean directoryEntered(String path);

        /** A folder that was entered, and whose listing failed halfway; its children before the failure were handed over, the ones after are not known. It is no longer a folder to the sink, it is a failure. */
        boolean directoryFailed(String path, FailureReason reason);

        /** A folder that was told not to be entered ({@link Bounds#excluded()}). It counts no entry. */
        void notWalked(String path);
    }

    /**
     * The bounds of one walk.
     *
     * @param maxEntries how many entries (folders, files, links) the walk may look at; the one after them stops it
     * @param maxDuration how long the walk may take; it is checked at every entry, so a single call of the file system that does not return is not cut short
     * @param excluded real paths of folders the walk does not enter (they are handed to {@link LookSink#notWalked}, and count no entry)
     */
    public record Bounds(long maxEntries, Duration maxDuration, Set<Path> excluded) {
        public Bounds {
            if (maxEntries < 0 || maxDuration.isNegative()) {
                throw new IllegalArgumentException("A bound of a walk is not negative: " + maxEntries + " entries, " + maxDuration);
            }
            excluded = Set.copyOf(excluded);
        }
    }

    /**
     * What a walk found of a file.
     *
     * @param size the size in bytes
     * @param modifiedMillis the time it was last changed, in milliseconds since the epoch
     */
    public record FileStamp(long size, long modifiedMillis) {
    }

    /**
     * How a walk ended. {@code stoppedBy} is null when it saw the whole folder (but the not walked ones); otherwise it saw the first {@code entries} entries it came to.
     *
     * @param stoppedBy why the walk stopped early; null when it did not
     * @param entries how many entries it looked at
     */
    public record Walked(StopReason stoppedBy, long entries) {
        /** Whether the walk saw the whole folder. */
        public boolean complete() {
            return stoppedBy == null;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoundedScanner.class);
    private static final Duration LONGEST = Duration.ofDays(36_500);
    /** The name of the folder itself in the paths a walk hands over. */
    public static final String ROOT = ".";
    /** What joins the names of a path a walk hands over, whatever the file system joins them with. */
    private static final char SEPARATOR = '/';

    /**
     * The folder above a path as a walk writes it: the folder itself ({@link #ROOT}) for a name at the top, and null for the folder itself, which has none. The names are taken whole, so the
     * folder above {@code D2/x} is {@code D2}, and the chain of the folders above a path never goes through a sibling whose name begins like one of them.
     */
    public static String parent(String path) {
        if (ROOT.equals(path)) {
            return null;
        }
        int separator = path.lastIndexOf(SEPARATOR);
        return separator < 0 ? ROOT : path.substring(0, separator);
    }

    /**
     * Walks {@code root} (made absolute and normalized, then resolved to its real path, so a folder that is itself a link, or lies under one, is walked) as far as {@code bounds} say, and
     * hands what it sees to {@code sink}. A link below it is never followed: it is handed over as skipped. A root that cannot be resolved is one failure on the root, never a skipped link.
     */
    public Walked walk(Path root, Bounds bounds, LookSink sink) {
        Walk walk = new Walk(bounds, sink);
        try {
            Path real = root.toAbsolutePath().normalize().toRealPath();
            walk.start(real);
            Files.walkFileTree(real, walk);
        } catch (IOException failure) {
            walk.rootFailed(failure);
        }
        return walk.end();
    }

    /** The reason a read that failed is told with: what the file system said, as one of the reasons of the journal's walk. The exception, with its stack, is not kept for every path that failed. */
    private static FailureReason reasonOf(IOException cause) {
        FailureReason reason = switch (cause) {
            case NoSuchFileException ignored -> FailureReason.NOT_FOUND;
            case AccessDeniedException ignored -> FailureReason.ACCESS_DENIED;
            default -> FailureReason.IO_ERROR;
        };
        LOG.warn("Bounded scan failed: {} ({})", reason, cause.getClass().getName());
        return reason;
    }

    /** One walk: the visitor, what has been left of its bounds, and where it stands. Read and changed by the walking thread only. */
    private static final class Walk extends SimpleFileVisitor<Path> {
        private final long maxEntries;
        private final long limitNanos;
        private final long startedNanos = System.nanoTime();
        private final Set<Path> excluded;
        private final LookSink sink;
        private Path root;
        private long entries;
        private StopReason stoppedBy;

        Walk(Bounds bounds, LookSink sink) {
            this.maxEntries = bounds.maxEntries();
            // a bound longer than a hundred years is a hundred years: a count of nanoseconds that does not fit in a long would otherwise be an error
            this.limitNanos = bounds.maxDuration().compareTo(LONGEST) > 0 ? LONGEST.toNanos() : bounds.maxDuration().toNanos();
            this.excluded = bounds.excluded();
            this.sink = sink;
        }

        void start(Path realRoot) {
            this.root = realRoot;
        }

        Walked end() {
            return new Walked(stoppedBy, entries);
        }

        /** Whether the walk may look at one more entry; when it may not, why is kept. The sink is asked after this, so that it is not asked about an entry that is not looked at. */
        private boolean admit() {
            if (entries >= maxEntries) {
                stoppedBy = StopReason.ENTRY_LIMIT;
                return false;
            }
            if (System.nanoTime() - startedNanos >= limitNanos) {
                stoppedBy = StopReason.TIME_LIMIT;
                return false;
            }
            return true;
        }

        /** An entry the sink took is looked at: counted, and the walk goes on as {@code next} says. One it did not take stops the walk. */
        private FileVisitResult looked(boolean taken, FileVisitResult next) {
            if (!taken) {
                stoppedBy = StopReason.TOTAL_ENTRY_LIMIT;
                return FileVisitResult.TERMINATE;
            }
            entries++;
            return next;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (excluded.contains(directory)) {
                sink.notWalked(name(directory));
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!admit()) {
                return FileVisitResult.TERMINATE;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return looked(sink.failure(name(directory), skippedReason(directory)), FileVisitResult.SKIP_SUBTREE);
            }
            return looked(sink.directoryEntered(name(directory)), FileVisitResult.CONTINUE);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!admit()) {
                return FileVisitResult.TERMINATE;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return looked(sink.failure(name(file), skippedReason(file)), FileVisitResult.CONTINUE);
            }
            return looked(sink.file(name(file), new FileStamp(attributes.size(), attributes.lastModifiedTime().toInstant().toEpochMilli())), FileVisitResult.CONTINUE);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) {
            if (!admit()) {
                return FileVisitResult.TERMINATE;
            }
            return looked(sink.failure(name(file), reasonOf(failure)), FileVisitResult.CONTINUE);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
            if (failure == null) {
                return FileVisitResult.CONTINUE;
            }
            // the folder was counted when it was entered: it is the same entry, and what it is changes
            if (!sink.directoryFailed(name(directory), reasonOf(failure))) {
                stoppedBy = StopReason.TOTAL_ENTRY_LIMIT;
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        /** The root that cannot be resolved (or a walk that failed as a whole) is one failure on the root. */
        void rootFailed(IOException cause) {
            if (!sink.failure(ROOT, reasonOf(cause)) && stoppedBy == null) {
                stoppedBy = StopReason.TOTAL_ENTRY_LIMIT;
            }
        }

        /** The root itself can be a special file here (a link was resolved); below it a link or special file is skipped by design. */
        private FailureReason skippedReason(Path walked) {
            return walked.equals(root) ? FailureReason.NOT_A_REGULAR_FILE : FailureReason.LINK_OR_SPECIAL_SKIPPED;
        }

        /** The path under the root as names joined by {@code /}; the root itself is {@code .}. */
        private String name(Path walked) {
            Path under = root.relativize(walked);
            int count = under.getNameCount();
            if (count == 1 && under.getName(0).toString().isEmpty()) {
                return ROOT;
            }
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < count; index++) {
                if (index > 0) {
                    text.append(SEPARATOR);
                }
                text.append(under.getName(index));
            }
            return text.toString();
        }
    }
}
