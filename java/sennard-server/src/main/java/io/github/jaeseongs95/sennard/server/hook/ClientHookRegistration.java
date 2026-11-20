// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;

/** Supplies the native hook and MCP registrations for a host's plugin. */
public interface ClientHookRegistration {
    HostId host();

    /** The hook definition and MCP registration written for this installation. */
    List<HostRegistrationFile> registrationFiles(HookInstallation installation);

    /** Standalone registration files shipped with the plugin before installation sets its commands and paths. */
    List<HostRegistrationFile.WholeFile> shippedFiles();
}
