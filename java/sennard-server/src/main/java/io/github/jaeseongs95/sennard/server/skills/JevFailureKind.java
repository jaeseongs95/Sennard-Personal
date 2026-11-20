// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ReasonCode;

/**
 * What a provider call that did not advise means for the next step. This is the one place that tells a communication error from the rest.
 * <ul>
 * <li>{@link #COMMUNICATION}: no connection, cut off, no answer in time, HTTP 429 or 5xx. The same target is tried again, the pause growing, until the request's deadline
 * or the budget ends it ({@link JevRetry}).</li>
 * <li>{@link #NON_COMMUNICATION}: no key, a refused key (401, 403), an answer in the wrong form, another 4xx, an input or answer too large. The same target is not tried
 * again: the user's fallback evaluator is asked when there is one, and when there is none the failure is returned and the agent chooses.</li>
 * <li>{@link #TERMINAL}: not a failure of the provider's side, so no other evaluator is asked either: the user did not allow sending, the budget is spent, the task moved
 * or the call was cancelled.</li>
 * </ul>
 */
enum JevFailureKind {
    COMMUNICATION, NON_COMMUNICATION, TERMINAL;

    static JevFailureKind of(ReasonCode code) {
        return switch (code) {
            case TRANSPORT_UNAVAILABLE, RATE_LIMITED, SERVER_UNAVAILABLE, PROVIDER_TIMEOUT -> COMMUNICATION;
            case INVALID_PROVIDER_RESPONSE, OUTPUT_TOO_LONG, PROVIDER_RESPONSE_TOO_LARGE, AUTH_UNAVAILABLE, API_UNAVAILABLE, CREDENTIAL_UNAVAILABLE, CREDENTIAL_MALFORMED,
                    INVALID_PROVIDER_REQUEST, INPUT_TOO_LONG -> NON_COMMUNICATION;
            case CANCELLED, REMOTE_CONTENT_NOT_APPROVED, STALE_CLASSIFICATION, BUDGET_UNAVAILABLE, PROFILE_UNAVAILABLE, REQUEST_ID_CONFLICT, JEV_MATERIAL_FACT_UNRESOLVED -> TERMINAL;
        };
    }
}
