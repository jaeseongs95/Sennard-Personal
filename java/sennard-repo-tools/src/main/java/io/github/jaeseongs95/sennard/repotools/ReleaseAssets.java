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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the release workflows check about the files of a release, in Java and not in shell steps (a job without write permission calls only {@code ./gradlew}): the name of the
 * jar a build made against the tag and the version it is stamped with, whose checksum it writes, and the jar against its checksum file. The setup launchers are rendered
 * against that same jar name and verified against their source, byte for byte: {@code setup.cmd} always with CRLF line endings and {@code setup.sh} always with LF.
 * The upload to the release is not here: the job that may write to the release runs no code of the repository and uploads with {@code gh release upload}. The checksum file is
 * {@code <sha256 hex>  <name>}, the format {@code sha256sum --check} reads.
 */
final class ReleaseAssets {
    private static final String SHA256 = "SHA-256";
    private static final String CHECKSUM_SUFFIX = ".sha256";
    private static final List<String> SETUP_LAUNCHERS = List.of("setup.cmd", "setup.sh");
    /** How long {@code setup.cmd} keeps a failure message on the screen when no key is pressed. */
    static final int FAILURE_WAIT_SECONDS = 300;
    private static final String JAR_PLACEHOLDER = "@SENNARD_RELEASE_JAR@";
    private static final String WAIT_PLACEHOLDER = "@SENNARD_FAILURE_WAIT_SECONDS@";
    /** The placeholders each template must contain; one that is missing is a failure, whichever of the others is left. */
    private static final Map<String, List<String>> REQUIRED_PLACEHOLDERS = Map.of(
            "setup.cmd", List.of(JAR_PLACEHOLDER, WAIT_PLACEHOLDER),
            "setup.sh", List.of(JAR_PLACEHOLDER));
    /** Anything shaped like a placeholder that the render left behind: a misspelled name, or one the render does not know. */
    private static final Pattern LEFTOVER_PLACEHOLDER = Pattern.compile("@SENNARD_[A-Z_]+@");

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
     * Checks the jar and tag against the build version and prepares the checksum and setup launchers beside it. Existing assets must match their expected content: a checksum
     * file that is already there (the build writes one) must be the line this check computes, so a stale one is a failure and is never overwritten.
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
        if (!names.contains(asset) || !assetNames(asset).containsAll(names)) {
            throw new RepoTools.Failed("Expected " + asset + " and only its companion assets in " + directory + ", found: " + names);
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
        checkSetupLaunchers(directory, asset, true);
        return asset;
    }

    /**
     * Checks the complete set of server release assets against the jar checksum and the launcher templates.
     */
    void checkChecksum(Path directory) {
        String asset = jarName(version());
        String checksum = asset + CHECKSUM_SUFFIX;
        List<String> names = names(directory);
        if (!names.equals(assetNames(asset))) {
            throw new RepoTools.Failed("Expected " + assetNames(asset) + " in " + directory + ", found: " + names);
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
        checkSetupLaunchers(directory, asset, false);
    }

    private static List<String> assetNames(String jar) {
        return Stream.concat(Stream.of(jar, jar + CHECKSUM_SUFFIX), SETUP_LAUNCHERS.stream()).sorted().toList();
    }

    private void checkSetupLaunchers(Path directory, String jar, boolean writeMissing) {
        for (String launcher : SETUP_LAUNCHERS) {
            Path asset = directory.resolve(launcher);
            try {
                byte[] expected = launcherBytes(launcher, jar, FAILURE_WAIT_SECONDS);
                if (writeMissing && Files.notExists(asset)) {
                    Files.write(asset, expected);
                } else if (!Arrays.equals(Files.readAllBytes(asset), expected)) {
                    throw new RepoTools.Failed(launcher + " does not match the release template for " + jar + ".");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(launcher, e);
            }
        }
    }

    /**
     * The bytes of a launcher rendered from its template: the jar name and, in the templates that wait after a failure, the seconds the wait lasts. The seconds are a value of the
     * render and not a setting of the user; the release uses {@link #FAILURE_WAIT_SECONDS}, and a test of the wait renders a shorter one.
     */
    byte[] launcherBytes(String launcher, String jar, int failureWaitSeconds) throws IOException {
        String template = Files.readString(repo.path("release/" + launcher + ".template"), StandardCharsets.UTF_8);
        for (String placeholder : REQUIRED_PLACEHOLDERS.getOrDefault(launcher, List.of())) {
            if (!template.contains(placeholder)) {
                throw new RepoTools.Failed(launcher + " does not reference the placeholder " + placeholder + ".");
            }
        }
        String rendered = template.replace(JAR_PLACEHOLDER, jar).replace(WAIT_PLACEHOLDER, Integer.toString(failureWaitSeconds));
        Matcher leftover = LEFTOVER_PLACEHOLDER.matcher(rendered);
        if (leftover.find()) {
            throw new RepoTools.Failed(launcher + " has a placeholder the render does not fill: " + leftover.group() + ".");
        }
        // The line ending belongs to the kind of file, not to the checkout or the OS that builds: a batch file is CRLF, a shell script is LF.
        String lf = rendered.replace("\r", "");
        return (launcher.equals("setup.cmd") ? lf.replace("\n", "\r\n") : "#!/bin/sh\n" + lf).getBytes(StandardCharsets.UTF_8);
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
