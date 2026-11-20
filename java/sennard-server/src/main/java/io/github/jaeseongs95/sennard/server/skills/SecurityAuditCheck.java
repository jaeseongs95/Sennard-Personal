// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import io.github.jaeseongs95.sennard.service.files.FileFactsException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * software-security-auditor: a request's target snapshot and a report's structural, relational and digest bindings. Whether a finding is real stays the auditor's.
 * The request and the report are bound to records once the contract's schema has accepted them; the digests are the canonical form of the records as Jackson writes them,
 * which for a document the schema accepted (every member named, none added) is the document's own canonical form.
 */
@Component
public class SecurityAuditCheck implements SkillCheck<SecurityAuditCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditCheck.class);
    private static final String CONTRACTS = "skills/software-security-auditor/contracts/";
    private static final String REQUEST = CONTRACTS + "security-audit-request.v1.schema.json";
    private static final String REPORT = CONTRACTS + "security-audit-report.v1.schema.json";
    private static final int FILE_LIMIT = SkillArguments.DOCUMENT_LIMIT;
    /**
     * A path the audit may name: one or more segments joined by {@code /}, no segment empty, {@code .} or {@code ..}, and none holding a backslash, a colon or a control
     * character. It is a rule of the contract (a relative path that means the same everywhere), not a look at the host's file system; the file layer opens the file.
     */
    private static final String SEGMENT = "(?![.]{1,2}(?:/|\\z))[^/\\\\:\\x00-\\x1f]+";
    private static final Pattern SAFE_RELATIVE = Pattern.compile(SEGMENT + "(?:/" + SEGMENT + ")*");

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        SNAPSHOT("snapshot"), VALIDATE("validate"), PROVIDER_SEMANTICS("provider-semantics");

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
        @JsonProperty("repository") REPOSITORY, @JsonProperty("diff") DIFF
    }

    enum Profile {
        @JsonProperty("web-api") WEB_API, @JsonProperty("cli-mcp") CLI_MCP
    }

    enum AuditStatus {
        @JsonProperty("complete") COMPLETE, @JsonProperty("partial") PARTIAL, @JsonProperty("blocked") BLOCKED
    }

    enum CheckStatus {
        @JsonProperty("checked") CHECKED, @JsonProperty("not-checked") NOT_CHECKED, @JsonProperty("not-applicable") NOT_APPLICABLE
    }

    enum Method {
        @JsonProperty("static-analysis") STATIC_ANALYSIS, @JsonProperty("local-reproduction") LOCAL_REPRODUCTION, @JsonProperty("not-executed") NOT_EXECUTED
    }

    enum FindingStatus {
        @JsonProperty("confirmed") CONFIRMED, @JsonProperty("suspected") SUSPECTED, @JsonProperty("refuted") REFUTED
    }

    enum Severity {
        @JsonProperty("critical") CRITICAL, @JsonProperty("high") HIGH, @JsonProperty("medium") MEDIUM, @JsonProperty("low") LOW, @JsonProperty("informational") INFORMATIONAL
    }

    enum Proof {
        @JsonProperty("static-analysis") STATIC_ANALYSIS, @JsonProperty("local-reproduction") LOCAL_REPRODUCTION, @JsonProperty("unverified") UNVERIFIED
    }

    enum EvidenceKind {
        @JsonProperty("source") SOURCE, @JsonProperty("test") TEST, @JsonProperty("tool") TOOL, @JsonProperty("advisory") ADVISORY, @JsonProperty("configuration") CONFIGURATION
    }

    /** A file the request pins: its path under a root and the digest of its bytes. */
    record PinnedFile(String path, String digest) {
    }

    /** {@code baseRef} is a required member that may be null, so it is written even then (the target's digest covers it). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Target(String identifier, String baseRef, List<PinnedFile> files) {
    }

    record Authorization(boolean localReproduction, List<String> constraints) {
    }

    record CheckSpec(String id, String description) {
    }

    record AuditRequest(String schemaVersion, Target target, Mode mode, List<Profile> profiles, List<String> excluded, String environment, Authorization authorization,
            List<CheckSpec> checks, List<PinnedFile> providedEvidence) {
        /** This request with the target's files replaced (the pinned form a snapshot answers). */
        AuditRequest withTargetFiles(List<PinnedFile> files) {
            return new AuditRequest(schemaVersion, new Target(target.identifier(), target.baseRef(), files), mode, profiles, excluded, environment, authorization, checks, providedEvidence);
        }
    }

    record ThreatModel(List<String> assets, List<String> actors, List<String> entrypoints, List<String> trustBoundaries) {
    }

    record CheckResult(String id, CheckStatus status, List<String> evidenceRefs, String observation, Method method) {
    }

    record Location(String path, int line) {
    }

    record Cvss(String vector, String rationale) {
    }

    record Finding(String id, FindingStatus status, Severity severity, List<String> checkIds, List<Location> locations, String preconditions, String attackPath, String impact,
            String defenseReview, List<String> evidenceRefs, Proof proof, String remediation, String retest, Cvss cvss) {
    }

    record EvidenceItem(String id, String path, String digest, String targetDigest, EvidenceKind kind, String description) {
    }

    record AuditReport(String schemaVersion, AuditRequest request, String requestDigest, String targetDigest, AuditStatus status, ThreatModel threatModel, List<CheckResult> checks,
            List<Finding> findings, List<EvidenceItem> evidence, List<String> limitations) {
    }

    /** The answer of {@code snapshot}: the request with its files pinned, and the two digests a report binds to. */
    record Snapshot(AuditRequest request, String requestDigest, String targetDigest) {
    }

    /** The answer of {@code validate} and {@code provider-semantics}: what the report breaks, each as the name (or the {@code SUBJECT: code} text) callers read. */
    record Judgment(boolean valid, List<String> errors) {
    }

    /** One thing a report breaks. The rules are the contract's closed set; a file the layer refused to read is the subject and the layer's own code. */
    private sealed interface Problem {
        String text();

        /** The JSON value of a rule is its name, the string callers have always read. */
        enum Rule implements Problem {
            INVALID_REQUEST_SCHEMA, INVALID_REPORT_SCHEMA, REQUEST_BINDING_MISMATCH, REQUEST_DIGEST_MISMATCH, TARGET_DIGEST_MISMATCH, DUPLICATE_TARGET_PATH, DUPLICATE_PROVIDED_EVIDENCE,
            DUPLICATE_REQUEST_CHECK, DUPLICATE_CHECK, DUPLICATE_EVIDENCE, DUPLICATE_FINDING, CHECK_INVENTORY_MISMATCH, UNSAFE_PATH, STALE_TARGET, STALE_PROVIDED_EVIDENCE, STALE_EVIDENCE,
            UNKNOWN_EVIDENCE, CHECK_WITHOUT_EVIDENCE, UNEXECUTED_CHECK_METHOD, UNKNOWN_FINDING_LOCATION, UNVERIFIED_FINDING_CHECK, UNVERIFIED_CONFIRMED_FINDING, UNSUPPORTED_REPRODUCTION,
            UNAUTHORIZED_REPRODUCTION, STATUS_COVERAGE_MISMATCH, MISSING_LIMITATIONS;

            @Override
            public String text() {
                return name();
            }
        }

        record Unreadable(Subject subject, String code) implements Problem {
            @Override
            public String text() {
                return subject.name() + ": " + code;
            }
        }
    }

    /** Which pinned files a digest check reads, and the rule it breaks when the bytes differ. */
    private enum Subject {
        TARGET(Problem.Rule.STALE_TARGET), PROVIDED_EVIDENCE(Problem.Rule.STALE_PROVIDED_EVIDENCE), EVIDENCE(Problem.Rule.STALE_EVIDENCE);

        private final Problem.Rule stale;

        Subject(Problem.Rule stale) {
            this.stale = stale;
        }
    }

    private final SkillSchemas schemas;
    private final JsonMapper mapper;

    public SecurityAuditCheck(SkillSchemas schemas, JsonMapper mapper) {
        this.schemas = schemas;
        this.mapper = mapper;
    }

    @Override
    public String skill() {
        return "software-security-auditor";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return switch (command) {
            case PROVIDER_SEMANTICS -> providerSemantics(args);
            case SNAPSHOT -> {
                String targetRoot = args.requiredText("target-root");
                yield SkillCheckResult.ok(snapshot(args.document("input"), targetRoot, args.files()));
            }
            case VALIDATE -> {
                String targetRoot = args.requiredText("target-root");
                JsonNode request = args.document("input");
                need(args.has("report") && args.has("evidence-root"), InputCode.SECURITY_AUDIT_MISSING_REPORT_OR_ROOT);
                yield judged(validateReport(request, args.document("report"), targetRoot, args.requiredText("evidence-root"), args.files()));
            }
        };
    }

    /** The workflow stage's rules on an already recorded report: pure structure, no files. */
    private SkillCheckResult providerSemantics(SkillArguments args) {
        JsonNode document = args.document("report");
        if (!schemas.valid(REPORT, document)) {
            return judged(List.of(Problem.Rule.INVALID_REPORT_SCHEMA));
        }
        return judged(semantics(schemas.bind(document, AuditReport.class)));
    }

    private static SkillCheckResult judged(List<Problem> problems) {
        return SkillCheckResult.of(new Judgment(problems.isEmpty(), problems.stream().map(Problem::text).toList()), problems.isEmpty());
    }

    static boolean safeRelative(String value) {
        return value != null && SAFE_RELATIVE.matcher(value).matches();
    }

    private String digest(AuditRequest request) {
        return Checks.digest(mapper, request);
    }

    private String targetDigest(AuditRequest request) {
        return Checks.digest(mapper, request.target());
    }

    private Snapshot snapshot(JsonNode document, String targetRoot, FileFacts files) {
        // Shape and path inventory are checked before any target file is touched.
        List<SkillSchemas.Violation> violations = schemas.violations(REQUEST, document);
        Checks.needWith(violations.isEmpty(), InputCode.SECURITY_AUDIT_INVALID_OPTION, Map.of("errors", violations));
        AuditRequest request = schemas.bind(document, AuditRequest.class);
        List<String> paths = request.target().files().stream().map(PinnedFile::path).toList();
        need(Checks.allUnique(paths), InputCode.SECURITY_AUDIT_INVALID_OPTION);
        List<PinnedFile> pinned = new ArrayList<>();
        for (String path : paths) {
            need(safeRelative(path), InputCode.SECURITY_AUDIT_INVALID_OPTION);
            pinned.add(new PinnedFile(path, Checks.hash(files.readUnderRoot(targetRoot, path, FILE_LIMIT))));
        }
        AuditRequest snapshotted = request.withTargetFiles(pinned);
        return new Snapshot(snapshotted, digest(snapshotted), targetDigest(snapshotted));
    }

    private List<Problem> validateReport(JsonNode requestDocument, JsonNode reportDocument, String targetRoot, String evidenceRoot, FileFacts files) {
        if (!schemas.valid(REQUEST, requestDocument)) {
            return List.of(Problem.Rule.INVALID_REQUEST_SCHEMA);
        }
        if (!schemas.valid(REPORT, reportDocument)) {
            return List.of(Problem.Rule.INVALID_REPORT_SCHEMA);
        }
        AuditRequest request = schemas.bind(requestDocument, AuditRequest.class);
        AuditReport report = schemas.bind(reportDocument, AuditReport.class);
        Set<Problem> errors = new LinkedHashSet<>(semantics(report));
        if (!Objects.equals(digest(request), report.requestDigest())) {
            errors.add(Problem.Rule.REQUEST_BINDING_MISMATCH);
        }
        for (PinnedFile item : request.target().files()) {
            verify(files, targetRoot, item.path(), item.digest(), Subject.TARGET, errors);
        }
        for (PinnedFile item : request.providedEvidence()) {
            verify(files, evidenceRoot, item.path(), item.digest(), Subject.PROVIDED_EVIDENCE, errors);
        }
        for (EvidenceItem item : report.evidence()) {
            verify(files, evidenceRoot, item.path(), item.digest(), Subject.EVIDENCE, errors);
        }
        return new ArrayList<>(errors);
    }

    private static void verify(FileFacts files, String root, String path, String digest, Subject subject, Set<Problem> errors) {
        try {
            if (!Objects.equals(Checks.hash(files.readUnderRoot(root, path, FILE_LIMIT)), digest)) {
                errors.add(subject.stale);
            }
        } catch (FileFactsException refused) {
            // The file layer's refusal (not there, a link, too large) is this file's finding in the verdict; the log ends the handling of the exception.
            LOG.debug("software-security-auditor: {} {} could not be read for its digest: {}", subject, path, refused.code(), refused);
            errors.add(new Problem.Unreadable(subject, refused.code()));
        }
    }

    private static <T> Set<String> unique(List<T> items, Function<T, String> key, Problem.Rule duplicate, Set<Problem> errors) {
        List<String> values = items.stream().map(key).toList();
        if (!Checks.allUnique(values)) {
            errors.add(duplicate);
        }
        return new HashSet<>(values);
    }

    private List<Problem> semantics(AuditReport report) {
        Set<Problem> errors = new LinkedHashSet<>();
        AuditRequest request = report.request();
        if (!Objects.equals(digest(request), report.requestDigest())) {
            errors.add(Problem.Rule.REQUEST_DIGEST_MISMATCH);
        }
        if (!Objects.equals(targetDigest(request), report.targetDigest())) {
            errors.add(Problem.Rule.TARGET_DIGEST_MISMATCH);
        }
        Set<String> paths = unique(request.target().files(), PinnedFile::path, Problem.Rule.DUPLICATE_TARGET_PATH, errors);
        unique(request.providedEvidence(), PinnedFile::path, Problem.Rule.DUPLICATE_PROVIDED_EVIDENCE, errors);
        Set<String> expected = unique(request.checks(), CheckSpec::id, Problem.Rule.DUPLICATE_REQUEST_CHECK, errors);
        Set<String> checks = unique(report.checks(), CheckResult::id, Problem.Rule.DUPLICATE_CHECK, errors);
        Set<String> evidence = unique(report.evidence(), EvidenceItem::id, Problem.Rule.DUPLICATE_EVIDENCE, errors);
        unique(report.findings(), Finding::id, Problem.Rule.DUPLICATE_FINDING, errors);
        if (!expected.equals(checks)) {
            errors.add(Problem.Rule.CHECK_INVENTORY_MISMATCH);
        }
        boolean unsafe = request.target().files().stream().anyMatch(item -> !safeRelative(item.path())) || request.providedEvidence().stream().anyMatch(item -> !safeRelative(item.path()))
                || report.evidence().stream().anyMatch(item -> !safeRelative(item.path()));
        if (unsafe) {
            errors.add(Problem.Rule.UNSAFE_PATH);
        }
        if (report.evidence().stream().anyMatch(item -> !Objects.equals(item.targetDigest(), report.targetDigest()))) {
            errors.add(Problem.Rule.STALE_EVIDENCE);
        }
        Map<String, CheckResult> checkMap = new HashMap<>();
        report.checks().forEach(item -> checkMap.put(item.id(), item));
        if (report.checks().stream().anyMatch(item -> !evidence.containsAll(item.evidenceRefs())) || report.findings().stream().anyMatch(item -> !evidence.containsAll(item.evidenceRefs()))) {
            errors.add(Problem.Rule.UNKNOWN_EVIDENCE);
        }
        long notChecked = 0;
        long checked = 0;
        for (CheckResult item : report.checks()) {
            if (item.status() == CheckStatus.CHECKED && (item.evidenceRefs().isEmpty() || item.method() == Method.NOT_EXECUTED)) {
                errors.add(Problem.Rule.CHECK_WITHOUT_EVIDENCE);
            }
            if (item.status() != CheckStatus.CHECKED && item.method() != Method.NOT_EXECUTED) {
                errors.add(Problem.Rule.UNEXECUTED_CHECK_METHOD);
            }
            notChecked += item.status() == CheckStatus.NOT_CHECKED ? 1 : 0;
            checked += item.status() == CheckStatus.CHECKED ? 1 : 0;
        }
        boolean localAllowed = request.authorization().localReproduction();
        for (Finding item : report.findings()) {
            if (item.locations().stream().anyMatch(location -> !paths.contains(location.path()))) {
                errors.add(Problem.Rule.UNKNOWN_FINDING_LOCATION);
            }
            List<String> ids = item.checkIds();
            if (ids.stream().anyMatch(id -> !expected.contains(id) || !checkMap.containsKey(id) || checkMap.get(id).status() != CheckStatus.CHECKED)) {
                errors.add(Problem.Rule.UNVERIFIED_FINDING_CHECK);
            }
            if (item.status() == FindingStatus.CONFIRMED && item.proof() == Proof.UNVERIFIED) {
                errors.add(Problem.Rule.UNVERIFIED_CONFIRMED_FINDING);
            }
            if (item.proof() == Proof.LOCAL_REPRODUCTION
                    && (!localAllowed || ids.stream().noneMatch(id -> checkMap.containsKey(id) && checkMap.get(id).method() == Method.LOCAL_REPRODUCTION))) {
                errors.add(Problem.Rule.UNSUPPORTED_REPRODUCTION);
            }
        }
        if (!localAllowed && report.checks().stream().anyMatch(item -> item.method() == Method.LOCAL_REPRODUCTION)) {
            errors.add(Problem.Rule.UNAUTHORIZED_REPRODUCTION);
        }
        AuditStatus expectedStatus = notChecked == 0 ? AuditStatus.COMPLETE : checked > 0 ? AuditStatus.PARTIAL : AuditStatus.BLOCKED;
        if (expectedStatus != report.status()) {
            errors.add(Problem.Rule.STATUS_COVERAGE_MISMATCH);
        }
        if (notChecked > 0 && report.limitations().isEmpty()) {
            errors.add(Problem.Rule.MISSING_LIMITATIONS);
        }
        return new ArrayList<>(errors);
    }
}
