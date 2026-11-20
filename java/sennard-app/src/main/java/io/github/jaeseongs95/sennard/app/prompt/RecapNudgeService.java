// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * The reminder at the end of a turn: asks for what the agent has not recorded: the recap of the prompt, once per prompt, and the session's summary on the session board (the
 * summary is required and must tell what the session does now; the caller reads the board and says whether it is missing, older than the last prompt or current), at every turn end until it is current. The recap is reminded once whatever happens after: the row of
 * the reminder is kept, and its key is the prompt's, so a second turn end of the same prompt finds the first one's row and does not ask for the recap again. A second turn end at the
 * very same moment as the first meets the primary key instead: that duplicate key is not caught and not run again, and fails that call.
 */
@Service
@Validated
public class RecapNudgeService {
    /** What the agent is asked for now: the recap of the prompt, the summary of the session (when its state is due), or both in one reminder. At least one is asked. */
    public record Reminder(boolean recap, SummaryState summary) {
    }

    private final UserPromptRepository prompts;
    private final TurnRecapRepository recaps;
    private final RecapNudgeRepository nudges;
    private final Clock clock;

    public RecapNudgeService(UserPromptRepository prompts, TurnRecapRepository recaps, RecapNudgeRepository nudges, Clock clock) {
        this.prompts = prompts;
        this.recaps = recaps;
        this.nudges = nudges;
        this.clock = clock;
    }

    /**
     * @param summary what the session board shows of the session's summary
     * @return what the agent is to be asked now. The recap is asked when the session's newest prompt has none, and only once per prompt (this call records that it asks). The summary
     *     is asked every time it is missing or older than the last prompt, with no record: at each turn end until it is current. Empty for a session with no prompt in the archive, and
     *     when nothing is due.
     */
    @Transactional
    public Optional<Reminder> remindNow(@NotBlank String host, @NotBlank String sessionId, SummaryState summary) {
        return prompts.findLatestOfSession(host, sessionId).flatMap(latest -> remind(latest.userPromptId(), summary));
    }

    private Optional<Reminder> remind(String userPromptId, SummaryState summary) {
        boolean recapDue = !nudges.existsById(userPromptId) && !recaps.existsById(userPromptId);
        if (!recapDue && !summary.due()) {
            return Optional.empty();
        }
        if (recapDue) {
            nudges.insert(userPromptId, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        }
        return Optional.of(new Reminder(recapDue, summary));
    }
}
