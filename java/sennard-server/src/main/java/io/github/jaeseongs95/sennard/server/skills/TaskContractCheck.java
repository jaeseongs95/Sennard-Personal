// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.hook.host.ForeignPathText;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/** task-contract: a report's task envelope is bound to its request's authorization evidence, criteria and scope. */
@Component
public class TaskContractCheck implements SkillCheck<TaskContractCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(TaskContractCheck.class);
    private static final String VALIDATION = "skills/task-contract/contracts/task-contract-validation.v1.schema.json";
    /** The one definition of an acceptance criterion's ID label (for example AC-001); only task-contract assigns it. */
    static final String CRITERION_LABEL = "AC-[0-9]{3,}";
    /** An ID label standing alone, as an earlier positional input names a criterion. */
    static final Pattern CRITERION_LABEL_ONLY = Pattern.compile("^" + CRITERION_LABEL + "$");
    private static final Pattern CRITERION = Pattern.compile("^\\[(" + CRITERION_LABEL + ")\\](?:\\s|$)");
    private static final Pattern GLOB_CHARACTERS = Pattern.compile("[*?\\[\\]{}!]");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern SLASH = Pattern.compile("/");
    private static final Set<String> RELATIVE_SEGMENTS = Set.of(".", "..");
    /** The envelope fields every report must account for in its provenance. */
    private static final List<String> CORE_FIELDS = List.of("/objective", "/scope", "/riskLevel", "/authorization");

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        VALIDATE("validate");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** Whether a path the request declares is compared without regard to case: the request's own {@code pathSemantics}, never the host's file system. */
    enum PathSemantics {
        @JsonProperty("windows") WINDOWS, @JsonProperty("posix") POSIX
    }

    enum Effect {
        @JsonProperty("allow") ALLOW, @JsonProperty("prohibit") PROHIBIT, @JsonProperty("require-approval") REQUIRE_APPROVAL
    }

    enum Authority {
        @JsonProperty("system") SYSTEM, @JsonProperty("developer") DEVELOPER, @JsonProperty("user") USER, @JsonProperty("project-instruction") PROJECT_INSTRUCTION
    }

    /** The envelope list an authorization binding names; each list carries one effect. */
    enum EnvelopeField {
        @JsonProperty("/authorization/allowedActions") ALLOWED_ACTIONS, @JsonProperty("/authorization/prohibitedActions") PROHIBITED_ACTIONS,
        @JsonProperty("/authorization/approvalRequired") APPROVAL_REQUIRED
    }

    enum Basis {
        @JsonProperty("explicit") EXPLICIT, @JsonProperty("instruction") INSTRUCTION, @JsonProperty("verified-inference") VERIFIED_INFERENCE
    }

    enum Impact {
        @JsonProperty("low") LOW, @JsonProperty("material") MATERIAL
    }

    enum RiskLevel {
        @JsonProperty("low") LOW, @JsonProperty("medium") MEDIUM, @JsonProperty("high") HIGH, @JsonProperty("critical") CRITICAL
    }

    enum Complexity {
        @JsonProperty("simple") SIMPLE, @JsonProperty("complex") COMPLEX
    }

    enum EvidenceKind {
        @JsonProperty("file") FILE, @JsonProperty("test") TEST, @JsonProperty("document") DOCUMENT, @JsonProperty("tool") TOOL, @JsonProperty("user-input") USER_INPUT
    }

    enum Verdict {
        PASS, NEEDS_INPUT, BLOCKED
    }

    /** The validation input: a request and the report that answers it. */
    record Validation(String schemaVersion, Request request, Report report) {
    }

    record Request(String schemaVersion, String taskId, String request, List<String> instructionResolutionRefs, PathSemantics pathSemantics, List<Evidence> authorizationEvidence,
            List<Fact> suppliedFacts, List<String> userDecisions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Evidence(String action, Effect effect, Authority authority, String sourceLocator, String sourceReceiptId) {
    }

    record Fact(String statement, String locator) {
    }

    /** The report, also the check's output: it is written back as the contract names its members. */
    record Report(String schemaVersion, Envelope taskEnvelope, Plan acceptanceEvidencePlan, List<Provenance> provenance, List<Binding> authorizationProvenance, List<Assumption> assumptions,
            List<Ambiguity> ambiguities, List<Contradiction> contradictions, Verdict verdict) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Envelope(String schemaVersion, String taskId, String objective, Scope scope, List<String> acceptanceCriteria, RiskLevel riskLevel, List<WorkUnit> workUnits,
            List<ArtifactContract> artifactContracts, List<String> requiredCapabilities, List<String> constraints, Authorization authorization, EnvelopeDecision decision,
            Orchestration orchestration) {
    }

    record Scope(List<String> included, List<String> excluded) {
    }

    record WorkUnit(String id, String objective, List<String> dependencies, List<String> writeTargets) {
    }

    record ArtifactContract(String capability, List<Produced> produced) {
    }

    record Produced(String artifactId, String schemaId, String targetDigest) {
    }

    record Authorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    record EnvelopeDecision(Complexity complexity, boolean hasConflicts) {
    }

    record Orchestration(boolean requested, boolean mcpAvailable) {
    }

    record Plan(String schemaVersion, List<PlanCriterion> criteria) {
    }

    record PlanCriterion(String criterionId, String statement, String verificationMethod, List<EvidenceKind> expectedEvidenceKinds, String passCondition) {
    }

    record Provenance(String field, String valueSummary, String sourceLocator, Basis basis) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Binding(String action, EnvelopeField envelopeField, Effect effect, Authority authority, String sourceLocator, String sourceReceiptId) {
    }

    record Assumption(String statement, Impact impact, boolean confirmationRequired) {
    }

    record Ambiguity(String field, String question, boolean blocking) {
    }

    record Contradiction(List<String> fields, String description, List<String> sourceLocators) {
    }

    /** The answer of {@code validate}: the report, or the refusal with its code, catalog key and details. */
    record Answer(String schemaVersion, boolean ok, Report output, Refusal error) {
    }

    record Refusal(String code, String message, Map<String, Object> details) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;

    public TaskContractCheck(SkillSchemas schemas, Messages messages) {
        this.schemas = schemas;
        this.messages = messages;
    }

    @Override
    public String skill() {
        return "task-contract";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        try {
            return SkillCheckResult.ok(new Answer("1.0.0", true, check(args.document("input")), null));
        } catch (InvalidInputException failure) {
            // The refusal is the validation's own answer (code, catalog key, details); this ends the handling.
            LOG.debug("task-contract: the input is refused: {}", failure.messageKey(), failure);
            return SkillCheckResult.of(new Answer("1.0.0", false, null, new Refusal(failure.code().wireCode(), messages.forRequest(failure), failure.details().isEmpty() ? null : failure.details())), false);
        }
    }

    private static InvalidInputException fail(InputCode code, Map<String, Object> details) {
        return new InvalidInputException(code, Map.of(), details == null ? Map.of() : details);
    }

    static boolean isRepoRelativePosixPath(String value) {
        if (value == null || value.isEmpty() || !Objects.equals(value.trim(), value) || value.contains("//") || value.contains("\\") || value.startsWith("/")
                || ForeignPathText.looksLikeDrivePath(value) || GLOB_CHARACTERS.matcher(value).find()) {
            return false;
        }
        List<String> segments = SLASH.splitAsStream(value).filter(segment -> !segment.isEmpty()).toList();
        return !segments.isEmpty() && segments.stream().noneMatch(RELATIVE_SEGMENTS::contains);
    }

    private static String normalized(String value, boolean caseInsensitive) {
        String trimmed = TRAILING_SLASHES.matcher(value).replaceAll("");
        return caseInsensitive ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    /** Whether two paths in their {@link #normalized} form are the same or one lies under the other. */
    private static boolean overlaps(String first, String second) {
        return ForeignPathText.overlaps(first, second);
    }

    /**
     * Whether a provenance field (a JSON Pointer into the envelope) is the envelope property {@code field} (written with its leading slash) or lies under it. Read as a pointer, so a
     * property whose name starts with the same letters, or one with an escaped slash, is not taken for it.
     */
    static boolean fieldWithin(String pointer, String field) {
        return pointer.startsWith("/") && JsonPointer.compile(pointer).matchesProperty(field.substring(1));
    }

    /** A declared repo-relative path with its comparison form, computed once. */
    private record Declared(String path, String normalized) {
        static Declared of(String path, boolean caseInsensitive) {
            return new Declared(path, TaskContractCheck.normalized(path, caseInsensitive));
        }

        boolean overlaps(Declared other) {
            return TaskContractCheck.overlaps(normalized, other.normalized);
        }
    }

    private static void assertGraph(List<WorkUnit> units) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            if (graph.containsKey(unit.id())) {
                throw fail(InputCode.TASK_CONTRACT_WORK_UNIT_IDS_UNIQUE, SennardException.args("workUnitId", unit.id()));
            }
            graph.put(unit.id(), unit.dependencies());
        }
        for (var entry : graph.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (!graph.containsKey(dependency)) {
                    throw fail(InputCode.TASK_CONTRACT_DEPENDENCY_MISSING, SennardException.args("workUnitId", entry.getKey(), "dependency", dependency));
                }
                if (Objects.equals(dependency, entry.getKey())) {
                    throw fail(InputCode.TASK_CONTRACT_SELF_DEPENDENCY, SennardException.args("workUnitId", entry.getKey()));
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) {
            visit(id, graph, visiting, visited);
        }
    }

    private static void visit(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            throw fail(InputCode.TASK_CONTRACT_DEPENDENCY_CYCLE, SennardException.args("workUnitId", id));
        }
        if (visited.contains(id)) {
            return;
        }
        visiting.add(id);
        for (String dependency : graph.getOrDefault(id, List.of())) {
            visit(dependency, graph, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static void assertNoOverlap(List<String> first, List<String> second, InputCode code, boolean caseInsensitive) {
        Set<String> secondKeys = new HashSet<>();
        for (String item : second) {
            secondKeys.add(key(item, caseInsensitive));
        }
        List<String> found = first.stream().filter(item -> secondKeys.contains(key(item, caseInsensitive))).toList();
        if (!found.isEmpty()) {
            throw fail(code, SennardException.args("overlaps", found));
        }
    }

    private static String key(String item, boolean caseInsensitive) {
        return caseInsensitive ? item.trim().toLowerCase(Locale.ROOT) : item.trim();
    }

    /** One envelope list, the effect it carries, its actions and their comparison keys (trimmed, lower case). */
    private record Group(EnvelopeField field, Effect effect, List<String> actions, Set<String> keys) {
        static Group of(EnvelopeField field, Effect effect, List<String> actions) {
            Set<String> keys = new HashSet<>();
            for (String action : actions) {
                keys.add(actionKey(action));
            }
            return new Group(field, effect, actions, keys);
        }
    }

    /** The binding an authorization-provenance entry makes: one action, in one envelope list, with one effect. */
    private record BindingKey(String action, EnvelopeField field, Effect effect) {
    }

    private static String actionKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void assertAuthorizationBound(Request request, Report report) {
        Authorization authorization = report.taskEnvelope().authorization();
        List<Group> groups = List.of(Group.of(EnvelopeField.ALLOWED_ACTIONS, Effect.ALLOW, authorization.allowedActions()),
                Group.of(EnvelopeField.PROHIBITED_ACTIONS, Effect.PROHIBIT, authorization.prohibitedActions()),
                Group.of(EnvelopeField.APPROVAL_REQUIRED, Effect.REQUIRE_APPROVAL, authorization.approvalRequired()));
        Map<Effect, Group> byEffect = new EnumMap<>(Effect.class);
        for (Group group : groups) {
            byEffect.put(group.effect(), group);
        }
        List<Evidence> evidence = request.authorizationEvidence();
        Set<String> resolutionRefs = new HashSet<>(request.instructionResolutionRefs());
        Set<String> restrictiveActions = new HashSet<>();
        for (Evidence item : evidence) {
            Effect effect = item.effect();
            if (item.sourceReceiptId() != null && effect != Effect.PROHIBIT) {
                throw fail(InputCode.TASK_CONTRACT_RECEIPT_CANNOT_AUTHORIZE, SennardException.args("action", item.action(), "effect", effect, "sourceReceiptId", item.sourceReceiptId()));
            }
            if (effect == Effect.ALLOW && item.authority() == Authority.PROJECT_INSTRUCTION && !resolutionRefs.contains(item.sourceLocator())) {
                throw fail(InputCode.TASK_CONTRACT_PROJECT_GRANT_UNBOUND, SennardException.args("action", item.action(), "sourceLocator", item.sourceLocator()));
            }
            if (effect != Effect.ALLOW) {
                Group group = byEffect.get(effect);
                String actionKey = actionKey(item.action());
                if (group == null || !group.keys().contains(actionKey)) {
                    throw fail(InputCode.TASK_CONTRACT_RESTRICTIVE_EVIDENCE_OMITTED, SennardException.args("action", item.action(), "effect", effect));
                }
                restrictiveActions.add(actionKey);
            }
        }
        for (Group group : groups) {
            if (group.keys().size() != group.actions().size()) {
                throw fail(InputCode.TASK_CONTRACT_ACTIONS_NOT_UNIQUE, SennardException.args("envelopeField", group.field()));
            }
        }
        List<Binding> provenance = report.authorizationProvenance();
        int expectedCount = groups.stream().mapToInt(group -> group.actions().size()).sum();
        if (provenance.size() != expectedCount) {
            throw fail(InputCode.TASK_CONTRACT_PROVENANCE_BINDING_EXACTLY_ONE, SennardException.args("expectedCount", expectedCount, "actualCount", provenance.size()));
        }
        Map<BindingKey, List<Integer>> bindings = new HashMap<>();
        for (int index = 0; index < provenance.size(); index++) {
            Binding binding = provenance.get(index);
            bindings.computeIfAbsent(new BindingKey(binding.action(), binding.envelopeField(), binding.effect()), ignored -> new ArrayList<>()).add(index);
        }
        Set<Integer> used = new HashSet<>();
        for (Group group : groups) {
            for (String action : group.actions()) {
                if (group.effect() == Effect.ALLOW && restrictiveActions.contains(actionKey(action))) {
                    throw fail(InputCode.TASK_CONTRACT_ALLOWED_CONFLICTS_RESTRICTIVE, SennardException.args("action", action));
                }
                List<Integer> matching = bindings.getOrDefault(new BindingKey(action, group.field(), group.effect()), List.of());
                if (matching.size() != 1) {
                    throw fail(InputCode.TASK_CONTRACT_PROVENANCE_BINDING_EXACTLY_ONE, SennardException.args("action", action, "envelopeField", group.field(), "bindingCount", matching.size()));
                }
                int index = matching.get(0);
                if (!used.add(index)) {
                    throw fail(InputCode.TASK_CONTRACT_BINDING_COVERS_MULTIPLE, SennardException.args("action", action));
                }
                Binding binding = provenance.get(index);
                long evidenceMatches = evidence.stream().filter(item -> Objects.equals(action, item.action()) && item.effect() == binding.effect() && item.authority() == binding.authority()
                        && Objects.equals(item.sourceLocator(), binding.sourceLocator()) && Objects.equals(item.sourceReceiptId(), binding.sourceReceiptId())).count();
                if (evidenceMatches != 1) {
                    throw fail(InputCode.TASK_CONTRACT_PROVENANCE_MATCH_EXACTLY_ONE, SennardException.args("action", action, "evidenceMatches", evidenceMatches));
                }
            }
        }
        if (used.size() != provenance.size()) {
            throw fail(InputCode.TASK_CONTRACT_PROVENANCE_EXTRA_ACTION, null);
        }
    }

    private static String criterionId(String statement) {
        Matcher matcher = CRITERION.matcher(statement);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Returns the report when it is bound to its request, or fails. Used by recovery-task binding too. */
    public JsonNode validate(JsonNode input) {
        return schemas.mapper().valueToTree(check(input));
    }

    private Report check(JsonNode input) {
        List<SkillSchemas.Violation> violations = schemas.violations(VALIDATION, input);
        if (!violations.isEmpty()) {
            throw fail(InputCode.TASK_CONTRACT_SCHEMA_VALIDATION_FAILED, SennardException.args("errors", violations));
        }
        Validation validation = schemas.bind(input, Validation.class);
        Request request = validation.request();
        Report report = validation.report();
        Envelope envelope = report.taskEnvelope();
        if (!Objects.equals(request.taskId(), envelope.taskId())) {
            throw fail(InputCode.TASK_CONTRACT_TASK_ID_MISMATCH, null);
        }
        boolean caseInsensitive = request.pathSemantics() == PathSemantics.WINDOWS;
        assertGraph(envelope.workUnits());
        Scope scope = envelope.scope();
        assertNoOverlap(scope.included(), scope.excluded(), InputCode.TASK_CONTRACT_SCOPE_OVERLAP_EXACT, caseInsensitive);
        List<Declared> excludedPaths = scope.excluded().stream().filter(TaskContractCheck::isRepoRelativePosixPath).map(path -> Declared.of(path, caseInsensitive)).toList();
        for (String path : scope.included()) {
            if (!isRepoRelativePosixPath(path)) {
                continue;
            }
            Declared included = Declared.of(path, caseInsensitive);
            excludedPaths.stream().filter(included::overlaps).findFirst().ifPresent(excluded -> {
                throw fail(InputCode.TASK_CONTRACT_SCOPE_PATH_OVERLAP, SennardException.args("included", path, "excluded", excluded.path()));
            });
        }
        Authorization authorization = envelope.authorization();
        assertNoOverlap(authorization.allowedActions(), authorization.prohibitedActions(), InputCode.TASK_CONTRACT_ALLOWED_PROHIBITED_OVERLAP, true);
        assertNoOverlap(authorization.allowedActions(), authorization.approvalRequired(), InputCode.TASK_CONTRACT_ALLOWED_APPROVAL_OVERLAP, true);
        assertNoOverlap(authorization.prohibitedActions(), authorization.approvalRequired(), InputCode.TASK_CONTRACT_PROHIBITED_APPROVAL_OVERLAP, true);
        assertAuthorizationBound(request, report);
        for (WorkUnit unit : envelope.workUnits()) {
            for (String target : unit.writeTargets()) {
                if (!isRepoRelativePosixPath(target)) {
                    throw fail(InputCode.TASK_CONTRACT_WRITE_TARGETS_FORMAT, SennardException.args("target", target));
                }
                Declared declared = Declared.of(target, caseInsensitive);
                excludedPaths.stream().filter(declared::overlaps).findFirst().ifPresent(excluded -> {
                    throw fail(InputCode.TASK_CONTRACT_WRITE_TARGET_EXCLUDED, SennardException.args("target", target, "excluded", excluded.path()));
                });
            }
        }
        Map<String, String> envelopeCriteria = new HashMap<>();
        for (String statement : envelope.acceptanceCriteria()) {
            String id = criterionId(statement);
            if (id == null) {
                throw fail(InputCode.TASK_CONTRACT_CRITERION_ID_MISSING, SennardException.args("statement", statement));
            }
            if (envelopeCriteria.put(id, statement) != null) {
                throw fail(InputCode.TASK_CONTRACT_CRITERION_IDS_UNIQUE, SennardException.args("criterionId", id));
            }
        }
        Map<String, PlanCriterion> planCriteria = new HashMap<>();
        for (PlanCriterion criterion : report.acceptanceEvidencePlan().criteria()) {
            if (planCriteria.put(criterion.criterionId(), criterion) != null) {
                throw fail(InputCode.TASK_CONTRACT_PLAN_CRITERION_IDS_UNIQUE, SennardException.args("criterionId", criterion.criterionId()));
            }
        }
        if (envelopeCriteria.size() != planCriteria.size()) {
            throw fail(InputCode.TASK_CONTRACT_CRITERIA_ONE_TO_ONE, null);
        }
        for (var entry : envelopeCriteria.entrySet()) {
            PlanCriterion planned = planCriteria.get(entry.getKey());
            if (planned == null || !Objects.equals(entry.getValue(), planned.statement())) {
                throw fail(InputCode.TASK_CONTRACT_CRITERION_STATEMENT_MISMATCH, SennardException.args("criterionId", entry.getKey()));
            }
        }
        for (String field : CORE_FIELDS) {
            if (report.provenance().stream().noneMatch(item -> fieldWithin(item.field(), field))) {
                throw fail(InputCode.TASK_CONTRACT_CORE_FIELD_NO_PROVENANCE, SennardException.args("field", field));
            }
        }
        if (report.verdict() == Verdict.PASS) {
            if (report.ambiguities().stream().anyMatch(Ambiguity::blocking)) {
                throw fail(InputCode.TASK_CONTRACT_PASS_BLOCKING_AMBIGUITY, null);
            }
            if (!report.contradictions().isEmpty()) {
                throw fail(InputCode.TASK_CONTRACT_PASS_CONTRADICTIONS, null);
            }
            if (report.assumptions().stream().anyMatch(item -> item.impact() == Impact.MATERIAL && item.confirmationRequired())) {
                throw fail(InputCode.TASK_CONTRACT_PASS_MATERIAL_ASSUMPTION, null);
            }
        }
        return report;
    }
}
