// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;

/**
 * Which job of the attachment upkeep each start task of the server runs (design 4.3 and 7): the key and folder check, which a request must not be let in before, is a
 * {@link StoreStartTask}; the comparison of the folder with the rows, which looks at every file and which no request waits for, is a {@link StoreReadyTask}. The upkeep is an
 * {@link AttachmentMaintenance} that only records which of its jobs was asked for, because what each job does is tested where its rows and folders are (sennard-store-pg).
 *
 * <pre>
 * s1  start check     the store-start task of the attachments runs the start check ({@code prepare}) and nothing else
 * s2  start compare   the store-ready task of the attachments runs the comparison of the folder with the rows ({@code reconcileFolder}) and nothing else
 * </pre>
 */
class ServerStartTasksTest {
    /** The upkeep, with only the two jobs that are the start tasks' recorded; nothing else of it is used. */
    private static final class RecordingMaintenance extends AttachmentMaintenance {
        private final List<String> calls = new ArrayList<>();

        RecordingMaintenance() {
            super(null, null, null, null, null);
        }

        @Override
        public void prepare() {
            calls.add("prepare");
        }

        @Override
        public Reconciled reconcileFolder() {
            calls.add("reconcileFolder");
            return new Reconciled(0, 0);
        }
    }

    private final ServerStartTasks tasks = new ServerStartTasks();

    @Test
    void theStoreStartTaskOfTheAttachmentsRunsTheStartCheckAndNotTheComparison() {
        RecordingMaintenance maintenance = new RecordingMaintenance();

        tasks.attachmentStorage(maintenance).beforeStoreReady();

        assertEquals(List.of("prepare"), maintenance.calls);
    }

    @Test
    void theStoreReadyTaskOfTheAttachmentsRunsTheComparisonOfTheFolderAndNotTheStartCheck() {
        RecordingMaintenance maintenance = new RecordingMaintenance();

        tasks.attachmentFolderComparison(maintenance).whenStoreReady();

        assertEquals(List.of("reconcileFolder"), maintenance.calls);
    }
}
