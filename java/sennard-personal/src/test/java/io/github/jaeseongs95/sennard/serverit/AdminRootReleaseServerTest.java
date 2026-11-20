// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.server.admin.AdminPageData;
import io.github.jaeseongs95.sennard.server.workflow.PresenceState;
import io.github.jaeseongs95.sennard.server.workflow.RootOwnerPresence;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A person releases an open convergence root from the admin page when its session cannot. Real Tomcat, real PostgreSQL, the real signed log; the
 * browser's passkey is a software authenticator, and the owner's presence is the server's own reading except where a case needs an owner that is online.
 *
 * <pre>
 * r1   the list                       only the open roots are listed; each has its revision, state, UTC times, newest run, owner references and the owner's presence
 * r2   the owner is online            the list says so, with the host of the owner session
 * r3   no signature                   a commit without a valid assertion (garbage, a key the log does not know, a signature over another intent) releases nothing
 * r4   the release                    the root is abandoned, the run in progress is blocked, and the release records the admin page, the reason, the time and the decision, which names the signed record
 * r5   no run                         a root with no run is released with no run closed
 * r6   refused                        a root that is gone, closed since, or changed since is refused before a passkey is asked, and after a signature it is refused and the log does not grow
 * r7   an owner that is online        the release is possible and records that the owner was online
 * r8   the request's rules            a release whose root ID, revision or reason breaks a rule of the change is a problem naming the field
 * r9   the same request twice         a commit asked again with the same request ID returns the record and releases nothing twice
 * r10  nothing without the passkey    viewing, preparing an intent and looking up a request leave a root open
 * r11  the screen                     the page carries it; its sentences come in the request's language (en by default, ko on Accept-Language) and every key is in both catalogs
 * </pre>
 */
@Tag("business-db")
class AdminRootReleaseServerTest {
    private static final String ADMIN_ACTOR = "admin-page";
    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;
    private static Path workspaces;

