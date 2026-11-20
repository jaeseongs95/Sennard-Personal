// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** The current live generation of the claiming session (instance and birth generation as the identity area minted them); a claim records its boundary against it. */
public record Generation(String instanceId, String birthGeneration) {
}
