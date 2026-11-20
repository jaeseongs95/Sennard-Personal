// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** acceptance-evidence-validator: derives a criterion-by-criterion verdict from a frozen task and its current-target evidence, then confirms a report agrees. */
@Component
public class AcceptanceEvidenceCheck implements SkillCheck<AcceptanceEvidenceCheck.CheckCommand> {
    private static final Logger LOG = LoggerFactory.getLogger(AcceptanceEvidenceCheck.class);
    private static final String M ="skillScripts.acceptanceEvidence.";
    private static final String DIR = "skills/acceptance-evidence-validator/contracts/";
    private static final String INPUT = DIR + "acceptance-evidence-input.v1.schema.json";
    private static final String REPORT = DIR + "acceptance-evidence-report.v1.schema.json";
    private static final String VALIDATION = DIR + "acceptance-report-validation.v1.schema.json";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final Pattern EXCLUDED_SCOPE = Pattern.compile("^task-envelope#/scope/excluded/([0-9]+)$");
    private static final Pattern PROHIBITED_ACTION = Pattern.compile("^task-envelope#/authorization/prohibitedActions/([0-9]+)$");
    /** An earlier positional label; its definition belongs to task-contract, which alone assigns criterion IDs. */
    private static final Pattern LEGACY = TaskContractCheck.CRITERION_LABEL_ONLY;
    /** This skill assigns no ID: the key binds evidence to a criterion's exact text as a prefix plus 12 hex of that text's SHA-256. */
    private static final String DIGEST_KEY_PREFIX = "AC-";
    private static final int DIGEST_KEY_HEX = 12;
    private static final Pattern DIGEST_KEY = Pattern.compile("^" + DIGEST_KEY_PREFIX + "[a-f0-9]{12}$");
    /** The longest run of digits {@link Integer#parseInt(String)} reads without overflow, the bound of a locator's index. */
    private static final int LOCATOR_INDEX_DIGITS = 9;

    /** The commands of this check, by the name a caller sends. */
    enum CheckCommand implements SkillCommand {
        ANALYZE("analyze"), VALIDATE_REPORT("validate-report"), VALIDATE_AGAINST_REQUEST("validate-against-request");

        private final String wire;

