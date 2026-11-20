// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.jobs;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.jaeseongs95.sennard.service.store.JobState;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Starts a job after the transaction that queued it committed, off that transaction's thread: nothing that waits or leaves the process
 * happens inside a transaction (design B.3). Its claim and its end are two use cases of {@link JobService}; the handler runs between them, in none.
 */
@Service
public class JobRunner {
    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);

    private final JobService jobs;
    private final Map<String, JobHandler> handlers;

    public JobRunner(JobService jobs, ObjectProvider<JobHandler> handlers) {
        this.jobs = jobs;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::kind, Function.identity()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void start(JobQueued queued) {
        if (jobs.claim(queued.jobId())) {
            run(queued);
        }
    }

    private void run(JobQueued queued) {
        JobHandler handler = handlers.get(queued.kind());
        if (handler == null) {
            jobs.finish(queued.jobId(), JobState.FAILED, "JOB_KIND_UNKNOWN", null);
            return;
        }
        try {
            jobs.finish(queued.jobId(), JobState.SUCCEEDED, null, handler.run(queued.inputJson()));
        } catch (RuntimeException failure) {
            // The class only: a job's input and message may hold anything.
            LOG.warn("background job {} of kind {} failed: {}", queued.jobId(), queued.kind(), failure.getClass().getName());
            jobs.finish(queued.jobId(), JobState.FAILED, failure instanceof StoreException store ? store.code().name() : "JOB_FAILED", null);
        }
    }
}
