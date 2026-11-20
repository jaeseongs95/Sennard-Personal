// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.jobs;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.store.JobState;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * The job table (design B.3): {@code queued -> running -> succeeded | failed}. It joins the transaction of its caller and opens none ({@link JobService} is
 * where each use case is one transaction). {@link #enqueue} runs in the business transaction that asks for the work, so the work starts only if that transaction
 * commits; a row is claimed by a conditional UPDATE, so it runs once.
 */
@Repository
public class JobStore {
    /** One row of the table, what {@code get_background_job} answers from. */
    public record BackgroundJob(UUID jobId, String kind, JobState state, Instant createdAt, Instant startedAt, Instant finishedAt, String errorCode, String resultJson) {
    }

    /** The row as the table has it; Spring's DataClassRowMapper reads the state name back into the constant and a TIMESTAMP as a {@link LocalDateTime}. */
    private record JobRow(String jobId, String kind, JobState state, LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode, String resultJson) {
    }

    private record Unfinished(String jobId, String kind, String inputJson) {
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public JobStore(@Qualifier("storeJdbc") JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /** The injected clock's reading as a TIMESTAMP value. */
    private LocalDateTime now() {
        return Timestamps.toTimestamp(clock.instant());
    }

    /** Adds a queued row in the caller's transaction and asks for a start after the commit. */
    public UUID enqueue(String kind, String inputJson) {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sennard.background_job (job_id, kind, state, input_json, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(StoreValues.bind(jobId, kind, JobState.QUEUED, inputJson, now())).update();
        events.publishEvent(new JobQueued(jobId, kind, inputJson));
        return jobId;
    }

    /** True when this caller moved the row out of {@code queued}: only that caller runs it. */
    public boolean claim(UUID jobId) {
        return jdbc.sql("UPDATE sennard.background_job SET state = ?, started_at = ? WHERE job_id = ? AND state = ?")
                .params(StoreValues.bind(JobState.RUNNING, now(), jobId, JobState.QUEUED)).update() == 1;
    }

    /** Ends a running job. A row the start-up recovery closed in the meantime keeps that outcome. */
    public void finish(UUID jobId, JobState state, String errorCode, String resultJson) {
        if (!state.isFinished()) {
            throw new IllegalArgumentException("A job is finished with SUCCEEDED or FAILED, not " + state);
        }
        jdbc.sql("UPDATE sennard.background_job SET state = ?, finished_at = ?, error_code = ?, result_json = ? WHERE job_id = ? AND state = ?")
                .params(StoreValues.bind(state, now(), errorCode, resultJson, jobId, JobState.RUNNING)).update();
    }

    public Optional<BackgroundJob> find(UUID jobId) {
        return jdbc.sql("SELECT job_id, kind, state, created_at, started_at, finished_at, error_code, result_json FROM sennard.background_job WHERE job_id = ?")
                .params(jobId.toString())
                .query(JobRow.class)
                .optional()
                .map(row -> new BackgroundJob(UUID.fromString(row.jobId()), row.kind(), row.state(), Timestamps.toInstant(row.createdAt()), Timestamps.toInstantOrNull(row.startedAt()),
                        Timestamps.toInstantOrNull(row.finishedAt()), row.errorCode(), row.resultJson()));
    }

    /**
     * At start-up: a job that was running when the process ended may have had an effect nobody
     * saw, so it is closed as failed and not repeated; a job still queued had no effect and starts again.
     *
     * @return how many jobs start again
     */
    public int recover() {
        List<Unfinished> running = jdbc.sql("SELECT job_id, kind, input_json FROM sennard.background_job WHERE state = ?")
                .params(StoreValues.bind(JobState.RUNNING))
                .query(Unfinished.class)
                .list();
        for (Unfinished job : running) {
            jdbc.sql("UPDATE sennard.background_job SET state = ?, finished_at = ?, error_code = 'SERVICE_RESTARTED', result_json = '{\"effect\":\"unknown\"}' "
                    + "WHERE job_id = ? AND state = ?")
                    .params(StoreValues.bind(JobState.FAILED, now(), job.jobId(), JobState.RUNNING)).update();
        }
        List<Unfinished> queued = jdbc.sql("SELECT job_id, kind, input_json FROM sennard.background_job WHERE state = ?")
                .params(StoreValues.bind(JobState.QUEUED))
                .query(Unfinished.class)
                .list();
        for (Unfinished job : queued) {
            events.publishEvent(new JobQueued(UUID.fromString(job.jobId()), job.kind(), job.inputJson()));
        }
        return queued.size();
    }
}
