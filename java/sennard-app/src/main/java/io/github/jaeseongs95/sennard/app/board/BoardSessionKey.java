// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.board;

/** The key of a board row: the host, then the host's own session. */
public record BoardSessionKey(String host, String sessionId) {
}
