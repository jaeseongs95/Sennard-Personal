// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * Which host a hook, a tool call or a stored session belongs to: the one value that travels wherever the code needs to tell hosts apart. A host's names are
 * written once, in the host's own adapter package (an adapter's {@code HostDefinition}); everything else carries this value and never a name of its own.
 *
 * <p>A host has two names because two outside forms were fixed before this type existed: the label its hooks and its MCP requests are posted under, and the
 * name its sessions are stored under (the stored one is what a session row holds, so it is not renamed). They are the same for most hosts.
 *
 * <p>{@link #toString} is the label: it is what the printed form of {@link HostContext} shows, which the adapters' golden digests compare.
 *
 * @param label the label the host's hooks and MCP requests carry; a path segment of the hook endpoint, so it has no slash and no space
 * @param sessionHost the name the host's sessions are stored under
 */
public record HostId(String label, String sessionHost) {
    /** A host that is stored under its label. */
    public HostId(String name) {
        this(name, name);
    }

    public HostId {
        if (label == null || label.isBlank() || label.chars().anyMatch(c -> c == '/' || Character.isWhitespace(c))) {
            throw new IllegalArgumentException("A host's label is a path segment of its endpoint: not empty, without a slash or a space: " + label);
        }
        if (sessionHost == null || sessionHost.isBlank()) {
            throw new IllegalArgumentException("A host's session name is not empty: " + sessionHost);
        }
    }

    /** The identity of "a host nobody described", for code that must answer for a hook whose host has no adapter of the kind it asks for. */
    public static final HostId UNDESCRIBED = new HostId("undescribed");

    @Override
    public String toString() {
        return label;
    }
}
