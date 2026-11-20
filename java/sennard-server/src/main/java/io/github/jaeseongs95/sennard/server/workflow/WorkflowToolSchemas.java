// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The input schema each workflow tool advertises, derived from the contract files so the published schema and the validator cannot drift.
 * Every cross-file reference is inlined, because some hosts send a referenced object as a string; validation still uses the exact contract.
 */
@Component
public class WorkflowToolSchemas {
    private static final Set<String> ANNOTATION_ONLY = Set.of("$schema", "$id", "$defs", "definitions");
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "patternProperties", "dependentSchemas");
    private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMA_VALUES = Set.of("additionalProperties", "unevaluatedProperties", "propertyNames", "items", "additionalItems", "unevaluatedItems", "contains", "not", "if", "then", "else", "contentSchema");

    private final Map<String, JsonNode> documents;
    private final Messages messages;
    private final JsonMapper mapper;

    public WorkflowToolSchemas(ContractValidator validator, Messages messages, JsonMapper mapper) {
        this.documents = validator.contracts();
        this.messages = messages;
        this.mapper = mapper;
    }

    /** One advertised tool. */
    public record Published(Map<String, Object> inputSchema, String description) {
    }

    private String text(String key) {
        return messages.text(key);
    }

    private static final String ID_PREFIX = "https://skill-suite.local/contracts/";

    private ObjectNode inlined(String fileName) {
        return (ObjectNode) inline(documents.get(ID_PREFIX + fileName), documents.get(ID_PREFIX + fileName), new HashSet<>());
    }

    private JsonNode inline(JsonNode schema, JsonNode document, Set<String> resolving) {
        if (!schema.isObject()) {
            return schema.deepCopy();
        }
        if (schema.path("$ref").isString()) {
            String reference = schema.get("$ref").asString();
            int hash = reference.indexOf('#');
            String id = hash < 0 ? reference : reference.substring(0, hash);
            String fragment = hash < 0 ? "" : reference.substring(hash + 1);
            JsonNode target = id.isEmpty() ? document : documents.get(id);
            if (target == null) {
                throw new IllegalStateException("Unresolvable schema reference " + reference);
            }
            JsonNode resolved = fragment.isEmpty() ? target : target.at(fragment);
            if (resolved.isMissingNode()) {
                throw new IllegalStateException("Unresolvable schema pointer " + reference);
            }
            String key = (id.isEmpty() ? document.path("$id").asString("") : id) + "#" + fragment;
            if (!resolving.add(key)) {
                throw new IllegalStateException("Recursive schema reference " + reference);
            }
            ObjectNode out = (ObjectNode) inline(resolved, target, resolving);
            resolving.remove(key);
            for (String sibling : schema.propertyNames()) {
                if (!"$ref".equals(sibling)) {
                    out.set(sibling, schema.get(sibling).deepCopy());
                }
            }
            return out;
        }
        ObjectNode out = mapper.createObjectNode();
        schema.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (ANNOTATION_ONLY.contains(key)) {
                return;
            }
            if (SCHEMA_MAPS.contains(key) && value.isObject()) {
                ObjectNode map = out.putObject(key);
                value.properties().forEach(item -> map.set(item.getKey(), inline(item.getValue(), document, resolving)));
            } else if (SCHEMA_ARRAYS.contains(key) && value.isArray()) {
                ArrayNode array = out.putArray(key);
                value.forEach(item -> array.add(inline(item, document, resolving)));
            } else if (SCHEMA_VALUES.contains(key) && value.isObject()) {
                out.set(key, inline(value, document, resolving));
            } else {
                out.set(key, value.deepCopy());
            }
        });
        return out;
    }

    // The schemas below are built as trees because they are JSON Schema documents, whose shape the schema dialect owns and no record can state.

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ArrayNode stringArray(Collection<String> values) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        values.forEach(node::add);
        return node;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> out.add(item.asString()));
        }
        return out;
    }

    /** The values a closed set is written with, in constant order: what a published input schema lists as its {@code enum}. */
    private ArrayNode values(Class<? extends Enum<?>> type) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (Enum<?> constant : type.getEnumConstants()) {
            out.add(mapper.valueToTree(constant));
        }
        return out;
    }

    private ObjectNode responseMode() {
        ObjectNode mode = object();
        mode.set("enum", values(ResponseMode.class));
        mode.set("default", values(ResponseMode.class).get(ResponseMode.FULL.ordinal()));
        return mode;
    }

    private static void optional(ObjectNode schema, String... names) {
        List<String> required = texts(schema.get("required"));
        required.removeAll(List.of(names));
        schema.set("required", stringArray(required));
    }

    private static void addRequired(ObjectNode schema, String name) {
        List<String> required = new ArrayList<>(texts(schema.get("required")));
        required.add(name);
        schema.set("required", stringArray(required));
    }

    private static ObjectNode text() {
        return object().put("type", "string").put("minLength", 1);
    }

    private static ObjectNode strings() {
        ObjectNode schema = object().put("type", "array").put("minItems", 1).put("maxItems", 32).put("uniqueItems", true);
        schema.set("items", text());
        return schema;
    }

    /** The files of a change plan: 1 to 256 distinct paths of at most 2000 characters (the limits of {@link MinimalStartRequest} and {@link RunRequest.Plan}). */
    private static ObjectNode files() {
        ObjectNode schema = object().put("type", "array").put("minItems", 1).put("maxItems", 256).put("uniqueItems", true);
        schema.set("items", text().put("maxLength", 2000));
        return schema;
    }

    private static ObjectNode closedObject(String... required) {
        ObjectNode schema = object().put("type", "object").put("additionalProperties", false);
        schema.set("required", stringArray(List.of(required)));
        schema.putObject("properties");
        return schema;
    }

    private static ObjectNode properties(ObjectNode schema) {
        return (ObjectNode) schema.get("properties");
    }

    private ObjectNode described(ObjectNode schema, String key) {
        return schema.put("description", text(key));
    }

    private ObjectNode revisionTool() {
        ObjectNode schema = closedObject("runId", "expectedRevision");
        properties(schema).set("runId", text());
        properties(schema).set("expectedRevision", object().put("type", "integer").put("minimum", 0));
        properties(schema).set("responseMode", responseMode());
        return schema;
    }

    private ObjectNode idTool(String field) {
        ObjectNode schema = closedObject(field);
        properties(schema).set(field, text());
        properties(schema).set("detail", responseMode());
        return schema;
    }

    /** The input schema of a kind that owns no files: its goal, its skills (required or not as the kind says), its target when it takes one, and the optional classification key and workspace. */
    private ObjectNode kindSchema(WorkflowKind kind, ObjectNode skills, ObjectNode operation) {
        String tool = kind.toolName();
        List<String> required = new ArrayList<>(List.of("goal"));
        if (kind.skillsRequired()) {
            required.add("skills");
        }
        if (kind.takesTarget()) {
            required.add("target");
        }
        ObjectNode schema = closedObject(required.toArray(String[]::new));
        ObjectNode fields = properties(schema);
        fields.set("goal", text().put("maxLength", 2000));
        fields.set("skills", described(skills.deepCopy(), "tools." + tool + ".input.skills"));
        fields.set("classificationOperationKey", described(operation.deepCopy(), "tools." + tool + ".input.classificationOperationKey"));
        fields.set("workspace", described(text(), "tools." + tool + ".input.workspace"));
        if (kind.takesTarget()) {
            fields.set("target", described(text().put("maxLength", 2000), "tools." + tool + ".input.target"));
        }
        return schema;
    }

    public Map<String, Published> published() {
        Map<String, Published> tools = new LinkedHashMap<>();
        // No top-level oneOf: a merged object is the only form every host accepts. The exact contract still validates the call.
        ObjectNode envelope = inlined("task-envelope.v1.schema.json");
        ObjectNode plan = object().put("type", "object").put("additionalProperties", false);
        described(plan, "tools.plan_workflow.inlineInput");
        ObjectNode planProperties = plan.putObject("properties");
        planProperties.setAll(properties(envelope).deepCopy());
        planProperties.set("taskEnvelope", envelope.deepCopy());
        planProperties.set("evaluationAuditPurpose", object().set("enum", values(EvaluationAuditPurpose.class)));
        planProperties.set("requiredAuditPhase", object().set("enum", values(AuditPhase.class)));
        add(tools, "plan_workflow", plan);

        ObjectNode open = inlined("open-convergence-root-request.v1.schema.json");
        properties(open).set("responseMode", responseMode());
        add(tools, "open_convergence_root", open);

        ObjectNode claim = inlined("attempt-proposal.v1.schema.json");
        optional(claim, "taskEnvelope", "frame");
        ObjectNode dependent = claim.putObject("dependentRequired");
        dependent.set("taskEnvelope", stringArray(List.of("frame")));
        dependent.set("frame", stringArray(List.of("taskEnvelope")));
        add(tools, "claim_workflow_attempt", claim);

        ObjectNode guarded = inlined("guarded-workflow-start-request.v1.schema.json");
        ObjectNode skills = ((ObjectNode) properties(guarded).get("skills")).deepCopy();
        described(skills, "tools.start_workflow.input.skills");
        ObjectNode operation = ((ObjectNode) properties(guarded).get("classificationOperationKey")).deepCopy();
        described(operation, "tools.start_workflow.input.classificationOperationKey");
        properties(guarded).set("responseMode", responseMode());
        properties(guarded).set("skills", skills.deepCopy());
        properties(guarded).set("classificationOperationKey", operation.deepCopy());
        optional(guarded, "plan");
        if (!texts(guarded.get("required")).contains("skills")) {
            addRequired(guarded, "skills");
        }
        add(tools, "start_guarded_workflow", guarded);

        add(tools, "get_convergence_status", idTool("rootId"));

        ObjectNode resolve = inlined("resolve-convergence-gate-request.v1.schema.json");
        properties(resolve).set("responseMode", responseMode());
        add(tools, "resolve_convergence_gate", resolve);

        ObjectNode start = closedObject("goal", "stages", "skills", "acceptanceCriteria", "writeTargets");
        ObjectNode startProperties = properties(start);
        startProperties.set("goal", text().put("maxLength", 2000));
        startProperties.set("stages", described(strings(), "tools.start_workflow.input.stages"));
        startProperties.set("skills", skills);
        startProperties.set("classificationOperationKey", operation);
        startProperties.set("acceptanceCriteria", strings());
        startProperties.set("workspace", described(text(), "tools.start_workflow.input.workspace"));
        startProperties.set("writeTargets", described(strings(), "tools.start_workflow.input.writeTargets"));
        startProperties.set("rootId", described(text(), "tools.start_workflow.input.rootId"));
        ObjectNode prior = closedObject("hypothesis", "changeSummary", "discriminator", "evidenceRefs");
        properties(prior).set("hypothesis", text());
        properties(prior).set("changeSummary", text());
        properties(prior).set("discriminator", text());
        properties(prior).set("evidenceRefs", strings());
        startProperties.set("priorFailure", described(prior, "tools.start_workflow.input.priorFailure"));
        startProperties.set("changePlan", described(files(), "tools.start_workflow.input.changePlan"));
        add(tools, "start_workflow", start);

        // The tool of every other kind, made from the kind's properties: what it requires, and the field of its own when it has one.
        for (WorkflowKind kind : WorkflowKind.values()) {
            if (!kind.allowsFileEdits()) {
                add(tools, kind.toolName(), kindSchema(kind, skills, operation));
            }
        }

        ObjectNode resume = closedObject("runId");
        properties(resume).set("runId", described(text(), "tools.resume_workflow.input.runId"));
        properties(resume).set("expectedRevision", described(object().put("type", "integer").put("minimum", 0), "tools.resume_workflow.input.expectedRevision"));
        properties(resume).set(WorkflowReceipts.FIELD, object().put("type", "string").put("pattern", io.github.jaeseongs95.sennard.app.operation.OperationKey.SHAPE.pattern()));
        ObjectNode together = object();
        together.set("expectedRevision", mapper.createArrayNode().add(WorkflowReceipts.FIELD));
        together.set(WorkflowReceipts.FIELD, mapper.createArrayNode().add("expectedRevision"));
        resume.set("dependentRequired", together);
        add(tools, "resume_workflow", resume);

        ObjectNode pause = revisionTool();
        properties(pause).set("reason", described(text().put("maxLength", 2000), "tools.pause_workflow.input.reason"));
        properties(pause).set("resumeCondition", described(text().put("maxLength", 2000), "tools.pause_workflow.input.resumeCondition"));
        addRequired(pause, "reason");
        addRequired(pause, "resumeCondition");
        add(tools, "pause_workflow", pause);

        ObjectNode submit = closedObject("runId", "expectedRevision", "files");
        properties(submit).set("runId", text());
        properties(submit).set("expectedRevision", object().put("type", "integer").put("minimum", 0));
        properties(submit).set("files", described(files(), "tools.submit_change_plan.input.files"));
        add(tools, "submit_change_plan", submit);

        ObjectNode record = inlined("stage-result.v1.schema.json");
        properties(record).remove("executionContext");
        optional(record, "executionContext");
        properties(record).set("responseMode", responseMode());
        add(tools, "record_stage_result", record);

        add(tools, "get_workflow_status", idTool("runId"));
        add(tools, "list_session_workflows", closedObject());
        add(tools, "finalize_workflow", revisionTool());
        add(tools, "abort_workflow", revisionTool());

        ObjectNode release = closedObject("rootId", "expectedRevision", "reason");
        ObjectNode releaseProperties = properties(release);
        releaseProperties.set("rootId", described(text(), "tools.release_convergence_root.input.rootId"));
        releaseProperties.set("expectedRevision", described(object().put("type", "integer").put("minimum", 0), "tools.release_convergence_root.input.expectedRevision"));
        releaseProperties.set("reason", described(text().put("maxLength", 2000), "tools.release_convergence_root.input.reason"));
        ObjectNode approvals = object().put("type", "array").put("maxItems", 32).put("uniqueItems", true);
        approvals.set("items", text());
        releaseProperties.set("userApprovalRefs", described(approvals, "tools.release_convergence_root.input.userApprovalRefs"));
        add(tools, "release_convergence_root", release);
        return tools;
    }

    /** The write tools of the workflow: each takes the caller's operationKey, required (the use case answers a request that comes again with the first success; see WorkflowReceipts). */
    private static final Set<String> KEYED = Set.of("plan_workflow", "open_convergence_root", "claim_workflow_attempt", "start_guarded_workflow", "resolve_convergence_gate", "start_workflow",
            "record_stage_result", "finalize_workflow", "abort_workflow", "pause_workflow", "release_convergence_root", "submit_change_plan");

    private static void keyed(ObjectNode schema) {
        properties(schema).set(WorkflowReceipts.FIELD, object().put("type", "string").put("pattern", io.github.jaeseongs95.sennard.app.operation.OperationKey.SHAPE.pattern()));
        addRequired(schema, WorkflowReceipts.FIELD);
    }

    private void add(Map<String, Published> tools, String name, ObjectNode schema) {
        if (KEYED.contains(name) || WorkflowKind.ofTool(name).isPresent()) {
            keyed(schema);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = mapper.convertValue(schema, Map.class);
        tools.put(name, new Published(asMap, text("tools." + name + ".description")));
    }
}
