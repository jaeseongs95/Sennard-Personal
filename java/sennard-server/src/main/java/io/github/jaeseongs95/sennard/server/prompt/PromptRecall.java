// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.prompt.ArchivedSession;
import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveReader;
import io.github.jaeseongs95.sennard.app.prompt.PromptHit;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import tools.jackson.core.JacksonException;

/**
 * {@code recall_user_prompts}: what the user said, word for word, read back. The adapter between the tool and the read side of the archive ({@link PromptArchiveReader}).
 * Which sessions are read is decided here, for the caller:
 * <ul>
 * <li>{@link Scope#SESSION}: the calling session's own prompts;</li>
 * <li>{@link Scope#ROLE}: the prompts of the sessions the user bound to the same role as the calling session. The binding is the signed one that the context tools use
 * ({@link ContextLayerService#signedRoles}, which is the one place a binding is judged: it must exist, be signed, and the session must have been in use in the last
 * 30 days). A caller with no such binding is refused ({@link InputCode#ROLE_NOT_BOUND}); a session's own say-so is never a role.</li>
 * </ul>
 * Nothing here writes: this class and the tool that calls it can read the archive and have no way to add or change a word.
 */
@Service
public class PromptRecall {
    private static final Instant FIRST_INSTANT = Instant.parse("0001-01-01T00:00:00Z");

    /** What was sent as the time range, for the sentence of a refusal ({@code -} for an end that was not given). */
    private static Map<String, Object> sent(String fromTime, String toTime) {
        return SennardException.args("from", fromTime == null ? "-" : fromTime, "to", toTime == null ? "-" : toTime);
    }

    /** Which sessions a recall reads; the wire shows the names. */
    public enum Scope {
        SESSION, ROLE
    }

    /**
     * One prompt of the answer.
     *
     * @param prompt the kept text: the whole prompt, or its first part when {@code truncated}
     * @param originalBytes the UTF-8 length of the whole prompt
     * @param recap what the agent recorded for it; absent when nothing was
     */
    public record Recalled(String promptId, String host, String sessionId, String receivedAt, String prompt, boolean truncated, long originalBytes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String recap) {
    }

    /** What the tool answers: the scope read and the prompts, newest first. */
    public record Recall(Scope scope, List<Recalled> prompts) {
    }

    private final PromptArchiveReader reader;
    private final ArchiveSessionKeys keys;
    private final ContextLayerService context;

    PromptRecall(PromptArchiveReader reader, ArchiveSessionKeys keys, ContextLayerService context) {
        this.reader = reader;
        this.keys = keys;
        this.context = context;
    }

    /**
     * @param query a part of a prompt to find; null takes every prompt
     * @param limit the most prompts to answer; null is {@link PromptArchiveReader#DEFAULT_LIMIT}
     * @param scope what to read; null is {@link Scope#SESSION}
     * @throws InvalidInputException {@link InputCode#PROMPT_RANGE_INVALID} for a time range that is empty or out of bounds and {@link InputCode#PROMPT_TIME_UNREADABLE} for a time that
     *     does not exist (both name what was sent), {@link InputCode#ROLE_NOT_BOUND} for a role recall of a session with no signed binding, {@link InputCode#PROMPT_SESSION_UNHOOKED} when the
     *     session is not one the hooks observe
     */
    public Recall recall(SelfSession session, String query, Integer limit, Scope scope, String fromTime, String toTime) {
        Instant start;
        Instant to;
        try {
            start = fromTime == null ? null : Instant.parse(fromTime);
            to = toTime == null ? null : Instant.parse(toTime);
        } catch (DateTimeParseException invalid) {
            throw new InvalidInputException(InputCode.PROMPT_TIME_UNREADABLE, sent(fromTime, toTime), Map.of(), invalid);
        }
        // from is inclusive and to is exclusive, so a range that ends where it starts is empty too.
        if ((start != null && start.isBefore(FIRST_INSTANT)) || (to != null && !to.isAfter(FIRST_INSTANT)) || (start != null && to != null && !start.isBefore(to))) {
            throw new InvalidInputException(InputCode.PROMPT_RANGE_INVALID, sent(fromTime, toTime));
        }
        Scope kind = scope == null ? Scope.SESSION : scope;
        ArchivedSession self = keys.of(session);
        List<ArchivedSession> from = switch (kind) {
            case SESSION -> List.of(self);
            case ROLE -> sessionsOfTheRoleOf(self);
        };
        List<PromptHit> hits = reader.recall(from, query, limit == null ? PromptArchiveReader.DEFAULT_LIMIT : limit, start, to);
        return new Recall(kind, hits.stream().map(hit -> new Recalled(hit.userPromptId(), hit.host(), hit.sessionId(), Timestamps.format(hit.receivedAt()), hit.promptText(), hit.truncated(),
                hit.originalBytes(), hit.recapText())).toList());
    }

    /** The calling session and the archived sessions that carry the same signed role. */
    private List<ArchivedSession> sessionsOfTheRoleOf(ArchivedSession self) {
        List<ArchivedSession> archived = reader.sessions();
        List<ContextLayerService.SessionKey> asked = Stream.concat(Stream.of(self), archived.stream()).distinct()
                .map(session -> new ContextLayerService.SessionKey(session.host(), session.sessionId())).toList();
        Map<String, String> roles;
        try {
            roles = context.signedRoles(asked);
        } catch (DataAccessException | TransactionException | JacksonException | IllegalStateException failure) {
            // Without the signed record nobody's role is known, and a recall for a role must not guess one.
            throw new UnavailableException(UnavailableCode.STORE_UNAVAILABLE, Map.of(), Map.of("step", "role-binding", "cause", failure.getClass().getSimpleName()), failure);
        }
        String own = roles.get(AdminLog.bindingKey(self.host(), self.sessionId()));
        if (own == null) {
            throw new InvalidInputException(InputCode.ROLE_NOT_BOUND);
        }
        return asked.stream().filter(session -> own.equals(roles.get(AdminLog.bindingKey(session.host(), session.sessionId()))))
                .map(session -> new ArchivedSession(session.host(), session.sessionId())).toList();
    }
}
