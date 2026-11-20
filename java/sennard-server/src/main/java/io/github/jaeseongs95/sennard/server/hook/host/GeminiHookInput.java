// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Flag;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Section;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Text;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** What Gemini CLI writes for a hook on stdin (geminicli.com/docs/hooks/reference): the fields every event has, then the event's own. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiHookInput(@Text String sessionId, @Text String cwd, @Text String transcriptPath, @Text String source, @Text String reason, @Text String prompt,
        @Flag Boolean stopHookActive, @Section LlmRequest llmRequest, @Text String toolName, JsonNode toolInput, @Section McpContext mcpContext,
        @Section ToolResponse toolResponse) {

    /** Only the model: a model request's {@code messages} (the whole conversation, megabytes) and {@code config} are not read, so they are not here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LlmRequest(@Text String model) {
    }

    /** The MCP server a tool call is to and the tool's name there. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpContext(@Text String serverName, @Text String toolName) {
    }

    /** What a tool returned: {@code llmContent} (a text or parts), {@code returnDisplay}, and an {@code error} when it failed; any of them as the tool writes it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResponse(JsonNode llmContent, JsonNode error) {
    }
}
