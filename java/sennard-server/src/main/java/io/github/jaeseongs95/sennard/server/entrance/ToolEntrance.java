// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.entrance;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.app.support.PersistenceErrors;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The entrance of an MCP tool of a rewritten area (measured by McpToolInputShapeTest). A tool is a {@code SyncToolSpecification}: its arguments are
 * flat, as the public contract is, and Jackson turns them into the tool's input record ({@code convertValue}); a record parameter of an {@code @McpTool} method
 * would be published as one object named by the parameter and the contract's flat keys would be refused. What every tool does around its handler is here and nowhere
 * else: the session, the input record and its declared constraints, the handler, and the result envelope with the one
 * complete {@code switch} over the error categories ({@link SennardToolResults}).
 *
 * <p>The input schema a model reads is the contract file's ({@code contracts/<tool>-request.v1.schema.json} with the optional {@code sessionRef}), published and
 * enforced by {@code ContractGuard} for the tools in its {@code OwnTool}; the schema given here is the placeholder that guard replaces, and
 * {@code ToolEntranceTest} fails when an entrance tool is not one of them.
 *
 * <p>The session binding ({@code SessionBinding.require()} throws a {@code BindingException} with the published code {@code BINDING_REQUIRED}) and the identity code that the tools of
 * the board and the messaging call throw {@code SennardException}s like every other refusal, answered by {@link SennardToolResults}. The tools of the other areas (context, continuity, skills, trust, workflow, updates) do not go through this entrance yet:
 * they run through {@code ToolCalls} or {@code ToolSupport} and answer by the same {@link SennardToolResults}.
 */
@Component
public class ToolEntrance {
    private static final Logger LOG = LoggerFactory.getLogger(ToolEntrance.class);
    /** The argument every session tool takes besides its own; the session binding consumes it, the input record has no such component. */
    static final String SESSION_REF = "sessionRef";

    @FunctionalInterface
    public interface Handler<I> {
        Object handle(SelfSession session, I input) throws Exception;
    }

    /**
     * The handler of a tool that works with or without a session: it is handed the binding as the call made it (a session, or why there is none) and asks for the session itself
     * ({@link SessionBinding#require()}) where it needs one.
     */
    @FunctionalInterface
    public interface BindingHandler<I> {
        Object handle(SessionBinding binding, I input) throws Exception;
    }

    private final SennardToolResults results;
    private final ToolSupport legacy;
    private final Validator validator;
    private final MessageSource messages;
    private final JsonMapper inputMapper;

    public ToolEntrance(SennardToolResults results, ToolSupport legacy, Validator validator, MessageSource messages, JsonMapper mapper) {
        this.results = results;
        this.legacy = legacy;
        this.validator = validator;
        this.messages = messages;
        // An argument the record does not declare is a mistake of the caller, not something to drop.
        this.inputMapper = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /**
     * @param name the tool's public name
     * @param input the record the arguments become
     * @param annotations the MCP hints (read-only, idempotent, ...)
     */
    public <I> McpStatelessServerFeatures.SyncToolSpecification tool(String name, Class<I> input, McpSchema.ToolAnnotations annotations, Handler<I> handler) {
        return specification(name, input, annotations, true, (binding, in) -> handler.handle(binding.require(), in));
    }

    /**
     * A tool whose call may come without a session (a client outside the model that holds the id of what it continues): the binding is not required before the handler, which decides
     * what in its input needs the session.
     */
    public <I> McpStatelessServerFeatures.SyncToolSpecification toolWithOptionalSession(String name, Class<I> input, McpSchema.ToolAnnotations annotations, BindingHandler<I> handler) {
        return specification(name, input, annotations, false, handler);
    }

    private <I> McpStatelessServerFeatures.SyncToolSpecification specification(String name, Class<I> input, McpSchema.ToolAnnotations annotations, boolean sessionRequired,
            BindingHandler<I> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of("type", "object")).description(messages.getMessage("tools." + name + ".description", null, name, Locale.ENGLISH))
                .annotations(annotations).build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> call(name, input, sessionRequired, handler, request, context)).build();
    }

    private <I> McpSchema.CallToolResult call(String name, Class<I> inputType, boolean sessionRequired, BindingHandler<I> handler, McpSchema.CallToolRequest request,
            io.modelcontextprotocol.common.McpTransportContext context) {
        McpMeta meta = new McpMeta(request.meta() == null ? Map.of() : request.meta());
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            SessionBinding binding = bind(name, arguments, meta, context);
            if (sessionRequired) {
                binding.require();
            }
            I input = input(inputType, arguments);
            return results.success(handler.handle(binding, input));
        } catch (SennardException failure) {
            return results.failure(failure);
        } catch (Exception failure) {
            // Boundary (MCP tool entrance): the one place a tool call's failure becomes the result the host reads; a persistence failure is translated to an SennardException,
            // anything else is logged with its cause and answered as a fixed server failure.
            if (failure instanceof RuntimeException runtime && PersistenceErrors.translate(runtime) instanceof SennardException sennard) {
                return results.failure(sennard);
            }
            LOG.warn("TOOL_CALL_FAILED {}", name, failure);
            return results.unexpected(failure);
        }
    }

    private SessionBinding bind(String name, Map<String, Object> arguments, McpMeta meta, io.modelcontextprotocol.common.McpTransportContext context) {
        Object sessionRef = arguments.get(SESSION_REF);
        return legacy.bind(name, arguments, meta, context, sessionRef instanceof String text ? text : null);
    }

    private <I> I input(Class<I> type, Map<String, Object> arguments) {
        Map<String, Object> own = arguments;
        if (arguments.containsKey(SESSION_REF)) {
            own = new LinkedHashMap<>(arguments);
            own.remove(SESSION_REF);
        }
        I input;
        try {
            input = inputMapper.convertValue(own, type);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new InvalidInputException(InputCode.CONTRACT_MISMATCH, SennardException.args("name", type.getSimpleName()), SennardException.args("cause", unreadable.getClass().getSimpleName()));
        }
        Set<ConstraintViolation<I>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new jakarta.validation.ConstraintViolationException(violations);
        }
        return input;
    }
}
