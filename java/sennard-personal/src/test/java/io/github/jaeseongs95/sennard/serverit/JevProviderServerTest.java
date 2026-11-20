// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.skills.JevProvider;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Diagnostics;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DispatchState;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Judgment;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ProviderKind;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Qualification;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.QualificationStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Request;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Response;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResponseStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Score;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ScoreKind;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skill;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SupportedOptions;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Usage;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Verdict;
import io.github.jaeseongs95.sennard.server.skills.TypeSafeProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * D-0206 and D-0199, through the real tool calls, the real HTTP client and the real signed settings: the JEV provider is a contract with an adapter behind it, a call that
 * failed in communication is made again within the request's deadline and the budget (following {@code Retry-After}), and a call that failed for any other reason goes to the
 * fallback evaluator the user chose, or comes back as a failure the agent decides on. Only the provider's far end is a stand-in (a local HTTP server).
 *
 * <pre>
 * a  communication errors (connection cut, 5xx, 429) are retried, Retry-After is followed, the deadline and the budget stop the tries
 * b  other errors (401, 403, 400, a wrong answer form) are never retried; the fallback evaluator is asked, once
 * c  with no fallback evaluator the failure comes back as it always did and the agent chooses
 * d  the service depends on the contract only: a second provider, registered as a bean, is chosen by its id
 * e  a retried call is reserved and settled like any other, and the tries stop where the budget does
 * </pre>
 */
@Tag("business-db")
class JevProviderServerTest {
    private static final String STUB_KEY_NAME = "STUB_JEV_KEY";
    private static final String THROWING_KEY_NAME = "THROWING_JEV_KEY";
    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;

    private static Profile stubProfile(String providerId, String modelId) {
        String id = "jev:" + providerId + ":" + modelId;
        return new Profile(id, ProviderKind.JEV, providerId, modelId, modelId, null, new SupportedOptions(Collections.singletonList(null), true), id, "not-run", "stub-r1",
                "stub-prompt", 256 * 1024, 4000, 0.001, null, new Qualification(QualificationStatus.NOT_RUN, "not-run", "not-run", modelId, "stub-prompt", "not-run", "stub-digest"));
    }

    /** A second provider of the contract, to show the service chooses a provider by its id and by nothing else; a third that throws, to show no way a call ends keeps its reservation. */
    @Configuration(proxyBeanMethods = false)
    static class StubProviderConfiguration {
        @Bean
        JevProvider stubJevProvider() {
            return new StubProvider();
        }

        @Bean
        JevProvider throwingJevProvider() {
            return new ThrowingProvider();
        }
    }

    /** A provider whose call throws: a defect of a provider is an exception the contract does not return. */
    static final class ThrowingProvider implements JevProvider {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String providerId() {
            return "throwing-provider";
        }

        @Override
        public String label() {
            return "Throwing";
        }

        @Override
        public String endpoint() {
            return "stub://throwing";
        }

        @Override
        public String credentialName() {
            return THROWING_KEY_NAME;
        }

        @Override
        public double maximumCostUsd() {
            return 0.001;
        }

        @Override
        public double estimateUsd(long inputTokens) {
            return 0;
        }

        @Override
        public Optional<Profile> profileFor(String modelId) {
            return "boom-1".equals(modelId) ? Optional.of(stubProfile("throwing-provider", modelId)) : Optional.empty();
        }

        @Override
        public int projectionBytes(Request request) {
            return 100;
        }

        @Override
        public Evaluated classify(Request request, Profile profile, SecretValue key) {
            CALLS.incrementAndGet();
            throw new IllegalStateException("the provider failed in a way its contract has no answer for");
        }

        @Override
        public CatalogSnapshot catalog(SecretValue key, long nowMs) {
            return new CatalogSnapshot("throwing-provider", CatalogStatus.CURRENT, null, 24, List.of("boom-1"));
        }

