// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;

/** Streams a file through the application's SHA-256 implementation without retaining its content. */
@Component
public class FileDigest {
    public String sha256(Path path) throws IOException {
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return HexFormat.of().formatHex(Digests.sha256(input));
        }
    }
}
