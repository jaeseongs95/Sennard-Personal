// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Who holds the files of a write surface (AGS D-0319). One active root holds a surface (a row of {@code convergence_active_surfaces}); a root nobody runs gives its row up to another
 * session's root that takes its files, and its owner may continue it only when no other root holds those files then (ROOT_CONFLICT otherwise, on every way a root is continued:
 * {@code start_workflow} with a rootId, {@code claim_workflow_attempt}, {@code start_guarded_workflow}). A root that waits for a review or for the user is never given up. The states are
 * made by the workflow tools only, never by a write of the database.
 *
 * <p>The order of the writes of two sessions that meet (D-0400) is made by the test, not left to who is faster: see {@link #meetBehind}. A session that does not own a root is refused
 * its claim before anything is written (D-0401).
 */
@Tag("business-db")
class RootSurfaceServerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static RunningServer server;
    private static Path productBase;
    private static DomainServerSupport.Clients clients;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        productBase = Path.of("build", "root-surface-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(productBase);
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"),
                "--sennard.skills-root=" + repository);
        clients = new DomainServerSupport.Clients(server.port, Duration.ofSeconds(120));
    }

    @AfterAll
    static void stop() {
        try {
            clients.close();
        } finally {
            server.close();
        }
    }

    // ---- harness

    private static Map<String, Object> codexMeta(String session) {
        Map<String, Object> meta = new HashMap<>(SessionServerSupport.meta("windows", "codex", session, "none"));
        meta.put("callId", "exec-" + UUID.randomUUID());
        return meta;
    }

    private static JsonNode call(String tool, Map<String, Object> arguments, String session) {
        return call(clients.of("codex"), tool, arguments, session);
    }

    private static JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments, String session) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(OperationKeys.withKey(tool, arguments)).meta(codexMeta(session)).build());
        return MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.get("data");
    }

    private static String code(JsonNode result) {
        return result.path("error").path("code").asString("");
    }

    private static String reason(JsonNode result) {
        return result.path("error").path("details").path("reason").asString("");
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static Path product() throws Exception {
        return Files.createDirectories(productBase.resolve(UUID.randomUUID().toString()));
    }

    private static String session() {
        return UUID.randomUUID().toString();
    }

    /** A start with a change plan: the run starts in its executing phase. */
    private static Map<String, Object> start(Path workspace) {
        return arguments("goal", "Change alpha in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test")),
                "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(), "writeTargets", List.of("alpha"), "changePlan", List.of("alpha"));
    }

    private static JsonNode root(String rootId, String session) {
        return ok(call("get_convergence_status", arguments("rootId", rootId), session)).get("root");
    }

    private static long count(String table, String where, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql("SELECT COUNT(*) FROM sennard." + table + " WHERE " + where).params(params).query(Long.class).single());
    }

    private static String textOf(String sql, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql(sql).params(params).query(String.class).single());
    }

    private static long surfaces(String rootId) {
        return count("convergence_active_surfaces", "root_id = ?", rootId);
    }

    private static long attempts(String rootId) {
        return count("workflow_attempt_links", "root_id = ?", rootId);
    }

    /** The leases of a root by state: what a claim writes (a lease issued, the owner's lease superseded). */
    private static String leasesOf(String rootId) {
        return count("convergence_leases", "root_id = ? AND state = 'ISSUED'", rootId) + " issued, " + count("convergence_leases", "root_id = ? AND state = 'EXPIRED'", rootId) + " expired, "
                + count("convergence_leases", "root_id = ? AND state = 'CONSUMED'", rootId) + " consumed";
    }

    /** A start of the same shape as {@link #start} on another write target of the workspace. */
    private static Map<String, Object> startOn(Path workspace, String target) {
        Map<String, Object> out = new LinkedHashMap<>(start(workspace));
        out.put("writeTargets", List.of(target));
        out.put("changePlan", List.of(target));
        return out;
    }

    private static String attachment(String session) {
        return ToolServerSupport.uploadEvidence(server.port, (tool, input, caller) -> call(tool, input, caller), session).attachmentId();
    }

    /** The first attempt of a root that has ended: the root stays open and nobody runs it. */
    private static JsonNode startAndEndAttempt(String session, Path workspace) {
        JsonNode started = ok(call("start_workflow", start(workspace), session));
        ok(call("abort_workflow", arguments("runId", started.get("runId").asString(), "expectedRevision", started.get("revision").asLong()), session));
        return started;
    }

    /** The continuing of an owner's root after its first attempt ended (the evidence of the failure is an attachment of the session). */
    private static Map<String, Object> continuing(String owner, Path workspace, String rootId) {
        Map<String, Object> continued = new LinkedHashMap<>(start(workspace));
        continued.put("rootId", rootId);
        continued.put("priorFailure", arguments("hypothesis", "h", "changeSummary", "c", "discriminator", "d", "evidenceRefs", List.of(attachment(owner))));
        continued.put("skills", List.of(arguments("skillId", "blocker-diagnostician", "reason", "the first attempt failed")));
        return continued;
    }

    private static void release(String session, String rootId) {
        ok(call("release_convergence_root", arguments("rootId", rootId, "expectedRevision", root(rootId, session).get("revision").asLong(), "reason", "The work is over."), session));
    }

    private static List<String> actionsOf(JsonNode refusal) {
        List<String> actions = new ArrayList<>();
        refusal.path("error").path("details").path("nextActions").forEach(action -> actions.add(action.path("action").asString()));
        return actions;
    }

    /** The task and the frame of a root the session made and released, the workspace they are in: what open_convergence_root opens a root of the same files from. */
    private record Base(Path workspace, Map<String, Object> task, Map<String, Object> frame) {
    }

    private static Base releasedRootOf(String owner) throws Exception {
        Path workspace = product();
        String rootId = ok(call("start_workflow", start(workspace), owner)).get("rootId").asString();
        JsonNode current = root(rootId, owner);
        release(owner, rootId);
        return new Base(workspace, new LinkedHashMap<>(MAPPER.convertValue(current.get("taskEnvelope"), new TypeReference<Map<String, Object>>() { })),
                MAPPER.convertValue(current.get("frame"), new TypeReference<Map<String, Object>>() { }));
    }

    private static JsonNode open(String session, Base base) {
        return ok(call("open_convergence_root", openArguments(base), session));
    }

    /** What the opening of a root of the same files sends (a task of its own), made before the call so that two calls can leave together. */
    private static Map<String, Object> openArguments(Base base) {
        Map<String, Object> task = new LinkedHashMap<>(base.task());
        task.put("taskId", "task-" + UUID.randomUUID());
        return arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", task, "frame", base.frame(), "userApprovalRefs", List.of());
    }

    /** A claim of the root with its own task and frame (left out): the plan is the root's task planned. */
    private static JsonNode claim(String session, String rootId) {
        return call("claim_workflow_attempt", claimArguments(session, rootId), session);
    }

    /** What the claim of the root with its own task and frame sends, made before the call so that two calls can leave together. */
    private static Map<String, Object> claimArguments(String session, String rootId) {
        JsonNode current = root(rootId, session);
        JsonNode planned = ok(call("plan_workflow", MAPPER.convertValue(current.get("taskEnvelope"), new TypeReference<Map<String, Object>>() { }), session));
        return arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "plan", MAPPER.convertValue(planned, Map.class),
                "outputTargets", List.of("alpha"), "priorFailure", null);
    }

    private static JsonNode startGuarded(String session, JsonNode lease) {
        return call("start_guarded_workflow", arguments("schemaVersion", "1.0.0", "leaseId", lease.get("leaseId").asString(), "expectedRootRevision", lease.get("rootRevision").asLong(),
                "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test"))), session);
    }

    // ---- F1: the owner continues a root that gave its files up

    @Test
    void theOwnerCannotContinueAnIdleRootWhoseFilesAnotherSessionTookAndCanOnceThoseAreFree() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        assertEquals(1, surfaces(rootId), "the root of an attempt that ended holds its files until another root takes them");

        String taker = ok(call("start_workflow", start(workspace), other)).get("rootId").asString();
        assertEquals(0, surfaces(rootId), "the idle root gave its row of the surface up");
        assertEquals(1, surfaces(taker));
        assertEquals("OPEN", root(rootId, owner).get("state").asString(), "the owner's root is still active and its own");
        long runsBefore = attempts(rootId);

        JsonNode refused = call("start_workflow", continuing(owner, workspace, rootId), owner);
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        JsonNode details = refused.path("error").path("details");
        assertEquals(taker, details.path("rootId").asString(), "the refusal names the root that holds the files: " + refused);
        assertFalse(details.path("ownedBySession").asBoolean(true), refused.toString());
        assertFalse(actionsOf(refused).contains("continue-own-root"), "the files are another workflow's now; continuing the owner's root is no way out: " + actionsOf(refused));
        assertEquals(runsBefore, attempts(rootId), "no run was started");
        assertEquals(0, surfaces(rootId));
        assertEquals(1, surfaces(taker));

        release(other, taker);
        JsonNode continued = ok(call("start_workflow", continuing(owner, workspace, rootId), owner));
        assertEquals(rootId, continued.get("rootId").asString());
        assertEquals(runsBefore + 1, attempts(rootId));
        assertEquals(1, surfaces(rootId), "the continued root holds its files again");
        assertEquals(0, surfaces(taker));
    }

    /** Two idle roots of one file: whichever is continued takes the files, and the other is then refused; the files are never held by both. */
    @Test
    void ofTwoIdleRootsOfOneFileTheOneContinuedFirstHoldsItAndTheOtherIsRefused() throws Exception {
        Path workspace = product();
        String first = session();
        String second = session();
        String firstRoot = startAndEndAttempt(first, workspace).get("rootId").asString();
        String secondRoot = startAndEndAttempt(second, workspace).get("rootId").asString();
        assertEquals(0, surfaces(firstRoot), "the second session's start took the files of the first one's idle root");
        assertEquals(1, surfaces(secondRoot));

        assertEquals(firstRoot, ok(call("start_workflow", continuing(first, workspace, firstRoot), first)).get("rootId").asString());
        assertEquals(1, surfaces(firstRoot));
        assertEquals(0, surfaces(secondRoot), "the root that was continued took the files from the other idle root");

        JsonNode refused = call("start_workflow", continuing(second, workspace, secondRoot), second);
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        assertEquals(firstRoot, refused.path("error").path("details").path("rootId").asString(), refused.toString());
    }

    @Test
    void aClaimOfARootThatGaveItsFilesUpIsRefusedWhileTheyAreHeldAndLeasedWhenTheyAreFree() throws Exception {
        String owner = session();
        String other = session();
        Base base = releasedRootOf(owner);
        // The root was opened and nothing claimed yet, so no run is in progress and no lease issued: another session's start takes its files. (A lease that is still valid does not keep
        // the files; see the report of D-0319 for the rule that would, which the server does not have.)
        String rootId = open(owner, base).get("rootId").asString();
        assertEquals(1, surfaces(rootId));
        String taker = ok(call("start_workflow", start(base.workspace()), other)).get("rootId").asString();
        assertEquals(0, surfaces(rootId));

        JsonNode refused = claim(owner, rootId);
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        assertEquals(taker, refused.path("error").path("details").path("rootId").asString(), refused.toString());
        assertFalse(actionsOf(refused).contains("continue-own-root"), actionsOf(refused).toString());
        assertEquals(0, count("convergence_leases", "root_id = ?", rootId), "nothing was leased");

        release(other, taker);
        JsonNode lease = ok(claim(owner, rootId));
        assertEquals(1, surfaces(rootId), "the claim took the files back");
        ok(startGuarded(owner, lease));
        assertEquals(1, attempts(rootId));
    }

    @Test
    void aGuardedStartWithALeaseIssuedBeforeAnotherRootTookTheFilesIsRefusedWhileTheyAreHeld() throws Exception {
        String owner = session();
        String other = session();
        Base base = releasedRootOf(owner);
        String rootId = open(owner, base).get("rootId").asString();
        JsonNode lease = ok(claim(owner, rootId));
        // The claim leased the attempt but no run is in progress: another session's start takes the files.
        String taker = ok(call("start_workflow", start(base.workspace()), other)).get("rootId").asString();
        assertEquals(0, surfaces(rootId));

        JsonNode refused = startGuarded(owner, lease);
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        assertEquals(taker, refused.path("error").path("details").path("rootId").asString(), refused.toString());
        assertEquals(0, attempts(rootId), "no run was started on the files of another root");
        assertEquals(0, surfaces(rootId));

        // The files are free again, but the give-up moved the root on: the lease of the old revision is no longer its attempt, and a new claim is.
        release(other, taker);
        assertEquals("STALE_REVISION", code(startGuarded(owner, lease)));
        assertEquals(0, surfaces(rootId), "a refused start leaves nothing, not even the surface it took back");
        ok(startGuarded(owner, ok(claim(owner, rootId))));
        assertEquals(1, surfaces(rootId));
        assertEquals(1, attempts(rootId));
    }

    // ---- what the refusal says: the continue is offered when it would take the files, not before

    /**
     * The refusal of a new start of the owner's session that is held up by its own root which gave its files up (its row is another root's): the continue of that root is offered only when
     * the continue would take the files now. The root that took them runs (the continue would be refused as well), then it has ended and the same refusal offers the continue, which holds.
     */
    @Test
    void theRefusalOffersTheContinueOfTheOwnRootThatGaveItsFilesUpOnlyWhenContinuingWouldTakeThemBack() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        // The other session's root covers a part of the files of the owner's root, and runs: it took the row of the idle root.
        String taker = ok(call("start_workflow", startOn(workspace, "alpha/x"), other)).get("rootId").asString();
        assertEquals(0, surfaces(rootId));
        assertEquals(1, surfaces(taker));

        // The owner's new start on another part of the same files meets only its own root (the running one is elsewhere).
        JsonNode blocked = call("start_workflow", startOn(workspace, "alpha/y"), owner);
        assertEquals("ROOT_CONFLICT", code(blocked), blocked.toString());
        JsonNode details = blocked.path("error").path("details");
        assertEquals(rootId, details.path("rootId").asString(), "the refusal names the owner's own root, which holds no row: " + blocked);
        assertTrue(details.path("ownedBySession").asBoolean(false), blocked.toString());
        assertFalse(actionsOf(blocked).contains("continue-own-root"), "the continue would be refused as well while another root runs on its files: " + actionsOf(blocked));
        assertTrue(actionsOf(blocked).contains("restart-with-disjoint-writeTargets"), actionsOf(blocked).toString());
        assertTrue(actionsOf(blocked).contains("release-own-root"), actionsOf(blocked).toString());

        release(other, taker);
        JsonNode offered = call("start_workflow", startOn(workspace, "alpha/y"), owner);
        assertEquals("ROOT_CONFLICT", code(offered), offered.toString());
        assertEquals(rootId, offered.path("error").path("details").path("rootId").asString(), offered.toString());
        assertTrue(actionsOf(offered).contains("continue-own-root"), "the root that took the files has ended, so the continue is offered: " + actionsOf(offered));
        JsonNode action = null;
        for (JsonNode candidate : offered.path("error").path("details").path("nextActions")) {
            if ("continue-own-root".equals(candidate.path("action").asString())) {
                action = candidate;
            }
        }
        assertEquals(rootId, action.path("arguments").path("rootId").asString(), "the action continues the owner's root: " + action);
        assertEquals(0, surfaces(rootId), "a refusal takes nothing");

        // The offered continue holds: it is not another refusal.
        assertEquals(rootId, ok(call("start_workflow", continuing(owner, workspace, rootId), owner)).get("rootId").asString());
        assertEquals(1, surfaces(rootId));
    }

    /**
     * The refusal of a new start reads the owner's own root a second time to know whether continuing it would hold the files (WorkflowService#continuingHoldsFiles). A root that closes
     * between that read of the root and the read of the active roots is no longer among them, and the read ends in STALE_REVISION; the refusal must then be answered as it stands
     * (ROOT_CONFLICT with the root that blocks), without the continue, and not end in that STALE_REVISION.
     *
     * <p>The meeting is made by the test: the first read of the table of the surface rows that the call makes is the one that asks whether the root holds its row, which comes between the
     * two reads, so a lock of that table holds the refusal exactly there (the wait is seen from the database, as in {@link #meetBehind}). The root then closes in a write of the test, the
     * one exception to "states are made by the workflow tools only" of this class: the release of the product ends the root's row of the surface and would wait for the very lock that
     * holds the refusal. What the refusal's second read finds is a root that is no longer active, as it finds it when the release commits there.
     */
    @Test
    void aRefusalWhoseRootClosedBetweenItsTwoReadsIsAnsweredWithoutTheContinue() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        String taker = ok(call("start_workflow", startOn(workspace, "alpha/x"), other)).get("rootId").asString();
        release(other, taker);
        assertEquals(0, surfaces(rootId), "the owner's root gave its row up and nobody holds the files now");
        Map<String, Object> blocked = startOn(workspace, "alpha/y");
        HookRaceLocks.warm(server, 2);
        McpSyncClient client = DomainServerSupport.client(server.port, "codex", Duration.ofSeconds(120));
        JsonNode refused;
        try (HookRaceLocks gate = HookRaceLocks.holding("LOCK TABLE sennard.convergence_active_surfaces IN ACCESS EXCLUSIVE MODE");
                HookRaceLocks.Calls<JsonNode> held = HookRaceLocks.Calls.start(List.<Callable<JsonNode>>of(() -> call(client, "start_workflow", blocked, owner)))) {
            gate.awaitBlocked(1, "the refusal did not come to ask whether the root holds its row", held);
            setRootState(rootId, "ABANDONED");
            gate.release();
            refused = held.answers().getFirst();
        } finally {
            setRootState(rootId, "OPEN");
            client.close();
        }
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        assertEquals(rootId, refused.path("error").path("details").path("rootId").asString(), refused.toString());
        assertFalse(actionsOf(refused).contains("continue-own-root"), "the root closed: there is nothing to continue: " + actionsOf(refused));
        assertTrue(actionsOf(refused).contains("restart-with-disjoint-writeTargets"), actionsOf(refused).toString());
    }

    /** The state column of a root's row, written as the test says (see {@link #aRefusalWhoseRootClosedBetweenItsTwoReadsIsAnsweredWithoutTheContinue}). */
    private static void setRootState(String rootId, String state) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        server.bean(TransactionTemplate.class).executeWithoutResult(status -> jdbc.sql("UPDATE sennard.convergence_roots SET state = ? WHERE root_id = ?").params(state, rootId).update());
    }

    // ---- a claim that is refused leaves the files of the other idle roots as they were

    /**
     * A claim refused with a record (a retry that binds nothing new: NEW_EVIDENCE_REQUIRED) commits that record only. The files of another session's idle root are taken where the lease
     * is issued, so after the refusal that root still holds its row at the revision it had; the claim that is leased takes them.
     */
    @Test
    void aClaimRefusedForItsRetryLeavesTheRowAndTheRevisionOfTheIdleRootThatHoldsTheFiles() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        String holder = startAndEndAttempt(other, workspace).get("rootId").asString();
        assertEquals(0, surfaces(rootId), "the second session's root took the files of the idle root");
        assertEquals(1, surfaces(holder));
        long holderRevision = root(holder, other).get("revision").asLong();
        long leasesBefore = count("convergence_leases", "root_id = ?", rootId);

        // The owner's claim of its next attempt names no failure: the retry is refused, and the refusal is recorded on the owner's root.
        JsonNode refused = claim(owner, rootId);
        assertEquals("NEW_EVIDENCE_REQUIRED", code(refused), refused.toString());
        assertEquals(1, surfaces(holder), "the idle root keeps its row of the files: " + refused);
        assertEquals(0, surfaces(rootId), "and the root that was refused has not taken it");
        assertEquals(holderRevision, root(holder, other).get("revision").asLong(), "the idle root was not written");
        assertEquals("OPEN", root(holder, other).get("state").asString());
        assertEquals(leasesBefore, count("convergence_leases", "root_id = ?", rootId), "nothing was leased");

        // The continue that is leased takes the files from the idle root, as before.
        assertEquals(rootId, ok(call("start_workflow", continuing(owner, workspace, rootId), owner)).get("rootId").asString());
        assertEquals(1, surfaces(rootId));
        assertEquals(0, surfaces(holder));
        assertNotEquals(holderRevision, root(holder, other).get("revision").asLong(), "the give-up is a write of the idle root");
    }

    // ---- F2: a root that waits for a review keeps its files

    /** The root's task with a changed objective: a claim with it changes the frame, which gates the root for a review. */
    private static JsonNode claimChanged(String rootId, String session) {
        JsonNode current = root(rootId, session);
        ObjectNode task = (ObjectNode) current.get("taskEnvelope").deepCopy();
        task.put("objective", task.get("objective").asString() + " Also more.");
        JsonNode planned = ok(call("plan_workflow", MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), session));
        return call("claim_workflow_attempt", claimChangedArguments(rootId, current, task, planned), session);
    }

    private static Map<String, Object> claimChangedArguments(String rootId, JsonNode current, ObjectNode task, JsonNode planned) {
        return arguments("schemaVersion", "1.0.0", "rootId", rootId, "expectedRevision", current.get("revision").asLong(), "taskEnvelope", MAPPER.convertValue(task, Map.class),
                "frame", MAPPER.convertValue(current.get("frame"), Map.class), "plan", MAPPER.convertValue(planned, Map.class), "outputTargets", List.of("alpha"), "priorFailure", null);
    }

    @Test
    void aRootThatWaitsForAReviewStillBlocksEveryOtherSessionAfterItsRunEnded() throws Exception {
        Path workspace = product();
        String owner = session();
        JsonNode started = ok(call("start_workflow", start(workspace), owner));
        String rootId = started.get("rootId").asString();
        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner)));
        assertEquals("NEEDS_REVIEW", root(rootId, owner).get("state").asString());
        ok(call("abort_workflow", arguments("runId", started.get("runId").asString(), "expectedRevision", started.get("revision").asLong()), owner));
        assertEquals("NEEDS_REVIEW", root(rootId, owner).get("state").asString(), "the attempt ended; the root waits for its review");

        JsonNode refused = call("start_workflow", start(workspace), session());
        assertEquals("ROOT_CONFLICT", code(refused), "a root that waits for a review holds its files whether or not a run is in progress: " + refused);
        JsonNode details = refused.path("error").path("details");
        assertEquals(rootId, details.path("rootId").asString(), refused.toString());
        assertEquals("NEEDS_REVIEW", details.path("blockerState").asString(), refused.toString());
        assertTrue(actionsOf(refused).contains("resolve-gate"), actionsOf(refused).toString());
        assertEquals(1, surfaces(rootId), "and it still holds its row of the surface");
    }

    /**
     * A claim that moves an open root to its review holds the files first: a root that waits for a review holds its files until the gate is resolved, so the root that gave its row up takes
     * it back from the idle root that has it, and that root cannot be continued while the gate holds the files. This is also what a claim of the owner that is refused for its review
     * does to another session's idle root: it gives its files to the root that waits for the review (the user guide says so).
     */
    @Test
    void aClaimThatGatesARootThatGaveItsFilesUpTakesThemBackForTheGate() throws Exception {
        Path workspace = product();
        String owner = session();
        String other = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        String holder = startAndEndAttempt(other, workspace).get("rootId").asString();
        assertEquals(0, surfaces(rootId));
        assertEquals(1, surfaces(holder));

        assertEquals("FRAME_REVIEW_REQUIRED", code(claimChanged(rootId, owner)));
        assertEquals("NEEDS_REVIEW", root(rootId, owner).get("state").asString());
        assertEquals(1, surfaces(rootId), "the root that waits for its review holds its row of the files");
        assertEquals(0, surfaces(holder), "and the idle root that held them gave them up");

        JsonNode refused = call("start_workflow", continuing(other, workspace, holder), other);
        assertEquals("ROOT_CONFLICT", code(refused), "the idle root cannot run on the files the gate keeps: " + refused);
        JsonNode details = refused.path("error").path("details");
        assertEquals(rootId, details.path("rootId").asString(), refused.toString());
        assertEquals("NEEDS_REVIEW", details.path("blockerState").asString(), refused.toString());
        assertEquals(0, surfaces(holder));
    }

    // ---- D-0401: a session that does not own a root cannot claim it

    /**
     * A claim of a root that another session owns is refused with ROOT_NOT_OWNED before anything is written, whatever the claim would have done (issue a lease and supersede the owner's,
     * move the files of another session's idle root, move the root to its review). The root, its leases, its row of the surface and the idle root that holds the files are as they were.
     */
    @Test
    void aSessionThatDoesNotOwnARootIsRefusedItsClaimBeforeAnythingIsWritten() throws Exception {
        Path workspace = product();
        String owner = session();
        String holderSession = session();
        String stranger = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        String holder = startAndEndAttempt(holderSession, workspace).get("rootId").asString();
        assertEquals(0, surfaces(rootId), "the owner's root gave its row up to the holder's");
        assertEquals(1, surfaces(holder));
        String rootBefore = root(rootId, owner).toString();
        String holderBefore = root(holder, holderSession).toString();
        String leasesBefore = leasesOf(rootId);

        JsonNode claimed = claim(stranger, rootId);
        assertEquals("INVALID_INPUT", code(claimed), claimed.toString());
        assertEquals("ROOT_NOT_OWNED", reason(claimed), claimed.toString());
        JsonNode changed = claimChanged(rootId, stranger);
        assertEquals("INVALID_INPUT", code(changed), changed.toString());
        assertEquals("ROOT_NOT_OWNED", reason(changed), changed.toString());

        assertEquals(rootBefore, root(rootId, owner).toString(), "the root is as it was: its revision, its state, its gate");
        assertEquals("OPEN", root(rootId, owner).get("state").asString());
        assertEquals(leasesBefore, leasesOf(rootId), "no lease was issued or superseded");
        assertEquals(0, surfaces(rootId), "the surface was not moved to the root");
        assertEquals(1, surfaces(holder), "the idle root keeps its files");
        assertEquals(holderBefore, root(holder, holderSession).toString(), "and was not written");
    }

    /** A root belongs to the session that opened it (before any run starts): that session claims it as before, and another session is refused without a lease being issued. */
    @Test
    void aRootBelongsToTheSessionThatOpenedItAndOnlyThatSessionClaimsIt() throws Exception {
        String opener = session();
        Base base = releasedRootOf(opener);
        String rootId = open(opener, base).get("rootId").asString();
        JsonNode refused = claim(session(), rootId);
        assertEquals("INVALID_INPUT", code(refused), refused.toString());
        assertEquals("ROOT_NOT_OWNED", reason(refused), refused.toString());
        assertEquals(0, count("convergence_leases", "root_id = ?", rootId), "the refused claim issued no lease");
        JsonNode lease = ok(claim(opener, rootId));
        assertEquals(rootId, lease.get("rootId").asString());
        assertEquals(1, count("convergence_leases", "root_id = ? AND state = 'ISSUED'", rootId));
    }

    // ---- the two sessions meet where the writes once crossed (D-0400)

    /** What two calls answered when they met: the first call (held at a row of the gate) and the second (waiting behind the first). */
    private record Met(JsonNode first, JsonNode second) {
    }

    /**
     * Two calls meet at the place where the writes of a root and of a surface once crossed, in an order the test makes and sees from the database, not one left to who is faster (the
     * contract of {@link HookRaceLocks}). The gate is a transaction of the test that holds one row; the first call runs into it holding what it took before that row, the test looks at the
     * database until it says that call waits for the gate, and only then the second call is sent. The second call must wait behind the first one, for a row the first call took before the
     * gate (the order of the product: the root rows by root id, then the surface rows); a second call that took another row first would wait for the gate itself, and the look for its wait
     * behind the first call is never satisfied (a deadline only a failure reaches), or the two end in the database's deadlock. Then the gate rolls back and the answers of both are taken.
     * A scene tells the order of D-0400 from the one before it only when the second call takes a row of the surface itself (a root that holds no row); the scenes of a call whose root holds
     * its row say so in their own words.
     */
    private static Met meetBehind(HookRaceLocks gate, Function<McpSyncClient, JsonNode> first, Function<McpSyncClient, JsonNode> second, String what) throws Exception {
        McpSyncClient firstClient = DomainServerSupport.client(server.port, "codex", Duration.ofSeconds(120));
        McpSyncClient secondClient = DomainServerSupport.client(server.port, "codex", Duration.ofSeconds(120));
        try (HookRaceLocks.Calls<JsonNode> held = HookRaceLocks.Calls.start(List.<Callable<JsonNode>>of(() -> first.apply(firstClient)))) {
            gate.awaitBlocked(1, "the first call did not come to wait for the row of the gate (" + what + ")", held);
            try (HookRaceLocks.Calls<JsonNode> behind = HookRaceLocks.Calls.start(List.<Callable<JsonNode>>of(() -> second.apply(secondClient)))) {
                gate.awaitQueuedBehindBlocked(1, "the second call did not wait behind the first one for the row it took before the gate (" + what + ")", held, behind);
                gate.close();
                return new Met(held.answers().getFirst(), behind.answers().getFirst());
            }
        } finally {
            firstClient.close();
            secondClient.close();
        }
    }

    /** The row of the surface of a root: a transaction that locks it holds up whoever ends the root's hold on it (the take of the root's files by another root). */
    private static HookRaceLocks holdingTheSurfaceRowOf(String rootId) throws Exception {
        return HookRaceLocks.holding("UPDATE sennard.convergence_active_surfaces SET root_id = root_id WHERE root_id = ?", rootId);
    }

    /** The file of an idle root that gave its row up and that nobody holds now: the key of its surface, and a root that has ended and holds no row (the gate inserts the key for it). */
    private record FreeFile(String key, String ended) {
        HookRaceLocks insertingTheKey() throws Exception {
            return HookRaceLocks.holding("INSERT INTO sennard.convergence_active_surfaces (active_surface, root_id) VALUES (?, ?)", key, ended);
        }
    }

    /** Another session takes the file of an idle root and then releases its root: the idle root holds no row, nobody holds the file, and the key of the surface is known. */
    private static FreeFile freeFileOf(String rootId, String taker, Path workspace) {
        String ended = ok(call("start_workflow", start(workspace), taker)).get("rootId").asString();
        String key = textOf("SELECT active_surface FROM sennard.convergence_active_surfaces WHERE root_id = ?", ended);
        release(taker, ended);
        assertEquals(0, surfaces(rootId), "the root holds no row: the session that took its files has released its root");
        assertEquals(0, surfaces(ended));
        return new FreeFile(key, ended);
    }

    /**
     * The owner continues an idle root that still holds its row while another session starts on its file. The start takes the root's row, then the row of the surface, and is held at the
     * surface row by the gate; the continue writes the same root row, so it waits behind the start. The start runs; the continue reports ROOT_CONFLICT and starts no attempt.
     *
     * <p>What this scene guarantees is the answer of the call that lost, with the meeting made by the gate. It does not tell the order of D-0400 from the one before it: the continue of a
     * root that holds its row takes no row of the surface, so before the fix it waited for the same root row behind the same start (one idle root is taken root row first, surface row
     * second, in both orders). Reversing the order to surface rows first does fail it (the start would wait at the gate holding nothing). The order itself is guarded by the scenes of the
     * roots that hold no row below and by {@code WorkflowSurfaceLockOrderTest}.
     */
    @Test
    void aContinueOfAnIdleRootThatHoldsItsRowReportsRootConflictWhenAStartTakesItsFilesFirst() throws Exception {
        Path workspace = product();
        String owner = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        assertEquals(1, surfaces(rootId));
        String key = textOf("SELECT active_surface FROM sennard.convergence_active_surfaces WHERE root_id = ?", rootId);
        Map<String, Object> continuing = continuing(owner, workspace, rootId);
        Map<String, Object> starting = start(workspace);
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = holdingTheSurfaceRowOf(rootId)) {
            met = meetBehind(gate, client -> call(client, "start_workflow", starting, session()), client -> call(client, "start_workflow", continuing, owner), "start on the file, continue of the root that holds it");
        }
        String newRoot = ok(met.first()).get("rootId").asString();
        assertStartConflict(met.second(), newRoot);
        assertEquals(1, surfaces(newRoot), "the start holds the file");
        assertEquals(newRoot, textOf("SELECT root_id FROM sennard.convergence_active_surfaces WHERE active_surface = ?", key));
        assertEquals(0, surfaces(rootId), "the root that was continued gave its row up");
        assertEquals(1, attempts(rootId), "the continue started no attempt");
    }

    /**
     * The owner continues a root that holds no row (the root that took its files has ended; nobody holds them) while a third session starts a new root on the same file. The new root takes
     * the idle root's row and is held at the surface row by the gate; the continue wants the same root row first, so it waits behind the new root and does not take the surface row. The
     * new root runs; the continue reports ROOT_CONFLICT, starts no attempt, and leaves the file with the new root only.
     */
    @Test
    void aContinueOfARootWithoutItsRowReportsRootConflictAfterTheNewRootTakesItsRow() throws Exception {
        Path workspace = product();
        String owner = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        FreeFile file = freeFileOf(rootId, session(), workspace);
        Map<String, Object> continuing = continuing(owner, workspace, rootId);
        Map<String, Object> starting = start(workspace);
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = file.insertingTheKey()) {
            met = meetBehind(gate, client -> call(client, "start_workflow", starting, session()), client -> call(client, "start_workflow", continuing, owner), "new root, continue of the root without a row");
        }
        String newRoot = ok(met.first()).get("rootId").asString();
        assertStartConflict(met.second(), newRoot);
        assertEquals(1, surfaces(newRoot), "the new root holds the file");
        assertEquals(newRoot, textOf("SELECT root_id FROM sennard.convergence_active_surfaces WHERE active_surface = ?", file.key()));
        assertEquals(0, surfaces(rootId));
        assertEquals(1, attempts(rootId), "the continue started no attempt");
    }

    private static void assertStartConflict(JsonNode refused, String blocker) {
        assertEquals("ROOT_CONFLICT", code(refused), refused.toString());
        JsonNode conflict = refused.path("error").path("details");
        assertEquals(blocker, conflict.path("rootId").asString());
        assertEquals("OPEN", conflict.path("blockerState").asString());
        assertEquals("alpha", conflict.path("requestedEntry").asString());
        assertEquals("alpha", conflict.path("existingEntry").asString());
    }

    /** The claim of a root that holds no row, against a new root on its file: the claim waits behind it, and is refused with LEASE_CONFLICT; no lease is issued. */
    @Test
    void aClaimOfARootWithoutItsRowWaitsBehindTheNewRootThatTakesItsRowAndIsRefusedWithLeaseConflict() throws Exception {
        String owner = session();
        Base base = releasedRootOf(owner);
        String rootId = open(owner, base).get("rootId").asString();
        FreeFile file = freeFileOf(rootId, session(), base.workspace());
        Map<String, Object> claiming = claimArguments(owner, rootId);
        Map<String, Object> starting = start(base.workspace());
        String leasesBefore = leasesOf(rootId);
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = file.insertingTheKey()) {
            met = meetBehind(gate, client -> call(client, "start_workflow", starting, session()), client -> call(client, "claim_workflow_attempt", claiming, owner), "new root, claim of the root without a row");
        }
        String newRoot = ok(met.first()).get("rootId").asString();
        assertEquals("LEASE_CONFLICT", code(met.second()), met.second().toString());
        assertEquals(1, surfaces(newRoot), "the new root holds the file");
        assertEquals(0, surfaces(rootId), "the claim took nothing");
        assertEquals(leasesBefore, leasesOf(rootId), "and issued no lease");
    }

    /**
     * The claim that moves the owner's root to its review must hold the files for the gate (a root that waits for a review holds them), so it wants the root rows and the surface row as a
     * claim of the next attempt does, in the same order: it waits behind the new root that takes the root's row, and is refused as a root that moved (STALE_REVISION); the root is not
     * moved to its review and the new root holds the file.
     */
    @Test
    void aClaimThatGatesARootWithoutItsRowWaitsBehindTheNewRootThatTakesItsRowAndFindsTheRootMoved() throws Exception {
        Path workspace = product();
        String owner = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        FreeFile file = freeFileOf(rootId, session(), workspace);
        JsonNode current = root(rootId, owner);
        ObjectNode task = (ObjectNode) current.get("taskEnvelope").deepCopy();
        task.put("objective", task.get("objective").asString() + " Also more.");
        JsonNode planned = ok(call("plan_workflow", MAPPER.convertValue(task, new TypeReference<Map<String, Object>>() { }), owner));
        Map<String, Object> gating = claimChangedArguments(rootId, current, task, planned);
        Map<String, Object> starting = start(workspace);
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = file.insertingTheKey()) {
            met = meetBehind(gate, client -> call(client, "start_workflow", starting, session()), client -> call(client, "claim_workflow_attempt", gating, owner), "new root, claim that gates the root without a row");
        }
        String newRoot = ok(met.first()).get("rootId").asString();
        assertEquals("STALE_REVISION", code(met.second()), met.second().toString());
        assertEquals("OPEN", root(rootId, owner).get("state").asString(), "the root was not moved to its review");
        assertEquals(1, surfaces(newRoot), "the new root holds the file");
        assertEquals(0, surfaces(rootId));
    }

    /**
     * A lease issued to the owner is started while another session opens a new root on the same file (the files of a root nobody runs are given up to it). The new root takes the root's
     * row and is held at the row of the surface the root still holds; the start of the lease wants the same root row, so it waits behind and finds the root given up: LEASE_CONFLICT, no run
     * on the file, and the new root holds it.
     *
     * <p>As the scene of the continue above, it guarantees the answer of the call that lost (LEASE_CONFLICT, no run, who holds the file), not the order: the start of a lease whose root
     * holds its row takes no row of the surface, and it waits for the root row behind the new root in the order before D-0400 too (it took the lease row before, which the new root does
     * not touch). The order of the lease row and the root row is guarded by {@code WorkflowSurfaceLockOrderTest}.
     */
    @Test
    void aGuardedStartOfAnIssuedLeaseIsRefusedWithLeaseConflictWhenANewRootTakesItsFilesFirst() throws Exception {
        String owner = session();
        Base base = releasedRootOf(owner);
        String rootId = open(owner, base).get("rootId").asString();
        JsonNode lease = ok(claim(owner, rootId));
        Map<String, Object> guarded = arguments("schemaVersion", "1.0.0", "leaseId", lease.get("leaseId").asString(), "expectedRootRevision", lease.get("rootRevision").asLong(),
                "skills", List.of(arguments("skillId", "minimal-implementation", "reason", "test")));
        Map<String, Object> opening = openArguments(base);
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = holdingTheSurfaceRowOf(rootId)) {
            met = meetBehind(gate, client -> call(client, "open_convergence_root", opening, session()), client -> call(client, "start_guarded_workflow", guarded, owner), "new root, start of the lease of the root that holds the file");
        }
        String newRoot = ok(met.first()).get("rootId").asString();
        assertEquals("LEASE_CONFLICT", code(met.second()), met.second().toString());
        assertEquals(0, attempts(rootId), "no run was started on the file");
        assertEquals(1, surfaces(newRoot), "the new root holds the file it took");
        assertEquals(0, surfaces(rootId));
    }

    /**
     * Two roots of one file that both gave their rows up (a third session took the file from both and has released its root) are claimed by two sessions at the same moment, and each claim
     * asks the other's root to give its row up. They meet on the smaller root id first, whichever claim started first (the root rows are taken in root id order, the caller's own root among
     * them): the claim of the smaller root is held by the gate at the larger one, and the claim of the larger root waits behind it for the smaller one. The first claim is leased; the second
     * finds the root it wanted to take the row of written by that lease: ROOT_CONFLICT, and nothing it did before stays.
     */
    @Test
    void twoClaimsOfRootsWithoutRowsThatAskEachOtherToGiveTheirRowsUpMeetOnTheSmallerRootFirst() throws Exception {
        Base base = releasedRootOf(session());
        String sessionX = session();
        String sessionY = session();
        String taker = session();
        String rootX = open(sessionX, base).get("rootId").asString();
        String rootY = open(sessionY, base).get("rootId").asString();
        String ended = ok(call("start_workflow", start(base.workspace()), taker)).get("rootId").asString();
        release(taker, ended);
        assertEquals(0, surfaces(rootX));
        assertEquals(0, surfaces(rootY));
        assertEquals(0, surfaces(ended));
        boolean xFirst = rootX.compareTo(rootY) < 0;
        String smaller = xFirst ? rootX : rootY;
        String larger = xFirst ? rootY : rootX;
        String smallerSession = xFirst ? sessionX : sessionY;
        String largerSession = xFirst ? sessionY : sessionX;
        Map<String, Object> claimingSmaller = claimArguments(smallerSession, smaller);
        Map<String, Object> claimingLarger = claimArguments(largerSession, larger);
        long largerRevision = root(larger, largerSession).get("revision").asLong();
        HookRaceLocks.warm(server, 2);
        Met met;
        try (HookRaceLocks gate = HookRaceLocks.holding("UPDATE sennard.convergence_roots SET revision = revision WHERE root_id = ?", larger)) {
            met = meetBehind(gate, client -> call(client, "claim_workflow_attempt", claimingSmaller, smallerSession), client -> call(client, "claim_workflow_attempt", claimingLarger, largerSession),
                    "claim of the smaller root, claim of the larger one");
        }
        JsonNode lease = ok(met.first());
        assertEquals(smaller, lease.get("rootId").asString());
        assertEquals("ROOT_CONFLICT", code(met.second()), met.second().toString());
        assertEquals(1, surfaces(smaller), "the first claim took the surface");
        assertEquals(0, surfaces(larger));
        assertEquals(0, count("convergence_leases", "root_id = ?", larger), "the second claim issued no lease");
        assertNotEquals(largerRevision, root(larger, largerSession).get("revision").asLong(), "the larger root was written once, by the first claim's take of its row");
    }

    @Test
    void aStartOnAnotherFileLeavesAnIdleRootAlone() throws Exception {
        // The give-up is for the files a root covers only: a root of another file is neither asked nor changed.
        Path workspace = product();
        String owner = session();
        String rootId = startAndEndAttempt(owner, workspace).get("rootId").asString();
        long revision = root(rootId, owner).get("revision").asLong();
        Map<String, Object> elsewhere = new LinkedHashMap<>(start(workspace));
        elsewhere.put("writeTargets", List.of("beta"));
        elsewhere.put("changePlan", List.of("beta"));
        String otherRoot = ok(call("start_workflow", elsewhere, session())).get("rootId").asString();
        assertNotEquals(rootId, otherRoot);
        assertEquals(1, surfaces(rootId), "a root of another file keeps its files");
        assertEquals(revision, root(rootId, owner).get("revision").asLong(), "and is not written");
    }
}
