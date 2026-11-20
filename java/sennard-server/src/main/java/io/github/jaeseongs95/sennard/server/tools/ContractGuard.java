// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.SchemaLocale;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes and enforces a tool's request contract ({@code contracts/<tool>-request.v1.schema.json}) on the specifications the starter
 * built: the contract is the one published as the tool's input schema and the description comes from the catalog, so what a model reads is
 * what the contract file says; an argument the contract refuses gets the {@code INVALID_INPUT} envelope before any type conversion.
 * A tool with no contract file passes through untouched, so another tool's owner decides its own schema.
 *
 * <p>Why this is a bean post processor and not an {@code @Aspect}: Spring AOP advises the calls of a bean's methods through a proxy, and what this wraps is not a bean's
 * method. The starter builds the tool specifications (records holding an immutable {@code Tool} and a handler lambda) and publishes them as the elements of one {@code List}
 * bean, and this guard replaces the published {@code Tool} itself (its schema and description), which no join point exposes. An advice on the {@code @McpTool} methods would run
 * after the argument conversion that the contract has to precede, and could not change what {@code tools/list} publishes. The post processor is Spring's own extension point
 * for decorating what a library builds.
 */
final class ContractGuard implements BeanPostProcessor, Ordered {
    private static final Pattern CAMEL_TAIL = Pattern.compile("_([a-z])");
    /**
     * The tools whose contract is enforced here, by the name the tool is published under (the constant's name in lower case). A contract file alone does not make a tool this guard's: the workflow
     * tools publish theirs inlined, and the board and trust tools declare an added sessionRef. The cleanup tools bind no session; the rest bind a session and take the optional sessionRef every session tool takes.
     */
    private enum OwnTool {
        CHECKPOINT_CONTEXT(true), MARK_TURN_UNCHANGED(true), INSPECT_CONTEXT(true), LOAD_CONTEXT(true), SUPPRESS_CONTEXT_RESTORE(true), PURGE_DIRECT_CONTEXT(true), PREPARE_STATE_CLEANUP(false), EXECUTE_STATE_CLEANUP(false),
        PREPARE_SESSION_MESSAGE(true), SEND_SESSION_MESSAGE(true), ACKNOWLEDGE_SESSION_MESSAGES(true), GET_SESSION_MESSAGE_STATUS(true), RECALL_SESSION_MESSAGE(true), LAUNCH_MANAGED_CHILD(true),
        UPDATE_SESSION_STATUS(true), LIST_SESSION_STATUS(true), RECORD_TURN_RECAP(true), RECALL_USER_PROMPTS(true), READ_PROMPT_TRANSCRIPT(true),
        ARTIFACT_BEGIN(true), ARTIFACT_CHUNK(true), ARTIFACT_FINISH(true), ARTIFACT_STATUS(true), ARTIFACT_READ(true);

        private static final Map<String, OwnTool> BY_NAME = Map.copyOf(Arrays.stream(values()).collect(Collectors.toMap(OwnTool::wire, Function.identity())));

        private final boolean bindsSession;

        OwnTool(boolean bindsSession) {
            this.bindsSession = bindsSession;
        }

        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Optional<OwnTool> named(String tool) {
            return Optional.ofNullable(BY_NAME.get(tool));
        }
    }

    static final String SESSION_REF = "sessionRef";
    private final ObjectProvider<Messages> messages;
    private final ObjectProvider<SennardToolResults> results;
    private final ObjectProvider<JsonMapper> mapper;
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    ContractGuard(ObjectProvider<Messages> messages, ObjectProvider<SennardToolResults> results, ObjectProvider<JsonMapper> mapper) {
        this.messages = messages;
        this.results = results;
        this.mapper = mapper;
    }

