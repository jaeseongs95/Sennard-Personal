// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;
import static io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.pathWithin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Identity;
import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Surface;
import tools.jackson.databind.json.JsonMapper;

/** The pure rules of convergence roots: digests of their inputs and which active root blocks a new one. Two roots conflict only when their physical write locations overlap. */
final class ConvergenceLogic {
    private static final Logger LOG = LoggerFactory.getLogger(ConvergenceLogic.class);

    record Stored(Identity identity, String surfaceDigest) {
    }

    /** {@code fresh}: the identity was derived now (none was stored, or the stored one belongs to other inputs), so it is saved. */
    record Identified(ConvergenceRoot root, Identity identity, boolean fresh, String surfaceDigest) {
    }

    record Conflict(ConvergenceRoot root, Surface requested, Surface existing) {
    }

    /** {@code surfaceDigest}: of the inputs the identity was derived from. {@code surfaceKey}: of where the root writes ({@link #surfaceKey}), the value of the unique column. */
    record Plan(Identity identity, String surfaceDigest, String surfaceKey, Conflict conflict) {
    }

    private ConvergenceLogic() {
    }

    /**
     * What a failure is told apart by: the state the run ended in, the last stage that recorded a result, the error's code and what stayed blocked or
     * unresolved. Every key is written, as {@code null} when it does not apply, because the digest of it is what a retry must name.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record FailureFingerprint(RunState state, String stage, String error, List<String> blockers, List<String> unresolved) {
    }

    /** The place and the entries a root writes to: the identity of its write surface is judged by their digest. */
    record WriteSurface(String locator, List<String> entries) {
    }

    /** Fingerprint of a non-passing receipt; a retry must bind the latest one. */
    static String failureFingerprint(DocumentDigests digests, WorkflowReceipt receipt) {
        List<StageResult> results = receipt.stageResults();
        return digests.of(new FailureFingerprint(receipt.state(), results.isEmpty() ? null : results.getLast().stageId(), receipt.error() == null ? null : receipt.error().code().name(),
                receipt.blockers(), receipt.unresolved()));
    }

    /**
     * Where a root's locations are physically resolved. The resolution reads the file system (real paths), so a use case in a transaction does not call it: the entry resolves before
     * the transaction ({@link WorkflowFileReader}) and the use case's resolver answers with that. A test of the rules passes {@link WorkspaceIdentity#resolveRootIdentity}.
     */
    @FunctionalInterface
    interface IdentityResolver {
        Identity resolve(String workspaceLocator, List<String> entries, boolean physicalOnly);
    }

    static List<String> writeSurface(ConvergenceRoot root) {
        return writeSurface(root.taskEnvelope(), root.frame());
    }

    /** The entries a task and its frame write to, in the order the identity of the surface is made over them. */
    static List<String> writeSurface(TaskEnvelope task, ConvergenceFrame frame) {
        List<String> entries = new ArrayList<>(task.scope().included());
        task.workUnits().forEach(unit -> entries.addAll(unit.writeTargets()));
        frame.targetArtifacts().forEach(artifact -> entries.add(artifact.locator()));
        return entries;
    }

    /** Whether the identity of a stored root has to be derived again: none was stored, or the stored one belongs to other inputs. */
    static boolean needsDerivation(DocumentDigests digests, ConvergenceRoot root, Stored stored) {
        return stored == null || !Objects.equals(stored.surfaceDigest(), surfaceDigest(digests, root));
    }

    /** Digest of the inputs as written: it tells whether a stored identity was derived from the root's present inputs. It is not where the root writes: see {@link #surfaceKey}. */
    static String surfaceDigest(DocumentDigests digests, ConvergenceRoot root) {
        return digests.of(new WriteSurface(root.frame().workspace().locator(), writeSurface(root)));
    }

    /** What {@link #surfaceKey} is made over: the host and the physical locations, sorted and without repeats. */
    record SurfaceKey(String host, List<String> locations) {
    }

    /**
     * The key by which two active roots that write the same places are told apart by the database: the digest of the host and of the physical locations of the resolved identity
     * ({@link WorkspaceIdentity}), sorted and without repeats, so it does not depend on the order, the spelling or the duplicates of the entries. It carries the host because the
     * check of overlapping roots looks at one host only ({@link WorkspaceIdentity#sameHost}): the same place on two hosts is two places. A root with no host is a value of its own
     * (null): it holds the key against a root with no host only, where the check also lets it block every host. Equal keys therefore always overlap in
     * that check, so the key never refuses what the check would let through.
     */
    static String surfaceKey(DocumentDigests digests, Identity identity) {
        Set<String> locations = new TreeSet<>();
        for (Surface surface : identity.surfaces()) {
            locations.add(surface.physical().toString());
        }
        return digests.of(new SurfaceKey(identity.host(), List.copyOf(locations)));
    }

