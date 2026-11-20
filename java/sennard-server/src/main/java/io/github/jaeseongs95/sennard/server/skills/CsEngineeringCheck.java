// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimitSource;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * cs-engineering: a locked rule pack, constraint and review reports bound by digest to their requests, and a verdict recomputed from obligations. The pack is read
 * and verified once, when the check is built (it is packaged and does not change while the server runs); every command judges a caller's documents against it.
 */
@Component
public class CsEngineeringCheck implements SkillCheck<CsEngineeringCheck.Command> {
    private static final String M = "skillTools.csEngineering.";
    private static final String VERSION = "0.1.0";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String SKILL_ROOT = "skills/cs-engineering/";
    private static final String SCHEMA_ENGINE = "networknt-2020-12";
    /** A path a caller names must be portable: no backslash, drive colon, control character or DEL in it. */
    private static final Pattern FORBIDDEN_PATH_CHARACTER = Pattern.compile("[\\\\:\\x00-\\x1f\\x7f]");
    private static final Pattern SLASH = Pattern.compile("/");
    /** A file name some file systems reserve, with or without an extension. */
    private static final Pattern RESERVED_NAME = Pattern.compile("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?", Pattern.CASE_INSENSITIVE);

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        HELP("help"), SELF_CHECK("self-check"), CATALOG("catalog"), RULES("rules"), HANDOFF("handoff"), CHECK_STAGE_BUNDLE("check-stage-bundle"), VALIDATE_REQUEST("validate-request"),
        VALIDATE_CONSTRAINTS("validate-constraints"), VALIDATE_REVIEW("validate-review"), CHECK_BUNDLE("check-bundle"), PROVIDER_RESULT("provider-result");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** The contracts this check binds documents to; the name is also what a schema refusal says. */
    private enum Contract {
        REQUEST("cs-request"), CONSTRAINT_REPORT("cs-constraint-report"), REVIEW_REPORT("cs-review-report"), CANDIDATE("cs-candidate"), POLICY("cs-policy"), BINDING("cs-engineering-binding"),
        STAGE_BUNDLE("cs-stage-bundle");

        private final String wire;

        Contract(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        String location() {
            return SKILL_ROOT + "contracts/" + wire + ".v1.schema.json";
        }
    }

    /**
     * What a refusal names in its sentence: a catalog key has one wording for each of these (key#label=...), so a refusal says which digest, which repeated item or which
     * reference it found wrong. The catalog is told the name below, not the constant.
     */
    private enum Label {
        RULE_DIGEST("ruleDigest"), SELECTED_RULE("selectedRule"), KNOWLEDGE_PACK("knowledgePack"), REQUEST("request"), SOURCE_SNAPSHOT("sourceSnapshot"), BOUND_REQUEST("boundRequest"),
        BOUND_REPORT("boundReport"), BOUND_TASK("boundTask"), BOUND_KNOWLEDGE("boundKnowledge"), BOUND_POLICY("boundPolicy"), REVIEW_CONSTRAINT("reviewConstraint"),
        REVIEW_CANDIDATE("reviewCandidate"), EVIDENCE_CANDIDATE("evidenceCandidate"), RESULT_CANDIDATE("resultCandidate"), KNOWLEDGE_FILE("knowledgeFile"), MODULE("module"),
        CATALOGUE_RULE("catalogueRule"), SOURCE_REFERENCE("sourceReference"), SOURCE_PATH("sourcePath"), OPERATIONAL_FACT("operationalFact"), FACT("fact"), ASSUMPTION("assumption"),
        INVARIANT("invariant"), OBLIGATION("obligation"), DESIGN_CHOICE("designChoice"), CANDIDATE_FILE("candidateFile"), EVIDENCE("evidence"), FINDING("finding"),
        REVIEWED_INVARIANT("reviewedInvariant"), VERIFICATION_RESULT("verificationResult"), FACT_EVIDENCE("factEvidence"), INVARIANT_BASIS("invariantBasis"),
        SELECTED_INVARIANT_RULE("selectedInvariantRule"), CHOICE_BASIS("choiceBasis"), OBLIGATION_INVARIANT("obligationInvariant"), RESULT_EVIDENCE("resultEvidence"),
        FINDING_EVIDENCE("findingEvidence");

        private final String wire;

