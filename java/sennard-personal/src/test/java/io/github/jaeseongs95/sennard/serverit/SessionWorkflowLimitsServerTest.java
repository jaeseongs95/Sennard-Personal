// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Capacity and related follow-up work through real MCP and the transaction store. */
@Tag("business-db")
class SessionWorkflowLimitsServerTest {
    private static ToolServerSupport server;
    private static ToolServerSupport.Passkey owner;
    private static Path workspaces;
    @BeforeAll static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) repository = repository.getParent();
        workspaces = Files.createDirectories(Path.of("build", "session-capacity", UUID.randomUUID().toString()).toAbsolutePath());
        server = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        owner = server.owner();
    }
    @AfterAll static void close() { if (server != null) server.close(); }
    @BeforeEach void defaults() throws Exception { limit(5); }
    private static void limit(int count) throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "RESOURCE_LIMITS_SET");
        change.putObject("limits").put("csArtifactMaxBytes", 8_388_608).put("maxOpenWorkflowsPerSession", count);
        server.sign(owner, change);
    }
    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(), result.toString());
        return result.path("data");
    }
    private static Map<String,Object> args(boolean change) throws Exception {
        Path workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString()));
        Map<String,Object> args = new LinkedHashMap<>();
        args.put("goal", "Capacity test " + workspace.getFileName());
        args.put("workspace", workspace.toString());
        args.put("skills", List.of(Map.of("skillId", "minimal-implementation", "reason", "Capacity regression")));
        if (change) {
            Files.createDirectories(workspace.resolve("work"));
            args.put("stages", List.of("minimal-implementation"));
            args.put("acceptanceCriteria", List.of("The requested files work"));
            args.put("writeTargets", List.of("work"));
            args.put("changePlan", List.of("work/a.txt"));
        } else args.put("target", workspace.toString());
        return args;
    }
    private static JsonNode open(String session) throws Exception {
        return ok(server.callAsNewHostCall("start_operation_workflow", args(false), session));
    }
    private static Map<String,Object> revision(JsonNode run) {
        return Map.of("runId", run.path("runId").asString(), "expectedRevision", run.path("revision").asLong());
    }
    private static void capacity(JsonNode run, int max, long open) {
        JsonNode c = run.path("sessionCapacity");
        assertEquals(max, c.path("limit").asInt(), run.toString());
        assertEquals(open, c.path("openCount").asLong(), run.toString());
        assertEquals(Math.max(0, max-open), c.path("remaining").asLong(), run.toString());
    }
    private static void reached(JsonNode result, int max, int open) {
        assertFalse(result.path("ok").asBoolean(), result.toString());
        assertEquals("SESSION_WORKFLOW_LIMIT_REACHED", result.path("error").path("details").path("reason").asString(), result.toString());
        assertEquals(max, result.path("error").path("details").path("limit").asInt());
        assertEquals(open, result.path("error").path("details").path("openCount").asInt());
        assertTrue(result.path("error").path("message").asString().contains("resume_workflow"), result.toString());
    }
    private static long count(String session) {
        ContinuityStore store = server.bean(ContinuityStore.class);
        return store.openRootCount(store.opaqueDigest("session", session));
    }

    @Test void defaultFiveAndRemainingSlotsAreSessionScopedAndReplayCreatesNothing() throws Exception {
        String session = server.session();
        Map<String,Object> firstArgs = args(false);
        firstArgs.put("operationKey", "capacity-replay-" + UUID.randomUUID());
        JsonNode first = server.callAsNewHostCall("start_operation_workflow", firstArgs, session);
        capacity(ok(first),5,1);
        assertEquals(first, server.callAsNewHostCall("start_operation_workflow", firstArgs, session));
        for (int i=2;i<=5;i++) capacity(open(session),5,i);
        int before = server.bean(WorkflowStore.class).readActiveRoots().size();
        reached(server.callAsNewHostCall("start_operation_workflow",args(false),session),5,5);
        assertEquals(before, server.bean(WorkflowStore.class).readActiveRoots().size(), "rejected start rolls back its new root");
        assertEquals(5,count(session));
        capacity(open(server.session()),5,1);
    }

    @Test void followUpRevisesTheSamePlanAtCapacityAndPauseRetainsItsSlot() throws Exception {
        String session=server.session();
        JsonNode change=ok(server.callAsNewHostCall("start_workflow",args(true),session));
        for(int i=1;i<5;i++) open(session);
        ok(server.call("resume_workflow",Map.of("runId",change.path("runId").asString()),session));
        JsonNode plan=ok(server.call("submit_change_plan",Map.of("runId",change.path("runId").asString(),"expectedRevision",0,
                "files",List.of("work/a.txt","work/b.txt")),session));
        assertEquals(change.path("runId"),plan.path("runId"));
        assertEquals(5,count(session));
        JsonNode paused=ok(server.call("pause_workflow",Map.of("runId",change.path("runId").asString(),"expectedRevision",0,
                "reason","Wait for user input","resumeCondition","User explicitly resumes"),session));
        reached(server.callAsNewHostCall("start_operation_workflow",args(false),session),5,5);
        assertFalse(server.call("resume_workflow",Map.of("runId",change.path("runId").asString()),session).path("ok").asBoolean());
        ok(server.call("resume_workflow",Map.of("runId",paused.path("runId").asString(),"expectedRevision",paused.path("revision").asLong(),"operationKey","resume-at-capacity-"+UUID.randomUUID()),session));
        assertEquals(5,count(session));
    }

    @Test void loweringPreservesExistingWorkAndCompletionOrCancellationFreesASlot() throws Exception {
        String session=server.session();
        List<JsonNode> runs=new ArrayList<>();
        for(int i=0;i<5;i++) runs.add(open(session));
        limit(2);
        assertEquals(5,count(session));
        ok(server.call("resume_workflow",Map.of("runId",runs.getFirst().path("runId").asString()),session));
        reached(server.callAsNewHostCall("start_operation_workflow",args(false),session),2,5);
        for(int i=0;i<4;i++) ok(server.call("finalize_workflow",revision(runs.get(i)),session));
        capacity(open(session),2,2);
        JsonNode root=ok(server.call("get_convergence_status",Map.of("rootId",runs.get(4).path("rootId").asString()),session)).path("root");
        ok(server.call("release_convergence_root",Map.of("rootId",root.path("rootId").asString(),"expectedRevision",root.path("revision").asLong(),"reason","Cancel test work"),session));
        capacity(open(session),2,2);
    }

    @Test void concurrentDistinctStartsCannotOverbookTheSession() throws Exception {
        limit(1);
        String session=server.session();
        ContinuityStore store=server.bean(ContinuityStore.class);
        server.inTransaction(()->store.ensureTask(store.opaqueDigest("session",session),java.time.Instant.now().toString()));
        var a=args(false); var b=args(false);
        int before=server.bean(WorkflowStore.class).readActiveRoots().size();
        CountDownLatch ready=new CountDownLatch(2), go=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<JsonNode> first=()->{ready.countDown();go.await();return server.callAsNewHostCall("start_operation_workflow",a,session);};
            Callable<JsonNode> second=()->{ready.countDown();go.await();return server.callAsNewHostCall("start_operation_workflow",b,session);};
            var f=executor.submit(first);var g=executor.submit(second);
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            var results=List.of(f.get(60,TimeUnit.SECONDS),g.get(60,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(r->r.path("ok").asBoolean()).count(),results.toString());
            for(var result:results)if(!result.path("ok").asBoolean())reached(result,1,1);
        }
        assertEquals(1,count(session));
        assertEquals(before+1,server.bean(WorkflowStore.class).readActiveRoots().size());
    }
}
