// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.server.approval.RootAction;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sentence a root approval request carries to the agent: one whole catalog sentence per {@link RootAction}, English for the model, with the arguments
 * {@code WorkflowService} gives it (action, rootId, actorId, currentScope, and replacementScope for a replacement). Rendered here by the catalog's own message source,
 * the one the application uses.
 *
 * <pre>
 * r1  every RootAction has a sentence key, and its English text has no Hangul
 * r2  the English text holds the root, the actor, the action and the current scope that were given
 * r3  the replacement sentence holds the replacement scope; the release sentence has no place for one
 * r4  the Korean catalog has the same keys, with Korean text (the translation exists)
 * r5  the refusal sentence for each reason an approval cannot be used (RootApprovalProblem) exists in both languages
 * </pre>
 */
class RootApprovalSentencesTest {
    private static final CatalogMessageSource SOURCE = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build());
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final String SCOPE = "{\"task\":\"scope-A\"}";
    private static final String REPLACEMENT = "{\"task\":\"scope-B\"}";

    private static String key(RootAction action) {
        return switch (action) {
            case RELEASE_ROOT -> "workflow.service.rootApprovalReleaseDescription";
            case REPLACE_ROOT -> "workflow.service.rootApprovalReplaceDescription";
        };
    }

    private static String render(RootAction action, Locale locale) {
        Map<String, Object> arguments = Map.of("action", action.name(), "rootId", "root-7", "actorId", "actor-9", "currentScope", SCOPE, "replacementScope", REPLACEMENT);
        return SOURCE.getMessage(key(action), new Object[] {arguments}, locale);
    }

    @Test
    void everyActionHasAnEnglishSentenceWithoutHangul() {
        for (RootAction action : RootAction.values()) {
            String sentence = render(action, Locale.ENGLISH);
            assertFalse(HANGUL.matcher(sentence).find(), sentence);
            assertFalse(sentence.contains("workflow.service."), sentence);
        }
    }

    @Test
    void theSentenceHoldsTheRootTheActorTheActionAndTheCurrentScope() {
        for (RootAction action : RootAction.values()) {
            String sentence = render(action, Locale.ENGLISH);
            assertTrue(sentence.contains("root-7"), sentence);
            assertTrue(sentence.contains("actor-9"), sentence);
            assertTrue(sentence.contains(action.name()), sentence);
            assertTrue(sentence.contains(SCOPE), sentence);
        }
    }

    @Test
    void onlyTheReplacementSentenceHoldsTheReplacementScope() {
        assertTrue(render(RootAction.REPLACE_ROOT, Locale.ENGLISH).contains(REPLACEMENT));
        assertFalse(render(RootAction.RELEASE_ROOT, Locale.ENGLISH).contains(REPLACEMENT));
    }

    @Test
    void theKoreanCatalogHasTheSameSentencesInKorean() {
        for (RootAction action : RootAction.values()) {
            String sentence = render(action, Locale.KOREAN);
            assertTrue(HANGUL.matcher(sentence).find(), sentence);
            assertTrue(sentence.contains("root-7") && sentence.contains(SCOPE), sentence);
        }
    }

    private static String refusalKey(WorkflowService.RootApprovalProblem problem) {
        return switch (problem) {
            case ACTOR_UNAVAILABLE -> "workflow.service.rootApprovalActorUnavailable";
            case REFERENCE_INVALID -> "workflow.service.rootApprovalReferenceInvalid";
            case REFERENCE_UNBOUND -> "workflow.service.rootApprovalReferenceUnbound";
        };
    }

    @Test
    void everyRefusalOfAnUnusableApprovalExistsInBothLanguages() {
        for (WorkflowService.RootApprovalProblem problem : WorkflowService.RootApprovalProblem.values()) {
            String english = SOURCE.getMessage(refusalKey(problem), new Object[] {Map.of()}, Locale.ENGLISH);
            assertFalse(HANGUL.matcher(english).find() || english.startsWith("workflow.service."), english);
            assertTrue(HANGUL.matcher(SOURCE.getMessage(refusalKey(problem), new Object[] {Map.of()}, Locale.KOREAN)).find(), problem.name());
        }
    }
}
