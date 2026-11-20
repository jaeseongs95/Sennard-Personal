// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.GeminiHost;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The installed Gemini CLI extension: {@code gemini extensions install} copies it to {@code ~/.gemini/extensions/<name>}
 * (geminicli.com/docs/extensions/reference). The copy is the host's own, so this installation's hook definition is written over its {@code hooks/hooks.json}, as for the other
 * hosts that run a command; the definition the package ships runs the launcher by its bare name and so starts the whole application for every hook.
 *
 * <p>An extension that was linked ({@code gemini extensions link}) is the user's own folder that the host loads in place: it is a link or a junction, not a plain folder, and
 * it is reported as such and not written. Not verified on a machine with the Gemini CLI: that the folder has exactly this name and place, and how a link is made on each operating system.
 */
@Component
final class GeminiPackageLocation implements HostPackageLocation {
    private static final String EXTENSION = "sennard";

    /** The extension's own {@code gemini-extension.json}, the one field this class needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(String version) {
    }

    private final JsonMapper json;

    GeminiPackageLocation(JsonMapper json) {
        this.json = json;
    }

    @Override
    public HostId host() {
        return GeminiHost.ID;
    }

    /** One copy at most; its version is the one in the extension's own {@code gemini-extension.json}. */
    @Override
    public List<InstalledPackage> packages(InstallEnvironment environment) throws IOException {
        Path root = environment.home().resolve(".gemini/extensions").resolve(EXTENSION);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean plainFolder = attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
        return List.of(new InstalledPackage(root, version(root), plainFolder));
    }

    private String version(Path root) throws IOException {
        Path manifest = root.resolve("gemini-extension.json");
        if (!Files.isRegularFile(manifest)) {
            return "";
        }
        try {
            Manifest read = json.readValue(Files.readString(manifest, StandardCharsets.UTF_8), Manifest.class);
            return read == null || read.version() == null ? "" : read.version();
        } catch (JacksonException unreadable) {
            throw new IOException("Could not read " + manifest + ": " + unreadable.getMessage(), unreadable);
        }
    }
}
