// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Publishes the workflow tools with their contract schemas in place of the schema generated from the Java method, which only says that the
 * method takes a raw request. Ordered, so the undeclared-argument check (unordered) reads the published contract schema and not the generated one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SennardProperties.class)
public class WorkflowToolConfiguration {
    /** Declared by its own type: the container sorts post-processors before creating them, by what the factory method says it returns. */
    // @Bean: a static factory method, so that the container makes this BeanPostProcessor before any other bean (a component would be made with the beans it post-processes).
    @Bean
    static Publisher workflowToolPublisher(ObjectProvider<WorkflowToolSchemas> schemas) {
        return new Publisher(schemas);
    }

    static final class Publisher implements BeanPostProcessor, Ordered {
        private final ObjectProvider<WorkflowToolSchemas> schemas;

        Publisher(ObjectProvider<WorkflowToolSchemas> schemas) {
            this.schemas = schemas;
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof List<?> list && !list.isEmpty()) {
                if (list.getFirst() instanceof McpStatelessServerFeatures.SyncToolSpecification) {
                    Map<String, WorkflowToolSchemas.Published> published = schemas.getObject().published();
                    List<McpStatelessServerFeatures.SyncToolSpecification> items = list.stream().map(McpStatelessServerFeatures.SyncToolSpecification.class::cast).map(item -> {
                        WorkflowToolSchemas.Published tool = published.get(item.tool().name());
                        return tool == null ? item : McpStatelessServerFeatures.SyncToolSpecification.builder().tool(replaced(item.tool(), tool)).callHandler(item.callHandler()).build();
                    }).toList();
                    return withKindTools(items, published);
                }
                if (list.getFirst() instanceof McpServerFeatures.SyncToolSpecification) {
                    Map<String, WorkflowToolSchemas.Published> published = schemas.getObject().published();
                    return list.stream().map(McpServerFeatures.SyncToolSpecification.class::cast).map(item -> {
                        WorkflowToolSchemas.Published tool = published.get(item.tool().name());
                        return tool == null ? item : McpServerFeatures.SyncToolSpecification.builder().tool(replaced(item.tool(), tool)).callHandler(item.callHandler()).build();
                    }).toList();
                }
            }
            return bean;
        }

        /**
         * One tool for every {@link WorkflowKind}: the change's tool is the annotated {@code start_workflow}, whose handler serves every kind (it reads the kind from the name the call
         * carries), so the tool of each other kind is that tool's specification under the kind's own name, with the kind's published schema and description. A kind added to the enum is
         * registered here with nothing else to write.
         */
        private static List<McpStatelessServerFeatures.SyncToolSpecification> withKindTools(List<McpStatelessServerFeatures.SyncToolSpecification> items, Map<String, WorkflowToolSchemas.Published> published) {
            java.util.Optional<McpStatelessServerFeatures.SyncToolSpecification> found = items.stream().filter(item -> WorkflowKind.CHANGE.toolName().equals(item.tool().name())).findFirst();
            if (found.isEmpty()) {
                return items;
            }
            McpStatelessServerFeatures.SyncToolSpecification template = found.get();
            List<McpStatelessServerFeatures.SyncToolSpecification> all = new java.util.ArrayList<>(items);
            for (WorkflowKind kind : WorkflowKind.values()) {
                WorkflowToolSchemas.Published tool = published.get(kind.toolName());
                if (kind != WorkflowKind.CHANGE) {
                    if (tool == null) {
                        throw new IllegalStateException("The workflow kind " + kind + " has no published tool schema: add it to WorkflowToolSchemas.");
                    }
                    all.add(McpStatelessServerFeatures.SyncToolSpecification.builder().tool(replaced(template.tool(), kind.toolName(), tool)).callHandler(template.callHandler()).build());
                }
            }
            return List.copyOf(all);
        }

        private static McpSchema.Tool replaced(McpSchema.Tool tool, String name, WorkflowToolSchemas.Published published) {
            return McpSchema.Tool.builder(name, published.inputSchema()).title(tool.title()).description(published.description()).annotations(tool.annotations()).build();
        }

        private static McpSchema.Tool replaced(McpSchema.Tool tool, WorkflowToolSchemas.Published published) {
            // The nature of a tool (read-only, idempotent, destructive, open-world) is what its @McpTool declares; only the schema and the description are replaced here.
            return McpSchema.Tool.builder(tool.name(), published.inputSchema()).title(tool.title()).description(published.description()).annotations(tool.annotations()).build();
        }
    }
}
