// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;

/**
 * The placeholders the examples of a refusal print in the place of a value the model has to make from its own work ({@code <goal>}, {@code <path to change>}, ...). A model that
 * copies an example sends them back, and a request whose fields are only well formed passes: so a value that is one of them is refused, with the reason and what to put there.
 * The texts are the catalog's own, rendered in the language the examples are printed in, so the examples and this check cannot drift apart.
 */
@Component
public class PlaceholderTexts {
    /** What the value of a field has to be, by the selector of the refusal's wording. */
    public enum Need {
        GOAL, REASON, CRITERION, PATH, SKILL, TARGET, STAGE
    }

    private static final List<String> KEYS = List.of("hooks.editGate.goalPlaceholder", "hooks.editGate.skillReasonPlaceholder", "hooks.editGate.criterionPlaceholder",
            "hooks.editGate.writeTargetPlaceholder", "hooks.editGate.skillPlaceholder", "hooks.workflowGate.targetPlaceholder", "hooks.editGate.stagePlaceholder");

    /** The values that stand for "a value goes here" whoever wrote them, compared after the white space around is stripped and without regard to case. */
    private static final Set<String> GENERIC = Set.of("todo", "tbd", "fixme", "xxx", "n/a", "na", "-", "...", "…", "x", "placeholder");

    /** A value that is one angle-bracketed piece as a whole ({@code <anything>}): the shape of every example's own placeholder. */
    private static final Pattern ANGLE_BRACKETED = Pattern.compile("<[^<>]*>");

    private final Messages messages;

    public PlaceholderTexts(Messages messages) {
        this.messages = messages;
    }

    /**
     * Whether the text is a placeholder: one an example prints, or a value that says nothing of the work by its form ({@code TODO}, {@code x}, {@code N/A}, {@code <...>}, ...).
     * White space around it does not make it a value.
     */
    public boolean isPlaceholder(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.strip();
        return texts().contains(stripped) || GENERIC.contains(stripped.toLowerCase(Locale.ROOT)) || ANGLE_BRACKETED.matcher(stripped).matches();
    }

    /** Refuses the recording of a stage result whose free text ({@code note}, {@code summary}, a finding, ...) is a placeholder; nothing is recorded. */
    public void assertNoneInResult(String field, String value) {
        if (isPlaceholder(value)) {
            MessageRef reason = msg("workflow.service.resultPlaceholderReturned", "field", field, "value", value.strip());
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, reason, details("field", field, "reason", "PLACEHOLDER_RETURNED", "value", value.strip()));
        }
    }

    /** Refuses the start or the plan when the value is a placeholder. */
    public void assertNone(String field, Need need, String value) {
        if (isPlaceholder(value)) {
            throw returned(field, need, value);
        }
    }

    public void assertNone(String field, Need need, Collection<String> values) {
        if (values != null) {
            values.forEach(value -> assertNone(field, need, value));
        }
    }

    private WorkflowFailure returned(String field, Need need, String value) {
        MessageRef reason = msg("workflow.service.placeholderReturned", "field", field, "value", value.strip(), "need", need.name());
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, reason, details("field", field, "reason", "PLACEHOLDER_RETURNED", "value", value.strip()));
    }

    private Set<String> texts() {
        return KEYS.stream().map(messages::text).collect(Collectors.toUnmodifiableSet());
    }
}
