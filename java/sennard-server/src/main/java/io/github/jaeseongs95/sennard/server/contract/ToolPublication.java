// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a tool's annotation cannot say about its published form: the catalog description (a tool declares its {@code tools.*} key, because the
 * sentence is reviewed in {@code locales/en/tools.json}); the arguments that may be null, which the schema generator has no way to mark; and the
 * whole input schema of a tool whose nested objects are refused on any unknown key; the {@code pattern} of the operationKey of every tool that takes the key
 * ({@link #operationKeyShape}); and the order inside each published tool-specification list, which is by tool name. The order between those lists follows the order the list beans
 * were registered in, and the tools/list golden pins it. Runs before the argument
 * guard, which reads this result.
 */
@Component
public final class ToolPublication implements BeanPostProcessor, PriorityOrdered, BeanFactoryAware {
    private static final String KEY_PREFIX = "tools.";
    private static final Map<String, Set<String>> NULLABLE = Map.of("checkpoint_global_context", Set.of("expectedStateToken"), "checkpoint_role_context", Set.of("expectedStateToken"),
            "bind_session_role", Set.of("role"));
    private static final Set<String> PUBLISHED_SCHEMAS = Set.of("classify_skills", "record_skill_selection");

    private ObjectProvider<JsonMapper> mapper;
    private ObjectProvider<Messages> messages;

    /** The sentences are made by the message source, which is not there yet when this post processor is made (it is made before constructor injection exists), so it is asked for when the first tool is published. */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.messages = beanFactory.getBeanProvider(Messages.class);
        this.mapper = beanFactory.getBeanProvider(JsonMapper.class);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty()) {
            if (list.getFirst() instanceof McpStatelessServerFeatures.SyncToolSpecification) {
                return list.stream().map(McpStatelessServerFeatures.SyncToolSpecification.class::cast).sorted(Comparator.comparing(spec -> spec.tool().name()))
                        .map(spec -> McpStatelessServerFeatures.SyncToolSpecification.builder().tool(published(spec.tool())).callHandler(spec.callHandler()).build()).toList();
            }
            if (list.getFirst() instanceof McpServerFeatures.SyncToolSpecification) {
                return list.stream().map(McpServerFeatures.SyncToolSpecification.class::cast).sorted(Comparator.comparing(spec -> spec.tool().name()))
                        .map(spec -> McpServerFeatures.SyncToolSpecification.builder().tool(published(spec.tool())).callHandler(spec.callHandler()).build()).toList();
            }
        }
        return bean;
    }

    private McpSchema.Tool published(McpSchema.Tool tool) {
        String description = tool.description();
        boolean cataloged = description != null && description.startsWith(KEY_PREFIX);
        String sentence = cataloged ? messages.getObject().text(description) : description;
        if (cataloged && sentence.equals(description)) { // no such key: the annotation's own text is published
            cataloged = false;
        }
        Map<String, Object> declared = cataloged ? (PUBLISHED_SCHEMAS.contains(tool.name()) ? schemaOf(tool.name()) : nullable(tool.name(), tool.inputSchema())) : tool.inputSchema();
        Map<String, Object> schema = operationKeyShape(declared);
        if (!cataloged && schema == tool.inputSchema()) {
            return tool;
        }
        return McpSchema.Tool.builder(tool.name(), schema).title(tool.title()).description(sentence).outputSchema(tool.outputSchema())
                .annotations(tool.annotations()).meta(tool.meta()).build();
    }

    /**
     * The shape of the operationKey, published as the schema's {@code pattern} for every tool that takes the key: the annotation of a parameter has no way to say it, and the
     * pattern is the one the server refuses with ({@link OperationKey#SHAPE}), so the schema and the refusal cannot differ. A schema that already says a pattern is left as it is.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> operationKeyShape(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> declared) || !(declared.get(OperationKey.FIELD) instanceof Map<?, ?> key) || key.containsKey("pattern")) {
            return schema;
        }
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) declared);
        Map<String, Object> shaped = new LinkedHashMap<>((Map<String, Object>) key);
        shaped.put("pattern", OperationKey.SHAPE.pattern());
        properties.put(OperationKey.FIELD, shaped);
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("properties", properties);
        return result;
    }

    private Map<String, Object> schemaOf(String tool) {
        try (InputStream in = new ClassPathResource("tool-schemas/" + tool + ".json").getInputStream()) {
            return mapper.getObject().readValue(in,new TypeReference<Map<String, Object>>() { });
        } catch (IOException missing) {
            throw new IllegalStateException("The published schema of " + tool + " is missing from the jar.", missing);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullable(String tool, Map<String, Object> schema) {
        Set<String> names = NULLABLE.get(tool);
        if (names == null || !(schema.get("properties") instanceof Map<?, ?> declared)) {
            return schema;
        }
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) declared);
        for (String name : names) {
            if (properties.get(name) instanceof Map<?, ?> property) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) property);
                copy.put("type", List.of("string", "null"));
                properties.put(name, copy);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("properties", properties);
        return result;
    }
}