        @Override
        public TransmittedFields transmittedFields() {
            return new TransmittedFields(List.of("model"), List.of("originalPrompt"), List.of("skillId"));
        }
    }

    static final class StubProvider implements JevProvider {
        static final AtomicInteger CALLS = new AtomicInteger();
        static final AtomicInteger KEYS_SEEN = new AtomicInteger();

        @Override
        public String providerId() {
            return "stub-provider";
        }

        @Override
        public String label() {
            return "Stub";
        }

        @Override
        public String endpoint() {
            return "stub://advice";
        }

        @Override
        public String credentialName() {
            return STUB_KEY_NAME;
        }

        @Override
        public double maximumCostUsd() {
            return 0.001;
        }

        @Override
        public double estimateUsd(long inputTokens) {
            return inputTokens * 1e-6;
        }

        @Override
        public Optional<Profile> profileFor(String modelId) {
            if (!"stub-1".equals(modelId)) {
                return Optional.empty();
            }
            return Optional.of(stubProfile("stub-provider", modelId));
        }

        @Override
        public int projectionBytes(Request request) {
            return 100;
        }

        @Override
        public Evaluated classify(Request request, Profile profile, SecretValue key) {
            CALLS.incrementAndGet();
            if (SecretValue.of("stub-key-value").equals(key)) {
                KEYS_SEEN.incrementAndGet();
            }
            List<Judgment> judgments = request.skills().stream().map(Skill::skillId).map(id -> new Judgment(id, Verdict.NOT_NEEDED, List.of("stub"), null)).toList();
            Response response = new Response("1.0.0", request.requestId(), request.operationKey(), request.requestDigest(), request.inventoryDigest(), ResponseStatus.SUCCESS, judgments,
                    List.of(), null);
            List<Score> scores = request.skills().stream().map(skill -> new Score(skill.skillId(), 0.9)).toList();
            return new Evaluation(response, new Usage(10L, 1L, null, null), DispatchState.STARTED, new Diagnostics(ScoreKind.CHOICE_CONFIDENCE, scores));
        }

        @Override
        public CatalogSnapshot catalog(SecretValue key, long nowMs) {
            return new CatalogSnapshot("stub-provider", CatalogStatus.CURRENT, null, 24, List.of("stub-1"));
        }

        @Override
        public TransmittedFields transmittedFields() {
            return new TransmittedFields(List.of("model"), List.of("originalPrompt"), List.of("skillId"));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        // The pauses are short and the deadline is two seconds, so the tests that run a retry to its end take seconds, not a minute.
        sennard = ToolServerSupport.startWithSourcesAndSettings(List.of(StubProviderConfiguration.class), "--sennard.jev.retry.initial-delay=20ms", "--sennard.jev.retry.max-delay=80ms",
                "--sennard.jev.retry.deadline=2s");
        owner = sennard.owner();
        register("JEV_API_KEY", "jev-test-key");
        register(STUB_KEY_NAME, "stub-key-value");
        register(THROWING_KEY_NAME, "throwing-key-value");
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static void register(String name, String secret) throws Exception {
        JsonNode prepared = sennard.prepare(ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_KEY_SET").put("name", name).put("fingerprint", "sha256:" + ToolContractServerTest.sha256(secret)), false);
        String intent = prepared.path("intent").asString();
        assertEquals(200, sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, secret).status());
    }

    /** Signs JEV on with model {@code provider/model}, an optional fallback evaluator ({@code provider/model} or null) and a budget limit. */
    private static void jevOn(String model, String fallback, double limitUsd) throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_SET").put("enabled", true).put("limitUsd", limitUsd).put("transmissionAllowed", true);
        change.putObject("presets").put("skill-classification", true);
        putModel(change, "model", model);
        putModel(change, "fallbackModel", fallback);
        sennard.sign(owner, change);
    }

    private static void putModel(ObjectNode change, String name, String value) {
        if (value == null) {
            change.putNull(name);
            return;
        }
        int slash = value.indexOf('/');
        change.putObject(name).put("providerId", value.substring(0, slash)).put("modelId", value.substring(slash + 1));
    }

