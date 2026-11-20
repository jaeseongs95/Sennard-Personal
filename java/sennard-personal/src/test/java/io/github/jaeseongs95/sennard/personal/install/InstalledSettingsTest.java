// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHookAdapter;

/**
 * The settings context finds the host adapters by the package marker, not by naming one of them, and gives the settings by injection: the values below are the ones the
 * installation's {@code sennard.yaml} sets, or the defaults of the records when it sets none, and {@code InstalledSettings} asks the context for no bean.
 *
 * <pre>
 * s1  adapters   the scan by the marker finds the four host adapters
 * s2  settings   a sennard.yaml sets "do not prepare the store": InstalledSettings gives it; the state folder named by the environment's SENNARD_STATE_DIR is the state folder, over the
 *                yaml's (Spring's order: the environment is above a config file), which is also what keeps a test run in its own folders
 * s3  defaults   without them: the store is prepared; application.yaml's state folder is {@code ${user.home}/.sennard} and its server port is 0 (the operating system gives the server
 *                a port at each start, so there is no port to read: a client finds the server through the address file in the state folder)
 * s4  parts      the JSON mapper, the random source and the host package locations are there
 * s5  address    the address file is server.properties in the state folder
 * </pre>
 */
class InstalledSettingsTest {
    @TempDir
    Path application;

    @Test
    void s2TheInstallationsYamlSetsWhetherTheStoreIsPreparedAndTheEnvironmentsStateFolderIsAboveIt() throws IOException {
        Path state = application.resolve("state");
        Files.writeString(application.resolve("sennard.yaml"), "sennard:\n  state-dir: " + state.toString().replace('\\', '/') + "\n  store:\n    prepare: false\n", StandardCharsets.UTF_8);
        String isolated = System.getenv("SENNARD_STATE_DIR");
        assertNotNull(isolated, "the build gives every test run its own state folder through this variable");
        try (InstalledSettings settings = InstalledSettings.at(application)) {
            assertFalse(settings.preparesStore());
            assertEquals(Path.of(isolated), settings.stateDirectory(), "the variable is above the installation's file");
        }
    }

    @Test
    void s3WithoutAnInstallationYamlTheStoreIsPrepared() {
        try (InstalledSettings settings = InstalledSettings.quietlyAt(application)) {
            assertTrue(settings.preparesStore());
        }
    }

    /**
     * The product's {@code application.yaml} read with a {@code user.home} the test gives: the state folder's default is the JVM's home (the same question on every OS, so the same
     * input gives the same folder), not the OS's variable for it, and the server's port is 0. A test run's own environment names its state folder, so the default is read from the file.
     */
    @Test
    void s3ApplicationYamlsStateFolderIsUnderTheJvmsHomeAndTheServersPortIsTheOneTheOperatingSystemGives() throws IOException {
        Path home = application.resolve("a home");
        PropertyResolver yaml = applicationYaml(home);

        assertEquals(home.resolve(".sennard"), Path.of(yaml.getRequiredProperty("sennard.state-dir")));
        assertEquals(0, yaml.getRequiredProperty("server.port", Integer.class), "no fixed port: the server binds the one the operating system gives it and announces it in its address file");
    }

    @Test
    void s4ThePartsTheCommandsUseAreThere() {
        try (InstalledSettings settings = InstalledSettings.at(application)) {
            assertNotNull(settings.jsonMapper());
            assertNotNull(settings.secureRandom());
            assertFalse(settings.hostLocations().isEmpty());
        }
    }

    @Test
    void s5TheAddressFileIsTheServerPropertiesFileOfTheStateFolder() {
        try (InstalledSettings settings = InstalledSettings.at(application)) {
            assertEquals("server.properties", ServerAddress.FILE_NAME);
            assertEquals(settings.stateDirectory().resolve("server.properties"), settings.serverAddressFile());
        }
    }

    @Test
    void theScanByThePackageMarkerFindsEveryHostAdapter() {
        try (InstalledSettings settings = InstalledSettings.at(application)) {
            Set<Class<?>> found = settings.hostAdapters().stream().map(Object::getClass).collect(Collectors.toSet());
            assertEquals(Set.of(ClaudeHookAdapter.class, CodexHookAdapter.class, AntigravityHookAdapter.class, GeminiHookAdapter.class), found);
        }
    }

    /** {@code application.yaml} of the product as Boot reads a config file, with {@code user.home} resolved to {@code home}. */
    private static PropertyResolver applicationYaml(Path home) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("test", Map.of("user.home", home.toString())));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            sources.addLast(source);
        }
        return new PropertySourcesPropertyResolver(sources);
    }
}
