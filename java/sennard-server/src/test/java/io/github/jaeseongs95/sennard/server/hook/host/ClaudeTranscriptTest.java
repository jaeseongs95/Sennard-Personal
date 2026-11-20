// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptEntry;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptItem;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLocation;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLookup;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptQuery;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptRead;
import io.github.jaeseongs95.sennard.domain.transcript.UnreadableReason;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Code's transcript behind {@code Transcript}, on synthetic JSON Lines files (a real transcript is never read by a test: the lines below are made from what the existing
 * reader of the host's transcript relies on, {@code ClaudeTranscriptExecutionReader}, and from the documented fields type, uuid, sessionId, timestamp, isSidechain,
 * isMeta and message.content). The time of the prompt is {@code T}; the hook received it one second later.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * l1  found                 a file with a summary line, the user's "hello" (uuid u1), the agent's answer; query "hello" -> Located(host, session, file, entryKey u1)
 * l2  the same words twice  "go" said at T (u1) and again ten minutes later (u2): received at T+1s -> u1; received ten minutes later + 1s -> u2 (the first line is older than the
 *                           window); the window is inclusive: a line written exactly 5 s before the receipt is found, 1 ms earlier is not
 * l3  not the user's        the same text as a subagent's line (isSidechain), as a line the host injected (isMeta), as a tool result (a tool_result block) and, then, as the user's own
 *                           -> only the last is found
 * l4  not there yet         the file holds other lines only, or is empty -> NotYetWritten; the user line is written later (the file grows) -> Located
 * l5  no file               a path that does not exist -> Unreadable(FILE_MISSING) and a WARN with the cause is logged; a directory -> Unreadable and WARN
 * l6  broken lines          a half line in the middle and one at the end of the file, a blank line -> skipped, the entry is found; a file of nothing but such lines -> Unreadable
 *                           (not a transcript); a JSON array line is no entry
 * l7  a cut prompt          the archive kept the first part ("AAAA", truncated): the line "AAAA...rest" is found by its beginning; the same stored text as a whole prompt does not find it
 * l8  blocks of text        content as a list: [ide context, "hello"] is found by the block "hello" and by the joined text; only an image block -> not found
 * l9  no time or no id      a user line without a timestamp, with a timestamp that is not an instant, or without a uuid is not found (NotYetWritten)
 * l10 another host          a query or a location of host codex -> Unsupported("codex"); nothing is read
 * l11 the host's name       host() is claude-code, the name the sessions are stored under
 * a1  one turn              after u1: the agent's text, a tool call, the tool's result, the agent's closing text, then the user's next message (u2) -> the four entries in order, more false
 * a2  the limit             five entries, limit 2 -> 2 entries and more true; limit 5 with the user's next message after them -> 5, more false; limit 5 at the end of the file -> 5, more true;
 *                           limit 9 -> 5, more false; limit 1 and 100 are the bounds, 0 and 101 are refused
 * a3  the cut at 2000       an agent text of 2500 characters -> 2000 and marked cut; a tool result of 2000 -> whole and not cut, of 2001 -> cut
 * a4  not part of the turn  a subagent's lines, the model's thinking, a summary line, a system line and a line the host injected (isMeta) are not entries and the injected line does not
 *                           end the turn; a tool result whose content is a list of text blocks is read as the texts joined by a new line
 * a5  the turn is open      the file ends before the user's next message -> the entries so far, more true
 * a6  the position is gone  an entryKey the file does not hold, or one that appears only as the parent of another line -> Unreadable; a missing file -> Unreadable and WARN
 * a7  confirmed by uuid     a line that names the key as its parent comes before the line that has it -> the entries after the line that has it
 * a8  the first of the turn  the position is the user's own line; the entries before it (earlier turns) are never returned
 * a9  keys                  every entry has a key: the plain uuid of its line, and for the second and later entries of one line the uuid and /<place> (a line of three texts: u, u/1, u/2)
 * a10 continuing            a turn of six entries read with limit 2 and continued after the last key of each page (once inside a line of three texts) -> the pages together equal the turn
 *                           read at once, none twice, none missing; more false only on the page that reaches the user's next message
 * a11 the end               continuing after the last entry -> no items; more false when the user's next message follows, true when the file ends first
 * a12 not in the turn       a key of an earlier turn, of the user's lines (this turn's and the next), of the next turn, of a subagent's or an injected line, an unknown key -> NotInTurn(key)
 * a13 the same bounds       a limit of 0 or 101 is refused, another host is Unsupported, a turn message that is gone is ENTRY_GONE, a missing file is FILE_MISSING with a WARN
 * </pre>
 */
class ClaudeTranscriptTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant T = Instant.parse("2026-11-12T10:00:00.000Z");
    private static final String SESSION = "session-1";

    @TempDir
    Path dir;

    private final ClaudeTranscript transcript = new ClaudeTranscript(new ClaudeTranscriptFile(MAPPER));
    private ListAppender<ILoggingEvent> warnings;
    private Logger logger;

    @BeforeEach
    void listen() {
        logger = (Logger) LoggerFactory.getLogger(ClaudeTranscript.class);
        warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
    }

    @AfterEach
    void stopListening() {
        logger.detachAppender(warnings);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> user(String uuid, Instant at, Object content) {
        return map("type", "user", "uuid", uuid, "sessionId", SESSION, "timestamp", at.toString(), "isSidechain", false, "message", map("role", "user", "content", content));
    }

    private static Map<String, Object> assistant(String uuid, Instant at, Object... blocks) {
        return map("type", "assistant", "uuid", uuid, "sessionId", SESSION, "timestamp", at.toString(), "isSidechain", false, "message",
                map("role", "assistant", "model", "a-model", "content", List.of(blocks)));
    }

    private static Map<String, Object> toolResult(String uuid, Instant at, String callId, Object content) {
        return user(uuid, at, List.of(map("type", "tool_result", "tool_use_id", callId, "content", content)));
    }

    private static Map<String, Object> text(String text) {
        return map("type", "text", "text", text);
    }

    private static Map<String, Object> toolUse(String id, String name) {
        return map("type", "tool_use", "id", id, "name", name, "input", Map.of());
    }

    private static Map<String, Object> with(Map<String, Object> line, Object... pairs) {
        Map<String, Object> changed = new LinkedHashMap<>(line);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            changed.put((String) pairs[index], pairs[index + 1]);
        }
        return changed;
    }

    private Path file(Object... lines) throws IOException {
        List<String> text = new ArrayList<>();
        for (Object line : lines) {
            text.add(line instanceof String raw ? raw : MAPPER.writeValueAsString(line));
        }
        Path file = dir.resolve("transcript-" + System.nanoTime() + ".jsonl");
        Files.writeString(file, String.join("\n", text) + "\n");
        return file;
    }

    private TranscriptQuery query(Path file, String promptText) {
        return new TranscriptQuery("claude-code", SESSION, file, promptText, T.plusSeconds(1), false);
    }

    private static String key(TranscriptLookup lookup) {
        return assertInstanceOf(TranscriptLookup.Located.class, lookup, lookup.toString()).location().entryKey();
    }

    private TranscriptRead.Entries read(Path file, String entryKey, int limit) {
        return assertInstanceOf(TranscriptRead.Entries.class, transcript.readAfter(new TranscriptLocation("claude-code", SESSION, file, entryKey), limit));
    }

    private long warnCount() {
        return warnings.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    }

    private static Map<String, Object> summary() {
        return map("type", "summary", "summary", "a title", "leafUuid", "x");
    }

    // ------------------------------------------------------------------------------------------------------------------ locate

    @Test
    void theUsersLineIsFoundByItsTextAndItsUuidIsThePosition() throws Exception {
        Path file = file(summary(), user("u1", T, "hello"), assistant("a1", T.plusSeconds(1), text("hi")));
        TranscriptLookup found = transcript.locate(query(file, "hello"));
        assertEquals(new TranscriptLookup.Located(new TranscriptLocation("claude-code", SESSION, file, "u1")), found);
    }

    @Test
    void theSameWordsSaidTwiceAreToldApartByTheTimeOfTheReceipt() throws Exception {
        Instant later = T.plusSeconds(600);
        Path file = file(user("u1", T, "go"), assistant("a1", T.plusSeconds(1), text("ok")), user("u2", later, "go"));
        assertEquals("u1", key(transcript.locate(new TranscriptQuery("claude-code", SESSION, file, "go", T.plusSeconds(1), false))));
        assertEquals("u2", key(transcript.locate(new TranscriptQuery("claude-code", SESSION, file, "go", later.plusSeconds(1), false))));

        Path edge = file(user("e1", T, "edge"));
        assertEquals("e1", key(transcript.locate(new TranscriptQuery("claude-code", SESSION, edge, "edge", T.plusSeconds(5), false))), "written exactly 5 s before the receipt is inside the window");
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(new TranscriptQuery("claude-code", SESSION, edge, "edge", T.plusSeconds(5).plusMillis(1), false)),
                "1 ms earlier is outside it");
    }

    @Test
    void aLineThatIsNotWhatTheUserTypedIsNotTheUsersMessage() throws Exception {
        Path file = file(
                with(user("side", T, "same words"), "isSidechain", true),
                with(user("meta", T, "same words"), "isMeta", true),
                toolResult("result", T, "toolu_1", "same words"),
                assistant("a0", T, text("same words")),
                user("real", T.plusMillis(10), "same words"));
        assertEquals("real", key(transcript.locate(query(file, "same words"))));
    }

    @Test
    void anEntryThatIsNotThereYetIsNotYetWrittenAndIsFoundOnceItIs() throws Exception {
        Path others = file(summary(), assistant("a1", T, text("only the agent")));
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(query(others, "hello")));
        Path empty = dir.resolve("empty.jsonl");
        Files.writeString(empty, "");
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(query(empty, "hello")), "a file with nothing in it yet");
        assertEquals(0, warnCount(), "not being there yet is no failure");

        Files.writeString(others, Files.readString(others) + MAPPER.writeValueAsString(user("u1", T, "hello")) + "\n");
        assertEquals("u1", key(transcript.locate(query(others, "hello"))));
    }

    @Test
    void aFileThatCannotBeReadIsUnreadableAndTheFailureIsLoggedWithItsCause() throws Exception {
        TranscriptLookup missing = transcript.locate(query(dir.resolve("nowhere.jsonl"), "hello"));
        assertEquals(new TranscriptLookup.Unreadable(UnreadableReason.FILE_MISSING), missing);
        assertEquals(1, warnCount());
        assertTrue(warnings.list.get(0).getThrowableProxy() != null, "the cause is logged, not dropped");

        TranscriptLookup directory = transcript.locate(query(dir, "hello"));
        assertInstanceOf(TranscriptLookup.Unreadable.class, directory);
        assertEquals(2, warnCount());
    }

    @Test
    void brokenLinesAreSkippedAndAFileOfNothingElseIsNotATranscript() throws Exception {
        Path file = file("{\"type\":\"user\",\"uuid\":\"half", "", "[1,2,3]", user("u1", T, "hello"), "{\"type\":\"assistant\",\"mess");
        assertEquals("u1", key(transcript.locate(query(file, "hello"))));

        Path garbage = file("not json at all", "{\"type\":", "###");
        assertEquals(new TranscriptLookup.Unreadable(UnreadableReason.NOT_A_TRANSCRIPT), transcript.locate(query(garbage, "hello")));
        assertEquals(0, warnCount(), "an unreadable format is answered, not a failure to read");
    }

    @Test
    void aCutPromptIsFoundByItsBeginning() throws Exception {
        Path file = file(user("u1", T, "AAAA and the rest of what was said"));
        TranscriptQuery cut = new TranscriptQuery("claude-code", SESSION, file, "AAAA", T.plusSeconds(1), true);
        assertEquals("u1", key(transcript.locate(cut)));
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(new TranscriptQuery("claude-code", SESSION, file, "AAAA", T.plusSeconds(1), false)),
                "a prompt that was kept whole must match whole");
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(new TranscriptQuery("claude-code", SESSION, file, "BBBB", T.plusSeconds(1), true)));
    }

    @Test
    void aMessageOfSeveralBlocksIsFoundByTheUsersBlockOrByTheWholeText() throws Exception {
        Path blocks = file(user("u1", T, List.of(text("<ide_opened_file>a.java</ide_opened_file>"), text("hello"))));
        assertEquals("u1", key(transcript.locate(query(blocks, "hello"))));
        assertEquals("u1", key(transcript.locate(query(blocks, "<ide_opened_file>a.java</ide_opened_file>\nhello"))));
        Path image = file(user("u2", T, List.of(map("type", "image", "source", map("type", "base64", "data", "AAAA")))));
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(query(image, "hello")));
    }

    @Test
    void aLineWithoutATimeOrAnIdCannotBePlaced() throws Exception {
        Map<String, Object> noTime = user("u1", T, "hello");
        noTime.remove("timestamp");
        Path file = file(noTime, with(user("u2", T, "hello"), "timestamp", "yesterday"), with(user("u3", T, "hello"), "uuid", null));
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(query(file, "hello")));
    }

    @Test
    void aTranscriptOfAnotherHostIsNotRead() throws Exception {
        Path file = file(user("u1", T, "hello"));
        assertEquals(new TranscriptLookup.Unsupported("codex"), transcript.locate(new TranscriptQuery("codex", SESSION, file, "hello", T, false)));
        assertEquals(new TranscriptRead.Unsupported("codex"), transcript.readAfter(new TranscriptLocation("codex", SESSION, file, "u1"), 5));
        assertEquals("claude-code", transcript.host());
        assertEquals(0, warnCount());
    }

    // ------------------------------------------------------------------------------------------------------------------ readAfter

    @Test
    void aTurnIsReadFromTheUsersMessageToTheNextOne() throws Exception {
        Path file = file(
                user("u0", T.minusSeconds(60), "an earlier turn"), assistant("a0", T.minusSeconds(59), text("earlier answer")),
                user("u1", T, "hello"),
                assistant("a1", T.plusSeconds(1), text("I will look"), toolUse("toolu_1", "Read")),
                toolResult("r1", T.plusSeconds(2), "toolu_1", "the file's text"),
                assistant("a2", T.plusSeconds(3), text("done")),
                user("u2", T.plusSeconds(60), "next prompt"),
                assistant("a3", T.plusSeconds(61), text("second turn")));
        TranscriptRead.Entries entries = read(file, "u1", 20);
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("I will look"), new TranscriptEntry.ToolCall("toolu_1", "Read"), TranscriptEntry.ToolResult.of("toolu_1", "the file's text"),
                TranscriptEntry.AgentMessage.of("done")), entries.entries());
        assertEquals(false, entries.more(), "the user's next message was reached");
    }

    @Test
    void theLimitStopsAReadAndMoreSaysWhetherTheTurnGoesOn() throws Exception {
        Path ended = file(user("u1", T, "go"), assistant("a1", T, text("one"), text("two"), text("three")), assistant("a2", T, text("four"), text("five")), user("u2", T.plusSeconds(9), "next"));
        TranscriptRead.Entries two = read(ended, "u1", 2);
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("one"), TranscriptEntry.AgentMessage.of("two")), two.entries());
        assertTrue(two.more());
        TranscriptRead.Entries exact = read(ended, "u1", 5);
        assertEquals(5, exact.entries().size());
        assertEquals(false, exact.more(), "five entries and then the user's next message: nothing is left");
        assertEquals(5, read(ended, "u1", 9).entries().size());
        assertEquals(false, read(ended, "u1", 9).more());
        assertEquals(1, read(ended, "u1", 1).entries().size());
        assertEquals(5, read(ended, "u1", 100).entries().size());

        Path open = file(user("u1", T, "go"), assistant("a1", T, text("one"), text("two"), text("three"), text("four"), text("five")));
        TranscriptRead.Entries all = read(open, "u1", 5);
        assertEquals(5, all.entries().size());
        assertTrue(all.more(), "the file ends before the turn does: the turn may still be written");
        assertThrows(IllegalArgumentException.class, () -> transcript.readAfter(new TranscriptLocation("claude-code", SESSION, open, "u1"), 0));
        assertThrows(IllegalArgumentException.class, () -> transcript.readAfter(new TranscriptLocation("claude-code", SESSION, open, "u1"), 101));
    }

    @Test
    void everyTextIsCutAtTwoThousandCharactersAndMarkedCut() throws Exception {
        Path file = file(user("u1", T, "go"), assistant("a1", T, text("x".repeat(2500))), toolResult("r1", T, "t1", "y".repeat(2000)), toolResult("r2", T, "t2", "z".repeat(2001)));
        List<TranscriptEntry> entries = read(file, "u1", 20).entries();
        assertEquals(3, entries.size());
        TranscriptEntry.AgentMessage message = assertInstanceOf(TranscriptEntry.AgentMessage.class, entries.get(0));
        assertEquals("x".repeat(2000), message.text());
        assertTrue(message.truncated());
        TranscriptEntry.ToolResult whole = assertInstanceOf(TranscriptEntry.ToolResult.class, entries.get(1));
        assertEquals("y".repeat(2000), whole.text());
        assertEquals(false, whole.truncated());
        TranscriptEntry.ToolResult cut = assertInstanceOf(TranscriptEntry.ToolResult.class, entries.get(2));
        assertEquals("z".repeat(2000), cut.text());
        assertTrue(cut.truncated());
    }

    /** A ToolSearch result holds tool_reference blocks and no text: the result is not shown as empty (which said the tool returned nothing), and an image block, which holds bytes, stays out. */
    @Test
    void aToolResultOfToolReferencesIsShownAsTheReferencesNotAsNothing() throws Exception {
        Path file = file(
                user("u1", T, "go"),
                toolResult("r1", T, "toolu_1", List.of(map("type", "tool_reference", "tool_name", "mcp__sennard__classify_skills"), map("type", "image", "source", Map.of("data", "AAAA")),
                        map("type", "tool_reference", "tool_name", "mcp__sennard__get_skill_inventory"))),
                user("u2", T.plusSeconds(30), "next"));
        TranscriptRead.Entries entries = read(file, "u1", 20);
        assertEquals(List.of(TranscriptEntry.ToolResult.of("toolu_1", "{\"type\":\"tool_reference\",\"tool_name\":\"mcp__sennard__classify_skills\"}\n{\"type\":\"tool_reference\",\"tool_name\":\"mcp__sennard__get_skill_inventory\"}")),
                entries.entries());
    }

    @Test
    void whatIsNotPartOfTheMainTurnIsLeftOut() throws Exception {
        Path file = file(
                user("u1", T, "go"),
                assistant("a1", T, map("type", "thinking", "thinking", "private reasoning", "signature", "s"), text("visible")),
                with(assistant("s1", T, text("a subagent says"), toolUse("toolu_s", "Bash")), "isSidechain", true),
                with(toolResult("sr", T, "toolu_s", "subagent result"), "isSidechain", true),
                map("type", "system", "uuid", "sys", "content", "a system line"),
                summary(),
                with(user("meta", T, "<system-reminder>injected</system-reminder>"), "isMeta", true),
                toolResult("r1", T, "toolu_1", List.of(text("first"), text("second"), map("type", "image", "source", Map.of()))),
                user("u2", T.plusSeconds(30), "next"));
        TranscriptRead.Entries entries = read(file, "u1", 20);
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("visible"), TranscriptEntry.ToolResult.of("toolu_1", "first\nsecond")), entries.entries());
        assertEquals(false, entries.more());
    }

    @Test
    void aPositionThatIsNotInTheFileIsUnreadable() throws Exception {
        Path file = file(user("u1", T, "go"), with(assistant("a1", T, text("x")), "parentUuid", "ghost"));
        assertEquals(new TranscriptRead.Unreadable(UnreadableReason.ENTRY_GONE),
                transcript.readAfter(new TranscriptLocation("claude-code", SESSION, file, "ghost"), 5), "the key appears only as a parent");
        assertEquals(0, warnCount());

        TranscriptRead missing = transcript.readAfter(new TranscriptLocation("claude-code", SESSION, dir.resolve("nowhere.jsonl"), "u1"), 5);
        assertEquals(new TranscriptRead.Unreadable(UnreadableReason.FILE_MISSING), missing);
        assertEquals(1, warnCount());
        assertTrue(warnings.list.get(0).getThrowableProxy() != null);
    }

    @Test
    void aLineIsTheEntryOnlyWhenItsOwnUuidIsTheKey() throws Exception {
        Path file = file(with(assistant("early", T, text("a child that names its parent before the parent is written")), "parentUuid", "u1"), user("u1", T, "go"), assistant("a1", T, text("after")),
                user("u2", T.plusSeconds(9), "next"));
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("after")), read(file, "u1", 20).entries());
    }

    private TranscriptRead.Entries continued(Path file, String turnKey, String afterKey, int limit) {
        return assertInstanceOf(TranscriptRead.Entries.class, transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, file, turnKey), afterKey, limit));
    }

    private static List<String> keys(TranscriptRead.Entries read) {
        return read.items().stream().map(TranscriptItem::key).toList();
    }

    /** A turn of u1 with a line of three texts, a tool call and its result on two lines, and a closing text; the user's next message follows when {@code closed}. */
    private Path turnOfSixEntries(boolean closed) throws IOException {
        List<Object> lines = new ArrayList<>(List.of(user("u0", T.minusSeconds(60), "an earlier turn"), assistant("a0", T.minusSeconds(59), text("earlier answer")), user("u1", T, "go"),
                assistant("a1", T.plusSeconds(1), text("one"), text("two"), text("three")), assistant("a2", T.plusSeconds(2), toolUse("toolu_1", "Read")),
                toolResult("r1", T.plusSeconds(3), "toolu_1", "the text"), assistant("a3", T.plusSeconds(4), text("six"))));
        if (closed) {
            lines.addAll(List.of(user("u2", T.plusSeconds(60), "next prompt"), assistant("a4", T.plusSeconds(61), text("second turn"))));
        }
        return file(lines.toArray());
    }

    @Test
    void everyEntryComesWithItsKeyTheLinesUuidAndForALineOfSeveralEntriesTheUuidAndItsPlace() throws Exception {
        Path file = turnOfSixEntries(true);
        TranscriptRead.Entries all = read(file, "u1", 20);
        assertEquals(List.of("a1", "a1/1", "a1/2", "a2", "r1", "a3"), keys(all), "the first entry of a line has the plain uuid, the later ones add /<place>");
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("one"), TranscriptEntry.AgentMessage.of("two"), TranscriptEntry.AgentMessage.of("three"), new TranscriptEntry.ToolCall("toolu_1", "Read"),
                TranscriptEntry.ToolResult.of("toolu_1", "the text"), TranscriptEntry.AgentMessage.of("six")), all.entries());
        assertEquals(keys(all), keys(read(file, "u1", 20)), "the same keys on every read");
    }

    @Test
    void aReadContinuesAfterTheLastEntryOfThePageEvenInTheMiddleOfALine() throws Exception {
        Path file = turnOfSixEntries(true);
        TranscriptRead.Entries first = read(file, "u1", 2);
        assertEquals(List.of("a1", "a1/1"), keys(first));
        assertTrue(first.more());
        TranscriptRead.Entries second = continued(file, "u1", "a1/1", 2);
        assertEquals(List.of("a1/2", "a2"), keys(second), "the third text of the line the first page stopped inside comes first");
        assertTrue(second.more());
        TranscriptRead.Entries third = continued(file, "u1", "a2", 2);
        assertEquals(List.of("r1", "a3"), keys(third));
        assertEquals(false, third.more(), "the user's next message follows the last of them");
        List<TranscriptItem> pages = new ArrayList<>(first.items());
        pages.addAll(second.items());
        pages.addAll(third.items());
        assertEquals(read(file, "u1", 20).items(), pages, "the pages together are the turn read at once: no entry twice, none missing");
    }

    @Test
    void continuingAfterTheLastEntryAnswersNothingMoreWhenTheTurnEndedAndSomethingMoreMayComeWhenItDidNot() throws Exception {
        TranscriptRead.Entries closed = continued(turnOfSixEntries(true), "u1", "a3", 5);
        assertEquals(List.of(), closed.items());
        assertEquals(false, closed.more());
        TranscriptRead.Entries open = continued(turnOfSixEntries(false), "u1", "a3", 5);
        assertEquals(List.of(), open.items());
        assertTrue(open.more(), "the file ends before the user's next message: the turn may still be written");
    }

    @Test
    void aKeyThatIsNotAnEntryOfTheTurnIsNotInTheTurn() throws Exception {
        Path file = file(user("u0", T.minusSeconds(60), "an earlier turn"), assistant("a0", T.minusSeconds(59), text("earlier answer")), user("u1", T, "go"), assistant("a1", T.plusSeconds(1), text("one")),
                with(assistant("s1", T.plusSeconds(2), text("a subagent")), "isSidechain", true), with(user("meta", T.plusSeconds(2), "injected"), "isMeta", true), user("u2", T.plusSeconds(60), "next prompt"),
                assistant("a2", T.plusSeconds(61), text("second turn")));
        for (String key : List.of("a0", "u0", "u1", "u2", "a2", "s1", "meta", "ghost", "a1/1")) {
            assertEquals(new TranscriptRead.NotInTurn(key), transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, file, "u1"), key, 5), key + " is not an entry of the turn of u1");
        }
        assertEquals(List.of(), keys(continued(file, "u1", "a1", 5)), "a1 is");
    }

    @Test
    void continuingKeepsTheBoundsOfAReadAndItsFailures() throws Exception {
        Path file = turnOfSixEntries(true);
        assertThrows(IllegalArgumentException.class, () -> transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, file, "u1"), "a1", 0));
        assertThrows(IllegalArgumentException.class, () -> transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, file, "u1"), "a1", 101));
        assertEquals(new TranscriptRead.Unsupported("codex"), transcript.continueAfter(new TranscriptLocation("codex", SESSION, file, "u1"), "a1", 5));
        assertEquals(new TranscriptRead.Unreadable(UnreadableReason.ENTRY_GONE), transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, file, "ghost"), "a1", 5),
                "the message of the turn is no longer in the file");
        assertEquals(0, warnCount());
        assertEquals(new TranscriptRead.Unreadable(UnreadableReason.FILE_MISSING), transcript.continueAfter(new TranscriptLocation("claude-code", SESSION, dir.resolve("nowhere.jsonl"), "u1"), "a1", 5));
        assertEquals(1, warnCount());
    }

    @Test
    void theEntriesBeforeThePositionAreNeverReturned() throws Exception {
        Path file = file(user("u1", T.minusSeconds(100), "first"), assistant("a1", T.minusSeconds(99), text("first answer")), user("u2", T, "second"), assistant("a2", T.plusSeconds(1), text("second answer")));
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("second answer")), read(file, "u2", 20).entries());
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("first answer")), read(file, "u1", 20).entries());
    }

    @Test
    void aTranscriptThatIsReadOnceIsReadAgainFromTheKeyWithoutTheText() throws Exception {
        Path file = file(user("u1", T, "hello"), assistant("a1", T, text("hi")));
        String key = key(transcript.locate(query(file, "hello")));
        Files.writeString(file, Files.readString(file).replace("\"hello\"", "\"edited\""));
        assertInstanceOf(TranscriptLookup.NotYetWritten.class, transcript.locate(query(file, "hello")));
        assertEquals(List.of(TranscriptEntry.AgentMessage.of("hi")), read(file, key, 20).entries(), "the position is the uuid, so the read does not depend on the text");
    }
}
