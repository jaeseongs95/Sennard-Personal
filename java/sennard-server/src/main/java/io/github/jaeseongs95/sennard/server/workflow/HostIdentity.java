// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * The machine a root was stored on: one random UUID ({@link UUID#randomUUID()}) made once, when Sennard is installed, and kept in the state folder. Paths on
 * different hosts never overlap (two machines sharing one database). Nothing is read from the operating system: the file is the identity, so it survives
 * an upgrade and a reinstall (the state folder stays), and a state folder copied to another machine carries the identity with it.
 * A component, made from {@code SennardProperties#stateDir()}. The state folder is made through the port of the storage of secrets ({@link PrivateStorage}), like every folder that holds
 * a secret, so that it is private to its owner from its first instant; what this class writes in it is the identity, which is no secret.
 */
@Component
public class HostIdentity {
    /** The file in the state folder that holds the identity. */
    public static final String FILE = "host-id";

    private static final Logger LOG = LoggerFactory.getLogger(HostIdentity.class);
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path file;
    private final PrivateStorage storage;
    private volatile String resolved;

    /** The component: the identity is kept in the state folder the server's settings name, which a context that starts the server must have. */
    @Autowired
    public HostIdentity(SennardProperties properties, PrivateStorage storage) {
        this(stateDirectoryOf(properties), storage);
    }

    /** @param storage the port that makes the state folder when there is none */
    public HostIdentity(Path stateDirectory, PrivateStorage storage) {
        this.file = stateDirectory.resolve(FILE);
        this.storage = storage;
    }

    private static Path stateDirectoryOf(SennardProperties properties) {
        if (properties.stateDir() == null) {
            throw new IllegalStateException("sennard.state-dir is not set: the host identity is kept in the state folder.");
        }
        return properties.stateDir();
    }

    private static WorkflowFailure unresolved(Exception cause) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("services.workspace.hostUnresolved"),
                details("reason", "HOST_IDENTITY_UNRESOLVED", "cause", cause == null ? "unreadable" : cause.getClass().getSimpleName()), cause);
    }

    /** Resolved once for the process; a read failure is never remembered and never becomes a new identity. */
    public String id() {
        String known = resolved;
        if (known != null) {
            return known;
        }
        String id;
        try {
            id = ensure(file.getParent(), storage);
        } catch (IOException | UnavailableException cause) {
            throw unresolved(cause);
        }
        if (id == null) {
            throw unresolved(null);
        }
        resolved = id;
        return id;
    }

    /**
     * The identity kept in the state folder, made first when there is none: the installer calls this once, and the server calls it when it finds none (a
     * server started without an install). An identity that exists is never replaced, so a reinstall keeps it.
     *
     * @param storage makes the state folder, private to its owner, when it is not there
     * @return the identity, or null when the file does not hold a UUID
     */
    public static String ensure(Path stateDirectory, PrivateStorage storage) throws IOException {
        Path identity = stateDirectory.resolve(FILE);
        storage.createFolder(stateDirectory);
        synchronized (HostIdentity.class) {
            if (!Files.exists(identity)) {
                // Written whole under another name and then moved, so that a reader of the file never sees it half written; a move onto an existing file fails.
                Path draft = Files.createTempFile(stateDirectory, FILE + "-", ".tmp");
                try {
                    Files.writeString(draft, UUID.randomUUID() + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.move(draft, identity);
                } catch (FileAlreadyExistsException lost) {
                    LOG.debug("Another process made the identity first; its value is read.", lost);
                } finally {
                    Files.deleteIfExists(draft);
                }
            }
        }
        String value = Files.readString(identity, StandardCharsets.UTF_8).strip();
        return UUID_TEXT.matcher(value).matches() ? value : null;
    }
}
