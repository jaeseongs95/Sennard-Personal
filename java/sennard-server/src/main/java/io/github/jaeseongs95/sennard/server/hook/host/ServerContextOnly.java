// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

/**
 * Marks a class of the host adapters' package that needs beans only the server's own context has (an endpoint that answers hooks needs the hook pipeline), so that a
 * smaller context that scans this package for the adapters ({@link HostAdapterPackage}) leaves it out by this type and not by the name of a host.
 */
public interface ServerContextOnly {
}
