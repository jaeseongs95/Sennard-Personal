// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

/**
 * A {@code checkpoint_role_context} call saved a role body. The tool publishes it after the save has committed, outside any transaction, so that the continuity
 * module (which comes before the context module in the global lock order, and which this package does not depend on) can note the save for the turn check of its session.
 *
 * @param host the calling session's host name, as the session list stores it
 * @param sessionId the calling session's id
 * @param agentKey the subagent's identifier, or null for the main agent
 */
public record RoleContextSaved(String host, String sessionId, String agentKey) {
}
