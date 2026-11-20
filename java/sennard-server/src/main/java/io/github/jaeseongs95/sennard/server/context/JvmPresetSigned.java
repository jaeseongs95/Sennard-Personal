// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;

/**
 * Published inside the commit of a signed {@link AdminOp#JVM_PRESET_SET}. The JVM's options are fixed before the server (and its store) starts, so the
 * installation that owns the launcher listens for this, after the commit, and writes the preset where the launcher reads it.
 */
public record JvmPresetSigned(JvmPreset preset) {
}
