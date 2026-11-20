// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors;
import io.github.jaeseongs95.sennard.app.attachment.Sha256Text;
import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * What {@code sennard attachments prepare} says of a file, and the measure {@code send} takes again: the size and the sha256 of the bytes of one regular file, read once from start to end.
 * The measure is a snapshot only if the file did not change while it was read, so the size and the modification time the file system reports are read before and after, and the
 * bytes counted must be the size; a file that changed during the read is refused, never measured. It connects to nothing: it is the part of the upload client that runs on the
 * computer the file is on.
 *
 * @param fileName the name {@code artifact_begin} takes (the file's own name, or the name the caller gave: a file of a review bundle is named by its path under the bundle's folder)
 * @param size the number of bytes
 * @param sha256 the text form {@code sha256:<64 hex digits>}
 * @param mediaType the media type {@code artifact_begin} takes
 */
public record PreparedFile(String fileName, long size, String sha256, String mediaType) {
    private static final Logger LOG = LoggerFactory.getLogger(PreparedFile.class);
    private static final String UNKNOWN_MEDIA_TYPE = "application/octet-stream";
    private static final int BUFFER_BYTES = 262144;

    /** The size and the sha256 of a file, as one snapshot. */
    public record Measure(long size, String sha256) {
    }

    /**
     * @param name the file name to declare, or null for the file's own name
     * @throws io.github.jaeseongs95.sennard.domain.error.InvalidInputException when the path is not a regular file or the file changed while it was read
     */
    public static PreparedFile of(Path file, String name) {
        Measure measure = measure(file);
        Path own = file.getFileName();
        String fileName = name != null ? name : own == null ? file.toString() : own.toString();
        return new PreparedFile(fileName, measure.size(), measure.sha256(), mediaType(file));
    }

    /** One read of the whole file: its size and sha256, when it did not change while it was read. */
    public static Measure measure(Path file) {
        BasicFileAttributes before = attributes(file);
        MessageDigest digest = Digests.newSha256();
        long count = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            for (int read = in.readNBytes(buffer, 0, buffer.length); read > 0; read = in.readNBytes(buffer, 0, buffer.length)) {
                digest.update(buffer, 0, read);
                count += read;
            }
        } catch (IOException cause) {
            throw unreadable(cause);
        }
        BasicFileAttributes after = attributes(file);
        if (count != before.size() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw ArtifactErrors.invalidArgument("<file>", "the file changed while it was read");
        }
        return new Measure(count, Sha256Text.of(HexFormat.of().formatHex(digest.digest())));
    }

    private static BasicFileAttributes attributes(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw ArtifactErrors.invalidArgument("<file>", "not a regular file");
            }
            return attributes;
        } catch (IOException cause) {
            throw unreadable(cause);
        }
    }

    /** A file that cannot be read is a wrong argument of the command: the cause is logged, and the line says which kind of failure it was. */
    private static RuntimeException unreadable(IOException cause) {
        LOG.debug("The file could not be read.", cause);
        return ArtifactErrors.invalidArgument("<file>", "cannot be read (" + cause.getClass().getSimpleName() + ")");
    }

    private static String mediaType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? UNKNOWN_MEDIA_TYPE : probed;
        } catch (IOException unknown) {
            // The platform cannot tell the type: the file is sent as bytes, which is what the unknown type says.
            LOG.debug("The media type of {} is not known.", file.getFileName(), unknown);
            return UNKNOWN_MEDIA_TYPE;
        }
    }
}
