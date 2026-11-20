// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A folder of the server's own, made for one check of a review bundle (AGS3-D-0264): the bundle's files are attachments, and the skills' checks read a bundle from a folder, so the
 * entry puts each attachment here at the path its file name says, runs the check, and removes the folder. The server never reads a path of the caller's computer: every byte in
 * this folder came out of an attachment. A file name that is not a safe relative path of the portable form the checks' contract fixes (read by {@code /}, no {@code ..}, no drive, no
 * backslash, no reserved name), a path used twice, or more bytes than a bundle may hold, refuses the check ({@link WorkflowFailure}, INVALID_INPUT).
 */
final class BundleFolder implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BundleFolder.class);
    /** The bytes a bundle's files may hold together: the bound the checks' own snapshot has. */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Pattern BACKSLASH_OR_COLON = Pattern.compile("[\\\\:]");
    private static final Pattern SLASH = Pattern.compile("/");
    private static final Pattern RESERVED_NAME = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);

    private final Path root;
    private final List<List<String>> used = new ArrayList<>();
    private long bytes;

    private BundleFolder(Path root) {
        this.root = root;
    }

    /** A new, empty folder of the server's own. */
    static BundleFolder open() {
        try {
            return new BundleFolder(Files.createTempDirectory("sennard-bundle-").toRealPath());
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    /** The folder the check is run in. */
    Path root() {
        return root;
    }

    /**
     * Puts the bytes of an attachment in the folder at the path of its file name.
     *
     * @return the path under the folder, as the checks name it
     */
    String put(String attachmentId, String fileName, byte[] content) {
        if (!portable(fileName)) {
            throw refused(attachmentId, "PATH_NOT_PORTABLE");
        }
        List<String> key = List.of(SLASH.split(fileName.toLowerCase(Locale.ROOT), -1));
        for (List<String> other : used) {
            // The same path, or a path that is a file in one place and a folder in the other ("a" and "a/b"), cannot both be in a folder: one is the start of the other.
            int common = Math.min(key.size(), other.size());
            if (key.subList(0, common).equals(other.subList(0, common))) {
                throw refused(attachmentId, "DUPLICATE_PATH");
            }
        }
        used.add(key);
        bytes += content.length;
        if (bytes > MAX_BYTES) {
            throw refused(attachmentId, "TOO_LARGE");
        }
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw refused(attachmentId, "PATH_NOT_PORTABLE");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
        return fileName;
    }

    /** Whether the text is a relative path in the portable form: bounded, read by {@code /}, with no empty, {@code .} or {@code ..} part, no drive, backslash or control character, no reserved name. */
    static boolean portable(String relative) {
        if (relative == null || relative.isEmpty() || relative.length() > 4096 || relative.startsWith("/")
                || BACKSLASH_OR_COLON.matcher(relative).find() || !relative.chars().allMatch(c -> c > 31 && c != 127)) {
            return false;
        }
        for (String part : SLASH.split(relative, -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.endsWith(".") || part.endsWith(" ") || RESERVED_NAME.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    private static WorkflowFailure refused(String attachmentId, String reason) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.bundle.fileNotUsable", "reason", reason), details("attachmentId", attachmentId, "reason", reason));
    }

    /** Removes the folder and everything in it. A folder that cannot be removed is logged: it holds copies of attachments the server keeps anyway, and the check has been answered. */
    @Override
    public void close() {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cause) {
            LOG.warn("The bundle folder {} could not be removed", root, cause);
        }
    }
}
