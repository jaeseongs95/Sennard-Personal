// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.message.MessageRef;

/**
 * Records the files an edit tool changed in the change journals of the session's running workflows, at the host's event for a finished tool. The files are the ones the
 * host's adapter reads out of the call ({@link HostWorkFacts#editTargets}); a path outside a run's write roots, or a run still in its planning phase, is not that run's. What the
 * journal could not read, or could not write, comes back as lines for the hook's answer and is logged with its cause: a recording failure never fails the hook, and the next
 * stage result compares the write roots again, so a change the hook missed (a shell write it could not read) is still found.
 */
@Component
class JournalHookRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(JournalHookRecorder.class);

    private final ContinuityService continuity;
    private final ChangeJournalService journal;
    private final JournalScanner scanner;

    JournalHookRecorder(ContinuityService continuity, ChangeJournalService journal, JournalScanner scanner) {
        this.continuity = continuity;
        this.journal = journal;
        this.scanner = scanner;
    }

    /** The lines to show for what could not be recorded; empty when everything was, or when the call changed no file the hook can name. */
    List<MessageRef> record(HostEvent.ToolFinished finished, HostWorkFacts host, String session, String workspace) {
        List<Path> files = host.editTargets(finished.tool()).map(targets -> ProductEditTargets.resolved(workspace, targets.paths())).orElse(List.of());
        if (files.isEmpty()) {
            return List.of();
        }
        List<MessageRef> lines = new ArrayList<>();
        try {
            for (String runId : continuity.activeRunIds(session)) {
                if (journal.phase(runId).filter(phase -> phase == WorkflowPhase.EXECUTING).isEmpty()) {
                    continue;
                }
                for (Path file : files) {
                    recordIn(runId, file, lines);
                }
            }
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): the recording must not fail the hook's answer; the failure is logged with its cause and shown with its class.
            LOG.warn("The edit could not be looked up in the change journals of the session's workflows", failure);
            lines.add(MessageRef.of("hooks.changeJournal.failed", "path", files.getFirst().toString(), "cause", failure.getClass().getSimpleName()));
        }
        return lines;
    }

    private void recordIn(String runId, Path file, List<MessageRef> lines) {
        try {
            // Three steps, the scan between them outside every transaction: what the read starts from (empty: no write root of the run holds the file), the read, the write.
            journal.hookBasis(runId, file).ifPresent(basis -> {
                for (JournalScanner.ReadFailure failure : journal.recordHook(basis, scanner.scanHook(basis))) {
                    lines.add(MessageRef.of("hooks.changeJournal.unreadable", "path", failure.path().toString(), "reason", failure.reason().name()));
                }
            });
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): one run's journal failing must not keep the others from recording, nor fail the hook; logged with its cause and shown with its class.
            LOG.warn("The edit of {} could not be recorded in the change journal of run {}", file, runId, failure);
            lines.add(MessageRef.of("hooks.changeJournal.failed", "path", file.toString(), "cause", failure.getClass().getSimpleName()));
        }
    }
}
