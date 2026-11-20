// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.AntigravityHost;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The installed Antigravity plugin: {@code agy plugin install} copies it to {@code ~/.gemini/config/plugins/<name>}. */
@Component
final class AntigravityPackageLocation implements HostPackageLocation {
    private static final String PLUGIN = "sennard";

    /** The package's own {@code plugin.json}, the one field this class needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(String version) {
    }

    private final JsonMapper json;

    AntigravityPackageLocation(JsonMapper json) {
        this.json = json;
    }

    @Override
    public HostId host() {
        return AntigravityHost.ID;
    }

    /** One copy at most; its version is the one in the package's own {@code plugin.json}. */
    @Override
    public List<InstalledPackage> packages(InstallEnvironment environment) throws IOException {
        Path root = environment.home().resolve(".gemini/config/plugins").resolve(PLUGIN);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Path manifest = root.resolve("plugin.json");
        String version = "";
        if (Files.isRegularFile(manifest)) {
            try {
                Manifest read = json.readValue(Files.readString(manifest, StandardCharsets.UTF_8), Manifest.class);
                version = read == null || read.version() == null ? "" : read.version();
            } catch (JacksonException unreadable) {
                throw new IOException("Could not read " + manifest + ": " + unreadable.getMessage(), unreadable);
            }
        }
        return List.of(new InstalledPackage(root, version, true));
    }
}
