// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

/**
 * Marks the package of the host adapters, for a {@code @ComponentScan(basePackageClasses = ...)} that wants all of them and no host in particular (the
 * marker Spring's own documentation advises for this attribute): a new host adapter in this package is found without a scan naming it.
 */
public interface HostAdapterPackage {
}