        CheckCommand(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    private enum Direction {
        @JsonProperty("supports") SUPPORTS, @JsonProperty("refutes") REFUTES
    }

    private enum EvidenceKind {
        @JsonProperty("user-input") USER_INPUT, @JsonProperty("file") FILE, @JsonProperty("test") TEST, @JsonProperty("document") DOCUMENT, @JsonProperty("tool") TOOL,
        @JsonProperty("verification-command") VERIFICATION_COMMAND
    }

    private enum TargetKind {
        @JsonProperty("commit") COMMIT, @JsonProperty("diff") DIFF, @JsonProperty("artifact") ARTIFACT, @JsonProperty("document") DOCUMENT, @JsonProperty("observable-state") OBSERVABLE_STATE
    }

    private enum Status {
        @JsonProperty("satisfied") SATISFIED, @JsonProperty("unsatisfied") UNSATISFIED, @JsonProperty("insufficient-evidence") INSUFFICIENT_EVIDENCE, @JsonProperty("not-applicable") NOT_APPLICABLE
    }

    private enum Verdict {
        PASS, FAIL, BLOCKED, NEEDS_INPUT
    }

    /** The request ({@code acceptance-evidence-input.v1}); {@code schemaVersion} is the schema's constant and no judgment reads it. */
    @JsonIgnoreProperties("schemaVersion")
    private record Input(Envelope taskEnvelope, Target target, List<Evidence> evidence, List<Command> verificationCommands, List<String> knownLimitations, List<CriterionOverride> criterionOverrides,
            List<Binding> criterionBindings) {
        Input {
            criterionBindings = criterionBindings == null ? List.of() : criterionBindings;
        }
    }

    /** The frozen task; the schema constant {@code schemaVersion}, the optional {@code artifactContracts} and the members no judgment reads stay with the schema. */
    @JsonIgnoreProperties({"schemaVersion", "artifactContracts", "riskLevel", "orchestration"})
    private record Envelope(String taskId, String objective, Scope scope, List<String> acceptanceCriteria, List<WorkUnit> workUnits, List<String> requiredCapabilities, List<String> constraints,
            Authorization authorization, Decision decision) {
    }

    private record Scope(List<String> included, List<String> excluded) {
    }

    private record WorkUnit(String id, String objective, List<String> dependencies, List<String> writeTargets) {
    }

    private record Authorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    /** {@code complexity} is a closed set of the schema that no judgment reads. */
    @JsonIgnoreProperties("complexity")
    private record Decision(boolean hasConflicts) {
    }

    private record Target(TargetKind kind, String identifier, String digest) {
    }

    private record Evidence(String id, EvidenceKind kind, String locator, String digest, String targetDigest, boolean verified, List<String> criterionIds, Direction direction, String observedResult) {
        Evidence resolved(List<String> ids) {
            return new Evidence(id, kind, locator, digest, targetDigest, verified, ids, direction, observedResult);
        }
    }

    private record Command(String id, String command, long exitCode, String locator, String targetDigest, List<String> criterionIds) {
        Command resolved(List<String> ids) {
            return new Command(id, command, exitCode, locator, targetDigest, ids);
        }
    }

    /** The schema constant {@code status} ({@code not-applicable}) is the only value an override can carry. */
    @JsonIgnoreProperties("status")
    private record CriterionOverride(String criterionId, String rationale, String authorityRef) {
    }

    private record Binding(String criterionId, String statement) {
    }

    /** The report: derived by {@link #analyze} and read back from a caller's document. */
    private record Report(String schemaVersion, Target target, List<CriterionRow> criteria, List<IndexEntry> verifiedEvidenceIndex, List<String> unresolvedCriteria, List<String> limitations,
            Verdict verdict) {
    }

    private record CriterionRow(String criterionId, String statement, Status status, List<String> evidenceRefs, String observedResult, String rationale) {
    }

    private record IndexEntry(String evidenceId, EvidenceKind kind, String locator, String digest, String targetDigest, List<String> criterionIds, Direction direction) {
    }

    /** One piece of current-target evidence with what it observed. */
    private record Ground(IndexEntry entry, String observedResult) {
    }

    /** What a verification command's evidence digest covers. */
    private record CommandBasis(String command, long exitCode, String locator, String targetDigest, List<String> criterionIds) {
    }

    /** What an authority evidence's digest covers: the source evidence as given plus the TaskEnvelope value its locator names. */
    private record AuthorityBasis(String id, EvidenceKind kind, String locator, String targetDigest, boolean verified, List<String> criterionIds, Direction direction, String observedResult,
            String authorityValue) {
    }

    /** The wrapper {@code acceptance-report-validation.v1}; its members are documents the contract validates by reference. */
    private record ValidationInput(String schemaVersion, JsonNode report, JsonNode request, JsonNode requestArtifact) {
    }

    private record RequestArtifact(String artifactId, String schemaId, String locator, String digest) {
    }

    private record Validation(boolean valid, List<String> errors) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    private final JsonMapper mapper;

    public AcceptanceEvidenceCheck(SkillSchemas schemas, Messages messages) {
        this.schemas = schemas;
        this.messages = messages;
        this.mapper = schemas.mapper();
    }

    @Override
    public String skill() {
        return "acceptance-evidence-validator";
    }

    @Override
    public Class<CheckCommand> commandType() {
        return CheckCommand.class;
    }

    @Override
    public SkillCheckResult run(CheckCommand command, SkillArguments args) {
        return switch (command) {
            case ANALYZE -> SkillCheckResult.ok(analyze(bound(args.document("input"))));
            case VALIDATE_REPORT -> answer(validate(args.document("report"), args.document("request"), args.document("requestArtifact")));
            // The workflow stage's rules: the report against the frozen request the stage recorded, without the artifact wrapper.
            case VALIDATE_AGAINST_REQUEST -> answer(againstRequest(args.document("report"), args.document("request")));
        };
    }

    private static SkillCheckResult answer(List<String> errors) {
        return SkillCheckResult.of(new Validation(errors.isEmpty(), errors), errors.isEmpty());
    }

    private static InvalidInputException input(InputCode code, Object... params) {
        return new InvalidInputException(code, SennardException.args(params));
    }

    private String t(String key, Object... params) {
        return messages.forRequest(M + key, SennardException.args(params));
    }

    /** A string the schema lets through but the judgments do not: blank. */
    private static void filled(String value, String name) {
        if (value.isBlank()) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_BE_NON_EMPTY_STRING, "name", name);
        }
    }

