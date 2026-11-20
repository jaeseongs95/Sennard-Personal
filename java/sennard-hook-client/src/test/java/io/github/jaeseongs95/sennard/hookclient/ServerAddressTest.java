// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file where the server announces its address ({@link ServerAddress}), with real files in a temporary folder and real processes: the JDK's {@link ProcessHandle} says what is
 * alive. Case table (input: an address, a file's text; expected: what {@code read}, {@code live} and {@code writeAtomically} give):
 * <ol>
 * <li>an address written is the address read, to the instant; the file is {@code port}, {@code pid} and {@code processStart} as JDK properties; the base URL is
 * {@code http://127.0.0.1:<port>}</li>
 * <li>writing over an address replaces it whole and leaves no file of its own beside it; a folder that is not there is an {@link IOException} and leaves nothing</li>
 * <li>no file, a file that is a folder, an empty file, text that is no properties, a member missing (each of the three), a port or a pid that is no number, a port of 0, 65536 or negative,
 * a pid of 0, a time that is no instant: no address (empty), never an exception</li>
 * <li>an address of this process, as this process says it started, is live; the same with a start that is not this process's (an id that was given to another process) is not; the
 * process of a child that ended is not; a start a few microseconds apart (the precision of the operating system) is the same</li>
 * <li>an address cannot be made with a port or a pid out of range, or no start time</li>
 * <li>{@code ofThisProcess}: this process's id and the start the operating system tells of it</li>
 * </ol>
 */
class ServerAddressTest {
    private static final Instant START = Instant.parse("2026-11-16T01:02:03.456Z");

    @TempDir
    Path temporary;

    @Test
    void anAddressWrittenIsTheAddressReadAndItsFileIsJavaProperties() throws IOException {
        Path file = temporary.resolve(ServerAddress.FILE_NAME);
        ServerAddress address = new ServerAddress(54321, 4242, START);

        address.writeAtomically(file);

        assertEquals(Optional.of(address), ServerAddress.read(file));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.lines().anyMatch("port=54321"::equals), text);
        assertTrue(text.lines().anyMatch("pid=4242"::equals), text);
        assertTrue(text.lines().anyMatch(("processStart=" + START.toString().replace(":", "\\:"))::equals), "a colon is escaped in a properties file: " + text);
        assertEquals(URI.create("http://127.0.0.1:54321"), address.baseUrl());
        assertEquals("server.properties", ServerAddress.FILE_NAME);
    }

    @Test
    void writingOverAnAddressReplacesItWholeAndLeavesNoFileOfItsOwn() throws IOException {
        Path file = temporary.resolve(ServerAddress.FILE_NAME);
        new ServerAddress(1111, 1, START).writeAtomically(file);

        ServerAddress second = new ServerAddress(2222, 2, START.plusSeconds(5));
        second.writeAtomically(file);

        assertEquals(Optional.of(second), ServerAddress.read(file));
        try (Stream<Path> files = Files.list(temporary)) {
            assertEquals(List.of(file.getFileName().toString()), files.map(path -> path.getFileName().toString()).toList(), "only the address file is left");
        }
    }

    @Test
    void aFolderThatIsNotThereIsAnIoExceptionAndLeavesNothing() throws IOException {
        Path file = temporary.resolve("missing-folder").resolve(ServerAddress.FILE_NAME);

        assertThrows(IOException.class, () -> new ServerAddress(1111, 1, START).writeAtomically(file));

        try (Stream<Path> files = Files.list(temporary)) {
            assertEquals(List.of(), files.toList());
        }
    }

    private Optional<ServerAddress> readText(String text) throws IOException {
        Path file = temporary.resolve("text.properties");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return ServerAddress.read(file);
    }

    @Test
    void whatIsNotAnAddressIsNoAddress() throws IOException {
        assertEquals(Optional.empty(), ServerAddress.read(temporary.resolve("absent.properties")), "no file");
        assertEquals(Optional.empty(), ServerAddress.read(temporary), "a folder");
        for (String text : List.of("", "this is not a properties file\n=\n", "pid=1\nprocessStart=2026-11-16T01:02:03Z\n", "port=1\nprocessStart=2026-11-16T01:02:03Z\n", "port=1\npid=1\n",
                "port=abc\npid=1\nprocessStart=2026-11-16T01:02:03Z\n", "port=1\npid=abc\nprocessStart=2026-11-16T01:02:03Z\n", "port=0\npid=1\nprocessStart=2026-11-16T01:02:03Z\n",
                "port=65536\npid=1\nprocessStart=2026-11-16T01:02:03Z\n", "port=-5\npid=1\nprocessStart=2026-11-16T01:02:03Z\n", "port=1\npid=0\nprocessStart=2026-11-16T01:02:03Z\n",
                "port=1\npid=1\nprocessStart=yesterday\n", "port=1\npid=1\nprocessStart=\n", "port=\\u00zz\npid=1\nprocessStart=2026-11-16T01:02:03Z\n")) {
            assertEquals(Optional.empty(), readText(text), text);
        }
        assertEquals(Optional.of(new ServerAddress(1, 1, Instant.parse("2026-11-16T01:02:03Z"))), readText("port= 1 \npid=1\nprocessStart=2026-11-16T01\\:02\\:03Z\n"),
                "the spaces around a value are not part of it");
        Path notText = temporary.resolve("not-text.properties");
        Files.write(notText, new byte[] {'p', 'o', 'r', 't', '=', (byte) 0xFF, (byte) 0xFE, '\n'});
        assertEquals(Optional.empty(), ServerAddress.read(notText), "bytes that are not UTF-8 text");
    }

    @Test
    void inspectingTellsAnAbsentFileFromOneThatIsNoAddressAndSaysWhy() throws IOException {
        Path good = temporary.resolve("good.properties");
        new ServerAddress(7, 8, START).writeAtomically(good);
        Path notNumber = temporary.resolve("not-number.properties");
        Files.writeString(notNumber, "port=abc\npid=1\nprocessStart=2026-11-16T01:02:03Z\n", StandardCharsets.UTF_8);
        Path badTime = temporary.resolve("bad-time.properties");
        Files.writeString(badTime, "port=1\npid=1\nprocessStart=yesterday\n", StandardCharsets.UTF_8);
        Path notText = temporary.resolve("not-text.properties");
        Files.write(notText, new byte[] {'p', (byte) 0xFF, '\n'});

        assertEquals(new ServerAddress.Reading.Found(new ServerAddress(7, 8, START)), ServerAddress.inspect(good));
        assertEquals(new ServerAddress.Reading.Absent(), ServerAddress.inspect(temporary.resolve("absent.properties")));
        for (Path file : List.of(notNumber, badTime, notText)) {
            ServerAddress.Reading reading = ServerAddress.inspect(file);
            assertTrue(reading instanceof ServerAddress.Reading.Unusable unusable && unusable.cause() != null, file.getFileName() + ": " + reading);
            assertEquals(Optional.empty(), reading.found(), file.getFileName().toString());
        }
        assertTrue(((ServerAddress.Reading.Unusable) ServerAddress.inspect(notNumber)).cause() instanceof NumberFormatException);
        assertTrue(((ServerAddress.Reading.Unusable) ServerAddress.inspect(badTime)).cause() instanceof DateTimeParseException);
    }

    @Test
    void anAddressNeedsAPortAPidAndAStartTime() {
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress(0, 1, START));
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress(65_536, 1, START));
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress(1, 0, START));
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress(1, 1, null));
        assertEquals(65_535, new ServerAddress(65_535, 1, START).port());
    }

    @Test
    void thisProcessIsLiveAndAProcessThatIsNotTheSameOrHasEndedIsNot() throws Exception {
        ServerAddress self = ServerAddress.ofThisProcess(5000);

        assertEquals(ProcessHandle.current().pid(), self.pid());
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow(), self.processStart());
        assertTrue(self.live());
        assertFalse(new ServerAddress(5000, self.pid(), self.processStart().minusSeconds(3600)).live(), "this id, a start that is not this process's: an id given to another process");
        assertFalse(new ServerAddress(5000, self.pid(), self.processStart().plusSeconds(3600)).live());
        assertTrue(new ServerAddress(5000, self.pid(), self.processStart().plusNanos(900_000)).live(), "the operating systems give the start to a different precision: a millisecond is the same");

        Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Instant childStart = ended.info().startInstant().orElse(START);
        ended.waitFor();
        assertFalse(new ServerAddress(5000, ended.pid(), childStart).live(), "the process of the file has ended");
    }
}
