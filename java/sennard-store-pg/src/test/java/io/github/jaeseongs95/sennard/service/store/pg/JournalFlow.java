// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.ScanBasis;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;

/**
 * The journal as its caller uses it: each call that needs a scan is the three steps of the callers of the product (a read-only use case of {@link ChangeJournalService}, the scan
 * of {@link JournalScanner} outside every transaction, one write use case), with nothing in between that judges. A case that needs the steps apart (a write whose basis is out
 * of date) calls the service and the scanner itself.
 */
final class JournalFlow {
    private final ChangeJournalService journal;
    private final JournalScanner scanner;

    JournalFlow(ChangeJournalService journal, JournalScanner scanner) {
        this.journal = journal;
        this.scanner = scanner;
    }

    static JournalFlow of(RewrittenAreasContext context) {
        return new JournalFlow(context.bean(ChangeJournalService.class), context.bean(JournalScanner.class));
    }

    ChangeJournalService service() {
        return journal;
    }

    JournalScanner scanner() {
        return scanner;
    }

    /** The plan of a journal: a revision of one whose baseline is captured is recorded with a scan of its roots first. */
    long submitPlan(String workflowId, Set<Path> paths) {
        if (journal.phase(workflowId).filter(phase -> phase == WorkflowPhase.EXECUTING).isEmpty()) {
            return journal.submitPlan(workflowId, paths);
        }
        ScanBasis basis = journal.revisionBasis(workflowId, paths);
        return journal.revisePlan(basis, scanner.rescan(basis), paths, Operations.fresh("submit_change_plan", workflowId)).planRevision();
    }

    List<JournalScanner.ReadFailure> captureBaseline(String workflowId, Set<Path> roots) {
        ScanBasis basis = journal.baselineBasis(workflowId, roots);
        return journal.captureBaseline(basis, scanner.scanBaseline(basis));
    }

    /** One hook observation; a file that no write root holds is refused. */
    List<JournalScanner.ReadFailure> recordHook(String workflowId, Path file) {
        return journal.hookBasis(workflowId, file).map(basis -> journal.recordHook(basis, scanner.scanHook(basis)))
                .orElseThrow(() -> new IllegalArgumentException("Hook path is outside the write roots"));
    }

    ChangeJournalService.ScanResult rescan(String workflowId) {
        ScanBasis basis = journal.rescanBasis(workflowId);
        return journal.recordRescan(basis, scanner.rescan(basis));
    }

    ChangeJournalService.Changes changes(String workflowId) {
        return journal.changes(workflowId);
    }

    List<ChangeJournalService.BaselineEntry> baseline(String workflowId) {
        return journal.baseline(workflowId);
    }

    Optional<WorkflowPhase> phase(String workflowId) {
        return journal.phase(workflowId);
    }
}
