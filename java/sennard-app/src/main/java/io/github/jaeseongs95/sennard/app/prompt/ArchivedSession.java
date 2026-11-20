// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

/** A session as the archive knows it: the host's name for itself and the host's own session identifier, the pair every prompt of the session is stored under. */
public record ArchivedSession(String host, String sessionId) {
}
