// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * Where a task's restorable memory comes from: {@code DIRECT} is the agent's own {@code checkpoint_context} record, {@code WORKFLOW} is the
 * card the server builds from the convergence root the task is bound to. The column {@code continuity_tasks.pending_source} holds the
 * constant's name, and the restore summaries and tokens spell the same names.
 */
public enum ContextSource {
    DIRECT,
    WORKFLOW
}
