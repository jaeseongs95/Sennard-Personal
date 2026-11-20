// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.glossary.KoreanProseGlossary.LookupRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** The Korean prose glossary tool. */
@Component
public class GlossaryTools {
    private final KoreanProseGlossary glossary;
    private final ToolSupport support;

    public GlossaryTools(KoreanProseGlossary glossary, ToolSupport support) {
        this.glossary = glossary;
        this.support = support;
    }

    @McpTool(name = "lookup_korean_prose_terms", description = "tools.lookup_korean_prose_terms.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult lookup(@McpToolParam(required = true, description = "Always 1.0.0") String schemaVersion,
            @McpToolParam(required = true, description = "The Korean text to look up") String sourceText,
            @McpToolParam(required = true, description = "The hex SHA-256 of sourceText in UTF-8") String sourceDigest) {
        return support.run(() -> glossary.lookup(new LookupRequest(schemaVersion, sourceText, sourceDigest)));
    }
}
