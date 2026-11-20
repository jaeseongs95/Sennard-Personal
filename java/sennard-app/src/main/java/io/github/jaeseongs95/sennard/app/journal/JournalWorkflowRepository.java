// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface JournalWorkflowRepository extends Repository<JournalWorkflow, String> {
    Optional<JournalWorkflow> findById(String id);
    /** The first row of a workflow's journal: an {@code INSERT} (the version is null). A state change is {@link #advance}, never {@code save}. */
    JournalWorkflow save(JournalWorkflow row);

    /**
     * Moves the journal to {@code nextState} and its version on, while it is still in {@code expectedState} at the version the caller read: one statement, and its changed rows say who
     * won (1: the caller's write goes on, 0: the journal changed after the caller read it, so the caller lost). The version is the row's own number and the {@code WHERE} names it, so
     * no {@code @Version} save is needed to tell a stale read; two writers that read the same version wait on the row and only the first one's {@code WHERE} still holds after its commit.
     */
    @Modifying
    @Query("UPDATE sennard.journal_workflow SET baseline_state = :nextState, version = version + 1 "
            + "WHERE workflow_id = :workflowId AND baseline_state = :expectedState AND version = :version")
    int advance(@Param("workflowId") String workflowId, @Param("expectedState") String expectedState, @Param("nextState") String nextState, @Param("version") long version);
}
