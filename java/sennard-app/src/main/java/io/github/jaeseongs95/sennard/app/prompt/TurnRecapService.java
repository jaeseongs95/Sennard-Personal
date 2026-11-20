// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.prompt.Recap;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The agent's note of a turn ({@code record_turn_recap}): attaches a recap to the session's newest prompt. A prompt has at most one recap, and recording again replaces it.
 * The recap arrives already judged ({@link Recap} cannot exist outside its limit), the time is the injected clock's, and the transaction is declared. This
 * service reads a prompt only to find which one the recap is for; it writes the recap table and nothing that holds the user's words.
 *
 * <p>Concurrency is the database's: a replacement is one UPDATE statement that the database applies to the row as the last committed writer left it, so two replacements both
 * take effect and nothing is retried. The first recording of a prompt is an INSERT under the table's primary key in the same transaction; two first recordings of one prompt at
 * the same moment meet that key, and the one that loses ends with the translated duplicate-key {@code ConflictException}, which is not caught and not run again.
 */
@Service
@Validated
public class TurnRecapService {
    /** What was recorded: for which prompt, the recap as kept, when, and whether it replaced an earlier recap of the same prompt. */
    public record Recorded(String userPromptId, String recap, Instant recordedAt, boolean replaced) {
    }

    private final UserPromptRepository prompts;
    private final TurnRecapRepository recaps;
    private final Clock clock;
    private final OperationReceipts receipts;

    public TurnRecapService(UserPromptRepository prompts, TurnRecapRepository recaps, Clock clock, OperationReceipts receipts) {
        this.prompts = prompts;
        this.recaps = recaps;
        this.clock = clock;
        this.receipts = receipts;
    }

    /**
     * Records the recap of the newest prompt once per operationKey (AGS3-D-0267), the one entry of the recording. Only a recording is stored: a session with no prompt to attach the recap to has no result, changes
     * nothing, and is judged again.
     */
    @Transactional
    public Optional<Recorded> record(@NotBlank String host, @NotBlank String sessionId, @NotNull Recap recap, OperationKey operation) {
        Optional<Recorded> stored = receipts.replay(operation, Recorded.class);
        if (stored.isPresent()) {
            return stored;
        }
        Optional<Recorded> made = record(host, sessionId, recap);
        made.ifPresent(recorded -> receipts.record(operation, recorded));
        return made;
    }

    /**
     * The write of the recording, inside the transaction of the entry above (it has none of its own): one UPDATE statement replaces the recap it has, and when that changes no row the prompt has none yet and the same transaction
     * writes it with an INSERT.
     *
     * @return the recording, or nothing when the session has no prompt in the archive to attach a recap to
     */
    private Optional<Recorded> record(String host, String sessionId, Recap recap) {
        return prompts.findLatestOfSession(host, sessionId).map(latest -> {
            Instant at = now();
            boolean replaced = recaps.replace(latest.userPromptId(), recap.text(), at) == 1;
            if (!replaced) {
                recaps.insert(latest.userPromptId(), recap.text(), at);
            }
            return new Recorded(latest.userPromptId(), recap.text(), at, replaced);
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
