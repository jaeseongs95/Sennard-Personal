// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which workflow each agent started, counted by the database: nothing is written here. The kind of a run is a column of {@code workflow_runs} and the skills its start declared are rows of
 * {@code workflow_run_skills}, both written with the run (V22), so the count by kind and the count of the skills of each kind are {@code GROUP BY} statements over the runs of the
 * period and no receipt is read for them. Only the newest runs are read further: a run's goal is its root's task and its host is the front of its lease's actor, values that are in
 * those documents only, and they are read for the few runs the page lists, in one statement.
 */
@Repository
public class WorkflowStatsStore extends ModuleStore {
    private static final long DAY_MS = 24 * 3600_000L;
    private static final String ACTOR_SESSION = ":session-";
    private static final String T = "sennard.";

    private final JsonMapper mapper;
    private final SqlBuilder limit;

    public WorkflowStatsStore(@Qualifier("storeJdbc") JdbcClient jdbc, JsonMapper mapper, SqlBuilder limit) {
        super(jdbc);
        this.mapper = mapper;
        this.limit = limit;
    }

    /**
     * How many runs started with one kind, and how many of those declared no skill.
     *
     * @param kind the name of the kind's constant (this module does not know the kinds)
     */
    public record KindTotal(String kind, int started, int withoutSkills) {
    }

    /** A skill (its own name, without a plugin prefix) and in how many runs of one kind it was declared. */
    public record KindSkill(String kind, String skillId, int workflows) {
    }

    /**
     * One started workflow run.
     *
     * @param kind the name of the kind the run was started as
     * @param skills the skills the start declared, each as the skill's own name, in alphabetical order; empty for a start that declared none
     * @param host the host of the session that started it, or null when the lease names no actor
     * @param startedAt the time the run was created, as UTC text
     */
    public record Run(String runId, String rootId, String kind, String state, String goal, List<String> skills, String host, String startedAt) {
    }

    /**
     * The statistics of a period.
     *
     * @param kinds a row for each kind that had a start, in no fixed order
     * @param skills the skills of each kind, kind by kind, the most declared first and then by name
     * @param newest the newest runs, the newest first
     */
    public record Stats(List<KindTotal> kinds, List<KindSkill> skills, List<Run> newest) {
    }

    private record RunSkill(String runId, String skillId) {
    }

    /** The runs created in the last {@code days} days: the counts by kind and by skill of all of them, and the {@code recentLimit} newest. */
    public Stats stats(int days, Instant now, int recentLimit) {
        LocalDateTime since = StoreValues.ts(now.toEpochMilli() - days * DAY_MS);
        // The period is a range of workflow_runs_period (created_at, kind): the count by kind is read from that index alone, the skills of each run by the primary key (run_id, skill_id).
        List<KindTotal> kinds = jdbc.sql("SELECT k.kind, COUNT(*) AS started, SUM(CASE WHEN EXISTS (SELECT 1 FROM " + T + "workflow_run_skills s WHERE s.run_id = k.run_id) THEN 0 ELSE 1 END) AS without_skills "
                + "FROM " + T + "workflow_runs k WHERE k.created_at >= ? GROUP BY k.kind")
                .params(since).query((rs, row) -> new KindTotal(rs.getString("kind"), rs.getInt("started"), rs.getInt("without_skills"))).list();
        List<KindSkill> skills = jdbc.sql("SELECT k.kind, s.skill_id, COUNT(*) AS workflows FROM " + T + "workflow_runs k JOIN " + T + "workflow_run_skills s ON s.run_id = k.run_id "
                + "WHERE k.created_at >= ? GROUP BY k.kind, s.skill_id ORDER BY k.kind, COUNT(*) DESC, s.skill_id")
                .params(since).query((rs, row) -> new KindSkill(rs.getString("kind"), rs.getString("skill_id"), rs.getInt("workflows"))).list();
        return new Stats(kinds, skills, newest(since, recentLimit));
    }

    /**
     * The newest runs, newest first. The {@code recentLimit} runs are chosen first, by the index, and only those are joined to the documents their goal and host are read from (the
     * root's task, the lease's actor), then their skills are read in one statement: three statements however many runs there are, and no document of an older run is read.
     */
    private List<Run> newest(LocalDateTime since, int recentLimit) {
        String newest = limit.applyLimit("SELECT run_id, kind, state, created_at AS started_at FROM " + T + "workflow_runs WHERE created_at >= ? ORDER BY created_at DESC, run_id DESC", "?");
        List<Run> runs = jdbc.sql("SELECT k.run_id, k.kind, k.started_at, k.state, l.root_id, c.root_json, s.lease_json FROM (" + newest + ") k "
                + "JOIN " + T + "workflow_attempt_links l ON l.run_id = k.run_id "
                + "JOIN " + T + "convergence_roots c ON c.root_id = l.root_id JOIN " + T + "convergence_leases s ON s.lease_id = l.lease_id "
                + "ORDER BY k.started_at DESC, k.run_id DESC")
                .params(since, recentLimit).query((rs, row) -> run(rs)).list();
        if (runs.isEmpty()) {
            return runs;
        }
        Map<String, List<String>> skills = new HashMap<>();
        for (RunSkill declared : jdbc.sql("SELECT run_id, skill_id FROM " + T + "workflow_run_skills WHERE run_id IN (:runIds) ORDER BY run_id, skill_id")
                .param("runIds", runs.stream().map(Run::runId).toList())
                .query((rs, row) -> new RunSkill(rs.getString("run_id"), rs.getString("skill_id"))).list()) {
            skills.computeIfAbsent(declared.runId(), id -> new ArrayList<>()).add(declared.skillId());
        }
        return runs.stream().map(run -> new Run(run.runId(), run.rootId(), run.kind(), run.state(), run.goal(), List.copyOf(skills.getOrDefault(run.runId(), List.of())), run.host(), run.startedAt())).toList();
    }

    /** One run without its skills: the root's and the lease's documents are made into the few fields that are kept as the row is read. */
    private Run run(ResultSet rs) throws SQLException {
        String runId = rs.getString("run_id");
        try {
            JsonNode root = mapper.readTree(rs.getString("root_json"));
            JsonNode lease = mapper.readTree(rs.getString("lease_json"));
            String actor = lease.path("actorId").isString() ? lease.path("actorId").asString() : null;
            int session = actor == null ? -1 : actor.indexOf(ACTOR_SESSION);
            return new Run(runId, rs.getString("root_id"), rs.getString("kind"), rs.getString("state"), root.path("taskEnvelope").path("objective").asString(), List.of(),
                    session > 0 ? actor.substring(0, session) : null, StoreValues.isoOrNull(rs.getObject("started_at", LocalDateTime.class)));
        } catch (JacksonException unreadable) {
            throw new StoreException(StoreCode.INTEGRITY_FAILED, "WORKFLOW_RECORD_UNREADABLE: a stored document of run " + runId + " is not JSON.", unreadable);
        }
    }
}
