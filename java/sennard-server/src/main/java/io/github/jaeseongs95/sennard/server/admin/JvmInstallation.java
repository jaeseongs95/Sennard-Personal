// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.util.Optional;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;

/** What the admin page needs to know of the JVM the server was installed to start with; the installation implements it. */
public interface JvmInstallation {
    /**
     * @param preset the preset the launcher's options were last written for
     * @param observedBytes the memory the installer read when it chose the first preset, null when the preset was not chosen from a reading
     */
    record Saved(JvmPreset preset, Long observedBytes) {
    }

    /** @return empty when the installation holds no preset */
    Optional<Saved> saved();
}