    /** {@link #filled(String, String)} for a name made of a subject; the name is built only when the value is refused. */
    private static void filled(String value, String before, String subject, String after) {
        if (value.isBlank()) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_BE_NON_EMPTY_STRING, "name", before + subject + after);
        }
    }

    private static void noBlank(List<String> values, String name) {
        for (String value : values) {
            if (value.isBlank()) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_BE_STRING_ARRAY, "name", name);
            }
        }
    }

    private static void noBlank(List<String> values, String before, String subject, String after) {
        for (String value : values) {
            if (value.isBlank()) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_BE_STRING_ARRAY, "name", before + subject + after);
            }
        }
    }

    /** The request as a record: the object, the contract's schema, then the binding. The judgments run in {@link #analyze}. */
    private Input bound(JsonNode tree) {
        if (!tree.isObject()) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_INPUT_NOT_OBJECT);
        }
        List<SkillSchemas.Violation> violations = schemas.violations(INPUT, tree);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.ACCEPTANCE_EVIDENCE_INPUT_SCHEMA_FAILED, Map.of(), SennardException.args("errors", violations));
        }
        return schemas.bind(tree, Input.class);
    }

    private static void envelopeRules(Envelope task) {
        filled(task.taskId(), "taskEnvelope.taskId");
        filled(task.objective(), "taskEnvelope.objective");
        noBlank(task.scope().included(), "taskEnvelope.scope.included");
        noBlank(task.scope().excluded(), "taskEnvelope.scope.excluded");
        noBlank(task.acceptanceCriteria(), "taskEnvelope.acceptanceCriteria");
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (WorkUnit unit : task.workUnits()) {
            filled(unit.id(), "taskEnvelope.workUnit.id");
            String id = unit.id();
            if (graph.containsKey(id)) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_DUPLICATE_WORK_UNIT, "id", id);
            }
            graph.put(id, unit.dependencies());
            filled(unit.objective(), "taskEnvelope.workUnit ", id, " objective");
            noBlank(unit.dependencies(), "taskEnvelope.workUnit ", id, " dependencies");
            noBlank(unit.writeTargets(), "taskEnvelope.workUnit ", id, " writeTargets");
        }
        for (List<String> dependencies : graph.values()) {
            for (String dependency : dependencies) {
                if (!graph.containsKey(dependency)) {
                    throw input(InputCode.ACCEPTANCE_EVIDENCE_UNKNOWN_DEPENDENCY, "dependency", dependency);
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) {
            visit(id, graph, visiting, visited);
        }
        noBlank(task.requiredCapabilities(), "taskEnvelope.requiredCapabilities");
        noBlank(task.constraints(), "taskEnvelope.constraints");
        noBlank(task.authorization().allowedActions(), "taskEnvelope.authorization.allowedActions");
        noBlank(task.authorization().prohibitedActions(), "taskEnvelope.authorization.prohibitedActions");
        noBlank(task.authorization().approvalRequired(), "taskEnvelope.authorization.approvalRequired");
    }

    private static void visit(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_DEPENDENCY_CYCLE, "id", id);
        }
        if (visited.contains(id)) {
            return;
        }
        visiting.add(id);
        for (String dependency : graph.get(id)) {
            visit(dependency, graph, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static String criterionId(String statement) {
        return DIGEST_KEY_PREFIX + Digests.sha256Hex(statement).substring(0, DIGEST_KEY_HEX);
    }

    /** The derived report for a request that passed its schema. A separate step from validation so a stage gate can compare a report to exactly this. */
    private Report analyze(Input input) {
        Envelope task = input.taskEnvelope();
        envelopeRules(task);
        Target target = input.target();
        filled(target.identifier(), "target.identifier");
        noBlank(input.knownLimitations(), "knownLimitations");
        List<String> statements = task.acceptanceCriteria();
        List<String> criterionIds = new ArrayList<>(statements.size());
        Set<String> valid = new LinkedHashSet<>();
        Map<String, String> idByStatement = new HashMap<>();
        for (String statement : statements) {
            String id = criterionId(statement);
            criterionIds.add(id);
            valid.add(id);
            idByStatement.put(statement, id);
        }
        if (valid.size() < statements.size()) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_NOT_DUPLICATE, "name", "criterionIds");
        }
        Map<String, String> aliases = new HashMap<>();
        Set<String> boundStatements = new HashSet<>();
        for (Binding binding : input.criterionBindings()) {
            String id = idByStatement.get(binding.statement());
            if (id == null) {
                throw new InvalidInputException(InputCode.ACCEPTANCE_EVIDENCE_CRITERIA_MISMATCH, Map.of(), SennardException.args("code", "CRITERION_BINDING_STATEMENT_MISMATCH", "binding", binding));
            }
            String alias = binding.criterionId();
            if (aliases.containsKey(alias) || boundStatements.contains(binding.statement())) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_MUST_NOT_DUPLICATE, "name", "criterionBindings");
            }
            if (valid.contains(alias) && !Objects.equals(alias, id)) {
                throw new InvalidInputException(InputCode.ACCEPTANCE_EVIDENCE_CRITERIA_MISMATCH, Map.of(), SennardException.args("code", "CRITERION_BINDING_ID_COLLISION", "binding", binding));
            }
            aliases.put(alias, id);
            boundStatements.add(binding.statement());
        }
        Resolver resolver = new Resolver(valid, aliases);
        Set<String> evidenceIds = new HashSet<>();
        Map<String, Evidence> sourceById = new HashMap<>();
        List<Evidence> rewrittenEvidence = new ArrayList<>();
        for (Evidence evidence : input.evidence()) {
            String id = evidence.id();
            filled(id, "evidence.id");
            if (!evidenceIds.add(id)) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_DUPLICATE_EVIDENCE, "id", id);
            }
            filled(evidence.locator(), "evidence ", id, " locator");
            List<String> resolved = resolver.resolveAll(evidence.criterionIds(), id);
            filled(evidence.observedResult(), "evidence ", id, " observedResult");
            sourceById.put(id, evidence);
            if (!resolved.isEmpty()) {
                rewrittenEvidence.add(evidence.resolved(resolved));
            }
        }
        Set<String> commandIds = new HashSet<>();
        List<Command> rewrittenCommands = new ArrayList<>();
        for (Command command : input.verificationCommands()) {
            String id = command.id();
            filled(id, "verificationCommand.id");
            if (!commandIds.add(id)) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_DUPLICATE_COMMAND, "id", id);
            }
            String subject = "command:" + id;
            if (evidenceIds.contains(subject)) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_RESERVED_COMMAND_EVIDENCE_ID, "id", id);
            }
            filled(command.command(), "verificationCommand.command");
            filled(command.locator(), "verificationCommand.locator");
            List<String> resolved = resolver.resolveAll(command.criterionIds(), subject);
            if (!resolved.isEmpty()) {
                rewrittenCommands.add(command.resolved(resolved));
            }
        }
        Map<String, CriterionOverride> overrides = new LinkedHashMap<>();
        Set<String> overrideIds = new HashSet<>();
        for (CriterionOverride override : input.criterionOverrides()) {
            String raw = override.criterionId();
            String resolved = resolver.resolve(raw, "criterionOverrides");
            if (!overrideIds.add(raw) || (resolved != null && overrides.containsKey(resolved))) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_DUPLICATE_OVERRIDE, "criterionId", raw);
            }
            filled(override.rationale(), "override ", raw, " rationale");
            filled(override.authorityRef(), "override ", raw, " authorityRef");
            if (resolved != null) {
                overrides.put(resolved, override);
            }
        }
        Set<String> unboundLegacy = resolver.unboundLegacy();
        List<String> limitations = new ArrayList<>(input.knownLimitations());
        if (!unboundLegacy.isEmpty()) {
            limitations.add("Legacy positional criterion IDs (" + String.join(", ", unboundLegacy) + ") lack exact statement bindings. Supply criterionBindings from the frozen original request/report or collect fresh content-bound evidence.");
        }
        String targetDigest = target.digest();
        String identifier = target.identifier();
        List<Ground> grounds = new ArrayList<>();
        Map<String, Ground> currentById = new HashMap<>();
        for (Evidence item : rewrittenEvidence) {
            if (!Objects.equals(targetDigest, item.targetDigest())) {
                limitations.add("Evidence " + item.id() + " is stale for target " + identifier + ".");
            } else if (item.verified()) {
                Ground ground = new Ground(new IndexEntry(item.id(), item.kind(), item.locator(), item.digest(), item.targetDigest(), item.criterionIds(), item.direction()), item.observedResult());
                grounds.add(ground);
                currentById.put(item.id(), ground);
            }
        }
        Set<String> staleCommandCriteria = new HashSet<>();
        for (Command command : rewrittenCommands) {
            if (!Objects.equals(targetDigest, command.targetDigest())) {
                limitations.add("Verification command " + command.id() + " is stale for target " + identifier + ".");
                staleCommandCriteria.addAll(command.criterionIds());
                continue;
            }
            String digest = Checks.digest(mapper, new CommandBasis(command.command(), command.exitCode(), command.locator(), command.targetDigest(), command.criterionIds()));
            grounds.add(new Ground(new IndexEntry("command:" + command.id(), EvidenceKind.VERIFICATION_COMMAND, command.locator(), digest, command.targetDigest(), command.criterionIds(),
                    command.exitCode() == 0 ? Direction.SUPPORTS : Direction.REFUTES), "Verification command exited with code " + command.exitCode() + "."));
        }
        for (Map.Entry<String, CriterionOverride> entry : overrides.entrySet()) {
            checkAuthority(entry.getKey(), entry.getValue(), currentById, sourceById, task);
        }
        Map<String, List<Ground>> byCriterion = new HashMap<>();
        for (Ground ground : grounds) {
            for (String id : ground.entry().criterionIds()) {
                byCriterion.computeIfAbsent(id, ignored -> new ArrayList<>()).add(ground);
            }
        }
        List<CriterionRow> criteria = new ArrayList<>(statements.size());
        List<String> unresolved = new ArrayList<>();
        boolean anyUnsatisfied = false;
        for (int position = 0; position < statements.size(); position++) {
            String statement = statements.get(position);
            String id = criterionIds.get(position);
            List<Ground> refuting = new ArrayList<>();
            List<Ground> supporting = new ArrayList<>();
            for (Ground ground : byCriterion.getOrDefault(id, List.of())) {
                (ground.entry().direction() == Direction.REFUTES ? refuting : supporting).add(ground);
            }
            CriterionOverride override = overrides.get(id);
            CriterionRow row;
            if (!refuting.isEmpty()) {
                row = row(id, statement, Status.UNSATISFIED, refuting, "Current target evidence contradicts this acceptance criterion.");
            } else if (!unboundLegacy.isEmpty()) {
                row = row(id, statement, Status.INSUFFICIENT_EVIDENCE, List.of(), "Legacy positional evidence cannot be assigned to exact criterion statements.");
            } else if (override != null) {
                row = new CriterionRow(id, statement, Status.NOT_APPLICABLE, List.of(override.authorityRef()), "Excluded by explicit authority.", override.rationale());
            } else if (staleCommandCriteria.contains(id)) {
                row = row(id, statement, Status.INSUFFICIENT_EVIDENCE, List.of(), "A declared verification command does not match the current target.");
            } else if (!supporting.isEmpty()) {
                row = row(id, statement, Status.SATISFIED, supporting, "Verified evidence for the current target supports this criterion.");
            } else {
                row = row(id, statement, Status.INSUFFICIENT_EVIDENCE, List.of(), "No verified evidence for the current target supports or refutes this criterion.");
            }
            if (row.status() == Status.INSUFFICIENT_EVIDENCE) {
                unresolved.add(id);
            } else if (row.status() == Status.UNSATISFIED) {
                anyUnsatisfied = true;
            }
            criteria.add(row);
        }
        boolean conflicts = task.decision().hasConflicts();
        Verdict verdict = conflicts ? Verdict.NEEDS_INPUT : anyUnsatisfied ? Verdict.FAIL : !unresolved.isEmpty() ? Verdict.BLOCKED : Verdict.PASS;
        if (conflicts) {
            limitations.add("TaskEnvelope records unresolved conflicts that require input before acceptance can be finalized.");
        }
        List<IndexEntry> index = new ArrayList<>(grounds.size());
        for (Ground ground : grounds) {
            index.add(ground.entry());
        }
        return new Report(SCHEMA_VERSION, target, criteria, index, unresolved, List.copyOf(new LinkedHashSet<>(limitations)), verdict);
    }

    /** An override stands only on current, verified authority evidence whose locator names an exclusion or a prohibition of the TaskEnvelope and whose digest covers that value. */
    private void checkAuthority(String criterion, CriterionOverride override, Map<String, Ground> currentById, Map<String, Evidence> sourceById, Envelope task) {
        Ground authority = currentById.get(override.authorityRef());
        if (authority == null || (authority.entry().kind() != EvidenceKind.USER_INPUT && authority.entry().kind() != EvidenceKind.DOCUMENT) || authority.entry().direction() != Direction.SUPPORTS
                || !authority.entry().criterionIds().contains(criterion)) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_OVERRIDE_NEEDS_AUTHORITY, "criterionId", criterion);
        }
        String locator = authority.entry().locator();
        Matcher match = EXCLUDED_SCOPE.matcher(locator);
        List<String> collection;
        if (match.matches()) {
            collection = task.scope().excluded();
        } else {
            match = PROHIBITED_ACTION.matcher(locator);
            if (!match.matches()) {
                throw input(InputCode.ACCEPTANCE_EVIDENCE_OVERRIDE_AUTHORITY_LOCATOR, "criterionId", criterion);
            }
            collection = task.authorization().prohibitedActions();
        }
        String digits = match.group(1);
        int position = digits.length() > LOCATOR_INDEX_DIGITS ? -1 : Integer.parseInt(digits);
        String value = position < 0 || position >= collection.size() ? null : collection.get(position);
        if (value == null || value.isEmpty()) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_OVERRIDE_LOCATOR_UNRESOLVED, "criterionId", criterion);
        }
        Evidence source = sourceById.get(authority.entry().evidenceId());
        String expected = Checks.digest(mapper, new AuthorityBasis(source.id(), source.kind(), source.locator(), source.targetDigest(), source.verified(), source.criterionIds(), source.direction(),
                source.observedResult(), value));
        if (!Objects.equals(expected, authority.entry().digest())) {
            throw input(InputCode.ACCEPTANCE_EVIDENCE_OVERRIDE_DIGEST_MISMATCH, "criterionId", criterion);
        }
    }

    private static CriterionRow row(String id, String statement, Status status, List<Ground> evidence, String rationale) {
        List<String> refs = new ArrayList<>(evidence.size());
        List<String> observed = new ArrayList<>(evidence.size());
        for (Ground ground : evidence) {
            refs.add(ground.entry().evidenceId());
            observed.add(ground.observedResult());
        }
        return new CriterionRow(id, statement, status, refs, String.join("; ", observed), rationale);
    }

    /** Maps the criterion IDs a document carries (digest keys, bound aliases, unbound positional labels) to digest keys. */
    private static final class Resolver {
        private final Set<String> valid;
        private final Map<String, String> aliases;
        private final Set<String> unboundLegacy = new LinkedHashSet<>();

        Resolver(Set<String> valid, Map<String, String> aliases) {
            this.valid = valid;
            this.aliases = aliases;
        }

        Set<String> unboundLegacy() {
            return unboundLegacy;
        }

        String resolve(String id, String subject) {
            if (valid.contains(id)) {
                return id;
            }
            String alias = aliases.get(id);
            if (alias != null) {
                return alias;
            }
            if (LEGACY.matcher(id).matches()) {
                BigInteger position = new BigInteger(id.substring(DIGEST_KEY_PREFIX.length()));
                if (position.signum() > 0 && position.compareTo(BigInteger.valueOf(valid.size())) <= 0) {
                    unboundLegacy.add(id);
                    return null;
                }
            }
            throw input(InputCode.ACCEPTANCE_EVIDENCE_EVIDENCE_UNKNOWN_CRITERION, "evidenceId", subject, "criterionId", id);
        }

        List<String> resolveAll(List<String> ids, String subject) {
            Set<String> out = new LinkedHashSet<>();
            for (String id : ids) {
                String resolved = resolve(id, subject);
                if (resolved != null) {
                    out.add(resolved);
                }
            }
            return new ArrayList<>(out);
        }
    }

    private List<String> validate(JsonNode report, JsonNode request, JsonNode requestArtifact) {
        JsonNode document = mapper.valueToTree(new ValidationInput(SCHEMA_VERSION, report, request, requestArtifact));
        List<SkillSchemas.Violation> violations = schemas.violations(VALIDATION, document);
        if (!violations.isEmpty()) {
            return List.of(t("validationInputSchemaFailed", "errors", mapper.writeValueAsString(violations)));
        }
        List<String> errors = new ArrayList<>();
        if (!Objects.equals(CanonicalJson.digest(request), schemas.bind(requestArtifact, RequestArtifact.class).digest())) {
            errors.add(t("requestArtifactDigestMismatch"));
        }
        // The wrapper's schema already held both documents to their contracts.
        errors.addAll(judge(schemas.bind(report, Report.class), request, true));
        return errors;
    }

    /** One-to-one agreement of a report with {@link #analyze}. */
    List<String> againstRequest(JsonNode report, JsonNode request) {
        if (!report.isObject()) {
            return List.of(t("reportNotObject"));
        }
        List<SkillSchemas.Violation> violations = schemas.violations(REPORT, report);
        if (!violations.isEmpty()) {
            // A report that breaks its contract is refused on that ground: its members cannot be judged one by one.
            return List.of(t("reportSchemaFailed", "errors", mapper.writeValueAsString(violations)));
        }
        return judge(schemas.bind(report, Report.class), request, false);
    }

    private List<String> judge(Report report, JsonNode request, boolean requestChecked) {
        List<String> errors = new ArrayList<>();
        String targetDigest = report.target().digest();
        Map<String, IndexEntry> index = new HashMap<>();
        for (IndexEntry item : report.verifiedEvidenceIndex()) {
            if (index.put(item.evidenceId(), item) != null) {
                errors.add(t("indexDuplicateEvidence", "evidenceId", item.evidenceId()));
            }
            if (!Objects.equals(targetDigest, item.targetDigest())) {
                errors.add(t("indexStaleEvidence", "evidenceId", item.evidenceId()));
            }
        }
        Set<String> seen = new HashSet<>();
        boolean unique = true;
        for (CriterionRow item : report.criteria()) {
            unique &= seen.add(item.criterionId());
        }
        if (!unique) {
            errors.add(t("criterionIdsUnique"));
        }
        boolean hasFailure = false;
        List<String> expectedUnresolved = new ArrayList<>();
        for (CriterionRow item : report.criteria()) {
            String id = item.criterionId();
            Status status = item.status();
            if (!DIGEST_KEY.matcher(id).matches()) {
                errors.add(t("criterionIdInvalid", "criterionId", id));
            }
            boolean hasRefs = !item.evidenceRefs().isEmpty();
            if (status == Status.SATISFIED && !hasRefs) {
                errors.add(t("satisfiedWithoutEvidence", "criterionId", id));
            }
            if (status == Status.NOT_APPLICABLE && (item.rationale().isBlank() || !hasRefs)) {
                errors.add(t("notApplicableWithoutAuthority", "criterionId", id));
            }
            for (String ref : item.evidenceRefs()) {
                IndexEntry evidence = index.get(ref);
                if (evidence == null) {
                    errors.add(t("evidenceAbsentFromIndex", "criterionId", id, "ref", ref));
                } else if (!evidence.criterionIds().contains(id)) {
                    errors.add(t("evidenceNotBound", "ref", ref, "criterionId", id));
                } else if (status == Status.SATISFIED && evidence.direction() != Direction.SUPPORTS) {
                    errors.add(t("satisfiedNonSupporting", "criterionId", id, "ref", ref));
                } else if (status == Status.UNSATISFIED && evidence.direction() != Direction.REFUTES) {
                    errors.add(t("unsatisfiedNoRefuting", "criterionId", id, "ref", ref));
                }
            }
            if (status == Status.UNSATISFIED) {
                hasFailure = true;
            } else if (status == Status.INSUFFICIENT_EVIDENCE) {
                expectedUnresolved.add(id);
            }
        }
        Verdict expected = hasFailure ? Verdict.FAIL : !expectedUnresolved.isEmpty() ? Verdict.BLOCKED : Verdict.PASS;
        if (expected != report.verdict() && report.verdict() != Verdict.NEEDS_INPUT) {
            errors.add(t("verdictMustBe", "verdict", expected.name()));
        }
        if (!sorted(expectedUnresolved).equals(sorted(report.unresolvedCriteria()))) {
            errors.add(t("unresolvedVsStatuses"));
        }
        try {
            Report derived = analyze(requestChecked ? schemas.bind(request, Input.class) : bound(request));
            if (!derived.target().equals(report.target())) {
                errors.add(t("targetMismatch"));
            }
            if (!derived.criteria().equals(report.criteria())) {
                errors.add(t("criteriaMismatch"));
            }
            if (!derived.verifiedEvidenceIndex().equals(report.verifiedEvidenceIndex())) {
                errors.add(t("indexMismatch"));
            }
            if (!derived.unresolvedCriteria().equals(report.unresolvedCriteria())) {
                errors.add(t("unresolvedMismatch"));
            }
            if (!derived.limitations().equals(report.limitations())) {
                errors.add(t("limitationsMismatch"));
            }
            if (derived.verdict() != report.verdict()) {
                errors.add(t("verdictMustBe", "verdict", derived.verdict().name()));
            }
        } catch (InvalidInputException failure) {
            // The request cannot be analysed: the verdict below carries the reason (the failure's catalog key), so this ends the handling.
            LOG.debug("acceptance-evidence: the request cannot substantiate the report: {}", failure.messageKey(), failure);
            errors.add(t("requestCannotSubstantiate", "message", messages.forRequest(failure)));
        }
        return errors;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