    /**
     * Identity of a stored root: what was stored for the same inputs stays; otherwise it is derived again, keeping the host the root was first stored with
     * (deriving on another machine never claims it). A location that cannot be read is taken as written, so an unreadable root keeps blocking here.
     */
    static Identified activeRootIdentity(DocumentDigests digests, ConvergenceRoot root, Stored stored, IdentityResolver resolver) {
        String digest = surfaceDigest(digests, root);
        if (!needsDerivation(digests, root, stored)) {
            return new Identified(root, stored.identity(), false, digest);
        }
        String locator = root.frame().workspace().locator();
        Identity derived;
        try {
            derived = resolver.resolve(locator, writeSurface(root), false);
        } catch (WorkflowFailure unreadable) {
            LOG.debug("The write surface of the root's workspace {} cannot be read on this host; its locations are taken as written", locator, unreadable);
            derived = resolver.resolve(locator, writeSurface(root), true);
        }
        return new Identified(root, derived.withHost(stored == null ? null : stored.identity().host()), true, digest);
    }

    private static boolean overlaps(Path left, Path right) {
        return pathWithin(left, right) || pathWithin(right, left);
    }

    /** The first active root that blocks the candidate: one whose write location overlaps the candidate's, on the same host. A replaced parent never blocks its replacement. */
    static Conflict findRootConflict(ConvergenceRoot candidateRoot, Identity candidate, List<Identified> actives) {
        String parentRootId = candidateRoot.parentRootId();
        for (Identified active : actives) {
            if (Objects.equals(active.root().rootId(), parentRootId) || !WorkspaceIdentity.sameHost(candidate.host(), active.identity().host())) {
                continue;
            }
            for (Surface requested : candidate.surfaces()) {
                for (Surface existing : active.identity().surfaces()) {
                    // A kind that owns no files stands on a place of its own that no file lies in: it neither blocks nor is blocked.
                    if (WorkflowKind.isSentinel(requested.entry()) || WorkflowKind.isSentinel(existing.entry())) {
                        continue;
                    }
                    if (overlaps(requested.physical(), existing.physical())) {
                        return new Conflict(active.root(), requested, existing);
                    }
                }
            }
        }
        return null;
    }

    static Plan planRootInsertion(DocumentDigests digests, ConvergenceRoot root, List<Identified> actives, String hostId, IdentityResolver resolver) {
        Identity derived = resolver.resolve(root.frame().workspace().locator(), writeSurface(root), false);
        Identity identity = derived.withHost(hostId);
        String parentRootId = root.parentRootId();
        if (parentRootId != null) {
            Identified parent = null;
            for (Identified active : actives) {
                if (Objects.equals(active.root().rootId(), parentRootId)) {
                    parent = active;
                }
            }
            if (parent == null || !parent.root().state().isGated()) {
                throw new WorkflowFailure(WorkflowCode.INVALID_TRANSITION, msg("workflow.service.gatedConvergenceRootReplaced"),
                        details("parentRootId", parentRootId, "parentState", parent == null ? null : parent.root().state()));
            }
            if (!identity.workspacePhysical().equals(parent.identity().workspacePhysical())) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.convergence.replacementRootRemainBoundSame"), details("parentRootId", parentRootId));
            }
        }
        return new Plan(identity, surfaceDigest(digests, root), surfaceKey(digests, identity), findRootConflict(root, identity, actives));
    }

    /** ROOT_CONFLICT details: which root blocks, whether it is gated, and which entries collide. */
    static Map<String, Object> conflictDetails(Conflict conflict) {
        Map<String, Object> out = new LinkedHashMap<>();
        ConvergenceRoot root = conflict.root();
        out.put("rootId", root.rootId());
        out.put("workspaceId", root.frame().workspace().workspaceId());
        out.put("scope", root.taskEnvelope().scope().included());
        out.put("blockerState", root.state());
        out.put("rootCreatedAt", root.createdAt().toString());
        out.put("rootUpdatedAt", root.updatedAt().toString());
        out.put("rootRevision", root.revision());
        out.put("requestedEntry", conflict.requested().entry());
        out.put("existingEntry", conflict.existing().entry());
        out.put("existingPath", conflict.existing().physical().toString());
        List<String> expansion = new ArrayList<>();
        if (conflict.requested().conservative() != null) {
            expansion.add(conflict.requested().conservative());
        }
        if (conflict.existing().conservative() != null) {
            expansion.add(conflict.existing().conservative());
        }
        out.put("conservativeExpansion", expansion);
        return out;
    }

    /** The stored form of a root's identity: locations are the text of their {@link Path}, which is what {@code Path.of} reads back on this file system. */
    record IdentityDocument(int version, String workspacePhysical, List<SurfaceDocument> surfaces, @JsonInclude(JsonInclude.Include.NON_NULL) String host) {
    }

    record SurfaceDocument(String entry, String physical, String conservative) {
    }

    static String identityJson(JsonMapper mapper, Identity identity) {
        List<SurfaceDocument> surfaces = new ArrayList<>();
        for (Surface surface : identity.surfaces()) {
            surfaces.add(new SurfaceDocument(surface.entry(), surface.physical().toString(), surface.conservative()));
        }
        return mapper.writeValueAsString(new IdentityDocument(1, identity.workspacePhysical().toString(), surfaces, identity.host()));
    }

    static Stored storedIdentity(JsonMapper mapper, String json, String surfaceDigest) {
        IdentityDocument document = mapper.readValue(json, IdentityDocument.class);
        List<Surface> surfaces = new ArrayList<>();
        for (SurfaceDocument item : document.surfaces()) {
            surfaces.add(new Surface(item.entry(), Path.of(item.physical()), item.conservative()));
        }
        return new Stored(new Identity(document.host(), Path.of(document.workspacePhysical()), surfaces), surfaceDigest);
    }
}
