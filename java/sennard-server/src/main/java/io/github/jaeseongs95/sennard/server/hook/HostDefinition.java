// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import tools.jackson.databind.json.JsonMapper;

/**
 * What a host contributes to the code that runs without a Spring context: the Sennard hook client and the repository tools (they generate the empty hook definition each plugin
 * package ships). Inside the server a host is its adapter beans, found by component scan; outside it a host is one of these, found by {@link java.util.ServiceLoader}
 * (the JDK's own way to find implementations without a container).
 * A host is added with its adapter beans and one implementation of this interface, named in {@code META-INF/services/} of the module that holds it; a test
 * holds the two sets together.
 *
 * <p>An implementation has a public constructor without parameters (ServiceLoader's rule) and holds the host's one {@link HostId}: the adapters of the host
 * read their names from it, so a name is written nowhere else.
 */
public interface HostDefinition {
    /** The host's names. */
    HostId host();

    /**
     * The record the host's hook input is read into: the fields the server reads of it. The hook client sends a command hook's stdin cut down to exactly these fields, so a
     * host's whole input (a model request's conversation, megabytes) does not travel to the server for the one field read of it; the client has no way to load this record,
     * so its list of the fields is the file {@code sennard-hook-input/<label>.fields} of its module, which a test holds to this record. It is the record the host's
     * {@link HostHookAdapter#inputType() adapter} binds.
     */
    Class<?> inputType();

    /** The adapter that answers this host's hooks, made without a container: it needs only the mapper it reads and writes JSON with. */
    HostHookAdapter hookAdapter(JsonMapper mapper);

    /**
     * Where the plugin package that ships this host's hook definition is, in the repository: a folder of it, empty for the repository's root. The definition's
     * own place in the package is {@link HostRegistrationFile#relativePath()}.
     */
    String packageDirectory();
}
