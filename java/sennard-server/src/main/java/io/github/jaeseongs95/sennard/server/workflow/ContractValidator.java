// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.SchemaLocale;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeType;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract checks of the workflow documents: the wire schemas are the contract files themselves, so MCP and the
 * stored documents share one definition, and a skill's declared output schema is read from the content root and bound to the digest the
 * plan froze. Reading a declared schema is a file read: the entry does it before the transaction of a use case ({@link #bound}), and the checks take the result.
 */
@Component
public class ContractValidator {
    /** The {@code schemaVersion} every contract document carries. */
    public static final String CONTRACT_VERSION = "1.0.0";
    private static final String CONTRACT_ID_PREFIX = "https://skill-suite.local/contracts/";
    /** What a JSON Schema's {@code type} says for an object: the name of the JSON node type of objects, in the lower case JSON Schema writes it. */
    private static final String OBJECT_TYPE = JsonNodeType.OBJECT.name().toLowerCase(Locale.ROOT);
    /** The folder of the skills among the repository's folders; a schema path that starts with it names a skill's own contracts. */
    private static final Path SKILLS_FOLDER = Path.of("skills");
    /** The words a schema's subject is named by in the sentences of its refusals: the provider's result, its output, a reference-only output and a deliberation's decision record. */
    static final String PROVIDER_RESULT = "provider result";
    static final String PROVIDER_OUTPUT = "provider output";
    static final String REFERENCE_ONLY_OUTPUT = "reference-only output";
    static final String DECISION_RECORD = "deliberation decision record";
    private static final Map<String, String> SCHEMA_SUBJECTS = Map.of(PROVIDER_RESULT, "providerResult", PROVIDER_OUTPUT, "providerOutput",
            REFERENCE_ONLY_OUTPUT, "referenceOnlyOutput", DECISION_RECORD, "decisionRecord");

    private final JsonMapper mapper;
    private final ContentRoot content;
    private final Map<String, String> contractText = new HashMap<>();
    private final SchemaRegistry contracts;
    // ceiling: one compiled schema per contract name and per skill schema file; bound by what ships, not by callers.
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    public ContractValidator(JsonMapper mapper, ContentRoot content) throws IOException {
        this.mapper = mapper;
        this.content = content;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:contracts/*.schema.json")) {
            String text = resource.getContentAsString(StandardCharsets.UTF_8);
            JsonNode schema = mapper.readTree(text);
            contractText.put(schema.path("$id").asString(CONTRACT_ID_PREFIX + resource.getFilename()), text);
        }
        this.contracts = registry(contractText);
    }

    private static SchemaRegistry registry(Map<String, String> schemas) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemas(schemas)
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    }

    /** The document at this contract file name, for the tool schemas. */
    public JsonNode contract(String fileName) {
        return mapper.readTree(contractText.get(CONTRACT_ID_PREFIX + fileName));
    }

    /** Every contract, by file name, for inlining their cross-references into published tool schemas. */
    public Map<String, JsonNode> contracts() {
        Map<String, JsonNode> all = new HashMap<>();
        contractText.forEach((id, text) -> all.put(id, mapper.readTree(text)));
        return all;
    }

    /**
     * Checks a document against the contract file of this name. The schema validator reads a tree, and this is the one place a typed document
     * becomes one: a value that is a tree already (a caller's input, a document of a kind not yet typed) is checked as it is.
     */
    public void assertValid(String name, Object document) {
        Schema schema = compiled.computeIfAbsent(name, ignored -> contracts.getSchema(SchemaLocation.of(CONTRACT_ID_PREFIX + name)));
        JsonNode value = document instanceof JsonNode tree ? tree : mapper.valueToTree(document);
        List<Error> errors = schema.validate(value, SchemaLocale.IN_ENGLISH);
        if (!errors.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.contractMismatch", "name", name), details("validationErrors", errorText(errors)));
        }
    }

    /**
     * A caller's input: checked against the contract file of this name as the caller wrote it, then bound to its record. A value the schema accepts but
     * the record cannot hold (a time Java does not read, for one) is the caller's invalid input too, with the binding failure as the cause.
     */
    public <T> T read(String name, JsonNode input, Class<T> type) {
        assertValid(name, input);
        return bind(name, input, type);
    }

    /** Binds an input the contract of this name has accepted to its record; a key the record does not list is refused. */
    public <T> T bind(String name, JsonNode input, Class<T> type) {
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(input);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.contractMismatch", "name", name), details("validationErrors", unbound.getOriginalMessage()), unbound);
        }
    }

    public void planWorkflowRequest(JsonNode value) { assertValid("plan-workflow-request.v1.schema.json", value); }
    void planWorkflowRequest(TaskEnvelope value) { assertValid("plan-workflow-request.v1.schema.json", value); }
    void workflowPlan(WorkflowPlan value) { assertValid("workflow-plan.v1.schema.json", value); }
    void workflowReceipt(WorkflowReceipt value) { assertValid("workflow-receipt.v1.schema.json", value); }
    void stageResult(StageResult value) { assertValid("stage-result.v1.schema.json", value); }
    void convergenceRoot(ConvergenceRoot value) { assertValid("convergence-root.v1.schema.json", value); }
    void openConvergenceRootRequest(OpenConvergenceRootRequest value) { assertValid("open-convergence-root-request.v1.schema.json", value); }
    void attemptProposal(AttemptProposal value) { assertValid("attempt-proposal.v1.schema.json", value); }
    public void attemptLease(AttemptLease value) { assertValid("attempt-lease.v1.schema.json", value); }
    void guardedWorkflowStartRequest(GuardedStartRequest value) { assertValid("guarded-workflow-start-request.v1.schema.json", value); }
    void attemptOutcome(AttemptOutcome value) { assertValid("attempt-outcome.v1.schema.json", value); }
    void convergenceStatus(ConvergenceStatus value) { assertValid("convergence-status.v1.schema.json", value); }

    private static String errorText(List<Error> errors) {
        StringBuilder text = new StringBuilder();
        for (Error error : errors) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(error.getInstanceLocation()).append(' ').append(error.getMessage());
        }
        return text.toString();
    }

    /**
     * A schema file the plan froze, as the content root has it now (its bytes held to the digest the plan froze) and compiled. Reading it is a file read, so the entry does it
     * before the transaction of a use case ({@link WorkflowFileReader}) and the use case judges with the result.
     */
    record BoundSchema(WorkflowPlan.SchemaReference reference, JsonNode tree, Schema compiled) {
    }

    /** Reads, checks and compiles the schema file a plan froze; the file is read from the content root each time, because the install owner may change it after the planning. */
    BoundSchema bound(WorkflowPlan.SchemaReference reference, String label) {
        JsonNode target = readBoundSchema(reference, label);
        Schema schema = compiled.computeIfAbsent(reference.path() + "\0" + reference.digest(), ignored -> compileSkillSchema(reference, target));
        return new BoundSchema(reference, target, schema);
    }

    /** StageResult.output validated against the resultSchema and outputSchema the plan froze; the output schema is taken only where the result has an output. */
    void providerResult(BoundSchema resultSchemaBound, Supplier<BoundSchema> outputSchema, ProviderResult result) {
        JsonNode declared = resultSchemaBound.tree();
        boolean declaresVersion = declared.path("properties").has("schemaVersion");
        // The skill's schema judges a tree: the provider's result is written out here, the one place its envelope becomes one (its output is already a tree).
        ObjectNode view = (ObjectNode) mapper.valueToTree(result);
        if (!declaresVersion) {
            view.remove("schemaVersion");
        }
        if (view.path("artifacts").isArray()) {
            ArrayNode artifacts = mapper.createArrayNode();
            for (JsonNode artifact : view.get("artifacts")) {
                artifacts.add(artifactDigestView(declared, artifact));
            }
            view.set("artifacts", artifacts);
        }
        assertSchemaFile(resultSchemaBound, view, PROVIDER_RESULT);
        if (result.hasOutput()) {
            assertSchemaFile(outputSchema.get(), view.get("output"), PROVIDER_OUTPUT);
        }
    }

    public JsonNode declaredSchema(BoundSchema schema, JsonNode value, String label) {
        return assertSchemaFile(schema, value, label);
    }

    public Set<String> referenceOnlyFixedTokens(BoundSchema schema) {
        Set<String> tokens = new HashSet<>();
        visitTokens(schema.tree(), schema.reference(), tokens);
        return tokens;
    }

    private void visitTokens(JsonNode value, WorkflowPlan.SchemaReference reference, Set<String> tokens) {
        if (value.isArray()) {
            value.forEach(item -> visitTokens(item, reference, tokens));
        } else if (value.isObject()) {
            if ((OBJECT_TYPE.equals(value.path("type").asString(null)) || value.has("properties")) && !(value.has("additionalProperties") && value.get("additionalProperties").isBoolean() && !value.get("additionalProperties").booleanValue())) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.referenceProviderOutputSchemaClose"), details("schemaPath", reference.path()));
            }
            if (value.path("const").isString()) {
                tokens.add(value.get("const").asString());
            }
            value.path("enum").forEach(item -> {
                if (item.isString()) {
                    tokens.add(item.asString());
                }
            });
            value.forEach(item -> visitTokens(item, reference, tokens));
        }
    }

    /** Providers declare digests bare or sha256:-prefixed; a digest that misses the declared pattern only by that prefix is validated in the declared form. */
    private JsonNode artifactDigestView(JsonNode declared, JsonNode artifact) {
        JsonNode items = declared.path("properties").path("artifacts").path("items");
        if (items.path("$ref").isString() && items.get("$ref").asString().startsWith("#/")) {
            items = declared.at(items.get("$ref").asString().substring(1));
        }
        JsonNode properties = items.path("properties");
        if (!artifact.isObject() || properties.isMissingNode()) {
            return artifact;
        }
        ObjectNode view = ((ObjectNode) artifact).deepCopy();
        for (String key : List.of("digest", "targetDigest")) {
            JsonNode current = view.get(key);
            JsonNode pattern = properties.path(key).path("pattern");
            if (current == null || !current.isString() || !pattern.isString() || !ReceiptPolicy.DIGEST.matcher(current.asString()).matches()) {
                continue;
            }
            Pattern declaredForm = Pattern.compile(pattern.asString());
            String value = current.asString();
            String alternate = value.startsWith("sha256:") ? value.substring(7) : "sha256:" + value;
            if (!declaredForm.matcher(value).find() && declaredForm.matcher(alternate).find()) {
                view.put(key, alternate);
            }
        }
        return view;
    }

    private JsonNode assertSchemaFile(BoundSchema bound, JsonNode value, String label) {
        List<Error> errors = bound.compiled().validate(value, SchemaLocale.IN_ENGLISH);
        if (!errors.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.declaredSchemaMismatch", "subject", SCHEMA_SUBJECTS.getOrDefault(label, "other"), "label", label),
                    details("schemaPath", bound.reference().path(), "validationErrors", errorText(errors)));
        }
        return value;
    }

    private Schema compileSkillSchema(WorkflowPlan.SchemaReference reference, JsonNode target) {
        Path root = content.path();
        String targetId = target.path("$id").asString(null);
        Map<String, String> schemas = new HashMap<>(contractText);
        for (Path directory : List.of(root.resolve("contracts"), skillSchemaRoot(root, reference.path()))) {
            for (Path candidate : schemaFiles(directory)) {
                String text = read(candidate);
                JsonNode schema = mapper.readTree(text);
                String id = schema.path("$id").asString(candidate.toUri().toString());
                schemas.putIfAbsent(id, text);
            }
        }
        if (targetId != null) {
            schemas.put(targetId, target.toString());
        }
        SchemaRegistry registry = registry(schemas);
        return targetId != null ? registry.getSchema(SchemaLocation.of(targetId)) : registry.getSchema(target);
    }

    private JsonNode readBoundSchema(WorkflowPlan.SchemaReference reference, String label) {
        Path root = content.path();
        Path schemaPath = root.resolve(reference.path()).normalize();
        if (!schemaPath.startsWith(root)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.schema.schemaEscapesPluginRoot", "subject", SCHEMA_SUBJECTS.getOrDefault(label, "other"), "label", label),
                    details("schemaPath", reference.path()));
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(schemaPath);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
        String digest = "sha256:" + Digests.sha256Hex(raw);
        if (!Objects.equals(digest, reference.digest())) {
            throw new WorkflowFailure(WorkflowCode.STALE_REVISION, msg("workflow.schema.schemaChangedAfterPlanning", "subject", SCHEMA_SUBJECTS.getOrDefault(label, "other"), "label", label),
                    details("schemaPath", reference.path(), "expectedDigest", reference.digest(), "actualDigest", digest));
        }
        return mapper.readTree(raw);
    }

    private static Path skillSchemaRoot(Path root, String schemaPath) {
        Path declared = Path.of(schemaPath);
        return declared.getNameCount() > 1 && declared.startsWith(SKILLS_FOLDER) ? root.resolve(SKILLS_FOLDER).resolve(declared.getName(1)) : root.resolve("contracts");
    }

    private static List<Path> schemaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".schema.json")).forEach(files::add);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }
}
