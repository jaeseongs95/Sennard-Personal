// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Refuses a tool call that carries an argument its schema does not declare: the schema generator does not emit
 * {@code additionalProperties:false} and the tool callback only takes the names it knows, so an unknown argument would be dropped in
 * silence. The check sits on the tool specifications the starter publishes and reads the published schema, so every tool is covered
 * without each one declaring a {@code CallToolRequest} parameter and without an advice around it.
 *
 * <p>Why not an {@code @Aspect}: Spring AOP advises the calls of a bean's methods through a proxy; the specifications are records the starter builds and publishes as the
 * elements of one {@code List} bean, whose handler is a lambda, so there is no bean method to advise. An advice on the {@code @McpTool} methods would see the arguments only after
 * the callback dropped the ones it does not know, which is the very thing this check has to see. A post processor on the list is Spring's own extension point for decorating
 * what a library builds.
 */
final class UndeclaredArgumentGuard implements BeanPostProcessor {
    /** Asked on the first refusal, not now: this post processor is made before the application's beans can be injected. The refusal is answered by the one place that makes a tool's result. */
    private final ObjectProvider<SennardToolResults> results;

    UndeclaredArgumentGuard(ObjectProvider<SennardToolResults> results) {
        this.results = results;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty()) {
            if (list.getFirst() instanceof McpStatelessServerFeatures.SyncToolSpecification) {
                return list.stream().map(McpStatelessServerFeatures.SyncToolSpecification.class::cast).map(this::guarded).toList();
            }
            if (list.getFirst() instanceof McpServerFeatures.SyncToolSpecification) {
                return list.stream().map(McpServerFeatures.SyncToolSpecification.class::cast).map(this::guarded).toList();
            }
        }
        return bean;
    }

    private McpStatelessServerFeatures.SyncToolSpecification guarded(McpStatelessServerFeatures.SyncToolSpecification specification) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(specification.tool())
                .callHandler((context, request) -> refusal(specification.tool(), request)
                        .orElseGet(() -> envelope(specification.callHandler().apply(
                                key -> McpConfiguration.TOOL_REQUEST.equals(key) ? request : context.get(key), request))))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification guarded(McpServerFeatures.SyncToolSpecification specification) {
        return McpServerFeatures.SyncToolSpecification.builder().tool(specification.tool())
                .callHandler((exchange, request) -> refusal(specification.tool(), request)
                        .orElseGet(() -> specification.callHandler().apply(exchange, request)))
                .build();
    }

    /** Looks at each argument name once and builds the sorted set only for a call that has an undeclared one. */
    private Optional<McpSchema.CallToolResult> refusal(McpSchema.Tool tool, McpSchema.CallToolRequest request) {
        if (request.arguments() == null || request.arguments().isEmpty()) {
            return Optional.empty();
        }
        Set<String> undeclared = null;
        Map<?, ?> declared = tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> properties ? properties : Map.of();
        for (String name : request.arguments().keySet()) {
            if (!declared.containsKey(name)) {
                if (undeclared == null) {
                    undeclared = new TreeSet<>();
                }
                undeclared.add(name);
            }
        }
        return undeclared == null ? Optional.empty() : Optional.of(refused(InputCode.UNDECLARED_ARGUMENT, SennardException.args("arguments", undeclared.toString())));
    }

    /** The refusal is the envelope every tool answers with: a client that reads {@code error.code} sees the same shape as a refusal from inside a tool. */
    private McpSchema.CallToolResult refused(InputCode reason, Map<String, Object> arguments) {
        return results.getObject().failure(new InvalidInputException(reason, arguments));
    }

    /** An argument the callback could not convert fails before the tool body; it is the caller's input and answers as one. */
    private McpSchema.CallToolResult envelope(McpSchema.CallToolResult result) {
        if (!Boolean.TRUE.equals(result.isError()) || result.content().isEmpty()
                || !(result.content().getFirst() instanceof McpSchema.TextContent text) || text.text().startsWith("{")) {
            return result;
        }
        return refused(InputCode.ARGUMENT_CONVERSION_FAILED, Map.of());
    }
}
