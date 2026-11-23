// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The local edition's observations of host plugins, independent of the release repository. */
public interface HostPackageStatus {
    List<Mismatch> mismatches(String version);

    /** An unknown body version is reported as null. */
    record Mismatch(String host, String path, String expectedVersion, String actualVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String installation) {
    }
}
