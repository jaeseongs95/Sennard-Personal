// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.List;

/**
 * What a use case will judge from files, as its read-only planning method found it in the rows: the requests the entry answers before the use case's transaction opens.
 *
 * @param revision the revision of the run the requests were made for, or -1 for a use case that has no run; the use case refuses answers made for another revision
 */
record ReadPlan(long revision, List<ReadRequest> requests) {
    /** The plan of a use case that has nothing to read. */
    static final ReadPlan NONE = new ReadPlan(-1, List.of());
}
