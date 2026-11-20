// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStatsStore;

/**
 * The page's shape of the workflow statistics the database counted, without a server or a database (the counting itself is {@code WorkflowStatsStoreTest}, on PostgreSQL). Cases (written first):
 * <pre>
 * v1  no run          every kind of the enum is listed, in the enum's order, with no start; the totals are zero
 * v2  by kind         the counts of a kind are the store's counts of it, and the total is their sum
 * v3  unknown kind    a kind the database holds that the enum does not have is not listed and not in the total; a kind of the enum the database has no row of is listed with zeros
 * v4  skills by kind  a kind lists the skills the store counted for it, in the order the store gave them, and never another kind's; the runs without skills are the store's count
 * v5  newest          the list keeps the order it was given and stops at the limit, a run keeps the kind the database holds (the page shows an unknown name as it is)
 * </pre>
 */
class WorkflowStatsViewTest {
    private static WorkflowStatsStore.Run run(String id, String kind, String... skills) {
        return new WorkflowStatsStore.Run(id, "root-" + id, kind, "PASSED", "goal " + id, List.of(skills), "codex", "2026-11-16T00:00:00.000Z");
    }

    private static WorkflowStatsStore.Stats counted(List<WorkflowStatsStore.KindTotal> kinds, List<WorkflowStatsStore.KindSkill> skills, List<WorkflowStatsStore.Run> newest) {
        return new WorkflowStatsStore.Stats(kinds, skills, newest);
    }

    private static WorkflowStatsView.KindCount count(WorkflowStatsView.Stats stats, WorkflowKind kind) {
        return stats.kinds().stream().filter(entry -> entry.kind().equals(kind.name())).findFirst().orElseThrow();
    }

    @Test
    void v1_everyKindOfTheEnumIsListedInItsOrderWhenNothingStarted() {
        WorkflowStatsView.Stats stats = WorkflowStatsView.of("1.0.0", 30, counted(List.of(), List.of(), List.of()));
        assertEquals(Arrays.stream(WorkflowKind.values()).map(Enum::name).toList(), stats.kinds().stream().map(WorkflowStatsView.KindCount::kind).toList());
        assertEquals(0, stats.total());
        assertTrue(stats.kinds().stream().allMatch(entry -> entry.started() == 0 && entry.withoutSkills() == 0));
        assertTrue(stats.skills().isEmpty());
        assertTrue(stats.recent().isEmpty());
        assertEquals(30, stats.days());
    }

    @Test
    void v2_theCountsOfAKindAreTheStoresAndTheTotalIsTheirSum() {
        WorkflowStatsView.Stats stats = WorkflowStatsView.of("1.0.0", 7, counted(List.of(new WorkflowStatsStore.KindTotal("INQUIRY", 1, 1), new WorkflowStatsStore.KindTotal("OPERATION", 2, 0),
                new WorkflowStatsStore.KindTotal("CHANGE", 1, 0)), List.of(), List.of()));
        assertEquals(1, count(stats, WorkflowKind.INQUIRY).started());
        assertEquals(1, count(stats, WorkflowKind.INQUIRY).withoutSkills());
        assertEquals(2, count(stats, WorkflowKind.OPERATION).started());
        assertEquals(1, count(stats, WorkflowKind.CHANGE).started());
        assertEquals(4, stats.total());
        assertEquals(WorkflowKind.INQUIRY.skillsRequired(), count(stats, WorkflowKind.INQUIRY).skillsRequired());
    }

    @Test
    void v3_aKindThisBuildDoesNotKnowIsNotListedAndAKindWithNoRowHasZeros() {
        WorkflowStatsView.Stats stats = WorkflowStatsView.of("1.0.0", 30, counted(List.of(new WorkflowStatsStore.KindTotal("INQUIRY", 3, 0), new WorkflowStatsStore.KindTotal("A_KIND_OF_A_LATER_BUILD", 5, 5)),
                List.of(new WorkflowStatsStore.KindSkill("A_KIND_OF_A_LATER_BUILD", "x", 5)), List.of()));
        assertEquals(3, stats.total());
        assertEquals(0, count(stats, WorkflowKind.CHANGE).started());
        assertEquals(Arrays.stream(WorkflowKind.values()).count(), stats.kinds().size());
        assertTrue(stats.skills().isEmpty(), "the skills of a kind that is not listed are not listed");
    }

    @Test
    void v4_theSkillsOfAKindAreTheStoresInItsOrderAndNeverAnotherKinds() {
        WorkflowStatsView.Stats stats = WorkflowStatsView.of("1.0.0", 30, counted(
                List.of(new WorkflowStatsStore.KindTotal("INQUIRY", 3, 2), new WorkflowStatsStore.KindTotal("CHANGE", 3, 0)),
                List.of(new WorkflowStatsStore.KindSkill("CHANGE", "y", 3), new WorkflowStatsStore.KindSkill("CHANGE", "x", 1), new WorkflowStatsStore.KindSkill("INQUIRY", "x", 1)),
                List.of()));
        // The kinds come in the enum's order, and the skills of a kind in the order the store gave them (most declared first).
        assertEquals(List.of("INQUIRY:x:1", "CHANGE:y:3", "CHANGE:x:1"), stats.skills().stream().map(skill -> skill.kind() + ":" + skill.skillId() + ":" + skill.workflows()).toList());
        assertEquals(2, count(stats, WorkflowKind.INQUIRY).withoutSkills());
        assertEquals(0, count(stats, WorkflowKind.CHANGE).withoutSkills());
    }

    @Test
    void v5_theNewestListKeepsItsOrderStopsAtTheLimitAndKeepsTheKindOfTheRun() {
        List<WorkflowStatsStore.Run> runs = new ArrayList<>();
        runs.add(run("first", "A_KIND_OF_A_LATER_BUILD", "a", "b"));
        for (int i = 1; i < WorkflowStatsView.RECENT_LIMIT + 5; i++) {
            runs.add(run(String.valueOf(i), "INQUIRY"));
        }
        WorkflowStatsView.Stats stats = WorkflowStatsView.of("1.0.0", 30, counted(List.of(new WorkflowStatsStore.KindTotal("INQUIRY", WorkflowStatsView.RECENT_LIMIT + 4, WorkflowStatsView.RECENT_LIMIT + 4)), List.of(), runs));
        assertEquals(WorkflowStatsView.RECENT_LIMIT, stats.recent().size());
        assertEquals("first", stats.recent().get(0).runId());
        assertEquals("A_KIND_OF_A_LATER_BUILD", stats.recent().get(0).kind());
        assertEquals(List.of("a", "b"), stats.recent().get(0).skills());
        assertEquals("1", stats.recent().get(1).runId());
        assertEquals(WorkflowStatsView.RECENT_LIMIT + 4, count(stats, WorkflowKind.INQUIRY).started(), "the counts are the database's, whatever the list holds");
        assertEquals(WorkflowStatsView.RECENT_LIMIT, stats.recentLimit());
    }
}
