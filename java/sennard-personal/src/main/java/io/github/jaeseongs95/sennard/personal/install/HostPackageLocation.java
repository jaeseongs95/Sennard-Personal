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

    /** Identifies the installed copies this host loads and any cache candidates that prevent a selection. */
    Scan scan(InstallEnvironment environment) throws IOException;

    /** Identified copies and cache candidates whose presence prevents a selection. */
    record Scan(List<InstalledPackage> packages, List<UndeterminedPackage> undetermined) {
        public Scan {
            packages = List.copyOf(packages);
            undetermined = List.copyOf(undetermined);
        }
    }

    record UndeterminedPackage(Path root, String installation) {
    }

    /**
     * One installed copy.
     *
     * @param root the plugin's root folder
     * @param version the plugin version the host records for it, empty when unknown
     * @param hostCopy true for a copy the host made for itself; false for a folder the host loads in place, which is the user's own and
     *     is not written
     * @param installation the host's installation identifier and scope, when available
     */
    record InstalledPackage(Path root, String version, boolean hostCopy, String installation) {
    }

    /** The host advertises its version separately from the manifest of the package it actually loads. */
    interface SeparateVersionRecord extends HostPackageLocation {
        Path bodyManifest(Path root);
    }
}
