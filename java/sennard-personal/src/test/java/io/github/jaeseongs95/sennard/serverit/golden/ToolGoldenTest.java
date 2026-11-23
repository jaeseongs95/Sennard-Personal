// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.serverit.GoldenHarness;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every published tool through the bytes a host sends to {@code /mcp}, on the assembled server with real PostgreSQL: one success and one
 * representative failure each (cases T-*), and the refusals every tool shares, an undeclared argument and an argument that cannot be
 * converted (cases X-*). A case may first make calls of its own to reach its state (a run to finalize, a token to present); only its last
 * call is the golden answer. The cases run in the order below, each under its own session, on one server.
 */
@Tag("business-db")
class ToolGoldenTest {
    private static final String GROUP = "tools";
    private static final String ONE = "1.0.0";
    private static final String STALE_TOKEN = "a".repeat(48);
    private static final String NO_MESSAGE = "message-does-not-exist";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static Locale before;
    private static GoldenHarness server;
    private static JsonNode releaseVersion;
    private static Path workspaces;
    /** The folder the profile cases read: outside any checkout, so the profile reports no branch or dirty state of the machine that runs it. */
    private static Path profileRoot;
    private static List<Golden.Token> tokens;
    private static final Set<String> registered = new HashSet<>();
    private static final Map<String, Integer> calls = new HashMap<>();

