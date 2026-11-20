// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.server.updates.PluginUpdateService;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the hooks of the continuity area do when they meet, on the assembled server and a real PostgreSQL database. A hook takes no module lock: what several requests of one session
 * (or of several) may do at once is decided by the statements' conditions and the database's keys. Nothing here retries or waits for a time. The races of one key are not left to
 * who arrives when (on one CPU the requests would never meet, and a test that passes only then judges nothing): the test holds a lock of the database on a connection of its own,
 * sends the requests, looks at the database until the waits it expects are there ({@link HookRaceLocks}, the contract of the store tests' {@code LockWaits}), and only then lets
 * the lock go; a wait that is not seen fails the test. What is judged is the answers and the facts the database holds afterwards (rows, counters).
 *
 * <pre>
 * R2  the whole hook order      SessionStart, UserPromptSubmit, PreToolUse, PostToolUse, SubagentStart, Stop of one session are each answered 200 (there is no module lock row to take)
 * R4  three Stops               the Stops of one turn that wait for the same task row ask the question once: one ASKED row, one answer that continues the turn, every answer 200
 * R4b a Stop that loses         the Stop that meets the recap question of its prompt, written at that moment by another turn end, ends in 409 HOOK_HANDLER_CONFLICT (DUPLICATE)
 * R5  three first prompts       the first prompts of a registered session that has no task, held at the task's creation: one is answered 200 and makes the task, the others end in
 *                               409 HOOK_HANDLER_CONFLICT (STORE_UNIQUE_VIOLATION)
 * R5b three first inputs        the first prompts of a session whose task exists, held at the messaging queue's first row of the session: one 200, the others 409 (DUPLICATE)
 * R6  two sessions' edits       two sessions' PreToolUse edits that arrive together are both answered 200, each with its own task (two sessions share no key: nothing to hold)
 * R7  three first edits         the continuity PreToolUse edits of a registered session that has no task, held at the task's creation: all are answered 200; the one that makes the task
 *                               gets the gate's own answer (its first file is let run), the two that lose the creation are denied with HOOK_HANDLER_CONFLICT instead of ending in 409
 *                               (AGS3-D-0276)
 * R8  the task's references     a row that refers to a task has a foreign key to it: the task is not deleted from under its observations, and no observation lacks its task
 * R9  eight update notices      the notice of a newer release is claimed once however many prompts ask for it together
 * </pre>
 */
@Tag("business-db")
class ContinuityHookRaceServerTest {
    private static final String CHECK = "[Sennard save check]";
    private static final int TOGETHER = 8;
    /** The requests a test holds at a lock of the database: each waiting request holds a connection of the server's pool (BusinessDb.POOL_SIZE, 4), so there are fewer of them than that. */
    private static final int WAITING = 3;
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static RawHttp.Response post(String event, String session, Consumer<ObjectNode> edit) throws IOException {
        return DomainServerSupport.hook(server.port, "windows", "claude", event, null, session, edit);
    }

    private static String ok(RawHttp.Response response) {
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    /** Runs the calls at once, each from its own thread released by one latch, and returns their results in order. */
    private static <T> List<T> together(List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static long count(String sql, Object... params) {
        return SessionServerSupport.count(server, sql, params);
    }

    private static long tasks() {
        return count("SELECT count(*) FROM sennard.continuity_tasks");
    }

    private static Consumer<ObjectNode> prompt() {
        return body -> body.put("prompt", "keep what I said " + UUID.randomUUID());
    }

    private static Consumer<ObjectNode> stop() {
        return body -> body.put("stop_hook_active", false);
    }

    private static Consumer<ObjectNode> write(String file) {
        return DomainServerSupport.tool("Write", DomainServerSupport.MAPPER.createObjectNode().put("file_path", file));
    }

    @Test
    void theWholeHookOrderOfASessionIsAnsweredEveryTime() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> {
        }));
        ok(post("UserPromptSubmit", session, prompt()));
        ok(post("PreToolUse", session, write("src/main/R2.java")));
        ok(post("PostToolUse", session, write("src/main/R2.java")));
        ok(DomainServerSupport.hookFrom("Stop", server.port, "windows", "claude", "SubagentStart", null, session,
                body -> body.put("hook_event_name", "SubagentStart").put("agent_id", "child-r2").put("agent_type", "general-purpose")));
        ok(post("Stop", session, stop()));
        assertEquals(0, count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sennard' AND table_name = 'module_lock'"), "the schema has no module lock row to queue on");
    }

    private static String taskOf(String session) {
        return server.bean(ContinuityStore.class).opaqueDigest("session", session);
    }

    private static String promptOf(String session) {
        return (String) SessionServerSupport.row(server, "SELECT user_prompt_id FROM sennard.user_prompt WHERE session_id = ?", session).get("user_prompt_id");
    }

    private static List<Callable<RawHttp.Response>> posts(String event, String session, Supplier<Consumer<ObjectNode>> body, int count) {
        List<Callable<RawHttp.Response>> calls = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            calls.add(() -> post(event, session, body.get()));
        }
        return calls;
    }

    /**
     * The Stops of one turn that wait for the same task row. The one statement that decides the question is the {@code UPDATE} of the task's {@code turn_asked_start} (nothing else in
     * the path of a Stop writes that row), so the test holds the task row {@code FOR NO KEY UPDATE} (which leaves the foreign keys of the observation rows alone), sends the Stops, and lets
     * the row go when the database shows every Stop waiting for it: the statements then meet at the row whatever the number of CPUs. The one whose statement changes the row asks; the
     * others find the turn asked, close it as unconfirmed, and are answered 200 (the recap question of the prompt was asked already, so no Stop meets another first creation).
     */
    @Test
    void stopsOfOneTurnThatWaitForTheSameTaskRowAskTheQuestionOnce() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> {
        }));
        ok(post("UserPromptSubmit", session, prompt()));
        SessionServerSupport.update(server, "INSERT INTO sennard.recap_nudge (user_prompt_id, nudged_at) VALUES (?, ?)", promptOf(session), Timestamps.toTimestamp(Instant.now()));
        long asked = count("SELECT count(*) FROM sennard.continuity_observations WHERE event = 'turn-check:asked'");
        List<RawHttp.Response> answers;
        HookRaceLocks.warm(server, WAITING);
        try (HookRaceLocks task = HookRaceLocks.holding("SELECT 1 FROM sennard.continuity_tasks WHERE task_correlation = ? FOR NO KEY UPDATE", taskOf(session));
                HookRaceLocks.Calls<RawHttp.Response> stops = HookRaceLocks.Calls.start(posts("Stop", session, ContinuityHookRaceServerTest::stop, WAITING))) {
            // Of the statements that wait for one row, the first is blocked by the holder itself and the others stand behind it on the row's tuple lock (PostgreSQL).
            task.awaitBlocked(1, "no Stop came to wait for the task row of the session", stops);
            task.awaitQueuedBehindBlocked(WAITING - 1, "the other Stops do not wait for the task row of the session: the statements did not meet", stops);
            task.release();
            answers = stops.answers();
        }
        List<String> texts = answers.stream().map(ContinuityHookRaceServerTest::ok).toList();
        assertEquals(1, texts.stream().filter(answer -> answer.contains(CHECK)).count(), "one Stop continues the turn with the question");
        assertEquals(asked + 1, count("SELECT count(*) FROM sennard.continuity_observations WHERE event = 'turn-check:asked'"), "one ASKED row");
    }

    /**
     * The first recap question of a prompt, written by another turn end at the moment a Stop writes it: the key is the prompt's, so the Stop that meets the other's row ends in the
     * translated unique violation (409 HOOK_HANDLER_CONFLICT, reason DUPLICATE); nothing catches it and nothing asks again. The other turn end is the test's connection, which inserts
     * the question's row and does not commit: the Stop reads that the prompt has no question (the row is not visible), inserts, and waits for it; the database shows that wait, and
     * only then the row is committed. The next Stop finds the question asked and is answered 200.
     */
    @Test
    void aStopThatMeetsTheRecapQuestionWrittenAtThatMomentEndsInTheConflictOfTheKey() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> {
        }));
        ok(post("UserPromptSubmit", session, prompt()));
        assertTrue(ok(post("Stop", session, stop())).contains(CHECK), "the first Stop asks the question of the turn and goes no further");
        String prompt = promptOf(session);
        assertEquals(0, count("SELECT count(*) FROM sennard.recap_nudge WHERE user_prompt_id = ?", prompt), "the prompt has no recap question yet");
        RawHttp.Response lost;
        try (HookRaceLocks question = HookRaceLocks.holding("INSERT INTO sennard.recap_nudge (user_prompt_id, nudged_at) VALUES (?, LOCALTIMESTAMP)", prompt);
                HookRaceLocks.Calls<RawHttp.Response> stops = HookRaceLocks.Calls.start(posts("Stop", session, ContinuityHookRaceServerTest::stop, 1))) {
            question.awaitBlocked(1, "the Stop waits for the recap question of its prompt: the statements did not meet", stops);
            question.release();
            lost = stops.answers().getFirst();
        }
        assertEquals(409, lost.status(), DomainServerSupport.text(lost));
        assertEquals("DUPLICATE", SessionServerSupport.json(lost).path("reason").stringValue(), DomainServerSupport.text(lost));
        assertEquals(1, count("SELECT count(*) FROM sennard.recap_nudge WHERE user_prompt_id = ?", prompt), "one recap question");
        ok(post("Stop", session, stop()));
    }

    /**
     * The service method of a prompt reads the session's task and makes it in its own transaction when there is none. Of the first prompts that find none the primary key lets one
     * insert; a prompt that meets the first one's row ends in the conflict of the key (409, HOOK_HANDLER_CONFLICT, reason STORE_UNIQUE_VIOLATION: the server works, this call lost the
     * creation), which nothing catches or runs again. Who arrives when is not left to chance: the session is registered by a start of a kind the continuity rules do nothing for (no
     * task is made), and the test holds the table of the tasks' current memory, the second insert of the use case that makes a task. The first prompt inserts the task and stops there
     * with its row not committed; the database shows the other two waiting for that row. Only then the lock goes: one prompt is answered 200 and made the task, the other two ended in
     * the conflict, whatever the number of CPUs.
     */
    @Test
    void firstPromptsOfARegisteredSessionWithNoTaskMakeOneTaskAndTheOthersEndInTheConflictOfTheKey() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> body.put("source", "other")));
        String task = taskOf(session);
        assertEquals(0, count("SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?", task), "the session is registered and has no task");
        List<RawHttp.Response> answers;
        HookRaceLocks.warm(server, WAITING);
        try (HookRaceLocks memory = HookRaceLocks.lockingWritesOf("sennard.continuity_current_memory");
                HookRaceLocks.Calls<RawHttp.Response> prompts = HookRaceLocks.Calls.start(posts("UserPromptSubmit", session, ContinuityHookRaceServerTest::prompt, WAITING))) {
            memory.awaitBlocked(1, "no prompt came to make the task and wait for its current memory", prompts);
            memory.awaitQueuedBehindBlocked(WAITING - 1, "the other prompts do not wait for the row of the task that the first one inserted: the statements did not meet", prompts);
            memory.release();
            answers = prompts.answers();
        }
        assertEquals(1, answers.stream().filter(answer -> answer.status() == 200).count(), "one prompt made the task and was answered 200: " + statuses(answers));
        for (RawHttp.Response answer : answers) {
            if (answer.status() != 200) {
                assertEquals(409, answer.status(), DomainServerSupport.text(answer));
                assertEquals("STORE_UNIQUE_VIOLATION", SessionServerSupport.json(answer).path("reason").stringValue(), DomainServerSupport.text(answer));
            }
        }
        assertEquals(1, count("SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?", task), "the first prompts of one session made one task");
        ok(post("UserPromptSubmit", session, prompt()));
    }

    /**
     * AGS3-D-0276 (the decision of 2026-11-16): the edit gate that loses the first creation of the session's task must still refuse the edit. The calls are the continuity
     * PreToolUse of Claude Code (the recorded body, a Write of a product file) of a registered session that has no task, held at the same table as the first prompts above: the first
     * edit inserts the task and stops there with its row not committed, and the database shows the other two waiting for it. When the lock goes, one edit is answered with the gate's
     * own decision, which for a request with no workflow is to refuse the file (no file is free of a workflow), and the two
     * that lost the creation, which ended in 409 and let the tool run when Claude Code took it as a failed hook, are answered 200 with the deny of the tool and the code of the
     * refusal. Whatever the number of CPUs, no edit gets through for having lost the race.
     */
    @Test
    void firstEditsOfASessionWithNoTaskAreDeniedForTheOnesThatLoseTheFirstCreation() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> body.put("source", "other")));
        String task = taskOf(session);
        assertEquals(0, count("SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?", task), "the session is registered and has no task");
        List<Callable<RawHttp.Response>> calls = new ArrayList<>();
        for (String file : List.of("src/main/R7a.java", "src/main/R7b.java", "src/main/R7c.java")) {
            calls.add(() -> edit(session, file));
        }
        List<RawHttp.Response> answers;
        HookRaceLocks.warm(server, WAITING);
        try (HookRaceLocks memory = HookRaceLocks.lockingWritesOf("sennard.continuity_current_memory");
                HookRaceLocks.Calls<RawHttp.Response> edits = HookRaceLocks.Calls.start(calls)) {
            memory.awaitBlocked(1, "no edit came to make the task and wait for its current memory", edits);
            memory.awaitQueuedBehindBlocked(WAITING - 1, "the other edits do not wait for the row of the task that the first one inserted: the statements did not meet", edits);
            memory.release();
            answers = edits.answers();
        }
        List<String> bodies = answers.stream().map(DomainServerSupport::text).toList();
        List<String> lost = new ArrayList<>();
        List<String> decided = new ArrayList<>();
        for (RawHttp.Response answer : answers) {
            assertEquals(200, answer.status(), "a refused gate is answered, not a failed hook: " + bodies);
            var output = SessionServerSupport.json(answer).path("hookSpecificOutput");
            assertEquals("deny", output.path("permissionDecision").asString(""), "every edit is denied (a request with no workflow has no file it may change): " + bodies);
            String reason = output.path("permissionDecisionReason").asString("");
            (reason.startsWith(HookTestSupport.GATE_REFUSED) ? lost : decided).add(reason);
        }
        // The edit that made the task is answered with the gate's own decision: its file is no file of any workflow of the request.
        assertEquals(1, decided.size(), "one edit made the task and the gate decided its file: " + bodies);
        assertTrue(decided.getFirst().contains("no workflow handles this request"), "the gate's own refusal says what is missing: " + bodies);
        assertEquals(WAITING - 1, lost.size(), "the two edits that lost the creation are denied, not let through as a failed hook: " + bodies);
        assertTrue(lost.stream().allMatch(reason -> reason.contains("HOOK_HANDLER_CONFLICT")), "and their reason says the server refused them with the conflict: " + bodies);
        assertEquals(1, count("SELECT count(*) FROM sennard.continuity_tasks WHERE task_correlation = ?", task), "the first edits of one session made one task");
    }

    private static RawHttp.Response edit(String session, String file) throws IOException {
        return DomainServerSupport.hook(server.port, "windows", "claude", "PreToolUse", "continuity", session, write(file));
    }

    /**
     * The first input of a session on the messaging queue: the prompt that finds no row of the session inserts it, in the same transaction that took the input into account. The key
     * is the session's, so a prompt that meets a row another writer inserted at that moment ends in the translated unique violation (409 HOOK_HANDLER_CONFLICT, reason DUPLICATE, as the
     * services of the application layer state it); nothing catches it and nothing runs it again. The other writer is the test's connection: it inserts the row and does not commit,
     * the three prompts read that the session has none (the row is not visible), insert, and wait for it, and the database shows all of them waiting before the row is committed. The
     * next prompt finds the row and is answered 200.
     */
    @Test
    void firstInputsOfASessionThatMeetTheQueuesRowWrittenAtThatMomentEndInTheConflictOfTheKey() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> {
        }));
        String ref = (String) SessionServerSupport.row(server, "SELECT session_ref FROM sennard.session_ref WHERE local_session_id = ?", session).get("session_ref");
        assertEquals(0, count("SELECT count(*) FROM sennard.input_observations WHERE session_ref = ?", ref), "the queue has no row of the session yet");
        List<RawHttp.Response> answers;
        HookRaceLocks.warm(server, WAITING);
        try (HookRaceLocks row = HookRaceLocks.holding("INSERT INTO sennard.input_observations (session_ref, state, observed_at, version) VALUES (?, 'DEFERRED', LOCALTIMESTAMP, 0)", ref);
                HookRaceLocks.Calls<RawHttp.Response> prompts = HookRaceLocks.Calls.start(posts("UserPromptSubmit", session, ContinuityHookRaceServerTest::prompt, WAITING))) {
            row.awaitBlocked(WAITING, "every prompt waits for the queue's row of the session: the statements did not meet", prompts);
            row.release();
            answers = prompts.answers();
        }
        for (RawHttp.Response answer : answers) {
            assertEquals(409, answer.status(), DomainServerSupport.text(answer));
            assertEquals("DUPLICATE", SessionServerSupport.json(answer).path("reason").stringValue(), DomainServerSupport.text(answer));
        }
        assertEquals(1, count("SELECT count(*) FROM sennard.input_observations WHERE session_ref = ?", ref), "one row of the session");
        ok(post("UserPromptSubmit", session, prompt()));
    }

    private static List<Integer> statuses(List<RawHttp.Response> answers) {
        return answers.stream().map(RawHttp.Response::status).toList();
    }

    @Test
    void editsOfTwoSessionsThatArriveTogetherAreBothAnsweredAndEachHasItsOwnTask() throws Exception {
        String first = SessionServerSupport.uniqueSession();
        String second = SessionServerSupport.uniqueSession();
        long before = tasks();
        List<String> answers = together(List.of(
                () -> ok(post("PreToolUse", first, write("src/main/R6a.java"))),
                () -> ok(post("PreToolUse", second, write("src/main/R6b.java")))));
        assertEquals(2, answers.size(), "both edits were answered 200");
        assertEquals(before + 2, tasks(), "each session has its own task");
    }

    @Test
    void aRowThatRefersToATaskHasAForeignKeyToIt() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        ok(post("SessionStart", session, body -> {
        }));
        ok(post("UserPromptSubmit", session, prompt()));
        String task = (String) SessionServerSupport.row(server, "SELECT task_correlation FROM sennard.continuity_observations ORDER BY observation_id DESC FETCH FIRST 1 ROW ONLY")
                .get("task_correlation");
        assertThrows(DataAccessException.class, () -> SessionServerSupport.update(server, "DELETE FROM sennard.continuity_tasks WHERE task_correlation = ?", task),
                "the task is not deleted while a row refers to it");
        assertThrows(DataAccessException.class, () -> SessionServerSupport.update(server, "INSERT INTO sennard.continuity_observations (task_correlation, epoch, event, turn_hash, outcome, observed_at) "
                + "VALUES (?, 1, 'orphan', NULL, 'SUCCEEDED', ?)", "no-such-task", Timestamps.toTimestamp(Instant.now())), "an observation of no task is refused");
        assertEquals(0, count("SELECT count(*) FROM sennard.continuity_observations o WHERE NOT EXISTS "
                + "(SELECT 1 FROM sennard.continuity_tasks t WHERE t.task_correlation = o.task_correlation)"), "no observation lacks its task");
    }

    /**
     * Not held at a lock, because the result does not depend on who arrives when. The notice is claimed by one conditional {@code UPDATE} of an existing row
     * ({@code WorkflowStore#claimPluginUpdateNotice}: {@code WHERE last_notified_version IS NULL}, or the version read), and there is no insert and no key to lose on. A request that
     * reads the row after the claim committed finds the version notified and takes nothing; a request whose statement waits for the claimer's row lock meets (READ COMMITTED) the
     * committed row again, its {@code WHERE} no longer holds, and it changes no row. Whether the eight requests overlap or follow one another, exactly one statement changes the row, and
     * no request ends in an error.
     */
    @Test
    void theUpdateNoticeIsClaimedOnceHoweverManyAskTogether() throws Exception {
        String now = Instant.now().toString();
        SessionServerSupport.update(server, "DELETE FROM sennard.plugin_update_state WHERE target_id = 'sennard'");
        SessionServerSupport.update(server, "INSERT INTO sennard.plugin_update_state (target_id, current_version, latest_version, latest_tag, latest_commit, comparison, "
                + "last_attempt_at, last_successful_check_at, next_check_at) VALUES ('sennard', '0.0.1', '99.0.0', 'v99.0.0', ?, 'UPDATE_AVAILABLE', ?, ?, ?)",
                "a".repeat(40), Timestamps.toTimestamp(now), Timestamps.toTimestamp(now), Timestamps.toTimestamp(Instant.now().plusSeconds(3600)));
        PluginUpdateService updates = server.bean(PluginUpdateService.class);
        List<Callable<Optional<PluginUpdateService.Notice>>> asks = new ArrayList<>();
        for (int index = 0; index < TOGETHER; index += 1) {
            asks.add(updates::noticeForTurn);
        }
        long notices = together(asks).stream().filter(Optional::isPresent).count();
        assertEquals(1, notices, "one request took the notice");
        assertTrue(updates.noticeForTurn().isEmpty(), "the notice is not given again");
    }
}
