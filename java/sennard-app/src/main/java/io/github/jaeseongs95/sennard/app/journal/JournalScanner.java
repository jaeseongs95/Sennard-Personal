// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks the caller's write roots (each resolved to its real path) without following any link below them. It is the journal's file I/O and nothing else: it reads no table and
 * judges nothing, it only reports what the files are. The caller runs it between a read-only use case and a write use case of {@link ChangeJournalService}, outside every
 * database transaction.
 */
@Component
public class JournalScanner {
    public record Metadata(long size, Instant modifiedAt, String sha256) {
    }

    public enum FailureReason { NOT_FOUND, ACCESS_DENIED, NOT_A_REGULAR_FILE, LINK_OR_SPECIAL_SKIPPED, CHANGED_DURING_SCAN, IO_ERROR }

    public record ReadFailure(Path path, FailureReason reason, IOException cause) {
        public ReadFailure(Path path, FailureReason reason) { this(path, reason, null); }
    }

    private static final Logger LOG = LoggerFactory.getLogger(JournalScanner.class);

    private ReadFailure readFailure(Path path, IOException cause) {
        FailureReason reason = switch (cause) {
            case NoSuchFileException ignored -> FailureReason.NOT_FOUND;
            case AccessDeniedException ignored -> FailureReason.ACCESS_DENIED;
            default -> FailureReason.IO_ERROR;
        };
        LOG.warn("Journal scan failed: {} ({})", reason, cause.getClass().getName());
        return new ReadFailure(path, reason, cause);
    }

