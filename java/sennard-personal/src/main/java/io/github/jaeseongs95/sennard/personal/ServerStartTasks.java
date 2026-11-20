// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.server.continuity.ContinuityService;
import io.github.jaeseongs95.sennard.server.continuity.StateCleanupService;
import io.github.jaeseongs95.sennard.server.updates.PluginUpdateService;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;

/**
 * What exists once in the whole server is made when the server starts, once the store is open, and a request only reads it: the keys that sign and correlate (continuity, the
 * workflow's plans, the state cleanup's plans) and the row of the plugin's update state. A request that had to make one would race the others for a row that has one key. Each is a
 * use case of its own service, in its own transaction; a key that already exists is left as it is. They are {@link StoreStartTask}s: the store is not ready, and a request is not let
 * in, before they have all finished.
 */
@Configuration(proxyBeanMethods = false)
class ServerStartTasks {
    // @Bean: each task is a lambda of the functional interface StoreStartTask over the service that owns the use case.
    @Bean
    StoreStartTask continuitySecret(ContinuityService continuity) {
        return continuity::prepareSecret;
    }

    @Bean
    StoreStartTask workflowSigningKey(WorkflowService workflows) {
        return workflows::prepareSigningKey;
    }

    @Bean
    StoreStartTask stateCleanupSigningKey(StateCleanupService cleanup) {
        return cleanup::prepareSigningKey;
    }

    @Bean
    StoreStartTask pluginUpdateState(PluginUpdateService updates) {
        return updates::prepare;
    }

    /**
     * The business database is ready for the first time: the section of the admin log before the link (in the settings database) takes no more records, and the signed log goes on
     * in the business database. Closing is idempotent, so a later start closes nothing again.
     */
    @Bean
    StoreStartTask preLinkClosure(PreLinkAdminRecords preLink) {
        return preLink::close;
    }

    /** The attachment folders and keys: made or checked as the owner's only, the first key made, and the key file of every key a current row points at found, before a request is let in. */
    @Bean
    StoreStartTask attachmentStorage(AttachmentMaintenance attachments) {
        return attachments::prepare;
    }

    /**
     * The comparison of the attachment folder with the rows (the design's 7), once at the start. It asks the rows one to three questions for every file of the folder, so it is a
     * {@link StoreReadyTask} and not a {@link StoreStartTask}: a request does not wait for it (the store is ready for requests when it runs), and a failure is logged without taking the
     * store down. The cleaner's minute does not do it; the interval ({@code sennard.attachments.reconcile-interval}) and {@code sennard attachments purge-orphans} do it again.
     */
    // @Bean: a method reference of the functional interface StoreReadyTask over the use of the upkeep that owns the comparison.
    @Bean
    StoreReadyTask attachmentFolderComparison(AttachmentMaintenance attachments) {
        return attachments::reconcileFolder;
    }
}
