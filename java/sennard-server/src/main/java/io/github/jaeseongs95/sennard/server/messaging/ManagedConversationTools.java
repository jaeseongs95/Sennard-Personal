// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Configuration(proxyBeanMethods = false)
public class ManagedConversationTools {
    private static final String LAUNCH = "launch_managed_child";
    private static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}]+";

    public record LaunchInput(String schemaVersion, @NotNull String operationKey,
            @NotBlank @Size(max = 200) @Pattern(regexp = NO_CONTROL_CHARACTERS) String model,
            @NotBlank @Size(max = 200) @Pattern(regexp = NO_CONTROL_CHARACTERS) String effort) {
    }

    @Bean
    List<SyncToolSpecification> managedConversationTools(ToolEntrance entrance, ManagedConversationService service, OperationKeys keys) {
        return List.of(entrance.tool(LAUNCH, LaunchInput.class,
                ToolAnnotations.builder().readOnlyHint(false).idempotentHint(true).destructiveHint(false).openWorldHint(false).build(),
                (session, input) -> service.launch(session, input.model(), input.effort(),
                        keys.of(LAUNCH, session, Map.of("schemaVersion", input.schemaVersion(), "operationKey", input.operationKey(),
                                "model", input.model(), "effort", input.effort(), "rootGeneration", session.generation())))));
    }
}
