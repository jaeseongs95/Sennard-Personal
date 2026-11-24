// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.board;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.domain.board.BoardRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The session board: per session, the host-provided user title, one-line summary, role and last prompt time. The service opens the transactions
 * ({@code @Transactional}), the time comes from the injected clock, and the limits from {@link BoardProperties}. It knows no MCP and no HTTP: a refusal is an
 * {@code SennardException} and a result is a record.
 *
 * <p>Each use case is one method and one transaction. A change of an existing row is one UPDATE statement ({@link BoardSessionRepository#touch},
 * {@link BoardSessionRepository#summarize}) that the database applies to the row as the last committed writer left it, so two writers of one row both take effect and nothing is
 * retried. When either of those updates changes no row the session has none yet, and the same transaction writes it with an INSERT under the table's primary key. Two first writes of one
 * session at the same moment meet that key: the one that loses ends with the translated duplicate-key {@code ConflictException}, which is not caught and not run again.
 */
@Service
@Validated
public class BoardService {
    /** What {@link #setSummary} stored: the summary, when, and the role now in force (an omitted role keeps the one written before). */
    public record SummaryResult(String summary, Instant summaryAt, String role) {
    }

    private final BoardSessionRepository sessions;
    private final BoardProperties properties;
    private final Clock clock;
    private final OperationReceipts receipts;

    public BoardService(BoardSessionRepository sessions, BoardProperties properties, Clock clock, OperationReceipts receipts) {
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.receipts = receipts;
    }

    /**
     * The summary, and the role when given, once per operationKey (AGS3-D-0267): the same key with the same content answers the first result, another content is the conflict. The
     * board's own write is a replacement of the same line, so a repeat would be harmless; the key is kept for what the contract says, not for this table's sake. The session's row is
     * written by this call when it has none yet. This is the one entry: the write itself is the private method below, which runs only inside this transaction.
     */
    @Transactional
    public SummaryResult setSummary(@NotBlank String host, @NotBlank String sessionId, String summary, String role, OperationKey operation) {
        return receipts.once(operation, SummaryResult.class, () -> setSummary(host, sessionId, summary, role)).value();
    }

    /**
     * One input the hook observed: the working directory always, the prompt time only for a user prompt. The session's row is written by this call when it has none yet, and a
     * user prompt also removes the rows not seen for the retention period first. The board holds no prompt text; the user's words are in the prompt archive ({@code sennard},
     * {@code PromptArchiveWriter}).
     */
    @Transactional
    public void recordInput(@NotBlank String host, @NotBlank String sessionId, String cwd, boolean prompt) {
        Instant now = now();
        if (prompt) {
            sessions.deleteBySeenAtLessThan(now.minus(properties.retention()));
        }
        String workingDirectory = cwd == null || cwd.isEmpty() ? null : cwd;
        Instant promptAt = prompt ? now : null;
        if (sessions.touch(host, sessionId, workingDirectory, promptAt, now) == 0) {
            sessions.insert(host, sessionId, workingDirectory, promptAt, null, null, null, now);
        }
    }

    /** The write of {@link #setSummary(String, String, String, String, OperationKey)}; an omitted role keeps the one written before. It has no transaction of its own: it runs inside the entry's. */
    private SummaryResult setSummary(String host, String sessionId, String summary, String role) {
        String line = BoardRules.summary(summary, properties.summaryMaxLength());
        String roleLine = role == null ? null : BoardRules.role(role, properties.roleMaxLength());
        Instant at = now();
        if (sessions.summarize(host, sessionId, line, at, roleLine) == 0) {
            sessions.insert(host, sessionId, null, null, line, at, roleLine, at);
            return new SummaryResult(line, at, roleLine);
        }
        // This transaction wrote the row and holds it until it ends, so the role read here is the one in force after this write.
        return new SummaryResult(line, at, find(host, sessionId).orElseThrow().role());
    }

    /**
     * Stores a host-confirmed title observation on an existing session. A null title represents a confirmed removal.
     * Like other board timestamps, observations are compared at the JDBC boundary's millisecond precision.
     */
    @Transactional
    public void observeTitle(@NotBlank String host, @NotBlank String sessionId, String title, @NotNull Instant observedAt) {
        sessions.observeTitle(host, sessionId, title, observedAt.truncatedTo(ChronoUnit.MILLIS));
    }

    @Transactional(readOnly = true)
    public Optional<BoardEntry> readEntry(String host, String sessionId) {
        return find(host, sessionId).map(row -> new BoardEntry(row.cwd(), row.summary(), row.summaryAt(), row.role(), row.lastPromptAt(), row.userTitle()));
    }

    /** The injected clock's instant, to the millisecond: the precision the tools show and every database stores. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Optional<BoardSession> find(String host, String sessionId) {
        return sessions.findByKeyHostAndKeySessionId(host, sessionId);
    }

    private static BoardSessionKey key(String host, String sessionId) {
        return new BoardSessionKey(host, sessionId);
    }
}
