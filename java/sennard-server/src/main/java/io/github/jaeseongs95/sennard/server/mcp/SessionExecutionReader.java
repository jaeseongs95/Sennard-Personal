// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

/**
 * The model behind a call, read from the host's own records, for a host whose hook did not report it. An adapter of the host that needs this
 * provides it; a host without one simply stays unobserved.
 */
public interface SessionExecutionReader {
    /** The host name the session is stored under. */
    String host();

    /**
     * May wait for the host to finish writing, so it is called before any transaction. The model when it was found; otherwise an {@link SelfSession.Execution.Unobserved} whose
     * facts say why (codes and numbers, never the host's text).
     */
    SelfSession.Execution read(SelfSession session);
}
