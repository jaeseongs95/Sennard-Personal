// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decisions kept with the workflow rows. A decision is written once per scope digest and reused until revoked; no expiry or one-time use is added. */
@Service
public class StoreRootApprovalService implements RootApprovalService {
    private static final String DIGEST_PREFIX = "sha256:";

    /**
     * A waiting request as the workflow wrote it. The scope stays a tree: the decision copies it as it is, and the digest the workflow checks later is the canonical digest of
     * exactly those members.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingRecord(JsonNode scope, String currentScope, String replacementScope) {
    }

    /** The members of a scope this class shows on the page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopeFacts(RootAction action, String rootId, String actorId) {
    }

    /** The record of a decision, in the order of its stored text; {@code revokedAt} is null until it is revoked, and the key is there all the same. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Decision(String approvalId, JsonNode scope, String scopeDigest, DecisionSource source, String createdAt, String revokedAt) {
    }

    /** Where the decision was made. */
    public record DecisionSource(ApprovalSourceKind kind, String recordHash, String credentialId) {
    }

    private final WorkflowStore store;
    private final JsonMapper mapper;

    private final Clock clock;

    public StoreRootApprovalService(WorkflowStore store, JsonMapper mapper, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pending> pending() {
        return store.listPendingRootApprovals().stream().map(row -> {
            PendingRecord record = mapper.readValue(row.recordJson(), PendingRecord.class);
            ScopeFacts scope = mapper.treeToValue(record.scope(), ScopeFacts.class);
            return new Pending(approvalId(row.scopeDigest()), scope.action(), scope.rootId(), scope.actorId(), row.scopeDigest(), record.currentScope(), record.replacementScope(),
                    row.updatedAt());
        }).toList();
    }

    /** The page's identifier: the digest's hex, which the signed change's pattern accepts and which the same request asked again keeps. */
    private static String approvalId(String scopeDigest) {
        return scopeDigest.substring(DIGEST_PREFIX.length());
    }

    /**
     * The request is decided by the statement that takes it off the waiting list: the {@code DELETE} of its row changes one row for the decision that came first and none for one that
     * meets the row already gone (the database re-reads the row after the first decision's commit), so a request is approved or rejected once and no lock is taken.
     */
    @Override
    @Transactional
    public boolean approve(String approvalId, String scopeDigest, Source source) {
        return find(approvalId).filter(row -> row.scopeDigest().equals(scopeDigest)).map(waiting -> {
            if (!store.deletePendingRootApproval(waiting.scopeDigest())) {
                return false;
            }
            String now = clock.instant().toString();
            String decisionId = "approval-" + UUID.randomUUID();
            Decision decision = new Decision(decisionId, mapper.readValue(waiting.recordJson(), PendingRecord.class).scope(), scopeDigest,
                    new DecisionSource(source.kind(), source.recordHash(), source.credentialId()), now, null);
            store.insertRootApproval(new WorkflowStore.RootApproval(decisionId, scopeDigest, source.kind().name(), source.recordHash(), mapper.writeValueAsString(decision), now, null));
            return true;
        }).orElse(false);
    }

    @Override
    @Transactional
    public boolean reject(String approvalId) {
        return find(approvalId).map(row -> store.deletePendingRootApproval(row.scopeDigest())).orElse(false);
    }

    private Optional<WorkflowStore.PendingApproval> find(String approvalId) {
        return store.listPendingRootApprovals().stream().filter(row -> approvalId(row.scopeDigest()).equals(approvalId)).findFirst();
    }
}
