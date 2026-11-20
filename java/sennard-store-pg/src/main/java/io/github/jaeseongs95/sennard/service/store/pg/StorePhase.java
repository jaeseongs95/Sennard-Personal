// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

/**
 * How far the server has come in reaching its business database: NOT_CONFIGURED (no connection setting yet), CONNECTING, MIGRATING, READY, or FAILED with the reason
 * after three failed attempts in a row. Each step is idempotent, so an attempt after a failure resumes instead of starting over.
 */
public enum StorePhase {
    NOT_CONFIGURED, CONNECTING, MIGRATING, READY, FAILED
}
