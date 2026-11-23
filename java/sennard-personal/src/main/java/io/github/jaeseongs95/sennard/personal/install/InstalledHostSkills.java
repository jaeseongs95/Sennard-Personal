// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostInstalledSkills;

/**
 * The skills of the host's installed copies of the plugin, found through the host's own {@link HostPackageLocation}: the installer and the observation of skill use ask the same
 * place where a host keeps the plugin. Each skill's folder is under the package's {@code skills/} folder ({@code .codex-plugin/plugin.json}: {@code "skills": "./skills/"}). A host with
 * no location, or whose location cannot be read, has no skill folder here.
 */
@Component
final class InstalledHostSkills implements HostInstalledSkills {
    private static final Logger LOG = LoggerFactory.getLogger(InstalledHostSkills.class);
    private static final String SKILLS = "skills";

    private final List<HostPackageLocation> locations;
    private final Supplier<InstallEnvironment> environment;

    @Autowired
    InstalledHostSkills(List<HostPackageLocation> locations) {
        this(locations, InstallEnvironment::current);
    }

    InstalledHostSkills(List<HostPackageLocation> locations, Supplier<InstallEnvironment> environment) {
        this.locations = List.copyOf(locations);
        this.environment = environment;
    }

    /**
     * The folders of a host, found once and kept: where a host keeps its copies is not a question for every tool call. A host's copies change when Sennard is installed or upgraded,
     * which stops the running server and starts the new one ({@code Installer}), so a server that has found them keeps them for its life. Only a found result is kept: a host with no
     * copy yet, or whose location could not be read, is asked again at the next skill file read, which is the only time this is asked.
     */
    private final Map<HostId, List<Path>> found = new ConcurrentHashMap<>();

    @Override
    public List<Path> skillFolders(HostId host) {
        List<Path> kept = found.get(host);
        if (kept != null) {
            return kept;
        }
        List<Path> resolved = resolve(host);
        if (!resolved.isEmpty()) {
            found.put(host, resolved);
        }
        return resolved;
    }

    private List<Path> resolve(HostId host) {
        for (HostPackageLocation location : locations) {
            if (!location.host().equals(host)) {
                continue;
            }
            try {
                return location.scan(environment.get()).packages().stream().map(installed -> installed.root().resolve(SKILLS).toAbsolutePath().normalize()).toList();
            } catch (IOException unreadable) {
                LOG.warn("Where {} keeps the Sennard plugin could not be read, so its skill reads are not counted: {}", host, unreadable.getMessage(), unreadable);
                return List.of();
            }
        }
        return List.of();
    }
}