    @BeforeAll
    static void start() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.US); // the case table's language; restored after the class
        // The edit gate does not count the OS temporary directory, so the workspaces live in this checkout's build directory (as WorkflowToolsServerTest's).
        workspaces = Path.of("build", "golden-ws", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        profileRoot = Files.createTempDirectory("sennard-golden-profile-").toRealPath();
        Files.writeString(profileRoot.resolve("README.md"), "# fixture\n");
        server = GoldenHarness.full(Golden.REPOSITORY);
        String releaseMetadata = Files.readString(Golden.REPOSITORY.resolve("release/version.json"), StandardCharsets.UTF_8);
        releaseVersion = Golden.MAPPER.readTree(releaseMetadata).path("version");
        assertTrue(releaseVersion.isString() && !releaseVersion.asString().isBlank(), "release/version.json must declare a version string");
        tokens = List.of(new Golden.Token("{WS}", workspaces.toString(), true), new Golden.Token("{PROFILE}", profileRoot.toString(), true), new Golden.Token("{STATE}", server.stateDirectory().toString(), true),
                new Golden.Token("{REPO}", Golden.REPOSITORY.toString(), true), new Golden.Token("{TMP}", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString(), true),
                new Golden.Token("{ADMIN_PORT}", Integer.toString(server.adminPort()), false), new Golden.Token("{PORT}", Integer.toString(server.port()), false),
                new Golden.Token("{RELEASE_VERSION}", releaseVersion.asString(), false));
    }

    @AfterAll
    static void stop() {
        try {
            server.close();
        } finally {
            Locale.setDefault(before);
            try (Stream<Path> files = Files.walk(profileRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
            } catch (java.io.IOException unreadable) {
                throw new java.io.UncheckedIOException(unreadable);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ calls

    private record Case(String id, Body body) {
    }

    @FunctionalInterface
    private interface Body {
        /** Runs the case's own calls and returns the answer of its last one. */
        JsonNode run(String id) throws Exception;
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> v1(Object... pairs) {
        Map<String, Object> map = args("schemaVersion", ONE);
        map.putAll(args(pairs));
        return map;
    }

    /** The case's own session: the same identifier on every run, so it needs no placeholder. */
    private static String session(String id) {
        return UUID.nameUUIDFromBytes(("golden:" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The case's session, started once with the host's recorded SessionStart. */
    private static String started(String id, String host) throws Exception {
        String session = session(id + (host.equals("codex") ? "" : "/" + host));
        if (registered.add(session)) {
            server.startSession(host, session);
        }
        return session;
    }

    private static String started(String id) throws Exception {
        return started(id, "codex");
    }

    /** The recorded Codex {@code _meta} with this session; every call is its own host call, numbered within its session. */
    private static Map<String, Object> meta(String session) {
        Map<String, Object> meta = new HashMap<>(GoldenHarness.meta("windows", "codex", session, "none"));
        int n = calls.merge(session, 1, Integer::sum);
        meta.put("callId", "exec-golden-" + session.substring(0, 8) + "-" + n);
        return meta;
    }

    /** One tools/call as the host's bytes; the answer as a golden tree with each JSON text content parsed. */
    private static JsonNode raw(String tool, Map<String, Object> arguments, Map<String, Object> meta) throws Exception {
        ObjectNode request = Golden.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode params = request.putObject("params").put("name", tool);
        params.set("arguments", Golden.MAPPER.valueToTree(io.github.jaeseongs95.sennard.serverit.OperationKeys.withKey(tool, arguments)));
        if (meta != null) {
            params.set("_meta", Golden.MAPPER.valueToTree(meta));
        }
        ObjectNode answer = Golden.answer(server.mcp(Golden.MAPPER.writeValueAsBytes(request), "codex"));
        JsonNode content = answer.path("body").path("result").path("content");
        if (content instanceof ArrayNode items) {
            for (JsonNode item : items) {
                if (item instanceof ObjectNode text && text.path("text").isString()) {
                    text.set("text", Golden.parsedOrText(text.get("text").asString()));
                }
            }
        }
        return answer;
    }

    private static JsonNode call(String session, String tool, Map<String, Object> arguments) throws Exception {
        return raw(tool, arguments, meta(session));
    }

    /** The envelope of a preparing call, which must have succeeded. */
    private static JsonNode data(String session, String tool, Map<String, Object> arguments) throws Exception {
        JsonNode envelope = envelope(call(session, tool, arguments));
        assertTrue(envelope.path("ok").asBoolean(false), () -> tool + " while preparing: " + envelope);
        return envelope.path("data");
    }

    private static JsonNode envelope(JsonNode answer) {
        return answer.path("body").path("result").path("content").path(0).path("text");
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------------------------------------- inputs taken from the tool tests

    /** ToolContractServerTest.decision. */
    private static Map<String, Object> decision() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("schemaVersion", "1.2.0");
        decision.put("sourceOriginKind", "artifact");
        decision.put("sourceReceiptId", null);
        decision.put("authorityEffect", "none");
        decision.put("userDirective", "unspecified");
        decision.put("netBenefitCriteria", args("independentlyCompletable", false, "parallelBottleneckReduced", false, "limitedContextSufficient", false, "singleWriterOwnership", false,
                "netBenefitAfterOverhead", false));
        decision.put("auditSeparationRequired", false);
        decision.put("route", "direct");
        return decision;
    }

    /** ToolContractServerTest.classifyInput. */
    private static Map<String, Object> classifyInput(String operationKey, String requestId, String prompt) {
        Map<String, Object> confirmed = new LinkedHashMap<>();
        for (String field : List.of("taskRevision", "objective", "actions", "targets", "constraints", "prohibitedActions", "background")) {
            confirmed.put(field, null);
        }
        Map<String, Object> input = v1("requestId", requestId, "operationKey", operationKey, "originalPrompt", prompt, "confirmedContext", confirmed, "contextSources", List.of(),
                "explicitSkillIds", List.of(), "ruleRequiredSkillIds", List.of(), "vendorContext", args("vendorId", "codex", "reference", "session"), "publicSynthetic", false);
        return input;
    }

    /** ToolContractServerTest.selection. */
    private static Map<String, Object> selection(String operationKey) {
        String zero = "sha256:" + "0".repeat(64);
        Map<String, Object> decision = v1("classificationResponseRef", zero, "requestDigest", zero, "inventoryDigest", zero, "taskRevision", null, "configRevision", "unconfigured",
                "profileRevision", "unconfigured", "explicitSkillIds", List.of(), "ruleRequiredSkillIds", List.of(), "agentSelectedSkillIds", List.of(), "selectionReasons", List.of(),
                "applicabilityChecks", List.of(), "unresolvedSkillReferences", List.of(), "selectionStatus", "PARTIAL", "adviceApplied", false, "hostReceipt", null);
        return v1("operationKey", operationKey, "decision", decision);
    }

    /** ContinuityMessagingToolContractTest.core. */
    private static Map<String, Object> core(String objective) {
        return args("objective", objective, "completionCriteria", List.of("c"), "constraints", List.of(), "decisions", List.of(), "progress", List.of(), "blockers", List.of(),
                "nextActions", List.of());
    }

    /** A saved continuity state for this session; returns its state token. */
    private static String checkpointed(String session) throws Exception {
        String operationKey = "checkpoint-" + session;
        JsonNode stale = envelope(call(session, "checkpoint_context", v1("operationKey", operationKey,"expectedStateToken", STALE_TOKEN, "status", "ACTIVE", "core", core("golden objective"),
                "evidenceRefs", List.of())));
        String actual = stale.path("error").path("details").path("actualStateToken").asString();
        return data(session, "checkpoint_context", v1("operationKey", operationKey,"expectedStateToken", actual, "status", "ACTIVE", "core", core("golden objective"), "evidenceRefs", List.of()))
                .path("stateToken").asString();
    }

    private static String prepared(String id, String sender) throws Exception {
        String target = started(id, "claude");
        return data(sender, "prepare_session_message", v1("targetHost", "claude-code", "targetSessionId", target, "body", "golden hello")).path("messageId").asString();
    }

    /** A workspace of this case, created; its name is lower case because Sennard folds a Windows path's case. */
    private static Path workspace(String id) throws Exception {
        Path directory = workspaces.resolve(id.toLowerCase(Locale.ROOT).replace('_', '-'));
        Files.createDirectories(directory);
        return directory;
    }

    /** WorkflowToolsServerTest.start. */
    private static Map<String, Object> startInput(Path workspace, List<String> writeTargets) {
        return args("goal", "Change " + writeTargets + " in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills",
                List.of(args("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(), "writeTargets", writeTargets);
    }

    /** The start input of a kind that owns no files: its goal, its skills when it requires them and its target when it takes one. */
    private static Map<String, Object> kindInput(WorkflowKind kind, Path workspace) {
        Map<String, Object> input = args("goal", "Handle " + kind.name() + " in " + workspace.getFileName(), "workspace", workspace.toString());
        if (kind.skillsRequired()) {
            input.put("skills", List.of(args("skillId", "minimal-implementation", "reason", "test")));
        }
        if (kind.takesTarget()) {
            input.put("target", "the golden repository");
        }
        return input;
    }

    private static JsonNode startedRun(String session, String id, List<String> writeTargets) throws Exception {
        return data(session, "start_workflow", startInput(workspace(id), writeTargets));
    }

    private static JsonNode root(String session, String rootId) throws Exception {
        return data(session, "get_convergence_status", args("rootId", rootId)).path("root");
    }

    /** The evidence names an attachment of the case's session (the one file of the attachment cases) and its digest; the server never reads a path. */
    private static Map<String, Object> passedStage(String runId, long revision, String stageId, String attachmentId) throws Exception {
        return v1("runId", runId, "expectedRevision", revision, "stageId", stageId, "state", "PASSED",
                "output", v1("kind", "output", "output", args("status", "passed"), "artifacts", List.of(), "error", null),
                "evidence", List.of(args("artifactId", "observed", "kind", "test", "attachmentId", attachmentId, "digest", attachmentDigest(), "verified", true, "note", "Synthetic fixture.")),
                "findings", List.of(), "blockers", List.of(), "error", null);
    }

    /** The run's status and the stage it is at. */
    private static JsonNode receipt(String session, String runId) throws Exception {
        return data(session, "get_workflow_status", args("runId", runId));
    }

    private static String stageOf(JsonNode receipt) {
        return receipt.path("plan").path("currentStageId").asString(receipt.path("plan").path("nextStageId").asString());
    }

    /** A root that this session opened with open_convergence_root, on a released start's task and frame; returns [root, task, frame]. */
    private static JsonNode[] openedRoot(String session, String id) throws Exception {
        JsonNode run = startedRun(session, id, List.of("alpha"));
        String first = run.path("rootId").asString();
        JsonNode current = root(session, first);
        data(session, "release_convergence_root", args("rootId", first, "expectedRevision", current.path("revision").asLong(), "reason", "The golden case opens its own root."));
        ObjectNode task = (ObjectNode) current.path("taskEnvelope").deepCopy();
        task.put("taskId", "task-" + id.toLowerCase(Locale.ROOT).replace('_', '-'));
        JsonNode frame = current.path("frame");
        JsonNode opened = data(session, "open_convergence_root", v1("parentRootId", null, "taskEnvelope", Golden.MAPPER.convertValue(task, MAP), "frame", Golden.MAPPER.convertValue(frame, MAP),
                "userApprovalRefs", List.of()));
        return new JsonNode[] {opened, task, frame};
    }

    /** A claimed lease on a root this session opened; returns [lease, plan]. */
    private static JsonNode[] claimed(String session, String id) throws Exception {
        JsonNode[] opened = openedRoot(session, id);
        JsonNode plan = data(session, "plan_workflow", Golden.MAPPER.convertValue(opened[1], MAP));
        JsonNode lease = data(session, "claim_workflow_attempt", claimInput(opened[0], opened[1], opened[2], plan));
        return new JsonNode[] {lease, plan};
    }

    private static Map<String, Object> claimInput(JsonNode root, JsonNode task, JsonNode frame, JsonNode plan) {
        return v1("rootId", root.path("rootId").asString(), "expectedRevision", root.path("revision").asLong(), "taskEnvelope", Golden.MAPPER.convertValue(task, MAP),
                "frame", Golden.MAPPER.convertValue(frame, MAP), "plan", Golden.MAPPER.convertValue(plan, MAP), "outputTargets", List.of("alpha"), "priorFailure", null);
    }

    /** WorkflowToolsServerTest.claimChanged: the owner's claim with a changed objective, which moves the root to review. */
    private static JsonNode frameChanged(String session, String rootId) throws Exception {
        JsonNode current = root(session, rootId);
        ObjectNode task = (ObjectNode) current.path("taskEnvelope").deepCopy();
        task.put("objective", task.path("objective").asString() + " Also more.");
        JsonNode plan = data(session, "plan_workflow", Golden.MAPPER.convertValue(task, MAP));
        JsonNode answer = call(session, "claim_workflow_attempt", claimInput(current, task, current.path("frame"), plan));
        JsonNode refused = envelope(answer);
        assertEquals("FRAME_REVIEW_REQUIRED", refused.path("error").path("code").asString(), refused.toString());
        return answer;
    }

    private static Map<String, Object> review(String rootId, JsonNode status) {
        JsonNode current = status.path("root");
        return v1("reviewId", "review-golden", "rootId", rootId, "rootRevision", current.path("revision").asLong(), "epoch", status.path("currentEpoch").asLong(),
                "reviewerActorId", "fresh-frame-auditor", "freshContext", args("confirmed", true, "evidenceRef", "review-evidence:1"), "classification", "semantics-changing",
                "comparability", args("comparable", false, "rationale", "The evaluation meaning is changed or unresolved."), "route", "needs-user",
                "proposedFrame", Golden.MAPPER.convertValue(current.path("frame"), MAP), "evidenceRefs", List.of("review-evidence:1"), "userApprovalRefs", List.of(),
                "reviewedAt", "2026-01-01T00:00:00Z");
    }

    private static JsonNode skillCheck(Map<String, Object> arguments) throws Exception {
        return raw("run_skill_check", arguments, null);
    }

    /**
     * A check that runs as a background job: it compares the change record of a run that has none, so its answer is the same refusal on every machine (the answer holds
     * the run's name, which is fixed here, and no path).
     */
    private static Map<String, Object> profileInput() {
        Map<String, Object> envelope = args("schemaVersion", "1.0.0", "taskId", "task-1", "objective", "Update source", "scope", args("included", List.of("src/**"), "excluded", List.of()),
                "acceptanceCriteria", List.of("Source is updated"), "riskLevel", "low",
                "workUnits", List.of(args("id", "unit-1", "objective", "Edit source", "dependencies", List.of(), "writeTargets", List.of("src/**"))), "requiredCapabilities", List.of(), "constraints", List.of(),
                "authorization", args("allowedActions", List.of("edit"), "prohibitedActions", List.of(), "approvalRequired", List.of()), "decision", args("complexity", "simple", "hasConflicts", false),
                "orchestration", args("requested", false, "mcpAvailable", false));
        Map<String, Object> request = v1("mode", "verify", "workflowId", "run-golden-no-record", "workspaceRoot", profileRoot.toString(), "taskEnvelope", envelope);
        return args("skill", "change-scope-guardian", "command", "compare", "arguments", args("input", request));
    }

    /** The one file of the attachment cases: 18 bytes of text, sent through multipart. */
    private static final String ATTACHMENT_TEXT = "golden attachment\n";
    private static final String MISSING_ATTACHMENT = "golden-attachment-missing";

    private static String attachmentDigest() throws Exception {
        return "sha256:" + sha256(ATTACHMENT_TEXT);
    }

    /** artifact_begin for the attachment cases: the operationKey is the case ID (16 to 128 characters of letters, digits, hyphen and underscore, as every case ID is). */
    private static Map<String, Object> beginInput(String id, String purpose) throws Exception {
        Map<String, Object> file = args("fileName", "golden.txt", "mediaType", "text/plain", "size", ATTACHMENT_TEXT.getBytes(StandardCharsets.UTF_8).length, "sha256", attachmentDigest());
        return v1("operationKey", id, "files", List.of(file), "purpose", purpose);
    }

    /** The case's attachment, begun by its session with its one file: the answer of artifact_begin (the attachmentId and the upload of file 0). */
    private static JsonNode begunAttachment(String session, String id) throws Exception {
        return data(session, "artifact_begin", beginInput(id, "evidence"));
    }

    /** The case's upload of its one file, begun by its session; returns the uploadId. */
    private static String begun(String session, String id) throws Exception {
        return begunAttachment(session, id).path("files").get(0).path("uploadId").asString();
    }

    /** The case's attachment, begun, its one file filled with its one chunk and finished by the attachmentId; returns the attachmentId. */
    private static String kept(String session, String id) throws Exception {
        JsonNode begun = begunAttachment(session, id);
        server.upload(begun.path("files").get(0).path("uploadId").asString(), ATTACHMENT_TEXT.getBytes(StandardCharsets.UTF_8));
        return data(session, "artifact_finish", v1("attachmentId", begun.path("attachmentId").asString())).path("attachmentId").asString();
    }

    // ------------------------------------------------------------------------------------------------------------ cases

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        // The board first: list_session_status shows every running session, so it runs while its own is the only one.
        cases.add(new Case("T-list_session_status-ok", id -> call(started(id), "list_session_status", v1())));
        cases.add(new Case("T-list_session_status-invalid_input", id -> call(started(id), "list_session_status", args("schemaVersion", "9.9.9"))));
        cases.add(new Case("T-update_session_status-ok", id -> call(started(id), "update_session_status", v1("summary", "golden summary", "role", "golden role"))));
        cases.add(new Case("T-update_session_status-invalid_input", id -> call(started(id), "update_session_status", v1("summary", "two\nlines"))));

        // Shared context and roles.
        cases.add(new Case("T-load_shared_context-ok", id -> call(started(id), "load_shared_context", v1())));
        cases.add(new Case("T-load_shared_context-binding_required", id -> raw("load_shared_context", v1(), Map.of())));
        cases.add(new Case("T-checkpoint_global_context-ok", id -> call(started(id), "checkpoint_global_context", v1("expectedStateToken", null, "body", "golden global"))));
        cases.add(new Case("T-checkpoint_global_context-too_large", id -> call(started(id), "checkpoint_global_context", v1("expectedStateToken", null, "body", "가".repeat(1333) + "ab"))));
        cases.add(new Case("T-bind_session_role-ok", id -> {
            server.addRole("role-golden-bind", "golden-bind-role");
            return call(started(id), "bind_session_role", v1("role", "golden-bind-role"));
        }));
        cases.add(new Case("T-bind_session_role-role_not_registered", id -> call(started(id), "bind_session_role", v1("role", "golden-unregistered-role"))));
        cases.add(new Case("T-checkpoint_role_context-ok", id -> {
            String session = started(id);
            server.addRole("role-golden-checkpoint", "golden-checkpoint-role");
            server.approve(data(session, "bind_session_role", v1("role", "golden-checkpoint-role")).path("proposalId").asString());
            return call(session, "checkpoint_role_context", v1("expectedStateToken", null, "body", "golden role body"));
        }));
        cases.add(new Case("T-checkpoint_role_context-role_not_bound", id -> call(started(id), "checkpoint_role_context", v1("expectedStateToken", null, "body", "x"))));

        // Continuity.
        cases.add(new Case("T-checkpoint_context-ok", id -> {
            String session = started(id);
            String operationKey = "checkpoint-" + session;
            String actual = envelope(call(session, "checkpoint_context", v1("operationKey", operationKey,"expectedStateToken", STALE_TOKEN, "status", "ACTIVE", "core", core("golden objective"),
                    "evidenceRefs", List.of()))).path("error").path("details").path("actualStateToken").asString();
            return call(session, "checkpoint_context", v1("operationKey", operationKey,"expectedStateToken", actual, "status", "ACTIVE", "core", core("golden objective"), "evidenceRefs", List.of()));
        }));
        cases.add(new Case("T-checkpoint_context-stale_revision", id -> call(started(id), "checkpoint_context", v1("operationKey", "checkpoint-stale","expectedStateToken", STALE_TOKEN,
                "status", "ACTIVE", "core", core("golden objective"), "evidenceRefs", List.of()))));
        cases.add(new Case("T-inspect_context-ok", id -> {
            String session = started(id);
            checkpointed(session);
            return call(session, "inspect_context", v1());
        }));
        cases.add(new Case("T-inspect_context-invalid_input", id -> call(started(id), "inspect_context", args())));
        cases.add(new Case("T-load_context-ok", id -> {
            String session = started(id);
            checkpointed(session);
            String candidate = data(session, "inspect_context", v1()).path("restoreToken").asString();
            return call(session, "load_context", v1("candidateToken", candidate));
        }));
        cases.add(new Case("T-load_context-binding_invalid", id -> call(started(id), "load_context", v1("candidateToken", "x".repeat(40)))));
        cases.add(new Case("T-suppress_context_restore-ok", id -> {
            String session = started(id);
            checkpointed(session);
            return call(session, "suppress_context_restore", v1("expectedEpoch", 1));
        }));
        cases.add(new Case("T-suppress_context_restore-stale_revision", id -> call(started(id), "suppress_context_restore", v1("expectedEpoch", 987654))));
        cases.add(new Case("T-purge_direct_context-ok", id -> {
            String session = started(id);
            String token = checkpointed(session);
            return call(session, "purge_direct_context", v1("operationKey", "purge-golden-operation","expectedStateToken", token));
        }));
        cases.add(new Case("T-purge_direct_context-stale_revision", id -> call(started(id), "purge_direct_context", v1("operationKey", "purge-golden-operation","expectedStateToken", STALE_TOKEN))));

        // Session messages.
        cases.add(new Case("T-prepare_session_message-ok", id -> {
            String sender = started(id);
            return call(sender, "prepare_session_message", v1("targetHost", "claude-code", "targetSessionId", started(id, "claude"), "body", "golden hello"));
        }));
        cases.add(new Case("T-prepare_session_message-invalid_input", id -> {
            String sender = started(id);
            return call(sender, "prepare_session_message", v1("targetHost", "claude-code", "targetSessionId", started(id, "claude"), "body", "   "));
        }));
        cases.add(new Case("T-send_session_message-ok", id -> {
            String sender = started(id);
            return call(sender, "send_session_message", v1("messageId", prepared(id, sender)));
        }));
        cases.add(new Case("T-send_session_message-mcp_unavailable", id -> call(started(id), "send_session_message", v1("messageId", NO_MESSAGE))));
        cases.add(new Case("T-acknowledge_session_messages-ok", id -> call(started(id), "acknowledge_session_messages", v1("messageIds", List.of(NO_MESSAGE)))));
        cases.add(new Case("T-acknowledge_session_messages-invalid_input", id -> call(started(id), "acknowledge_session_messages", args())));
        cases.add(new Case("T-get_session_message_status-ok", id -> {
            String sender = started(id);
            return call(sender, "get_session_message_status", v1("messageId", prepared(id, sender)));
        }));
        cases.add(new Case("T-get_session_message_status-invalid_input", id -> call(started(id), "get_session_message_status", args())));
        cases.add(new Case("T-recall_session_message-ok", id -> {
            String sender = started(id);
            String messageId = prepared(id, sender);
            data(sender, "send_session_message", v1("messageId", messageId));
            return call(sender, "recall_session_message", v1("messageId", messageId));
        }));
        cases.add(new Case("T-recall_session_message-invalid_input", id -> call(started(id), "recall_session_message", args())));

        // Glossary, skills, trust, updates, sessions.
        String prose = "프로젝트를 위한 문서를 보여주기만 하고 끝낸다.";
        cases.add(new Case("T-lookup_korean_prose_terms-ok", id -> call(started(id), "lookup_korean_prose_terms", v1("sourceText", prose, "sourceDigest", sha256(prose)))));
        cases.add(new Case("T-lookup_korean_prose_terms-invalid_input", id -> call(started(id), "lookup_korean_prose_terms", v1("sourceText", "가", "sourceDigest", "0".repeat(64)))));
        cases.add(new Case("T-get_skill_inventory-ok", id -> call(started(id), "get_skill_inventory", args())));
        cases.add(new Case("T-get_skill_inventory-binding_required", id -> raw("get_skill_inventory", args(), Map.of())));
        cases.add(new Case("T-classify_skills-ok", id -> call(started(id), "classify_skills", classifyInput("op-golden-classify", "req-golden-classify", "golden prompt"))));
        cases.add(new Case("T-classify_skills-invalid_input", id -> {
            Map<String, Object> input = classifyInput("op-golden-classify-invalid", "req-golden-classify-invalid", "golden prompt");
            input.remove("requestId");
            return call(started(id), "classify_skills", input);
        }));
        cases.add(new Case("T-record_skill_selection-ok", id -> {
            String session = started(id);
            JsonNode classified = data(session, "classify_skills", classifyInput("op-golden-select", "req-golden-select", "golden prompt"));
            JsonNode inventory = data(session, "get_skill_inventory", args());
            Map<String, Object> selection = selection("op-golden-select");
            @SuppressWarnings("unchecked")
            Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
            decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
            decision.put("requestDigest", classified.path("result").path("response").path("requestDigest").asString());
            decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());
            return call(session, "record_skill_selection", selection);
        }));
        cases.add(new Case("T-record_skill_selection-invalid_input", id -> call(started(id), "record_skill_selection", selection("nobody-classified-this"))));
        cases.add(new Case("T-run_skill_check-ok", id -> {
            JsonNode input = Golden.MAPPER.readTree(Files.readString(Golden.REPOSITORY.resolve("tests/task-contract/fixtures/normal/simple-read.json"), StandardCharsets.UTF_8));
            return skillCheck(args("skill", "task-contract", "command", "validate", "arguments", args("input", Golden.MAPPER.convertValue(input, MAP))));
        }));
        cases.add(new Case("T-run_skill_check-queued", id -> skillCheck(profileInput())));
        cases.add(new Case("T-run_skill_check-invalid_input", id -> skillCheck(args("skill", "no-such-skill", "command", "x"))));
        cases.add(new Case("T-get_background_job-ok", id -> {
            String jobRef = envelope(skillCheck(profileInput())).path("data").path("jobRef").asString();
            Instant end = Instant.now().plus(Duration.ofSeconds(60));
            JsonNode job = raw("get_background_job", args("jobRef", jobRef), null);
            while (Set.of("QUEUED", "RUNNING").contains(envelope(job).path("data").path("state").asString()) && Instant.now().isBefore(end)) {
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
                job = raw("get_background_job", args("jobRef", jobRef), null);
            }
            return job;
        }));
        cases.add(new Case("T-get_background_job-job_not_found", id -> raw("get_background_job", args("jobRef", "00000000-0000-4000-8000-000000000000"), null)));
        cases.add(new Case("T-open_session-ok", id -> raw("open_session", args(), null)));
        cases.add(new Case("T-open_session-undeclared_argument", id -> raw("open_session", args("bogus", 1), null)));
        cases.add(new Case("T-get_trust_capabilities-ok", id -> call(started(id), "get_trust_capabilities", args())));
        cases.add(new Case("T-get_trust_capabilities-undeclared_argument", id -> call(started(id), "get_trust_capabilities", args("bogus", 1))));
        cases.add(new Case("T-validate_collaboration_decision-ok", id -> call(started(id), "validate_collaboration_decision", args("decision", decision()))));
        cases.add(new Case("T-validate_collaboration_decision-invalid_input", id -> call(started(id), "validate_collaboration_decision", args())));
        cases.add(new Case("T-check_for_updates-ok", id -> {
            JsonNode answer = call(started(id), "check_for_updates", args());
            assertEquals(releaseVersion, envelope(answer).at("/data/currentVersion"), "currentVersion must match release/version.json");
            return answer;
        }));
        cases.add(new Case("T-check_for_updates-invalid_input", id -> call(started(id), "check_for_updates", args("force", "true"))));

        // The attachments: the upload of each case is made by the case's own session, and its answers carry a generated id and a time (CASES.md normalizes them).
        cases.add(new Case("T-artifact_begin-ok", id -> call(started(id), "artifact_begin", beginInput(id, "evidence"))));
        cases.add(new Case("T-artifact_begin-unsupported_purpose", id -> call(started(id), "artifact_begin", beginInput(id, "upload"))));
        cases.add(new Case("T-artifact_finish-ok", id -> {
            String session = started(id);
            JsonNode begun = begunAttachment(session, id);
            server.upload(begun.path("files").get(0).path("uploadId").asString(), ATTACHMENT_TEXT.getBytes(StandardCharsets.UTF_8));
            return call(session, "artifact_finish", v1("attachmentId", begun.path("attachmentId").asString()));
        }));
        cases.add(new Case("T-artifact_finish-not_found", id -> call(started(id), "artifact_finish", v1("attachmentId", MISSING_ATTACHMENT))));
        cases.add(new Case("T-artifact_status-ok", id -> {
            String session = started(id);
            return call(session, "artifact_status", v1("uploadId", begun(session, id)));
        }));
        cases.add(new Case("T-artifact_status-not_found", id -> call(started(id), "artifact_status", v1("attachmentId", MISSING_ATTACHMENT))));
        cases.add(new Case("T-artifact_read-ok", id -> {
            String session = started(id);
            return call(session, "artifact_read", v1("attachmentId", kept(session, id)));
        }));
        cases.add(new Case("T-artifact_read-not_found", id -> call(started(id), "artifact_read", v1("attachmentId", MISSING_ATTACHMENT))));

        // Workflows, each on its own workspace.
        cases.add(new Case("T-start_workflow-ok", id -> call(started(id), "start_workflow", startInput(workspace(id), List.of(".")))));
        cases.add(new Case("T-start_workflow-invalid_input", id -> {
            Map<String, Object> input = startInput(workspace(id), List.of("."));
            input.remove("skills");
            return call(started(id), "start_workflow", input);
        }));
        // The tool of every other kind of workflow, from the enum (a kind that is added has its two cases with it): a success, and the refusal of what the kind requires.
        for (WorkflowKind kind : WorkflowKind.values()) {
            if (!kind.allowsFileEdits()) {
                cases.add(new Case("T-" + kind.toolName() + "-ok", id -> call(started(id), kind.toolName(), kindInput(kind, workspace(id)))));
                cases.add(new Case("T-" + kind.toolName() + "-invalid_input", id -> {
                    Map<String, Object> input = kindInput(kind, workspace(id));
                    input.remove("goal");
                    return call(started(id), kind.toolName(), input);
                }));
            }
        }
        cases.add(new Case("T-resume_workflow-ok", id -> {
            String session = started(id);
            JsonNode run = data(session, WorkflowKind.INQUIRY.toolName(), kindInput(WorkflowKind.INQUIRY, workspace(id)));
            return call(session, "resume_workflow", args("runId", run.path("runId").asString()));
        }));
        cases.add(new Case("T-resume_workflow-run_not_found", id -> call(started(id), "resume_workflow", args("runId", "run-golden-missing"))));
        cases.add(new Case("T-get_workflow_status-ok", id -> {
            String session = started(id);
            return call(session, "get_workflow_status", args("runId", startedRun(session, id, List.of(".")).path("runId").asString()));
        }));
        cases.add(new Case("T-get_workflow_status-run_not_found", id -> call(started(id), "get_workflow_status", args("runId", "run-golden-missing"))));
        cases.add(new Case("T-get_convergence_status-ok", id -> {
            String session = started(id);
            return call(session, "get_convergence_status", args("rootId", startedRun(session, id, List.of(".")).path("rootId").asString()));
        }));
        cases.add(new Case("T-get_convergence_status-run_not_found", id -> call(started(id), "get_convergence_status", args("rootId", "root-golden-missing"))));
        cases.add(new Case("T-record_stage_result-ok", id -> {
            String session = started(id);
            String runId = startedRun(session, id, List.of(".")).path("runId").asString();
            JsonNode receipt = receipt(session, runId);
            return call(session, "record_stage_result", passedStage(runId, receipt.path("revision").asLong(), stageOf(receipt), kept(session, id)));
        }));
        cases.add(new Case("T-record_stage_result-stale_revision", id -> {
            String session = started(id);
            String runId = startedRun(session, id, List.of(".")).path("runId").asString();
            JsonNode receipt = receipt(session, runId);
            return call(session, "record_stage_result", passedStage(runId, receipt.path("revision").asLong() + 5, stageOf(receipt), kept(session, id)));
        }));
        cases.add(new Case("T-finalize_workflow-ok", id -> {
            String session = started(id);
            String runId = startedRun(session, id, List.of(".")).path("runId").asString();
            JsonNode receipt = receipt(session, runId);
            long revision = data(session, "record_stage_result", passedStage(runId, receipt.path("revision").asLong(), stageOf(receipt), kept(session, id))).path("revision").asLong();
            return call(session, "finalize_workflow", args("runId", runId, "expectedRevision", revision));
        }));
        cases.add(new Case("T-finalize_workflow-stale_revision", id -> {
            String session = started(id);
            JsonNode run = startedRun(session, id, List.of("."));
            return call(session, "finalize_workflow", args("runId", run.path("runId").asString(), "expectedRevision", run.path("revision").asLong() + 7));
        }));
        cases.add(new Case("T-abort_workflow-ok", id -> {
            String session = started(id);
            JsonNode run = startedRun(session, id, List.of("."));
            return call(session, "abort_workflow", args("runId", run.path("runId").asString(), "expectedRevision", run.path("revision").asLong()));
        }));
        cases.add(new Case("T-abort_workflow-run_not_found", id -> call(started(id), "abort_workflow", args("runId", "run-golden-missing", "expectedRevision", 0))));
        cases.add(new Case("T-release_convergence_root-ok", id -> {
            String session = started(id);
            String rootId = startedRun(session, id, List.of("alpha")).path("rootId").asString();
            return call(session, "release_convergence_root", args("rootId", rootId, "expectedRevision", root(session, rootId).path("revision").asLong(), "reason", "The work moved elsewhere."));
        }));
        cases.add(new Case("T-release_convergence_root-invalid_transition", id -> {
            String session = started(id);
            String rootId = startedRun(session, id, List.of("alpha")).path("rootId").asString();
            data(session, "release_convergence_root", args("rootId", rootId, "expectedRevision", root(session, rootId).path("revision").asLong(), "reason", "The work moved elsewhere."));
            return call(session, "release_convergence_root", args("rootId", rootId, "expectedRevision", root(session, rootId).path("revision").asLong(), "reason", "again"));
        }));
        cases.add(new Case("T-plan_workflow-ok", id -> {
            String session = started(id);
            JsonNode task = root(session, startedRun(session, id, List.of("alpha")).path("rootId").asString()).path("taskEnvelope");
            return call(session, "plan_workflow", Golden.MAPPER.convertValue(task, MAP));
        }));
        cases.add(new Case("T-plan_workflow-invalid_input", id -> call(started(id), "plan_workflow", args())));
        cases.add(new Case("T-open_convergence_root-ok", id -> {
            String session = started(id);
            JsonNode run = startedRun(session, id, List.of("alpha"));
            String first = run.path("rootId").asString();
            JsonNode current = root(session, first);
            data(session, "release_convergence_root", args("rootId", first, "expectedRevision", current.path("revision").asLong(), "reason", "The golden case opens its own root."));
            ObjectNode task = (ObjectNode) current.path("taskEnvelope").deepCopy();
            task.put("taskId", "task-golden-open");
            return call(session, "open_convergence_root", v1("parentRootId", null, "taskEnvelope", Golden.MAPPER.convertValue(task, MAP), "frame",
                    Golden.MAPPER.convertValue(current.path("frame"), MAP), "userApprovalRefs", List.of()));
        }));
        cases.add(new Case("T-open_convergence_root-root_conflict", id -> {
            String session = started(id);
            JsonNode current = root(session, startedRun(session, id, List.of(".")).path("rootId").asString());
            ObjectNode task = (ObjectNode) current.path("taskEnvelope").deepCopy();
            task.put("taskId", "task-golden-conflict");
            return call(started(id + "/other"), "open_convergence_root", v1("parentRootId", null, "taskEnvelope", Golden.MAPPER.convertValue(task, MAP), "frame",
                    Golden.MAPPER.convertValue(current.path("frame"), MAP), "userApprovalRefs", List.of()));
        }));
        cases.add(new Case("T-claim_workflow_attempt-ok", id -> {
            String session = started(id);
            JsonNode[] opened = openedRoot(session, id);
            JsonNode plan = data(session, "plan_workflow", Golden.MAPPER.convertValue(opened[1], MAP));
            return call(session, "claim_workflow_attempt", claimInput(opened[0], opened[1], opened[2], plan));
        }));
        // No argument at all is not refused as a bad input: the claim looks the root up first and answers RUN_NOT_FOUND with an empty rootId.
        cases.add(new Case("T-claim_workflow_attempt-root_not_found", id -> call(started(id), "claim_workflow_attempt", args())));
        cases.add(new Case("T-claim_workflow_attempt-frame_review_required", id -> {
            String session = started(id);
            return frameChanged(session, startedRun(session, id, List.of("alpha")).path("rootId").asString());
        }));
        cases.add(new Case("T-start_guarded_workflow-ok", id -> {
            String session = started(id);
            JsonNode lease = claimed(session, id)[0];
            return call(session, "start_guarded_workflow", v1("leaseId", lease.path("leaseId").asString(), "expectedRootRevision", lease.path("rootRevision").asLong(),
                    "skills", List.of(args("skillId", "minimal-implementation", "reason", "test"))));
        }));
        cases.add(new Case("T-start_guarded_workflow-lease_conflict", id -> call(started(id), "start_guarded_workflow", v1("leaseId", "lease-golden-missing", "expectedRootRevision", 0,
                "skills", List.of(args("skillId", "minimal-implementation", "reason", "test"))))));
        cases.add(new Case("T-resolve_convergence_gate-ok", id -> {
            String session = started(id);
            String rootId = startedRun(session, id, List.of("alpha")).path("rootId").asString();
            frameChanged(session, rootId);
            JsonNode status = data(session, "get_convergence_status", args("rootId", rootId));
            return call(session, "resolve_convergence_gate", v1("rootId", rootId, "expectedRevision", status.path("root").path("revision").asLong(), "review", review(rootId, status)));
        }));
        cases.add(new Case("T-resolve_convergence_gate-stale_revision", id -> {
            String session = started(id);
            String rootId = startedRun(session, id, List.of("alpha")).path("rootId").asString();
            frameChanged(session, rootId);
            JsonNode status = data(session, "get_convergence_status", args("rootId", rootId));
            return call(session, "resolve_convergence_gate", v1("rootId", rootId, "expectedRevision", status.path("root").path("revision").asLong() + 1, "review", review(rootId, status)));
        }));

        // What every tool shares: an argument no tool declares, and one the declared type cannot take.
        cases.add(new Case("X-undeclared-update_session_status", id -> call(started(id), "update_session_status", v1("summary", "golden", "bogus", 1))));
        cases.add(new Case("X-undeclared-inspect_context", id -> call(started(id), "inspect_context", v1("bogus", 1))));
        cases.add(new Case("X-undeclared-start_workflow", id -> {
            Map<String, Object> input = startInput(workspace(id), List.of("."));
            input.put("_sessionBinding", args("host", "forged-host", "sessionId", "forged-session"));
            return call(started(id), "start_workflow", input);
        }));
        cases.add(new Case("X-undeclared-run_skill_check", id -> skillCheck(args("skill", "task-contract", "command", "validate", "bogus", 1))));
        cases.add(new Case("X-convert-check_for_updates-force", id -> call(started(id), "check_for_updates", args("force", "yes"))));
        cases.add(new Case("X-convert-validate_collaboration_decision-decision", id -> call(started(id), "validate_collaboration_decision", args("decision", "not an object"))));
        cases.add(new Case("X-convert-get_background_job-jobRef", id -> raw("get_background_job", args("jobRef", args("a", 1)), null)));
        cases.add(new Case("X-convert-run_skill_check-arguments", id -> skillCheck(args("skill", "task-contract", "command", "validate", "arguments", "text"))));
        cases.add(new Case("X-convert-update_session_status-summary", id -> call(started(id), "update_session_status", v1("summary", 42))));

        // Cleanup last: it acts on the whole state, which every case above has written to.
        cases.add(new Case("T-prepare_state_cleanup-ok", id -> call(started(id), "prepare_state_cleanup", v1())));
        cases.add(new Case("T-prepare_state_cleanup-invalid_input", id -> call(started(id), "prepare_state_cleanup", args())));
        cases.add(new Case("T-execute_state_cleanup-ok", id -> {
            String session = started(id);
            return call(session, "execute_state_cleanup", v1("planToken", data(session, "prepare_state_cleanup", v1()).path("planToken").asString()));
        }));
        cases.add(new Case("T-execute_state_cleanup-invalid_input", id -> call(started(id), "execute_state_cleanup", v1("planToken", "x".repeat(90)))));
        return cases;
    }

    @TestFactory
    Stream<DynamicTest> everyToolGivesTheSameAnswer() {
        return cases().stream().map(item -> DynamicTest.dynamicTest(item.id(), () -> Golden.check(GROUP, item.id(), item.body().run(item.id()), tokens)));
    }

    @Test
    void theTableListsExactlyTheseCases() {
        List<String> ids = cases().stream().map(Case::id).toList();
        Golden.assertTableCovers("T-", ids.stream().filter(id -> id.startsWith("T-")).toList());
        Golden.assertTableCovers("X-", ids.stream().filter(id -> id.startsWith("X-")).toList());
    }
}
