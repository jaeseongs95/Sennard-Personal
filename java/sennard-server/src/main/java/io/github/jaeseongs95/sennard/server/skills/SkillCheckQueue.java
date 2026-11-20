// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.service.store.jobs.JobService;
import tools.jackson.databind.JsonNode;

/**
 * The one write of {@code run_skill_check}: queuing the job of a check that runs in the background (AGS3-D-0267). The row of the job and the receipt of the operationKey are written
 * in this transaction, so the same key sent again answers the job the first call queued ({@code jobRef}) and queues none. A check that only judges (every other command) writes
 * nothing, so it has no receipt: the key is required of it too, and a key sent again simply asks the same question again.
 *
 * <p>The commands that queue a job are those a skill lists in {@link SkillCheck#backgroundCommands()}: {@code change-scope-guardian compare} (ChangeScopeCheck.java:285) and the
 * {@code run} command of the engineering checks (EngineeringPracticeChecks.java:38).
 */
@Service
public class SkillCheckQueue {
    private final JobService jobs;
    private final SkillCheckJob checkJob;
    private final OperationReceipts receipts;

    public SkillCheckQueue(JobService jobs, SkillCheckJob checkJob, OperationReceipts receipts) {
        this.jobs = jobs;
        this.checkJob = checkJob;
        this.receipts = receipts;
    }

    /** @return the reference of the job this key queued, the same one when the key comes again with the same content */
    @Transactional
    public UUID enqueue(String skill, String command, JsonNode input, OperationKey operation) {
        return receipts.once(operation, UUID.class, () -> jobs.enqueue(SkillCheckJob.KIND, checkJob.input(skill, command, input))).value();
    }
}
