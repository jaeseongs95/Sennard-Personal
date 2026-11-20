// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStatsStore;

/**
 * The workflow statistics the admin page shows (GET /api/workflow-stats): how many starts of each {@link WorkflowKind}, the skills the starts of each kind declared (a kind is counted
 * apart from the others: a kind whose skills are optional would distort the numbers of the others), and the newest starts. The database has counted them ({@link WorkflowStatsStore}); this
 * puts them in the page's shape. The kinds are read from the enum, so a kind added there is listed here with no change, one with no start is listed with zeros, and a kind the database
 * holds that this build does not know (a run of a later build) is not listed and not in the total.
 */
public final class WorkflowStatsView {
    /** How many of the newest starts are listed. */
    public static final int RECENT_LIMIT = 50;

    private WorkflowStatsView() {
    }

    public record Stats(String schemaVersion, int days, int total, List<KindCount> kinds, List<KindSkill> skills, int recentLimit, List<Recent> recent) {
    }

    /** One kind: its constant's name, whether its starts must declare skills, how many started, and how many of those declared none. */
    public record KindCount(String kind, boolean skillsRequired, int started, int withoutSkills) {
    }

    /** A skill declared by the starts of one kind, and in how many of them. */
    public record KindSkill(String kind, String skillId, int workflows) {
    }

    /** One start: kind (the constant's name), goal, run state, the declared skills, host, and the start time. */
    public record Recent(String runId, String kind, String goal, String state, List<String> skills, String host, String startedAt) {
    }

    /** The statistics {@code counted} by the database, the starts of the last {@code days} days; the skills of a kind come most declared first, as the store has them. */
    public static Stats of(String schemaVersion, int days, WorkflowStatsStore.Stats counted) {
        List<KindCount> kinds = new ArrayList<>();
        List<KindSkill> skills = new ArrayList<>();
        int total = 0;
        for (WorkflowKind kind : WorkflowKind.values()) {
            WorkflowStatsStore.KindTotal totals = counted.kinds().stream().filter(entry -> entry.kind().equals(kind.name())).findFirst().orElse(new WorkflowStatsStore.KindTotal(kind.name(), 0, 0));
            kinds.add(new KindCount(kind.name(), kind.skillsRequired(), totals.started(), totals.withoutSkills()));
            total += totals.started();
            counted.skills().stream().filter(entry -> entry.kind().equals(kind.name())).forEach(entry -> skills.add(new KindSkill(kind.name(), entry.skillId(), entry.workflows())));
        }
        List<Recent> recent = counted.newest().stream().limit(RECENT_LIMIT).map(run -> new Recent(run.runId(), run.kind(), run.goal(), run.state(), run.skills(), run.host(), run.startedAt())).toList();
        return new Stats(schemaVersion, days, total, kinds, skills, RECENT_LIMIT, recent);
    }
}
