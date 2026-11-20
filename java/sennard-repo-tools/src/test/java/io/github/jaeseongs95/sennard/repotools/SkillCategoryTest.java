// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.skills.SkillAssets;
import io.github.jaeseongs95.sennard.server.skills.SkillCategory;
import io.github.jaeseongs95.sennard.server.skills.SkillSchemas;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every skill belongs to one of the two groups, the agent-correction skills or the specialist-field skills, written as {@code category} in its {@code classification.json}.
 * The group is only how skills are listed (the READMEs, the admin page): these cases check that no skill lacks one, that each skill has the group the user assigned, and that the
 * READMEs list each skill under the heading of its group.
 */
class SkillCategoryTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));
    private static final JsonMapper MAPPER = JsonMapper.builder().build(); // a test's own mapper; the product's is Boot's bean
    private static final SkillSchemas SCHEMAS = new SkillSchemas(new SkillAssets(MAPPER), MAPPER);
    private static final String SCHEMA = "contracts/skill-classification-file.v1.schema.json";

    /** The assignment (user, 2026-11-11): fifteen skills that correct the agent, eight for specialist fields. */
    private static final Set<String> AGENT_CORRECTION = Set.of("acceptance-evidence-validator", "blocker-diagnostician", "change-scope-guardian", "coordinate-subagents",
            "evaluation-validity-auditor", "independent-audit-gate", "independent-deliberation-panel", "instruction-scope-resolver", "iteration-frame-auditor", "minimal-implementation",
            "mutation-risk-preflight", "orchestrator", "recovery-strategy-selector", "session-board", "task-contract");
    private static final Set<String> SPECIALIST = Set.of("cs-engineering", "code-review", "test-engineering", "solid-design", "ui-ux-design", "korean-prose-editor",
            "software-security-auditor", "prompt-engineering");

    private static List<String> skillFolders() throws IOException {
        try (Stream<Path> children = Files.list(REPO.path("skills"))) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static Map<String, SkillCategory> assigned() {
        Map<String, SkillCategory> table = new HashMap<>();
        AGENT_CORRECTION.forEach(skill -> table.put(skill, SkillCategory.AGENT_CORRECTION));
        SPECIALIST.forEach(skill -> table.put(skill, SkillCategory.SPECIALIST));
        return table;
    }

    @Test
    void theAssignmentCoversTheTwentyThreeSkillsExactlyOnce() throws IOException {
        assertEquals(15, AGENT_CORRECTION.size());
        assertEquals(8, SPECIALIST.size());
        Set<String> both = new TreeSet<>(AGENT_CORRECTION);
        both.retainAll(SPECIALIST);
        assertEquals(Set.of(), both, "a skill in both groups");
        assertEquals(new TreeSet<>(assigned().keySet()).stream().toList(), skillFolders(), "a skill folder without an assignment, or an assignment without a folder");
    }

    @Test
    void everySkillsClassificationFileConformsToItsSchemaAndNamesItsGroup() throws IOException {
        List<String> errors = new ArrayList<>();
        for (String skill : skillFolders()) {
            String file = "skills/" + skill + "/classification.json";
            if (!REPO.exists(file)) {
                errors.add(file + " is missing");
                continue;
            }
            JsonNode document = REPO.json(file);
            SCHEMAS.violations(SCHEMA, document).forEach(violation -> errors.add(file + " " + violation.instancePath() + " " + violation.message()));
        }
        assertEquals(List.of(), errors);
    }

    @Test
    void eachSkillHasTheGroupItWasAssigned() throws IOException {
        List<String> errors = new ArrayList<>();
        Map<String, SkillCategory> table = assigned();
        for (String skill : skillFolders()) {
            String wire = REPO.json("skills/" + skill + "/classification.json").path("category").stringValue("");
            SkillCategory expected = table.get(skill);
            String expectedWire = expected == SkillCategory.AGENT_CORRECTION ? "agent-correction" : "specialist";
            if (!expectedWire.equals(wire)) {
                errors.add(skill + ": expected " + expectedWire + ", found '" + wire + "'");
            }
        }
        assertEquals(List.of(), errors);
    }

    @Test
    void aSkillWithoutAGroupOrWithAnotherValueFailsTheSchema() throws IOException {
        JsonNode good = REPO.json("skills/task-contract/classification.json");
        assertTrue(SCHEMAS.valid(SCHEMA, good));
        // The enum's JSON values are the schema's: each value the schema allows binds to a constant, and the constants' JSON values are the two the schema allows.
        assertEquals(SkillCategory.AGENT_CORRECTION, MAPPER.readValue("\"agent-correction\"", SkillCategory.class));
        assertEquals(SkillCategory.SPECIALIST, MAPPER.readValue("\"specialist\"", SkillCategory.class));
        assertEquals(List.of("\"agent-correction\"", "\"specialist\""), Stream.of(SkillCategory.values()).map(MAPPER::writeValueAsString).toList());
        ObjectNode without = ((ObjectNode) good).deepCopy();
        without.remove("category");
        assertFalse(SCHEMAS.valid(SCHEMA, without), "no category");
        ObjectNode other = ((ObjectNode) good).deepCopy();
        other.put("category", "other");
        assertFalse(SCHEMAS.valid(SCHEMA, other), "a value outside the two");
        other.put("category", "AGENT_CORRECTION");
        assertFalse(SCHEMAS.valid(SCHEMA, other), "the enum constant is not the JSON value");
    }

    @Test
    void theReadmesListEachSkillUnderTheHeadingOfItsGroup() throws IOException {
        for (Map.Entry<String, List<String>> readme : Map.of("README.md", List.of("### Agent correction (15)", "### Specialist fields (8)"),
                "README.ko.md", List.of("### 에이전트 교정(15개)", "### 전문분야(8개)")).entrySet()) {
            String text = REPO.text(readme.getKey());
            int correction = text.indexOf(readme.getValue().get(0));
            int specialist = text.indexOf(readme.getValue().get(1));
            assertTrue(correction > 0 && specialist > correction, readme.getKey() + ": the two group headings, in this order");
            String correctionPart = text.substring(correction, specialist);
            String specialistPart = text.substring(specialist, text.indexOf("\n## ", specialist));
            for (String skill : AGENT_CORRECTION) {
                assertTrue(correctionPart.contains("| [`" + skill + "`](skills/" + skill + "/)"), readme.getKey() + ": " + skill + " under agent correction");
                assertFalse(specialistPart.contains("[`" + skill + "`]"), readme.getKey() + ": " + skill + " not under specialist fields");
            }
            for (String skill : SPECIALIST) {
                assertTrue(specialistPart.contains("| [`" + skill + "`](skills/" + skill + "/)"), readme.getKey() + ": " + skill + " under specialist fields");
                assertFalse(correctionPart.contains("[`" + skill + "`]"), readme.getKey() + ": " + skill + " not under agent correction");
            }
        }
    }
}
