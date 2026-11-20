// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * The two fields of a stored run that a reader who only needs to know which run it is and where it stands reads: the rest of the receipt (its plan and every
 * stage result) is skipped, not parsed into records.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RunHeader(String runId, RunState state) {
}
