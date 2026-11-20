// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** prompt-engineering and solid-design: a report's declared bindings to its request, checked against the request's exact bytes. Structure and binding only, never the quality of a finding. */
@Configuration(proxyBeanMethods = false)
public class ReportBindingChecks {
    private static final Logger LOG = LoggerFactory.getLogger(ReportBindingChecks.class);
    private static final String PROMPT ="skillScripts.promptEngineering.";
    private static final String SOLID = "skillScripts.solidDesign.";
    private static final String SOLID_CONTRACTS = "skills/solid-design/contracts/";
    private static final String SOLID_REQUEST = SOLID_CONTRACTS + "solid-design-request.v1.schema.json";
    private static final String SOLID_REPORT = SOLID_CONTRACTS + "solid-design-report.v1.schema.json";
    private static final String CONTRACTS = "skills/prompt-engineering/contracts/";
    private static final String REQUEST = CONTRACTS + "prompt-engineering-request.v1.schema.json";
    private static final String REPORT = CONTRACTS + "prompt-engineering-report.v1.schema.json";

    /** The commands of the report-binding checks, by the name a caller sends. */
    enum Command implements SkillCommand {
        CHECK_REPORT("check-report");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    enum Mode {
        @JsonProperty("create") CREATE, @JsonProperty("review") REVIEW, @JsonProperty("improve") IMPROVE, @JsonProperty("compare") COMPARE
    }

    enum Status {
        ANALYZED, NOT_APPLICABLE, NEEDS_INPUT
    }

    enum FindingKind {
        @JsonProperty("instruction") INSTRUCTION, @JsonProperty("context") CONTEXT, @JsonProperty("output-contract") OUTPUT_CONTRACT, @JsonProperty("example") EXAMPLE,
        @JsonProperty("evaluation") EVALUATION, @JsonProperty("resource") RESOURCE, @JsonProperty("non-prompt-cause") NON_PROMPT_CAUSE, @JsonProperty("preserve") PRESERVE
    }

    enum FindingBasis {
        @JsonProperty("observation") OBSERVATION, @JsonProperty("inference") INFERENCE, @JsonProperty("hypothesis") HYPOTHESIS
    }

    enum TraceStatus {
        @JsonProperty("preserved") PRESERVED, @JsonProperty("conflict") CONFLICT, @JsonProperty("unreviewed") UNREVIEWED
    }

    enum EvaluationState {
        NOT_RUN, OBSERVED
    }

    enum Conclusion {
        NOT_ESTABLISHED, IMPROVED, REGRESSED, MIXED, NO_CLEAR_DIFFERENCE
    }

    enum Split {
        @JsonProperty("development") DEVELOPMENT, @JsonProperty("holdout") HOLDOUT
    }

    record Requirement(String id, String text, String sourceRef) {
    }

    record Environment(String summary, List<String> verifiedRefs, List<String> unknowns) {
    }

    record Baseline(String ref, String digest) {
    }

    record PromptRequest(String schemaVersion, String requestId, Mode mode, String objective, String snapshotRef, List<String> targets, List<String> instructionRefs,
            List<String> evidenceRefs, List<Requirement> requirements, Environment environment, Baseline baseline) {
    }

    record Scope(List<String> reviewed, List<String> notReviewed) {
    }

    record Finding(String id, String target, FindingKind kind, FindingBasis basis, List<String> evidenceRefs, String claim, String impact, String recommendation) {
    }

    record Candidate(String id, String target, String text, String digest, String changeSummary) {
    }

    record Trace(String requirementId, TraceStatus status, List<String> candidateRefs, String explanation) {
    }

    record EvaluationCase(String id, String input, String expected, Split split, List<String> requirementIds) {
    }

    record Run(String id, String candidateId, String promptDigest, String baselineDigest, String datasetRef, String configurationRef, String resultRef) {
    }

    record Evaluation(EvaluationState state, Conclusion conclusion, List<EvaluationCase> cases, List<Run> runs, List<String> limitations) {
    }

    record Handoff(String owner, String reason, List<String> artifactRefs) {
    }

    record PromptReport(String schemaVersion, String requestId, String requestDigest, String snapshotRef, Status status, String summary, Scope scope, List<Finding> findings,
            List<Candidate> candidates, List<Trace> requirementTrace, Evaluation evaluation, List<String> limitations, List<Handoff> handoffs) {
    }

    enum Principle {
        SRP, OCP, LSP, ISP, DIP
    }

