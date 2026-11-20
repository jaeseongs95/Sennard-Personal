// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.ObservedFile;
import io.github.jaeseongs95.sennard.app.journal.ScanBasis;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.host.ForeignPathText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * change-scope-guardian: a task's write scope against the change record of the run (its workflow): the baseline taken when the plan was accepted, and the changes the hooks and the
 * rescans recorded after it ({@link ChangeJournalService}). No version control system is read; a file counts as changed when the record says so. Paths are the record's absolute paths,
 * written with {@code /}; the scopes of the task envelope are relative to the run's workspace ({@code workspaceRoot} of the request).
 */
@Component
public class ChangeScopeCheck implements SkillCheck<ChangeScopeCheck.Command> {
    private static final String CONTRACTS = "skills/change-scope-guardian/contracts/";
    private static final String REQUEST = CONTRACTS + "change-scope-request.v1.schema.json";
    private static final String BASELINE = CONTRACTS + "workspace-baseline.v1.schema.json";
    private static final String REPORT = CONTRACTS + "change-scope-report.v1.schema.json";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String NO_BASELINE = "0".repeat(64);
    /** The finding of a path the record could not read: what was not read is not reported as unchanged. */
    private static final String UNREADABLE = "unreadable:";
    private static final Pattern HEX64 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    private static final Pattern DOT_COMPONENT = Pattern.compile("(^|.*/)\\.\\.?($|/.*)");
    private static final Pattern CONTROL = Pattern.compile(".*[\\x00-\\x1f\\x7f].*", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]");

    private final SkillSchemas schemas;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ChangeJournalService journal;
    private final JournalScanner scanner;

    public ChangeScopeCheck(SkillSchemas schemas, JsonMapper mapper, Clock clock, ChangeJournalService journal, JournalScanner scanner) {
        this.schemas = schemas;
        this.mapper = mapper;
        this.clock = clock;
        this.journal = journal;
        this.scanner = scanner;
    }

