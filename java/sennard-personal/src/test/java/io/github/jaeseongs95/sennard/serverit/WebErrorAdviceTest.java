// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP boundary has one error advice (a {@code ResponseEntityExceptionHandler}): what Spring's own MVC throws, what the application throws on purpose and what nobody expected
 * are each answered by it, in the same shape, with no handler in a controller. Cases (a request, then its status, content type and the problem's fields):
 * <ol>
 * <li>a body that fails its record's constraint is 400 {@code REQUEST_INVALID} naming the field and not its value; so are a body that is not JSON (reason {@code JSON}), a parameter
 * of the wrong type and a missing parameter;</li>
 * <li>a method the route does not take and a path nothing serves are Spring's own 405 and 404 problems;</li>
 * <li>a refused hook call is its status and {@code {"reason": ...}} as JSON, not a problem;</li>
 * <li>a refused admin change is the status of its code (403 for a key the log does not know, 400 for a code with no status of its own) with its code and details;</li>
 * <li>what the settings store refuses and what the data access raises are translated by the one {@code PersistenceErrors} and answered as the application layer answers them: a race
 * is the retryable 409, a constraint is 422 and a store that does not answer is 503 {@code MCP_UNAVAILABLE} (reason {@code STORE_UNAVAILABLE});</li>
 * <li>an exception nobody expected is 500 {@code INTERNAL_FAILURE} with a fixed sentence that says nothing of what failed.</li>
 * </ol>
 * The server is the assembled application on a real Tomcat; the controller is the test's.
 */
class WebErrorAdviceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static RunningServer server;

    record Probe(@NotNull @Size(min = 3) String name) {
    }

    @RestController
    static class Failing {
        @PostMapping(path = "/web-errors/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody @Valid Probe probe) {
            return probe.name();
        }

        @GetMapping("/web-errors/param")
        String param(@RequestParam int count) {
            return "count=" + count;
        }

        @GetMapping("/web-errors/hook-rejected")
        String hookRejected() {
            throw new HookRejected(HookRejection.HOOK_WAKE_REPLACED);
        }

        @GetMapping("/web-errors/admin/{code}")
        String admin(@PathVariable String code) {
            Map<String, Object> seen = Map.of("reason", "SEEN");
            throw switch (code) {
                case "ADMIN_KEY_UNKNOWN" -> new GateRefusedException(GateCode.ADMIN_KEY_UNKNOWN, Map.of(), seen);
                case "ADMIN_ORIGIN_REJECTED" -> new GateRefusedException(GateCode.ADMIN_ORIGIN_REJECTED, Map.of(), seen);
                case "ADMIN_PROPOSAL_NOT_FOUND" -> new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND, Map.of());
                case "ADMIN_TARGET_STALE" -> new ConflictException(ConflictCode.ADMIN_TARGET_STALE, Map.of(), seen);
                case "ADMIN_CHANGE_INVALID" -> new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), seen);
                default -> new IllegalStateException(code);
            };
        }

        @GetMapping("/web-errors/settings/{code}")
        String settings(@PathVariable SettingsCode code) {
            throw new SettingsException(code, "the settings store refused");
        }

        @GetMapping("/web-errors/store/{kind}")
        String store(@PathVariable String kind) {
            throw switch (kind) {
                case "race" -> new ConcurrencyFailureException("CONCURRENT_UPDATE_1 jdbc:secret-store-detail");
                case "integrity" -> new DataIntegrityViolationException("check jdbc:secret-store-detail");
                case "down" -> new DataRetrievalFailureException("jdbc:secret-store-detail");
                default -> new IllegalStateException(kind);
            };
        }

        @GetMapping("/web-errors/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret-detail of the failure");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        Failing failing() {
            return new Failing();
        }
    }

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(Beans.class));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static RawHttp.Response get(String path) throws Exception {
        return RawHttp.exchange(server.port, "GET", path, Map.of(), null);
    }

    private static RawHttp.Response post(String path, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return RawHttp.exchange(server.port, "POST", path, Map.of("Content-Type", "application/json", "Content-Length", Integer.toString(body.length)), body);
    }

    private static JsonNode problem(RawHttp.Response response, int status, String code) throws Exception {
        assertEquals(status, response.status(), response.text());
        assertTrue(response.headers().getOrDefault("content-type", "").startsWith("application/problem+json"), response.headers().toString());
        JsonNode problem = MAPPER.readTree(response.text());
        assertEquals(status, problem.path("status").asInt(), response.text());
        if (code != null) {
            assertEquals(code, problem.path("code").asString(), response.text());
            assertEquals("urn:sennard:problem:" + code, problem.path("type").asString(), response.text());
            assertFalse(problem.path("detail").asString().isBlank(), response.text());
        }
        return problem;
    }

    @Test
    void aBodyThatBreaksItsRecordsConstraintNamesTheFieldAndNotItsValue() throws Exception {
        for (String json : List.of("{}", "{\"name\":\"ab\"}")) {
            RawHttp.Response response = post("/web-errors/body", json);
            assertEquals("name", problem(response, 400, "REQUEST_INVALID").path("details").path("field").asString(), json);
            assertFalse(response.text().contains("\"ab\""), "the value is not echoed: " + response.text());
        }
        assertEquals(200, post("/web-errors/body", "{\"name\":\"abc\"}").status());
    }

    @Test
    void aBodyThatIsNotJsonOfTheRecordsShapeIsTheRequestsFault() throws Exception {
        for (String json : List.of("{", "[]", "{\"name\":{\"x\":1}}")) {
            assertEquals("JSON", problem(post("/web-errors/body", json), 400, "REQUEST_INVALID").path("details").path("reason").asString(), json);
        }
    }

    @Test
    void aParameterOfTheWrongTypeOrMissingNamesTheParameter() throws Exception {
        assertEquals("count", problem(get("/web-errors/param?count=many"), 400, "REQUEST_INVALID").path("details").path("field").asString());
        assertEquals("count", problem(get("/web-errors/param"), 400, "REQUEST_INVALID").path("details").path("field").asString());
        assertEquals("count=3", get("/web-errors/param?count=3").text());
    }

    @Test
    void aMethodTheRouteDoesNotTakeAndAPathNothingServesAreSpringsOwnProblems() throws Exception {
        RawHttp.Response method = RawHttp.exchange(server.port, "DELETE", "/web-errors/param", Map.of(), null);
        problem(method, 405, null);
        assertTrue(method.headers().containsKey("allow"), method.headers().toString());
        problem(get("/web-errors/nothing-here"), 404, null);
    }

    @Test
    void aRefusedHookCallIsItsStatusAndItsReasonAsJson() throws Exception {
        RawHttp.Response response = get("/web-errors/hook-rejected");
        assertEquals(HookRejection.HOOK_WAKE_REPLACED.status(), response.status(), response.text());
        assertTrue(response.headers().get("content-type").startsWith("application/json"), response.headers().toString());
        assertEquals("HOOK_WAKE_REPLACED", MAPPER.readTree(response.text()).path("reason").asString());
    }

    @Test
    void aRefusedAdminChangeIsTheStatusOfItsCodeWithItsCodeAndDetails() throws Exception {
        record Case(String code, int status) {
        }
        for (Case expected : List.of(new Case("ADMIN_KEY_UNKNOWN", 403), new Case("ADMIN_ORIGIN_REJECTED", 403), new Case("ADMIN_PROPOSAL_NOT_FOUND", 404),
                new Case("ADMIN_TARGET_STALE", 409), new Case("ADMIN_CHANGE_INVALID", 400))) {
            JsonNode problem = problem(get("/web-errors/admin/" + expected.code()), expected.status(), expected.code());
            // The refusal of a missing proposal carries no details; every other case of the table carries the one it was thrown with.
            assertEquals(expected.status() == 404 ? "" : "SEEN", problem.path("details").path("reason").asString(""), expected.code());
        }
    }

    /**
     * The entrance states what the settings store refuses (the passkey registry, the records before the link) by the one translation ({@code PersistenceErrors.ofSettings}) and
     * answers it like every other refusal, never as the unexpected 500. This is a representative case; every code of the store is held to an answer in {@code PersistenceErrorsTest}.
     */
    @Test
    void whatTheSettingsStoreRefusesIsTranslatedAtTheEntranceAndAnsweredAsARefusal() throws Exception {
        RawHttp.Response response = get("/web-errors/settings/PASSKEY_EXISTS");
        JsonNode problem = problem(response, 409, "CONFLICT");
        assertEquals("DUPLICATE", problem.path("reasonCode").asString());
        assertFalse(response.text().contains("the settings store refused"), "the store's message is not echoed: " + response.text());
    }

    /**
     * The entrance states what the store raises outside a service method by the same translation the application layer runs ({@code PersistenceErrors.ofDataAccess}; the cases of each
     * exception are {@code PersistenceErrorsTest}'s): a race with another writer is the retryable 409 CONFLICT, a constraint is 422, and only a store that does not answer is 503. None
     * of them says anything of the driver.
     */
    @Test
    void whatTheStoreRaisesIsTranslatedAtTheEntranceAndOnlyAStoreThatDoesNotAnswerIs503() throws Exception {
        RawHttp.Response race = get("/web-errors/store/race");
        assertEquals("CONCURRENT_UPDATE", problem(race, 409, "CONFLICT").path("reasonCode").asString());
        RawHttp.Response integrity = get("/web-errors/store/integrity");
        assertEquals("VALUE_NOT_ALLOWED", problem(integrity, 422, "INTEGRITY_FAILED").path("reasonCode").asString());
        RawHttp.Response down = get("/web-errors/store/down");
        JsonNode unavailable = problem(down, 503, "MCP_UNAVAILABLE");
        assertEquals("STORE_UNAVAILABLE", unavailable.path("reasonCode").asString());
        assertEquals("STORE_FAILURE", unavailable.path("details").path("reason").asString());
        for (RawHttp.Response response : List.of(race, integrity, down)) {
            assertFalse(response.text().contains("secret-store-detail"), response.text());
        }
    }

    @Test
    void anExceptionNobodyExpectedIs500WithAFixedSentenceThatSaysNothingOfWhatFailed() throws Exception {
        RawHttp.Response response = get("/web-errors/unexpected");
        JsonNode problem = problem(response, 500, "INTERNAL_FAILURE");
        assertEquals("The server could not complete the request.", problem.path("detail").asString());
        assertFalse(response.text().contains("secret-detail") || response.text().contains("IllegalStateException"), response.text());
    }
}