    @BeforeAll
    static void start() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repository.resolve("contracts")) || !Files.isDirectory(repository.resolve("skills"))) {
            repository = repository.getParent();
        }
        workspaces = Path.of("build", "root-release-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspaces);
        sennard = ToolServerSupport.startWithSourcesAndSettings(List.of(OnlineOwnerStandIn.class), "--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        owner = sennard.owner();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    /**
     * The personal server reads which sessions are online from the hosts it watches; a test has no host process, so the presence reader answers "unknown" for a root's owner.
     * A stand-in that answers online for one named owner session keeps what that answer causes under test.
     */
    @Configuration(proxyBeanMethods = false)
    static class OnlineOwnerStandIn {
        static volatile String onlineOwner;

        @Bean
        static BeanPostProcessor onlineOwnerStandInProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RootOwnerPresence real)) {
                        return bean;
                    }
                    return (RootOwnerPresence) (rootId, call) -> {
                        String online = onlineOwner;
                        if (online == null) {
                            return real.read(rootId, call);
                        }
                        return new RootOwnerPresence.View(PresenceState.ONLINE, false, true,
                                List.of(new RootOwnerPresence.OwnerSession("codex", online, "codex:" + online, PresenceState.ONLINE, null, null)), null, Instant.now().toString());
                    };
                }
            };
        }
    }

    // ------------------------------------------------------------------------------------------------------------ harness

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static JsonNode ok(JsonNode result) {
        assertTrue(result.path("ok").asBoolean(false), result.toString());
        return result.get("data");
    }

    private record Started(String session, String rootId, String runId) {
    }

    /** A session starts a workflow in a workspace of its own: a root with a run in progress, owned by that session. */
    private static Started startWorkflow() throws Exception {
        Path workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString()));
        String session = sennard.session();
        JsonNode started = ok(sennard.call("start_workflow", arguments("goal", "Change alpha in " + workspace.getFileName(), "stages", List.of("minimal-implementation"), "skills",
                List.of(arguments("skillId", "minimal-implementation", "reason", "test")), "acceptanceCriteria", List.of("The change works."), "workspace", workspace.toString(),
                "writeTargets", List.of("alpha")), session));
        return new Started(session, started.get("rootId").asString(), started.get("runId").asString());
    }

    /** A root with no run: opened, never started. */
    private static Started openRootOnly() throws Exception {
        String workspace = Files.createDirectories(workspaces.resolve(UUID.randomUUID().toString())).toString();
        Map<String, Object> task = arguments("schemaVersion", "1.0.0", "taskId", "release-task-" + UUID.randomUUID(), "objective", "Change a file", "scope", Map.of("included", List.of("f"), "excluded", List.of()),
                "acceptanceCriteria", List.of("a"), "riskLevel", "low", "workUnits", List.of(Map.of("id", "u", "objective", "o", "dependencies", List.of(), "writeTargets", List.of("f.md"))),
                "requiredCapabilities", List.of("task-decomposition"), "constraints", List.of("n"),
                "authorization", Map.of("allowedActions", List.of("t"), "prohibitedActions", List.of("d"), "approvalRequired", List.of()), "decision", Map.of("complexity", "simple", "hasConflicts", false),
                "orchestration", Map.of("requested", true, "mcpAvailable", true));
        String digest = "sha256:" + "a".repeat(64);
        Map<String, Object> frame = arguments("schemaVersion", "1.0.0", "workspace", Map.of("workspaceId", "w", "locator", workspace),
                "controlArtifacts", List.of(Map.of("artifactId", "c", "role", "pass-condition", "locator", "c.md", "digest", digest)),
                "targetArtifacts", List.of(Map.of("artifactId", "t", "role", "target", "locator", "f.md", "digest", digest)),
                "operationalSettings", Map.of("maxAttemptsPerEpoch", 3, "maxEpochs", 2, "leaseTtlSeconds", 3600));
        String session = sennard.session();
        JsonNode opened = ok(sennard.call("open_convergence_root", arguments("schemaVersion", "1.0.0", "parentRootId", null, "taskEnvelope", task, "frame", frame, "userApprovalRefs", List.of()), session));
        return new Started(session, opened.get("rootId").asString(), null);
    }

    private static JsonNode root(String rootId) throws Exception {
        return ok(sennard.call("get_convergence_status", arguments("rootId", rootId), sennard.session())).get("root");
    }

    private static JsonNode run(String runId) throws Exception {
        return ok(sennard.call("get_workflow_status", arguments("runId", runId, "detail", "full"), sennard.session()));
    }

    private static JsonNode listed(String rootId) throws Exception {
        for (JsonNode item : sennard.state().path("openRoots").path("roots")) {
            if (rootId.equals(item.path("rootId").asString())) {
                return item;
            }
        }
        return null;
    }

    private static ObjectNode releaseChange(String rootId, long revision, String reason) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "ROOT_RELEASE").put("rootId", rootId).put("expectedRevision", revision).put("reason", reason);
    }

    private static ObjectNode intentBody(ObjectNode change) {
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode();
        body.set("change", change);
        body.put("bootstrap", false);
        return body;
    }

    private static String code(RawHttp.Response response) {
        return SessionServerSupport.json(response).path("code").asString();
    }

    /** What every refusal of the page's port is: an RFC 9457 problem whose type names the code and whose status is the response's. */
    private static void assertProblem(RawHttp.Response response, int status, String code) {
        JsonNode problem = SessionServerSupport.json(response);
        assertEquals(status, response.status(), response.text());
        assertEquals(status, problem.path("status").asInt(), response.text());
        assertEquals(code, problem.path("code").asString(), response.text());
        assertEquals("urn:sennard:problem:" + code, problem.path("type").asString(), response.text());
        assertFalse(problem.path("detail").asString().isBlank(), response.text());
        assertTrue(response.headers().get("content-type").startsWith("application/problem+json"), response.headers().toString());
    }

    private static long lastSeq() throws Exception {
        return sennard.state().path("lastSeq").asLong();
    }

    private static WorkflowStore.RootApproval decision(String approvalId) {
        return sennard.inTransaction(() -> sennard.bean(WorkflowStore.class).getRootApproval(approvalId));
    }

    // ---------------------------------------------------------------------------------------------------------- the list

    @Test
    void onlyTheOpenRootsAreListedWithTheirRunOwnerAndTimes() throws Exception {
        Started open = startWorkflow();
        Started closed = startWorkflow();
        JsonNode closedRoot = root(closed.rootId());
        ok(sennard.call("release_convergence_root", arguments("rootId", closed.rootId(), "expectedRevision", closedRoot.get("revision").asLong(), "reason", "done"), closed.session()));

        assertNull(listed(closed.rootId()), "a released root is not open");
        JsonNode item = listed(open.rootId());
        assertNotNull(item, sennard.state().path("openRoots").toString());
        assertEquals(root(open.rootId()).get("revision").asLong(), item.path("revision").asLong());
        assertEquals("OPEN", item.path("state").asString());
        assertTrue(item.path("objective").asString().contains("alpha"), item.toString());
        assertEquals(open.runId(), item.path("run").path("runId").asString());
        assertFalse(item.path("run").path("state").asString().isBlank());
        Instant created = Instant.parse(item.path("createdAt").asString());
        assertTrue(item.path("createdAt").asString().endsWith("Z"), "the times are UTC text: the page converts them");
        assertFalse(Instant.parse(item.path("lastActivityAt").asString()).isBefore(created));
        assertTrue(item.path("ownerBound").asBoolean());
        assertEquals("unknown", item.path("ownerPresence").asString(), "no live owner session was found");
        assertEquals(1, item.path("owners").size());
        assertTrue(item.path("owners").get(0).path("ref").asString().matches("[0-9a-f]{12}"), "the first characters of the owner's digest, not the hmac-sha256: scheme in front of it: "
                + item.path("owners").get(0));
        assertFalse(item.path("owners").get(0).path("ref").asString().contains(open.session()));
        assertTrue(item.path("owners").get(0).path("host").isNull() || item.path("owners").get(0).path("host").isMissingNode(), "only an online session has a host to show");
    }

    @Test
    void anOnlineOwnerIsShownWithItsHost() throws Exception {
        Started started = startWorkflow();
        OnlineOwnerStandIn.onlineOwner = started.session();
        try {
            JsonNode item = listed(started.rootId());
            assertEquals("online", item.path("ownerPresence").asString(), item.toString());
            assertEquals("codex", item.path("owners").get(0).path("host").asString());
        } finally {
            OnlineOwnerStandIn.onlineOwner = null;
        }
    }

    // ------------------------------------------------------------------------------------------------------- no signature

    @Test
    void withoutAValidSignatureNothingIsReleased() throws Exception {
        Started started = startWorkflow();
        long revision = root(started.rootId()).get("revision").asLong();
        long seq = lastSeq();
        ObjectNode change = releaseChange(started.rootId(), revision, "The owner session ended.");
        String intent = sennard.prepare(change, false).path("intent").asString();

        ObjectNode garbage = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", owner.credentialId).put("authenticatorData", "AA").put("clientDataJSON", "AA").put("signature", "AA");
        assertEquals(400, sennard.commit(intent, garbage, UUID.randomUUID().toString(), null, null).status(), "an assertion that is no assertion");

        ToolServerSupport.Passkey stranger = new ToolServerSupport.Passkey();
        RawHttp.Response unknownKey = sennard.commit(intent, stranger.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals(403, unknownKey.status(), unknownKey.text());
        assertEquals("ADMIN_KEY_UNKNOWN", code(unknownKey));

        String otherIntent = sennard.prepare(releaseChange(started.rootId(), revision, "Another reason."), false).path("intent").asString();
        RawHttp.Response wrongIntent = sennard.commit(intent, owner.assertion(otherIntent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals(400, wrongIntent.status(), wrongIntent.text());
        assertEquals("ADMIN_CHALLENGE_MISMATCH", code(wrongIntent), "a signature over another intent");

        assertEquals("OPEN", root(started.rootId()).get("state").asString());
        assertFalse(run(started.runId()).get("state").asString().equals("BLOCKED"), "the run goes on");
        assertEquals(seq, lastSeq(), "the log did not grow");
        assertNotNull(listed(started.rootId()));
    }

    // ----------------------------------------------------------------------------------------------------------- the release

    @Test
    void aSignedReleaseClosesTheRootBlocksTheRunAndRecordsWhoWhyAndOnWhichRecord() throws Exception {
        Started started = startWorkflow();
        long revision = root(started.rootId()).get("revision").asLong();
        String reason = "The owner session ended; released on the admin page.";

        JsonNode committed = sennard.sign(owner, releaseChange(started.rootId(), revision, "  " + reason + " "));
        assertEquals("rootRelease:" + started.rootId(), committed.path("record").path("target").asString());
        String recordHash = committed.path("record").path("recordHash").asString();

        JsonNode closed = root(started.rootId());
        assertEquals("ABANDONED", closed.get("state").asString());
        JsonNode release = closed.get("release");
        assertEquals(ADMIN_ACTOR, release.get("releasedBy").asString());
        assertFalse(release.get("releasedByOwner").asBoolean(true));
        assertEquals("OPEN", release.get("previousState").asString());
        assertEquals("unknown", release.get("ownerPresence").asString(), "the owner's presence as it was read is kept");
        assertEquals(reason, release.get("reason").asString(), "the reason is kept without the space around it");
        assertEquals(started.runId(), release.get("closedRunId").asString());
        assertTrue(Instant.parse(release.get("releasedAt").asString()).isAfter(Instant.now().minusSeconds(300)));
        assertEquals(1, release.get("userApprovalRefs").size());

        JsonNode blocked = run(started.runId());
        assertEquals("BLOCKED", blocked.get("state").asString());
        assertTrue(blocked.get("blockers").toString().contains("root-released"), blocked.toString());

        WorkflowStore.RootApproval approval = decision(release.get("userApprovalRefs").get(0).asString());
        JsonNode record = ToolServerSupport.MAPPER.readTree(approval.recordJson());
        assertEquals("ADMIN_PAGE", record.path("source").path("kind").asString());
        assertEquals(recordHash, record.path("source").path("recordHash").asString(), "the decision names the record the passkey signed");
        assertEquals(owner.credentialId, record.path("source").path("credentialId").asString());
        assertEquals("RELEASE_ROOT", record.path("scope").path("action").asString());
        assertEquals(started.rootId(), record.path("scope").path("rootId").asString());
        assertEquals(ADMIN_ACTOR, record.path("scope").path("actorId").asString());
        assertTrue(record.has("revokedAt") && record.get("revokedAt").isNull(), "the key is there until it is revoked");

        assertNull(listed(started.rootId()), "the root is no longer open");
        JsonNode repeated = sennard.call("release_convergence_root", arguments("rootId", started.rootId(), "expectedRevision", closed.get("revision").asLong(), "reason", "again"), started.session());
        assertEquals("ROOT_NOT_ACTIVE", repeated.path("error").path("details").path("reason").asString(), "the tool sees a closed root as it always did");
    }

    @Test
    void aRootWithNoRunIsReleasedWithNoRunClosed() throws Exception {
        Started started = openRootOnly();
        JsonNode before = listed(started.rootId());
        assertNotNull(before, sennard.state().path("openRoots").toString());
        assertTrue(before.path("run").isNull() || before.path("run").isMissingNode(), "no run is attached");
        sennard.sign(owner, releaseChange(started.rootId(), before.path("revision").asLong(), "Never started."));
        JsonNode release = root(started.rootId()).get("release");
        assertTrue(release.get("closedRunId").isNull());
        assertEquals("ABANDONED", root(started.rootId()).get("state").asString());
    }

    // ----------------------------------------------------------------------------------------------------------- refusals

    @Test
    void aRootThatIsGoneClosedOrChangedIsRefusedBeforeAndAfterTheSignature() throws Exception {
        Started gone = startWorkflow();
        long seq = lastSeq();
        assertProblem(sennard.post("/api/intent", intentBody(releaseChange("root-no-such", 1, "r"))), 404, "ADMIN_ROOT_NOT_FOUND");
        assertProblem(sennard.post("/api/intent", intentBody(releaseChange(gone.rootId(), root(gone.rootId()).get("revision").asLong() + 7, "r"))), 409, "ADMIN_ROOT_STALE");

        long revision = root(gone.rootId()).get("revision").asLong();
        // The root is closed after the intent was prepared: the signature is refused, not recorded.
        String intent = sennard.prepare(releaseChange(gone.rootId(), revision, "r"), false).path("intent").asString();
        ok(sennard.call("release_convergence_root", arguments("rootId", gone.rootId(), "expectedRevision", revision, "reason", "The owner released it first."), gone.session()));
        RawHttp.Response late = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertProblem(late, 409, "ADMIN_ROOT_NOT_ACTIVE");
        assertEquals(seq, lastSeq(), "the log did not grow");

        assertProblem(sennard.post("/api/intent", intentBody(releaseChange(gone.rootId(), revision + 1, "r"))), 409, "ADMIN_ROOT_NOT_ACTIVE");
        JsonNode release = root(gone.rootId()).get("release");
        assertTrue(release.get("releasedByOwner").asBoolean(), "the owner's release stands, the page's was never made");
    }

    // ------------------------------------------------------------------------------------------------- an owner that is online

    @Test
    void anOnlineOwnerDoesNotStopTheUserAndTheReleaseRecordsThatItWasOnline() throws Exception {
        Started started = startWorkflow();
        OnlineOwnerStandIn.onlineOwner = started.session();
        try {
            long revision = listed(started.rootId()).path("revision").asLong();
            sennard.sign(owner, releaseChange(started.rootId(), revision, "The user decided."));
        } finally {
            OnlineOwnerStandIn.onlineOwner = null;
        }
        JsonNode release = root(started.rootId()).get("release");
        assertEquals("online", release.get("ownerPresence").asString());
        List<String> actors = new ArrayList<>();
        release.get("ownerActors").forEach(actor -> actors.add(actor.asString()));
        assertEquals(List.of("codex:" + started.session()), actors);
        assertEquals("ABANDONED", root(started.rootId()).get("state").asString());
        assertEquals("BLOCKED", run(started.runId()).get("state").asString());
    }

    // ---------------------------------------------------------------------------------------------- the request's rules

    @Test
    void aReleaseThatBreaksARuleOfTheChangeIsAProblemNamingTheField() throws Exception {
        Started started = startWorkflow();
        long revision = root(started.rootId()).get("revision").asLong();
        List<ObjectNode> broken = new ArrayList<>();
        broken.add(releaseChange(started.rootId(), revision, "   "));
        broken.add(releaseChange(started.rootId(), revision, "r".repeat(2001)));
        broken.add(releaseChange("root with space", revision, "r"));
        broken.add(releaseChange(started.rootId(), -1, "r"));
        broken.add(releaseChange(started.rootId(), revision, "r").put("extra", true));
        ObjectNode noReason = releaseChange(started.rootId(), revision, "r");
        noReason.remove("reason");
        broken.add(noReason);
        for (ObjectNode change : broken) {
            RawHttp.Response refused = sennard.post("/api/intent", intentBody(change));
            assertProblem(refused, 400, "ADMIN_CHANGE_INVALID");
        }
        assertEquals(400, sennard.post("/api/commit", ToolServerSupport.MAPPER.createObjectNode().put("intent", "x")).status(), "a commit without the rest of its body is the page's own refusal");
        assertEquals("OPEN", root(started.rootId()).get("state").asString());
    }

    @Test
    void theSameRequestAskedTwiceReleasesOnce() throws Exception {
        Started started = startWorkflow();
        long revision = root(started.rootId()).get("revision").asLong();
        String intent = sennard.prepare(releaseChange(started.rootId(), revision, "Once."), false).path("intent").asString();
        JsonNode assertion = owner.assertion(intent, sennard.adminOrigin);
        String requestId = UUID.randomUUID().toString();
        JsonNode first = SessionServerSupport.json(sennard.commit(intent, assertion, requestId, null, null));
        assertFalse(first.path("duplicate").asBoolean(true));
        long afterFirst = root(started.rootId()).get("revision").asLong();
        JsonNode second = SessionServerSupport.json(sennard.commit(intent, assertion, requestId, null, null));
        assertTrue(second.path("duplicate").asBoolean(false));
        assertEquals(first.path("record").path("recordHash").asString(), second.path("record").path("recordHash").asString());
        assertEquals(afterFirst, root(started.rootId()).get("revision").asLong(), "the root was released once");
    }

    // ------------------------------------------------------------------------------------- nothing without the passkey

    @Test
    void viewingPreparingAndLookingUpLeaveARootOpen() throws Exception {
        Started started = startWorkflow();
        long revision = root(started.rootId()).get("revision").asLong();
        long seq = lastSeq();
        sennard.state();
        assertEquals(200, sennard.get("/api/skill-usage?days=7").status());
        sennard.prepare(releaseChange(started.rootId(), revision, "Prepared only."), false);
        assertEquals(200, sennard.get("/api/request/" + UUID.randomUUID()).status());
        assertEquals("OPEN", root(started.rootId()).get("state").asString());
        assertEquals(revision, root(started.rootId()).get("revision").asLong());
        assertEquals(seq, lastSeq());
        assertNotEquals("BLOCKED", run(started.runId()).get("state").asString());
    }

    // --------------------------------------------------------------------------------------------------------- the screen

    @Test
    void thePageCarriesTheScreenAndItsSentencesComeInTheRequestsLanguage() throws Exception {
        String page = sennard.get("/").text();
        assertTrue(page.contains("viewRoots") && page.contains("ROOT_RELEASE") && page.contains("openRoots"), "the screen and its signed change are in the page");

        // D-0228: the sentences are the page's catalog messages (admin.page.rootRelease.*), sent as patterns in the request's language like every other sentence of the page; the
        // state carries no sentences of its own.
        assertTrue(sennard.state().path("openRoots").path("texts").isMissingNode(), "the open roots have no server-made sentences");
        JsonNode defaults = sennard.pageTexts(null);
        JsonNode inKorean = sennard.pageTexts("ko");
        int sentences = 0;
        for (String key : defaults.propertyNames()) {
            if (key.startsWith("admin.page.rootRelease.")) {
                sentences++;
                assertFalse(defaults.path(key).asString().isBlank(), "an English sentence for " + key);
                assertNotEquals(defaults.path(key).asString(), inKorean.path(key).asString(), "a Korean sentence for " + key);
            }
        }
        assertEquals(48, sentences, "the screen's sentences");
        assertEquals("Open workflows", defaults.path("admin.page.rootRelease.tab").asString());
        assertEquals("열린 workflow", inKorean.path("admin.page.rootRelease.tab").asString());
        assertTrue(defaults.path("admin.page.rootRelease.done").asString().contains("{rootId}"), "the page fills in the placeholder");
        for (String code : List.of("ADMIN_ROOT_NOT_FOUND", "ADMIN_ROOT_NOT_ACTIVE", "ADMIN_ROOT_STALE")) {
            assertFalse(defaults.path("admin.page.error." + code).asString().isBlank(), "the page has a sentence for " + code);
        }
    }
}