    enum SolidFindingKind {
        @JsonProperty("contract-violation") CONTRACT_VIOLATION, @JsonProperty("design-recommendation") DESIGN_RECOMMENDATION, @JsonProperty("preserve-boundary") PRESERVE_BOUNDARY
    }

    record SolidRequest(String schemaVersion, String requestId, String objective, String snapshotRef, List<String> targets, List<String> instructionRefs, List<String> contractRefs,
            List<String> priorFindingRefs) {
    }

    record SolidFinding(String id, SolidFindingKind kind, List<Principle> principles, String target, List<String> evidenceRefs, String observation, String reasoning,
            List<String> contractRefs, String recommendation, List<String> preserve, List<String> verificationNeeds, List<String> relatedFindingRefs) {
    }

    record SolidReport(String schemaVersion, String requestId, String requestDigest, String snapshotRef, Status status, String summary, List<Principle> principlesConsidered, Scope scope,
            List<SolidFinding> findings, List<String> limitations, List<Handoff> handoffs) {
    }

    /** The answer when a document could not be judged against the other: no scope statement. */
    record Unjudged(boolean valid, List<String> errors) {
    }

    record Judged(boolean valid, List<String> errors, String validationScope) {
    }

    private final Messages messages;
    private final JsonMapper mapper;

