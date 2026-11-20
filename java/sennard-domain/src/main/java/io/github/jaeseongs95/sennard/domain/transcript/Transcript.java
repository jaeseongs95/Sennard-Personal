// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

/**
 * Where a host keeps the record of a session, and how to find and read the part of it that follows a user's message. Each host writes its transcript in its own file and
 * its own format, so the business rules know only this interface; one implementation per host reads that host's format and answers in the values of this package.
 *
 * <p>Every call answers with one of the sealed results, never {@code null} and never an empty {@code Optional}: a host this implementation does not read, a file that cannot be
 * read, and a message that is not in the file yet are three different answers and the caller tells the agent which one it is. A call for another host than {@link #host()} is
 * answered as {@code Unsupported}, not thrown, so that any implementation can stand in for another in a test or a registry.
 *
 * <p>Nothing here copies a transcript: {@link #locate} answers a position (the host's identifier of one entry), and {@link #readAfter} reads a bounded number of entries from
 * that position, each text cut at {@link TranscriptEntry#TEXT_MAX_CHARACTERS} characters.
 */
public interface Transcript {
    /** How many entries a read answers when the caller names no number. */
    int DEFAULT_LIMIT = 20;
    /** The most entries one read answers. */
    int MAX_LIMIT = 100;

    /** The name the host's sessions are stored under (the same value as the host of a session key), for example {@code claude-code}. */
    String host();

    /** Finds the entry of the transcript that holds the user's message of the query. Reads the file from its start and does not keep it. */
    TranscriptLookup locate(TranscriptQuery query);

    /**
     * The entries that follow the located one, up to the next message of the user (the end of the turn) or {@code limit} entries, whichever comes first.
     *
     * @param limit from 1 to {@link #MAX_LIMIT}
     */
    TranscriptRead readAfter(TranscriptLocation location, int limit);

    /**
     * The entries of the same turn that follow one already read: what {@link #readAfter} stopped before when the turn was longer than its limit. The entry is named by the key
     * ({@link TranscriptItem#key}) that an earlier read gave for it; the read starts at the entry after it and goes on to the next message of the user or {@code limit}
     * entries. The location is still the message that opened the turn: a key of any other turn is {@link TranscriptRead.NotInTurn}, so the caller cannot use it to read
     * what follows another message.
     *
     * @param location the message that opened the turn, as for {@link #readAfter}
     * @param entryKey the key of the entry to continue after
     * @param limit from 1 to {@link #MAX_LIMIT}
     */
    TranscriptRead continueAfter(TranscriptLocation location, String entryKey, int limit);
}
