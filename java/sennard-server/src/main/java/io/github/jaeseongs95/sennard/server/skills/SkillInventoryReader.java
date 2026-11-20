// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Discovery;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DiscoveryScope;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DiscoveryStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Inventory;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Issue;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.IssueCode;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ObservedInventory;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Phase;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skill;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SourceRef;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SourceSpan;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.JacksonYAMLParseException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads the installed skills from their own files on every call, so no cached descriptor can outlive its source bytes. A skill that cannot be read is
 * an issue, never a silently missing candidate. The server's own skill tree is the source; the projection branch of the Claude plugin package was not
 * carried over, because that packaging is replaced by the server install.
 */
@Component
@EnableConfigurationProperties(SennardProperties.class)
public class SkillInventoryReader {
    private static final Logger LOG = LoggerFactory.getLogger(SkillInventoryReader.class);
    private static final String REGISTRY = "skills/registry.json";
    /** The members of a skill that the registry, or else the skill's own version, stands for in the source map. */
    private static final List<String> REGISTRY_MEMBERS = List.of("version", "enabled", "capabilities", "phases");
    private static final List<String> VERSION_MEMBER = List.of("version");
    /** U+FEFF. The JDK's {@code Charset} page describes byte-order-mark handling only for the UTF-16 and UTF-32 charsets, none for UTF-8, so a UTF-8 file's mark is removed here. */
    private static final String BOM = "﻿";
    /**
     * The YAML reader of the frontmatter, built once; it holds only configuration and is immutable. It is not derived from the injected {@code JsonMapper}:
     * a mapper's token format is its factory, and {@code JsonMapper.rebuild()} keeps the JSON one. {@code STRICT_DUPLICATE_DETECTION} makes the parser itself
     * refuse a key that occurs twice in one mapping.
     */
    private static final YAMLMapper FRONTMATTER = YAMLMapper.shared().rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    /** The start of the message with which the parser refuses a repeated key (jackson-core JsonReadContext); it has no exception type of its own. */
    private static final String DUPLICATE_PROPERTY = "Duplicate Object property";
    /** The names under which an issue reports the file it concerns. */
    private static final String SKILL_FILE = "SKILL.md";
    private static final String CLASSIFICATION_FILE = "classification.json";

