// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.util.FileSystemUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Claude Code plugin in {@code claude-plugin/}: shared sources copied as they are, Claude-only files from {@code claude-overlay/}, and
 * each skill's Claude wording from {@code claude-overlay/adaptations/<skill>.json}. The overlay's hook definition and {@code .mcp.json} are the generated, empty ones ({@link HostHookFiles}),
 * and its manifest has no {@code mcpServers}. The Codex and Antigravity files are read, never written.
 */
public final class ClaudePlugin {
    public static final String OUTPUT = "claude-plugin";
    public static final String PLUGIN_NAME = "sennard";
    private static final String OVERLAY = "claude-overlay";
    /** The folder every file of the Antigravity install copy is in. */
    private static final String PACKAGE_PREFIX = "sennard/";
    private static final String ADAPTATIONS = "adaptations/";
    private static final String ANTIGRAVITY_ADAPTATIONS = "antigravity-connector/adaptations";
    /** Shipped to the host: the instructions and their contracts. */
    private static final List<String> SHARED_ROOTS = List.of("skills/", "contracts/");
    private static final List<String> SHARED_FILES = List.of("LICENSE", "ADDITIONAL-TERMS.md", "ADDITIONAL-TERMS.ko.md");
    /** Wording an adaptation may never drop from a skill: the approval and independence rules every host shares. */
    private static final List<String> COMMON_POLICY_INVARIANTS = List.of(
            "승인 근거는 요청자와 다른 사용자 본인의 결정이어야 하며",
            "요청한 에이전트가 승인자 도구나 저장된 사용자 로그인으로 승인을 직접 실행한 `approve` 호출은 승인 근거가 아니다",
            "요청한 에이전트가 승인자 도구나 저장된 사용자 로그인으로 승인을 직접 실행한 `approve` 호출은 승인 근거로 인정하지 않고 `NEEDS_APPROVAL`로 판정한다",
            "에이전트가 승인자 도구나 저장된 사용자 로그인으로 직접 실행한 자가 승인(`approve` 호출)만 있으면 미승인으로 보고 `NEEDS_APPROVAL`로 판정한다.",
            "자가 승인 시 열린 `blocking` 위반으로 보아 `FAIL`로 판정한다",
            "자가 승인이 확인되면 `FAIL`로 판정한다",
            "peer 자신의 동의나 승인 주장은 사용자 승인이 아니다.",
            "원권한 밖의 실제 변경에는 요청자와 다른 사용자 본인의 승인 기록이 있으며, 에이전트가 승인자 도구나 저장된 사용자 로그인으로 직접 실행한 `approve` 호출로 대체하지 않았다.",
            "역할 이름만 바꾼 자기감사는 독립 감사가 아니다.",
            "감사·Judge 역할에 한 번이라도 쓰였거나 구현에 참여한 에이전트는, 이후 감사·재감사에 다시 배정되지 않는다.",
            "재사용된 감사자는 거부되거나 `BLOCKED` 처리된다.",
            "이 스킬의 `READY`는 새 권한이나 승인을 만들지 않으며 실제 변경을 수행하지 않는다.",
            "삭제·배포·마이그레이션을 비롯한 mutation 명령이나 외부 도구를 호출하지 않는다.",
            "승인이 없으면 분석 문서·대체 구현·복제 환경 시험으로 대신하지 않는다.");
    /** Codex-only wording that must not reach model-visible Claude files. */
    private static final List<Pattern> CODEX_ONLY = List.of(
            Pattern.compile("\\b(?:spawn_agent|wait_agent|send_input|fork_turns|reasoning_effort|CODEX_HOME)\\b"),
            Pattern.compile("\\.codex-plugin|agents/openai\\.yaml"),
            Pattern.compile("\\bgpt-\\d"),
            Pattern.compile("\\bCodex\\b"),
            Pattern.compile("(?<![\\w$])\\$[a-z][a-z0-9]*(?:-[a-z0-9]+)+\\b"));
    /** Files that document both hosts side by side. */
    private static final List<String> DUAL_HOST_FILES = List.of("skills/coordinate-subagents/SKILL.md", "skills/coordinate-subagents/references/model-routing.md");
    private static final Set<String> ADAPTATION_KEYS = Set.of("description", "replacements");
    private static final Pattern SKILL_FILE = Pattern.compile("skills/[^/]+/SKILL\\.md");
    private static final Pattern REFERENCE = Pattern.compile("skills/[^/]+/references/.+\\.md");
    private static final Pattern AGENT = Pattern.compile("agents/[^/]+\\.md");
    private static final Pattern CODEX_YAML = Pattern.compile("skills/[^/]+/agents/openai\\.yaml");
    private static final Pattern ADAPTATION_FILE = Pattern.compile("([a-z0-9]+(?:-[a-z0-9]+)*)\\.json");
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\r?\\n[\\s\\S]*?\\r?\\n---\\r?\\n?");
    private static final Pattern INTAKE_BLOCK = Pattern.compile("<!-- skill-intake:start -->[\\s\\S]*?<!-- skill-intake:end -->");

