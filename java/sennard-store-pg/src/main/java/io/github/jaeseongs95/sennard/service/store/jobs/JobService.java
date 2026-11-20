// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.jobs;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.service.store.JobState;

/**
 * The use cases of the job table: each method is one transaction, and {@link JobStore} (the repository) only joins it. A caller that queues a job inside its own
 * transaction joins that one, so the job exists and starts only if the caller commits ({@link JobRunner} starts it after the commit); a job's start, its end and the
 * start-up recovery are separate use cases, because what a job runs between them (a process, a directory walk) must not hold a transaction.
 */
@Service
public class JobService {
    private final JobStore jobs;

    public JobService(JobStore jobs) {
        this.jobs = jobs;
    }

    /** Adds a queued row in this transaction (the caller's, when it has one) and asks for a start after the commit. */
    @Transactional
    public UUID enqueue(String kind, String inputJson) {
        return jobs.enqueue(kind, inputJson);
    }

    /** True when this caller moved the row out of {@code queued}: only that caller runs it. */
    @Transactional
    public boolean claim(UUID jobId) {
        return jobs.claim(jobId);
    }

    /** Ends a running job. A row the start-up recovery closed in the meantime keeps that outcome. */
    @Transactional
    public void finish(UUID jobId, JobState state, String errorCode, String resultJson) {
        jobs.finish(jobId, state, errorCode, resultJson);
    }

    @Transactional(readOnly = true)
    public Optional<JobStore.BackgroundJob> find(UUID jobId) {
        return jobs.find(jobId);
    }

    /**
     * At start-up: a job that was running when the process ended is closed as failed and not repeated, a queued one starts again.
     *
     * @return how many jobs start again
     */
    @Transactional
    public int recover() {
        return jobs.recover();
    }
}
