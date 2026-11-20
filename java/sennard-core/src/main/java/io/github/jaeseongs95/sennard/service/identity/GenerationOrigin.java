// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

/**
 * How a session generation came to exist ({@code session_generation.origin}, holding the constant's name): a client {@code REGISTER}ed it, or a
 * tool call or hook {@code RECOVERY}-created it for a client that had not registered.
 */
public enum GenerationOrigin {
    REGISTER,
    RECOVERY
}
