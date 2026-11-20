// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Count;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.FirstText;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Flag;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Section;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Text;
import tools.jackson.databind.JsonNode;

/**
 * What Antigravity writes for a hook (antigravity.google/docs/hooks): conversation identifiers instead of session identifiers, and the tool call as one
 * object.
 *
 * @param firstWorkspacePath the first of the host's {@code workspacePaths}, the only one the server uses
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AntigravityHookInput(@Text String conversationId, @JsonProperty("workspacePaths") @FirstText String firstWorkspacePath, @Text String transcriptPath,
        @Text String artifactDirectoryPath, @Text String modelName, @Text String lastUserInput, @Count Integer invocationNum, @Section ToolCall toolCall, @Text String error,
        @Text String terminationReason, @Flag Boolean fullyIdle, @Count Integer executionNum) {

    /** @param args the tool's arguments: any tool's, so the host's tool defines them */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(@Text String name, JsonNode args) {
    }

    /** The arguments of the host's wrapper tool for an MCP call: which server, which of its tools, and that tool's own arguments. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpWrapperArguments(@JsonProperty("ServerName") @Text String serverName, @JsonProperty("ToolName") @Text String toolName,
            @JsonProperty("Arguments") JsonNode arguments) {
    }
}
