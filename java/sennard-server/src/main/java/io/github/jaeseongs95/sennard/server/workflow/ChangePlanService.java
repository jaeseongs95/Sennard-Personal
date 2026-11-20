// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.sennard.app.journal.ChangeJournalService;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner;
import io.github.jaeseongs95.sennard.app.journal.PlanReceipt;
import io.github.jaeseongs95.sennard.app.journal.ScanBasis;
import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools' side of a run's change journal: opening the executing phase from a plan, revising the plan, and reading what changed. It opens no transaction: it reads files, and
 * a file read must not hold a connection. What needs a scan is done here in three steps: a read-only use case of {@link ChangeJournalService} that gives what the scan starts from,
 * the scan ({@link JournalScanner}), and one write use case of the journal that is given its result.
 */
@Service
class ChangePlanService {
    private final WorkflowService workflows;
    private final ChangeJournalService journal;
    private final JournalScanner scanner;
    private final ChangeJournalScope scopes;
    private final ToolInputs inputs;
    private final WorkflowReceipts operations;
    private final JsonMapper mapper;
    private final PlaceholderTexts placeholders;

    ChangePlanService(WorkflowService workflows, ChangeJournalService journal, JournalScanner scanner, ChangeJournalScope scopes, ToolInputs inputs, WorkflowReceipts operations, JsonMapper mapper, PlaceholderTexts placeholders) {
        this.placeholders = placeholders;
        this.mapper = mapper;
        this.operations = operations;
        this.workflows = workflows;
        this.journal = journal;
        this.scanner = scanner;
        this.scopes = scopes;
        this.inputs = inputs;
    }

    /** What {@code submit_change_plan} answers: the run's phase after the plan, the plan's revision and its files. */
    record Submitted(String schemaVersion, String runId, long revision, WorkflowPhase phase, long planRevision, List<String> files, List<String> unreadable) {
    }

    /**
     * What the journal saw when it compared the write roots with the last observation: how many planned files changed and did not, the files that changed outside the plan, and
     * the paths it could not read (their change is unknown, not absent).
     */
    record Observation(int plannedChanged, int plannedUnchanged, List<String> unplanned, List<String> unreadable) {
    }

    /**
     * Before the start's transaction: the first walk of the write roots of a start that has a change plan, so that no file is read inside that transaction. The start writes the rows
     * of this walk with the run ({@link WorkflowService#startMinimalWorkflow}), and a failure of the start leaves nothing. Empty for a start with no change plan.
     */
    WorkflowService.StartBaseline startBaseline(JsonNode arguments, WorkflowCall call) {
        return workflows.planStartBaseline(arguments, call)
                .<WorkflowService.StartBaseline>map(scope -> new WorkflowService.StartBaseline.Taken(scope, scanner.scanBaseline(scope.roots(), scope.plan())))
                .orElseGet(WorkflowService.StartBaseline.None::new);
    }

