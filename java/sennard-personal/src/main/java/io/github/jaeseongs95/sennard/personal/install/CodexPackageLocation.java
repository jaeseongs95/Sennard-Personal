// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;

/**
 * The installed Codex plugin. Codex copies an installed plugin into {@code <codex home>/plugins/cache/<marketplace>/<plugin>/<version>/}, one folder per marketplace and version, and
 * reads the plugin's files from that copy, so this installation's definitions (the hooks and the MCP server, which the shipped package leaves empty because they name this PC's
 * {@code java} and jar) are written there. The codex home is {@code ~/.codex}, moved by {@code CODEX_HOME}.
 *
 * <p>Not verified on a machine with Codex: that the cache has exactly this layout and name, and that Codex does not ask the user to trust a hook definition again when a file of the copy
 * changes. The location reports what it finds only; a layout that is not found reports no copy, and {@code sennard hooks} then says the plugin is not installed.
 */
@Component
final class CodexPackageLocation implements HostPackageLocation {
    private static final String PLUGIN = "sennard";

    @Override
    public HostId host() {
        return CodexHost.ID;
    }

    /** Every version of the plugin in every marketplace of the cache; the version is the copy's folder name. */
    @Override
    public List<InstalledPackage> packages(InstallEnvironment environment) throws IOException {
        Path cache = codexHome(environment).resolve("plugins").resolve("cache");
        if (!Files.isDirectory(cache)) {
            return List.of();
        }
        List<InstalledPackage> found = new ArrayList<>();
        try (Stream<Path> marketplaces = Files.list(cache)) {
            for (Path marketplace : marketplaces.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path plugin = marketplace.resolve(PLUGIN);
                if (!Files.isDirectory(plugin)) {
                    continue;
                }
                try (Stream<Path> versions = Files.list(plugin)) {
                    versions.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString))
                            .forEach(version -> found.add(new InstalledPackage(version, version.getFileName().toString(), true)));
                }
            }
        }
        return List.copyOf(found);
    }

    private static Path codexHome(InstallEnvironment environment) {
        String configured = environment.environment().get("CODEX_HOME");
        return configured != null && !configured.isBlank() ? Path.of(configured) : environment.home().resolve(".codex");
    }
}
