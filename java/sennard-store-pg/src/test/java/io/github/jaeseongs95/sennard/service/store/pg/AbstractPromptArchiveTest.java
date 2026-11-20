// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.prompt.ArchivedSession;
import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveReader;
import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveWriter;
import io.github.jaeseongs95.sennard.app.prompt.PromptHit;
import io.github.jaeseongs95.sennard.app.prompt.PromptSource;
import io.github.jaeseongs95.sennard.app.prompt.RecapNudgeRepository;
import io.github.jaeseongs95.sennard.app.prompt.RecapNudgeService;
import io.github.jaeseongs95.sennard.app.prompt.SummaryState;
import io.github.jaeseongs95.sennard.app.prompt.TranscriptAnchorRepository;
import io.github.jaeseongs95.sennard.app.prompt.TranscriptAnchorService;
import io.github.jaeseongs95.sennard.app.prompt.TurnRecap;
import io.github.jaeseongs95.sennard.app.prompt.TurnRecapRepository;
import io.github.jaeseongs95.sennard.app.prompt.TurnRecapService;
import io.github.jaeseongs95.sennard.app.prompt.UserPromptRepository;
import io.github.jaeseongs95.sennard.app.prompt.UserPromptWrites;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.prompt.Recap;

/**
 * The prompt archive (sennard-app: {@link PromptArchiveWriter}, {@link PromptArchiveReader}, {@link TurnRecapService}, {@link RecapNudgeService} and their repositories) on a
 * real database (PostgreSQL), through {@link RewrittenAreasContext}, in the way of the board's and the messaging's tests: the primary keys, the
 * version column, the code's one write path for the user's words, and the database's own ordering of writers (no retry). The user's words must be word for word, unchangeable, and found again.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * a  stored as it came        store("Hello 가나다 😀\nline two", with a transcript path) -> one row: the text exactly, original_bytes = its UTF-8 length, prompt_digest = SHA-256 of it,
 *                             completeness COMPLETE, received_at = the clock's UTC time, the transcript path; no path (null or "") -> NULL; read back by recall(SESSION)
 * b  the empty prompt         store("") -> a row with the empty text (not NULL), 0 bytes, the digest of nothing; recall shows ""
 * c  over the limit           1 MiB + 2 bytes of Hangul (3 bytes each) -> completeness TRUNCATED, the kept text is 1 MiB - 1 bytes (never half a character), original_bytes and the digest
 *                             are the whole prompt's; the row holds the kept part
 * d  written once, by code    I4 (a) a second insert with the key of a stored prompt -> DuplicateKeyException, the stored text, digest, time, size flag and path as they were; the same words stored
 *                             again are a new row. I4 (b) over the {@code @Query} of UserPromptWrites, UserPromptRepository, TranscriptAnchorRepository, TurnRecapRepository and
 *                             RecapNudgeRepository (reflection, public methods): no UPDATE, DELETE, TRUNCATE or MERGE; the one INSERT into user_prompt is in UserPromptWrites; no
 *                             method without a query is anything but find, exists, count (and turn_recap's save). I4 (c) turn_recap is replaced by an UPDATE (case f), its prompt unchanged.
 *                             (These replace the old test that connected as the service role and had the database refuse the statements.)
 * f  recap                    first recording -> not replaced; second -> replaced and the text is the second, the version is 1; it goes to the session's newest prompt (the older has
 *                             none); a session with no prompt -> nothing recorded; recall shows each prompt's own recap
 * g  optimistic lock          two transactions read one recap and both save -> the second gets OptimisticLockingFailureException, the winner's text stays
 * h  many recordings          a first recording writes the recap; N threads record the recap of the same prompt at once -> every call succeeds (the replacement is one UPDATE
 *                             statement, no retry), one row, version N
 * h2 first recordings, race   the test holds a first recording's transaction open and a second recording of the same prompt comes in; the database says the second one is blocked by
 *                             the holder; the holder commits -> the second ends in the translated ConflictException DUPLICATE and wrote nothing; one row is left (LockWaits)
 * i  the reminder, once       asksNow -> true, then false; after a recap -> false; a new prompt asks once again; a session with no prompt -> false; N threads asking at once ->
 *                             exactly one true and one row; the repository's second insert for one prompt is a duplicate key
 * j  UTC times                the JVM zone set to Asia/Seoul: the stored received_at is the UTC wall time and the instant comes back as it went in (recall)
 * k  the search               text finds a part of a prompt ignoring only the case of ASCII A-Z; "100%" finds only "100% done", "a_b" does not find "aXb", a backslash finds the backslash;
 *                             a Korean part is found; empty and blank take every prompt; newest first; the limit cuts; several sessions at once; sessions not named are not read
 * k2 search interval          prompts on different days are returned newest first, the limit stops the search, and the inclusive start and exclusive end bound the result
 * k3 clock rollback           store an older and a later prompt, return the clock to the older time; no end bound includes both in descending order, explicit end remains exclusive,
 *                             a future start still includes the later row, and the result limit is respected
 * k4 one database query      a nonmatching search executes one prepared query through the real DataSource; the database's EXPLAIN ANALYZE plan is recorded in test output
 * k5 search index            database metadata reports the four session-order index columns in the required order and direction
 * l  invalid input            a blank, over-long (65) host or (513) session, a null prompt, limit 0 and 101, a 201-character query -> InvalidInputException VALIDATION_FAILED, nothing stored
 * n  the sessions             sessions() lists each session that has prompts once, the most recently active first
 * o  the anchor               remember(prompt, key) -> the key, one row (entry_key, located_at = the clock's UTC time); find -> the key, and nothing before it; the first key stays when another
 *                             is offered later (remember answers the kept one); the table has no column that could hold the transcript's text (only the key and the time)
 * p  anchors race             N threads remember different keys for one prompt that has its anchor at once -> every call succeeds with the same key, one row; the repository's second insert for
 *                             one prompt is a duplicate key
 * q  anchor stays             the repository's second insert for a prompt -> DuplicateKeyException and the service answers the kept position, the row and its time unchanged (no
 *                             method updates or deletes it: case d); a prompt that does not exist cannot be anchored (REFERENCE_VIOLATION)
 * r  anchor input             a blank key, a 513-character key, a blank prompt id -> VALIDATION_FAILED and nothing written; a 512-character key passes
 * t  the prompt's source      source(promptId) -> host, session, received time, text, truncated and transcript path exactly as stored (a cut prompt: truncated true; no path: null); an
 *                             unknown identifier -> nothing
 * s  schema facts             no row-level security on the four tables; each key is user_prompt_id; turn_recap, recap_nudge and transcript_anchor have a foreign key to user_prompt; no role other
 *                             than the one account holds a privilege on them; version is NOT NULL; the module row 'prompt' is 1
 * g2  Boot default path       the same cases run on a plain DataSource bean and Boot's transaction manager only (PromptArchiveBootDefaultTest)
 * </pre>
 *
 * The cases are written here and run by both subclasses; each subclass states how its context is connected. The class runs on the database the test run names
 * ({@link BusinessDbTest}).
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPromptArchiveTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-03T00:00:00Z");
    private static final int WRITERS = 8;
    private static final long MEBIBYTE = 1024L * 1024L;

    private RewrittenAreasContext context;

    /** How the subclass connects the application layer to the database. */
    abstract RewrittenAreasContext open(Instant start);

    @BeforeAll
    void start() throws Exception {
        // the schemas are migrated anew by @BusinessDbTest before this method; the one account of the shared database is what the application connects as
        context = open(T0);
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    final RewrittenAreasContext context() {
        return context;
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private long ownerLong(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String ownerText(String sql) throws SQLException {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /** The column as the database writes it in text: CAST to VARCHAR (PostgreSQL's CHAR(n) pads with blanks). */
    private static String asText(String column) {
        return "CAST(" + column + " AS VARCHAR(40))";
    }

    /** The columns of a table in table order, from the standard catalog. */
    private List<String> columnsOf(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement();
                ResultSet rows = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = '" + table + "' ORDER BY ordinal_position")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private PromptArchiveWriter writer() {
        return context.bean(PromptArchiveWriter.class);
    }

    private PromptArchiveReader reader() {
        return context.bean(PromptArchiveReader.class);
    }

    private RecapNudgeService nudges() {
        return context.bean(RecapNudgeService.class);
    }

    private void at(Instant instant) {
        context.clock().set(instant);
    }

    private PromptArchiveWriter.Archived store(String host, String session, String text) {
        return writer().store(host, session, text, null);
    }

    private List<PromptHit> recall(List<ArchivedSession> from, String query, int limit) {
        return reader().recall(from, query, limit);
    }

    private List<PromptHit> recallAll(String host, String session) {
        return recall(List.of(new ArchivedSession(host, session)), null, 100);
    }

    private Optional<TurnRecapService.Recorded> recap(String host, String session, String text) {
        Recap kept = ((Recap.Accepted) Recap.judge(text)).recap();
        return context.bean(TurnRecapService.class).record(host, session, kept, Operations.fresh("record_turn_recap", host + "/" + session));
    }

    private boolean asks(String host, String session) {
        return nudges().remindNow(host, session, SummaryState.CURRENT).isPresent();
    }

    // ------------------------------------------------------------------------------------------------------------------ a, b, c

    @Test
    void aPromptIsKeptWordForWordWithItsSizeDigestTimeAndTranscriptPath() throws Exception {
        String text = "Hello 가나다 😀\nline two\t \u00e9";
        at(T0);
        PromptArchiveWriter.Archived stored = writer().store("claude-code", "s-a", text, "/home/u/.claude/projects/p/s-a.jsonl");
        assertEquals(T0, stored.receivedAt());
        assertFalse(stored.truncated());

        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.user_prompt " + where));
        assertEquals(text, ownerText("SELECT prompt_text FROM sennard.user_prompt " + where));
        assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, ownerLong("SELECT original_bytes FROM sennard.user_prompt " + where));
        assertEquals(Digests.sha256Hex(text), ownerText("SELECT prompt_digest FROM sennard.user_prompt " + where));
        assertEquals("COMPLETE", ownerText("SELECT completeness FROM sennard.user_prompt " + where));
        assertEquals("/home/u/.claude/projects/p/s-a.jsonl", ownerText("SELECT transcript_path FROM sennard.user_prompt " + where));
        assertEquals("2026-01-01 00:00:00", ownerText("SELECT " + asText("received_at") + " FROM sennard.user_prompt " + where));
        assertEquals("claude-code", ownerText("SELECT host FROM sennard.user_prompt " + where));

        List<PromptHit> read = recallAll("claude-code", "s-a");
        assertEquals(List.of(new PromptHit(stored.userPromptId(), "claude-code", "s-a", T0, text, stored.originalBytes(), false, null)), read);

        PromptArchiveWriter.Archived withoutPath = writer().store("claude-code", "s-a2", "no path", "");
        assertNull(ownerText("SELECT transcript_path FROM sennard.user_prompt WHERE user_prompt_id = '" + withoutPath.userPromptId() + "'"));
        PromptArchiveWriter.Archived nullPath = writer().store("claude-code", "s-a3", "no path", null);
        assertNull(ownerText("SELECT transcript_path FROM sennard.user_prompt WHERE user_prompt_id = '" + nullPath.userPromptId() + "'"));
    }

    @Test
    void anEmptyPromptIsKeptAsAnEmptyText() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("codex", "s-b", "");
        assertEquals(0, stored.originalBytes());
        assertEquals("", ownerText("SELECT prompt_text FROM sennard.user_prompt WHERE user_prompt_id = '" + stored.userPromptId() + "'"), "the empty text, not NULL");
        assertEquals(Digests.sha256Hex(""), ownerText("SELECT prompt_digest FROM sennard.user_prompt WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
        List<PromptHit> read = recallAll("codex", "s-b");
        assertEquals(1, read.size());
        assertEquals("", read.get(0).promptText());
    }

    @Test
    void aPromptOverTheLimitIsCutAtACharacterAndKnownToBeCut() throws Exception {
        at(T0);
        String whole = "가".repeat((int) (MEBIBYTE / 3) + 1); // 349,526 characters: 1,048,578 bytes
        PromptArchiveWriter.Archived stored = store("gemini", "s-c", whole);
        assertTrue(stored.truncated());
        assertEquals(1_048_578L, stored.originalBytes());
        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        assertEquals("TRUNCATED", ownerText("SELECT completeness FROM sennard.user_prompt " + where));
        assertEquals(1_048_578L, ownerLong("SELECT original_bytes FROM sennard.user_prompt " + where));
        assertEquals(Digests.sha256Hex(whole), ownerText("SELECT prompt_digest FROM sennard.user_prompt " + where));
        assertEquals(349_525L, ownerLong("SELECT CHAR_LENGTH(prompt_text) FROM sennard.user_prompt " + where), "the characters that fit whole");
        assertEquals(1_048_575L, ownerLong("SELECT octet_length(prompt_text) FROM sennard.user_prompt " + where), "one byte under the limit: the next character would not fit");
        PromptHit read = recallAll("gemini", "s-c").get(0);
        assertTrue(read.truncated());
        assertEquals(1_048_578L, read.originalBytes());
        assertEquals(whole.substring(0, 349_525), read.promptText());
    }

    // ------------------------------------------------------------------------------------------------------------------ d

    /**
     * I4 (a), the replacement of the old grant test ({@code theUserWordsCannotBeChangedOrRemovedEvenByTheServiceRole}, which had the database refuse the statements): the words are written
     * once through the service, a second write with the key of a stored prompt is refused as a duplicate and the stored words and time are as they were, and the service has no
     * way to write the same words again over them (the same text again is a new row).
     */
    @Test
    void aSecondInsertWithTheKeyOfAStoredPromptIsADuplicateAndTheStoredWordsAndTimeStay() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-d", "what the user said");
        recap("claude-code", "s-d", "a recap");
        UserPromptWrites writes = context.bean(UserPromptWrites.class);
        TransactionTemplate transaction = new TransactionTemplate(context.transactionManager());
        Instant later = T1;
        assertThrows(DuplicateKeyException.class, () -> transaction.execute(status -> writes.insert(stored.userPromptId(), "claude-code", "s-d", later,
                "changed", 7L, "not the digest", "TRUNCATED", "/elsewhere.jsonl")), "the key of a stored prompt is taken, whatever the new values are");
        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        assertEquals("what the user said", ownerText("SELECT prompt_text FROM sennard.user_prompt " + where));
        assertEquals(Digests.sha256Hex("what the user said"), ownerText("SELECT prompt_digest FROM sennard.user_prompt " + where));
        assertEquals("2026-01-01 00:00:00", ownerText("SELECT " + asText("received_at") + " FROM sennard.user_prompt " + where));
        assertEquals("COMPLETE", ownerText("SELECT completeness FROM sennard.user_prompt " + where));
        assertNull(ownerText("SELECT transcript_path FROM sennard.user_prompt " + where));
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.user_prompt " + where));

        at(T1);
        PromptArchiveWriter.Archived again = store("claude-code", "s-d", "what the user said");
        assertNotEquals(stored.userPromptId(), again.userPromptId(), "the same words again are a new prompt, not a write over the first");
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.user_prompt"));
        assertEquals("2026-01-01 00:00:00", ownerText("SELECT " + asText("received_at") + " FROM sennard.user_prompt " + where), "the first prompt keeps its time");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.turn_recap WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
    }

    /**
     * I4 (b), the other half of the old grant test: no method of the repositories of the archive can write to user_prompt or transcript_anchor other than by INSERT, which for
     * user_prompt is in {@link UserPromptWrites} alone, and none deletes from any of the four tables. Read from the interfaces' {@code @Query} annotations through their public
     * methods (reflection in the test only); the statements that Spring Data derives from a method name (find, exists) are reads by their names, which this test also holds to.
     * turn_recap is replaced by an UPDATE ({@code save}, case f), which only {@link TurnRecapRepository} has.
     */
    @Test
    void noMethodOfTheArchiveRepositoriesUpdatesOrDeletesPromptsOrAnchorsAndOnlyUserPromptWritesInsertsPrompts() {
        Pattern writes = Pattern.compile("\\b(UPDATE|DELETE|TRUNCATE|MERGE|REPLACE)\\b", Pattern.CASE_INSENSITIVE);
        Pattern promptInsert = Pattern.compile("\\bINSERT\\s+INTO\\s+sennard\\.user_prompt\\b", Pattern.CASE_INSENSITIVE);
        Pattern anchorInsert = Pattern.compile("\\bINSERT\\s+INTO\\s+sennard\\.transcript_anchor\\b", Pattern.CASE_INSENSITIVE);
        Pattern readName = Pattern.compile("(find|exists|count)[A-Z].*");
        int queries = 0;
        int promptInserts = 0;
        int anchorInserts = 0;
        for (Class<?> repository : List.of(UserPromptWrites.class, UserPromptRepository.class, TranscriptAnchorRepository.class, TurnRecapRepository.class, RecapNudgeRepository.class)) {
            assertTrue(repository.isInterface(), repository.getName());
            for (Method method : repository.getMethods()) {
                String label = repository.getSimpleName() + "." + method.getName();
                Query query = method.getAnnotation(Query.class);
                if (query != null) {
                    queries++;
                    String sql = query.value();
                    boolean recapReplace = repository == TurnRecapRepository.class && "replace".equals(method.getName()) && sql.startsWith("UPDATE sennard.turn_recap SET ");
                    if (recapReplace) {
                        continue; // turn_recap alone is replaced, in one UPDATE statement that names no other table
                    }
                    assertFalse(writes.matcher(sql).find(), label + " must not UPDATE or DELETE: " + sql);
                    if (promptInsert.matcher(sql).find()) {
                        promptInserts++;
                        assertEquals(UserPromptWrites.class, repository, label + ": the one INSERT into user_prompt is in UserPromptWrites");
                    }
                    if (anchorInsert.matcher(sql).find()) {
                        anchorInserts++;
                        assertEquals(TranscriptAnchorRepository.class, repository, label);
                    }
                } else {
                    boolean recapSave = repository == TurnRecapRepository.class && "save".equals(method.getName()); // turn_recap alone is replaced: UPDATE ... WHERE version = ?
                    assertTrue(recapSave || readName.matcher(method.getName()).matches(), label + " has no @Query and is no find, exists or count by its name");
                }
            }
        }
        assertEquals(5, queries, "the statements written by hand: user_prompt insert, the anchor insert, the reminder insert, the recap's insert and the recap's replacing UPDATE; latest prompt is a derived read");
        assertEquals(1, promptInserts);
        assertEquals(1, anchorInserts);
    }

    /** I4 (c): turn_recap is the one that is replaced; a recording again is an UPDATE of the same row (the version moves), which is what the case wants. */
    @Test
    void theRecapIsTheOnlyRowOfTheArchiveThatAnUpdateReplacesAndItsPromptStaysAsItWas() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-d3", "prompt that stays");
        recap("claude-code", "s-d3", "first recap");
        at(T1);
        assertTrue(recap("claude-code", "s-d3", "second recap").orElseThrow().replaced());
        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        assertEquals("second recap", ownerText("SELECT recap_text FROM sennard.turn_recap " + where));
        assertEquals(1L, ownerLong("SELECT version FROM sennard.turn_recap " + where), "replaced in place");
        assertEquals("prompt that stays", ownerText("SELECT prompt_text FROM sennard.user_prompt " + where));
        assertEquals("2026-01-01 00:00:00", ownerText("SELECT " + asText("received_at") + " FROM sennard.user_prompt " + where));
    }

    // ------------------------------------------------------------------------------------------------------------------ f

    @Test
    void aRecapGoesToTheNewestPromptAndARecordingAgainReplacesIt() throws Exception {
        assertEquals(Optional.empty(), recap("claude-code", "s-f", "nothing to attach to"), "a session with no prompt");
        at(T0);
        PromptArchiveWriter.Archived older = store("claude-code", "s-f", "first prompt");
        at(T1);
        PromptArchiveWriter.Archived newer = store("claude-code", "s-f", "second prompt");

        at(T2);
        TurnRecapService.Recorded first = recap("claude-code", "s-f", "  did the first thing  ").orElseThrow();
        assertEquals(new TurnRecapService.Recorded(newer.userPromptId(), "did the first thing", T2, false), first, "trimmed, for the newest prompt, stamped by the clock");
        TurnRecapService.Recorded second = recap("claude-code", "s-f", "did it differently").orElseThrow();
        assertTrue(second.replaced());
        assertEquals("did it differently", second.recap());
        assertEquals(1L, ownerLong("SELECT version FROM sennard.turn_recap WHERE user_prompt_id = '" + newer.userPromptId() + "'"), "one insert, one update");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.turn_recap"));

        List<PromptHit> read = recallAll("claude-code", "s-f");
        assertEquals(List.of(newer.userPromptId(), older.userPromptId()), read.stream().map(PromptHit::userPromptId).toList(), "newest first");
        assertEquals("did it differently", read.get(0).recapText());
        assertNull(read.get(1).recapText(), "the older prompt has none");
    }

    // ------------------------------------------------------------------------------------------------------------------ g

    @Test
    void twoTransactionsThatReadOneRecapAndBothSaveLeaveTheSecondWithAnOptimisticLockFailure() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-g", "prompt");
        recap("claude-code", "s-g", "original recap");
        TurnRecapRepository recapRows = context.bean(TurnRecapRepository.class);
        PlatformTransactionManager manager = context.transactionManager();
        TransactionTemplate outer = new TransactionTemplate(manager);
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Instant now = T0;

        assertThrows(OptimisticLockingFailureException.class, () -> outer.execute(status -> {
            TurnRecap read = recapRows.findById(stored.userPromptId()).orElseThrow();
            assertEquals(0L, read.version());
            TurnRecap won = inner.execute(innerStatus -> recapRows.save(recapRows.findById(stored.userPromptId()).orElseThrow().replacedBy("the winner's", now)));
            assertEquals(1L, won.version());
            return recapRows.save(read.replacedBy("the loser's", now));
        }));
        assertEquals("the winner's", ownerText("SELECT recap_text FROM sennard.turn_recap WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
    }

    // ------------------------------------------------------------------------------------------------------------------ h

    @Test
    void manyRecordingsOfOnePromptAllSucceedAndLeaveOneRow() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-h", "prompt");
        assertFalse(recap("claude-code", "s-h", "recap first").orElseThrow().replaced(), "the first recording writes the row: version 0");
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            List<Future<Void>> writes = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                String text = "recap " + writer;
                writes.add(pool.submit(() -> {
                    go.await();
                    recap("claude-code", "s-h", text);
                    return null;
                }));
            }
            go.countDown();
            for (Future<Void> write : writes) {
                write.get(60, TimeUnit.SECONDS); // a replacement is one UPDATE: none fails, none is run again
            }
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.turn_recap WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
        assertEquals(WRITERS, ownerLong("SELECT version FROM sennard.turn_recap WHERE user_prompt_id = '" + stored.userPromptId() + "'"), "one insert and one replacement for every writer: no write was lost");
    }

    /**
     * Two first recordings of one prompt at the same moment: the test holds the first one's transaction open (the recap row is written and not committed) and a second recording
     * of the same session comes in. The test waits until the database itself says the second one is blocked by the holder. The second recording's UPDATE sees no row, its
     * INSERT waits on the primary key and, when the holder commits, fails; the service lets the translated duplicate-key {@code ConflictException} through. One row is left and
     * nothing is caught to go on or run again.
     */
    @Test
    void twoFirstRecordingsOfOnePromptAtTheSameMomentLeaveOneRowAndTheLaterEndsInTheTranslatedUniqueKeyError() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-h2", "prompt");
        TurnRecapRepository recapRows = context.bean(TurnRecapRepository.class);
        TransactionTemplate holder = new TransactionTemplate(context.transactionManager());
        CountDownLatch holderWroteTheRow = new CountDownLatch(1);
        CountDownLatch holderMayCommit = new CountDownLatch(1);
        AtomicLong holderBackend = new AtomicLong(); // the database's identifier of the holder's connection, taken inside its transaction (a wait of another connection does not count)
        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> holder.executeWithoutResult(status -> {
                holderBackend.set(LockWaits.backendOfThisTransaction(context.dataSource()));
                recapRows.insert(stored.userPromptId(), "the holder's recap", T0);
                holderWroteTheRow.countDown();
                hold(holderMayCommit);
            }));
            assertTrue(holderWroteTheRow.await(120, TimeUnit.SECONDS), "the holder did not write its row");
            Future<?> second = pool.submit(() -> recap("claude-code", "s-h2", "the second recap"));
            LockWaits.awaitWaiterBlockedBy(holderBackend.get(), Duration.ofSeconds(120), "the second recording did not come to wait for the holder's row");
            assertFalse(second.isDone(), "the second recording waits for the first one's commit");
            holderMayCommit.countDown();
            first.get(120, TimeUnit.SECONDS);
            ExecutionException lost = assertThrows(ExecutionException.class, () -> second.get(120, TimeUnit.SECONDS));
            ConflictException duplicate = assertInstanceOf(ConflictException.class, lost.getCause(), "the service lets the translated unique-key error through");
            assertEquals(ConflictCode.DUPLICATE, duplicate.code());
            assertEquals(0L, ownerLong("SELECT version FROM sennard.turn_recap " + where), "the later recording wrote nothing");
            assertEquals("the holder's recap", ownerText("SELECT recap_text FROM sennard.turn_recap " + where));
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.turn_recap " + where));
    }

    /** The holder's side of a latch, inside the holder's transaction (a callback that throws no checked exception). */
    private static void hold(CountDownLatch latch) {
        try {
            assertTrue(latch.await(120, TimeUnit.SECONDS), "a latch of the test was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the transaction open", interrupted);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ i

    @Test
    void thePromptIsAskedAboutOnceAndNeverWhenItHasItsRecap() throws Exception {
        at(T0);
        assertFalse(asks("claude-code", "s-i"), "no prompt, nothing to ask about");
        store("claude-code", "s-i", "first");
        assertTrue(asks("claude-code", "s-i"));
        assertFalse(asks("claude-code", "s-i"), "once per prompt");
        assertFalse(asks("claude-code", "s-i"));

        at(T1);
        store("claude-code", "s-i", "second");
        recap("claude-code", "s-i", "done");
        assertFalse(asks("claude-code", "s-i"), "the newest prompt has its recap");

        at(T2);
        store("claude-code", "s-i", "third");
        assertTrue(asks("claude-code", "s-i"), "a new prompt is asked about once again");
        assertEquals(2, ownerLong("SELECT count(*) FROM sennard.recap_nudge"));
    }

    @Test
    void aSessionSummaryThatIsMissingOrStaleIsAskedForEveryTimeAndTheRecapOncePerPrompt() throws Exception {
        at(T0);
        assertTrue(nudges().remindNow("claude-code", "s-i4", SummaryState.MISSING).isEmpty(), "no prompt, nothing to ask about");
        store("claude-code", "s-i4", "first");
        assertEquals(new RecapNudgeService.Reminder(true, SummaryState.MISSING), nudges().remindNow("claude-code", "s-i4", SummaryState.MISSING).orElseThrow(),
                "no recap and no summary: both in one reminder");
        assertEquals(new RecapNudgeService.Reminder(false, SummaryState.MISSING), nudges().remindNow("claude-code", "s-i4", SummaryState.MISSING).orElseThrow(),
                "the recap once per prompt, the missing summary every time");
        assertEquals(new RecapNudgeService.Reminder(false, SummaryState.STALE), nudges().remindNow("claude-code", "s-i4", SummaryState.STALE).orElseThrow(),
                "a summary older than the last prompt is asked for too");
        assertTrue(nudges().remindNow("claude-code", "s-i4", SummaryState.CURRENT).isEmpty(), "a current summary asks for nothing, the recap was asked");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.recap_nudge"), "one row: the recap's");

        at(T1);
        store("claude-code", "s-i4", "second");
        recap("claude-code", "s-i4", "done");
        assertTrue(nudges().remindNow("claude-code", "s-i4", SummaryState.CURRENT).isEmpty(), "the recap and the summary are there");
        assertEquals(new RecapNudgeService.Reminder(false, SummaryState.MISSING), nudges().remindNow("claude-code", "s-i4", SummaryState.MISSING).orElseThrow(), "the recap is there, the summary is not: the summary alone");
        assertEquals(new RecapNudgeService.Reminder(false, SummaryState.MISSING), nudges().remindNow("claude-code", "s-i4", SummaryState.MISSING).orElseThrow(), "and again, not once per prompt");

        at(T2);
        store("claude-code", "s-i4", "third");
        assertEquals(new RecapNudgeService.Reminder(true, SummaryState.CURRENT), nudges().remindNow("claude-code", "s-i4", SummaryState.CURRENT).orElseThrow(), "the summary is current: the recap alone, as before");
    }

    @Test
    void manyTurnEndsOfOnePromptAskExactlyOnce() throws Exception {
        at(T0);
        store("claude-code", "s-i2", "prompt");
        assertTrue(asks("claude-code", "s-i2"), "the first turn end asks and writes the reminder's row (the INSERT, made once)");
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            for (int turnEnd = 0; turnEnd < WRITERS; turnEnd++) {
                answers.add(pool.submit(() -> {
                    go.await();
                    return asks("claude-code", "s-i2");
                }));
            }
            go.countDown();
            int asked = 0;
            for (Future<Boolean> answer : answers) {
                asked += answer.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(0, asked, "the others find it asked");
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.recap_nudge"));
    }

    @Test
    void aSecondReminderRowForOnePromptMeetsThePrimaryKey() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-i3", "prompt");
        RecapNudgeRepository rows = context.bean(RecapNudgeRepository.class);
        TransactionTemplate transaction = new TransactionTemplate(context.transactionManager());
        Instant now = T0;
        Integer written = transaction.execute(status -> rows.insert(stored.userPromptId(), now));
        assertEquals(1, written.intValue());
        assertThrows(DuplicateKeyException.class, () -> transaction.execute(status -> rows.insert(stored.userPromptId(), now)));
        Boolean own = transaction.execute(status -> rows.existsById(stored.userPromptId()));
        Boolean unknown = transaction.execute(status -> rows.existsById(UUID.randomUUID().toString()));
        assertTrue(own);
        assertFalse(unknown);
    }

    // ------------------------------------------------------------------------------------------------------------------ j

    @Test
    void aTimeIsStoredAsTheUtcInstantWhateverZoneTheJvmIsIn() throws Exception {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            Instant instant = Instant.parse("2026-03-04T05:06:07.089Z");
            at(instant);
            PromptArchiveWriter.Archived stored = store("claude-code", "s-j", "tz");
            assertEquals(instant, recallAll("claude-code", "s-j").get(0).receivedAt(), "the instant comes back as it went in");
            assertEquals("2026-03-04 05:06:07.089", ownerText("SELECT " + asText("received_at") + " FROM sennard.user_prompt WHERE user_prompt_id = '" + stored.userPromptId() + "'"),
                    "the stored value is the UTC wall time, not Seoul's (14:06)");
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ k, n

    @Test
    void aSearchFindsATextWithoutRegardToCaseAndTakesTheWildcardsLiterally() throws Exception {
        List<ArchivedSession> session = List.of(new ArchivedSession("claude-code", "s-k"));
        at(T0);
        store("claude-code", "s-k", "Hello World");
        at(T0.plusSeconds(1));
        store("claude-code", "s-k", "100% done");
        at(T0.plusSeconds(2));
        store("claude-code", "s-k", "a_b");
        at(T0.plusSeconds(3));
        store("claude-code", "s-k", "aXb");
        at(T0.plusSeconds(4));
        store("claude-code", "s-k", "path C:\\temp\\x");
        at(T0.plusSeconds(5));
        store("claude-code", "s-k", "한글로 말하기 가나다");

        assertEquals(List.of("Hello World"), texts(recall(session, "hello", 10)), "the case of ASCII letters does not matter");
        assertEquals(List.of("Hello World"), texts(recall(session, "WORLD", 10)));
        assertEquals(List.of("100% done"), texts(recall(session, "100%", 10)), "a percent sign is a percent sign");
        assertEquals(List.of("a_b"), texts(recall(session, "a_b", 10)), "an underscore is not any character: aXb is not found");
        assertEquals(List.of("path C:\\temp\\x"), texts(recall(session, "\\temp", 10)), "a backslash is a backslash");
        assertEquals(List.of("한글로 말하기 가나다"), texts(recall(session, "말하기", 10)));
        assertEquals(List.of(), texts(recall(session, "no such text", 10)));

        List<String> everything = texts(recall(session, null, 10));
        assertEquals(List.of("한글로 말하기 가나다", "path C:\\temp\\x", "aXb", "a_b", "100% done", "Hello World"), everything, "newest first");
        assertEquals(everything, texts(recall(session, "", 10)));
        assertEquals(everything, texts(recall(session, "   ", 10)), "only white space is no search");
        assertEquals(everything.subList(0, 2), texts(recall(session, null, 2)), "the limit keeps the newest");
        assertEquals(List.of(), recall(List.of(), null, 10), "no session named, nothing read");
    }

    @Test
    void aRecallReadsSeveralSessionsAtOnceAndOnlyThoseNamed() throws Exception {
        at(T0);
        store("claude-code", "one", "from one");
        at(T1);
        store("codex", "two", "from two");
        at(T2);
        store("claude-code", "three", "from three");
        store("claude-code", "two", "same session name, another host");

        assertEquals(List.of("from two", "from one"), texts(recall(List.of(new ArchivedSession("claude-code", "one"), new ArchivedSession("codex", "two")), null, 10)));
        assertEquals(List.of("from one"), texts(recall(List.of(new ArchivedSession("claude-code", "one"), new ArchivedSession("codex", "two")), "one", 10)));
        List<String> sessions = reader().sessions().stream().map(s -> s.host() + "/" + s.sessionId()).toList();
        assertEquals(4, sessions.size());
        assertEquals(new TreeSet<>(List.of("claude-code/one", "codex/two", "claude-code/three", "claude-code/two")), new TreeSet<>(sessions), "each session once");
        assertTrue(sessions.get(0).startsWith("claude-code/"), "the most recently active first: " + sessions);
    }

    private static List<String> texts(List<PromptHit> hits) {
        return hits.stream().map(PromptHit::promptText).toList();
    }

    @Test
    void searchRespectsTheRequestedInterval() {
        List<ArchivedSession> session = List.of(new ArchivedSession("claude-code", "window-search"));
        at(T0);
        store("claude-code", "window-search", "old");
        at(T1);
        store("claude-code", "window-search", "middle");
        at(T2);
        store("claude-code", "window-search", "new");
        assertEquals(List.of("new", "middle", "old"), texts(reader().recall(session, null, 10, null, null)));
        assertEquals(List.of("new", "middle"), texts(reader().recall(session, null, 2, null, null)));
        assertEquals(List.of("middle"), texts(reader().recall(session, null, 10, T1, T2)));
        assertEquals(List.of(), texts(reader().recall(session, null, 10, T0.plusSeconds(1), T1)));
    }

    @Test
    void clockRollbackDoesNotHideAlreadyStoredPrompts() {
        List<ArchivedSession> session = List.of(new ArchivedSession("claude-code", "clock-rollback"));
        at(T0);
        store("claude-code", "clock-rollback", "older");
        at(T1);
        store("claude-code", "clock-rollback", "later");
        at(T0);
        assertEquals(List.of("later", "older"), texts(reader().recall(session, null, 10, null, null)));
        assertEquals(List.of("later"), texts(reader().recall(session, null, 1, null, null)));
        assertEquals(List.of("later"), texts(reader().recall(session, null, 10, T1, null)));
        assertEquals(List.of("older"), texts(reader().recall(session, null, 10, null, T1)));
    }

    @Test
    void noMatchExecutesOneRealDatabaseQueryAndReportsItsPlan() throws Exception {
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        javax.sql.DataSource counting = new org.springframework.jdbc.datasource.DelegatingDataSource(context.dataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(connection, args);
                                if (method.getName().equals("prepareStatement") && args != null && args.length > 0 && args[0] instanceof String sql
                                        && result instanceof java.sql.PreparedStatement statement) {
                                    captured.set(sql);
                                    return java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {java.sql.PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                                if (java.util.Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(statementMethod.getName())) {
                                                    executions.incrementAndGet();
                                                }
                                                try {
                                                    return statementMethod.invoke(statement, statementArgs);
                                                } catch (java.lang.reflect.InvocationTargetException cause) {
                                                    throw cause.getCause();
                                                }
                                            });
                                }
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException cause) {
                                throw cause.getCause();
                            }
                        });
            }
        };
        var wiring = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        wiring.registerSingleton("jdbc", new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(counting));
        wiring.registerSingleton("timestamps", context.bean(io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns.class));
        wiring.registerSingleton("sqlBuilder", context.bean(io.github.jaeseongs95.sennard.service.database.SqlBuilder.class));
        wiring.registerSingleton("clock", context.clock());
        var search = wiring.createBean(io.github.jaeseongs95.sennard.app.prompt.JdbcPromptSearch.class);
        assertEquals(List.of(), search.search(List.of(new ArchivedSession("claude-code", "no-match")), "never-found", 10, null, null));
        assertEquals(1, executions.get(), "one executed search through the actual DataSource");
        String sql = captured.get();
        assertFalse(sql.contains("received_at >="), "an omitted start adds no condition");
        assertFalse(sql.contains("received_at <"), "an omitted end adds no condition");
        // The plan that ran, in the database's own words: PostgreSQL's EXPLAIN ANALYZE.
        String planStatement = "EXPLAIN (ANALYZE, FORMAT TEXT) ";
        try (Connection actual = BusinessDb.connect(); var explain = actual.prepareStatement(planStatement + sql)) {
            explain.setString(1, "claude-code");
            explain.setString(2, "no-match");
            explain.setString(3, "%never-found%");
            explain.setInt(4, 10);
            try (ResultSet rows = explain.executeQuery()) {
                var plan = new StringBuilder();
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
                assertFalse(plan.isEmpty(), "the database returned the actual query plan");
                org.slf4j.LoggerFactory.getLogger(AbstractPromptArchiveTest.class).info("Search EXPLAIN ANALYZE:\n{}", plan);
            }
        }
    }

    @Test
    void searchIndexIncludesTheStableDescendingOrder() throws Exception {
        var columns = new java.util.TreeMap<Short, String>();
        // PostgreSQL's driver takes the schema as the schema (no catalog).
        try (Connection actual = BusinessDb.connect(); ResultSet rows = actual.getMetaData().getIndexInfo(null, "sennard", "user_prompt", false, false)) {
            while (rows.next()) {
                if ("user_prompt_session".equals(rows.getString("INDEX_NAME"))) {
                    columns.put(rows.getShort("ORDINAL_POSITION"), rows.getString("COLUMN_NAME") + ":" + rows.getString("ASC_OR_DESC"));
                }
            }
        }
        assertEquals(List.of("host:A", "session_id:A", "received_at:D", "user_prompt_id:D"), List.copyOf(columns.values()));
    }

    // ------------------------------------------------------------------------------------------------------------------ l

    @Test
    void invalidInputIsRefusedWithItsCodeAndStoresNothing() throws Exception {
        at(T0);
        String longHost = "h".repeat(65);
        String longSession = "s".repeat(513);
        for (Runnable call : List.<Runnable>of(
                () -> writer().store("", "s", "text", null),
                () -> writer().store(longHost, "s", "text", null),
                () -> writer().store("claude-code", " ", "text", null),
                () -> writer().store("claude-code", longSession, "text", null),
                () -> writer().store("claude-code", "s", null, null),
                () -> reader().recall(List.of(new ArchivedSession("claude-code", "s")), null, 0),
                () -> reader().recall(List.of(new ArchivedSession("claude-code", "s")), null, 101),
                () -> reader().recall(List.of(new ArchivedSession("claude-code", "s")), "q".repeat(201), 10))) {
            InvalidInputException refused = assertThrows(InvalidInputException.class, call::run);
            assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        }
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.user_prompt"));
        // the longest values that are allowed pass
        store("h".repeat(64), "s".repeat(512), "limits");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.user_prompt"));
        assertEquals(List.of("limits"), texts(recall(List.of(new ArchivedSession("h".repeat(64), "s".repeat(512))), null, 100)));
        assertEquals(List.of(), recall(List.of(new ArchivedSession("h".repeat(64), "s".repeat(512))), "q".repeat(200), 1));
    }

    // ------------------------------------------------------------------------------------------------------------------ o, p, q, r, t

    private TranscriptAnchorService anchors() {
        return context.bean(TranscriptAnchorService.class);
    }

    private String remember(String promptId, String entryKey) {
        return anchors().remember(promptId, entryKey);
    }

    private Optional<String> anchorOf(String promptId) {
        return anchors().find(promptId);
    }

    @Test
    void theAnchorKeepsWhereThePromptIsAndTheFirstPositionStays() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-o", "prompt");
        assertEquals(Optional.empty(), anchorOf(stored.userPromptId()), "nothing is kept before the first read");

        at(T1);
        assertEquals("entry-uuid-1", remember(stored.userPromptId(), "entry-uuid-1"));
        String where = "WHERE user_prompt_id = '" + stored.userPromptId() + "'";
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.transcript_anchor " + where));
        assertEquals("entry-uuid-1", ownerText("SELECT entry_key FROM sennard.transcript_anchor " + where));
        assertEquals("2026-01-02 00:00:00", ownerText("SELECT " + asText("located_at") + " FROM sennard.transcript_anchor " + where), "the clock's UTC time");
        assertEquals(Optional.of("entry-uuid-1"), anchorOf(stored.userPromptId()));

        at(T2);
        assertEquals("entry-uuid-1", remember(stored.userPromptId(), "another-entry"), "the first position found is the one kept");
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.transcript_anchor " + where));
        assertEquals("entry-uuid-1", ownerText("SELECT entry_key FROM sennard.transcript_anchor " + where));
        assertEquals("2026-01-02 00:00:00", ownerText("SELECT " + asText("located_at") + " FROM sennard.transcript_anchor " + where), "and it was not written again");
        assertEquals(List.of("user_prompt_id", "entry_key", "located_at"), columnsOf("transcript_anchor"), "a position and a time: no column for any text of the transcript");
    }

    @Test
    void manyReadingsOfOnePromptAllSucceedWithOneKeptPosition() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-p", "prompt");
        assertEquals("entry-first", remember(stored.userPromptId(), "entry-first")); // the first position is an INSERT, made once; the readings below find it
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            for (int reading = 0; reading < WRITERS; reading++) {
                String key = "entry-" + reading;
                answers.add(pool.submit(() -> {
                    go.await();
                    return remember(stored.userPromptId(), key);
                }));
            }
            go.countDown();
            String kept = ownerKeyAfter(answers);
            assertEquals(kept, ownerText("SELECT entry_key FROM sennard.transcript_anchor WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
        }
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.transcript_anchor"));
    }

    /** Every answer must be the same key: the one that is kept. */
    private static String ownerKeyAfter(List<Future<String>> answers) throws Exception {
        String first = answers.get(0).get(60, TimeUnit.SECONDS);
        for (Future<String> answer : answers) {
            assertEquals(first, answer.get(60, TimeUnit.SECONDS), "every caller is told the one position that is kept");
        }
        return first;
    }

    @Test
    void aSecondAnchorRowForOnePromptMeetsThePrimaryKey() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-p2", "prompt");
        TranscriptAnchorRepository rows = context.bean(TranscriptAnchorRepository.class);
        TransactionTemplate transaction = new TransactionTemplate(context.transactionManager());
        Instant now = T0;
        Integer written = transaction.execute(status -> rows.insert(stored.userPromptId(), "k1", now));
        assertEquals(1, written.intValue());
        assertThrows(DuplicateKeyException.class, () -> transaction.execute(status -> rows.insert(stored.userPromptId(), "k2", now)));
        assertEquals("k1", ownerText("SELECT entry_key FROM sennard.transcript_anchor WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
    }

    /**
     * I4 for the anchor (the replacement of the old grant test's anchor half, which had the database refuse the statements): the position that is written stays when another is offered
     * (the service answers the kept one; the repository's insert is a duplicate key), and no repository method updates or deletes it
     * ({@code noMethodOfTheArchiveRepositoriesUpdatesOrDeletesPromptsOrAnchorsAndOnlyUserPromptWritesInsertsPrompts}). Besides, a prompt that does not exist cannot be anchored.
     */
    @Test
    void theAnchorStaysWhateverIsOfferedLaterAndAPromptThatDoesNotExistCannotBeAnchored() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-q", "prompt");
        remember(stored.userPromptId(), "a-entry");
        TranscriptAnchorRepository rows = context.bean(TranscriptAnchorRepository.class);
        TransactionTemplate transaction = new TransactionTemplate(context.transactionManager());
        Instant later = T1;
        assertThrows(DuplicateKeyException.class, () -> transaction.execute(status -> rows.insert(stored.userPromptId(), "changed", later)));
        assertEquals("a-entry", remember(stored.userPromptId(), "changed"), "the service answers the position that is kept");
        assertEquals("a-entry", ownerText("SELECT entry_key FROM sennard.transcript_anchor WHERE user_prompt_id = '" + stored.userPromptId() + "'"));
        assertEquals("2026-01-01 00:00:00", ownerText("SELECT " + asText("located_at") + " FROM sennard.transcript_anchor WHERE user_prompt_id = '" + stored.userPromptId() + "'"));

        IntegrityException nobody = assertThrows(IntegrityException.class, () -> remember(UUID.randomUUID().toString(), "k"));
        assertEquals(IntegrityCode.REFERENCE_VIOLATION, nobody.code());
        assertEquals(1, ownerLong("SELECT count(*) FROM sennard.transcript_anchor WHERE entry_key IN ('a-entry', 'k')"));
    }

    @Test
    void anAnchorInputOutsideItsLimitsIsRefusedAndWritesNothing() throws Exception {
        at(T0);
        PromptArchiveWriter.Archived stored = store("claude-code", "s-r", "prompt");
        for (Runnable call : List.<Runnable>of(() -> remember(stored.userPromptId(), " "), () -> remember(stored.userPromptId(), "k".repeat(513)),
                () -> remember(" ", "key"), () -> anchorOf(""))) {
            InvalidInputException refused = assertThrows(InvalidInputException.class, call::run);
            assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        }
        assertEquals(0, ownerLong("SELECT count(*) FROM sennard.transcript_anchor"));
        assertEquals("k".repeat(512), remember(stored.userPromptId(), "k".repeat(512)));
    }

    @Test
    void aPromptIsReadBackByItsIdentifierWithEverythingTheTranscriptReaderNeeds() throws Exception {
        at(T1);
        PromptArchiveWriter.Archived stored = writer().store("claude-code", "s-t", "what the user said 가나다", "/home/u/.claude/projects/p/s-t.jsonl");
        PromptSource source = reader().source(stored.userPromptId()).orElseThrow();
        assertEquals(new PromptSource(stored.userPromptId(), "claude-code", "s-t", T1, "what the user said 가나다", false, "/home/u/.claude/projects/p/s-t.jsonl"), source);

        PromptArchiveWriter.Archived withoutPath = store("codex", "s-t2", "no path");
        assertNull(reader().source(withoutPath.userPromptId()).orElseThrow().transcriptPath());
        PromptArchiveWriter.Archived cut = store("gemini", "s-t3", "가".repeat((int) (MEBIBYTE / 3) + 1));
        PromptSource cutSource = reader().source(cut.userPromptId()).orElseThrow();
        assertTrue(cutSource.truncated());
        assertEquals(349_525, cutSource.promptText().length());

        assertEquals(Optional.empty(), reader().source(UUID.randomUUID().toString()));
        assertThrows(InvalidInputException.class, () -> reader().source(" "));
    }

    // ------------------------------------------------------------------------------------------------------------------ s

    @Test
    void theTablesHaveNoPolicyAreKeyedByThePromptReferenceEachOtherAndGiveNoPrivilegeToAnyoneButTheOneAccount() throws Exception {
        try (Connection owner = BusinessDb.connect(); Statement s = owner.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rows = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'sennard' AND table_type = 'BASE TABLE' "
                    + "AND table_name IN ('recap_nudge', 'transcript_anchor', 'turn_recap', 'user_prompt') ORDER BY table_name")) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
            assertEquals(List.of("recap_nudge", "transcript_anchor", "turn_recap", "user_prompt"), names);
            try (ResultSet rows = s.executeQuery("SELECT c.relname, c.relrowsecurity, (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sennard' AND c.relkind = 'r' "
                    + "AND c.relname IN ('recap_nudge', 'transcript_anchor', 'turn_recap', 'user_prompt') ORDER BY c.relname")) {
                List<String> secured = new ArrayList<>();
                while (rows.next()) {
                    secured.add(rows.getString(1));
                    assertFalse(rows.getBoolean(2), rows.getString(1) + ": no row-level security");
                    assertEquals(0, rows.getInt(3));
                }
                assertEquals(names, secured);
            }
            try (ResultSet rows = s.executeQuery("SELECT (SELECT count(*) FROM information_schema.routines WHERE routine_schema = 'sennard'), "
                    + "(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema = 'sennard')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "no function or procedure");
                assertEquals(0, rows.getInt(2), "no trigger");
            }
            for (String table : List.of("user_prompt", "turn_recap", "recap_nudge", "transcript_anchor")) {
                List<String> key = new ArrayList<>();
                try (ResultSet rows = s.executeQuery("SELECT k.column_name FROM information_schema.key_column_usage k JOIN information_schema.table_constraints t "
                        + "ON t.constraint_schema = k.constraint_schema AND t.constraint_name = k.constraint_name AND t.table_schema = k.table_schema AND t.table_name = k.table_name "
                        + "WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_schema = 'sennard' AND k.table_name = '" + table + "' ORDER BY k.ordinal_position")) {
                    while (rows.next()) {
                        key.add(rows.getString(1));
                    }
                }
                assertEquals(List.of("user_prompt_id"), key, table);
            }
            for (String table : List.of("turn_recap", "recap_nudge", "transcript_anchor")) {
                // PostgreSQL's referential_constraints names only the key it refers to (the same name in every table of its schema is possible), so the table is asked of pg_constraint.
                String refersToPrompt = "SELECT count(*) FROM pg_constraint c WHERE c.contype = 'f' AND c.conrelid = to_regclass('sennard." + table + "') "
                        + "AND c.confrelid = to_regclass('sennard.user_prompt')";
                try (ResultSet rows = s.executeQuery(refersToPrompt)) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1), table + " refers to the prompt");
                }
            }
            // No privilege is held by a name other than the one account's: PostgreSQL lists them in role_table_grants (grantee = a role).
            String strangers = "SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema = 'sennard' AND grantee <> current_user";
            try (ResultSet rows = s.executeQuery(strangers)) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "there is one account and no other role holds a privilege: what the code may write is the code's rule (the repository test of case d)");
            }
            try (ResultSet rows = s.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'sennard' AND table_name = 'turn_recap' AND column_name = 'version'")) {
                assertTrue(rows.next());
                assertEquals("NO", rows.getString(1));
            }
            try (ResultSet rows = s.executeQuery("SELECT version FROM sennard.module_schema WHERE module = 'prompt'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