    public ReportBindingChecks(Messages messages, JsonMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    // @Bean: one binding-check SkillCheck class made once per skill (its name and its judgment are the settings); the same class twice.
    @Bean
    SkillCheck<Command> promptEngineeringCheck(SkillSchemas schemas) {
        return simple("prompt-engineering", args -> {
            byte[] requestBytes = args.documentBytes("request");
            return checkPrompt(schemas, requestBytes, args.document("report"));
        });
    }

    // @Bean: the second instance of the binding-check SkillCheck class, for the other skill.
    @Bean
    SkillCheck<Command> solidDesignCheck(SkillSchemas schemas) {
        return simple("solid-design", args -> {
            byte[] requestBytes = args.documentBytes("request");
            return checkSolid(schemas, requestBytes, args.document("report"));
        });
    }

    private interface Judgment {
        SkillCheckResult apply(SkillArguments args);
    }

    private static SkillCheck<Command> simple(String skill, Judgment judgment) {
        return new SkillCheck<Command>() {
            @Override
            public String skill() {
                return skill;
            }

            @Override
            public Class<Command> commandType() {
                return Command.class;
            }

            @Override
            public SkillCheckResult run(Command command, SkillArguments arguments) {
                return judgment.apply(arguments);
            }
        };
    }

    private String p(String key, Object... params) {
        return messages.forRequest(PROMPT + key, SennardException.args(params));
    }

    private String s(String key, Object... params) {
        return messages.forRequest(SOLID + key, SennardException.args(params));
    }

    private static boolean duplicates(List<String> values) {
        return !Checks.allUnique(values);
    }

    private static boolean unbound(Collection<String> values, Set<String> allowed) {
        return !allowed.containsAll(values);
    }

    private static List<String> schemaErrors(SkillSchemas schemas, String location, JsonNode value, String label) {
        List<String> errors = new ArrayList<>();
        List<SkillSchemas.Violation> violations = schemas.violations(location, value);
        if (!violations.isEmpty()) {
            errors.add(label + ": " + violations.stream().map(v -> v.instancePath() + " " + v.message()).collect(Collectors.joining(", ")));
        }
        return errors;
    }

    private SkillCheckResult checkPrompt(SkillSchemas schemas, byte[] requestBytes, JsonNode reportDocument) {
        JsonNode requestDocument;
        try {
            requestDocument = mapper.readTree(requestBytes);
        } catch (JacksonException notJson) {
            // The request's own bytes are what the report binds to, so a request that is not JSON is a finding about it, not a refused call:
            // the answer carries the finding, and this log ends the handling of the parse failure.
            LOG.debug("prompt-engineering: the request is not JSON", notJson);
            return SkillCheckResult.of(new Unjudged(false, List.of(p("requestNotJson"))), false);
        }
        List<String> errors = new ArrayList<>(schemaErrors(schemas, REQUEST, requestDocument, "request"));
        errors.addAll(schemaErrors(schemas, REPORT, reportDocument, "report"));
        if (!errors.isEmpty()) {
            return SkillCheckResult.of(new Unjudged(false, errors), false);
        }
        PromptRequest request = schemas.bind(requestDocument, PromptRequest.class);
        PromptReport report = schemas.bind(reportDocument, PromptReport.class);
        if (!Objects.equals(report.requestId(), request.requestId())) {
            errors.add(p("requestIdMismatch"));
        }
        if (!Objects.equals(Checks.hash(requestBytes), report.requestDigest())) {
            errors.add(p("requestDigestMismatch"));
        }
        if (!Objects.equals(report.snapshotRef(), request.snapshotRef())) {
            errors.add(p("snapshotRefMismatch"));
        }
        Set<String> targets = new HashSet<>(request.targets());
        List<String> reviewed = report.scope().reviewed();
        Set<String> reviewedSet = new HashSet<>(reviewed);
        List<String> covered = new ArrayList<>(reviewed);
        covered.addAll(report.scope().notReviewed());
        if (duplicates(covered)) {
            errors.add(p("duplicateScopeTarget"));
        }
        if (unbound(covered, targets)) {
            errors.add(p("unboundScopeTarget"));
        }
        if (!new HashSet<>(covered).containsAll(targets)) {
            errors.add(p("scopeOmits"));
        }
        List<String> requirements = request.requirements().stream().map(Requirement::id).toList();
        Set<String> requirementSet = new HashSet<>(requirements);
        if (duplicates(requirements)) {
            errors.add(p("duplicateRequestRequirementId"));
        }
        List<String> traceIds = report.requirementTrace().stream().map(Trace::requirementId).toList();
        if (duplicates(traceIds)) {
            errors.add(p("duplicateRequirementTraceId"));
        }
        if (unbound(traceIds, requirementSet)) {
            errors.add(p("unboundRequirementTrace"));
        }
        if (!new HashSet<>(traceIds).containsAll(requirementSet)) {
            errors.add(p("traceOmits"));
        }
        Set<String> evidence = new HashSet<>(request.evidenceRefs());
        evidence.addAll(request.instructionRefs());
        request.requirements().forEach(item -> evidence.add(item.sourceRef()));
        Baseline baseline = request.baseline();
        if (baseline != null) {
            evidence.add(baseline.ref());
        }
        if (unbound(request.environment().verifiedRefs(), evidence)) {
            errors.add(p("unboundEnvironmentReference"));
        }
        List<Finding> findings = report.findings();
        if (duplicates(findings.stream().map(Finding::id).toList())) {
            errors.add(p("duplicateFindingId"));
        }
        for (Finding finding : findings) {
            if (!reviewedSet.contains(finding.target())) {
                errors.add(p("unboundFindingTarget"));
            }
            if (unbound(finding.evidenceRefs(), evidence)) {
                errors.add(p("unboundFindingEvidence"));
            }
        }
        List<Candidate> candidateList = report.candidates();
        List<String> candidates = candidateList.stream().map(Candidate::id).toList();
        Set<String> candidateIds = new HashSet<>(candidates);
        Set<String> candidateTargets = new HashSet<>();
        Map<String, Candidate> candidateById = new HashMap<>();
        if (duplicates(candidates)) {
            errors.add(p("duplicateCandidateId"));
        }
        for (Candidate candidate : candidateList) {
            candidateTargets.add(candidate.target());
            candidateById.putIfAbsent(candidate.id(), candidate);
            if (!reviewedSet.contains(candidate.target())) {
                errors.add(p("unboundCandidateTarget"));
            }
            if (!Objects.equals(Checks.hash(candidate.text()), candidate.digest())) {
                errors.add(p("promptDigestMismatch", "id", candidate.id()));
            }
        }
        List<Trace> trace = report.requirementTrace();
        for (Trace item : trace) {
            if (unbound(item.candidateRefs(), candidateIds)) {
                errors.add(p("unboundTraceCandidate"));
            }
        }
        if (report.status() == Status.ANALYZED && (request.mode() == Mode.CREATE || request.mode() == Mode.IMPROVE)) {
            if (candidates.isEmpty()) {
                errors.add(p("needsCandidate"));
            }
            if (request.targets().stream().anyMatch(target -> !candidateTargets.contains(target))) {
                errors.add(p("needsCandidateEveryTarget"));
            }
            if (trace.stream().anyMatch(item -> item.status() != TraceStatus.PRESERVED)) {
                errors.add(p("unresolvedAnalyzed"));
            }
            if (!candidates.isEmpty() && trace.stream().anyMatch(item -> item.candidateRefs().isEmpty())) {
                errors.add(p("candidateTraceAll"));
            }
        }
        Evaluation evaluation = report.evaluation();
        if (duplicates(evaluation.cases().stream().map(EvaluationCase::id).toList())) {
            errors.add(p("duplicateEvaluationCaseId"));
        }
        for (EvaluationCase testCase : evaluation.cases()) {
            if (unbound(testCase.requirementIds(), requirementSet)) {
                errors.add(p("unboundCaseRequirement"));
            }
        }
        List<Run> runs = evaluation.runs();
        if (duplicates(runs.stream().map(Run::id).toList())) {
            errors.add(p("duplicateEvaluationRunId"));
        }
        Set<String> requestEvidence = new HashSet<>(request.evidenceRefs());
        String baselineDigest = baseline == null ? null : baseline.digest();
        for (Run run : runs) {
            Candidate candidate = candidateById.get(run.candidateId());
            if (candidate == null || !Objects.equals(run.promptDigest(), candidate.digest())) {
                errors.add(p("runCandidateBinding", "id", run.id()));
            }
            if (!Objects.equals(run.baselineDigest(), baselineDigest)) {
                errors.add(p("runBaselineDigest", "id", run.id()));
            }
            if (unbound(List.of(run.datasetRef(), run.configurationRef(), run.resultRef()), requestEvidence)) {
                errors.add(p("unboundEvaluationEvidence"));
            }
        }
        if (evaluation.conclusion() != Conclusion.NOT_ESTABLISHED && baseline == null) {
            errors.add(p("conclusionNeedsBaseline"));
        }
        return SkillCheckResult.of(new Judged(errors.isEmpty(), errors, "structure-and-binding-only"), errors.isEmpty());
    }

    private SkillCheckResult checkSolid(SkillSchemas schemas, byte[] requestBytes, JsonNode reportDocument) {
        JsonNode requestDocument;
        try {
            requestDocument = mapper.readTree(requestBytes);
        } catch (JacksonException notJson) {
            // The request's own bytes are what the report binds to, so a request that is not JSON is a finding about it, not a refused call:
            // the answer carries the finding, and this log ends the handling of the parse failure.
            LOG.debug("solid-design: the request is not JSON", notJson);
            return SkillCheckResult.of(new Unjudged(false, List.of(s("requestNotJson"))), false);
        }
        List<String> errors = new ArrayList<>(schemaErrors(schemas, SOLID_REQUEST, requestDocument, "request"));
        errors.addAll(schemaErrors(schemas, SOLID_REPORT, reportDocument, "report"));
        if (!errors.isEmpty()) {
            return SkillCheckResult.of(new Unjudged(false, errors), false);
        }
        SolidRequest request = schemas.bind(requestDocument, SolidRequest.class);
        SolidReport report = schemas.bind(reportDocument, SolidReport.class);
        if (!Objects.equals(report.requestId(), request.requestId())) {
            errors.add(s("requestIdMismatch"));
        }
        if (!Objects.equals(Checks.hash(requestBytes), report.requestDigest())) {
            errors.add(s("requestDigestMismatch"));
        }
        if (!Objects.equals(report.snapshotRef(), request.snapshotRef())) {
            errors.add(s("snapshotRefMismatch"));
        }
        Set<String> targets = new HashSet<>(request.targets());
        List<String> reviewed = report.scope().reviewed();
        List<String> covered = new ArrayList<>(reviewed);
        covered.addAll(report.scope().notReviewed());
        if (duplicates(covered)) {
            errors.add(s("scopeDuplicate"));
        }
        if (unbound(covered, targets)) {
            errors.add(s("scopeUndeclared"));
        }
        if (!new HashSet<>(covered).containsAll(targets)) {
            errors.add(s("scopeOmits"));
        }
        List<SolidFinding> findings = report.findings();
        if (duplicates(findings.stream().map(SolidFinding::id).toList())) {
            errors.add(s("duplicateFinding"));
        }
        Set<String> reviewedSet = new HashSet<>(reviewed);
        Set<Principle> principles = new HashSet<>(report.principlesConsidered());
        Set<String> contractRefs = new HashSet<>(request.contractRefs());
        Set<String> priorRefs = new HashSet<>(request.priorFindingRefs());
        for (SolidFinding finding : findings) {
            if (!reviewedSet.contains(finding.target())) {
                errors.add(s("findingNotReviewed", "id", finding.id()));
            }
            if (!principles.containsAll(finding.principles())) {
                errors.add(s("principleNotConsidered", "id", finding.id()));
            }
            if (unbound(finding.contractRefs(), contractRefs)) {
                errors.add(s("contractRefUnbound", "id", finding.id()));
            }
            if (unbound(finding.relatedFindingRefs(), priorRefs)) {
                errors.add(s("priorRefUnbound", "id", finding.id()));
            }
        }
        return SkillCheckResult.of(new Judged(errors.isEmpty(), errors, "schema-and-binding-only"), errors.isEmpty());
    }
}
