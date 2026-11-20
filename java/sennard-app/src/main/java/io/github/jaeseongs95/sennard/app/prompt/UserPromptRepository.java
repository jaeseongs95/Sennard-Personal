// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * The prompts, read only. It extends the {@code Repository} marker (only the methods declared here are offered) and the SQL is ANSI. Writing is
 * {@link UserPromptWrites}, and only {@link PromptArchiveWriter} uses it.
 */
public interface UserPromptRepository extends Repository<UserPrompt, String> {
    Optional<UserPrompt> findById(String userPromptId);

    /**
     * The session's newest prompt. Two prompts of one session at the same millisecond would be told apart by the identifier, which is no order of speaking; a session's user
     * speaks one prompt at a time, so the time decides.
     */
    Optional<UserPrompt> findFirstByHostAndSessionIdOrderByReceivedAtDescUserPromptIdDesc(String host, String sessionId);

    default Optional<UserPrompt> findLatestOfSession(String host, String sessionId) {
        return findFirstByHostAndSessionIdOrderByReceivedAtDescUserPromptIdDesc(host, sessionId);
    }
}
