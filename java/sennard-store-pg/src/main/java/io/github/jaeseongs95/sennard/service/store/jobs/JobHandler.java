// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.jobs;

/** The work of one kind of background job. It runs on its own virtual thread with no transaction open. */
public interface JobHandler {
    String kind();

    /**
     * @param inputJson what was queued with the job
     * @return the result JSON to keep on the row, or null
     */
    String run(String inputJson);
}
