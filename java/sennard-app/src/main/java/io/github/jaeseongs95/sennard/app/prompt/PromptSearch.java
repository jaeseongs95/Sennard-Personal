// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import java.util.List;
import java.time.Instant;

/**
 * How the archived prompts are searched: the port the recall reads through. The default answer is the database's own ({@link JdbcPromptSearch}, standard SQL); a search
 * engine can be another implementation later without the recall changing. An implementation answers newest first, only for the sessions named, and
 * with the recap of each prompt.
 */
public interface PromptSearch {
    /**
     * @param sessions the sessions whose prompts are searched; empty finds nothing
     * @param text a part of the prompt to find, ignoring only the case of the ASCII letters A-Z; null or empty takes every prompt
     * @param limit the most prompts to answer; at least 1
     * @return at most {@code limit} prompts, the newest first
     */
    List<PromptHit> search(List<ArchivedSession> sessions, String text, int limit, Instant from, Instant to);
}
