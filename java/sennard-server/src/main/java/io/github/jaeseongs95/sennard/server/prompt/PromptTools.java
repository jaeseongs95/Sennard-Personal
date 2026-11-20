// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveReader;
import io.github.jaeseongs95.sennard.domain.transcript.Transcript;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The three tools of the prompt archive, declared through the tool entrance (docs/contributing/tool-input-mapping.md): {@code record_turn_recap}, which attaches the agent's
 * summary of its turn to the session's newest prompt, {@code recall_user_prompts}, which reads the user's words back, and {@code read_prompt_transcript}, which reads from the
 * host's own transcript what the agent did after one of those words. They go through services that read the archive, write a recap or keep a transcript position; this class
 * has no way to write a prompt, and the machine check ({@code PromptArchiveRules}) holds it to that.
 *
 * <p>The input records' components are the contract's keys (contracts/record-turn-recap-request.v1.schema.json, contracts/recall-user-prompts-request.v1.schema.json,
 * contracts/read-prompt-transcript-request.v1.schema.json); the contract itself is enforced and published by {@code ContractGuard}.
 */
@Configuration(proxyBeanMethods = false)
public class PromptTools {
    public record RecapInput(String schemaVersion, @NotNull String operationKey, @NotNull String recap) {
    }

    public record RecallInput(String schemaVersion, @Size(max = PromptArchiveReader.QUERY_MAX_LENGTH) String query, @Min(1) @Max(PromptArchiveReader.MAX_LIMIT) Integer limit,
            PromptRecall.Scope scope, String from, String to) {
    }

    /** The length of a prompt's identifier (a UUID's text). */
    static final int PROMPT_ID_MAX_LENGTH = 36;

    /** The longest key of an entry a transcript read may name as {@code after}. */
    static final int ENTRY_KEY_MAX_LENGTH = 128;

    /**
     * The promptId is the identifier {@code recall_user_prompts} gave; the limit is the most entries the transcript read answers; {@code after} is the key of the last entry of an
     * earlier answer, to read on from the entry that follows it.
     */
    public record TranscriptInput(String schemaVersion, @NotBlank @Size(max = PROMPT_ID_MAX_LENGTH) String promptId, @Min(1) @Max(Transcript.MAX_LIMIT) Integer limit,
            @Size(min = 1, max = ENTRY_KEY_MAX_LENGTH) String after) {
    }

    // @Bean: a List of the MCP SDK's SyncToolSpecification (library types), made by the tool entrance from the declarations below.
    @Bean
    List<SyncToolSpecification> promptTools(ToolEntrance entrance, PromptRecapService recaps, PromptRecall recall, PromptTranscriptService transcripts, OperationKeys keys) {
        return List.of(
                entrance.tool("record_turn_recap", RecapInput.class, hints(false), (session, input) -> recaps.record(session, input.recap(), keys.of("record_turn_recap", session, input))),
                entrance.tool("recall_user_prompts", RecallInput.class, hints(true), (session, input) -> recall.recall(session, input.query(), input.limit(), input.scope(), input.from(), input.to())),
                // Read-only for the caller: it reads the host's transcript and the archive. The one thing it writes is the position it found (transcript_anchor), a cache that changes no answer.
                entrance.tool("read_prompt_transcript", TranscriptInput.class, hints(true), (session, input) -> transcripts.read(session, input.promptId(), input.limit(), input.after())));
    }

    private static ToolAnnotations hints(boolean readOnly) {
        return ToolAnnotations.builder().readOnlyHint(readOnly).idempotentHint(true).destructiveHint(false).openWorldHint(false).build();
    }
}
