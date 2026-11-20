// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import io.github.jaeseongs95.sennard.service.files.FileFactsException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen-evidence rules behind {@code test-engineering} and {@code code-review}: a plan fixes requirements and cases before any run,
 * a proof ties a test's red and green receipts to the plan and to exact file digests, a review answers the changed files of a request.
 * Unsigned local consistency, not attestation; the limitations in every result say so.
 *
 * <p>{@code run} starts the caller's own test command through the {@link SkillProcessRunner} and answers the
 * receipt of that run; {@code check-proof} judges only whether the receipts, the plan and the files agree, whoever made the receipts. Every document is checked
 * against its contract, then bound to a record; the digests of whole documents are taken over their canonical form.
 */
@Component
public class EngineeringPractices {
    private static final Logger LOG = LoggerFactory.getLogger(EngineeringPractices.class);
    private static final String CORE = "practices.core.";
    private static final String KNOWLEDGE = "skills/orchestrator/references/engineering-practices/catalog.json";
    private static final String CONTRACT_ID = "https://skill-suite.local/contracts/";
    private static final String VERSION = "1.0.0";
    /** The run receipt's own version: 2.0.0 replaced the 5.x receipt (1.0.0, which has {@code nodeVersion} and {@code signal}); a 1.0.0 receipt is refused, not read. */
    private static final String RECEIPT_VERSION = "2.0.0";
    private static final String LEGACY_RECEIPT_VERSION = "1.0.0";
    private static final String RECEIPT_CONTRACT = "contracts/engineering-run-receipt.v2.schema.json";
    private static final String SCHEMA_ENGINE = "networknt-2020-12";    private static final int FILE_LIMIT = 8 * 1024 * 1024;
    private static final int TOTAL_LIMIT = 64 * 1024 * 1024;
    private static final int BUNDLE_LIMIT = 1024 * 1024;
    private static final int SNAPSHOT_FILES = 1000;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    /** The receipt contract's bounds of {@code argv}: at most 128 items of at most 8192 characters. */
    private static final int ARGV_ITEMS = 128;
    private static final int ARGV_ITEM_LENGTH = 8192;
    /** What a caller may set in the child's environment with {@code env}: at most this many variables, a name of 1..128 characters, a value of at most 8192. */
    private static final int ENV_VARIABLES = 32;
    private static final int ENV_NAME_LENGTH = 128;
    private static final int ENV_VALUE_LENGTH = 8192;
    private static final String UNREADABLE_AFTER_RUN = "Snapshot became unreadable after command execution.";
    private static final int MIN_WITNESS = 4;

    private static final Pattern EXACT_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final Pattern BACKSLASH_OR_COLON = Pattern.compile("[\\\\:]");
    private static final Pattern SLASH = Pattern.compile("/");
    private static final Pattern RESERVED_NAME = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTACHMENT_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final List<String> LIMITATIONS = List.of(
            "This is an unsigned local consistency check, not host attestation or an independent audit.",
            "Snapshots bind only explicitly declared files; environment, dependencies outside scope and omitted Git changes require separate review.",
            "Semantic correctness, test oracle quality, assertion causality and review completeness remain reviewer responsibilities.");
    private static final String REVIEW_LIMITATION = "Reviewer independence is declared, not verified by this package. NO_BLOCKING_FINDINGS is not release approval.";

    /** The commands of the two skills that share this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        SELF_CHECK("self-check"), CATALOG("catalog"), SNAPSHOT("snapshot"), RUN("run"), CHECK_PLAN("check-plan"), CHECK_PROOF("check-proof"), CHECK_REVIEW("check-review"),
        PROVIDER_RESULT("provider-result"), CHECK_STAGE_BUNDLE("check-stage-bundle");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** The capabilities a provider result or a stage bundle names, each with the contract of the result it carries. */
    enum Capability {
        TEST_PLAN_VALIDATION("test-plan-validation", "engineering-plan-result"), TEST_SENSITIVITY_REVIEW("test-sensitivity-review", "engineering-test-result"),
        CHANGE_CODE_REVIEW("change-code-review", "engineering-review-result");

        private final String wire;
        private final String schema;

        Capability(String wire, String schema) {
            this.wire = wire;
            this.schema = schema;
        }

        /** The name the contracts and the callers use for this capability. */
        @JsonValue
        public String wire() {
            return wire;
        }

        String schema() {
            return schema;
        }

        /** The capability a caller named, or null when this check does not know it. */
        static Capability fromWire(String name) {
            for (Capability capability : values()) {
                if (Objects.equals(capability.wire, name)) {
                    return capability;
                }
            }
            return null;
        }
    }

    enum Verdict {
        READY, CONSISTENT, INCOMPLETE, NO_BLOCKING_FINDINGS, CHANGES_REQUESTED
    }

    enum RunStatus {
        PASS, FAIL, TIMED_OUT, SPAWN_ERROR, INPUT_CHANGED
    }

    enum ProofStatus {
        DEMONSTRATED, NOT_RUN, EXEMPT
    }

    enum CoverageStatus {
        REVIEWED, NOT_REVIEWED
    }

    enum Level {
        @JsonProperty("unit") UNIT, @JsonProperty("integration") INTEGRATION, @JsonProperty("e2e") E2E, @JsonProperty("static") STATIC
    }

    enum Sensitivity {
        @JsonProperty("red-green") RED_GREEN, @JsonProperty("mutation") MUTATION, @JsonProperty("manual-review") MANUAL_REVIEW, @JsonProperty("not-applicable") NOT_APPLICABLE
    }

    enum Side {
        @JsonProperty("base") BASE, @JsonProperty("head") HEAD
    }

    enum Category {
        @JsonProperty("defect") DEFECT, @JsonProperty("recommendation") RECOMMENDATION
    }

    enum Severity {
        @JsonProperty("critical") CRITICAL, @JsonProperty("high") HIGH, @JsonProperty("medium") MEDIUM, @JsonProperty("low") LOW, @JsonProperty("informational") INFORMATIONAL
    }

    enum Confidence {
        @JsonProperty("confirmed") CONFIRMED, @JsonProperty("supported") SUPPORTED, @JsonProperty("hypothesis") HYPOTHESIS
    }

    enum Independence {
        @JsonProperty("self-review") SELF_REVIEW, @JsonProperty("separate-reviewer") SEPARATE_REVIEWER, @JsonProperty("not-established") NOT_ESTABLISHED
    }

    enum RiskLevel {
        @JsonProperty("low") LOW, @JsonProperty("medium") MEDIUM, @JsonProperty("high") HIGH, @JsonProperty("critical") CRITICAL
    }

    enum Complexity {
        @JsonProperty("simple") SIMPLE, @JsonProperty("complex") COMPLEX
    }

    enum SelfCheckStatus {
        CATALOG_CONSISTENT
    }

    enum HostBehavior {
        NOT_RUN
    }

    /** The code of the error a provider result carries when its verdict is not a pass. */
    enum ErrorCode {
        GATE_FAILED, MISSING_EVIDENCE
    }

    // ---- the packaged catalog ----

    record Rule(String id, String module, String owner, String title, String appliesWhen, String action, String evidence, String avoid, String exceptions, String contentStatus,
            String behaviorEvaluation) {
    }

    record Catalog(String schemaVersion, String packVersion, List<Rule> rules) {
    }

    record SelfCheck(SelfCheckStatus status, int rules, List<String> modules, String schemaEngine, HostBehavior hostBehaviorEvaluation) {
    }

    // ---- snapshots and receipts ----

    record SnapshotFile(String path, String digest, long size, long lineCount) {
    }

    /** A snapshot without its digest: the form the digest is taken over. */
    record SnapshotBody(String schemaVersion, String kind, List<SnapshotFile> files) {
    }

    record Snapshot(String schemaVersion, String kind, List<SnapshotFile> files, String digest) {
    }

