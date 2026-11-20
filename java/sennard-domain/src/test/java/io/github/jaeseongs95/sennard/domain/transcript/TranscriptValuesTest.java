// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The values a transcript is read into, and the rule that every call answers with one of a closed set of results (the host's own format never reaches this package).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * v1  every answer a case     a switch with no default over TranscriptLookup (Located, NotYetWritten, Unsupported, Unreadable), over TranscriptRead (Entries, Unsupported, Unreadable) and
 *                             over TranscriptEntry (UserMessage, AgentMessage, ToolCall, ToolResult) compiles and labels every variant (a new variant would not compile)
 * v2  the size of a text      2000 characters -> kept whole, not cut; 2001 -> the first 2000 and marked cut; "" -> kept; the same for the user's message, the agent's message and a tool's result
 * v3  characters, not units   2001 emoji (two UTF-16 units each) -> the first 2000 emoji (4000 units), cut, and no lone surrogate at the end
 * v4  cannot exceed           an entry built by its constructor with a text over 2000 characters -> IllegalArgumentException; a null text -> NullPointerException
 * v5  entries are a copy      the list an Entries holds does not change when the list it was built from does, and cannot be changed
 * v6  nothing is optional     a query, a location, a tool call and a result without their parts cannot be built (NullPointerException)
 * v7  the limits             the default is 20 and the most is 100
 * v8  a reason is a value     both Unreadable answers hold an UnreadableReason (NOT_A_TRANSCRIPT, ENTRY_GONE, FILE_MISSING, FILE_DENIED, FILE_IO), not a sentence; a null reason -> NullPointerException
 * v9  an item has a key       each entry of a read comes with the host's key of it (TranscriptItem: a key and an entry, neither null) so that a later read can continue after it; a read that cannot
 *                             find that key in the turn is the answer NotInTurn, which holds the key (null -> NullPointerException) and is a case of the same closed set as the others (v1)
 * </pre>
 */
class TranscriptValuesTest {
    private static final Path FILE = Path.of("transcript.jsonl");

    @Test
    void everyAnswerIsOneCaseOfAClosedSet() {
        List<String> lookups = List.of(label(new TranscriptLookup.Located(new TranscriptLocation("claude-code", "s", FILE, "k"))), label(new TranscriptLookup.NotYetWritten()),
                label(new TranscriptLookup.Unsupported("codex")), label(new TranscriptLookup.Unreadable(UnreadableReason.FILE_MISSING)));
        assertEquals(List.of("located k", "not yet written", "unsupported codex", "unreadable FILE_MISSING"), lookups);

        List<String> reads = List.of(label(new TranscriptRead.Entries(List.of(new TranscriptItem("k1", new TranscriptEntry.ToolCall("c", "Read"))), true)), label(new TranscriptRead.Unsupported("gemini")),
                label(new TranscriptRead.Unreadable(UnreadableReason.ENTRY_GONE)), label(new TranscriptRead.NotInTurn("k9")));
        assertEquals(List.of("entries 1 more=true keys=k1", "unsupported gemini", "unreadable ENTRY_GONE", "not in turn k9"), reads);

        List<String> entries = List.of(label(TranscriptEntry.UserMessage.of("u")), label(TranscriptEntry.AgentMessage.of("a")), label(new TranscriptEntry.ToolCall("c1", "Bash")),
                label(TranscriptEntry.ToolResult.of("c1", "r")));
        assertEquals(List.of("user u", "agent a", "call c1 Bash", "result c1 r"), entries);
    }

    private static String label(TranscriptLookup lookup) {
        return switch (lookup) {
            case TranscriptLookup.Located located -> "located " + located.location().entryKey();
            case TranscriptLookup.NotYetWritten notYet -> "not yet written";
            case TranscriptLookup.Unsupported unsupported -> "unsupported " + unsupported.host();
            case TranscriptLookup.Unreadable unreadable -> "unreadable " + unreadable.reason().name();
        };
    }

    private static String label(TranscriptRead read) {
        return switch (read) {
            case TranscriptRead.Entries entries -> "entries " + entries.entries().size() + " more=" + entries.more() + " keys=" + String.join(",", entries.items().stream().map(TranscriptItem::key).toList());
            case TranscriptRead.Unsupported unsupported -> "unsupported " + unsupported.host();
            case TranscriptRead.Unreadable unreadable -> "unreadable " + unreadable.reason().name();
            case TranscriptRead.NotInTurn notInTurn -> "not in turn " + notInTurn.entryKey();
        };
    }

    private static String label(TranscriptEntry entry) {
        return switch (entry) {
            case TranscriptEntry.UserMessage message -> "user " + message.text();
            case TranscriptEntry.AgentMessage message -> "agent " + message.text();
            case TranscriptEntry.ToolCall call -> "call " + call.callId() + " " + call.toolName();
            case TranscriptEntry.ToolResult result -> "result " + result.callId() + " " + result.text();
        };
    }

