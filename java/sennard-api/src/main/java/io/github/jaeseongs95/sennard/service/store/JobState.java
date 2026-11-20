// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/** The state of a background job ({@code background_job.state}): {@code QUEUED -> RUNNING -> SUCCEEDED | FAILED}. */
public enum JobState {
    QUEUED(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true);

    private final boolean finished;

    JobState(boolean finished) {
        this.finished = finished;
    }

    public boolean isFinished() {
        return finished;
    }
}
