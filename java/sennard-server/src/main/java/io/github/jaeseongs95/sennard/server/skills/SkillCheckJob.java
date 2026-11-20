// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.store.jobs.JobHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A skill check that left the request's thread: the same service as the direct call, so the job's answer is that call's answer: the envelope the tool answers with ({@link SkillCheckService.Outcome#result}). */
@Component
public class SkillCheckJob implements JobHandler {
    public static final String KIND = "skill-check";

    /** What a job of this kind is queued with. {@code arguments} is the call's own arguments object, kept as the caller gave it. */
    private record Input(String skill, String command, JsonNode arguments) {
    }

    private final SkillCheckService service;
    private final JsonMapper mapper;

    public SkillCheckJob(SkillCheckService service, JsonMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** The job input for a call: the text the queue stores. */
    public String input(String skill, String command, JsonNode arguments) {
        return mapper.writeValueAsString(new Input(skill, command, arguments));
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String run(String inputJson) {
        Input input = mapper.readValue(inputJson, Input.class);
        JsonNode arguments = input.arguments() == null || input.arguments().isNull() ? null : input.arguments();
        return mapper.writeValueAsString(service.run(input.skill(), input.command(), arguments).result());
    }
}