        Label(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------- the packaged knowledge

    enum RuleStatus {
        @JsonProperty("draft") DRAFT, @JsonProperty("validated") VALIDATED
    }

    private record LockFile(String path, String digest) {
    }

    private record Lock(String schemaVersion, String version, List<LockFile> files) {
    }

    private record KnowledgeModule(String id, String title, RuleStatus status, String reference, String ruleFile, List<String> ruleIds) {
    }

    private record CatalogueRule(String id, String domain, String title, RuleStatus status, String path, String digest) {
    }

    private record Catalogue(String schemaVersion, String name, String version, Map<String, String> moduleStatusMeaning, List<KnowledgeModule> modules, List<CatalogueRule> rules) {
    }

    /** One rule card, exactly the members the rule files carry (the pack is read refusing any other member, so the record holds the whole card the {@code rules} command answers). */
    record Rule(String id, String version, String domain, RuleStatus status, String title, List<String> appliesWhen, List<String> assumptions, String principle, String invariant,
            List<String> failureModes, List<String> decisionQuestions, List<String> verificationObligations, List<String> nonGoals, String validationScope) {
    }

    private record PackRule(Rule card, String digest) {
    }

    private record Pack(String digest, Map<String, PackRule> rules, Map<String, KnowledgeModule> modules) {
    }

    // ------------------------------------------------------------------------------------------------------------------- the documents judged

    enum RequestMode {
        @JsonProperty("derive") DERIVE, @JsonProperty("review") REVIEW
    }

    enum PolicyMode {
        @JsonProperty("observe") OBSERVE, @JsonProperty("enforce") ENFORCE
    }

    enum Applicability {
        @JsonProperty("applicable") APPLICABLE, @JsonProperty("not-applicable") NOT_APPLICABLE
    }

    enum InvariantLevel {
        @JsonProperty("required-invariant") REQUIRED_INVARIANT, @JsonProperty("design-choice") DESIGN_CHOICE, @JsonProperty("recommendation") RECOMMENDATION
    }

    enum ObligationLevel {
        @JsonProperty("required") REQUIRED, @JsonProperty("advisory") ADVISORY
    }

    enum Method {
        @JsonProperty("static-review") STATIC_REVIEW, @JsonProperty("dynamic-test") DYNAMIC_TEST, @JsonProperty("property-test") PROPERTY_TEST, @JsonProperty("load-test") LOAD_TEST,
        @JsonProperty("fault-injection") FAULT_INJECTION, @JsonProperty("formal-proof") FORMAL_PROOF
    }

    enum FindingStatus {
        SATISFIED, VIOLATED, UNVERIFIED, NOT_APPLICABLE
    }

    enum ExecutionStatus {
        EXECUTED, NOT_RUN, UNSUPPORTED
    }

    enum Observation {
        PASS, FAIL, UNKNOWN
    }

    /** What a verdict means to the caller: the exit code of the answer, and the error a provider result carries for it. */
    enum Standing {
        HOLDS(0, null), VIOLATED(1, ProviderCode.GATE_FAILED), INCOMPLETE(1, ProviderCode.MISSING_EVIDENCE);

        private final int exitCode;
        private final ProviderCode error;

        Standing(int exitCode, ProviderCode error) {
            this.exitCode = exitCode;
            this.error = error;
        }

        int exitCode() {
            return exitCode;
        }

        ProviderCode error() {
            return error;
        }
    }

    enum ConstraintVerdict {
        READY(Standing.HOLDS), NEEDS_INPUT(Standing.INCOMPLETE), NEEDS_REDESIGN(Standing.VIOLATED), BLOCKED(Standing.INCOMPLETE);

        private final Standing standing;

        ConstraintVerdict(Standing standing) {
            this.standing = standing;
        }

        Standing standing() {
            return standing;
        }
    }

    enum ReviewVerdict {
        PASS(Standing.HOLDS), FAIL(Standing.VIOLATED), BLOCKED(Standing.INCOMPLETE);

        private final Standing standing;

        ReviewVerdict(Standing standing) {
            this.standing = standing;
        }

        Standing standing() {
            return standing;
        }
    }

    /** A file a document names: its path under the artifact root and the digest of its bytes. */
    record Ref(String path, String digest) {
    }

    record SourceRef(String id, String path, String digest) {
    }

    record OperationalFact(String id, String statement, List<String> evidenceRefs) {
    }

    record Request(String schemaVersion, String requestId, RequestMode mode, String objective, String sourceSnapshotDigest, List<SourceRef> sourceRefs, List<OperationalFact> operationalFacts,
            String existingContractRef) {
    }

    record DraftUse(boolean allowed, String basis) {
    }

    record SelectedRule(String ruleId, String ruleDigest, String applicabilityReason) {
    }

    record Fact(String id, String statement, List<String> evidenceRefs) {
    }

    record Assumption(String id, String statement, String verificationPlan) {
    }

    record Invariant(String id, String statement, String scope, List<String> basisRefs, InvariantLevel requirementLevel, List<String> ruleIds) {
    }

    record DesignChoice(String id, String statement, String rationale, List<String> basisRefs) {
    }

    record Obligation(String id, List<String> invariantIds, Method method, String requiredEnvironment, String failureOracle, ObligationLevel requirementLevel) {
    }

    /** What a provider answers with: a constraint report or a review report. */
    sealed interface ProviderOutput permits ConstraintReport, ReviewReport {
    }

    record ConstraintReport(String schemaVersion, String reportId, String requestDigest, String sourceSnapshotDigest, String knowledgePackVersion, String knowledgePackDigest, DraftUse draftUse,
            Applicability applicability, String applicabilityReason, List<SelectedRule> selectedRules, List<Fact> facts, List<Assumption> assumptions, List<Invariant> invariants,
            List<DesignChoice> designChoices, List<Obligation> verificationObligations, List<String> requiredCapabilities, ConstraintVerdict verdict) implements ProviderOutput {
    }

    record Candidate(String schemaVersion, String scopeSummary, List<Ref> files) {
    }

    record Policy(String schemaVersion, String policyId, String version, PolicyMode mode, boolean allowDraft) {
    }

    record Binding(String schemaVersion, Ref requestRef, String requestDigest, Ref constraintReportRef, String constraintReportDigest, String taskDigest, String knowledgePackVersion,
            String knowledgePackDigest, String policyId, String policyVersion, String policyDigest, PolicyMode mode, List<String> requiredObligationIds) {
    }

    record StageBundle(String schemaVersion, Ref binding, Ref task, Ref policy, Ref review, Ref candidate) {
    }

    record Finding(String id, String invariantId, FindingStatus status, List<String> evidenceRefs, String reasoningSummary) {
    }

    record VerificationResult(String obligationId, ExecutionStatus executionStatus, Observation result, String targetDigest, String environmentRef, Method method, List<String> evidenceRefs) {
    }

    record Evidence(String id, String path, String digest, String targetDigest, String environmentRef, Method kind, String statement) {
    }

    record ReviewReport(String schemaVersion, String reviewId, String constraintReportDigest, String candidateDigest, String reviewerActorRef, List<Finding> findings,
            List<VerificationResult> verificationResults, List<Evidence> evidence, List<String> blockingFindingIds, List<String> unverifiedRequiredObligationIds, ReviewVerdict verdict)
            implements ProviderOutput {
    }

    // ---------------------------------------------------------------------------------------------------------------------------- the answers

    enum SelfCheckStatus {
        PASS
    }

    enum ValidationStatus {
        VALID
    }

    enum Kind {
        @JsonProperty("cs-bundle-check") BUNDLE_CHECK, @JsonProperty("cs-handoff") HANDOFF, @JsonProperty("output") OUTPUT
    }

    /** The code of the error a provider result carries; the sentence is this key under the check's catalog prefix. */
    enum ProviderCode {
        GATE_FAILED("gateFailed"), MISSING_EVIDENCE("missingEvidence");

        private final String messageKey;

        ProviderCode(String messageKey) {
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }

    /** The two things a provider result can be asked to validate. */
    private enum ProviderMode {
        DERIVE("derive"), REVIEW("review");

        private final String wire;

        ProviderMode(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    record Help(List<String> commands, String notes) {
    }

    record SelfCheck(SelfCheckStatus status, String scope, String schemaEngine, String version, String packDigest, int modules, int rules, long validatedRules) {
    }

    record CatalogEntry(String id, String domain, String title, RuleStatus status) {
    }

    record Catalog(String version, String packDigest, List<CatalogEntry> rules, String notice) {
    }

    record RuleList(List<Rule> rules) {
    }

    record RequestValid(ValidationStatus status, String scope, String schemaEngine) {
    }

    record ConstraintsValid(ValidationStatus status, String scope, ConstraintVerdict verdict, List<String> requiredObligationIds, String schemaEngine) {
    }

    record ReviewValid(ValidationStatus status, String scope, ReviewVerdict verdict, List<String> blockingFindingIds, List<String> unverifiedRequiredObligationIds, int requiredCount,
            String schemaEngine) {
    }

    /**
     * The answer of check-bundle and, with the three last members, of check-stage-bundle: the task the stage was judged for, which this check only digests (it is the caller's
     * task document, handed back as it came), the review it read and where it read it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BundleCheck(String schemaVersion, Kind kind, ReviewVerdict verdict, boolean canProceedUnderSuppliedPolicy, PolicyMode mode, String schemaEngine, String knowledgePackDigest,
            String candidateDigest, String constraintReportDigest, int checkedRawFiles, int requiredObligationCount, String assurance, JsonNode task, ReviewReport review, Ref reviewRef) {
    }

    record HandoffAnswer(String schemaVersion, Kind kind, String taskDigest, String bindingDigest, Ref constraintReportRef, String constraintReportDigest, String knowledgePackDigest,
            List<String> requiredObligationIds, String policyDigest, String assurance) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ProviderError(ProviderCode code, String message, Map<String, Object> details) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ProviderResult(String schemaVersion, Kind kind, ProviderOutput output, List<Object> artifacts, ProviderError error) {
    }

    // ------------------------------------------------------------------------------------------------------------------ working values

    /** A document bound to its record, with the digest of the document as it was given. */
    private record Doc<T>(T value, String digest) {
    }

    private record Validated(Map<String, Invariant> invariants, Map<String, Obligation> obligations, List<String> requiredObligationIds) {
    }

    /** A constraint report that passed every rule, with the request it was bound to when one was given. */
    private record Constrained(Doc<ConstraintReport> doc, Validated validated, Doc<Request> request) {
    }

    /** What a binding froze: the constraint report it names, the request it was derived from and the policy. */
    private record Frozen(Constrained constraint, Doc<Policy> policy) {
    }

    private record Assessment(ReviewVerdict verdict, List<String> blockingFindingIds, List<String> unverifiedRequiredObligationIds, int requiredCount) {
    }

    private final SkillSchemas schemas;
    private final SkillAssets assets;
    private final Messages messages;
    private final JsonMapper mapper;
    private final Pack pack;
    private final ResourceLimitSource limits;

    public CsEngineeringCheck(SkillSchemas schemas, Messages messages, JsonMapper mapper, ResourceLimitSource limits) {
        this.schemas = schemas;
        this.assets = schemas.assets();
        this.messages = messages;
        this.mapper = mapper;
        this.limits = limits;
        this.pack = loadPack();
    }

    @Override
    public String skill() {
        return "cs-engineering";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        ArtifactReader artifacts = new ArtifactReader(args, limits.current().csArtifactMaxBytes());
        return switch (command) {
            case HELP -> SkillCheckResult.ok(new Help(Arrays.stream(Command.values()).map(Command::wire).sorted().toList(), text("helpNotes")));
            case SELF_CHECK -> SkillCheckResult.ok(new SelfCheck(SelfCheckStatus.PASS, text("selfCheckScope"), SCHEMA_ENGINE, VERSION, pack.digest(), pack.modules().size(), pack.rules().size(),
                    pack.rules().values().stream().filter(rule -> rule.card().status() == RuleStatus.VALIDATED).count()));
            case CATALOG -> SkillCheckResult.ok(new Catalog(VERSION, pack.digest(),
                    select(List.of(), List.of(), args.flag("allow-draft")).stream().map(rule -> new CatalogEntry(rule.id(), rule.domain(), rule.title(), rule.status())).toList(), text("catalogNotice")));
            case RULES -> SkillCheckResult.ok(new RuleList(select(args.stringList("domain"), args.stringList("rule"), args.flag("allow-draft"))));
            case VALIDATE_REQUEST -> {
                validateRequest(artifacts.node("input"));
                yield SkillCheckResult.ok(new RequestValid(ValidationStatus.VALID, text("requestScope"), SCHEMA_ENGINE));
            }
            case VALIDATE_CONSTRAINTS -> {
                JsonNode input = artifacts.node("input");
                JsonNode request = artifacts.node("request");
                Constrained constrained = validateConstraint(input, request);
                ConstraintVerdict verdict = constrained.doc().value().verdict();
                yield answer(new ConstraintsValid(ValidationStatus.VALID, text("constraintsScope"), verdict, constrained.validated().requiredObligationIds(), SCHEMA_ENGINE), verdict.standing());
            }
            case VALIDATE_REVIEW -> {
                JsonNode input = artifacts.node("input");
                JsonNode constraint = artifacts.node("constraint");
                Doc<Candidate> candidate = validateCandidate(artifacts.node("candidate"));
                Doc<ReviewReport> review = bound(input, Contract.REVIEW_REPORT, ReviewReport.class);
                Assessment assessment = assess(review, validateConstraint(constraint, null), candidate.digest());
                yield answer(new ReviewValid(ValidationStatus.VALID, text("reviewScope"), assessment.verdict(), assessment.blockingFindingIds(), assessment.unverifiedRequiredObligationIds(),
                        assessment.requiredCount(), SCHEMA_ENGINE), assessment.verdict().standing());
            }
            case CHECK_BUNDLE -> {
                String root = args.requiredText("root");
                JsonNode binding = artifacts.node("binding");
                JsonNode task = artifacts.node("task");
                JsonNode policy = artifacts.node("policy");
                JsonNode review = artifacts.node("review");
                JsonNode candidate = artifacts.node("candidate");
                BundleCheck check = checkBundle(artifacts, root, binding, task, policy, review, candidate, null);
                yield answer(check, check.verdict().standing());
            }
            case HANDOFF -> {
                String root = args.requiredText("root");
                JsonNode binding = artifacts.node("binding");
                JsonNode task = artifacts.node("task");
                JsonNode policy = artifacts.node("policy");
                yield SkillCheckResult.ok(checkHandoff(artifacts, root, binding, task, policy));
            }
            case CHECK_STAGE_BUNDLE -> {
                BundleCheck check = checkStageBundle(args, artifacts);
                yield answer(check, check.verdict().standing());
            }
            case PROVIDER_RESULT -> {
                JsonNode output = artifacts.node("input");
                ProviderOutput judged = switch (providerMode(args.text("mode"))) {
                    case DERIVE -> validateConstraint(output, artifacts.node("request")).doc().value();
                    case REVIEW -> {
                        JsonNode constraint = artifacts.node("constraint");
                        Doc<Candidate> candidate = validateCandidate(artifacts.node("candidate"));
                        Doc<ReviewReport> review = bound(output, Contract.REVIEW_REPORT, ReviewReport.class);
                        assess(review, validateConstraint(constraint, null), candidate.digest());
                        yield review.value();
                    }
                };
                Standing standing = standing(judged);
                ProviderError error = standing.error() == null ? null : new ProviderError(standing.error(), text(standing.error().messageKey()), null);
                yield answer(new ProviderResult(SCHEMA_VERSION, Kind.OUTPUT, judged, List.of(), error), standing);
            }
        };
    }

    private static SkillCheckResult answer(Object value, Standing standing) {
        return new SkillCheckResult(value, standing.exitCode());
    }

    private static Standing standing(ProviderOutput output) {
        return switch (output) {
            case ConstraintReport report -> report.verdict().standing();
            case ReviewReport report -> report.verdict().standing();
        };
    }

    private static ProviderMode providerMode(String wire) {
        return Arrays.stream(ProviderMode.values()).filter(mode -> Objects.equals(mode.wire(), wire)).findFirst().orElseThrow(() -> new InvalidInputException(InputCode.CS_ENGINEERING_INVALID_MODE));
    }

    private String text(String key) {
        return messages.forRequest(M + key, Map.of());
    }

    // ------------------------------------------------------------------------------------------------------------------------ reading input

    /** All file reads in one invocation use the same settings snapshot. */
    private final class ArtifactReader {
        private final SkillArguments args;
        private final int maxBytes;
        private ArtifactReader(SkillArguments args, int maxBytes) {
            this.args = args;
            this.maxBytes = maxBytes;
        }
        private JsonNode node(String name) {
            JsonNode value = args.document(name, maxBytes);
            if (!value.isString()) return value;
            return SkillArguments.parse(args.files().readUnderRoot(args.requiredText("root"), value.stringValue(), maxBytes), mapper);
        }
        private byte[] readArtifact(String root, String path, String digest) {
            byte[] bytes = args.files().readUnderRoot(root, path, maxBytes);
            if (!Objects.equals(Checks.hash(bytes), digest)) {
                throw new IntegrityException(IntegrityCode.CS_ENGINEERING_ARTIFACT_DIGEST_MISMATCH, Map.of(), SennardException.args("path", path));
            }
            return bytes;
        }
        private JsonNode readNode(String root, Ref ref) {
            return SkillArguments.parse(readArtifact(root, ref.path(), ref.digest()), mapper);
        }
    }

    /** A document that conforms to its contract, bound to the record that describes it, with the digest of the document as given. */
    private <T> Doc<T> bound(JsonNode document, Contract contract, Class<T> type) {
        List<SkillSchemas.Violation> violations = schemas.violations(contract.location(), document);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.CS_ENGINEERING_SCHEMA_VALIDATION_FAILED, SennardException.args("name", contract.wire()), SennardException.args("errors", violations));
        }
        return new Doc<>(schemas.bind(document, type), CanonicalJson.digest(document));
    }

    // ---------------------------------------------------------------------------------------------------------------------- shared rules

    private static void digest(String left, String right, Label label) {
        need(Objects.equals(left, right), IntegrityCode.CS_ENGINEERING_DIGEST_MISMATCH, "label", label.wire());
    }

    private static <T> Map<String, T> uniqueBy(List<T> items, Function<T, String> key, Label label) {
        return Checks.uniqueBy(items, key, InputCode.CS_ENGINEERING_DUPLICATE, label.wire());
    }

    private static void refsExist(List<String> refs, Set<String> known, Label label) {
        for (String ref : refs) {
            need(known.contains(ref), InputCode.CS_ENGINEERING_UNKNOWN_REFERENCE, "label", label.wire(), "value", ref);
        }
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean filledAll(List<String> values) {
        return values != null && !values.isEmpty() && values.stream().allMatch(CsEngineeringCheck::filled);
    }

    /**
     * A relative path that means the same on every file system: bounded, with no absolute, drive, backslash or control form and no reserved or dotted component.
     * It is a form the check's contract fixes for a path an agent writes (always read by {@code /}), not a path of this file system, so {@link java.nio.file.Path}, which follows
     * only the syntax of the file system it runs on, cannot decide it; the same text must be accepted or refused alike on Windows and on Linux.
     */
    private static void portablePath(String relative) {
        need(relative != null && !relative.isEmpty() && relative.length() <= 4096, InputCode.CS_ENGINEERING_BOUNDED_PATH_REQUIRED);
        need(!FORBIDDEN_PATH_CHARACTER.matcher(relative).find() && !relative.startsWith("/"), InputCode.CS_ENGINEERING_FORBIDDEN_PATH_FORM);
        for (String part : SLASH.split(relative, -1)) {
            need(!part.isEmpty() && !".".equals(part) && !"..".equals(part) && !part.endsWith(".") && !part.endsWith(" ") && !RESERVED_NAME.matcher(part).matches(), InputCode.CS_ENGINEERING_UNSAFE_PATH_COMPONENT);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------- the pack

    /** A document of the pack read refusing a member its record does not name. */
    private <T> T strict(JsonNode document, Class<T> type) {
        return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(document);
    }

    private Pack loadPack() {
        JsonNode lockDocument = mapper.readTree(assets.bytes(SKILL_ROOT + "assets/knowledge-lock.json"));
        Lock lock = strict(lockDocument, Lock.class);
        need(SCHEMA_VERSION.equals(lock.schemaVersion()) && VERSION.equals(lock.version()) && lock.files() != null, InputCode.CS_ENGINEERING_INVALID_KNOWLEDGE_LOCK);
        uniqueBy(lock.files(), LockFile::path, Label.KNOWLEDGE_FILE);
        for (LockFile entry : lock.files()) {
            String path = SKILL_ROOT + entry.path();
            if (assets.exists(path)) {
                need(Objects.equals(Checks.hash(assets.bytes(path)), entry.digest()), IntegrityCode.CS_ENGINEERING_ARTIFACT_DIGEST_MISMATCH);
            }
        }
        Catalogue catalogue = strict(mapper.readTree(assets.bytes(SKILL_ROOT + "assets/rule-catalog.json")), Catalogue.class);
        Map<String, KnowledgeModule> modules = uniqueBy(catalogue.modules(), KnowledgeModule::id, Label.MODULE);
        Map<String, CatalogueRule> catalogueRules = uniqueBy(catalogue.rules(), CatalogueRule::id, Label.CATALOGUE_RULE);
        Map<String, PackRule> rules = new LinkedHashMap<>();
        for (KnowledgeModule module : modules.values()) {
            JsonNode rows = mapper.readTree(assets.bytes(SKILL_ROOT + module.ruleFile()));
            Rule[] cards = strict(rows, Rule[].class);
            need(cards.length > 0, InputCode.CS_ENGINEERING_EMPTY_KNOWLEDGE_MODULE);
            List<String> digests = new ArrayList<>();
            rows.forEach(row -> digests.add(CanonicalJson.digest(row)));
            need(module.ruleIds() != null && Checks.sameSet(Arrays.stream(cards).map(Rule::id).toList(), module.ruleIds()), InputCode.CS_ENGINEERING_MODULE_RULE_LIST_MISMATCH);
            for (int at = 0; at < cards.length; at++) {
                Rule card = cards[at];
                need(!rules.containsKey(card.id()), InputCode.CS_ENGINEERING_DUPLICATE_RULE_ID);
                need(Objects.equals(module.id(), card.domain()) && module.status() == card.status() && VERSION.equals(card.version()), InputCode.CS_ENGINEERING_RULE_METADATA_MISMATCH);
                need(filled(card.title()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "title");
                need(filled(card.principle()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "principle");
                need(filled(card.invariant()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "invariant");
                need(filled(card.validationScope()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "validationScope");
                need(filledAll(card.appliesWhen()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "appliesWhen");
                need(filledAll(card.assumptions()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "assumptions");
                need(filledAll(card.failureModes()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "failureModes");
                need(filledAll(card.decisionQuestions()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "decisionQuestions");
                need(filledAll(card.verificationObligations()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "verificationObligations");
                need(filledAll(card.nonGoals()), InputCode.CS_ENGINEERING_RULE_LACKS_FIELD, "field", "nonGoals");
                CatalogueRule index = catalogueRules.get(card.id());
                need(index != null && Objects.equals(module.id(), index.domain()) && Objects.equals(module.ruleFile(), index.path()) && module.status() == index.status(), InputCode.CS_ENGINEERING_CATALOGUE_ROW_MISMATCH);
                digest(index.digest(), digests.get(at), Label.RULE_DIGEST);
                rules.put(card.id(), new PackRule(card, digests.get(at)));
            }
        }
        need(rules.size() == catalogueRules.size(), InputCode.CS_ENGINEERING_UNLOADED_CATALOGUE_RULE);
        Set<String> locked = lock.files().stream().map(LockFile::path).collect(Collectors.toSet());
        List<String> requiredPaths = new ArrayList<>(List.of("assets/rule-catalog.json"));
        modules.values().forEach(module -> {
            requiredPaths.add(module.reference());
            requiredPaths.add(module.ruleFile());
        });
        requiredPaths.forEach(path -> need(locked.contains(path), IntegrityCode.CS_ENGINEERING_LOCK_MISSING_SOURCE));
        return new Pack(CanonicalJson.digest(lockDocument), rules, modules);
    }

    private List<Rule> select(List<String> domains, List<String> ruleIds, boolean allowDraft) {
        domains.forEach(domain -> need(pack.modules().containsKey(domain), InputCode.CS_ENGINEERING_UNKNOWN_DOMAIN, "value", domain));
        ruleIds.forEach(id -> need(pack.rules().containsKey(id), InputCode.CS_ENGINEERING_UNKNOWN_RULE, "value", id));
        return pack.rules().values().stream().map(PackRule::card)
                .filter(rule -> (domains.isEmpty() && ruleIds.isEmpty() || domains.contains(rule.domain()) || ruleIds.contains(rule.id())) && (allowDraft || rule.status() == RuleStatus.VALIDATED)).toList();
    }

    // ------------------------------------------------------------------------------------------------------------------------ the request

    private Doc<Request> validateRequest(JsonNode document) {
        Doc<Request> doc = bound(document, Contract.REQUEST, Request.class);
        Request request = doc.value();
        Map<String, SourceRef> sourceIds = uniqueBy(request.sourceRefs(), SourceRef::id, Label.SOURCE_REFERENCE);
        uniqueBy(request.sourceRefs(), SourceRef::path, Label.SOURCE_PATH);
        uniqueBy(request.operationalFacts(), OperationalFact::id, Label.OPERATIONAL_FACT);
        request.sourceRefs().forEach(ref -> portablePath(ref.path()));
        request.operationalFacts().forEach(fact -> refsExist(fact.evidenceRefs(), sourceIds.keySet(), Label.FACT_EVIDENCE));
        digest(request.sourceSnapshotDigest(), Checks.digest(mapper, request.sourceRefs()), Label.SOURCE_SNAPSHOT);
        return doc;
    }

    // ------------------------------------------------------------------------------------------------------------------ the constraint report

    private Constrained validateConstraint(JsonNode document, JsonNode requestDocument) {
        Doc<ConstraintReport> doc = bound(document, Contract.CONSTRAINT_REPORT, ConstraintReport.class);
        ConstraintReport report = doc.value();
        digest(report.knowledgePackDigest(), pack.digest(), Label.KNOWLEDGE_PACK);
        need(VERSION.equals(report.knowledgePackVersion()), IntegrityCode.CS_ENGINEERING_KNOWLEDGE_VERSION_MISMATCH);
        Map<String, SelectedRule> selected = uniqueBy(report.selectedRules(), SelectedRule::ruleId, Label.SELECTED_RULE);
        boolean draftAllowed = report.draftUse().allowed();
        for (SelectedRule row : selected.values()) {
            PackRule rule = pack.rules().get(row.ruleId());
            need(rule != null, InputCode.CS_ENGINEERING_SELECTED_RULE_MISSING);
            digest(row.ruleDigest(), rule.digest(), Label.SELECTED_RULE);
            need(rule.card().status() == RuleStatus.VALIDATED || draftAllowed, InputCode.CS_ENGINEERING_DRAFT_NEEDS_BASIS);
        }
        Map<String, Fact> facts = uniqueBy(report.facts(), Fact::id, Label.FACT);
        Map<String, Assumption> assumptions = uniqueBy(report.assumptions(), Assumption::id, Label.ASSUMPTION);
        Set<String> basis = new HashSet<>(facts.keySet());
        basis.addAll(assumptions.keySet());
        need(basis.size() == facts.size() + assumptions.size(), InputCode.CS_ENGINEERING_FACT_ASSUMPTION_COLLIDE);
        Map<String, Invariant> invariants = uniqueBy(report.invariants(), Invariant::id, Label.INVARIANT);
        Map<String, Obligation> obligations = uniqueBy(report.verificationObligations(), Obligation::id, Label.OBLIGATION);
        Map<String, DesignChoice> choices = uniqueBy(report.designChoices(), DesignChoice::id, Label.DESIGN_CHOICE);
        for (Invariant invariant : invariants.values()) {
            refsExist(invariant.basisRefs(), basis, Label.INVARIANT_BASIS);
            refsExist(invariant.ruleIds(), selected.keySet(), Label.SELECTED_INVARIANT_RULE);
            if (invariant.requirementLevel() == InvariantLevel.REQUIRED_INVARIANT) {
                need(invariant.basisRefs().stream().anyMatch(facts::containsKey), InputCode.CS_ENGINEERING_REQUIRED_INVARIANT_NEEDS_FACT);
            }
        }
        for (DesignChoice choice : choices.values()) {
            refsExist(choice.basisRefs(), basis, Label.CHOICE_BASIS);
        }
        for (Obligation obligation : obligations.values()) {
            refsExist(obligation.invariantIds(), invariants.keySet(), Label.OBLIGATION_INVARIANT);
            boolean required = obligation.invariantIds().stream().anyMatch(id -> invariants.get(id).requirementLevel() == InvariantLevel.REQUIRED_INVARIANT);
            need(obligation.requirementLevel() == (required ? ObligationLevel.REQUIRED : ObligationLevel.ADVISORY), InputCode.CS_ENGINEERING_OBLIGATION_SEVERITY_MISMATCH);
        }
        for (Invariant invariant : invariants.values()) {
            if (invariant.requirementLevel() == InvariantLevel.REQUIRED_INVARIANT) {
                need(obligations.values().stream().anyMatch(obligation -> obligation.invariantIds().contains(invariant.id()) && obligation.requirementLevel() == ObligationLevel.REQUIRED),
                        InputCode.CS_ENGINEERING_REQUIRED_INVARIANT_NO_OBLIGATION);
            }
        }
        if (report.applicability() == Applicability.APPLICABLE && report.verdict() == ConstraintVerdict.READY) {
            need(!selected.isEmpty() && !invariants.isEmpty() && !obligations.isEmpty(), InputCode.CS_ENGINEERING_APPLICABLE_READY_INCOMPLETE);
        }
        if (report.applicability() == Applicability.NOT_APPLICABLE) {
            need(selected.isEmpty() && invariants.isEmpty() && obligations.isEmpty() && choices.isEmpty(), InputCode.CS_ENGINEERING_NOT_APPLICABLE_HAS_CONSTRAINTS);
        }
        Doc<Request> request = null;
        if (requestDocument != null) {
            request = validateRequest(requestDocument);
            digest(report.requestDigest(), request.digest(), Label.REQUEST);
            digest(report.sourceSnapshotDigest(), request.value().sourceSnapshotDigest(), Label.SOURCE_SNAPSHOT);
            Set<String> sourceIds = request.value().sourceRefs().stream().map(SourceRef::id).collect(Collectors.toSet());
            facts.values().forEach(fact -> refsExist(fact.evidenceRefs(), sourceIds, Label.FACT_EVIDENCE));
        }
        List<String> requiredIds = obligations.values().stream().filter(obligation -> obligation.requirementLevel() == ObligationLevel.REQUIRED).map(Obligation::id).toList();
        return new Constrained(doc, new Validated(invariants, obligations, requiredIds), request);
    }

    // ------------------------------------------------------------------------------------------------------------------------ the binding

    private Frozen validateBinding(Doc<Binding> bindingDoc, JsonNode taskDocument, JsonNode requestDocument, JsonNode constraintDocument, JsonNode policyDocument) {
        Binding binding = bindingDoc.value();
        Doc<Policy> policyDoc = bound(policyDocument, Contract.POLICY, Policy.class);
        Policy policy = policyDoc.value();
        Constrained constraint = validateConstraint(constraintDocument, requestDocument);
        need(constraint.doc().value().verdict() == ConstraintVerdict.READY, InputCode.CS_ENGINEERING_ONLY_READY_FROZEN);
        digest(binding.requestDigest(), constraint.request().digest(), Label.BOUND_REQUEST);
        digest(binding.constraintReportDigest(), constraint.doc().digest(), Label.BOUND_REPORT);
        digest(binding.taskDigest(), CanonicalJson.digest(taskDocument), Label.BOUND_TASK);
        digest(binding.knowledgePackDigest(), pack.digest(), Label.BOUND_KNOWLEDGE);
        digest(binding.policyDigest(), policyDoc.digest(), Label.BOUND_POLICY);
        need(VERSION.equals(binding.knowledgePackVersion()) && Objects.equals(binding.policyId(), policy.policyId()) && Objects.equals(binding.policyVersion(), policy.version())
                && binding.mode() == policy.mode(), IntegrityCode.CS_ENGINEERING_POLICY_KNOWLEDGE_CHANGED);
        need(Checks.sameSet(binding.requiredObligationIds(), constraint.validated().requiredObligationIds()), IntegrityCode.CS_ENGINEERING_REQUIRED_OBLIGATIONS_CHANGED);
        if (!policy.allowDraft()) {
            need(constraint.doc().value().selectedRules().stream().noneMatch(row -> pack.rules().get(row.ruleId()).card().status() == RuleStatus.DRAFT), InputCode.CS_ENGINEERING_DRAFT_NOT_AUTHORIZED);
        }
        portablePath(binding.requestRef().path());
        portablePath(binding.constraintReportRef().path());
        return new Frozen(constraint, policyDoc);
    }

    private HandoffAnswer checkHandoff(ArtifactReader artifacts, String root, JsonNode bindingDocument, JsonNode taskDocument, JsonNode policyDocument) {
        Doc<Binding> bindingDoc = bound(bindingDocument, Contract.BINDING, Binding.class);
        Binding binding = bindingDoc.value();
        JsonNode requestDocument = artifacts.readNode(root, binding.requestRef());
        JsonNode constraintDocument = artifacts.readNode(root, binding.constraintReportRef());
        Frozen frozen = validateBinding(bindingDoc, taskDocument, requestDocument, constraintDocument, policyDocument);
        for (SourceRef ref : frozen.constraint().request().value().sourceRefs()) {
            artifacts.readArtifact(root, ref.path(), ref.digest());
        }
        return new HandoffAnswer(SCHEMA_VERSION, Kind.HANDOFF, binding.taskDigest(), bindingDoc.digest(), binding.constraintReportRef(), binding.constraintReportDigest(), pack.digest(),
                frozen.constraint().validated().requiredObligationIds(), binding.policyDigest(), text("handoffAssurance"));
    }

    // ---------------------------------------------------------------------------------------------------------------------- the candidate

    private Doc<Candidate> validateCandidate(JsonNode document) {
        Doc<Candidate> doc = bound(document, Contract.CANDIDATE, Candidate.class);
        List<Ref> files = doc.value().files();
        uniqueBy(files, Ref::path, Label.CANDIDATE_FILE);
        files.forEach(file -> portablePath(file.path()));
        for (int at = 1; at < files.size(); at++) {
            need(files.get(at - 1).path().compareTo(files.get(at).path()) < 0, InputCode.CS_ENGINEERING_CANDIDATE_PATHS_UNSORTED);
        }
        return doc;
    }

    // ------------------------------------------------------------------------------------------------------------------------- the review

    /** The verdict a review must carry, recomputed from the obligations and findings; every rule a review report must keep on the way. */
    private Assessment assess(Doc<ReviewReport> reviewDoc, Constrained constraint, String candidateDigest) {
        ReviewReport review = reviewDoc.value();
        Validated validated = constraint.validated();
        need(constraint.doc().value().verdict() == ConstraintVerdict.READY, InputCode.CS_ENGINEERING_REVIEW_NEEDS_READY);
        digest(review.constraintReportDigest(), constraint.doc().digest(), Label.REVIEW_CONSTRAINT);
        digest(review.candidateDigest(), candidateDigest, Label.REVIEW_CANDIDATE);
        Map<String, Evidence> evidence = uniqueBy(review.evidence(), Evidence::id, Label.EVIDENCE);
        Map<String, Finding> findings = uniqueBy(review.findings(), Finding::id, Label.FINDING);
        Map<String, Finding> byInvariant = uniqueBy(review.findings(), Finding::invariantId, Label.REVIEWED_INVARIANT);
        Map<String, VerificationResult> results = uniqueBy(review.verificationResults(), VerificationResult::obligationId, Label.VERIFICATION_RESULT);
        need(Checks.sameSet(validated.invariants().keySet(), byInvariant.keySet()), InputCode.CS_ENGINEERING_INVARIANT_REVIEW_COVERAGE);
        need(Checks.sameSet(validated.obligations().keySet(), results.keySet()), InputCode.CS_ENGINEERING_OBLIGATION_RESULT_COVERAGE);
        for (Evidence item : evidence.values()) {
            portablePath(item.path());
            digest(item.targetDigest(), candidateDigest, Label.EVIDENCE_CANDIDATE);
        }
        for (VerificationResult result : results.values()) {
            Obligation obligation = validated.obligations().get(result.obligationId());
            digest(result.targetDigest(), candidateDigest, Label.RESULT_CANDIDATE);
            need(result.method() == obligation.method(), InputCode.CS_ENGINEERING_VERIFICATION_METHOD_CHANGED);
            List<String> refs = result.evidenceRefs();
            refsExist(refs, evidence.keySet(), Label.RESULT_EVIDENCE);
            if (result.executionStatus() == ExecutionStatus.EXECUTED) {
                need(result.result() != Observation.UNKNOWN && !refs.isEmpty(), InputCode.CS_ENGINEERING_EXECUTED_NEEDS_EVIDENCE);
                need(Objects.equals(result.environmentRef(), obligation.requiredEnvironment()), InputCode.CS_ENGINEERING_ENVIRONMENT_MISMATCH);
                for (String id : refs) {
                    Evidence item = evidence.get(id);
                    need(item.kind() == result.method() && Objects.equals(item.environmentRef(), result.environmentRef()), InputCode.CS_ENGINEERING_EVIDENCE_KIND_MISMATCH);
                }
            } else {
                need(result.result() == Observation.UNKNOWN && refs.isEmpty(), InputCode.CS_ENGINEERING_NON_EXECUTED_CLAIMS);
            }
        }
        List<String> blocking = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (String id : validated.requiredObligationIds()) {
            VerificationResult result = results.get(id);
            if (result.executionStatus() != ExecutionStatus.EXECUTED || result.result() == Observation.UNKNOWN) {
                unverified.add(id);
            }
        }
        boolean requiredViolation = false;
        boolean missingRequiredFinding = false;
        for (Finding finding : findings.values()) {
            Invariant invariant = validated.invariants().get(finding.invariantId());
            List<Obligation> relevant = validated.obligations().values().stream().filter(obligation -> obligation.invariantIds().contains(invariant.id())).toList();
            List<String> refs = finding.evidenceRefs();
            refsExist(refs, evidence.keySet(), Label.FINDING_EVIDENCE);
            Set<String> resultEvidence = new HashSet<>();
            relevant.forEach(obligation -> resultEvidence.addAll(results.get(obligation.id()).evidenceRefs()));
            need(resultEvidence.containsAll(refs), InputCode.CS_ENGINEERING_FINDING_EVIDENCE_UNCONNECTED);
            FindingStatus status = finding.status();
            switch (status) {
                case SATISFIED -> {
                    need(!refs.isEmpty(), InputCode.CS_ENGINEERING_CONCLUSIVE_NEEDS_EVIDENCE);
                    need(!relevant.isEmpty() && relevant.stream().allMatch(obligation -> {
                        VerificationResult result = results.get(obligation.id());
                        return result.executionStatus() == ExecutionStatus.EXECUTED && result.result() == Observation.PASS;
                    }), InputCode.CS_ENGINEERING_SATISFIED_INCOMPLETE);
                }
                case VIOLATED -> {
                    need(!refs.isEmpty(), InputCode.CS_ENGINEERING_CONCLUSIVE_NEEDS_EVIDENCE);
                    need(relevant.stream().anyMatch(obligation -> results.get(obligation.id()).result() == Observation.FAIL), InputCode.CS_ENGINEERING_VIOLATED_NO_FAIL);
                }
                case UNVERIFIED, NOT_APPLICABLE -> {
                    // Neither claims a conclusion, so neither needs evidence.
                }
            }
            if (invariant.requirementLevel() == InvariantLevel.REQUIRED_INVARIANT) {
                if (status == FindingStatus.VIOLATED) {
                    blocking.add(finding.id());
                    requiredViolation = true;
                }
                if (status == FindingStatus.UNVERIFIED || status == FindingStatus.NOT_APPLICABLE) {
                    missingRequiredFinding = true;
                }
                if (relevant.stream().anyMatch(obligation -> results.get(obligation.id()).result() == Observation.FAIL)) {
                    requiredViolation = true;
                    need(status == FindingStatus.VIOLATED, InputCode.CS_ENGINEERING_FAILED_VERIFICATION_HIDDEN);
                }
            }
        }
        ReviewVerdict expected;
        if (requiredViolation) {
            expected = ReviewVerdict.FAIL;
        } else if (!unverified.isEmpty() || missingRequiredFinding) {
            expected = ReviewVerdict.BLOCKED;
        } else {
            expected = ReviewVerdict.PASS;
        }
        need(Checks.sameSet(review.blockingFindingIds(), blocking), InputCode.CS_ENGINEERING_BLOCKING_LIST_INACCURATE);
        need(Checks.sameSet(review.unverifiedRequiredObligationIds(), unverified), InputCode.CS_ENGINEERING_UNVERIFIED_LIST_INACCURATE);
        need(expected == review.verdict(), InputCode.CS_ENGINEERING_REVIEW_VERDICT_MISMATCH, "expected", expected.name());
        return new Assessment(expected, blocking, unverified, validated.requiredObligationIds().size());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- bundles

    /**
     * The frozen chain of a stage's files, each read under the root and held to the digest its reference names. {@code stageReview} is the review's reference when the files
     * came from a stage bundle: the answer then also carries the task and the review this check read.
     */
    private BundleCheck checkBundle(ArtifactReader artifacts, String root, JsonNode bindingDocument, JsonNode taskDocument, JsonNode policyDocument, JsonNode reviewDocument,
            JsonNode candidateDocument, Ref stageReview) {
        Doc<Binding> bindingDoc = bound(bindingDocument, Contract.BINDING, Binding.class);
        Binding binding = bindingDoc.value();
        JsonNode requestDocument = artifacts.readNode(root, binding.requestRef());
        JsonNode constraintDocument = artifacts.readNode(root, binding.constraintReportRef());
        Frozen frozen = validateBinding(bindingDoc, taskDocument, requestDocument, constraintDocument, policyDocument);
        Doc<Candidate> candidate = validateCandidate(candidateDocument);
        int count = 2;
        for (SourceRef ref : frozen.constraint().request().value().sourceRefs()) {
            artifacts.readArtifact(root, ref.path(), ref.digest());
            count++;
        }
        for (Ref file : candidate.value().files()) {
            artifacts.readArtifact(root, file.path(), file.digest());
            count++;
        }
        Doc<ReviewReport> review = bound(reviewDocument, Contract.REVIEW_REPORT, ReviewReport.class);
        Assessment assessment = assess(review, frozen.constraint(), candidate.digest());
        for (Evidence item : review.value().evidence()) {
            artifacts.readArtifact(root, item.path(), item.digest());
            count++;
        }
        PolicyMode mode = frozen.policy().value().mode();
        boolean staged = stageReview != null;
        return new BundleCheck(SCHEMA_VERSION, Kind.BUNDLE_CHECK, assessment.verdict(), mode == PolicyMode.OBSERVE || assessment.verdict() == ReviewVerdict.PASS, mode, SCHEMA_ENGINE, pack.digest(),
                candidate.digest(), frozen.constraint().doc().digest(), count, assessment.requiredCount(), text("bundleAssurance"), staged ? taskDocument : null, staged ? review.value() : null,
                stageReview);
    }

    private BundleCheck checkStageBundle(SkillArguments args, ArtifactReader artifacts) {
        String root = args.requiredText("root");
        String bundleDigest = args.text("bundle-digest");
        String taskDigest = args.text("task-digest");
        need(Checks.isDigest(bundleDigest) && Checks.isDigest(taskDigest), InputCode.CS_ENGINEERING_STAGE_DIGESTS_REQUIRED);
        JsonNode manifestDocument = SkillArguments.parse(artifacts.readArtifact(root, args.requiredText("input"), bundleDigest), mapper);
        StageBundle manifest = bound(manifestDocument, Contract.STAGE_BUNDLE, StageBundle.class).value();
        JsonNode binding = artifacts.readNode(root, manifest.binding());
        JsonNode task = artifacts.readNode(root, manifest.task());
        JsonNode policy = artifacts.readNode(root, manifest.policy());
        JsonNode review = artifacts.readNode(root, manifest.review());
        JsonNode candidate = artifacts.readNode(root, manifest.candidate());
        need(Objects.equals(CanonicalJson.digest(task), taskDigest), IntegrityCode.CS_ENGINEERING_STAGE_TASK_DIFFERS);
        return checkBundle(artifacts, root, binding, task, policy, review, candidate, manifest.review());
    }
}
