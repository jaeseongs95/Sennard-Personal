// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.github.jaeseongs95.sennard.server.mcp.IntakeDeliveryBudget;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every path that puts the shared intake in front of a session, measured as the assembled server sends it: the initialize instructions, the answer of a session start (Claude Code and
 * Codex), the answer of a turn start (Claude Code, and agy, which restores at every turn start), and the applicability and exclusions of each skill in {@code get_skill_inventory}
 * ({@code classify_skills} lists the skills from the same {@code SkillInventoryReader}). Each path has its size budget in {@link IntakeDeliveryBudget}, and the block of the intake
 * file travels by the server instructions alone: no other path repeats more of it than {@link IntakeDeliveryBudget#QUOTED_BLOCK_BYTES}.
 *
 * <pre>
 * Case                                        Input                                              Expected
 * size of each path                           the five answers of the assembled server           each within its IntakeDeliveryBudget
 * the block travels once                      each answer against the block of the intake file   the instructions carry it; no other path repeats more than the quoted share
 * </pre>
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class IntakeDeliveryServerTest {
    private static final Path INTAKE = Path.of(System.getProperty("sennard.repo.root")).resolve("skills/orchestrator/references/intake.md");
    private static final Pattern BLOCK = Pattern.compile("<!-- skill-intake:start -->\\r?\\n(.*?)\\r?\\n<!-- skill-intake:end -->", Pattern.DOTALL);
    /** A run of this many characters that a path shares with the block is a repeat of it; shorter runs are common words. */
    private static final int REPEAT_RUN = 24;
    private static final String ORCHESTRATOR = "orchestrator";

    private static ToolServerSupport sennard;
    /** Each path by name, as the text a model reads from it. */
    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    /** Each skill's applicability and its exclusions, as the text a model reads from the inventory. */
    private static final Map<String, String> APPLICABILITY = new LinkedHashMap<>();
    private static final Map<String, String> CRITERIA = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.startWithDeployedSkills();
        PATHS.put("server instructions", instructions());
        PATHS.put("session start (claude)", hook("claude", "SessionStart", SessionServerSupport.uniqueSession(), false));
        PATHS.put("session start (codex)", hook("codex", "SessionStart", SessionServerSupport.uniqueSession(), false));
        PATHS.put("turn start (claude)", hook("claude", "UserPromptSubmit", SessionServerSupport.uniqueSession(), false));
        PATHS.put("turn start (agy)", hook("agy", "PreInvocation", SessionServerSupport.uniqueSession(), true));
        // The list is a summary; the lines a skill applies by come with the skills read by name, a few to a call.
        JsonNode listed = sennard.call("get_skill_inventory", new HashMap<>(), sennard.session()).path("data").path("skills");
        assertTrue(listed.size() > 0, "the inventory lists the installed skills: " + listed);
        List<String> ids = new ArrayList<>();
        listed.forEach(skill -> ids.add(skill.path("skillId").asString()));
        for (int from = 0; from < ids.size(); from += 6) {
            Map<String, Object> named = new HashMap<>();
            named.put("skillIds", ids.subList(from, Math.min(ids.size(), from + 6)));
            for (JsonNode skill : sennard.call("get_skill_inventory", named, sennard.session()).path("data").path("skills")) {
                String id = skill.path("skillId").asString();
                APPLICABILITY.put(id, join(skill.path("applicability")));
                CRITERIA.put(id, join(skill.path("applicability")) + "\n" + join(skill.path("exclusions")));
            }
        }
        assertEquals(ids.size(), APPLICABILITY.size(), "every listed skill was read whole");
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static String join(JsonNode lines) {
        List<String> texts = new ArrayList<>();
        lines.forEach(line -> texts.add(line.asString()));
        return String.join("\n", texts);
    }

    private static String instructions() {
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + sennard.server.port).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(60)).build()) {
            return client.initialize().instructions();
        }
    }

    private static String hook(String host, String event, String session, boolean invocationStart) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(sennard.server.port, "windows", host, event, null, session, body -> {
            if (invocationStart) {
                body.put("invocationNum", 0);
            }
        });
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String block() throws IOException {
        Matcher matcher = BLOCK.matcher(Files.readString(INTAKE, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "the intake file has a block between its markers");
        return matcher.group(1);
    }

    /** The bytes of {@code block} that lie in a run of {@link #REPEAT_RUN} characters or more that {@code carried} also holds, word for word. */
    static int repeatedBytes(String block, String carried) {
        Set<String> runs = new HashSet<>();
        for (int at = 0; at + REPEAT_RUN <= carried.length(); at++) {
            runs.add(carried.substring(at, at + REPEAT_RUN));
        }
        boolean[] covered = new boolean[block.length()];
        for (int at = 0; at + REPEAT_RUN <= block.length(); at++) {
            if (runs.contains(block.substring(at, at + REPEAT_RUN))) {
                Arrays.fill(covered, at, at + REPEAT_RUN, true);
            }
        }
        int size = 0;
        for (int at = 0; at < block.length(); at++) {
            if (covered[at]) {
                size += bytes(Character.toString(block.charAt(at)));
            }
        }
        return size;
    }

    @Test
    void theMeasureSeesACopyAndNotACommonSentence() {
        String block = "첫 도구 호출 전, 출력 없이도 요청 목적과 실제 행동을 스킬별 적용·제외 조건과 대조해 고른다. 키워드로만 고르지 않는다.";
        assertEquals(bytes(block), repeatedBytes(block, "앞 " + block + " 뒤"), "the whole block inside another text");
        assertEquals(0, repeatedBytes(block, "스킬별 적용 조건과 대조"), "a short run of common words is not a repeat");
        assertEquals(0, repeatedBytes(block, ""), "nothing carried");
    }

    @Test
    void everyPathStaysWithinItsSizeBudget() {
        List<String> over = new ArrayList<>();
        check(over, "server instructions", bytes(PATHS.get("server instructions")), IntakeDeliveryBudget.SERVER_INSTRUCTIONS);
        check(over, "session start (claude)", bytes(PATHS.get("session start (claude)")), IntakeDeliveryBudget.SESSION_START_HOOK);
        check(over, "session start (codex)", bytes(PATHS.get("session start (codex)")), IntakeDeliveryBudget.SESSION_START_HOOK);
        check(over, "turn start (claude)", bytes(PATHS.get("turn start (claude)")), IntakeDeliveryBudget.TURN_START_HOOK);
        check(over, "turn start (agy)", bytes(PATHS.get("turn start (agy)")), IntakeDeliveryBudget.TURN_START_HOOK);
        APPLICABILITY.forEach((skill, text) -> check(over, "applicability of " + skill, bytes(text), IntakeDeliveryBudget.SKILL_APPLICABILITY));
        assertEquals(List.of(), over, "a path is larger than its budget");
    }

    private static void check(List<String> over, String path, int size, IntakeDeliveryBudget budget) {
        if (size > budget.bytes()) {
            over.add(path + " is " + size + " bytes; the budget " + budget.name() + " is " + budget.bytes());
        }
    }

    @Test
    void theIntakeBlockTravelsByTheServerInstructionsAlone() throws IOException {
        String block = block();
        int blockBytes = bytes(block);
        int inInstructions = repeatedBytes(block, PATHS.get("server instructions"));
        assertTrue(inInstructions >= blockBytes * 9 / 10, "the instructions carry the block: " + inInstructions + " of " + blockBytes + " bytes");

        List<String> copies = new ArrayList<>();
        PATHS.forEach((path, text) -> {
            if (!"server instructions".equals(path)) {
                addIfRepeated(copies, block, path, text);
            }
        });
        CRITERIA.forEach((skill, text) -> addIfRepeated(copies, block, "applicability and exclusions of " + skill, text));
        assertEquals(List.of(), copies, "a path other than the server instructions repeats the intake block");
        assertTrue(CRITERIA.containsKey(ORCHESTRATOR), "the orchestrator, whose files hold the block, is in the inventory");
    }

    private static void addIfRepeated(List<String> copies, String block, String path, String text) {
        int repeated = repeatedBytes(block, text);
        if (repeated > IntakeDeliveryBudget.QUOTED_BLOCK_BYTES) {
            copies.add(path + " repeats " + repeated + " bytes of the block; at most " + IntakeDeliveryBudget.QUOTED_BLOCK_BYTES + " may be quoted");
        }
    }
}