    /** The registry file. Members it has beyond these belong to the registry's own contract and are not read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistryFile(String schemaVersion, List<RegistryEntry> skills) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistryEntry(String skillId, String version, Boolean enabled, String path, List<RegistryProvider> providers, List<String> dependencies) {
        /** A descriptor the inventory can use: an identity, a version, a switch and at least one provider. */
        boolean wellFormed() {
            return skillId != null && !skillId.isEmpty() && version != null && enabled != null && providers != null && !providers.isEmpty() && !providers.contains(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistryProvider(List<String> capabilities, String phase, int phaseOrder, List<String> requiredInputArtifacts, List<String> producedArtifacts,
            JsonNode gate /* the registry's own gate document: the inventory only forwards it */, JsonNode inputBindings /* likewise the registry's own binding list */,
            List<String> preconditions) {
        String phaseName() {
            return phase == null ? "" : phase;
        }
    }

    /** What a skill says about itself beside its SKILL.md. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Classification(String schemaVersion, String taxonomyRevision, SkillCategory category, List<String> actions, List<String> targets, List<String> constraints, List<String> dependencies,
            List<String> capabilities, List<Citation> applicability, List<Citation> exclusions) {
    }

    /** The one member of a skill's {@code classification.json} the listings of skills read: the group it belongs to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CategoryOnly(SkillCategory category) {
    }

    /** A cited range of one source file; the bytes are counted within the cited lines. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Citation(String path, String digest, Integer startLine, Integer endLine, Integer startByte, Integer endByte) {
    }

    /** A registry precondition as an applicability line: the phase and capabilities it belongs to, and the condition. */
    private record Precondition(String phase, List<String> capabilities, String precondition) {
    }

    /** What the digest of one read of the tree covers. {@code sources} are pairs of a path and the digest of its bytes. */
    private record TreeDigest(List<Skill> skills, String taxonomyRevision, List<Issue> issues, List<List<String>> sources) {
    }

    private record InventoryDigest(List<Skill> skills, List<Issue> issues, String taxonomyRevision, List<String> sourceInventoryDigests) {
    }

    private record Frontmatter(String name, String description, String version, int descriptionStart, int descriptionEnd) {
    }

    /** The members of the frontmatter that the inventory reads; any other member (license, the rest of metadata) is not its business. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontmatterDocument(String name, Description description, String version, FrontmatterMetadata metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontmatterMetadata(String version) {
    }

    /** The description and the lines of SKILL.md that it covers (the first and the last, counted from 1 over the whole file, the opening {@code ---} being line 1). */
    @JsonDeserialize(using = DescriptionReader.class)
    record Description(String text, int startLine, int endLine) {
        /** A description key that has no value. */
        static final Description EMPTY = new Description("", 0, 0);
    }

    /**
     * Reads the description from the YAML parser and takes its lines from the parser's own locations: the token starts on the line of the scalar (for a
     * block scalar, of its {@code >} or {@code |}), and the end of the token is where the scalar ends. A flow scalar ends on the line it started on; a
     * block scalar ends at the beginning of the next content line, so its last line is the line before that one, and its text is trimmed.
     * The head the parser is given starts at line 2 of the file, so every line it reports is one less than the file's.
     */
    static final class DescriptionReader extends ValueDeserializer<Description> {
        @Override
        public Description deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.currentToken().isScalarValue()) {
                return context.reportInputMismatch(Description.class, "The description must be a scalar");
            }
            int line = parser.currentTokenLocation().getLineNr();
            int stop = parser.currentLocation().getLineNr();
            String text = parser.getString();
            return stop > line ? new Description(text.trim(), line + 1, stop) : new Description(text, line + 1, line + 1);
        }

        @Override
        public Description getNullValue(DeserializationContext context) {
            return Description.EMPTY;
        }

        /** No key at all is another refusal than a key without a value. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /**
     * A skill the loader refuses, with the issue code it reports. It ends the multi-step check of one skill at the first step that fails; the
     * failures that arrive as exceptions anyway (a file that cannot be read, a document that does not bind) are reported through the same issue.
     * It is kept as an exception, and carries no stack, because the refusals come from four levels of helpers ({@code read}, {@code strings}, {@code spans},
     * {@code frontmatter}) and from inside argument expressions of the one {@code Skill} constructor call, and the first failing step decides the issue code, so
     * returned values would re-order the checks. It is caught in one place ({@code load}), which records the issue and logs the refusal with its cause.
     */
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IssueCode code;

        Refusal(IssueCode code) {
            this(code, null);
        }

        Refusal(IssueCode code, Throwable cause) {
            super(code.name(), cause, false, false);
            this.code = code;
        }
    }

    private final Path root;
    private final JsonMapper mapper;

    public SkillInventoryReader(SennardProperties properties, JsonMapper mapper) {
        this.root = properties.skillsTree();
        this.mapper = mapper;
    }

    public Path root() {
        return root;
    }

    private String digestOf(Object value) {
        return Checks.digest(mapper, value);
    }

