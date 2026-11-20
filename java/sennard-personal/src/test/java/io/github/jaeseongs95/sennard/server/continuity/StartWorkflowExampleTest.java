// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.continuity.ContinuityHookHandler.SkillReason;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityHookHandler.StartWorkflowExample;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code start_workflow} example the edit gate prints as a record: its JSON keeps the members and their order the tree gave. That the printed
 * example is a start that works is {@code WorkflowToolsServerTest.theRefusalsStartExampleIsAStartThatWorks}, that it names no skill of its own on each host is
 * {@code WorkflowToolsServerTest.theRefusalsExampleHoldsNoSkillOfItsOwnAndItsPlaceholdersAreRefusedWhenTheyComeBack}.
 */
class StartWorkflowExampleTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** T1: the example is written with its members in order and its text escaped as JSON; a session with no classification cites none. */
    @Test
    void theExampleIsWrittenAsTheTreeWasWritten() {
        StartWorkflowExample example = new StartWorkflowExample("<goal \"x\">", List.of("minimal-implementation"), List.of(new SkillReason("<skill>", "<why>")),
                List.of("<criterion>"), List.of("<path to change>"), "<key>", null);

        assertEquals("{\"goal\":\"<goal \\\"x\\\">\",\"stages\":[\"minimal-implementation\"],\"skills\":[{\"skillId\":\"<skill>\",\"reason\":\"<why>\"}],"
                + "\"acceptanceCriteria\":[\"<criterion>\"],\"writeTargets\":[\"<path to change>\"],\"operationKey\":\"<key>\"}", MAPPER.writeValueAsString(example));
    }

    /** T2: a session that made a classification has its call's key cited, last. */
    @Test
    void theExampleCitesTheClassificationOfTheSessionWhenThereIsOne() {
        StartWorkflowExample example = new StartWorkflowExample("<goal>", List.of("minimal-implementation"), List.of(new SkillReason("test-engineering", "<why>")),
                List.of("<criterion>"), List.of("<path to change>"), "<key>", "op-classify-1");

        assertEquals("{\"goal\":\"<goal>\",\"stages\":[\"minimal-implementation\"],\"skills\":[{\"skillId\":\"test-engineering\",\"reason\":\"<why>\"}],"
                + "\"acceptanceCriteria\":[\"<criterion>\"],\"writeTargets\":[\"<path to change>\"],\"operationKey\":\"<key>\",\"classificationOperationKey\":\"op-classify-1\"}",
                MAPPER.writeValueAsString(example));
    }
}
