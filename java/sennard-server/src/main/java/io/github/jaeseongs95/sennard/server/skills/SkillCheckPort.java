// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.workflow.SkillChecks;

import tools.jackson.databind.JsonNode;

/** The workflow's port onto the skills' rules: the same service {@code run_skill_check} calls, so a stage and the tool cannot disagree. */
@Component
class SkillCheckPort implements SkillChecks {
    private final SkillCheckService service;

    SkillCheckPort(SkillCheckService service) {
        this.service = service;
    }

    @Override
    public Result run(String skill, String command, JsonNode arguments) {
        SkillCheckService.Outcome outcome = service.run(skill, command, arguments);
        return new Result(outcome.body(), outcome.exitCode());
    }
}