    // ---------------------------------------------------------------------------------------------------- contract values

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        CAPTURE_BASELINE("capture-baseline"), COMPARE("compare"), VALIDATE_REPORT("validate-report");

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
        CAPTURE("capture"), VERIFY("verify");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }
    }

    /** What the record says happened to a path since the baseline. */
    enum Status {
        CREATED("created"), MODIFIED("modified"), DELETED("deleted");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }
    }

    enum Classification {
        IN_SCOPE("in-scope"), EXCLUDED("excluded"), UNPLANNED("unplanned"), OWNERSHIP_UNKNOWN("ownership-unknown");

        private final String label;

        Classification(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }

        /** Whether a change of this class is listed among a report's findings. */
        boolean isFinding() {
            return switch (this) {
                case IN_SCOPE -> false;
                case EXCLUDED, UNPLANNED, OWNERSHIP_UNKNOWN -> true;
            };
        }
    }

    enum Verdict {
        PASS, NEEDS_REVIEW, BLOCKED, INCONCLUSIVE
    }

    enum ArtifactKind {
        BASELINE("workspace-baseline"), REPORT("change-scope-report");

        private final String label;

        ArtifactKind(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }
    }

    /** One file of a baseline, as the record holds it: the absolute path, the size, the modification time (UTC, milliseconds) and the digest (only a file that was planned when the baseline was taken has one). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Entry(String path, long sizeBytes, String modifiedAt, String sha256) {
    }

    record WorkflowRef(String id) {
    }

    /** The baseline document, as the check writes it and as a caller hands it back. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Baseline(String schemaVersion, WorkflowRef workflow, long planRevision, String taskEnvelopeDigest, List<Entry> entries, String manifestSha256, String capturedAt) {
    }

    /** What a baseline's checksum covers: the baseline without its own checksum. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Manifest(String workflowId, long planRevision, String taskEnvelopeDigest, List<Entry> entries, String capturedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Change(String path, Status status, Classification classification, String baselineSha256, String currentSha256, String matchedRule) {
    }

    record Summary(@JsonProperty("in-scope") long inScope, @JsonProperty("excluded") long excluded, @JsonProperty("unplanned") long unplanned, @JsonProperty("ownership-unknown") long ownershipUnknown) {
        static Summary count(List<Change> changes) {
            long[] counts = new long[Classification.values().length];
            for (Change change : changes) {
                counts[change.classification().ordinal()]++;
            }
            return new Summary(counts[Classification.IN_SCOPE.ordinal()], counts[Classification.EXCLUDED.ordinal()], counts[Classification.UNPLANNED.ordinal()], counts[Classification.OWNERSHIP_UNKNOWN.ordinal()]);
        }

        long of(Classification classification) {
            return switch (classification) {
                case IN_SCOPE -> inScope;
                case EXCLUDED -> excluded;
                case UNPLANNED -> unplanned;
                case OWNERSHIP_UNKNOWN -> ownershipUnknown;
            };
        }

        /** The verdict a comparison against a baseline draws from its counts. */
        Verdict verdict() {
            return excluded > 0 ? Verdict.BLOCKED : unplanned > 0 ? Verdict.NEEDS_REVIEW : Verdict.PASS;
        }
    }

    /**
     * The report; {@code reportSha256} is the digest of the report without it, so it is absent while the digest is being made. {@code currentDigest} is the canonical digest of
     * {@code changes}: the record has no tree to fingerprint, so the present state is the list of changes it reports.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Report(String schemaVersion, String workflowId, String baselineDigest, String currentDigest, String taskEnvelopeDigest, List<Change> changes, Summary summary, List<String> findings, Verdict verdict,
            String observedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String reportSha256) {
        Report unsigned() {
            return signedWith(null);
        }

        Report signedWith(String digest) {
            return new Report(schemaVersion, workflowId, baselineDigest, currentDigest, taskEnvelopeDigest, changes, summary, findings, verdict, observedAt, digest);
        }
    }

    record Validated(boolean valid, ArtifactKind kind) {
    }

    // ---------------------------------------------------------------------------------------------------------- inputs

    /**
     * The request. The envelope is another check's document (task-contract owns its schema): it is hashed as given, and its write scopes are read through {@link Envelope}.
     * {@code workspaceRoot} is the folder the envelope's scopes are relative to; the contract requires it for {@code verify}.
     */
    record Request(String schemaVersion, Mode mode, String workflowId, String workspaceRoot, JsonNode taskEnvelope, Baseline baseline, String baselineArtifactDigest) {
    }

    /** The part of a task envelope this check reads; the rest is the envelope schema's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Scope scope, List<WorkUnit> workUnits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Scope(List<String> included, List<String> excluded) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkUnit(String id, List<String> writeTargets) {
    }

    /** {@code validate-report}: the artifact is the caller's document, a baseline or a report (told apart by {@link Shape}); it goes to its schema as given and is bound to its record after that. */
    record ArtifactInput(JsonNode artifact, String expectedArtifactDigest) {
    }

    /** What tells a baseline from a report; its members are bound by their own record after the schema accepted them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Shape(List<JsonNode> entries, String manifestSha256) {
    }

    /** A request that passed its schema and its rules: the bound record, the digest of the envelope as given, and the write scopes in portable form. */
    private record Accepted(Request request, String envelopeDigest, Rules rules) {
    }

    /** The scopes of a task envelope, normalised: what may be written (the scope plus every work unit's write targets) and what may not. */
    private record Rules(List<String> included, List<String> excluded) {
    }

    // ------------------------------------------------------------------------------------------------------------ entry

    @Override
    public String skill() {
        return "change-scope-guardian";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    /** {@code compare} rescans the write roots of the run (it reads files); {@code capture-baseline} only reads the record. */
    @Override
    public Set<Command> backgroundCommands() {
        return EnumSet.of(Command.COMPARE);
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return SkillCheckResult.ok(switch (command) {
            case CAPTURE_BASELINE -> captureBaseline(args.document("input"), clock.instant());
            case COMPARE -> compare(args.document("input"), clock.instant());
            case VALIDATE_REPORT -> validateArtifact(args.document("input"));
        });
    }

    private String digestOf(Object document) {
        return Digests.sha256Hex(CanonicalJson.of(mapper.valueToTree(document)));
    }

    private void assertSchema(String name, String location, JsonNode value) {
        List<SkillSchemas.Violation> violations = schemas.violations(location, value);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.CHANGE_SCOPE_SCHEMA_FAILED, SennardException.args("name", name, "detail", String.join("; ", SkillSchemas.texts(violations))));
        }
    }

    // ------------------------------------------------------------------------------------------------------- the record

    /** A path as the documents write it: the record's absolute path with {@code /} on every platform. */
    private static String text(Path path) {
        return ForeignPathText.slashed(path.toString());
    }

    /** The run's record must exist and be past its plan: before the baseline there is nothing to compare with. */
    private void requireExecuting(String workflowId) {
        WorkflowPhase phase = journal.phase(workflowId).orElseThrow(() -> new InvalidInputException(InputCode.CHANGE_SCOPE_JOURNAL_MISSING, SennardException.args("workflowId", workflowId)));
        need(phase == WorkflowPhase.EXECUTING, InputCode.CHANGE_SCOPE_JOURNAL_PLANNING, "workflowId", workflowId);
    }

    /** The record's baseline in the documents' form. */
    private List<Entry> recordedBaseline(String workflowId) {
        return journal.baseline(workflowId).stream().map(entry -> new Entry(text(entry.path()), entry.sizeBytes(), Checks.iso(entry.modifiedAt()), entry.sha256())).toList();
    }

    private String manifestDigest(Baseline baseline) {
        return digestOf(new Manifest(baseline.workflow().id(), baseline.planRevision(), baseline.taskEnvelopeDigest(), baseline.entries(), baseline.capturedAt()));
    }

    private Baseline withChecksum(Baseline baseline) {
        return new Baseline(baseline.schemaVersion(), baseline.workflow(), baseline.planRevision(), baseline.taskEnvelopeDigest(), baseline.entries(), manifestDigest(baseline), baseline.capturedAt());
    }

    /** The baseline of the run's record bound to this envelope; read-only. */
    private Baseline captureBaseline(JsonNode input, Instant now) {
        Accepted accepted = accept(input, Mode.CAPTURE);
        Request request = accepted.request();
        String workflowId = request.workflowId();
        Baseline given = request.baseline();
        if (given != null) {
            // A scope update: the record's baseline is always the state before the task, so the given one (already shown equal to it) is bound to the new envelope as it was taken.
            return withChecksum(new Baseline(SCHEMA_VERSION, given.workflow(), given.planRevision(), accepted.envelopeDigest(), given.entries(), null, given.capturedAt()));
        }
        return withChecksum(new Baseline(SCHEMA_VERSION, new WorkflowRef(workflowId), journal.plan(workflowId).revision(), accepted.envelopeDigest(), recordedBaseline(workflowId), null, Checks.iso(now)));
    }

    // ----------------------------------------------------------------------------------------------------------- scopes

    private static boolean unsafeRule(String normalized) {
        return normalized.isEmpty() || ".".equals(normalized) || normalized.startsWith("/") || SCHEME.matcher(normalized).matches() || normalized.startsWith("~") || "..".equals(normalized) || normalized.startsWith("../")
                || DOT_COMPONENT.matcher(normalized).matches() || normalized.contains("//") || CONTROL.matcher(normalized).matches()
                || (IDENTIFIER.matcher(normalized).find() && (normalized.contains("$") || normalized.contains("%")));
    }

    /**
     * A write scope in the portable form the contracts require. Backslashes are read as separators on every platform: a scope is a pattern over workspace paths written with
     * {@code /}, so the JDK's platform-dependent {@link Path} parsing cannot be used on it.
     */
    private static String normalizeRule(String rule) {
        need(rule != null, InputCode.CHANGE_SCOPE_RULE_NOT_STRING);
        String normalized = ForeignPathText.slashed(rule.strip());
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        need(!unsafeRule(normalized), InputCode.CHANGE_SCOPE_RULE_UNSAFE, "rule", rule);
        return normalized;
    }

    private static List<String> normalized(List<String> rules) {
        List<String> result = new ArrayList<>(rules.size());
        for (String rule : rules) {
            result.add(normalizeRule(rule));
        }
        return result;
    }

    /** A scope matches the paths it names and everything beneath them: {@code *} stays within a path segment, {@code **} crosses segments. Names are compared as spelled (case-sensitive). */
    private static Pattern globToRegex(String rule) {
        StringBuilder regex = new StringBuilder();
        int literalStart = 0;
        for (int i = 0; i < rule.length(); i++) {
            if (rule.charAt(i) == '*') {
                if (i > literalStart) {
                    regex.append(Pattern.quote(rule.substring(literalStart, i)));
                }
                if (i + 1 < rule.length() && rule.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
                literalStart = i + 1;
            }
        }
        if (rule.length() > literalStart) {
            regex.append(Pattern.quote(rule.substring(literalStart)));
        }
        regex.append("(?:/.*)?");
        return Pattern.compile(regex.toString());
    }

    /** Scopes compiled once for a comparison. */
    private record ScopeMatcher(List<String> rules, List<Pattern> patterns) {
        static ScopeMatcher of(List<String> rules) {
            List<Pattern> patterns = new ArrayList<>(rules.size());
            for (String rule : rules) {
                patterns.add(globToRegex(rule));
            }
            return new ScopeMatcher(rules, patterns);
        }

        /** The first scope that names this path, or null. */
        String firstMatch(String path) {
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(path).matches()) {
                    return rules.get(i);
                }
            }
            return null;
        }
    }

    /** The folder the scopes are relative to: a path of this host's file system, read with {@link Path} so that the host's own rules of spelling decide what lies beneath it. */
    private static Path workspaceRoot(String root) {
        need(root != null, InputCode.CHANGE_SCOPE_WORKSPACE_ROOT_INVALID, "workspaceRoot", "");
        try {
            Path path = Path.of(root);
            need(path.isAbsolute(), InputCode.CHANGE_SCOPE_WORKSPACE_ROOT_INVALID, "workspaceRoot", root);
            return path.normalize();
        } catch (InvalidPathException notAPath) {
            throw new InvalidInputException(InputCode.CHANGE_SCOPE_WORKSPACE_ROOT_INVALID, SennardException.args("workspaceRoot", root), Map.of(), notAPath);
        }
    }

    /** The path relative to the workspace with {@code /}, or null when it lies outside (the scopes say nothing of it). */
    private static String relative(Path workspace, Path path) {
        if (!path.startsWith(workspace)) {
            return null;
        }
        Path below = workspace.relativize(path);
        List<String> names = new ArrayList<>();
        below.forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    // -------------------------------------------------------------------------------------------------------------- compare

    /** What the record last says of a path, against its baseline entry; null when that equals the baseline (a path put back as it was is not a change). */
    private static Status statusOf(Entry before, ObservedFile last) {
        if (last.sizeBytes() == null) {
            return before == null ? null : Status.DELETED;
        }
        if (before == null) {
            return Status.CREATED;
        }
        boolean sameSize = before.sizeBytes() == last.sizeBytes();
        boolean sameContent = before.sha256() != null && last.sha256() != null ? Objects.equals(before.sha256(), last.sha256())
                : Objects.equals(before.modifiedAt(), last.modifiedAt() == null ? null : Checks.iso(last.modifiedAt()));
        return sameSize && sameContent ? null : Status.MODIFIED;
    }

    private static Classification classify(String excludedRule, boolean planned, String includedRule) {
        if (excludedRule != null) {
            return Classification.EXCLUDED;
        }
        return planned || includedRule != null ? Classification.IN_SCOPE : Classification.UNPLANNED;
    }

    /** The paths the record could not read, as findings. A link or special file the scan skipped is not followed by design (the skill never follows a link), so it is not a read failure. */
    private static Set<String> unreadable(List<JournalScanner.ReadFailure> failures) {
        Set<String> findings = new LinkedHashSet<>();
        for (JournalScanner.ReadFailure failure : failures) {
            if (failure.reason() != JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED) {
                findings.add(UNREADABLE + text(failure.path()));
            }
        }
        return findings;
    }

    private Report compare(JsonNode input, Instant now) {
        Accepted accepted = accept(input, Mode.VERIFY);
        Request request = accepted.request();
        String workflowId = request.workflowId();
        Path workspace = workspaceRoot(request.workspaceRoot());
        Baseline baseline = request.baseline();
        if (baseline != null) {
            need(Objects.equals(baseline.taskEnvelopeDigest(), accepted.envelopeDigest()), InputCode.CHANGE_SCOPE_BASELINE_TASK_ENVELOPE_MISMATCH);
        }
        // The rescan reads files, so it holds no transaction: what it starts from, the walk, then the one write that records it.
        ScanBasis basis = journal.rescanBasis(workflowId);
        ChangeJournalService.ScanResult scan = journal.recordRescan(basis, scanner.rescan(basis));
        Set<String> unreadable = unreadable(scan.failures());
        Map<String, Entry> baselineByPath = new TreeMap<>();
        recordedBaseline(workflowId).forEach(entry -> baselineByPath.put(entry.path(), entry));
        // The paths that have an event are the ones that changed; their present state is the journal's latest observation (events carry no sequence).
        Set<Path> touched = new HashSet<>();
        scan.changes().events().forEach(event -> touched.add(Path.of(event.path())));
        Map<Path, ObservedFile> lastEvents = new TreeMap<>();
        journal.observed(workflowId).forEach((path, row) -> {
            if (touched.contains(path)) {
                lastEvents.put(path, row);
            }
        });
        Set<Path> plan = journal.plan(workflowId).files();
        ScopeMatcher included = ScopeMatcher.of(accepted.rules().included());
        ScopeMatcher excluded = ScopeMatcher.of(accepted.rules().excluded());
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Path, ObservedFile> last : lastEvents.entrySet()) {
            String path = text(last.getKey());
            Entry before = baselineByPath.get(path);
            Status status = statusOf(before, last.getValue());
            if (status == null) {
                continue;
            }
            String inWorkspace = relative(workspace, last.getKey());
            String excludedRule = inWorkspace == null ? null : excluded.firstMatch(inWorkspace);
            String includedRule = inWorkspace == null ? null : included.firstMatch(inWorkspace);
            Classification classification = baseline == null ? Classification.OWNERSHIP_UNKNOWN : classify(excludedRule, plan.contains(last.getKey()), includedRule);
            changes.add(new Change(path, status, classification, baseline == null || before == null ? null : before.sha256(), last.getValue().sha256(), baseline == null ? null : excludedRule != null ? excludedRule : includedRule));
        }
        List<String> findings = new ArrayList<>();
        for (Change change : changes) {
            if (change.classification().isFinding()) {
                findings.add(change.classification().label() + ":" + change.path());
            }
        }
        findings.addAll(unreadable);
        Summary summary = Summary.count(changes);
        Verdict verdict = baseline == null || !unreadable.isEmpty() ? Verdict.INCONCLUSIVE : summary.verdict();
        return sign(new Report(SCHEMA_VERSION, workflowId, baseline == null ? NO_BASELINE : baseline.manifestSha256(), digestOf(changes), accepted.envelopeDigest(), changes, summary, findings, verdict, Checks.iso(now), null));
    }

    private Report sign(Report unsigned) {
        return unsigned.signedWith(digestOf(unsigned));
    }

    // ------------------------------------------------------------------------------------------------------- validation

    /** The rules of a baseline the schema cannot state: absolute paths in portable form (no backslash), each once, and a checksum that covers the rest. */
    private void verifyBaseline(Baseline baseline) {
        List<String> paths = baseline.entries().stream().map(Entry::path).toList();
        need(Checks.allUnique(paths) && paths.stream().allMatch(ChangeScopeCheck::portableAbsolutePath), InputCode.CHANGE_SCOPE_BASELINE_PATHS);
        need(Objects.equals(manifestDigest(baseline), baseline.manifestSha256()), InputCode.CHANGE_SCOPE_BASELINE_CHECKSUM);
    }

    /** An absolute path as the documents write it: a POSIX root or a drive, separated by {@code /}. */
    private static boolean portableAbsolutePath(String path) {
        return !path.isEmpty() && !ForeignPathText.hasBackslash(path) && (path.startsWith("/") || ForeignPathText.looksLikeDrivePath(path));
    }

    /** The baseline a caller hands back is the one the run's record holds: same workflow, same files with the same size, time and digest. */
    private void requireRecorded(Baseline baseline, String workflowId) {
        need(Objects.equals(baseline.workflow().id(), workflowId), InputCode.CHANGE_SCOPE_BASELINE_IDENTITY_MISMATCH, "workflowId", workflowId);
        List<Entry> recorded = recordedBaseline(workflowId);
        need(recorded.size() == baseline.entries().size() && Set.copyOf(recorded).equals(Set.copyOf(baseline.entries())), InputCode.CHANGE_SCOPE_BASELINE_RECORD_MISMATCH, "workflowId", workflowId);
    }

    /** The rules the shared envelope schema cannot state: write scopes are portable and work unit identifiers repeat nowhere. */
    private static Rules envelopeRules(Envelope envelope) {
        List<String> included = normalized(envelope.scope().included());
        List<String> excluded = normalized(envelope.scope().excluded());
        for (WorkUnit unit : envelope.workUnits()) {
            included.addAll(normalized(unit.writeTargets()));
        }
        need(Checks.allUnique(envelope.workUnits().stream().map(WorkUnit::id).toList()), InputCode.CHANGE_SCOPE_ENVELOPE_WORK_UNIT_IDS);
        return new Rules(included, excluded);
    }

    /**
     * The request in the given mode: its schema (which also holds the envelope's and the baseline's), the rules beyond it, the run's record (it must exist and be past its plan), and the
     * digest of the envelope as given. A baseline in the request must be the record's own.
     */
    private Accepted accept(JsonNode input, Mode expected) {
        assertSchema("request", REQUEST, input);
        Request request = schemas.bind(input, Request.class);
        need(request.mode() == expected, InputCode.CHANGE_SCOPE_REQUEST_MODE, "mode", expected.label());
        Rules rules = envelopeRules(schemas.bind(request.taskEnvelope(), Envelope.class));
        need(!request.workflowId().isBlank(), InputCode.CHANGE_SCOPE_JOURNAL_MISSING, "workflowId", request.workflowId());
        requireExecuting(request.workflowId());
        if (request.baseline() != null) {
            verifyBaseline(request.baseline());
            need(Objects.equals(request.baselineArtifactDigest(), request.baseline().manifestSha256()), InputCode.CHANGE_SCOPE_BASELINE_DIGEST_MISMATCH);
            requireRecorded(request.baseline(), request.workflowId());
        }
        return new Accepted(request, Digests.sha256Hex(CanonicalJson.of(request.taskEnvelope())), rules);
    }

    private Validated validateArtifact(JsonNode input) {
        ArtifactInput given = schemas.bind(input, ArtifactInput.class);
        need(given.artifact() != null && given.expectedArtifactDigest() != null, InputCode.CHANGE_SCOPE_ARTIFACT_AND_DIGEST_REQUIRED);
        Shape shape = schemas.bind(given.artifact(), Shape.class);
        if (shape != null && shape.entries() != null && shape.manifestSha256() != null) {
            assertSchema("baseline", BASELINE, given.artifact());
            Baseline baseline = schemas.bind(given.artifact(), Baseline.class);
            verifyBaseline(baseline);
            need(Objects.equals(given.expectedArtifactDigest(), baseline.manifestSha256()), InputCode.CHANGE_SCOPE_BASELINE_DIGEST_MISMATCH);
            return new Validated(true, ArtifactKind.BASELINE);
        }
        assertSchema("report", REPORT, given.artifact());
        validateReport(schemas.bind(given.artifact(), Report.class), given.expectedArtifactDigest());
        return new Validated(true, ArtifactKind.REPORT);
    }

    private void validateReport(Report report, String expectedDigest) {
        need(expectedDigest != null && HEX64.matcher(expectedDigest).matches() && Objects.equals(expectedDigest, report.reportSha256()), InputCode.CHANGE_SCOPE_REPORT_DIGEST_MISMATCH);
        Summary actual = Summary.count(report.changes());
        for (Classification classification : Classification.values()) {
            need(report.summary().of(classification) == actual.of(classification), InputCode.CHANGE_SCOPE_SUMMARY_COUNT, "classification", classification.label());
        }
        need(Checks.allUnique(report.changes().stream().map(Change::path).toList()), InputCode.CHANGE_SCOPE_CHANGE_PATHS_UNIQUE);
        String baselineDigest = report.baselineDigest();
        boolean noBaseline = NO_BASELINE.equals(baselineDigest);
        need(!noBaseline || report.changes().stream().allMatch(change -> change.classification() == Classification.OWNERSHIP_UNKNOWN), InputCode.CHANGE_SCOPE_MISSING_BASELINE);
        List<String> unreadable = report.findings().stream().filter(finding -> finding.startsWith(UNREADABLE)).toList();
        Verdict expected = noBaseline || report.summary().ownershipUnknown() > 0 || !unreadable.isEmpty() ? Verdict.INCONCLUSIVE : report.summary().verdict();
        need(expected == report.verdict(), InputCode.CHANGE_SCOPE_VERDICT_MISMATCH, "verdict", String.valueOf(report.verdict()), "expected", expected.name());
        need(HEX64.matcher(baselineDigest).matches() && HEX64.matcher(report.currentDigest()).matches() && Objects.equals(digestOf(report.changes()), report.currentDigest()), InputCode.CHANGE_SCOPE_REPORT_DIGEST_INVALID);
        List<String> expectedFindings = new ArrayList<>();
        for (Change change : report.changes()) {
            if (change.classification().isFinding()) {
                expectedFindings.add(change.classification().label() + ":" + change.path());
            }
        }
        List<String> changeFindings = report.findings().stream().filter(finding -> !finding.startsWith(UNREADABLE)).toList();
        need(Checks.sameSet(expectedFindings, changeFindings), InputCode.CHANGE_SCOPE_FINDINGS_MISMATCH);
        need(Objects.equals(digestOf(report.unsigned()), report.reportSha256()), InputCode.CHANGE_SCOPE_REPORT_CHECKSUM);
    }
}
