// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the server sends every session as its initialize instructions is the intake block inside the catalog's frame plus the session-message guide, so that is what
 * the size budget holds for, in each catalog language. The budget is the user's decision (12,000 UTF-8 bytes); the intake file's own block is held to the same number by
 * RepositoryContractTest.theIntakeBlockStaysWithinItsByteBudget in the repository tools. Merge repeated sentences instead of raising it.
 */
class SkillIntakeBudgetTest {
    private static final int INSTRUCTIONS_BUDGET_BYTES = IntakeDeliveryBudget.SERVER_INSTRUCTIONS.bytes();
    private static final Path SKILLS = Path.of(System.getProperty("sennard.repo.root")).resolve("skills");
    private static final Messages MESSAGES = new Messages(new MessageSourceAccessor(
            new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build()), Locale.ENGLISH));

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void theAssembledInstructionsStayWithinTheByteBudget(String language) {
        String instructions = SkillIntake.serverInstructions(MESSAGES, SKILLS.toString(), language);
        int size = instructions.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(size <= INSTRUCTIONS_BUDGET_BYTES, "the " + language + " instructions are " + size + " bytes; the budget is " + INSTRUCTIONS_BUDGET_BYTES + ". Merge repeated sentences instead of adding to them");
    }

    @Test
    void whatTheServerSendsIsTheModelLanguageAssembly() {
        String sent = SkillIntake.serverInstructions(MESSAGES, SKILLS.toString());
        assertEquals(SkillIntake.serverInstructions(MESSAGES, SKILLS.toString(), Messages.MODEL_LOCALE), sent);
    }
}
