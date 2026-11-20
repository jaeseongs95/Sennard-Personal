// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import tools.jackson.databind.JsonNode;

/**
 * The one command of a skill's check a workflow stage needs. The skills module owns the rules, so the stage and the tool that runs the same
 * check cannot disagree; the workflow only reads the verdict.
 */
public interface SkillChecks {
    /**
     * @param body {@code {exitCode, value}}, or the error body of a refused input
     * @param exitCode the check's own exit code; 2 is a refused input
     */
    record Result(JsonNode body, int exitCode) {
    }

    Result run(String skill, String command, JsonNode arguments);
}
