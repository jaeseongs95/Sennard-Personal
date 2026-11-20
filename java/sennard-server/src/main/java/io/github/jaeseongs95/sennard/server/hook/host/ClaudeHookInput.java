// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Flag;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Marker;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Section;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Text;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What Claude Code writes for a hook (code.claude.com/docs/en/hooks, common input fields and each event's own). {@code agent_id} is there only inside a
 * subagent, and {@code agent_type} in a subagent and in every hook of a session started with {@code --agent}: whether they are there at all is what
 * decides who made the call, so both are {@link Marker}s.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeHookInput(@Text String sessionId, @Text String transcriptPath, @Text String cwd, @Text String workspaceId, @Text String model, @Section Effort effort,
        @Marker String agentId, @Marker String agentType, @Text String turnId, @Text String prompt, @Text String source, @Text String reason, @Text String toolName,
        @Text String toolUseId, JsonNode toolInput, JsonNode toolResponse, JsonNode error, @Flag Boolean stopHookActive) implements SessionIdInput {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Effort(@Text String level) {
    }
}
