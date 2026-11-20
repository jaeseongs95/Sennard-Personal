// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/** The verdict of a mandatory audit's gate, written by the constant's name. Only {@code PASS} lets the audited stage pass. */
enum GateVerdict {
    PASS,
    FAIL,
    BLOCKED
}
