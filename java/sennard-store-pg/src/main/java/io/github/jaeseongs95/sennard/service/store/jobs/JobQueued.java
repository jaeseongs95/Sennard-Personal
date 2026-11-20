// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.jobs;

import java.util.UUID;

/** A job row is in the table; delivered after the transaction that wrote it committed. */
public record JobQueued(UUID jobId, String kind, String inputJson) {
}
