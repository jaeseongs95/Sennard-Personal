// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import io.github.jaeseongs95.sennard.domain.Digests;

/** The checksum recorded in {@code skills/source-lock.json}: every file's relative path and content, each framed by its byte length. The bytes are compared as they are: .gitattributes (`* text=auto eol=lf`) makes every checkout of a text file LF, so no line end is rewritten here. */
public final class DirectoryChecksum {
    private static final Set<String> SKIPPED = Set.of(".git", "__pycache__", "results");

    private DirectoryChecksum() {
    }

    public static String of(Repo repo, String directory) {
        MessageDigest digest = Digests.newSha256();
        List<String> files = repo.walk(directory).stream().filter(DirectoryChecksum::counted).toList();
        for (String file : files) {
            byte[] relative = Repo.under(directory, file).getBytes(StandardCharsets.UTF_8);
            byte[] content = repo.bytes(file);
            digest.update((relative.length + ":").getBytes(StandardCharsets.US_ASCII));
            digest.update(relative);
            digest.update((content.length + ":").getBytes(StandardCharsets.US_ASCII));
            digest.update(content);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256Of(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    private static boolean counted(String file) {
        for (Path segment : Path.of(file)) {
            if (SKIPPED.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }
}
