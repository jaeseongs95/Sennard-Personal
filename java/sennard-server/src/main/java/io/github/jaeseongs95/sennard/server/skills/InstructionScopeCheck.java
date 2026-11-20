// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import io.github.jaeseongs95.sennard.service.files.FileFacts.EntryType;
import io.github.jaeseongs95.sennard.service.files.FileFacts.PathState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * instruction-scope-resolver: for each target path, the instruction files on its chain from each authorised root. Which file names count
 * is the adapter's {@code instructionFiles} argument; the request document cannot choose them, and without an adapter the common rule holds.
 */
@Component
public class InstructionScopeCheck implements SkillCheck<InstructionScopeCheck.Command> {
    private static final String M = "skillTools.instructionScope.";
    private static final String REQUEST = "skills/instruction-scope-resolver/contracts/instruction-scope-request.v1.schema.json";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int MAX_FILE_DESCRIPTORS = 8;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.md");
    /** White space (as {@code Character.isWhitespace} reads it) or the byte order mark U+FEFF, any number of them. */
    private static final Pattern BLANK = Pattern.compile("[\\p{javaWhitespace}\\uFEFF]*");

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        RESOLVE("resolve");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    enum Kind {
        AGENTS("agents"), OVERRIDE("override");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }
    }

    enum AnalysisStatus {
        REQUIRED("required"), COMPLETE("complete");

        private final String label;

        AnalysisStatus(String label) {
            this.label = label;
        }

        @JsonValue
        String label() {
            return label;
        }
    }

    enum Verdict {
        ANALYSIS_REQUIRED, PASS, NEEDS_INPUT, BLOCKED
    }

    /** An instruction file name the adapter lists, with what it is: exactly these two members, in the adapter's order. */
    record FileDescriptor(String name, Kind kind) {
    }

    private static final List<FileDescriptor> DEFAULT_FILES = List.of(new FileDescriptor("AGENTS.override.md", Kind.OVERRIDE), new FileDescriptor("AGENTS.md", Kind.AGENTS));

    /** The request; {@code schemaVersion} and {@code externalPolicyRefs} are the schema's (the check reads neither). */
    @JsonIgnoreProperties({"schemaVersion", "externalPolicyRefs"})
    record Request(String workspaceRoot, List<RootRequest> instructionRoots, List<TargetRequest> targets) {
    }

    record RootRequest(String path, int precedence, boolean authorized) {
    }

    record TargetRequest(String path, boolean mayNotExist) {
    }

    record InstructionFile(String path, Kind kind, boolean replacesSiblingAgents, long size, String sha256, int precedence) {
    }

    record ActiveRule(String ruleId, String summary, String sourcePath, String sourceLocator) {
    }

    record OverriddenRule(String ruleId, String broaderSource, String narrowerSource, String reason) {
    }

    record TargetResolution(String requestedPath, String resolvedPath, boolean exists, List<InstructionFile> instructionChain, List<ActiveRule> activeRules, List<OverriddenRule> overriddenRules,
            List<String> unresolvedConflicts) {
    }

    record Resolution(String schemaVersion, String workspaceRoot, List<InstructionFile> instructionFileManifest, List<TargetResolution> targets, List<String> findings, AnalysisStatus analysisStatus, Verdict verdict) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    /** The adapter's file list is a closed shape (no other member is allowed), so it is bound by a mapper that refuses unknown members. */
    private final JsonMapper strictMapper;

    public InstructionScopeCheck(SkillSchemas schemas, Messages messages) {
        this.schemas = schemas;
        this.messages = messages;
        this.strictMapper = schemas.mapper().rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @Override
    public String skill() {
        return "instruction-scope-resolver";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return SkillCheckResult.ok(resolve(args.document("input"), args.has("instructionFiles") ? args.document("instructionFiles") : null, args.files()));
    }

    private List<FileDescriptor> instructionFiles(JsonNode supplied) {
        if (supplied == null) {
            return DEFAULT_FILES;
        }
        FileDescriptor[] descriptors;
        try {
            descriptors = strictMapper.treeToValue(supplied, FileDescriptor[].class);
        } catch (JacksonException notDescriptors) {
            throw new InvalidInputException(InputCode.INSTRUCTION_SCOPE_INVALID_FILE_DESCRIPTOR, Map.of(), Map.of(), notDescriptors);
        }
        need(descriptors != null && descriptors.length > 0 && descriptors.length <= MAX_FILE_DESCRIPTORS, InputCode.INSTRUCTION_SCOPE_INVALID_FILE_DESCRIPTOR);
        // Two names that differ only in case would be one file on a case-insensitive file system.
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (FileDescriptor descriptor : descriptors) {
            need(descriptor != null && descriptor.name() != null && descriptor.kind() != null && FILE_NAME.matcher(descriptor.name()).matches() && seen.add(descriptor.name()), InputCode.INSTRUCTION_SCOPE_INVALID_FILE_DESCRIPTOR);
        }
        return List.of(descriptors);
    }

    private static InvalidInputException refusal(InputCode code, Object... details) {
        return new InvalidInputException(code, Map.of(), SennardException.args(details));
    }

    private static boolean isDirectory(FileFacts files, Path path) {
        return files.stat(path.toString()).map(state -> state.type() == EntryType.DIRECTORY).orElse(false);
    }

    /** Where a target lies: the path it names, the real path it resolves to (through its nearest existing ancestor when it does not exist yet), and the directory whose instructions apply. */
    private record Resolved(String requestedPath, Path path, boolean exists, Path directory) {
    }

    private static Resolved canonicalTarget(Path workspaceRoot, TargetRequest target, FileFacts files) {
        Deque<Path> missing = new ArrayDeque<>();
        PathState existing = nearestExisting(workspaceRoot.resolve(target.path()).normalize(), missing, target, files);
        Path resolved = Path.of(existing.realPath());
        for (Path part : missing) {
            resolved = resolved.resolve(part);
        }
        boolean exists = missing.isEmpty();
        if (!exists && !target.mayNotExist()) {
            throw refusal(InputCode.INSTRUCTION_SCOPE_TARGET_MISSING, "target", target.path());
        }
        return new Resolved(target.path(), resolved, exists, exists && existing.type() == EntryType.FILE ? resolved.getParent() : resolved);
    }

    /** The state of the path itself, or of its nearest ancestor that exists; the names of the parts that do not exist are added to {@code missing}, the deepest last. */
    private static PathState nearestExisting(Path cursor, Deque<Path> missing, TargetRequest target, FileFacts files) {
        return files.stat(cursor.toString()).orElseGet(() -> {
            Path parent = cursor.getParent();
            if (parent == null) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_NO_EXISTING_ANCESTOR, "target", target.path());
            }
            missing.addFirst(cursor.getFileName());
            return nearestExisting(parent, missing, target, files);
        });
    }

    /** The root and every directory from it down to {@code directory}, which lies within the root. */
    private static List<Path> ancestors(Path root, Path directory) {
        List<Path> result = new ArrayList<>();
        result.add(root);
        if (!directory.equals(root)) {
            Path cursor = root;
            for (Path segment : root.relativize(directory)) {
                cursor = cursor.resolve(segment);
                result.add(cursor);
            }
        }
        return result;
    }

    /** An instruction file found in a directory; the position in a chain is given when it joins one. */
    private record Found(String path, Kind kind, long size, String sha256) {
        InstructionFile at(int precedence) {
            return new InstructionFile(path, kind, kind == Kind.OVERRIDE, size, sha256, precedence);
        }
    }

    /**
     * Whether the file holds nothing but white space and byte order marks. The UTF-8 charset keeps a leading U+FEFF as a character (the JDK's {@code Charset} page describes
     * byte-order-mark handling only for the UTF-16 and UTF-32 charsets), so it is allowed for here; the decoded characters are matched as they are, with no intermediate String.
     */
    private static boolean blank(byte[] data) {
        return BLANK.matcher(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data))).matches();
    }

    /**
     * The instruction file of each directory, looked at, read and hashed once however many targets' chains the directory lies on. A directory is the first listed name that exists in it
     * with content; a file that resolves outside every authorised root is refused.
     */
    private static final class Selections {
        private final List<Path> authorizedRoots;
        private final List<FileDescriptor> names;
        private final FileFacts files;
        private final Map<Path, Found> selected = new HashMap<>();
        private final Set<Path> withoutInstruction = new HashSet<>();

        Selections(List<Path> authorizedRoots, List<FileDescriptor> names, FileFacts files) {
            this.authorizedRoots = authorizedRoots;
            this.names = names;
            this.files = files;
        }

        Optional<Found> of(Path directory) {
            if (withoutInstruction.contains(directory)) {
                return Optional.empty();
            }
            Found known = selected.get(directory);
            if (known != null) {
                return Optional.of(known);
            }
            Optional<Found> read = read(directory);
            read.ifPresentOrElse(found -> selected.put(directory, found), () -> withoutInstruction.add(directory));
            return read;
        }

        private Optional<Found> read(Path directory) {
            if (!isDirectory(files, directory)) {
                return Optional.empty();
            }
            for (FileDescriptor file : names) {
                Path candidate = directory.resolve(file.name());
                Optional<Found> found = files.stat(candidate.toString()).map(state -> instruction(candidate, state, file));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        /** What the file holds, or null (which {@code Optional.map} turns into empty) when it holds nothing but white space. */
        private Found instruction(Path candidate, PathState state, FileDescriptor file) {
            Path actual = Path.of(state.realPath());
            if (authorizedRoots.stream().noneMatch(actual::startsWith)) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_INSTRUCTION_OUTSIDE_ROOTS, "path", candidate.toString());
            }
            byte[] data = files.read(actual.toString(), SkillArguments.DOCUMENT_LIMIT);
            return blank(data) ? null : new Found(actual.toString(), file.kind(), data.length, Digests.sha256Hex(data));
        }
    }

    private record Root(Path path, int precedence) {
    }

    private Resolution resolve(JsonNode input, JsonNode adapterFiles, FileFacts files) {
        List<FileDescriptor> names = instructionFiles(adapterFiles);
        List<SkillSchemas.Violation> violations = schemas.violations(REQUEST, input);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.INSTRUCTION_SCOPE_REQUEST_VALIDATION_FAILED, Map.of(), SennardException.args("errors", violations));
        }
        Request request = schemas.bind(input, Request.class);
        PathState root = files.stat(request.workspaceRoot()).orElseThrow(() -> refusal(InputCode.INSTRUCTION_SCOPE_WORKSPACE_ROOT_NOT_DIRECTORY));
        Path workspaceRoot = Path.of(root.realPath());
        need(isDirectory(files, workspaceRoot), InputCode.INSTRUCTION_SCOPE_WORKSPACE_ROOT_NOT_DIRECTORY);
        List<RootRequest> rootRequests = new ArrayList<>(request.instructionRoots());
        rootRequests.sort(Comparator.comparingInt(RootRequest::precedence).thenComparing(RootRequest::path));
        List<Root> authorized = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        for (RootRequest candidate : rootRequests) {
            if (!candidate.authorized()) {
                findings.add(messages.forRequest(M + "skippedUnauthorizedRoot", SennardException.args("path", candidate.path())));
                continue;
            }
            PathState state = files.stat(workspaceRoot.resolve(candidate.path()).normalize().toString()).orElseThrow(() -> refusal(InputCode.INSTRUCTION_SCOPE_INSTRUCTION_ROOT_NOT_DIRECTORY, "path", candidate.path()));
            Path actual = Path.of(state.realPath());
            if (!isDirectory(files, actual)) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_INSTRUCTION_ROOT_NOT_DIRECTORY, "path", candidate.path());
            }
            authorized.add(new Root(actual, candidate.precedence()));
        }
        need(!authorized.isEmpty(), InputCode.INSTRUCTION_SCOPE_NO_AUTHORIZED_ROOT);
        List<Path> authorizedRoots = authorized.stream().map(Root::path).toList();
        // The same directory lies on the chain of several targets: it is looked at, read and hashed once.
        Selections selections = new Selections(authorizedRoots, names, files);
        List<TargetResolution> targets = new ArrayList<>(request.targets().size());
        Map<String, InstructionFile> manifest = new LinkedHashMap<>();
        for (TargetRequest target : request.targets()) {
            Path requestedAbsolute = workspaceRoot.resolve(target.path()).normalize();
            if (!requestedAbsolute.startsWith(workspaceRoot)) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_TARGET_OUTSIDE_WORKSPACE, "target", target.path(), "requestedAbsolute", requestedAbsolute.toString());
            }
            Resolved resolved = canonicalTarget(workspaceRoot, target, files);
            if (!resolved.path().startsWith(workspaceRoot)) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_TARGET_RESOLVES_OUTSIDE, "target", target.path(), "resolvedPath", resolved.path().toString());
            }
            List<Root> applicable = authorized.stream().filter(r -> resolved.directory().startsWith(r.path())).toList();
            if (applicable.isEmpty()) {
                throw refusal(InputCode.INSTRUCTION_SCOPE_TARGET_NOT_COVERED, "target", target.path());
            }
            Map<Path, Integer> directories = new LinkedHashMap<>();
            for (Root applicableRoot : applicable) {
                for (Path directory : ancestors(applicableRoot.path(), resolved.directory())) {
                    directories.merge(directory, applicableRoot.precedence(), Math::min);
                }
            }
            List<Map.Entry<Path, Integer>> ordered = new ArrayList<>(directories.entrySet());
            ordered.sort(Comparator.<Map.Entry<Path, Integer>>comparingInt(e -> e.getKey().getNameCount()).thenComparing(Map.Entry::getValue).thenComparing(e -> e.getKey().toString()));
            List<InstructionFile> chain = new ArrayList<>();
            Set<String> chained = new HashSet<>();
            for (Map.Entry<Path, Integer> directory : ordered) {
                selections.of(directory.getKey()).filter(found -> chained.add(found.path())).ifPresent(found -> {
                    InstructionFile instruction = found.at(chain.size() + 1);
                    chain.add(instruction);
                    manifest.put(instruction.path(), instruction);
                });
            }
            if (!resolved.exists()) {
                findings.add(messages.forRequest(M + "futureNestedUnobservable", SennardException.args("path", target.path())));
            }
            targets.add(new TargetResolution(resolved.requestedPath(), resolved.path().toString(), resolved.exists(), chain, List.of(), List.of(), List.of()));
        }
        List<InstructionFile> manifestRows = new ArrayList<>(manifest.values());
        manifestRows.sort(Comparator.comparing(InstructionFile::path));
        return new Resolution(SCHEMA_VERSION, workspaceRoot.toString(), manifestRows, targets, findings, AnalysisStatus.REQUIRED, Verdict.ANALYSIS_REQUIRED);
    }
}
