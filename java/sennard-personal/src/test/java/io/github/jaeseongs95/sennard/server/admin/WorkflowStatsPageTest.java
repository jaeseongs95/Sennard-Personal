// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sentences of the workflow statistics and of the open workflows screen. Cases (written first):
 * <pre>
 * p1  kinds     every {@link WorkflowKind} has its name on the page in both languages, different in the two (the page is given the constant's name and finds the sentence by it)
 * p2  states    every {@link RunState} has its name on the page in both languages
 * p3  the name  Workflow management and Workflow 관리 appear in navigation and in the title, with no internal root terminology
 * </pre>
 */
class WorkflowStatsPageTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode catalog(String language) throws IOException {
        try (InputStream in = new ClassPathResource("locales/" + language + "/admin.json").getInputStream()) {
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void p1_everyKindHasItsNameInBothLanguages() throws IOException {
        JsonNode english = catalog("en"), korean = catalog("ko");
        for (WorkflowKind kind : WorkflowKind.values()) {
            String key = "admin.page.workflows.kind." + kind.name();
            assertFalse(english.path(key).asString().isBlank(), "English name of " + kind);
            assertFalse(korean.path(key).asString().isBlank(), "Korean name of " + kind);
            assertNotEquals(english.path(key).asString(), korean.path(key).asString(), "the two languages differ for " + kind);
        }
    }

    @Test
    void p2_everyRunStateHasItsNameInBothLanguages() throws IOException {
        for (String language : List.of("en", "ko")) {
            JsonNode names = catalog(language);
            for (RunState state : RunState.values()) {
                assertFalse(names.path("admin.page.workflows.state." + state.name()).asString().isBlank(), language + " name of " + state);
            }
        }
    }

    @Test
    void p3_workflowManagementHasALocalizedTitleAndUserFacingTerminology() throws IOException {
        JsonNode english = catalog("en"), korean = catalog("ko");
        for (String key : List.of("admin.page.nav.roots", "admin.page.rootRelease.tab", "admin.page.rootRelease.title")) {
            assertEquals("Workflow management", english.path(key).asString(), key);
            assertEquals("Workflow 관리", korean.path(key).asString(), key);
        }
        for (String language : List.of("en", "ko")) {
            JsonNode names = catalog(language);
            for (String key : names.propertyNames()) {
                boolean screen = key.startsWith("admin.page.rootRelease.") || key.startsWith("admin.rootRelease.") || key.equals("admin.page.nav.roots")
                        || key.matches("admin\\.page\\.error\\.ADMIN_ROOT_.*");
                if (screen) {
                    String sentence = names.path(key).asString().replace("{rootId}", "");
                    assertFalse(sentence.matches("(?is).*\\broots?\\b.*"), language + " " + key + " still says root: " + sentence);
                }
            }
        }
    }
}
