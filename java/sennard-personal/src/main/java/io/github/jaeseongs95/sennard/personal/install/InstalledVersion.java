// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.Collector;

/**
 * One installed version on disk: the folder {@code <installation>/versions/<version>/} that {@code sennard install} makes from the release jar. The release is one Spring Boot
 * jar; a Boot jar keeps its dependencies as jars inside itself, and the JDK's AOT cache cannot be made for a class path of nested jars, so the installation holds the jar extracted
 * ({@code -Djarmode=tools extract}): {@code sennard-<version>.jar} beside {@code lib/} with the other jars, the same files on every OS (the JVM is the only thing that differs, and it
 * is the user's). The skill folders and the contract schemas the server reads are copied out of the jar's {@code sennard-content/} into the same folder.
 *
 * @param folder the version's folder; its name is the version
 */
public record InstalledVersion(Path folder) {
    /** The folder of an installation that holds one folder per version. */
    public static final String VERSIONS = "versions";
    /** The start of the file name of the hook client's jar (Gradle names a module's jar {@code <module>-<version>.jar}). */
    static final String HOOK_CLIENT_JAR_PREFIX = "sennard-hook-client-";

    public InstalledVersion {
        folder = folder.toAbsolutePath().normalize();
    }

    /** The version at {@code folder}, when its application jar is there; empty for a folder that is no installed version (a leftover of a failed publish, for one). */
    public static Optional<InstalledVersion> at(Path folder) {
        InstalledVersion version = new InstalledVersion(folder);
        return Files.isRegularFile(version.appJar()) ? Optional.of(version) : Optional.empty();
    }

    /**
     * The installed version a jar belongs to, when it is the application jar of one ({@code <installation>/versions/<version>/sennard-<version>.jar}); empty for a release jar that
     * runs from where the user put it.
     */
    public static Optional<InstalledVersion> ofJar(Path jar) {
        Path absolute = jar.toAbsolutePath().normalize();
        Path folder = absolute.getParent();
        if (folder == null || folder.getFileName() == null || folder.getParent() == null || folder.getParent().getFileName() == null
                || !Path.of(VERSIONS).equals(folder.getParent().getFileName())) {
            return Optional.empty();
        }
        InstalledVersion version = new InstalledVersion(folder);
        return version.appJar().equals(absolute) ? Optional.of(version) : Optional.empty();
    }

    /** The version folder a server runs from: the folder the server's {@code sennard.home} names (the one beside its jar). */
    public static InstalledVersion ofFolder(Path folder) {
        return new InstalledVersion(folder);
    }

    public String version() {
        return folder.getFileName().toString();
    }

    /** The application jar, named by the version so that a folder of one version never holds the jar of another. */
    public Path appJar() {
        return folder.resolve(jarName(version()));
    }

    /** {@code sennard-<version>.jar}: the name of the release jar as published and of the application jar in the installed folder. */
    public static String jarName(String version) {
        return "sennard-" + version + ".jar";
    }

    /**
     * The jar of the hook client ({@code sennard-hook-client-<version>.jar}), which the folder's {@code lib} holds with the other modules' jars: what a command hook and the MCP relay put
     * on their class path, and the only thing on it.
     *
     * @throws UnavailableException when there is not exactly one
     */
    public Path hookClientJar() {
        Path lib = folder.resolve("lib");
        try (Stream<Path> found = Files.list(lib)) {
            List<Path> jars = found.filter(path -> path.getFileName().toString().startsWith(HOOK_CLIENT_JAR_PREFIX) && path.getFileName().toString().endsWith(".jar")).toList();
            if (jars.size() != 1) {
                throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", lib));
            }
            return jars.get(0);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", lib), cause);
        }
    }

    /** The settings file beside the jar, where the server looks for it. */
    public Path settingsFile() {
        return folder.resolve("sennard.yaml");
    }

    /** The AOT cache of one collector: a server started with another collector finds no file of its own and starts without a cache. */
    public Path aotCache(Collector collector) {
        return folder.resolve(collector.cacheFileName());
    }

    /**
     * The one file the installer and the admin page write the server's JVM preset to ({@code sennard.jvm.preset}, with the memory the installer read to choose it).
     * It is beside the jar and not in {@code sennard.yaml}, which the user writes by hand: this one is rewritten by programs, so it holds nothing else.
     */
    public Path jvmSettingsFile() {
        return folder.resolve("sennard-jvm.yaml");
    }

    /**
     * The folder an installation is made in, when this is one of its versions ({@code <root>/versions/<version>}); empty for a folder that was only extracted. The files that outlive a
     * version (the JVM's own logs) go there.
     */
    public Optional<Path> installRoot() {
        Path versions = folder.getParent();
        return versions != null && versions.getFileName() != null && Path.of(VERSIONS).equals(versions.getFileName()) && versions.getParent() != null
                ? Optional.of(versions.getParent()) : Optional.empty();
    }

    /** What the training run records and the cache is created from; removed once the cache exists. */
    public Path aotConfiguration() {
        return folder.resolve(JvmOptions.AOT_CONFIGURATION_FILE);
    }
}
