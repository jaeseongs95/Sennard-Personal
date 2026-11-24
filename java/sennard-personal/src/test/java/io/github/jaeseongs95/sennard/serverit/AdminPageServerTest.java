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

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageStore;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The local admin page on its own connector: who may reach it, that nothing is saved without a passkey signature, and the signed changes the page makes.
 * Real Tomcat, real PostgreSQL, the real signed log; the browser's passkey is a software authenticator making the same assertion.
 */
@Tag("business-db")
class AdminPageServerTest {
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

    private static ObjectNode intentBody(ObjectNode change) {
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode();
        body.set("change", change);
        body.put("bootstrap", false);
        return body;
    }

    private static ObjectNode globalSet(String body) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "GLOBAL_SET").put("body", body);
    }

    private static String globalBody() throws Exception {
        JsonNode global = sennard.state().path("global");
        return global.isNull() ? null : global.path("body").asString();
    }

    // ---------------------------------------------------------------------------------------------------------- reaching it

    @Test
    void thePageIsServedWithANonceCspAndNoPlaceholderLeft() throws Exception {
        RawHttp.Response page = sennard.get("/");
        assertEquals(200, page.status());
        String csp = page.headers().get("content-security-policy");
        Matcher nonce = Pattern.compile("script-src 'nonce-([^']+)'").matcher(csp);
        assertTrue(nonce.find(), csp);
        assertTrue(page.text().contains("nonce=\"" + nonce.group(1) + "\""), "the nonce reaches the page's own script");
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertFalse(page.text().contains("__CSP_NONCE__"));
        assertEquals("DENY", page.headers().get("x-frame-options"));
        assertEquals("no-store", page.headers().get("cache-control"));
        assertNotEquals(csp, sennard.get("/").headers().get("content-security-policy"), "a new nonce for each page");
    }

    @Test
    void theTwoPortsServeOnlyTheirOwnPaths() throws Exception {
        assertProblem(RawHttp.exchange(sennard.server.port, "GET", "/", Map.of(), null), 404, "NOT_FOUND");
        assertProblem(RawHttp.exchange(sennard.server.port, "GET", "/api/state", Map.of(), null), 404, "NOT_FOUND");
        assertProblem(sennard.get("/actuator/health"), 404, "NOT_FOUND");
        byte[] notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".getBytes(StandardCharsets.UTF_8);
        assertProblem(sennard.post("/mcp", notification, Map.of()), 404, "NOT_FOUND");
        // A path no route of the page names is refused on the page's port by the gate, whatever it is.
        assertProblem(sennard.get("/favicon.ico"), 404, "NOT_FOUND");
        assertProblem(sennard.get("/hooks/v1/claude/SessionStart"), 404, "NOT_FOUND");
    }

    /** The port split judges the path the servlet routes, not the bytes of the request line: encoding, a path parameter or dot segments do not move a path to the other port. */
    @Test
    void anEncodedOrDottedPathDoesNotCrossToTheOtherPort() throws Exception {
        for (String path : List.of("/%61pi/state", "/api;x/state", "/./api/state", "/x/../api/state")) {
            RawHttp.Response response = RawHttp.exchange(sennard.server.port, "GET", path, Map.of(), null);
            assertEquals(404, response.status(), path + " on the MCP port: " + response.text());
        }
        RawHttp.Response health = sennard.get("/api/../actuator/health");
        assertEquals(404, health.status(), "Actuator through the admin port: " + health.text());
        byte[] notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".getBytes(StandardCharsets.UTF_8);
        RawHttp.Response mcp = sennard.post("/api/../mcp", notification, Map.of());
        assertEquals(404, mcp.status(), "MCP through the admin port: " + mcp.text());
    }

    @Test
    void pathVariantsCannotExposeAdminRoutesOnTheMcpPort() throws Exception {
        assertEquals(200, sennard.get("/").status());
        assertEquals(200, sennard.get("/api/state").status());
        for (String path : List.of("/%61pi/state", "/api;jsessionid=x/state", "/x/../api/state", "/x/%2e%2e/api/state",
                "/api%2Fstate", "//api/state", "/API/state", "/api/state/", "/./", "/x/../", "/;jsessionid=x")) {
            RawHttp.Response response = RawHttp.exchange(sennard.server.port, "GET", path, Map.of(), null);
            assertTrue(response.status() >= 400, path + " exposed a response on the MCP port: " + response.status() + " " + response.text());
            RawHttp.Response admin = sennard.get(path);
            assertTrue(admin.status() < 500, path + " failed on the admin port: " + admin.status() + " " + admin.text());
        }
    }

    @Test
    void aForeignHostIsRefusedAndTheLoopbackAddressMovesToLocalhostWithTheReason() throws Exception {
        assertEquals(421, RawHttp.exchange(sennard.adminPort, "GET", "/api/state", Map.of("Host", "evil.example:" + sennard.adminPort), null).status(), "the server's own guard answers first");
        RawHttp.Response moved = RawHttp.exchange(sennard.adminPort, "GET", "/", Map.of(), null);
        assertEquals(200, moved.status());
        assertTrue(moved.text().contains(sennard.adminOrigin + "/"), moved.text());
        assertTrue(moved.text().contains("WebAuthn"), moved.text());
        assertProblem(RawHttp.exchange(sennard.adminPort, "GET", "/api/state", Map.of(), null), 403, "HOST_REJECTED");
    }

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    /** The page on localhost, asked for in a language (null: no Accept-Language). */
    private static RawHttp.Response pageIn(String acceptLanguage) throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Host", "localhost:" + sennard.adminPort);
        if (acceptLanguage != null) {
            headers.put("Accept-Language", acceptLanguage);
        }
        return RawHttp.exchange(sennard.adminPort, "GET", "/", headers, null);
    }

    /** The page the loopback address is sent to localhost with, asked for in a language. */
    private static RawHttp.Response movePageIn(String acceptLanguage) throws Exception {
        return RawHttp.exchange(sennard.adminPort, "GET", "/", acceptLanguage == null ? Map.of() : Map.of("Accept-Language", acceptLanguage), null);
    }

    /** D-0204: the page's frame (language, title, the line shown before the script runs) and its sentences follow the request's language; English is the default. */
    @Test
    void thePageIsInTheLanguageOfTheRequestAndEnglishByDefault() throws Exception {
        for (String requested : new String[] {null, "en", "en-US,en;q=0.9", "fr-FR,fr;q=0.9", "de"}) {
            RawHttp.Response page = pageIn(requested);
            assertEquals(200, page.status());
            assertTrue(page.text().contains("<html lang=\"en\">"), requested + ": " + page.text().substring(0, 200));
            // The one Korean an English page has: the language switch names Korean in Korean (the catalog entry the button is drawn from, which carries lang="ko").
            // Take out exactly that entry, require it to be exactly one, and the rest of the page must have no Korean at all.
            String languageKo = "\"admin.page.top.languageKo\":\"한국어\"";
            String text = page.text();
            assertEquals(1, text.split(Pattern.quote(languageKo), -1).length - 1, requested + ": the Korean language name is in the page exactly once");
            assertFalse(HANGUL.matcher(text.replace(languageKo, "")).find(), requested + ": an English page has no Korean in it but the Korean language's own name");
            assertFalse(page.text().contains("__PAGE_"), "no placeholder is left");
            assertTrue(page.text().contains("<footer class=\"source\"><a href=\"https://github.com/jaeseongs95/Sennard-Personal\""), "AGPL section 13: the page always links the source code");
            assertTrue(page.text().contains("\"admin.page.frame.title\""), "the page carries its sentences");
            assertTrue(page.headers().get("vary").contains("Accept-Language"), page.headers().toString());
        }
        RawHttp.Response korean = pageIn("ko-KR,ko;q=0.9,en;q=0.8");
        assertEquals(200, korean.status());
        assertTrue(korean.text().contains("<html lang=\"ko\">"));
        assertTrue(HANGUL.matcher(korean.text()).find(), "a Korean request gets the Korean sentences");
        assertTrue(korean.text().contains("소스 코드 (AGPL-3.0-or-later)"), "the source link is in Korean too");
        assertFalse(korean.text().contains("__PAGE_"));
    }

    @Test
    void theMovePageIsInTheLanguageOfTheRequestToo() throws Exception {
        RawHttp.Response english = movePageIn(null);
        assertEquals(200, english.status());
        assertTrue(english.text().contains("<html lang=\"en\">"), english.text());
        assertFalse(HANGUL.matcher(english.text()).find(), english.text());
        assertTrue(english.text().contains("<code>" + sennard.adminOrigin + "/</code>"), "the address is shown as code: " + english.text());
        assertTrue(english.text().contains("WebAuthn"), english.text());
        RawHttp.Response korean = movePageIn("ko");
        assertTrue(korean.text().contains("<html lang=\"ko\">"), korean.text());
        assertTrue(HANGUL.matcher(korean.text()).find(), korean.text());
        assertTrue(korean.text().contains("<code>" + sennard.adminOrigin + "/</code>"), korean.text());
        assertTrue(korean.text().contains("WebAuthn"), korean.text());
    }

    /** The page on localhost with a cookie and an Accept-Language (either may be null). */
    private static RawHttp.Response pageWith(String cookie, String acceptLanguage) throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Host", "localhost:" + sennard.adminPort);
        if (acceptLanguage != null) {
            headers.put("Accept-Language", acceptLanguage);
        }
        if (cookie != null) {
            headers.put("Cookie", cookie);
        }
        return RawHttp.exchange(sennard.adminPort, "GET", "/", headers, null);
    }

    /** The language the user chose (the cookie of the page) is followed before Accept-Language; with none, or one that is not a supported language, Accept-Language is. */
    @Test
    void theLanguageTheUserChoseIsFollowedBeforeAcceptLanguage() throws Exception {
        assertTrue(pageWith("sennard-admin-language=ko", "en-US,en;q=0.9").text().contains("<html lang=\"ko\">"), "the cookie wins over the browser's English");
        assertTrue(pageWith("sennard-admin-language=en", "ko-KR,ko;q=0.9").text().contains("<html lang=\"en\">"), "the cookie wins over the browser's Korean");
        assertTrue(pageWith("sennard-admin-language=ko", null).text().contains("<html lang=\"ko\">"));
        assertTrue(pageWith(null, "ko-KR,ko;q=0.9").text().contains("<html lang=\"ko\">"), "no choice: the browser's language");
        assertTrue(pageWith("other=1", "ko").text().contains("<html lang=\"ko\">"), "another cookie is not a choice");
        for (String bad : new String[] {"fr", "KO-KR", "", "<script>", "ko-", "../ko"}) {
            RawHttp.Response page = pageWith("sennard-admin-language=" + bad, "ko-KR,ko;q=0.9");
            assertEquals(200, page.status(), bad);
            assertTrue(page.text().contains("<html lang=\"ko\">"), "a value that is not en or ko is ignored: " + bad);
            assertTrue(pageWith("sennard-admin-language=" + bad, "en").text().contains("<html lang=\"en\">"), bad);
        }
        RawHttp.Response page = pageWith("sennard-admin-language=ko", null);
        assertTrue(page.headers().get("vary").contains("Cookie"), "a cache must not serve one language to the other: " + page.headers());
    }

    /** The page's switch: both names in their own language, in both catalogs' patterns; and the choice is kept by the server in a cookie of the page and nothing else. */
    @Test
    void theHeaderHasTheSwitchAndTheChoiceIsKeptInTheCookieOfThePage() throws Exception {
        for (String requested : new String[] {"en", "ko"}) {
            String page = pageWith(null, requested).text();
            assertTrue(page.contains("\"admin.page.top.languageKo\":\"한국어\"") && page.contains("\"admin.page.top.languageEn\":\"English\""), requested + ": each language is named in itself");
        }
        for (String language : new String[] {"ko", "en"}) {
            RawHttp.Response chosen = sennard.post("/api/language", ToolServerSupport.MAPPER.writeValueAsBytes(Map.of("language", language)), Map.of());
            assertEquals(200, chosen.status(), chosen.text());
            String cookie = chosen.headers().get("set-cookie");
            assertTrue(cookie.startsWith("sennard-admin-language=" + language + ";"), cookie);
            assertTrue(cookie.contains("SameSite=Strict") && cookie.contains("HttpOnly") && cookie.contains("Path=/") && cookie.contains("Max-Age="), cookie);
        }
        for (String bad : new String[] {"fr", "KO", "", "ko;Path=/x"}) {
            RawHttp.Response refused = sennard.post("/api/language", ToolServerSupport.MAPPER.writeValueAsBytes(Map.of("language", bad)), Map.of());
            assertEquals(400, refused.status(), bad + " " + refused.text());
            assertFalse(refused.headers().containsKey("set-cookie"), "a refused choice sets no cookie: " + bad);
        }
        assertEquals(400, sennard.post("/api/language", ToolServerSupport.MAPPER.writeValueAsBytes(Map.of()), Map.of()).status(), "no language");
        assertProblem(sennard.post("/api/language", ToolServerSupport.MAPPER.writeValueAsBytes(Map.of("language", "ko")), Map.of("Origin", "https://evil.example")), 403,
                "REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED");
    }

    @Test
    void aPostFromElsewhereOrWithoutJsonOrOverTheCapIsRefused() throws Exception {
        byte[] body = ToolServerSupport.MAPPER.writeValueAsBytes(intentBody(globalSet("x")));
        assertProblem(sennard.post("/api/intent", body, Map.of("Origin", "https://evil.example")), 403, "REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED"); // the guard filter answers first
        assertProblem(sennard.post("/api/intent", body, java.util.Collections.singletonMap("Origin", null)), 403, "ADMIN_ORIGIN_REJECTED");
        assertProblem(sennard.post("/api/intent", body, Map.of("Sec-Fetch-Site", "cross-site")), 403, "ADMIN_ORIGIN_REJECTED");
        assertProblem(sennard.post("/api/intent", body, Map.of("Content-Type", "text/plain")), 415, "CONTENT_TYPE_NOT_JSON");
        RawHttp.Response big = sennard.post("/api/intent", ToolServerSupport.MAPPER.writeValueAsBytes(intentBody(globalSet("x".repeat(64 * 1024)))), Map.of());
        assertProblem(big, 413, "BODY_TOO_LARGE");
    }

    // -------------------------------------------------------------------------------------------- nothing saved without a passkey

    @Test
    void anUnsignedSaveIsRefusedAndChangesNothing() throws Exception {
        String before = globalBody();
        JsonNode prepared = sennard.prepare(globalSet("by an unsigned request"), false);
        String intent = prepared.path("intent").asString();
        ObjectNode fake = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", owner.credentialId).put("authenticatorData", "AA").put("clientDataJSON", "AA").put("signature", "AA");
        RawHttp.Response refused = sennard.commit(intent, fake, UUID.randomUUID().toString(), null, null);
        assertEquals(400, refused.status(), refused.text());
        assertEquals("ADMIN_SIGNATURE_INVALID", code(refused));
        assertEquals(before, globalBody(), "nothing applied");
    }

    @Test
    void aSignatureFromAnotherKeyOrAnotherOriginOrForAnotherIntentIsRefused() throws Exception {
        String before = globalBody();
        String intent = sennard.prepare(globalSet("forged"), false).path("intent").asString();
        RawHttp.Response stranger = sennard.commit(intent, new ToolServerSupport.Passkey().assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals("ADMIN_KEY_UNKNOWN", code(stranger));
        assertEquals(403, stranger.status());
        RawHttp.Response phished = sennard.commit(intent, owner.assertion(intent, "http://localhost:3000"), UUID.randomUUID().toString(), null, null);
        assertEquals(403, phished.status(), phished.text());
        assertEquals("ADMIN_ORIGIN_REJECTED", code(phished));
        String other = sennard.prepare(globalSet("something else"), false).path("intent").asString();
        RawHttp.Response wrong = sennard.commit(intent, owner.assertion(other, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals("ADMIN_CHALLENGE_MISMATCH", code(wrong));
        assertEquals(before, globalBody());
    }

    /**
     * D-0222: the page's port is another at each start. A look-alike page on a port the next start will get collects a signature for an intent the earlier run prepared; the
     * origin check knows only the current port, so the commit after that restart is refused by the start of the server alone, at once and however fresh the intent is.
     */
    @Test
    void anIntentPreparedBeforeTheServerStartedIsRefusedByTheServerThatStartedAfterIt() throws Exception {
        String before = globalBody();
        String earlier = sennard.prepare(globalSet("prepared by the earlier run"), false).path("intent").asString();
        try (ToolServerSupport restarted = ToolServerSupport.start()) {
            restarted.adoptPasskeysOf(sennard); // the same installation: the signed log is the class's one, and its keys are the registry's
            assertNotEquals(sennard.adminPort, restarted.adminPort, "the restarted server's page is on another port");
            RawHttp.Response refused = restarted.commit(earlier, owner.assertion(earlier, restarted.adminOrigin), UUID.randomUUID().toString(), null, null);
            assertEquals(400, refused.status(), refused.text());
            assertEquals("ADMIN_INTENT_BEFORE_START", code(refused));
            assertEquals(before, globalBody(), "nothing applied");
            String own = restarted.prepare(globalSet("prepared by the restarted run"), false).path("intent").asString();
            assertEquals(200, restarted.commit(own, owner.assertion(own, restarted.adminOrigin), UUID.randomUUID().toString(), null, null).status(), "an intent of this run still commits");
        }
    }

    /** The prepared intent with one change of its own, written again in the canonical form (what a client that is not the page can make). */
    private static String rewritten(String prepared, Consumer<ObjectNode> edit) throws Exception {
        ObjectNode tree = (ObjectNode) ToolServerSupport.MAPPER.readTree(prepared);
        edit.accept(tree);
        return CanonicalJson.of(tree);
    }

    /**
     * D-0222: the signature proves what the user signed, not that this server asked for it. A text another client wrote, with an instant ahead of the server's clock, is
     * well formed, canonical, signed by the owner's key on this page's origin and after this start; only the server's own record of what it prepared refuses it.
     */
    @Test
    void aTextTheServerDidNotPrepareIsRefusedEvenSignedOnThisOriginWithAnInstantAheadOfTheClock() throws Exception {
        String before = globalBody();
        long seq = sennard.state().path("lastSeq").asLong();
        String prepared = sennard.prepare(globalSet("prepared"), false).path("intent").asString();
        String ahead = rewritten(prepared, tree -> tree.put("issuedAt", StoreValues.iso(StoreValues.parseIso(tree.path("issuedAt").asString()) + 30_000)));
        RawHttp.Response refused = sennard.commit(ahead, owner.assertion(ahead, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals(400, refused.status(), refused.text());
        assertEquals("ADMIN_INTENT_NOT_ISSUED", code(refused));
        String other = rewritten(prepared, tree -> ((ObjectNode) tree.path("change")).put("body", "another change under the same head"));
        RawHttp.Response changed = sennard.commit(other, owner.assertion(other, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals("ADMIN_INTENT_NOT_ISSUED", code(changed), "a signature for one change is not good for another text, though the target, head and instant are the same");
        assertEquals(before, globalBody(), "nothing applied");
        assertEquals(seq, sennard.state().path("lastSeq").asLong(), "no record appended");
        assertEquals(200, sennard.commit(prepared, owner.assertion(prepared, sennard.adminOrigin), UUID.randomUUID().toString(), null, null).status(), "the prepared text itself still commits");
    }

    @Test
    void anIntentASignedRecordHasUsedIsRefusedAgainUnderAnotherRequestId() throws Exception {
        String intent = sennard.prepare(globalSet("used once"), false).path("intent").asString();
        ObjectNode assertion = owner.assertion(intent, sennard.adminOrigin);
        assertEquals(200, sennard.commit(intent, assertion, UUID.randomUUID().toString(), null, null).status());
        long seq = sennard.state().path("lastSeq").asLong();
        RawHttp.Response again = sennard.commit(intent, assertion, UUID.randomUUID().toString(), null, null);
        assertEquals(400, again.status(), again.text());
        assertEquals("ADMIN_INTENT_NOT_ISSUED", code(again));
        assertEquals(seq, sennard.state().path("lastSeq").asLong(), "the same signature is not a second record");
    }

    @Test
    void aSignedSaveAppliesAndALostResponseIsAnsweredByItsRequestId() throws Exception {
        String intent = sennard.prepare(globalSet("from the page"), false).path("intent").asString();
        String requestId = UUID.randomUUID().toString();
        ObjectNode assertion = owner.assertion(intent, sennard.adminOrigin);
        JsonNode saved = SessionServerSupport.json(sennard.commit(intent, assertion, requestId, null, null));
        assertFalse(saved.path("duplicate").asBoolean(true));
        assertEquals("global", saved.path("record").path("target").asString());
        assertEquals("from the page", globalBody());
        assertEquals(saved.path("record"), SessionServerSupport.json(sennard.get("/api/request/" + requestId)).path("record"));
        assertTrue(SessionServerSupport.json(sennard.get("/api/request/" + UUID.randomUUID())).path("record").isNull());
        JsonNode repeated = SessionServerSupport.json(sennard.commit(intent, assertion, requestId, null, null));
        assertTrue(repeated.path("duplicate").asBoolean(), "the same request id answers with what is stored");
        assertEquals(saved.path("record").path("seq"), repeated.path("record").path("seq"));
    }

    @Test
    void anEditBasedOnAnOlderRevisionIsAConflict() throws Exception {
        String intent = sennard.prepare(globalSet("mine"), false).path("intent").asString();
        sennard.sign(owner, globalSet("theirs"));
        RawHttp.Response lost = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals(409, lost.status(), lost.text());
        assertEquals("ADMIN_TARGET_STALE", code(lost));
        assertEquals("theirs", globalBody());
    }

    @Test
    void aSecondFirstKeyIsRefused() throws Exception {
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode();
        body.set("change", new ToolServerSupport.Passkey().addChange());
        body.put("bootstrap", true);
        RawHttp.Response again = sennard.post("/api/intent", body);
        assertEquals(409, again.status(), again.text());
        assertEquals("ADMIN_ALREADY_BOOTSTRAPPED", code(again));
    }

    @Test
    void aSignedJevSettingCarriesItsFallbackEvaluatorAndTheStateShowsIt() throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_SET").put("enabled", true).put("limitUsd", 100).put("transmissionAllowed", true);
        change.putObject("presets").put("skill-classification", true);
        change.putObject("model").put("providerId", "typesafe").put("modelId", "jev-1.14.0");
        change.putNull("fallbackModel");
        sennard.sign(owner, change);
        JsonNode jev = SessionServerSupport.json(sennard.get("/api/state")).path("settings").path("jev");
        assertTrue(jev.path("switchOn").asBoolean(), jev.toString());
        assertTrue(jev.path("fallbackModel").isNull(), "none chosen: " + jev);

        change.putObject("fallbackModel").put("providerId", "typesafe").put("modelId", "jev-1.13.0");
        sennard.sign(owner, change);
        jev = SessionServerSupport.json(sennard.get("/api/state")).path("settings").path("jev");
        assertEquals("jev-1.13.0", jev.path("fallbackModel").path("modelId").asString(), jev.toString());
        assertEquals("jev-1.14.0", jev.path("model").path("modelId").asString(), jev.toString());

        ObjectNode sameAsModel = change.deepCopy();
        sameAsModel.putObject("fallbackModel").put("providerId", "typesafe").put("modelId", "jev-1.14.0");
        assertEquals("ADMIN_CHANGE_INVALID", code(sennard.post("/api/intent", intentBody(sameAsModel))));
        ObjectNode withoutModel = change.deepCopy();
        withoutModel.putNull("model");
        assertEquals("ADMIN_CHANGE_INVALID", code(sennard.post("/api/intent", intentBody(withoutModel))));
        ObjectNode withUnknownKey = change.deepCopy().putNull("unknownSetting");
        assertEquals("ADMIN_CHANGE_INVALID", code(sennard.post("/api/intent", intentBody(withUnknownKey))));
    }

    @Test
    void theFallbackEvaluatorSentencesComeInTheRequestsLanguageAndThePageCarriesTheField() throws Exception {
        assertTrue(sennard.get("/").text().contains("jevFallbackField"), "the page has the fallback field");
        // D-0228: the sentences are the page's catalog messages (admin.page.jev.fallback.*), sent as patterns in the request's language like every other sentence of the page; the
        // state carries no sentences of its own.
        assertTrue(sennard.state().path("settings").path("jev").path("texts").isMissingNode(), "the JEV state has no server-made sentences");
        JsonNode defaults = sennard.pageTexts(null);
        JsonNode inKorean = sennard.pageTexts("ko");
        for (String name : List.of("label", "none", "hint", "needsModel", "same")) {
            String key = "admin.page.jev.fallback." + name;
            String en = defaults.path(key).asString();
            String ko = inKorean.path(key).asString();
            assertFalse(en.isBlank() || en.equals(key), "an English sentence for " + key);
            assertFalse(ko.isBlank() || ko.equals(key), "a Korean sentence for " + key);
            assertNotEquals(en, ko, key);
        }
        assertEquals("Fallback evaluator", defaults.path("admin.page.jev.fallback.label").asString());
        assertEquals("대체 평가자", inKorean.path("admin.page.jev.fallback.label").asString());
        assertTrue(defaults.path("admin.page.jev.model.unlisted").asString().contains("{model}"), "the page fills in the placeholder, with the rules every sentence of the page has");
    }

    @Test
    void aMalformedRequestIsTheRequestsFault() throws Exception {
        assertEquals("REQUEST_INVALID", code(sennard.post("/api/intent", "{".getBytes(StandardCharsets.UTF_8), Map.of())));
        assertEquals("REQUEST_INVALID", code(sennard.post("/api/intent", "[]".getBytes(StandardCharsets.UTF_8), Map.of())));
        assertEquals("ADMIN_CHANGE_INVALID", code(sennard.post("/api/intent", intentBody(ToolServerSupport.MAPPER.createObjectNode().put("op", "nope")))));
        assertEquals(400, sennard.get("/api/request/short").status());
    }

    /**
     * The request records' constraints (case table of the hand-roll removal): each refusal is a 400 problem naming the field and never its value; a missing required
     * key, a wrong type, a too-long or malformed value and an unknown key are each a case. A base that moved since the page read it is the conflict the page expects.
     */
    @Test
    void aRequestThatDoesNotMatchItsRecordNamesTheFieldAndNeverItsValue() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ObjectNode assertion = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", "c").put("authenticatorData", "a").put("clientDataJSON", "d").put("signature", "s");
        record Case(String name, ObjectNode body, String path, String field) {
        }
        List<Case> cases = List.of(
                new Case("intent without change", ToolServerSupport.MAPPER.createObjectNode().put("bootstrap", false), "/api/intent", "change"),
                new Case("baseHash over its limit", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", globalSet("x")).put("baseCheck", true).put("baseHash", "h".repeat(201)),
                        "/api/intent", "baseHash"),
                new Case("commit without assertion", ToolServerSupport.MAPPER.createObjectNode().put("intent", "i").put("requestId", requestId), "/api/commit", "assertion"),
                new Case("commit without intent", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("assertion", assertion).put("requestId", requestId), "/api/commit", "intent"),
                new Case("commit with a short request id", ToolServerSupport.MAPPER.createObjectNode().put("intent", "i").<ObjectNode>set("assertion", assertion).put("requestId", "short"),
                        "/api/commit", "requestId"),
                new Case("commit with an empty signature", ToolServerSupport.MAPPER.createObjectNode().put("intent", "i")
                        .<ObjectNode>set("assertion", assertion.deepCopy().put("signature", "")).put("requestId", requestId), "/api/commit", "assertion.signature"),
                new Case("commit with an empty proposal id", ToolServerSupport.MAPPER.createObjectNode().put("intent", "i").<ObjectNode>set("assertion", assertion).put("requestId", requestId)
                        .put("proposalId", ""), "/api/commit", "proposalId"));
        for (Case refusal : cases) {
            RawHttp.Response response = sennard.post(refusal.path(), refusal.body());
            assertProblem(response, 400, "REQUEST_INVALID");
            assertEquals(refusal.field(), SessionServerSupport.json(response).path("details").path("field").asString(), refusal.name());
        }
        // A wrong type is not the record's shape: the body cannot be read at all.
        RawHttp.Response wrongType = sennard.post("/api/intent", "{\"change\":{},\"bootstrap\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8), Map.of());
        assertProblem(wrongType, 400, "REQUEST_INVALID");
        assertEquals("JSON", SessionServerSupport.json(wrongType).path("details").path("reason").asString());
        // An unknown key is not the page's: it is left out, and the request is judged by the keys the record has.
        ObjectNode withUnknown = intentBody(globalSet("with an unknown key")).put("unknownKey", true);
        assertEquals(200, sennard.post("/api/intent", withUnknown).status());
        // The base the page read is checked only when the page says so; a null base then means "no head yet".
        ObjectNode moved = intentBody(globalSet("on an old base")).put("baseCheck", true).put("baseHash", "sha256:not-the-head");
        RawHttp.Response stale = sennard.post("/api/intent", moved);
        assertProblem(stale, 409, "ADMIN_TARGET_STALE");
        assertEquals(200, sennard.post("/api/intent", intentBody(globalSet("no base checked")).put("baseCheck", false).putNull("baseHash")).status());
    }

    // --------------------------------------------------------------------------------------------------------- what it shows

    @Test
    void theStateReturnsTheLiteralUserTitleAlongsideTheExistingCardFields() throws Exception {
        String titled = sennard.session();
        String untitled = sennard.session();
        sennard.call("update_session_status", new java.util.HashMap<>(Map.of("schemaVersion", "1.0.0", "summary", "Current work", "role", "developer")), titled);
        sennard.call("update_session_status", new java.util.HashMap<>(Map.of("schemaVersion", "1.0.0", "summary", "Other work")), untitled);
        JsonNode before = codexSession(sennard.state(), titled);

        String title = "<b>R&D \"review\"</b>";
        sennard.bean(BoardService.class).observeTitle("codex", titled, title, Instant.now());

        JsonNode state = sennard.state();
        assertTrue(state.path("sessionsError").isNull(), state.toString());
        JsonNode card = codexSession(state, titled);
        assertTrue(card.path("userTitle").isString(), card.toString());
        assertEquals(title, card.path("userTitle").asString());
        assertEquals("Current work", card.path("summary").asString());
        assertEquals("developer", card.path("selfRole").asString());
        assertEquals(before.path("lastSeenAt"), card.path("lastSeenAt"));
        assertEquals(before.path("binding"), card.path("binding"));
        JsonNode other = codexSession(state, untitled);
        assertTrue(other.path("userTitle").isNull() || other.path("userTitle").isMissingNode(), other.toString());
        assertEquals("Other work", other.path("summary").asString());
    }

    private static JsonNode codexSession(JsonNode state, String sessionId) {
        for (JsonNode session : state.path("sessions")) {
            if ("codex".equals(session.path("host").asString()) && sessionId.equals(session.path("sessionId").asString())) {
                return session;
            }
        }
        throw new AssertionError("missing Codex session " + sessionId + ": " + state.path("sessions"));
    }

    @Test
    void theStateListsObservedMainsAndRetainsTheirEndedBindings() throws Exception {
        String live = sennard.session();
        sennard.call("update_session_status", new java.util.HashMap<>(Map.of("schemaVersion", "1.0.0", "summary", "일하는 중", "role", "자칭")), live);
        String gone = sennard.session();
        assertEquals(200, DomainServerSupport.hook(sennard.server.port, "windows", "codex", "SessionEnd", null, gone, body -> { }).status());
        String child = SessionServerSupport.uniqueSession();
        String unknown = SessionServerSupport.uniqueSession();
        assertTrue(sennard.bean(SessionRegistry.class).bindObservedChild("codex", live, child));
        String roleId = "role-" + UUID.randomUUID();
        sennard.sign(owner, ToolServerSupport.roleAdd(roleId, "검수-" + roleId));
        for (String session : List.of(gone, child, unknown)) {
            sennard.sign(owner, ToolServerSupport.MAPPER.createObjectNode().put("op", "BINDING_SET").put("host", "codex").put("sessionId", session).put("roleId", roleId));
        }
        JsonNode state = sennard.state();
        assertTrue(state.path("sessionsError").isNull(), state.path("sessionsError").toString());
        JsonNode liveRow = null;
        JsonNode goneRow = null;
        for (JsonNode session : state.path("sessions")) {
            String id = session.path("sessionId").asString();
            assertFalse(id.equals(child) || id.equals(unknown), session.toString());
            if (id.equals(live)) {
                liveRow = session;
            } else if (id.equals(gone)) {
                goneRow = session;
            }
        }
        assertNotNull(liveRow, state.path("sessions").toString());
        assertEquals("일하는 중", liveRow.path("summary").asString());
        // The page says when the summary was written and whether a request came after it: the time is UTC text, and the staleness is the board's own judgment (null with no request seen).
        assertTrue(java.time.Instant.parse(liveRow.path("summaryAt").asString()) != null, liveRow.toString());
        assertTrue(liveRow.path("summaryStale").isNull() || liveRow.path("summaryStale").isBoolean(), liveRow.toString());
        assertEquals("자칭", liveRow.path("selfRole").asString());
        assertTrue(liveRow.path("binding").isNull());
        assertNotNull(goneRow);
        assertEquals("ended", goneRow.path("state").asString());
        assertEquals(roleId, goneRow.path("binding").path("roleId").asString());
    }

    @Test
    void anAntigravityStartAppearsOnlyAfterItsActorIsObserved() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, DomainServerSupport.hook(sennard.server.port, "windows", "agy", "SessionStart", null, session, body -> { }).status());
        assertFalse(hasBindingSession(session));
        DomainServerSupport.observeAgyRoot(sennard.server, session);
        assertTrue(hasBindingSession(session));
    }

    private static boolean hasBindingSession(String session) throws Exception {
        for (JsonNode row : sennard.state().path("sessions")) {
            if (session.equals(row.path("sessionId").asString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void skillStatisticsAreServedForAnOfferedPeriodOnly() throws Exception {
        sennard.bean(SkillUsageService.class).record(new SkillUsageStore.Call("codex", "s", "orchestrator", false, "tool", "call-1", java.time.Instant.now()));
        sennard.bean(SkillUsageService.class).record(new SkillUsageStore.Call("codex", "s", "test-engineering", false, "tool", "call-2", java.time.Instant.now()));
        JsonNode usage = SessionServerSupport.json(sennard.get("/api/skill-usage?days=30"));
        assertEquals(30, usage.path("days").asInt());
        assertEquals(90, usage.path("retainDays").asInt());
        assertTrue(usage.path("rows").toString().contains("orchestrator"), usage.toString());
        assertTrue(usage.path("rows").toString().contains("test-engineering"), usage.toString());
        // The skills come in the two groups, each titled in the request's language, and every skill is in the group its classification.json names.
        JsonNode groups = usage.path("groups");
        assertEquals(List.of("agent-correction", "specialist"), List.of(groups.path(0).path("category").asString(), groups.path(1).path("category").asString()), usage.toString());
        assertEquals(15, groups.path(0).path("skills").size(), usage.toString());
        assertEquals(8, groups.path(1).path("skills").size(), usage.toString());
        assertTrue(groups.path(0).path("skills").toString().contains("orchestrator"), usage.toString());
        assertTrue(groups.path(1).path("skills").toString().contains("test-engineering"), usage.toString());
        assertFalse(groups.path(0).path("title").asString().isBlank(), usage.toString());
        assertFalse(groups.path(0).path("title").asString().startsWith("admin."), "the title is the catalog sentence, not its key: " + usage);
        for (String days : List.of("", "0", "2", "365", "x")) {
            RawHttp.Response refused = sennard.get("/api/skill-usage?days=" + days);
            assertEquals(400, refused.status(), days);
            assertProblem(refused, 400, "REQUEST_INVALID");
            assertEquals("days", SessionServerSupport.json(refused).path("details").path("field").asString(), days);
        }
    }

    @Test
    void aJevKeyTravelsOnlyWithItsSignedRegistrationAndIsNeverSentBack() throws Exception {
        String value = "sk-page-fixture-value";
        String fingerprint = "sha256:" + ToolContractServerTest.sha256(value);
        String intent = sennard.prepare(ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_KEY_SET").put("name", "JEV_PAGE_KEY").put("fingerprint", fingerprint), false).path("intent").asString();
        RawHttp.Response control = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, "sk-\u0001");
        assertEquals(400, control.status(), control.text());
        assertProblem(control, 400, "REQUEST_INVALID");
        assertEquals("secret", SessionServerSupport.json(control).path("details").path("field").asString());
        RawHttp.Response mismatch = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, "other");
        assertEquals("ADMIN_CHANGE_INVALID", code(mismatch));
        RawHttp.Response saved = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, value);
        assertEquals(200, saved.status(), saved.text());
        assertFalse(saved.text().contains(value));
        RawHttp.Response state = sennard.get("/api/state");
        assertFalse(state.text().contains(value));
        boolean listed = false;
        for (JsonNode key : SessionServerSupport.json(state).path("settings").path("jev").path("keys")) {
            listed |= "JEV_PAGE_KEY".equals(key.path("name").asString()) && fingerprint.equals(key.path("fingerprint").asString()) && key.path("stored").asBoolean();
        }
        assertTrue(listed, state.text());
        assertEquals(io.github.jaeseongs95.sennard.domain.secret.SecretValue.of(value), sennard.inTransaction(() -> sennard.bean(io.github.jaeseongs95.sennard.server.context.ContextLayerService.class).jevKey("JEV_PAGE_KEY")));
    }

    @Test
    void anAgentRequestCanBeRejectedFromThePageOnce() throws Exception {
        String session = sennard.session();
        JsonNode global = sennard.state().path("global");
        java.util.HashMap<String, Object> arguments = new java.util.HashMap<>(Map.of("schemaVersion", "1.0.0", "body", "asked"));
        arguments.put("expectedStateToken", global.isNull() ? null : global.path("stateToken").asString());
        String proposal = sennard.call("checkpoint_global_context", arguments, session).path("data").path("proposalId").asString();
        assertEquals(200, sennard.post("/api/proposals/" + proposal + "/reject", ToolServerSupport.MAPPER.createObjectNode()).status());
        RawHttp.Response again = sennard.post("/api/proposals/" + proposal + "/reject", ToolServerSupport.MAPPER.createObjectNode());
        assertEquals(404, again.status());
        assertEquals("ADMIN_PROPOSAL_NOT_FOUND", code(again));
    }

    // --------------------------------------------------------------------------------------------------------- root approvals

    /** The row the workflow writes when a tool asks for a decision. */
    private static void waiting(String digest) {
        ObjectNode row = ToolServerSupport.MAPPER.createObjectNode();
        row.put("scopeDigest", digest);
        row.putObject("scope").put("action", "REPLACE_ROOT").put("rootId", "root-1").put("actorId", "codex:s:root");
        row.put("message", "이 작업을 승인하시겠습니까?");
        row.put("currentScope", "현재 범위");
        row.put("replacementScope", "교체 범위");
        row.put("requestedBy", "codex:s:root");
        sennard.inTransaction(() -> {
            sennard.bean(WorkflowStore.class).savePendingRootApproval(digest, row.toString(), "2026-11-10T00:00:00.000Z");
            return null;
        });
    }

    private static String newDigest() {
        return "sha256:" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String idOf(String digest) {
        return digest.substring("sha256:".length());
    }

    private static boolean stillWaiting(String digest) {
        return sennard.inTransaction(() -> sennard.bean(RootApprovalService.class).pending().stream().anyMatch(item -> item.scopeDigest().equals(digest)));
    }

    private static WorkflowStore.RootApproval decision(String digest) {
        return sennard.inTransaction(() -> sennard.bean(WorkflowStore.class).findRootApproval(digest));
    }

    private static ObjectNode approveChange(String id, String digest) {
        return ToolServerSupport.MAPPER.createObjectNode().put("op", "ROOT_APPROVE").put("approvalId", id).put("scopeDigest", digest);
    }

    @Test
    void aWaitingRootApprovalIsShownAndApprovedOnlyWithThePasskeyForTheScopeShown() throws Exception {
        String digest = newDigest();
        String id = idOf(digest);
        waiting(digest);
        JsonNode listed = sennard.state().path("rootApprovals");
        List<String> ids = new ArrayList<>();
        listed.forEach(item -> ids.add(item.path("approvalId").asString()));
        assertTrue(ids.contains(id), listed.toString());
        for (JsonNode item : listed) {
            if (item.path("approvalId").asString().equals(id)) {
                assertEquals("REPLACE_ROOT", item.path("action").asString());
                assertEquals("root-1", item.path("rootId").asString());
                assertEquals("codex:s:root", item.path("actorId").asString());
                assertEquals("현재 범위", item.path("scope").asString());
                assertEquals("교체 범위", item.path("replacementScope").asString());
            }
        }

        // The scope moved after the page drew it: no signature is even asked for.
        assertEquals(404, sennard.post("/api/intent", intentBody(approveChange(id, newDigest()))).status());

        String intent = sennard.prepare(approveChange(id, digest), false).path("intent").asString();
        ObjectNode fake = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", owner.credentialId).put("authenticatorData", "AA").put("clientDataJSON", "AA").put("signature", "AA");
        assertEquals(400, sennard.commit(intent, fake, UUID.randomUUID().toString(), null, null).status(), "without a signature nothing is approved");
        assertNull(decision(digest));
        assertTrue(stillWaiting(digest));

        JsonNode committed = sennard.sign(owner, approveChange(id, digest), false, null);
        JsonNode source = ToolServerSupport.MAPPER.readTree(decision(digest).recordJson()).path("source");
        assertEquals("ADMIN_PAGE", source.path("kind").asString());
        assertEquals(committed.path("record").path("recordHash").asString(), source.path("recordHash").asString());
        assertEquals(owner.credentialId, source.path("credentialId").asString());
        assertFalse(stillWaiting(digest));
    }

    @Test
    void aSignatureForARequestThatIsGoneIsRefusedAndNotRecorded() throws Exception {
        String digest = newDigest();
        String id = idOf(digest);
        waiting(digest);
        String intent = sennard.prepare(approveChange(id, digest), false).path("intent").asString();
        sennard.inTransaction(() -> {
            sennard.bean(WorkflowStore.class).deletePendingRootApproval(digest);
            return null;
        });
        long seq = sennard.state().path("lastSeq").asLong();
        RawHttp.Response refused = sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null);
        assertEquals(404, refused.status(), refused.text());
        assertEquals("ADMIN_PROPOSAL_NOT_FOUND", code(refused));
        assertEquals(seq, sennard.state().path("lastSeq").asLong(), "the log did not grow");
    }

    @Test
    void aRootApprovalCanBeRejectedWithoutAPasskeyOnce() throws Exception {
        String digest = newDigest();
        String id = idOf(digest);
        waiting(digest);
        assertEquals(200, sennard.post("/api/root-approvals/" + id + "/reject", ToolServerSupport.MAPPER.createObjectNode()).status());
        assertFalse(stillWaiting(digest));
        assertNull(decision(digest), "a rejection decides nothing");
        assertEquals(404, sennard.post("/api/root-approvals/" + id + "/reject", ToolServerSupport.MAPPER.createObjectNode()).status());
    }

    @Test
    void thePageOffersTheRootApprovalScreen() throws Exception {
        String page = sennard.get("/").text();
        assertTrue(page.contains("viewApprovals") && page.contains("ROOT_APPROVE") && page.contains("/api/root-approvals/"), "the screen and its two calls are in the page");
    }

    /** The page's port is chosen at each start, so {@code sennard status} learns it from the server's own endpoint: the address given there is the page that answers. */
    @Test
    void theServersEndpointTellsWhereThePageIsListening() throws Exception {
        JsonNode status = SessionServerSupport.json(RawHttp.exchange(sennard.server.port, "GET", "/actuator/sennard", Map.of(), null));
        assertEquals(sennard.adminOrigin, status.path("adminPage").asString(), status.toString());
        assertEquals(200, sennard.get("/").status());
    }
}

/** A server with no passkey yet: the first key is registered with its own signature and nothing else can be saved. */
@Tag("business-db")
class AdminPageFirstKeyServerTest {
    @Test
    void beforeTheFirstKeyNothingCanBeSavedAndTheFirstKeySignsItsOwnRegistration() throws Exception {
        try (ToolServerSupport sennard = ToolServerSupport.start()) {
            RawHttp.Response refused = sennard.post("/api/intent", ToolServerSupport.MAPPER.createObjectNode().<ObjectNode>set("change", ToolServerSupport.MAPPER.createObjectNode().put("op", "GLOBAL_SET").put("body", "x")).put("bootstrap", false));
            assertEquals(403, refused.status(), refused.text());
            assertEquals("ADMIN_KEY_NOT_REGISTERED", SessionServerSupport.json(refused).path("code").asString());
            assertEquals(0, sennard.state().path("keys").size());

            ToolServerSupport.Passkey key = new ToolServerSupport.Passkey();
            JsonNode prepared = sennard.prepare(key.addChange(), true);
            assertEquals(key.credentialId, prepared.path("allowCredentials").get(0).asString());
            String intent = prepared.path("intent").asString();
            assertEquals(200, sennard.commit(intent, key.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, null, key.publicKey()).status());
            assertEquals(key.credentialId, sennard.state().path("keys").get(0).path("credentialId").asString());
        }
    }

    /**
     * The page's port is the operating system's choice at each start, so a program holding a port, or another Sennard server with its own page, never stops this one: each server
     * has a page on a port of its own and answers there, and the address a server tells is the one it listens on.
     */
    @Test
    void aPortHeldByAnotherProgramOrAnotherServerNeverStopsTheStart() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
                ToolServerSupport first = ToolServerSupport.start();
                ToolServerSupport second = ToolServerSupport.start()) {
            assertNotEquals(first.adminPort, second.adminPort, "two servers of one PC, two pages");
            assertNotEquals(taken.getLocalPort(), first.adminPort);
            assertNotEquals(first.server.port, first.adminPort, "the page is not on the server's own port");
            assertEquals(200, first.get("/").status());
            assertEquals(200, second.get("/").status());
            assertEquals("http://localhost:" + first.adminPort, first.server.bean(AdminPageAddress.class).origin(), "the one address every text and check reads");
        }
    }
}
