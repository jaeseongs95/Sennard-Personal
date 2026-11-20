// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * When the upkeep of the attachments runs on its own, two jobs on two intervals, each registered with the interval as the {@link Duration} the settings record binds (the way
 * {@code UpdateCheckRounds} does it): the first round of each comes one interval after the start, and each next one that long after the end of the one before.
 *
 * <ul>
 * <li>The cleaner, every {@code sennard.attachments.reaper-interval} (one minute by default): {@link AttachmentMaintenance#reap()}, which only finds the expired and the done upload rows in
 * the database and deletes them.</li>
 * <li>The comparison of the folder with the rows, every {@code sennard.attachments.reconcile-interval} (24 hours by default): {@link AttachmentMaintenance#reconcileFolder()}, which looks at
 * every file of the folder. The comparison of the start is the store-ready task of the edition ({@code ServerStartTasks}); the command {@code sennard attachments purge-orphans} runs it too.</li>
 * </ul>
 *
 * It is an entrance: it opens no transaction. With no attachment folder in the settings there is nothing to clean and no round is registered.
 */
@Component
public class AttachmentReaperRounds implements SchedulingConfigurer {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentReaperRounds.class);

    private final AttachmentMaintenance maintenance;
    private final AttachmentProperties properties;

    public AttachmentReaperRounds(AttachmentMaintenance maintenance, AttachmentProperties properties) {
        this.maintenance = maintenance;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.directory() != null) {
            registrar.addFixedDelayTask(new IntervalTask(this::round, properties.reaperInterval(), properties.reaperInterval()));
            registrar.addFixedDelayTask(new IntervalTask(this::reconcileRound, properties.reconcileInterval(), properties.reconcileInterval()));
        }
    }

    /** One round of the cleaner. Boundary (scheduled task top): a failed round is tried again in the next one; the failure is logged with its cause. */
    void round() {
        try {
            AttachmentMaintenance.Reaped reaped = maintenance.reap();
            LOG.debug("ATTACHMENT_REAPED {}", reaped);
        } catch (RuntimeException failure) {
            LOG.warn("ATTACHMENT_REAP_FAILED", failure);
        }
    }

    /** One comparison of the folder with the rows. Boundary (scheduled task top): a failed comparison is tried again in the next one; the failure is logged with its cause. */
    void reconcileRound() {
        try {
            AttachmentMaintenance.Reconciled reconciled = maintenance.reconcileFolder();
            LOG.debug("ATTACHMENT_RECONCILED {}", reconciled);
        } catch (RuntimeException failure) {
            LOG.warn("ATTACHMENT_RECONCILE_FAILED", failure);
        }
    }
}
