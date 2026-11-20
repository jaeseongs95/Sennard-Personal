// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.jaeseongs95.sennard.server.hook.HostId;

/**
 * Where one host keeps its installed copies of the Sennard plugin, which are the files the host reads its hook definition from. Host knowledge
 * stays in an implementation of this interface; the installer only matches hosts.
 */
public interface HostPackageLocation {
    /** The host this location belongs to: the host of the adapter whose definition is placed in it. */
    HostId host();

    /** Every installed copy of the Sennard plugin this host loads for this user, of any version; empty when there is none. */
    List<InstalledPackage> packages(InstallEnvironment environment) throws IOException;

    /**
     * One installed copy.
     *
     * @param root the plugin's root folder
     * @param version the plugin version the host records for it, empty when unknown
     * @param hostCopy true for a copy the host made for itself; false for a folder the host loads in place, which is the user's own and
     *     is not written
     */
    record InstalledPackage(Path root, String version, boolean hostCopy) {
    }
}
