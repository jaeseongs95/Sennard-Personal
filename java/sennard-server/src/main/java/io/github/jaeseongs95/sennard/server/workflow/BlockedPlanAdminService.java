// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanKey;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanOrigin;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanView;

/** The administrator's plan denials. The signed admin commit is the only mutation caller. */
@Service
public class BlockedPlanAdminService {
    private final WorkflowStore store;

    public BlockedPlanAdminService(WorkflowStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<BlockedPlanView> list() {
        return store.listBlockedPlans();
    }

    @Transactional(readOnly = true)
    public void requireCurrent(String workspaceId, String kind, String planDigest, String recordId) {
        BlockedPlanOrigin current = store.blockedPlan(new BlockedPlanKey(workspaceId, kind, planDigest));
        if (current == null || !recordId.equals(current.recordId())) {
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unblock(String workspaceId, String kind, String planDigest, String recordId) {
        store.requireBlockedPlanGuardForUpdate();
        if (!store.unblockPlan(new BlockedPlanKey(workspaceId, kind, planDigest), recordId)) {
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE);
        }
    }
}
