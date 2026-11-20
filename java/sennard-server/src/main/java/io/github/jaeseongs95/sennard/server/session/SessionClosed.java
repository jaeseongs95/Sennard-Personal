// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

/**
 * A session's generation ended and the end is committed. A domain that keeps something alive for the session, such as a waiting peer,
 * listens for this.
 *
 * @param reason why it ended: the host's own reason, or {@code HOST_PROCESS_ENDED} when the host went away without saying so
 */
public record SessionClosed(String sessionRef, long generation, String reason) {
}
