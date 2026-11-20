// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.personal.OwnerOnlyAccess;

/**
 * The state folder holds the server's log file, and Logback makes the missing folders of a log file's path with the default access when the logging system starts, before any bean:
 * the folder is made first, through the port, by the listener that runs between the loading of the settings and the logging system (D-0268).
 */
class StateFolderBeforeLoggingTest {
    @TempDir
    Path temporary;

    private static ApplicationEnvironmentPreparedEvent event(Map<String, Object> settings) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", settings));
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment);
    }

    @Test
    void aStateFolderThatIsNotThereIsMadePrivateToItsOwnerBeforeLogging() throws Exception {
        Path state = temporary.resolve("home").resolve(".sennard");

        new StateFolderBeforeLogging(new LocalPrivateStorage()).onApplicationEvent(event(Map.of("sennard.state-dir", state.toString())));

        OwnerOnlyAccess.assertOwnerOnlyFolder(state);
        OwnerOnlyAccess.assertOwnerOnlyFolder(state.getParent());
        assertFalse(Files.exists(state.resolve("logs")), "only the state folder is made here: the log folder is Logback's, inside a folder that only its owner can enter");
    }

    @Test
    void thePathIsReadWithItsPlaceholdersTheWayTheServerReadsIt() throws Exception {
        Path home = temporary.resolve("profile");
        new StateFolderBeforeLogging(new LocalPrivateStorage()).onApplicationEvent(event(Map.of("USERPROFILE", home.toString(), "sennard.state-dir", "${USERPROFILE}/.sennard")));

        OwnerOnlyAccess.assertOwnerOnlyFolder(home.resolve(".sennard"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aStateFolderThatExistsIsLeftAsItIs() throws Exception {
        Path state = Files.createDirectory(temporary.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));

        new StateFolderBeforeLogging(new LocalPrivateStorage()).onApplicationEvent(event(Map.of("sennard.state-dir", state.toString())));

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)), "the personal edition trusts the PC: a folder that exists is not repaired");
    }

    @Test
    void aContextWithNoStateFolderSettingMakesNothing() {
        new StateFolderBeforeLogging(new LocalPrivateStorage()).onApplicationEvent(event(Map.of("unrelated", "value")));
        new StateFolderBeforeLogging(new LocalPrivateStorage()).onApplicationEvent(event(Map.of("sennard.state-dir", " ")));
    }

    /** What makes the listener work: it runs after the settings are loaded and before the logging system is initialised; a listener ordered anywhere else would make nothing before Logback does. */
    @Test
    void itRunsAfterTheSettingsAreLoadedAndBeforeTheLoggingSystemStarts() {
        int order = new StateFolderBeforeLogging(new LocalPrivateStorage()).getOrder();
        assertTrue(order > EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER, "after the settings are loaded");
        assertTrue(order < LoggingApplicationListener.DEFAULT_ORDER, "before the logging system");
    }
}
