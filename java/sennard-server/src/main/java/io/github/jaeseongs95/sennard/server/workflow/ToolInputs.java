// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds the arguments of a tool to the record that states its input: Jackson binds the arguments (a key the record does not list is refused, and a number with a
 * fraction is not an integer), then a {@code Validator} holds the record to its constraints. Either refusal is {@code INVALID_INPUT} that names the tool, with the
 * reason as the details and the binding failure as the cause. The tools whose contract is a schema file are checked against that file first
 * ({@link ContractValidator#read}) and do not come here.
 */
@Component
final class ToolInputs {
    private static final String NAME_KEY = "workflow.schema.contractMismatch";

    private final JsonMapper mapper;
    private final Validator rules;

    ToolInputs(JsonMapper mapper, Validator rules) {
        this.mapper = mapper;
        this.rules = rules;
    }

    <T> T read(String tool, JsonNode arguments, Class<T> type) {
        T input;
        try {
            input = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(arguments);
        } catch (JacksonException unbound) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, name(tool), details("validationErrors", unbound.getOriginalMessage()), unbound);
        }
        if (input == null) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, name(tool), details("validationErrors", "the input is not an object"));
        }
        Set<ConstraintViolation<T>> violations = rules.validate(input);
        if (!violations.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, name(tool),
                    details("validationErrors", violations.stream().map(violation -> violation.getPropertyPath() + " " + violation.getMessage()).sorted().collect(Collectors.joining("; "))));
        }
        return input;
    }

    private static MessageRef name(String tool) {
        return msg(NAME_KEY, "name", tool + " input");
    }
}
