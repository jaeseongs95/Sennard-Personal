// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.nio.file.Path;

import tools.jackson.databind.JsonNode;

/**
 * What an installation writes into a host's copy of the plugin so that the host runs Sennard: a hook definition and the registration of the MCP server. Both are the
 * installation's own because they name its Java, its jar and the file where its server announces its address (see {@link HookInstallation}); a plugin package ships them empty
 * ({@link HostHookAdapter#shippedFiles()}). A host keeps them in a file of its own or as one member of a file that holds more, and the two ways are written differently, so the
 * form is part of the type.
 */
public sealed interface HostRegistrationFile {
    /** Where the file lives under the root of the host's package. */
    Path relativePath();

    /** What is written: the file's whole content for a {@link WholeFile}, the value of the one member for a {@link Member}, as the tree a file writer renders. */
    JsonNode content();

    /**
     * A file Sennard owns whole: the writer replaces the file with the content, and the shipped form of it is the same file with nothing registered.
     *
     * @param relativePath where the file lives under the package root
     * @param content the definition: what the adapter's definition record writes as JSON
     */
    record WholeFile(Path relativePath, JsonNode content) implements HostRegistrationFile {
    }

    /**
     * One member of a file that the host's own manifest owns (the MCP servers of Gemini CLI's {@code gemini-extension.json}, which also holds the extension's name and version): the
     * writer sets that member and leaves the rest of the file as it is, and restores the member when the installation is removed.
     *
     * @param relativePath where the file lives under the package root
     * @param jsonPointer the member's place in the file as RFC 6901 writes it, such as {@code /mcpServers}
     * @param content the value of that member
     */
    record Member(Path relativePath, String jsonPointer, JsonNode content) implements HostRegistrationFile {
    }
}
