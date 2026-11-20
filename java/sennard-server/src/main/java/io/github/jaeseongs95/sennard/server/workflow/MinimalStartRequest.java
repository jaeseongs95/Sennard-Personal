// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The input of {@code start_workflow}. The published tool schema is the only contract of it, so the rules that schema states are the constraints of the record,
 * which a {@code Validator} checks after Jackson bound it: a goal that is not blank and has at most 2000 characters, 1 to 32 distinct stages, acceptance criteria and
 * write targets that are not blank and have at most 2000 characters, 1 to 32 skills, an operation ID of at most 256 characters. The message of each constraint is the
 * catalog key the refusal carries. {@link #reader} binds it: a key the tool does not take is refused, and so is a text field that holds another JSON type than the
 * schema names or a {@code null}. A {@code priorFailure} or a {@code changePlan} that is {@code null} is read as one that is left out. The {@code changePlan} is the files the
 * work will change, relative to the workspace, 1 to 256 distinct paths of at most 2000 characters; with it the run starts in its executing phase, without it in its planning phase.
 */
record MinimalStartRequest(
        @JsonDeserialize(using = StrictInput.Text.class) @NotBlank(message = MinimalStartRequest.GOAL) @Size(max = 2000, message = MinimalStartRequest.GOAL) String goal,
        @JsonDeserialize(contentUsing = StrictInput.Item.class) @NotNull(message = MinimalStartRequest.LIST) @Size(max = 32, message = MinimalStartRequest.LIST) @Distinct(message = MinimalStartRequest.LIST)
        List<@NotBlank(message = MinimalStartRequest.LIST) @Size(max = 2000, message = MinimalStartRequest.LIST) String> stages,
        @Size(max = 32) List<@NotNull @Valid SkillDeclaration> skills,
        @JsonDeserialize(using = StrictInput.Text.class) @Pattern(regexp = MinimalStartRequest.NOT_BLANK, message = MinimalStartRequest.OPERATION) @Size(max = 256, message = MinimalStartRequest.OPERATION)
        String classificationOperationKey,
        @JsonDeserialize(contentUsing = StrictInput.Item.class) @NotNull(message = MinimalStartRequest.LIST) @Size(min = 1, max = 32, message = MinimalStartRequest.LIST) @Distinct(message = MinimalStartRequest.LIST)
        List<@NotBlank(message = MinimalStartRequest.LIST) @Size(max = 2000, message = MinimalStartRequest.LIST) String> acceptanceCriteria,
        @JsonDeserialize(using = StrictInput.Text.class) String workspace,
        @JsonDeserialize(contentUsing = StrictInput.Item.class) @NotNull(message = MinimalStartRequest.LIST) @Size(min = 1, max = 32, message = MinimalStartRequest.LIST) @Distinct(message = MinimalStartRequest.LIST)
        List<@NotBlank(message = MinimalStartRequest.LIST) @Size(max = 2000, message = MinimalStartRequest.LIST) String> writeTargets,
        @JsonDeserialize(using = StrictInput.Text.class) @Pattern(regexp = MinimalStartRequest.NOT_BLANK, message = MinimalStartRequest.ROOT) String rootId,
        @Valid PriorFailure priorFailure,
        @JsonDeserialize(contentUsing = StrictInput.Item.class) @Size(min = 1, max = 256, message = MinimalStartRequest.LIST) @Distinct(message = MinimalStartRequest.LIST)
        List<@NotBlank(message = MinimalStartRequest.LIST) @Size(max = 2000, message = MinimalStartRequest.LIST) String> changePlan,
        WorkflowKind kind,
        @JsonDeserialize(using = StrictInput.Text.class) @Size(max = 2000, message = MinimalStartRequest.TARGET) String target) {

    static final String TARGET = "workflow.service.targetShape";

    /** The kind of the workflow this start opens: a start that names none is a change, which is what {@code start_workflow} opens. */
    WorkflowKind effectiveKind() {
        return kind == null ? WorkflowKind.CHANGE : kind;
    }

    /** A text with something other than white space in it. An optional field is left out as null, which {@code @NotBlank} would refuse, so it takes this. */
    static final String NOT_BLANK = "(?s).*\\S.*";
    static final String GOAL = "workflow.service.goalNonEmptyString";
    static final String LIST = "workflow.service.distinctStringArray";
    static final String OPERATION = "workflow.service.classificationOperationKeyShape";
    static final String ROOT = "workflow.service.startWorkflowAcceptsGoalStages";
    static final String PRIOR = "workflow.service.startWorkflowPriorFailureShape";
    /** The sentences that name the field they are about as an argument. */
    static final Set<String> NAMES_FIELD = Set.of(LIST);

    /** What a start that continues a root says about the attempt that failed there: all four are required. */
    record PriorFailure(
            @JsonDeserialize(using = StrictInput.Text.class) @NotBlank(message = MinimalStartRequest.PRIOR) String hypothesis,
            @JsonDeserialize(using = StrictInput.Text.class) @NotBlank(message = MinimalStartRequest.PRIOR) String changeSummary,
            @JsonDeserialize(using = StrictInput.Text.class) @NotBlank(message = MinimalStartRequest.PRIOR) String discriminator,
            @JsonDeserialize(contentUsing = StrictInput.Item.class) @NotNull(message = MinimalStartRequest.LIST) @Size(min = 1, max = 32, message = MinimalStartRequest.LIST) @Distinct(message = MinimalStartRequest.LIST)
            List<@NotBlank(message = MinimalStartRequest.LIST) @Size(max = 2000, message = MinimalStartRequest.LIST) String> evidenceRefs) {
    }

    /** The reader that binds a start input: strict about the keys. */
    static ObjectReader reader(JsonMapper mapper) {
        return mapper.readerFor(MinimalStartRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
