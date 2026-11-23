// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeHost;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The installed Claude Code plugin. Claude Code records each install in {@code installed_plugins.json} under its plugins root, with the
 * {@code installPath} it loads the plugin from and the plugin's {@code version}; a marketplace install is a copy at
 * {@code cache/<marketplace>/<plugin>/<version>/}, one per version, and a plugin of a marketplace added from a local folder is loaded in place
 * from that folder (Claude Code plugin loading reference, "Find plugins on disk"); when the record names a copy in the cache for such a plugin, the host does not read
 * that copy, so the plugin is found through {@code known_marketplaces.json} and its marketplace's manifest. The plugins root is {@code ~/.claude/plugins}, moved by
 * {@code CLAUDE_CODE_PLUGIN_CACHE_DIR}, and follows {@code CLAUDE_CONFIG_DIR} when that moves the whole configuration folder.
 */
@Component
final class ClaudePackageLocation implements HostPackageLocation.SeparateVersionRecord {
    private static final String PLUGIN = "sennard";

    /** What the host's record holds, the fields this class needs: {@code plugins} maps {@code <plugin>@<marketplace>} to its installs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstalledPlugins(Map<String, List<Install>> plugins) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Install(String installPath, String version, String scope, String projectPath) {
        String installation(String pluginId) {
            StringJoiner details = new StringJoiner(", ", " (", ")").setEmptyValue("");
            if (scope != null && !scope.isBlank()) {
                details.add("scope=" + scope);
            }
            if (projectPath != null && !projectPath.isBlank()) {
                details.add("projectPath=" + projectPath);
            }
            return pluginId + details;
        }
    }

    /**
     * One marketplace of {@code known_marketplaces.json}: {@code source} is an object whose own {@code source} says what kind it is added from ({@code directory} or
     * {@code file} for a local path), and {@code installLocation} is, for a local path, the path that was given (Claude Code plugin loading reference, "Find plugins on disk").
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Marketplace(JsonNode source, String installLocation) {
    }

    /** The plugin entries of a marketplace's {@code .claude-plugin/marketplace.json}; {@code source} is a relative path string for a plugin inside the marketplace. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketplaceFile(List<Entry> plugins) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Entry(String name, JsonNode source) {
        }
    }

    /** The kinds of source of a marketplace added from a local path, whose relative-path plugins Claude Code loads in place from the marketplace's own folder. */
    private static final Set<String> LOCAL_SOURCES = Set.of("directory", "file");

    /** A plugin with one install may be written as the install itself and not as a list of one. */
    private final ObjectReader reader;
    private final ObjectReader marketplaces;
    private final ObjectReader marketplaceFile;

    ClaudePackageLocation(JsonMapper json) {
        this.reader = json.readerFor(InstalledPlugins.class).with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        this.marketplaces = json.readerFor(new TypeReference<Map<String, Marketplace>>() {
        });
        this.marketplaceFile = json.readerFor(MarketplaceFile.class);
    }

    @Override
    public HostId host() {
        return ClaudeHost.ID;
    }

