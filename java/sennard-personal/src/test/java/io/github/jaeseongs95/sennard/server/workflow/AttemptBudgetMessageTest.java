// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an agent reads when the attempt budget is used up (code {@code ATTEMPT_BUDGET_EXHAUSTED}). The policy is unchanged: the root's own
 * number of full attempts (3 unless the user set another), no further one. The words are there to tell the agent that the approach it has been taking may be the wrong one, so it re-examines its premises and the
 * cause of the failures instead of looking for another way to run the same thing again.
 */
class AttemptBudgetMessageTest {
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final String CLAIM_REFUSED = "workflow.service.attemptBudgetStartedConvergence";
    private static final String REVIEW_REQUIRED = "workflow.service.independentReviewRequiredBeforeAnother";

    private static Messages messages;

    @BeforeAll
    static void wire() throws IOException {
        messages = new Messages(new MessageSourceAccessor(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build()), java.util.Locale.ENGLISH));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            CLAIM_REFUSED + "|en|may be wrong|premises|cause of the failures|another is not allowed|independent review",
            CLAIM_REFUSED + "|ko|틀렸을 수 있다|전제|실패 원인|더 시도할 수 없다|독립 검토",
            REVIEW_REQUIRED + "|en|may be wrong|premises|cause of the failures|Independent review is required|Independent review",
            REVIEW_REQUIRED + "|ko|틀렸을 수 있으니|전제|실패 원인|독립 검토가 필요하다|독립 검토"})
    void theBudgetMessageSaysTheApproachMayBeWrongAndKeepsTheRule(String key, String locale, String wrong, String premises, String cause, String rule, String review) {
        String text = messages.render(MessageRef.of(key, "attempts", 3), locale);
        for (String part : List.of(wrong, premises, cause, rule, review)) {
            assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains(part.toLowerCase(java.util.Locale.ROOT)), key + " (" + locale + ") lacks '" + part + "': " + text);
        }
    }

    /** The number in the claim refusal is the root's own limit, not a fixed three. */
    @ParameterizedTest
    @CsvSource({"en,1", "en,3", "en,7", "ko,1", "ko,3", "ko,7"})
    void theClaimRefusalNamesTheLimitOfTheRoot(String locale, int attempts) {
        String text = messages.render(MessageRef.of(CLAIM_REFUSED, "attempts", attempts), locale);
        assertTrue(text.contains(String.valueOf(attempts)), text);
        assertFalse(text.contains("{attempts}"), "the number was filled in: " + text);
        for (int other : List.of(1, 3, 7)) {
            if (other != attempts) {
                assertFalse(text.contains(" " + other + " ") || text.contains(" " + other + "회"), "the limit of " + attempts + " does not name " + other + ": " + text);
            }
        }
    }

    @Test
    void neitherMessageOffersAnotherFullAttemptAsTheWayOut() {
        for (String key : List.of(CLAIM_REFUSED, REVIEW_REQUIRED)) {
            for (String locale : List.of("en", "ko")) {
                String text = messages.render(MessageRef.of(key, "attempts", 3), locale).toLowerCase(java.util.Locale.ROOT);
                assertFalse(text.contains("try again") || text.contains("retry") || text.contains("다시 시도") || text.contains("재시도"), key + " (" + locale + "): " + text);
            }
        }
    }

    /** The code is raised in three places (the claim's fourth try, a later claim on the gated root, the root's status) and with these two messages only. */
    @Test
    void theCodeIsRaisedOnlyWithTheseTwoMessages() throws IOException {
        Pattern raised = Pattern.compile("WorkflowCode\\.ATTEMPT_BUDGET_EXHAUSTED,\\s*msg\\(\"([A-Za-z0-9_.]+)\"");
        List<String> keys = new java.util.ArrayList<>();
        Path server = REPO.resolve("java/sennard-server/src/main/java/io/github/jaeseongs95/sennard/server/workflow");
        try (var files = Files.list(server)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher match = raised.matcher(Files.readString(file));
                while (match.find()) {
                    keys.add(match.group(1));
                }
            }
        }
        assertEquals(3, keys.size(), keys.toString());
        assertEquals(1, java.util.Collections.frequency(keys, CLAIM_REFUSED), keys.toString());
        assertEquals(2, java.util.Collections.frequency(keys, REVIEW_REQUIRED), keys.toString());
    }
}
