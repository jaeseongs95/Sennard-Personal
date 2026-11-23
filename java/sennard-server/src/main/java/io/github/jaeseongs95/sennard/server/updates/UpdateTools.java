// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** The update check tool. */
@Component
public class UpdateTools {
    private final PluginUpdateService updates;
    private final UpdateCheckSchedule schedule;
    private final ToolSupport support;

    private final ObjectProvider<HostPackageStatus> hostPackages;

    public UpdateTools(PluginUpdateService updates, UpdateCheckSchedule schedule, ToolSupport support, ObjectProvider<HostPackageStatus> hostPackages) {
        this.hostPackages = hostPackages;
        this.updates = updates;
        this.schedule = schedule;
        this.support = support;
    }

    @McpTool(name = "check_for_updates", description = "tools.check_for_updates.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = false, destructiveHint = false, openWorldHint = true))
    public CallToolResult check(@McpToolParam(required = false, description = "Also ask the repository now, in the background; this answer is still the last check, so read again for the new one") Boolean force,
            CallToolRequest request) {
        return support.run(() -> {
            Map<String, Object> args = request.arguments();
            if (args != null && args.containsKey("force") && !(args.get("force") instanceof Boolean)) {
                throw new InvalidInputException(InputCode.UPDATES_ARGUMENTS_INVALID);
            }
            if (Boolean.TRUE.equals(force)) {
                schedule.now();
            }
            PluginUpdateService.Status status = updates.status();
            HostPackageStatus local = hostPackages.getIfAvailable();
            return status.withHostPackageMismatches(local == null ? List.of() : local.mismatches(status.currentVersion()));
        });
    }
}
