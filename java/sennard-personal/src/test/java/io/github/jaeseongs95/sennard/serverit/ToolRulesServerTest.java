// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.service.store.modules.TrustStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The business rules of these tools, through the real tool calls on the real store. Each test takes its own sessions. */
@Tag("business-db")
class ToolRulesServerTest {
    private static final String ONE = "1.0.0";
    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
        owner = sennard.owner();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", ONE);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void assertRefused(JsonNode answer, String code, String reasonCode) {
        assertEquals(code, answer.path("error").path("code").asString(), answer.toString());
        if (reasonCode != null) {
            assertEquals(reasonCode, answer.path("error").path("details").path("reasonCode").asString(), answer.toString());
        }
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
    }

    private static String registerRole(String name) throws Exception {
        String id = "role-" + UUID.randomUUID();
        sennard.sign(owner, ToolServerSupport.roleAdd(id, name));
        return id;
    }

    private static void bound(String session, String role) throws Exception {
        JsonNode requested = data(sennard.call("bind_session_role", args("role", role), session));
        sennard.approve(owner, requested.path("proposalId").asString());
    }

    private static String globalToken() throws Exception {
        JsonNode global = sennard.state().path("global");
        return global.isNull() ? null : global.path("stateToken").asString();
    }

    private static Map<String, Object> checkpoint(Object token, String body) {
        return args("expectedStateToken", token, "body", body);
    }

    // ---------------------------------------------------------------------------------------------------- shared context

    @Test
    void aGlobalChangeWaitsForTheUsersSignatureAndAStaleOrOversizedRequestIsRefused() throws Exception {
        String session = sennard.session();
        String token = globalToken();
        String atLimit = "가".repeat(1333) + "a";
        assertEquals(4000, atLimit.getBytes(StandardCharsets.UTF_8).length);
        JsonNode oversize = sennard.call("checkpoint_global_context", checkpoint(token, atLimit + "c"), session);
        assertRefused(oversize, "INVALID_INPUT", "CONTEXT_LAYER_TOO_LARGE");
        assertEquals(4001, oversize.path("error").path("details").path("actualBytes").asInt());
        assertEquals(4000, oversize.path("error").path("details").path("maxBytes").asInt());

        JsonNode requested = data(sennard.call("checkpoint_global_context", checkpoint(token, atLimit), session));
        assertTrue(requested.path("pending").asBoolean());
        assertEquals(sennard.adminOrigin, requested.path("adminUrl").asString());
        assertEquals(token == null ? "null" : token, String.valueOf(sennard.state().path("global").path("stateToken").asString(null)), "nothing applies before the signature");
        assertNotEquals(atLimit, data(sennard.call("load_shared_context", args(), session)).path("global").path("body").asString(""));

        sennard.approve(owner, requested.path("proposalId").asString());
        JsonNode loaded = data(sennard.call("load_shared_context", args(), session));
        assertEquals(atLimit, loaded.path("global").path("body").asString());

        JsonNode stale = sennard.call("checkpoint_global_context", checkpoint(token, "lost the race"), session);
        assertRefused(stale, "STALE_REVISION", "CONTEXT_LAYER_STATE_STALE");
        assertEquals(loaded.path("global").path("stateToken").asString(), stale.path("error").path("details").path("actualStateToken").asString());
    }

    @Test
    void aSessionKeepsOnePendingRequestPerTargetAndARejectedRequestAppliesNothing() throws Exception {
        String session = sennard.session();
        String token = globalToken();
        String older = data(sennard.call("checkpoint_global_context", checkpoint(token, "old"), session)).path("proposalId").asString();
        String newer = data(sennard.call("checkpoint_global_context", checkpoint(token, "new"), session)).path("proposalId").asString();
        List<String> pending = new ArrayList<>();
        sennard.state().path("proposals").forEach(item -> pending.add(item.path("id").asString()));
        assertFalse(pending.contains(older), pending.toString());
        assertTrue(pending.contains(newer), pending.toString());
        assertEquals(200, sennard.post("/api/proposals/" + newer + "/reject", ToolServerSupport.MAPPER.createObjectNode()).status());
        assertEquals(token, globalToken(), "a rejected request applies nothing");
    }

    /** AGS3-D-0267: a role save that comes again with its operationKey is answered with the first save; the same body under a new key meets the moved token. */
    @Test
    void aRoleSaveThatComesAgainWithItsKeyIsAnsweredWithTheFirstSaveAndAnotherBodyIsAConflict() throws Exception {
        String a = sennard.session();
        String name = "키-리뷰어-" + UUID.randomUUID();
        registerRole(name);
        sennard.approve(owner, data(sennard.call("bind_session_role", args("role", name), a)).path("proposalId").asString());
        String key = OperationKeys.fresh();
        Map<String, Object> save = new java.util.HashMap<>(checkpoint(null, "the keyed role body"));
        save.put("operationKey", key);
        JsonNode first = data(sennard.call("checkpoint_role_context", save, a));
        JsonNode again = data(sennard.call("checkpoint_role_context", save, a));
        assertEquals(first, again, "the first save again, though the role's token has moved on");
        Map<String, Object> newKey = new java.util.HashMap<>(save);
        newKey.put("operationKey", OperationKeys.fresh());
        assertRefused(sennard.call("checkpoint_role_context", newKey, a), "STALE_REVISION", "CONTEXT_LAYER_STATE_STALE");
        Map<String, Object> other = new java.util.HashMap<>(save);
        other.put("body", "another body");
        JsonNode conflict = sennard.call("checkpoint_role_context", other, a);
        assertEquals("REQUEST_CONFLICT", conflict.path("error").path("code").asString(), conflict.toString());
        assertEquals("OPERATION_CONFLICT", conflict.path("error").path("details").path("reason").asString(), conflict.toString());
    }

    @Test
    void aSessionBindsOnlyToARegisteredRoleWithTheUsersSignatureAndUnbindingEndsAccess() throws Exception {
        String a = sennard.session();
        String b = sennard.session();
        String c = sennard.session();
        JsonNode unbound = sennard.call("checkpoint_role_context", checkpoint(null, "x"), a);
        assertRefused(unbound, "BINDING_REQUIRED", "ROLE_NOT_BOUND");
        assertTrue(unbound.path("error").path("details").path("adminUrl").isString());
        String name = "리뷰어-" + UUID.randomUUID();
        JsonNode unknown = sennard.call("bind_session_role", args("role", name), a);
        assertRefused(unknown, "INVALID_INPUT", "ROLE_NOT_REGISTERED");

        registerRole(name);
        JsonNode requested = data(sennard.call("bind_session_role", args("role", " " + name + " "), a));
        assertTrue(requested.path("pending").asBoolean());
        assertTrue(data(sennard.call("load_shared_context", args(), a)).path("binding").isNull(), "not bound before the signature");
        sennard.approve(owner, requested.path("proposalId").asString());
        bound(b, name);

        JsonNode saved = data(sennard.call("checkpoint_role_context", checkpoint(null, "근거 없는 판정 금지"), a));
        String token = saved.path("stateToken").asString();
        JsonNode other = data(sennard.call("load_shared_context", args(), b));
        assertEquals(name, other.path("binding").path("role").asString());
        assertEquals("근거 없는 판정 금지", other.path("role").path("body").asString());
        assertRefused(sennard.call("checkpoint_role_context", checkpoint(null, "덮어쓰기"), b), "STALE_REVISION", "CONTEXT_LAYER_STATE_STALE");
        assertTrue(data(sennard.call("load_shared_context", args(), c)).path("role").isNull(), "a session of no role reads none");

        JsonNode unbind = data(sennard.call("bind_session_role", args("role", null), a));
        sennard.approve(owner, unbind.path("proposalId").asString());
        assertRefused(sennard.call("checkpoint_role_context", checkpoint(token, "y"), a), "BINDING_REQUIRED", "ROLE_NOT_BOUND");
    }

    @Test
    void aRoleBodyIsLimitedInUtf8BytesAndARoleNameMustBeOneLine() throws Exception {
        String session = sennard.session();
        String name = "한도-" + UUID.randomUUID();
        registerRole(name);
        bound(session, name);
        String atLimit = "가".repeat(666) + "ab";
        assertEquals(2000, atLimit.getBytes(StandardCharsets.UTF_8).length);
        JsonNode saved = data(sennard.call("checkpoint_role_context", checkpoint(null, atLimit), session));
        assertEquals(2000, saved.path("bytes").asInt());
        JsonNode oversize = sennard.call("checkpoint_role_context", checkpoint(saved.path("stateToken").asString(), atLimit + "b"), session);
        assertRefused(oversize, "INVALID_INPUT", "CONTEXT_LAYER_TOO_LARGE");
        assertEquals(2000, oversize.path("error").path("details").path("maxBytes").asInt());
        assertRefused(sennard.call("bind_session_role", args("role", "a\nb"), session), "INVALID_INPUT", "ROLE_NAME_INVALID");
    }

    @Test
    void aStoredRoleBodyThatFailsItsDigestIsAnIntegrityFailure() throws Exception {
        String session = sennard.session();
        String name = "변조-" + UUID.randomUUID();
        String roleId = registerRole(name);
        bound(session, name);
        data(sennard.call("checkpoint_role_context", checkpoint(null, "ok"), session));
        SessionServerSupport.update(sennard.server, "UPDATE sennard.role_contexts SET body = 'tampered' WHERE role_id = ?", roleId);
        JsonNode refused = sennard.call("load_shared_context", args(), session);
        assertRefused(refused, "INTEGRITY_FAILED", "CONTEXT_LAYER_INTEGRITY_FAILED");
        assertEquals("role", refused.path("error").path("details").path("layer").asString(), refused.toString());
        assertFalse(refused.toString().contains("tampered"), "the altered body is not handed back");
    }

    // ------------------------------------------------------------------------------------------------------ session board

    private static JsonNode row(JsonNode listing, String session) {
        for (JsonNode item : listing.path("sessions")) {
            if (session.equals(item.path("sessionId").asString())) {
                return item;
            }
        }
        return null;
    }

    @Test
    void aSummaryIsOneBoundedLineAndARoleOnceWrittenIsKeptUntilReplaced() throws Exception {
        String session = sennard.session();
        for (String bad : List.of("", "   ", "둘\n줄", "a\rb", "x".repeat(201))) {
            assertRefused(sennard.call("update_session_status", args("summary", bad), session), "INVALID_INPUT", null);
        }
        assertEquals("x".repeat(200), data(sennard.call("update_session_status", args("summary", "x".repeat(200)), session)).path("summary").asString());
        JsonNode trimmed = data(sennard.call("update_session_status", args("summary", " " + "x".repeat(198) + " "), session));
        assertEquals("x".repeat(198), trimmed.path("summary").asString());
        assertTrue(trimmed.path("role").isNull(), "a session that never wrote a role has none");
        JsonNode roled = data(sennard.call("update_session_status", args("summary", "Sennard 결함 수정", "role", " Sennard 담당 "), session));
        assertEquals("Sennard 담당", roled.path("role").asString());
        assertEquals("Sennard 담당", data(sennard.call("update_session_status", args("summary", "다음 결함"), session)).path("role").asString());
        for (String bad : List.of("", "   ", "둘\n줄", "x".repeat(121))) {
            assertRefused(sennard.call("update_session_status", args("summary", "거절", "role", bad), session), "INVALID_INPUT", null);
        }
        assertEquals("다음 결함", row(data(sennard.call("list_session_status", args(), session)), session).path("summary").asString());
    }

    @Test
    void theListShowsRunningSessionsSelfFirstAndTheSignedRoleOverTheSelfWrittenOne() throws Exception {
        String me = sennard.session();
        String signedOne = sennard.session();
        String selfOne = sennard.session();
        String name = "승인된 역할-" + UUID.randomUUID();
        registerRole(name);
        bound(signedOne, name);
        data(sennard.call("update_session_status", args("summary", "일함", "role", "자칭 담당"), signedOne));
        data(sennard.call("update_session_status", args("summary", "일함", "role", "자칭 담당"), selfOne));

        JsonNode listing = data(sennard.call("list_session_status", args(), me));
        JsonNode first = listing.path("sessions").get(0);
        assertEquals(me, first.path("sessionId").asString());
        assertTrue(first.path("current").asBoolean());
        assertEquals("ok", listing.path("board").path("status").asString());
        assertEquals(name, row(listing, signedOne).path("role").asString());
        assertEquals("signed", row(listing, signedOne).path("roleSource").asString());
        assertEquals("self", row(listing, selfOne).path("roleSource").asString());
    }

    /** L6: the working folder the start carried is on the board before the session's first prompt (it showed none until a prompt). */
    @Test
    void aSessionThatHasOnlyStartedShowsItsWorkingFolder() throws Exception {
        String me = sennard.session();
        String started = sennard.session();
        JsonNode listed = row(data(sennard.call("list_session_status", args(), me)), started);
        assertEquals("C:\\ags-tests\\hp-v600-probe-r1\\cases\\codex-a\\proj", listed.path("cwd").asString(), listed.toString());
        assertTrue(listed.path("lastPromptAt").isNull(), "a start is not a prompt: " + listed);
    }

    @Test
    void aSummaryIsStaleAgainstANewerPromptAndAnEndedSessionIsNotListed() throws Exception {
        String me = sennard.session();
        String ending = sennard.session();
        data(sennard.call("update_session_status", args("summary", "끝날 세션"), ending));
        assertTrue(row(data(sennard.call("list_session_status", args(), me)), ending).path("summaryStale").isNull(), "no prompt observed: unknown, never false");
        Thread.sleep(5); // the prompt is recorded with the clock's time: later than the summary above, by more than the stored millisecond
        sennard.inTransaction(() -> {
            sennard.bean(BoardService.class).recordInput("codex", ending, "/work/a", true);
            return null;
        });
        assertTrue(row(data(sennard.call("list_session_status", args(), me)), ending).path("summaryStale").asBoolean());

        SessionServerSupport.post(sennard.server.port, "/hooks/v1/codex/SessionEnd", SessionServerSupport.hook("windows", "codex", "SessionEnd", ending, "none"));
        SessionServerSupport.await(() -> {
            JsonNode listing = data(sennard.call("list_session_status", args(), me));
            return row(listing, ending) == null ? java.util.Optional.of(true) : java.util.Optional.empty();
        }, "the ended session to leave the list");
    }

    // ------------------------------------------------------------------------------------------------------ trust

    private static String receipt(String session, String origin, Instant expires, boolean forged) throws Exception {
        String id = "source-" + UUID.randomUUID();
        return sennard.inTransaction(() -> {
            try {
                TrustStore store = sennard.bean(TrustStore.class);
                io.github.jaeseongs95.sennard.domain.secret.SecretValue key = store.requireSigningKey();
                ObjectNode json = ToolServerSupport.MAPPER.createObjectNode().put("receiptId", id).put("host", "codex").put("sessionId", session).put("eventId", id).put("originKind", origin)
                        .put("authorityEffect", "none").put("observedAt", Instant.now().toString()).put("expiresAt", expires.toString());
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(key.revealBytes()), "HmacSHA256"));
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(CanonicalJson.of(json).getBytes(StandardCharsets.UTF_8)));
                json.put("integrityToken", forged ? "x".repeat(43) : token);
                store.recordInputSource(new TrustStore.Receipt(id, "codex", session, id, origin, "none", json.path("observedAt").asString(), expires.toString(), json.toString()), prior -> true);
                return id;
            } catch (java.security.GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
    }

    private static JsonNode validate(Map<String, Object> decision, String session) {
        return data(sennard.call("validate_collaboration_decision", new HashMap<>(Map.of("decision", decision)), session));
    }

    @Test
    void capabilitiesClaimNoAuthorityAndAMissingReceiptStaysUnverified() throws Exception {
        JsonNode capabilities = data(sennard.call("get_trust_capabilities", Map.of(), sennard.session()));
        assertFalse(capabilities.path("directUserInputAttestation").asBoolean(true));
        assertFalse(capabilities.path("authorityIssuance").asBoolean(true));
        assertFalse(capabilities.path("scopedDelegation").asBoolean(true));

        String session = sennard.session();
        Map<String, Object> decision = ToolContractServerTest.decision();
        decision.put("sourceOriginKind", "artifact");
        JsonNode checked = validate(decision, session);
        assertEquals("valid", checked.path("structuralValidity").asString());
        assertFalse(checked.path("receiptFound").asBoolean(true));
        assertTrue(checked.path("receiptIntegrity").isNull() && checked.path("receiptBoundToCaller").isNull() && checked.path("receiptFreshness").isNull());
        assertEquals("observational", checked.path("callerBindingAssurance").asString());
        assertEquals("host-observed", checked.path("callerObservation").path("observedBy").asString());

        decision.put("route", "not-a-route");
        assertEquals("invalid", validate(decision, session).path("structuralValidity").asString());
    }

    @Test
    void aReceiptReportsItsIntegrityBindingFreshnessAndClaimIndependently() throws Exception {
        String session = sennard.session();
        String other = sennard.session();
        Map<String, Object> decision = ToolContractServerTest.decision();
        decision.put("sourceReceiptId", receipt(session, "peer", Instant.now().plusSeconds(600), false));
        JsonNode fresh = validate(decision, session);
        assertEquals("valid", fresh.path("receiptIntegrity").asString());
        assertTrue(fresh.path("receiptBoundToCaller").asBoolean());
        assertEquals("fresh", fresh.path("receiptFreshness").asString());
        assertTrue(fresh.path("sourceClaimMatch").asBoolean());

        JsonNode elsewhere = validate(decision, other);
        assertFalse(elsewhere.path("receiptBoundToCaller").asBoolean(true));

        decision.put("sourceReceiptId", receipt(session, "peer", Instant.now().minusSeconds(600), false));
        assertEquals("stale", validate(decision, session).path("receiptFreshness").asString());

        decision.put("sourceOriginKind", "artifact");
        decision.put("sourceReceiptId", receipt(session, "peer", Instant.now().plusSeconds(600), false));
        assertFalse(validate(decision, session).path("sourceClaimMatch").asBoolean(true));

        decision.put("sourceOriginKind", "peer");
        decision.put("sourceReceiptId", receipt(session, "peer", Instant.now().plusSeconds(600), true));
        JsonNode forged = validate(decision, session);
        assertTrue(forged.path("receiptFound").asBoolean());
        assertEquals("invalid", forged.path("receiptIntegrity").asString());
        assertTrue(forged.path("receiptBoundToCaller").isNull() && forged.path("receiptFreshness").isNull() && forged.path("sourceClaimMatch").isNull(), forged.toString());
    }

    // ----------------------------------------------------------------------------------------- glossary and updates

    @Test
    void theGlossaryRefusesADigestThatIsNotTheTexts() throws Exception {
        JsonNode mismatch = sennard.call("lookup_korean_prose_terms", args("sourceText", "가", "sourceDigest", "0".repeat(64)), sennard.session());
        assertRefused(mismatch, "INVALID_INPUT", null);
        String text = "프로젝트를 위한 문서를 보여주기만 하고 끝낸다.";
        JsonNode found = data(sennard.call("lookup_korean_prose_terms", args("sourceText", text, "sourceDigest", ToolContractServerTest.sha256(text)), sennard.session()));
        assertEquals(ToolContractServerTest.sha256(text), found.path("sourceDigest").asString());
        assertTrue(found.path("matches").isArray());
        assertTrue(found.path("matchSetDigest").asString().matches("^[a-f0-9]{64}$"), found.toString());
    }

    @Test
    void anUpdateCheckIsAskedOffTheRequestAndReportsAFailureOverTheLastResult() {
        ToolServerSupport.StubRemote remote = sennard.remote;
        JsonNode before = data(sennard.call("check_for_updates", new HashMap<>(), "unused"));
        assertEquals(0, remote.tagCalls.get(), "answering asks nothing: " + before);

        sennard.call("check_for_updates", new HashMap<>(Map.of("force", true)), "unused");
        JsonNode first = awaitUpdateCheck(status -> !status.path("latestVersion").isNull());
        assertEquals("99.1.0", first.path("latestVersion").asString(), first.toString());
        assertEquals("UPDATE_AVAILABLE", first.path("comparison").asString(), first.toString());
        assertFalse(first.path("automaticInstall").asBoolean(true));
        int asked = remote.tagCalls.get();
        assertEquals("99.1.0", data(sennard.call("check_for_updates", new HashMap<>(), "unused")).path("latestVersion").asString());
        assertEquals(asked, remote.tagCalls.get(), "an unforced read does not ask");

        remote.tagStatus = 500;
        sennard.call("check_for_updates", new HashMap<>(Map.of("force", true)), "unused");
        JsonNode failed = awaitUpdateCheck(status -> !status.path("lastErrorCode").isNull());
        assertEquals("99.1.0", failed.path("latestVersion").asString(), "the last good result stays");
        remote.tagStatus = 200;
    }

    /** Waits for the background check's own outcome on the row the tool reads; the deadline only ends a hung run. */
    private static JsonNode awaitUpdateCheck(java.util.function.Predicate<JsonNode> done) {
        java.time.Instant end = java.time.Instant.now().plusSeconds(30);
        JsonNode status = null;
        while (java.time.Instant.now().isBefore(end)) {
            status = data(sennard.call("check_for_updates", new HashMap<>(), "unused"));
            if (done.test(status)) {
                return status;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("the background check did not finish: " + status);
    }

    @Test
    void aCheckThatWalksATreeAnswersWithAJobAndTheJobHoldsTheAnswer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        // The run's change record is the real one on this server's business database: a plan, then the baseline of the workspace; the check rescans the write roots (it reads files).
        java.nio.file.Path workspace = root.toRealPath();
        java.nio.file.Files.createDirectories(workspace.resolve("src"));
        java.nio.file.Files.writeString(workspace.resolve("src/app.js"), "export const value = 1;\n");
        String workflowId = "run-" + UUID.randomUUID();
        io.github.jaeseongs95.sennard.app.journal.ChangeJournalService journal = sennard.bean(io.github.jaeseongs95.sennard.app.journal.ChangeJournalService.class);
        journal.submitPlan(workflowId, java.util.Set.of(workspace.resolve("src/app.js")));
        // The baseline is three steps, the read of the files between them outside every transaction (as the tool's side makes it).
        io.github.jaeseongs95.sennard.app.journal.ScanBasis basis = journal.baselineBasis(workflowId, java.util.Set.of(workspace));
        assertTrue(journal.captureBaseline(basis, sennard.bean(io.github.jaeseongs95.sennard.app.journal.JournalScanner.class).scanBaseline(basis)).isEmpty());
        java.nio.file.Files.writeString(workspace.resolve("src/app.js"), "export const value = 22;\n");
        Map<String, Object> envelope = new HashMap<>(Map.of("schemaVersion", "1.0.0", "taskId", "task-1", "objective", "Update source", "scope", Map.of("included", List.of("src/**"), "excluded", List.of()),
                "acceptanceCriteria", List.of("Source is updated"), "riskLevel", "low",
                "workUnits", List.of(Map.of("id", "unit-1", "objective", "Edit source", "dependencies", List.of(), "writeTargets", List.of("src/**"))), "requiredCapabilities", List.of(), "constraints", List.of()));
        envelope.putAll(Map.of("authorization", Map.of("allowedActions", List.of("edit"), "prohibitedActions", List.of(), "approvalRequired", List.of()), "decision", Map.of("complexity", "simple", "hasConflicts", false),
                "orchestration", Map.of("requested", false, "mcpAvailable", false)));
        Map<String, Object> request = Map.of("schemaVersion", "1.0.0", "mode", "verify", "workflowId", workflowId, "workspaceRoot", workspace.toString(), "taskEnvelope", envelope);
        Map<String, Object> arguments = new HashMap<>(Map.of("skill", "change-scope-guardian", "command", "compare", "arguments", Map.of("input", request)));

        JsonNode queued = ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callRaw("run_skill_check", arguments, Map.of()).content().getFirst()).text()).path("data");
        assertEquals("QUEUED", queued.path("state").asString(), queued.toString());
        assertTrue(queued.path("value").isMissingNode(), "the answer is the job's: " + queued);

        java.time.Instant end = java.time.Instant.now().plusSeconds(60);
        JsonNode job = null;
        while (java.time.Instant.now().isBefore(end)) {
            job = jobView(queued.path("jobRef").asString());
            if (!"QUEUED".equals(job.path("state").asString()) && !"RUNNING".equals(job.path("state").asString())) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(50).toNanos());
        }
        assertEquals("SUCCEEDED", job.path("state").asString(), job.toString());
        assertEquals(0, job.path("result").path("data").path("exitCode").asInt(-1), job.toString());
        assertTrue(job.path("result").path("data").path("value").path("reportSha256").isString(), job.toString());
        assertEquals(workflowId, job.path("result").path("data").path("value").path("workflowId").asString(), job.toString());
    }

    private static long count(String sql, Object... params) {
        org.springframework.jdbc.core.simple.JdbcClient jdbc = sennard.server.context.getBean("storeJdbcClient", org.springframework.jdbc.core.simple.JdbcClient.class);
        return sennard.inTransaction(() -> jdbc.sql(sql).params(params).query(Long.class).single());
    }

    private static JsonNode skillCheckAnswer(Map<String, Object> arguments) throws Exception {
        return ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callRaw("run_skill_check", arguments, Map.of()).content().getFirst()).text());
    }

    /**
     * AGS3-D-0267: run_skill_check requires an operationKey. The receipt is written only by the commands that queue a job (change-scope-guardian compare and test-engineering run), in
     * the transaction that queues it: the same key with the same content answers the job already queued, another content under the key is a conflict. A command that only judges
     * writes nothing and has no receipt.
     */
    @Test
    void aCheckThatQueuesAJobAnswersTheJobAlreadyQueuedWhenItsKeyComesAgain() throws Exception {
        String key = OperationKeys.fresh();
        Map<String, Object> compare = new HashMap<>(Map.of("operationKey", key, "skill", "change-scope-guardian", "command", "compare", "arguments", Map.of("input", Map.of("keyed", "one"))));
        JsonNode first = skillCheckAnswer(compare);
        assertEquals("QUEUED", first.path("data").path("state").asString(), first.toString());
        assertEquals(first.path("data").path("jobRef").asString(), skillCheckAnswer(compare).path("data").path("jobRef").asString(), "the key answered with the job it queued, and queued none");
        assertEquals(1, count("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'run_skill_check' AND operation_key = ?", key));
        Map<String, Object> other = new HashMap<>(compare);
        other.put("arguments", Map.of("input", Map.of("keyed", "two")));
        JsonNode conflict = skillCheckAnswer(other);
        assertEquals("REQUEST_CONFLICT", conflict.path("error").path("code").asString(), conflict.toString());
        assertEquals("OPERATION_CONFLICT", conflict.path("error").path("details").path("reason").asString(), "the same shape as the other write tools: " + conflict);
        assertEquals("operationKey", conflict.path("error").path("details").path("field").asString(), conflict.toString());
        assertEquals("services.operation.keyConflict", conflict.path("error").path("messageKey").asString(), conflict.toString());
        String fresh = skillCheckAnswer(new HashMap<>(Map.of("skill", "change-scope-guardian", "command", "compare", "arguments", Map.of("input", Map.of("keyed", "one"))))).path("data").path("jobRef").asString();
        assertFalse(fresh.equals(first.path("data").path("jobRef").asString()), "the same content under a new key queues a job of its own");

        // a check that only judges: the key is required, and no receipt is written
        String judgeKey = OperationKeys.fresh();
        Map<String, Object> judge = new HashMap<>(Map.of("operationKey", judgeKey, "skill", "no-such-skill", "command", "x"));
        assertEquals("INVALID_INPUT", skillCheckAnswer(judge).path("error").path("code").asString());
        assertEquals(0, count("SELECT count(*) FROM sennard.operation_receipt WHERE tool = 'run_skill_check' AND operation_key = ?", judgeKey));
        JsonNode missing = ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callResultAsSent("run_skill_check", Map.of("skill", "no-such-skill", "command", "x"), sennard.session()).content().getFirst()).text());
        assertEquals("INVALID_INPUT", missing.path("error").path("code").asString(), missing.toString());
        assertEquals("operationKey", missing.path("error").path("details").path("field").asString(), missing.toString());
    }

    /** A test command run through the tool: the call answers with a job, and the job holds the receipt the same server's skill check made (case j of EngineeringRunTest). */
    @Test
    void aTestCommandRunThroughTheToolAnswersWithAJobAndTheJobHoldsItsReceipt() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("sennard-run-");
        try {
            java.nio.file.Files.writeString(root.resolve("Hello.java"), "public class Hello {\n    public static void main(String[] args) {\n        System.out.println(\"hello from the job\");\n    }\n}\n");
            Map<String, Object> capture = Map.of("skill", "test-engineering", "command", "snapshot", "arguments", Map.of("root", root.toString(), "files", List.of("Hello.java")));
            JsonNode snapshot = ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callRaw("run_skill_check", capture, Map.of()).content().getFirst()).text()).path("data").path("value");
            assertTrue(snapshot.path("digest").isString(), snapshot.toString());

            String javaCommand = ProcessHandle.current().info().command().orElseThrow();
            Map<String, Object> run = Map.of("skill", "test-engineering", "command", "run", "arguments",
                    Map.of("root", root.toString(), "snapshot", ToolServerSupport.MAPPER.convertValue(snapshot, Map.class), "timeout-ms", 60000, "argv", List.of(javaCommand, "-Xmx" + System.getProperty("sennard.test.childHeap"), "Hello.java")));
            JsonNode queued = ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callRaw("run_skill_check", run, Map.of()).content().getFirst()).text()).path("data");
            assertEquals("QUEUED", queued.path("state").asString(), queued.toString());
            assertTrue(queued.path("value").isMissingNode(), "the receipt is the job's: " + queued);

            java.time.Instant end = java.time.Instant.now().plusSeconds(90);
            JsonNode job = null;
            while (java.time.Instant.now().isBefore(end)) {
                job = jobView(queued.path("jobRef").asString());
                if (!"QUEUED".equals(job.path("state").asString()) && !"RUNNING".equals(job.path("state").asString())) {
                    break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(50).toNanos());
            }
            assertEquals("SUCCEEDED", job.path("state").asString(), job.toString());
            assertEquals(0, job.path("result").path("data").path("exitCode").asInt(-1), job.toString());
            JsonNode receipt = job.path("result").path("data").path("value");
            assertEquals("engineering-run-receipt", receipt.path("kind").asString(), job.toString());
            assertEquals("PASS", receipt.path("status").asString(), job.toString());
            assertEquals(0, receipt.path("exitCode").asInt(-1), job.toString());
            assertTrue(receipt.path("stdout").asString().startsWith("hello from the job"), job.toString());
            assertEquals(snapshot.path("digest").asString(), receipt.path("snapshot").path("digest").asString());
            assertEquals(snapshot.path("digest").asString(), receipt.path("afterDigest").asString());
            assertTrue(receipt.path("digest").asString().matches("^sha256:[a-f0-9]{64}$"), job.toString());
        } finally {
            org.springframework.util.FileSystemUtils.deleteRecursively(root);
        }
    }

    @Test
    void anUnknownJobReferenceIsRefused() throws Exception {
        io.modelcontextprotocol.spec.McpSchema.CallToolResult absent = sennard.callRaw("get_background_job", Map.of("jobRef", UUID.randomUUID().toString()), Map.of());
        assertTrue(absent.isError());
        assertTrue(((io.modelcontextprotocol.spec.McpSchema.TextContent) absent.content().getFirst()).text().contains("JOB_NOT_FOUND"));
        assertTrue(sennard.callRaw("get_background_job", Map.of("jobRef", "not-a-uuid"), Map.of()).isError());
    }

    private static JsonNode jobView(String reference) throws Exception {
        return ToolServerSupport.MAPPER.readTree(((io.modelcontextprotocol.spec.McpSchema.TextContent) sennard.callRaw("get_background_job", Map.of("jobRef", reference), Map.of()).content().getFirst()).text()).path("data");
    }

    // ------------------------------------------------------------------------------------------------- skill classification

    /** Signs JEV on with a key and a limit, and answers every skill the same way. */
    private static void jevOn(String choice) throws Exception {
        sennard.bean(io.github.jaeseongs95.sennard.server.skills.JevBudget.class);
        String secret = "jev-test-key";
        JsonNode prepared = sennard.prepare(ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_KEY_SET").put("name", "JEV_API_KEY").put("fingerprint", "sha256:" + ToolContractServerTest.sha256(secret)), false);
        String intent = prepared.path("intent").asString();
        assertEquals(200, sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, secret).status());
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_SET").put("enabled", true).put("limitUsd", 100).put("transmissionAllowed", true);
        change.putObject("presets").put("skill-classification", true);
        change.putObject("model").put("providerId", "typesafe").put("modelId", "jev-1.14.0");
        change.putNull("fallbackModel");
        sennard.sign(owner, change);
        sennard.remote.jevAnswer = request -> {
            ObjectNode body = ToolServerSupport.MAPPER.createObjectNode().put("model", request.path("model").asString());
            ObjectNode answers = body.putObject("answers");
            for (String id : request.path("questions").propertyNames()) {
                ObjectNode answer = answers.putObject(id).put("type", "choice").put("choice", choice).put("confidence", 0.9);
                answer.putObject("probabilities").put("needed", "needed".equals(choice) ? 0.9 : 0.05).put("not-needed", "not-needed".equals(choice) ? 0.9 : 0.05).put("uncertain", "uncertain".equals(choice) ? 0.9 : 0.05);
            }
            body.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
            return body.toString();
        };
    }

    @Test
    void theInventoryReadsTheShippedSkillsAndSaysWhatTheHostObserved() throws Exception {
        JsonNode inventory = data(sennard.call("get_skill_inventory", new HashMap<>(), sennard.session()));
        assertTrue(inventory.path("issues").isEmpty(), inventory.path("issues").toString());
        assertTrue(inventory.path("skills").size() > 10);
        assertTrue(inventory.path("inventoryDigest").asString().startsWith("sha256:"));
        assertEquals("COMPLETE", inventory.path("discovery").path("status").asString(), inventory.path("discovery").toString());
    }

    /**
     * The list an agent reads to choose skills is a summary: its size does not grow with the lines each skill applies by (the whole inventory was 175 KB for 23 skills, which a host
     * keeps in a file and an agent cannot read in its turn). A skill read by name comes whole, and the digest is the same either way.
     */
    @Test
    void theInventoryListIsASummaryAndASkillReadByNameComesWhole() throws Exception {
        String session = sennard.session();
        JsonNode list = data(sennard.call("get_skill_inventory", new HashMap<>(), session));
        assertEquals("summary", list.path("view").asString(), list.toString());
        assertTrue(list.toString().length() < 40_000, "a list a host reads in one piece: " + list.toString().length() + " characters");
        for (JsonNode skill : list.path("skills")) {
            assertFalse(skill.has("applicability") || skill.has("exclusions") || skill.has("phases") || skill.has("sourceMap") || skill.has("sourceRefs"), "a summary: " + skill);
            assertFalse(skill.path("description").asString().isBlank(), skill.path("skillId").asString());
        }
        String first = list.path("skills").get(0).path("skillId").asString();
        String second = list.path("skills").get(1).path("skillId").asString();
        Map<String, Object> named = new HashMap<>();
        named.put("skillIds", List.of(second, first, second));
        JsonNode detail = data(sennard.call("get_skill_inventory", named, session));
        assertEquals("detail", detail.path("view").asString(), detail.toString());
        assertEquals(List.of(second, first), detail.path("skills").valueStream().map(skill -> skill.path("skillId").asString()).toList(), "in the order named, once each");
        assertEquals(list.path("inventoryDigest").asString(), detail.path("inventoryDigest").asString());
        for (JsonNode skill : detail.path("skills")) {
            assertTrue(skill.has("applicability") && skill.has("exclusions") && skill.has("phases") && skill.has("sourceRefs"), "whole: " + skill.path("skillId").asString());
            assertFalse(skill.has("sourceMap"), "the line map of the sources is not sent");
        }
    }

    @Test
    void skillIdsThatAreNotInstalledOrTooManyAreRefusedWithTheIdsAndTheLimit() throws Exception {
        String session = sennard.session();
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("skillIds", List.of("no-such-skill", "another-missing-one"));
        JsonNode refused = sennard.call("get_skill_inventory", unknown, session);
        assertRefused(refused, "INVALID_INPUT", "UNKNOWN_SKILL_ID");
        assertEquals(List.of("no-such-skill", "another-missing-one"), refused.path("error").path("details").path("skillIds").valueStream().map(JsonNode::asString).toList(), refused.toString());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add("skill-" + i);
        }
        Map<String, Object> tooMany = new HashMap<>();
        tooMany.put("skillIds", many);
        JsonNode over = sennard.call("get_skill_inventory", tooMany, session);
        assertRefused(over, "INVALID_INPUT", "TOO_MANY_SKILL_IDS");
        assertEquals(6, over.path("error").path("details").path("limit").asInt(), over.toString());
    }

    /**
     * The answer of {@code classify_skills} does not send the skills back: the server read them itself, so the request comes back as digests and a count. The whole answer was 168 KB,
     * and the agent had nothing to do with the list in it.
     */
    @Test
    void theClassificationAnswerDoesNotRepeatTheSkillsOrThePrompt() throws Exception {
        String session = sennard.session();
        String op = "op-" + UUID.randomUUID();
        JsonNode answer = sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트 원문 그대로"), session);
        JsonNode classified = data(answer);
        JsonNode request = classified.path("result").path("request");
        assertFalse(request.has("skills") || request.has("originalPrompt") || request.has("confirmedContext"), request.toString());
        assertTrue(request.path("skillCount").asInt() > 10, request.toString());
        assertTrue(request.path("requestDigest").asString().startsWith("sha256:"), request.toString());
        assertTrue(request.path("inventoryDigest").asString().startsWith("sha256:"), request.toString());
        assertTrue(answer.toString().length() < 20_000, "an answer an agent reads in its turn: " + answer.toString().length() + " characters");
        assertEquals(data(sennard.call("get_skill_inventory", new HashMap<>(), session)).path("inventoryDigest").asString(), request.path("inventoryDigest").asString(), "bound to the inventory the tool shows");
    }

    /**
     * What an agent needs to record its selection stays in the slim answers: {@code classificationResponseRef}, {@code result.response.requestDigest} and the inventory's digest. The case
     * also holds the shipped skills to their sources: a citation of a skill source whose digest is no longer the file's makes the inventory refuse (STALE_SOURCE), and
     * {@code classify_skills} then answers that it needs input, with no result, so every value below would be empty and the selection refused as malformed.
     */
    @Test
    void aSelectionIsRecordedFromTheValuesTheSlimAnswersHold() throws Exception {
        String session = sennard.session();
        String op = "op-" + UUID.randomUUID();
        JsonNode classified = data(sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), session));
        assertTrue(classified.has("result"), "classified, not asked for input: " + classified.path("errors"));
        JsonNode inventory = data(sennard.call("get_skill_inventory", new HashMap<>(), session));
        assertTrue(inventory.path("issues").isEmpty(), inventory.path("issues").toString());
        JsonNode result = classified.path("result");
        assertEquals(result.path("response").path("requestDigest").asString(), result.path("request").path("requestDigest").asString());
        assertEquals(inventory.path("inventoryDigest").asString(), result.path("request").path("inventoryDigest").asString());
        Map<String, Object> selection = ToolContractServerTest.selection(op);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
        decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
        decision.put("requestDigest", result.path("response").path("requestDigest").asString());
        decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());
        JsonNode answer = sennard.call("record_skill_selection", selection, session);
        assertTrue(answer.path("ok").asBoolean(), "the selection is read as the contract says, and judged: " + answer);
    }

    @Test
    void anUnknownOperationIsRefusedAndTheSameOperationCannotChangeItsRequest() throws Exception {
        String session = sennard.session();
        assertRefused(sennard.call("record_skill_selection", ToolContractServerTest.selection("nobody-classified-this"), session), "INVALID_INPUT", "UNKNOWN_CLASSIFICATION_OPERATION");
        String op = "op-" + UUID.randomUUID();
        data(sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-1-" + op, "무엇을 할까"), session));
        JsonNode changed = sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-2-" + op, "다른 프롬프트"), session);
        assertRefused(changed, "INVALID_INPUT", "OPERATION_DIGEST_CONFLICT");
    }

    /** D-0216, D-0098: a context source that names no context field, or a supplied context member with no source, is the caller's input error, not the runtime being unavailable. */
    @Test
    void aBadContextSourceIsTheCallersInputErrorAndNotTheRuntimeBeingUnavailable() throws Exception {
        String session = sennard.session();
        String op = "op-" + UUID.randomUUID();
        Map<String, Object> unknownField = ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트");
        unknownField.put("contextSources", List.of(Map.of("field", "nope", "reference", "the user's prompt")));
        JsonNode refused = sennard.call("classify_skills", unknownField, session);
        assertRefused(refused, "INVALID_INPUT", "INVALID_CONTEXT_SOURCE");
        assertEquals("input", refused.path("error").path("details").path("category").asString(), refused.toString());

        String other = "op-" + UUID.randomUUID();
        Map<String, Object> missing = ToolContractServerTest.classifyInput(other, "req-" + other, "프롬프트");
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmed = (Map<String, Object>) missing.get("confirmedContext");
        confirmed.put("objective", "무엇을 할지");
        JsonNode missingSource = sennard.call("classify_skills", missing, session);
        assertRefused(missingSource, "INVALID_INPUT", "CONTEXT_SOURCE_MISSING");
        assertEquals("input", missingSource.path("error").path("details").path("category").asString(), missingSource.toString());
    }

    @Test
    void aSkillThatTheClassificationDidNotFindCannotBeRecordedAndACallerMayNotSendAReceipt() throws Exception {
        String session = sennard.session();
        sennard.inTransaction(() -> {
            sennard.bean(BoardService.class).recordInput("codex", session, "/w", true);
            return null;
        });
        String op = "op-" + UUID.randomUUID();
        JsonNode classified = data(sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), session));
        JsonNode inventory = data(sennard.call("get_skill_inventory", new HashMap<>(), session));
        Map<String, Object> selection = ToolContractServerTest.selection(op);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
        decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
        decision.put("requestDigest", classified.path("result").path("request").path("requestDigest").asString());
        decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());

        decision.put("hostReceipt", Map.of("receiptId", "r", "host", "codex", "requestDigest", "sha256:" + "0".repeat(64), "inventoryDigest", "sha256:" + "0".repeat(64),
                "agentSelectedSkillIds", List.of(), "acceptedAt", Instant.now().toString()));
        assertRefused(sennard.call("record_skill_selection", selection, session), "INVALID_INPUT", "CALLER_SELECTION_RECEIPT_REJECTED");
        decision.put("hostReceipt", null);

        decision.put("agentSelectedSkillIds", null);
        assertRefused(sennard.call("record_skill_selection", selection, session), "INVALID_INPUT", "AGENT_SELECTION_MISSING");

        decision.put("agentSelectedSkillIds", List.of("no-such-skill"));
        decision.put("selectionReasons", List.of(Map.of("skillId", "no-such-skill", "reason", "r")));
        JsonNode refused = data(sennard.call("record_skill_selection", selection, session));
        assertFalse(refused.path("valid").asBoolean(true));
        assertTrue(refused.path("errors").toString().contains("UNKNOWN_SELECTED_SKILL:no-such-skill"), refused.toString());
    }

    @Test
    void withJevOnTheAdviceIsReportedAndTheAgentsOwnSelectionIsRecordedOnce() throws Exception {
        jevOn("needed");
        String session = sennard.session();
        sennard.inTransaction(() -> {
            sennard.bean(BoardService.class).recordInput("codex", session, "/w", true);
            return null;
        });
        String op = "op-" + UUID.randomUUID();
        int asked = sennard.remote.jevCalls.get();
        JsonNode classified = data(sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), session));
        assertEquals(asked + 1, sennard.remote.jevCalls.get());
        JsonNode response = classified.path("result").path("response");
        assertEquals("SUCCESS", response.path("status").asString(), classified.toString());
        assertEquals("PROPOSED", classified.path("selectionStatus").asString(), "advice alone selects nothing");

        JsonNode inventory = data(sennard.call("get_skill_inventory", new HashMap<>(), session));
        String skill = inventory.path("skills").get(0).path("skillId").asString();
        List<String> needed = new ArrayList<>(List.of(skill));
        inventory.path("skills").get(0).path("dependencies").forEach(item -> needed.add(item.asString()));
        Map<String, Object> selection = ToolContractServerTest.selection(op);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
        decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
        decision.put("requestDigest", response.path("requestDigest").asString());
        decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());
        decision.put("configRevision", classified.path("result").path("config").path("configRevision").asString());
        decision.put("profileRevision", classified.path("result").path("profileRevision").asString());
        decision.put("agentSelectedSkillIds", needed);
        decision.put("selectionReasons", needed.stream().map(id -> Map.of("skillId", id, "reason", "필요")).toList());
        decision.put("applicabilityChecks", needed.stream().map(id -> Map.of("skillId", id, "applies", true, "excluded", false, "reasonRefs", List.of("r"))).toList());
        decision.put("selectionStatus", "SELECTED");
        decision.put("adviceApplied", true);

        JsonNode recorded = data(sennard.call("record_skill_selection", selection, session));
        assertTrue(recorded.path("valid").asBoolean(), recorded.toString());
        assertEquals("NOT_RUN", recorded.path("verifiedStatus").asString());

        JsonNode again = data(sennard.call("record_skill_selection", selection, session));
        assertTrue(again.path("valid").asBoolean(), "the same selection repeated is the recorded one");
        decision.put("selectionReasons", needed.stream().map(id -> Map.of("skillId", id, "reason", "다른 이유")).toList());
        assertRefused(sennard.call("record_skill_selection", selection, session), "INVALID_INPUT", "SELECTION_ALREADY_RECORDED");
        assertEquals("sha256:" + ToolContractServerTest.sha256("jev-test-key"), jevFingerprint());
    }

    /**
     * D-0199: when JEV cannot be used for a reason that asking again would not mend (a refused key, another 4xx) and the user chose no fallback evaluator, the skill-classification
     * contract says the agent chooses by itself. The record of that choice, {@code independent-agent-after-helper-failure}, is accepted (it was refused as
     * HELPER_FAILURE_NOT_ELIGIBLE for these failures). Real MCP calls, the real HTTP client and signed settings; only the provider's far end is a local server.
     */
    @Test
    void afterAFailureThatAskingAgainWouldNotMendTheAgentsIndependentSelectionIsRecorded() throws Exception {
        jevOn("needed");
        try {
            for (int status : new int[] {401, 403, 400}) {
                sennard.remote.jevReply = (call, request) -> ToolServerSupport.StubRemote.JevReply.of(status, "{}");
                String session = sennard.session();
                sennard.inTransaction(() -> {
                    sennard.bean(BoardService.class).recordInput("codex", session, "/w", true);
                    return null;
                });
                String op = "op-" + UUID.randomUUID();
                int asked = sennard.remote.jevCalls.get();
                JsonNode classified = data(sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), session));
                assertEquals(asked + 1, sennard.remote.jevCalls.get(), "HTTP " + status + " is asked once");
                assertEquals(status == 400 ? "API_UNAVAILABLE" : "AUTH_UNAVAILABLE", classified.path("result").path("response").path("error").path("code").asString(), classified.toString());

                JsonNode inventory = data(sennard.call("get_skill_inventory", new HashMap<>(), session));
                String skill = inventory.path("skills").get(0).path("skillId").asString();
                List<String> chosen = new ArrayList<>(List.of(skill));
                inventory.path("skills").get(0).path("dependencies").forEach(item -> chosen.add(item.asString()));
                Map<String, Object> selection = ToolContractServerTest.selection(op);
                selection.put("selectionSource", "independent-agent-after-helper-failure");
                @SuppressWarnings("unchecked")
                Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
                decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
                decision.put("requestDigest", classified.path("result").path("request").path("requestDigest").asString());
                decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());
                decision.put("configRevision", classified.path("result").path("config").path("configRevision").asString());
                decision.put("profileRevision", classified.path("result").path("profileRevision").asString());
                decision.put("agentSelectedSkillIds", chosen);
                decision.put("selectionReasons", chosen.stream().map(id -> Map.of("skillId", id, "reason", "필요")).toList());
                decision.put("applicabilityChecks", chosen.stream().map(id -> Map.of("skillId", id, "applies", true, "excluded", false, "reasonRefs", List.of("r"))).toList());
                decision.put("selectionStatus", "SELECTED");
                decision.put("adviceApplied", false);

                JsonNode recorded = data(sennard.call("record_skill_selection", selection, session));
                assertTrue(recorded.path("valid").asBoolean(), "HTTP " + status + ": " + recorded);
                assertFalse(recorded.path("independentSelection").isMissingNode() || recorded.path("independentSelection").isNull(), "the independent selection is recorded with its proof: " + recorded);
            }
        } finally {
            sennard.remote.jevReply = null;
        }
    }

    private static String jevFingerprint() throws Exception {
        for (JsonNode key : sennard.state().path("settings").path("jev").path("keys")) {
            if ("JEV_API_KEY".equals(key.path("name").asString())) {
                return key.path("fingerprint").asString();
            }
        }
        return null;
    }
}
