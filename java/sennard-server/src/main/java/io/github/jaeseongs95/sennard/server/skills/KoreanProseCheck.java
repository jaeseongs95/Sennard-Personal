// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * korean-prose-editor: the protected-span manifest, source units and the finalizer that lets only accepted, minimal, in-scope edits through.
 * Any broken contract among the actors' work products means the source comes back unchanged; only a malformed request is refused.
 * Offsets are UTF-16 code units, the same as {@link String#length()}.
 *
 * <p>The request of {@code finalize} is bound to a record; each work product it carries is a document the contract's schema judges first and the check binds to a record
 * second. A document's digest is that of its own canonical form ({@link CanonicalJson}), never of what the record would write back.
 */
@Component
public class KoreanProseCheck implements SkillCheck<KoreanProseCheck.Command> {
    private static final String CONTRACTS = "skills/korean-prose-editor/contracts/";
    private static final String MANIFEST_SCHEMA = CONTRACTS + "protected-manifest.schema.json";
    private static final String MATCH_SET_SCHEMA = CONTRACTS + "glossary-match-set.v1.schema.json";
    private static final String SELECTION_SCHEMA = CONTRACTS + "selection-work-product.v1.schema.json";
    private static final String EDITING_SCHEMA = CONTRACTS + "editing-work-product.v1.schema.json";
    private static final String VERIFICATION_SCHEMA = CONTRACTS + "verification-work-product.v1.schema.json";
    private static final String VERSION = "1.0.0";
    private static final String ENTRYPOINT = "run_skill_check:korean-prose-editor/finalize";
    private static final String UNVERIFIED = "unverified";
    private static final int ACTORS = 3;
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    /** Where a violation of a work product lies inside one of its items ({@code /edits/3/start}); anything else breaks the document's own shape. */
    private static final Pattern EDIT_ITEM = Pattern.compile("/edits/([0-9]+)(?:/.*)?", Pattern.DOTALL);
    private static final Pattern DECISION_ITEM = Pattern.compile("/decisions/([0-9]+)(?:/.*)?", Pattern.DOTALL);

    /** ECMAScript's {@code \s}, which Java's does not match without changing {@code \d} as well. */
    private static final String WS = "\\s\\u00a0\\ufeff\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000";
    private static final String LINE_START = "(?<![^\\n\\r\\u2028\\u2029])";
    private static final String LINE_END = "(?![^\\n\\r\\u2028\\u2029])";
    private static final String NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String UNITS_PATTERN = "퍼센트포인트|퍼센트|개월|시간|분기|킬로미터|킬로그램|밀리리터|리터|세제곱미터|제곱미터|개|건|명|회|번|대|장|권|곳|개소|년|월|일|시|분|초|주|세|살|원|달러|배|점|쪽|층|도|평|톤|억|만|천|%|℃|°C|kg|km|cm|mm|ms|mL|GB|MB|KB|m|g|L";
    private static final List<Rule> RULES = List.of(
            rule(SpanKind.FENCED_CODE, 100, "```[^\\n]*\\n[\\s\\S]*?```|~~~[^\\n]*\\n[\\s\\S]*?~~~"),
            rule(SpanKind.INLINE_CODE, 90, "`[^`\\n]+`"),
            rule(SpanKind.COMMAND, 85, LINE_START + "[ \\t]*(?:\\$|PS [^>\\n]+>)[ \\t]+[^" + WS + "][^\\n]*"),
            // Possessive inner run: the percent escape's alternative cannot start inside it, and a long URL must not recurse per character.
            rule(SpanKind.URL, 70, "https?://(?:[A-Za-z0-9\\-._~:/?#@!$&*+,;=]++|%[A-Fa-f0-9]{2})+"),
            rule(SpanKind.EMAIL, 70, "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}"),
            rule(SpanKind.PATH, 65, "(?<![\\p{L}\\p{N}_:/\\\\])(?:[\\p{Lower}\\p{Upper}]:[\\\\/]|\\\\\\\\|\\.{1,2}[\\\\/]|~/|/)[^" + WS + "<>\"'`()\\[\\]{}，。！？]+"),
            rule(SpanKind.QUOTATION, 60, "“[^”\\n]+”|‘[^’\\n]+’|「[^」\\n]+」|『[^』\\n]+』|《[^》\\n]+》|〈[^〉\\n]+〉|\"[^\"\\n]+\"|'[^'\\n]+'"),
            rule(SpanKind.NUMBER_OR_DATE, 55, NOT_WORD_BEFORE + "\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:[ \\t]*(?:AM|PM))?(?![\\d:])"),
            rule(SpanKind.NUMBER_OR_DATE, 55, NOT_WORD_BEFORE + "(?:[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?[ \\t]*(?:" + UNITS_PATTERN + "))+"),
            rule(SpanKind.NUMBER_OR_DATE, 50, "(?:\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}(?!\\d)|" + NOT_WORD_BEFORE + "(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?%?)(?![\\p{L}\\p{N}_]))"));
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]\\n]*\\]\\(");
    private static final Pattern URL_TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?]+$");
    private static final Pattern FENCE_LINE = Pattern.compile(LINE_START + "( {0,3})(`{3,}|~{3,})[^\\r\\n]*(?:\\r?\\n|" + LINE_END + ")");
    /** A closing fence line of backticks or of tildes; the run is checked against the opening fence's length by the caller. */
    private static final Pattern BACKTICK_FENCE_CLOSE = Pattern.compile(LINE_START + " {0,3}(`{3,})[ \\t]*(?:\\r?\\n|" + LINE_END + ")");
    private static final Pattern TILDE_FENCE_CLOSE = Pattern.compile(LINE_START + " {0,3}(~{3,})[ \\t]*(?:\\r?\\n|" + LINE_END + ")");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n)*");

    private static Rule rule(SpanKind kind, int priority, String regex) {
        return new Rule(kind, priority, Pattern.compile(regex));
    }

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        EXTRACT_PROTECTED_SPANS("extract-protected-spans"), SOURCE_UNIT_MANIFEST("source-unit-manifest"), CHECK_RESULT("check-result"), FINALIZE("finalize");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** What a protected span is; the rule that found it, and the contract's {@code kind}. */
    enum SpanKind {
        @JsonProperty("fenced-code") FENCED_CODE, @JsonProperty("inline-code") INLINE_CODE, @JsonProperty("command") COMMAND, @JsonProperty("markdown-target") MARKDOWN_TARGET,
        @JsonProperty("url") URL, @JsonProperty("email") EMAIL, @JsonProperty("path") PATH, @JsonProperty("quotation") QUOTATION, @JsonProperty("number-or-date") NUMBER_OR_DATE,
        @JsonProperty("user-defined") USER_DEFINED, @JsonProperty("glossary") GLOSSARY
    }

    enum UnitKind {
        @JsonProperty("prose") PROSE, @JsonProperty("fenced-code") FENCED_CODE
    }

    /** Who finalizes: through the trusted transport (the receipt only) or directly (the text comes back too, never verified). */
    enum Mode {
        MCP("mcp"), DIRECT("direct");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }

        /** The mode a request names, or null for a name that is none. */
        static Mode of(String wire) {
            for (Mode mode : values()) {
                if (Objects.equals(mode.wire, wire)) {
                    return mode;
                }
            }
            return null;
        }
    }

    enum Assurance {
        @JsonProperty("verified") VERIFIED, @JsonProperty("unverified") UNVERIFIED
    }

    enum ReceiptStatus {
        @JsonProperty("finalized") FINALIZED, @JsonProperty("fallback") FALLBACK
    }

    enum ProviderKind {
        @JsonProperty("agent") AGENT, @JsonProperty("deterministic") DETERMINISTIC
    }

    /** The three language actors, in the order of the plan's {@code actorIds}. */
    enum Role {
        SELECTION("selection"), EDITING("editing"), VERIFICATION("verification");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum GlossaryMode {
        @JsonProperty("none") NONE, @JsonProperty("mcp") MCP
    }

    /** What the glossary lookup found; {@code DIRECT} is the receipt's word for a finalization that used no glossary. */
    enum GlossaryStatus {
        @JsonProperty("direct") DIRECT(false, null), @JsonProperty("matched") MATCHED(true, null), @JsonProperty("no-match") NO_MATCH(true, null),
        @JsonProperty("unavailable") UNAVAILABLE(false, Warning.GLOSSARY_UNAVAILABLE), @JsonProperty("limit-exceeded") LIMIT_EXCEEDED(false, Warning.GLOSSARY_MATCH_LIMIT_EXCEEDED),
        @JsonProperty("unsupported-normalization") UNSUPPORTED_NORMALIZATION(false, Warning.GLOSSARY_UNSUPPORTED_NORMALIZATION);

        private final boolean available;
        private final Warning warning;

        GlossaryStatus(boolean available, Warning warning) {
            this.available = available;
            this.warning = warning;
        }

        /** Whether the lookup answered, so that its glossary and match-set digest are stated. */
        boolean available() {
            return available;
        }

        /** The warnings a match set of this status states, and no others. */
        List<Warning> impliedWarnings() {
            return warning == null ? List.of() : List.of(warning);
        }
    }

    enum Policy {
        @JsonProperty("protect") PROTECT, @JsonProperty("prefer") PREFER, @JsonProperty("allow") ALLOW, @JsonProperty("avoid") AVOID
    }

    enum SelectionStatus {
        @JsonProperty("ready") READY, @JsonProperty("needs-input") NEEDS_INPUT, @JsonProperty("blocked") BLOCKED
    }

    enum SelectionAction {
        @JsonProperty("edit") EDIT, @JsonProperty("retain") RETAIN, @JsonProperty("defer") DEFER
    }

    enum GlobalDecision {
        @JsonProperty("continue") CONTINUE, @JsonProperty("fallback") FALLBACK
    }

    enum DecisionKind {
        @JsonProperty("accept") ACCEPT, @JsonProperty("retain") RETAIN
    }

    enum Rating {
        @JsonProperty("pass") PASS, @JsonProperty("fail") FAIL, @JsonProperty("uncertain") UNCERTAIN
    }

    enum TerminologyRating {
        @JsonProperty("pass") PASS, @JsonProperty("fail") FAIL, @JsonProperty("uncertain") UNCERTAIN, @JsonProperty("not-applicable") NOT_APPLICABLE
    }

    enum PairPreference {
        @JsonProperty("candidate") CANDIDATE, @JsonProperty("original") ORIGINAL, @JsonProperty("tie") TIE, @JsonProperty("neither") NEITHER
    }

    /** What a check or a finalization found; the receipt lists them in name order. */
    enum Warning {
        EDIT_CONTRACT_INVALID, EDIT_OUT_OF_SCOPE_RETAINED, EDIT_RANGES_OVERLAP, EDITING_DIGEST_MISMATCH, FINAL_PROTECTED_CHECK_FAILED, GLOBAL_FALLBACK_APPLIED, GLOSSARY_BINDING_MISMATCH,
        GLOSSARY_MATCH_LIMIT_EXCEEDED, GLOSSARY_MATCH_SET_INVALID, GLOSSARY_PROTECT_MISSING, GLOSSARY_UNAVAILABLE, GLOSSARY_UNSUPPORTED_NORMALIZATION, MANIFEST_SCHEMA_VERSION,
        MANIFEST_SPAN_INVALID, MANIFEST_SPANS_INVALID, NON_MINIMAL_EDIT_RETAINED, PROTECTED_EDIT_RETAINED, PROTECTED_SPAN_MISSING_OR_REORDERED, SELECTION_CONTRACT_INVALID,
        SELECTION_DIGEST_MISMATCH, SELECTION_NOT_READY, SOURCE_MANIFEST_MISMATCH, SOURCE_UNIT_MANIFEST_MISMATCH, VERIFICATION_CONTRACT_INVALID, VERIFICATION_DECISION_INVALID,
        VERIFICATION_DECISION_MISSING, VERIFICATION_DIGEST_MISMATCH, VERIFICATION_RUBRIC_DIGEST_MISMATCH, VERIFIER_GLOBAL_FALLBACK
    }

    // ---- The protected-span manifest and the source units: inputs and answers of the first three commands --------------------------------

    /** The input of {@code extract-protected-spans}; a member this check does not read is none of its concern. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpansInput(String source, List<String> protectedStrings) {
    }

    /**
     * The manifest the contract describes. Every member may be absent here: {@code check-result} judges a manifest that is not one and says why, so what a member holds is
     * the caller's; the contract's schema is the gate where a manifest must be exact.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(String schemaVersion, String sourceDigest, Integer sourceLength, List<ManifestSpan> spans) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ManifestSpan(String id, SpanKind kind, Integer start, Integer end, String text, String digest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UnitsInput(String source, Manifest manifest) {
    }

    record UnitManifest(String schemaVersion, String sourceDigest, int sourceLength, List<SourceUnit> units) {
    }

    record SourceUnit(String unitId, int start, int end, UnitKind kind, String digest, List<String> protectedSpanIds) {
    }

    /** The manifest is a document here: its digest is that of the text the caller sent, whatever the manifest holds beyond what this check reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckInput(String source, String candidate, JsonNode manifest) {
    }

    record CheckDigests(String source, String candidate, String manifest) {
    }

    record CheckLengths(int source, int candidate) {
    }

    record CheckDecisions(boolean protectedSpansPreserved) {
    }

    record CheckReport(String schemaVersion, CheckDigests digest, CheckLengths length, CheckDecisions decisions, List<Warning> warnings) {
    }

    // ---- The finalization request: the plan is bound, each work product stays a document the schema judges ---------------------------------

    /** The request. A member the finalizer does not name is ignored; each work product is a whole document, kept as one for its digest and its schema. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinalizeRequest(String schemaVersion, String mode, String source, boolean subagentsAvailable, Plan plan, JsonNode manifest, JsonNode sourceUnitManifest,
            JsonNode glossaryMatchSet, JsonNode selection, JsonNode editing, JsonNode verification, String rubricDigest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Plan(String schemaVersion, List<String> actorIds, Providers providers) {
        Provider agent(Role role) {
            return switch (role) {
                case SELECTION -> providers.selection();
                case EDITING -> providers.editing();
                case VERIFICATION -> providers.verification();
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Providers(Provider selection, Provider editing, Provider verification, Provider finalization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Provider(ProviderKind kind, String actorId, String entrypoint) {
    }

    /** The glossary binding a work product states, kept as the document it is: a work product's binding must equal, as a document, the one the finalizer derives. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BindingView(JsonNode glossaryBinding) {
    }

    /** The members of the editing work product that are judged apart from its shape: its binding, and its edits as the documents they are. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EditingView(JsonNode glossaryBinding, JsonNode edits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerificationView(JsonNode glossaryBinding, JsonNode decisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Selection(String actorId, String sourceDigest, SelectionStatus status, List<SelectionDecision> decisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SelectionDecision(String unitId, SelectionAction action, List<String> reasonCodes, List<String> additionalProtectedStrings, List<IssueRange> issueRanges) {
    }

    record IssueRange(int start, int end, String reasonCode) {
    }

    /** The editing work product's own members (its edits are judged one by one). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EditingRoot(String actorId, String sourceDigest, String selectionDigest, String candidateDigest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EditItem(String id, String unitId, String sourceDigest, int start, int end, String replacement, String actorId) {
    }

    /** The verification work product's own members (its decisions are judged one by one). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerificationRoot(String actorId, String sourceDigest, String editingDigest, String rubricDigest, GlobalDecision globalDecision, Assessment assessment) {
    }

    record Assessment(Rating meaningPreservation, boolean majorMeaningChange, Rating registerCompliance, Rating protectedStrings, TerminologyRating terminologyJudgment,
            PairPreference pairPreference) {
        /** Whether the whole assessment lets an accepted edit through. */
        boolean safe() {
            return meaningPreservation == Rating.PASS && !majorMeaningChange && registerCompliance == Rating.PASS && protectedStrings == Rating.PASS
                    && (terminologyJudgment == TerminologyRating.PASS || terminologyJudgment == TerminologyRating.NOT_APPLICABLE) && pairPreference == PairPreference.CANDIDATE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerificationDecision(String editId, DecisionKind decision) {
    }

    // ---- The glossary: a match set, and the binding that carries it into the work products -------------------------------------------------

    record GlossaryInfo(String id, String version, String contentDigest) {
    }

    record Match(int start, int end, String entryId, Policy policy, String canonicalForm, int priority) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatchSet(GlossaryStatus status, String sourceDigest, GlossaryInfo glossary, List<Match> matches, String matchSetDigest, List<Warning> warnings) {
    }

    /** What the match-set digest is made over. */
    record MatchSetDigestInput(String schemaVersion, String sourceDigest, GlossaryInfo glossary, List<Match> matches) {
    }

    /** The glossary binding of the three work products, as the finalizer derives it. */
    sealed interface Binding permits NoBinding, McpBinding {
    }

    record NoBinding(String schemaVersion, GlossaryMode mode) implements Binding {
        static NoBinding none() {
            return new NoBinding(VERSION, GlossaryMode.NONE);
        }
    }

    record McpBinding(String schemaVersion, GlossaryMode mode, GlossaryStatus status, String sourceDigest, String glossaryId, String glossaryVersion, String glossaryDigest,
            String matchSetDigest, int matchCount, List<Warning> warnings) implements Binding {
    }

    record GlossarySummary(GlossaryMode mode, GlossaryStatus status, String id, String version, String contentDigest, String matchSetDigest, int matchCount, List<Warning> warnings) {
    }

    private record GlossaryOutcome(boolean valid, Binding binding, GlossarySummary summary, List<Warning> warnings) {
    }

    // ---- The receipt and the answers of finalize ---------------------------------------------------------------------------------------------

    record ReceiptDigest(String source, String result, String manifest) {
    }

    record ReceiptLength(int source, int result) {
    }

    record ReceiptDecisions(Mode mode, Assurance assurance, ReceiptStatus status, List<String> appliedEditDigests, List<String> retainedEditDigests, boolean fallback) {
    }

    record Receipt(String schemaVersion, List<String> actorIds, ReceiptDigest digest, ReceiptLength length, GlossarySummary glossary, ReceiptDecisions decisions, List<Warning> warnings) {
    }

    /** What {@code direct} mode answers: the text too, and that nobody verified it. */
    record DirectAnswer(String output, String verificationStatus, Receipt receipt) {
    }

    private record Finalized(Mode mode, String output, Receipt receipt) {
    }

    // ---- Working records -------------------------------------------------------------------------------------------------------------------

    private record Rule(SpanKind kind, int priority, Pattern regex) {
    }

    private record Candidate(SpanKind kind, int start, int end, String text, int priority) {
    }

    private record Range(int start, int end) {
    }

    private record Partition(int start, int end, UnitKind kind) {
    }

    /** Whether a candidate keeps the manifest's protected spans, and the warnings that say what it does not. */
    private record Preservation(boolean preserved, Set<Warning> warnings) {
    }

    /** What the schema says of a work product whose items are judged one by one: whether its own shape holds, and which of its items break theirs. */
    private record Shape(boolean rootValid, Set<Integer> brokenItems) {
    }

    /** What the finalizer has found so far: the warnings, in name order, and whether the source must come back unchanged. */
    private static final class Tally {
        private final Set<Warning> warnings = new TreeSet<>(Comparator.comparing(Warning::name));
        private boolean fallback;

        void note(Warning warning) {
            warnings.add(warning);
        }

        /** A warning that sends the whole text back unchanged. */
        void flag(Warning warning) {
            warnings.add(warning);
            fallback = true;
        }

        boolean fallback() {
            return fallback;
        }

        List<Warning> warnings() {
            return List.copyOf(warnings);
        }
    }

    private final SkillSchemas schemas;
    private final JsonMapper mapper;

    public KoreanProseCheck(SkillSchemas schemas, JsonMapper mapper) {
        this.schemas = schemas;
        this.mapper = mapper;
    }

    @Override
    public String skill() {
        return "korean-prose-editor";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        JsonNode input = requireObject(args.document("input"), "input");
        return SkillCheckResult.ok(switch (command) {
            case EXTRACT_PROTECTED_SPANS -> extract(schemas.bind(input, SpansInput.class));
            case SOURCE_UNIT_MANIFEST -> unitManifest(schemas.bind(input, UnitsInput.class));
            case CHECK_RESULT -> checkResult(schemas.bind(input, CheckInput.class));
            case FINALIZE -> answer(finalizeText(schemas.bind(input, FinalizeRequest.class)));
        });
    }

    private static String hex(String value) {
        return Digests.sha256Hex(value);
    }

    /** The digest of a document, as the text of its canonical form: the same text for the same document, whatever its member order. */
    private static String documentDigest(JsonNode document) {
        return Digests.sha256Hex(CanonicalJson.of(document));
    }

    /** An identifier of the contract's form: the prefix and the number in at least four digits. */
    private static String numbered(String prefix, int number) {
        String digits = Integer.toString(number);
        return prefix + "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    /** A request or one of its documents that must be a JSON object: no record describes a document that is not one. */
    private static JsonNode requireObject(JsonNode value, String label) {
        need(value != null && value.isObject(), InputCode.KOREAN_PROSE_MUST_BE_OBJECT, "label", label);
        return value;
    }

    /** The items of an array document; a document that is not an array has none. */
    private static List<JsonNode> items(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }

    // ---- extract-protected-spans ----------------------------------------------------------------------------------------------------------

    private Manifest extract(SpansInput input) {
        String source = input.source();
        need(source != null, InputCode.KOREAN_PROSE_SPANS_SOURCE_NOT_STRING);
        List<String> protectedStrings = input.protectedStrings() == null ? List.of() : input.protectedStrings();
        need(protectedStrings.stream().noneMatch(value -> value == null || value.isEmpty() || !source.contains(value)), InputCode.KOREAN_PROSE_SPANS_PROTECTED_STRINGS_INVALID);
        List<Candidate> candidates = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.regex().matcher(source);
            while (matcher.find()) {
                String text = rule.kind() == SpanKind.URL ? URL_TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("") : matcher.group();
                candidates.add(new Candidate(rule.kind(), matcher.start(), matcher.start() + text.length(), text, rule.priority()));
            }
        }
        Matcher links = MARKDOWN_LINK.matcher(source);
        while (links.find()) {
            addMarkdownTarget(source, links.end(), candidates);
        }
        for (String value : new LinkedHashSet<>(protectedStrings)) {
            for (int start = source.indexOf(value); start != -1; start = source.indexOf(value, start + 1)) {
                candidates.add(new Candidate(SpanKind.USER_DEFINED, start, start + value.length(), value, 40));
            }
        }
        candidates.sort(Comparator.<Candidate>comparingInt(c -> -c.priority()).thenComparingInt(Candidate::start).thenComparingInt(c -> -c.end()));
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<Candidate> overlapping = selected.stream().filter(span -> candidate.start() < span.end() && span.start() < candidate.end()).toList();
            if (overlapping.isEmpty()) {
                selected.add(candidate);
                continue;
            }
            // The union is preserved even when a user string crosses an automatic span's boundary.
            Candidate first = overlapping.get(0);
            int start = Math.min(candidate.start(), overlapping.stream().mapToInt(Candidate::start).min().orElseThrow());
            int end = Math.max(candidate.end(), overlapping.stream().mapToInt(Candidate::end).max().orElseThrow());
            selected.removeAll(overlapping);
            selected.add(new Candidate(first.kind(), start, end, source.substring(start, end), first.priority()));
        }
        selected.sort(Comparator.comparingInt(Candidate::start).thenComparingInt(Candidate::end));
        List<ManifestSpan> spans = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            Candidate c = selected.get(i);
            spans.add(new ManifestSpan(numbered("span-", i + 1), c.kind(), c.start(), c.end(), c.text(), hex(c.text())));
        }
        return new Manifest(VERSION, hex(source), source.length(), spans);
    }

    /** The target's closing parenthesis: an escaped, angled or titled one does not close it. */
    private static void addMarkdownTarget(String source, int start, List<Candidate> candidates) {
        int depth = 1;
        boolean angle = false;
        char quote = 0;
        for (int end = start; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '\n' || c == '\r') {
                return;
            }
            if (c == '\\') {
                end++;
                continue;
            }
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
                continue;
            }
            if (angle) {
                angle = c != '>';
                continue;
            }
            if (c == '<' && onlySpacesAndTabs(source, start, end)) {
                angle = true;
                continue;
            }
            if ((c == '"' || c == '\'') && end > 0 && isJsSpace(source.charAt(end - 1)) && depth == 1) {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                if (end > start) {
                    candidates.add(new Candidate(SpanKind.MARKDOWN_TARGET, start, end, source.substring(start, end), 80));
                }
                return;
            }
        }
    }

    private static boolean onlySpacesAndTabs(String source, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isJsSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == '\u00a0' || c == '\ufeff' || c == '\u1680' || (c >= '\u2000' && c <= '\u200a') || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000';
    }

    /** Whether the edit's range touches the other range; an empty range touches only what it lies strictly inside. */
    private static boolean overlaps(int start, int end, int otherStart, int otherEnd) {
        return start == end ? otherStart < start && start < otherEnd : start < otherEnd && otherStart < end;
    }

    // ---- source-unit-manifest -------------------------------------------------------------------------------------------------------------

    private UnitManifest unitManifest(UnitsInput input) {
        need(input.source() != null, InputCode.KOREAN_PROSE_UNITS_SOURCE_NOT_STRING);
        return unitManifest(input.source(), input.manifest());
    }

    private UnitManifest unitManifest(String source, Manifest manifest) {
        need(manifest != null && Objects.equals(hex(source), manifest.sourceDigest()) && Objects.equals(source.length(), manifest.sourceLength()) && manifest.spans() != null,
                InputCode.KOREAN_PROSE_UNITS_MANIFEST_SOURCE_MISMATCH);
        List<Partition> partitions = new ArrayList<>();
        int cursor = 0;
        for (Range range : fencedRanges(source)) {
            proseRanges(source, cursor, range.start(), partitions);
            partitions.add(new Partition(range.start(), range.end(), UnitKind.FENCED_CODE));
            cursor = range.end();
        }
        proseRanges(source, cursor, source.length(), partitions);
        List<SourceUnit> units = new ArrayList<>(partitions.size());
        for (int i = 0; i < partitions.size(); i++) {
            Partition partition = partitions.get(i);
            List<String> ids = manifest.spans().stream()
                    .filter(span -> span != null && span.start() != null && span.end() != null && partition.start() < span.end() && span.start() < partition.end())
                    .map(ManifestSpan::id).toList();
            units.add(new SourceUnit(numbered("unit-", i + 1), partition.start(), partition.end(), partition.kind(), hex(source.substring(partition.start(), partition.end())), ids));
        }
        return new UnitManifest(VERSION, hex(source), source.length(), units);
    }

    private static void proseRanges(String source, int start, int end, List<Partition> out) {
        String segment = source.substring(start, end);
        Matcher separators = PARAGRAPH_BREAK.matcher(segment);
        int cursor = 0;
        List<Range> boundaries = new ArrayList<>();
        while (separators.find()) {
            boundaries.add(new Range(cursor, separators.start()));
            cursor = separators.end();
        }
        boundaries.add(new Range(cursor, segment.length()));
        for (Range boundary : boundaries) {
            int from = boundary.start();
            int to = boundary.end();
            while (from < to && isJsSpace(segment.charAt(from))) {
                from++;
            }
            while (to > from && isJsSpace(segment.charAt(to - 1))) {
                to--;
            }
            if (from < to) {
                out.add(new Partition(start + from, start + to, UnitKind.PROSE));
            }
        }
    }

    private static List<Range> fencedRanges(String source) {
        List<Range> ranges = new ArrayList<>();
        Matcher opening = FENCE_LINE.matcher(source);
        int from = 0;
        while (from <= source.length() && opening.find(from)) {
            String marker = opening.group(2);
            Matcher closing = (marker.charAt(0) == '`' ? BACKTICK_FENCE_CLOSE : TILDE_FENCE_CLOSE).matcher(source);
            int end = closingEnd(closing, opening.end(), marker.length(), source.length());
            ranges.add(new Range(opening.start(), end));
            if (end == opening.start()) {
                break;
            }
            from = end;
        }
        return ranges;
    }

    /** The end of the first closing fence at or after {@code from} whose run of fence characters is at least {@code length} long, or the end of the source when none closes it. */
    private static int closingEnd(Matcher closing, int from, int length, int sourceLength) {
        int at = from;
        while (at <= sourceLength && closing.find(at)) {
            if (closing.group(1).length() >= length) {
                return closing.end();
            }
            at = closing.end();
        }
        return sourceLength;
    }

    // ---- check-result ---------------------------------------------------------------------------------------------------------------------

    /** The manifest's digest is that of the document as it was sent: {@code null} when there is none. */
    private CheckReport checkResult(CheckInput input) {
        String source = input.source() == null ? "" : input.source();
        String candidate = input.candidate() == null ? "" : input.candidate();
        boolean given = !absent(input.manifest());
        Manifest manifest = given ? schemas.bind(input.manifest(), Manifest.class) : null;
        Preservation result = preservation(source, candidate, manifest);
        return new CheckReport(VERSION, new CheckDigests(hex(source), hex(candidate), hex(given ? CanonicalJson.of(input.manifest()) : "null")),
                new CheckLengths(source.length(), candidate.length()), new CheckDecisions(result.preserved()), List.copyOf(result.warnings()));
    }

    /** Whether the candidate keeps every protected span of the manifest, in order and as often as the source has it, and what is wrong with the manifest or the candidate if not. */
    private static Preservation preservation(String source, String candidate, Manifest manifest) {
        Set<Warning> warnings = new TreeSet<>(Comparator.comparing(Warning::name));
        boolean valid = true;
        if (manifest == null || !VERSION.equals(manifest.schemaVersion())) {
            warnings.add(Warning.MANIFEST_SCHEMA_VERSION);
            valid = false;
        }
        if (manifest == null || !Objects.equals(hex(source), manifest.sourceDigest()) || !Objects.equals(source.length(), manifest.sourceLength())) {
            warnings.add(Warning.SOURCE_MANIFEST_MISMATCH);
            valid = false;
        }
        boolean spansGiven = manifest != null && manifest.spans() != null;
        if (!spansGiven) {
            warnings.add(Warning.MANIFEST_SPANS_INVALID);
            valid = false;
        }
        List<ManifestSpan> spans = spansGiven ? manifest.spans() : List.of();
        int cursor = 0;
        for (ManifestSpan span : spans) {
            String text = span == null || span.text() == null ? "" : span.text();
            int start = span == null || span.start() == null ? -1 : span.start();
            int end = span == null || span.end() == null ? -1 : span.end();
            boolean matchesSource = !text.isEmpty() && Objects.equals(hex(text), span.digest()) && start >= 0 && end >= start && end <= source.length()
                    && Objects.equals(source.substring(start, end), text);
            if (!matchesSource) {
                warnings.add(Warning.MANIFEST_SPAN_INVALID);
                valid = false;
                continue;
            }
            int foundAt = candidate.indexOf(text, cursor);
            if (foundAt == -1) {
                warnings.add(Warning.PROTECTED_SPAN_MISSING_OR_REORDERED);
                valid = false;
                continue;
            }
            cursor = foundAt + text.length();
        }
        Set<String> texts = new LinkedHashSet<>();
        for (ManifestSpan span : spans) {
            if (span != null && span.text() != null && !span.text().isEmpty()) {
                texts.add(span.text());
            }
        }
        for (String text : texts) {
            if (count(source, text) != count(candidate, text)) {
                warnings.add(Warning.PROTECTED_SPAN_MISSING_OR_REORDERED);
                valid = false;
            }
        }
        return new Preservation(valid, warnings);
    }

    private static int count(String text, String value) {
        int count = 0;
        for (int i = text.indexOf(value); i != -1; i = text.indexOf(value, i + value.length())) {
            count++;
        }
        return count;
    }

    // ---- finalize -------------------------------------------------------------------------------------------------------------------------

    private static Object answer(Finalized result) {
        return switch (result.mode()) {
            case MCP -> result.receipt();
            case DIRECT -> new DirectAnswer(result.output(), UNVERIFIED, result.receipt());
        };
    }

    /** The actors the plan names, once the plan is sound: a plan that is not is a request that is wrong, and is refused. */
    private static List<String> validatePlan(Plan plan, boolean subagentsAvailable) {
        need(subagentsAvailable, InputCode.KOREAN_PROSE_THREE_ACTORS_REQUIRED);
        need(plan != null, InputCode.KOREAN_PROSE_MUST_BE_OBJECT, "label", "plan");
        need(VERSION.equals(plan.schemaVersion()), InputCode.KOREAN_PROSE_PLAN_SCHEMA_VERSION_UNSUPPORTED);
        List<String> actors = plan.actorIds();
        need(actors != null && actors.size() == ACTORS, InputCode.KOREAN_PROSE_ACTOR_IDS_COUNT);
        need(actors.stream().allMatch(actor -> actor != null && UUID.matcher(actor).matches()), InputCode.KOREAN_PROSE_ACTOR_IDS_FORMAT);
        need(new HashSet<>(actors).size() == ACTORS, InputCode.KOREAN_PROSE_ACTOR_IDS_UNIQUE);
        need(plan.providers() != null, InputCode.KOREAN_PROSE_MUST_BE_OBJECT, "label", "providers");
        for (Role role : Role.values()) {
            Provider provider = plan.agent(role);
            need(provider != null, InputCode.KOREAN_PROSE_MUST_BE_OBJECT, "label", "providers." + role.label());
            need(provider.kind() == ProviderKind.AGENT && Objects.equals(actors.get(role.ordinal()), provider.actorId()), InputCode.KOREAN_PROSE_PROVIDER_ACTOR_MISMATCH, "name", role.label(), "index", role.ordinal());
        }
        Provider finalization = plan.providers().finalization();
        need(finalization != null, InputCode.KOREAN_PROSE_MUST_BE_OBJECT, "label", "providers.finalization");
        need(finalization.kind() == ProviderKind.DETERMINISTIC && ENTRYPOINT.equals(finalization.entrypoint()), InputCode.KOREAN_PROSE_FINALIZER_PROVIDER);
        need(finalization.actorId() == null, InputCode.KOREAN_PROSE_FINALIZER_ACTOR);
        return actors;
    }

    private Finalized finalizeText(FinalizeRequest request) {
        need(VERSION.equals(request.schemaVersion()), InputCode.KOREAN_PROSE_FINAL_REQUEST_SCHEMA_VERSION_UNSUPPORTED);
        Mode mode = Mode.of(request.mode());
        need(mode != null, InputCode.KOREAN_PROSE_FINAL_MODE_INVALID);
        String source = request.source();
        need(source != null, InputCode.KOREAN_PROSE_FINAL_SOURCE_NOT_STRING);
        String sourceDigest = hex(source);
        List<String> actorIds = validatePlan(request.plan(), request.subagentsAvailable());
        JsonNode manifestDocument = requireObject(request.manifest(), "manifest");
        JsonNode unitManifestDocument = requireObject(request.sourceUnitManifest(), "sourceUnitManifest");
        JsonNode selectionDocument = requireObject(request.selection(), "selection");
        JsonNode editingDocument = requireObject(request.editing(), "editing");
        JsonNode verificationDocument = requireObject(request.verification(), "verification");
        Tally tally = new Tally();
        Manifest manifest = validManifest(manifestDocument, source, sourceDigest);
        if (manifest == null) {
            tally.flag(Warning.SOURCE_MANIFEST_MISMATCH);
        }
        GlossaryOutcome glossary = glossary(mode, sourceDigest, request.glossaryMatchSet(), manifest);
        glossary.warnings().forEach(tally::note);
        if (!glossary.valid()) {
            tally.flag(Warning.GLOSSARY_MATCH_SET_INVALID);
        }
        String binding = CanonicalJson.of(mapper.valueToTree(glossary.binding()));
        BindingView selectionView = schemas.bind(selectionDocument, BindingView.class);
        EditingView editingView = schemas.bind(editingDocument, EditingView.class);
        VerificationView verificationView = schemas.bind(verificationDocument, VerificationView.class);
        boolean selectionBound = bound(binding, selectionView.glossaryBinding());
        boolean editingBound = bound(binding, editingView.glossaryBinding());
        boolean verificationBound = bound(binding, verificationView.glossaryBinding());
        if (!selectionBound || !editingBound || !verificationBound) {
            tally.flag(Warning.GLOSSARY_BINDING_MISMATCH);
        }
        List<SourceUnit> units = List.of();
        if (!tally.fallback()) {
            UnitManifest expected = unitManifest(source, manifest);
            if (Objects.equals(CanonicalJson.of(unitManifestDocument), CanonicalJson.of(mapper.valueToTree(expected)))) {
                units = expected.units();
            } else {
                tally.flag(Warning.SOURCE_UNIT_MANIFEST_MISMATCH);
            }
        }
        Map<String, SourceUnit> unitById = new HashMap<>();
        for (SourceUnit unit : units) {
            unitById.put(unit.unitId(), unit);
        }
        Map<String, SelectionDecision> selectionByUnit = new HashMap<>();
        Selection selection = validSelection(selectionDocument, sourceDigest, actorIds.get(Role.SELECTION.ordinal()), unitById, source, selectionBound);
        if (selection == null) {
            tally.flag(Warning.SELECTION_CONTRACT_INVALID);
        } else {
            for (SelectionDecision decision : selection.decisions()) {
                selectionByUnit.put(decision.unitId(), decision);
            }
            if (selection.status() != SelectionStatus.READY) {
                tally.flag(Warning.SELECTION_NOT_READY);
            }
        }
        String selectionDigest = documentDigest(selectionDocument);
        Shape editingShape = shape(EDITING_SCHEMA, editingDocument, EDIT_ITEM);
        EditingRoot editingRoot = editingShape.rootValid() ? schemas.bind(editingDocument, EditingRoot.class) : null;
        String editingActor = actorIds.get(Role.EDITING.ordinal());
        if (editingRoot == null || !editingBound || !Objects.equals(editingActor, editingRoot.actorId()) || !Objects.equals(sourceDigest, editingRoot.sourceDigest())
                || !Objects.equals(selectionDigest, editingRoot.selectionDigest())) {
            tally.flag(editingRoot != null && !Objects.equals(selectionDigest, editingRoot.selectionDigest()) ? Warning.SELECTION_DIGEST_MISMATCH : Warning.EDIT_CONTRACT_INVALID);
        }
        List<EditItem> edits = new ArrayList<>();
        Set<String> editIds = new HashSet<>();
        List<JsonNode> rawEdits = items(editingView.edits());
        for (int i = 0; i < rawEdits.size(); i++) {
            EditItem edit = editingShape.brokenItems().contains(i) ? null : readEdit(rawEdits.get(i), source, sourceDigest, editingActor, editIds);
            if (edit == null) {
                tally.flag(Warning.EDIT_CONTRACT_INVALID);
                continue;
            }
            editIds.add(edit.id());
            edits.add(edit);
        }
        edits.sort(Comparator.comparingInt(EditItem::start).thenComparingInt(EditItem::end).thenComparing(EditItem::id));
        for (int i = 1; i < edits.size(); i++) {
            EditItem previous = edits.get(i - 1);
            EditItem current = edits.get(i);
            if (current.start() < previous.end() || (current.start() == previous.start() && current.start() == current.end())) {
                tally.flag(Warning.EDIT_RANGES_OVERLAP);
            }
        }
        if (!tally.fallback() && !Objects.equals(hex(apply(source, edits)), editingRoot.candidateDigest())) {
            tally.flag(Warning.EDITING_DIGEST_MISMATCH);
        }
        String editingDigest = documentDigest(editingDocument);
        Shape verificationShape = shape(VERIFICATION_SCHEMA, verificationDocument, DECISION_ITEM);
        VerificationRoot verification = verificationShape.rootValid() ? schemas.bind(verificationDocument, VerificationRoot.class) : null;
        if (verification == null || !verificationBound || !Objects.equals(actorIds.get(Role.VERIFICATION.ordinal()), verification.actorId())
                || !Objects.equals(sourceDigest, verification.sourceDigest()) || !Objects.equals(editingDigest, verification.editingDigest())) {
            tally.flag(verification != null && !Objects.equals(editingDigest, verification.editingDigest()) ? Warning.VERIFICATION_DIGEST_MISMATCH : Warning.VERIFICATION_CONTRACT_INVALID);
        }
        String rubric = request.rubricDigest();
        if (rubric == null || !DIGEST.matcher(rubric).matches() || (verification != null && !Objects.equals(rubric, verification.rubricDigest()))) {
            tally.flag(Warning.VERIFICATION_RUBRIC_DIGEST_MISMATCH);
        }
        if (verification != null && verification.globalDecision() == GlobalDecision.FALLBACK) {
            tally.flag(Warning.VERIFIER_GLOBAL_FALLBACK);
        }
        Map<String, VerificationDecision> decisionByEdit = new HashMap<>();
        List<JsonNode> rawDecisions = items(verificationView.decisions());
        for (int i = 0; i < rawDecisions.size(); i++) {
            VerificationDecision decision = verificationShape.brokenItems().contains(i) ? null : schemas.bind(rawDecisions.get(i), VerificationDecision.class);
            if (decision == null || decisionByEdit.containsKey(decision.editId()) || !editIds.contains(decision.editId())) {
                tally.flag(Warning.VERIFICATION_DECISION_INVALID);
                continue;
            }
            decisionByEdit.put(decision.editId(), decision);
        }
        // The manifest the final check holds the result to: the source's own when the finalization already falls back, else with what the selection asked to protect.
        boolean originalManifest = tally.fallback();
        Manifest effective = originalManifest ? manifest : withSelectionProtected(source, manifest, selection.decisions(), unitById);
        List<Range> spans = effective == null ? List.of() : effective.spans().stream().map(span -> new Range(span.start(), span.end())).toList();
        List<EditItem> accepted = new ArrayList<>();
        List<EditItem> retained = new ArrayList<>();
        for (EditItem edit : edits) {
            SourceUnit unit = unitById.get(edit.unitId());
            SelectionDecision selected = selectionByUnit.get(edit.unitId());
            boolean inScope = unit != null && selected != null && selected.action() == SelectionAction.EDIT && edit.start() >= unit.start() && edit.end() <= unit.end()
                    && selected.issueRanges().stream().anyMatch(issue -> overlaps(edit.start(), edit.end(), issue.start(), issue.end()));
            if (!inScope) {
                retained.add(edit);
                tally.note(Warning.EDIT_OUT_OF_SCOPE_RETAINED);
                continue;
            }
            if (unit.kind() == UnitKind.FENCED_CODE || spans.stream().anyMatch(span -> overlaps(edit.start(), edit.end(), span.start(), span.end()))) {
                retained.add(edit);
                tally.note(Warning.PROTECTED_EDIT_RETAINED);
                continue;
            }
            if (!minimal(source, edit)) {
                retained.add(edit);
                tally.note(Warning.NON_MINIMAL_EDIT_RETAINED);
                continue;
            }
            VerificationDecision decision = decisionByEdit.get(edit.id());
            if (decision == null || decision.decision() != DecisionKind.ACCEPT) {
                retained.add(edit);
                if (decision == null) {
                    tally.note(Warning.VERIFICATION_DECISION_MISSING);
                }
                continue;
            }
            accepted.add(edit);
        }
        if (!tally.fallback() && !accepted.isEmpty() && !verification.assessment().safe()) {
            tally.flag(Warning.VERIFICATION_CONTRACT_INVALID);
        }
        String output = source;
        if (!tally.fallback()) {
            output = apply(source, accepted);
            Preservation check = preservation(source, output, effective);
            if (!check.preserved()) {
                tally.flag(Warning.FINAL_PROTECTED_CHECK_FAILED);
                check.warnings().forEach(tally::note);
                output = source;
            }
        }
        boolean fallback = tally.fallback();
        if (fallback) {
            tally.note(Warning.GLOBAL_FALLBACK_APPLIED);
        }
        List<EditItem> applied = fallback ? List.of() : accepted;
        List<EditItem> allRetained = fallback ? edits : retained;
        String manifestDigest = originalManifest ? documentDigest(manifestDocument) : documentDigest(mapper.valueToTree(effective));
        ReceiptDecisions decisions = new ReceiptDecisions(mode, mode == Mode.MCP && !fallback ? Assurance.VERIFIED : Assurance.UNVERIFIED, fallback ? ReceiptStatus.FALLBACK : ReceiptStatus.FINALIZED,
                applied.stream().map(edit -> hex(edit.id())).toList(), allRetained.stream().map(edit -> hex(edit.id())).toList(), fallback);
        Receipt receipt = new Receipt(VERSION, actorIds, new ReceiptDigest(sourceDigest, hex(output), manifestDigest), new ReceiptLength(source.length(), output.length()), glossary.summary(),
                decisions, tally.warnings());
        return new Finalized(mode, output, receipt);
    }

    /** Whether a work product's glossary binding is, as a document, the binding the finalizer derived (the binding's text). */
    private static boolean bound(String binding, JsonNode stated) {
        return Objects.equals(binding, stated == null || stated.isMissingNode() ? "null" : CanonicalJson.of(stated));
    }

    /** The schema's verdict on a work product, split by where a violation lies: in the document's own shape, or inside one of its items (by the pattern of the items' path). */
    private Shape shape(String schema, JsonNode document, Pattern itemPath) {
        boolean rootValid = true;
        Set<Integer> brokenItems = new HashSet<>();
        for (SkillSchemas.Violation violation : schemas.violations(schema, document)) {
            Matcher item = itemPath.matcher(violation.instancePath());
            if (item.matches()) {
                brokenItems.add(Integer.valueOf(item.group(1)));
            } else {
                rootValid = false;
            }
        }
        return new Shape(rootValid, brokenItems);
    }

    /** The manifest the request carries when it holds to its contract and to the source (its spans in order, each the source's own text), else null. */
    private Manifest validManifest(JsonNode document, String source, String sourceDigest) {
        if (!schemas.valid(MANIFEST_SCHEMA, document)) {
            return null;
        }
        Manifest manifest = schemas.bind(document, Manifest.class);
        if (!Objects.equals(sourceDigest, manifest.sourceDigest()) || manifest.sourceLength() != source.length()) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        int previousEnd = 0;
        for (ManifestSpan span : manifest.spans()) {
            if (!ids.add(span.id()) || span.start() < previousEnd || span.end() <= span.start() || span.end() > source.length()) {
                return null;
            }
            if (!Objects.equals(span.text(), source.substring(span.start(), span.end())) || !Objects.equals(hex(span.text()), span.digest())) {
                return null;
            }
            previousEnd = span.end();
        }
        return manifest;
    }

    private static NoBinding noBinding() {
        return NoBinding.none();
    }

    private static McpBinding failureBinding(String sourceDigest) {
        return new McpBinding(VERSION, GlossaryMode.MCP, GlossaryStatus.UNAVAILABLE, sourceDigest, null, null, null, null, 0, List.of(Warning.GLOSSARY_UNAVAILABLE));
    }

    private static GlossarySummary unavailableSummary() {
        return new GlossarySummary(GlossaryMode.MCP, GlossaryStatus.UNAVAILABLE, null, null, null, null, 0, List.of());
    }

    private static McpBinding bindingFrom(MatchSet matchSet) {
        GlossaryInfo glossary = matchSet.glossary();
        return new McpBinding(VERSION, GlossaryMode.MCP, matchSet.status(), matchSet.sourceDigest(), glossary == null ? null : glossary.id(), glossary == null ? null : glossary.version(),
                glossary == null ? null : glossary.contentDigest(), matchSet.matchSetDigest(), matchSet.matches().size(), matchSet.warnings());
    }

    private static GlossarySummary summaryFrom(Binding binding) {
        return switch (binding) {
            case NoBinding none -> new GlossarySummary(none.mode(), GlossaryStatus.DIRECT, null, null, null, null, 0, List.of());
            case McpBinding mcp -> new GlossarySummary(mcp.mode(), mcp.status(), mcp.glossaryId(), mcp.glossaryVersion(), mcp.glossaryDigest(), mcp.matchSetDigest(), mcp.matchCount(), mcp.warnings());
        };
    }

    private GlossaryOutcome glossary(Mode mode, String sourceDigest, JsonNode value, Manifest manifest) {
        return switch (mode) {
            case DIRECT -> new GlossaryOutcome(absent(value), noBinding(), summaryFrom(noBinding()), List.of());
            case MCP -> mcpGlossary(sourceDigest, value, manifest);
        };
    }

    /**
     * The glossary lookup's match set judged against the source: its schema, its digest, what its status states, and (with a manifest to hold it to) that its matches lie in the
     * source and that every {@code protect} match lies inside a protected span. A match set that fails any of this is unavailable; one whose only fault is a protect match left
     * unprotected still binds the work products to it.
     */
    private GlossaryOutcome mcpGlossary(String sourceDigest, JsonNode value, Manifest manifest) {
        GlossaryOutcome invalid = new GlossaryOutcome(false, failureBinding(sourceDigest), unavailableSummary(), List.of());
        if (absent(value) || !schemas.valid(MATCH_SET_SCHEMA, value)) {
            return invalid;
        }
        MatchSet matchSet = schemas.bind(value, MatchSet.class);
        GlossaryStatus status = matchSet.status();
        GlossaryInfo glossary = matchSet.glossary();
        boolean available = status.available();
        if (!Objects.equals(sourceDigest, matchSet.sourceDigest()) || !status.impliedWarnings().equals(matchSet.warnings())) {
            return invalid;
        }
        // A lookup that answered states its glossary and digest; one that did not states no digest, and a glossary only when it is the one whose limit it exceeded.
        boolean statesWhatItAnswers = available ? glossary != null && matchSet.matchSetDigest() != null
                : matchSet.matchSetDigest() == null && (glossary == null || status == GlossaryStatus.LIMIT_EXCEEDED);
        if (!statesWhatItAnswers) {
            return invalid;
        }
        int count = matchSet.matches().size();
        if ((!available && count != 0) || (status == GlossaryStatus.MATCHED && count == 0) || (status == GlossaryStatus.NO_MATCH && count != 0)) {
            return invalid;
        }
        if (manifest != null) {
            int limit = manifest.sourceLength() == null ? 0 : manifest.sourceLength();
            if (matchSet.matches().stream().anyMatch(match -> match.start() < 0 || match.end() <= match.start() || match.end() > limit)) {
                return invalid;
            }
        }
        if (available) {
            MatchSetDigestInput digestInput = new MatchSetDigestInput(VERSION, sourceDigest, glossary, matchSet.matches());
            if (!Objects.equals(matchSet.matchSetDigest(), documentDigest(mapper.valueToTree(digestInput)))) {
                return invalid;
            }
            boolean protectMissing = manifest != null && matchSet.matches().stream().anyMatch(match -> match.policy() == Policy.PROTECT && manifest.spans().stream().noneMatch(span -> span.start() <= match.start() && span.end() >= match.end()));
            if (protectMissing) {
                McpBinding binding = bindingFrom(matchSet);
                return new GlossaryOutcome(false, binding, summaryFrom(binding), List.of(Warning.GLOSSARY_PROTECT_MISSING));
            }
        }
        McpBinding binding = bindingFrom(matchSet);
        return new GlossaryOutcome(true, binding, summaryFrom(binding), matchSet.warnings());
    }

    /** The selection work product, bound, when it holds to its contract, to this source, its units and its actor; else null. */
    private Selection validSelection(JsonNode document, String sourceDigest, String actorId, Map<String, SourceUnit> unitById, String source, boolean boundToGlossary) {
        if (!schemas.valid(SELECTION_SCHEMA, document)) {
            return null;
        }
        Selection selection = schemas.bind(document, Selection.class);
        if (!Objects.equals(actorId, selection.actorId()) || !Objects.equals(sourceDigest, selection.sourceDigest()) || !boundToGlossary || selection.decisions().size() != unitById.size()) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        for (SelectionDecision decision : selection.decisions()) {
            SourceUnit unit = unitById.get(decision.unitId());
            if (unit == null || !seen.add(decision.unitId()) || (unit.kind() == UnitKind.FENCED_CODE && decision.action() == SelectionAction.EDIT)) {
                return null;
            }
            String unitText = source.substring(unit.start(), unit.end());
            if (decision.additionalProtectedStrings().stream().anyMatch(text -> !unitText.contains(text))) {
                return null;
            }
            if (decision.action() == SelectionAction.EDIT && !validIssues(decision, unit, source)) {
                return null;
            }
        }
        return selection;
    }

    /** The issue ranges of an {@code edit} decision lie in the unit, on code-point boundaries, are not repeated, and give exactly the reason codes the decision names. */
    private static boolean validIssues(SelectionDecision decision, SourceUnit unit, String source) {
        Set<IssueRange> seen = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (IssueRange issue : decision.issueRanges()) {
            if (issue.start() < unit.start() || issue.end() > unit.end() || issue.end() <= issue.start() || !boundary(source, issue.start()) || !boundary(source, issue.end()) || !seen.add(issue)) {
                return false;
            }
            codes.add(issue.reasonCode());
        }
        List<String> reasons = decision.reasonCodes();
        return reasons.size() == codes.size() && codes.containsAll(reasons);
    }

    /** The edit as bound, when it states this source's digest and the editing actor, is not a repeat of an earlier edit's id and cuts no surrogate pair; else null. */
    private EditItem readEdit(JsonNode raw, String source, String sourceDigest, String actorId, Set<String> ids) {
        EditItem edit = schemas.bind(raw, EditItem.class);
        boolean sound = !ids.contains(edit.id()) && Objects.equals(sourceDigest, edit.sourceDigest()) && Objects.equals(actorId, edit.actorId()) && edit.end() >= edit.start()
                && edit.end() <= source.length() && boundary(source, edit.start()) && boundary(source, edit.end());
        return sound ? edit : null;
    }

    private static boolean boundary(String source, int index) {
        return index <= 0 || index >= source.length() || !(Character.isHighSurrogate(source.charAt(index - 1)) && Character.isLowSurrogate(source.charAt(index)));
    }

    private static String apply(String source, List<EditItem> edits) {
        List<EditItem> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(EditItem::start).reversed().thenComparing(Comparator.comparingInt(EditItem::end).reversed()));
        StringBuilder output = new StringBuilder(source);
        ordered.forEach(edit -> output.replace(edit.start(), edit.end(), edit.replacement()));
        return output.toString();
    }

    /** Whether the edit changes the text and touches neither the first nor the last code point it replaces. */
    private static boolean minimal(String source, EditItem edit) {
        String original = source.substring(edit.start(), edit.end());
        if (Objects.equals(original, edit.replacement())) {
            return false;
        }
        if (original.isEmpty() || edit.replacement().isEmpty()) {
            return true;
        }
        int[] before = original.codePoints().toArray();
        int[] after = edit.replacement().codePoints().toArray();
        return before[0] != after[0] && before[before.length - 1] != after[after.length - 1];
    }

    /** The manifest with the strings the selection asked to protect inside their units; overlapping ranges are joined, and every range is then a {@code user-defined} span. */
    private static Manifest withSelectionProtected(String source, Manifest manifest, List<SelectionDecision> decisions, Map<String, SourceUnit> unitById) {
        List<Range> ranges = new ArrayList<>();
        manifest.spans().forEach(span -> ranges.add(new Range(span.start(), span.end())));
        for (SelectionDecision decision : decisions) {
            SourceUnit unit = unitById.get(decision.unitId());
            if (unit == null) {
                continue;
            }
            for (String value : decision.additionalProtectedStrings()) {
                for (int start = source.indexOf(value, unit.start()); start != -1 && start + value.length() <= unit.end(); start = source.indexOf(value, start + 1)) {
                    ranges.add(new Range(start, start + value.length()));
                }
            }
        }
        ranges.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));
        List<Range> merged = new ArrayList<>();
        for (Range range : ranges) {
            Range previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null && range.start() < previous.end()) {
                merged.set(merged.size() - 1, new Range(previous.start(), Math.max(previous.end(), range.end())));
            } else {
                merged.add(range);
            }
        }
        List<ManifestSpan> spans = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            String text = source.substring(merged.get(i).start(), merged.get(i).end());
            spans.add(new ManifestSpan(numbered("span-", i + 1), SpanKind.USER_DEFINED, merged.get(i).start(), merged.get(i).end(), text, hex(text)));
        }
        return new Manifest(VERSION, hex(source), source.length(), spans);
    }
}
