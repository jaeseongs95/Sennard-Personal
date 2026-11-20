// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.hook.host.ForeignPathText;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import io.github.jaeseongs95.sennard.service.files.FileFactsException;

/**
 * The one class on this PC that opens a file from a value a request carried. Requests name files by absolute path or by a
 * relative path under a real directory root; a UNC path, a device path, a link anywhere on the way or a file over the limit never opens.
 */
@Component
public class LocalFileFacts implements FileFacts {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFileFacts.class);
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    @Override
    public byte[] read(String absolutePath, int maxBytes) {
        Path path = absolute(absolutePath);
        return readRegular(path, maxBytes);
    }

    @Override
    public byte[] readUnderRoot(String root, String relative, int maxBytes) {
        Path target = beneathRoot(root, relative);
        return readRegular(target, maxBytes);
    }

    @Override
    public boolean existsUnderRoot(String root, String relative) {
        Path base = Path.of(root).toAbsolutePath().normalize();
        Path current = base;
        for (String part : portable(relative)) {
            current = current.resolve(part);
        }
        return Files.exists(current, LinkOption.NOFOLLOW_LINKS);
    }

    private Path beneathRoot(String root, String relative) {
        List<String> parts = portable(relative);
        Path base = absolute(root);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw refused("INVALID_INPUT", "practices.io.artifactRootMustBeAReal");
        }
        Path current = realPath(base);
        for (int i = 0; i < parts.size(); i++) {
            current = current.resolve(parts.get(i));
            BasicFileAttributes info = attributes(current);
            if (info.isSymbolicLink()) {
                throw refused("INVALID_INPUT", "practices.io.symlinksAreNotAllowedInArtifact");
            }
            if (i == parts.size() - 1 ? !info.isRegularFile() : !info.isDirectory()) {
                throw refused("INVALID_INPUT", "practices.io.onlyRegularFilesBeneathDirectoriesAre");
            }
        }
        Path realRoot = realPath(base);
        if (!realPath(current).startsWith(realRoot)) {
            throw refused("INVALID_INPUT", "practices.io.artifactEscapesItsRoot");
        }
        return current;
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // A path that names nothing is its own refusal (NOT_FOUND), apart from one that names something that is not a regular file below directories (INVALID_INPUT).
            throw refused("NOT_FOUND", "practices.io.fileNotFound");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readRegular(Path path, int maxBytes) {
        BasicFileAttributes info = attributes(path);
        if (info.isSymbolicLink() || !info.isRegularFile()) {
            throw refused("INVALID_INPUT", "practices.io.artifactMustBeARegularFile");
        }
        if (info.size() > maxBytes) {
            throw refused("INVALID_INPUT", "practices.io.artifactExceedsTheByteLimit");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) info.size());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read until the size the attributes reported
            }
            if (buffer.hasRemaining() || channel.size() != info.size()) {
                throw refused("INTEGRITY_FAILED", "practices.io.artifactChangedWhileBeingRead");
            }
            return buffer.array();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path absolute(String value) {
        if (value == null || value.isEmpty() || value.startsWith("\\\\") || value.startsWith("//")) {
            throw refused("INVALID_INPUT", "practices.io.aBoundedRelativePathIsRequired");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw refused("INVALID_INPUT", "practices.io.aBoundedRelativePathIsRequired");
        }
        if (!path.isAbsolute() || path.getNameCount() == 0) {
            throw refused("INVALID_INPUT", "practices.io.aBoundedRelativePathIsRequired");
        }
        return path.normalize();
    }

    /**
     * A portable relative path's components: no drive, stream, backslash, control character, dot component or Windows reserved name. The form is the check contract's, read by
     * {@code /} on every file system (an empty component is refused, where {@link java.nio.file.Path} would quietly drop it), so it is parsed as that format and the components
     * are then resolved with {@link Path#resolve}.
     */
    static List<String> portable(String relative) {
        if (relative == null || relative.isEmpty() || relative.length() > 4096) {
            throw refused("INVALID_INPUT", "practices.io.aBoundedRelativePathIsRequired");
        }
        if (relative.startsWith("/") || ForeignPathText.hasBackslash(relative) || relative.indexOf(':') >= 0 || relative.chars().anyMatch(c -> c <= 31 || c == 127)) {
            throw refused("INVALID_INPUT", "practices.io.absoluteDriveBackslashControlAndStream");
        }
        List<String> parts = new ArrayList<>();
        for (String part : ForeignPathText.components(relative)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.endsWith(".") || part.endsWith(" ") || WINDOWS_RESERVED.matcher(part).matches()) {
                throw refused("INVALID_INPUT", "practices.io.unsafePathComponent");
            }
            parts.add(part);
        }
        return parts;
    }

    @Override
    public Optional<List<DirectoryEntry>> list(String directory) {
        Path path = absolute(directory);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        List<DirectoryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                BasicFileAttributes info = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                EntryType type = info.isSymbolicLink() ? EntryType.LINK : info.isRegularFile() ? EntryType.FILE : info.isDirectory() ? EntryType.DIRECTORY : EntryType.OTHER;
                entries.add(new DirectoryEntry(child.getFileName().toString(), type, info.size()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.of(entries);
    }

    @Override
    public boolean isRealDirectory(String absolutePath) {
        Path path = absolute(absolutePath);
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Optional<PathState> stat(String absolutePath) {
        Path path = absolute(absolutePath);
        try {
            Path real = path.toRealPath();
            BasicFileAttributes info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return Optional.of(new PathState(typeOf(info), real.toString(), info.size()));
        } catch (NoSuchFileException | java.nio.file.NotDirectoryException e) {
            LOG.debug("{} is not there.", path, e); // the answer "absent" is what the caller asked for; the JDK's way to ask is the exception
            return Optional.empty();
        } catch (IOException e) {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<PathFingerprint> fingerprint(String root, String relative) {
        Path target = absolute(root);
        try {
            for (String part : ForeignPathText.components(relative)) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    throw refused("INVALID_INPUT", "practices.io.unsafePathComponent");
                }
                target = target.resolve(part);
            }
        } catch (InvalidPathException e) {
            throw refused("INVALID_INPUT", "practices.io.unsafePathComponent");
        }
        try {
            BasicFileAttributes info = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String sha256 = info.isSymbolicLink() ? Digests.sha256Hex(Files.readSymbolicLink(target).toString()) : info.isRegularFile() ? fileHash(target) : null;
            return Optional.of(new PathFingerprint(typeOf(info), sha256, info.size()));
        } catch (NoSuchFileException | java.nio.file.NotDirectoryException e) {
            LOG.debug("{} is not there.", target, e);
            return Optional.empty();
        } catch (IOException e) {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            throw new UncheckedIOException(e);
        }
    }

    private static EntryType typeOf(BasicFileAttributes info) {
        return info.isSymbolicLink() ? EntryType.LINK : info.isRegularFile() ? EntryType.FILE : info.isDirectory() ? EntryType.DIRECTORY : EntryType.OTHER;
    }

    private static String fileHash(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return HexFormat.of().formatHex(Digests.sha256(in));
        }
    }

    private static FileFactsException refused(String code, String messageKey) {
        return new FileFactsException(code, messageKey, Map.of());
    }
}
