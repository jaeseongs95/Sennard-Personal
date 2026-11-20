// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind.Action;
import tools.jackson.databind.json.JsonMapper;

/**
 * The properties every kind carries, over every constant of the enum: a kind added to it is held to these without a line here. What the constant says is read by the judgments
 * (the start, the edit gate, the hooks, the end), so the invariants of the definitions are checked here, and the catalogs have the kind's keys in English and in Korean.
 */
class WorkflowKindTest {
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(directory.resolve("locales"))) {
            directory = directory.getParent();
        }
        return directory;
    }

    private static Set<String> catalog(String locale) throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.list(repository().resolve("locales").resolve(locale))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().equals("translations.lock.json")).toList()) {
                mapper.readTree(file.toFile()).propertyNames().forEach(keys::add);
            }
        }
        return keys;
    }

    /** The keys a kind needs in the catalogs: its own, its tool's description, and the description of each field its tool takes that the common tools do not describe. */
    private static List<String> keysOf(WorkflowKind kind) {
        List<String> keys = new ArrayList<>();
        WorkflowKind.REQUIRED_KEYS.forEach(suffix -> keys.add(kind.key(suffix)));
        keys.add("tools." + kind.toolName() + ".description");
        if (!kind.allowsFileEdits()) {
            keys.add("tools." + kind.toolName() + ".input.skills");
            keys.add("tools." + kind.toolName() + ".input.classificationOperationKey");
            keys.add("tools." + kind.toolName() + ".input.workspace");
            if (kind.takesTarget()) {
                keys.add("tools." + kind.toolName() + ".input.target");
            }
        }
        return keys;
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void everyKindHasItsKeysInBothCatalogs(WorkflowKind kind) throws IOException {
        for (String locale : List.of("en", "ko")) {
            Set<String> missing = new TreeSet<>(keysOf(kind));
            missing.removeAll(catalog(locale));
            assertEquals(Set.of(), missing, kind + " in " + locale);
        }
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void aKindIsFoundByItsToolAndItsToolNameIsItsOwn(WorkflowKind kind) {
        assertEquals(kind, WorkflowKind.ofTool(kind.toolName()).orElseThrow());
        assertEquals(1, Stream.of(WorkflowKind.values()).filter(other -> other.toolName().equals(kind.toolName())).count());
        assertTrue(kind.messagePrefix().startsWith("workflow.kind."), kind.messagePrefix());
        assertEquals(kind.messagePrefix() + ".purpose", kind.key("purpose"));
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void theProcessIsOrderedAndEachPhaseAllowsReading(WorkflowKind kind) {
        List<WorkflowKind.Phase> process = kind.process();
        assertFalse(process.isEmpty());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < process.size(); index += 1) {
            WorkflowKind.Phase phase = process.get(index);
            assertTrue(ids.add(phase.id()), kind + " repeats " + phase.id());
            assertTrue(phase.allows().contains(Action.READ), kind + " phase " + phase.id() + " allows reading: a tool that only reads is allowed whenever an instance is open");
            if (index < process.size() - 1) {
                assertTrue(phase.advancedBy() != null, kind + " phase " + phase.id() + " says what moves the instance on");
            } else {
                assertNull(phase.advancedBy(), kind + " has no phase after its last");
            }
        }
        assertEquals(process.getFirst(), kind.firstPhase());
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void theKindsPropertiesAreWhatItsProcessAllows(WorkflowKind kind) {
        for (Action action : Action.values()) {
            boolean some = kind.process().stream().anyMatch(phase -> phase.allows().contains(action));
            assertEquals(some, kind.allows(action), kind + " " + action);
            for (WorkflowKind.Phase phase : kind.process()) {
                assertEquals(phase.allows().contains(action), kind.allows(phase.id(), action), kind + " " + phase.id() + " " + action);
            }
        }
        assertEquals(kind.allows(Action.EDIT), kind.allowsFileEdits());
        assertEquals(kind.allows(Action.COMMAND), kind.allowsCommands());
        assertFalse(kind.allows("NO_SUCH_PHASE", Action.READ));
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void aKindThatOwnsNoFilesIsAnswerOrOperationShaped(WorkflowKind kind) {
        // What a kind owns decides what its start takes: files, stages and a change plan for a kind that edits; a goal, a target when it acts on one, and skills as it says otherwise.
        if (kind.allowsFileEdits()) {
            assertFalse(kind.takesTarget(), kind + " owns files, so its work is not described by a target");
            assertEquals(WorkflowKind.Closing.FINALIZE, kind.closing(), kind + " owns files: the agent finishes it");
        }
        // The JEV step runs for the kinds whose skills are required (the advice fills the refusal of a start that left them out).
        assertEquals(kind.skillsRequired(), kind.jevPreStage(), kind.toString());
    }

    @ParameterizedTest
    @EnumSource(WorkflowKind.class)
    void anInstanceOfAKindWithoutFilesStandsOnAPlaceOfItsOwn(WorkflowKind kind) {
        String first = WorkflowKind.sentinelEntry(kind, "one");
        String second = WorkflowKind.sentinelEntry(kind, "two");
        assertTrue(WorkflowKind.isSentinel(first), first);
        assertTrue(WorkflowKind.isSentinel(second), second);
        assertFalse(first.equals(second));
        assertFalse(WorkflowKind.isSentinel("src/main"));
        assertFalse(WorkflowKind.isSentinel("."));
    }

    @Test
    void theThreeKindsAreWhatTheDesignSays() {
        assertFalse(WorkflowKind.INQUIRY.skillsRequired());
        assertFalse(WorkflowKind.INQUIRY.allowsFileEdits());
        assertEquals(WorkflowKind.Closing.TURN_END, WorkflowKind.INQUIRY.closing());
        assertTrue(WorkflowKind.CHANGE.skillsRequired());
        assertTrue(WorkflowKind.CHANGE.allowsFileEdits());
        assertEquals("start_workflow", WorkflowKind.CHANGE.toolName());
        assertTrue(WorkflowKind.OPERATION.skillsRequired());
        assertFalse(WorkflowKind.OPERATION.allowsFileEdits());
        assertTrue(WorkflowKind.OPERATION.allowsCommands());
        assertTrue(WorkflowKind.OPERATION.takesTarget());
        assertEquals(WorkflowKind.Closing.FINALIZE, WorkflowKind.OPERATION.closing());
    }
}
