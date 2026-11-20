// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.server.skills.JevProvider.Evaluated;
import io.github.jaeseongs95.sennard.server.skills.JevProvider.Evaluation;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ConfirmedContext;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Request;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResponseStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skill;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * D-0097: the output ceiling of the TypeSafe provider ({@link TypeSafeProvider#MAXIMUM_OUTPUT_TOKENS}) holds the answer for 25 skills, at the provider's boundary and with no
 * call outside this computer (a local HTTP server stands in for the provider). The provider's own token count cannot be measured without calling it, so the size of the
 * answer it would send stands in for it, at four bytes a token (what JSON text of this kind comes to); the ceiling must be well above that for 25 skills, and the documented
 * growth of about 43 tokens a skill must not be below what the answer's size says.
 */
class TypeSafeProviderOutputCeilingTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int SKILLS = 25;
    private static final int BYTES_PER_TOKEN = 4;
    private static final AtomicLong LAST_ANSWER_BYTES = new AtomicLong();
    private static HttpServer remote;

    @BeforeAll
    static void start() throws IOException {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/systemone", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            ObjectNode body = MAPPER.createObjectNode().put("model", request.path("model").asString());
            ObjectNode answers = body.putObject("answers");
            for (String id : request.path("questions").propertyNames()) {
                ObjectNode answer = answers.putObject(id).put("type", "choice").put("choice", "not-needed").put("confidence", 0.91);
                answer.putObject("probabilities").put("needed", 0.01).put("not-needed", 0.91).put("uncertain", 0.08);
            }
            long answerBytes = MAPPER.writeValueAsBytes(answers).length;
            LAST_ANSWER_BYTES.set(answerBytes);
            body.putObject("usage").put("input_tokens", 1000).put("output_tokens", (answerBytes + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN);
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        remote.start();
    }

    @AfterAll
    static void stop() {
        remote.stop(0);
    }

    private static TypeSafeProvider provider() {
        String address = "http://127.0.0.1:" + remote.getAddress().getPort();
        RestClient client = RestClient.builder().baseUrl(address).build();
        return new TypeSafeProvider(MAPPER, new TypeSafeProperties(address, true), client, client);
    }

    private static Request requestFor(int skills) {
        List<Skill> list = new ArrayList<>();
        for (int i = 1; i <= skills; i++) {
            list.add(new Skill("skill-" + (i < 10 ? "0" : "") + i, "1.0.0", "A skill that does thing " + i, true, true, true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of()));
        }
        ConfirmedContext none = new ConfirmedContext(null, null, null, null, null, null, null);
        return new Request("1.0.0", "req", "op", "prompt", "sha256:" + "0".repeat(64), none, List.of(), list, "sha256:" + "1".repeat(64), "t1", "ref", "sha256:" + "2".repeat(64));
    }

    @Test
    void theAnswerForTwentyFiveSkillsIsReadAndItsSizeIsUnderTheCeiling() {
        TypeSafeProvider provider = provider();
        Profile profile = provider.profileFor("jev-1.14.0").orElseThrow();
        Evaluated evaluated = provider.classify(requestFor(SKILLS), profile, io.github.jaeseongs95.sennard.domain.secret.SecretValue.of("a-key"));
        Evaluation evaluation = assertInstanceOf(Evaluation.class, evaluated, evaluated.toString());
        assertEquals(ResponseStatus.SUCCESS, evaluation.response().status());
        assertEquals(SKILLS, evaluation.response().judgments().size());
        long tokens = evaluation.usage().outputTokens();
        assertTrue(tokens <= profile.maximumOutputTokens(), SKILLS + " skills: " + tokens + " tokens against a ceiling of " + profile.maximumOutputTokens() + " (" + LAST_ANSWER_BYTES.get() + " bytes)");
        assertTrue(tokens * 2 < profile.maximumOutputTokens(), "room to spare: " + SKILLS + " skills use less than half the ceiling");
    }

    @Test
    void theDocumentedGrowthPerSkillIsNotBelowWhatTheAnswerSaysAndTheCeilingCoversTheSkillsItIsSaidTo() {
        long documentedPerSkill = 43;
        TypeSafeProvider provider = provider();
        provider.classify(requestFor(SKILLS), provider.profileFor("jev-1.14.0").orElseThrow(), io.github.jaeseongs95.sennard.domain.secret.SecretValue.of("a-key"));
        long measuredPerSkill = (LAST_ANSWER_BYTES.get() / SKILLS + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN;
        assertTrue(measuredPerSkill <= documentedPerSkill, "an answer of one skill is about " + measuredPerSkill + " tokens; the ceiling's arithmetic (" + documentedPerSkill + ") must not be below it");
        assertTrue(TypeSafeProvider.MAXIMUM_OUTPUT_TOKENS / documentedPerSkill >= 90, "the ceiling covers about 93 skills at " + documentedPerSkill + " tokens each");
    }
}