    /** Before {@code UndeclaredArgumentGuard}, so an undeclared argument reaches that guard's fixed refusal first. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof McpStatelessServerFeatures.SyncToolSpecification) {
            return list.stream().map(McpStatelessServerFeatures.SyncToolSpecification.class::cast).map(this::guarded).toList();
        }
        return bean;
    }

    private McpStatelessServerFeatures.SyncToolSpecification guarded(McpStatelessServerFeatures.SyncToolSpecification specification) {
        return contract(specification.tool().name()).map(contract -> guarded(specification, contract)).orElse(specification);
    }

    private McpStatelessServerFeatures.SyncToolSpecification guarded(McpStatelessServerFeatures.SyncToolSpecification specification, Contract contract) {
        JsonMapper json = mapper.getObject();
        McpSchema.Tool tool = McpSchema.Tool.builder(specification.tool().name(), contract.published()).title(specification.tool().title())
                .description(contract.description(messages.getObject()))
                .annotations(specification.tool().annotations()).meta(specification.tool().meta()).build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            JsonNode arguments = json.valueToTree(request.arguments() == null ? Map.of() : request.arguments());
            List<Error> errors = contract.schema().validate(arguments, SchemaLocale.IN_ENGLISH);
            if (errors.isEmpty()) {
                return specification.callHandler().apply(context, request);
            }
            Map<String, Object> violations = SennardException.args("validationErrors", errors.stream().map(Error::getMessage).toList());
            // A malformed send most often carries an uncertain delivery: the refusal says how to go on without sending twice.
            if (contract.tool() == OwnTool.SEND_SESSION_MESSAGE) {
                String cause = messages.getObject().render(MessageRef.of("workflow.schema.contractMismatch", "name", contract.validatorName()));
                return results.getObject().failure(new InvalidInputException(InputCode.SEND_INVALID, SennardException.args("cause", cause), violations));
            }
            return results.getObject().failure(new InvalidInputException(InputCode.TOOL_ARGUMENTS_MISMATCH, SennardException.args("name", contract.validatorName()), violations));
        }).build();
    }

    private record Contract(OwnTool tool, Schema schema, Map<String, Object> published, String validatorName) {
        String description(Messages catalog) {
            return catalog.render(MessageRef.of("tools." + tool.wire() + ".description"));
        }
    }

    private Optional<Contract> contract(String name) {
        return OwnTool.named(name).flatMap(this::contract);
    }

    private Optional<Contract> contract(OwnTool own) {
        String tool = own.wire();
        ClassPathResource resource = new ClassPathResource("contracts/" + tool.replace('_', '-') + "-request.v1.schema.json");
        if (!resource.exists()) {
            return Optional.empty();
        }
        JsonMapper json = mapper.getObject();
        try (InputStream in = resource.getInputStream()) {
            ObjectNode tree = (ObjectNode) json.readTree(in);
            // The contract plus the session reference, for a host that cannot attach its session to a call (as the board tools take it).
            if (own.bindsSession) {
                tree.withObject("properties").putObject(SESSION_REF).put("type", "string").put("minLength", 1)
                        .put("description", ToolSupport.SESSION_REF_DESCRIPTION);
            }
            Schema schema = registry.getSchema(json.writeValueAsString(tree));
            // MCP clients refuse a top-level oneOf; the contract's own validation above keeps it.
            ObjectNode published = tree.deepCopy();
            published.remove(List.of("oneOf", "anyOf", "allOf", "$schema", "$id", "title"));
            return Optional.of(new Contract(own, schema, json.convertValue(published, new TypeReference<Map<String, Object>>() {
            }), validatorName(tool)));
        } catch (IOException cause) {
            throw new UncheckedIOException("The contract of " + tool + " could not be read", cause);
        }
    }

    /** {@code checkpoint_context} is the validator {@code checkpointContextRequest}. */
    private static String validatorName(String tool) {
        Matcher matcher = CAMEL_TAIL.matcher(tool);
        return matcher.replaceAll(match -> match.group(1).toUpperCase(java.util.Locale.ROOT)) + "Request";
    }
}
