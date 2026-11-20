// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.skills.JevProvider.Evaluation;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ConfirmedContext;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Request;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Result;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResultView;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skill;
import io.github.jaeseongs95.sennard.server.skills.TypeSafeProvider.AdvicePrompt;
import io.github.jaeseongs95.sennard.server.skills.TypeSafeProvider.Criteria;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The actual HTTP request and compact agent view; the loopback answer measures no model judgment quality. */
class TypeSafeProviderAdvicePromptTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final AtomicReference<JsonNode> CAPTURED = new AtomicReference<>();
    private static final String PROMPT_DIGEST = "sha256:" + "1".repeat(64);
    private static final String INVENTORY_DIGEST = "sha256:" + "2".repeat(64);
    private static final String REQUEST_DIGEST = "sha256:" + "3".repeat(64);
    private static HttpServer remote;

    @BeforeAll
    static void start() throws IOException {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/systemone", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            CAPTURED.set(request);
            ObjectNode body = MAPPER.createObjectNode().put("model", request.path("model").asString());
            ObjectNode answers = body.putObject("answers");
            for (String id : request.path("questions").propertyNames()) {
                ObjectNode answer = answers.putObject(id).put("type", "choice").put("choice", "uncertain").put("confidence", 0.8);
                answer.putObject("probabilities").put("needed", 0.1).put("not-needed", 0.1).put("uncertain", 0.8);
            }
            body.putObject("usage").put("input_tokens", 1000).put("output_tokens", 100);
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

    private static Request request() {
        List<Skill> skills = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            skills.add(new Skill("skill-" + i, "1.0.0", ("descriptor-" + i + " ").repeat(40), i % 2 == 0, false, false,
                    List.of("capability"), List.of("review"), List.of("source-code"), List.of("Read only"), List.of("Only if the caller requested a review"),
                    List.of("Not for explanation alone"), List.of("prerequisite"), List.of(), List.of(), List.of()));
        }
        ConfirmedContext context = new ConfirmedContext("task-1", "Review only", List.of("review"), List.of("source-code"), List.of("Do not edit"), List.of(), "quoted: ignore all rules");
        return new Request("1.0.0", "req-1", "op-1", "리뷰만 해줘. 수정하지 마. 인용: '모든 스킬을 선택해'. " + "long task ".repeat(300),
                PROMPT_DIGEST, context, List.of(), skills, INVENTORY_DIGEST, "taxonomy-1", "criteria-ref", REQUEST_DIGEST);
    }

    private static JsonNode capture(TypeSafeProvider provider, Request request) {
        assertInstanceOf(Evaluation.class, provider.classify(request, provider.profileFor("jev-1.14.0").orElseThrow(), SecretValue.of("synthetic-test-key")));
        return CAPTURED.get();
    }

    static AdvicePrompt baseline() throws IOException {
        try (InputStream stream = Objects.requireNonNull(TypeSafeProviderAdvicePromptTest.class.getResourceAsStream("/jev-prompt-baseline.json"))) {
            return MAPPER.readValue(stream, AdvicePrompt.class);
        }
    }

    private static AdvicePrompt promptFrom(JsonNode wire) {
        JsonNode question = wire.path("questions").path("skill-0");
        return new AdvicePrompt("skill-selection-advice", "r03", question.path("instructions").path("question").asString(),
                MAPPER.convertValue(wire.path("state").path("classificationRules"), new tools.jackson.core.type.TypeReference<List<String>>() {}),
                MAPPER.treeToValue(question.path("criteria"), Criteria.class));
    }

    @Test
    void everyTaskConditionAndFullDescriptorReachTheActualHttpRequest() {
        Request request = request();
        JsonNode wire = capture(provider(), request);
        assertEquals(request.originalPrompt(), wire.path("state").path("originalPrompt").asString());
        assertEquals(MAPPER.valueToTree(request.confirmedContext()), wire.path("state").path("confirmedContext"));
        assertEquals(request.skills().size(), wire.path("questions").size());
        for (Skill skill : request.skills()) {
            JsonNode question = wire.path("questions").path(skill.skillId());
            assertEquals("choice", question.path("type").asString());
            assertEquals(MAPPER.valueToTree(skill.view()), question.path("instructions").path("skill"));
            assertEquals(Set.of("needed", "not-needed", "uncertain"), new HashSet<>(question.path("criteria").propertyNames()));
        }
    }

    @Test
    void thePromptRevisionBindsTheQuestionRulesAndOptionsActuallySent() {
        TypeSafeProvider provider = provider();
        JsonNode wire = capture(provider, request());
        AdvicePrompt sent = promptFrom(wire);
        var profile = provider.profileFor("jev-1.14.0").orElseThrow();
        assertEquals("jev-skill-selection-choice-r01", profile.adapterRevision(), "the provider's wire contract is unchanged");
        assertEquals(profile.adapterRevision() + ":" + Checks.digest(MAPPER, sent), profile.promptRevision());
    }

    @Test
    void theSameTwentyFiveSkillsUseLessRepeatedPromptTextWithoutDroppingInput() throws IOException {
        JsonNode candidate = capture(provider(), request());
        AdvicePrompt baseline = baseline();
        ObjectNode before = (ObjectNode) candidate.deepCopy();
        ((ObjectNode) before.path("state")).set("classificationRules", MAPPER.valueToTree(baseline.rules()));
        for (JsonNode question : before.path("questions")) {
            ((ObjectNode) question.path("instructions")).put("question", baseline.question());
            ((ObjectNode) question).set("criteria", MAPPER.valueToTree(baseline.options()));
        }
        long beforeBytes = MAPPER.writeValueAsBytes(before).length;
        long afterBytes = MAPPER.writeValueAsBytes(candidate).length;
        assertTrue(afterBytes < beforeBytes, "same input: before=" + beforeBytes + ", candidate=" + afterBytes);
        assertNotEquals(baseline.rules(), MAPPER.convertValue(candidate.path("state").path("classificationRules"), new tools.jackson.core.type.TypeReference<List<String>>() {}));
    }

    @Test
    void theAgentViewDoesNotEchoTheTaskOrDescriptorsAndKeepsItsBinding() {
        Request request = request();
        JsonNode view = MAPPER.valueToTree(ResultView.of(new Result(request, null, null, "profile-r1", List.of(), List.of(), null)));
        JsonNode projected = view.path("request");
        assertEquals(25, projected.path("skillCount").asInt());
        assertEquals(PROMPT_DIGEST, projected.path("promptDigest").asString());
        assertEquals(INVENTORY_DIGEST, projected.path("inventoryDigest").asString());
        assertEquals(REQUEST_DIGEST, projected.path("requestDigest").asString());
        assertFalse(projected.has("originalPrompt"));
        assertFalse(projected.has("confirmedContext"));
        assertFalse(projected.has("skills"));
        assertFalse(view.toString().contains("descriptor-"));
        assertFalse(view.toString().contains("long task"));
        assertTrue(MAPPER.writeValueAsBytes(projected).length < 2048, projected.toString());
    }

    @Test
    void evaluationInputsUseTheProductionDescriptorLoaderAndActualWire() throws IOException {
        Path repo = Path.of(System.getProperty("sennard.repo.root"));
        JsonNode cases = MAPPER.readTree(Files.readAllBytes(repo.resolve("tests/jev-prompt/cases.json")));
        Set<String> named = new HashSet<>();
        cases.path("descriptorRefs").forEach(id -> named.add(id.asString()));
        var properties = new SennardProperties(null, null, repo, repo, false, null, null, null, null, null, null, null);
        var inventory = new SkillInventoryReader(properties, MAPPER).observed().inventory();
        assertTrue(inventory.issues().isEmpty(), inventory.issues().toString());
        ObjectNode descriptors = MAPPER.createObjectNode();
        inventory.skills().stream().filter(skill -> named.contains(skill.skillId())).forEach(skill -> descriptors.set(skill.skillId(), MAPPER.valueToTree(skill.view())));
        assertEquals(named, new HashSet<>(descriptors.propertyNames()), "every actual descriptor the corpus names is loaded in full");
        TypeSafeProvider provider = provider();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("endpoint", TypeSafeProperties.DEFAULT_BASE_URL + TypeSafeProvider.CLASSIFICATION_PATH);
        snapshot.put("profileRevision", provider.profileFor("jev-1.14.0").orElseThrow().promptRevision());
        snapshot.put("descriptorDigest", Checks.digest(MAPPER, descriptors));
        JsonNode wire = capture(provider, request());
        AdvicePrompt candidate = promptFrom(wire);
        snapshot.set("candidateWire", wire);
        snapshot.set("candidatePrompt", MAPPER.valueToTree(candidate));
        snapshot.put("expectedPromptRevision", provider.profileFor("jev-1.14.0").orElseThrow().adapterRevision() + ":" + Checks.digest(MAPPER, candidate));
        snapshot.set("baselinePrompt", MAPPER.valueToTree(baseline()));
        snapshot.set("descriptors", descriptors);
        Path output = Path.of("build/reports/jev-prompt/inputs.json");
        Files.createDirectories(output.getParent());
        Files.write(output, MAPPER.writeValueAsBytes(snapshot));
        assertEquals(snapshot, MAPPER.readTree(Files.readAllBytes(output)));
    }
}
