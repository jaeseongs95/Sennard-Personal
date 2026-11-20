// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import tools.jackson.databind.JsonNode;

/**
 * What the receipt of a change plan keeps (AGS3-D-0267): the revision the plan was written as, and, once the call that wrote it has finished, the answer that call gave the caller.
 * The plan is written, with its receipt, in one transaction of the journal; the answer of {@code submit_change_plan} is made after it (the file reads and the baseline capture stay
 * outside every transaction), so the receipt first holds the revision alone and the call completes it with its answer ({@code OperationReceipts#complete}). A request that comes
 * again is answered with {@code answer} as it was given; with no answer yet (the first call stopped before it finished) it resumes from the revision.
 *
 * @param planRevision the revision number of the plan
 * @param answer the answer the first call gave; null until that call has finished (read back from the stored JSON it is a null node, which {@link #answered()} also counts as none)
 */
public record PlanReceipt(long planRevision, JsonNode answer) {
    /** Whether the call that wrote the plan has finished and left its answer. */
    public boolean answered() {
        return answer != null && !answer.isNull() && !answer.isMissingNode();
    }
}
