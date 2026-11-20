// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

/** Where a root approval was decided, written in the approval record by the constant's name. Only the signed admin page can decide one; a model-supplied text never counts. */
public enum ApprovalSourceKind {
    ADMIN_PAGE
}
