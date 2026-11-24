// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * {@link ReleaseJar#extractInto}: the release jar made into the installed form of a version. The extraction is the JDK's {@code java} on the jar ({@code -Djarmode=tools extract},
 * Spring Boot's own jar mode, which only a JVM started on the jar runs), so a stand-in for the command makes the folder the real one makes and the rest is under test: the
 * words of the command, the failure of the command, the name of the application jar, and the content folders copied out of the jar. The real extraction of the real jar is
 * {@code ReleaseJarFixture.extracted()}'s and the install flow's.
 *
 * <pre>
 * r1  command   {@code <java> -Djarmode=tools -jar <jar's file name> extract --destination <folder>} in the jar's folder, with the environment it is given
 * r2  failed    a command that ends with a code other than 0 is INSTALL_FAILED (extractFailed) with the command's own output as the cause; nothing is copied
 * r3  name      the application jar is named sennard-&lt;version&gt; whatever it was called when it was downloaded; one that has the name is not moved
 * r4  content   every folder of sennard-content/ is copied to the version's folder under its own name, files and subfolders with their bytes; a file beside them is not copied
 * r5  no content a jar without sennard-content/ is refused (INSTALL_FAILED), and so is an extraction that left no application jar or more than one
 * r6  not a jar a folder, a file that is no .jar and a missing file are no release jar (empty); a .jar is, as an absolute path
 * </pre>
 */
class ReleaseJarTest {
    @TempDir
    Path temporary;

    private static final byte[] SKILL = "---\nname: a-skill\n---\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}".getBytes(StandardCharsets.UTF_8);

    /** A small jar made with the JDK's zip stream: the content folders the install copies, and an entry the install does not look at. */
    private Path jar(String name, boolean withContent) throws IOException {
        Path jar = temporary.resolve(name);
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "BOOT-INF/classes/io/example/Main.class", new byte[] {1, 2, 3});
            if (withContent) {
                entry(zip, "sennard-content/skills/a-skill/SKILL.md", SKILL);
                entry(zip, "sennard-content/skills/a-skill/references/detail.md", "detail".getBytes(StandardCharsets.UTF_8));
                entry(zip, "sennard-content/contracts/work-v1.schema.json", SCHEMA);
                entry(zip, "sennard-content/README.txt", "a file beside the folders, which is not a folder of content".getBytes(StandardCharsets.UTF_8));
            }
        }
        return jar;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** What the extraction command makes in its destination: the application jar under the name the build gave it, and {@code lib/} with the other jars. */
    private static void extracted(Path destination, String applicationJar) throws IOException {
        Files.createDirectories(destination.resolve("lib"));
        Files.writeString(destination.resolve(applicationJar), "the application jar");
        Files.writeString(destination.resolve("lib").resolve("sennard-hook-client-6.0.0.jar"), "the hook client");
    }

    /** The folder after the command's own {@code --destination}: the last word. */
    private static Path destinationOf(List<String> argv) {
        return Path.of(argv.getLast());
    }

    @Test
    void theExtractionIsTheJavaOfTheJarModeToolsOnTheJarWithTheDestination() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", true);
        Path folder = temporary.resolve("extracted");
        Path java = temporary.resolve("a jdk").resolve("bin").resolve("java");
        List<List<String>> ran = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        List<Map<String, String>> environments = new ArrayList<>();
        List<Duration> deadlines = new ArrayList<>();
        Commands commands = new Commands() {
            @Override
            public Result run(List<String> argv, Map<String, String> environment, Duration timeout) {
                throw new AssertionError("the extraction runs in the jar's folder");
            }

            @Override
            public Result run(List<String> argv, Path directory, Map<String, String> environment, Duration timeout) {
                ran.add(argv);
                directories.add(directory);
                environments.add(environment);
                deadlines.add(timeout);
                try {
                    extracted(destinationOf(argv), "sennard-6.0.0.jar");
                } catch (IOException cause) {
                    throw new IllegalStateException(cause);
                }
                return new Commands.Result(0, "");
            }
        };

        ReleaseJar.of(jar).orElseThrow().extractInto(folder, "6.0.0", java, commands, Map.of("SENNARD_STATE_DIR", "x"));

        assertEquals(List.of(List.of(java.toString(), "-Djarmode=tools", "-jar", "sennard-6.0.0.jar", "extract", "--destination", folder.toString())), ran,
                "the jar is named by its file name: a path in the command line is read in the code page of the system");
        assertEquals(List.of(jar.toAbsolutePath().normalize().getParent()), directories, "the command runs in the folder of the jar");
        assertEquals(List.of(Map.of("SENNARD_STATE_DIR", "x")), environments, "the command runs in the environment of the install");
        assertEquals(1, deadlines.size());
        assertTrue(Files.isRegularFile(folder.resolve("sennard-6.0.0.jar")) && Files.isRegularFile(folder.resolve("lib").resolve("sennard-hook-client-6.0.0.jar")));
    }

    @Test
    void aCommandThatEndsWithAnotherCodeThanZeroIsAnInstallFailureWithItsOutputAndNothingIsCopied() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", true);
        Path folder = temporary.resolve("failed");
        Commands failing = (argv, environment, timeout) -> new Commands.Result(1, "Error: Unable to access jarfile\n");

        UnavailableException failure = assertThrows(UnavailableException.class, () -> ReleaseJar.of(jar).orElseThrow().extractInto(folder, "6.0.0", temporary.resolve("java"), failing, Map.of()));

        assertEquals(UnavailableCode.INSTALL_FAILED, failure.code());
        assertTrue(Sentences.english(failure).startsWith("Could not extract " + jar.toAbsolutePath().normalize() + ": Error: Unable to access jarfile"), Sentences.english(failure));
        assertFalse(Files.exists(folder), "nothing is made when the command made nothing");
    }

    @Test
    void theApplicationJarIsNamedForTheVersionWhateverItWasCalledWhenDownloaded() throws IOException {
        Path jar = jar("sennard-6.0.0 (1).jar", true);
        Path folder = temporary.resolve("renamed");
        // Spring Boot's extraction names the application jar after the file it was given.
        Commands commands = (argv, environment, timeout) -> {
            try {
                extracted(destinationOf(argv), "sennard-6.0.0 (1).jar");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };

        ReleaseJar.of(jar).orElseThrow().extractInto(folder, "6.0.1", temporary.resolve("java"), commands, Map.of());

        assertTrue(Files.isRegularFile(folder.resolve("sennard-6.0.1.jar")), "named for the version");
        assertFalse(Files.exists(folder.resolve("sennard-6.0.0 (1).jar")), "the download's name is gone");
        assertEquals("sennard-6.0.1.jar", InstalledVersion.jarName("6.0.1"), "the name InstalledVersion#appJar looks for in a version folder");
    }

    @Test
    void aJarThatAlreadyHasTheVersionsNameIsLeftWhereItIs() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", true);
        Path folder = temporary.resolve("named");
        Commands commands = (argv, environment, timeout) -> {
            try {
                extracted(destinationOf(argv), "sennard-6.0.0.jar");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };

        ReleaseJar.of(jar).orElseThrow().extractInto(folder, "6.0.0", temporary.resolve("java"), commands, Map.of());

        assertEquals("the application jar", Files.readString(folder.resolve("sennard-6.0.0.jar")));
    }

    @Test
    void everyFolderOfTheJarsContentIsCopiedToTheVersionsFolderUnderItsOwnNameAndAFileBesideThemIsNot() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", true);
        Path folder = temporary.resolve("content");
        Commands commands = (argv, environment, timeout) -> {
            try {
                extracted(destinationOf(argv), "sennard-6.0.0.jar");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };

        ReleaseJar.of(jar).orElseThrow().extractInto(folder, "6.0.0", temporary.resolve("java"), commands, Map.of());

        assertArrayEquals(SKILL, Files.readAllBytes(folder.resolve("skills").resolve("a-skill").resolve("SKILL.md")));
        assertEquals("detail", Files.readString(folder.resolve("skills").resolve("a-skill").resolve("references").resolve("detail.md")));
        assertArrayEquals(SCHEMA, Files.readAllBytes(folder.resolve("contracts").resolve("work-v1.schema.json")));
        assertFalse(Files.exists(folder.resolve("README.txt")) || Files.exists(folder.resolve("sennard-content")), "the folder's own name is not part of the copy, and a file is not a folder of content");
        assertEquals("sennard-content", ReleaseJar.CONTENT);
    }

    @Test
    void aJarWithoutContentIsRefused() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", false);
        Commands commands = (argv, environment, timeout) -> {
            try {
                extracted(destinationOf(argv), "sennard-6.0.0.jar");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };

        UnavailableException failure = assertThrows(UnavailableException.class, () -> ReleaseJar.of(jar).orElseThrow().extractInto(temporary.resolve("no-content"), "6.0.0", temporary.resolve("java"), commands, Map.of()));

        assertEquals(UnavailableCode.INSTALL_FAILED, failure.code());
        assertEquals("Could not read " + jar.toAbsolutePath().normalize() + ".", Sentences.english(failure));
    }

    @Test
    void anExtractionWithNoApplicationJarOrWithTwoIsRefused() throws IOException {
        Path jar = jar("sennard-6.0.0.jar", true);
        Commands none = (argv, environment, timeout) -> {
            try {
                Files.createDirectories(destinationOf(argv).resolve("lib"));
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };
        Commands two = (argv, environment, timeout) -> {
            try {
                extracted(destinationOf(argv), "sennard-6.0.0.jar");
                Files.writeString(destinationOf(argv).resolve("another.jar"), "x");
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
            return new Commands.Result(0, "");
        };
        Path java = temporary.resolve("java");

        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> ReleaseJar.of(jar).orElseThrow().extractInto(temporary.resolve("none"), "6.0.0", java, none, Map.of())).code());
        assertEquals(UnavailableCode.INSTALL_FAILED, assertThrows(UnavailableException.class, () -> ReleaseJar.of(jar).orElseThrow().extractInto(temporary.resolve("two"), "6.0.0", java, two, Map.of())).code());
    }

    @Test
    void onlyARegularFileThatEndsInJarIsAReleaseJar() throws IOException {
        Path folder = Files.createDirectories(temporary.resolve("a-folder.jar"));
        Path text = temporary.resolve("sennard-6.0.0.zip");
        Files.writeString(text, "x");
        Path jar = jar("sennard-6.0.0.jar", true);

        assertEquals(Optional.empty(), ReleaseJar.of(folder), "a folder of classes is no jar to copy");
        assertEquals(Optional.empty(), ReleaseJar.of(text));
        assertEquals(Optional.empty(), ReleaseJar.of(temporary.resolve("missing.jar")));
        assertEquals(jar.toAbsolutePath().normalize(), ReleaseJar.of(jar).orElseThrow().path());
    }
}
