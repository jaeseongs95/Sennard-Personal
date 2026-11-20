// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties.ServerProtocol;
import org.springframework.stereotype.Component;

/**
 * The MCP endpoint is the stateless one and no setting changes that. Spring AI's own default for {@code spring.ai.mcp.server.protocol} is STREAMABLE, so a start whose
 * bound value is anything but STATELESS (the key removed from {@code application.yaml}, an {@code sennard.yaml} line, an environment variable, a command-line argument) is
 * refused: the tools are registered for the stateless transport only, and a server that quietly started on another one would show hosts a tool list that is empty.
 */
@Component
final class StatelessMcpProtocol {
    StatelessMcpProtocol(McpServerProperties mcp) {
        if (mcp.getProtocol() != ServerProtocol.STATELESS) {
            throw new IllegalStateException("spring.ai.mcp.server.protocol must be STATELESS in the personal edition.");
        }
    }
}