    private static String advice(JsonNode request) {
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode().put("model", request.path("model").asString());
        ObjectNode answers = body.putObject("answers");
        for (String id : request.path("questions").propertyNames()) {
            ObjectNode answer = answers.putObject(id).put("type", "choice").put("choice", "needed").put("confidence", 0.9);
            answer.putObject("probabilities").put("needed", 0.9).put("not-needed", 0.05).put("uncertain", 0.05);
        }
        body.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
        return body.toString();
    }

    private static StubRemoteReply ok(JsonNode request) {
        return new StubRemoteReply(200, Map.of(), advice(request));
    }

    private record StubRemoteReply(int status, Map<String, String> headers, String body) {
    }

    /** Answers the n-th call since this point by {@code script}, every one after the script's end by its last. */
    private static void script(java.util.function.BiFunction<Integer, JsonNode, StubRemoteReply> replies) {
        int before = sennard.remote.jevCalls.get();
        sennard.remote.jevReply = (call, request) -> {
            StubRemoteReply reply = replies.apply(call - before, request);
            return reply == null ? ToolServerSupport.StubRemote.JevReply.CUT : new ToolServerSupport.StubRemote.JevReply(reply.status(), reply.headers(), reply.body());
        };
    }

    private static JsonNode classify() throws Exception {
        String session = sennard.session();
        String op = "op-" + UUID.randomUUID();
        JsonNode answer = sennard.call("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), session);
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
    }

    private static JsonNode usage() throws Exception {
        return sennard.state().path("settings").path("jev").path("usage");
    }

    private static double perCallUsd() throws Exception {
        return sennard.state().path("settings").path("jev").path("budget").path("perCallUsd").asDouble();
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- a

    @Test
    void aConnectionThatIsCutIsRetriedUntilTheProviderAnswers() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> call <= 2 ? null : ok(request));
        JsonNode classified = classify();
        assertEquals(3, sennard.remote.jevCalls.get() - before, "two cut connections, then the answer");
        JsonNode result = classified.path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), classified.toString());
        assertEquals(3, result.path("attempts").size(), result.path("attempts").toString());
        assertEquals("TRANSPORT_UNAVAILABLE", result.path("attempts").get(0).path("errorCode").asString());
        assertEquals("TRANSPORT_UNAVAILABLE", result.path("attempts").get(1).path("errorCode").asString());
        assertTrue(result.path("attempts").get(2).path("errorCode").isNull());
    }

    /**
     * A confidence is the evaluator's confidence in the judgment it gave, whatever that is, so the answer puts each skill's judgment beside its confidence (a 0.98 on a skill that is not
     * needed is not a strong need, and a 0.19 on a needed one is a weak need), the needed ones first.
     */
    @Test
    void theAnswerPairsEachSkillsJudgmentWithTheConfidenceInIt() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        script((call, request) -> {
            ObjectNode body = ToolServerSupport.MAPPER.createObjectNode().put("model", request.path("model").asString());
            ObjectNode answers = body.putObject("answers");
            int index = 0;
            for (String id : request.path("questions").propertyNames()) {
                boolean needed = index++ % 2 == 0;
                double confidence = needed ? 0.2 + index / 100.0 : 0.98;
                String choice = needed ? "needed" : "not-needed";
                ObjectNode answer = answers.putObject(id).put("type", "choice").put("choice", choice).put("confidence", confidence);
                ObjectNode probabilities = answer.putObject("probabilities");
                probabilities.put("needed", needed ? 0.5 : 0.01).put("not-needed", needed ? 0.25 : 0.98).put("uncertain", needed ? 0.25 : 0.01);
            }
            body.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
            return new StubRemoteReply(200, Map.of(), body.toString());
        });
        JsonNode classified = classify();
        JsonNode advice = classified.path("advice");
        assertEquals(classified.path("result").path("request").path("skillCount").asInt(), advice.size(), advice.toString());
        boolean seenNotNeeded = false;
        for (JsonNode skill : advice) {
            String judgment = skill.path("judgment").asString();
            assertTrue(skill.path("confidence").isNumber(), skill.toString());
            if ("not-needed".equals(judgment)) {
                seenNotNeeded = true;
                assertEquals(0.98, skill.path("confidence").asDouble(), 1e-9, "the confidence in 'not-needed'");
            } else {
                assertFalse(seenNotNeeded, "the needed skills come first: " + advice);
                assertEquals("needed", judgment, skill.toString());
                assertTrue(skill.path("confidence").asDouble() < 0.98, skill.toString());
            }
        }
        assertTrue(seenNotNeeded, advice.toString());
        assertFalse(classified.path("result").path("request").has("skills"), "the skills the server added are not sent back");
    }

    @Test
    void aServerErrorIsRetriedAtTheSameModelAndNeverGoesToTheFallbackEvaluator() throws Exception {
        jevOn("typesafe/jev-1.14.0", "typesafe/jev-1.13.0", 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> call == 1 ? new StubRemoteReply(503, Map.of(), "{}") : ok(request));
        JsonNode result = classify().path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), result.toString());
        List<String> models = sennard.remote.jevModels.subList(before, sennard.remote.jevModels.size());
        assertEquals(List.of("jev-1.14.0", "jev-1.14.0"), models, "the same target is tried again; the fallback evaluator is not asked for a communication error");
        assertEquals("SERVER_UNAVAILABLE", result.path("attempts").get(0).path("errorCode").asString());
    }

    @Test
    void tooManyRequestsIsRetriedAfterTheTimeTheProviderNamed() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> call == 1 ? new StubRemoteReply(429, Map.of("Retry-After", "1"), "{}") : ok(request));
        JsonNode result = classify().path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), result.toString());
        assertEquals(2, sennard.remote.jevCalls.get() - before);
        // What the wait is (the one second the provider named, not the 20 ms pause of this test's settings) is read from the retry's policy, not measured: JevRetryTest.
        assertEquals("1", result.path("attempts").get(0).path("rateLimitObservation").path("retryAfter").asString(), "the header is reported as it was sent");
    }

    @Test
    void aRetryAfterBeyondTheDeadlineStopsTheTriesAtOnce() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> new StubRemoteReply(429, Map.of("Retry-After", "3600"), "{}"));
        JsonNode classified = classify();
        assertEquals(1, sennard.remote.jevCalls.get() - before, "the hour asked for is past the deadline: no second call, and the pause is not slept");
        JsonNode response = classified.path("result").path("response");
        assertEquals("UNAVAILABLE", response.path("status").asString(), classified.toString());
        assertEquals("RATE_LIMITED", response.path("error").path("code").asString());
        assertEquals("PROPOSED", classified.path("selectionStatus").asString(), "the agent chooses itself");
    }

    @Test
    void aProviderThatNeverRecoversIsTriedUntilTheDeadlineAndThenTheFailureIsReturned() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> new StubRemoteReply(503, Map.of(), "{}"));
        JsonNode result = classify().path("result");
        int calls = sennard.remote.jevCalls.get() - before;
        // The deadline (two seconds here) is what ends the tries; that it counts from the request's start by the clock, and ends a run whose wait would pass it, is JevRetryTest's.
        assertTrue(calls >= 3, "tried again and again: " + calls);
        assertEquals(calls, result.path("attempts").size(), "every try is an attempt of the result");
        assertEquals("SERVER_UNAVAILABLE", result.path("response").path("error").path("code").asString());
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- b, c

    @Test
    void aRefusedKeyAWrongRequestOrAWrongAnswerIsNeverRetriedAndWithNoFallbackTheFailureComesBack() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 1000);
        Map<Integer, String> expected = Map.of(401, "AUTH_UNAVAILABLE", 403, "AUTH_UNAVAILABLE", 400, "API_UNAVAILABLE", 404, "API_UNAVAILABLE");
        for (Map.Entry<Integer, String> failure : expected.entrySet()) {
            int before = sennard.remote.jevCalls.get();
            script((call, request) -> new StubRemoteReply(failure.getKey(), Map.of(), "{}"));
            JsonNode classified = classify();
            assertEquals(1, sennard.remote.jevCalls.get() - before, "HTTP " + failure.getKey() + " is not tried again");
            JsonNode response = classified.path("result").path("response");
            assertEquals(failure.getValue(), response.path("error").path("code").asString(), classified.toString());
            assertEquals("PROPOSED", classified.path("selectionStatus").asString());
            assertEquals(1, classified.path("result").path("attempts").size());
        }
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> new StubRemoteReply(200, Map.of(), "{\"model\":\"jev-1.14.0\"}"));
        JsonNode wrongForm = classify();
        assertEquals(1, sennard.remote.jevCalls.get() - before, "an answer in the wrong form is not asked for again");
        assertEquals("INVALID_PROVIDER_RESPONSE", wrongForm.path("result").path("response").path("error").path("code").asString(), wrongForm.toString());
    }

    @Test
    void aFailureThatIsNotCommunicationGoesToTheFallbackEvaluatorOnce() throws Exception {
        jevOn("typesafe/jev-1.14.0", "typesafe/jev-1.13.0", 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> call == 1 ? new StubRemoteReply(401, Map.of(), "{}") : ok(request));
        JsonNode result = classify().path("result");
        assertEquals(List.of("jev-1.14.0", "jev-1.13.0"), sennard.remote.jevModels.subList(before, sennard.remote.jevModels.size()), "the chosen model once, then the fallback evaluator");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), result.toString());
        assertEquals(2, result.path("attempts").size());
        assertEquals("jev-1.14.0", result.path("attempts").get(0).path("modelId").asString());
        assertEquals("AUTH_UNAVAILABLE", result.path("attempts").get(0).path("errorCode").asString());
        assertEquals("jev-1.13.0", result.path("attempts").get(1).path("modelId").asString());
    }

    @Test
    void whenTheFallbackEvaluatorFailsToo_theFailureIsReturnedAndNobodyIsAskedAThirdTime() throws Exception {
        jevOn("typesafe/jev-1.14.0", "typesafe/jev-1.13.0", 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> new StubRemoteReply(403, Map.of(), "{}"));
        JsonNode result = classify().path("result");
        assertEquals(2, sennard.remote.jevCalls.get() - before);
        assertEquals("AUTH_UNAVAILABLE", result.path("response").path("error").path("code").asString());
        assertEquals(2, result.path("attempts").size());
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- d

    @Test
    void aSecondProviderOfTheContractIsChosenByItsIdAndTheProviderPageListsBoth() throws Exception {
        jevOn("stub-provider/stub-1", null, 1000);
        int remoteBefore = sennard.remote.jevCalls.get();
        int stubBefore = StubProvider.CALLS.get();
        int keysBefore = StubProvider.KEYS_SEEN.get();
        JsonNode result = classify().path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), result.toString());
        assertEquals(stubBefore + 1, StubProvider.CALLS.get(), "the stub provider was asked");
        assertEquals(keysBefore + 1, StubProvider.KEYS_SEEN.get(), "with the key registered under the stub provider's own credential name");
        assertEquals(remoteBefore, sennard.remote.jevCalls.get(), "TypeSafe was not called");
        assertEquals("stub-1", result.path("attempts").get(0).path("modelId").asString());

        JsonNode providers = sennard.state().path("settings").path("jev").path("providers");
        List<String> ids = new java.util.ArrayList<>();
        providers.forEach(provider -> ids.add(provider.path("providerId").asString()));
        assertTrue(ids.contains("stub-provider") && ids.contains(TypeSafeProvider.PROVIDER_ID), ids.toString());

        // A fallback evaluator of another provider is asked the same way.
        jevOn("typesafe/jev-1.14.0", "stub-provider/stub-1", 1000);
        script((call, request) -> new StubRemoteReply(401, Map.of(), "{}"));
        int stubsBefore = StubProvider.CALLS.get();
        JsonNode viaFallback = classify().path("result");
        assertEquals("SUCCESS", viaFallback.path("response").path("status").asString(), viaFallback.toString());
        assertEquals(stubsBefore + 1, StubProvider.CALLS.get());
    }

    @Test
    void theAddressThePageSaysTheKeyIsSentToIsTheOneTheClientCalls() throws Exception {
        // D-0223: the page lists what the provider tells (endpoint()), which is made from the same setting the HTTP client is built with; the local stand-in is where the call goes.
        JsonNode providers = sennard.state().path("settings").path("jev").path("providers");
        String shown = null;
        for (JsonNode provider : providers) {
            if (TypeSafeProvider.PROVIDER_ID.equals(provider.path("providerId").asString())) {
                shown = provider.path("endpoint").asString();
            }
        }
        assertEquals(sennard.remote.url("/jev") + "/systemone", shown, providers.toString());
        jevOn("typesafe/jev-1.14.0", null, 1000);
        int before = sennard.remote.jevCalls.get();
        script((call, request) -> ok(request));
        classify();
        assertEquals(1, sennard.remote.jevCalls.get() - before, "the call reached the address the page shows");
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- e

    @Test
    void aRetriedCallIsRecordedOnceByTheTokensItsAnswerReportedAndNothingStaysReserved() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 100000);
        JsonNode before = usage();
        script((call, request) -> call == 1 ? new StubRemoteReply(503, Map.of(), "{}") : ok(request));
        JsonNode result = classify().path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), result.toString());
        JsonNode after = usage();
        assertEquals(before.path("calls").asLong() + 1, after.path("calls").asLong(), "the answered try is recorded; the 503 reported no tokens and is not counted");
        assertEquals(before.path("estimatedUsd").asDouble() + 100 * 0.042 / 1_000_000, after.path("estimatedUsd").asDouble(), 1e-9, "100 input tokens at the published price");
        assertEquals(before.path("reservedUsd").asDouble(), after.path("reservedUsd").asDouble(), 1e-12, "both tries reserved their ceiling, and both released it when they were over");
        assertEquals(2, result.path("attempts").size());
        assertTrue(result.path("attempts").get(0).path("reservedCostUsd").asDouble() > 0 && result.path("attempts").get(1).path("reservedCostUsd").asDouble() > 0, "each try reserved");
        assertFalse(after.has("spentUsd"), "an answer carries no billed amount, so none is shown");
    }

    @Test
    void everyKindOfFailureReleasesItsReservation() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 100000);
        JsonNode before = usage();
        for (int status : new int[] {401, 400, 404}) {
            script((call, request) -> new StubRemoteReply(status, Map.of(), "{}"));
            classify();
        }
        script((call, request) -> new StubRemoteReply(200, Map.of(), "{\"model\":\"jev-1.14.0\"}"));
        classify();
        script((call, request) -> null);
        classify();
        JsonNode after = usage();
        assertEquals(before.path("reservedUsd").asDouble(), after.path("reservedUsd").asDouble(), 1e-12, "an HTTP error, a wrong answer and a cut connection leave nothing reserved");
        assertEquals(before.path("calls").asLong(), after.path("calls").asLong(), "none of them answered");
    }

    @Test
    void aProviderThatThrowsLeavesNothingReserved() throws Exception {
        jevOn("throwing-provider/boom-1", null, 100000);
        JsonNode before = usage();
        int callsBefore = ThrowingProvider.CALLS.get();
        String session = sennard.session();
        String op = "op-" + UUID.randomUUID();
        // The call reaches the provider (it reserved its ceiling to get there) and the provider throws: what the agent is told is not what this test is about, what the budget holds is.
        assertNotNull(sennard.callRaw("classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, "프롬프트"), SessionServerSupport.meta("windows", "codex", session, "none")));
        assertEquals(callsBefore + 1, ThrowingProvider.CALLS.get(), "the provider was reached, so its ceiling was reserved");
        JsonNode after = usage();
        assertEquals(before.path("reservedUsd").asDouble(), after.path("reservedUsd").asDouble(), 1e-12, "an exception of the provider releases the reservation, which no restart would");
        assertEquals(before.path("calls").asLong(), after.path("calls").asLong(), "the call did not answer");
    }

    @Test
    void theTriesStopWhereTheBudgetStops() throws Exception {
        // The budget has room for exactly one call. The first classification is told to wait a second; in that second another one is answered, which uses some of the room, so
        // the first one's next try no longer fits and is not sent.
        JsonNode now = usage();
        double room = now.path("estimatedUsd").asDouble() + now.path("reservedUsd").asDouble() + perCallUsd() + 1e-7;
        jevOn("typesafe/jev-1.14.0", null, room);
        int before = sennard.remote.jevCalls.get();
        java.util.concurrent.CountDownLatch firstSeen = new java.util.concurrent.CountDownLatch(1);
        script((call, request) -> {
            if (call == 1) {
                firstSeen.countDown();
                return new StubRemoteReply(503, Map.of("Retry-After", "1"), "{}");
            }
            return call == 2 ? ok(request) : new StubRemoteReply(503, Map.of(), "{}");
        });
        // The first classification waits on the server for its answer, and the server's HTTP client finishes the answer of a call on the common pool of this JVM (the JDK client's
        // sendAsync completes its future there). A task of the common pool that blocks, as supplyAsync with this blocking call would, holds that pool, which on a one-processor
        // test JVM has one thread: the provider's 503 then waits behind the test. So the call runs on an executor of the test's own.
        try (java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.CompletableFuture<JsonNode> first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return classify();
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            }, callers);
            assertTrue(firstSeen.await(20, java.util.concurrent.TimeUnit.SECONDS), "the first classification reached the provider");
            // The provider has the first try now, but the try holds its ceiling until its answer is settled. The case is the one where that try is over and only the pause is left, so
            // the second classification is started once the first try has given its reservation back (the answer, a 503, is what releases it), not when the request arrived.
            long giveUp = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (usage().path("reservedUsd").asDouble() > now.path("reservedUsd").asDouble() + 1e-12) {
                if (System.nanoTime() >= giveUp) {
                    // What the provider saw tells a first try that never got its 503 (one request) from a second try that is waiting for its answer (two).
                    List<String> seen = sennard.remote.jevTimes.stream().skip(sennard.remote.jevTimes.size() - (sennard.remote.jevCalls.get() - before))
                            .map(time -> Duration.ofNanos(System.nanoTime() - time).toMillis() + " ms ago").toList();
                    fail("the first try was answered with a 503 and has to give its reservation back: " + usage() + "; the provider received " + seen.size() + " request(s) of this test: " + seen);
                }
                Thread.sleep(10);
            }
            JsonNode second = classify();
            assertEquals("SUCCESS", second.path("result").path("response").path("status").asString(), second.toString());
            JsonNode result = first.get(20, java.util.concurrent.TimeUnit.SECONDS).path("result");
            assertEquals(2, sennard.remote.jevCalls.get() - before, "the first one's second try did not fit the budget and was not sent");
            assertEquals("SERVER_UNAVAILABLE", result.path("response").path("error").path("code").asString(), "what the provider last said, not a budget refusal");
            boolean budgetSkipped = false;
            for (JsonNode skipped : result.path("skipped")) {
                budgetSkipped |= "BUDGET_UNAVAILABLE".equals(skipped.path("reasonCode").asString());
            }
            assertTrue(budgetSkipped, "the result says why the tries stopped: " + result.path("skipped"));
            assertEquals(1, result.path("attempts").size());
        }
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- D-0097

    @Test
    void anAnswerThatReportsMoreOutputTokensThanTheCeilingIsRefusedAndOneAtTheCeilingIsKept() throws Exception {
        jevOn("typesafe/jev-1.14.0", null, 100000);
        for (long reported : new long[] {TypeSafeProvider.MAXIMUM_OUTPUT_TOKENS, TypeSafeProvider.MAXIMUM_OUTPUT_TOKENS + 1}) {
            script((call, request) -> {
                JsonNode answer = ToolServerSupport.MAPPER.readTree(advice(request));
                ((ObjectNode) answer.path("usage")).put("output_tokens", reported);
                return new StubRemoteReply(200, Map.of(), answer.toString());
            });
            JsonNode response = classify().path("result").path("response");
            if (reported <= TypeSafeProvider.MAXIMUM_OUTPUT_TOKENS) {
                assertEquals("SUCCESS", response.path("status").asString(), response.toString());
            } else {
                assertEquals("OUTPUT_TOO_LONG", response.path("error").path("code").asString(), response.toString());
            }
        }
    }

    /** Signs a JEV setting with the given switch, model ({@code provider/model} or null) and permission to send; the budget is large and the preset is on. */
    private static void jevSigned(boolean enabled, String model, boolean transmission) throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_SET").put("enabled", enabled).put("limitUsd", 1000).put("transmissionAllowed", transmission);
        change.putObject("presets").put("skill-classification", true);
        putModel(change, "model", model);
        putModel(change, "fallbackModel", null);
        sennard.sign(owner, change);
    }

    private static List<String> missing() throws Exception {
        List<String> codes = new java.util.ArrayList<>();
        sennard.state().path("settings").path("jev").path("missing").forEach(code -> codes.add(code.asString()));
        return codes;
    }

    @Test
    void theAdminStateGivesTheResultOfEachConditionInTheOrderThePageListsThem() throws Exception {
        // The keys of this class are registered, so the key condition is met; what the user left out is listed, and the page's ready flag agrees.
        jevSigned(false, null, false);
        assertEquals(List.of("SWITCH_OFF", "MODEL_NOT_SELECTED", "TRANSMISSION_NOT_ALLOWED"), missing());
        assertFalse(sennard.state().path("settings").path("jev").path("ready").asBoolean());

        jevSigned(true, null, false);
        assertEquals(List.of("MODEL_NOT_SELECTED", "TRANSMISSION_NOT_ALLOWED"), missing(), "switched on is not enough");
        assertFalse(sennard.state().path("settings").path("jev").path("ready").asBoolean());

        jevSigned(true, "typesafe/jev-1.14.0", true);
        assertEquals(List.of(), missing());
        assertTrue(sennard.state().path("settings").path("jev").path("ready").asBoolean());
    }

    @Test
    void theConfigOfAClassificationCarriesTheSignedPermissionAndProviderNotFixedValues() throws Exception {
        jevSigned(true, "typesafe/jev-1.14.0", true);
        script((call, request) -> ok(request));
        JsonNode on = classify().path("result").path("config");
        assertTrue(on.path("jevEnabled").asBoolean(), on.toString());
        assertTrue(on.path("externalClassificationAllowed").asBoolean(), "the signed permission to send");
        assertEquals("typesafe", on.path("providerProfileRegistryRef").asString(), "the provider of the signed model");

        jevSigned(true, "typesafe/jev-1.14.0", false);
        JsonNode notAllowed = classify().path("result").path("config");
        assertFalse(notAllowed.path("externalClassificationAllowed").asBoolean(), notAllowed.toString());
        assertEquals("typesafe", notAllowed.path("providerProfileRegistryRef").asString());

        jevSigned(true, null, true);
        JsonNode noModel = classify().path("result").path("config");
        assertTrue(noModel.path("externalClassificationAllowed").asBoolean(), noModel.toString());
        assertEquals("unconfigured", noModel.path("providerProfileRegistryRef").asString(), "no model is chosen");
    }
}
