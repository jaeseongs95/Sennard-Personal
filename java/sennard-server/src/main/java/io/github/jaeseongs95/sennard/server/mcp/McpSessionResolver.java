// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpMeta;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Which session a tool call came from. Hosts tell it in different ways and those differences live in the adapters behind this:
 * a host may name the session in {@code _meta}, name only the call so that the row its hook left must be claimed, or say nothing at all,
 * in which case the caller holds a {@code sessionRef} from {@code open_session}.
 *
 * <p>Call this once per tool call: a claim consumes the row the hook left, so a second bind of the same call finds nothing.
 */
public interface McpSessionResolver {
    /**
     * @param tool the local tool name, as the tool is published
     * @param arguments the call's arguments for callers of the resolver outside the MCP handler. The stateless MCP handler carries
     *     the original arguments in the transport context for matching a host's call to its hook
     * @param meta the {@code _meta} of the tool call
     * @param context what the transport carried for this request
     * @param sessionRef the tool's optional {@code sessionRef} argument, null when the call has none. It is used only when the host itself names no session, because a
     *     value the model typed must never override what the host observed
     * @return the session, or why there is none; a contradiction in what the host sent is a {@code BINDING_INVALID} failure instead
     */
    SessionBinding bind(String tool, Map<String, Object> arguments, McpMeta meta, McpTransportContext context, String sessionRef);
}
