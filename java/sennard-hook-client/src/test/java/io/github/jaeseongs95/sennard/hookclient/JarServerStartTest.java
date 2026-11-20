// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the client starts the installed server ({@link JarServerStart}): the real command in a real JVM, on a jar that this test makes with {@link StartRecorder} as its main class
 * (a jar is what the release is, and {@code java -jar} is the whole of how it is run). Case table (input: a jar, a wait; expected: the words of the command, what the jar's program
 * received, how a start that fails ends):
 * <ol>
 * <li>the command's words: this client's Java, the JVM options of a command that only starts the server and waits, {@code -jar <jar> start --within=<seconds>}</li>
 * <li>the jar's program receives {@code start} and {@code --within=<seconds>}, from a folder whose name has a space; a start that ends with exit code 0 returns</li>
 * <li>a jar that ends with another exit code is a start that failed, with that code; a jar that is not there is one (the Java that could not open it ends with exit code 1)</li>
 * </ol>
 */
class JarServerStartTest {
    @TempDir
    Path temporary;

    /** A jar of {@link StartRecorder} alone, with it as {@code Main-Class}, in a folder with a space in its name. */
    private Path recorderJar() throws IOException {
        Path folder = Files.createDirectory(temporary.resolve("install folder"));
        Path jar = folder.resolve("sennard-6.0.0.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, StartRecorder.class.getName());
        String entry = StartRecorder.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest); InputStream classFile = StartRecorder.class.getResourceAsStream("StartRecorder.class")) {
            out.putNextEntry(new JarEntry(entry));
            out.write(classFile.readAllBytes());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void theCommandIsThisClientsJavaItsOptionsAndTheJarsStartCommand() {
        assertEquals(List.of("/java/bin/java", "-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-jar", Path.of("/install/sennard-6.0.0.jar").toString(), "start", "--within=30"),
                JarServerStart.command("/java/bin/java", Path.of("/install/sennard-6.0.0.jar"), Duration.ofSeconds(30)));
        assertEquals("start", HookClientCall.START_COMMAND);
        assertEquals("--within", HookClientCall.START_WITHIN);
    }

    @Test
    void theJarIsRunWithStartAndTheWaitAndAStartThatEndsWithExitZeroReturns() throws IOException {
        Path jar = recorderJar();

        new JarServerStart(jar).startWithin(Duration.ofSeconds(3));

        assertEquals("start\n--within=3", Files.readString(jar.resolveSibling("start-arguments.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void aJarThatEndsWithAnotherExitCodeIsAStartThatFailedWithThatCode() throws IOException {
        Path jar = recorderJar();
        Files.createFile(jar.resolveSibling("fail"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new JarServerStart(jar).startWithin(Duration.ofSeconds(3)));

        assertEquals("the start ended with exit code 3", failure.getMessage());
        assertEquals("start\n--within=3", Files.readString(jar.resolveSibling("start-arguments.txt"), StandardCharsets.UTF_8), "the jar was run");
    }

    @Test
    void aJarThatIsNotThereIsAStartThatFailedWithJavasExitCode() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new JarServerStart(temporary.resolve("no-such.jar")).startWithin(Duration.ofSeconds(3)));

        assertEquals("the start ended with exit code 1", failure.getMessage());
    }
}
