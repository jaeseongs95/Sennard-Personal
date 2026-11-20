// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;

/**
 * The session board tools' answer: each session writes its own one-line status, and the list shows who runs now from the session identity tables, joined with
 * what each row says. Presence decides who is listed, so a board row alone never shows a session. The board's own data and rules are
 * {@code BoardService} (sennard-app); this class is the adapter that joins them with presence and signed roles and shapes the tool's result.
 */
@Service
public class SessionBoardService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionBoardService.class);

    public record Update(String summary, String summaryAt, String role) {
    }

    public record Listed(String host, String sessionId, boolean current, String presence, String startedAt, String lastSeenAt, String cwd, Object repo, String summary, String summaryAt,
            String role, RoleSource roleSource, Boolean summaryStale, String lastPromptAt, Object wake) {
    }

    /** Whose word the listed role is: the signed record of the admin page, or the session's own line. The wire shows the lower-case words of the contract. */
    public enum RoleSource {
        @JsonProperty("signed") SIGNED,
        @JsonProperty("self") SELF
    }

    /** Whether the board's own rows could be read for this listing; {@code step} names where it failed. */
    public record BoardState(BoardStatus status, String step) {
    }

    /** The wire shows the lower-case words of the published contract (contracts/session-board-list.v2.schema.json). */
    public enum BoardStatus {
        @JsonProperty("ok") OK,
        @JsonProperty("unavailable") UNAVAILABLE
    }

    public record Listing(List<Listed> sessions, long truncated, BoardState board) {
    }

    private final BoardService board;
    private final PresenceStore presence;
    private final ContextLayerService context;

    public SessionBoardService(BoardService board, PresenceStore presence, ContextLayerService context) {
        this.board = board;
        this.presence = presence;
        this.context = context;
    }

    public Update update(String host, String sessionId, String summary, String role, OperationKey operation) {
        try {
            BoardService.SummaryResult stored = board.setSummary(host, sessionId, summary, role, operation);
            return new Update(stored.summary(), Timestamps.format(stored.summaryAt()), stored.role());
        } catch (UnavailableException failed) {
            throw new UnavailableException(UnavailableCode.BOARD_NOT_RECORDED, Map.of(), Map.of("step", "write", "cause", causeName(failed)), failed);
        }
    }

    /** ceiling: {@code repo} is null until the file-facts port the server uses to read a working tree exists. */
    public Listing list(String selfHost, String selfSessionId) {
        PresenceStore.CurrentPresence present;
        try {
            present = presence.currentPresence(selfHost, selfSessionId);
        } catch (DataAccessException | TransactionException failed) {
            // Without presence nobody can tell which sessions run; board rows are never shown in its place.
            throw new UnavailableException(UnavailableCode.BOARD_LIVENESS_UNAVAILABLE, Map.of(), Map.of("step", "presence", "cause", causeName(failed)), failed);
        }
        Map<String, String> signed = signedRoles(present.sessions());
        List<Listed> sessions = new ArrayList<>();
        BoardState state = new BoardState(BoardStatus.OK, null);
        try {
            for (PresenceStore.Row row : present.sessions()) {
                sessions.add(board.readEntry(row.host(), row.sessionId()).map(entry -> listed(row, entry, signed, selfHost, selfSessionId))
                        .orElseGet(() -> listed(row, null, signed, selfHost, selfSessionId)));
            }
        } catch (UnavailableException failed) {
            LOG.warn("The board rows could not be read; the list shows presence only.", failed);
            sessions.clear();
            for (PresenceStore.Row row : present.sessions()) {
                sessions.add(listed(row, null, signed, selfHost, selfSessionId));
            }
            state = new BoardState(BoardStatus.UNAVAILABLE, "read");
        }
        return new Listing(sessions, present.truncated(), state);
    }

    /** One session of the list; {@code entry} is its board entry, null when the board has none (or could not be read). */
    private static Listed listed(PresenceStore.Row row, BoardEntry entry, Map<String, String> signed, String selfHost, String selfSessionId) {
        String signedRole = signed == null ? null : signed.get(AdminLog.bindingKey(row.host(), row.sessionId()));
        String selfRole = entry == null ? null : entry.role();
        String role = signedRole != null ? signedRole : selfRole;
        return new Listed(row.host(), row.sessionId(), row.host().equals(selfHost) && row.sessionId().equals(selfSessionId), row.state(), row.startedAt(), row.heartbeatAt(),
                entry == null ? null : entry.cwd(), null, entry == null ? null : entry.summary(), entry == null ? null : wire(entry.summaryAt()), role,
                signedRole != null ? RoleSource.SIGNED : role != null ? RoleSource.SELF : null, entry == null ? null : entry.summaryStale(), entry == null ? null : wire(entry.lastPromptAt()), null);
    }

    /** The tools show a time as fixed-width UTC text (contracts/session-board-list.v2.schema.json); the null of an absent time stays null. */
    private static String wire(Instant instant) {
        return instant == null ? null : Timestamps.format(instant);
    }

    private static String causeName(Throwable failed) {
        return (failed.getCause() == null ? failed : failed.getCause()).getClass().getSimpleName();
    }

    /** An unreadable signed record leaves the self-written roles, labelled as such; the list itself still answers. */
    private Map<String, String> signedRoles(List<PresenceStore.Row> sessions) {
        try {
            return context.signedRoles(sessions.stream().map(row -> new ContextLayerService.SessionKey(row.host(), row.sessionId())).toList());
        } catch (DataAccessException | TransactionException | JacksonException | IllegalStateException failed) {
            LOG.warn("Signed roles could not be read; the list shows the self-written roles.", failed);
            return null;
        }
    }
}
