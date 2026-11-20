// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.server.skills.SkillClassificationService;
import io.modelcontextprotocol.client.McpSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent does not have to ask JEV for advice (the user's report: JEV was on and the agents did not use it): when JEV can be asked, the server starts the classification at the
 * user's prompt and the next hook that carries context gives its short result once. Through the real hooks, the real tool calls, the real signed settings and the real
 * TypeSafe client; only the provider's far end is the stand-in the other JEV tests use.
 *
 * <pre>
 * a  a ready JEV is asked once at the prompt (the hook does not wait), and a short advice comes once at a later hook
 * b  the advice names at most the cap, whatever the inventory holds
 * c  the agent's own classify_skills for the same prompt adopts the advice and does not call JEV again; another prompt asks again
 * d  a new prompt drops the advice of the one before
 * e  JEV off: nothing is asked, nothing is added, nothing is logged
 * f  a call that was made and failed adds no advice and is logged
 * g  Codex and Antigravity: the hooks that cannot carry the advice neither give nor take it, so it still comes at the one that can
 * </pre>
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class JevAutoAdviceServerTest {
    private static final String HEADER = "[Sennard skill advice]";
    private static final String NEEDED = ": needed (confidence ";
    private static final String HOST = "codex";

    private static ToolServerSupport sennard;
    private static ToolServerSupport.Passkey owner;
    private static ListAppender<ILoggingEvent> logged;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
        owner = sennard.owner();
        register("JEV_API_KEY", "jev-test-key");
        // Attached after the server started: Spring Boot's LoggingSystem resets the Logback context when the application starts, which detaches the appenders of every logger.
        logged = new ListAppender<>();
        logged.start();
        ((Logger) LoggerFactory.getLogger(SkillClassificationService.class)).addAppender(logged);
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static void register(String name, String secret) throws Exception {
        ObjectNode key = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_KEY_SET").put("name", name).put("fingerprint", "sha256:" + ToolContractServerTest.sha256(secret));
        String intent = sennard.prepare(key, false).path("intent").asString();
        assertEquals(200, sennard.commit(intent, owner.assertion(intent, sennard.adminOrigin), UUID.randomUUID().toString(), null, secret).status());
    }

    private static void jev(boolean enabled) throws Exception {
        ObjectNode change = ToolServerSupport.MAPPER.createObjectNode().put("op", "JEV_SET").put("enabled", enabled).put("limitUsd", 1000).put("transmissionAllowed", true);
        change.putObject("presets").put("skill-classification", true);
        change.putObject("model").put("providerId", "typesafe").put("modelId", "jev-1.14.0");
        change.putNull("fallbackModel");
        sennard.sign(owner, change);
    }

    /** The provider's answer: the confidence of each skill it judges needed (null for one it judges not needed). */
    private static String advice(JsonNode request, Function<String, Double> confidence) {
        ObjectNode body = ToolServerSupport.MAPPER.createObjectNode().put("model", request.path("model").asString());
        ObjectNode answers = body.putObject("answers");
        for (String id : request.path("questions").propertyNames()) {
            Double needed = confidence.apply(id);
            ObjectNode answer = answers.putObject(id).put("type", "choice");
            ObjectNode probabilities = answer.putObject("probabilities");
            if (needed == null) {
                answer.put("choice", "not-needed").put("confidence", 0.9);
                probabilities.put("needed", 0.05).put("not-needed", 0.9).put("uncertain", 0.05);
            } else {
                answer.put("choice", "needed").put("confidence", needed);
                probabilities.put("needed", needed).put("not-needed", (1 - needed) / 2).put("uncertain", (1 - needed) / 2);
            }
        }
        body.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
        return body.toString();
    }

    private static Double twoSkills(String id) {
        return switch (id) {
            case "minimal-implementation" -> 0.9;
            case "orchestrator" -> 0.7;
            default -> null;
        };
    }

    private static void answer(Function<String, Double> confidence) {
        sennard.remote.jevReply = (call, request) -> ToolServerSupport.StubRemote.JevReply.of(200, advice(request, confidence));
    }

    private static void awaited(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the stand-in provider was interrupted while it waited for the test", interrupted);
        }
    }

    private static String post(String event, String session, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(sennard.server.port, "windows", HOST, event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static String prompt(String session, String text) throws IOException {
        return post("UserPromptSubmit", session, body -> body.put("prompt", text));
    }

    private static String step(String session) throws IOException {
        return post("PostToolUse", session, body -> {
        });
    }

    /** The hooks after a prompt, until the advice comes: a classification runs on its own thread, so the hook that carries it is the first one after it finished. */
    private static String adviceAt(String session) throws Exception {
        for (int tries = 0; tries < 200; tries++) {
            String answer = step(session);
            if (answer.contains(HEADER)) {
                return answer;
            }
            Thread.sleep(50);
        }
        return fail("no hook carried the advice");
    }

    private static String newSession() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        post("SessionStart", session, body -> {
        });
        return session;
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static JsonNode classifyAsAgent(String session, String prompt) throws IOException {
        String op = "op-" + UUID.randomUUID();
        try (McpSyncClient client = DomainServerSupport.client(sennard.server.port, HOST)) {
            JsonNode answer = DomainServerSupport.callAsSent(client, "classify_skills", ToolContractServerTest.classifyInput(op, "req-" + op, prompt),
                    SessionServerSupport.meta("windows", HOST, session, "none"));
            assertTrue(answer.path("ok").asBoolean(), answer.toString());
            assertEquals(op, answer.path("data").path("result").path("response").path("operationKey").asString(), "the answer is bound to this call's own operation");
            return answer.path("data");
        }
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- a

    @Test
    void aReadyJevIsAskedOnceAtThePromptAndItsShortAdviceComesOnceAtALaterHook() throws Exception {
        jev(true);
        CountDownLatch providerMayAnswer = new CountDownLatch(1);
        sennard.remote.jevReply = (call, request) -> {
            awaited(providerMayAnswer);
            return ToolServerSupport.StubRemote.JevReply.of(200, advice(request, JevAutoAdviceServerTest::twoSkills));
        };
        int before = sennard.remote.jevCalls.get();
        String session = newSession();
        String work = "로그인 화면의 오류 문구를 고쳐 줘";

        // The provider has not answered and is held: a hook that waited for it would never return here.
        String atPrompt = prompt(session, work);
        assertFalse(atPrompt.contains(HEADER), atPrompt);
        assertFalse(atPrompt.contains("classify_skills"), "the agent is not asked to call the tool: " + atPrompt);
        assertFalse(step(session).contains(HEADER), "the answer is not ready, so there is nothing to give yet");

        providerMayAnswer.countDown();
        String advice = adviceAt(session);
        assertTrue(advice.contains("- minimal-implementation" + NEEDED + "0.90)"), advice);
        assertTrue(advice.contains("- orchestrator" + NEEDED + "0.70)"), advice);
        assertTrue(advice.indexOf("- minimal-implementation") < advice.indexOf("- orchestrator"), "the most confident first: " + advice);
        assertEquals(2, count(advice, NEEDED), "only what was judged needed: " + advice);
        assertFalse(advice.contains(work), "the prompt does not come back: " + advice);
        assertFalse(advice.contains("classify_skills"), advice);
        assertTrue(advice.length() < 2000, "the advice stays a few lines: " + advice.length());

        assertFalse(step(session).contains(HEADER), "the advice is given once");
        assertEquals(1, sennard.remote.jevCalls.get() - before, "JEV is asked once for the prompt");
    }

    /**
     * Each host gives the advice at the boundary that takes context, and the hooks of a host that cannot carry it leave it where it is (the user's report: it showed in Claude Code and
     * not in Codex or Antigravity). Codex's {@code PostModelRequest} has no place for text: it used to take the finished advice and then be refused as a 500, so the advice was lost
     * before the tool hook that could carry it. Antigravity carries context at its invocation boundary and not at a finished tool.
     */
    @ParameterizedTest
    @CsvSource({"codex,PostModelRequest,PostToolUse", "agy,PostToolUse,PostInvocation"})
    void theHooksThatCannotCarryTheAdviceLeaveItForTheOneThatCan(String host, String cannotCarry, String carries) throws Exception {
        jev(true);
        answer(JevAutoAdviceServerTest::twoSkills);
        int before = sennard.remote.jevCalls.get();
        String session = SessionServerSupport.uniqueSession();
        hookOf(host, "SessionStart", session);
        String work = "로그인 화면의 오류 문구를 고쳐 줘";
        if (agy(host)) {
            DomainServerSupport.observeAgyRoot(sennard.server, session);
            hookOf(host, "PreInvocation", session, body -> body.put("invocationNum", 0).put("lastUserInput", work));
        } else {
            hookOf(host, "UserPromptSubmit", session, body -> body.put("prompt", work));
        }

        // The answer comes within these hooks (the stand-in provider replies at once): none of them may give it or take it.
        for (int tries = 0; tries < 40; tries++) {
            String answer = hookOf(host, cannotCarry, session);
            assertFalse(answer.contains(HEADER), host + " " + cannotCarry + " cannot carry the advice: " + answer);
            Thread.sleep(50);
        }

        String advice = null;
        for (int tries = 0; tries < 200 && advice == null; tries++) {
            String answer = hookOf(host, carries, session);
            advice = answer.contains(HEADER) ? answer : null;
            Thread.sleep(50);
        }
        assertTrue(advice != null, host + " " + carries + " gave no advice");
        assertTrue(advice.contains("- minimal-implementation" + NEEDED + "0.90)"), advice);
        assertEquals(1, sennard.remote.jevCalls.get() - before, "JEV is asked once for the prompt");
    }

    private static boolean agy(String host) {
        return "agy".equals(host);
    }

    private static String hookOf(String host, String event, String session) throws IOException {
        return hookOf(host, event, session, body -> {
        });
    }

    /** A hook of the host, through the fixture of the event (an event with no recording of its own, Codex's model-request ones, is posted with the prompt's body). */
    private static String hookOf(String host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        String fixture = event.endsWith("ModelRequest") ? "UserPromptSubmit" : event;
        RawHttp.Response response = DomainServerSupport.hookFrom(fixture, sennard.server.port, "windows", host, event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- b

    @Test
    void theAdviceNamesAtMostTheCapWhateverTheInventoryHolds() throws Exception {
        jev(true);
        answer(id -> 0.9);
        String session = newSession();
        prompt(session, "모든 스킬이 필요한 큰 작업");
        String advice = adviceAt(session);
        assertEquals(SkillClassificationService.MAX_ADVISED, count(advice, NEEDED), advice);
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- c

    @Test
    void theAgentsOwnClassifyForTheSamePromptAdoptsTheAdviceAndAnotherPromptAsksAgain() throws Exception {
        jev(true);
        answer(JevAutoAdviceServerTest::twoSkills);
        String session = newSession();
        String work = "결제 모듈의 반올림 오류를 고쳐 줘";
        prompt(session, work);
        adviceAt(session);
        int asked = sennard.remote.jevCalls.get();

        JsonNode same = classifyAsAgent(session, work);
        assertEquals(asked, sennard.remote.jevCalls.get(), "the same prompt: JEV is not asked, nor paid, a second time");
        JsonNode result = same.path("result");
        assertEquals("SUCCESS", result.path("response").path("status").asString(), same.toString());
        assertFalse(result.path("response").path("judgments").isEmpty(), "the answer carries the judgments the hook's classification made");
        assertEquals(1, result.path("attempts").size(), "what was spent stays what it was: " + result.path("attempts"));

        classifyAsAgent(session, "다른 일: 배포 스크립트를 점검해 줘");
        assertEquals(asked + 1, sennard.remote.jevCalls.get(), "another prompt is another question");
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- d

    @Test
    void aNewPromptDropsTheAdviceOfTheOneBefore() throws Exception {
        jev(true);
        CountDownLatch providerMayAnswer = new CountDownLatch(1);
        sennard.remote.jevReply = (call, request) -> {
            awaited(providerMayAnswer);
            boolean first = request.toString().contains("첫째 요청");
            return ToolServerSupport.StubRemote.JevReply.of(200, advice(request, id -> id.equals(first ? "minimal-implementation" : "orchestrator") ? 0.9 : null));
        };
        String session = newSession();
        prompt(session, "첫째 요청: 목록을 정렬해 줘");
        prompt(session, "둘째 요청: 표를 인쇄해 줘");
        providerMayAnswer.countDown();

        String advice = adviceAt(session);
        assertTrue(advice.contains("- orchestrator" + NEEDED), advice);
        assertFalse(advice.contains("- minimal-implementation"), "the advice of the earlier prompt is not given for the latest one: " + advice);
        assertFalse(step(session).contains(HEADER), "and the earlier one does not come after it");
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- e

    @Test
    void aJevThatIsOffIsNotAskedAddsNothingAndLogsNothing() throws Exception {
        jev(false);
        answer(JevAutoAdviceServerTest::twoSkills);
        logged.list.clear();
        int before = sennard.remote.jevCalls.get();
        String session = newSession();

        assertFalse(prompt(session, "꺼진 상태의 요청").contains(HEADER));
        assertFalse(step(session).contains(HEADER));
        assertEquals(0, sennard.remote.jevCalls.get() - before, "nothing is sent while JEV is off");
        assertEquals(List.of(), logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList(), "the user's own setting is not logged on every prompt");
    }

    // --------------------------------------------------------------------------------------------------------------------------------------- f

    @Test
    void aCallThatWasMadeAndFailedAddsNoAdviceAndIsLoggedOnce() throws Exception {
        jev(true);
        sennard.remote.jevReply = (call, request) -> ToolServerSupport.StubRemote.JevReply.of(401, "{}");
        logged.list.clear();
        int before = sennard.remote.jevCalls.get();
        String session = newSession();

        prompt(session, "공급자가 거절하는 요청");
        for (int tries = 0; tries < 200 && logged.list.isEmpty(); tries++) {
            Thread.sleep(50);
        }
        assertEquals(1, logged.list.size(), logged.list.toString());
        assertTrue(logged.list.get(0).getFormattedMessage().contains("JEV advice for a prompt was not given"), logged.list.get(0).getFormattedMessage());
        assertTrue(logged.list.get(0).getFormattedMessage().contains("reason=AUTH_UNAVAILABLE"), logged.list.get(0).getFormattedMessage());
        assertFalse(step(session).contains(HEADER), "a failure is not given to the agent as advice");
        assertEquals(1, sennard.remote.jevCalls.get() - before, "a refused key is not retried");
    }
}
