// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.files.FileFacts;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** evaluation-validity-auditor: whether an evaluation was frozen before it ran and whether its claimed metrics follow from its own records. */
@Component
public class EvaluationValidityCheck implements SkillCheck<EvaluationValidityCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationValidityCheck.class);
    private static final String M = "skillReports.evaluationValidityAuditor.";
    private static final String KO = "skillReportsKo.evaluationValidityAuditor.";
    private static final String C = "skills/evaluation-validity-auditor/contracts/";
    private static final String REQUEST = C + "evaluation-validity-request.v1.schema.json";
    private static final String CASE = C + "evaluation-case-record.v1.schema.json";
    private static final String RESULT = C + "evaluation-result-record.v1.schema.json";
    private static final String CLAIM = C + "evaluation-aggregate-claim.v1.schema.json";
    private static final String REPORT = C + "evaluation-validity-report.v1.schema.json";
    private static final String VALIDATION = C + "evaluation-validity-validation.v1.schema.json";
    private static final String VERSION = "1.0.0";
    private static final Set<Role> CONTROL_ROLES = Set.of(Role.FIXTURE_MANIFEST, Role.RUBRIC, Role.ORACLE, Role.AGGREGATION_RULE, Role.TIMING_EVIDENCE);
    private static final Set<Role> POST_ROLES = Set.of(Role.RESULT_RECORDS, Role.AGGREGATE_CLAIM);
    private static final Set<Role> POST_REQUIRED = Set.of(Role.FIXTURE_MANIFEST, Role.RUBRIC, Role.ORACLE, Role.AGGREGATION_RULE, Role.TIMING_EVIDENCE, Role.RESULT_RECORDS, Role.AGGREGATE_CLAIM);
    private static final Set<JudgmentMethod> EXACT_METHODS = Set.of(JudgmentMethod.DETERMINISTIC_ORACLE, JudgmentMethod.LEXICAL_MATCH);
    private static final List<String> OWN_LIMITATIONS = List.of("COOPERATIVE_PROVENANCE_ASSERTIONS", "JSON_JSONL_ONLY_V1");
    private static final Map<String, String> LIMITATION_KEYS = Map.of("COOPERATIVE_PROVENANCE_ASSERTIONS", "limitationCooperativeProvenance", "JSON_JSONL_ONLY_V1", "limitationJsonJsonlOnly");
    private static final Pattern UNDERSCORE = Pattern.compile("_");

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        DIGEST_REQUEST("digest-request"), ANALYZE("analyze"), VALIDATE_REPORT("validate-report");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    // ---- the output format argument

    private enum Format {
        @JsonProperty("json") JSON,
        @JsonProperty("human-ko") HUMAN_KO
    }

    // ---- contract enums (closed value sets of the schemas under skills/evaluation-validity-auditor/contracts)

    private enum Stage {
        @JsonProperty("pre-execution") PRE_EXECUTION,
        @JsonProperty("post-execution") POST_EXECUTION
    }

    private enum Role {
        @JsonProperty("fixture-manifest") FIXTURE_MANIFEST,
        @JsonProperty("rubric") RUBRIC,
        @JsonProperty("oracle") ORACLE,
        @JsonProperty("aggregation-rule") AGGREGATION_RULE,
        @JsonProperty("timing-evidence") TIMING_EVIDENCE,
        @JsonProperty("result-records") RESULT_RECORDS,
        @JsonProperty("aggregate-claim") AGGREGATE_CLAIM,
        @JsonProperty("evidence") EVIDENCE
    }

    private enum MediaType {
        @JsonProperty("json") JSON,
        @JsonProperty("jsonl") JSONL,
        @JsonProperty("text") TEXT
    }

    private enum Audience {
        @JsonProperty("author") AUTHOR,
        @JsonProperty("executor") EXECUTOR,
        @JsonProperty("judge") JUDGE,
        @JsonProperty("auditor") AUDITOR
    }

    private enum ProvenanceSource {
        @JsonProperty("git-revision") GIT_REVISION,
        @JsonProperty("signed-receipt") SIGNED_RECEIPT,
        @JsonProperty("trusted-system") TRUSTED_SYSTEM
    }

    private enum CriterionKind {
        @JsonProperty("deterministic") DETERMINISTIC,
        @JsonProperty("semantic") SEMANTIC
    }

    private enum JudgmentMethod {
        @JsonProperty("deterministic-oracle") DETERMINISTIC_ORACLE,
        @JsonProperty("independent-review") INDEPENDENT_REVIEW,
        @JsonProperty("self-report") SELF_REPORT,
        @JsonProperty("lexical-match") LEXICAL_MATCH
    }

    private enum Operation {
        @JsonProperty("rate") RATE,
        @JsonProperty("mean") MEAN,
        @JsonProperty("sum") SUM
    }

    private enum Outcome {
        PASS, FAIL, ERROR
    }

    private enum Compare {
        GE(">="), GT(">"), EQ("="), LE("<="), LT("<");

        private final String symbol;

        Compare(String symbol) {
            this.symbol = symbol;
        }

        /** The comparator as the contracts write it; Jackson reads and writes the enum through it. */
        @JsonValue
        String symbol() {
            return symbol;
        }
    }

    private enum RunStatus {
        @JsonProperty("completed") COMPLETED,
        @JsonProperty("failed") FAILED,
        @JsonProperty("timeout") TIMEOUT
    }

    /** A check's status and the report's verdict share these three values. */
    private enum Status {
        PASS, FAIL, BLOCKED
    }

    private enum CheckId {
        ARTIFACT_INTEGRITY, FRAME_FROZEN, CASE_MANIFEST_COMPLETE, ROLE_INDEPENDENCE, JUDGMENT_METHOD_VALID, AGGREGATION_RULE_VALID, TIMING_PROVENANCE_VALID, NO_POSTHOC_INPUT, RUN_SET_COMPLETE,
        RESULT_RECORDS_COMPLETE, AGGREGATE_RECOMPUTED, CLAIM_MATCHES
    }

    private enum Code {
        CHECK_PASSED, ARTIFACT_MISSING, DIGEST_MISMATCH, REQUIRED_ARTIFACT_ROLE_INVALID, FRAME_FROZEN_AFTER_AUDIT, FRAME_FROZEN_AFTER_EXECUTION, AUDIT_NOT_PRE_EXECUTION, AUDIT_NOT_POST_EXECUTION,
        RESULT_EVIDENCE_NOT_AVAILABLE_AT_AUDIT, JSONL_PARSE_FAILED, CASE_SCHEMA_INVALID, CASE_DUPLICATE, CASE_MISSING, CASE_UNKNOWN, CASE_ORDER_MISMATCH, ROLE_CONFLICT, SEMANTIC_LEXICAL_ONLY,
        SELF_REPORT_ONLY, JUDGMENT_METHOD_MISMATCH, ORACLE_BINDING_INVALID, PROVENANCE_MISSING, POSTHOC_INPUT, RUN_DUPLICATE, RUN_MISSING, RUN_UNKNOWN, RESULT_SCHEMA_INVALID, RESULT_DUPLICATE,
        RESULT_MISSING, RESULT_UNKNOWN, AGGREGATION_INVALID, AGGREGATE_CLAIM_MISSING, AGGREGATE_CLAIM_INVALID, AGGREGATE_MISMATCH
    }

    // ---- the request (evaluation-validity-request.v1.schema.json); date-times stay strings, parsed with Checks.instant where a rule needs the instant

    private record Request(String schemaVersion, String auditId, Stage auditStage, String auditedAt, Target target, String frozenAt, String executionStartedAt, Actors actors, List<Artifact> artifacts,
            Expected expected, List<Criterion> criteria, List<Metric> metrics, List<Run> runs, List<String> knownLimitations) {
    }

    private record Target(String evaluationId, String identifier, String revision, String digest) {
    }

    private record Actors(List<String> authorIds, List<String> executorIds, List<String> judgeIds, String auditorId) {
    }

    private record Artifact(String artifactId, Role role, String locator, String digest, MediaType mediaType, String availableAt, List<Audience> visibleToRoles, Provenance provenance) {
    }

    private record Provenance(ProvenanceSource source, String observedAt, List<String> evidenceRefs) {
    }

    private record Expected(List<String> caseIds, List<String> runIds, List<String> criterionIds) {
    }

    private record Criterion(String criterionId, CriterionKind kind, List<JudgmentMethod> judgmentMethods, String oracleArtifactId, List<String> evidenceRefs) {
    }

    /** {@code threshold} and {@code claimedValue} stay {@link Number}s so the report echoes them as the caller wrote them (an integer stays an integer). */
    private record Metric(String metricId, String criterionId, Operation operation, String denominator, List<Outcome> numeratorOutcomes, Compare comparator, Number threshold, Number claimedValue) {
    }

    private record Run(String runId, RunStatus status, String resultArtifactId) {
    }

    // ---- artifacts the request names, read as packaged JSON(L): case, result, claim and aggregation rule

    private record CaseRecord(String schemaVersion, String caseId, String split) {
    }

    private record ResultRecord(String schemaVersion, String caseId, String runId, String criterionId, Outcome outcome, Double value, String judgeActorId, JudgmentMethod judgmentMethod,
            List<String> evidenceRefs) {
    }

    private record Claim(String schemaVersion, String evaluationId, String targetDigest, List<ClaimMetric> metrics) {
    }

    private record ClaimMetric(String metricId, double numerator, double denominator, double value, Compare comparator, double threshold, boolean passed) {
    }

    /** The aggregation rule is a caller artifact with no contract of its own: only the members this check reads are bound. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AggregationRule(String schemaVersion, Denominator denominator, List<String> metricIds) {
    }

    /** The denominators a rule may name: the request schema's closed set ({@code metrics[].denominator}). A value outside it fails the binding, which {@link #aggregationRuleCheck} answers as an invalid rule. */
    private enum Denominator {
        @JsonProperty("all-expected-records") ALL_EXPECTED_RECORDS
    }

    // ---- the report (evaluation-validity-report.v1.schema.json); component order is the report's key order

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Report(String schemaVersion, String auditId, Stage auditStage, String auditedAt, String auditorActorId, String requestDigest, Target target, List<Binding> artifactBindings,
            List<CheckResult> checks, Coverage coverage, List<MetricResult> recomputedMetrics, List<Binding> verifiedEvidenceIndex, List<Code> blockingCodes, List<String> limitations,
            boolean qualifiesAsQualityOrReleaseEvidence, Status verdict, String reportDigest) {
        /** The same report with this digest; a null digest is the report as it is digested (the member is left out). */
        Report withDigest(String digest) {
            return new Report(schemaVersion, auditId, auditStage, auditedAt, auditorActorId, requestDigest, target, artifactBindings, checks, coverage, recomputedMetrics, verifiedEvidenceIndex,
                    blockingCodes, limitations, qualifiesAsQualityOrReleaseEvidence, verdict, digest);
        }
    }

    private record Binding(String artifactId, Role role, String digest) {
    }

    private record CheckResult(CheckId checkId, Status status, Code code, List<String> evidenceRefs) {
    }

    private record Coverage(long expectedCases, long observedCases, long expectedRuns, long observedRuns, long expectedResults, long observedResults) {
    }

    private record MetricResult(String metricId, Number numerator, long denominator, Number value, Compare comparator, Number threshold, Number claimedValue, boolean claimMatches,
            boolean thresholdPassed) {
    }

    // ---- other answers

    private record RequestDigest(String schemaVersion, String artifactId, String digest) {
    }

    private record ValidationResult(String schemaVersion, boolean valid, List<String> errors) {
    }

    /**
     * A validation document as the caller gave it. {@code request} and {@code report} stay raw trees: the request's digest is the digest of the tree as given,
     * and the report is compared as given with the one this check derives; both are bound to their records after the contract gate.
     */
    private record Parts(String schemaVersion, JsonNode request, JsonNode requestArtifact, JsonNode report) {
    }

    private record RequestArtifact(String artifactId, String locator, String digest) {
    }

    private record Validation(List<String> errors, Report report) {
    }

    // ---- what is read of the artifacts' files

    private record State(Artifact artifact, boolean exists, boolean digestMatches, byte[] bytes) {
        String id() {
            return artifact.artifactId();
        }

        Role role() {
            return artifact.role();
        }
    }

    private record Inventory(Map<String, State> byId, Map<Role, List<State>> byRole) {
        List<State> role(Role role) {
            return byRole.getOrDefault(role, List.of());
        }

        State one(Role role) {
            List<State> found = role(role);
            return found.size() == 1 ? found.get(0) : null;
        }

        List<State> among(Set<Role> roles) {
            return byId.values().stream().filter(s -> roles.contains(s.role())).toList();
        }
    }

    private record Lines<T>(boolean ok, Code code, List<T> records) {
    }

    private record CaseManifest(CheckResult check, List<CaseRecord> records) {
    }

    private record Results(CheckResult check, List<ResultRecord> records) {
    }

    private record Recomputed(CheckResult check, List<MetricResult> metrics) {
    }

    /** One expected result: the run, the case and the criterion it answers. */
    private record Slot(String runId, String caseId, String criterionId) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    private final JsonMapper mapper;

    public EvaluationValidityCheck(SkillSchemas schemas, Messages messages, JsonMapper mapper) {
        this.schemas = schemas;
        this.messages = messages;
        this.mapper = mapper;
    }

    @Override
    public String skill() {
        return "evaluation-validity-auditor";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        Format format = format(args.text("format"));
        return switch (command) {
            case DIGEST_REQUEST -> SkillCheckResult.ok(digestRequest(args.document("input")));
            case ANALYZE -> {
                Report report = analyze(args.document("input"), artifactRoot(args), args.files());
                yield SkillCheckResult.ok(format == Format.HUMAN_KO ? renderKo(report) : report);
            }
            case VALIDATE_REPORT -> {
                JsonNode document = args.has("request")
                        ? mapper.valueToTree(new Parts(VERSION, args.document("request"), args.document("request-artifact"), args.document("report")))
                        : args.document("input");
                Validation validation = validateReport(document, artifactRoot(args), args.files());
                boolean valid = validation.errors().isEmpty();
                Object value = format == Format.HUMAN_KO
                        ? (valid ? messages.forRequest(KO + "validatePassed", Map.of()) + "\n" + renderKo(validation.report())
                                : messages.forRequest(KO + "validateFailed", SennardException.args("count", validation.errors().size())) + "\n")
                        : new ValidationResult(VERSION, valid, validation.errors());
                yield new SkillCheckResult(value, valid ? 0 : 1);
            }
        };
    }

    /** The format argument, converted by Jackson from its wire name; absent means JSON. */
    private Format format(String text) {
        if (text == null) {
            return Format.JSON;
        }
        Format format;
        try {
            format = mapper.convertValue(text, Format.class);
        } catch (JacksonException | IllegalArgumentException unknown) {
            throw new InvalidInputException(InputCode.EVALUATION_VALIDITY_FORMAT_INVALID, Map.of(), Map.of(), unknown);
        }
        need(format != null, InputCode.EVALUATION_VALIDITY_FORMAT_INVALID);
        return format;
    }

    private static String artifactRoot(SkillArguments args) {
        String root = args.text("artifact-root");
        need(root != null && !root.isEmpty(), InputCode.EVALUATION_VALIDITY_ARTIFACT_ROOT_REQUIRED);
        return args.files().stat(root).orElseThrow(() -> new InvalidInputException(InputCode.EVALUATION_VALIDITY_ARTIFACT_ROOT_UNRESOLVED)).realPath();
    }

    private static CheckResult check(CheckId checkId, Status status, Code code, Collection<String> refs) {
        return new CheckResult(checkId, status, code, refs.stream().distinct().sorted().toList());
    }

    private static CheckResult check(CheckId checkId, Status status, Code code) {
        return new CheckResult(checkId, status, code, List.of());
    }

    /** What the request names, read through the file boundary: an escaping or absolute locator is refused there, absence is the only outcome that is not a refusal. */
    private static Inventory inspect(Request request, String root, FileFacts files) {
        Map<String, State> states = new LinkedHashMap<>();
        for (Artifact artifact : request.artifacts()) {
            String id = artifact.artifactId();
            need(!states.containsKey(id), InputCode.EVALUATION_VALIDITY_DUPLICATE_ARTIFACT_ID, "artifactId", id);
            if (!files.existsUnderRoot(root, artifact.locator())) {
                states.put(id, new State(artifact, false, false, null));
                continue;
            }
            byte[] bytes = files.readUnderRoot(root, artifact.locator(), SkillArguments.DOCUMENT_LIMIT);
            states.put(id, new State(artifact, true, Objects.equals(Checks.hash(bytes), artifact.digest()), bytes));
        }
        Map<Role, List<State>> byRole = states.values().stream().collect(Collectors.groupingBy(State::role, () -> new EnumMap<>(Role.class), Collectors.toList()));
        return new Inventory(states, byRole);
    }

    private static List<String> ids(State state) {
        return state == null ? List.of() : List.of(state.id());
    }

    private static List<String> ids(Collection<State> states) {
        return states.stream().map(State::id).toList();
    }

    private static CheckResult artifactIntegrity(Request request, Inventory inventory) {
        Set<Role> required = request.auditStage() == Stage.POST_EXECUTION ? POST_REQUIRED : CONTROL_ROLES;
        boolean missingRole = required.stream().anyMatch(role -> role == Role.RESULT_RECORDS ? inventory.role(role).isEmpty() : inventory.role(role).size() != 1);
        boolean duplicateRole = required.stream().anyMatch(role -> role != Role.RESULT_RECORDS && inventory.role(role).size() > 1);
        List<String> refs = ids(inventory.byId().values());
        if (duplicateRole) {
            return check(CheckId.ARTIFACT_INTEGRITY, Status.FAIL, Code.REQUIRED_ARTIFACT_ROLE_INVALID, refs);
        }
        if (missingRole) {
            return check(CheckId.ARTIFACT_INTEGRITY, Status.BLOCKED, Code.REQUIRED_ARTIFACT_ROLE_INVALID, refs);
        }
        if (inventory.byId().values().stream().anyMatch(s -> s.exists() && !s.digestMatches())) {
            return check(CheckId.ARTIFACT_INTEGRITY, Status.FAIL, Code.DIGEST_MISMATCH, refs);
        }
        if (inventory.byId().values().stream().anyMatch(s -> !s.exists())) {
            return check(CheckId.ARTIFACT_INTEGRITY, Status.BLOCKED, Code.ARTIFACT_MISSING, refs);
        }
        return check(CheckId.ARTIFACT_INTEGRITY, Status.PASS, Code.CHECK_PASSED, refs);
    }

    /** The JSON Lines of a state's file, each line gated by its schema and bound to its record; the records read before a failure stay in the answer. */
    private <T> Lines<T> parseJsonl(State state, String schema, Class<T> type, Code schemaCode) {
        if (state == null || !state.exists() || !state.digestMatches()) {
            return new Lines<>(false, Code.ARTIFACT_MISSING, List.of());
        }
        if (state.artifact().mediaType() != MediaType.JSONL) {
            return new Lines<>(false, schemaCode, List.of());
        }
        List<T> records = new ArrayList<>();
        try {
            // The bytes are the packaged file's, read through FileFacts; Jackson's multi-value reader would also accept values that share or span lines, which JSON Lines does not.
            for (String line : (Iterable<String>) new String(state.bytes(), StandardCharsets.UTF_8).lines()::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if (!schemas.valid(schema, node)) {
                    return new Lines<>(false, schemaCode, records);
                }
                records.add(schemas.bind(node, type));
            }
        } catch (JacksonException malformed) {
            LOG.debug("{} holds a line that is not JSON", state.id(), malformed);
            return new Lines<>(false, Code.JSONL_PARSE_FAILED, records);
        }
        return new Lines<>(true, Code.CHECK_PASSED, records);
    }

    /** The aggregate claim a state holds, or null when it is missing, changed, not JSON or not a claim. */
    private Claim claimOf(State state) {
        if (state == null || !state.exists() || !state.digestMatches() || state.artifact().mediaType() != MediaType.JSON) {
            return null;
        }
        try {
            JsonNode value = mapper.readTree(state.bytes());
            return value.isObject() && schemas.valid(CLAIM, value) ? schemas.bind(value, Claim.class) : null;
        } catch (JacksonException malformed) {
            LOG.debug("{} is not JSON", state.id(), malformed);
            return null;
        }
    }

    private static CheckResult frameCheck(Request request) {
        Instant frozen = Checks.instant(request.frozenAt());
        Instant audited = Checks.instant(request.auditedAt());
        Instant started = Checks.instant(request.executionStartedAt());
        Stage stage = request.auditStage();
        if (frozen.isAfter(audited)) {
            return check(CheckId.FRAME_FROZEN, Status.FAIL, Code.FRAME_FROZEN_AFTER_AUDIT);
        }
        if (started != null && frozen.isAfter(started)) {
            return check(CheckId.FRAME_FROZEN, Status.FAIL, Code.FRAME_FROZEN_AFTER_EXECUTION);
        }
        if (stage == Stage.PRE_EXECUTION && started != null && audited.isAfter(started)) {
            return check(CheckId.FRAME_FROZEN, Status.FAIL, Code.AUDIT_NOT_PRE_EXECUTION);
        }
        if (stage == Stage.POST_EXECUTION && started != null && audited.isBefore(started)) {
            return check(CheckId.FRAME_FROZEN, Status.FAIL, Code.AUDIT_NOT_POST_EXECUTION);
        }
        return check(CheckId.FRAME_FROZEN, Status.PASS, Code.CHECK_PASSED);
    }

    private CaseManifest caseManifest(Request request, Inventory inventory) {
        State state = inventory.one(Role.FIXTURE_MANIFEST);
        Lines<CaseRecord> parsed = parseJsonl(state, CASE, CaseRecord.class, Code.CASE_SCHEMA_INVALID);
        List<String> refs = ids(state);
        if (!parsed.ok()) {
            return new CaseManifest(check(CheckId.CASE_MANIFEST_COMPLETE, parsed.code() == Code.ARTIFACT_MISSING ? Status.BLOCKED : Status.FAIL, parsed.code(), refs), parsed.records());
        }
        List<String> seen = parsed.records().stream().map(CaseRecord::caseId).toList();
        List<String> expected = request.expected().caseIds();
        Set<String> seenSet = new HashSet<>(seen);
        Set<String> expectedSet = new HashSet<>(expected);
        Code code = !Checks.allUnique(seen) ? Code.CASE_DUPLICATE
                : !seenSet.containsAll(expectedSet) ? Code.CASE_MISSING
                        : !expectedSet.containsAll(seenSet) ? Code.CASE_UNKNOWN
                                : !seen.equals(expected) ? Code.CASE_ORDER_MISMATCH : null;
        return new CaseManifest(code == null ? check(CheckId.CASE_MANIFEST_COMPLETE, Status.PASS, Code.CHECK_PASSED, refs) : check(CheckId.CASE_MANIFEST_COMPLETE, Status.FAIL, code, refs), parsed.records());
    }

    private static CheckResult roleCheck(Request request) {
        Actors actors = request.actors();
        Set<String> authors = new HashSet<>(actors.authorIds());
        Set<String> executors = new HashSet<>(actors.executorIds());
        String auditor = actors.auditorId();
        boolean conflict = authors.contains(auditor) || executors.contains(auditor) || actors.judgeIds().contains(auditor)
                || actors.judgeIds().stream().anyMatch(id -> authors.contains(id) || executors.contains(id));
        // A claimed identifier's difference is no observation that the workers were really separate; v1 has no trusted input for that.
        return conflict ? check(CheckId.ROLE_INDEPENDENCE, Status.FAIL, Code.ROLE_CONFLICT) : check(CheckId.ROLE_INDEPENDENCE, Status.BLOCKED, Code.PROVENANCE_MISSING);
    }

    private static CheckResult methodCheck(Request request, Inventory inventory) {
        List<Criterion> criteria = request.criteria();
        List<String> ids = criteria.stream().map(Criterion::criterionId).toList();
        if (!Checks.sameSet(ids, request.expected().criterionIds())) {
            return check(CheckId.JUDGMENT_METHOD_VALID, Status.FAIL, Code.JUDGMENT_METHOD_MISMATCH);
        }
        List<String> all = new ArrayList<>();
        for (Criterion criterion : criteria) {
            List<JudgmentMethod> methods = criterion.judgmentMethods();
            List<String> refs = criterion.evidenceRefs();
            CriterionKind kind = criterion.kind();
            boolean selfReportOnly = methods.size() == 1 && methods.get(0) == JudgmentMethod.SELF_REPORT;
            if (kind == CriterionKind.SEMANTIC && !methods.contains(JudgmentMethod.INDEPENDENT_REVIEW)) {
                return check(CheckId.JUDGMENT_METHOD_VALID, Status.FAIL,
                        selfReportOnly ? Code.SELF_REPORT_ONLY : methods.contains(JudgmentMethod.LEXICAL_MATCH) ? Code.SEMANTIC_LEXICAL_ONLY : Code.JUDGMENT_METHOD_MISMATCH, refs);
            }
            if (kind == CriterionKind.DETERMINISTIC && methods.stream().noneMatch(EXACT_METHODS::contains)) {
                return check(CheckId.JUDGMENT_METHOD_VALID, Status.FAIL, selfReportOnly ? Code.SELF_REPORT_ONLY : Code.JUDGMENT_METHOD_MISMATCH, refs);
            }
            String oracleId = criterion.oracleArtifactId();
            boolean named = oracleId != null && !oracleId.isEmpty();
            State oracle = named ? inventory.byId().get(oracleId) : null;
            List<String> oracleRefs = named ? List.of(oracleId) : List.of();
            if (oracle == null || !oracle.exists()) {
                return check(CheckId.JUDGMENT_METHOD_VALID, Status.BLOCKED, Code.ARTIFACT_MISSING, oracleRefs);
            }
            if (oracle.role() != Role.ORACLE || !oracle.digestMatches() || !inventory.byId().keySet().containsAll(refs)) {
                List<String> combined = new ArrayList<>(oracleRefs);
                combined.addAll(refs);
                return check(CheckId.JUDGMENT_METHOD_VALID, Status.FAIL, Code.ORACLE_BINDING_INVALID, combined);
            }
            if (kind == CriterionKind.SEMANTIC && refs.isEmpty()) {
                return check(CheckId.JUDGMENT_METHOD_VALID, Status.BLOCKED, Code.PROVENANCE_MISSING);
            }
            all.addAll(oracleRefs);
            all.addAll(refs);
        }
        return check(CheckId.JUDGMENT_METHOD_VALID, Status.PASS, Code.CHECK_PASSED, all);
    }

    private CheckResult aggregationRuleCheck(Request request, Inventory inventory) {
        State state = inventory.one(Role.AGGREGATION_RULE);
        if (state == null || !state.exists() || !state.digestMatches()) {
            return check(CheckId.AGGREGATION_RULE_VALID, Status.BLOCKED, Code.ARTIFACT_MISSING, ids(state));
        }
        if (state.artifact().mediaType() != MediaType.JSON) {
            return check(CheckId.AGGREGATION_RULE_VALID, Status.FAIL, Code.AGGREGATION_INVALID, ids(state));
        }
        AggregationRule rule;
        try {
            rule = mapper.readValue(state.bytes(), AggregationRule.class);
        } catch (JacksonException notARule) {
            LOG.debug("{} is not an aggregation rule", state.id(), notARule);
            return check(CheckId.AGGREGATION_RULE_VALID, Status.FAIL, Code.AGGREGATION_INVALID, ids(state));
        }
        List<String> metricIds = request.metrics().stream().map(Metric::metricId).toList();
        Set<String> expectedCriteria = new HashSet<>(request.expected().criterionIds());
        boolean valid = rule != null && VERSION.equals(rule.schemaVersion()) && rule.denominator() == Denominator.ALL_EXPECTED_RECORDS && rule.metricIds() != null && Checks.sameSet(rule.metricIds(), metricIds)
                && request.metrics().stream().allMatch(m -> expectedCriteria.contains(m.criterionId()));
        return check(CheckId.AGGREGATION_RULE_VALID, valid ? Status.PASS : Status.FAIL, valid ? Code.CHECK_PASSED : Code.AGGREGATION_INVALID, ids(state));
    }

    private static CheckResult provenanceCheck(Inventory inventory) {
        List<State> controls = inventory.among(CONTROL_ROLES);
        if (controls.stream().anyMatch(s -> s.artifact().provenance() == null)) {
            return check(CheckId.TIMING_PROVENANCE_VALID, Status.BLOCKED, Code.PROVENANCE_MISSING, ids(controls));
        }
        List<String> refs = controls.stream().flatMap(s -> s.artifact().provenance().evidenceRefs().stream()).toList();
        return check(CheckId.TIMING_PROVENANCE_VALID, Status.PASS, Code.CHECK_PASSED, refs);
    }

    private static CheckResult posthocCheck(Request request, Inventory inventory) {
        Instant started = Checks.instant(request.executionStartedAt());
        if (started == null) {
            return check(CheckId.NO_POSTHOC_INPUT, Status.PASS, Code.CHECK_PASSED);
        }
        List<State> controls = inventory.among(CONTROL_ROLES);
        boolean posthoc = controls.stream().anyMatch(s -> Checks.instant(s.artifact().availableAt()).isAfter(started)
                || (s.artifact().provenance() != null && Checks.instant(s.artifact().provenance().observedAt()).isAfter(started)));
        Instant audited = Checks.instant(request.auditedAt());
        List<State> results = inventory.among(POST_ROLES);
        boolean unavailable = request.auditStage() == Stage.POST_EXECUTION && results.stream().anyMatch(s -> {
            Instant available = Checks.instant(s.artifact().availableAt());
            Provenance provenance = s.artifact().provenance();
            Instant observed = provenance == null ? available : Checks.instant(provenance.observedAt());
            return available.isBefore(started) || available.isAfter(audited) || observed.isBefore(started) || observed.isAfter(audited);
        });
        if (posthoc) {
            return check(CheckId.NO_POSTHOC_INPUT, Status.FAIL, Code.POSTHOC_INPUT, ids(controls));
        }
        return unavailable ? check(CheckId.NO_POSTHOC_INPUT, Status.FAIL, Code.RESULT_EVIDENCE_NOT_AVAILABLE_AT_AUDIT, ids(results))
                : check(CheckId.NO_POSTHOC_INPUT, Status.PASS, Code.CHECK_PASSED, ids(controls));
    }

    private static CheckResult runCheck(Request request) {
        List<String> actual = request.runs().stream().map(Run::runId).toList();
        Set<String> actualSet = new HashSet<>(actual);
        Set<String> expected = new HashSet<>(request.expected().runIds());
        if (!Checks.allUnique(actual)) {
            return check(CheckId.RUN_SET_COMPLETE, Status.FAIL, Code.RUN_DUPLICATE);
        }
        if (!actualSet.containsAll(expected)) {
            return check(CheckId.RUN_SET_COMPLETE, Status.FAIL, Code.RUN_MISSING);
        }
        if (!expected.containsAll(actualSet)) {
            return check(CheckId.RUN_SET_COMPLETE, Status.FAIL, Code.RUN_UNKNOWN);
        }
        return check(CheckId.RUN_SET_COMPLETE, Status.PASS, Code.CHECK_PASSED, request.runs().stream().map(Run::resultArtifactId).filter(Objects::nonNull).toList());
    }

    private static Slot slot(ResultRecord record) {
        return new Slot(record.runId(), record.caseId(), record.criterionId());
    }

    private Results parseResults(Request request, Inventory inventory) {
        List<ResultRecord> records = new ArrayList<>();
        List<String> resultIds = ids(inventory.role(Role.RESULT_RECORDS));
        List<String> completed = new ArrayList<>();
        for (Run run : request.runs()) {
            if (run.status() != RunStatus.COMPLETED) {
                continue;
            }
            String runId = run.runId();
            completed.add(runId);
            String artifactId = run.resultArtifactId();
            State state = artifactId == null ? null : inventory.byId().get(artifactId);
            if (state == null || state.role() != Role.RESULT_RECORDS) {
                return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, Status.BLOCKED, Code.ARTIFACT_MISSING, artifactId == null ? List.of() : List.of(artifactId)), records);
            }
            Lines<ResultRecord> parsed = parseJsonl(state, RESULT, ResultRecord.class, Code.RESULT_SCHEMA_INVALID);
            if (!parsed.ok()) {
                return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, parsed.code() == Code.ARTIFACT_MISSING ? Status.BLOCKED : Status.FAIL, parsed.code(), ids(state)), records);
            }
            if (parsed.records().stream().anyMatch(r -> !Objects.equals(runId, r.runId()))) {
                records.addAll(parsed.records());
                return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, Status.FAIL, Code.RESULT_UNKNOWN, ids(state)), records);
            }
            records.addAll(parsed.records());
        }
        List<Slot> keys = records.stream().map(EvaluationValidityCheck::slot).toList();
        if (!Checks.allUnique(keys)) {
            return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, Status.FAIL, Code.RESULT_DUPLICATE, resultIds), records);
        }
        Set<Slot> expected = new HashSet<>();
        for (String run : completed) {
            for (String caseId : request.expected().caseIds()) {
                for (String criterionId : request.expected().criterionIds()) {
                    expected.add(new Slot(run, caseId, criterionId));
                }
            }
        }
        Set<Slot> keySet = new HashSet<>(keys);
        if (!keySet.containsAll(expected)) {
            return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, Status.FAIL, Code.RESULT_MISSING, resultIds), records);
        }
        if (!expected.containsAll(keySet)) {
            return new Results(check(CheckId.RESULT_RECORDS_COMPLETE, Status.FAIL, Code.RESULT_UNKNOWN, resultIds), records);
        }
        Map<String, Criterion> criteria = new LinkedHashMap<>();
        request.criteria().forEach(c -> criteria.put(c.criterionId(), c));
        Set<String> judges = new HashSet<>(request.actors().judgeIds());
        boolean invalid = records.stream().anyMatch(record -> {
            Criterion criterion = criteria.get(record.criterionId());
            JudgmentMethod method = record.judgmentMethod();
            boolean allowed = criterion != null && (criterion.kind() == CriterionKind.SEMANTIC ? method == JudgmentMethod.INDEPENDENT_REVIEW : EXACT_METHODS.contains(method));
            return criterion == null || !judges.contains(record.judgeActorId()) || !criterion.judgmentMethods().contains(method) || !allowed || !inventory.byId().keySet().containsAll(record.evidenceRefs());
        });
        return new Results(invalid ? check(CheckId.RESULT_RECORDS_COMPLETE, Status.FAIL, Code.JUDGMENT_METHOD_MISMATCH, resultIds) : check(CheckId.RESULT_RECORDS_COMPLETE, Status.PASS, Code.CHECK_PASSED, resultIds),
                records);
    }

    private static boolean compare(double value, Compare comparator, double threshold) {
        return switch (comparator) {
            case GE -> value >= threshold;
            case GT -> value > threshold;
            case EQ -> value == threshold;
            case LE -> value <= threshold;
            case LT -> value < threshold;
        };
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) <= 1e-12 * Math.max(1, Math.max(Math.abs(left), Math.abs(right)));
    }

    private static Recomputed recompute(Request request, List<ResultRecord> records, List<CheckResult> prerequisites) {
        if (prerequisites.stream().anyMatch(p -> p.status() == Status.FAIL)) {
            return new Recomputed(check(CheckId.AGGREGATE_RECOMPUTED, Status.FAIL, Code.AGGREGATION_INVALID), List.of());
        }
        if (prerequisites.stream().anyMatch(p -> p.status() == Status.BLOCKED)) {
            return new Recomputed(check(CheckId.AGGREGATE_RECOMPUTED, Status.BLOCKED, Code.ARTIFACT_MISSING), List.of());
        }
        Map<Slot, ResultRecord> bySlot = new LinkedHashMap<>();
        records.forEach(r -> bySlot.put(slot(r), r));
        List<MetricResult> metrics = new ArrayList<>();
        List<String> runs = request.expected().runIds();
        List<String> cases = request.expected().caseIds();
        for (Metric metric : request.metrics()) {
            List<ResultRecord> slots = new ArrayList<>();
            // A slot with no record counts in the denominator as an error: a missing result cannot improve a rate.
            for (String run : runs) {
                for (String caseId : cases) {
                    slots.add(bySlot.get(new Slot(run, caseId, metric.criterionId())));
                }
            }
            long denominator = slots.size();
            double numerator = 0;
            if (metric.operation() == Operation.RATE) {
                Set<Outcome> outcomes = Set.copyOf(metric.numeratorOutcomes());
                numerator = slots.stream().filter(s -> outcomes.contains(s == null ? Outcome.ERROR : s.outcome())).count();
            } else {
                for (ResultRecord slot : slots) {
                    Double value = slot == null ? null : slot.value();
                    if (value == null) {
                        continue;
                    }
                    if (!Double.isFinite(value)) {
                        return new Recomputed(check(CheckId.AGGREGATE_RECOMPUTED, Status.FAIL, Code.AGGREGATION_INVALID), List.of());
                    }
                    numerator += value;
                }
            }
            double value = metric.operation() == Operation.SUM ? numerator : numerator / denominator;
            double claimed = metric.claimedValue().doubleValue();
            double threshold = metric.threshold().doubleValue();
            metrics.add(new MetricResult(metric.metricId(), number(numerator), denominator, number(value), metric.comparator(), metric.threshold(), metric.claimedValue(), nearlyEqual(value, claimed),
                    compare(value, metric.comparator(), threshold)));
        }
        return new Recomputed(check(CheckId.AGGREGATE_RECOMPUTED, Status.PASS, Code.CHECK_PASSED), metrics);
    }

    /** A whole number stays an integer on the wire, as JSON readers of these reports expect. */
    private static Number number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < (1L << 53)) {
            return (long) value;
        }
        return value;
    }

    private CheckResult claimCheck(Request request, Inventory inventory, List<MetricResult> metrics, Status aggregateStatus) {
        if (aggregateStatus == Status.BLOCKED) {
            return check(CheckId.CLAIM_MATCHES, Status.BLOCKED, Code.ARTIFACT_MISSING);
        }
        if (aggregateStatus == Status.FAIL) {
            return check(CheckId.CLAIM_MATCHES, Status.FAIL, Code.AGGREGATION_INVALID);
        }
        State state = inventory.one(Role.AGGREGATE_CLAIM);
        if (state == null || !state.exists()) {
            return check(CheckId.CLAIM_MATCHES, Status.BLOCKED, Code.AGGREGATE_CLAIM_MISSING);
        }
        Claim claim = claimOf(state);
        if (claim == null) {
            return check(CheckId.CLAIM_MATCHES, Status.FAIL, Code.AGGREGATE_CLAIM_INVALID, ids(state));
        }
        Target target = request.target();
        if (!Objects.equals(claim.evaluationId(), target.evaluationId()) || !Objects.equals(claim.targetDigest(), target.digest())) {
            return check(CheckId.CLAIM_MATCHES, Status.FAIL, Code.AGGREGATE_MISMATCH, ids(state));
        }
        Map<String, ClaimMetric> claimedById = new LinkedHashMap<>();
        claim.metrics().forEach(m -> claimedById.put(m.metricId(), m));
        boolean matches = claim.metrics().size() == metrics.size() && metrics.stream().allMatch(metric -> {
            ClaimMetric claimed = claimedById.get(metric.metricId());
            return claimed != null && nearlyEqual(claimed.numerator(), metric.numerator().doubleValue()) && claimed.denominator() == metric.denominator()
                    && nearlyEqual(claimed.value(), metric.value().doubleValue()) && claimed.comparator() == metric.comparator() && nearlyEqual(claimed.threshold(), metric.threshold().doubleValue())
                    && claimed.passed() == metric.thresholdPassed() && metric.claimMatches();
        });
        return check(CheckId.CLAIM_MATCHES, matches ? Status.PASS : Status.FAIL, matches ? Code.CHECK_PASSED : Code.AGGREGATE_MISMATCH, ids(state));
    }

    /** The request document gate: a request that breaks its contract is refused with the validator's violations. */
    private void requireRequest(JsonNode input) {
        List<SkillSchemas.Violation> violations = schemas.violations(REQUEST, input);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.EVALUATION_VALIDITY_REQUEST_INVALID, Map.of(), SennardException.args("errors", violations));
        }
    }

    private RequestDigest digestRequest(JsonNode input) {
        requireRequest(input);
        return new RequestDigest(VERSION, "evaluation-validity-request", CanonicalJson.digest(input));
    }

    /** The report for a request document; the request's digest is the digest of the tree as given. */
    private Report analyze(JsonNode input, String artifactRoot, FileFacts files) {
        requireRequest(input);
        return analyze(schemas.bind(input, Request.class), CanonicalJson.digest(input), artifactRoot, files);
    }

    private Report analyze(Request request, String requestDigest, String artifactRoot, FileFacts files) {
        Inventory inventory = inspect(request, artifactRoot, files);
        CaseManifest manifest = caseManifest(request, inventory);
        List<CheckResult> checks = new ArrayList<>(List.of(artifactIntegrity(request, inventory), frameCheck(request), manifest.check(), roleCheck(request), methodCheck(request, inventory),
                aggregationRuleCheck(request, inventory), provenanceCheck(inventory), posthocCheck(request, inventory)));
        List<ResultRecord> records = List.of();
        List<MetricResult> metrics = List.of();
        boolean post = request.auditStage() == Stage.POST_EXECUTION;
        if (post) {
            CheckResult run = runCheck(request);
            Results results = parseResults(request, inventory);
            checks.add(run);
            checks.add(results.check());
            records = results.records();
            Recomputed aggregate = recompute(request, records, List.of(run, results.check(), manifest.check()));
            metrics = aggregate.metrics();
            checks.add(aggregate.check());
            checks.add(claimCheck(request, inventory, metrics, aggregate.check().status()));
        }
        Status verdict = checks.stream().anyMatch(c -> c.status() == Status.FAIL) ? Status.FAIL : checks.stream().anyMatch(c -> c.status() == Status.BLOCKED) ? Status.BLOCKED : Status.PASS;
        Expected expected = request.expected();
        Comparator<Binding> byArtifactId = Comparator.comparing(Binding::artifactId);
        List<Binding> bindings = request.artifacts().stream().map(a -> new Binding(a.artifactId(), a.role(), a.digest())).sorted(byArtifactId).toList();
        List<Binding> index = inventory.byId().values().stream().filter(s -> s.exists() && s.digestMatches()).map(s -> new Binding(s.id(), s.role(), s.artifact().digest())).sorted(byArtifactId).toList();
        List<Code> blocking = checks.stream().filter(c -> c.status() != Status.PASS).map(CheckResult::code).distinct().sorted(Comparator.comparing(Code::name)).toList();
        Set<String> limitations = new TreeSet<>(request.knownLimitations());
        limitations.addAll(OWN_LIMITATIONS);
        int expectedResults = expected.caseIds().size() * expected.runIds().size() * expected.criterionIds().size();
        Coverage coverage = new Coverage(expected.caseIds().size(), manifest.records().size(), expected.runIds().size(), request.runs().size(), expectedResults, records.size());
        Report unsealed = new Report(VERSION, request.auditId(), request.auditStage(), request.auditedAt(), request.actors().auditorId(), requestDigest, request.target(), bindings, checks, coverage,
                metrics, index, blocking, List.copyOf(limitations), post && verdict == Status.PASS, verdict, null);
        Report report = unsealed.withDigest(Checks.digest(mapper, unsealed));
        List<SkillSchemas.Violation> violations = schemas.violations(REPORT, mapper.valueToTree(report));
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.EVALUATION_VALIDITY_GENERATED_REPORT_INVALID, Map.of(), SennardException.args("errors", violations));
        }
        return report;
    }

    /**
     * The errors of a validation document: the request artifact and the report must bind the request as given, the report's digest must be its own, and the report must be what this check
     * derives from the request now. A report that breaks only its own contract is answered as an error of the validation, not refused.
     */
    private Validation validateReport(JsonNode validation, String artifactRoot, FileFacts files) {
        need(validation != null && validation.isObject(), InputCode.EVALUATION_VALIDITY_VALIDATION_NOT_OBJECT);
        List<SkillSchemas.Violation> violations = schemas.violations(VALIDATION, validation);
        if (!violations.isEmpty()) {
            boolean reportOnly = violations.stream().allMatch(v -> "/report".equals(v.instancePath()) || v.instancePath().startsWith("/report/"));
            if (reportOnly) {
                return new Validation(List.of(messages.forRequest(M + "reportSchemaFailed", SennardException.args("errors", mapper.writeValueAsString(violations)))), null);
            }
            throw new InvalidInputException(InputCode.EVALUATION_VALIDITY_VALIDATION_INPUT_INVALID, Map.of(), SennardException.args("errors", violations));
        }
        Parts parts = schemas.bind(validation, Parts.class);
        RequestArtifact requestArtifact = schemas.bind(parts.requestArtifact(), RequestArtifact.class);
        Request request = schemas.bind(parts.request(), Request.class);
        Report report = schemas.bind(parts.report(), Report.class);
        List<String> errors = new ArrayList<>();
        String requestDigest = CanonicalJson.digest(parts.request());
        if (!Objects.equals(requestDigest, requestArtifact.digest())) {
            errors.add(messages.forRequest(M + "requestArtifactDigestMismatch", Map.of()));
        }
        if (!Objects.equals(requestDigest, report.requestDigest())) {
            errors.add(messages.forRequest(M + "reportRequestDigestMismatch", Map.of()));
        }
        // The report's own digest covers every member but itself; the schema leaves no member unbound, so the record writes back what was given.
        if (!Objects.equals(Checks.digest(mapper, report.withDigest(null)), report.reportDigest())) {
            errors.add(messages.forRequest(M + "reportDigestMismatch", Map.of()));
        }
        Report fresh = analyze(request, requestDigest, artifactRoot, files);
        if (!Objects.equals(CanonicalJson.of(mapper.valueToTree(fresh)), CanonicalJson.of(parts.report()))) {
            errors.add(messages.forRequest(M + "reportStale", Map.of()));
        }
        return new Validation(errors, report);
    }

    /** {@code CASE_ORDER_MISMATCH} as {@code CaseOrderMismatch}: the tail of a catalog key. */
    private static String camel(String code) {
        return UNDERSCORE.splitAsStream(code).map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT)).collect(Collectors.joining());
    }

    private String ko(String key, Object... params) {
        return messages.forRequest(KO + key, SennardException.args(params));
    }

    private String statusText(Status status) {
        return ko("status" + camel(status.name()));
    }

    /** A number as the report's canonical text writes it. */
    private static String canonical(Number value) {
        return CanonicalJson.number(new BigDecimal(value.toString()));
    }

    /** Only a generated or revalidated report is rendered; no source artifact's prose reaches this text. */
    private String renderKo(Report report) {
        boolean pre = report.auditStage() == Stage.PRE_EXECUTION;
        Coverage c = report.coverage();
        Status verdict = report.verdict();
        List<String> lines = new ArrayList<>();
        lines.add(ko("reportConclusion", "status", statusText(verdict), "verdict", verdict.name(), "stage", pre ? "pre" : "post"));
        lines.add(ko(report.qualifiesAsQualityOrReleaseEvidence() ? "reportQualifies" : "reportNotQualified"));
        lines.add(verdict == Status.PASS ? ko("reportReasonAllPassed", "count", report.checks().size()) : ko("reportReasonNotPassed"));
        for (CheckResult item : report.checks()) {
            Object[] params = {"check", ko("check" + camel(item.checkId().name())), "status", statusText(item.status()), "reason", ko("reason" + camel(item.code().name())), "checkId", item.checkId().name(),
                    "code", item.code().name(), "refs", String.join(", ", item.evidenceRefs())};
            lines.add(item.evidenceRefs().isEmpty() ? ko("reportCheckLine", params) : ko("reportCheckLineWithEvidence", params));
        }
        lines.add(ko("reportCoverage", "observedCases", c.observedCases(), "expectedCases", c.expectedCases(), "observedRuns", c.observedRuns(), "expectedRuns", c.expectedRuns(), "observedResults",
                c.observedResults(), "expectedResults", c.expectedResults()));
        lines.add(ko(pre ? "reportPreExecutionNotChecked" : "reportDenominatorNote"));
        for (MetricResult m : report.recomputedMetrics()) {
            lines.add(ko("reportMetricLine", "metricId", m.metricId(), "numerator", canonical(m.numerator()), "denominator", Long.toString(m.denominator()), "value", canonical(m.value()), "comparator",
                    m.comparator().symbol(), "threshold", canonical(m.threshold()), "thresholdPassed", Boolean.toString(m.thresholdPassed()), "claimedValue", canonical(m.claimedValue()), "claimMatches",
                    Boolean.toString(m.claimMatches())));
        }
        if (!pre && report.recomputedMetrics().isEmpty()) {
            lines.add(ko("reportNoMetrics"));
        }
        lines.add(ko("reportLimitationsIntro"));
        for (String item : new LinkedHashSet<>(report.limitations())) {
            lines.add(ko("reportLimitationLine", "text", ko(LIMITATION_KEYS.getOrDefault(item, "limitationUnknown")), "code", item));
        }
        Target target = report.target();
        lines.add(ko("reportBindings", "auditId", report.auditId(), "evaluationId", target.evaluationId(), "identifier", target.identifier(), "digest", target.digest()));
        lines.add(ko("reportDigests", "requestDigest", report.requestDigest(), "reportDigest", report.reportDigest()));
        return String.join("\n", lines) + "\n";
    }
}