    @Test
    void aTextOfTwoThousandCharactersIsWholeAndOneMoreIsCut() {
        String whole = "x".repeat(2000);
        String over = "x".repeat(2001);
        TranscriptEntry.UserMessage keptUser = TranscriptEntry.UserMessage.of(whole);
        assertEquals(whole, keptUser.text());
        assertFalse(keptUser.truncated());
        TranscriptEntry.AgentMessage cutAgent = TranscriptEntry.AgentMessage.of(over);
        assertEquals(whole, cutAgent.text());
        assertTrue(cutAgent.truncated());
        TranscriptEntry.ToolResult cutResult = TranscriptEntry.ToolResult.of("c", over);
        assertEquals(whole, cutResult.text());
        assertTrue(cutResult.truncated());
        assertFalse(TranscriptEntry.ToolResult.of("c", whole).truncated());
        TranscriptEntry.UserMessage empty = TranscriptEntry.UserMessage.of("");
        assertEquals("", empty.text());
        assertFalse(empty.truncated());
    }

    @Test
    void theSizeIsCountedInCharactersAndTheCutNeverSplitsAPair() {
        String emoji = "😀";
        TranscriptEntry.AgentMessage cut = TranscriptEntry.AgentMessage.of(emoji.repeat(2001));
        assertTrue(cut.truncated());
        assertEquals(emoji.repeat(2000), cut.text());
        assertEquals(4000, cut.text().length());
        assertFalse(Character.isHighSurrogate(cut.text().charAt(cut.text().length() - 1)), "the text does not end inside a pair");
        TranscriptEntry.AgentMessage whole = TranscriptEntry.AgentMessage.of(emoji.repeat(2000));
        assertFalse(whole.truncated(), "2000 characters of two units each are within the size");
        assertEquals(4000, whole.text().length());
    }

    @Test
    void aConstructorDoesNotLetATextPassTheSize() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptEntry.UserMessage("x".repeat(2001), false));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptEntry.AgentMessage("x".repeat(2001), true));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptEntry.ToolResult("c", "x".repeat(2001), false));
        assertThrows(NullPointerException.class, () -> new TranscriptEntry.UserMessage(null, false));
        assertThrows(NullPointerException.class, () -> TranscriptEntry.AgentMessage.of(null));
    }

    @Test
    void theEntriesOfAReadAreACopy() {
        List<TranscriptItem> source = new ArrayList<>(List.of(new TranscriptItem("k1", new TranscriptEntry.ToolCall("c", "Read"))));
        TranscriptRead.Entries read = new TranscriptRead.Entries(source, false);
        source.add(new TranscriptItem("k2", new TranscriptEntry.ToolCall("d", "Bash")));
        assertEquals(1, read.items().size());
        assertEquals(List.of(new TranscriptEntry.ToolCall("c", "Read")), read.entries(), "the entries are the items' entries, in order");
        assertThrows(UnsupportedOperationException.class, () -> read.items().add(new TranscriptItem("k3", new TranscriptEntry.ToolCall("e", "Edit"))));
    }

    @Test
    void anItemHoldsItsKeyAndItsEntryAndAReadThatContinuesNamesAKey() {
        TranscriptItem item = new TranscriptItem("uuid-1/2", TranscriptEntry.AgentMessage.of("text"));
        assertEquals("uuid-1/2", item.key());
        assertEquals(TranscriptEntry.AgentMessage.of("text"), item.entry());
        assertThrows(NullPointerException.class, () -> new TranscriptItem(null, TranscriptEntry.AgentMessage.of("text")));
        assertThrows(NullPointerException.class, () -> new TranscriptItem("k", null));
        assertThrows(NullPointerException.class, () -> new TranscriptRead.NotInTurn(null));
    }

    @Test
    void aValueWithoutItsPartsCannotBeBuilt() {
        assertThrows(NullPointerException.class, () -> new TranscriptQuery("claude-code", "s", FILE, null, Instant.EPOCH, false));
        assertThrows(NullPointerException.class, () -> new TranscriptQuery("claude-code", "s", null, "text", Instant.EPOCH, false));
        assertThrows(NullPointerException.class, () -> new TranscriptQuery("claude-code", "s", FILE, "text", null, false));
        assertThrows(NullPointerException.class, () -> new TranscriptLocation("claude-code", "s", FILE, null));
        assertThrows(NullPointerException.class, () -> new TranscriptEntry.ToolCall(null, "Read"));
        assertThrows(NullPointerException.class, () -> new TranscriptEntry.ToolCall("c", null));
        assertThrows(NullPointerException.class, () -> new TranscriptEntry.ToolResult(null, "text", false));
        assertThrows(NullPointerException.class, () -> new TranscriptLookup.Unsupported(null));
    }

    @Test
    void aReadAnswersTwentyByDefaultAndAtMostOneHundred() {
        assertEquals(20, Transcript.DEFAULT_LIMIT);
        assertEquals(100, Transcript.MAX_LIMIT);
    }

    @Test
    void anUnreadableAnswerHoldsAReasonValueAndNeverNothing() {
        assertEquals(List.of("NOT_A_TRANSCRIPT", "ENTRY_GONE", "FILE_MISSING", "FILE_DENIED", "FILE_IO"), java.util.Arrays.stream(UnreadableReason.values()).map(Enum::name).toList());
        assertThrows(NullPointerException.class, () -> new TranscriptLookup.Unreadable(null));
        assertThrows(NullPointerException.class, () -> new TranscriptRead.Unreadable(null));
    }
}
