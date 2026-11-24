// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * The release: one Spring Boot jar, {@code sennard-<version>.jar}, which the user runs with {@code java -jar} (the only thing Sennard is shipped as; the JDK is the user's). An installation
 * cannot run from it as it is: the jar holds its dependencies as nested jars, and the JDK's AOT cache (what keeps the server's and the hook client's start short) cannot be made for a class
 * path of nested jars. So {@link #extractInto} unpacks it the way Spring Boot documents for this ({@code -Djarmode=tools extract}: the application jar and {@code lib/} beside it) and
 * copies out of the jar the folders the server and the hosts read as files ({@code sennard-content/skills}, {@code sennard-content/contracts}).
 *
 * <p>The extraction is a child process of the JDK's own {@code java} because the jar mode is a feature of Spring Boot's launcher, which only a JVM started on the jar runs.
 */
final class ReleaseJar {
    /** The folder of the jar that holds what the server and the hosts read as files; its two folders go to the version's folder under their own names. */
    static final String CONTENT = "sennard-content";
    private static final Duration EXTRACT = Duration.ofMinutes(3);

    private final Path jar;

    private ReleaseJar(Path jar) {
        this.jar = jar;
    }

    /** The release jar the process runs from; empty when it runs from anything else (a folder of classes, a test), which has no jar to copy. */
    static Optional<ReleaseJar> of(Path source) {
        return Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar") ? Optional.of(new ReleaseJar(source.toAbsolutePath().normalize())) : Optional.empty();
    }

    Path path() {
        return jar;
    }

    /**
     * Makes {@code folder} the installed form of this release: the extracted application jar, named {@code sennard-<version>.jar} whatever the file was called when it was
     * downloaded, and {@code lib/}, and the content folders. The folder must not exist; the caller moves it into place when this has returned, so a version folder is never seen half made.
     *
     * @throws UnavailableException INSTALL_FAILED when the extraction fails or the jar holds no content folder
     */
    void extractInto(Path folder, String version, Path java, Commands commands, Map<String, String> environment) {
        // The jar is named by its file name and the child runs in the jar's folder: the Windows java launcher reads its command line in the system's code page and turns a letter outside
        // it into '?', so the jar's folder cannot be an argument. The destination is the one path that is: the installation's folder, which the installer has checked against the same code
        // page, and absolute because the child no longer runs in the folder this command does.
        Commands.Result result = commands.run(List.of(java.toString(), "-Djarmode=tools", "-jar", jar.getFileName().toString(), "extract", "--destination", folder.toAbsolutePath().normalize().toString()),
                jar.getParent(), environment, EXTRACT);
        if (result.exitCode() != 0) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "extractFailed", "file", jar, "cause", result.output().strip()));
        }
        try {
            Path extracted = onlyJar(folder);
            Path named = folder.resolve(InstalledVersion.jarName(version));
            if (!extracted.equals(named)) {
                Files.move(extracted, named);
            }
            copyContent(folder);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "copyFailed", "folder", folder), cause);
        }
    }

    private Path onlyJar(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> jars = files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar")).toList();
            if (jars.size() != 1) {
                throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", folder));
            }
            return jars.getFirst();
        }
    }

    /** Copies {@code sennard-content/<name>/...} of the jar to {@code <folder>/<name>/...} for each folder it holds, through the JDK's zip file system. */
    private void copyContent(Path folder) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(jar)) {
            Path content = zip.getPath("/" + CONTENT);
            if (!Files.isDirectory(content)) {
                throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", jar));
            }
            try (Stream<Path> names = Files.list(content)) {
                for (Path source : names.filter(Files::isDirectory).toList()) {
                    copyTree(source, folder.resolve(source.getFileName().toString()));
                }
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
