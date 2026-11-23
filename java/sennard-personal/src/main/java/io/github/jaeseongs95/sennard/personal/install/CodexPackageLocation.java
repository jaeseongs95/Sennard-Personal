// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.updates.StableVersion;

/**
 * Codex keeps plugin versions under {@code <codex home>/plugins/cache/<marketplace>/<plugin>/<version>/}.
 * This location selects one copy per marketplace. Codex gives {@code local} priority over versioned directories.
 * Sennard can compare numbered releases and refuses to infer a selection from other admissible version names.
 *
 * @see <a href="https://github.com/openai/codex/blob/rust-v0.171.0/codex-rs/core-plugin-common/src/installed.rs#L18-L62">Codex installed version selection</a>
 */
@Component
final class CodexPackageLocation implements HostPackageLocation.SeparateVersionRecord {
    private static final String PLUGIN = "sennard";
    private static final String LOCAL = "local";
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("[A-Za-z0-9._+\\-]+");

    @Override
    public HostId host() {
        return CodexHost.ID;
    }

    @Override
    public Scan scan(InstallEnvironment environment) throws IOException {
        Path cache = codexHome(environment).resolve("plugins").resolve("cache");
        if (!Files.isDirectory(cache)) {
            return new Scan(List.of(), List.of());
        }
        List<InstalledPackage> found = new ArrayList<>();
        List<UndeterminedPackage> undetermined = new ArrayList<>();
        try (Stream<Path> marketplaces = Files.list(cache)) {
            for (Path marketplace : marketplaces.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path plugin = marketplace.resolve(PLUGIN);
                if (!Files.isDirectory(plugin)) {
                    continue;
                }
                try (Stream<Path> versions = Files.list(plugin)) {
                    List<Path> candidates = versions.filter(CodexPackageLocation::isVersionDirectory).sorted().toList();
                    Scan selection = select(candidates, PLUGIN + "@" + marketplace.getFileName());
                    found.addAll(selection.packages());
                    undetermined.addAll(selection.undetermined());
                }
            }
        }
        return new Scan(found, undetermined);
    }

    private static boolean isVersionDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && VERSION_DIRECTORY.matcher(path.getFileName().toString()).matches();
    }

    private static Scan select(List<Path> candidates, String installation) {
        for (Path candidate : candidates) {
            if (LOCAL.equals(candidate.getFileName().toString())) {
                return new Scan(List.of(new InstalledPackage(candidate, LOCAL, true, installation)), List.of());
            }
        }
        List<UndeterminedPackage> undetermined = new ArrayList<>();
        Path selected = null;
        StableVersion latest = null;
        for (Path candidate : candidates) {
            Optional<StableVersion> parsed = StableVersion.tryParse(candidate.getFileName().toString());
            if (parsed.isEmpty()) {
                undetermined.add(new UndeterminedPackage(candidate, installation));
                continue;
            }
            StableVersion version = parsed.orElseThrow();
            if (latest == null || version.compareTo(latest) > 0) {
                latest = version;
                selected = candidate;
            }
        }
        if (selected == null || !undetermined.isEmpty()) {
            return new Scan(List.of(), undetermined);
        }
        return new Scan(List.of(new InstalledPackage(selected, selected.getFileName().toString(), true, installation)), List.of());
    }

    @Override
    public Path bodyManifest(Path root) {
        return root.resolve(".codex-plugin/plugin.json");
    }

    private static Path codexHome(InstallEnvironment environment) {
        String configured = environment.environment().get("CODEX_HOME");
        return configured != null && !configured.isBlank() ? Path.of(configured) : environment.home().resolve(".codex");
    }
}