    /**
     * The frontmatter of a SKILL.md. The {@code ---} lines that fence it are Markdown's, not YAML's, so they are found here; the text between them is YAML
     * and the YAML parser reads it. A repeated key or a YAML syntax error refuses the skill.
     */
    private static Frontmatter frontmatter(String text) {
        List<String> lines = (text.startsWith(BOM) ? text.substring(1) : text).lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0))) {
            throw new Refusal(IssueCode.INVALID_FRONTMATTER);
        }
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        if (end < 1) {
            throw new Refusal(IssueCode.INVALID_FRONTMATTER);
        }
        FrontmatterDocument document = parse(String.join("\n", lines.subList(1, end)) + "\n");
        if (document == null || document.description() == null) {
            throw new Refusal(IssueCode.MISSING_DESCRIPTION);
        }
        Description description = document.description();
        if (document.name() == null || document.name().isEmpty() || description.text().isEmpty()) {
            throw new Refusal(IssueCode.MISSING_FRONTMATTER_FIELD);
        }
        String metadataVersion = document.metadata() == null ? null : document.metadata().version();
        if (metadataVersion != null && document.version() != null && !Objects.equals(metadataVersion, document.version())) {
            throw new Refusal(IssueCode.SOURCE_CONFLICT);
        }
        return new Frontmatter(document.name(), description.text(), metadataVersion != null ? metadataVersion : document.version(), description.startLine(), description.endLine());
    }

    /** The YAML head bound to its record; {@code null} when it holds no document at all. */
    private static FrontmatterDocument parse(String yaml) {
        try (JsonParser parser = FRONTMATTER.createParser(yaml)) {
            return parser.nextToken() == null ? null : FRONTMATTER.readValue(parser, FrontmatterDocument.class);
        } catch (JacksonException failure) {
            throw new Refusal(repeatedKey(failure) ? IssueCode.DUPLICATE_FRONTMATTER_FIELD : IssueCode.INVALID_FRONTMATTER, failure);
        }
    }

    /** Whether the parser refused the head because a key occurs twice (the binding may wrap the parser's exception in a binding exception). */
    private static boolean repeatedKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonYAMLParseException) {
                return false;
            }
            if (cause instanceof StreamReadException read && read.getOriginalMessage() != null && read.getOriginalMessage().startsWith(DUPLICATE_PROPERTY)) {
                return true;
            }
        }
        return false;
    }

    /** A list of non-empty strings; a missing list, an empty one where a member is required, or an empty string refuses the skill. */
    private static List<String> strings(List<String> list, boolean nonEmpty) {
        if (list == null || (nonEmpty && list.isEmpty()) || list.stream().anyMatch(item -> item == null || item.isEmpty())) {
            throw new Refusal(IssueCode.MISSING_OR_INVALID_METADATA);
        }
        return List.copyOf(list);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static SourceSpan span(String field, String path, String digest) {
        return new SourceSpan(field, path, null, null, digest);
    }

    /** One read of a tree: every source's bytes are digested as they are read, and a file that changes in between fails the load. */
    private final class Read {
        final Path tree;
        final Map<String, String> sources = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        final Map<String, RegistryEntry> registry = new HashMap<>();
        final Set<String> duplicates = new HashSet<>();
        final Set<String> names = new HashSet<>();
        final Set<String> revisions = new TreeSet<>();

        Read(Path tree) {
            this.tree = tree;
        }

        byte[] read(String relative) throws IOException {
            // The paths of the registry and of the citations are written with '/', whatever the platform.
            if (relative.contains("\\")) {
                throw new Refusal(IssueCode.SOURCE_PATH_INVALID);
            }
            Path resolved = tree.resolve(relative).normalize();
            if (!resolved.startsWith(tree)) {
                throw new Refusal(IssueCode.SOURCE_PATH_INVALID);
            }
            Path actual = resolved.toRealPath();
            if (!actual.startsWith(tree)) {
                throw new Refusal(IssueCode.SOURCE_PATH_INVALID);
            }
            byte[] bytes = Files.readAllBytes(actual);
            String digest = Checks.hash(bytes);
            String prior = sources.putIfAbsent(relative, digest);
            if (prior != null && !Objects.equals(prior, digest)) {
                throw new Refusal(IssueCode.SOURCE_CHANGED_DURING_LOAD);
            }
            return bytes;
        }

        private void loadRegistry() {
            try {
                RegistryFile file = mapper.readValue(read(REGISTRY), RegistryFile.class);
                if (file == null || !"2.0.0".equals(file.schemaVersion()) || file.skills() == null) {
                    issues.add(new Issue(null, IssueCode.MISSING_OR_INVALID_REGISTRY, "registry"));
                    return;
                }
                for (RegistryEntry entry : file.skills()) {
                    if (entry == null || !entry.wellFormed()) {
                        issues.add(new Issue(null, IssueCode.INVALID_REGISTRY_DESCRIPTOR, "registry"));
                        continue;
                    }
                    if (registry.containsKey(entry.skillId())) {
                        duplicates.add(entry.skillId());
                        issues.add(new Issue(entry.skillId(), IssueCode.DUPLICATE_ID, "registry.skillId"));
                    }
                    registry.put(entry.skillId(), entry);
                }
            } catch (IOException | JacksonException | Refusal failed) {
                LOG.warn("The skill registry could not be read", failed);
                issues.add(new Issue(null, IssueCode.MISSING_OR_INVALID_REGISTRY, "registry"));
            }
        }

        /** The group of each skill folder, from its {@code classification.json}; a folder whose file cannot be read or names no known group is left out (the inventory reports it as an issue). */
        Map<String, SkillCategory> categories() throws IOException {
            Map<String, SkillCategory> found = new LinkedHashMap<>();
            try (Stream<Path> children = Files.list(tree.resolve("skills"))) {
                for (String directory : children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList()) {
                    try {
                        SkillCategory category = mapper.readValue(read(new Loading(directory).metadataPath()), CategoryOnly.class).category();
                        if (category != null) {
                            found.put(directory, category);
                        }
                    } catch (IOException | JacksonException | Refusal failed) {
                        LOG.warn("The group of the skill {} could not be read", directory, failed);
                    }
                }
            }
            return found;
        }

        Inventory load() throws IOException {
            loadRegistry();
            List<String> directories;
            try (Stream<Path> children = Files.list(tree.resolve("skills"))) {
                directories = children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
            }
            List<Skill> skills = new ArrayList<>();
            for (String directory : directories) {
                Loading loading = new Loading(directory);
                try {
                    Skill skill = loading.skill();
                    if (skill != null) {
                        skills.add(skill);
                    }
                } catch (Refusal refusal) {
                    LOG.debug("The skill {} is refused at {}", directory, loading.field, refusal);
                    issues.add(new Issue(directory, refusal.code, loading.field));
                } catch (IOException | JacksonException failure) {
                    LOG.warn("The skill {} could not be read", directory, failure);
                    issues.add(new Issue(directory, IssueCode.MISSING_OR_INVALID_METADATA, loading.field));
                }
            }
            for (String id : registry.keySet()) {
                if (!names.contains(id)) {
                    issues.add(new Issue(id, IssueCode.REGISTRY_SKILL_MISSING, SKILL_FILE));
                }
            }
            List<Skill> valid = skills.stream().filter(skill -> !duplicates.contains(skill.skillId())).toList();
            dependencyIssues(valid, issues);
            if (revisions.size() != 1) {
                issues.add(new Issue(null, IssueCode.TAXONOMY_REVISION_CONFLICT, "taxonomyRevision"));
            }
            String taxonomy = revisions.size() == 1 ? revisions.iterator().next() : "invalid";
            List<List<String>> ordered = sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> List.of(entry.getKey(), entry.getValue())).toList();
            return new Inventory(valid, digestOf(new TreeDigest(valid, taxonomy, issues, ordered)), taxonomy, issues);
        }

        /** The check of one skill. {@code field} is the member of the skill the check has reached, which is what an issue reports. */
        private final class Loading {
            private final String directory;
            private final String base;
            private final Path folder;
            private final Map<String, List<String>> lines = new HashMap<>();
            String field = SKILL_FILE;

            Loading(String directory) {
                this.directory = directory;
                this.base = "skills/" + directory;
                this.folder = Path.of("skills", directory);
            }

            /** Whether a cited path names something below this skill's folder. */
            private boolean inside(String path) {
                try {
                    Path cited = Path.of(path);
                    return cited.startsWith(folder) && cited.getNameCount() > folder.getNameCount();
                } catch (InvalidPathException invalid) {
                    throw new Refusal(IssueCode.SOURCE_PATH_INVALID, invalid);
                }
            }

            /** The skill's classification file, relative to the tree, written with {@code '/'} whatever the platform. */
            String metadataPath() {
                return base + "/classification.json";
            }

            Skill skill() throws IOException {
                byte[] skillBytes = read(base + "/SKILL.md");
                Frontmatter head = frontmatter(new String(skillBytes, StandardCharsets.UTF_8));
                if (!names.add(head.name())) {
                    duplicates.add(head.name());
                    issues.add(new Issue(head.name(), IssueCode.DUPLICATE_ID, "frontmatter.name"));
                }
                if (!Objects.equals(head.name(), directory)) {
                    issues.add(new Issue(directory, IssueCode.SOURCE_CONFLICT, "frontmatter.name"));
                    return null;
                }
                RegistryEntry entry = registry.get(directory);
                if (entry != null && (!Objects.equals("./" + directory, entry.path()) || (head.version() != null && !Objects.equals(head.version(), entry.version())))) {
                    issues.add(new Issue(directory, IssueCode.SOURCE_CONFLICT, "registry.path/version"));
                    return null;
                }
                field = CLASSIFICATION_FILE;
                String metadataPath = metadataPath();
                Classification metadata = mapper.readValue(read(metadataPath), Classification.class);
                if (metadata == null || !"1.0.0".equals(metadata.schemaVersion()) || metadata.taxonomyRevision() == null || metadata.taxonomyRevision().isEmpty() || metadata.category() == null) {
                    throw new Refusal(IssueCode.MISSING_OR_INVALID_METADATA);
                }
                revisions.add(metadata.taxonomyRevision());
                String skillDigest = sources.get(base + "/SKILL.md");
                String registryDigest = sources.get(REGISTRY);
                String metadataDigest = sources.get(metadataPath);
                String versionSource = entry != null ? REGISTRY : base + "/SKILL.md";
                String versionDigest = entry != null ? registryDigest : skillDigest;
                List<SourceSpan> map = new ArrayList<>(List.of(span("skillId", versionSource, versionDigest),
                        new SourceSpan("description", base + "/SKILL.md", head.descriptionStart(), head.descriptionEnd(), skillDigest)));
                for (String item : entry != null ? REGISTRY_MEMBERS : VERSION_MEMBER) {
                    map.add(span(item, versionSource, versionDigest));
                }
                for (String item : List.of("actions", "targets", "constraints")) {
                    map.add(span(item, metadataPath, metadataDigest));
                }
                boolean registryDependencies = entry != null && entry.dependencies() != null;
                map.add(registryDependencies ? span("dependencies", REGISTRY, registryDigest) : span("dependencies", metadataPath, metadataDigest));
                map.add(span("taxonomyRevision", metadataPath, metadataDigest));
                if (entry == null) {
                    map.add(span("capabilities", metadataPath, metadataDigest));
                }
                Map<String, String> refs = new LinkedHashMap<>();
                refs.put(base + "/SKILL.md", skillDigest);
                refs.put(metadataPath, metadataDigest);
                if (entry != null) {
                    refs.put(REGISTRY, registryDigest);
                }
                Set<String> applicability = new LinkedHashSet<>(spans(metadata.applicability(), "applicability", refs, map));
                List<String> exclusions = spans(metadata.exclusions(), "exclusions", refs, map);
                boolean preconditions = false;
                if (entry != null) {
                    for (RegistryProvider provider : entry.providers()) {
                        for (String condition : orEmpty(provider.preconditions())) {
                            preconditions = true;
                            applicability.add(mapper.writeValueAsString(new Precondition(provider.phaseName(), provider.capabilities(), condition)));
                        }
                    }
                }
                if (preconditions) {
                    map.add(span("applicability", REGISTRY, registryDigest));
                }
                List<String> declared = metadata.capabilities() == null ? null : strings(metadata.capabilities(), false);
                Set<String> provided = new LinkedHashSet<>();
                if (entry != null) {
                    entry.providers().forEach(provider -> provided.addAll(orEmpty(provider.capabilities())));
                }
                List<String> capabilities = entry != null ? List.copyOf(provided) : declared;
                if (capabilities == null || capabilities.isEmpty() || (entry == null && head.version() == null)) {
                    throw new Refusal(IssueCode.MISSING_CAPABILITY_OR_VERSION);
                }
                if (entry != null && declared != null && !capabilities.equals(declared)) {
                    throw new Refusal(IssueCode.SOURCE_CONFLICT);
                }
                List<String> dependencies = strings(metadata.dependencies(), false);
                if (registryDependencies) {
                    List<String> fromRegistry = strings(entry.dependencies(), false);
                    if (!fromRegistry.equals(dependencies)) {
                        throw new Refusal(IssueCode.SOURCE_CONFLICT);
                    }
                    dependencies = fromRegistry;
                }
                List<Phase> phases = new ArrayList<>();
                if (entry != null) {
                    for (RegistryProvider provider : entry.providers()) {
                        List<String> providerCapabilities = orEmpty(provider.capabilities());
                        if (providerCapabilities.isEmpty()) {
                            continue;
                        }
                        List<String> required = strings(provider.requiredInputArtifacts(), false);
                        List<String> produced = strings(provider.producedArtifacts(), false);
                        for (String capability : providerCapabilities) {
                            phases.add(new Phase(capability, provider.phaseName(), provider.phaseOrder(), required, produced, provider.gate(), provider.inputBindings()));
                        }
                    }
                }
                List<SourceRef> sourceRefs = refs.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(ref -> new SourceRef(ref.getKey(), ref.getValue())).toList();
                return new Skill(directory, entry != null ? entry.version() : head.version(), head.description(), entry == null || entry.enabled(), true, true, capabilities,
                        strings(metadata.actions(), true), strings(metadata.targets(), true), strings(metadata.constraints(), false), List.copyOf(applicability), exclusions, dependencies, phases,
                        sourceRefs, List.copyOf(map));
            }

            /** The texts of the cited ranges, each once, in the order cited. */
            private List<String> spans(List<Citation> citations, String name, Map<String, String> refs, List<SourceSpan> map) throws IOException {
                if (citations == null || citations.isEmpty()) {
                    throw new Refusal(IssueCode.MISSING_OR_INVALID_METADATA);
                }
                field = name;
                Set<String> result = new LinkedHashSet<>();
                for (Citation source : citations) {
                    String path = source == null || source.path() == null ? "" : source.path();
                    if (!inside(path)) {
                        throw new Refusal(IssueCode.SOURCE_PATH_INVALID);
                    }
                    byte[] bytes = read(path);
                    String digest = sources.get(path);
                    if (!Objects.equals(digest, source.digest())) {
                        throw new Refusal(IssueCode.STALE_SOURCE);
                    }
                    // The same file cited again has the same bytes (a change between two reads fails the load), so its lines are cut once. The bytes are the ones that were
                    // digested; Files.readString would read the file a second time, and that read could differ from the digested one.
                    List<String> fileLines = lines.computeIfAbsent(path, key -> new String(bytes, StandardCharsets.UTF_8).lines().toList());
                    int startLine = source.startLine() == null ? 0 : source.startLine();
                    int endLine = source.endLine() == null ? 0 : source.endLine();
                    if (startLine < 1 || endLine < startLine || endLine > fileLines.size()) {
                        throw new Refusal(IssueCode.SOURCE_RANGE_INVALID);
                    }
                    String cited = String.join("\n", fileLines.subList(startLine - 1, endLine));
                    String value;
                    if (source.startByte() != null || source.endByte() != null) {
                        byte[] selected = cited.getBytes(StandardCharsets.UTF_8);
                        if (source.startByte() == null || source.endByte() == null || source.startByte() < 0 || source.startByte() >= source.endByte() || source.endByte() > selected.length) {
                            throw new Refusal(IssueCode.SOURCE_RANGE_INVALID);
                        }
                        try {
                            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(selected, source.startByte(), source.endByte() - source.startByte())).toString().strip();
                        } catch (CharacterCodingException invalid) {
                            throw new Refusal(IssueCode.SOURCE_RANGE_INVALID, invalid);
                        }
                    } else {
                        value = cited.strip();
                    }
                    if (value.isEmpty()) {
                        throw new Refusal(IssueCode.MISSING_APPLICABILITY_OR_EXCLUSION);
                    }
                    result.add(value);
                    refs.put(path, digest);
                    map.add(new SourceSpan(name, path, startLine, endLine, digest));
                }
                return new ArrayList<>(result);
            }
        }
    }

    private static void dependencyIssues(List<Skill> skills, List<Issue> issues) {
        Set<String> available = skills.stream().filter(skill -> skill.enabled() && skill.installed() && skill.hostSupported()).map(Skill::skillId).collect(Collectors.toSet());
        for (Skill skill : skills) {
            for (String dependency : skill.dependencies()) {
                if (!available.contains(dependency)) {
                    issues.add(new Issue(skill.skillId(), IssueCode.DEPENDENCY_UNAVAILABLE, "dependencies"));
                }
            }
        }
    }

    /** The local tree as one inventory. The digest covers the digest of the read of the tree, so it changes when any source byte does. */
    Inventory load() throws IOException {
        Inventory local = new Read(root.toRealPath()).load();
        List<Issue> issues = new ArrayList<>(local.issues().stream().filter(issue -> issue.code() != IssueCode.DEPENDENCY_UNAVAILABLE).toList());
        dependencyIssues(local.skills(), issues);
        return new Inventory(local.skills(), digestOf(new InventoryDigest(local.skills(), issues, local.taxonomyRevision(), List.of(local.inventoryDigest()))), local.taxonomyRevision(), issues);
    }

    /** The group of each skill of the local tree, by the skill's folder name: what the admin page uses to list the skills in two groups. */
    public Map<String, SkillCategory> categories() throws IOException {
        return new Read(root.toRealPath()).categories();
    }

    /**
     * The inventory of the local tree, which is the suite this server ships: no host CLI is asked what is installed, so the tree is the one source and the
     * discovery says so ({@code local-tree}), complete when the tree read with no issue.
     */
    public ObservedInventory observed() throws IOException {
        Inventory inventory = load();
        return new ObservedInventory(inventory, new Discovery(inventory.issues().isEmpty() ? DiscoveryStatus.COMPLETE : DiscoveryStatus.INCOMPLETE, DiscoveryScope.LOCAL_TREE));
    }
}
