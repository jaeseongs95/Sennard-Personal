// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Properties;

/**
 * Where the running server listens, as the server tells it: the one place this is written and read, for the server that writes it and for every client (the hook client, the
 * MCP relay, the installer's commands) that reads it. The server does not have a port of its own choosing: it binds the port the operating system gives it at each start
 * ({@code server.port: 0}, as the admin page's connector does), so no host's definition and no client can name the port beforehand. The server writes the port into a file of the
 * state folder once it is ready, and a client reads that file for every call, so a server that was started again at another port is found as well.
 *
 * <p>The file is a JDK {@link Properties} file ({@code port}, {@code pid}, {@code processStart}) and this class has nothing but {@code java.base}: the hook client may not load a
 * library. It is written to a file of its own next to it and moved over the old one in one step, so a client never reads half of it. A file outlives a server that was killed, so
 * the file alone does not say that a server is there: {@link #live()} asks the operating system whether the process that wrote it is still the same one (its id, and the time it
 * started, which an id that was given to another process later does not share). A client that finds no live server does not delete the file; the next server writes over it.
 *
 * @param port the port of the hooks, the MCP endpoint and Actuator
 * @param pid the server's process id
 * @param processStart when that process started, as the operating system says
 */
public record ServerAddress(int port, long pid, Instant processStart) {
    /** The file's name in the state folder. */
    public static final String FILE_NAME = "server.properties";
    private static final String PORT = "port";
    private static final String PID = "pid";
    private static final String PROCESS_START = "processStart";
    private static final int MAX_PORT = 65_535;

    public ServerAddress {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("A server's port is 1 to " + MAX_PORT + ": " + port);
        }
        if (pid < 1) {
            throw new IllegalArgumentException("A process id is positive: " + pid);
        }
        if (processStart == null) {
            throw new IllegalArgumentException("A server address names when its process started");
        }
    }

    /**
     * The address of this process at {@code port}.
     *
     * @throws IllegalStateException when the operating system does not tell when this process started: without it a client cannot tell this server from a process that was given the
     *         same id later, so a server that cannot say it does not start
     */
    public static ServerAddress ofThisProcess(int port) {
        ProcessHandle self = ProcessHandle.current();
        Instant start = self.info().startInstant().orElseThrow(() -> new IllegalStateException("The operating system does not tell when this process started: SERVER_ADDRESS_NEEDS_PROCESS_START"));
        return new ServerAddress(port, self.pid(), start);
    }

    /** What the file at a path holds: an address, nothing, or something that is not an address, with the reason. */
    public sealed interface Reading {
        /** The file is an address. */
        record Found(ServerAddress address) implements Reading {
        }

        /** There is no file: no server announced itself here (or the one that did is gone and its file was removed). */
        record Absent() implements Reading {
        }

        /**
         * There is a file and it cannot be used: one that cannot be read (a server is moving over it this moment, bytes that are not text) or that is not an address (a member missing
         * or not a number, a port out of range, a time that is not an instant).
         *
         * @param cause why: the read's own exception, or the one that the member that is not valid raised
         */
        record Unusable(Exception cause) implements Reading {
        }

        /** The address, when the file is one. */
        default Optional<ServerAddress> found() {
            return this instanceof Found found ? Optional.of(found.address()) : Optional.empty();
        }
    }

    /**
     * @return the address in the file, or empty when there is none: no file, a file that cannot be read, or one that is not an address. A client reads it for every call and what it
     *         does is the same for each of them ("no server"); {@link #inspect} tells them apart, for the one that says why
     */
    public static Optional<ServerAddress> read(Path file) {
        return inspect(file).found();
    }

    /** What the file holds, and when it holds no address why (never an exception: a client must not fail for a file that a server left behind). */
    public static Reading inspect(Path file) {
        if (Files.notExists(file)) {
            return new Reading.Absent();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            return new Reading.Unusable(unreadable);
        }
        try {
            return new Reading.Found(new ServerAddress(Integer.parseInt(properties.getProperty(PORT, "").strip()), Long.parseLong(properties.getProperty(PID, "").strip()),
                    Instant.parse(properties.getProperty(PROCESS_START, "").strip())));
        } catch (IllegalArgumentException | DateTimeParseException notAnAddress) { // a number that is not one is a NumberFormatException, an IllegalArgumentException
            return new Reading.Unusable(notAnAddress);
        }
    }

    /**
     * Writes the file whole or not at all: to a file of this process's own beside it, then moved over {@code file} in one step. A reader sees the old content or the new, never a
     * part, and two servers that write at once (one of them is about to refuse: only the one that holds the state folder's lock goes on to write) do not share a temporary file.
     */
    public void writeAtomically(Path file) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Properties properties = new Properties();
        properties.setProperty(PORT, Integer.toString(port));
        properties.setProperty(PID, Long.toString(pid));
        properties.setProperty(PROCESS_START, processStart.toString());
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "The address of the running Sennard server (ServerAddress)");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailed) {
                failed.addSuppressed(cleanupFailed);
            }
            throw failed;
        }
    }

    /** Whether the process of this address is running and is the one that started at {@link #processStart}: a process that was given the same id later started at another time. */
    public boolean live() {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(process -> process.info().startInstant())
                // The operating systems give the start time to a different precision; a millisecond is what they all have.
                .map(started -> started.truncatedTo(ChronoUnit.MILLIS).equals(processStart.truncatedTo(ChronoUnit.MILLIS))).orElse(false);
    }

    /** {@code http://127.0.0.1:<port>}: the server listens on the loopback address only. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port);
    }
}