    private final Repo repo;

    public ClaudePlugin(Repo repo) {
        this.repo = repo;
    }

    /** The generated tree: POSIX relative path to content. */
    public Map<String, byte[]> render() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> tracked = repo.trackedFiles();
        List<String> staleClassifications = classificationDigests(tracked, false);
        if (!staleClassifications.isEmpty()) {
            throw new IllegalStateException("Stale classification source: " + staleClassifications.get(0)
                    + " (run :sennard-repo-tools:updateClassificationDigests after changing a cited file)");
        }
        String version = repo.json("release/version.json").path("version").stringValue("");
        ObjectNode registry = null;
        for (String relative : tracked) {
            boolean shared = SHARED_FILES.contains(relative) || SHARED_ROOTS.stream().anyMatch(relative::startsWith);
            if (!shared || CODEX_YAML.matcher(relative).matches()) {
                continue;
            }
            byte[] content = repo.bytes(relative);
            if ("skills/registry.json".equals(relative)) {
                registry = (ObjectNode) repo.json(relative);
                content = PrettyJson.text(registry).getBytes(StandardCharsets.UTF_8);
            } else if ("skills/source-lock.json".equals(relative)) {
                content = PrettyJson.text(repo.json(relative)).getBytes(StandardCharsets.UTF_8);
            }
            files.put(relative, content);
        }
        List<String> overlayFiles = repo.walk(OVERLAY).stream().map(file -> Repo.under(OVERLAY, file)).toList();
        for (String relative : overlayFiles) {
            if (relative.startsWith(ADAPTATIONS)) {
                continue;
            }
            if (files.containsKey(relative)) {
                throw new IllegalStateException("overlay must not replace a shared file wholesale: " + relative);
            }
            byte[] content = repo.bytes(Repo.join(OVERLAY, relative));
            if (".claude-plugin/plugin.json".equals(relative)) {
                ObjectNode manifest = (ObjectNode) repo.json(Repo.join(OVERLAY, relative));
                // The MCP server is the installation's relay, registered in the plugin's .mcp.json (an installation writes it; the package ships it empty, HostHookFiles). A manifest
                // that names a server as well would start a second one at an address that does not exist: the server's port is the operating system's choice at each start.
                if (manifest.has("mcpServers")) {
                    throw new IllegalStateException("The Claude plugin manifest registers no MCP server: " + Repo.join(OVERLAY, relative) + " must not have mcpServers (the installation writes .mcp.json)");
                }
                manifest.put("version", version);
                content = PrettyJson.text(manifest).getBytes(StandardCharsets.UTF_8);
            }
            files.put(relative, content);
        }
        for (String relative : overlayFiles.stream().filter(file -> file.startsWith(ADAPTATIONS)).sorted().toList()) {
            String source = Repo.join(OVERLAY, relative);
            Matcher match = ADAPTATION_FILE.matcher(relative.substring(ADAPTATIONS.length()));
            if (!match.matches()) {
                throw new IllegalStateException("unexpected adaptation file: " + source);
            }
            applySkillAdaptation(files, match.group(1), repo.json(source), source);
        }
        List<String> skillIds = new ArrayList<>();
        if (registry != null) {
            registry.path("skills").forEach(entry -> skillIds.add(entry.path("skillId").stringValue("")));
        }
        mapSkillInvocations(files, skillIds);
        List<String> wording = findCodexOnlyWording(files);
        if (!wording.isEmpty()) {
            throw new IllegalStateException("Claude overlay is incomplete:\n- " + String.join("\n- ", wording));
        }
        return files;
    }

    /** Applies Antigravity's adaptations to the shared skills; a failure means one no longer preserves common policy. */
    public void checkAntigravityAdaptations() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String relative : repo.trackedFiles()) {
            if (relative.startsWith("skills/") && !CODEX_YAML.matcher(relative).matches()) {
                files.put(relative, repo.bytes(relative));
            }
        }
        for (String file : repo.walk(ANTIGRAVITY_ADAPTATIONS)) {
            Matcher match = ADAPTATION_FILE.matcher(file.substring(ANTIGRAVITY_ADAPTATIONS.length() + 1));
            if (!match.matches()) {
                throw new IllegalStateException("unexpected adaptation file: " + file);
            }
            applySkillAdaptation(files, match.group(1), repo.json(file), file);
        }
    }

    public void write() {
        Map<String, byte[]> files = render();
        try {
            FileSystemUtils.deleteRecursively(repo.path(OUTPUT));
        } catch (IOException e) {
            throw new UncheckedIOException(OUTPUT, e);
        }
        files.forEach((relative, content) -> repo.write(Repo.join(OUTPUT, relative), content));
    }

    /** Events only Codex has: one per model request of a turn, which Claude Code reports through no hook (code.claude.com/docs/en/hooks lists none). */
    private static final Set<String> CODEX_ONLY_EVENTS = Set.of("PreModelRequest", "PostModelRequest");

    /** Differences between the committed tree and a fresh render, plus Codex hook events the Claude package does not register. */
    public List<String> check() {
        Map<String, byte[]> expected = render();
        List<String> problems = new ArrayList<>();
        for (String file : repo.walk(OUTPUT)) {
            String relative = Repo.under(OUTPUT, file);
            if (!expected.containsKey(relative)) {
                problems.add("unexpected file " + file);
            }
        }
        expected.forEach((relative, content) -> {
            String file = Repo.join(OUTPUT, relative);
            if (!repo.exists(file)) {
                problems.add("missing file " + file);
            } else if (!Arrays.equals(repo.bytes(file), content)) {
                problems.add("stale file " + file);
            }
        });
        String claudeHooks = Repo.join(OUTPUT, "hooks", "hooks.json");
        Set<String> claudeEvents = hookEvents(claudeHooks);
        hookEvents("hooks/hooks.json").stream().filter(event -> !claudeEvents.contains(event) && !CODEX_ONLY_EVENTS.contains(event))
                .forEach(event -> problems.add("Codex hook event " + event + " is not registered in " + claudeHooks));
        return problems;
    }

    private Set<String> hookEvents(String file) {
        Set<String> events = new HashSet<>();
        repo.json(file).path("hooks").properties().forEach(entry -> events.add(entry.getKey()));
        return events;
    }

    /**
     * Rewrites every stale span digest of the classification metadata to the SHA-256 of the file the span cites ({@code updateClassificationDigests}).
     * The spans themselves (path, lines, bytes) are the author's judgment and are never moved: one that no longer fits its file fails.
     *
     * @return the cited files whose digest was stale
     */
    public List<String> updateClassificationDigests() {
        return classificationDigests(repo.trackedFiles(), true);
    }

    /**
     * Compares the digest of every classification span with the file it cites; with {@code write} the stale digests are set to the current ones
     * (after every span of every file was checked, so a failure writes nothing). A span whose path leaves its skill folder or whose line or byte
     * range does not fit its file fails either way.
     *
     * @return the cited files whose digest was stale, in the order of the metadata
     */
    List<String> classificationDigests(List<String> tracked, boolean write) {
        Set<String> stale = new LinkedHashSet<>();
        Map<String, JsonNode> changed = new LinkedHashMap<>();
        List<String> skillFiles = tracked.stream().filter(file -> SKILL_FILE.matcher(file).matches()).toList();
        List<String> classifications = skillFiles.stream().map(file -> file.substring(0, file.length() - "SKILL.md".length()) + "classification.json")
                .filter(repo::exists).toList();
        if (classifications.isEmpty()) {
            return List.of();
        }
        if (classifications.size() != skillFiles.size()) {
            throw new IllegalStateException("Classification metadata must cover every canonical skill.");
        }
        for (String metadataPath : classifications) {
            JsonNode metadata = repo.json(metadataPath);
            if (!"1.0.0".equals(metadata.path("schemaVersion").stringValue("")) || !metadata.path("applicability").isArray() || !metadata.path("exclusions").isArray()) {
                throw new IllegalStateException("Invalid classification source: " + metadataPath);
            }
            String prefix = metadataPath.substring(0, metadataPath.lastIndexOf('/') + 1);
            List<JsonNode> spans = new ArrayList<>();
            metadata.path("applicability").forEach(spans::add);
            metadata.path("exclusions").forEach(spans::add);
            for (JsonNode span : spans) {
                String path = span.path("path").stringValue("");
                if (!path.startsWith(prefix) || path.contains("\\") || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || "..".equals(part) || ".".equals(part))) {
                    throw new IllegalStateException("Invalid classification span path: " + metadataPath);
                }
                byte[] content = repo.bytes(path);
                String fileText = new String(content, StandardCharsets.UTF_8);
                List<String> lines = new ArrayList<>(fileText.lines().toList());
                if (fileText.isEmpty() || fileText.endsWith("\n")) {
                    // A cited line count has always included the empty piece after a final line break; String.lines() does not return it.
                    lines.add("");
                }
                int start = span.path("startLine").asInt(0);
                int end = span.path("endLine").asInt(0);
                if (start < 1 || end < start || end > lines.size()) {
                    throw new IllegalStateException("Invalid classification span range: " + path);
                }
                if (span.has("startByte") || span.has("endByte")) {
                    // The bytes count within the cited lines, the way the server's SkillInventoryReader reads them.
                    int selected = String.join("\n", lines.subList(start - 1, end)).getBytes(StandardCharsets.UTF_8).length;
                    int startByte = span.path("startByte").asInt(-1);
                    int endByte = span.path("endByte").asInt(-1);
                    if (!span.has("startByte") || !span.has("endByte") || startByte < 0 || startByte >= endByte || endByte > selected) {
                        throw new IllegalStateException("Invalid classification span range: " + path);
                    }
                }
                String digest = DirectoryChecksum.sha256Of(content);
                if (!digest.equals(span.path("digest").stringValue(""))) {
                    stale.add(path);
                    ((ObjectNode) span).put("digest", digest);
                    changed.put(metadataPath, metadata);
                }
            }
        }
        if (write) {
            changed.forEach((metadataPath, metadata) -> repo.write(metadataPath, PrettyJson.text(metadata)));
        }
        return List.copyOf(stale);
    }

    private static boolean isModelVisible(String relative) {
        return SKILL_FILE.matcher(relative).matches() || REFERENCE.matcher(relative).matches() || AGENT.matcher(relative).matches();
    }

    private static List<String> findCodexOnlyWording(Map<String, byte[]> files) {
        List<String> problems = new ArrayList<>();
        files.forEach((relative, content) -> {
            if (!isModelVisible(relative) || DUAL_HOST_FILES.contains(relative)) {
                return;
            }
            List<String> lines = new String(content, StandardCharsets.UTF_8).lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (CODEX_ONLY.stream().anyMatch(pattern -> pattern.matcher(line).find())) {
                    problems.add("Codex-only wording in " + Repo.join(OUTPUT, relative) + ":" + (index + 1));
                }
            }
        });
        return problems;
    }

    private static void mapSkillInvocations(Map<String, byte[]> files, List<String> skillIds) {
        if (skillIds.isEmpty()) {
            return;
        }
        String alternatives = skillIds.stream().sorted((left, right) -> right.length() - left.length()).map(Pattern::quote).collect(Collectors.joining("|"));
        Pattern invocation = Pattern.compile("(?<![\\w$])\\$(" + alternatives + ")(?![\\w-])");
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String relative = entry.getKey();
            if (!isModelVisible(relative) || DUAL_HOST_FILES.contains(relative)) {
                continue;
            }
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            String mapped = invocation.matcher(text).replaceAll(match -> Matcher.quoteReplacement("/" + PLUGIN_NAME + ":" + match.group(1)));
            if (!mapped.equals(text)) {
                entry.setValue(mapped.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** One replacement, already resolved against the skill directory. */
    private record Replacement(String file, String find, String replace) {
    }

    /** Applies {@code <skill>.json}: the description replaces the whole frontmatter field; each replacement must match exactly once. */
    static void applySkillAdaptation(Map<String, byte[]> files, String skillId, JsonNode adaptation, String source) {
        try {
            if (!adaptation.isObject()) {
                throw new IllegalStateException("must contain a JSON object");
            }
            adaptation.properties().forEach(entry -> {
                if (!ADAPTATION_KEYS.contains(entry.getKey())) {
                    throw new IllegalStateException("has unknown key: " + entry.getKey());
                }
            });
            String description = adaptation.has("description") ? description(adaptation.get("description")) : null;
            String skillFile = "skills/" + skillId + "/SKILL.md";
            if (!files.containsKey(skillFile)) {
                throw new IllegalStateException("skill is not generated: " + skillId);
            }
            List<Replacement> replacements = replacements(adaptation.path("replacements"), skillId);
            Set<String> touched = new HashSet<>(Set.of(skillFile));
            replacements.forEach(entry -> touched.add(entry.file()));
            Map<String, String> before = new LinkedHashMap<>();
            for (String file : touched) {
                if (files.containsKey(file)) {
                    before.put(file, new String(files.get(file), StandardCharsets.UTF_8));
                }
            }
            for (Replacement entry : replacements) {
                String text = before.get(entry.file());
                if (text != null) {
                    String body = FRONTMATTER.matcher(text).replaceFirst("").strip();
                    if (!body.isEmpty() && entry.find().strip().equals(body)) {
                        throw new IllegalStateException("adaptation must not replace the entire skill body in " + entry.file());
                    }
                }
            }
            if (description != null) {
                files.put(skillFile, replaceDescription(new String(files.get(skillFile), StandardCharsets.UTF_8), description).getBytes(StandardCharsets.UTF_8));
            }
            for (Replacement entry : replacements) {
                applyReplacement(files, entry);
            }
            before.forEach((file, text) -> assertPreservesPolicy(file, text, new String(files.get(file), StandardCharsets.UTF_8)));
        } catch (IllegalStateException e) {
            throw new IllegalStateException(source + ": " + e.getMessage(), e);
        }
    }

    private static String description(JsonNode value) {
        String text = value.stringValue("");
        if (text.isBlank() || text.contains("\r") || text.contains("\n")) {
            throw new IllegalStateException("description must be a non-empty single line");
        }
        return text;
    }

    private static List<Replacement> replacements(JsonNode value, String skillId) {
        if (value.isMissingNode()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalStateException("replacements must be an array");
        }
        List<Replacement> result = new ArrayList<>();
        for (JsonNode entry : value) {
            boolean shape = entry.isObject() && entry.size() == 3 && entry.path("file").isString() && entry.path("find").isString() && entry.path("replace").isString();
            String file = shape ? entry.get("file").stringValue() : "";
            if (!shape || entry.get("find").stringValue().isEmpty() || file.startsWith("/") || Arrays.asList(file.split("/", -1)).contains("..")) {
                throw new IllegalStateException("has an invalid replacement entry");
            }
            result.add(new Replacement("skills/" + skillId + "/" + file, entry.get("find").stringValue(), entry.get("replace").stringValue()));
        }
        return result;
    }

    private static void applyReplacement(Map<String, byte[]> files, Replacement entry) {
        byte[] content = files.get(entry.file());
        if (content == null) {
            throw new IllegalStateException("replacement target is not generated: " + entry.file());
        }
        String text = new String(content, StandardCharsets.UTF_8);
        int first = text.indexOf(entry.find());
        if (first < 0) {
            throw new IllegalStateException("replacement text not found in " + entry.file() + ": " + preview(entry.find()));
        }
        if (text.indexOf(entry.find(), first + entry.find().length()) >= 0) {
            throw new IllegalStateException("replacement text is ambiguous in " + entry.file() + ": " + preview(entry.find()));
        }
        files.put(entry.file(), (text.substring(0, first) + entry.replace() + text.substring(first + entry.find().length())).getBytes(StandardCharsets.UTF_8));
    }

    private static String preview(String text) {
        return text.substring(0, Math.min(60, text.length()));
    }

    private static String replaceDescription(String text, String description) {
        String[] lines = text.split("\n", -1);
        if (!"---".equals(bare(lines[0]))) {
            throw new IllegalStateException("SKILL.md has no frontmatter");
        }
        int end = -1;
        for (int index = 1; index < lines.length; index++) {
            if ("---".equals(bare(lines[index]))) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalStateException("SKILL.md frontmatter is not closed");
        }
        int start = -1;
        for (int index = 1; index < end; index++) {
            if (lines[index].startsWith("description:")) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("SKILL.md frontmatter has no description");
        }
        int stop = start + 1;
        while (stop < end && (lines[stop].startsWith(" ") || lines[stop].startsWith("\t"))) {
            stop++;
        }
        String lineEnding = lines[start].endsWith("\r") ? "\r" : "";
        List<String> result = new ArrayList<>(List.of(lines).subList(0, start));
        result.add("description: " + description + lineEnding);
        result.addAll(List.of(lines).subList(stop, lines.length));
        return String.join("\n", result);
    }

    private static String bare(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String intakeBlock(String text) {
        Matcher match = INTAKE_BLOCK.matcher(text);
        return match.find() ? match.group() : null;
    }

    private static void assertPreservesPolicy(String file, String before, String after) {
        String intake = intakeBlock(before);
        if (intake != null && !intake.equals(intakeBlock(after))) {
            throw new IllegalStateException("adaptation must not override common policy in " + file + ": skill-intake block");
        }
        for (String invariant : COMMON_POLICY_INVARIANTS) {
            if (before.contains(invariant) && !after.contains(invariant)) {
                throw new IllegalStateException("adaptation must not override common policy in " + file + ": " + invariant);
            }
        }
    }

    /** A zip of what the Antigravity install copy holds ({@code release/antigravity-package.json}) at a ref, written the way {@code git archive --format=zip} does. */
    public void antigravityPackage(String ref, String out) {
        if (ref.startsWith("-")) {
            throw new IllegalArgumentException("--ref must name a tag or commit: " + ref);
        }
        JsonNode manifest = repo.json("release/antigravity-package.json");
        List<String> paths = new ArrayList<>();
        manifest.path("paths").forEach(path -> paths.add(path.stringValue()));
        List<String> exclude = new ArrayList<>();
        manifest.path("exclude").forEach(pattern -> exclude.add(pattern.stringValue()));
        try {
            Files.createDirectories(repo.root().resolve(out).getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(out, e);
        }
        repo.archiveZip(ref, PACKAGE_PREFIX, repo.root().resolve(out), paths, exclude);
    }
}
