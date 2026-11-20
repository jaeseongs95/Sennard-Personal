// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * Makes the state folder through the port of the storage of secrets before Boot starts its logging. The server's log file is in the state folder
 * ({@code logging.file.name=${sennard.state-dir}/logs/sennard-server.log}), and Logback creates the missing folders of a log file's path (its {@code FileAppender} does it unconditionally,
 * {@code FileUtil.createMissingParentDirectories}) with the default access of the system, when the logging system is initialised: before any bean exists, so before
 * {@link LocalStateDirectory}. Without this a server that starts with no state folder would have it made by the log file, not private to its owner. The state folder is
 * read from the environment once the settings are loaded, and this listener runs after that and before the logging listener.
 *
 * <p>A state folder that exists is left as it is (the personal edition trusts the PC); only the folder, not the log folder inside it, is made here: the log folder is made by
 * Logback inside a folder that only its owner can enter.
 */
final class StateFolderBeforeLogging implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    /** After the settings are loaded (ConfigData, the environment post-processors) and before the logging system is initialised ({@code LoggingApplicationListener#DEFAULT_ORDER}, 10 higher). */
    static final int ORDER = EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER + 5;

    private final PrivateStorage storage;

    StateFolderBeforeLogging(PrivateStorage storage) {
        this.storage = storage;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String configured = event.getEnvironment().getProperty("sennard.state-dir");
        if (configured == null || configured.isBlank()) {
            return; // a context with no state folder (the properties' own validation names it later)
        }
        Path folder = Path.of(configured).toAbsolutePath().normalize();
        try {
            storage.createFolder(folder);
        } catch (IOException cause) {
            throw new UncheckedIOException("The state folder " + folder + " cannot be made.", cause);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
