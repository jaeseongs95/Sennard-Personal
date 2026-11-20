// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;

/**
 * Tells where the server is. The server's port is the operating system's choice at each start ({@code server.port: 0}; the admin page's already was), so no host's definition, no
 * client and no command can know it beforehand: once the server is ready, this writes the port with the process id and the process's start time into
 * {@value ServerAddress#FILE_NAME} of the state folder ({@link ServerAddress}, the one place that writes and reads it), and the hook client, the MCP relay and the commands read it from
 * there. A fixed port was a port another program could hold, and every host configuration that named it broke with it; a file that is read for every call also finds a server that was
 * started again at another port.
 *
 * <p>It is written on {@link ApplicationReadyEvent}, not when the web server starts: a client that finds the file may send at once, and the server has finished starting by
 * then. A server that cannot write its address fails its start (the exception ends the run): a server that nothing can find would be running for nobody. It is written only by the holder of
 * the state folder's lock ({@link ServerInstanceLock}), whose folder it is written to.
 *
 * <p>The file is removed when the context closes, and only when it is still this server's own: a server that was started again over the same folder has written its own over it, and a
 * killed server leaves its file behind, which a reader tells from a live server's by the process id and start time ({@link ServerAddress#live()}) and does not delete.
 */
@Component
final class ServerAddressPublisher {
    private final ServerInstanceLock lock;
    private final Environment environment;
    private volatile ServerAddress published; // null until written, and after it is removed

    ServerAddressPublisher(ServerInstanceLock lock, Environment environment) {
        this.lock = lock;
        this.environment = environment;
    }

    private Path file() {
        return lock.folder().resolve(ServerAddress.FILE_NAME);
    }

    /** Writes the address: the port the web server bound ({@code local.server.port}, which Boot sets from the main connector, not the admin page's). */
    @EventListener(ApplicationReadyEvent.class)
    void publish() {
        ServerAddress address = ServerAddress.ofThisProcess(environment.getRequiredProperty("local.server.port", Integer.class));
        try {
            address.writeAtomically(file());
        } catch (IOException cannotWrite) {
            throw new UncheckedIOException("The server's address cannot be written to " + file() + ": nothing could find this server.", cannotWrite);
        }
        published = address;
    }

    /** Removes the address file when it is this server's own. */
    @EventListener(ContextClosedEvent.class)
    void retract() {
        ServerAddress mine = published;
        if (mine == null) {
            return;
        }
        published = null;
        Optional<ServerAddress> current = ServerAddress.read(file());
        if (current.isPresent() && current.get().equals(mine)) {
            try {
                Files.deleteIfExists(file());
            } catch (IOException cannotDelete) {
                throw new UncheckedIOException("The server's address file " + file() + " cannot be removed.", cannotDelete);
            }
        }
    }
}