    /**
     * A run receipt (contract 2.0.0). {@code exitCode} is null when the process did not end by itself, and the receipt's own JSON writes it as null (its contract requires the
     * member). {@code recorderRuntime} is the Java runtime of the server that recorded the run.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Receipt(String schemaVersion, String kind, List<String> argv, Snapshot snapshot, String afterDigest, String startedAt, String finishedAt, RunStatus status, BigInteger exitCode,
            String stdout, String stderr, String stdoutDigest, String stderrDigest, long timeoutMs, String executable, String recorderRuntime, String platform, String environmentNote,
            String digest) {
    }

    /** A receipt without its digest, with the null member written as the receipts' own JSON writes it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReceiptBody(String schemaVersion, String kind, List<String> argv, Snapshot snapshot, String afterDigest, String startedAt, String finishedAt, RunStatus status, BigInteger exitCode,
            String stdout, String stderr, String stdoutDigest, String stderrDigest, long timeoutMs, String executable, String recorderRuntime, String platform, String environmentNote) {
    }

    /** The one member of a receipt read before its contract is applied: which contract the document claims. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceiptVersion(String schemaVersion) {
    }

    // ---- the plan and the proof ----

    record PlanCase(String id, List<String> requirementIds, String scenario, String entryPoint, Level level, String expectedBehavior, String oracle, List<String> testFiles,
            boolean required, Sensitivity sensitivity, String sensitivityReason, List<String> argv) {
    }

    record Plan(String schemaVersion, String kind, String taskId, String contractDigest, List<String> requirements, List<String> scopeFiles, String runner, String environment,
            List<PlanCase> cases, List<String> exclusions) {
    }

    /** A file under the artifact root and the digest of its raw bytes. */
    record FileRef(String path, String digest) {
    }

    record ProofEntry(String caseId, ProofStatus status, FileRef red, FileRef green, List<String> mutationPaths, String assertionWitness, String reason) {
    }

    record Proof(String schemaVersion, String kind, String planDigest, Snapshot candidate, List<ProofEntry> proofs) {
    }

    // ---- the review request and the review report ----

    record ReviewRequest(String schemaVersion, String kind, String taskId, String contractDigest, Snapshot base, Snapshot head, List<String> changedFiles, List<String> excludedFiles,
            List<String> requirements) {
    }

    record Coverage(String path, CoverageStatus status, String reason) {
    }

    record Finding(String id, String path, Side side, long lineStart, long lineEnd, Category category, Severity severity, Confidence confidence, boolean blocking, String title,
            String contract, String trace, String impact, String correction) {
    }

    record Handoff(String owner, String path, String reason) {
    }

    record ReviewReport(String schemaVersion, String kind, String requestDigest, String headDigest, String reviewer, Independence independence, List<Coverage> coverage,
            List<Finding> findings, List<String> openQuestions, List<Handoff> handoffs) {
    }

    // ---- the results ----

    /** What a check answers about a plan, a proof or a review. */
    sealed interface EngineeringResult permits PlanResult, TargetResult {
        Verdict verdict();
    }

    /** A result about a candidate or a head: the digest it judged, what is missing, and what the check cannot say. */
    sealed interface TargetResult extends EngineeringResult permits TestResult, ReviewResult {
        String targetDigest();

        List<String> issues();

        List<String> limitations();
    }

    record PlanResult(String schemaVersion, String kind, Verdict verdict, String planDigest, List<String> caseIds) implements EngineeringResult {
    }

    record TestResult(String schemaVersion, String kind, Verdict verdict, String targetDigest, String reportDigest, List<String> issues, List<String> limitations) implements TargetResult {
    }

    record ReviewResult(String schemaVersion, String kind, Verdict verdict, String targetDigest, String reportDigest, List<String> issues, List<String> limitations) implements TargetResult {
    }

    // ---- the provider result ----

    record Artifact(String artifactId, String schemaId, String attachmentId, String digest, String targetDigest, boolean verified) {
    }

    record ErrorDetails(List<String> issues) {
    }

    record ProviderError(ErrorCode code, String message, ErrorDetails details) {
    }

    /** The provider result: the checked result, the artifact that carries it, and the error a failing verdict is answered with (null otherwise). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ProviderOutput(String schemaVersion, String kind, EngineeringResult output, List<Artifact> artifacts, ProviderError error) {
    }

    // ---- the stage bundle ----

    record TaskScope(List<String> included, List<String> excluded) {
    }

    record WorkUnit(String id, String objective, List<String> dependencies, List<String> writeTargets) {
    }

    record Produced(String artifactId, String schemaId, String targetDigest) {
    }

    record ArtifactContract(String capability, List<Produced> produced) {
    }

    record Authorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    record TaskDecision(Complexity complexity, boolean hasConflicts) {
    }

    record Orchestration(boolean requested, boolean mcpAvailable) {
    }

    /** The workflow's frozen task, as its contract states it ({@code artifactContracts} is the one optional member). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TaskEnvelope(String schemaVersion, String taskId, String objective, TaskScope scope, List<String> acceptanceCriteria, RiskLevel riskLevel, List<WorkUnit> workUnits,
            List<ArtifactContract> artifactContracts, List<String> requiredCapabilities, List<String> constraints, Authorization authorization, TaskDecision decision,
            Orchestration orchestration) {
    }

    /** A stage bundle: the files of one stage's closed evidence, each by path and raw digest. The two kinds differ in the prepared input and the evidence they name. */
    sealed interface StageBundle permits TestBundle, ReviewBundle {
        String schemaVersion();

        BundleKind kind();

        Capability capability();
    }

    /** The one kind of document a stage bundle is; any other value is refused when the bundle is read. */
    enum BundleKind {
        @JsonProperty("engineering-stage-bundle") ENGINEERING_STAGE_BUNDLE
    }

    record TestBundle(String schemaVersion, BundleKind kind, Capability capability, FileRef task, FileRef plan, FileRef proof, FileRef result) implements StageBundle {
    }

    record ReviewBundle(String schemaVersion, BundleKind kind, Capability capability, FileRef task, FileRef request, FileRef report, FileRef result) implements StageBundle {
    }

    /** The files of a bundle in the order they are read: the task, the prepared input, the evidence and the stage's result. */
    private record Refs(FileRef task, FileRef prepared, FileRef evidence, FileRef result) {
    }

    /** What {@code check-stage-bundle} answers; the workflow reads {@code kind}, {@code task}, {@code result}, {@code resultRef} and {@code targetDigest}. */
    record StageCheck(String kind, TaskEnvelope task, Capability capability, TargetResult result, FileRef resultRef, String targetDigest, List<String> limitations) {
    }

    // ---- details of a refusal ----

    private final SkillSchemas schemas;
    private final JsonMapper mapper;
    private final Messages messages;
    private final Catalog packaged;
    private final SkillProcessRunner runner;
    private final Clock clock;

    public EngineeringPractices(SkillSchemas schemas, JsonMapper mapper, Messages messages, SkillProcessRunner runner, Clock clock) {
        this.schemas = schemas;
        this.mapper = mapper;
        this.messages = messages;
        this.runner = runner;
        this.clock = clock;
        this.packaged = schemas.assets().read(KNOWLEDGE, Catalog.class);
    }

