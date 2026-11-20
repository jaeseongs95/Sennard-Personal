// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.message.SennardMessageResolvable;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the published tool envelope ({@link ApiResult}, the published {@code ApiResultV1}) as a {@code CallToolResult}. A registered native
 * hook returns the host's own JSON through its hook transport. ToolEnvelopeServerTest holds other published tools to the envelope.
 *
 * <p>It is also the one place a failure becomes the result of an MCP tool. The {@code switch} over an {@link SennardException} has no {@code default}: the hierarchy is sealed, so a
 * category added to it does not compile here until it is answered. The sentence is English (the model reads it; {@code _meta} says so), made by the context's
 * message source from the code's catalog key; the Korean one is for people and is made at the HTTP boundary ({@code SennardExceptionHandler}).
 *
 * <p>Whatever nobody expected, and so is not an {@code SennardException}, is answered here too ({@link #unexpected}), so that no runner of tools has an answer of its own.
 */
@Component
public class SennardToolResults {
    private static final String CONTENT_LANGUAGE = "io.modelcontextprotocol/contentLanguage";
    /** The language the model reads. */
    static final Locale MODEL_LOCALE = Locale.ENGLISH;
    private static final String INTERNAL_FAILURE = "INTERNAL_FAILURE";

    private final MessageSource messages;
    private final JsonMapper mapper;

    public SennardToolResults(MessageSource messages, JsonMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    public McpSchema.CallToolResult success(Object data) {
        return answer(ApiResult.ok(data));
    }

    public McpSchema.CallToolResult failure(SennardException failure) {
        return answer(ApiResult.failed(switch (failure) {
            case InvalidInputException input -> body(input);
            case NotFoundException missing -> body(missing);
            case ConflictException conflict -> body(conflict);
            case GateRefusedException gate -> body(gate);
            case BindingException binding -> body(binding);
            case IntegrityException integrity -> body(integrity);
            case UnavailableException unavailable -> body(unavailable);
        }));
    }

    /** A failure nobody expected: the caller cannot fix it by changing its input, so the answer is a fixed sentence and a reason (the store's own code, or {@code INTERNAL_FAILURE}), never the original text. */
    public McpSchema.CallToolResult unexpected(Throwable cause) {
        return failure(new UnavailableException(UnavailableCode.SERVER_FAILURE, Map.of(), Map.of("reason", cause instanceof StoreException store ? store.code() : INTERNAL_FAILURE), cause));
    }

    /** The envelope's error: the code's public code, the sentence, its catalog key and arguments, and the reason (the code constant, when the contract shows it) with the details. */
    private ApiResult.ErrorBody body(SennardException failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure.code().wireReason()) {
            details.put("reasonCode", failure.code().name());
        }
        details.putAll(failure.details());
        return new ApiResult.ErrorBody(failure.code().wireCode(), messages.getMessage(SennardMessageResolvable.of(failure), MODEL_LOCALE), failure.messageKey(), failure.arguments(),
                details.isEmpty() ? null : details);
    }

    /** A failure whose body the caller has made (the workflow tools' codes, a guard's refusal): the envelope of a failed result. */
    public McpSchema.CallToolResult failure(ApiResult.ErrorBody error) {
        return answer(ApiResult.failed(error));
    }

    public McpSchema.CallToolResult answer(ApiResult result) {
        return answer(result, List.of());
    }

    /**
     * The result of a tool: the envelope as the first text content, then the notes (the lines a tool adds for the model to read after its answer) as one more. {@code isError} is the envelope's
     * own {@code ok}, so the two cannot disagree.
     */
    public McpSchema.CallToolResult answer(ApiResult result, List<String> notes) {
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder().isError(!result.ok()).addTextContent(mapper.writeValueAsString(result));
        if (!notes.isEmpty()) {
            builder.addTextContent(String.join("\n", notes));
        }
        return builder.meta(Map.of(CONTENT_LANGUAGE, MODEL_LOCALE.getLanguage())).build();
    }
}
