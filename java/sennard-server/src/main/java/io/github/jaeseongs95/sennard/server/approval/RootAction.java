// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

/**
 * What the user is asked to approve for a convergence root, written in the approval scope and shown on the admin page by the constant's name:
 * {@code REPLACE_ROOT} gives up a gated parent root and opens a new one, {@code RELEASE_ROOT} releases a root another session owns.
 */
public enum RootAction {
    REPLACE_ROOT,
    RELEASE_ROOT
}
