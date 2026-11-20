// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;
import org.springframework.validation.annotation.Validated;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.Digests;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * The change journal of a workflow: its plan, the baseline of the files the plan may touch, and what changed after it. Every public method is one use case and one transaction (a
 * read-only one when it only reads), and the judgment is made inside it.
 *
 * <p>A file read must not hold a connection, so what needs a scan (the baseline, a rescan, a hook's file, a revised plan) is three steps of its caller: a read-only use case here
 * that returns what the scan starts from ({@link ScanBasis}, {@link HookBasis}), the scan ({@link JournalScanner}, no transaction), and one write use case here that is given the
 * scan's result. The write is judged by the conditional {@code UPDATE} of the journal's version ({@link JournalWorkflowRepository#advance}), which names the version and the state the
 * basis was read at: of the writers that were handed the same basis only the one whose statement moves the version on writes anything, and the others change no row and fail with
 * {@link OptimisticLockingFailureException} (the service boundary turns it into a {@code ConflictException}). The one that lost is not run again; its caller reads the journal anew.
 * The isolation level is the edition's setting, not a choice made here.
 */
@Service
@Validated
public class ChangeJournalService {
    public record Changes(Set<Path> plannedChanges, Set<Path> unplannedChanges, Set<Path> plannedUnchanged,
            Map<Path, Long> laterPlanRevisions, List<ChangeEvent> events, List<JournalScanner.ReadFailure> failures) { }
    public record ScanResult(Changes changes, List<JournalScanner.ReadFailure> failures) { }
    /** One file of the captured baseline; the hash is present only for a file that was planned when the baseline was captured. */
    public record BaselineEntry(Path path, long sizeBytes, Instant modifiedAt, String sha256) { }
    /** The latest plan revision of a workflow: its number, its files, and the roots the baseline was captured under (empty until it is). */
    public record PlanState(long revision, Set<Path> files, List<Path> writeRoots) { }

    /** The journal as the database has it, for the use cases that read all of it. */
    private record View(JournalWorkflow workflow, long revision, Map<Long, Set<Path>> plansByRevision, List<Path> roots, Map<Path, ObservedFile> observed,
            List<ChangeEvent> events, List<JournalScanner.ReadFailure> failures) { }

    /** What a write that records a scan changes: the files seen, the files gone, and the read failures the journal holds after it. */
    private record Comparison(Map<Path, JournalScanner.Metadata> changes, Set<Path> deletions, List<JournalScanner.ReadFailure> failures) { }

    private final JournalWorkflowRepository workflows;
    private final PlanRevisionRepository revisions;
    private final PlanFileRepository plans;
    private final BaselineFileRepository baselines;
    private final WriteRootRepository roots;
    private final ObservedFileRepository observed;
    private final ChangeEventRepository events;
    private final ScanFailureRepository failures;
    private final IdGenerator ids;
    private final Clock clock;
    private final OperationReceipts receipts;
    private final BaselineRows baselineRows;

    public ChangeJournalService(JournalWorkflowRepository workflows, PlanRevisionRepository revisions, PlanFileRepository plans, BaselineFileRepository baselines,
            WriteRootRepository roots, ObservedFileRepository observed, ChangeEventRepository events, ScanFailureRepository failures, IdGenerator ids, Clock clock,
            OperationReceipts receipts, BaselineRows baselineRows) {
        this.baselineRows = baselineRows;
        this.receipts = receipts;
        this.workflows = workflows;
        this.revisions = revisions;
        this.plans = plans;
        this.baselines = baselines;
        this.roots = roots;
        this.observed = observed;
        this.events = events;
        this.failures = failures;
        this.ids = ids;
        this.clock = clock;
    }

    // ----------------------------------------------------------------------------------------------------- reads

    /** The phase of the workflow's journal; empty when the workflow has no journal (nothing was planned for it). */
    @Transactional(readOnly = true)
    public Optional<WorkflowPhase> phase(@NotBlank String workflowId) {
        return workflows.findById(workflowId).map(workflow -> WorkflowPhase.of(workflow.baselineState()));
    }

    /** The baseline's files, ordered by path. */
    @Transactional(readOnly = true)
    public List<BaselineEntry> baseline(@NotBlank String workflowId) {
        return baselines.findByWorkflowId(workflowId).stream()
                .map(row -> new BaselineEntry(Path.of(row.path()), row.sizeBytes(), row.modifiedAt(), row.sha256()))
                .sorted(Comparator.comparing(entry -> entry.path().toString())).toList();
    }

    /** The latest observation of every file the journal has seen, by path; a null size is a deletion. Events carry no sequence, so a path's present state is read from here. */
    @Transactional(readOnly = true)
    public Map<Path, ObservedFile> observed(@NotBlank String workflowId) {
        return observedFiles(workflowId);
    }

    @Transactional(readOnly = true)
    public PlanState plan(@NotBlank String workflowId) {
        List<PlanRevision> all = revisions.findByWorkflowId(workflowId);
        PlanRevision latest = latest(all);
        return new PlanState(latest.revision(), files(latest), writeRoots(workflowId));
    }

    @Transactional(readOnly = true)
    public Changes changes(@NotBlank String workflowId) {
        return changesOf(view(workflowId));
    }

    /**
     * The first step of the baseline: the roots to walk and the files the plan names. The plan must lie under the roots; the baseline must not be captured yet.
     *
     * @throws IllegalArgumentException if a plan path is outside the roots
     * @throws IllegalStateException    if the baseline was captured already
     */
    @Transactional(readOnly = true)
    public ScanBasis baselineBasis(@NotBlank String workflowId, @NotEmpty Set<Path> writeRoots) {
        JournalWorkflow workflow = workflows.findById(workflowId).orElseThrow();
        List<Path> normalized = List.copyOf(normalize(writeRoots));
        PlanRevision latest = latest(revisions.findByWorkflowId(workflowId));
        Set<Path> plan = files(latest);
        validatePlanRoots(plan, normalized);
        if (workflow.baselineState() != BaselineState.EMPTY) {
            throw new IllegalStateException("Baseline already captured");
        }
        return new ScanBasis(workflowId, workflow.version(), normalized, plan, Map.of(), Set.of());
    }

    /**
     * The first step of a rescan: the roots of the baseline, the plan, and the last observation.
     *
     * @throws IllegalStateException if the baseline was not captured
     */
    @Transactional(readOnly = true)
    public ScanBasis rescanBasis(@NotBlank String workflowId) {
        return capturedBasis(workflowId);
    }

    /**
     * The first step of a revision of the plan after the baseline: as {@link #rescanBasis}, and the new plan must lie under the roots (refused before any file is read).
     *
     * @throws IllegalArgumentException if a plan path is outside the roots
     * @throws IllegalStateException    if the baseline was not captured
     */
    @Transactional(readOnly = true)
    public ScanBasis revisionBasis(@NotBlank String workflowId, @NotNull Set<Path> paths) {
        ScanBasis basis = capturedBasis(workflowId);
        validatePlanRoots(normalize(paths), basis.roots());
        return basis;
    }

    /** The first step of one hook observation; empty when no write root of the workflow holds the file (a journal whose baseline is not captured has no roots). */
    @Transactional(readOnly = true)
    public Optional<HookBasis> hookBasis(@NotBlank String workflowId, @NotNull Path file) {
        JournalWorkflow workflow = workflows.findById(workflowId).orElseThrow();
        Path path = normalized(file);
        return writeRoots(workflowId).stream().filter(path::startsWith).findFirst()
                .map(root -> new HookBasis(workflowId, workflow.version(), root, path, previousMetadata(observedFiles(workflowId))));
    }

    private ScanBasis capturedBasis(String workflowId) {
        JournalWorkflow workflow = workflows.findById(workflowId).orElseThrow();
        if (workflow.baselineState() != BaselineState.CAPTURED) {
            throw new IllegalStateException("Baseline has not been captured");
        }
        Map<Path, ObservedFile> seen = observedFiles(workflowId);
        Set<Path> failed = readFailures(workflowId).stream().map(JournalScanner.ReadFailure::path).collect(Collectors.toUnmodifiableSet());
        return new ScanBasis(workflowId, workflow.version(), writeRoots(workflowId), files(latest(revisions.findByWorkflowId(workflowId))), previousMetadata(seen), failed);
    }

    // ---------------------------------------------------------------------------------------------------- writes

    /**
     * The plan of a journal whose baseline is not captured: the first plan creates the journal (two first plans of one workflow at the same moment meet at its primary key and the
     * second fails with the duplicate), a later one adds a revision.
     *
     * @return the receipt of the plan: its revision number (and the answer the first call gave, when this is a request answered from the receipt of a call that finished)
     * @throws OptimisticLockingFailureException if the journal changed after this call read it, or its baseline is captured (a plan after the baseline is {@link #revisePlan})
     */
    @Transactional
    public PlanReceipt submitPlan(@NotBlank String workflowId, @NotNull Set<Path> paths, OperationKey operation) {
        return receipts.once(operation, PlanReceipt.class, () -> new PlanReceipt(submitPlan(workflowId, paths), null)).value();
    }

    /**
     * The plan of a journal whose baseline is captured, once per operationKey (AGS3-D-0267): the receipt keeps the plan's revision number ({@link PlanReceipt}), and is written in this
     * transaction after the revision. The digest of the request is the tool's input; the scan in {@code found} is not part of it.
     */
    @Transactional
    public PlanReceipt revisePlan(@NotNull ScanBasis basis, @NotNull JournalScanner.Rescan found, @NotNull Set<Path> paths, OperationKey operation) {
        return receipts.once(operation, PlanReceipt.class, () -> new PlanReceipt(revisePlan(basis, found, paths), null)).value();
    }

    /**
     * The first plan of a run, written inside the transaction of another use case: {@code WorkflowService.startMinimal} writes the journal row of a run it starts in its own transaction,
     * which already keeps the receipt of the start tool's operationKey, so a second receipt here would be written for the same operation. It is no entry of a tool (a tool's plan comes
     * through the keyed {@code submitPlan} and {@code revisePlan}); it joins the transaction it is called in, and is kept public only because that use case is in another package.
     */
    @Transactional
    public long submitPlan(@NotBlank String workflowId, @NotNull Set<Path> paths) {
        Set<Path> normalized = normalize(paths);
        return workflows.findById(workflowId)
                .map(workflow -> reviseBeforeBaseline(workflow, normalized))
                .orElseGet(() -> firstPlan(workflowId, normalized));
    }

    private long firstPlan(String workflowId, Set<Path> paths) {
        workflows.save(new JournalWorkflow(workflowId, BaselineState.EMPTY, null));
        return savePlan(workflowId, paths, 1);
    }

    private long reviseBeforeBaseline(JournalWorkflow workflow, Set<Path> paths) {
        String workflowId = workflow.workflowId();
        if (workflow.baselineState() != BaselineState.EMPTY) {
            throw new OptimisticLockingFailureException("The baseline of the change journal was captured after the caller read its phase: " + workflowId);
        }
        advance(workflowId, BaselineState.EMPTY, BaselineState.EMPTY, workflow.version());
        return savePlan(workflowId, paths, latest(revisions.findByWorkflowId(workflowId)).revision() + 1);
    }

    /**
     * The work of the keyed revisePlan above (it has no transaction of its own and is no entry): the scan of its roots is recorded first (a change seen before the new revision belongs to the plan that was current), and
     * a plan whose roots could not be read is refused with nothing written, because what is unreadable may be changed.
     *
     * @return the revision number of the plan
     * @throws IllegalStateException             if a path could not be read (the first one is the cause, the others are suppressed)
     * @throws IllegalArgumentException          if a plan path is outside the roots
     * @throws OptimisticLockingFailureException if the journal changed after {@code basis} was read
     */
    private long revisePlan(ScanBasis basis, JournalScanner.Rescan found, Set<Path> paths) {
        String workflowId = basis.workflowId();
        Set<Path> normalized = normalize(paths);
        advance(workflowId, BaselineState.CAPTURED, BaselineState.CAPTURED, basis.version());
        validatePlanRoots(normalized, writeRoots(workflowId));
        Map<Path, ObservedFile> before = observedFiles(workflowId);
        Comparison comparison = compare(before, readFailures(workflowId), found);
        List<JournalScanner.ReadFailure> unreadable = comparison.failures().stream()
                .filter(failure -> failure.reason() != JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED).toList();
        if (!unreadable.isEmpty()) {
            JournalScanner.ReadFailure first = unreadable.getFirst();
            IllegalStateException error = new IllegalStateException("The previous plan could not be scanned before revision: "
                    + first.path() + " (" + first.reason() + ")", first.cause());
            unreadable.stream().skip(1).forEach(failure -> error.addSuppressed(new IllegalStateException(
                    failure.path() + " (" + failure.reason() + ")", failure.cause())));
            throw error;
        }
        long revision = latest(revisions.findByWorkflowId(workflowId)).revision();
        recordChanges(workflowId, revision, before, comparison, ChangeSource.RESCAN);
        return savePlan(workflowId, normalized, revision + 1);
    }

    /**
     * Captures the baseline from the first scan of the roots. A scan that could not read something leaves no partial baseline and writes nothing; the failures come back.
     *
     * @return what the scan could not read (only skipped links when the baseline was captured)
     * @throws OptimisticLockingFailureException if the journal changed after {@code basis} was read
     */
    @Transactional
    public List<JournalScanner.ReadFailure> captureBaseline(@NotNull ScanBasis basis, @NotNull JournalScanner.Scan scan) {
        if (!scan.failures().stream().allMatch(failure -> failure.reason() == JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED)) {
            return scan.failures();
        }
        String workflowId = basis.workflowId();
        advance(workflowId, BaselineState.EMPTY, BaselineState.CAPTURED, basis.version());
        basis.roots().forEach(path -> roots.save(new WriteRoot(id(), workflowId, path.toString(), pathHash(path), null)));
        // Batches, not a statement for each file: a workspace has tens of thousands of them, and this transaction has the edition's time (spring.transaction.default-timeout).
        baselineRows.insert(workflowId, scan.files(), this::id);
        storeFailures(workflowId, scan.failures());
        return scan.failures();
    }

    /**
     * Records one hook observation, including a deletion of a previously observed file. What the file read could not read comes back, and is kept (only for that file) until a later
     * read of it succeeds.
     *
     * @throws OptimisticLockingFailureException if the journal changed after {@code basis} was read
     */
    @Transactional
    public List<JournalScanner.ReadFailure> recordHook(@NotNull HookBasis basis, @NotNull JournalScanner.Scan scan) {
        String workflowId = basis.workflowId();
        Path path = basis.file();
        advance(workflowId, BaselineState.CAPTURED, BaselineState.CAPTURED, basis.version());
        Map<Path, ObservedFile> before = observedFiles(workflowId);
        ObservedFile previous = before.get(path);
        Map<Path, JournalScanner.Metadata> changes = Map.of();
        Set<Path> deletions = Set.of();
        List<JournalScanner.ReadFailure> unread = List.of();
        if (scan.failures().size() == 1 && scan.failures().getFirst().reason() == JournalScanner.FailureReason.NOT_FOUND && previous != null && previous.sizeBytes() != null) {
            deletions = Set.of(path);
        } else if (scan.failures().isEmpty() && scan.files().containsKey(path)) {
            changes = scan.files();
        } else if (scan.failures().isEmpty()) {
            unread = List.of(new JournalScanner.ReadFailure(path, JournalScanner.FailureReason.NOT_A_REGULAR_FILE));
        } else {
            unread = scan.failures().stream().map(failure -> new JournalScanner.ReadFailure(path, failure.reason(), failure.cause())).toList();
        }
        List<JournalScanner.ReadFailure> retained = new ArrayList<>(readFailures(workflowId));
        List<JournalScanner.ReadFailure> now = unread;
        retained.removeIf(failure -> failure.path().equals(path) || now.stream().anyMatch(next -> next.path().equals(failure.path())));
        retained.addAll(unread);
        recordChanges(workflowId, latest(revisions.findByWorkflowId(workflowId)).revision(), before, new Comparison(changes, deletions, retained), ChangeSource.HOOK);
        return unread;
    }

    /**
     * Records a rescan, compared with the last observation so that a hook and a following rescan do not duplicate a change.
     *
     * @throws OptimisticLockingFailureException if the journal changed after {@code basis} was read
     */
    @Transactional
    public ScanResult recordRescan(@NotNull ScanBasis basis, @NotNull JournalScanner.Rescan found) {
        String workflowId = basis.workflowId();
        advance(workflowId, BaselineState.CAPTURED, BaselineState.CAPTURED, basis.version());
        Map<Path, ObservedFile> before = observedFiles(workflowId);
        Comparison comparison = compare(before, readFailures(workflowId), found);
        recordChanges(workflowId, latest(revisions.findByWorkflowId(workflowId)).revision(), before, comparison, ChangeSource.RESCAN);
        return new ScanResult(changesOf(view(workflowId)), comparison.failures());
    }

    // ------------------------------------------------------------------------------------------------ judgments

    /**
     * What a rescan changes: a file the last observation has and the walk does not find is deleted, unless a read failure covers it (what cannot be read is not reported as
     * deleted); a failure of the last scan is kept until the path is read again, replaced by a new failure, found to be gone, or its directory is walked again. A skipped link or
     * special file is not a read failure of what the last observation had under that path: a link is never followed, so the files that were there are not there now and are deleted
     * (the link itself stays reported as skipped). AGS3-D-0259: the folder or file that a junction or a symbolic link took the place of was hidden by the skipped link.
     */
    private static Comparison compare(Map<Path, ObservedFile> before, List<JournalScanner.ReadFailure> previousFailures, JournalScanner.Rescan found) {
        JournalScanner.Scan scan = found.scan();
        Set<Path> deletions = previousMetadata(before).keySet().stream().filter(path -> !scan.files().containsKey(path))
                .filter(path -> scan.failures().stream().noneMatch(failure -> failure.reason() != JournalScanner.FailureReason.LINK_OR_SPECIAL_SKIPPED && path.startsWith(failure.path())))
                .collect(Collectors.toUnmodifiableSet());
        List<JournalScanner.ReadFailure> kept = new ArrayList<>(scan.failures());
        for (JournalScanner.ReadFailure previousFailure : previousFailures) {
            Path path = previousFailure.path();
            boolean coveredByFailure = scan.failures().stream().anyMatch(failure -> path.startsWith(failure.path()));
            boolean replaced = scan.failures().stream().anyMatch(failure -> path.equals(failure.path()));
            boolean deleted = !coveredByFailure && found.absentFailedPaths().contains(path);
            boolean directoryRecovered = previousFailure.reason() != JournalScanner.FailureReason.NOT_A_REGULAR_FILE
                    && scan.scannedDirectories().contains(path) && !coveredByFailure;
            if (!replaced && !scan.files().containsKey(path) && !deleted && !directoryRecovered) {
                kept.add(previousFailure);
            }
        }
        return new Comparison(scan.files(), deletions, List.copyOf(kept));
    }

    private static Map<Path, JournalScanner.Metadata> previousMetadata(Map<Path, ObservedFile> seen) {
        Map<Path, JournalScanner.Metadata> previous = new HashMap<>();
        seen.forEach((path, row) -> {
            if (row.sizeBytes() != null) {
                previous.put(path, new JournalScanner.Metadata(row.sizeBytes(), row.modifiedAt(), row.sha256()));
            }
        });
        return previous;
    }

    private Changes changesOf(View view) {
        Set<Path> changed = new HashSet<>();
        Set<Path> plannedChanges = new HashSet<>();
        Set<Path> unplannedChanges = new HashSet<>();
        Map<Path, Long> laterPlans = new HashMap<>();
        for (ChangeEvent event : view.events()) {
            Path path = Path.of(event.path());
            changed.add(path);
            if (view.plansByRevision().get(event.planRevision()).contains(path)) {
                plannedChanges.add(path);
            } else {
                unplannedChanges.add(path);
                view.plansByRevision().forEach((revision, files) -> {
                    if (revision > event.planRevision() && files.contains(path)) {
                        laterPlans.merge(path, revision, Math::min);
                    }
                });
            }
        }
        Set<Path> unchanged = new HashSet<>(view.plansByRevision().get(view.revision()));
        unchanged.removeAll(changed);
        unchanged.removeIf(path -> view.failures().stream().anyMatch(failure -> path.startsWith(failure.path())));
        return new Changes(Set.copyOf(plannedChanges), Set.copyOf(unplannedChanges), Set.copyOf(unchanged), Map.copyOf(laterPlans), view.events(), view.failures());
    }

    private void validatePlanRoots(Set<Path> paths, List<Path> writeRoots) {
        List<Path> outside = paths.stream().filter(path -> writeRoots.stream().noneMatch(path::startsWith)).sorted().toList();
        if (!outside.isEmpty()) {
            throw new IllegalArgumentException("Plan paths outside write roots: " + outside);
        }
    }

    // ----------------------------------------------------------------------------------------------- the tables

    /**
     * The journal as the database has it, read in an order that needs no snapshot: the workflow first (a write that follows is refused by the version read here when anything
     * changed after it, whatever the later reads saw), then the events, then the plan revisions. A plan revision is only ever added, and an event names a revision that existed
     * when the event was written, so every revision an event names is among the revisions read after it, whichever writer commits in between. The reads of the observed files,
     * the roots and the failures hold no reference to the others.
     */
    private View view(String workflowId) {
        JournalWorkflow workflow = workflows.findById(workflowId).orElseThrow();
        List<ChangeEvent> changeEvents = List.copyOf(events.findByWorkflowId(workflowId));
        List<JournalScanner.ReadFailure> readFailures = readFailures(workflowId);
        List<PlanRevision> allRevisions = revisions.findByWorkflowId(workflowId);
        PlanRevision latest = latest(allRevisions);
        Map<Long, Set<Path>> plansByRevision = allRevisions.stream().collect(Collectors.toUnmodifiableMap(PlanRevision::revision, this::files));
        return new View(workflow, latest.revision(), plansByRevision, writeRoots(workflowId), observedFiles(workflowId), changeEvents, readFailures);
    }

    private static PlanRevision latest(List<PlanRevision> all) {
        return all.stream().max(Comparator.comparingLong(PlanRevision::revision)).orElseThrow();
    }

    private Set<Path> files(PlanRevision revision) {
        return plans.findByPlanRevisionId(revision.planRevisionId()).stream().map(row -> Path.of(row.path())).collect(Collectors.toUnmodifiableSet());
    }

    private List<Path> writeRoots(String workflowId) {
        return roots.findByWorkflowId(workflowId).stream().map(row -> Path.of(row.path())).toList();
    }

    private Map<Path, ObservedFile> observedFiles(String workflowId) {
        return observed.findByWorkflowId(workflowId).stream().collect(Collectors.toUnmodifiableMap(row -> Path.of(row.path()), Function.identity()));
    }

    private List<JournalScanner.ReadFailure> readFailures(String workflowId) {
        return failures.findByWorkflowId(workflowId).stream()
                .map(row -> new JournalScanner.ReadFailure(Path.of(row.path()), JournalScanner.FailureReason.valueOf(row.reason()))).toList();
    }

    private long savePlan(String workflowId, Set<Path> paths, long revision) {
        String revisionId = id();
        revisions.save(new PlanRevision(revisionId, workflowId, revision, now(), null));
        paths.forEach(path -> plans.save(new PlanFile(id(), revisionId, path.toString(), pathHash(path), null)));
        return revision;
    }

    /** The writes of a recorded scan or hook: each changed file is an observation and, unless only its hash was learned, an event of the plan revision that is current; then the failures. */
    private void recordChanges(String workflowId, long revision, Map<Path, ObservedFile> before, Comparison comparison, ChangeSource source) {
        comparison.changes().forEach((path, metadata) -> writeChange(workflowId, revision, before.get(path), path, metadata, source));
        comparison.deletions().forEach(path -> writeChange(workflowId, revision, before.get(path), path, null, source));
        storeFailures(workflowId, comparison.failures());
    }

    private void writeChange(String workflowId, long revision, ObservedFile before, Path path, JournalScanner.Metadata metadata, ChangeSource source) {
        Long size = metadata == null ? null : metadata.size();
        Instant modified = metadata == null ? null : metadata.modifiedAt();
        String hash = metadata == null ? null : metadata.sha256();
        if (before != null && Objects.equals(before.sizeBytes(), size) && Objects.equals(before.modifiedAt(), modified) && Objects.equals(before.sha256(), hash)) {
            return;
        }
        boolean hashOnly = before != null && before.sha256() == null && hash != null && Objects.equals(before.sizeBytes(), size) && Objects.equals(before.modifiedAt(), modified);
        observed.save(new ObservedFile(before == null ? id() : before.observedFileId(), workflowId, path.toString(), pathHash(path), size, modified, hash,
                before == null ? null : before.version()));
        if (!hashOnly) {
            events.save(new ChangeEvent(id(), workflowId, revision, path.toString(), pathHash(path), hash, size, modified, now(), source, null));
        }
    }

    private void storeFailures(String workflowId, List<JournalScanner.ReadFailure> readFailures) {
        failures.deleteByWorkflowId(workflowId);
        readFailures.forEach(failure -> failures.save(new ScanFailure(id(), workflowId, failure.path().toString(), failure.reason().name(), null)));
    }

    /**
     * The journal's one compare-and-set: the statement moves the version on only while the journal is in {@code expected} at {@code version}, the version and state the caller read.
     * Of the writers that were handed the same read the first one's statement changes the row and the others' find it moved (their transaction waited on the row, and the
     * {@code WHERE} is judged again after the first one's commit). Nothing is written before it, so a write based on an older read is refused whole.
     */
    private void advance(String workflowId, BaselineState expected, BaselineState next, long version) {
        if (workflows.advance(workflowId, expected.name(), next.name(), version) != 1) {
            throw new OptimisticLockingFailureException("The change journal changed after it was read: " + workflowId);
        }
    }

    private Set<Path> normalize(Set<Path> paths) {
        return paths.stream().map(ChangeJournalService::normalized).collect(Collectors.toUnmodifiableSet());
    }

    /** The spelling of a path the journal and its scan use: absolute and normalized. */
    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private String id() {
        return ids.generateId().toString();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String pathHash(Path path) {
        return Digests.sha256Hex(path.toString());
    }
}
