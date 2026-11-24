// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.execution.ExecutionIds;
import io.github.jaeseongs95.sennard.server.execution.ExecutionLogStore;

/**
 * The log files that {@link LocalProcessRunner} made, in the folder {@code executions/<execution id>/} of the state folder: which folders there are, how much one folder takes and when
 * it was last written, and deleting it.
 *
 * <p>A folder is this server's only when it is where the runner made it. What is in the place of the folder is read first, without following a link: a link of any kind there (a symbolic
 * link, also one that points nowhere or round in a circle, or a junction) or anything that is not a folder is not this server's, for sure. Then it is decided by where the folder really
 * is, whatever kind of link may lie on the way: the real path
 * of the folder ({@link Path#toRealPath}) must be a folder directly in the real path of the log folder, under the same name, and the name must be written as the id of an execution
 * ({@link ExecutionIds}). A symbolic link, a Windows junction or any other link in the place of the folder resolves to somewhere else and is not it; so is a folder that is not named
 * as an execution id (this server did not make it), a file that has the name of an execution id, and a link that points nowhere. One about which the file system did not answer (an entry
 * or a real path cannot be read: nothing is taken for "nothing there") is not "not this server's": {@link #ownershipOf} says {@link Ownership.Undecided} with the reason, nothing is listed, measured or deleted for it
 * now, and a later look decides. The same answer is given on every system: nothing here asks which system it is. A location from a record is used only when it is such a folder: a record
 * is data and is not trusted to name a path to delete.
 *
 * <p>Inside a folder nothing is followed: only regular files are counted, and what is not a folder's file but a link is deleted as the link and never through it. Deleting is
 * repeatable: what is gone is not an error, and when something cannot be deleted the rest still is, and the failure is thrown after.
 */
