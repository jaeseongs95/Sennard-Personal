// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.LeaseState;

/**
 * An attempt lease ({@code attempt-lease.v1.schema.json}): the server's permission for one attempt on a convergence root, bound to the
 * proposal it was issued for. The store keeps it as JSON text and edits only its {@code state}; the service reads and writes it through
 * Jackson. {@code actorId} is written as {@code null} when the embedding supplied no verified caller, because the schema requires the key.
 */
public record AttemptLease(String schemaVersion, String leaseId, String rootId, long rootRevision, long epoch, long ordinal, String proposalDigest,
        String frameDigest, String workspaceDigest, String controlDigest, String targetDigest, String operationalDigest, String taskDigest,
        String outputTargetsDigest, String planIntegrityToken, @JsonInclude(JsonInclude.Include.ALWAYS) String actorId, List<String> priorEvidenceDigests,
        List<String> outputTargets, Instant issuedAt, Instant expiresAt, LeaseState state) {

    /** A lease stored without {@code priorEvidenceDigests} (the schema makes it optional) read as one that cites none. */
    public AttemptLease {
        priorEvidenceDigests = priorEvidenceDigests == null ? List.of() : priorEvidenceDigests;
    }

    /** The lease an attempt of its epoch has consumed. */
    boolean consumedIn(long attemptEpoch) {
        return epoch == attemptEpoch && state == LeaseState.CONSUMED;
    }
}
