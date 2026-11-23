// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

/**
 * Sennard is not a coding plugin. A general skill checks any kind of work, so its wording must not tie where it applies to one
 * field. Finds field-limited words in the description and body of every general SKILL.md (source and the Claude package), in the shared
 * intake block, the four mandatory reference boundaries, every other reference of a general skill, the classification targets every
 * session lists, and the hook, tool and instruction messages of every locale. A heuristic over explicit clause markers, not a language parser.
 */
final class DomainNeutralWording {
    /** Every skill is general (empty: it applies to any field of work) or field-specific (the field it exists for). */
    static final Map<String, String> SKILL_FIELDS = new LinkedHashMap<>();

    static {
        for (String general : List.of("acceptance-evidence-validator", "blocker-diagnostician", "coordinate-subagents", "evaluation-validity-auditor", "independent-audit-gate",
                "independent-deliberation-panel", "iteration-frame-auditor", "minimal-implementation", "mutation-risk-preflight", "orchestrator",
                "prompt-engineering", "recovery-strategy-selector", "session-board", "task-contract", "ui-ux-design")) {
            SKILL_FIELDS.put(general, "");
        }
        SKILL_FIELDS.put("change-scope-guardian", "run의 변경 기록에 따른 변경 범위");
        SKILL_FIELDS.put("code-review", "코드 변경분 검토");
        SKILL_FIELDS.put("cs-engineering", "소프트웨어의 동시성·DB·알고리즘·성능");
        SKILL_FIELDS.put("instruction-scope-resolver", "작업 공간의 에이전트 지침 파일");
        SKILL_FIELDS.put("korean-prose-editor", "한국어 산문");
        SKILL_FIELDS.put("software-security-auditor", "소프트웨어 보안");
        SKILL_FIELDS.put("solid-design", "코드 설계");
        SKILL_FIELDS.put("test-engineering", "소프트웨어 테스트");
    }

    private record Term(String name, Pattern pattern) {
    }

    private static Term term(String name, String regex) {
        return new Term(name, Pattern.compile(regex));
    }

