// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP answer to each {@code SennardException} category (docs/contributing/exceptions-and-messages.md): the cases are
 * <ol>
 * <li>each of the seven categories throws from a controller and arrives as a problem+json with its status, its public code and its reason;</li>
 * <li>the sentence follows {@code Accept-Language}: English by default, Korean for {@code ko}, with the named argument filled in;</li>
 * <li>an exception that is not an {@code SennardException} is not taken over by this handler.</li>
 * </ol>
 * The server is the assembled application on a real Tomcat; the controller is the test's.
 */
class SennardExceptionHandlerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static RunningServer server;

    @RestController
    static class Failing {
        @GetMapping("/hooks/v1/sennard-failure/{kind}")
        String fail(@PathVariable String kind) {
            throw switch (kind) {
                case "input" -> new InvalidInputException(InputCode.SUMMARY_INVALID, SennardException.args("maxLength", 200));
                case "missing" -> new NotFoundException(NotFoundCode.SESSION_NOT_FOUND);
                case "conflict" -> new ConflictException(ConflictCode.CONCURRENT_UPDATE);
                case "gate" -> new GateRefusedException(GateCode.GATE_FAILED, Map.of(), Map.of());
                case "binding" -> new BindingException(BindingCode.BINDING_REQUIRED, Map.of(), Map.of());
                case "integrity" -> new IntegrityException(IntegrityCode.REFERENCE_VIOLATION);
                case "unavailable" -> new UnavailableException(UnavailableCode.STORE_UNAVAILABLE);
                default -> new IllegalStateException("not a Sennard exception");
            };
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

    private static RawHttp.Response get(String kind, String language) throws Exception {
        Map<String, String> headers = language == null ? Map.of() : Map.of("Accept-Language", language);
        return RawHttp.exchange(server.port, "GET", "/hooks/v1/sennard-failure/" + kind, headers, null);
    }

    @Test
    void eachCategoryIsAProblemWithItsStatusItsPublicCodeAndItsReason() throws Exception {
        record Case(String kind, int status, String code, String reason) {
        }
        for (Case expected : List.of(new Case("input", 400, "INVALID_INPUT", "SUMMARY_INVALID"), new Case("missing", 404, "NOT_FOUND", "SESSION_NOT_FOUND"),
                new Case("conflict", 409, "CONFLICT", "CONCURRENT_UPDATE"), new Case("gate", 422, "GATE_FAILED", "GATE_FAILED"),
                new Case("binding", 401, "BINDING_REQUIRED", "BINDING_REQUIRED"), new Case("integrity", 422, "INTEGRITY_FAILED", "REFERENCE_VIOLATION"),
                new Case("unavailable", 503, "MCP_UNAVAILABLE", "STORE_UNAVAILABLE"))) {
            RawHttp.Response response = get(expected.kind(), null);
            assertEquals(expected.status(), response.status(), expected.kind() + ": " + response.text());
            assertTrue(response.headers().getOrDefault("content-type", "").startsWith("application/problem+json"), response.headers().toString());
            JsonNode problem = MAPPER.readTree(response.text());
            assertEquals(expected.code(), problem.path("code").asString(), response.text());
            assertEquals(expected.reason(), problem.path("reasonCode").asString(), response.text());
            assertTrue(problem.path("messageKey").asString().length() > 0, response.text());
        }
    }

    @Test
    void theSentenceFollowsTheRequestsLanguageAndFillsTheNamedArgument() throws Exception {
        String english = MAPPER.readTree(get("input", null).text()).path("detail").asString();
        String korean = MAPPER.readTree(get("input", "ko").text()).path("detail").asString();
        assertEquals("summary must be one non-empty line of at most 200 characters.", english);
        assertEquals("summary는 비어 있지 않은 한 줄이어야 하고 200자 이하여야 한다.", korean);
    }

    @Test
    void anExceptionThatIsNotAnSennardExceptionIsLeftToTheFrameworksOwnAnswer() throws Exception {
        RawHttp.Response response = get("other", null);
        assertEquals(500, response.status(), response.text());
        assertTrue(!response.headers().getOrDefault("content-type", "").startsWith("application/problem+json") || !response.text().contains("\"reasonCode\""), response.text());
    }
}