    /**
     * {@code submit_change_plan}: a first plan opens the executing phase, a later one revises the plan and is kept as its own revision. The operationKey (AGS3-D-0267) is kept by the
     * use case that writes the plan, in its own transaction; the file reads and the scans stay outside every transaction, and the digest of the request is the tool's input only (no
     * scan result is in it). The receipt first holds the plan's revision number, and the call, once it has its answer, completes the receipt with that answer
     * ({@link WorkflowReceipts#complete}), so a request that comes again after the call finished is answered with the first answer as it was given, not made again from the run as it is
     * now. A request that comes again after the plan was written but before the call finished (it stopped at the baseline) takes the stored revision, does not write the plan again,
     * reads the baseline, and completes the receipt itself.
     */
    Submitted submit(JsonNode arguments, OperationKey operation) {
        RunRequest.Plan input = inputs.read("submit_change_plan", arguments, RunRequest.Plan.class);
        // The refusal of a planning-phase edit shows the files as a placeholder when it cannot name them; a plan that sends it back lists no file of this work.
        placeholders.assertNone("files", PlaceholderTexts.Need.PATH, input.files());
        Optional<PlanReceipt> written = operations.replay(operation, PlanReceipt.class);
        if (written.isPresent() && written.get().answered()) {
            return mapper.treeToValue(written.get().answer(), Submitted.class);
        }
        WorkflowService.RunScope run = workflows.runScope(input.id(), written.isPresent() ? null : input.expectedRevision());
        Set<Path> plan = scopes.plan(run.scope(), input.files(), "files");
        long planRevision = written.isPresent() ? written.get().planRevision() : revise(run.runId(), plan, operation);
        List<String> unreadable = List.of();
        if (journal.phase(run.runId()).orElseThrow() == WorkflowPhase.PLANNING) {
            unreadable = capture(run.runId(), run.scope());
            if (journal.phase(run.runId()).orElseThrow() == WorkflowPhase.PLANNING) {
                throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.journal.baselineUnreadable", "paths", String.join(", ", unreadable)),
                        details("reason", "BASELINE_UNREADABLE", "unreadable", unreadable, "planRevision", planRevision));
            }
        }
        Submitted submitted = new Submitted(ContractValidator.CONTRACT_VERSION, run.runId(), run.revision(), journal.phase(run.runId()).orElseThrow(), planRevision, input.files(), unreadable);
        operations.complete(operation, new PlanReceipt(planRevision, mapper.valueToTree(submitted)));
        return submitted;
    }

    /** How many of the files outside the plan a notice and a refusal name; the refusal's details carry all of them. */
    static final int UNPLANNED_SHOWN = 20;

    /**
     * The one judgment of what the journal found, for the notice after a stage result or a finish and for the refusal of a finish ({@code finalize_workflow}): empty for a run that has no
     * executing journal (nothing was planned for it). A change outside the plan is unplanned until a later revision of the plan names its file ({@code laterPlanRevisions}): the plan that
     * was current when the file changed did not, and {@code submit_change_plan} is how the caller answers for it, so a file a later plan took in is no longer found outside the plan.
     */
    Optional<Observation> observe(String runId) {
        if (journal.phase(runId).filter(phase -> phase == WorkflowPhase.EXECUTING).isEmpty()) {
            return Optional.empty();
        }
        ScanBasis basis = journal.rescanBasis(runId);
        ChangeJournalService.ScanResult scan = journal.recordRescan(basis, scanner.rescan(basis));
        ChangeJournalService.Changes changes = scan.changes();
        List<String> unplanned = changes.unplannedChanges().stream().filter(path -> !changes.laterPlanRevisions().containsKey(path)).map(Path::toString).sorted().toList();
        return Optional.of(new Observation(changes.plannedChanges().size(), changes.plannedUnchanged().size(), unplanned, scan.failures().stream().map(ChangePlanService::describe).sorted().toList()));
    }

    private long revise(String runId, Set<Path> plan, OperationKey operation) {
        try {
            if (journal.phase(runId).filter(phase -> phase == WorkflowPhase.EXECUTING).isEmpty()) {
                return journal.submitPlan(runId, plan, operation).planRevision();
            }
            ScanBasis basis = journal.revisionBasis(runId, plan);
            return journal.revisePlan(basis, scanner.rescan(basis), plan, operation).planRevision();
        } catch (IllegalStateException unscannable) {
            throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.journal.planRescanFailed", "detail", String.valueOf(unscannable.getMessage())),
                    details("reason", "PLAN_RESCAN_FAILED"), unscannable);
        } catch (ConflictException concurrent) {
            // The conflict of the operationKey (the same request raced and met the first one's receipt) is the caller's to see as it is; only another conflict is the journal that changed.
            if (concurrent.isOperationKey()) {
                throw concurrent;
            }
            // The journal changed after it was read: this call lost (the service boundary turned the failed conditional UPDATE into the conflict) and is not run again.
            throw new WorkflowFailure(WorkflowCode.REQUEST_CONFLICT, msg("workflow.journal.changedDuringScan"), details("reason", "JOURNAL_CHANGED_DURING_SCAN"), concurrent);
        }
    }

    private List<String> capture(String runId, ChangeJournalScope.Scope scope) {
        ScanBasis basis = journal.baselineBasis(runId, new LinkedHashSet<>(scope.roots()));
        return journal.captureBaseline(basis, scanner.scanBaseline(basis)).stream().map(ChangePlanService::describe).sorted().toList();
    }

    static String describe(JournalScanner.ReadFailure failure) {
        return failure.path() + " (" + failure.reason() + ")";
    }
}
