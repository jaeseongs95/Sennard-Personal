// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the tool of a kind sends becomes the one input every start reads ({@link MinimalStartRequest}), so that every kind starts through the same path. A kind that owns files sends
 * that input itself (the change's tool is the original {@code start_workflow}). A kind that owns none sends its goal, its skills and, when it takes one, its target; what the rest of
 * the input holds is what the kind's properties say: no stage, the kind's own acceptance criterion, and a write surface made of the instance's own name ({@link WorkflowKind#sentinelEntry}).
 */
@Component
class KindStartInputs {
    /** The fields a kind's tool sends that the common input takes as they are. */
    private static final List<String> SHARED_FIELDS = List.of("goal", "skills", "classificationOperationKey", "workspace", "target");

    private final Messages messages;
    private final JsonMapper mapper;

    KindStartInputs(Messages messages, JsonMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    /** The common start input of this call of the kind's tool; the same tree for every read of the start (the instance's name is made once). */
    JsonNode startInput(WorkflowKind kind, JsonNode sent) {
        if (kind.allowsFileEdits()) {
            return sent;
        }
        ObjectNode input = mapper.createObjectNode();
        input.put("kind", kind.name());
        SHARED_FIELDS.forEach(field -> {
            JsonNode value = sent.get(field);
            if (value != null && !value.isNull()) {
                input.set(field, value);
            }
        });
        input.putArray("stages");
        input.putArray("acceptanceCriteria").add(messages.text(kind.key("acceptance")));
        input.putArray("writeTargets").add(WorkflowKind.sentinelEntry(kind, UUID.randomUUID().toString()));
        return input;
    }

    /** The sentence of the refusal of a start that names no skills (the one every kind that requires them answers). */
    private static final MessageRef SKILLS_REQUIRED = msg("workflow.service.skillsRequired");

    /** Whether the refusal is the one of a start that left out its required skills. */
    boolean isSkillsRequired(WorkflowFailure failure) {
        return failure.ref().key().equals(SKILLS_REQUIRED.key());
    }

    /** The refusal of a start that left out its required skills, with the skills JEV advised for the request, so that the next call can fill them. */
    WorkflowFailure skillsRequiredWithAdvice(WorkflowFailure refused, ClassificationAdvice.StartAdvice advice) {
        List<Map<String, Object>> skills = advice.skills().stream().map(skill -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skillId", skill.skillId());
            entry.put("score", skill.score());
            return entry;
        }).toList();
        String ids = String.join(", ", advice.skills().stream().map(ClassificationAdvice.StartAdvice.Skill::skillId).toList());
        return new WorkflowFailure(refused.code(), msg("workflow.service.skillsRequiredWithAdvice", "skills", ids),
                details("field", "skills", "reason", "SKILLS_REQUIRED", "classificationOperationKey", advice.operationKey(), "advice", skills), refused);
    }
}