@Component
public class LocalExecutionLogs implements ExecutionLogStore {
    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutionLogs.class);
    private final Path root;

    public LocalExecutionLogs(PersonalProperties personal) {
        this.root = personal.stateDir().resolve(LocalProcessRunner.LOG_FOLDER).toAbsolutePath().normalize();
    }

    @Override
    public List<Folder> folders() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Path realRoot = root.toRealPath();
        List<Folder> found = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                try {
                    if (folderOf(child, realRoot).isPresent()) {
                        found.add(new Folder(child.getFileName().toString(), child.toString()));
                    }
                } catch (IOException undecided) {
                    // one entry that cannot be judged now is not listed in this pass; it does not hide the others
                    LOG.warn("Whether {} is a log folder of this server cannot be told now ({}); it is not listed in this pass.", child, undecided.toString());
                }
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Ownership ownershipOf(String logLocation) {
        try {
            return folderOf(Path.of(logLocation).toAbsolutePath().normalize()).isPresent() ? new Ownership.Ours() : new Ownership.NotOurs();
        } catch (InvalidPathException notAPath) {
            LOG.debug("Not a path, so not a log folder of this server: {}", logLocation, notAPath);
            return new Ownership.NotOurs();
        } catch (IOException undecided) {
            return new Ownership.Undecided(undecided);
        }
    }

    @Override
    public long sizeOf(String logLocation) throws IOException {
        Path folder = own(logLocation);
        long bytes = 0;
        for (Path file : filesOf(folder)) {
            bytes += regularFileBytes(file);
        }
        return bytes;
    }

    @Override
    public Measure measure(String logLocation) throws IOException {
        Path folder = own(logLocation);
        FileTime newest = Files.getLastModifiedTime(folder, LinkOption.NOFOLLOW_LINKS);
        long bytes = 0;
        for (Path file : filesOf(folder)) {
            bytes += regularFileBytes(file);
            FileTime written = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
            if (written.compareTo(newest) > 0) {
                newest = written;
            }
        }
        return new Measure(bytes, newest.toInstant());
    }

    @Override
    public void delete(String logLocation) throws IOException {
        Path folder = own(logLocation);
        IOException failed = null;
        for (Path file : filesOf(folder)) {
            try {
                // deleteIfExists does not follow a link and does not go into a folder: a link in the folder is deleted as the link, what it points to is not touched
                Files.deleteIfExists(file);
            } catch (IOException notDeleted) {
                failed = remember(failed, notDeleted);
            }
        }
        try {
            Files.deleteIfExists(folder);
        } catch (IOException notDeleted) {
            failed = remember(failed, notDeleted);
        }
        if (failed != null) {
            throw failed;
        }
    }

    /** The real path of the folder at {@code logLocation} when it is a folder of this server; refused with the reason it is not (or that it cannot be told now: the IOException is that). */
    private Path own(String logLocation) throws IOException {
        try {
            return folderOf(Path.of(logLocation).toAbsolutePath().normalize())
                    .orElseThrow(() -> new IOException("Not a log folder of an execution of this server: " + logLocation));
        } catch (InvalidPathException notAPath) {
            throw new IOException("Not a log folder of an execution of this server: " + logLocation, notAPath);
        }
    }

    /**
     * As {@link #folderOf(Path, Path)}, with the real path of the log folder of this call: a log folder that is not there is its own path, and then nothing is in it.
     *
     * @throws IOException when a real path cannot be had (the answer is not known: it is not "not a folder of this server")
     */
    private Optional<Path> folderOf(Path folder) throws IOException {
        Path realRoot = attributesOf(root).isPresent() ? root.toRealPath() : root;
        return folderOf(folder, realRoot);
    }

    /**
     * What is at {@code path}, read without following a link: empty when nothing is there ({@link NoSuchFileException}, the only answer that means "nothing"; {@link Files#exists} would
     * also say false when the file system did not answer, and an unknown would be taken for nothing).
     *
     * @throws IOException when the file system did not answer: it is not known whether anything is there
     */
    private static Optional<BasicFileAttributes> attributesOf(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException nothing) {
            LOG.debug("Nothing is at {}.", path, nothing);
            return Optional.empty();
        }
    }

    /**
     * The place the folder really is, when that is a folder of this server: directly in {@code realRoot}, under the name it was asked by, the name is an execution id, and what is there
     * is a folder. Empty when it is not (a link elsewhere, a link that points nowhere, a file, another name). A folder that is not there is where it would be (there is nothing in it to
     * delete, and the record that names it is not out of reach).
     *
     * @throws IOException when a real path cannot be had for a reason that is not "nothing is there": the answer is not known now
     */
    private Optional<Path> folderOf(Path folder, Path realRoot) throws IOException {
        Path parent = folder.getParent();
        Path name = folder.getFileName();
        if (parent == null || name == null || !ExecutionIds.isExecutionId(name.toString())) {
            return Optional.empty();
        }
        Path expected = realRoot.resolve(name.toString());
        Path realParent = attributesOf(parent).isPresent() ? parent.toRealPath() : parent;
        if (!realParent.equals(realRoot)) {
            return Optional.empty();
        }
        Optional<BasicFileAttributes> there = attributesOf(folder);
        if (there.isEmpty()) {
            return Optional.of(expected);
        }
        // what is in the place of the folder is looked at itself, before anything is followed: a link of any kind (a symbolic link; a junction, which the JDK reports as another kind of
        // file) or anything that is not a folder is not this server's, whatever it points to, and nowhere or round in a circle is no reason to say "not known now"
        BasicFileAttributes attributes = there.get();
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            return Optional.empty();
        }
        Path real;
        try {
            real = folder.toRealPath();
        } catch (NoSuchFileException deleted) {
            // it was a folder just now and is gone: it is where it would be
            LOG.debug("The folder {} was deleted while its real path was asked.", folder, deleted);
            return Optional.of(expected);
        }
        return real.equals(expected) ? Optional.of(expected) : Optional.empty();
    }

    /** What is in the folder; nothing when the folder is gone. The passes that call this do not overlap, so a folder that goes between the check and the listing is a failure to report. */
    private static List<Path> filesOf(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }

    /** The bytes of a regular file; a link or a folder in the log folder takes nothing here, and is never followed to what it points to. */
    private static long regularFileBytes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isRegularFile() ? attributes.size() : 0;
    }

    private static IOException remember(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