    private static boolean isLinkOrSpecial(BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    public record Scan(Map<Path, Metadata> files, List<ReadFailure> failures, Set<Path> scannedDirectories) {
        public Scan {
            files = Map.copyOf(files);
            failures = List.copyOf(failures);
            scannedDirectories = Set.copyOf(scannedDirectories);
        }
    }

    /** The result of comparing the roots with the last observation: the walk, and which of the paths whose last read failed are gone now. */
    public record Rescan(Scan scan, Set<Path> absentFailedPaths) {
        public Rescan {
            absentFailedPaths = Set.copyOf(absentFailedPaths);
        }
    }

    private final FileDigest digest;

    public JournalScanner(FileDigest digest) {
        this.digest = digest;
    }

    /** The first walk of the write roots a baseline is captured from: the planned files are hashed, and a root that does not exist yet is a root with no files. */
    public Scan scanBaseline(ScanBasis basis) {
        return scanBaseline(basis.roots(), basis.plannedFiles());
    }

    /** The same walk for a run that does not exist yet (a start reads its baseline before the transaction that creates the run): only the roots and the planned files are needed. */
    public Scan scanBaseline(List<Path> roots, Set<Path> plannedFiles) {
        return scan(existing(roots), Map.of(), plannedFiles, true);
    }

    /**
     * The walk that compares the write roots with the last observation (metadata is compared before a file is hashed; a path whose last read failed is read again), and which of
     * the paths whose last read failed no longer exist.
     */
    public Rescan rescan(ScanBasis basis) {
        Scan scan = scan(existing(basis.roots()), basis.previous(), basis.plannedFiles(), false, false, basis.failedPaths());
        Set<Path> absent = basis.failedPaths().stream().filter(path -> Files.notExists(path, LinkOption.NOFOLLOW_LINKS)).collect(Collectors.toUnmodifiableSet());
        return new Rescan(scan, absent);
    }

    /** The read of one file an edit tool changed (it is read even when its metadata is unchanged). */
    public Scan scanHook(HookBasis basis) {
        return scanUnder(basis.root(), basis.file(), basis.previous());
    }

    /**
     * The roots that are there to be walked. A write root that does not exist yet (the directory or file the work is going to create) is a root with no files, not an
     * unreadable one: it stays a write root, and what is created under it later is seen by the rescan that finds it. A root that cannot be told to be absent is walked, so
     * that its read failure is reported.
     */
    private List<Path> existing(List<Path> roots) {
        return roots.stream().filter(root -> !Files.notExists(root, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    /** Existing metadata is compared before hashing; initial planned files are hashed. */
    public Scan scan(List<Path> roots, Map<Path, Metadata> previous, Set<Path> planned, boolean initial) {
        return scan(roots, previous, planned, initial, false);
    }

    /** A hook verifies that its target is readable even when its metadata is unchanged. */
    public Scan scan(List<Path> roots, Map<Path, Metadata> previous, Set<Path> planned, boolean initial, boolean verifyRead) {
        return scan(roots, previous, planned, initial, verifyRead, Set.of());
    }

    /**
     * Walks each declared root. A root is the path the caller names (a write root of the run): it is resolved to its real path first, so a root that is itself a link, or lies under
     * one, is scanned and not skipped; the files are still recorded under the spelling the caller used, which is the one the hooks and the comparison use. A link below the root is
     * never followed: it is skipped and reported.
     */
    public Scan scan(List<Path> roots, Map<Path, Metadata> previous, Set<Path> planned, boolean initial, boolean verifyRead, Set<Path> retryReads) {
        Reads reads = new Reads(previous, planned, initial, verifyRead, retryReads);
        Found found = new Found();
        for (Path requested : roots) {
            Path root = normalize(requested);
            walk(root, root, reads, found);
        }
        return found.scan();
    }

    /**
     * Reads one file or directory {@code target} that lies under a declared {@code root}, for a hook. The root is resolved as in {@link #scan}; every element from the root down
     * to the target is below the root, so a link among them is skipped and reported, not followed.
     */
    public Scan scanUnder(Path root, Path target, Map<Path, Metadata> previous) {
        Path declared = normalize(root);
        Path wanted = normalize(target);
        if (!wanted.startsWith(declared)) {
            throw new IllegalArgumentException("The target is not under the root");
        }
        Found found = new Found();
        walk(declared, wanted, new Reads(previous, Set.of(), false, true, Set.of()), found);
        return found.scan();
    }

    private record Reads(Map<Path, Metadata> previous, Set<Path> planned, boolean initial, boolean verifyRead, Set<Path> retryReads) {
    }

    /** What the walks of one scan have found so far. */
    private static final class Found {
        private final Map<Path, Metadata> files = new LinkedHashMap<>();
        private final List<ReadFailure> failures = new ArrayList<>();
        private final Set<Path> scannedDirectories = new HashSet<>();

        Scan scan() {
            return new Scan(files, failures, scannedDirectories);
        }
    }

    /**
     * One declared root and the {@code target} in it to walk (the root itself, or something below it). A root that cannot be resolved is one failure on the root: it is never
     * a skipped link, because a skipped link is not reported as unreadable and the scan would pass with no file read.
     */
    private void walk(Path declared, Path target, Reads reads, Found found) {
        Path realRoot;
        try {
            realRoot = declared.toRealPath();
        } catch (IOException failure) {
            found.failures.add(readFailure(declared, failure));
            return;
        }
        Path start = realRoot;
        try {
            if (!target.equals(declared)) {
                for (Path part : declared.relativize(target)) {
                    start = start.resolve(part);
                    BasicFileAttributes attributes = Files.readAttributes(start, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (isLinkOrSpecial(attributes)) {
                        found.failures.add(new ReadFailure(spelled(declared, realRoot, start), FailureReason.LINK_OR_SPECIAL_SKIPPED));
                        return;
                    }
                }
            }
        } catch (IOException failure) {
            found.failures.add(readFailure(target, failure));
            return;
        }
        Path walkedStart = start;
        // Only the root itself can be a special file here (a link was resolved); below it a link or special file is skipped by design.
        Path rootItself = target.equals(declared) ? realRoot : null;
        try {
            Files.walkFileTree(walkedStart, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (reads.verifyRead() && directory.equals(walkedStart)) {
                        found.failures.add(new ReadFailure(target, FailureReason.NOT_A_REGULAR_FILE));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (isLinkOrSpecial(attributes)) {
                        found.failures.add(skipped(directory));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path path = spelled(declared, realRoot, file);
                    if (isLinkOrSpecial(attributes)) {
                        found.failures.add(skipped(file));
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attributes.isRegularFile() || found.files.containsKey(path)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Instant modified = attributes.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS);
                    Metadata before = reads.previous().get(path);
                    boolean changed = before == null || before.size() != attributes.size() || !before.modifiedAt().equals(modified);
                    try {
                        String hash = (changed && (!reads.initial() || reads.planned().contains(path))) || reads.verifyRead() || reads.retryReads().contains(path) || (!reads.initial() && before != null && before.sha256() == null)
                                ? digest.sha256(file) : before == null ? null : before.sha256();
                        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!after.isRegularFile() || after.size() != attributes.size() || !after.lastModifiedTime().equals(attributes.lastModifiedTime())) {
                            found.failures.add(new ReadFailure(path, FailureReason.CHANGED_DURING_SCAN));
                        } else {
                            found.files.put(path, new Metadata(attributes.size(), modified, hash));
                        }
                    } catch (IOException failure) {
                        found.failures.add(readFailure(path, failure));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    // A directory may fail to open before preVisitDirectory receives its attributes.
                    if (!file.equals(rootItself)) {
                        try {
                            if (isLinkOrSpecial(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
                                found.failures.add(skipped(file));
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (IOException probeFailure) {
                            LOG.debug("Journal could not classify the entry as a link or special file", probeFailure);
                        }
                    }
                    found.failures.add(readFailure(spelled(declared, realRoot, file), failure));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    Path path = spelled(declared, realRoot, directory);
                    if (failure != null) found.failures.add(readFailure(path, failure));
                    else found.scannedDirectories.add(path);
                    return FileVisitResult.CONTINUE;
                }

                private ReadFailure skipped(Path walked) {
                    boolean root = walked.equals(rootItself);
                    return new ReadFailure(spelled(declared, realRoot, walked), root ? FailureReason.NOT_A_REGULAR_FILE : FailureReason.LINK_OR_SPECIAL_SKIPPED);
                }
            });
        } catch (IOException failure) {
            found.failures.add(readFailure(target, failure));
        }
    }

    /** The path as the caller spells it: the walked path under the real root, put back under the declared root. */
    private static Path spelled(Path declared, Path realRoot, Path walked) {
        return declared.resolve(realRoot.relativize(walked));
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
