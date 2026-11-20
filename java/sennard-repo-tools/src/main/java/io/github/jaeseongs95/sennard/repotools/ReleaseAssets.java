// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * What the release workflows check about the files of a release, in Java and not in shell steps (a job without write permission calls only {@code ./gradlew}): the name of the
 * jar a build made against the tag and the version it is stamped with, whose checksum it writes, and the jar against its checksum file. A release is those two files and no more:
 * one jar runs on every OS, so there is no asset per OS or CPU.
 * The upload to the release is not here: the job that may write to the release runs no code of the repository and uploads with {@code gh release upload}. The checksum file is
 * {@code <sha256 hex>  <name>}, the format {@code sha256sum --check} reads.
 */
final class ReleaseAssets {
    private static final String SHA256 = "SHA-256";
    private static final String CHECKSUM_SUFFIX = ".sha256";

    private final Repo repo;

    ReleaseAssets(Repo repo) {
        this.repo = repo;
    }

    /** The {@code version} of {@code java/gradle.properties}, the one the build stamps. */
    String version() {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(repo.path("java/gradle.properties"))) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("java/gradle.properties", e);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new RepoTools.Failed("java/gradle.properties has no version");
        }
        return version.strip();
    }

    /**
     * Checks that {@code directory} holds the release jar {@code sennard-<version>.jar} and, at most, its checksum file, that a given tag is {@code v<version>}, and leaves
     * {@code <jar>.sha256} beside the jar. The jar is the same on every OS (the JVM absorbs the difference), so its name has no platform. A checksum file that is already there (the
     * build writes one) must be the line this check computes, so a stale one is a failure and never overwritten.
     *
     * @param tag the release tag, null or blank when the ref is only built
     * @return the name of the jar
     */
    String checkJar(Path directory, String tag) {
        String version = version();
        if (tag != null && !tag.isBlank() && !tag.equals("v" + version)) {
            throw new RepoTools.Failed("Tag " + tag + " is not v" + version + ", the version the build stamps.");
        }
        String asset = jarName(version);
        List<String> names = names(directory);
        if (!names.equals(List.of(asset)) && !names.equals(List.of(asset, asset + CHECKSUM_SUFFIX))) {
            throw new RepoTools.Failed("Expected only " + asset + " (and " + asset + CHECKSUM_SUFFIX + ") in " + directory + ", found: " + names);
        }
        Path checksum = directory.resolve(asset + CHECKSUM_SUFFIX);
        String line = sha256(directory.resolve(asset)) + "  " + asset + "\n";
        try {
            if (Files.exists(checksum)) {
                String found = Files.readString(checksum, StandardCharsets.UTF_8);
                if (!found.equals(line)) {
                    throw new RepoTools.Failed(checksum.getFileName() + " is not the checksum of " + asset + ": " + found.strip());
                }
            } else {
                Files.writeString(checksum, line, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(directory.toString(), e);
        }
        return asset;
    }

    /**
     * Checks a folder that holds the release jar and its checksum file and nothing else: the checksum file names the jar and carries the digest the jar has (the strict form of
     * {@code sha256sum --check}).
     */
    void checkChecksum(Path directory) {
        String asset = jarName(version());
        String checksum = asset + CHECKSUM_SUFFIX;
        List<String> names = names(directory);
        if (!names.equals(List.of(asset, checksum))) {
            throw new RepoTools.Failed("Expected only " + asset + " and " + checksum + " in " + directory + ", found: " + names);
        }
        String line;
        try {
            line = Files.readString(directory.resolve(checksum), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(checksum, e);
        }
        String[] parts = line.split("  ", 2);
        if (parts.length != 2 || !parts[1].equals(asset)) {
            throw new RepoTools.Failed(checksum + " does not name " + asset + ": " + line);
        }
        String actual = sha256(directory.resolve(asset));
        if (!actual.equals(parts[0])) {
            throw new RepoTools.Failed(asset + ": the checksum is " + parts[0] + ", the file's is " + actual);
        }
    }

    private static String jarName(String version) {
        return "sennard-" + version + ".jar";
    }

    /** The names of the files of a folder, sorted. */
    private static List<String> names(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(directory.toString(), e);
        }
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(SHA256);
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(file.toString(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
