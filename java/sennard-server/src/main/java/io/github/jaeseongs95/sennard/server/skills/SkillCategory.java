// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The group a skill belongs to, written as {@code category} in the skill's {@code classification.json} (the JSON Schema {@code skill-classification-file.v1} allows the same two
 * values). The category only decides how skills are listed (the admin page, the READMEs); no selection, check or hook looks at it.
 */
public enum SkillCategory {
    /** A skill that keeps the agent to the request, the evidence and the agreed scope. */
    @JsonProperty("agent-correction") AGENT_CORRECTION("admin.skillGroup.agentCorrection"),
    /** A skill that brings the expertise of one field to the work. */
    @JsonProperty("specialist") SPECIALIST("admin.skillGroup.specialist");

    private final String messageKey;

    SkillCategory(String messageKey) {
        this.messageKey = messageKey;
    }

    /** The catalog key of the group's title on the admin page. */
    public String messageKey() {
        return messageKey;
    }
}
