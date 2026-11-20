// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;
import java.util.Optional;

/**
 * What the workflow asks of the skill classification: whether its advice can be given now, and what the session's latest classify_skills call came to. The skills module
 * implements it ({@code SkillClassificationService}), so the workflow depends on this port and not on the skills' classes, the way it depends on {@link SkillChecks}.
 */
public interface ClassificationAdvice {
    /** Whether a request to classify skills would be advised (the provider and its budget are there). */
    boolean adviceReady();

    /**
     * The session's latest classify_skills call, for the example a refusal shows: the example is filled from this call and from nothing else.
     *
     * @param operationKey the call's operationKey
     * @param needed the skills the call judged needed, with the skills the caller named as explicit or rule-required, that the server knows to be usable: a skill that is
     *     disabled, not installed, not supported by the host or not in the inventory the call saw is left out
     */
    record Latest(String operationKey, List<String> needed) {
        public Latest {
            needed = List.copyOf(needed);
        }
    }

    /** The session's latest classify_skills call; empty when it made none. */
    Optional<Latest> latest(String host, String sessionId);

    /**
     * The classification the server ran for the request a workflow of a kind that runs JEV first starts for, as the start gives it back to the caller and records it in the run.
     *
     * @param operationKey the key of the classification the advice came from
     * @param skills the skills JEV judged needed, the most confident first
     */
    record StartAdvice(String operationKey, List<Skill> skills) {
        public StartAdvice {
            skills = List.copyOf(skills);
        }

        /** One advised skill with JEV's confidence in it. */
        public record Skill(String skillId, double score) {
        }
    }

    /**
     * The advice for the request the session is working on, from the classification of that request when the server already ran it (the prompt hook starts it), otherwise from one run now
     * for {@code goal}; it waits for a classification that is still running. Empty when JEV is off, cannot be asked or did not advise. The advice is marked given, so the hook does not give it again.
     */
    default Optional<StartAdvice> adviceFor(String host, String sessionId, String goal) {
        return Optional.empty();
    }
}