    @Override
    public Scan scan(InstallEnvironment environment) throws IOException {
        Path plugins = pluginsRoot(environment);
        Path record = plugins.resolve("installed_plugins.json");
        if (!Files.isRegularFile(record)) {
            return new Scan(List.of(), List.of());
        }
        InstalledPlugins installs;
        try {
            installs = reader.readValue(Files.readString(record, StandardCharsets.UTF_8));
        } catch (JacksonException unreadable) {
            throw new IOException("Could not read " + record + ": " + unreadable.getMessage(), unreadable);
        }
        if (installs == null || installs.plugins() == null) {
            return new Scan(List.of(), List.of());
        }
        Path cache = plugins.resolve("cache").toAbsolutePath().normalize();
        Map<Path, InstalledPackage> found = new LinkedHashMap<>();
        for (Map.Entry<String, List<Install>> plugin : installs.plugins().entrySet()) {
            if (!plugin.getKey().startsWith(PLUGIN + "@") || plugin.getValue() == null) {
                continue;
            }
            String marketplace = plugin.getKey().substring(PLUGIN.length() + 1);
            for (Install entry : plugin.getValue()) {
                if (entry == null || entry.installPath() == null || entry.installPath().isEmpty()) {
                    continue;
                }
                Path root = Path.of(entry.installPath()).toAbsolutePath().normalize();
                String version = entry.version() == null ? "" : entry.version();
                String installation = entry.installation(plugin.getKey());
                // The record may name a copy in the cache for a plugin that Claude Code does not read from there: one of a marketplace added from a local path is loaded from the
                // marketplace's own folder, and that folder is the one that holds the hook definition the host runs.
                Optional<Path> loadedInPlace = root.startsWith(cache) ? inPlaceRoot(plugins, marketplace) : Optional.empty();
                if (loadedInPlace.isPresent()) {
                    found.putIfAbsent(loadedInPlace.get(), new InstalledPackage(loadedInPlace.get(), version, false, installation));
                } else if (!Files.notExists(root)) {
                    // Keep unverified paths in the inventory so preflight still rejects an unreadable installation.
                    found.putIfAbsent(root, new InstalledPackage(root, version, root.startsWith(cache), installation));
                }
            }
        }
        return new Scan(List.copyOf(found.values()), List.of());
    }

    /**
     * The folder Claude Code loads this plugin from in place, when its marketplace was added from a local path and lists the plugin by a relative path; empty for a marketplace
     * the host fetched (it copies those plugins into the cache) and for one whose manifest is not there (nothing was installed from it that Claude Code could still read).
     *
     * @throws IOException {@code known_marketplaces.json} or the marketplace's manifest exists and cannot be read
     */
    private Optional<Path> inPlaceRoot(Path plugins, String marketplace) throws IOException {
        Path known = plugins.resolve("known_marketplaces.json");
        if (!Files.isRegularFile(known)) {
            return Optional.empty();
        }
        Map<String, Marketplace> all = read(known, marketplaces);
        Marketplace listed = all == null ? null : all.get(marketplace);
        if (listed == null || listed.source() == null || listed.installLocation() == null || !LOCAL_SOURCES.contains(listed.source().path("source").asString())) {
            return Optional.empty();
        }
        Path location = Path.of(listed.installLocation()).toAbsolutePath().normalize();
        Path manifest = Files.isRegularFile(location) ? location : location.resolve(".claude-plugin").resolve("marketplace.json");
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        // The marketplace's root is the folder that holds .claude-plugin; a relative source is written from there.
        Path base = manifest.getParent();
        if (base.getFileName() != null && ".claude-plugin".equals(base.getFileName().toString())) {
            base = base.getParent();
        }
        MarketplaceFile listing = read(manifest, marketplaceFile);
        if (listing == null || listing.plugins() == null) {
            return Optional.empty();
        }
        for (MarketplaceFile.Entry entry : listing.plugins()) {
            if (entry != null && PLUGIN.equals(entry.name()) && entry.source() != null && entry.source().isString()) {
                Path folder = base.resolve(entry.source().asString()).toAbsolutePath().normalize();
                return folder.startsWith(base) && Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static <T> T read(Path file, ObjectReader reader) throws IOException {
        try {
            return reader.readValue(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JacksonException unreadable) {
            throw new IOException("Could not read " + file + ": " + unreadable.getMessage(), unreadable);
        }
    }

    @Override
    public Path bodyManifest(Path root) {
        return root.resolve(".claude-plugin/plugin.json");
    }

    private static Path pluginsRoot(InstallEnvironment environment) {
        String cache = environment.environment().get("CLAUDE_CODE_PLUGIN_CACHE_DIR");
        if (cache != null && !cache.isBlank()) {
            return Path.of(cache);
        }
        return userSettings(environment).resolveSibling("plugins");
    }

    static Path userSettings(InstallEnvironment environment) {
        String config = environment.environment().get("CLAUDE_CONFIG_DIR");
        Path folder = config != null && !config.isBlank() ? Path.of(config) : environment.home().resolve(".claude");
        return folder.resolve("settings.json");
    }
}
