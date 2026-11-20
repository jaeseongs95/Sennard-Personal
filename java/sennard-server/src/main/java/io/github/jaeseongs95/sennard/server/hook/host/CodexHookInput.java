// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Flag;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Text;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What Codex writes for a hook (developers.openai.com Codex hooks), whether on a command hook's stdin or as the fields of an {@code mcp_tool} hook's input.
 * The fields are Claude Code's, with the model-request events' {@code status}. A field the event carries as null (the transcript path, a model request's error) is the
 * same as absent. {@code agent_id} is absent from every event of the main session, so the optional-agent events are command hooks, whose stdin holds the event as the host
 * wrote it ({@code mcp_tool} hooks cannot take a field that may be absent: the host fails the hook).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodexHookInput(@Text String sessionId, @Text String transcriptPath, @Text String cwd, @Text String workspaceId, @Text String model, @Text String agentId,
        @Text String turnId, @Text String prompt, @Text String source, @Text String reason, @Text String toolName, @Text String toolUseId, JsonNode toolInput,
        JsonNode toolResponse, JsonNode error, @Flag Boolean stopHookActive, @Text String status) implements SessionIdInput {
}
