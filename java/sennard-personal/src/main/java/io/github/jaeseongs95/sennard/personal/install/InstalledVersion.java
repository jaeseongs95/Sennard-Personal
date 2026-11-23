// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(InstalledVersion.class);
    /** The folder of an installation that holds one folder per version. */
    public static final String VERSIONS = "versions";
    /** The start of the file name of the hook client's jar (Gradle names a module's jar {@code <module>-<version>.jar}). */
    static final String HOOK_CLIENT_JAR_PREFIX = "sennard-hook-client-";
    private static final String RUNTIME_FILES = ".runtime-files";

    public InstalledVersion {
        folder = folder.toAbsolutePath().normalize();
    }

    /** A published version has its extracted runtime files. A legacy directory can be incomplete after an interrupted deletion. */
    public static Optional<InstalledVersion> at(Path folder) {
        if (folder.getFileName() == null || folder.getFileName().toString().startsWith(".") || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        InstalledVersion version = new InstalledVersion(folder);
        try {
            return completeRuntime(version.folder(), version.appJar()) ? Optional.of(version) : Optional.empty();
        } catch (IOException | IllegalArgumentException incomplete) {
            LOG.debug("Could not validate the installed runtime at {}", version.folder(), incomplete);
            return Optional.empty();
        }
    }

    /** Record the files extracted by the release before publishing the staging folder. Settings and generated caches are added later. */
    static void recordRuntime(Path staging, String version) throws IOException {
        Path folder = staging.toAbsolutePath().normalize();
        if (!completeRuntime(folder, folder.resolve(jarName(version)))) {
            throw new IOException("Extracted runtime is incomplete: " + staging);
        }
        List<String> files;
        try (Stream<Path> paths = Files.walk(staging)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(staging::relativize).map(Path::toString).sorted().toList();
        }
        Files.write(staging.resolve(RUNTIME_FILES), files);
    }

    private static boolean completeRuntime(Path folder, Path app) throws IOException {
        if (!regularRuntimeFile(app) || !regularRuntimeFile(folder.resolve("skills/registry.json"))
                || !regularRuntimeFile(folder.resolve("contracts/workflow-plan.v1.schema.json"))) {
            return false;
        }
        Path lib = folder.resolve("lib");
        if (!Files.isDirectory(lib, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Optional<Path> clientJar = findHookClientJar(lib);
        if (clientJar.isEmpty()) {
            return false;
        }
        try (JarInputStream jar = new JarInputStream(Files.newInputStream(app))) {
            if (jar.getManifest() == null) {
                return false;
            }
            String classPath = jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.isBlank()) {
                return false;
            }
            int hookEntries = 0;
            for (String entry : classPath.strip().split("\\s+")) {
                URI relative = URI.create(entry);
                if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawQuery() != null || relative.getRawFragment() != null) {
                    return false;
                }
                Path dependency = folder.resolve(relative.getPath()).normalize();
                if (!lib.equals(dependency.getParent()) || !dependency.getFileName().toString().endsWith(".jar") || !regularRuntimeFile(dependency)) {
                    return false;
                }
                if (dependency.equals(clientJar.get())) {
                    hookEntries++;
                }
            }
            return hookEntries == 1 && inventoryPresent(folder);
        }
    }

    private static boolean inventoryPresent(Path folder) throws IOException {
        Path inventory = folder.resolve(RUNTIME_FILES);
        List<String> files;
        try {
            files = Files.readAllLines(inventory);
        } catch (NoSuchFileException legacy) {
            // Legacy versions have an application manifest but no extracted content inventory.
            LOG.trace("No extracted file inventory at {}; accepting the legacy runtime layout.", inventory, legacy);
            return true;
        }
        return !files.isEmpty() && files.stream().allMatch(entry -> {
            Path file = folder.resolve(entry).normalize();
            return file.startsWith(folder) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        });
    }

    private static boolean regularRuntimeFile(Path path) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 0;
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
     * @throws UnavailableException unless exactly one candidate exists and is a nonempty regular file
     */
    public Path hookClientJar() {
        Path lib = folder.resolve("lib");
        try {
            return findHookClientJar(lib).orElseThrow(
                    () -> new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", lib)));
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", lib), cause);
        }
    }

    private static Optional<Path> findHookClientJar(Path lib) throws IOException {
        try (Stream<Path> found = Files.list(lib)) {
            List<Path> jars = found.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(HOOK_CLIENT_JAR_PREFIX) && name.endsWith(".jar");
            }).limit(2).toList();
            return jars.size() == 1 && regularRuntimeFile(jars.getFirst()) ? Optional.of(jars.getFirst()) : Optional.empty();
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