    private static Term ci(String name, String regex) {
        return new Term(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /** Words that tie a sentence to software or screens. */
    private static final List<Term> FIELD_TERMS = List.of(
            term("코드", "(?<![레바에])코드"), term("소스", "(?<!리)소스"), term("저장소", "저장소"), term("커밋", "커밋"), term("브랜치", "브랜치"), term("빌드", "빌드"),
            term("테스트", "테스트"), term("구현", "구현"), term("개발", "개발"), term("리팩터링", "리팩터링"), term("라이브러리", "라이브러리"), term("프레임워크", "프레임워크"),
            term("컴포넌트", "컴포넌트"), term("렌더링", "렌더링"), term("화면", "화면"), term("웹", "웹(?!툰)"), term("데스크톱", "데스크톱"), term("모바일", "모바일"),
            term("백엔드", "백엔드"), term("프런트엔드", "프[런론]트엔드"), term("소프트웨어", "소프트웨어"),
            ci("diff", "\\bdiff\\b"), ci("patch", "\\bpatch(?:es)?\\b"), term("PR", "\\bPRs?\\b|\\bpull requests?\\b"), term("CI", "\\bCI\\b"), ci("Git", "\\bgit\\b"),
            ci("push", "\\bpush\\b"), term("API", "\\bAPIs?\\b"), term("CLI", "\\bCLIs?\\b"), term("UI", "\\bUI\\b"), ci("code", "\\b(?:source )?code(?:base)?\\b"),
            ci("repository", "\\brepositor(?:y|ies)\\b|\\brepos?\\b"), ci("commit", "\\bcommits?\\b"), ci("branch", "\\bbranch(?:es)?\\b"), ci("build", "\\bbuilds?\\b"),
            ci("implementation", "\\bimplement(?:s|ed|ing|ation|ations|er|ers)?\\b"), ci("library", "\\blibrar(?:y|ies)\\b"), ci("screen", "\\bscreens?\\b"),
            ci("software", "\\bsoftware\\b"), ci("runner", "\\brunner\\b"));
    /** JavaScript's {@code \s}: Java's default covers only the ASCII spaces. */
    private static final String S = "\\s\\x{A0}\\x{FEFF}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}";
    private static final String SP = "[" + S + "]";
    private static final Pattern EXCLUSION = Pattern.compile("(?:사용하지|쓰지|적용하지|대신하지|대신 수행하지) 않는다|범위가 아니다|\\b[Dd]o not (?:use|activate|apply)\\b");
    private static final Pattern EXCLUSION_LEAD = Pattern.compile("\\b(?:[Nn]ot (?:automatically )?for|[Dd]o not (?:use|activate|apply)(?: it)? for)\\b");
    private static final Pattern ONLY = Pattern.compile("에만|이외|\\bonly\\b");
    private static final Pattern CLAUSE_END = Pattern.compile("(?:고|며|지만|으나|는데)," + SP + "|[;:]|\\b(?:and|but)," + SP);
    private static final Pattern BRANCH_SUFFIX = Pattern.compile("^" + SP + "*(?:이면|면|이라면|라면|일 때|인 경우|일 경우|에서는)");
    private static final Pattern BRANCH_HEADING = Pattern.compile("^#{2,}" + SP + ".*(?:일 때|인 경우|이면)" + SP + "*$|^#{2,}" + SP + "+When\\b");
    private static final Pattern EXAMPLE_GROUP = Pattern.compile("\\((?:예|e\\.g\\.|for example)[^)]*\\)");
    private static final Pattern EXAMPLE_LEAD = Pattern.compile("예를 들어|예컨대|\\be\\.g\\.|\\bfor example\\b|\\bsuch as\\b");
    private static final Pattern NAMES = Pattern.compile("\\[[^\\]]*\\]\\(https?://[^)]*\\)|\\]\\([^)]*\\)|\\bUI/UX\\b|\\b[a-z]+(?:-[a-z]+)+\\b|\\bClaude Code\\b|(?:종료|오류|상태|응답|원인|이유) 코드|화면 캡처");
    private static final Pattern INLINE_IDENTIFIER = Pattern.compile("^(?:[^" + S + "]+[/\\\\]|[./\\\\]|\\{|\\[|v?\\d+\\.\\d+|(?:node|python3?|git|pnpm|npm|npx)(?:" + SP + "|$))|^[^" + S + "]+\\.[a-zA-Z][a-zA-Z0-9]{0,7}$");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("(?:하고|하며|이고|이며|쓰고|쓰며|지만|으나|는데),?" + SP + "|[;:]|\\b(?:and|but)," + SP);
    private static final Pattern CONDITION = Pattern.compile("^" + SP + "*(?:-" + SP + "*)?(?:저장소 관례가 필요하고 이미 제공됐다면|소프트웨어 테스트와 표준 runner가 있는 경우에는)");
    private static final Pattern PARENTHESIZED = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern LIST_SPLIT = Pattern.compile("," + SP + "*|·");
    private static final Pattern STOP = Pattern.compile("[" + S + ",()「」『』:;.]");
    private static final Pattern SENTENCE_GAP = Pattern.compile("(?<=[.!?])" + SP + "+");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    // Consume MessageFormat quotes (including doubled apostrophes) before looking for catalog arguments.
    private static final Pattern CATALOG_ARGUMENT_OR_QUOTE = Pattern.compile("''|'(?:[^']++|'')*+'|(\\{[^,{}]+(?:,\\s*(?:number\\s*)?)?\\})");
    private static final Pattern FENCE = Pattern.compile("^" + SP + "*(```|~~~)");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]*)`");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}" + SP);
    private static final List<String> FIELD_SKILLS = SKILL_FIELDS.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    private static final String INTAKE_FILE = "skills/orchestrator/references/intake.md";

    private record Selection(String heading, Pattern select, int minimum) {
    }

    private static Selection sel(String heading, String select, int minimum) {
        return new Selection(heading, Pattern.compile(select), minimum);
    }

    /** Only the four mandatory references identified by D38, and their general application, role and procedure clauses. */
    private static final Map<String, List<Selection>> REFERENCE_RULES = Map.of(
            "skills/task-contract/references/entry-details.md", List.of(sel("작성 절차", "^1\\.\\s", 1)),
            "skills/independent-audit-gate/references/entry-details.md", List.of(
                    sel("권한과 역할 경계", "^- 감사자는", 3),
                    sel("실행 안전", "^(?:감사자와|- (?:저장소의|대상의) 기존 검증|- 감사자는)", 3),
                    sel("실행 절차", "^(?:3|4|6)\\.\\s", 3),
                    sel("완료 불변조건", "^- 감사자와", 1),
                    sel("판정의 범위를 보존한다", "^- 발견사항의 ID", 1)),
            "skills/independent-audit-gate/references/audit-protocol.md", List.of(
                    sel("2. 감사자 독립성", "^- ", 6),
                    sel("3. 감사자 briefing", "^필수 정보", 1),
                    sel("재현과 실행 안전", "^(?:증거 재현은|자원 경계는|감사자는 기존 검증|(?:구현자|변경 작업자)는 명세를|- (?:저장소가 정의한|대상에 정의된) 검증)", 5),
                    sel("6. 수정과 재감사", "^공백·서식", 1)),
            "skills/independent-audit-gate/references/orchestrator-integration.md", List.of(
                    sel("1. 책임 분리", "^- 상위 orchestrator", 1),
                    sel("3. 출력 handoff", "^- `Independence`", 1)));
    /** References of a general skill that exist for one field. */
    private static final Map<String, String> REFERENCE_FIELDS = Map.of(
            "skills/acceptance-evidence-validator/references/verification.md", "소프트웨어 엔지니어링 완료 근거 대조 모듈",
            "skills/blocker-diagnostician/references/debugging.md", "소프트웨어 엔지니어링 실패 판별 모듈",
            "skills/minimal-implementation/references/implementation.md", "소프트웨어 엔지니어링 구현 모듈",
            "skills/prompt-engineering/references/agent-instructions.md", "에이전트 지침 문서 작성 모듈");
    /** Locale messages whose subject is one field. */
    private static final Map<String, String> LOCALE_FIELDS = Map.of(
            "tools.check_for_updates.description", "Sennard 자체 배포 저장소의 판 확인",
            "tools.list_session_status.description", "세션 현황판이 보고하는 작업 공간 Git 상태");
    private static final List<String> LOCALE_DOMAINS = List.of("hooks", "tools", "instructions");
    /** English field words a hyphenated classification target can carry ("test-evidence"). */
    private static final List<Term> TARGET_TERMS;

    static {
        List<Term> terms = new ArrayList<>(FIELD_TERMS.stream().filter(term -> Character.isLetter(term.name().charAt(0)) && term.name().charAt(0) < 128).toList());
        terms.add(ci("test", "\\btests?\\b"));
        terms.add(ci("code", "\\bcoding\\b"));
        TARGET_TERMS = List.copyOf(terms);
    }

    record Finding(String file, int line, String term, String text) {
    }

    record Report(List<String> general, List<Finding> findings, List<String> problems) {
    }

    private DomainNeutralWording() {
    }

    private static String blank(String text) {
        return text.replaceAll("[^\\n]", " ");
    }

    private static boolean hasFieldTerm(String text) {
        return FIELD_TERMS.stream().anyMatch(term -> term.pattern().matcher(text).find());
    }

    private static boolean test(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private static int[] clauseBounds(String sentence, int index) {
        int start = 0;
        Matcher boundary = CLAUSE_BOUNDARY.matcher(sentence);
        while (boundary.find()) {
            int next = boundary.end();
            if (next > index) {
                return new int[] {start, boundary.start()};
            }
            start = next;
        }
        return new int[] {start, sentence.length()};
    }

    private static boolean inMixedList(String sentence, int index, int length) {
        int start = index;
        while (start > 0 && !STOP.matcher(String.valueOf(sentence.charAt(start - 1))).matches()) {
            start--;
        }
        int end = index + length;
        while (end < sentence.length() && !STOP.matcher(String.valueOf(sentence.charAt(end))).matches()) {
            end++;
        }
        String[] items = sentence.substring(start, end).split("·", -1);
        return items.length > 1 && Arrays.stream(items).anyMatch(item -> !item.isBlank() && !hasFieldTerm(item));
    }

    /** The rule that lets this occurrence stand, or null when it ties the sentence to the field. */
    private static String allowance(String sentence, String raw, int index, int length, boolean underBranchHeading) {
        if (underBranchHeading) {
            return "branch-heading";
        }
        if (test(BRANCH_SUFFIX, sentence.substring(index + length))) {
            return "branch";
        }
        int[] bounds = clauseBounds(sentence, index);
        int start = bounds[0];
        int end = bounds[1];
        String clause = sentence.substring(start, end);
        Matcher condition = CONDITION.matcher(sentence);
        if (condition.find() && start < condition.group().length()) {
            return "branch";
        }
        if (sentence.substring(index).startsWith("diff와 의미 동일성 근거")) {
            return "evidence-kind";
        }
        String rawClause = raw.substring(Math.min(start, raw.length()), Math.min(end, raw.length()));
        if (FIELD_SKILLS.stream().anyMatch(id -> rawClause.contains("`" + id + "`"))) {
            return "field-skill-routing";
        }
        Matcher group = EXAMPLE_GROUP.matcher(sentence);
        while (group.find()) {
            if (index >= group.start() && index < group.end()) {
                return "example";
            }
        }
        Matcher parenthesized = PARENTHESIZED.matcher(sentence);
        while (parenthesized.find()) {
            if (index < parenthesized.start() || index >= parenthesized.end()) {
                continue;
            }
            String[] items = LIST_SPLIT.split(parenthesized.group(1), -1);
            if (items.length > 1 && Arrays.stream(items).anyMatch(item -> !item.isBlank() && !hasFieldTerm(item))) {
                return "example-list";
            }
        }
        Matcher lead = EXAMPLE_LEAD.matcher(clause);
        if (lead.find() && lead.start() < index - start) {
            return "example";
        }
        if (!sentence.substring(index).startsWith("구현자") && inMixedList(sentence, index, length)) {
            return "mixed-list";
        }
        // The word must sit in the clause the exclusion closes, not in an earlier clause.
        Matcher exclusion = EXCLUSION.matcher(sentence.substring(index));
        if (exclusion.find() && !test(CLAUSE_END, sentence.substring(index, index + exclusion.start())) && !test(ONLY, sentence)) {
            return "exclusion";
        }
        Matcher excludedBy = EXCLUSION_LEAD.matcher(sentence.substring(0, index));
        int lastLead = -1;
        while (excludedBy.find()) {
            lastLead = excludedBy.start();
        }
        if (lastLead >= 0 && !test(CLAUSE_END, sentence.substring(lastLead, index)) && !test(ONLY, sentence)) {
            return "exclusion";
        }
        return null;
    }

    /** Findings in markdown; {@code lines} limits the scan (null: every line). Fenced code blocks and HTML comments are skipped. */
    static List<Finding> findFieldWording(String text, Set<Integer> lines) {
        return findFieldWording(text, lines, false);
    }

    private static List<Finding> findFieldWording(String text, Set<Integer> lines, boolean catalogMessage) {
        List<Finding> findings = new ArrayList<>();
        String source = replaceAll(HTML_COMMENT, text, match -> blank(match.group()));
        String[] all = source.split("\\r?\\n", -1);
        String[] scanLines = catalogMessage ? blankCatalogArguments(source).split("\\r?\\n", -1) : all;
        boolean fenced = false;
        boolean underBranchHeading = false;
        for (int offset = 0; offset < all.length; offset++) {
            String raw = all[offset];
            int line = offset + 1;
            if (test(FENCE, raw)) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            if (test(HEADING, raw)) {
                underBranchHeading = test(BRANCH_HEADING, raw);
            }
            if (lines != null && !lines.contains(line)) {
                continue;
            }
            // Blanking keeps every offset, so a span of cleaned is the same span of raw.
            String inlineNames = replaceAll(BACKTICKED, scanLines[offset], match -> {
                String content = match.group(1);
                return SKILL_FIELDS.containsKey(content) || test(INLINE_IDENTIFIER, content) ? blank(match.group()) : " " + content + " ";
            });
            String cleaned = replaceAll(NAMES, inlineNames, match -> blank(match.group()));
            List<int[]> ends = new ArrayList<>();
            Matcher gap = SENTENCE_GAP.matcher(cleaned);
            while (gap.find()) {
                ends.add(new int[] {gap.start(), gap.end()});
            }
            ends.add(new int[] {cleaned.length(), cleaned.length()});
            int start = 0;
            for (int[] end : ends) {
                String sentence = cleaned.substring(start, end[0]);
                String original = raw.substring(Math.min(start, raw.length()), Math.min(end[0], raw.length()));
                start = end[1];
                for (Term term : FIELD_TERMS) {
                    Matcher match = term.pattern().matcher(sentence);
                    while (match.find()) {
                        if (allowance(sentence, original, match.start(), match.end() - match.start(), underBranchHeading) == null) {
                            findings.add(new Finding(null, line, term.name(), original.strip()));
                        }
                    }
                }
            }
        }
        return findings;
    }

    private static String blankCatalogArguments(String text) {
        Matcher tokens = CATALOG_ARGUMENT_OR_QUOTE.matcher(text);
        StringBuilder prose = new StringBuilder(text);
        while (tokens.find()) {
            if (tokens.start(1) < 0) {
                continue;
            }
            // Keep line breaks and offsets so findings can still show the original message.
            for (int index = tokens.start(1); index < tokens.end(1); index++) {
                char character = text.charAt(index);
                if (character != '\r' && character != '\n') {
                    prose.setCharAt(index, ' ');
                }
            }
        }
        return prose.toString();
    }

    private static String replaceAll(Pattern pattern, String text, java.util.function.Function<java.util.regex.MatchResult, String> replacement) {
        return pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(replacement.apply(match)));
    }

    /** The 1-based lines of the frontmatter description and of the body. */
    static Set<Integer> skillLines(String markdown) {
        String[] all = markdown.split("\\r?\\n", -1);
        Set<Integer> result = new HashSet<>();
        if (!"---".equals(all[0])) {
            for (int index = 0; index < all.length; index++) {
                result.add(index + 1);
            }
            return result;
        }
        int close = indexOf(all, "---", 1);
        for (int index = 1; index < close; index++) {
            if (!all[index].startsWith("description:")) {
                continue;
            }
            result.add(index + 1);
            for (int next = index + 1; next < close && (all[next].isBlank() || all[next].matches("\\s+\\S.*")); next++) {
                result.add(next + 1);
            }
        }
        for (int index = close + 1; index < all.length; index++) {
            result.add(index + 1);
        }
        return result;
    }

    private static int indexOf(String[] lines, String text, int from) {
        for (int index = from; index < lines.length; index++) {
            if (text.equals(lines[index])) {
                return index;
            }
        }
        return -1;
    }

    private static Set<Integer> intakeLines(String markdown) {
        String[] all = markdown.split("\\r?\\n", -1);
        int start = -1;
        int end = -1;
        for (int index = 0; index < all.length; index++) {
            if (start < 0 && all[index].contains("<!-- skill-intake:start -->")) {
                start = index;
            }
            if (end < 0 && all[index].contains("<!-- skill-intake:end -->")) {
                end = index;
            }
        }
        if (start < 0 || end < start) {
            throw new IllegalStateException("skill-intake markers are missing");
        }
        Set<Integer> lines = new HashSet<>();
        for (int index = start + 1; index < end; index++) {
            lines.add(index + 1);
        }
        return lines;
    }

    private static List<Path> markdownFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A missing or empty structural selection fails; zero lines scanned would look like a pass. */
    private static Set<Integer> referenceLines(String markdown, List<Selection> rules) {
        String[] all = replaceAll(HTML_COMMENT, markdown, match -> blank(match.group())).split("\\r?\\n", -1);
        Set<Integer> prose = new HashSet<>();
        boolean fenced = false;
        for (int index = 0; index < all.length; index++) {
            if (test(FENCE, all[index])) {
                fenced = !fenced;
            } else if (!fenced && !all[index].isBlank()) {
                prose.add(index);
            }
        }
        Set<Integer> lines = new HashSet<>();
        Pattern headingLevel = Pattern.compile("^(#{1,6})\\s+(.*)$");
        for (Selection rule : rules) {
            List<Integer> starts = new ArrayList<>();
            for (int index = 0; index < all.length; index++) {
                Matcher heading = headingLevel.matcher(all[index]);
                if (prose.contains(index) && heading.matches() && heading.group(2).equals(rule.heading())) {
                    starts.add(index);
                }
            }
            if (starts.size() != 1) {
                throw new IllegalStateException("required reference section missing or repeated: " + rule.heading());
            }
            int start = starts.get(0);
            Matcher first = headingLevel.matcher(all[start]);
            first.matches();
            int level = first.group(1).length();
            int selected = 0;
            for (int index = start + 1; index < all.length; index++) {
                if (!prose.contains(index)) {
                    continue;
                }
                Matcher next = headingLevel.matcher(all[index]);
                if (next.matches() && next.group(1).length() <= level) {
                    break;
                }
                if (rule.select().matcher(all[index]).find()) {
                    lines.add(index + 1);
                    selected++;
                }
            }
            if (selected < rule.minimum()) {
                throw new IllegalStateException("required reference clauses missing: " + rule.heading() + " (" + selected + "/" + rule.minimum() + ")");
            }
        }
        return lines;
    }

    static Report checkRepository(Repo repo) {
        List<String> problems = new ArrayList<>();
        List<String> ids;
        try (Stream<Path> dirs = Files.list(repo.path("skills"))) {
            ids = dirs.filter(path -> Files.isDirectory(path) && Files.exists(path.resolve("SKILL.md"))).map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String id : ids) {
            if (!SKILL_FIELDS.containsKey(id)) {
                problems.add(id + ": not classified as general or field-specific in SKILL_FIELDS");
            }
        }
        for (String id : SKILL_FIELDS.keySet()) {
            if (!ids.contains(id)) {
                problems.add(id + ": classified but has no skills/" + id + "/SKILL.md");
            }
        }
        List<String> general = ids.stream().filter(id -> SKILL_FIELDS.getOrDefault(id, "x").isEmpty()).toList();
        List<Finding> findings = new ArrayList<>();
        for (String id : general) {
            for (String file : List.of("skills/" + id + "/SKILL.md", "claude-plugin/skills/" + id + "/SKILL.md")) {
                if (!repo.exists(file)) {
                    if (file.startsWith("claude-plugin/")) {
                        continue; // A skill the Claude package leaves out has no Claude copy.
                    }
                    throw new IllegalStateException("missing " + file);
                }
                String text = repo.text(file);
                add(findings, file, findFieldWording(text, skillLines(text)));
            }
        }
        String intake = repo.text(INTAKE_FILE);
        add(findings, INTAKE_FILE, findFieldWording(intake, intakeLines(intake)));
        REFERENCE_RULES.forEach((source, rules) -> {
            for (String file : List.of(source, "claude-plugin/" + source)) {
                String skill = source.split("/")[1];
                if (!repo.exists(file)) {
                    if (file.startsWith("claude-plugin/") && !repo.exists("claude-plugin/skills/" + skill + "/SKILL.md")) {
                        continue;
                    }
                    problems.add(file + ": required reference missing");
                    continue;
                }
                String text = repo.text(file);
                try {
                    add(findings, file, findFieldWording(text, referenceLines(text, rules)));
                } catch (IllegalStateException e) {
                    problems.add(file + ": " + e.getMessage());
                }
            }
        });
        Set<String> seen = findings.stream().map(finding -> finding.file() + ":" + finding.line() + ":" + finding.term()).collect(Collectors.toCollection(HashSet::new));
        for (String id : general) {
            for (String prefix : List.of("", "claude-plugin/")) {
                Path root = repo.path(prefix.isEmpty() ? "." : prefix).normalize();
                for (Path full : markdownFiles(repo.path(prefix + "skills/" + id + "/references"))) {
                    String source = root.relativize(full).toString().replace('\\', '/');
                    if (source.equals(INTAKE_FILE) || REFERENCE_FIELDS.keySet().stream().anyMatch(source::startsWith)) {
                        continue;
                    }
                    String file = prefix + source;
                    for (Finding finding : findFieldWording(repo.text(file), null)) {
                        if (seen.add(file + ":" + finding.line() + ":" + finding.term())) {
                            findings.add(new Finding(file, finding.line(), finding.term(), finding.text()));
                        }
                    }
                }
            }
        }
        for (String field : REFERENCE_FIELDS.keySet()) {
            if (!repo.exists(field)) {
                problems.add(field + ": field-specific reference listed but missing");
            }
        }
        for (String id : general) {
            String file = "skills/" + id + "/classification.json";
            if (!repo.exists(file)) {
                continue;
            }
            for (JsonNode target : repo.json(file).path("targets")) {
                String words = target.stringValue("").replace('-', ' ');
                for (Term term : TARGET_TERMS) {
                    if (term.pattern().matcher(words).find()) {
                        findings.add(new Finding(file, 0, term.name(), target.stringValue("")));
                    }
                }
            }
        }
        Path localeRoot = repo.path("locales");
        if (Files.isDirectory(localeRoot)) {
            List<String> locales;
            try (Stream<Path> dirs = Files.list(localeRoot)) {
                locales = dirs.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String locale : locales) {
                for (String domain : LOCALE_DOMAINS) {
                    String file = "locales/" + locale + "/" + domain + ".json";
                    if (!repo.exists(file)) {
                        continue;
                    }
                    repo.json(file).properties().forEach(entry -> {
                        if (LOCALE_FIELDS.containsKey(entry.getKey()) || !entry.getValue().isString()) {
                            return;
                        }
                        for (Finding finding : findFieldWording(entry.getValue().stringValue(), null, true)) {
                            findings.add(new Finding(file, 0, finding.term(), entry.getKey() + ": " + finding.text()));
                        }
                    });
                }
            }
            for (String key : LOCALE_FIELDS.keySet()) {
                boolean found = locales.stream().anyMatch(locale -> LOCALE_DOMAINS.stream().anyMatch(domain -> {
                    String file = "locales/" + locale + "/" + domain + ".json";
                    return repo.exists(file) && repo.json(file).has(key);
                }));
                if (!found) {
                    problems.add(key + ": field-specific locale message listed but missing");
                }
            }
        }
        return new Report(general, findings, problems);
    }

    private static void add(List<Finding> findings, String file, List<Finding> found) {
        found.forEach(finding -> findings.add(new Finding(file, finding.line(), finding.term(), finding.text())));
    }
}
