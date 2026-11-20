// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.transcript.UnreadableReason;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sentences {@code read_prompt_transcript} gives for a transcript it cannot read, from the catalog and not from code.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * m1  every reason has words   each UnreadableReason, in en and in ko -> a sentence that is not the key, not blank, with no unresolved placeholder
 * m2  the reasons differ       the five reasons give five different sentences in each locale (no two reasons share words)
 * m3  unsupported names host   UNSUPPORTED with host "codex" -> en and ko sentences that contain codex and are not the key
 * m4  the two service reasons  "the host named no transcript" and "the stored path is not a path here" -> a sentence in en and ko, not the key
 * m5  en is exact              the en sentence of each reason equals the wording that was in the code before it moved to the catalog (the meaning did not change)
 * </pre>
 */
class PromptTranscriptMessagesTest {
    private static Messages messages;

    @BeforeAll
    static void wire() {
        messages = new Messages(new MessageSourceAccessor(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build()), Locale.ENGLISH));
    }

    private static String sentence(String key, String locale) {
        return messages.render(MessageRef.of(key), locale);
    }

    @Test
    void everyReasonHasASentenceInEnglishAndKorean() {
        for (String locale : new String[] {"en", "ko"}) {
            Set<String> seen = new HashSet<>();
            for (UnreadableReason reason : UnreadableReason.values()) {
                String key = PromptTranscriptService.keyOf(reason);
                String text = sentence(key, locale);
                assertNotEquals(key, text, reason + " (" + locale + ") has no sentence in the catalog");
                assertFalse(text.isBlank(), reason + " (" + locale + ")");
                assertFalse(text.contains("{"), reason + " (" + locale + ") has an unresolved placeholder: " + text);
                assertTrue(seen.add(text), reason + " (" + locale + ") shares its sentence with another reason: " + text);
            }
            assertEquals(UnreadableReason.values().length, seen.size());
        }
    }

    @Test
    void theEnglishSentencesKeepTheMeaningTheyHadInCode() {
        Map<UnreadableReason, String> expected = new LinkedHashMap<>();
        expected.put(UnreadableReason.NOT_A_TRANSCRIPT, "The file is not in the transcript format of the host.");
        expected.put(UnreadableReason.ENTRY_GONE, "The entry that was located is no longer in the transcript.");
        expected.put(UnreadableReason.FILE_MISSING, "The transcript file does not exist.");
        expected.put(UnreadableReason.FILE_DENIED, "The transcript file may not be read.");
        expected.put(UnreadableReason.FILE_IO, "The transcript file cannot be read.");
        assertEquals(Set.of(UnreadableReason.values()), expected.keySet());
        expected.forEach((reason, text) -> assertEquals(text, sentence(PromptTranscriptService.keyOf(reason), "en"), reason.name()));
    }

    @Test
    void anUnsupportedHostIsNamedInTheSentence() {
        for (String locale : new String[] {"en", "ko"}) {
            String text = messages.render(MessageRef.of(PromptTranscriptService.KEY_UNSUPPORTED, "host", "codex"), locale);
            assertNotEquals(PromptTranscriptService.KEY_UNSUPPORTED, text, locale);
            assertTrue(text.contains("codex"), locale + ": " + text);
            assertFalse(text.contains("{"), locale + ": " + text);
        }
        assertEquals("This server does not read the transcripts of the host codex yet.", messages.render(MessageRef.of(PromptTranscriptService.KEY_UNSUPPORTED, "host", "codex"), "en"));
    }

    @Test
    void theTwoReasonsOfTheServiceHaveSentences() {
        for (String locale : new String[] {"en", "ko"}) {
            for (String key : new String[] {PromptTranscriptService.KEY_NONE_NAMED, PromptTranscriptService.KEY_PATH_NOT_LOCAL}) {
                String text = sentence(key, locale);
                assertNotEquals(key, text, key + " (" + locale + ")");
                assertFalse(text.isBlank(), key + " (" + locale + ")");
            }
        }
        assertEquals("The host named no transcript for this prompt.", sentence(PromptTranscriptService.KEY_NONE_NAMED, "en"));
        assertEquals("The transcript path stored for this prompt is not a path on this computer.", sentence(PromptTranscriptService.KEY_PATH_NOT_LOCAL, "en"));
    }
}
