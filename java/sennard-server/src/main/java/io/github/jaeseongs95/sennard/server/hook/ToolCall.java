// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import tools.jackson.databind.JsonNode;

/**
 * A tool call as the host reports it.
 *
 * @param callId the host's identifier for this call; a host that gives none leaves it null
 * @param name the tool's name in the host's own surface
 * @param sennardTool the local tool name when the call is to a Sennard tool, otherwise null; whether it is, is the adapter's knowledge of its host's naming
 * @param input the tool's arguments
 */
public record ToolCall(String callId, String name, String sennardTool, JsonNode input) {
}