    SkillCheckResult run(Command command, SkillArguments args) {
        FileFacts files = args.files();
        return switch (command) {
            case SELF_CHECK -> SkillCheckResult.ok(selfCheck());
            case CATALOG -> SkillCheckResult.ok(catalog(args.text("module")));
            case SNAPSHOT -> {
                String root = args.requiredText("root");
                if (!args.has("files")) {
                    throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_REQUIRED, SennardException.args("name", "files"));
                }
                yield SkillCheckResult.ok(captureSnapshot(files, root, args.stringList("files")));
            }
            case RUN -> runCommand(files, args);
            case CHECK_PLAN -> SkillCheckResult.ok(validatePlan(rootDoc(args, "plan")));
            case CHECK_PROOF -> {
                TestResult result = validateProof(files, args.requiredText("root"), rootDoc(args, "plan"), rootDoc(args, "proof"));
                yield SkillCheckResult.of(result, result.verdict() == Verdict.CONSISTENT);
            }
            case CHECK_REVIEW -> {
                ReviewResult result = validateReview(files, args.requiredText("root"), rootDoc(args, "request"), rootDoc(args, "report"));
                yield SkillCheckResult.of(result, result.verdict() == Verdict.NO_BLOCKING_FINDINGS);
            }
            case CHECK_STAGE_BUNDLE -> {
                StageCheck stage = checkStageBundle(files, args.requiredText("root"), args.requiredText("input"), args.requiredText("bundle-digest"), args.requiredText("task-digest"),
                        args.requiredText("capability"));
                yield SkillCheckResult.of(stage, passes(stage.result().verdict()));
            }
            case PROVIDER_RESULT -> {
                byte[] bytes = files.readUnderRoot(args.requiredText("root"), args.requiredText("result"), FILE_LIMIT);
                yield SkillCheckResult.ok(providerResult(args.requiredText("capability"), SkillArguments.parse(bytes, mapper), args.requiredText("attachmentId"), Checks.hash(bytes)));
            }
        };
    }

    /** The verdicts of a stage that hold: a proof that is consistent, a review with nothing blocking. */
    private static boolean passes(Verdict verdict) {
        return verdict == Verdict.CONSISTENT || verdict == Verdict.NO_BLOCKING_FINDINGS;
    }

    /** A document under the root when the argument names a file, or the JSON value given inline. */
    private JsonNode rootDoc(SkillArguments args, String name) {
        JsonNode value = args.document(name);
        return value.isString() ? parseJson(args.files().readUnderRoot(args.requiredText("root"), value.stringValue(), FILE_LIMIT)) : value;
    }

    private JsonNode parseJson(byte[] bytes) {
        return SkillArguments.parse(bytes, mapper);
    }

    private static String contract(String name) {
        return "contracts/" + name + ".v1.schema.json";
    }

    /** The document conforms to its contract, or the refusal carries every violation. */
    private void validateSchema(String name, JsonNode document) {
        List<SkillSchemas.Violation> violations = schemas.violations(contract(name), document);
        Checks.needWith(violations.isEmpty(), InputCode.PRACTICES_SCHEMA_VALIDATION_FAILED, SennardException.args("errors", violations), "name", name);
    }

    /** A result the check built, held to its own contract before it is answered. */
    private <T> T conform(String name, T result) {
        validateSchema(name, mapper.valueToTree(result));
        return result;
    }

    private <T> T bind(JsonNode document, Class<T> type) {
        return schemas.bind(document, type);
    }

    // ---- what a caller may name as a path ----

    /**
     * The components of a relative path in the portable form the check's contract fixes (always read by {@code /}, and the same on every file system). It is a format, not a path
     * of this file system: {@link java.nio.file.Path} follows only the syntax of the file system it runs on, so it would accept on Linux what Windows refuses and the reverse.
     */
    private static List<String> portable(String relative) {
        need(relative != null && !relative.isEmpty() && relative.length() <= 4096, InputCode.PRACTICES_IO_A_BOUNDED_RELATIVE_PATH_IS_REQUIRED);
        need(!BACKSLASH_OR_COLON.matcher(relative).find() && relative.chars().allMatch(c -> c > 31 && c != 127) && !relative.startsWith("/"), InputCode.PRACTICES_IO_ABSOLUTE_DRIVE_BACKSLASH_CONTROL_AND_STREAM);
        List<String> parts = List.of(SLASH.split(relative, -1));
        boolean safe = parts.stream().allMatch(part -> !part.isEmpty() && !".".equals(part) && !"..".equals(part) && !part.endsWith(".") && !part.endsWith(" ") && !RESERVED_NAME.matcher(part).matches());
        need(safe, InputCode.PRACTICES_IO_UNSAFE_PATH_COMPONENT);
        return parts;
    }

    private static void paths(List<String> values, String label, boolean allowEmpty) {
        need(allowEmpty || !values.isEmpty(), InputCode.PRACTICES_CORE_EMPTY_PATH_SET, "label", label);
        for (String value : values) {
            need(portable(value).stream().noneMatch(".git"::equalsIgnoreCase), InputCode.PRACTICES_CORE_GIT_INTERNALS_ARE_OUT_OF_SNAPSHOT);
        }
        Set<String> distinct = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        distinct.addAll(values);
        need(distinct.size() == values.size(), InputCode.PRACTICES_CORE_DUPLICATE_PATHS, "label", label);
    }

    private static void uniqueStrings(List<String> values, String label) {
        need(Checks.allUnique(values) && values.stream().allMatch(EngineeringPractices::filled), InputCode.PRACTICES_CORE_DUPLICATE_IDENTIFIER, "label", label);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    // ---- snapshots ----

    Snapshot captureSnapshot(FileFacts files, String root, List<String> selected) {
        paths(selected, "snapshot", false);
        need(selected.size() <= SNAPSHOT_FILES, InputCode.PRACTICES_CORE_SNAPSHOT_IS_LIMITED_TO_1000_DECLARED);
        long total = 0;
        List<SnapshotFile> entries = new ArrayList<>();
        for (String path : new TreeSet<>(selected)) {
            byte[] data = files.readUnderRoot(root, path, FILE_LIMIT);
            total += data.length;
            need(total <= TOTAL_LIMIT, InputCode.PRACTICES_CORE_SNAPSHOT_EXCEEDS_64_MIB);
            entries.add(new SnapshotFile(path, Checks.hash(data), data.length, lineCount(data)));
        }
        SnapshotBody body = new SnapshotBody(VERSION, "engineering-snapshot", entries);
        return new Snapshot(body.schemaVersion(), body.kind(), body.files(), Checks.digest(mapper, body));
    }

    /** The lines of a UTF-8 text file; a file with a NUL byte or bytes that are not UTF-8 is not text and has none. */
    private static long lineCount(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return 0;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer text = CharBuffer.allocate(data.length);
        CoderResult decoded = decoder.decode(ByteBuffer.wrap(data), text, true);
        if (decoded.isError() || decoder.flush(text).isError()) {
            return 0;
        }
        text.flip();
        if (text.length() == 0) {
            return 0;
        }
        long newlines = text.chars().filter(c -> c == '\n').count();
        return newlines + 1 - (text.charAt(text.length() - 1) == '\n' ? 1 : 0);
    }

    private static List<String> snapshotPaths(Snapshot snapshot) {
        return snapshot.files().stream().map(SnapshotFile::path).toList();
    }

    private static Map<String, String> digests(Snapshot snapshot) {
        Map<String, String> map = new HashMap<>();
        snapshot.files().forEach(file -> map.put(file.path(), file.digest()));
        return map;
    }

    /** The paths whose bytes differ between two snapshots (one that holds the path and one that does not differ too), in path order. */
    private static List<String> delta(Snapshot base, Snapshot head) {
        Map<String, String> before = digests(base);
        Map<String, String> after = digests(head);
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(key -> !Objects.equals(before.get(key), after.get(key))).toList();
    }

    private String digestOf(Snapshot snapshot) {
        return Checks.digest(mapper, new SnapshotBody(snapshot.schemaVersion(), snapshot.kind(), snapshot.files()));
    }

    /** A snapshot a document carried: declared paths, bounded sizes, sorted, and sealed by its own digest. (Its shape was held by the enclosing contract, which states it in full.) */
    private void assertSnapshot(Snapshot snapshot) {
        List<SnapshotFile> files = snapshot.files();
        List<String> pathList = snapshotPaths(snapshot);
        paths(pathList, "snapshot", false);
        need(files.size() <= SNAPSHOT_FILES, InputCode.PRACTICES_CORE_TOO_MANY_SNAPSHOT_FILES);
        need(files.stream().allMatch(file -> file.size() >= 0 && file.size() <= FILE_LIMIT && file.lineCount() >= 0 && file.lineCount() <= file.size()), InputCode.PRACTICES_CORE_INVALID_SNAPSHOT_SIZES_OR_LINES);
        need(files.stream().mapToLong(SnapshotFile::size).sum() <= TOTAL_LIMIT, InputCode.PRACTICES_CORE_SNAPSHOT_EXCEEDS_TOTAL_BOUND);
        need(pathList.equals(pathList.stream().sorted().toList()), InputCode.PRACTICES_CORE_SNAPSHOT_PATHS_MUST_BE_SORTED);
        need(Objects.equals(snapshot.digest(), digestOf(snapshot)), IntegrityCode.PRACTICES_CORE_SNAPSHOT_DIGEST_MISMATCH);
    }

    /** The snapshot is well formed and the files it declares are, byte for byte, what the root holds now. */
    private void verifySnapshot(FileFacts files, String root, Snapshot snapshot) {
        assertSnapshot(snapshot);
        need(Objects.equals(captureSnapshot(files, root, snapshotPaths(snapshot)).digest(), snapshot.digest()), IntegrityCode.PRACTICES_CORE_CURRENT_FILES_DIFFER_FROM_THE_DECLARED);
    }

    // ---- the plan ----

    private Plan bindPlan(JsonNode document) {
        validateSchema("engineering-test-plan", document);
        return bind(document, Plan.class);
    }

    private void checkPlan(Plan plan) {
        uniqueStrings(plan.requirements(), "requirements");
        paths(plan.scopeFiles(), "scopeFiles", false);
        Checks.uniqueBy(plan.cases(), PlanCase::id, InputCode.PRACTICES_IO_DUPLICATE_ENTRY, "case");
        Set<String> covered = new HashSet<>();
        for (PlanCase c : plan.cases()) {
            uniqueStrings(c.requirementIds(), "requirementIds");
            paths(c.testFiles(), "testFiles", false);
            need(c.argv().stream().noneMatch(argument -> argument.contains("\0")), InputCode.PRACTICES_CORE_NUL_IN_FROZEN_CASE_ARGV);
            for (String requirement : c.requirementIds()) {
                need(plan.requirements().contains(requirement), InputCode.PRACTICES_CORE_UNKNOWN_REQUIREMENT_IN_CASE, "caseId", c.id());
                covered.add(requirement);
            }
            need(plan.scopeFiles().containsAll(c.testFiles()), InputCode.PRACTICES_CORE_CASE_TEST_FILE_OUTSIDE_SCOPE, "caseId", c.id());
            need(filled(c.id()) && filled(c.oracle()) && filled(c.sensitivityReason()), InputCode.PRACTICES_CORE_CASE_IDENTITY_ORACLE_AND_SENSITIVITY_RATIONALE);
        }
        need(covered.containsAll(plan.requirements()), InputCode.PRACTICES_CORE_A_REQUIREMENT_HAS_NO_TEST_CASE);
    }

    PlanResult validatePlan(JsonNode document) {
        Plan plan = bindPlan(document);
        checkPlan(plan);
        return conform("engineering-plan-result", new PlanResult(VERSION, "engineering-plan-result", Verdict.READY, CanonicalJson.digest(document), plan.cases().stream().map(PlanCase::id).toList()));
    }

    // ---- receipts ----

    /** An instant written as the receipts write it (UTC, milliseconds), or null. */
    private static Instant exactIso(String value) {
        return value != null && EXACT_ISO.matcher(value).matches() ? Checks.instant(value) : null;
    }

    private static ReceiptBody body(Receipt receipt) {
        return new ReceiptBody(receipt.schemaVersion(), receipt.kind(), receipt.argv(), receipt.snapshot(), receipt.afterDigest(), receipt.startedAt(), receipt.finishedAt(), receipt.status(),
                receipt.exitCode(), receipt.stdout(), receipt.stderr(), receipt.stdoutDigest(), receipt.stderrDigest(), receipt.timeoutMs(), receipt.executable(), receipt.recorderRuntime(),
                receipt.platform(), receipt.environmentNote());
    }

    /**
     * A receipt another party made: its version, its shape, its own digests and the status it claims against the process facts it records. A receipt of the 5.x contract
     * (1.0.0: {@code nodeVersion}, {@code signal}) is refused with the reason, not converted: its facts were recorded by another recorder.
     */
    Receipt assertReceipt(JsonNode document) {
        need(!Objects.equals(bind(document, ReceiptVersion.class).schemaVersion(), LEGACY_RECEIPT_VERSION), InputCode.PRACTICES_CORE_RUN_RECEIPT_VERSION_1_IS_NOT_ACCEPTED);
        List<SkillSchemas.Violation> violations = schemas.violations(RECEIPT_CONTRACT, document);
        Checks.needWith(violations.isEmpty(), InputCode.PRACTICES_SCHEMA_VALIDATION_FAILED, SennardException.args("errors", violations), "name", "engineering-run-receipt");
        Receipt receipt = bind(document, Receipt.class);
        assertSnapshot(receipt.snapshot());
        need(receipt.argv().stream().noneMatch(argument -> argument.contains("\0")), InputCode.PRACTICES_CORE_NUL_IN_ARGV);
        need(Objects.equals(receipt.executable(), receipt.argv().getFirst()), InputCode.PRACTICES_CORE_RECEIPT_EXECUTABLE_DIFFERS_FROM_ARGV_0);
        Instant started = exactIso(receipt.startedAt());
        Instant finished = exactIso(receipt.finishedAt());
        need(started != null && finished != null && finished.compareTo(started) >= 0, InputCode.PRACTICES_CORE_INVALID_RECEIPT_TIMESTAMPS);
        need(receipt.timeoutMs() >= 1 && receipt.timeoutMs() <= MAX_TIMEOUT_MS, InputCode.PRACTICES_CORE_INVALID_TIMEOUT_BOUND);
        BigInteger exit = receipt.exitCode();
        need(exit == null || exit.bitLength() < Long.SIZE, InputCode.PRACTICES_CORE_UNSAFE_EXIT_CODE);
        need(Objects.equals(receipt.stdoutDigest(), Checks.hash(receipt.stdout())) && Objects.equals(receipt.stderrDigest(), Checks.hash(receipt.stderr())), IntegrityCode.PRACTICES_CORE_RECEIPT_LOG_DIGEST_MISMATCH);
        need(Objects.equals(receipt.digest(), Checks.digest(mapper, body(receipt))), IntegrityCode.PRACTICES_CORE_RECEIPT_DIGEST_MISMATCH);
        boolean unchanged = Objects.equals(receipt.snapshot().digest(), receipt.afterDigest());
        switch (receipt.status()) {
            case PASS -> need(exit != null && exit.signum() == 0 && unchanged, InputCode.PRACTICES_CORE_FALSE_PASSING_RECEIPT);
            case FAIL -> need(exit != null && exit.signum() != 0 && unchanged, InputCode.PRACTICES_CORE_NON_ASSERTING_PROCESS_FAILURE_CANNOT_MASQUERADE);
            case INPUT_CHANGED -> need(!unchanged, InputCode.PRACTICES_CORE_INPUT_CHANGED_REQUIRES_DIFFERENT_INPUTS);
            case TIMED_OUT, SPAWN_ERROR -> {
                // These two claim no exit and no unchanged input; the digests above are all there is to hold.
            }
        }
        return receipt;
    }

    private Receipt loadReceipt(FileFacts files, String root, FileRef ref) {
        portable(ref.path());
        need(Checks.isDigest(ref.digest()), InputCode.PRACTICES_CORE_INVALID_RECEIPT_REFERENCE);
        return assertReceipt(parseJson(readArtifact(files, root, ref)));
    }

    /** A file's bytes when they are the bytes its reference names. */
    private static byte[] readArtifact(FileFacts files, String root, FileRef ref) {
        byte[] bytes = files.readUnderRoot(root, ref.path(), FILE_LIMIT);
        if (!Objects.equals(Checks.hash(bytes), ref.digest())) {
            throw new IntegrityException(IntegrityCode.PRACTICES_IO_ARTIFACT_DIGEST_MISMATCH, Map.of(), SennardException.args("path", ref.path()));
        }
        return bytes;
    }

    // ---- running a test command ----

    private Receipt sealed(ReceiptBody body) {
        return new Receipt(body.schemaVersion(), body.kind(), body.argv(), body.snapshot(), body.afterDigest(), body.startedAt(), body.finishedAt(), body.status(), body.exitCode(),
                body.stdout(), body.stderr(), body.stdoutDigest(), body.stderrDigest(), body.timeoutMs(), body.executable(), body.recorderRuntime(), body.platform(), body.environmentNote(),
                Checks.digest(mapper, body));
    }

    /**
     * Runs the caller's command over a snapshot that still holds, and answers its receipt: the snapshot before, the digest of the same files after, the exit code, the
     * output and its digests. A receipt that is not {@code PASS} makes the exit code 1. A run that changed a file of the snapshot is {@code INPUT_CHANGED}, whatever the
     * command answered; a run that was still going at the limit is {@code TIMED_OUT}; one that could not be started, or wrote more than the runner keeps, is
     * {@code SPAWN_ERROR} (a command that wrote past the bound while it ran is killed and is {@code SPAWN_ERROR} too). The command starts in the real path of the root the
     * snapshot was checked in, with an emptied environment plus the server's listed variables and the caller's {@code env}; a batch file with an argument the Windows command
     * interpreter would read as syntax, an {@code env} out of bounds, and a run beyond the runner's concurrency limit are refused and nothing starts.
     */
    private SkillCheckResult runCommand(FileFacts files, SkillArguments args) {
        String root = args.requiredText("root");
        need(!args.isFileReference("snapshot"), InputCode.PRACTICES_RUNNER_SNAPSHOT_MUST_BE_UNDER_THE_ROOT);
        JsonNode snapshotDocument = rootDoc(args, "snapshot");
        validateSchema("engineering-snapshot", snapshotDocument);
        Snapshot snapshot = bind(snapshotDocument, Snapshot.class);
        List<String> argv = args.stringList("argv");
        long timeoutMs = args.integer("timeout-ms", DEFAULT_TIMEOUT_MS);
        String note = args.text("environment-note");
        Map<String, String> environment = args.stringMap("env");
        need(!argv.isEmpty() && argv.size() <= ARGV_ITEMS && argv.stream().allMatch(item -> !item.isEmpty() && item.length() <= ARGV_ITEM_LENGTH && item.indexOf('\0') < 0),
                InputCode.PRACTICES_RUNNER_EXPLICIT_BOUNDED_ARGV_IS_REQUIRED);
        need(timeoutMs >= 1 && timeoutMs <= MAX_TIMEOUT_MS, InputCode.PRACTICES_RUNNER_TIMEOUTMS_MUST_BE_WITHIN_1300000);
        need(environment.size() <= ENV_VARIABLES && environment.entrySet().stream().allMatch(variable -> environmentVariable(variable.getKey(), variable.getValue())),
                InputCode.PRACTICES_RUNNER_EXPLICIT_BOUNDED_ENV_IS_REQUIRED);
        String environmentNote = note == null ? runner.describeEnvironment(environment.keySet()) : note;
        need(filled(environmentNote), InputCode.PRACTICES_RUNNER_ENVIRONMENT_NOTE_IS_REQUIRED);
        verifySnapshot(files, root, snapshot);
        // The directory the snapshot was just checked in: its real path, the one the files were read through, not the text the caller wrote.
        Path workingDirectory = files.stat(root).filter(state -> state.type() == FileFacts.EntryType.DIRECTORY).map(state -> Path.of(state.realPath()))
                .orElseThrow(() -> new InvalidInputException(InputCode.PRACTICES_IO_ARTIFACT_ROOT_MUST_BE_A_REAL));

        String startedAt = Checks.iso(clock.instant());
        String runtime = Runtime.version().toString();
        String emptyDigest = Checks.hash("");
        // A receipt of a run that did not happen is held to the contract before the command starts: what the contract would refuse afterwards is refused now.
        assertReceipt(mapper.valueToTree(sealed(new ReceiptBody(RECEIPT_VERSION, "engineering-run-receipt", argv, snapshot, snapshot.digest(), startedAt, startedAt, RunStatus.SPAWN_ERROR, null, "", "",
                emptyDigest, emptyDigest, timeoutMs, argv.getFirst(), runtime, runner.platform(), environmentNote))));

        SkillProcessRunner.Completed child;
        try {
            child = runner.run(workingDirectory, argv, environment, Duration.ofMillis(timeoutMs));
        } catch (SkillProcessRunner.Refused refused) {
            throw switch (refused.reason()) {
                case TOO_MANY_RUNS -> new InvalidInputException(InputCode.PRACTICES_RUNNER_TOO_MANY_RUNS, SennardException.args("limit", refused.detail()), Map.of(), refused);
                case STOPPING -> new InvalidInputException(InputCode.PRACTICES_RUNNER_SERVER_IS_STOPPING, Map.of(), Map.of(), refused);
                case UNSAFE_ARGUMENT -> new InvalidInputException(InputCode.PRACTICES_RUNNER_BATCH_FILE_ARGUMENTS_UNSAFE, SennardException.args("index", refused.detail()), Map.of(), refused);
            };
        }
        String finishedAt = Checks.iso(clock.instant());
        String afterDigest = digestAfterRun(files, root, snapshot);
        RunStatus status = !Objects.equals(afterDigest, snapshot.digest()) ? RunStatus.INPUT_CHANGED : switch (child) {
            case SkillProcessRunner.Exited exited -> exited.outputTruncated() ? RunStatus.SPAWN_ERROR : exited.exitCode() == 0 ? RunStatus.PASS : RunStatus.FAIL;
            case SkillProcessRunner.TimedOut _ -> RunStatus.TIMED_OUT;
            case SkillProcessRunner.Failed _ -> RunStatus.SPAWN_ERROR;
        };
        BigInteger exitCode = child instanceof SkillProcessRunner.Exited exited ? BigInteger.valueOf(exited.exitCode()) : null;
        Receipt receipt = sealed(new ReceiptBody(RECEIPT_VERSION, "engineering-run-receipt", argv, snapshot, afterDigest, startedAt, finishedAt, status, exitCode, child.stdout(), child.stderr(),
                Checks.hash(child.stdout()), Checks.hash(child.stderr()), timeoutMs, argv.getFirst(), runtime, runner.platform(), environmentNote));
        assertReceipt(mapper.valueToTree(receipt));
        return SkillCheckResult.of(receipt, status == RunStatus.PASS);
    }

    /** Whether a variable of the caller can be set: a name of 1..128 characters without {@code =} or a control character (NUL among them), a value of at most 8192 without NUL. */
    private static boolean environmentVariable(String name, String value) {
        return !name.isEmpty() && name.length() <= ENV_NAME_LENGTH && name.chars().noneMatch(c -> c == '=' || c <= 31 || c == 127) && value.length() <= ENV_VALUE_LENGTH
                && value.indexOf('\0') < 0;
    }

    /**
     * The digest of the snapshot's files as they are after the run; files the run left unreadable are answered with a digest no snapshot has.
     *
     * <p>The run may have ended because the calling thread was interrupted, and reading a file is interruptible: with the interrupt set every read would fail and the receipt
     * would call an untouched input changed. The interrupt is set aside while the files are read and set again afterwards.
     */
    private String digestAfterRun(FileFacts files, String root, Snapshot snapshot) {
        boolean interrupted = Thread.interrupted();
        try {
            return captureSnapshot(files, root, snapshotPaths(snapshot)).digest();
        } catch (FileFactsException | InvalidInputException | IntegrityException | UncheckedIOException unreadable) {
            LOG.info("A file of the snapshot could not be read after the command ran; the receipt records the input as changed.", unreadable);
            return Checks.hash(UNREADABLE_AFTER_RUN);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- the proof ----

    TestResult validateProof(FileFacts files, String root, JsonNode planDocument, JsonNode proofDocument) {
        Plan plan = bindPlan(planDocument);
        checkPlan(plan);
        return proofResult(files, root, plan, planDocument, proofDocument);
    }

    private static boolean mechanical(Sensitivity sensitivity) {
        return sensitivity == Sensitivity.RED_GREEN || sensitivity == Sensitivity.MUTATION;
    }

    private TestResult proofResult(FileFacts files, String root, Plan plan, JsonNode planDocument, JsonNode proofDocument) {
        validateSchema("engineering-test-proof", proofDocument);
        Proof proof = bind(proofDocument, Proof.class);
        need(Objects.equals(proof.planDigest(), CanonicalJson.digest(planDocument)), IntegrityCode.PRACTICES_CORE_PROOF_BELONGS_TO_A_DIFFERENT_FROZEN);
        Snapshot candidate = proof.candidate();
        verifySnapshot(files, root, candidate);
        List<String> candidatePaths = snapshotPaths(candidate);
        need(Checks.sameSet(plan.scopeFiles(), candidatePaths), InputCode.PRACTICES_CORE_CANDIDATE_FILE_SET_DIFFERS_FROM_THE);
        Map<String, ProofEntry> records = Checks.uniqueBy(proof.proofs(), ProofEntry::caseId, InputCode.PRACTICES_IO_DUPLICATE_ENTRY, "proof");
        need(Checks.sameSet(records.keySet(), plan.cases().stream().map(PlanCase::id).toList()), InputCode.PRACTICES_CORE_PROOF_CASES_DO_NOT_EXACTLY_MATCH);
        Set<String> allTestPaths = new HashSet<>();
        plan.cases().forEach(c -> allTestPaths.addAll(c.testFiles()));
        List<String> issues = new ArrayList<>();
        boolean incomplete = false;
        for (PlanCase c : plan.cases()) {
            ProofEntry entry = records.get(c.id());
            List<String> mutation = entry.mutationPaths();
            paths(mutation, "mutationPaths", true);
            switch (entry.status()) {
                case EXEMPT -> {
                    needNoExecution(entry, mutation);
                    need(c.sensitivity() == Sensitivity.NOT_APPLICABLE, InputCode.PRACTICES_CORE_EXEMPTION_WAS_NOT_IN_THE_FROZEN);
                }
                case NOT_RUN -> {
                    needNoExecution(entry, mutation);
                    issues.add((c.required() ? "REQUIRED " : "OPTIONAL ") + c.id() + ": NOT_RUN — " + entry.reason());
                    incomplete |= c.required();
                }
                case DEMONSTRATED -> demonstrate(files, root, c, entry, candidate, candidatePaths, allTestPaths, mutation);
            }
        }
        return conform("engineering-test-result", new TestResult(VERSION, "engineering-test-result", incomplete ? Verdict.INCOMPLETE : Verdict.CONSISTENT, candidate.digest(),
                CanonicalJson.digest(proofDocument), issues, LIMITATIONS));
    }

    /** A proof of a case that was not run (or is exempt) carries no run: no receipts, no mutation, no witness, and a reason. */
    private static void needNoExecution(ProofEntry entry, List<String> mutation) {
        need(entry.red().path().isEmpty() && entry.green().path().isEmpty() && mutation.isEmpty() && entry.assertionWitness().isEmpty() && filled(entry.reason()), InputCode.PRACTICES_CORE_A_NON_EXECUTED_PROOF_CANNOT_CARRY);
    }

    /** A demonstrated case: a failing red and a passing green of the frozen command, on the same tests, differing only in the production files the proof names. */
    private void demonstrate(FileFacts files, String root, PlanCase c, ProofEntry entry, Snapshot candidate, List<String> candidatePaths, Set<String> allTestPaths, List<String> mutation) {
        need(mechanical(c.sensitivity()), InputCode.PRACTICES_CORE_DEMONSTRATION_CONFLICTS_WITH_THE_PLANNED_METHOD);
        FileRef redRef = entry.red();
        FileRef greenRef = entry.green();
        need(!Objects.equals(redRef.path(), greenRef.path()) && !Objects.equals(redRef.digest(), greenRef.digest()) && entry.assertionWitness().trim().length() >= MIN_WITNESS,
                InputCode.PRACTICES_CORE_DISTINCT_RED_GREEN_RECEIPTS_AND_A);
        Receipt red = loadReceipt(files, root, redRef);
        Receipt green = loadReceipt(files, root, greenRef);
        need(red.status() == RunStatus.FAIL && green.status() == RunStatus.PASS, InputCode.PRACTICES_CORE_PROOF_REQUIRES_ORDINARY_FAILING_RED_AND);
        String candidateDigest = candidate.digest();
        need(Objects.equals(green.snapshot().digest(), candidateDigest) && Objects.equals(green.afterDigest(), candidateDigest), IntegrityCode.PRACTICES_CORE_GREEN_EVIDENCE_IS_FOR_A_DIFFERENT);
        need(!Objects.equals(red.snapshot().digest(), green.snapshot().digest()), InputCode.PRACTICES_CORE_NO_PRODUCTION_MUTATION_IS_REPRESENTED);
        need(red.argv().equals(green.argv()), InputCode.PRACTICES_CORE_RED_AND_GREEN_COMMANDS_DIFFER);
        need(red.argv().equals(c.argv()) && green.argv().equals(c.argv()), InputCode.PRACTICES_CORE_RED_AND_GREEN_COMMANDS_MUST_MATCH);
        need(Objects.equals(red.environmentNote(), green.environmentNote()) && Objects.equals(red.recorderRuntime(), green.recorderRuntime()) && Objects.equals(red.platform(), green.platform())
                && Objects.equals(red.executable(), green.executable()), InputCode.PRACTICES_CORE_DECLARED_EXECUTION_ENVIRONMENTS_DIFFER);
        need(exactIso(red.finishedAt()).compareTo(exactIso(green.startedAt())) <= 0, InputCode.PRACTICES_CORE_GREEN_MUST_FOLLOW_THE_RECORDED_RED);
        need(Checks.sameSet(snapshotPaths(red.snapshot()), candidatePaths), InputCode.PRACTICES_CORE_RED_AND_GREEN_FILE_SETS_DIFFER);
        List<String> changed = delta(red.snapshot(), green.snapshot());
        need(!changed.isEmpty() && Checks.sameSet(changed, mutation) && changed.stream().noneMatch(allTestPaths::contains), InputCode.PRACTICES_CORE_MUTATION_MUST_EXACTLY_DESCRIBE_PRODUCTION_CHANGES);
        Map<String, String> redDigests = digests(red.snapshot());
        Map<String, String> greenDigests = digests(green.snapshot());
        need(c.testFiles().stream().allMatch(file -> Objects.equals(redDigests.get(file), greenDigests.get(file))), IntegrityCode.PRACTICES_CORE_TEST_CODE_DIFFERS_BETWEEN_RED_AND);
        need((red.stdout() + "\n" + red.stderr()).contains(entry.assertionWitness()), InputCode.PRACTICES_CORE_THE_CLAIMED_ASSERTION_WITNESS_IS_ABSENT);
    }

    // ---- the review ----

    private ReviewRequest bindRequest(JsonNode document) {
        validateSchema("engineering-review-request", document);
        return bind(document, ReviewRequest.class);
    }

    private void checkRequest(ReviewRequest request) {
        assertSnapshot(request.base());
        assertSnapshot(request.head());
        List<String> changed = request.changedFiles();
        List<String> excluded = request.excludedFiles();
        paths(changed, "changedFiles", false);
        paths(excluded, "excludedFiles", true);
        uniqueStrings(request.requirements(), "requirements");
        need(Checks.sameSet(delta(request.base(), request.head()), changed), InputCode.PRACTICES_CORE_CHANGED_FILE_INVENTORY_OMITS_OR_INVENTS);
        need(changed.containsAll(excluded), InputCode.PRACTICES_CORE_EXCLUDED_PATH_IS_NOT_IN_THE);
        need(changed.stream().anyMatch(path -> !excluded.contains(path)), InputCode.PRACTICES_CORE_THE_REQUEST_EXCLUDES_ITS_ENTIRE_REVIEW);
    }

    ReviewResult validateReview(FileFacts files, String root, JsonNode requestDocument, JsonNode reportDocument) {
        ReviewRequest request = bindRequest(requestDocument);
        checkRequest(request);
        return reviewResult(files, root, request, requestDocument, reportDocument);
    }

    private ReviewResult reviewResult(FileFacts files, String root, ReviewRequest request, JsonNode requestDocument, JsonNode reportDocument) {
        validateSchema("engineering-review-report", reportDocument);
        ReviewReport report = bind(reportDocument, ReviewReport.class);
        need(Objects.equals(report.requestDigest(), CanonicalJson.digest(requestDocument)) && Objects.equals(report.headDigest(), request.head().digest()), IntegrityCode.PRACTICES_CORE_REVIEW_DOES_NOT_BIND_THIS_REQUEST);
        verifySnapshot(files, root, request.head());
        List<String> headPaths = snapshotPaths(request.head());
        for (SnapshotFile file : request.base().files()) {
            if (!headPaths.contains(file.path())) {
                need(!files.existsUnderRoot(root, file.path()), IntegrityCode.PRACTICES_CORE_A_SUPPOSEDLY_DELETED_FILE_IS_STILL);
            }
        }
        Map<String, Coverage> coverage = Checks.uniqueBy(report.coverage(), Coverage::path, InputCode.PRACTICES_IO_DUPLICATE_ENTRY, "coverage");
        List<String> changed = request.changedFiles();
        List<String> excluded = request.excludedFiles();
        need(Checks.sameSet(coverage.keySet(), changed), InputCode.PRACTICES_CORE_EVERY_CHANGED_FILE_MUST_BE_REVIEWED);
        Checks.uniqueBy(report.findings(), Finding::id, InputCode.PRACTICES_IO_DUPLICATE_ENTRY, "finding");
        List<String> issues = new ArrayList<>();
        boolean incomplete = false;
        boolean blocking = false;
        for (Coverage entry : coverage.values()) {
            need(filled(entry.reason()), InputCode.PRACTICES_CORE_COVERAGE_REQUIRES_A_REASON_SCOPE_NOTE);
            boolean isExcluded = excluded.contains(entry.path());
            if (isExcluded) {
                need(entry.status() == CoverageStatus.NOT_REVIEWED, InputCode.PRACTICES_CORE_AN_EXCLUDED_FILE_CANNOT_BE_COUNTED);
            }
            if (entry.status() == CoverageStatus.NOT_REVIEWED) {
                issues.add((isExcluded ? "EXCLUDED " : "UNREVIEWED ") + entry.path() + ": " + entry.reason());
                incomplete |= !isExcluded;
            }
        }
        for (Finding finding : report.findings()) {
            portable(finding.path());
            Coverage covered = coverage.get(finding.path());
            need(covered != null && covered.status() == CoverageStatus.REVIEWED, InputCode.PRACTICES_CORE_FINDING_POINTS_AT_AN_UNREVIEWED_OR);
            Snapshot side = switch (finding.side()) {
                case HEAD -> request.head();
                case BASE -> request.base();
            };
            Optional<SnapshotFile> source = side.files().stream().filter(file -> Objects.equals(file.path(), finding.path())).findFirst();
            need(source.map(file -> finding.lineStart() >= 1 && finding.lineEnd() >= finding.lineStart() && finding.lineEnd() <= file.lineCount()).orElse(false), InputCode.PRACTICES_CORE_FINDING_LINE_RANGE_IS_OUTSIDE_THE);
            need(Stream.of(finding.id(), finding.contract(), finding.trace(), finding.impact(), finding.correction()).allMatch(EngineeringPractices::filled), InputCode.PRACTICES_CORE_FINDING_HAS_MISSING_REASONING_OR_IDENTITY);
            if (finding.category() == Category.RECOMMENDATION || finding.confidence() == Confidence.HYPOTHESIS) {
                need(!finding.blocking(), InputCode.PRACTICES_CORE_RECOMMENDATIONS_AND_UNSUPPORTED_HYPOTHESES_CANNOT_BE);
            }
            if (finding.blocking()) {
                need(finding.category() == Category.DEFECT, InputCode.PRACTICES_CORE_BLOCKING_ITEM_IS_NOT_A_DEFECT);
                blocking = true;
            }
        }
        for (Handoff handoff : report.handoffs()) {
            portable(handoff.path());
            need(changed.contains(handoff.path()) && filled(handoff.owner()) && filled(handoff.reason()), InputCode.PRACTICES_CORE_INVALID_SPECIALIST_HANDOFF);
        }
        Verdict verdict = blocking ? Verdict.CHANGES_REQUESTED : incomplete ? Verdict.INCOMPLETE : Verdict.NO_BLOCKING_FINDINGS;
        List<String> limitations = new ArrayList<>(LIMITATIONS);
        limitations.add(REVIEW_LIMITATION);
        return conform("engineering-review-result", new ReviewResult(VERSION, "engineering-review-result", verdict, request.head().digest(), CanonicalJson.digest(reportDocument), issues, limitations));
    }

    // ---- the catalog ----

    private Catalog catalog() {
        need(VERSION.equals(packaged.schemaVersion()), InputCode.PRACTICES_CORE_UNKNOWN_CATALOG_FORMAT);
        Checks.uniqueBy(packaged.rules(), Rule::id, InputCode.PRACTICES_IO_DUPLICATE_ENTRY, "rule");
        for (Rule rule : packaged.rules()) {
            need(Stream.of(rule.title(), rule.appliesWhen(), rule.action(), rule.evidence(), rule.avoid(), rule.exceptions(), rule.owner(), rule.module()).allMatch(EngineeringPractices::filled),
                    InputCode.PRACTICES_CORE_INCOMPLETE_RULE_CARD);
        }
        return packaged;
    }

    /** The whole catalog, or the rules of one module. */
    Catalog catalog(String module) {
        Catalog catalog = catalog();
        if (module == null) {
            return catalog;
        }
        List<Rule> rules = catalog.rules().stream().filter(rule -> Objects.equals(module, rule.module())).toList();
        need(!rules.isEmpty(), InputCode.PRACTICES_CLI_UNKNOWN_MODULE);
        return new Catalog(catalog.schemaVersion(), catalog.packVersion(), rules);
    }

    /** The Java runtime's own consistency check: the packaged catalog parses and every rule is bound; what the build packaged is the build's concern. */
    SelfCheck selfCheck() {
        Catalog catalog = catalog();
        return new SelfCheck(SelfCheckStatus.CATALOG_CONSISTENT, catalog.rules().size(), catalog.rules().stream().map(Rule::module).distinct().toList(), SCHEMA_ENGINE, HostBehavior.NOT_RUN);
    }

    // ---- provider results ----

    /** A result a provider made, wrapped as the provider-output contract has it; a verdict that is not a pass is answered with an error beside the output. */
    ProviderOutput providerResult(String capabilityName, JsonNode result, String attachmentId, String artifactDigest) {
        if (attachmentId == null || !ATTACHMENT_ID.matcher(attachmentId).matches()) {
            throw new InvalidInputException(InputCode.VALIDATION_FAILED, Map.of(), Map.of("violations", List.of("attachmentId: the id of an attachment made with the artifact tools")));
        }
        Capability capability = Capability.fromWire(capabilityName);
        need(capability != null, InputCode.PRACTICES_CORE_UNSUPPORTED_CAPABILITY);
        validateSchema(capability.schema(), result);
        EngineeringResult bound = switch (capability) {
            case TEST_PLAN_VALIDATION -> bind(result, PlanResult.class);
            case TEST_SENSITIVITY_REVIEW -> bind(result, TestResult.class);
            case CHANGE_CODE_REVIEW -> bind(result, ReviewResult.class);
        };
        String target = switch (bound) {
            case PlanResult plan -> plan.planDigest();
            case TargetResult judged -> judged.targetDigest();
        };
        Artifact artifact = new Artifact(capability.schema(), CONTRACT_ID + capability.schema() + ".v1.schema.json", attachmentId, artifactDigest, target, false);
        ProviderError error = switch (bound.verdict()) {
            case CHANGES_REQUESTED -> failure(ErrorCode.GATE_FAILED, "blockingFindingsRemain", bound);
            case INCOMPLETE -> failure(ErrorCode.MISSING_EVIDENCE, "requiredEvidenceIncomplete", bound);
            case READY, CONSISTENT, NO_BLOCKING_FINDINGS -> null;
        };
        return new ProviderOutput(VERSION, "output", bound, List.of(artifact), error);
    }

    private ProviderError failure(ErrorCode code, String messageKey, EngineeringResult result) {
        List<String> issues = switch (result) {
            case PlanResult plan -> List.of();
            case TargetResult judged -> judged.issues();
        };
        return new ProviderError(code, messages.forRequest(CORE + messageKey, Map.of()), new ErrorDetails(issues));
    }

    // ---- the stage bundle ----

    /** A review or a proof bound to its frozen task (a stage's closed evidence, not attestation). */
    StageCheck checkStageBundle(FileFacts files, String root, String input, String bundleDigest, String taskDigest, String capabilityName) {
        byte[] bytes = files.readUnderRoot(root, input, BUNDLE_LIMIT);
        need(Objects.equals(Checks.hash(bytes), bundleDigest), IntegrityCode.PRACTICES_STAGE_STAGE_BUNDLE_BYTES_CHANGED);
        JsonNode manifest = parseJson(bytes);
        Capability capability = Capability.fromWire(capabilityName);
        need(capability == Capability.TEST_SENSITIVITY_REVIEW || capability == Capability.CHANGE_CODE_REVIEW, InputCode.PRACTICES_STAGE_UNSUPPORTED_ENGINEERING_STAGE_CAPABILITY);
        StageBundle bundle = bindBundle(manifest, capability);
        need(VERSION.equals(bundle.schemaVersion()) && bundle.kind() == BundleKind.ENGINEERING_STAGE_BUNDLE && capability == bundle.capability(), InputCode.PRACTICES_STAGE_INVALID_OR_MISMATCHED_STAGE_BUNDLE);
        Refs refs = switch (bundle) {
            case TestBundle test -> new Refs(test.task(), test.plan(), test.proof(), test.result());
            case ReviewBundle review -> new Refs(review.task(), review.request(), review.report(), review.result());
        };
        JsonNode taskDocument = rawFile(files, root, refs.task());
        JsonNode preparedDocument = rawFile(files, root, refs.prepared());
        JsonNode evidenceDocument = rawFile(files, root, refs.evidence());
        JsonNode resultDocument = rawFile(files, root, refs.result());
        validateSchema("task-envelope", taskDocument);
        TaskEnvelope task = bind(taskDocument, TaskEnvelope.class);
        need(Objects.equals(CanonicalJson.digest(taskDocument), taskDigest), IntegrityCode.PRACTICES_STAGE_BUNDLE_DOES_NOT_BIND_THE_SIGNED);
        need(task.requiredCapabilities().contains(capability.wire()), InputCode.PRACTICES_STAGE_TASK_DID_NOT_SELECT_THIS_ENGINEERING);
        TargetResult result = switch (bundle) {
            case TestBundle test -> testStage(files, root, task, taskDigest, preparedDocument, evidenceDocument);
            case ReviewBundle review -> reviewStage(files, root, task, taskDigest, preparedDocument, evidenceDocument);
        };
        need(Objects.equals(Checks.digest(mapper, result), CanonicalJson.digest(resultDocument)), IntegrityCode.PRACTICES_STAGE_STAGE_RESULT_DIFFERS_FROM_CURRENT_RAW);
        return new StageCheck("engineering-stage-check", task, capability, result, refs.result(), result.targetDigest(), result.limitations());
    }

    /** A bundle is bound to the record of its capability; a member the record does not name is refused. */
    private StageBundle bindBundle(JsonNode manifest, Capability capability) {
        Class<? extends StageBundle> type = capability == Capability.TEST_SENSITIVITY_REVIEW ? TestBundle.class : ReviewBundle.class;
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(manifest);
        } catch (JacksonException mismatch) {
            throw new InvalidInputException(InputCode.PRACTICES_STAGE_INVALID_OR_MISMATCHED_STAGE_BUNDLE, Map.of(), Map.of(), mismatch);
        }
    }

    private JsonNode rawFile(FileFacts files, String root, FileRef ref) {
        need(ref != null && ref.path() != null && ref.digest() != null, InputCode.PRACTICES_STAGE_EXPECTED_A_CLOSED_RAW_FILE_REFERENCE);
        return parseJson(readArtifact(files, root, ref));
    }

    /** The prepared input answers the signed task: its id and contract digest, and the requirements the task froze. */
    private static void bindsTask(String taskId, String contractDigest, List<String> requirements, TaskEnvelope task, String taskDigest) {
        need(Objects.equals(taskId, task.taskId()) && Objects.equals(taskDigest, contractDigest), IntegrityCode.PRACTICES_STAGE_PREPARED_INPUT_DOES_NOT_BIND_THIS);
        need(Checks.sameSet(requirements, task.acceptanceCriteria()), InputCode.PRACTICES_STAGE_PREPARED_REQUIREMENTS_DIFFER_FROM_THE_FROZEN);
    }

    private TestResult testStage(FileFacts files, String root, TaskEnvelope task, String taskDigest, JsonNode planDocument, JsonNode proofDocument) {
        Plan plan = bindPlan(planDocument);
        bindsTask(plan.taskId(), plan.contractDigest(), plan.requirements(), task, taskDigest);
        checkPlan(plan);
        List<PlanCase> required = plan.cases().stream().filter(PlanCase::required).toList();
        Set<String> requiredCoverage = new HashSet<>();
        required.forEach(c -> requiredCoverage.addAll(c.requirementIds()));
        need(requiredCoverage.containsAll(task.acceptanceCriteria()), InputCode.PRACTICES_STAGE_A_FROZEN_REQUIREMENT_HAS_NO_REQUIRED);
        need(required.stream().allMatch(c -> mechanical(c.sensitivity())), InputCode.PRACTICES_STAGE_REQUIRED_SENSITIVITY_EVIDENCE_CANNOT_BE_DOWNGRADED);
        return proofResult(files, root, plan, planDocument, proofDocument);
    }

    private ReviewResult reviewStage(FileFacts files, String root, TaskEnvelope task, String taskDigest, JsonNode requestDocument, JsonNode reportDocument) {
        ReviewRequest request = bindRequest(requestDocument);
        bindsTask(request.taskId(), request.contractDigest(), request.requirements(), task, taskDigest);
        checkRequest(request);
        return reviewResult(files, root, request, requestDocument, reportDocument);
    }
}
