// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.board;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.constraints.NotBlank;

/**
 * The session board tools, declared through the tool entrance (docs/contributing/tool-input-mapping.md): a tool is a name, an input record, the MCP hints and a
 * handler that calls the service with the calling session. The arguments arrive flat, as the public contract has them; the record's own constraints
 * are checked by Jakarta Validation before the handler runs.
 */
@Configuration(proxyBeanMethods = false)
public class SessionBoardTools {
    /** {@code update_session_status}: {@code role} is absent when the caller keeps the role already written (the contract allows no null). */
    public record UpdateInput(String schemaVersion, @NotBlank String operationKey, @NotBlank String summary, String role) {
    }

    public record ListInput(String schemaVersion) {
    }

    // @Bean: a List of the MCP SDK's SyncToolSpecification (library types), made by the tool entrance from the declarations below.
    @Bean
    List<SyncToolSpecification> sessionBoardTools(ToolEntrance entrance, SessionBoardService service, OperationKeys keys) {
        return List.of(
                entrance.tool("update_session_status", UpdateInput.class, hints(false),
                        (session, input) -> service.update(session.host(), ToolSupport.sessionId(session), input.summary(), input.role(), keys.of("update_session_status", session, input))),
                entrance.tool("list_session_status", ListInput.class, hints(true),
                        (session, input) -> service.list(session.host(), ToolSupport.sessionId(session))));
    }

    private static ToolAnnotations hints(boolean readOnly) {
        return ToolAnnotations.builder().readOnlyHint(readOnly).idempotentHint(true).destructiveHint(false).openWorldHint(false).build();
    }
}
