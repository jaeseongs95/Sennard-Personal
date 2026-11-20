// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Who may read the transcript behind a prompt, as a pure policy: no file, no database, no framework. The user's scope for the transcript is the same session only
 * (「같은 세션만 (권장)」).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * a1  the same session     caller (claude-code, s1), owner (claude-code, s1) -> Granted
 * a2  another host         caller (claude-code, s1), owner (codex, s1): the same id under another host is not the same session -> Refused(OTHER_HOST)
 * a3  another session      caller (claude-code, s1), owner (claude-code, s2) -> Refused(OTHER_SESSION)
 * a4  both differ          caller (claude-code, s1), owner (codex, s2) -> Refused(OTHER_HOST): the host is judged first
 * a5  no owner             an owner with a null host or a null session id is nobody's session -> Refused (never Granted)
 * a6  the caller's own     a caller with a null host or session id cannot be built (NullPointerException)
 * a7  complete switches    a switch over Decision and over Reason with no default compiles and answers every case (a new case would not compile)
 * </pre>
 */
class TranscriptAccessTest {
    private static final TranscriptAccess CALLER = new TranscriptAccess("claude-code", "s1");

    @Test
    void theSessionThatSaidThePromptMayRead() {
        assertInstanceOf(TranscriptAccess.Decision.Granted.class, CALLER.decide("claude-code", "s1"));
    }

    @Test
    void theSameSessionIdUnderAnotherHostIsAnotherSession() {
        assertEquals(new TranscriptAccess.Decision.Refused(TranscriptAccess.Reason.OTHER_HOST), CALLER.decide("codex", "s1"));
    }

    @Test
    void anotherSessionOfTheSameHostIsRefused() {
        assertEquals(new TranscriptAccess.Decision.Refused(TranscriptAccess.Reason.OTHER_SESSION), CALLER.decide("claude-code", "s2"));
    }

    @Test
    void whenBothDifferTheHostIsJudgedFirst() {
        assertEquals(new TranscriptAccess.Decision.Refused(TranscriptAccess.Reason.OTHER_HOST), CALLER.decide("codex", "s2"));
    }

    @Test
    void anOwnerThatIsNobodyIsRefused() {
        assertInstanceOf(TranscriptAccess.Decision.Refused.class, CALLER.decide(null, "s1"));
        assertInstanceOf(TranscriptAccess.Decision.Refused.class, CALLER.decide("claude-code", null));
        assertInstanceOf(TranscriptAccess.Decision.Refused.class, CALLER.decide(null, null));
    }

    @Test
    void aCallerWithoutASessionCannotBeBuilt() {
        assertThrows(NullPointerException.class, () -> new TranscriptAccess(null, "s1"));
        assertThrows(NullPointerException.class, () -> new TranscriptAccess("claude-code", null));
    }

    @Test
    void theDecisionAndTheReasonAreSwitchedCompletely() {
        assertEquals("granted", label(CALLER.decide("claude-code", "s1")));
        assertEquals("refused: OTHER_HOST", label(CALLER.decide("codex", "s1")));
        assertEquals("refused: OTHER_SESSION", label(CALLER.decide("claude-code", "s2")));
    }

    private static String label(TranscriptAccess.Decision decision) {
        return switch (decision) {
            case TranscriptAccess.Decision.Granted granted -> "granted";
            case TranscriptAccess.Decision.Refused refused -> "refused: " + switch (refused.reason()) {
                case OTHER_HOST -> "OTHER_HOST";
                case OTHER_SESSION -> "OTHER_SESSION";
            };
        };
    }
}
