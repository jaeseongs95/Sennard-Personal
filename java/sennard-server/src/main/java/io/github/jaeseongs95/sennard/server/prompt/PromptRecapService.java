// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.time.Instant;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.prompt.ArchivedSession;
import io.github.jaeseongs95.sennard.app.prompt.TurnRecapService;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.prompt.Recap;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.message.Messages;

/**
 * {@code record_turn_recap}: the agent's short note of its turn goes to the calling session's newest prompt. The adapter between the tool and the application layer: it finds
 * the session's archive key ({@link ArchiveSessionKeys}), turns the verdict of the pure rule ({@link Recap#judge}) into the tool's refusal, calls {@link TurnRecapService} and
 * adds the sentence that points at the shared context. It reaches the recap table only; the user's words are not written from here.
 */
@Service
public class PromptRecapService {
    /**
     * What the tool answers.
     *
     * @param promptId the prompt the recap belongs to
     * @param recap the recap as kept (trimmed)
     * @param recordedAt UTC, fixed-width text
     * @param replaced whether it replaced an earlier recap of the same prompt
     * @param sharedContextHint the one sentence that asks for a shared context save when this turn decided something later sessions need
     */
    public record Recorded(String promptId, String recap, String recordedAt, boolean replaced, String sharedContextHint) {
    }

    private final TurnRecapService recaps;
    private final ArchiveSessionKeys keys;
    private final Messages messages;

    PromptRecapService(TurnRecapService recaps, ArchiveSessionKeys keys, Messages messages) {
        this.recaps = recaps;
        this.keys = keys;
        this.messages = messages;
    }

    /**
     * @throws SennardException {@link InputCode#RECAP_NOT_ROOT} for a subagent's call, {@link InputCode#RECAP_INVALID} for a blank or too long recap, {@link InputCode#PROMPT_NOT_ARCHIVED} when the session has no prompt to attach it to,
     *     {@link InputCode#PROMPT_SESSION_UNHOOKED} when the session is not one the hooks observe
     */
    public Recorded record(SelfSession session, String recap, OperationKey operation) {
        // The recap is the main agent's summary of the turn: a subagent's call is refused, so its own report does not fill it in. Only an actor the host marked as a child is
        // refused. One whose actor cannot be told (a call that names the session by reference, or a host that says nothing) is treated as the root, as the other tool paths do:
        // a refusal there would block the main agent of every host that cannot mark its calls.
        if (session.actor().role() == HostContext.Role.CHILD) {
            throw new InvalidInputException(InputCode.RECAP_NOT_ROOT);
        }
        Recap kept = switch (Recap.judge(recap)) {
            case Recap.Accepted accepted -> accepted.recap();
            case Recap.Blank ignored -> throw new InvalidInputException(InputCode.RECAP_INVALID, SennardException.args("maxWeight", Recap.MAX_WEIGHT), SennardException.args("reason", "BLANK"));
            case Recap.TooLong tooLong -> throw new InvalidInputException(InputCode.RECAP_INVALID, SennardException.args("maxWeight", Recap.MAX_WEIGHT),
                    SennardException.args("reason", "TOO_LONG", "weight", tooLong.weight(), "maxWeight", Recap.MAX_WEIGHT));
        };
        ArchivedSession key = keys.of(session);
        TurnRecapService.Recorded recorded = recaps.record(key.host(), key.sessionId(), kept, operation).orElseThrow(() -> new InvalidInputException(InputCode.PROMPT_NOT_ARCHIVED));
        Instant at = recorded.recordedAt();
        return new Recorded(recorded.userPromptId(), recorded.recap(), Timestamps.format(at), recorded.replaced(), messages.text("server.promptRecap.sharedContextHint"));
    }
}
